/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.agentscopea2a.v2.dimension;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 维度状态管理器，负责多轮对话中维度上下文的继承、指代消解、组装和提取。
 *
 * <p>v2 版本：通过 {@link RuntimeContext} + {@link io.agentscope.core.state.AgentState} 进行持久化，
 * 替代 v1 的 {@code Session}/{@code SessionKey}。
 *
 * <p>使用方式：
 *
 * <pre>{@code
 * DimensionStateManager manager = new DimensionStateManager(llmService);
 *
 * // 处理用户问题（继承维度 + 组装）
 * String fullQuestion = manager.processQuestion(userQuestion);
 *
 * // 调用 Agent ...
 *
 * // 回答后更新维度状态
 * manager.updateFromAnswer(answer);
 *
 * // 持久化到 RuntimeContext
 * manager.saveTo(runtimeContext);
 * }</pre>
 */
public class DimensionStateManager {

    private static final Logger log = LoggerFactory.getLogger(DimensionStateManager.class);
    private static final String STATE_KEY = "dimensionState";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final LlmDimensionService llmService;
    /** 同义词表解析器，null 时退化为纯正则（原行为） */
    private final AliasResolver aliasResolver;
    /**
     * 指标词表（tool_metric_catalog.metricTags 快照），§13.4 组名候选左侧修剪用；
     * null 或取词失败时只修剪虚词。
     */
    private final Supplier<Set<String>> metricWordsSupplier;
    /**
     * 维度键字典启用行快照（口语值方案 §3.2）：versionPlan/vestQuarter/deptName 三个
     * 白名单内置键的词表 alias 合并进提取结果；null/读失败退化为纯正则。
     */
    private final Supplier<List<DimensionKeyConfig>> dimensionKeySupplier;
    private DimensionState currentState;

    /**
     * 反问确认轮的一次性回填：consumeClarification 命中后记录（问题文本 → 确认的 peer 维度）。
     * 确认后的标准名可能不在产品线/小组正则的固定词表里（如"金融产品定价与估值系统"），
     * 本轮 processQuestionInContext 按问题文本精确匹配后回填 peer 与映射显示，用后即清。
     */
    private volatile String clarifiedPeerQuestion;
    private volatile DimensionState.PeerDimension clarifiedPeer;

    /**
     * 创建维度状态管理器。
     *
     * @param llmService LLM 维度分析服务，可为 null（仅 {@link #updateFromAnswer} 需要）
     */
    public DimensionStateManager(LlmDimensionService llmService) {
        this(llmService, null, null, null);
    }

    /**
     * @param aliasResolver 同义词表解析器，可为 null（null 时口语化词识别关闭，仅正则兜底）
     */
    public DimensionStateManager(LlmDimensionService llmService, AliasResolver aliasResolver) {
        this(llmService, aliasResolver, null, null);
    }

    /**
     * @param metricWordsSupplier 指标词表供给器，可为 null（组名修剪只去虚词）
     */
    public DimensionStateManager(
            LlmDimensionService llmService,
            AliasResolver aliasResolver,
            Supplier<Set<String>> metricWordsSupplier) {
        this(llmService, aliasResolver, metricWordsSupplier, null);
    }

    /**
     * @param dimensionKeySupplier 维度键字典启用行供给器，可为 null（内置键词表合并关闭，仅正则）
     */
    public DimensionStateManager(
            LlmDimensionService llmService,
            AliasResolver aliasResolver,
            Supplier<Set<String>> metricWordsSupplier,
            Supplier<List<DimensionKeyConfig>> dimensionKeySupplier) {
        this.llmService = llmService;
        this.aliasResolver = aliasResolver;
        this.metricWordsSupplier = metricWordsSupplier;
        this.dimensionKeySupplier = dimensionKeySupplier;
    }

    /**
     * alias 词表标准名导出（维度值台账真值校验方案 §4.2）：指定 peer 维度类型的全部
     * standardName，并入 B①/B③ 真值域（业务显式配置过的标准值视为合法）。
     * aliasResolver 缺失 → 空集。
     */
    public Set<String> aliasStandardNames(DimensionState.PeerDimensionType type) {
        return aliasResolver == null ? Set.of() : aliasResolver.standardNames(type);
    }

    // ==================== v2 状态持久化 ====================

    /**
     * 将维度状态保存到 RuntimeContext（用于 v2 AgentState 持久化）。
     */
    public void saveTo(RuntimeContext ctx) {
        if (currentState != null) {
            ctx.put(STATE_KEY, DimensionState.class, currentState);
        }
    }

    /**
     * 从 RuntimeContext 加载维度状态。
     */
    public void loadFrom(RuntimeContext ctx) {
        DimensionState loaded = ctx.get(STATE_KEY, DimensionState.class);
        if (loaded != null) {
            this.currentState = loaded;
        }
    }

    /**
     * 在 per-request 上下文里处理用户问题（P1-2 多用户串扰修复）。
     *
     * <p>从 {@link RuntimeContext} 加载上一轮状态，处理问题（用局部变量，不碰实例字段
     * {@code currentState}），把新状态写回 ctx，返回组装后的问题和新状态。
     *
     * <p>这是替代 {@code loadFrom -> processQuestion -> getCurrentState -> saveTo} 序列的
     * 多用户安全 API。单例 bean 的 {@code currentState} 实例字段会被并发请求覆盖（详见
     * optimization-analysis.md P1-2），此方法用局部变量规避该问题。
     *
     * @param ctx          per-request 上下文
     * @param userQuestion 用户原始问题
     * @return 组装后的问题和新状态
     */
    public ProcessResult processQuestionInContext(RuntimeContext ctx, String userQuestion) {
        DimensionState loaded = ctx != null ? ctx.get(STATE_KEY, DimensionState.class) : null;
        QuestionAnalysis analysis = analyzeQuestionRuleBased(userQuestion);
        DimensionState inherited = inheritDimensions(loaded, analysis);
        String resolvedQuestion = resolveReference(inherited, analysis, userQuestion);
        String enriched = assembleQuestion(inherited, resolvedQuestion);
        List<AliasResolver.ResolvedAlias> resolvedAliases =
                new ArrayList<>(analysis.getAliasResolution().resolved());
        // 反问确认轮回填（用后即清）：确认出的标准名往往不被正则词表识别，上面的
        // 解析/继承链会把 peer 丢掉；这里按问题文本精确匹配把确认结果补回状态。
        // 只写维度+标准名，不进「同义词解析映射」段——选词是用户自己确认的，无需口语对应
        if (clarifiedPeerQuestion != null && clarifiedPeerQuestion.equals(userQuestion)
                && clarifiedPeer != null && inherited != null) {
            inherited.setPeerDimension(clarifiedPeer);
            log.info("Clarified peer re-applied to context: dimension={} values={}",
                    clarifiedPeer.getType(), clarifiedPeer.getValues());
            clarifiedPeerQuestion = null;
            clarifiedPeer = null;
        }
        if (inherited != null && ctx != null) {
            ctx.put(STATE_KEY, DimensionState.class, inherited);
        }
        return new ProcessResult(enriched, inherited, resolvedAliases);
    }

    /** processQuestionInContext 的返回值。resolvedAliases 是本轮同义词表解析出的口语词→标准名映射。 */
    public record ProcessResult(
            String enrichedQuestion,
            DimensionState newState,
            List<AliasResolver.ResolvedAlias> resolvedAliases) {}

    /**
     * 只读预览: 规则分析 + 从给定上一轮状态继承维度, 不写 ctx 也不碰单例 currentState。
     * 供 Skill 直接执行的 plan() 判定做参数预填; 是否正式写状态由调用方决定
     * (决定短路后才调 processQuestionInContext, 避免回退 LLM 时状态被解析两次)。
     */
    public DimensionState previewState(String userQuestion, DimensionState previousState) {
        QuestionAnalysis analysis = analyzeQuestionRuleBased(userQuestion);
        return inheritDimensions(previousState, analysis);
    }

    // ==================== 核心流程 ====================

    /**
     * 处理用户问题：规则分析 → 继承 → 指代消解 → 组装
     *
     * <p>使用纯正则规则分析问题，零 LLM 开销。维度具体值由 {@link #updateFromAnswer(String)} 的 LLM
     * 从回答中提取并修正。
     *
     * @param userQuestion 用户原始问题
     * @return 组装了维度上下文的完整问题
     */
    public String processQuestion(String userQuestion) {
        // 1. Rule-based question analysis (zero LLM overhead)
        QuestionAnalysis analysis = analyzeQuestionRuleBased(userQuestion);

        // 2. inherit dimensions based on detected level
        DimensionState inherited = inheritDimensions(currentState, analysis);

        // 3. update current state
        this.currentState = inherited;

        // 4. reference resolution ("那个组" → "渠道组")
        String resolvedQuestion = resolveReference(inherited, analysis, userQuestion);

        // 5. assemble full question
        return assembleQuestion(inherited, resolvedQuestion);
    }

    /**
     * 异步处理用户问题（内部同步执行，因为规则分析不涉及 IO）。
     *
     * @param userQuestion 用户原始问题
     * @return 组装了维度上下文的完整问题的 Mono
     */
    public Mono<String> processQuestionAsync(String userQuestion) {
        return Mono.fromCallable(() -> processQuestion(userQuestion));
    }

    /**
     * 回答后更新维度状态。
     *
     * @param answer LLM 的回答
     */
    public void updateFromAnswer(String answer) {
        DimensionState extracted = extractDimensions(answer);
        this.currentState = mergeExtracted(this.currentState, extracted);
    }

    /**
     * 异步更新维度状态。
     *
     * @param answer LLM 的回答
     * @return 完成信号的 Mono
     */
    public Mono<Void> updateFromAnswerAsync(String answer) {
        return extractDimensionsAsync(answer)
                .doOnNext(
                        extracted -> {
                            this.currentState = mergeExtracted(this.currentState, extracted);
                        })
                .then();
    }

    /**
     * 获取当前维度状态（只读）。
     */
    public DimensionState getCurrentState() {
        return currentState;
    }

    /**
     * 重置维度状态（清空所有维度）。
     */
    public void reset() {
        this.currentState = null;
    }

    // ==================== 步骤①：规则驱动的问题分析 ====================

    private static final Pattern REF_TIME = Pattern.compile("这个季度|那个季度|这个月|那个月|这个版本|那个版本");
    private static final Pattern REF_DEPT = Pattern.compile("这个部门|那个部门");
    private static final Pattern REF_TEAM = Pattern.compile("这个组|那个组");
    private static final Pattern REF_APP = Pattern.compile("这个应用|那个应用");
    private static final Pattern REF_PRODUCT = Pattern.compile("这个产品线|那个产品线");
    private static final Pattern REF_REQUIREMENT = Pattern.compile("这个需求项|那个需求项");
    private static final Pattern REF_SUB_REQUIREMENT = Pattern.compile("这个需求子条目|那个需求子条目");

