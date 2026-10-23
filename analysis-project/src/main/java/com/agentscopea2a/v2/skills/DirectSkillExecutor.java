/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.agentscopea2a.v2.skills;

import com.agentscopea2a.mapper.gauss.ScriptRegistryMapper;
import com.agentscopea2a.v2.dimension.DimensionKeyConfig;
import com.agentscopea2a.v2.dimension.DimensionKeyConfigRepository;
import com.agentscopea2a.v2.dimension.DimensionState;
import com.agentscopea2a.v2.dimension.DimensionStateManager;
import com.agentscopea2a.v2.dimension.TimeRangeExtractor;
import com.agentscopea2a.v2.dimension.TimeRangeResolver;
import com.agentscopea2a.v2.hooks.VersionPlanKnowledgeHook;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Skill 直接执行 (绕过 LLM) 的确定性判定器 (docs/history/skill-direct-exec-plan.md §2)。
 *
 * <p>内网 LLM 基本不可用, 配置了 {@code script_id} 的 skill 命中 top-1 时由 Java 直接
 * 调 script_exec 脚本。{@link #plan} 是纯判定: 任何一环不满足都返回 null, 调用方回退
 * 原 LLM 链路 (目录 + load_skill + script_exec), 行为不变。
 *
 * <p>参数组装两层结构 (2026/10/13 定稿):
 * <ol>
 *   <li>Layer 1 标准化提取体 {@link DimensionExtraction}: 固定 schema 全键输出
 *       (问题未提及的维度 = 空数组), 由 {@link DimensionStateManager#previewState}
 *       的只读预览归一化 (别名/简称/版本缺省都在这层消化);</li>
 *   <li>Layer 2 {@code param_mapping} (skill_routing_metadata.param_mapping JSON):
 *       key = 脚本参数名 (原样透传, 可不规则), value ∈ {@link #DIMENSION_KEYS}。
 *       填参规则唯一一条: {@code params[param] = extraction[key]}。</li>
 * </ol>
 *
 * <p>拍板口径 (2026/10/13): 候选非空即执行 top-1 (不区分精确/宽泛档); version/quarter
 * 缺失用当前在建版本补默认值; 脚本执行失败直接报错不回退。
 *
 * <p>缺参反问 (2026/10/15, docs/202610/Skill直接执行缺参反问方案.md): 必填参数缺失且在
 * param_mapping 内 (有维度语义) → 反问用户补参 (pending 按 userId:conversationId 存,
 * 一次只问一个, TTL 5min), 补齐后继续直接执行; 不在 mapping 内 (语义未知) 或调用方未传
 * 会话键 (3 参旧签名) → 维持回退 LLM。回答解析不出对应维度值 → 丢弃 pending 走正常链路。
 *
 * <p>预览不写状态: {@link #plan} 只读; 是否正式写维度状态 (processQuestionInContext)
 * 由 V2ChatStreamServiceImpl 决定短路后执行, 避免回退 LLM 时状态被解析两次
 * (特别是 clarifiedPeer 一次性回填被吃掉)。
 */
public class DirectSkillExecutor {

    private static final Logger log = LoggerFactory.getLogger(DirectSkillExecutor.class);

    private static final TypeReference<Map<String, String>> PARAM_MAPPING_TYPE =
            new TypeReference<>() {};

    /** Layer 1 固定维度键枚举 (param_mapping value 的合法取值, 管理页保存期校验同源)。
     *  perNo = 统一认证号 (人员台账维度方案 §3.4, 执行期解析不进指纹)。 */
    public static final Set<String> DIMENSION_KEYS = Set.of(
            "versionPlan", "vestQuarter", "deptName", "groupName",
            "appName", "productLine", "itemNo", "subItemNo", "perName", "perNo");

    /** 上级组织动态键 (2026/10/22): dimension_key_config orgName 行 (杭研/研发部/基地等
     *  变体 → 标准值「杭研」, 页面可加)。命中时缺部门不反问 (上级组织已是部门的上层口径)。 */
    public static final String ORG_DIMENSION_KEY = "orgName";

    private final SkillRoutingMetadataRepository metadataRepository;
    private final SkillCandidateSelector candidateSelector;
    private final SkillUsageResolver usageResolver;
    private final SkillIndexRepository indexRepo;
    private final ScriptRegistryMapper scriptRegistryMapper;
    private final DimensionStateManager dimensionStateManager;
    private final ObjectProvider<VersionPlanKnowledgeHook> versionPlanHookProvider;
    private final ObjectProvider<DimensionKeyConfigRepository> dimensionKeyRepoProvider;
    private final ObjectProvider<SkillBypassKeywordService> bypassKeywordServiceProvider;
    private final ObjectProvider<com.agentscopea2a.v2.dimension.VersionPlanAnchorResolver> anchorResolverProvider;
    private final ObjectProvider<com.agentscopea2a.v2.dimension.PersonalDimensionResolver> personalResolverProvider;
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    public DirectSkillExecutor(
            SkillRoutingMetadataRepository metadataRepository,
            SkillCandidateSelector candidateSelector,
            SkillUsageResolver usageResolver,
            SkillIndexRepository indexRepo,
            ScriptRegistryMapper scriptRegistryMapper,
            DimensionStateManager dimensionStateManager,
            ObjectProvider<VersionPlanKnowledgeHook> versionPlanHookProvider,
            ObjectProvider<DimensionKeyConfigRepository> dimensionKeyRepoProvider,
            ObjectMapper objectMapper,
            boolean enabled) {
        this(metadataRepository, candidateSelector, usageResolver, indexRepo, scriptRegistryMapper,
                dimensionStateManager, versionPlanHookProvider, dimensionKeyRepoProvider,
                null, null, null, objectMapper, enabled);
    }

    public DirectSkillExecutor(
            SkillRoutingMetadataRepository metadataRepository,
            SkillCandidateSelector candidateSelector,
            SkillUsageResolver usageResolver,
            SkillIndexRepository indexRepo,
            ScriptRegistryMapper scriptRegistryMapper,
            DimensionStateManager dimensionStateManager,
            ObjectProvider<VersionPlanKnowledgeHook> versionPlanHookProvider,
            ObjectProvider<DimensionKeyConfigRepository> dimensionKeyRepoProvider,
            ObjectProvider<SkillBypassKeywordService> bypassKeywordServiceProvider,
            ObjectMapper objectMapper,
            boolean enabled) {
        this(metadataRepository, candidateSelector, usageResolver, indexRepo, scriptRegistryMapper,
                dimensionStateManager, versionPlanHookProvider, dimensionKeyRepoProvider,
                bypassKeywordServiceProvider, null, null, objectMapper, enabled);
    }

    public DirectSkillExecutor(
            SkillRoutingMetadataRepository metadataRepository,
            SkillCandidateSelector candidateSelector,
            SkillUsageResolver usageResolver,
            SkillIndexRepository indexRepo,
            ScriptRegistryMapper scriptRegistryMapper,
            DimensionStateManager dimensionStateManager,
            ObjectProvider<VersionPlanKnowledgeHook> versionPlanHookProvider,
            ObjectProvider<DimensionKeyConfigRepository> dimensionKeyRepoProvider,
            ObjectProvider<SkillBypassKeywordService> bypassKeywordServiceProvider,
            ObjectProvider<com.agentscopea2a.v2.dimension.VersionPlanAnchorResolver> anchorResolverProvider,
            ObjectMapper objectMapper,
            boolean enabled) {
        this(metadataRepository, candidateSelector, usageResolver, indexRepo, scriptRegistryMapper,
                dimensionStateManager, versionPlanHookProvider, dimensionKeyRepoProvider,
                bypassKeywordServiceProvider, anchorResolverProvider, null, objectMapper, enabled);
    }

    public DirectSkillExecutor(
            SkillRoutingMetadataRepository metadataRepository,
            SkillCandidateSelector candidateSelector,
            SkillUsageResolver usageResolver,
            SkillIndexRepository indexRepo,
            ScriptRegistryMapper scriptRegistryMapper,
            DimensionStateManager dimensionStateManager,
            ObjectProvider<VersionPlanKnowledgeHook> versionPlanHookProvider,
            ObjectProvider<DimensionKeyConfigRepository> dimensionKeyRepoProvider,
            ObjectProvider<SkillBypassKeywordService> bypassKeywordServiceProvider,
            ObjectProvider<com.agentscopea2a.v2.dimension.VersionPlanAnchorResolver> anchorResolverProvider,
            ObjectProvider<com.agentscopea2a.v2.dimension.PersonalDimensionResolver> personalResolverProvider,
            ObjectMapper objectMapper,
            boolean enabled) {
        this.metadataRepository = metadataRepository;
        this.candidateSelector = candidateSelector;
        this.usageResolver = usageResolver;
        this.indexRepo = indexRepo;
        this.scriptRegistryMapper = scriptRegistryMapper;
        this.dimensionStateManager = dimensionStateManager;
        this.versionPlanHookProvider = versionPlanHookProvider;
        this.dimensionKeyRepoProvider = dimensionKeyRepoProvider;
        this.bypassKeywordServiceProvider = bypassKeywordServiceProvider;
        this.anchorResolverProvider = anchorResolverProvider;
        this.personalResolverProvider = personalResolverProvider;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
    }

    /** Layer 1 标准化「维度+指标」提取体: 固定 schema 全键输出, 空数组 = 问题未提及。
     *  extraDimensions = 动态维度键 (dimension_key_config 词表命中, §9), 仅当前问题提取。
     *  perNo = 统一认证号 (人员台账维度方案 §3.4): "我"→ChatRequest.userId, 人名→Q2 解析;
     *  执行期解析, 不进 DimensionState 不进指纹。 */
    public record DimensionExtraction(
            List<String> versionPlan,
            List<String> vestQuarter,
            List<String> deptName,
            List<String> groupName,
            List<String> appName,
            List<String> productLine,
            List<String> itemNo,
            List<String> subItemNo,
            List<String> perName,
            List<String> perNo,
            List<String> metrics,
            String matchedSkill,
            Map<String, List<String>> extraDimensions) {

        public DimensionExtraction {
            versionPlan = safe(versionPlan);
            vestQuarter = safe(vestQuarter);
            deptName = safe(deptName);
            groupName = safe(groupName);
            appName = safe(appName);
            productLine = safe(productLine);
            itemNo = safe(itemNo);
            subItemNo = safe(subItemNo);
            perName = safe(perName);
            perNo = safe(perNo);
            metrics = safe(metrics);
            extraDimensions = extraDimensions == null ? Map.of() : Map.copyOf(extraDimensions);
        }

        /** Legacy 11-arg shape (no perNo/dynamic keys) for existing call sites. */
        public DimensionExtraction(
                List<String> versionPlan, List<String> vestQuarter, List<String> deptName,
                List<String> groupName, List<String> appName, List<String> productLine,
                List<String> itemNo, List<String> subItemNo, List<String> perName,
                List<String> metrics, String matchedSkill) {
            this(versionPlan, vestQuarter, deptName, groupName, appName, productLine,
                    itemNo, subItemNo, perName, List.of(), metrics, matchedSkill, Map.of());
        }

        /** Legacy 12-arg shape (no perNo, with dynamic keys) for existing call sites. */
        public DimensionExtraction(
                List<String> versionPlan, List<String> vestQuarter, List<String> deptName,
                List<String> groupName, List<String> appName, List<String> productLine,
                List<String> itemNo, List<String> subItemNo, List<String> perName,
                List<String> metrics, String matchedSkill,
                Map<String, List<String>> extraDimensions) {
            this(versionPlan, vestQuarter, deptName, groupName, appName, productLine,
                    itemNo, subItemNo, perName, List.of(), metrics, matchedSkill, extraDimensions);
        }

        /** Legacy 10-arg shape (no dynamic keys) for existing call sites. */
        public DimensionExtraction(
                List<String> versionPlan, List<String> vestQuarter, List<String> deptName,
                List<String> groupName, List<String> appName, List<String> productLine,
                List<String> itemNo, List<String> perName, List<String> metrics, String matchedSkill) {
            this(versionPlan, vestQuarter, deptName, groupName, appName, productLine,
                    itemNo, List.of(), perName, metrics, matchedSkill);
        }

        private static List<String> safe(List<String> value) {
            return value == null ? List.of() : List.copyOf(value);
        }
    }

    /**
     * 直接执行计划; params 已按 param_mapping 填好 (单值 String / 多值 List)。
     * missingAsk 非空 = 缺必填参数待反问 (当前只含一个维度键 label, 口径: 一次只问一个),
     * 调用方短路下发 {@link #askPrompt} 文本; 空 = 正常执行计划。
     * askMessage 非空 = 自定义反问全文 (歧义反问, 时间维度方案 §3.3), askPrompt 优先返回它。
     */
    public record DirectExecutionPlan(
            DimensionExtraction extraction,
            String skillName,
            String scriptId,
            Map<String, Object> params,
            List<String> missingAsk,
            String askMessage) {

        public DirectExecutionPlan {
            missingAsk = missingAsk == null ? List.of() : List.copyOf(missingAsk);
        }

        /** Legacy 5-arg shape (no custom askMessage) for existing call sites/tests. */
        public DirectExecutionPlan(DimensionExtraction extraction, String skillName,
                                   String scriptId, Map<String, Object> params,
                                   List<String> missingAsk) {
            this(extraction, skillName, scriptId, params, missingAsk, null);
        }

        /** Legacy 4-arg shape (no missingAsk) for existing call sites/tests. */
        public DirectExecutionPlan(DimensionExtraction extraction, String skillName,
                                   String scriptId, Map<String, Object> params) {
            this(extraction, skillName, scriptId, params, List.of());
        }

        public boolean needsAsk() { return !missingAsk.isEmpty(); }
    }

    /** 反问文案 (方案 §4.2): 调用方两链路共用, 集中在这里免漂移; 示例值按维度键 label 给固定提示。
     *  歧义反问 (时间维度方案 §3.3) 携带自定义 askMessage, 原样下发。 */
    public static String askPrompt(DirectExecutionPlan plan) {
        if (plan.askMessage() != null && !plan.askMessage().isBlank()) {
            return plan.askMessage();
        }
        String label = plan.missingAsk().isEmpty() ? "" : plan.missingAsk().get(0);
        String example = ASK_EXAMPLES.getOrDefault(label, label);
        return "已匹配技能「" + plan.skillName() + "」，还需要补充：" + label
                + "。请直接回复，如：" + example;
    }

    private static final Map<String, String> ASK_EXAMPLES = Map.of(
            "部门", "杭州开发一部",
            "组（团队）", "一组",
            "应用", "xx应用",
            "产品线", "xx产品线",
            "需求项", "REQ-1001",
            "人", "张三",
            "统一认证号", "统一认证号（工号）");

    /** params_schema 元素 (direct-exec-param-type-plan.md §2.1): type 仅装配关心 "array",
     *  其余值/未写视为标量。包私有便于单测构造。 */
    record ParamSpec(String name, boolean required, String type) {}

    /** 人名重名反问 pending 标记前缀 (人员台账维度方案 §4.2): missingKeys[0] 形如
     *  "perNo@person:张三", 补参轮按序号/部门/统一认证号匹配。 */
    static final String PERSON_MARKER_PREFIX = "perNo@person:";

    /** 缺参反问 pending (方案 §4.1): key=userId:conversationId, TTL 5min, 取值时惰性清理。
     *  包私有便于单测构造过期条目。pendingAnchors 非空 = 歧义反问 (missingKeys[0] 形如
     *  "timeRange@AMBIGUOUS:..."), 保存原始锚点列表, 补参轮把歧义锚点原位替换成确定性锚点。
     *  schema 挂进去避免补参轮重查 registry (direct-exec-param-type-plan.md §2.3)。 */
    record PendingDirectExec(String skillName, String scriptId,
                             Map<String, String> mapping, List<ParamSpec> schema,
                             Map<String, Object> filledParams,
                             List<String> missingKeys, long createdAt,
                             List<String> pendingAnchors) {}

    private PendingDirectExec pendingOf(String skillName, String scriptId,
                                        Map<String, String> mapping, List<ParamSpec> schema,
                                        Map<String, Object> filledParams,
                                        List<String> missingKeys) {
        return new PendingDirectExec(skillName, scriptId, mapping, schema, filledParams, missingKeys,
                System.currentTimeMillis(), null);
    }

    private final java.util.concurrent.ConcurrentHashMap<String, PendingDirectExec> pending =
            new java.util.concurrent.ConcurrentHashMap<>();

    static final long PENDING_TTL_MS = 5 * 60 * 1000L;

    private static String sessionKey(String userId, String conversationId) {
        return (userId == null || userId.isBlank() ? "__anon__" : userId) + ":" + conversationId;
    }

    // ==================== LastDirectExec 快照 (docs/202610/skill-direct-exec-followup-plan.md §3.1) ====================
    // 追问轮走 LLM (v2 拍板): Java 不做执行决策, 只把「上一轮直接执行了什么、用了什么参数、
    // 结果摘要」补给给 LLM (hint 块 + 目录并入)。question 原文是 hint 块里唯一带指标语义的字段。

    /** 快照 TTL: 追问的自然间隔比缺参补参长, 30min (拍板点 D3)。 */
    static final long LAST_EXEC_TTL_MS = 30 * 60 * 1000L;

    /** resultDigest 截断长度 (拍板点 D2b): markdown 表格前缀截断保住表头+前几行。 */
    static final int RESULT_DIGEST_MAX_LENGTH = 2000;

    /**
     * 上次直接执行快照: plan() 返回可执行计划时记录 question/skill/params;
     * runDirectExecution 成功后 {@link #recordResult} 回填 resultDigest 并刷新 createdAt。
     */
    public record LastDirectExec(String question, String skillName, String scriptId,
                                 Map<String, Object> params, String resultDigest, long createdAt) {}

    private final java.util.concurrent.ConcurrentHashMap<String, LastDirectExec> lastExecStore =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 查询口 (visibility filter / context hook 消费): TTL 过期返回 null 并惰性清理;
     *  conversationId 为空 → null (与 pending 同规则)。 */
    public LastDirectExec lastExec(String userId, String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return null;
        }
        String key = sessionKey(userId, conversationId);
        LastDirectExec snapshot = lastExecStore.get(key);
        if (snapshot == null) {
            return null;
        }
        if (System.currentTimeMillis() - snapshot.createdAt() > LAST_EXEC_TTL_MS) {
            lastExecStore.remove(key, snapshot);
            return null;
        }
        return snapshot;
    }

    private void recordLastExec(String question, String userId, String conversationId,
                                String skillName, String scriptId, Map<String, Object> params) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        recordLastExec(sessionKey(userId, conversationId), question, skillName, scriptId, params);
    }

    private void recordLastExec(String sessionKey, String question,
                                String skillName, String scriptId, Map<String, Object> params) {
        lastExecStore.put(sessionKey,
                new LastDirectExec(question, skillName, scriptId,
                        params == null ? Map.of() : java.util.Collections.unmodifiableMap(
                                new LinkedHashMap<>(params)),
                        null, System.currentTimeMillis()));
    }

    /** runDirectExecution 成功后回填 stdout 摘要并刷新 TTL (§3.1 记录时机第 2 步)。无快照则 no-op。 */
    public void recordResult(String userId, String conversationId, String stdout) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        String key = sessionKey(userId, conversationId);
        LastDirectExec current = lastExecStore.get(key);
        if (current == null) {
            return;
        }
        lastExecStore.replace(key, current, new LastDirectExec(current.question(), current.skillName(),
                current.scriptId(), current.params(), truncateDigest(stdout), System.currentTimeMillis()));
    }

    static String truncateDigest(String stdout) {
        if (stdout == null || stdout.isBlank()) {
            return null;
        }
        String trimmed = stdout.strip();
        if (trimmed.length() <= RESULT_DIGEST_MAX_LENGTH) {
            return trimmed;
        }
        return trimmed.substring(0, RESULT_DIGEST_MAX_LENGTH) + "…(已截断)";
    }

    /**
     * 纯判定, 无副作用; null = 不短路, 走 LLM。
     *
     * @param question      用户本轮问题原文
     * @param userId        当前用户 (权限过滤)
     * @param previousState 上一轮维度状态 (调用方从 RuntimeContext 的 dimensionState 键取)
     */
    public DirectExecutionPlan plan(String question, String userId, DimensionState previousState) {
        // 3 参旧签名: 无会话键 = 不启用 pending, 缺参维持回退 LLM (存量行为不变)
        return plan(question, userId, previousState, null);
    }

    /**
     * 带会话键的判定 (方案 §4.1): conversationId 非空时启用缺参反问 pending。
     * pending 消费优先于正常判定链 (补参轮不重跑 skill 匹配, skill/scriptId 均来自
     * pending 原样透传, 首轮已过 canUse)。
     */
    public DirectExecutionPlan plan(String question, String userId,
                                    DimensionState previousState, String conversationId) {
        if (!enabled || question == null || question.isBlank()) {
            return null;
        }
        if (conversationId != null) {
            DirectExecutionPlan resumed = consumePending(question, userId, previousState, conversationId);
            if (resumed != null) {
                return resumed;
            }
        }
        if (!enabled || question == null || question.isBlank()) {
            return null;
        }
        // 检索旁路 (§8 拍板①): 与 SkillVectorIndexVisibilityFilter 同一判定, 必须两处同时拦截。
        if (bypassKeywordServiceProvider != null) {
            SkillBypassKeywordService bypass = bypassKeywordServiceProvider.getIfAvailable();
            if (bypass != null && bypass.bypassed(question)) {
                return null;
            }
        }
        List<SkillRoutingMetadata> active = metadataRepository.findActive();
        if (active.isEmpty()) {
            return null;
        }
        SkillCandidateSelection selection = candidateSelector.selectFromMetadata(active, question);
        if (selection.skillNames().isEmpty()) {
            return null;
        }
        // 拍板口径: 候选非空即执行 top-1
        String topName = selection.skillNames().get(0);
        SkillRoutingMetadata top = active.stream()
                .filter(m -> m.skillName().equals(topName))
                .findFirst()
                .orElse(null);
        if (top == null || top.scriptId() == null) {
            return null;
        }
        if (!canUse(topName, userId)) {
            return null;
        }
        Map<String, String> mapping = parseParamMapping(top.paramMapping());
        if (mapping.isEmpty()) {
            log.info("DirectSkillExecutor: skill {} 配了 script_id={} 但 param_mapping 为空/非法, 回退 LLM",
                    topName, top.scriptId());
            return null;
        }

        DimensionState preview = dimensionStateManager.previewState(question, previousState);
        DimensionExtraction extraction = extract(preview, selection.matchedKeywords(), topName,
                extractExtraDimensions(question));

        // 人员台账 (人员台账维度方案 §3.3/§4.1): 我X extraction 级 enrich → 人名扫描
        // (唯一命中直填 perName/perNo, 重名在时间歧义检查之后反问)。
        // 顺序约束: 我X enrich 必须先于人名扫描/下钻展开 ("我部人员的缺陷数"先有 deptName)
        PersonScanOutcome personScan = applyPersonalDimensions(extraction, question, userId);
        extraction = personScan.extraction();

        // params_schema 白名单/必填/类型: registry 查不到时按无 schema 处理 (mapping 原样传),
        // 白名单拒绝交给 scriptExec 的防注入校验 → 直接报错路径 (拍板: 配错不回退)
        List<ParamSpec> schema = null;
        List<String> required = List.of();
        try {
            var entry = scriptRegistryMapper.selectByScriptId(top.scriptId());
            if (entry != null && entry.getParamsSchema() != null && !entry.getParamsSchema().isBlank()) {
                schema = parseSchema(entry.getParamsSchema());
                required = schema.stream().filter(ParamSpec::required).map(ParamSpec::name).toList();
            }
        } catch (Exception e) {
            log.warn("DirectSkillExecutor: 查 script_registry 失败 scriptId={}: {}", top.scriptId(), e.getMessage());
        }

        // 歧义锚点 (时间维度方案 §3.3): 近X周方向 / 投产前二选一 → 真反问, 不做方向假设。
        // 无会话键无法挂 pending → 剥离歧义锚点降级继续 (时间部分回退 LLM)。
        List<String> trAnchors = extraction.extraDimensions()
                .getOrDefault(TimeRangeResolver.RESERVED_KEY, List.of());
        String ambiguous = firstAmbiguousAnchor(trAnchors);
        if (ambiguous != null) {
            List<String> kept = trAnchors.stream()
                    .filter(a -> !TimeRangeResolver.isAmbiguousAnchor(a)).toList();
            if (conversationId == null) {
                extraction = withTimeRangeAnchors(extraction, kept);
            } else {
                String key = sessionKey(userId, conversationId);
                Map<String, Object> preParams = buildParams(mapping, schema,
                        withTimeRangeAnchors(extraction, kept));
                pending.put(key, new PendingDirectExec(topName, top.scriptId(), mapping, schema, preParams,
                        List.of(TimeRangeResolver.RESERVED_KEY + "@" + ambiguous),
                        System.currentTimeMillis(), trAnchors));
                log.info("DirectSkillExecutor: skill {} 时间锚点歧义 {} → 反问 (pending {})",
                        topName, ambiguous, key);
                return new DirectExecutionPlan(extraction, topName, top.scriptId(), preParams,
                        List.of(labelFor(TimeRangeResolver.RESERVED_KEY)),
                        TimeRangeResolver.ambiguityAskPrompt(ambiguous));
            }
        }

        // 人名重名反问 (§4.2 直接执行轨): 复用 askMessage + PendingDirectExec (时间歧义锚点
        // 同构)。放时间歧义之后: 两者同时出现时先答时间, 下一轮 plan 重跑再问人名 (单 pending)。
        // 无会话键 → 剥离反问降级 (重名人不填值, LLM 路径兜底)。
        if (personScan.ambiguousName() != null) {
            if (conversationId == null) {
                log.info("DirectSkillExecutor: 人名 {} 重名但无会话键, 剥离反问降级", personScan.ambiguousName());
            } else {
                String key = sessionKey(userId, conversationId);
                Map<String, Object> preParams = buildParams(mapping, schema, extraction);
                pending.put(key, new PendingDirectExec(topName, top.scriptId(), mapping, schema,
                        preParams, List.of(PERSON_MARKER_PREFIX + personScan.ambiguousName()),
                        System.currentTimeMillis(), null));
                log.info("DirectSkillExecutor: skill {} 人名 {} 重名 {} 选 → 反问 (pending {})",
                        topName, personScan.ambiguousName(), personScan.candidates().size(), key);
                return new DirectExecutionPlan(extraction, topName, top.scriptId(), preParams,
                        List.of(labelFor("perNo")),
                        personAskMessage(personScan.ambiguousName(), personScan.candidates()));
            }
        }

        Map<String, Object> params = buildParams(mapping, schema, extraction);
        // 快照兜底补缺 (followup 方案 §3.5): 同 skill 且必填参数本轮未提及 → 继承上轮直接
        // 执行的值 (修 G2「时间层追问丢部门」在直接执行交替场景的暴露)。只补缺不覆盖;
        // 异 skill param 名空间不同不做翻译, 维持反问/回退。
        if (conversationId != null) {
            LastDirectExec last = lastExec(userId, conversationId);
            if (last != null && last.skillName().equals(topName)) {
                for (String req : required) {
                    if (!params.containsKey(req) && last.params().containsKey(req)) {
                        params.put(req, last.params().get(req));
                        log.info("DirectSkillExecutor: 必填参数 {} 本轮未提及, 快照兜底继承上轮值 (skill={})",
                                req, topName);
                    }
                }
            }
        }
        // 必填缺失分两类 (2026/10/15 缺参反问方案): 在 param_mapping 内 (有维度语义) →
        // 反问补参; 不在 mapping 内 (语义未知) 或调用方未传会话键 → 维持回退 LLM
        List<String> missingKeys = new ArrayList<>();
        for (String req : required) {
            if (params.containsKey(req)) {
                continue;
            }
            String dimKey = mapping.get(req);
            // 上级组织口径 (2026/10/22): 问题命中 orgName 动态键 (杭研维度词表) 时,
            // 缺部门不反问 — 上级组织已是部门的上层口径, 脚本按缺部门自行决定查询范围
            if ("deptName".equals(dimKey) && !extraction.extraDimensions()
                    .getOrDefault(ORG_DIMENSION_KEY, List.of()).isEmpty()) {
                log.info("DirectSkillExecutor: 必填参数 {} 缺失但命中 {} (上级组织口径), 不反问缺部门 (skill={})",
                        req, ORG_DIMENSION_KEY, topName);
                continue;
            }
            if (dimKey == null || conversationId == null) {
                log.info("DirectSkillExecutor: 必填参数 {} 无值 (skill={}, 维度未提及且无默认), 回退 LLM",
                        req, topName);
                return null;
            }
            if (!missingKeys.contains(dimKey)) {
                missingKeys.add(dimKey);
            }
        }
        if (!missingKeys.isEmpty()) {
            String key = sessionKey(userId, conversationId);
            pending.put(key, pendingOf(topName, top.scriptId(), mapping, schema, params, missingKeys));
            log.info("DirectSkillExecutor: skill {} 缺必填维度 {} → 反问补参 (pending {})",
                    topName, missingKeys, key);
            return new DirectExecutionPlan(extraction, topName, top.scriptId(), params,
                    List.of(labelFor(missingKeys.get(0))));
        }
        log.info("DirectSkillExecutor: plan hit skill={} scriptId={} params={} extraction={} matched={}",
                topName, top.scriptId(), params, extraction, extraction.metrics());
        recordLastExec(question, userId, conversationId, topName, top.scriptId(), params);
        return new DirectExecutionPlan(extraction, topName, top.scriptId(), params);
    }

    /** param_mapping → params 装配 (plan 与歧义补参轮共用): 白名单外不传, 缺值走 defaultTimeValues,
     *  形状按 schema 类型定 (direct-exec-param-type-plan.md §2.2)。 */
    private Map<String, Object> buildParams(Map<String, String> mapping, List<ParamSpec> schema,
                                            DimensionExtraction extraction) {
        Set<String> suppressedKeys = timeFamilyLoserKeys(mapping, extraction);
        boolean skipDefaultTime = requirementWithoutExplicitTime(extraction);
        Map<String, Object> params = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : mapping.entrySet()) {
            String param = e.getKey();
            if (schema != null && schema.stream().noneMatch(s -> param.equals(s.name()))) {
                continue; // params_schema 白名单外不传
            }
            if (suppressedKeys.contains(e.getValue())) {
                continue; // 双时间键脚本的时间二选一败者 (2026/10/21 规则)
            }
            List<String> values = valuesFor(extraction, e.getValue());
            if (values.isEmpty()) {
                List<String> fallback = skipDefaultTime ? List.of() : defaultTimeValues(e.getValue());
                if (fallback.isEmpty()) {
                    continue;
                }
                values = fallback;
            }
            params.put(param, shapeValue(param, schema, values));
        }
        return params;
    }

    /**
     * 特例 (2026/10/21 拍板): 问题包含需求项/需求子条目且未显式提及版本计划/季度时,
     * 不补默认在建版本 — 两个时间维度传给脚本的值为空 (需求项脚本按需求项全历史口径自查)。
     * 显式提及任一时间仍正常传该时间 (需求项+「12月版」→ 只传版本)。
     */
    private static boolean requirementWithoutExplicitTime(DimensionExtraction extraction) {
        boolean hasRequirement = (extraction.itemNo() != null && !extraction.itemNo().isEmpty())
                || (extraction.subItemNo() != null && !extraction.subItemNo().isEmpty());
        if (!hasRequirement) {
            return false;
        }
        boolean explicitTime =
                (extraction.versionPlan() != null && !extraction.versionPlan().isEmpty())
                        || (extraction.vestQuarter() != null && !extraction.vestQuarter().isEmpty());
        return !explicitTime;
    }

    /**
     * 时间二选一 (2026/10/21 拍板): 脚本同时支持版本计划与季度维度 (param_mapping 同时含
     * versionPlan 家族与 vestQuarter 家族, 含 .start/.end 后缀) 时, 按用户显式提及决定唯一
     * 胜者 — 显式提及季度 → 只传季度; 显式提及版本计划 (含在建版本锚点) → 只传版本;
     * 都未提及 → 默认在建版本计划。败者家族整体抑制, 防止「12月版」派生出季度一起传给脚本。
     * mapping 只映射单侧时间家族时不干预 (季度从版本派生的既有口径不变)。
     */
    private static Set<String> timeFamilyLoserKeys(Map<String, String> mapping,
                                                   DimensionExtraction extraction) {
        boolean hasVersionFamily = false;
        boolean hasQuarterFamily = false;
        for (String dimKey : mapping.values()) {
            if (dimKey.equals("versionPlan") || dimKey.startsWith("versionPlan.")) {
                hasVersionFamily = true;
            } else if (dimKey.equals("vestQuarter") || dimKey.startsWith("vestQuarter.")) {
                hasQuarterFamily = true;
            }
        }
        if (!hasVersionFamily || !hasQuarterFamily) {
            return Set.of();
        }
        boolean quarterExplicit = extraction.vestQuarter() != null && !extraction.vestQuarter().isEmpty();
        return quarterExplicit
                ? Set.of("versionPlan", "versionPlan.start", "versionPlan.end")
                : Set.of("vestQuarter", "vestQuarter.start", "vestQuarter.end");
    }

    /** 拍板①② (direct-exec-param-type-plan.md §4): schema 声明 type=array → 恒传 List 含单值,
     *  其余 (string/未声明/无 schema) 维持单值折叠, 不静默改变存量脚本契约。 */
    private static Object shapeValue(String param, List<ParamSpec> schema, List<String> values) {
        boolean isArray = schema != null && schema.stream()
                .anyMatch(s -> param.equals(s.name()) && "array".equals(s.type()));
        return isArray ? values : (values.size() == 1 ? values.get(0) : values);
    }

    /** timeRange 锚点列表里的第一个歧义锚点 (保序), 无则 null。 */
    private static String firstAmbiguousAnchor(List<String> anchors) {
        if (anchors == null) {
            return null;
        }
        return anchors.stream()
                .filter(TimeRangeResolver::isAmbiguousAnchor)
                .findFirst().orElse(null);
    }

    /** 复制提取体, 仅替换 timeRange 锚点列表 (其余 extraDimensions 键保留)。 */
    private static DimensionExtraction withTimeRangeAnchors(DimensionExtraction extraction,
                                                            List<String> anchors) {
        Map<String, List<String>> extra = new LinkedHashMap<>(extraction.extraDimensions());
        if (anchors == null || anchors.isEmpty()) {
            extra.remove(TimeRangeResolver.RESERVED_KEY);
        } else {
            extra.put(TimeRangeResolver.RESERVED_KEY, List.copyOf(anchors));
        }
        return new DimensionExtraction(extraction.versionPlan(), extraction.vestQuarter(),
                extraction.deptName(), extraction.groupName(), extraction.appName(),
                extraction.productLine(), extraction.itemNo(), extraction.subItemNo(),
                extraction.perName(), extraction.perNo(), extraction.metrics(),
                extraction.matchedSkill(), extra);
    }

    /**
     * 补参轮: 消费 pending, 用本轮回答解析第一个缺失维度键的值 (previewState 只读 + 词表扫描,
     * 别名/同义词都在这层消化)。解析不出 → 丢弃 pending 返回 null (口径 4: 用户可能问了别的事);
     * 还有剩余缺失 → 继续反问下一个 (口径 3); 补齐 → 返回完整执行计划。
     */
    private DirectExecutionPlan consumePending(String question, String userId,
                                               DimensionState previousState, String conversationId) {
        String key = sessionKey(userId, conversationId);
        PendingDirectExec p = pending.get(key);
        if (p == null) {
            return null;
        }
        if (System.currentTimeMillis() - p.createdAt() > PENDING_TTL_MS) {
            pending.remove(key, p);
            return null;
        }
        String firstMarker = p.missingKeys().isEmpty() ? "" : p.missingKeys().get(0);
        if (firstMarker.startsWith(PERSON_MARKER_PREFIX)) {
            return consumePersonPending(question, userId, previousState, conversationId, key, p,
                    firstMarker.substring(PERSON_MARKER_PREFIX.length()));
        }
        if (p.pendingAnchors() != null && !p.missingKeys().isEmpty()
                && p.missingKeys().get(0).startsWith(TimeRangeResolver.RESERVED_KEY + "@")) {
            DimensionState previewForAmbiguous = dimensionStateManager.previewState(question, previousState);
            return consumeAmbiguousPending(question, key, p, previewForAmbiguous);
        }
        DimensionState preview = dimensionStateManager.previewState(question, previousState);
        DimensionExtraction extraction = extract(preview, List.of(), p.skillName(),
                extractExtraDimensions(question));
        String missingKey = p.missingKeys().get(0);
        List<String> values = valuesFor(extraction, missingKey);
        if (values.isEmpty()) {
            log.info("DirectSkillExecutor: 补参回答未解析出 {} (pending {}), 丢弃走正常链路",
                    missingKey, key);
            pending.remove(key, p);
            return null;
        }
        Map<String, Object> params = new LinkedHashMap<>(p.filledParams());
        for (Map.Entry<String, String> e : p.mapping().entrySet()) {
            if (missingKey.equals(e.getValue())) {
                params.put(e.getKey(), shapeValue(e.getKey(), p.schema(), values));
            }
        }
        List<String> remaining = p.missingKeys().subList(1, p.missingKeys().size());
        if (remaining.isEmpty()) {
            pending.remove(key, p);
            log.info("DirectSkillExecutor: plan hit (补参完成) skill={} scriptId={} params={}",
                    p.skillName(), p.scriptId(), params);
            recordLastExec(question, userId, conversationId, p.skillName(), p.scriptId(), params);
            return new DirectExecutionPlan(extraction, p.skillName(), p.scriptId(), params);
        }
        pending.put(key, new PendingDirectExec(p.skillName(), p.scriptId(), p.mapping(), p.schema(),
                params, List.copyOf(remaining), System.currentTimeMillis(), null));
        log.info("DirectSkillExecutor: 补参 {} 已填, 继续反问 {}", missingKey, remaining.get(0));
        return new DirectExecutionPlan(extraction, p.skillName(), p.scriptId(), params,
                List.of(labelFor(remaining.get(0))));
    }

    /**
     * 歧义补参轮 (时间维度方案 §3.3): 用答复关键词把歧义锚点原位替换成确定性锚点
     * (近N周→LAST:/NEXT:nw, 投产前→投产周/交付周至投产周), 重算 timeRange 相关参数。
     * 答非所问 → 丢弃 pending 回退正常链路 (口径同缺参补参轮)。
     */
    private DirectExecutionPlan consumeAmbiguousPending(String question, String key,
                                                        PendingDirectExec p,
                                                        DimensionState previousState) {
        String marker = p.missingKeys().get(0);
        String ambiguous = marker.substring((TimeRangeResolver.RESERVED_KEY + "@").length());
        String resolved = TimeRangeResolver.resolveAmbiguousReply(question, ambiguous);
        if (resolved == null) {
            log.info("DirectSkillExecutor: 歧义答复未识别 {} (pending {}), 丢弃走正常链路",
                    ambiguous, key);
            pending.remove(key, p);
            return null;
        }
        List<String> finalAnchors = p.pendingAnchors().stream()
                .map(a -> a.equals(ambiguous) ? resolved : a)
                .toList();
        DimensionState preview = previousState;
        DimensionExtraction extraction = extract(preview, List.of(), p.skillName(),
                extractExtraDimensions(question));
        DimensionExtraction effective = withTimeRangeAnchors(extraction, finalAnchors);
        pending.remove(key, p);
        Map<String, Object> params = new LinkedHashMap<>(p.filledParams());
        for (Map.Entry<String, String> e : p.mapping().entrySet()) {
            String dimKey = e.getValue();
            boolean timeRangeBound = TimeRangeResolver.RESERVED_KEY.equals(dimKey)
                    || dimKey.startsWith(TimeRangeResolver.RESERVED_KEY + ".");
            if (!timeRangeBound) {
                continue;
            }
            List<String> values = valuesFor(effective, dimKey);
            if (!values.isEmpty()) {
                params.put(e.getKey(), shapeValue(e.getKey(), p.schema(), values));
            }
        }
        log.info("DirectSkillExecutor: 歧义 {} → {} (pending {}), plan hit skill={} scriptId={} params={}",
                ambiguous, resolved, key, p.skillName(), p.scriptId(), params);
        recordLastExec(key, question, p.skillName(), p.scriptId(), params);
        return new DirectExecutionPlan(effective, p.skillName(), p.scriptId(), params);
    }

    // ==================== 人员台账: 我X enrich + 人名扫描 (人员台账维度方案 §3.3/§4) ====================

    /** 人名扫描一轮的产物: enrich/扫描后的提取体 + 首个重名候选 (无则 null)。 */
    private record PersonScanOutcome(
            DimensionExtraction extraction,
            String ambiguousName,
            List<com.agentscopea2a.v2.dimension.PersonLedgerRepository.PersonIdentity> candidates) {}

    /**
     * 我X enrich (§3.3, extraction 级, 不经 PeerDimension) → 人名字典扫描 (§4.1)。
     * 唯一身份 → perName/perNo 直填; 重名 → 交反问 (调用方挂 pending); 台账不可用/查无 → 不注入。
     */
    private PersonScanOutcome applyPersonalDimensions(DimensionExtraction extraction,
                                                      String question, String userId) {
        if (personalResolverProvider == null) {
            return new PersonScanOutcome(extraction, null, List.of());
        }
        com.agentscopea2a.v2.dimension.PersonalDimensionResolver resolver =
                personalResolverProvider.getIfAvailable();
        if (resolver == null) {
            return new PersonScanOutcome(extraction, null, List.of());
        }
        extraction = enrichPersonal(extraction, resolver.resolve(question, userId), userId);

        String masked = com.agentscopea2a.v2.dimension.DimensionStateManager.maskForPersonScan(question);
        List<String> perName = new ArrayList<>(extraction.perName());
        List<String> perNo = new ArrayList<>(extraction.perNo());
        String ambiguousName = null;
        List<com.agentscopea2a.v2.dimension.PersonLedgerRepository.PersonIdentity> candidates = List.of();
        for (String name : resolver.scanNames(masked)) {
            List<com.agentscopea2a.v2.dimension.PersonLedgerRepository.PersonIdentity> identities =
                    resolver.resolveName(name);
            if (identities.isEmpty()) {
                continue; // 字典/快照口径不一致或查询失败, 跳过该名
            }
            if (identities.size() == 1) {
                if (!perName.contains(name)) {
                    perName.add(name);
                }
                String no = identities.get(0).perNo();
                if (no != null && !perNo.contains(no)) {
                    perNo.add(no);
                }
            } else if (ambiguousName == null) {
                ambiguousName = name;
                candidates = identities;
            }
        }
        DimensionExtraction out = new DimensionExtraction(extraction.versionPlan(),
                extraction.vestQuarter(), extraction.deptName(), extraction.groupName(),
                extraction.appName(), extraction.productLine(), extraction.itemNo(),
                extraction.subItemNo(), perName, perNo, extraction.metrics(),
                extraction.matchedSkill(), extraction.extraDimensions());
        return new PersonScanOutcome(out, ambiguousName, candidates);
    }

    /** §3.3 enrich 字段映射: 显式值优先保留, 台账多值追加去重; ledger null (anonymous/
     *  查无此人/查询失败) 时不注入维度, 仅 myself 且 userId 非空时仍填 perNo (=userId, 不查库)。 */
    private static DimensionExtraction enrichPersonal(
            DimensionExtraction extraction,
            com.agentscopea2a.v2.dimension.PersonalDimensionResolver.PersonalResolution personal,
            String userId) {
        if (!personal.anyHit()) {
            return extraction;
        }
        var ledger = personal.ledger();
        boolean anonymous = userId == null || userId.isBlank();
        if (ledger == null && (personal.myself() ? anonymous : true)) {
            log.info("DirectSkillExecutor: 我X命中但台账不可用 (userId={}), 不注入维度",
                    anonymous ? "空" : userId);
            return extraction;
        }
        List<String> deptName = appendNew(extraction.deptName(),
                ledger == null ? List.of() : ledger.depts());
        List<String> groupName = appendNew(extraction.groupName(),
                ledger == null ? List.of() : ledger.groups());
        List<String> appName = appendNew(extraction.appName(),
                ledger == null ? List.of() : ledger.apps());
        List<String> productLine = appendNew(extraction.productLine(),
                ledger == null ? List.of() : ledger.productLines());
        List<String> perName = new ArrayList<>(extraction.perName());
        List<String> perNo = new ArrayList<>(extraction.perNo());
        if (personal.myself() && !anonymous) {
            if (!perNo.contains(userId)) {
                perNo.add(userId);
            }
            if (ledger != null && ledger.name() != null && !perName.contains(ledger.name())) {
                perName.add(ledger.name());
            }
        }
        return new DimensionExtraction(extraction.versionPlan(), extraction.vestQuarter(),
                deptName, groupName, appName, productLine, extraction.itemNo(), extraction.subItemNo(),
                perName, perNo, extraction.metrics(), extraction.matchedSkill(),
                extraction.extraDimensions());
    }

    /** 追加不存在的非空值 (保序去重, 显式值已在 base 中优先保留)。 */
    private static List<String> appendNew(List<String> base, List<String> additions) {
        if (additions == null || additions.isEmpty()) {
            return base;
        }
        List<String> out = new ArrayList<>(base);
        for (String v : additions) {
            if (v != null && !v.isBlank() && !out.contains(v)) {
                out.add(v);
            }
        }
        return List.copyOf(out);
    }

    /**
     * 人名重名补参轮 (§4.2): 答复按序号/统一认证号/部门(组)名匹配 → perNo 落地继续执行;
     * 匹配不出 → 丢弃 pending 走正常链路 (口径同缺参补参轮)。
     */
    private DirectExecutionPlan consumePersonPending(String question, String userId,
                                                     DimensionState previousState,
                                                     String conversationId, String key,
                                                     PendingDirectExec p, String name) {
        com.agentscopea2a.v2.dimension.PersonalDimensionResolver resolver =
                personalResolverProvider == null ? null : personalResolverProvider.getIfAvailable();
        List<com.agentscopea2a.v2.dimension.PersonLedgerRepository.PersonIdentity> candidates =
                resolver == null ? List.of() : resolver.resolveName(name);
        com.agentscopea2a.v2.dimension.PersonLedgerRepository.PersonIdentity chosen =
                matchPersonReply(question, candidates);
        if (chosen == null) {
            log.info("DirectSkillExecutor: 人名反问答复未匹配 {} (pending {}), 丢弃走正常链路", name, key);
            pending.remove(key, p);
            return null;
        }
        DimensionState preview = dimensionStateManager.previewState(question, previousState);
        DimensionExtraction extraction = extract(preview, List.of(), p.skillName(),
                extractExtraDimensions(question));
        extraction = withResolvedPerson(extraction, chosen);
        Map<String, Object> params = new LinkedHashMap<>(p.filledParams());
        for (Map.Entry<String, String> e : p.mapping().entrySet()) {
            if ("perNo".equals(e.getValue())) {
                params.put(e.getKey(), shapeValue(e.getKey(), p.schema(), List.of(chosen.perNo())));
            }
        }
        pending.remove(key, p);
        log.info("DirectSkillExecutor: 人名 {} → perNo {} (pending {}), plan hit skill={} params={}",
                name, chosen.perNo(), key, p.skillName(), params);
        recordLastExec(question, userId, conversationId, p.skillName(), p.scriptId(), params);
        return new DirectExecutionPlan(extraction, p.skillName(), p.scriptId(), params);
    }

    private static DimensionExtraction withResolvedPerson(
            DimensionExtraction extraction,
            com.agentscopea2a.v2.dimension.PersonLedgerRepository.PersonIdentity identity) {
        List<String> perName = new ArrayList<>(extraction.perName());
        List<String> perNo = new ArrayList<>(extraction.perNo());
        if (identity.name() != null && !perName.contains(identity.name())) {
            perName.add(identity.name());
        }
        if (identity.perNo() != null && !perNo.contains(identity.perNo())) {
            perNo.add(identity.perNo());
        }
        return new DimensionExtraction(extraction.versionPlan(), extraction.vestQuarter(),
                extraction.deptName(), extraction.groupName(), extraction.appName(),
                extraction.productLine(), extraction.itemNo(), extraction.subItemNo(),
                perName, perNo, extraction.metrics(), extraction.matchedSkill(),
                extraction.extraDimensions());
    }

    /** 重名反问答复匹配: 序号 (1~N, 含全角/中文数字? 否——直接执行轮保持简单, 仅阿拉伯数字)
     *  优先, 其次统一认证号/部门(组)名包含匹配; 全不中 → null。 */
    private static com.agentscopea2a.v2.dimension.PersonLedgerRepository.PersonIdentity matchPersonReply(
            String reply, List<com.agentscopea2a.v2.dimension.PersonLedgerRepository.PersonIdentity> candidates) {
        if (reply == null || reply.isBlank() || candidates == null || candidates.isEmpty()) {
            return null;
        }
        String text = reply.trim();
        java.util.regex.Matcher m = REPLY_INDEX.matcher(text);
        while (m.find()) {
            int idx = 0;
            try {
                idx = Integer.parseInt(m.group());
            } catch (NumberFormatException ignore) {
                continue;
            }
            if (idx >= 1 && idx <= candidates.size()) {
                return candidates.get(idx - 1);
            }
        }
        for (var c : candidates) {
            if (c.perNo() != null && text.contains(c.perNo())) {
                return c;
            }
        }
        for (var c : candidates) {
            List<String> hints = new ArrayList<>(c.depts());
            hints.addAll(c.groups());
            for (String hint : hints) {
                if (hint != null && !hint.isBlank() && text.contains(hint)) {
                    return c;
                }
            }
        }
        return null;
    }

    private static final java.util.regex.Pattern REPLY_INDEX = java.util.regex.Pattern.compile("[1-9]\\d*");

    /** 重名反问文案 (§4.2): 序号 + 部门(组)后缀, 复用直接执行 askMessage 下发。 */
    static String personAskMessage(
            String name, List<com.agentscopea2a.v2.dimension.PersonLedgerRepository.PersonIdentity> candidates) {
        StringBuilder sb = new StringBuilder("「").append(name)
                .append("」匹配到多位人员，请回复序号确认：\n");
        for (int i = 0; i < candidates.size(); i++) {
            sb.append(i + 1).append(". ").append(candidates.get(i).displaySuffix()).append("\n");
        }
        sb.append("（回复序号或部门/组名即可）");
        return sb.toString();
    }

    /** 维度键 → 中文 label (字典 label 优先, 查不到/字典挂回退键名; timeRange 后缀键剥后缀再查)。 */
    private String labelFor(String dimKey) {
        String base = dimKey == null ? "" : dimKey.replaceAll("\\.(start|end)$", "");
        DimensionKeyConfigRepository repo = dimensionKeyRepoProvider.getIfAvailable();
        if (repo != null) {
            try {
                for (DimensionKeyConfig config : repo.findAllEnabled()) {
                    if (config.key().equals(base) && config.label() != null && !config.label().isBlank()) {
                        return config.label();
                    }
                }
            } catch (RuntimeException e) {
                log.warn("DirectSkillExecutor: 维度键字典读取失败, label 回退键名: {}", e.getMessage());
            }
        }
        return dimKey;
    }

    private DimensionExtraction extract(DimensionState state, List<String> metrics, String matchedSkill,
                                        Map<String, List<String>> extraDimensions) {
        List<String> versionPlan = List.of();
        List<String> vestQuarter = List.of();
        if (state != null && state.getTimeDimension() != null && !state.getTimeDimension().isEmpty()) {
            if (state.getTimeDimension().getType() == DimensionState.TimeDimensionType.VERSION) {
                versionPlan = state.getTimeDimension().getValues();
            } else {
                vestQuarter = state.getTimeDimension().getValues();
            }
        }
        List<String> departments = state == null ? List.of() : safe(state.getDepartments());
        List<String> groupName = List.of();
        List<String> appName = List.of();
        List<String> productLine = List.of();
        List<String> itemNo = List.of();
        List<String> subItemNo = List.of();
        if (state != null && state.getPeerDimension() != null && !state.getPeerDimension().isEmpty()) {
            List<String> values = state.getPeerDimension().getValues();
            switch (state.getPeerDimension().getType()) {
                case TEAM -> groupName = values;
                case APPLICATION -> appName = values;
                case PRODUCT_LINE -> productLine = values;
                case REQUIREMENT -> itemNo = values;
                case SUB_REQUIREMENT -> subItemNo = values;
            }
        }
        List<String> persons = state == null ? List.of() : safe(state.getPersons());
        // perNo 不进 DimensionState (执行期解析), 由 enrichPersonal/人名扫描在 plan 里填充
        return new DimensionExtraction(versionPlan, vestQuarter, departments, groupName,
                appName, productLine, itemNo, subItemNo, persons, List.of(), metrics, matchedSkill,
                extraDimensions);
    }

    /**
     * 动态维度键提取 (§9): dimension_key_config 启用键的词表对问题做 contains 扫描,
     * alias 命中 → 收集标准值 (去重保序)。仅当前问题提取, 不跨轮继承 (§9.1 拍板)。
     *
     * <p>例外: {@code timeRange} 保留键走时间范围提取 (L1 绝对区间正则 + L2 词表最长匹配),
     * 值为语义锚点 (docs/202610/dimension-key-dynamic-dimension-plan.md §6.1),
     * 执行期由 {@code valuesFor} 经 TimeRangeResolver 解析为 start/end。
     */
    private Map<String, List<String>> extractExtraDimensions(String question) {
        DimensionKeyConfigRepository repo = dimensionKeyRepoProvider.getIfAvailable();
        if (repo == null || question == null || question.isBlank()) {
            return Map.of();
        }
        List<DimensionKeyConfig> keys;
        try {
            keys = repo.findAllEnabled();
        } catch (RuntimeException e) {
            log.warn("DirectSkillExecutor: 维度键字典读取失败, 动态键跳过: {}", e.getMessage());
            return Map.of();
        }
        Map<String, List<String>> extra = new LinkedHashMap<>();
        for (DimensionKeyConfig config : keys) {
            if (config.builtin() || config.aliases().isEmpty()) {
                continue; // 内置键走结构化规则提取
            }
            if (TimeRangeResolver.RESERVED_KEY.equals(config.key())) {
                List<String> anchors = TimeRangeExtractor.extract(question, config.aliases());
                if (!anchors.isEmpty()) {
                    extra.put(config.key(), anchors);
                }
                continue;
            }
            List<String> values = new ArrayList<>();
            for (DimensionKeyConfig.AliasMapping alias : config.aliases()) {
                if (!alias.valid()) continue;
                if (com.agentscopea2a.v2.dimension.AliasRegexSupport.isRegexTemplate(alias.value())) {
                    // 正则捕获模板 ($1~$9): alias 按 regex 匹配, 捕获组替换产出值
                    for (String v : com.agentscopea2a.v2.dimension.AliasRegexSupport
                            .extract(question, alias.alias(), alias.value())) {
                        if (!values.contains(v)) {
                            values.add(v);
                        }
                    }
                } else if (question.contains(alias.alias()) && !values.contains(alias.value())) {
                    values.add(alias.value());
                }
            }
            if (!values.isEmpty()) {
                extra.put(config.key(), values);
            }
        }
        return extra;
    }

    /** 合法维度键全集 = 内置 9 键 ∪ 字典启用键 (timeRange 与 versionPlan/vestQuarter
     *  追加 .start/.end 后缀形态), 管理页下拉/保存校验同源。 */
    public java.util.LinkedHashSet<String> activeKeys() {
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>(DIMENSION_KEYS);
        DimensionKeyConfigRepository repo = dimensionKeyRepoProvider.getIfAvailable();
        if (repo != null) {
            try {
                repo.findAllEnabled().forEach(k -> keys.add(k.key()));
            } catch (RuntimeException e) {
                log.warn("DirectSkillExecutor: 维度键字典读取失败, 仅内置键可用: {}", e.getMessage());
            }
        }
        return com.agentscopea2a.v2.dimension.VersionPlanAnchorResolver.expandKeys(
                TimeRangeResolver.expandKeys(keys));
    }

    /** 调试口（/pm 智能体活动 dimension_extract 事件）：activeKeys 逐键 valuesFor 的全量
     *  解析值（含动态字典键与 .start/.end 后缀键，锚点已展开），仅收非空键。 */
    public java.util.LinkedHashMap<String, List<String>> resolvedValues(DimensionExtraction extraction) {
        java.util.LinkedHashMap<String, List<String>> out = new java.util.LinkedHashMap<>();
        for (String key : activeKeys()) {
            List<String> values = valuesFor(extraction, key);
            if (values != null && !values.isEmpty()) {
                out.put(key, values);
            }
        }
        return out;
    }

    /** 维度键 → 提取体取值; vestQuarter 在版本已给出时优先从版本推算 (口径一致)。
     *  口语值方案 §3.4: versionPlan/vestQuarter 的锚点值经 VersionPlanAnchorResolver
     *  执行期展开成版本值/季度值 (锚点原词绝不透传给脚本), .start/.end 后缀键取第一个
     *  可解析值的聚合时间窗口; timeRange 后缀键解析锚点取第一个范围 (多范围对比不支持, §6.1.6);
     *  不可解析锚点返回空 → 参数不传 → 必填缺失回退 LLM (fail-safe)。 */
    private List<String> valuesFor(DimensionExtraction extraction, String key) {
        return switch (key == null ? "" : key) {
            case "versionPlan" -> expandVersionValues(extraction.versionPlan());
            case "vestQuarter" -> {
                List<String> quarters = extraction.vestQuarter();
                if (quarters.isEmpty()) {
                    quarters = VersionPlanKnowledgeHook.deriveQuarters(
                            expandVersionValues(extraction.versionPlan()));
                }
                yield expandAnchorValues(quarters);
            }
            case "deptName" -> extraction.deptName();
            case "groupName" -> extraction.groupName();
            case "appName" -> extraction.appName();
            case "productLine" -> extraction.productLine();
            case "itemNo" -> extraction.itemNo();
            case "subItemNo" -> extraction.subItemNo();
            case "perName" -> extraction.perName();
            case "perNo" -> extraction.perNo();
            case "versionPlan.start" -> edge(extraction.versionPlan(), false);
            case "versionPlan.end" -> edge(extraction.versionPlan(), true);
            case "vestQuarter.start" -> quarterEdge(extraction, false);
            case "vestQuarter.end" -> quarterEdge(extraction, true);
            case TimeRangeResolver.RESERVED_KEY -> extraction.extraDimensions()
                    .getOrDefault(TimeRangeResolver.RESERVED_KEY, List.of());
            case TimeRangeResolver.KEY_START, TimeRangeResolver.KEY_END ->
                    timeRangeEdge(extraction.extraDimensions(),
                            TimeRangeResolver.KEY_END.equals(key));
            default -> extraction.extraDimensions().getOrDefault(key == null ? "" : key, List.of());
        };
    }

    private com.agentscopea2a.v2.dimension.VersionPlanAnchorResolver anchorResolver() {
        return anchorResolverProvider == null ? null : anchorResolverProvider.getIfAvailable();
    }

    /** 锚点 → 执行期展开 (resolver 缺失时锚点丢弃, 标准值透传)。 */
    private List<String> expandVersionValues(List<String> raw) {
        return expandAnchorValues(raw);
    }

    private List<String> expandAnchorValues(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        com.agentscopea2a.v2.dimension.VersionPlanAnchorResolver resolver = anchorResolver();
        if (resolver == null) {
            return raw.stream().filter(v -> !com.agentscopea2a.v2.dimension.VersionPlanAnchorResolver.isAnchor(v))
                    .toList();
        }
        return resolver.expandVersions(raw);
    }

    /** .start/.end 后缀键: 第一个可解析值的聚合时间窗口 (不可解析 → 空 → 不传参)。 */
    private List<String> edge(List<String> raw, boolean end) {
        com.agentscopea2a.v2.dimension.VersionPlanAnchorResolver resolver = anchorResolver();
        if (raw == null || raw.isEmpty() || resolver == null) {
            return List.of();
        }
        com.agentscopea2a.v2.dimension.VersionPlanAnchorResolver.VersionResolution r =
                resolver.resolveFirst(raw);
        if (r == null || r.start() == null || r.end() == null) {
            return List.of();
        }
        return List.of(end ? r.end() : r.start());
    }

    /** vestQuarter.start/.end: 季度列表为空时从展开后的版本值推算 (vestQuarter 既有口径)。 */
    private List<String> quarterEdge(DimensionExtraction extraction, boolean end) {
        List<String> raw = extraction.vestQuarter();
        if (raw.isEmpty()) {
            raw = VersionPlanKnowledgeHook.deriveQuarters(
                    expandVersionValues(extraction.versionPlan()));
        }
        return edge(raw, end);
    }

    /** 解析 timeRange 锚点列表的第一个范围, 取 start 或 end (ISO 日期字符串)。 */
    private static List<String> timeRangeEdge(Map<String, List<String>> extraDimensions,
                                              boolean end) {
        List<String> anchors = extraDimensions.getOrDefault(TimeRangeResolver.RESERVED_KEY, List.of());
        if (anchors.isEmpty()) {
            return List.of();
        }
        String[] range = anchors.get(0).startsWith("DATE:")
                ? TimeRangeResolver.resolveDateRange(anchors.get(0))
                : TimeRangeResolver.resolve(anchors.get(0), java.time.LocalDate.now(TimeRangeResolver.ZONE));
        if (range == null) {
            return List.of();
        }
        return List.of(end ? range[1] : range[0]);
    }

    private List<String> currentVersionsSafe() {
        try {
            VersionPlanKnowledgeHook hook = versionPlanHookProvider.getIfAvailable();
            if (hook == null) {
                return List.of();
            }
            return hook.currentVersions();
        } catch (Exception e) {
            log.warn("DirectSkillExecutor: 查当前在建版本失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 时间维度缺省补当前在建版本 (拍板): versionPlan/vestQuarter 都补**全量**在建版本列表
     *  (在建版本可能有多个, 只取第一个会漏查其他版本的行 → 无数据; 脚本侧按 IN 聚合,
     *  与 LLM 路径注入全部在建版本同语义); 其他维度无默认 → 空列表。 */
    private List<String> defaultTimeValues(String key) {
        if (!"versionPlan".equals(key) && !"vestQuarter".equals(key)) {
            return List.of();
        }
        List<String> versions = currentVersionsSafe();
        if (versions.isEmpty()) {
            return List.of();
        }
        if ("versionPlan".equals(key)) {
            return versions;
        }
        List<String> quarters = VersionPlanKnowledgeHook.deriveQuarters(versions);
        return quarters;
    }

    /**
     * 权限: top-1 必须 builtin (skill_index.source=auto_synthesized, 与 BuiltinSkillRegistrar
     * 同源) 或在该用户可用集合内; 解析失败 fail-closed → 回退 LLM。
     * 只查 top-1 (非 top-1 的越权 skill 只会造成多一次回退, 不会被执行)。
     */
    private boolean canUse(String skillName, String userId) {
        try {
            boolean builtin = indexRepo.findByName(skillName)
                    .map(entry -> SkillEntry.SOURCE_AUTO_SYNTHESIZED.equals(entry.source()))
                    .orElse(false);
            if (builtin) {
                return true;
            }
            if (userId == null || userId.isBlank()) {
                return false;
            }
            Set<String> usable = usageResolver.findUsableRetrievalNames(userId);
            return usable != null && usable.contains(skillName);
        } catch (RuntimeException e) {
            log.warn("DirectSkillExecutor: 权限解析失败 fail-closed, skill={}, user={}: {}",
                    skillName, userId, e.getMessage());
            return false;
        }
    }

    /** param_mapping JSON 解析; 失败降级空 map → plan 返回 null (按未配置直接执行处理)。 */
    private Map<String, String> parseParamMapping(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> parsed = objectMapper.readValue(json, PARAM_MAPPING_TYPE);
            if (parsed == null || parsed.isEmpty()) {
                return Map.of();
            }
            Map<String, String> cleaned = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : parsed.entrySet()) {
                if (e.getKey() == null || e.getKey().isBlank() || e.getValue() == null) {
                    continue;
                }
                cleaned.put(e.getKey().trim(), e.getValue().trim());
            }
            return cleaned;
        } catch (Exception e) {
            log.warn("DirectSkillExecutor: param_mapping JSON 解析失败, 按未配置处理: {}", e.getMessage());
            return Map.of();
        }
    }

    /** params_schema 解析 (JSON 数组, 元素 {name, required, type}); 必填名单由调用方按
     *  ParamSpec::required 过滤。解析失败返回空表 (非 null: 白名单为空 = 全不传, 维持原语义)。 */
    private List<ParamSpec> parseSchema(String paramsSchemaJson) {
        try {
            List<?> list = objectMapper.readValue(paramsSchemaJson, List.class);
            List<ParamSpec> specs = new ArrayList<>();
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) continue;
                Object name = m.get("name");
                if (name == null) continue;
                specs.add(new ParamSpec(String.valueOf(name),
                        Boolean.TRUE.equals(m.get("required")),
                        m.get("type") == null ? null : String.valueOf(m.get("type"))));
            }
            return specs;
        } catch (Exception e) {
            log.warn("DirectSkillExecutor: params_schema 解析失败: {}", e.getMessage());
            return List.of();
        }
    }

    private static List<String> safe(List<String> value) {
        return value == null ? List.of() : value;
    }
}
