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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
    private DimensionState currentState;

    /**
     * 创建维度状态管理器。
     *
     * @param llmService LLM 维度分析服务，可为 null（仅 {@link #updateFromAnswer} 需要）
     */
    public DimensionStateManager(LlmDimensionService llmService) {
        this(llmService, null);
    }

    /**
     * @param aliasResolver 同义词表解析器，可为 null（null 时口语化词识别关闭，仅正则兜底）
     */
    public DimensionStateManager(LlmDimensionService llmService, AliasResolver aliasResolver) {
        this.llmService = llmService;
        this.aliasResolver = aliasResolver;
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
        if (inherited != null && ctx != null) {
            ctx.put(STATE_KEY, DimensionState.class, inherited);
        }
        return new ProcessResult(enriched, inherited, analysis.getAliasResolution().resolved());
    }

    /** processQuestionInContext 的返回值。resolvedAliases 是本轮同义词表解析出的口语词→标准名映射。 */
    public record ProcessResult(
            String enrichedQuestion,
            DimensionState newState,
            List<AliasResolver.ResolvedAlias> resolvedAliases) {}

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

    // 年份前缀可选：无年份时由调用方补当前年（"4月份版本" → "2026年4月份版本"）
    private static final Pattern EXPLICIT_VERSION = Pattern.compile("(?:(\\d{4})年)?(\\d{1,2})月份版本");
    private static final Pattern EXPLICIT_QUARTER = Pattern.compile("(?:(\\d{4})年)?(\\d{1,2})季度");
    private static final Pattern EXPLICIT_MONTH = Pattern.compile("(?:(\\d{4})年)?(\\d{1,2})月(?!份)");
    // Q1/Q2/Q3/Q4 / 一季度 / 二季度 alias — normalised to "{year}年{n}季度" for fingerprint stability
    private static final Pattern EXPLICIT_QUARTER_ALIAS =
            Pattern.compile("(?:Q|q)([1-4])|([一二三四])季度");
    // KNOWLEDGE.md §部门 标准枚举 — 漏掉非"杭州开发X部"的会让用户连问 N 次也触发不了同指纹。
    // 别名按"长前短后"排列，避免"杭州服务支持部"被"服务支持"截胡；命中后经 normalizeDepartment 映射回标准名
    private static final Pattern EXPLICIT_DEPT =
            Pattern.compile(
                    "(杭州开发[一二三四五]部|杭州服务支持部|杭州技术部|云计算实验室"
                            + "|杭州[一二三四五]部|[一二三四五]部"
                            + "|服务支持部|服务支持|技术部|云计算|实验室)");

    /** 部门简称 → KNOWLEDGE.md 标准名，保证与全称写法生成同一指纹 */
    private static String normalizeDepartment(String matched) {
        return switch (matched) {
            case "杭州一部", "一部" -> "杭州开发一部";
            case "杭州二部", "二部" -> "杭州开发二部";
            case "杭州三部", "三部" -> "杭州开发三部";
            case "杭州四部", "四部" -> "杭州开发四部";
            case "杭州五部", "五部" -> "杭州开发五部";
            case "技术部" -> "杭州技术部";
            case "服务支持部", "服务支持" -> "杭州服务支持部";
            case "云计算", "实验室" -> "云计算实验室";
            default -> matched;
        };
    }
    // 应用 F-XXX / 群组 F-XXX-XXX — 同一正则吃下,后者只是更长
    private static final Pattern EXPLICIT_APP = Pattern.compile("(F-[A-Za-z][A-Za-z0-9-]*)");
    // 小组:KNOWLEDGE.md 举例都 5+ 字(如"金融市场自营测试组"),放宽到 {2,}
    private static final Pattern EXPLICIT_TEAM = Pattern.compile("([一-龥A-Za-z0-9]{2,}组)");
    // 产品线:同上放宽,并补常见非"...产品线"后缀的固定名(普惠金融 / 代理国库等)
    private static final Pattern EXPLICIT_PRODUCT_LINE =
            Pattern.compile(
                    "([一-龥]{2,}产品线|普惠金融|代理国库|代理财政|代理同业|交行输出"
                            + "|全球市场风险管理应用|金融市场共享数据服务)");
    // 需求项:之前抽的是"XX需求项"前 2 字,真正的 itemNo 格式如 I20260208-0005 反而没匹配
    private static final Pattern EXPLICIT_REQUIREMENT = Pattern.compile("(I\\d{8}-\\d{4})");

    public QuestionAnalysis analyzeQuestionRuleBased(String userQuestion) {
        QuestionAnalysis analysis = new QuestionAnalysis();

        // 0. 同义词表解析（aliasResolver 为 null 时返回空结果，退化为纯正则）
        AliasResolver.AliasResolution aliasRes =
                aliasResolver != null && userQuestion != null && !userQuestion.isBlank()
                        ? aliasResolver.resolve(userQuestion)
                        : AliasResolver.AliasResolution.EMPTY;
        boolean aliasPeerHit = !aliasRes.resolved().isEmpty() || !aliasRes.ambiguous().isEmpty();
        if (!aliasRes.ambiguous().isEmpty()) {
            analysis.setAmbiguousAliases(aliasRes.ambiguous());
        }
        analysis.setAliasResolution(aliasRes);

        // 1. detect reference
        QuestionAnalysis.ReferenceType refType = detectReferenceType(userQuestion);
        analysis.setHasReference(refType != null);
        analysis.setReferenceType(refType);

        // 2. detect level
        QuestionAnalysis.QuestionLevel level = detectLevel(userQuestion, refType, aliasPeerHit);
        analysis.setLevel(level);
        analysis.setCauseAnalysis(level == QuestionAnalysis.QuestionLevel.CAUSE);

        // 3. extract explicit dimensions
        QuestionAnalysis.ExplicitDimensions explicit = extractExplicitDimensions(userQuestion, aliasRes);
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
        return null;
    }

    private QuestionAnalysis.QuestionLevel detectLevel(
            String q, QuestionAnalysis.ReferenceType refType, boolean aliasPeerHit) {
        if (q.matches(".*原因.*") || q.matches(".*为什么.*"))
            return QuestionAnalysis.QuestionLevel.CAUSE;
        if (q.matches(".*过去.*") || q.matches(".*趋势.*") || q.matches(".*历史.*"))
            return QuestionAnalysis.QuestionLevel.TIME_RANGE;
        if (q.matches(".*谁.*")) return QuestionAnalysis.QuestionLevel.PERSON;
        if (refType == QuestionAnalysis.ReferenceType.TEAM
                || refType == QuestionAnalysis.ReferenceType.APPLICATION
                || refType == QuestionAnalysis.ReferenceType.PRODUCT_LINE
                || refType == QuestionAnalysis.ReferenceType.REQUIREMENT
                || aliasPeerHit
                || EXPLICIT_APP.matcher(q).find()
                || EXPLICIT_TEAM.matcher(q).find()
                || EXPLICIT_PRODUCT_LINE.matcher(q).find()
                || EXPLICIT_REQUIREMENT.matcher(q).find())
            return QuestionAnalysis.QuestionLevel.PEER;
        if (q.contains("部门") || EXPLICIT_DEPT.matcher(q).find())
            return QuestionAnalysis.QuestionLevel.DEPARTMENT;
        if (refType == QuestionAnalysis.ReferenceType.DEPARTMENT)
            return QuestionAnalysis.QuestionLevel.DEPARTMENT;
        if (EXPLICIT_VERSION.matcher(q).find()
                || EXPLICIT_MONTH.matcher(q).find()
                || EXPLICIT_QUARTER.matcher(q).find()
                || refType == QuestionAnalysis.ReferenceType.TIME)
            return QuestionAnalysis.QuestionLevel.TIME;
        // No dimensions detected — non-dimension question (e.g. "数据服务表清单下载")
        return null;
    }

    private QuestionAnalysis.ExplicitDimensions extractExplicitDimensions(
            String q, AliasResolver.AliasResolution aliasRes) {
        QuestionAnalysis.ExplicitDimensions explicit = new QuestionAnalysis.ExplicitDimensions();
        int year = LocalDate.now().getYear();

        // 时间维度：各写法归一化后多值收集（"4月版和5月版" → 两个版本计划）。
        // 带年份前缀用前缀年（"2025年4月"），否则补当前年。全部写法归一为完整格式，
        // 不同写法生成同一指纹，auto-synth 计数才能累积。
        // VERSION 与 QUARTER 同现时 VERSION 优先 — TimeDimension 单类型，混用只保一。
        Set<String> versions = new LinkedHashSet<>();
        Set<String> quarters = new LinkedHashSet<>();
        String currentYear = String.valueOf(year);

        Matcher versionMatcher = EXPLICIT_VERSION.matcher(q);
        while (versionMatcher.find()) {
            String y = versionMatcher.group(1) != null ? versionMatcher.group(1) : currentYear;
            versions.add(y + "年" + versionMatcher.group(2) + "月份版本");
        }

        Matcher quarterMatcher = EXPLICIT_QUARTER.matcher(q);
        while (quarterMatcher.find()) {
            String y = quarterMatcher.group(1) != null ? quarterMatcher.group(1) : currentYear;
            quarters.add(y + "年" + quarterMatcher.group(2) + "季度");
        }

        // Short month format: "4月" → construct full version（"4月份版本"已被上面吃掉）
        Matcher monthMatcher = EXPLICIT_MONTH.matcher(q);
        while (monthMatcher.find()) {
            String y = monthMatcher.group(1) != null ? monthMatcher.group(1) : currentYear;
            versions.add(y + "年" + monthMatcher.group(2) + "月份版本");
        }

        // Quarter aliases: "Q1" / "q1" / "一季度" — normalise so user shorthand maps to the same
        // fingerprint as "2026年1季度". Without this, "Q1 杭一部" and "1季度 杭一部" are different
        // candidates and the auto-synth counter never accumulates.
        Matcher aliasMatcher = EXPLICIT_QUARTER_ALIAS.matcher(q);
        while (aliasMatcher.find()) {
            String qNum = aliasMatcher.group(1) != null
                    ? aliasMatcher.group(1) // Q1-Q4
                    : switch (aliasMatcher.group(2)) {
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
        if (!departments.isEmpty()) {
            explicit.setDepartments(new ArrayList<>(departments));
        }

        // 应用（F-xxx）— 优先级最高：alias 解析出组/产品线但问题同时含 F-xxx 编码时，
        // 保持既有 app > 组/产品线 的优先序，alias 结果让位（歧义项仍记录，供短路反问）
        Matcher appMatcher = EXPLICIT_APP.matcher(q);
        if (appMatcher.find()) {
            explicit.setPeerDimension(
                    new DimensionState.PeerDimension(
                            DimensionState.PeerDimensionType.APPLICATION,
                            List.of(appMatcher.group())));
        }

        // 同义词表解析结果（标准名直接进状态，保证指纹稳定）— 优先于小组/产品线正则
        if (explicit.getPeerDimension() == null) {
            DimensionState.PeerDimension aliasPeer = buildAliasPeerDimension(aliasRes);
            if (aliasPeer != null) {
                explicit.setPeerDimension(aliasPeer);
            }
        }

        // 组正则兜底（alias 完全未命中时才跑，防止"风险组"这类歧义词被小组正则抢注）
        // — 排除指代词匹配（"这个组"、"那个组"）
        if (explicit.getPeerDimension() == null && !aliasHit(aliasRes)) {
            Matcher teamMatcher = EXPLICIT_TEAM.matcher(q);
            if (teamMatcher.find()) {
                String matched = teamMatcher.group(1);
                if (!matched.startsWith("这个") && !matched.startsWith("那个")) {
                    explicit.setPeerDimension(
                            new DimensionState.PeerDimension(
                                    DimensionState.PeerDimensionType.TEAM,
                                    List.of(teamMatcher.group())));
                }
            }
        }

        // 产品线（优先级低于组和应用）— 同上，alias 命中时跳过；排除指代词匹配
        if (explicit.getPeerDimension() == null && !aliasHit(aliasRes)) {
            Matcher plMatcher = EXPLICIT_PRODUCT_LINE.matcher(q);
            if (plMatcher.find()) {
                String matched = plMatcher.group(1);
                if (!matched.startsWith("这个") && !matched.startsWith("那个")) {
                    explicit.setPeerDimension(
                            new DimensionState.PeerDimension(
                                    DimensionState.PeerDimensionType.PRODUCT_LINE,
                                    List.of(plMatcher.group())));
                }
            }
        }

        // 需求项 — 真正的 itemNo 格式 I20260208-0005（alias 命中时同样跳过）
        if (explicit.getPeerDimension() == null && !aliasHit(aliasRes)) {
            Matcher reqMatcher = EXPLICIT_REQUIREMENT.matcher(q);
            if (reqMatcher.find()) {
                explicit.setPeerDimension(
                        new DimensionState.PeerDimension(
                                DimensionState.PeerDimensionType.REQUIREMENT,
                                List.of(reqMatcher.group())));
            }
        }

        return explicit;
    }

    /** alias 表是否命中过该问题（含歧义命中）——命中即禁用小组/产品线/需求项正则兜底。 */
    private static boolean aliasHit(AliasResolver.AliasResolution aliasRes) {
        return aliasRes != null
                && (!aliasRes.resolved().isEmpty() || !aliasRes.ambiguous().isEmpty());
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