    // 年份前缀可选：无年份时由调用方补当前年（"4月份版本" → "2026年4月份版本"）
    // 口语值方案 §3.2：后缀放宽到 月份?版本?（吃 "10月版本"/"10月份版"/"2026年10月份"），
    // 与 EXPLICIT_MONTH 归一产物相同（Set 去重，指纹不变）；(?!延后) 防把"10月延后"误吃成普通月份版本
    private static final Pattern EXPLICIT_VERSION =
            Pattern.compile("(?:(\\d{4})年)?(\\d{1,2})月份?(?!延后)版本?");
    // 共享后缀列举 ("10、11月版" / "10、11月" / "2026年10和11月份版本"): 末段带月份后缀,
    // 前段裸数字列举, EXPLICIT_VERSION/EXPLICIT_MONTH 只吃得到末段 → 须在本正则先行展开,
    // 每个数字归一 "{y}年{n}月份版本"; 版后缀可选 (与 TIME_MASK_SPAN 口径一致, 无版也算),
    // (?!延后) 防把 "10、11月延后" 误吃成普通月份版本
    private static final Pattern SHARED_SUFFIX_VERSION =
            Pattern.compile("(?:(\\d{4})年)?(\\d{1,2})((?:[、，,和与]\\s*\\d{1,2})+)\\s*月份?(?!延后)(?:版本?)?");
    private static final Pattern EXPLICIT_QUARTER = Pattern.compile("(?:(\\d{4})年)?(\\d{1,2})季度");
    // 共享后缀季度列举 ("3、4季度" / "2025年3和4季度"): 与 SHARED_SUFFIX_VERSION 同构,
    // 须在 EXPLICIT_QUARTER 之前展开, 否则前段裸数字漏收
    private static final Pattern SHARED_SUFFIX_QUARTER =
            Pattern.compile("(?:(\\d{4})年)?(\\d{1,2})((?:[、，,和与]\\s*\\d{1,2})+)\\s*季度");
    // "9月份"（无"版本"后缀）也算版本计划；(?!版本|延后) 防止与 EXPLICIT_VERSION/DELAYED 重复计数值，
    // "9月份版本" 会由 MONTH 退回匹配 "9月" 归一出同一值，Set 去重后指纹一致；
    // "10月延后" 是延后版标准值的前缀，不得按普通月份版本归一
    private static final Pattern EXPLICIT_MONTH = Pattern.compile("(?:(\\d{4})年)?(\\d{1,2})月份?(?!版本|延后)");
    // 口语值方案 §3.2：紧凑 YYYYMM（"202610版本"/"202610"）；词边界防误伤数字串/应用编码（F-202610）
    private static final Pattern COMPACT_VERSION =
            Pattern.compile("(?<![0-9A-Za-z-])(20\\d{2})(0[1-9]|1[0-2])(?![0-9])");
    // 口语值方案 §3.2：中文数字月份（"十月版"/"十一月份"/"2026年三月"）
    private static final Pattern CN_MONTH_VERSION =
            Pattern.compile("(?:(\\d{4})年)?([一二三四五六七八九十]{1,3})月份?(?:版本?)?");
    // 口语值方案 §3.2：延后版标准值（"10月延后"/"2026年10月延后版"），与月份版本同级的版本计划
    private static final Pattern DELAYED_VERSION =
            Pattern.compile("(?:(\\d{4})年)?(\\d{1,2})月(份)?延后(版本?)?");
    // 裸日期 "10月26日"（M月与D日紧接）：截止日/事件日，不是版本——EXPLICIT_VERSION/
    // EXPLICIT_MONTH 会从中吃出 "10月"（2026-10-27 E2E："杭州三部11月版、12月版10月26日
    // 到期" 多出 2026年10月份版本）。仅作屏蔽 span，不产出时间维度（到期日≠查询时间范围）
    private static final Pattern BARE_DATE_SPAN =
            Pattern.compile("(?:(\\d{4})年)?(\\d{1,2})月(\\d{1,2})[日号]");
    // 口语值方案 §3.1：近 N 个版本/近 N 个月 → LAST:{n}V 锚点（数字形态走正则不进词表；
    // 拍板③ 按版本个数推，"近X个月"也归版本键，周/天后缀归 timeRange）
    private static final Pattern LAST_N_VERSIONS =
            Pattern.compile("(?:近|最近)(\\d{1,3})个(?:月个)?版本|(?:近|最近)(\\d{1,3})个月");
    // 一季度~四季度 alias — normalised to "{year}年{n}季度" for fingerprint stability.
    // 裸 Q1-Q4 不算季度口语值 (2026-10-22): QI卡口 指标值就叫 Q1/Q2/Q3/Q4, 撞词后由词表按卡口解析
    private static final Pattern EXPLICIT_QUARTER_ALIAS =
            Pattern.compile("([一二三四])季度");
    // KNOWLEDGE.md §部门 标准枚举 — 漏掉非"杭州开发X部"的会让用户连问 N 次也触发不了同指纹。
    // 别名按"长前短后"排列，避免"杭州服务支持部"被"服务支持"截胡；命中后经 normalizeDepartment 映射回标准名
    // 口语值方案 §3.2：补 杭[一二三四五]部?（"杭一"/"杭二部"极简形，放 [一二三四五]部 之前保整段占用）
    private static final Pattern EXPLICIT_DEPT =
            Pattern.compile(
                    "(杭州开发[一二三四五]部|杭州服务支持部|杭州技术部|杭州产品赋能团队|云计算实验室"
                            + "|产品赋能团队|赋能团队"
                            + "|杭州[一二三四五]部|杭[一二三四五]部?|[一二三四五]部"
                            + "|服务支持部|服务支持|技术部|云计算|实验室)");

    /** 部门简称 → KNOWLEDGE.md 标准名，保证与全称写法生成同一指纹 */
    private static String normalizeDepartment(String matched) {
        return switch (matched) {
            case "杭州一部", "一部", "杭一部", "杭一" -> "杭州开发一部";
            case "杭州二部", "二部", "杭二部", "杭二" -> "杭州开发二部";
            case "杭州三部", "三部", "杭三部", "杭三" -> "杭州开发三部";
            case "杭州四部", "四部", "杭四部", "杭四" -> "杭州开发四部";
            case "杭州五部", "五部", "杭五部", "杭五" -> "杭州开发五部";
            case "技术部" -> "杭州技术部";
            case "服务支持部", "服务支持" -> "杭州服务支持部";
            case "云计算", "实验室" -> "云计算实验室";
            case "产品赋能团队", "赋能团队" -> "杭州产品赋能团队";
            default -> matched;
        };
    }
    // 应用 F-XXX / FS-XXX / 群组 F-XXX-XXX — 同一正则吃下,后者只是更长。
    // 应用码纯英文+连字符 (F-GCMS / F-ATP-INNER / FS-GBCP-EPL), 不含数字: 否则 "F-FPA4季度"
    // 会贪婪吃进 "4" 把应用名错切成 F-FPA4
    private static final Pattern EXPLICIT_APP = Pattern.compile("(FS?-[A-Za-z][A-Za-z-]*)");
    // 裸应用码 (省略 F-/FS- 前缀): 首段三位及以上连续字母, 可后接 -两位以上字母段
    // ("BASE"→F-BASE, "BCP-EPL"→F-BCP-EPL, "gcmcs"→F-GCMCS, 大小写归一为大写)。
    // 技术词 (sql/csv/api, 大小写都算) 由 BARE_APP_STOP 拦截, 见 bareAppCodes。
    // 前后环视排除字母/连字符邻接, 防止 F-ATP-INNER 的 "ATP"/"INNER" 内段被二次加前缀;
    // 语义让路见 bareAppCodes
    private static final Pattern BARE_APP =
            Pattern.compile("(?<![A-Za-z0-9_-])[A-Za-z]{3,}(?:-[A-Za-z]{2,})*(?![A-Za-z_-])");
    // 技术词表 (裸码拦截, 命中任一连字符段即跳过): 中文问句里游离的英文多为技术词,
    // 不补 F- 前缀; 新词按需追加
    private static final Set<String> BARE_APP_STOP = Set.of(
            "SQL", "CSV", "API", "HTTP", "HTTPS", "JSON", "HTML", "URL", "XML", "PDF", "PPT", "EXCEL");

    /**
     * 裸应用码提取 (用户省略 F-/FS- 前缀): 屏蔽 F- 编码 / 时间 / 部门 / 需求项 / 组 /
     * 产品线 / 别名命中与歧义 span 后, 剩余文本中的连续字母段 (含连字符串, 大小写都收)
     * 归一大写补 F- 返回; 命中技术词表 (任一段) 跳过。
     * 让路理由: "GBC" 是组/产品线别名、"FMBM" 内嵌在 "FMBM应用平台组" 组名里,
     * 都不是应用码; "FPA4季度" 的 FPA 无任何归属 → 应用 F-FPA。
     */
    private static List<String> bareAppCodes(String q, AliasResolver.AliasResolution aliasRes) {
        List<int[]> spans = new ArrayList<>();
        for (Pattern p : List.of(TIME_MASK_SPAN, EXPLICIT_DEPT, EXPLICIT_APP,
                EXPLICIT_REQUIREMENT, EXPLICIT_SUB_REQUIREMENT, EXPLICIT_TEAM,
                EXPLICIT_PRODUCT_LINE)) {
            collectSpans(q, p, spans);
        }
        if (aliasRes != null) {
            for (AliasResolver.ResolvedAlias r : aliasRes.resolved()) {
                spans.add(new int[] {r.start(), r.end()});
            }
            for (AliasResolver.AmbiguousAlias a : aliasRes.ambiguous()) {
                spans.add(new int[] {a.start(), a.end()});
            }
        }
        Matcher m = BARE_APP.matcher(maskSpanUnion(q, spans));
        List<String> codes = new ArrayList<>();
        while (m.find()) {
            String code = m.group().toUpperCase(Locale.ROOT);
            boolean stop = false;
            for (String seg : code.split("-")) {
                if (BARE_APP_STOP.contains(seg)) {
                    stop = true;
                    break;
                }
            }
            if (!stop) {
                codes.add("F-" + code);
            }
        }
        return codes;
    }

    /** 取 span 并集屏蔽 (重叠合并): 与 {@link #maskSpanList} 的先到先得不同, 排除用途
     *  要 maximal 屏蔽——TEAM 贪婪 span ("杭州二部FMBM应用平台组") 与 DEPT span 首位重叠,
     *  先到先得会把整段 TEAM 跳掉漏掉内嵌的 "FMBM"。 */
    private static String maskSpanUnion(String q, List<int[]> spans) {
        if (spans.isEmpty()) {
            return q;
        }
        spans.sort(Comparator.comparingInt(s -> s[0]));
        char[] chars = q.toCharArray();
        int lastEnd = -1;
        for (int[] s : spans) {
            int from = Math.max(s[0], lastEnd);
            if (from >= s[1]) {
                continue;
            }
            for (int i = from; i < s[1]; i++) {
                chars[i] = MASK_CHAR;
            }
            lastEnd = Math.max(lastEnd, s[1]);
        }
        return new String(chars);
    }
    // 小组:KNOWLEDGE.md 举例都 5+ 字(如"金融市场自营测试组"),放宽到 {2,}
    private static final Pattern EXPLICIT_TEAM = Pattern.compile("([一-龥A-Za-z0-9]{2,}组)");
    // 产品线:同上放宽,并补常见非"...产品线"后缀的固定名(普惠金融 / 代理国库等)
    private static final Pattern EXPLICIT_PRODUCT_LINE =
            Pattern.compile(
                    "([一-龥]{2,}产品线|普惠金融|代理国库|代理财政|代理同业|交行输出"
                            + "|全球市场风险管理应用|金融市场共享数据服务)");
    // 需求项:之前抽的是"XX需求项"前 2 字,真正的 itemNo 格式如 I20260208-0005 反而没匹配
    private static final Pattern EXPLICIT_REQUIREMENT = Pattern.compile("(I\\d{8}-\\d{4})");
    // 需求子条目编号: S+8位日期数字+-+6位数字, 如 S20250926-000741
    private static final Pattern EXPLICIT_SUB_REQUIREMENT = Pattern.compile("(S\\d{8}-\\d{6})");

    // ---- §5.1 粒度词守卫（docs/202610/人员台账维度方案.md）----
    // drillDown 动态键词表的粒度词（各组/各个组/每个组/各产品线…）会被
    // EXPLICIT_TEAM/EXPLICIT_PRODUCT_LINE 抢成垃圾 peer 值并经 param_mapping 传给脚本
    // （内网 2026-10-22 实证："杭州三部10月版各组" → stat_group 查空集）。
    // 守卫只收 各/每/按：分 开头有真实组名（分行业务组）不能拒，而 分组/分产品线
    // 裸词仅 1 字前缀、本来就构不成正则命中（拍板点⑧：无以各/每/按开头的真实组名）。
    private static final String GRANULARITY_LEADING = "各每按";

