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
 * 缺失用当前在建版本补默认值, 其他必填缺失回退 LLM; 脚本执行失败直接报错不回退。
 *
 * <p>预览不写状态: {@link #plan} 只读; 是否正式写维度状态 (processQuestionInContext)
 * 由 V2ChatStreamServiceImpl 决定短路后执行, 避免回退 LLM 时状态被解析两次
 * (特别是 clarifiedPeer 一次性回填被吃掉)。
 */
public class DirectSkillExecutor {

    private static final Logger log = LoggerFactory.getLogger(DirectSkillExecutor.class);

    private static final TypeReference<Map<String, String>> PARAM_MAPPING_TYPE =
            new TypeReference<>() {};

    /** Layer 1 固定维度键枚举 (param_mapping value 的合法取值, 管理页保存期校验同源)。 */
    public static final Set<String> DIMENSION_KEYS = Set.of(
            "versionPlan", "vestQuarter", "deptName", "groupName",
            "appName", "productLine", "itemNo", "perName");

    private final SkillRoutingMetadataRepository metadataRepository;
    private final SkillCandidateSelector candidateSelector;
    private final SkillUsageResolver usageResolver;
    private final SkillIndexRepository indexRepo;
    private final ScriptRegistryMapper scriptRegistryMapper;
    private final DimensionStateManager dimensionStateManager;
    private final ObjectProvider<VersionPlanKnowledgeHook> versionPlanHookProvider;
    private final ObjectProvider<DimensionKeyConfigRepository> dimensionKeyRepoProvider;
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
        this.metadataRepository = metadataRepository;
        this.candidateSelector = candidateSelector;
        this.usageResolver = usageResolver;
        this.indexRepo = indexRepo;
        this.scriptRegistryMapper = scriptRegistryMapper;
        this.dimensionStateManager = dimensionStateManager;
        this.versionPlanHookProvider = versionPlanHookProvider;
        this.dimensionKeyRepoProvider = dimensionKeyRepoProvider;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
    }

    /** Layer 1 标准化「维度+指标」提取体: 固定 schema 全键输出, 空数组 = 问题未提及。
     *  extraDimensions = 动态维度键 (dimension_key_config 词表命中, §9), 仅当前问题提取。 */
    public record DimensionExtraction(
            List<String> versionPlan,
            List<String> vestQuarter,
            List<String> deptName,
            List<String> groupName,
            List<String> appName,
            List<String> productLine,
            List<String> itemNo,
            List<String> perName,
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
            perName = safe(perName);
            metrics = safe(metrics);
            extraDimensions = extraDimensions == null ? Map.of() : Map.copyOf(extraDimensions);
        }

        /** Legacy 10-arg shape (no dynamic keys) for existing call sites. */
        public DimensionExtraction(
                List<String> versionPlan, List<String> vestQuarter, List<String> deptName,
                List<String> groupName, List<String> appName, List<String> productLine,
                List<String> itemNo, List<String> perName, List<String> metrics, String matchedSkill) {
            this(versionPlan, vestQuarter, deptName, groupName, appName, productLine,
                    itemNo, perName, metrics, matchedSkill, Map.of());
        }

        private static List<String> safe(List<String> value) {
            return value == null ? List.of() : List.copyOf(value);
        }
    }

    /** 直接执行计划; params 已按 param_mapping 填好 (单值 String / 多值 List)。 */
    public record DirectExecutionPlan(
            DimensionExtraction extraction,
            String skillName,
            String scriptId,
            Map<String, Object> params) {}

    /**
     * 纯判定, 无副作用; null = 不短路, 走 LLM。
     *
     * @param question      用户本轮问题原文
     * @param userId        当前用户 (权限过滤)
     * @param previousState 上一轮维度状态 (调用方从 RuntimeContext 的 dimensionState 键取)
     */
    public DirectExecutionPlan plan(String question, String userId, DimensionState previousState) {
        if (!enabled || question == null || question.isBlank()) {
            return null;
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

        // params_schema 白名单/必填: registry 查不到时按无 schema 处理 (mapping 原样传),
        // 白名单拒绝交给 scriptExec 的防注入校验 → 直接报错路径 (拍板: 配错不回退)
        List<String> declared = null;
        List<String> required = List.of();
        try {
            var entry = scriptRegistryMapper.selectByScriptId(top.scriptId());
            if (entry != null && entry.getParamsSchema() != null && !entry.getParamsSchema().isBlank()) {
                declared = parseNames(entry.getParamsSchema(), false);
                required = parseNames(entry.getParamsSchema(), true);
            }
        } catch (Exception e) {
            log.warn("DirectSkillExecutor: 查 script_registry 失败 scriptId={}: {}", top.scriptId(), e.getMessage());
        }

        Map<String, Object> params = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : mapping.entrySet()) {
            String param = e.getKey();
            if (declared != null && !declared.contains(param)) {
                continue; // params_schema 白名单外不传
            }
            List<String> values = valuesFor(extraction, e.getValue());
            if (values.isEmpty()) {
                String fallback = defaultTimeValue(e.getValue());
                if (fallback == null) {
                    continue;
                }
                values = List.of(fallback);
            }
            params.put(param, values.size() == 1 ? values.get(0) : values);
        }
        for (String req : required) {
            if (!params.containsKey(req)) {
                log.info("DirectSkillExecutor: 必填参数 {} 无值 (skill={}, 维度未提及且无默认), 回退 LLM",
                        req, topName);
                return null;
            }
        }
        log.info("DirectSkillExecutor: plan hit skill={} scriptId={} params={} extraction={} matched={}",
                topName, top.scriptId(), params, extraction, extraction.metrics());
        return new DirectExecutionPlan(extraction, topName, top.scriptId(), params);
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
        if (state != null && state.getPeerDimension() != null && !state.getPeerDimension().isEmpty()) {
            List<String> values = state.getPeerDimension().getValues();
            switch (state.getPeerDimension().getType()) {
                case TEAM -> groupName = values;
                case APPLICATION -> appName = values;
                case PRODUCT_LINE -> productLine = values;
                case REQUIREMENT -> itemNo = values;
            }
        }
        List<String> persons = state == null ? List.of() : safe(state.getPersons());
        return new DimensionExtraction(versionPlan, vestQuarter, departments, groupName,
                appName, productLine, itemNo, persons, metrics, matchedSkill, extraDimensions);
    }

    /**
     * 动态维度键提取 (§9): dimension_key_config 启用键的词表对问题做 contains 扫描,
     * alias 命中 → 收集标准值 (去重保序)。仅当前问题提取, 不跨轮继承 (§9.1 拍板)。
     *
     * <p>例外: {@code timeRange} 保留键走时间范围提取 (L1 绝对区间正则 + L2 词表最长匹配),
     * 值为语义锚点 (docs/history/dimension-key-dynamic-dimension-plan.md §6.1),
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
                if (question.contains(alias.alias()) && !values.contains(alias.value())) {
                    values.add(alias.value());
                }
            }
            if (!values.isEmpty()) {
                extra.put(config.key(), values);
            }
        }
        return extra;
    }

    /** 合法维度键全集 = 内置 8 键 ∪ 字典启用键 (timeRange 追加 .start/.end 后缀形态),
     *  管理页下拉/保存校验同源。 */
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
        return TimeRangeResolver.expandKeys(keys);
    }

    /** 维度键 → 提取体取值; vestQuarter 在版本已给出时优先从版本推算 (口径一致)。
     *  timeRange 后缀键解析锚点取第一个范围 (多范围对比不支持, §6.1.6);
     *  不可解析锚点返回空 → 参数不传 → 必填缺失回退 LLM (fail-safe)。 */
    private static List<String> valuesFor(DimensionExtraction extraction, String key) {
        return switch (key == null ? "" : key) {
            case "versionPlan" -> extraction.versionPlan();
            case "vestQuarter" -> !extraction.vestQuarter().isEmpty()
                    ? extraction.vestQuarter()
                    : VersionPlanKnowledgeHook.deriveQuarters(extraction.versionPlan());
            case "deptName" -> extraction.deptName();
            case "groupName" -> extraction.groupName();
            case "appName" -> extraction.appName();
            case "productLine" -> extraction.productLine();
            case "itemNo" -> extraction.itemNo();
            case "perName" -> extraction.perName();
            case TimeRangeResolver.RESERVED_KEY -> extraction.extraDimensions()
                    .getOrDefault(TimeRangeResolver.RESERVED_KEY, List.of());
            case TimeRangeResolver.KEY_START, TimeRangeResolver.KEY_END ->
                    timeRangeEdge(extraction.extraDimensions(),
                            TimeRangeResolver.KEY_END.equals(key));
            default -> extraction.extraDimensions().getOrDefault(key == null ? "" : key, List.of());
        };
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

    /** 时间维度缺省补当前在建版本 (拍板); 其他维度无默认 → null。 */
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

    private String defaultTimeValue(String key) {
        if (!"versionPlan".equals(key) && !"vestQuarter".equals(key)) {
            return null;
        }
        List<String> versions = currentVersionsSafe();
        if (versions.isEmpty()) {
            return null;
        }
        if ("versionPlan".equals(key)) {
            return versions.get(0);
        }
        List<String> quarters = VersionPlanKnowledgeHook.deriveQuarters(versions);
        return quarters.isEmpty() ? null : quarters.get(0);
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

    /** params_schema 解析 (JSON 数组, 元素 {name, required}); required=true 时取必填名单。 */
    private List<String> parseNames(String paramsSchemaJson, boolean requiredOnly) {
        try {
            List<?> list = objectMapper.readValue(paramsSchemaJson, List.class);
            List<String> names = new ArrayList<>();
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) continue;
                if (requiredOnly && !Boolean.TRUE.equals(m.get("required"))) continue;
                Object name = m.get("name");
                if (name != null) {
                    names.add(String.valueOf(name));
                }
            }
            return names;
        } catch (Exception e) {
            log.warn("DirectSkillExecutor: params_schema 解析失败: {}", e.getMessage());
            return List.of();
        }
    }

    private static List<String> safe(List<String> value) {
        return value == null ? List.of() : value;
    }
}