    /** 数量粒度短语（维度值台账真值校验方案 §3）：「两个组/3个组/几个组」——
     *  alias 命中嵌在以此结尾的 token 内时不是组名嵌入，抑制守卫豁免。 */
    private static final Pattern GRANULARITY_QUANTIFIER_SUFFIX =
            Pattern.compile("[一二两三四五六七八九十\\d]+个?组");

    private static boolean isGranularityToken(String token) {
        return token != null && !token.isEmpty()
                && GRANULARITY_LEADING.indexOf(token.charAt(0)) >= 0;
    }

    /**
     * 粒度尾缀守卫（§5.1 第二层，2026-10-22 E2E 补）：候选紧贴后缀（组/产品线）的字
     * 是粒度前导字即垃圾拼接——"我部10月版各组"/"我部各产品线"："我部"不被
     * EXPLICIT_DEPT 屏蔽，时间屏蔽后原文回退整段吞入，首字守卫（各/每/按开头）拦不住。
     * 真组名尾字非粒度前导（分行业务组/个人住房组/10月三组/数据智能组）不受影响。
     */
    private static boolean hasGranularityTail(String token, int suffixLen) {
        if (token == null || token.length() < suffixLen + 1) {
            return false;
        }
        // 回看 2 字覆盖 "各组"/"各个组"/"每个产品线"（"个"字夹在中间）
        for (int i = 1; i <= 2; i++) {
            int idx = token.length() - suffixLen - i;
            if (idx < 0) {
                break;
            }
            if (GRANULARITY_LEADING.indexOf(token.charAt(idx)) >= 0) {
                return true;
            }
        }
        return false;
    }

    /** 依次找首个非粒度词候选（粒度词候选跳过继续找，如"各个组、数据智能组"取后者）。 */
    private static boolean hasNonGranularityMatch(String text, Pattern p, int suffixLen, Set<String> stems,
                                                  List<Pattern> blocks) {
        Matcher m = p.matcher(text);
        while (m.find()) {
            String token = m.group(1);
            if (token == null || isGranularityToken(token) || hasGranularityTail(token, suffixLen)
                    || containsAnyStem(token, stems) || matchesAnyBlock(token, blocks)) {
                continue;
            }
            return true;
        }
        return false;
    }

    // ---- §5.1 第五层（词表反哺）：drillDown 动态键词表自动成为垃圾屏蔽词源 ----
    // "具体到各统计组"型粒度字藏在词中间，首字/尾缀守卫靠位置枚举永远追不出变体。
    // drillDown 词表本就由业务维护粒度说法，alias 原词自动反哺为对应内置键候选值的
    // contains 屏蔽词：补一条 "统计组→组维度" 即同时生效 ①contains-scan 提取
    // drill_down=组维度 ②拒绝所有含"统计组"的组名垃圾值——一处配置双向生效，
    // 覆盖不全由配置自愈，不用发版。

    /** drillDown 词表 alias value → 被垃圾屏蔽的内置键。个人维度不映射（人名走台账
     *  字典真名匹配，粒度词进不了字典）；杭研维度不映射（deptName 正则只认杭州开发X部）。 */
    private static String drillValueToBuiltinKey(String value) {
        if (value == null) {
            return null;
        }
        return switch (value) {
            case "组维度" -> "groupName";
            case "应用维度" -> "appName";
            case "产品线维度" -> "productLine";
            default -> null;
        };
    }

    /** 收集 drillDown 词表中映射到 targetKey 的粒度词词干（alias 原词，contains 判定）。
     *  字典供给器缺失/读失败 → 空集（位置守卫仍在）。 */
    private Set<String> granularityStems(String targetKey) {
        if (dimensionKeySupplier == null) {
            return Set.of();
        }
        List<DimensionKeyConfig> rows;
        try {
            rows = dimensionKeySupplier.get();
        } catch (RuntimeException e) {
            log.warn("DimensionStateManager: 维度键字典读取失败, 词表反哺垃圾屏蔽跳过: {}", e.getMessage());
            return Set.of();
        }
        if (rows == null) {
            return Set.of();
        }
        Set<String> stems = new LinkedHashSet<>();
        for (DimensionKeyConfig row : rows) {
            if (row.builtin() || !"drillDown".equals(row.key()) || row.aliases().isEmpty()) {
                continue;
            }
            for (DimensionKeyConfig.AliasMapping alias : row.aliases()) {
                if (alias.valid() && alias.isSynonym()
                        && targetKey.equals(drillValueToBuiltinKey(alias.value()))) {
                    stems.add(alias.alias());
                }
            }
        }
        return stems;
    }

    private static boolean containsAnyStem(String token, Set<String> stems) {
        if (token == null || stems.isEmpty()) {
            return false;
        }
        for (String stem : stems) {
            if (!stem.isEmpty() && token.contains(stem)) {
                return true;
            }
        }
        return false;
    }

    // ---- §5.1 第六层（block 屏蔽，我X方案 §9）：目标键行 block 型 regex 条目 ----
    // 候选 token 命中该键 block 正则即拒收——"我的组/我的产品线"等代词短语被
    // EXPLICIT_TEAM/EXPLICIT_PRODUCT_LINE 抢成垃圾维度值（2026-10-26 E2E 实证
    // groupName=["我的组","移动展业组"] 双值传参），粒度词守卫的 各/每/按 前导字拦不住。
    // 配置驱动（基线 seed=裸 `我` 单字），字典缺失/读失败 → 空列表 fail-open（同词表反哺）。

    /** 目标键行的 block 正则快照（findAllEnabled TTL 快照直读，同 granularityStems 无二级缓存）。 */
    private List<Pattern> blockPatterns(String targetKey) {
        if (dimensionKeySupplier == null) {
            return List.of();
        }
        List<DimensionKeyConfig> rows;
        try {
            rows = dimensionKeySupplier.get();
        } catch (RuntimeException e) {
            log.warn("DimensionStateManager: 维度键字典读取失败, block 屏蔽跳过: {}", e.getMessage());
            return List.of();
        }
        if (rows == null) {
            return List.of();
        }
        List<Pattern> out = new ArrayList<>();
        for (DimensionKeyConfig row : rows) {
            if (!targetKey.equals(row.key()) || row.aliases().isEmpty()) {
                continue;
            }
            for (DimensionKeyConfig.AliasMapping alias : row.aliases()) {
                if (!alias.valid() || !alias.isBlock()) {
                    continue;
                }
                try {
                    out.add(Pattern.compile(alias.alias()));
                } catch (Exception e) {
                    log.warn("DimensionStateManager: 非法 block 条目跳过 key={} alias={}: {}",
                            row.key(), alias.alias(), e.getMessage());
                }
            }
        }
        return out;
    }

    private static boolean matchesAnyBlock(String token, List<Pattern> blocks) {
        if (token == null || blocks.isEmpty()) {
            return false;
        }
        for (Pattern p : blocks) {
            if (p.matcher(token).find()) {
                return true;
            }
        }
        return false;
    }

    // ---- §13.4 标准格式感知的分段抽取（docs/history/dimension-alias-config-plan.md）----

    /** 屏蔽占位符：不在小组/产品线正则字符类 [一-龥A-Za-z0-9] 内，等长替换保偏移 */
    private static final char MASK_CHAR = '';

    /**
     * 第一遍占位用的时间宽形态（比归一化用的 EXPLICIT_VERSION/QUARTER 宽）：
     * "26年10月版" / "10月份版本" / "3季度" 都算时间 span，仅屏蔽不产出
     * （归一化仍由既有正则负责，避免指纹规则变化）。裸 "Q1" 不算时间 span，留给词表按卡口解析。
     */
    private static final Pattern TIME_MASK_SPAN = Pattern.compile(
            "(?:(?:\\d{4}|\\d{2})年)?\\d{1,2}(?:[、，,和与]\\s*\\d{1,2})+月份?(?:版本?)?"
                    + "|(?:(?:\\d{4}|\\d{2})年)?\\d{1,2}月份?(?:延后(?:版本?)?)?(?:版本?)?"
                    + "|(?:(?:\\d{4}|\\d{2})年)?\\d{1,2}(?:[、，,和与]\\s*\\d{1,2})+季度"
                    + "|(?:(?:\\d{4}|\\d{2})年)?[一二三四1-4]季度"
                    + "|(?:(?:\\d{4}|\\d{2})年)?[一二三四五六七八九十]{1,3}月份?(?:版本?)?");

    /** 组名候选左侧修剪虚词表：仅修剪 token 开头（"的质量xxx组" → "xxx组"） */
    private static final String TOKEN_LEADING_STOP = "的了是在有和与及为对按等把到从比跟由向着于";

    /**
     * 第一遍：识别标准格式 span（时间/部门/应用/需求项），等长掩码替换。
     * 掩码后组名正则不再吞 "10月" 与部门前缀——"杭州三部数据智能组" 的前缀剥离
     * 由部门 span 占用自然达成，产出 TEAM=数据智能组 + DEPT=杭州开发三部。
     */
    private static String buildMaskedQuestion(String q) {
        return maskSpans(q, TIME_MASK_SPAN, EXPLICIT_DEPT, EXPLICIT_APP,
                EXPLICIT_REQUIREMENT, EXPLICIT_SUB_REQUIREMENT);
    }

    /**
     * 人名扫描共用屏蔽文本（人员台账维度方案 §4.1）：在 {@link #buildMaskedQuestion} 的
     * 屏蔽集（时间/部门/应用/需求项/需求子条目）之上追加 组/产品线候选 span——
     * "张三组"里的"张三"不得被姓名字典扫描抢走（组名归 EXPLICIT_TEAM）。
     * 供 {@code DirectSkillExecutor.plan} 的人名 2~4 字窗口扫描消费。
     */
    public static String maskForPersonScan(String q) {
        return maskSpans(q, TIME_MASK_SPAN, EXPLICIT_DEPT, EXPLICIT_APP,
                EXPLICIT_REQUIREMENT, EXPLICIT_SUB_REQUIREMENT, EXPLICIT_TEAM, EXPLICIT_PRODUCT_LINE);
    }

    /** 等 len 屏蔽替换（占位符不在各正则字符类内，保偏移）；无 span 原样返回。 */
    private static String maskSpans(String q, Pattern... patterns) {
        List<int[]> spans = new ArrayList<>();
        for (Pattern p : patterns) {
            collectSpans(q, p, spans);
        }
        return maskSpanList(q, spans);
    }

    /**
     * 日期类屏蔽 span 并集（{@link TimeRangeExtractor#absoluteDateSpans} 的区间/截至
     * + 裸 "M月D日"）：供版本正则提取与内置键词表合并屏蔽——日期里的月份数字不是版本。
     */
    private static List<int[]> dateShieldSpans(String q) {
        List<int[]> spans = new ArrayList<>(TimeRangeExtractor.absoluteDateSpans(q));
        Matcher m = BARE_DATE_SPAN.matcher(q);
        while (m.find()) {
            spans.add(new int[] {m.start(), m.end()});
        }
        return spans;
    }

    /**
     * 时间 span 公共出口（检索词条 span 屏蔽用，docs/202610/检索关键词与维度值冲突治理方案.md S4）：
     * 宽时间形态（TIME_MASK_SPAN）+ 日期区间/裸 "M月D日"。入参为原文本（大小写不敏感场景
     * 由调用方保证），坐标与入参一致。
     */
    public static List<int[]> timeShieldSpans(String q) {
        if (q == null || q.isEmpty()) return List.of();
        List<int[]> spans = dateShieldSpans(q);
        collectSpans(q, TIME_MASK_SPAN, spans);
        return spans;
    }

    /** 多组 span 合并后统一屏蔽（maskSpanList 内部按 start 排序并跳过重叠）。 */
    private static String maskSpanUnion(String q, List<int[]>... groups) {
        List<int[]> all = new ArrayList<>();
        for (List<int[]> g : groups) {
            all.addAll(g);
        }
        return all.isEmpty() ? q : maskSpanList(q, all);
    }

    /** 屏蔽给定 start/end span (按 start 排序去重叠)。 */
    private static String maskSpanList(String q, List<int[]> spans) {        if (spans.isEmpty()) {
            return q;
        }
        spans.sort(Comparator.comparingInt(s -> s[0]));
        char[] chars = q.toCharArray();
        int lastEnd = -1;
        for (int[] s : spans) {
            if (s[0] < lastEnd) {
                continue;
            }
            for (int i = s[0]; i < s[1]; i++) {
                chars[i] = MASK_CHAR;
            }
            lastEnd = s[1];
        }
        return new String(chars);
    }

    private static void collectSpans(String q, Pattern p, List<int[]> out) {
        Matcher m = p.matcher(q);
        while (m.find()) {
            out.add(new int[] {m.start(), m.end()});
        }
    }

    /**
     * 第二遍：屏蔽文本上找组名 token；候选左侧修剪（虚词逐字、指标词整词）；
     * 屏蔽后无命中或修剪失败（剩余 <2 字符）回退原文匹配（拆分失败维持原行为）。
     * 粒度词候选（§5.1 守卫）跳过继续找，全部为粒度词时不回退原文——
     * 原文回退会把"杭州三部10月版各组"整段吞成组名。
     */
    private String findTeamToken(String q, String masked) {
        Set<String> stems = granularityStems("groupName");
        List<Pattern> blocks = blockPatterns("groupName");
        Matcher maskedMatcher = EXPLICIT_TEAM.matcher(masked);
        while (maskedMatcher.find()) {
            String token = trimTeamToken(q, maskedMatcher.start(), maskedMatcher.end());
            if (!isGranularityToken(token) && !hasGranularityTail(token, 1)
                    && !containsAnyStem(token, stems) && !matchesAnyBlock(token, blocks)) {
                return token;
            }
        }
        Matcher rawMatcher = EXPLICIT_TEAM.matcher(q);
        while (rawMatcher.find()) {
            String token = rawMatcher.group(1);
            if (isGranularityToken(token) || hasGranularityTail(token, 1)
                    || containsAnyStem(token, stems) || matchesAnyBlock(token, blocks)) {
                continue;
            }
            return token;
        }
        return null;
    }

    /**
     * 产品线候选：屏蔽文本优先，粒度词候选跳过；屏蔽后无命中回退原文（守卫同组名）。
     */
    private String findProductLineToken(String q, String masked) {
        Set<String> stems = granularityStems("productLine");
        List<Pattern> blocks = blockPatterns("productLine");
        Matcher maskedMatcher = EXPLICIT_PRODUCT_LINE.matcher(masked);
        while (maskedMatcher.find()) {
            String token = maskedMatcher.group(1);
            if (!isGranularityToken(token) && !hasGranularityTail(token, 3)
                    && !containsAnyStem(token, stems) && !matchesAnyBlock(token, blocks)) {
                return token;
            }
        }
        Matcher rawMatcher = EXPLICIT_PRODUCT_LINE.matcher(q);
        while (rawMatcher.find()) {
            String token = rawMatcher.group(1);
            if (isGranularityToken(token) || hasGranularityTail(token, 3)
                    || containsAnyStem(token, stems) || matchesAnyBlock(token, blocks)) {
                continue;
            }
            return token;
        }
        return null;
    }

    /** detectLevel 的组/产品线命中判定：屏蔽文本上找非粒度词候选（§5.1 守卫），
     *  "杭州三部10月版各组、各产品线" 不再误判 PEER 层级。 */
    private boolean hasTeamOrProductLineHit(String q) {
        String masked = buildMaskedQuestion(q);
        return hasNonGranularityMatch(masked, EXPLICIT_TEAM, 1, granularityStems("groupName"),
                blockPatterns("groupName"))
                || hasNonGranularityMatch(masked, EXPLICIT_PRODUCT_LINE, 3, granularityStems("productLine"),
                        blockPatterns("productLine"));
    }

    /** 我X按需注入方案 §3.2 重锚抑制的组维度显式命中判定（独立于 peerDimension 单槽位，
     *  应用占槽时组名显式提及仍可判出；含粒度词守卫与词表反哺 stems + block 屏蔽 §9）。 */
    public boolean hasExplicitTeamHit(String q) {
        String masked = buildMaskedQuestion(q);
        return hasNonGranularityMatch(masked, EXPLICIT_TEAM, 1, granularityStems("groupName"),
                blockPatterns("groupName"));
    }

    /** 同上，产品线维度显式命中判定。 */
    public boolean hasExplicitProductLineHit(String q) {
        String masked = buildMaskedQuestion(q);
        return hasNonGranularityMatch(masked, EXPLICIT_PRODUCT_LINE, 3, granularityStems("productLine"),
                blockPatterns("productLine"));
    }

    /**
     * 组名候选左侧修剪。整段 token 是词表已知的小组标准名/别名时不修剪，
     * 防"质量保障组"这类含指标词的真组名被误剪（词表没登记的真组名靠
     * 工具层 deptGroupCheck 兜底，残余风险见 §13.4 边缘规则）。
     */
    private String trimTeamToken(String q, int start, int end) {
        String token = q.substring(start, end);
        if (aliasResolver != null && aliasResolver.knowsTeamStandard(token)) {
            return token;
        }
        Set<String> metricWords =
                metricWordsSupplier != null ? metricWordsSupplier.get() : Set.of();
        int s = start;
        boolean progress = true;
        while (progress && end - s >= 2) {
            progress = false;
            if (TOKEN_LEADING_STOP.indexOf(q.charAt(s)) >= 0) {
                s++;
                progress = true;
                continue;
            }
            for (String w : metricWords) {
                if (w != null && w.length() >= 2 && q.startsWith(w, s) && end - (s + w.length()) >= 2) {
                    s += w.length();
                    progress = true;
                    break;
                }
            }
        }
        String trimmed = q.substring(s, end);
        return trimmed.length() >= 2 && trimmed.endsWith("组") ? trimmed : token;
    }

    public QuestionAnalysis analyzeQuestionRuleBased(String userQuestion) {
        QuestionAnalysis analysis = new QuestionAnalysis();

        // 0. 同义词表解析（aliasResolver 为 null 时返回空结果，退化为纯正则）
        AliasResolver.AliasResolution aliasRes =
                aliasResolver != null && userQuestion != null && !userQuestion.isBlank()
                        ? aliasResolver.resolve(userQuestion)
                        : AliasResolver.AliasResolution.EMPTY;
        aliasRes = resolveEmbeddedCandidates(userQuestion, aliasRes);
        aliasRes = suppressEmbeddedAliasHits(userQuestion, aliasRes);
        boolean aliasPeerHit = !aliasRes.resolved().isEmpty() || !aliasRes.ambiguous().isEmpty();
        if (!aliasRes.ambiguous().isEmpty()) {
            analysis.setAmbiguousAliases(aliasRes.ambiguous());
        }
        analysis.setAliasResolution(aliasRes);

        // 1. detect reference
        QuestionAnalysis.ReferenceType refType = detectReferenceType(userQuestion);
        analysis.setHasReference(refType != null);
        analysis.setReferenceType(refType);

        // 1.5 内置键字典词表命中（口语值方案 §3.2）：versionPlan/vestQuarter/deptName
        //     白名单键 alias 合并；timeRange L1 绝对区间 span 不参与（防 "10月1日" 被 "1月" 类词误收）
        BuiltinVocabHits vocab = collectBuiltinVocabHits(userQuestion);

        // 2. detect level
        QuestionAnalysis.QuestionLevel level = detectLevel(userQuestion, refType, aliasRes, vocab);
        analysis.setLevel(level);
        analysis.setCauseAnalysis(level == QuestionAnalysis.QuestionLevel.CAUSE);

        // 3. extract explicit dimensions
        QuestionAnalysis.ExplicitDimensions explicit = extractExplicitDimensions(userQuestion, aliasRes, vocab);
        analysis.setExplicitDimensions(explicit);
        explicit.build();

        return analysis;
    }

    private QuestionAnalysis.ReferenceType detectReferenceType(String q) {
        if (REF_TIME.matcher(q).find()) return QuestionAnalysis.ReferenceType.TIME;
        if (REF_DEPT.matcher(q).find()) return QuestionAnalysis.ReferenceType.DEPARTMENT;
        if (REF_TEAM.matcher(q).find()) return QuestionAnalysis.ReferenceType.TEAM;
        if (REF_APP.matcher(q).find()) return QuestionAnalysis.ReferenceType.APPLICATION;
        if (REF_PRODUCT.matcher(q).find()) return QuestionAnalysis.ReferenceType.PRODUCT_LINE;
        if (REF_REQUIREMENT.matcher(q).find()) return QuestionAnalysis.ReferenceType.REQUIREMENT;
        if (REF_SUB_REQUIREMENT.matcher(q).find())
            return QuestionAnalysis.ReferenceType.SUB_REQUIREMENT;
        return null;
    }

    private QuestionAnalysis.QuestionLevel detectLevel(
            String q, QuestionAnalysis.ReferenceType refType,
            AliasResolver.AliasResolution aliasRes, BuiltinVocabHits vocab) {
        boolean aliasPeerHit = aliasRes != null
                && (!aliasRes.resolved().isEmpty() || !aliasRes.ambiguous().isEmpty());
        if (q.matches(".*原因.*") || q.matches(".*为什么.*"))
            return QuestionAnalysis.QuestionLevel.CAUSE;
        if (q.matches(".*过去.*") || q.matches(".*趋势.*") || q.matches(".*历史.*"))
            return QuestionAnalysis.QuestionLevel.TIME_RANGE;
        if (q.matches(".*谁.*")) return QuestionAnalysis.QuestionLevel.PERSON;
        if (refType == QuestionAnalysis.ReferenceType.TEAM
                || refType == QuestionAnalysis.ReferenceType.APPLICATION
                || refType == QuestionAnalysis.ReferenceType.PRODUCT_LINE
                || refType == QuestionAnalysis.ReferenceType.REQUIREMENT
                || refType == QuestionAnalysis.ReferenceType.SUB_REQUIREMENT
                || aliasPeerHit
                || EXPLICIT_APP.matcher(q).find()
                || !bareAppCodes(q, aliasRes).isEmpty()
                || hasTeamOrProductLineHit(q)
                || EXPLICIT_REQUIREMENT.matcher(q).find()
                || EXPLICIT_SUB_REQUIREMENT.matcher(q).find())
            return QuestionAnalysis.QuestionLevel.PEER;
        if (q.contains("部门") || EXPLICIT_DEPT.matcher(q).find()
                || (vocab != null && !vocab.deptValues().isEmpty()))
            return QuestionAnalysis.QuestionLevel.DEPARTMENT;
        if (refType == QuestionAnalysis.ReferenceType.DEPARTMENT)
            return QuestionAnalysis.QuestionLevel.DEPARTMENT;
        if (EXPLICIT_VERSION.matcher(q).find()
                || EXPLICIT_MONTH.matcher(q).find()
                || EXPLICIT_QUARTER.matcher(q).find()
                || COMPACT_VERSION.matcher(q).find()
                || CN_MONTH_VERSION.matcher(q).find()
                || DELAYED_VERSION.matcher(q).find()
                || LAST_N_VERSIONS.matcher(q).find()
                || (vocab != null && (!vocab.versionValues().isEmpty() || !vocab.quarterValues().isEmpty()))
                || refType == QuestionAnalysis.ReferenceType.TIME)
            return QuestionAnalysis.QuestionLevel.TIME;
        // No dimensions detected — non-dimension question (e.g. "数据服务表清单下载")
        return null;
    }

    private QuestionAnalysis.ExplicitDimensions extractExplicitDimensions(
            String q, AliasResolver.AliasResolution aliasRes, BuiltinVocabHits vocab) {
        QuestionAnalysis.ExplicitDimensions explicit = new QuestionAnalysis.ExplicitDimensions();
        int year = LocalDate.now().getYear();

        // 时间维度：各写法归一化后多值收集（"4月版和5月版" → 两个版本计划）。
        // 带年份前缀用前缀年（"2025年4月"），否则补当前年。全部写法归一为完整格式，
        // 不同写法生成同一指纹，auto-synth 计数才能累积。
        // VERSION 与 QUARTER 同现时 VERSION 优先 — TimeDimension 单类型，混用只保一。
        Set<String> versions = new LinkedHashSet<>();
        Set<String> quarters = new LinkedHashSet<>();
        String currentYear = String.valueOf(year);

        // 裸日期/日期区间 span（"10月26日到期"）不是版本：先收集屏蔽，
        // 防止 EXPLICIT_VERSION/EXPLICIT_MONTH 从日期里吃出 "10月" 版本值
        List<int[]> dateSpans = dateShieldSpans(q);

        // 共享后缀列举先跑 ("10、11月版" → 10月+11月两个版本), 否则前段裸数字漏收;
        // 末段与 EXPLICIT_VERSION 归一产物相同, Set 去重指纹不变
        List<int[]> sharedTimeSpans = new ArrayList<>();
        Matcher sharedMatcher = SHARED_SUFFIX_VERSION.matcher(q);
        while (sharedMatcher.find()) {
            String y = sharedMatcher.group(1) != null ? sharedMatcher.group(1) : currentYear;
            versions.add(y + "年" + sharedMatcher.group(2) + "月份版本");
            for (String part : sharedMatcher.group(3).split("[、，,和与]\\s*")) {
                if (!part.isBlank()) {
                    versions.add(y + "年" + part.trim() + "月份版本");
                }
            }
            sharedTimeSpans.add(new int[] {sharedMatcher.start(), sharedMatcher.end()});
        }

        // 共享 span + 日期 span 屏蔽后再跑单数正则: 显式年份≠当前年时, 末段会被单数正则按
        // "无年份→补当前年"重复收一遍 (2025年3和4季度 → 多出 2026年4季度)
        String qTime = maskSpanUnion(q, dateSpans, sharedTimeSpans);

        Matcher versionMatcher = EXPLICIT_VERSION.matcher(qTime);
        while (versionMatcher.find()) {
            String y = versionMatcher.group(1) != null ? versionMatcher.group(1) : currentYear;
            versions.add(y + "年" + versionMatcher.group(2) + "月份版本");
        }

        Matcher sharedQuarterMatcher = SHARED_SUFFIX_QUARTER.matcher(q);
        while (sharedQuarterMatcher.find()) {
            String y = sharedQuarterMatcher.group(1) != null
                    ? sharedQuarterMatcher.group(1) : currentYear;
            quarters.add(y + "年" + sharedQuarterMatcher.group(2) + "季度");
            for (String part : sharedQuarterMatcher.group(3).split("[、，,和与]\\s*")) {
                if (!part.isBlank()) {
                    quarters.add(y + "年" + part.trim() + "季度");
                }
            }
            sharedTimeSpans.add(new int[] {sharedQuarterMatcher.start(), sharedQuarterMatcher.end()});
        }

        // 共享 span + 日期 span 已在 qTime 屏蔽串中重算覆盖 (含季度共享段)
        qTime = maskSpanUnion(q, dateSpans, sharedTimeSpans);

        Matcher quarterMatcher = EXPLICIT_QUARTER.matcher(qTime);
        while (quarterMatcher.find()) {
            String y = quarterMatcher.group(1) != null ? quarterMatcher.group(1) : currentYear;
            quarters.add(y + "年" + quarterMatcher.group(2) + "季度");
        }

        // 延后版标准值（口语值方案 §3.2）：与月份版本同级的独立版本计划，须在 MONTH 之前
        // （MONTH 已有 (?!延后) 守卫，此处顺序仅保证延后版写法优先归一）
        Matcher delayedMatcher = DELAYED_VERSION.matcher(q);
        while (delayedMatcher.find()) {
            String y = delayedMatcher.group(1) != null ? delayedMatcher.group(1) : currentYear;
            versions.add(y + "年" + delayedMatcher.group(2) + "月延后版");
        }

        // 紧凑 YYYYMM（"202610版本"/"202610" → 2026年10月份版本）
        Matcher compactMatcher = COMPACT_VERSION.matcher(q);
        while (compactMatcher.find()) {
            versions.add(compactMatcher.group(1) + "年"
                    + Integer.parseInt(compactMatcher.group(2)) + "月份版本");
        }

        // 中文数字月份（"十月版"/"十一月份" → 2026年10月份版本）
        Matcher cnMonthMatcher = CN_MONTH_VERSION.matcher(q);
        while (cnMonthMatcher.find()) {
            Integer m = cnMonthToNumber(cnMonthMatcher.group(2));
            if (m != null) {
                String y = cnMonthMatcher.group(1) != null ? cnMonthMatcher.group(1) : currentYear;
                versions.add(y + "年" + m + "月份版本");
            }
        }

        // Short month format: "4月" → construct full version（"4月份版本"已被上面吃掉）
        // 走 qTime: 无版共享段 ("10、11月") 已被共享正则收走, 屏蔽后不再被单数正则
        // 按 "无年份→补当前年" 重复收末段
        Matcher monthMatcher = EXPLICIT_MONTH.matcher(qTime);
        while (monthMatcher.find()) {
            String y = monthMatcher.group(1) != null ? monthMatcher.group(1) : currentYear;
            versions.add(y + "年" + monthMatcher.group(2) + "月份版本");
        }

        // 近 N 个版本/近 N 个月 → LAST:{n}V 锚点（执行期经日历展开，口语值方案 §3.1）
        Matcher lastNMatcher = LAST_N_VERSIONS.matcher(q);
        while (lastNMatcher.find()) {
            String n = lastNMatcher.group(1) != null ? lastNMatcher.group(1) : lastNMatcher.group(2);
            if (n != null) {
                versions.add("LAST:" + Integer.parseInt(n) + "V");
            }
        }

        // Quarter aliases: "一季度"~"四季度" — normalise so user shorthand maps to the same
        // fingerprint as "2026年1季度". Without this, "一季度 杭一部" and "1季度 杭一部" are different
        // candidates and the auto-synth counter never accumulates. 裸 "Q1"~"Q4" 不再算季度 (QI卡口撞词)。
        Matcher aliasMatcher = EXPLICIT_QUARTER_ALIAS.matcher(q);
        while (aliasMatcher.find()) {
            String qNum = switch (aliasMatcher.group(1)) {
                case "一" -> "1";
                case "二" -> "2";
                case "三" -> "3";
                case "四" -> "4";
                default -> null;
            };
            if (qNum != null) {
                quarters.add(currentYear + "年" + qNum + "季度");
            }
        }

        // 字典词表命中合并（口语值方案 §3.2）：锚点原词（YEAR_V/THIS_V/THIS_Q/近N个版本正则产物）
        // 与标准值都进 TimeDimension——锚点执行期经 VersionPlanAnchorResolver 展开，绝不进指纹
        if (vocab != null) {
            versions.addAll(vocab.versionValues());
            quarters.addAll(vocab.quarterValues());
        }

        if (!versions.isEmpty()) {
            explicit.setTimeDimension(
                    new DimensionState.TimeDimension(
                            DimensionState.TimeDimensionType.VERSION,
                            new ArrayList<>(versions)));
        } else if (!quarters.isEmpty()) {
            explicit.setTimeDimension(
                    new DimensionState.TimeDimension(
                            DimensionState.TimeDimensionType.QUARTER,
                            new ArrayList<>(quarters)));
        }

        // 部门：收集全部命中（"一部和二部比" → 两个部门），归一化后去重
        Matcher deptMatcher = EXPLICIT_DEPT.matcher(q);
        Set<String> departments = new LinkedHashSet<>();
        while (deptMatcher.find()) {
            departments.add(normalizeDepartment(deptMatcher.group()));
        }
        // 字典词表命中合并（口语值方案 §3.2）：词表 value 应为部门标准名，直接并入
        if (vocab != null) {
            departments.addAll(vocab.deptValues());
        }
        if (!departments.isEmpty()) {
            explicit.setDepartments(new ArrayList<>(departments));
        }

        // 应用（F-xxx）— 优先级最高：alias 解析出组/产品线但问题同时含 F-xxx 编码时，
        // 保持既有 app > 组/产品线 的优先序，alias 结果让位（歧义项仍记录，供短路反问）
        // 多值收集（"F-A、F-B" → 两个应用），与部门的收集口径一致
        Matcher appMatcher = EXPLICIT_APP.matcher(q);
        Set<String> apps = new LinkedHashSet<>();
        while (appMatcher.find()) {
            // 应用码归一大写 ("F-gcmcs"→F-GCMCS), 与裸码大写归一同口径, 保证去重合并
            apps.add(appMatcher.group().toUpperCase(Locale.ROOT));
        }
        // 裸应用码 ("FPA4季度" → F-FPA): 与 F- 编码同集合去重 ("FPA和F-FPA" → 一个应用)。
        // 裸码是省略前缀的启发式猜测, 不与配置过的 alias 精确命中争 peer 槽位 ——
        // E2E 2026-10-27: 「使用 q2_1_dual_time_demo skill 车贷、房贷两个组」skill 名被
        // 拆成 F-DUAL/F-TIME 假码, APPLICATION 单类型槽位把 alias 的 TEAM 双值整个挤掉
        if (!aliasHit(aliasRes)) {
            apps.addAll(bareAppCodes(q, aliasRes));
        }
        if (!apps.isEmpty()) {
            explicit.setPeerDimension(
                    new DimensionState.PeerDimension(
                            DimensionState.PeerDimensionType.APPLICATION,
                            new ArrayList<>(apps)));
        }

        // 同义词表解析结果（标准名直接进状态，保证指纹稳定）— 优先于小组/产品线正则
        if (explicit.getPeerDimension() == null) {
            DimensionState.PeerDimension aliasPeer = buildAliasPeerDimension(aliasRes);
            if (aliasPeer != null) {
                explicit.setPeerDimension(aliasPeer);
            }
        }

        // 组正则兜底（alias 完全未命中时才跑，防止"风险组"这类歧义词被小组正则抢注）
        // — §13.4 两遍抽取：先屏蔽时间/部门/应用/需求项 span 再找组名，
        //   部门前缀剥离由 span 占用自然达成（"杭州三部数据智能组"→数据智能组）；
        //   排除指代词匹配（"这个组"、"那个组"）
        if (explicit.getPeerDimension() == null && !aliasHit(aliasRes)) {
            String masked = buildMaskedQuestion(q);
            String teamToken = findTeamToken(q, masked);
            if (teamToken != null && !teamToken.startsWith("这个") && !teamToken.startsWith("那个")) {
                explicit.setPeerDimension(
                        new DimensionState.PeerDimension(
                                DimensionState.PeerDimensionType.TEAM,
                                List.of(teamToken)));
            }
        }

        // 产品线（优先级低于组和应用）— 同上，alias 命中时跳过；屏蔽文本匹配避免
        // 时间/部门 span 干扰（"三部10月的质量" 不再把"10月"吃进产品线候选），
        // 屏蔽后无命中回退原文；排除指代词匹配；粒度词守卫同组名（§5.1）
        if (explicit.getPeerDimension() == null && !aliasHit(aliasRes)) {
            String maskedPl = buildMaskedQuestion(q);
            String plMatched = findProductLineToken(q, maskedPl);
            if (plMatched != null && !plMatched.startsWith("这个") && !plMatched.startsWith("那个")) {
                explicit.setPeerDimension(
                        new DimensionState.PeerDimension(
                                DimensionState.PeerDimensionType.PRODUCT_LINE,
                                List.of(plMatched)));
            }
        }

        // 需求项 — 真正的 itemNo 格式 I20260208-0005（alias 命中时同样跳过）。
        // 多值收集 ("I-...、I-..." 两个需求项), 口径与部门/应用一致
        if (explicit.getPeerDimension() == null && !aliasHit(aliasRes)) {
            Matcher reqMatcher = EXPLICIT_REQUIREMENT.matcher(q);
            Set<String> reqs = new LinkedHashSet<>();
            while (reqMatcher.find()) {
                reqs.add(reqMatcher.group());
            }
            if (!reqs.isEmpty()) {
                explicit.setPeerDimension(
                        new DimensionState.PeerDimension(
                                DimensionState.PeerDimensionType.REQUIREMENT,
                                new ArrayList<>(reqs)));
            }
        }

        // 需求子条目 — subItemNo 格式 S20250926-000741（alias 命中时同样跳过）。多值同上
        if (explicit.getPeerDimension() == null && !aliasHit(aliasRes)) {
            Matcher subReqMatcher = EXPLICIT_SUB_REQUIREMENT.matcher(q);
            Set<String> subReqs = new LinkedHashSet<>();
            while (subReqMatcher.find()) {
                subReqs.add(subReqMatcher.group());
            }
            if (!subReqs.isEmpty()) {
                explicit.setPeerDimension(
                        new DimensionState.PeerDimension(
                                DimensionState.PeerDimensionType.SUB_REQUIREMENT,
                                new ArrayList<>(subReqs)));
            }
        }

        return explicit;
    }

    /** alias 表是否命中过该问题（含歧义命中）——命中即禁用小组/产品线/需求项正则兜底。 */
    private static boolean aliasHit(AliasResolver.AliasResolution aliasRes) {
        return aliasRes != null
                && (!aliasRes.resolved().isEmpty() || !aliasRes.ambiguous().isEmpty());
    }

    // ==================== 内置键字典词表合并（口语值方案 §3.2） ====================

    /** 词表合并白名单：仅这三个内置键的 dimension_key_config alias 行参与合并。 */
    private static final Set<String> VOCAB_MERGE_KEYS =
            Set.of("versionPlan", "vestQuarter", "deptName");

    /** 紧邻 alias 命中之前的年份前缀（"25年上半年"的"25年"，窗口 5 字符足够 4 位年+年）。 */
    private static final java.util.regex.Pattern YEAR_PREFIX_TAIL =
            java.util.regex.Pattern.compile("(?:20\\d{2}|\\d{2})年$");

    /** 可带 @yyyy 年份限定的锚点：YEAR_V / HALF_V:{1|2}（LAST/THIS_V/DELAYED 无年份语义）。 */
    private static boolean isYearScalableAnchor(String value) {
        return "YEAR_V".equals(value) || value != null && value.matches("HALF_V:[12]");
    }

    /** 内置键词表命中集（各 Set 均非 null，可为空）。 */
    record BuiltinVocabHits(
            Set<String> versionValues, Set<String> quarterValues, Set<String> deptValues) {

        static final BuiltinVocabHits EMPTY =
                new BuiltinVocabHits(Set.of(), Set.of(), Set.of());

        boolean isEmpty() {
            return versionValues.isEmpty() && quarterValues.isEmpty() && deptValues.isEmpty();
        }
    }

    /**
     * 扫描 dimension_key_config 白名单内置键的词表行：问题 contains alias 即收集 value
     * （与动态键轨同语义）。timeRange L1 绝对区间 span（"10月1日到10月15日"）不参与，
     * 防日期区间里的月份数字被词表误收。字典供给器缺失/读失败 → 返回 EMPTY（纯正则降级）。
     */
    private BuiltinVocabHits collectBuiltinVocabHits(String q) {
        if (q == null || q.isBlank() || dimensionKeySupplier == null) {
            return BuiltinVocabHits.EMPTY;
        }
        List<DimensionKeyConfig> rows;
        try {
            rows = dimensionKeySupplier.get();
        } catch (RuntimeException e) {
            log.warn("DimensionStateManager: 维度键字典读取失败, 内置键词表合并跳过: {}", e.getMessage());
            return BuiltinVocabHits.EMPTY;
        }
        if (rows == null || rows.isEmpty()) {
            return BuiltinVocabHits.EMPTY;
        }
        List<int[]> shielded = dateShieldSpans(q);
        Set<String> versions = new LinkedHashSet<>();
        Set<String> quarters = new LinkedHashSet<>();
        Set<String> depts = new LinkedHashSet<>();
        for (DimensionKeyConfig row : rows) {
            if (!VOCAB_MERGE_KEYS.contains(row.key()) || row.aliases().isEmpty()) {
                continue;
            }
            for (DimensionKeyConfig.AliasMapping alias : row.aliases()) {
                if (!alias.valid() || !alias.isSynonym()) {
                    continue; // regex 型条目（我X方案 §8.1）仅供 resolver 正则消费方使用
                }
                int from = 0;
                int idx;
                while ((idx = q.indexOf(alias.alias(), from)) >= 0) {
                    int end = idx + alias.alias().length();
                    int hitStart = idx;
                    boolean overlaps = shielded.stream()
                            .anyMatch(s -> hitStart < s[1] && end > s[0]);
                    if (!overlaps) {
                        String value = alias.value();
                        // "25年上半年"/"2025年各月"：alias 紧邻年份前缀时升级为 @yyyy 限定锚点，
                        // 解析期按限定年展开，否则 HALF_V/YEAR_V 一律落当前年（口语值方案 §3.2 表）
                        if ("versionPlan".equals(row.key()) && idx > 0 && isYearScalableAnchor(value)) {
                            java.util.regex.Matcher ym = YEAR_PREFIX_TAIL.matcher(
                                    q.substring(Math.max(0, idx - 5), idx));
                            if (ym.find()) {
                                String y = ym.group().substring(0, ym.group().length() - 1);
                                value = value + "@"
                                        + (y.length() == 2 ? 2000 + Integer.parseInt(y) : Integer.parseInt(y));
                            }
                        }
                        switch (row.key()) {
                            case "versionPlan" -> versions.add(value);
                            case "vestQuarter" -> quarters.add(alias.value());
                            case "deptName" -> depts.add(alias.value());
                            default -> { }
                        }
                    }
                    from = idx + 1;
                }
            }
        }
        return new BuiltinVocabHits(versions, quarters, depts);
    }

    /** 中文数字月 → 1~12；超界/无法解析返回 null（整段跳过）。"十一"=11、"二十"=20(拒绝)。 */
    private static Integer cnMonthToNumber(String cn) {
        if (cn == null || cn.isEmpty()) {
            return null;
        }
        int tenAt = cn.indexOf('十');
        if (tenAt < 0) {
            Integer single = cnDigit(cn);
            return single != null && single >= 1 && single <= 12 ? single : null;
        }
        int tens = 1;
        if (tenAt > 0) {
            Integer t = cnDigit(cn.substring(0, tenAt));
            if (t == null || t < 1 || t > 9) {
                return null;
            }
            tens = t;
        }
        int ones = 0;
        if (tenAt + 1 < cn.length()) {
            Integer o = cnDigit(cn.substring(tenAt + 1));
            if (o == null || o < 1 || o > 9) {
                return null;
            }
            ones = o;
        }
        int value = tens * 10 + ones;
        return value >= 1 && value <= 12 ? value : null;
    }

    private static Integer cnDigit(String cn) {
        if (cn == null || cn.length() != 1) {
            return null;
        }
        return switch (cn.charAt(0)) {
            case '一' -> 1;
            case '二' -> 2;
            case '三' -> 3;
            case '四' -> 4;
            case '五' -> 5;
            case '六' -> 6;
            case '七' -> 7;
            case '八' -> 8;
            case '九' -> 9;
            case '十' -> 10;
            default -> null;
        };
    }

    /**
     * 嵌入小组名内部的 alias 命中抑制："个贷数据开发组"里的"个贷"被 alias 解析成产品线
     * （个人信贷产品线，与该组毫无关系的实体），且压制小组识别（aliasHit 为 true 时小组
     * 正则不跑），此类命中丢弃，让小组正则接管。
     *
     * <p>判据（按样例特征归纳）：alias 命中嵌在组名 token 内部（end &lt; teamEnd 且之后
     * 还有不止一个"组"字的修饰成分）时——
     * <ul>
     *   <li>标准名与组名 token <b>互相包含</b> ⇒ alias 指的就是该组，保留并产出标准名+映射行：
     *       "杭州开发三部同业客户组"里的"同业"→ 同业客户组（标准名是 token 后缀）；
     *       "杭州服务支持部分行平台服务创新组"里的"分行"→ 归一化出完整标准名；</li>
     *   <li>互不包含 ⇒ 命中只是组名里的碰巧子串，抑制："个贷数据开发组"里的"个贷"
     *       （标准名"个人信贷产品线"与 token 无关）、"分行业务组"里的"分行"
     *       （否则错绑到"杭州服务支持部分行平台服务创新组"）。</li>
     * </ul>
     * 非组维度命中（产品线/应用）一律抑制——组名里嵌的产品线词不是产品线指代。
     */
    /**
     * 别名命中是完整标准名前缀时静默消歧（2026/10/29 反问回环修复）：
     * 问题文本已完整包含某候选标准名（如反问确认轮把『金融市场』替换成『金融市场对客交易』，
     * 替换结果仍是别名『金融市场』的子串超集）时，该歧义直接落为 resolved，不再反问；
     * 直接输入完整标准名的问题（"金融市场对客交易积压"）同样受益。多个候选同时出现
     * 则是真歧义，保留反问。
     */
    private static AliasResolver.AliasResolution resolveEmbeddedCandidates(
            String q, AliasResolver.AliasResolution res) {
        if (res.ambiguous().isEmpty()) {
            return res;
        }
        List<AliasResolver.ResolvedAlias> resolved = new ArrayList<>(res.resolved());
        List<AliasResolver.AmbiguousAlias> keptAmbiguous = new ArrayList<>();
        boolean changed = false;
        for (AliasResolver.AmbiguousAlias amb : res.ambiguous()) {
            List<String> present = amb.candidates().stream()
                    .filter(c -> c != null && q.contains(c))
                    .toList();
            if (present.size() == 1) {
                resolved.add(new AliasResolver.ResolvedAlias(
                        amb.dimension(), amb.alias(), present.get(0), amb.start(), amb.end()));
                changed = true;
            } else {
                keptAmbiguous.add(amb);
            }
        }
        if (!changed) {
            return res;
        }
        return new AliasResolver.AliasResolution(List.copyOf(resolved), List.copyOf(keptAmbiguous));
    }

    private static AliasResolver.AliasResolution suppressEmbeddedAliasHits(
            String q, AliasResolver.AliasResolution res) {
        if (res.resolved().isEmpty() && res.ambiguous().isEmpty()) {
            return res;
        }
        List<int[]> teamSpans = new ArrayList<>();
        Matcher teamMatcher = EXPLICIT_TEAM.matcher(q);
        while (teamMatcher.find()) {
            teamSpans.add(new int[] {teamMatcher.start(), teamMatcher.end()});
        }
        if (teamSpans.isEmpty()) {
            return res;
        }
        List<AliasResolver.ResolvedAlias> keptResolved = res.resolved().stream()
                .filter(h -> !embeddedInTeamSpan(
                        q, h.dimension(), List.of(h.standardName()), h.start(), h.end(), teamSpans))
                .toList();
        List<AliasResolver.AmbiguousAlias> keptAmbiguous = res.ambiguous().stream()
                .filter(h -> !embeddedInTeamSpan(
                        q, h.dimension(), h.candidates(), h.start(), h.end(), teamSpans))
                .toList();
        if (keptResolved.size() == res.resolved().size()
                && keptAmbiguous.size() == res.ambiguous().size()) {
            return res;
        }
        return new AliasResolver.AliasResolution(keptResolved, keptAmbiguous);
    }

    private static boolean embeddedInTeamSpan(
            String q, DimensionState.PeerDimensionType dimension, List<String> standards,
            int start, int end, List<int[]> teamSpans) {
        for (int[] span : teamSpans) {
            if (start >= span[0] && end < span[1] && span[1] - end > 1) {
                // 数量粒度后缀豁免（维度值台账真值校验方案 §3）：token 尾巴是「两个组/3个组」
                // 型数量短语 → 该 token 是「alias + N个组」的口语组合而非组名，不抑制。
                // E2E 实证 2026-10-26: 「车贷、房贷两个组的风险」房贷命中被误杀丢值。
                String suffix = q.substring(end, span[1]);
                if (GRANULARITY_QUANTIFIER_SUFFIX.matcher(suffix).matches()) {
                    continue;
                }
                if (dimension == DimensionState.PeerDimensionType.TEAM) {
                    String token = q.substring(span[0], span[1]);
                    for (String standard : standards) {
                        if (token.contains(standard) || standard.contains(token)) {
                            return false;
                        }
                    }
                }
                // 治理信号：安全网兜住的组名大概率没配全称 alias 行（配了则最长匹配天然解决，
                // 不会走到这里），运营据此补配置（docs/history/dimension-alias-config-plan.md §12）
                log.warn("Embedded alias suppressed: alias='{}' dimension={} inside team token='{}'; "
                                + "consider adding a TEAM alias row for the full token",
                        q.substring(start, end), dimension, q.substring(span[0], span[1]));
                return true;
            }
        }
        return false;
    }

    /**
     * 把 AliasResolver 的 resolved 结果组装成 PeerDimension（值为标准名）。
     *
     * <p>多个命中落在不同维度类型时（PeerDimension 单类型），保留语序最前的类型并 warn；
     * 同类型多命中去重后全部收集（"比较军队和票据"→ 双产品线值）。
     */
    private DimensionState.PeerDimension buildAliasPeerDimension(AliasResolver.AliasResolution aliasRes) {
        if (aliasRes == null || aliasRes.resolved().isEmpty()) {
            return null;
        }
        List<AliasResolver.ResolvedAlias> hits = aliasRes.resolved();
        DimensionState.PeerDimensionType type = hits.get(0).dimension();
        List<String> values = new ArrayList<>();
        for (AliasResolver.ResolvedAlias hit : hits) {
            if (hit.dimension() != type) {
                log.warn("Alias hits span multiple peer dimensions ({} vs {}); keeping the first: {}",
                        type, hit.dimension(), hits);
                continue;
            }
            if (!values.contains(hit.standardName())) {
                values.add(hit.standardName());
            }
        }
        return values.isEmpty() ? null : new DimensionState.PeerDimension(type, values);
    }

    // ==================== 步骤②：维度继承 ====================

    /**
     * 根据问题分析结果和上一轮维度状态，决定维度保留与丢弃。
     */
    DimensionState inheritDimensions(DimensionState previous, QuestionAnalysis analysis) {
        if (previous == null) {
            previous = new DimensionState();
        }

        DimensionState current = new DimensionState();

        QuestionAnalysis.QuestionLevel level = analysis.getLevel();
        if (level == null) {
            // Non-dimension questions (e.g. greetings) — keep previous state unchanged
            this.currentState = previous;
            return previous;
        }

        switch (level) {
            case TIME:
                // timeDimension 由 LLM 回答后提取
                break;

            case DEPARTMENT:
                current.setTimeDimension(previous.getTimeDimension());
                // departments 由 LLM 回答后提取
                break;

            case PEER:
                current.setTimeDimension(previous.getTimeDimension());
                current.setDepartments(previous.getDepartments());
                // peerDimension 由 LLM 回答后提取
                break;

            case PERSON:
                current.setTimeDimension(previous.getTimeDimension());
                current.setDepartments(previous.getDepartments());
                current.setPeerDimension(previous.getPeerDimension());
                // persons 由 LLM 回答后提取
                break;

            case CAUSE:
                if (analysis.isHasReference()) {
                    current = handleReferenceInCause(previous, analysis);
                } else {
                    current = previous.deepCopy();
                }
                break;

            case TIME_RANGE:
                current.setTimeDimension(previous.getTimeDimension());
                current.setDepartments(previous.getDepartments());
                current.setPeerDimension(previous.getPeerDimension());
                break;

            default:
                break;
        }

        applyExplicitDimensions(current, analysis.getExplicitDimensions());

        return current;
    }

    /**
     * 处理原因分析中的指代：根据指代层级丢弃下级维度。
     */
    private DimensionState handleReferenceInCause(
            DimensionState previous, QuestionAnalysis analysis) {
        DimensionState current = previous.deepCopy();

        if (analysis.getReferenceType() == null) {
            return current;
        }

        switch (analysis.getReferenceType()) {
            case TIME:
                // "这个季度差的原因" → 时间维度是顶层，指代不影响下级维度，全部保留
                break;

            case DEPARTMENT:
                // "这个部门差的原因" → 丢弃 peerDimension 和 persons
                current.setPeerDimension(null);
                current.setPersons(null);
                break;

            case TEAM:
            case APPLICATION:
            case PRODUCT_LINE:
                // "这个组差的原因" → 保留到 peerDimension，丢弃 persons
                current.setPersons(null);
                break;

            default:
                break;
        }

        return current;
    }

    /**
     * 用户显式指定的维度覆盖继承值，并重置被覆盖维度的下级维度。
     */
    private void applyExplicitDimensions(
            DimensionState current, QuestionAnalysis.ExplicitDimensions explicit) {
        if (explicit == null) {
            return;
        }

        // 时间维度：显式指定后，时间维度以下的全部重置
        // 注意：不 return，后续的显式维度（如部门）可继续覆盖
        if (explicit.getTimeDimension() != null && !explicit.getTimeDimension().isEmpty()) {
            current.setTimeDimension(explicit.getTimeDimension());
            current.setDepartments(null);
            current.setPeerDimension(null);
            current.setPersons(null);
        }

        // 部门：显式指定后，部门以下的维度重置
        if (explicit.getDepartments() != null && !explicit.getDepartments().isEmpty()) {
            current.setDepartments(explicit.getDepartments());
            current.setPeerDimension(null);
            current.setPersons(null);
        }

        // 业务同级维度：显式指定后，人维度重置
        if (explicit.getPeerDimension() != null && !explicit.getPeerDimension().isEmpty()) {
            current.setPeerDimension(explicit.getPeerDimension());
            current.setPersons(null);
        }

        // 人：直接覆盖
        if (explicit.getPersons() != null && !explicit.getPersons().isEmpty()) {
            current.setPersons(explicit.getPersons());
        }
    }

    // ==================== 步骤③：指代消解 ====================

    /**
     * 指代消解：将"这个X"/"那个X"替换为结构化状态中的具体值。
     * 如果状态中对应维度为 null 或类型不匹配，保持原文不变。
     */
    String resolveReference(DimensionState state, QuestionAnalysis analysis, String userQuestion) {
        if (!analysis.isHasReference() || analysis.getReferenceType() == null) {
            return userQuestion;
        }

        String resolved = userQuestion;

        switch (analysis.getReferenceType()) {
            case TIME:
                if (state.getTimeDimension() != null && !state.getTimeDimension().isEmpty()) {
                    String joined = String.join("、", state.getTimeDimension().getValues());
                    if (state.getTimeDimension().getType()
                            == DimensionState.TimeDimensionType.QUARTER) {
                        resolved = resolved.replaceAll("这个季度|那个季度", joined);
                    } else {
                        resolved = resolved.replaceAll("这个版本|那个版本|这个月|那个月|这个月版本|那个月版本", joined);
                    }
                }
                break;

            case DEPARTMENT:
                if (state.getDepartments() != null && !state.getDepartments().isEmpty()) {
                    resolved =
                            resolved.replaceAll(
                                    "这个部门|那个部门", String.join("、", state.getDepartments()));
                }
                break;

            case TEAM:
                if (state.getPeerDimension() != null
                        && state.getPeerDimension().getType()
                                == DimensionState.PeerDimensionType.TEAM
                        && !state.getPeerDimension().isEmpty()) {
                    resolved =
                            resolved.replaceAll(
                                    "这个组|那个组",
                                    String.join("、", state.getPeerDimension().getValues()));
                }
                break;

            case APPLICATION:
                if (state.getPeerDimension() != null
                        && state.getPeerDimension().getType()
                                == DimensionState.PeerDimensionType.APPLICATION
                        && !state.getPeerDimension().isEmpty()) {
                    resolved =
                            resolved.replaceAll(
                                    "这个应用|那个应用",
                                    String.join("、", state.getPeerDimension().getValues()));
                }
                break;

            case PRODUCT_LINE:
                if (state.getPeerDimension() != null
                        && state.getPeerDimension().getType()
                                == DimensionState.PeerDimensionType.PRODUCT_LINE
                        && !state.getPeerDimension().isEmpty()) {
                    resolved =
                            resolved.replaceAll(
                                    "这个产品线|那个产品线",
                                    String.join("、", state.getPeerDimension().getValues()));
                }
                break;

            case REQUIREMENT:
                if (state.getPeerDimension() != null
                        && state.getPeerDimension().getType()
                                == DimensionState.PeerDimensionType.REQUIREMENT
                        && !state.getPeerDimension().isEmpty()) {
                    resolved =
                            resolved.replaceAll(
                                    "这个需求项|那个需求项",
                                    String.join("、", state.getPeerDimension().getValues()));
                }
                break;

            case SUB_REQUIREMENT:
                if (state.getPeerDimension() != null
                        && state.getPeerDimension().getType()
                                == DimensionState.PeerDimensionType.SUB_REQUIREMENT
                        && !state.getPeerDimension().isEmpty()) {
                    resolved =
                            resolved.replaceAll(
                                    "这个需求子条目|那个需求子条目",
                                    String.join("、", state.getPeerDimension().getValues()));
                }
                break;

            default:
                break;
        }

        return resolved;
    }

    // ==================== 步骤④：组装完整问题 ====================

    /**
     * 将继承后的维度状态格式化为自然语言前缀，拼接用户问题。
     */
    String assembleQuestion(DimensionState state, String userQuestion) {
        StringBuilder prefix = new StringBuilder();

        if (state.getTimeDimension() != null && !state.getTimeDimension().isEmpty()) {
            String label =
                    state.getTimeDimension().getType() == DimensionState.TimeDimensionType.QUARTER
                            ? "季度"
                            : "版本计划";
            prefix.append(label)
                    .append("：")
                    .append(String.join("、", state.getTimeDimension().getValues()))
                    .append(" ");
        }
        if (state.getDepartments() != null && !state.getDepartments().isEmpty()) {
            prefix.append("部门：").append(String.join("、", state.getDepartments())).append(" ");
        }
        if (state.getPeerDimension() != null && !state.getPeerDimension().isEmpty()) {
            String label =
                    switch (state.getPeerDimension().getType()) {
                        case TEAM -> "组";
                        case APPLICATION -> "应用";
                        case PRODUCT_LINE -> "产品线";
                        case REQUIREMENT -> "需求项";
                        case SUB_REQUIREMENT -> "需求子条目";
                    };
            prefix.append(label)
                    .append("：")
                    .append(String.join("、", state.getPeerDimension().getValues()))
                    .append(" ");
        }
        if (state.getPersons() != null && !state.getPersons().isEmpty()) {
            prefix.append("人：").append(String.join("、", state.getPersons())).append(" ");
        }

        String prefixStr = prefix.toString().trim();
        return prefixStr.isEmpty() ? userQuestion : prefixStr + " " + userQuestion;
    }

    // ==================== 步骤⑤：维度提取 ====================

    private DimensionState extractDimensions(String answer) {
        if (llmService == null) {
            throw new DimensionException(
                    "LlmDimensionService is required for updateFromAnswer but was not provided");
        }
        String prompt = DimensionPrompts.EXTRACT_DIMENSIONS_PROMPT.replace("{answer}", answer);
        String response = llmService.call(prompt);
        return parseExtractResponse(response);
    }

    private Mono<DimensionState> extractDimensionsAsync(String answer) {
        if (llmService == null) {
            return Mono.error(
                    new DimensionException(
                            "LlmDimensionService is required for updateFromAnswer but was not"
                                    + " provided"));
        }
        String prompt = DimensionPrompts.EXTRACT_DIMENSIONS_PROMPT.replace("{answer}", answer);
        return llmService.callAsync(prompt).map(this::parseExtractResponse);
    }

    private DimensionState parseExtractResponse(String response) {
        try {
            String json = extractJson(response);
            ExtractResult result = OBJECT_MAPPER.readValue(json, ExtractResult.class);
            return convertToDimensionState(result);
        } catch (JsonProcessingException e) {
            throw new DimensionException("Failed to parse dimension extraction response", e);
        }
    }

    private DimensionState convertToDimensionState(ExtractResult result) {
        DimensionState state = new DimensionState();

        if (result.timeDimensionType != null
                && result.timeDimensionValues != null
                && !result.timeDimensionValues.isEmpty()) {
            state.setTimeDimension(
                    new DimensionState.TimeDimension(
                            result.timeDimensionType, result.timeDimensionValues));
        }

        if (result.departments != null && !result.departments.isEmpty()) {
            state.setDepartments(result.departments);
        }

        if (result.peerDimensionType != null
                && result.peerDimensionValues != null
                && !result.peerDimensionValues.isEmpty()) {
            state.setPeerDimension(normalizePeerValues(result.peerDimensionType, result.peerDimensionValues));
        }

        if (result.persons != null && !result.persons.isEmpty()) {
            state.setPersons(result.persons);
        }

        return state;
    }

    /**
     * LLM 从回答中提取的 peer 值过一遍同义词表归一化（"风险组"→标准名），
     * 保证 DimensionState 里存的永远是标准名（指纹/缓存键稳定）。
     * 精确查表未命中或歧义时保留原值。
     */
    private DimensionState.PeerDimension normalizePeerValues(
            DimensionState.PeerDimensionType type, List<String> values) {
        if (aliasResolver == null) {
            return new DimensionState.PeerDimension(type, values);
        }
        List<String> normalized = new ArrayList<>(values.size());
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String trimmed = value.trim();
            normalized.add(aliasResolver.lookupExact(trimmed)
                    .map(AliasResolver.ResolvedAlias::standardName)
                    .orElse(trimmed));
        }
        return new DimensionState.PeerDimension(type, normalized);
    }

    // ==================== 步骤⑥：合并更新 ====================

    /**
     * 合并提取结果到当前状态：提取到新值的维度替换，未提取到的维度保留。
     */
    DimensionState mergeExtracted(DimensionState current, DimensionState extracted) {
        DimensionState merged = current != null ? current.deepCopy() : new DimensionState();

        // 时间维度：如果提取到新值，替换（二选一，新类型自动覆盖旧类型）
        if (extracted.getTimeDimension() != null && !extracted.getTimeDimension().isEmpty()) {
            merged.setTimeDimension(extracted.getTimeDimension());
        }

        // 部门：如果提取到新值，替换；否则保留
        if (extracted.getDepartments() != null && !extracted.getDepartments().isEmpty()) {
            merged.setDepartments(extracted.getDepartments());
        }

        // 业务同级维度：如果提取到新值，替换；否则保留
        if (extracted.getPeerDimension() != null && !extracted.getPeerDimension().isEmpty()) {
            merged.setPeerDimension(extracted.getPeerDimension());
        }

        // 人：如果提取到新值，替换；否则保留
        if (extracted.getPersons() != null && !extracted.getPersons().isEmpty()) {
            merged.setPersons(extracted.getPersons());
        }

        return merged;
    }

    // ==================== 工具方法 ====================

    /**
     * 从 LLM 响应中提取 JSON 内容（兼容 markdown 代码块包裹的情况）。
     */
    private String extractJson(String response) {
        String trimmed = response.trim();

        // 尝试提取 markdown 代码块中的 JSON
        if (trimmed.contains("```json")) {
            int start = trimmed.indexOf("```json") + 7;
            int end = trimmed.indexOf("```", start);
            if (end > start) {
                return trimmed.substring(start, end).trim();
            }
        }
        if (trimmed.contains("```")) {
            int start = trimmed.indexOf("```") + 3;
            int end = trimmed.indexOf("```", start);
            if (end > start) {
                return trimmed.substring(start, end).trim();
            }
        }

        // 尝试提取花括号包裹的 JSON
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }

        return trimmed;
    }

    /**
     * LLM 提取结果的内部反序列化结构。
     */
    private static class ExtractResult {
        public DimensionState.TimeDimensionType timeDimensionType;
        public List<String> timeDimensionValues;
        public List<String> departments;
        public DimensionState.PeerDimensionType peerDimensionType;
        public List<String> peerDimensionValues;
        public List<String> persons;
    }

    // ==================== P3：一对多歧义反问状态机（方案 §6） ====================

    /** 下一轮回复中的序号："1" / "1." / "第1个" / "第一个" */
    private static final Pattern CLARIFY_INDEX_REPLY =
            Pattern.compile("^第?\\s*([0-9０-９一二三四五六七八九十]{1,3})\\s*[个.、,，]?\\s*$");
    private static final String CN_NUMERALS = "一二三四五六七八九十";

    /**
     * 短路层入口：问题含同维度一对多歧义时登记 pendingClarification 并返回反问文本；
     * 无歧义（或 alias 关闭）返回 null，调用方按普通问题继续。
     *
     * <p>由 V2ChatStreamServiceImpl 在 agent 启动前调用；反问文本作为最终回答经
     * SSE "done" 事件下发，本轮不启 agent（零 token）。
     */
    public synchronized String startClarificationIfAmbiguous(String question) {
        if (aliasResolver == null || question == null || question.isBlank()) {
            return null;
        }
        QuestionAnalysis analysis = analyzeQuestionRuleBased(question);
        List<AliasResolver.AmbiguousAlias> ambiguous = analysis.getAmbiguousAliases();
        if (ambiguous == null || ambiguous.isEmpty()) {
            return null;
        }
        AliasResolver.AmbiguousAlias amb = ambiguous.get(0);
        if (currentState == null) {
            currentState = new DimensionState();
        }
        currentState.setPendingClarification(new DimensionState.PendingClarification(
                amb.dimension(), amb.alias(), List.copyOf(amb.candidates()), question));
        log.info("Clarification pending registered: alias={} dimension={} candidates={}",
                amb.alias(), amb.dimension(), amb.candidates());
        return buildClarifyText(amb.alias(), amb.dimension(), amb.candidates());
    }

    /**
     * 下一轮入口：存在待确认歧义时对用户回复做确定性匹配（方案 §6.3）。
     *
     * <ul>
     *   <li>命中（序号或回复包含候选名）→ peerDimension 写入选定标准名、清除 pending，
     *       返回 alias 位置替换为标准名后的原问题（本轮拿它正常跑 agent）；</li>
     *   <li>未命中 → 丢弃 pending 返回 null（宁可少问一次也不死循环反问）。</li>
     * </ul>
     */
    public synchronized String consumeClarification(String reply) {
        DimensionState.PendingClarification pending =
                currentState != null ? currentState.getPendingClarification() : null;
        if (pending == null || pending.getCandidates() == null || pending.getCandidates().isEmpty()) {
            return null;
        }
        String chosen = matchClarificationReply(reply, pending.getCandidates());
        // 无论命中与否都清除 pending：命中则落地，未命中则按普通问题放行
        currentState.setPendingClarification(null);
        if (chosen == null) {
            log.info("Clarification reply not matched, pending dropped: reply={}", reply);
            return null;
        }
        currentState.setPeerDimension(
                new DimensionState.PeerDimension(pending.getDimension(), List.of(chosen)));
        String alias = pending.getAlias();
        String original = pending.getOriginalQuestion();
        String resolved = (original != null && alias != null && !alias.isBlank() && original.contains(alias))
                ? original.replaceFirst(Pattern.quote(alias), Matcher.quoteReplacement(chosen))
                : original;
        // 一次性回填记录：本轮 agent 的中间件按问题文本精确匹配后补回 peer
        this.clarifiedPeerQuestion = resolved;
        this.clarifiedPeer = new DimensionState.PeerDimension(pending.getDimension(), List.of(chosen));
        log.info("Clarification resolved: alias={} -> {} (dimension={})", alias, chosen, pending.getDimension());
        return resolved;
    }

    /** 反问文本格式（方案 §6.2）：候选顺序即序号顺序。 */
    static String buildClarifyText(
            String alias, DimensionState.PeerDimensionType dimension, List<String> candidates) {
        StringBuilder sb = new StringBuilder()
                .append("『").append(alias).append("』在")
                .append(dimensionLabel(dimension)).append("维度有多个匹配，请确认具体指哪一个：\n");
        for (int i = 0; i < candidates.size(); i++) {
            sb.append(i + 1).append(". ").append(candidates.get(i)).append("\n");
        }
        sb.append("（回复序号或名称即可）");
        return sb.toString();
    }

    private static String dimensionLabel(DimensionState.PeerDimensionType type) {
        return switch (type) {
            case TEAM -> "组";
            case APPLICATION -> "应用";
            case PRODUCT_LINE -> "产品线";
            case REQUIREMENT -> "需求项";
            case SUB_REQUIREMENT -> "需求子条目";
        };
    }

    /** 序号优先，其次回复包含候选名（含即命中，取第一个命中的候选）。 */
    static String matchClarificationReply(String reply, List<String> candidates) {
        if (reply == null || reply.isBlank()) {
            return null;
        }
        String trimmed = reply.trim();
        Matcher m = CLARIFY_INDEX_REPLY.matcher(trimmed);
        if (m.matches()) {
            Integer idx = parseOrdinal(m.group(1));
            if (idx != null && idx >= 1 && idx <= candidates.size()) {
                return candidates.get(idx - 1);
            }
            return null;
        }
        for (String candidate : candidates) {
            if (candidate != null && trimmed.contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** "1"/"１"/"一" → 1；解析失败返回 null（含"十"的复合数词不作为序号，按文本匹配兜底）。 */
    private static Integer parseOrdinal(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        if (raw.length() == 1) {
            char c = raw.charAt(0);
            if (c >= '0' && c <= '9') {
                return c - '0';
            }
            if (c >= '０' && c <= '９') {
                return c - '０';
            }
            int cn = CN_NUMERALS.indexOf(c);
            return cn >= 0 ? cn + 1 : null;
        }
        try {
            int n = 0;
            for (char c : raw.toCharArray()) {
                if (c >= '0' && c <= '9') {
                    n = n * 10 + (c - '0');
                } else if (c >= '０' && c <= '９') {
                    n = n * 10 + (c - '０');
                } else {
                    return null;
                }
            }
            return n;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
