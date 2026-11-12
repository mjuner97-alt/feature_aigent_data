package com.agentscopea2a.v2.skills;

import com.agentscopea2a.entity.ScriptRegistryEntry;
import com.agentscopea2a.mapper.gauss.ScriptRegistryMapper;
import com.agentscopea2a.v2.dimension.DimensionFamilyConfig;
import com.agentscopea2a.v2.dimension.DimensionFamilyConfigRepository;
import com.agentscopea2a.v2.dimension.DimensionKeyConfig;
import com.agentscopea2a.v2.dimension.DimensionKeyConfigRepository;
import com.agentscopea2a.v2.dimension.DimensionState;
import com.agentscopea2a.v2.dimension.DimensionStateManager;
import com.agentscopea2a.v2.dimension.QuestionAnalysis;
import com.agentscopea2a.v2.hooks.VersionPlanKnowledgeHook;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 直接执行判定器纯逻辑单测 (docs/history/skill-direct-exec-plan.md):
 * top-1 选择 / scriptId 缺失回退 / 两层填参 (单值 String 多值 List) /
 * 时间维度缺省补当前在建版本 / 必填缺失回退 / 权限 fail-closed。
 */
class DirectSkillExecutorTest {

    private static final String QUESTION = "杭州开发一部 2026年7月份版本 达标率多少";

    private final SkillRoutingMetadataRepository metadataRepository = mock(SkillRoutingMetadataRepository.class);
    private final SkillUsageResolver usageResolver = mock(SkillUsageResolver.class);
    private final SkillIndexRepository indexRepo = mock(SkillIndexRepository.class);
    private final ScriptRegistryMapper scriptRegistryMapper = mock(ScriptRegistryMapper.class);
    private final DimensionStateManager dimensionStateManager = mock(DimensionStateManager.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<VersionPlanKnowledgeHook> hookProvider = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<DimensionKeyConfigRepository> dimensionKeyRepoProvider = mock(ObjectProvider.class);
    private final VersionPlanKnowledgeHook versionPlanHook = mock(VersionPlanKnowledgeHook.class);
    private final SkillCandidateSelector selector = new SkillCandidateSelector(5, 10);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private DirectSkillExecutor newExecutor(boolean enabled) {
        return new DirectSkillExecutor(metadataRepository, selector, usageResolver, indexRepo,
                scriptRegistryMapper, dimensionStateManager, hookProvider, dimensionKeyRepoProvider,
                objectMapper, enabled);
    }

    private static SkillRoutingMetadata meta(String name, String scriptId, String mapping, String level1) {
        return new SkillRoutingMetadata(name, "summary", List.of(), List.of(), List.of(),
                "admin", true, LocalDateTime.now(), List.of(level1), List.of(), List.of(), scriptId, mapping);
    }

    private static DimensionState state(DimensionState.TimeDimension time, List<String> depts) {
        return new DimensionState(time, depts, null, null);
    }

    private static DimensionState.TimeDimension versionTime(List<String> values) {
        return new DimensionState.TimeDimension(DimensionState.TimeDimensionType.VERSION, values);
    }

    private static ScriptRegistryEntry registryEntry(String schemaJson) {
        ScriptRegistryEntry entry = new ScriptRegistryEntry();
        entry.setParamsSchema(schemaJson);
        return entry;
    }

    private void allowAll(String userId) {
        when(indexRepo.findByName(Mockito.anyString())).thenReturn(Optional.empty());
        when(usageResolver.findUsableRetrievalNames(userId)).thenReturn(Set.of("dept_skill"));
    }

    @Test
    void disabledOrBlankQuestionReturnsNull() {
        DirectSkillExecutor executor = newExecutor(false);
        assertNull(executor.plan(QUESTION, "alice", null));
        assertNull(newExecutor(true).plan("  ", "alice", null));
    }

    @Test
    void emptyOrAllInactiveMetadataReturnsNull() {
        when(metadataRepository.findActive()).thenReturn(List.of());
        assertNull(newExecutor(true).plan(QUESTION, "alice", null));
    }

    @Test
    void noCandidateHitReturnsNull() {
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("other_skill", "script_x", "{}", "闭环率")));
        assertNull(newExecutor(true).plan(QUESTION, "alice", null));
    }

    @Test
    void top1WithoutScriptIdFallsBackToLlm() {
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", null, null, "达标率")));
        allowAll("alice");
        assertNull(newExecutor(true).plan(QUESTION, "alice", null));
    }

    @Test
    void emptyParamMappingFallsBackToLlm() {
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "  ", "达标率")));
        allowAll("alice");
        assertNull(newExecutor(true).plan(QUESTION, "alice", null));
    }

    @Test
    void invalidParamMappingJsonFallsBackToLlm() {
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{not json", "达标率")));
        allowAll("alice");
        assertNull(newExecutor(true).plan(QUESTION, "alice", null));
    }

    @Test
    void singleValueFillsStringAndMultiValueFillsList() {
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"dept\":\"deptName\",\"version\":\"versionPlan\"}", "达标率")));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(QUESTION), Mockito.isNull())).thenReturn(
                state(versionTime(List.of("2026年7月份版本")), List.of("杭州开发一部", "杭州开发二部")));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(
                "[{\"name\":\"dept\",\"required\":true},{\"name\":\"version\",\"required\":true}]"));

        DirectSkillExecutor.DirectExecutionPlan plan = newExecutor(true).plan(QUESTION, "alice", null);

        assertEquals("dept_skill", plan.skillName());
        assertEquals("script_a", plan.scriptId());
        assertEquals(List.of("杭州开发一部", "杭州开发二部"), plan.params().get("dept"));
        assertEquals("2026年7月份版本", plan.params().get("version"));
        assertEquals("dept_skill", plan.extraction().matchedSkill());
        assertTrue(plan.extraction().metrics().contains("达标率"));
    }

    @Test
    void vestQuarterDerivedFromVersionValuesWhenQuarterMissing() {
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"quarter\":\"vestQuarter\"}", "达标率")));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(QUESTION), Mockito.isNull())).thenReturn(
                state(versionTime(List.of("2026年7月份版本")), List.of("杭州开发一部")));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(
                "[{\"name\":\"quarter\",\"required\":true}]"));

        DirectSkillExecutor.DirectExecutionPlan plan = newExecutor(true).plan(QUESTION, "alice", null);

        assertEquals("2026年3季度", plan.params().get("quarter"));
    }

    @Test
    void missingVersionFilledWithAllCurrentInBuildVersions() {
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"dept\":\"deptName\",\"version\":\"versionPlan\"}", "达标率")));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(QUESTION), Mockito.isNull())).thenReturn(
                state(null, List.of("杭州开发一部")));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(
                "[{\"name\":\"dept\",\"required\":true},{\"name\":\"version\",\"required\":true}]"));
        when(hookProvider.getIfAvailable()).thenReturn(versionPlanHook);
        when(versionPlanHook.currentVersions()).thenReturn(List.of("2026年10月份版本", "2026年11月份版本"));

        DirectSkillExecutor.DirectExecutionPlan plan = newExecutor(true).plan(QUESTION, "alice", null);

        // 全量在建版本 (IN 聚合), 只取第一个会漏查其他版本 → 无数据
        assertEquals(List.of("2026年10月份版本", "2026年11月份版本"), plan.params().get("version"));
    }

    @Test
    void missingRequiredNonTimeDimensionFallsBackToLlm() {
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"dept\":\"deptName\",\"version\":\"versionPlan\"}", "达标率")));
        allowAll("alice");
        // 问题未提及部门, version 有缺省可补, dept 无默认 → 必填缺失回退
        when(dimensionStateManager.previewState(Mockito.eq(QUESTION), Mockito.isNull())).thenReturn(
                state(null, List.of()));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(
                "[{\"name\":\"dept\",\"required\":true},{\"name\":\"version\",\"required\":true}]"));
        when(hookProvider.getIfAvailable()).thenReturn(versionPlanHook);
        when(versionPlanHook.currentVersions()).thenReturn(List.of("2026年10月份版本"));

        assertNull(newExecutor(true).plan(QUESTION, "alice", null));
    }

    @Test
    void paramsOutsideSchemaWhitelistAreDropped() {
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"dept\":\"deptName\",\"junk\":\"perName\"}", "达标率")));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(QUESTION), Mockito.isNull())).thenReturn(
                state(null, List.of("杭州开发一部")));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(
                "[{\"name\":\"dept\",\"required\":false}]"));

        DirectSkillExecutor.DirectExecutionPlan plan = newExecutor(true).plan(QUESTION, "alice", null);

        assertEquals(Map.of("dept", "杭州开发一部"), plan.params());
    }

    @Test
    void unknownScriptIdStillPlansAndFailsAtExecution() {
        // registry 查不到 = 按"无 schema"处理, mapping 原样透传, 拒绝交给 scriptExec → 直接报错路径
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_missing", "{\"dept\":\"deptName\"}", "达标率")));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(QUESTION), Mockito.isNull())).thenReturn(
                state(null, List.of("杭州开发一部")));
        when(scriptRegistryMapper.selectByScriptId("script_missing"))
                .thenThrow(new RuntimeException("db down"));

        DirectSkillExecutor.DirectExecutionPlan plan = newExecutor(true).plan(QUESTION, "alice", null);

        assertEquals("杭州开发一部", plan.params().get("dept"));
    }

    @Test
    void nonBuiltinSkillOutsideUserScopeFallsBack() {
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"dept\":\"deptName\"}", "达标率")));
        when(indexRepo.findByName("dept_skill")).thenReturn(Optional.empty());
        when(usageResolver.findUsableRetrievalNames("alice")).thenReturn(Set.of("other_skill"));
        assertNull(newExecutor(true).plan(QUESTION, "alice", null));
    }

    @Test
    void builtinSkillBypassesUserScopeCheck() {
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"dept\":\"deptName\"}", "达标率")));
        when(indexRepo.findByName("dept_skill")).thenReturn(Optional.of(new SkillEntry(
                "dept_skill", "fp", "desc", 1, 0, null, SkillEntry.STATUS_ACTIVE,
                SkillEntry.SOURCE_AUTO_SYNTHESIZED, null, null)));
        when(dimensionStateManager.previewState(Mockito.eq(QUESTION), Mockito.isNull())).thenReturn(
                state(null, List.of("杭州开发一部")));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(null));

        DirectSkillExecutor.DirectExecutionPlan plan = newExecutor(true).plan(QUESTION, "alice", null);

        assertEquals("杭州开发一部", plan.params().get("dept"));
    }

    @Test
    void permissionResolutionFailureFailsClosed() {
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"dept\":\"deptName\"}", "达标率")));
        when(indexRepo.findByName("dept_skill")).thenThrow(new RuntimeException("db down"));
        assertNull(newExecutor(true).plan(QUESTION, "alice", null));
    }

    // ==================== 旁路词让位层级轨 (docs/202610/直接执行优先于旁路词重排序方案.md §3.3) ====================

    @SuppressWarnings("unchecked")
    private final ObjectProvider<SkillBypassKeywordService> bypassProvider = mock(ObjectProvider.class);

    private DirectSkillExecutor newExecutorWithBypass(boolean enabled) {
        return new DirectSkillExecutor(metadataRepository, selector, usageResolver, indexRepo,
                scriptRegistryMapper, dimensionStateManager, hookProvider, dimensionKeyRepoProvider,
                bypassProvider, objectMapper, enabled);
    }

    /** 旁路词表; active metadata supplier 复用 metadataRepository (显式点名豁免与生产同源)。 */
    private SkillBypassKeywordService bypassService(String... words) {
        return new SkillBypassKeywordService(
                () -> java.util.Arrays.stream(words)
                        .map(w -> new SkillBypassKeyword(1L, "g", w, true, "")).toList(),
                metadataRepository::findActive);
    }

    /** 层级轨 metadata (L1+L2 配置, 叶层空)。 */
    private static SkillRoutingMetadata levelMeta(String name, String scriptId, String mapping,
                                                  List<String> level1, List<String> level2) {
        return new SkillRoutingMetadata(name, "summary", List.of(), List.of(), List.of(),
                "admin", true, LocalDateTime.now(), level1, level2, List.of(), scriptId, mapping);
    }

    /** 存量轨 metadata (三层全空, 仅叶层 keywords)。 */
    private static SkillRoutingMetadata legacyMeta(String name, String scriptId, String mapping,
                                                   String keyword) {
        return new SkillRoutingMetadata(name, "summary", List.of(keyword), List.of(), List.of(),
                "admin", true, LocalDateTime.now(), List.of(), List.of(), List.of(), scriptId, mapping);
    }

    /** 领域轨 metadata (三层全空, domain_tags 声明领域归属, 2026/10/28 拍板⑥形态)。 */
    private static SkillRoutingMetadata domainMeta(String name, List<String> domainTags) {
        return new SkillRoutingMetadata(name, "summary", List.of(), List.copyOf(domainTags), List.of(),
                "admin", true, LocalDateTime.now(), List.of(), List.of(), List.of(), null, null);
    }

    private static final String LEVEL_QUESTION = "三部10月未闭环的A号数据";

    @Test
    void bypassWordYieldsToLevelTrackDirectExec() {
        // 拍板①/冲突案例: 「A号」既是旁路词又是 L2 → 层级轨 top-1 接住, 直接执行不放弃
        when(metadataRepository.findActive()).thenReturn(List.of(
                levelMeta("dept_skill", "script_a", "{\"dept\":\"deptName\"}",
                        List.of("未闭环"), List.of("A号"))));
        when(bypassProvider.getIfAvailable()).thenReturn(bypassService("分析", "A号"));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(LEVEL_QUESTION), Mockito.isNull())).thenReturn(
                state(null, List.of("杭州开发三部")));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(
                "[{\"name\":\"dept\",\"required\":true}]"));

        DirectSkillExecutor.DirectExecutionPlan plan =
                newExecutorWithBypass(true).plan(LEVEL_QUESTION, "alice", null);

        assertEquals("dept_skill", plan.skillName());
        assertEquals("杭州开发三部", plan.params().get("dept"));
        assertTrue(plan.extraction().metrics().contains("未闭环"));
    }

    @Test
    void bypassWordStillBlocksLegacyTrackDirectExec() {
        // 拍板: 存量轨 (领域标签+叶层) 不让位 —— 旁路词命中照旧放弃短路
        when(metadataRepository.findActive()).thenReturn(List.of(
                legacyMeta("dept_skill", "script_a", "{\"dept\":\"deptName\"}", "达标率")));
        when(bypassProvider.getIfAvailable()).thenReturn(bypassService("分析"));
        allowAll("alice");

        assertNull(newExecutorWithBypass(true).plan("帮我分析一下达标率", "alice", null));
    }

    @Test
    void bypassWordYieldsButTopWithoutScriptIdStillFallsBack() {
        // 拍板③: 层级命中但未配 script_id → 直接执行未接住 → null (落回旁路 → LLM 走工具)
        when(metadataRepository.findActive()).thenReturn(List.of(
                levelMeta("dept_skill", null, null, List.of("未闭环"), List.of("A号"))));
        when(bypassProvider.getIfAvailable()).thenReturn(bypassService("分析", "A号"));
        allowAll("alice");

        assertNull(newExecutorWithBypass(true).plan(LEVEL_QUESTION, "alice", null));
    }

    @Test
    void bypassWordYieldsToLevelTrackMissingParamAsk() {
        // 拍板②: 层级命中 + 缺必填部门 + 旁路词 → 反问补参 (needsAsk) 而非丢给工具
        String question = "未闭环的A号情况";
        when(metadataRepository.findActive()).thenReturn(List.of(
                levelMeta("dept_skill", "script_a", "{\"dept\":\"deptName\"}",
                        List.of("未闭环"), List.of("A号"))));
        when(bypassProvider.getIfAvailable()).thenReturn(bypassService("分析", "A号"));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                state(null, List.of()));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(
                "[{\"name\":\"dept\",\"required\":true}]"));

        DirectSkillExecutor.DirectExecutionPlan plan =
                newExecutorWithBypass(true).plan(question, "alice", null, "conv1");

        assertTrue(plan.needsAsk());
        assertEquals("dept_skill", plan.skillName());
    }

    @Test
    void explicitNamingStillExemptsFromBypass() {
        // 拍板④ (§8 原口径不动): 显式点名 skill 名 → bypassed() 自身让位 → 正常判定链
        when(metadataRepository.findActive()).thenReturn(List.of(
                legacyMeta("dept_skill", "script_a", "{\"dept\":\"deptName\"}", "达标率")));
        when(bypassProvider.getIfAvailable()).thenReturn(bypassService("分析"));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.anyString(), Mockito.isNull())).thenReturn(
                state(null, List.of("杭州开发一部")));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(
                "[{\"name\":\"dept\",\"required\":true}]"));

        DirectSkillExecutor.DirectExecutionPlan plan =
                newExecutorWithBypass(true).plan("请使用 dept_skill 分析达标率", "alice", null);

        assertEquals("dept_skill", plan.skillName());
    }

    @Test
    void noBypassWordBehaviorUnchanged() {
        // 无旁路词 (bypassed=false) → 判定链与现状逐分支一致
        when(metadataRepository.findActive()).thenReturn(List.of(
                levelMeta("dept_skill", "script_a", "{\"dept\":\"deptName\"}",
                        List.of("未闭环"), List.of("A号"))));
        when(bypassProvider.getIfAvailable()).thenReturn(bypassService("分析"));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(LEVEL_QUESTION), Mockito.isNull())).thenReturn(
                state(null, List.of("杭州开发三部")));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(
                "[{\"name\":\"dept\",\"required\":true}]"));

        DirectSkillExecutor.DirectExecutionPlan plan =
                newExecutorWithBypass(true).plan(LEVEL_QUESTION, "alice", null);

        assertEquals("dept_skill", plan.skillName());
    }

    // ==================== domain 让位直接执行 (docs/202610/domain_tags命中优先于直接执行重排序方案.md §3.2) ====================

    @Test
    void domainHitYieldsToLlmEvenWhenLevelTrackHits() {
        // 对照表 1: 「部门例会上提到的未闭环问题」— 层级轨 AND 命中但 domain 命中优先,
        // 直接执行整体放弃回 LLM (漏斗第 2 层 > 第 3 层)
        when(metadataRepository.findActive()).thenReturn(List.of(
                levelMeta("dept_skill", "script_a", "{\"dept\":\"deptName\"}",
                        List.of("问题"), List.of("未闭环")),
                domainMeta("meeting_skill", List.of("部门例会", "杭研周例会"))));
        allowAll("alice");

        assertNull(newExecutor(true).plan("部门例会上提到的未闭环问题", "alice", null));
    }

    @Test
    void domainHitWithMissingRequiredReturnsNullNotAsk() {
        // 拍板③: domain 让位发生在参数组装/反问之前 — 缺必填回 LLM, 不反问
        when(metadataRepository.findActive()).thenReturn(List.of(
                levelMeta("dept_skill", "script_a", "{\"dept\":\"deptName\"}",
                        List.of("问题"), List.of("未闭环")),
                domainMeta("meeting_skill", List.of("部门例会"))));
        allowAll("alice");
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(
                "[{\"name\":\"dept\",\"required\":true}]"));

        assertNull(newExecutor(true).plan("部门例会上提到的未闭环问题", "alice", null, "conv1"));
    }

    @Test
    void domainHitExplicitNameStillPlans() {
        // 拍板①: 显式点名 skill 名豁免 domain 让位 — 正常出执行计划
        String question = "请使用 dept_skill 查部门例会的未闭环问题";
        when(metadataRepository.findActive()).thenReturn(List.of(
                levelMeta("dept_skill", "script_a", "{\"dept\":\"deptName\"}",
                        List.of("问题"), List.of("未闭环")),
                domainMeta("meeting_skill", List.of("部门例会"))));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                state(null, List.of("杭州开发一部")));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(null));

        DirectSkillExecutor.DirectExecutionPlan plan = newExecutor(true).plan(question, "alice", null);

        assertEquals("dept_skill", plan.skillName());
        assertEquals("杭州开发一部", plan.params().get("dept"));
    }

    @Test
    void pendingRoundNotBlockedByDomainHit() {
        // 拍板④: 补参轮 pending 消费在最前 — 反问后用户带 domain 词回答, 在途执行照常完成
        String askRound = "未闭环的A号情况";
        String reply = "部门例会 杭州开发一部";
        when(metadataRepository.findActive()).thenReturn(List.of(
                levelMeta("dept_skill", "script_a", "{\"dept\":\"deptName\"}",
                        List.of("未闭环"), List.of("A号")),
                domainMeta("meeting_skill", List.of("部门例会"))));
        allowAll("alice");
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(
                "[{\"name\":\"dept\",\"required\":true}]"));
        when(dimensionStateManager.previewState(Mockito.eq(askRound), Mockito.isNull())).thenReturn(
                state(null, List.of()));
        when(dimensionStateManager.previewState(Mockito.eq(reply), Mockito.isNull())).thenReturn(
                state(null, List.of("杭州开发一部")));

        DirectSkillExecutor executor = newExecutor(true);
        DirectSkillExecutor.DirectExecutionPlan ask = executor.plan(askRound, "alice", null, "conv1");
        assertTrue(ask.needsAsk());

        DirectSkillExecutor.DirectExecutionPlan resumed =
                executor.plan(reply, "alice", null, "conv1");

        assertEquals("dept_skill", resumed.skillName());
        assertEquals("杭州开发一部", resumed.params().get("dept"));
        assertFalse(resumed.needsAsk());
    }

    @Test
    void extractionDefaultsUnmentionedDimensionsToEmptyLists() {
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"dept\":\"deptName\"}", "达标率")));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(QUESTION), Mockito.isNull())).thenReturn(
                state(versionTime(List.of("2026年7月份版本")), List.of("杭州开发一部")));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(null));

        DirectSkillExecutor.DirectExecutionPlan plan = newExecutor(true).plan(QUESTION, "alice", null);

        DirectSkillExecutor.DimensionExtraction extraction = plan.extraction();
        assertEquals(List.of("2026年7月份版本"), extraction.versionPlan());
        assertEquals(List.of("杭州开发一部"), extraction.deptName());
        assertTrue(extraction.groupName().isEmpty());
        assertTrue(extraction.appName().isEmpty());
        assertTrue(extraction.productLine().isEmpty());
        assertTrue(extraction.itemNo().isEmpty());
        assertTrue(extraction.perName().isEmpty());
        assertTrue(extraction.vestQuarter().isEmpty());
        assertFalse(extraction.metrics().isEmpty());
    }

    @Test
    void planIsReadOnlyOnDimensionState() {
        // 预览不写状态: 只调 previewState, 不碰 processQuestionInContext
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"dept\":\"deptName\"}", "达标率")));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(QUESTION), Mockito.isNull())).thenReturn(
                state(null, List.of("杭州开发一部")));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(null));

        newExecutor(true).plan(QUESTION, "alice", null);

        Mockito.verify(dimensionStateManager).previewState(QUESTION, null);
        // 重锚抑制 (我X按需注入方案 §3.2) 只做只读规则分析, 不写状态
        Mockito.verify(dimensionStateManager).analyzeQuestionRuleBased(QUESTION);
        Mockito.verifyNoMoreInteractions(dimensionStateManager);
    }

    @Test
    void dimensionKeyValuesArePassedThroughVerbatim() {
        // param_mapping value 必须是 8 个固定维度键之一 (save 期已校验); 未知键取值为空 → 参数不传
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"dept\":\"deptName\",\"odd\":\"notAKey\"}", "达标率")));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(QUESTION), Mockito.isNull())).thenReturn(
                state(null, List.of("杭州开发一部")));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(null));

        DirectSkillExecutor.DirectExecutionPlan plan = newExecutor(true).plan(QUESTION, "alice", null);

        assertInstanceOf(String.class, plan.params().get("dept"));
        assertFalse(plan.params().containsKey("odd"));
    }

    // ==================== 动态维度键 (dimension_key_config 词表命中, §9) ====================

    private static DimensionKeyConfig qGateConfig() {
        return new DimensionKeyConfig(9L, "qGate", "卡口", false, List.of(
                new DimensionKeyConfig.AliasMapping("Q1卡口", "Q1"),
                new DimensionKeyConfig.AliasMapping("Q2卡口", "Q2"),
                new DimensionKeyConfig.AliasMapping("二季度卡口", "Q2"),
                new DimensionKeyConfig.AliasMapping("", "ignored")),
                true, null);
    }

    private void enableKeyDictionary(DimensionKeyConfig... configs) {
        DimensionKeyConfigRepository keyRepo = mock(DimensionKeyConfigRepository.class);
        when(keyRepo.findAllEnabled()).thenReturn(List.of(configs));
        when(dimensionKeyRepoProvider.getIfAvailable()).thenReturn(keyRepo);
    }

    @Test
    void dynamicKeyExtractsSingleAndMultiValuesFromAliases() {
        String question = "比较Q1卡口和二季度卡口的达标率";
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"gate\":\"qGate\"}", "达标率")));
        enableKeyDictionary(qGateConfig());
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                state(null, List.of()));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(null));

        DirectSkillExecutor.DirectExecutionPlan plan = newExecutor(true).plan(question, "alice", null);

        // Q1卡口→Q1, 二季度卡口→Q2 (Q2 与 Q1 去重保序)
        assertEquals(List.of("Q1", "Q2"), plan.params().get("gate"));
        assertEquals(List.of("Q1", "Q2"), plan.extraction().extraDimensions().get("qGate"));
    }

    @Test
    void regexCaptureAliasExtractsNumericValueForDynamicKey() {
        // 正则捕获模板 ($1): "流转次数>3" → transferTimes=["3"], 与 contains 行混配共存
        String question = "杭州三部 11月份版本未闭环问题数，跨部门流转次数>3";
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_t", "{\"threshold\":\"transferTimes\"}", "未闭环问题数")));
        enableKeyDictionary(new DimensionKeyConfig(12L, "transferTimes", "流转次数", false, List.of(
                new DimensionKeyConfig.AliasMapping("流转次数\\s*>\\s*(\\d+(?:\\.\\d+)?)", "$1"),
                new DimensionKeyConfig.AliasMapping("流转次数高", "高")),
                true, null));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                state(null, List.of()));
        when(scriptRegistryMapper.selectByScriptId("script_t")).thenReturn(registryEntry(null));

        DirectSkillExecutor.DirectExecutionPlan plan = newExecutor(true).plan(question, "alice", null);

        assertEquals("3", plan.params().get("threshold"));
        assertEquals(List.of("3"), plan.extraction().extraDimensions().get("transferTimes"));
    }

    @Test
    void regexCaptureAliasNoMatchFallsBackToLlm() {
        // 正则行未命中且 contains 行也未命中 → 动态键空 → 必填缺失回退 LLM
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_t", "{\"threshold\":\"transferTimes\"}", "未闭环问题数")));
        enableKeyDictionary(new DimensionKeyConfig(12L, "transferTimes", "流转次数", false, List.of(
                new DimensionKeyConfig.AliasMapping("流转次数\\s*>\\s*(\\d+(?:\\.\\d+)?)", "$1")),
                true, null));
        allowAll("alice");

        assertNull(newExecutor(true).plan("流转次数很高的怎么办", "alice", null));
    }

    // ==================== params_schema 数值类型转换 (direct-exec-param-type-plan.md 拍板③解除) ====================

    private DimensionKeyConfig transferTimesConfig() {
        return new DimensionKeyConfig(12L, "transferTimes", "流转次数", false, List.of(
                new DimensionKeyConfig.AliasMapping("流转次数\\s*>\\s*(\\d+(?:\\.\\d+)?)", "$1"),
                new DimensionKeyConfig.AliasMapping("流转次数高", "高")),
                true, null);
    }

    @Test
    void numberTypedSchemaConvertsIntegerString() {
        // type=number: "3" → 3 (Long), 不再以字符串传参
        String question = "杭州三部 11月份版本未闭环问题数，跨部门流转次数>3";
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_t", "{\"threshold\":\"transferTimes\"}", "未闭环问题数")));
        enableKeyDictionary(transferTimesConfig());
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                state(null, List.of()));
        when(scriptRegistryMapper.selectByScriptId("script_t")).thenReturn(registryEntry(
                "[{\"name\":\"threshold\",\"required\":true,\"type\":\"number\"}]"));

        DirectSkillExecutor.DirectExecutionPlan plan = newExecutor(true).plan(question, "alice", null);

        assertInstanceOf(Long.class, plan.params().get("threshold"));
        assertEquals(3L, plan.params().get("threshold"));
    }

    @Test
    void integerTypedSchemaConvertsSameAsNumber() {
        // type=integer 与 number 同一转换路径
        String question = "杭州三部 11月份版本未闭环问题数，跨部门流转次数>5";
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_t", "{\"threshold\":\"transferTimes\"}", "未闭环问题数")));
        enableKeyDictionary(transferTimesConfig());
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                state(null, List.of()));
        when(scriptRegistryMapper.selectByScriptId("script_t")).thenReturn(registryEntry(
                "[{\"name\":\"threshold\",\"required\":true,\"type\":\"integer\"}]"));

        DirectSkillExecutor.DirectExecutionPlan plan = newExecutor(true).plan(question, "alice", null);

        assertEquals(5L, plan.params().get("threshold"));
    }

    @Test
    void numberTypedSchemaDecimalBecomesDouble() {
        String question = "杭州三部 11月份版本未闭环问题数，跨部门流转次数>3.5";
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_t", "{\"threshold\":\"transferTimes\"}", "未闭环问题数")));
        enableKeyDictionary(transferTimesConfig());
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                state(null, List.of()));
        when(scriptRegistryMapper.selectByScriptId("script_t")).thenReturn(registryEntry(
                "[{\"name\":\"threshold\",\"required\":true,\"type\":\"number\"}]"));

        DirectSkillExecutor.DirectExecutionPlan plan = newExecutor(true).plan(question, "alice", null);

        assertInstanceOf(Double.class, plan.params().get("threshold"));
        assertEquals(3.5, plan.params().get("threshold"));
    }

    @Test
    void numberTypedSchemaMultiValueBecomesNumericList() {
        // 两次正则命中 → ["3","5"] → [3, 5]
        String question = "杭州三部 11月份版本未闭环问题数，跨部门流转次数>3、流转次数>5";
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_t", "{\"threshold\":\"transferTimes\"}", "未闭环问题数")));
        enableKeyDictionary(transferTimesConfig());
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                state(null, List.of()));
        when(scriptRegistryMapper.selectByScriptId("script_t")).thenReturn(registryEntry(
                "[{\"name\":\"threshold\",\"required\":true,\"type\":\"number\"}]"));

        DirectSkillExecutor.DirectExecutionPlan plan = newExecutor(true).plan(question, "alice", null);

        assertEquals(List.of(3L, 5L), plan.params().get("threshold"));
    }

    @Test
    void numberConversionFailureKeepsOriginalString() {
        // contains 行命中非数值 ("流转次数高" → "高"): 转换失败保底原字符串, 不抛异常
        String question = "杭州三部 11月份版本未闭环问题数，跨部门流转次数高";
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_t", "{\"threshold\":\"transferTimes\"}", "未闭环问题数")));
        enableKeyDictionary(transferTimesConfig());
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                state(null, List.of()));
        when(scriptRegistryMapper.selectByScriptId("script_t")).thenReturn(registryEntry(
                "[{\"name\":\"threshold\",\"required\":true,\"type\":\"number\"}]"));

        DirectSkillExecutor.DirectExecutionPlan plan = newExecutor(true).plan(question, "alice", null);

        assertEquals("高", plan.params().get("threshold"));
    }

    @Test
    void dynamicKeyNotMentionedFallsBackWhenRequired() {
        // 词表未命中 → extraDimensions 为空 → 必填 gate 无值 → 回退 LLM
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"gate\":\"qGate\"}", "达标率")));
        enableKeyDictionary(qGateConfig());
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(QUESTION), Mockito.isNull())).thenReturn(
                state(null, List.of()));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(
                "[{\"name\":\"gate\",\"required\":true}]"));

        assertNull(newExecutor(true).plan(QUESTION, "alice", null));
    }

    @Test
    void dynamicKeyDictionaryFailureSkipsDynamicKeys() {
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"dept\":\"deptName\",\"gate\":\"qGate\"}", "达标率")));
        DimensionKeyConfigRepository keyRepo = mock(DimensionKeyConfigRepository.class);
        when(keyRepo.findAllEnabled()).thenThrow(new RuntimeException("db down"));
        when(dimensionKeyRepoProvider.getIfAvailable()).thenReturn(keyRepo);
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(QUESTION), Mockito.isNull())).thenReturn(
                state(null, List.of("杭州开发一部")));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(null));

        // 字典挂 → 动态键跳过, 内置键链路不受影响
        DirectSkillExecutor.DirectExecutionPlan plan = newExecutor(true).plan(QUESTION, "alice", null);
        assertEquals("杭州开发一部", plan.params().get("dept"));
        assertFalse(plan.params().containsKey("gate"));
    }

    @Test
    void activeKeysMergeBuiltinAndDictionaryKeys() {
        // 12 内置含 perNo/perRole (人员台账维度方案 §3.4 + 岗位角色内置维度方案 §4.2)
        //    + projectNo (docs/202611/内置维度键projectNo项目编号方案.md)
        assertEquals(16, newExecutor(true).activeKeys().size());
        assertTrue(newExecutor(true).activeKeys().contains("perNo"));
        assertTrue(newExecutor(true).activeKeys().contains("perRole"));
        assertTrue(newExecutor(true).activeKeys().contains("projectNo"));
        enableKeyDictionary(qGateConfig());
        java.util.Set<String> keys = newExecutor(true).activeKeys();
        assertEquals(17, keys.size());
        assertTrue(keys.contains("qGate"));
        assertTrue(keys.contains("deptName"));
        assertTrue(keys.contains("versionPlan.start"));
    }

    // ==================== timeRange 保留键 (dimension-key-dynamic-dimension-plan.md §6.1) ====================

    private static DimensionKeyConfig timeRangeConfig() {
        return new DimensionKeyConfig(10L, "timeRange", "时间范围", false, List.of(
                new DimensionKeyConfig.AliasMapping("本周", "THIS_WEEK"),
                new DimensionKeyConfig.AliasMapping("前一周", "LAST_WEEK"),
                new DimensionKeyConfig.AliasMapping("近一周", "LAST:7d")), true, null);
    }

    @Test
    void timeRangeDateAnchorBoundToStartAndEndParams() {
        String question = "2026年10月1日到10月15日的缺陷数";
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"startDate\":\"timeRange.start\",\"endDate\":\"timeRange.end\"}", "缺陷数")));
        enableKeyDictionary(timeRangeConfig());
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                state(null, List.of()));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(null));

        DirectSkillExecutor.DirectExecutionPlan plan = newExecutor(true).plan(question, "alice", null);

        assertEquals(List.of("DATE:2026-10-01~2026-10-15"),
                plan.extraction().extraDimensions().get("timeRange"));
        assertEquals("2026-10-01", plan.params().get("startDate"));
        assertEquals("2026-10-15", plan.params().get("endDate"));
    }

    @Test
    void timeRangeWordAnchorBindsResolvedDates() {
        String question = "本周的缺陷数";
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"startDate\":\"timeRange.start\",\"endDate\":\"timeRange.end\"}", "缺陷数")));
        enableKeyDictionary(timeRangeConfig());
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                state(null, List.of()));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(null));

        DirectSkillExecutor.DirectExecutionPlan plan = newExecutor(true).plan(question, "alice", null);

        // 锚点原样入 extraDimensions; 日期执行期解析 (拍板①: 本周=周一~今天), 格式 ISO
        assertEquals(List.of("THIS_WEEK"), plan.extraction().extraDimensions().get("timeRange"));
        assertTrue(String.valueOf(plan.params().get("startDate")).matches("\\d{4}-\\d{2}-\\d{2}"));
        assertTrue(String.valueOf(plan.params().get("endDate")).matches("\\d{4}-\\d{2}-\\d{2}"));
    }

    @Test
    void timeRangeUnresolvableAnchorOmitsParam() {
        // 暂缓锚点 (如 LAST:3V) 若被配进词表: 提取照收, 解析失败 → 参数不传 → 必填回退 LLM (fail-safe)
        String question = "近三个版本的缺陷数";
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"startDate\":\"timeRange.start\"}", "缺陷数")));
        enableKeyDictionary(new DimensionKeyConfig(11L, "timeRange", "时间范围", false,
                List.of(new DimensionKeyConfig.AliasMapping("近三个版本", "LAST:3V")), true, null));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                state(null, List.of()));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(
                "[{\"name\":\"startDate\",\"required\":true}]"));

        assertNull(newExecutor(true).plan(question, "alice", null));
    }

    @Test
    void activeKeysWithTimeRangeExpandSuffixForms() {
        enableKeyDictionary(timeRangeConfig());
        java.util.Set<String> keys = newExecutor(true).activeKeys();
        // 12 内置 + timeRange + timeRange .start/.end + versionPlan/vestQuarter 各 .start/.end
        assertEquals(19, keys.size());
        assertTrue(keys.contains("timeRange"));
        assertTrue(keys.contains("timeRange.start"));
        assertTrue(keys.contains("timeRange.end"));
        assertTrue(keys.contains("versionPlan.start"));
        assertTrue(keys.contains("vestQuarter.end"));
    }

    @Test
    void timeRangeNotMentionedDoesNotAffectOtherKeys() {
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"dept\":\"deptName\",\"startDate\":\"timeRange.start\"}", "达标率")));
        enableKeyDictionary(timeRangeConfig());
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(QUESTION), Mockito.isNull())).thenReturn(
                state(null, List.of("杭州开发一部")));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(null));

        DirectSkillExecutor.DirectExecutionPlan plan = newExecutor(true).plan(QUESTION, "alice", null);

        assertEquals("杭州开发一部", plan.params().get("dept"));
        assertFalse(plan.params().containsKey("startDate")); // timeRange 未提及 → 不传
    }

    @Test
    void dynamicKeyAliasAsParamMappingValueButKeyNotConfiguredIsDropped() {
        // 字典里有 qGate 但 skill 没配它的映射 → extra 有值也不传参
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"dept\":\"deptName\"}", "达标率")));
        enableKeyDictionary(qGateConfig());
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(QUESTION), Mockito.isNull())).thenReturn(
                state(null, List.of("杭州开发一部")));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(null));

        DirectSkillExecutor.DirectExecutionPlan plan = newExecutor(true).plan(QUESTION, "alice", null);
        assertEquals(Map.of("dept", "杭州开发一部"), plan.params());
    }

    // ==================== 缺参反问 (docs/202610/Skill直接执行缺参反问方案.md) ====================

    private static DimensionKeyConfig builtinDeptNameConfig() {
        return new DimensionKeyConfig(1L, "deptName", "部门", true, List.of(), true, null);
    }

    @SuppressWarnings("unchecked")
    private java.util.concurrent.ConcurrentHashMap<String, DirectSkillExecutor.PendingDirectExec> pendingMap(
            DirectSkillExecutor executor) {
        try {
            var field = DirectSkillExecutor.class.getDeclaredField("pending");
            field.setAccessible(true);
            return (java.util.concurrent.ConcurrentHashMap<String, DirectSkillExecutor.PendingDirectExec>)
                    field.get(executor);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void missingRequiredInMappingWithSessionKeyReturnsAskPlan() {
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"dept\":\"deptName\",\"version\":\"versionPlan\"}", "达标率")));
        enableKeyDictionary(builtinDeptNameConfig());
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(QUESTION), Mockito.isNull())).thenReturn(
                state(null, List.of()));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(
                "[{\"name\":\"dept\",\"required\":true},{\"name\":\"version\",\"required\":true}]"));
        when(hookProvider.getIfAvailable()).thenReturn(versionPlanHook);
        when(versionPlanHook.currentVersions()).thenReturn(List.of("2026年10月份版本"));

        DirectSkillExecutor executor = newExecutor(true);
        DirectSkillExecutor.DirectExecutionPlan plan = executor.plan(QUESTION, "alice", null, "conv-1");

        assertTrue(plan.needsAsk());
        assertEquals(List.of("部门"), plan.missingAsk());
        assertEquals("2026年10月份版本", plan.params().get("version"));
        assertFalse(plan.params().containsKey("dept"));
        String prompt = DirectSkillExecutor.askPrompt(plan);
        assertTrue(prompt.contains("dept_skill"));
        assertTrue(prompt.contains("部门"));
        assertTrue(prompt.contains("杭州开发一部"));
        assertEquals(1, pendingMap(executor).size());
    }

    @Test
    void missingRequiredOutsideMappingStillFallsBackWithSessionKey() {
        // dept 必填但不在 param_mapping 内 (语义未知) → 即使有会话键也回退 LLM (口径 1)
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"version\":\"versionPlan\"}", "达标率")));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(QUESTION), Mockito.isNull())).thenReturn(
                state(null, List.of()));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(
                "[{\"name\":\"dept\",\"required\":true},{\"name\":\"version\",\"required\":true}]"));
        when(hookProvider.getIfAvailable()).thenReturn(versionPlanHook);
        when(versionPlanHook.currentVersions()).thenReturn(List.of("2026年10月份版本"));

        assertNull(newExecutor(true).plan(QUESTION, "alice", null, "conv-1"));
    }

    @Test
    void pendingAnswerCompletesExecution() {
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"dept\":\"deptName\",\"version\":\"versionPlan\"}", "达标率")));
        enableKeyDictionary(builtinDeptNameConfig());
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(QUESTION), Mockito.isNull())).thenReturn(
                state(null, List.of()));
        when(dimensionStateManager.previewState(Mockito.eq("杭州开发二部"), Mockito.isNull())).thenReturn(
                state(null, List.of("杭州开发二部")));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(
                "[{\"name\":\"dept\",\"required\":true},{\"name\":\"version\",\"required\":true}]"));
        when(hookProvider.getIfAvailable()).thenReturn(versionPlanHook);
        when(versionPlanHook.currentVersions()).thenReturn(List.of("2026年10月份版本"));

        DirectSkillExecutor executor = newExecutor(true);
        assertTrue(executor.plan(QUESTION, "alice", null, "conv-1").needsAsk());

        DirectSkillExecutor.DirectExecutionPlan plan =
                executor.plan("杭州开发二部", "alice", null, "conv-1");

        assertFalse(plan.needsAsk());
        assertEquals("dept_skill", plan.skillName());
        assertEquals("script_a", plan.scriptId());
        assertEquals("杭州开发二部", plan.params().get("dept"));
        assertEquals("2026年10月份版本", plan.params().get("version"));
        assertTrue(pendingMap(executor).isEmpty());
    }

    @Test
    void pendingAnswerUnparseableDropsPendingAndFallsThrough() {
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"dept\":\"deptName\",\"version\":\"versionPlan\"}", "达标率")));
        enableKeyDictionary(builtinDeptNameConfig());
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(QUESTION), Mockito.isNull())).thenReturn(
                state(null, List.of()));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(
                "[{\"name\":\"dept\",\"required\":true},{\"name\":\"version\",\"required\":true}]"));
        when(hookProvider.getIfAvailable()).thenReturn(versionPlanHook);
        when(versionPlanHook.currentVersions()).thenReturn(List.of("2026年10月份版本"));

        DirectSkillExecutor executor = newExecutor(true);
        assertTrue(executor.plan(QUESTION, "alice", null, "conv-1").needsAsk());

        // 回答解析不出部门 → 丢弃 pending 走正常链路 (口径 4)
        when(dimensionStateManager.previewState(Mockito.eq("帮我写个周报"), Mockito.isNull())).thenReturn(
                state(null, List.of()));
        assertNull(executor.plan("帮我写个周报", "alice", null, "conv-1"));
        assertTrue(pendingMap(executor).isEmpty());
    }

    @Test
    void pendingSecondMissingParameterAsksNextOne() {
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a",
                        "{\"dept\":\"deptName\",\"gate\":\"qGate\",\"version\":\"versionPlan\"}", "达标率")));
        enableKeyDictionary(builtinDeptNameConfig(), qGateConfig());
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(QUESTION), Mockito.isNull())).thenReturn(
                state(null, List.of()));
        when(dimensionStateManager.previewState(Mockito.eq("杭州开发二部"), Mockito.isNull())).thenReturn(
                state(null, List.of("杭州开发二部")));
        // Q1卡口 的值来自词表扫描 (extraDimensions), preview 不含它
        when(dimensionStateManager.previewState(Mockito.eq("Q1卡口"), Mockito.isNull())).thenReturn(
                state(null, List.of()));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(
                "[{\"name\":\"dept\",\"required\":true},{\"name\":\"gate\",\"required\":true},"
                        + "{\"name\":\"version\",\"required\":true}]"));
        when(hookProvider.getIfAvailable()).thenReturn(versionPlanHook);
        when(versionPlanHook.currentVersions()).thenReturn(List.of("2026年10月份版本"));

        DirectSkillExecutor executor = newExecutor(true);
        DirectSkillExecutor.DirectExecutionPlan first = executor.plan(QUESTION, "alice", null, "conv-1");
        assertEquals(List.of("部门"), first.missingAsk());

        DirectSkillExecutor.DirectExecutionPlan second =
                executor.plan("杭州开发二部", "alice", null, "conv-1");
        assertTrue(second.needsAsk());
        assertEquals(List.of("卡口"), second.missingAsk());

        DirectSkillExecutor.DirectExecutionPlan plan =
                executor.plan("Q1卡口", "alice", null, "conv-1");
        assertFalse(plan.needsAsk());
        assertEquals("杭州开发二部", plan.params().get("dept"));
        assertEquals("Q1", plan.params().get("gate"));
        assertEquals("2026年10月份版本", plan.params().get("version"));
        assertTrue(pendingMap(executor).isEmpty());
    }

    @Test
    void pendingTtlExpiryFallsBackToNormalChain() {
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"dept\":\"deptName\",\"version\":\"versionPlan\"}", "达标率")));
        enableKeyDictionary(builtinDeptNameConfig());
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(QUESTION), Mockito.isNull())).thenReturn(
                state(null, List.of()));
        when(dimensionStateManager.previewState(Mockito.eq("杭州开发二部"), Mockito.isNull())).thenReturn(
                state(null, List.of("杭州开发二部")));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(
                "[{\"name\":\"dept\",\"required\":true},{\"name\":\"version\",\"required\":true}]"));
        when(hookProvider.getIfAvailable()).thenReturn(versionPlanHook);
        when(versionPlanHook.currentVersions()).thenReturn(List.of("2026年10月份版本"));

        DirectSkillExecutor executor = newExecutor(true);
        assertTrue(executor.plan(QUESTION, "alice", null, "conv-1").needsAsk());

        var map = pendingMap(executor);
        map.replaceAll((k, v) -> new DirectSkillExecutor.PendingDirectExec(v.skillName(), v.scriptId(),
                v.mapping(), v.schema(), v.filledParams(), v.missingKeys(),
                System.currentTimeMillis() - DirectSkillExecutor.PENDING_TTL_MS - 1, null));

        // 过期 → 补参回答不再续上; 正常判定链对裸部门名无 skill 命中 → 回退 LLM
        assertNull(executor.plan("杭州开发二部", "alice", null, "conv-1"));
        assertTrue(map.isEmpty());
    }

    @Test
    void askLabelFallsBackToKeyNameWhenDictionaryUnavailable() {
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"dept\":\"deptName\"}", "达标率")));
        DimensionKeyConfigRepository keyRepo = mock(DimensionKeyConfigRepository.class);
        when(keyRepo.findAllEnabled()).thenThrow(new RuntimeException("db down"));
        when(dimensionKeyRepoProvider.getIfAvailable()).thenReturn(keyRepo);
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(QUESTION), Mockito.isNull())).thenReturn(
                state(null, List.of()));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(
                "[{\"name\":\"dept\",\"required\":true}]"));

        DirectSkillExecutor.DirectExecutionPlan plan =
                newExecutor(true).plan(QUESTION, "alice", null, "conv-1");

        assertTrue(plan.needsAsk());
        assertEquals(List.of("deptName"), plan.missingAsk());
    }

    // ==================== 版本/季度锚点执行期展开 (口语值方案 §3.4) ====================

    @SuppressWarnings("unchecked")
    private final ObjectProvider<com.agentscopea2a.v2.dimension.VersionPlanAnchorResolver>
            anchorResolverProvider = mock(ObjectProvider.class);

    private DirectSkillExecutor newExecutorWithResolver(
            com.agentscopea2a.v2.dimension.VersionPlanAnchorResolver resolver) {
        when(anchorResolverProvider.getIfAvailable()).thenReturn(resolver);
        return new DirectSkillExecutor(metadataRepository, selector, usageResolver, indexRepo,
                scriptRegistryMapper, dimensionStateManager, hookProvider, dimensionKeyRepoProvider,
                null, anchorResolverProvider, objectMapper, true);
    }

    /** 真实 resolver + 桩日历 (2026年10/11/12月份版本) + 在建版本供给。 */
    private com.agentscopea2a.v2.dimension.VersionPlanAnchorResolver stubResolver() {
        com.agentscopea2a.v2.dimension.VersionCalendarRepository cal =
                mock(com.agentscopea2a.v2.dimension.VersionCalendarRepository.class);
        when(cal.aggregateWindow(Mockito.anyList())).thenAnswer(inv -> {
            List<String> versions = inv.getArgument(0);
            boolean hit = versions != null && versions.stream().anyMatch(v ->
                    List.of("2026年10月份版本", "2026年11月份版本", "2026年12月份版本").contains(v));
            return hit
                    ? new com.agentscopea2a.v2.dimension.VersionCalendarRepository.VersionWindow(
                            "2025-09-22", "2025-12-22")
                    : null;
        });
        return new com.agentscopea2a.v2.dimension.VersionPlanAnchorResolver(
                cal, () -> List.of("2026年10月份版本"));
    }

    @Test
    void thisVAnchorExpandsToCurrentVersions() {
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"version\":\"versionPlan\"}", "达标率")));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(QUESTION), Mockito.isNull())).thenReturn(
                state(versionTime(List.of("THIS_V")), List.of("杭州开发一部")));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(
                "[{\"name\":\"version\",\"required\":true}]"));

        DirectSkillExecutor.DirectExecutionPlan plan = newExecutorWithResolver(stubResolver())
                .plan(QUESTION, "alice", null);

        // 锚点展开为在建版本值 (单值填 String), 锚点原词绝不透传
        assertEquals("2026年10月份版本", plan.params().get("version"));
    }

    @Test
    void anchorDroppedWhenResolverMissing() {
        // resolver 缺失 (10 参构造, 锚点解析器未装配): 锚点丢弃 → 参数不传 → 必填回退 LLM
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"version\":\"versionPlan\"}", "达标率")));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(QUESTION), Mockito.isNull())).thenReturn(
                state(versionTime(List.of("THIS_V")), List.of("杭州开发一部")));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(
                "[{\"name\":\"version\",\"required\":true}]"));

        assertNull(newExecutor(true).plan(QUESTION, "alice", null));
    }

    @Test
    void anchorExpandFailureSkipsDefaultAndAsks() {
        // 近N月方案 F3B (2026-11-11 拍板②选B): LAST:4V 展开空 (日历不可用+无在建版本) 时
        // 不得用 defaultTimeValues 在建版本默认值静默顶替 → 必填缺失反问
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"version\":\"versionPlan\"}", "达标率")));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(QUESTION), Mockito.isNull())).thenReturn(
                state(versionTime(List.of("LAST:4V")), List.of()));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(
                "[{\"name\":\"version\",\"required\":true}]"));
        // 在建版本有值: 若误兜底默认, version 会被填成 10月版本并直接执行 — 断言不得发生
        when(hookProvider.getIfAvailable()).thenReturn(versionPlanHook);
        when(versionPlanHook.currentVersions()).thenReturn(List.of("2026年10月份版本"));
        com.agentscopea2a.v2.dimension.VersionCalendarRepository deadCal =
                mock(com.agentscopea2a.v2.dimension.VersionCalendarRepository.class);
        when(deadCal.snapshot()).thenReturn(java.util.Map.of());
        when(deadCal.aggregateWindow(Mockito.anyList())).thenReturn(null);
        com.agentscopea2a.v2.dimension.VersionPlanAnchorResolver deadResolver =
                new com.agentscopea2a.v2.dimension.VersionPlanAnchorResolver(deadCal, List::of);

        DirectSkillExecutor executor = newExecutorWithResolver(deadResolver);
        DirectSkillExecutor.DirectExecutionPlan plan = executor.plan(QUESTION, "alice", null, "conv-1");

        assertTrue(plan.needsAsk());
        assertFalse(plan.missingAsk().isEmpty());
        assertFalse(plan.params().containsKey("version"));
    }

    @Test
    void noTimeMentionStillFallsBackToDefaultInBuildVersions() {
        // F3B 回归护栏: 未提及时间的问法 (extraction 无 versionPlan 值) 仍补在建版本默认
        // (defaultTimeValues 既有拍板), 不得被锚点失败判定误伤
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"version\":\"versionPlan\"}", "达标率")));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(QUESTION), Mockito.isNull())).thenReturn(
                state(versionTime(List.of()), List.of()));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(
                "[{\"name\":\"version\",\"required\":true}]"));
        when(hookProvider.getIfAvailable()).thenReturn(versionPlanHook);
        when(versionPlanHook.currentVersions()).thenReturn(List.of("2026年10月份版本"));

        DirectSkillExecutor.DirectExecutionPlan plan =
                newExecutor(true).plan(QUESTION, "alice", null, "conv-1");

        assertFalse(plan.needsAsk());
        assertEquals("2026年10月份版本", plan.params().get("version"));
    }

    @Test
    void versionStartEndSuffixKeysBindCalendarWindow() {
        String question = "2026年10月份版本的达标率";
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a",
                        "{\"startDate\":\"versionPlan.start\",\"endDate\":\"versionPlan.end\"}", "达标率")));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                state(versionTime(List.of("2026年10月份版本")), List.of()));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(null));

        DirectSkillExecutor.DirectExecutionPlan plan = newExecutorWithResolver(stubResolver())
                .plan(question, "alice", null);

        // 标准值 → 日历单版本窗口; 桩日历统一窗口 [2025-09-22, 2025-12-22]
        assertEquals("2025-09-22", plan.params().get("startDate"));
        assertEquals("2025-12-22", plan.params().get("endDate"));
    }

    @Test
    void versionStartEndUnresolvableOmitsParams() {
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"startDate\":\"versionPlan.start\"}", "达标率")));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(QUESTION), Mockito.isNull())).thenReturn(
                state(versionTime(List.of("2099年1月份版本")), List.of()));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(
                "[{\"name\":\"startDate\",\"required\":true}]"));

        // 日历查无该版本 → .start 解析为空 → 参数不传 → 必填缺失回退 (fail-safe)
        assertNull(newExecutorWithResolver(stubResolver()).plan(QUESTION, "alice", null));
    }

    @Test
    void vestQuarterStartDerivedFromVersionWhenQuarterMissing() {
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a",
                        "{\"qStart\":\"vestQuarter.start\",\"qEnd\":\"vestQuarter.end\"}", "达标率")));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(QUESTION), Mockito.isNull())).thenReturn(
                state(versionTime(List.of("2026年10月份版本")), List.of()));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(null));

        DirectSkillExecutor.DirectExecutionPlan plan = newExecutorWithResolver(stubResolver())
                .plan(QUESTION, "alice", null);

        // 2026年10月份版本 → 2026年4季度 → 季度内月度版本聚合窗口
        assertEquals("2025-09-22", plan.params().get("qStart"));
        assertEquals("2025-12-22", plan.params().get("qEnd"));
    }

    @Test
    void activeKeysWithVersionAndTimeRangeExpandAllSuffixForms() {
        enableKeyDictionary(timeRangeConfig(),
                new DimensionKeyConfig(2L, "versionPlan", "版本计划", true, List.of(), true, null),
                new DimensionKeyConfig(3L, "vestQuarter", "季度", true, List.of(), true, null));
        var keys = newExecutor(true).activeKeys();
        // versionPlan/vestQuarter 本就在内置键里, 字典行去重: 12 内置 + timeRange + 6 后缀
        assertEquals(19, keys.size());
        assertTrue(keys.contains("timeRange.start"));
        assertTrue(keys.contains("versionPlan.start"));
        assertTrue(keys.contains("versionPlan.end"));
        assertTrue(keys.contains("vestQuarter.start"));
        assertTrue(keys.contains("vestQuarter.end"));
    }

    // ==================== resolvedValues 调试口 (dimension_extract 面板) ====================

    @Test
    void resolvedValuesCoversUnmappedDynamicAndSuffixKeys() {
        // skill 只映射 dept, 但 resolved 要展示全量: 版本值 + versionPlan .start/.end 窗口
        allowAll("alice");

        DirectSkillExecutor executor = newExecutorWithResolver(stubResolver());
        DirectSkillExecutor.DimensionExtraction extraction = new DirectSkillExecutor.DimensionExtraction(
                List.of("2026年10月份版本"), List.of(), List.of("杭州开发一部"),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of("达标率"), "dept_skill", Map.of());

        var resolved = executor.resolvedValues(extraction);

        // 未映射进 skill 的键也展示
        assertEquals(List.of("2026年10月份版本"), resolved.get("versionPlan"));
        assertEquals(List.of("杭州开发一部"), resolved.get("deptName"));
        assertEquals(List.of("2025-09-22"), resolved.get("versionPlan.start"));
        assertEquals(List.of("2025-12-22"), resolved.get("versionPlan.end"));
        // 空键不出现; metrics 不在维度键全集里 (随 extraction 字段单独展示)
        assertFalse(resolved.containsKey("groupName"));
        assertFalse(resolved.containsKey("timeRange"));
        assertFalse(resolved.containsKey("metrics"));
    }

    // ==================== 时间维度歧义反问 (time-dimension-plan.md §3.3) ====================

    @Test
    void ambiguousWeeksAnchorTriggersAskAndResumeResolvesDirection() {
        String question = "近3周的达标率是多少";
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"range\":\"timeRange\"}", "达标率")));
        enableKeyDictionary(timeRangeConfig());
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                state(null, List.of()));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(null));

        DirectSkillExecutor executor = newExecutor(true);
        DirectSkillExecutor.DirectExecutionPlan plan = executor.plan(question, "alice", null, "conv-1");

        assertTrue(plan.needsAsk());
        assertTrue(plan.askMessage().contains("历史"));
        assertTrue(plan.askMessage().contains("未来"));
        // pending 带原始锚点列表 (歧义锚点在列)
        var map = pendingMap(executor);
        assertEquals(1, map.size());
        assertTrue(map.values().iterator().next().pendingAnchors().contains("AMBIGUOUS:WEEKS:3"));

        // 答"历史" → LAST:3w 透传裸键, pending 消费
        DirectSkillExecutor.DirectExecutionPlan done =
                executor.plan("历史", "alice", null, "conv-1");
        assertFalse(done.needsAsk());
        assertEquals("LAST:3w", done.params().get("range"));
        assertTrue(map.isEmpty());
    }

    @Test
    void ambiguousWeeksFutureAnswerResolvesNext() {
        String question = "近2周的达标率如何";
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"range\":\"timeRange\"}", "达标率")));
        enableKeyDictionary(timeRangeConfig());
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                state(null, List.of()));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(null));

        DirectSkillExecutor executor = newExecutor(true);
        assertTrue(executor.plan(question, "alice", null, "conv-1").needsAsk());

        DirectSkillExecutor.DirectExecutionPlan done =
                executor.plan("未来", "alice", null, "conv-1");
        assertEquals("NEXT:2w", done.params().get("range"));
    }

    @Test
    void ambiguousProdAnchorAskAndResume() {
        String question = "投产前的达标率";
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"range\":\"timeRange\"}", "达标率")));
        enableKeyDictionary(new DimensionKeyConfig(11L, "timeRange", "时间范围", false,
                List.of(new DimensionKeyConfig.AliasMapping("投产前", "AMBIGUOUS:PROD"),
                        new DimensionKeyConfig.AliasMapping("投产周", "投产周")), true, null));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                state(null, List.of()));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(null));

        DirectSkillExecutor executor = newExecutor(true);
        DirectSkillExecutor.DirectExecutionPlan plan = executor.plan(question, "alice", null, "conv-1");
        assertTrue(plan.needsAsk());
        assertTrue(plan.askMessage().contains("投产周"));
        assertTrue(plan.askMessage().contains("交付周至投产周"));

        DirectSkillExecutor.DirectExecutionPlan done =
                executor.plan("交付周至投产周", "alice", null, "conv-1");
        assertEquals("交付周至投产周", done.params().get("range"));
    }

    @Test
    void ambiguousIrrelevantAnswerDropsPendingAndFallsThrough() {
        String question = "近3周的达标率是多少";
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"range\":\"timeRange\"}", "达标率")));
        enableKeyDictionary(timeRangeConfig());
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                state(null, List.of()));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(null));

        DirectSkillExecutor executor = newExecutor(true);
        assertTrue(executor.plan(question, "alice", null, "conv-1").needsAsk());

        // 答非所问 → pending 丢弃, 正常链对该回答无 skill 命中 → 回退 LLM
        assertNull(executor.plan("今天天气不错", "alice", null, "conv-1"));
        assertTrue(pendingMap(executor).isEmpty());
    }

    @Test
    void ambiguousAnchorWithoutConversationIdStrippedAndDegrades() {
        String question = "近3周的达标率是多少";
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"range\":\"timeRange\"}", "达标率")));
        enableKeyDictionary(timeRangeConfig());
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                state(null, List.of()));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(null));

        // 无会话键: 歧义锚点剥离降级, 不反问, timeRange 参数缺值不传
        DirectSkillExecutor.DirectExecutionPlan plan = newExecutor(true).plan(question, "alice", null);
        assertFalse(plan.needsAsk());
        assertFalse(plan.params().containsKey("range"));
    }

    // ==================== LastDirectExec 快照 + 兜底补缺 (docs/202610/skill-direct-exec-followup-plan.md §3.1/§3.5) ====================

    @SuppressWarnings("unchecked")
    private java.util.concurrent.ConcurrentHashMap<String, DirectSkillExecutor.LastDirectExec> lastExecStore(
            DirectSkillExecutor executor) {
        try {
            var field = DirectSkillExecutor.class.getDeclaredField("lastExecStore");
            field.setAccessible(true);
            return (java.util.concurrent.ConcurrentHashMap<String, DirectSkillExecutor.LastDirectExec>)
                    field.get(executor);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private void stubExecutableDeptPlan(String question) {
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"dept\":\"deptName\"}", "达标率")));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                state(null, List.of("杭州开发一部")));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(
                "[{\"name\":\"dept\",\"required\":true}]"));
    }

    @Test
    void executablePlanRecordsSnapshotWithQuestionSkillParams() {
        stubExecutableDeptPlan(QUESTION);

        DirectSkillExecutor executor = newExecutor(true);
        executor.plan(QUESTION, "alice", null, "conv-1");

        DirectSkillExecutor.LastDirectExec snapshot = executor.lastExec("alice", "conv-1");
        assertEquals(QUESTION, snapshot.question());
        assertEquals("dept_skill", snapshot.skillName());
        assertEquals("script_a", snapshot.scriptId());
        assertEquals("杭州开发一部", snapshot.params().get("dept"));
        assertNull(snapshot.resultDigest());
        // 会话隔离: 其他会话查不到
        assertNull(executor.lastExec("alice", "conv-2"));
    }

    @Test
    void planWithoutConversationIdRecordsNothing() {
        stubExecutableDeptPlan(QUESTION);

        DirectSkillExecutor executor = newExecutor(true);
        executor.plan(QUESTION, "alice", null);

        assertNull(executor.lastExec("alice", null));
        assertNull(executor.lastExec("alice", "conv-1"));
    }

    @Test
    void askPlanDoesNotRecordSnapshot() {
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"dept\":\"deptName\",\"version\":\"versionPlan\"}", "达标率")));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(QUESTION), Mockito.isNull())).thenReturn(
                state(null, List.of()));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(
                "[{\"name\":\"dept\",\"required\":true},{\"name\":\"version\",\"required\":true}]"));

        DirectSkillExecutor executor = newExecutor(true);
        assertTrue(executor.plan(QUESTION, "alice", null, "conv-1").needsAsk());

        assertNull(executor.lastExec("alice", "conv-1"));
    }

    @Test
    void recordResultFillsDigestTruncatesAndRefreshesTtl() {
        stubExecutableDeptPlan(QUESTION);
        DirectSkillExecutor executor = newExecutor(true);
        executor.plan(QUESTION, "alice", null, "conv-1");
        long createdAtBefore = executor.lastExec("alice", "conv-1").createdAt();

        String stdout = "x".repeat(2500);
        executor.recordResult("alice", "conv-1", stdout);

        DirectSkillExecutor.LastDirectExec snapshot = executor.lastExec("alice", "conv-1");
        assertTrue(snapshot.resultDigest().endsWith("…(已截断)"));
        assertEquals(DirectSkillExecutor.RESULT_DIGEST_MAX_LENGTH + "…(已截断)".length(),
                snapshot.resultDigest().length());
        assertEquals(QUESTION, snapshot.question());
        assertTrue(snapshot.createdAt() >= createdAtBefore);

        // 空 stdout → digest 置空; 无快照会话 no-op
        executor.recordResult("alice", "conv-1", "  ");
        assertNull(executor.lastExec("alice", "conv-1").resultDigest());
        executor.recordResult("alice", "conv-other", stdout);
        assertNull(executor.lastExec("alice", "conv-other"));
    }

    @Test
    void lastExecTtlExpiryLazilyEvicts() {
        stubExecutableDeptPlan(QUESTION);
        DirectSkillExecutor executor = newExecutor(true);
        executor.plan(QUESTION, "alice", null, "conv-1");

        var store = lastExecStore(executor);
        store.replaceAll((k, v) -> new DirectSkillExecutor.LastDirectExec(v.question(), v.skillName(),
                v.scriptId(), v.params(), v.resultDigest(),
                System.currentTimeMillis() - DirectSkillExecutor.LAST_EXEC_TTL_MS - 1));

        assertNull(executor.lastExec("alice", "conv-1"));
        assertTrue(store.isEmpty());
    }

    @Test
    void snapshotFallbackFillsMissingRequiredParamFromSameSkill() {
        // §3.5: T1 直接执行 dept_skill → T2 关键词命中同 skill 但部门未解析 → 快照兜底继承,
        // 不反问不回退; T2 自身不再记录快照 (recordLastExec 只在可执行返回点之前)
        String t2 = "2026年9月份版本 达标率多少";
        stubExecutableDeptPlan(QUESTION);
        when(dimensionStateManager.previewState(Mockito.eq(t2), Mockito.isNull())).thenReturn(
                state(null, List.of()));

        DirectSkillExecutor executor = newExecutor(true);
        executor.plan(QUESTION, "alice", null, "conv-1");

        DirectSkillExecutor.DirectExecutionPlan t2Plan = executor.plan(t2, "alice", null, "conv-1");
        assertFalse(t2Plan.needsAsk());
        assertEquals("dept_skill", t2Plan.skillName());
        assertEquals("杭州开发一部", t2Plan.params().get("dept"));
    }

    @Test
    void snapshotFallbackDoesNotOverrideMentionedValue() {
        // T2 明确提到另一部门 → 本轮解析值优先, 快照只补缺不覆盖
        String t2 = "杭州开发二部 2026年9月份版本 达标率多少";
        stubExecutableDeptPlan(QUESTION);
        when(dimensionStateManager.previewState(Mockito.eq(t2), Mockito.isNull())).thenReturn(
                state(null, List.of("杭州开发二部")));

        DirectSkillExecutor executor = newExecutor(true);
        executor.plan(QUESTION, "alice", null, "conv-1");

        DirectSkillExecutor.DirectExecutionPlan t2Plan = executor.plan(t2, "alice", null, "conv-1");
        assertFalse(t2Plan.needsAsk());
        assertEquals("杭州开发二部", t2Plan.params().get("dept"));
    }

    @Test
    void snapshotFallbackSameSkillOnly() {
        // 跨 skill 参数名空间不同, 不兜底: T2 命中 other_skill 且必填缺失 → 仍反问
        String t2 = "2026年9月份版本 达标率多少";
        stubExecutableDeptPlan(QUESTION);
        DirectSkillExecutor executor = newExecutor(true);
        executor.plan(QUESTION, "alice", null, "conv-1");

        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("other_skill", "script_b", "{\"dept\":\"deptName\"}", "达标率")));
        when(usageResolver.findUsableRetrievalNames("alice")).thenReturn(
                Set.of("dept_skill", "other_skill"));
        when(dimensionStateManager.previewState(Mockito.eq(t2), Mockito.isNull())).thenReturn(
                state(null, List.of()));
        when(scriptRegistryMapper.selectByScriptId("script_b")).thenReturn(registryEntry(
                "[{\"name\":\"dept\",\"required\":true}]"));

        DirectSkillExecutor.DirectExecutionPlan t2Plan = executor.plan(t2, "alice", null, "conv-1");
        assertTrue(t2Plan.needsAsk());
    }

    // ==================== params_schema 类型保形 (direct-exec-param-type-plan.md) ====================

    private static final String ARRAY_VERSION_SCHEMA =
            "[{\"name\":\"version\",\"required\":true,\"type\":\"array\"}]";

    @Test
    void arrayTypedSchemaKeepsSingleValueAsList() {
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"version\":\"versionPlan\"}", "达标率")));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(QUESTION), Mockito.isNull())).thenReturn(
                state(versionTime(List.of("2026年7月份版本")), List.of()));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(ARRAY_VERSION_SCHEMA));

        DirectSkillExecutor.DirectExecutionPlan plan = newExecutor(true).plan(QUESTION, "alice", null);

        // type=array: 单值也保 List (修 script 侧按数组取值报错)
        assertEquals(List.of("2026年7月份版本"), plan.params().get("version"));
    }

    @Test
    void arrayTypedSchemaMultiValueUnchanged() {
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"version\":\"versionPlan\"}", "达标率")));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(QUESTION), Mockito.isNull())).thenReturn(
                state(versionTime(List.of("2026年10月份版本", "2026年11月份版本")), List.of()));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(ARRAY_VERSION_SCHEMA));

        DirectSkillExecutor.DirectExecutionPlan plan = newExecutor(true).plan(QUESTION, "alice", null);

        assertEquals(List.of("2026年10月份版本", "2026年11月份版本"), plan.params().get("version"));
    }

    @Test
    void stringTypedSchemaStillCollapsesSingleValue() {
        // 拍板②: type≠array 维持单值折叠 (start_date/end_date 现状即对)
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"dept\":\"deptName\"}", "达标率")));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(QUESTION), Mockito.isNull())).thenReturn(
                state(null, List.of("杭州开发一部")));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(
                "[{\"name\":\"dept\",\"required\":true,\"type\":\"string\"}]"));

        DirectSkillExecutor.DirectExecutionPlan plan = newExecutor(true).plan(QUESTION, "alice", null);

        assertInstanceOf(String.class, plan.params().get("dept"));
        assertEquals("杭州开发一部", plan.params().get("dept"));
    }

    @Test
    void noSchemaStillCollapsesSingleValue() {
        // 拍板①: registry 无 schema 行 → 维持现状, 不静默改存量脚本契约
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"dept\":\"deptName\"}", "达标率")));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(QUESTION), Mockito.isNull())).thenReturn(
                state(null, List.of("杭州开发一部")));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(null));

        DirectSkillExecutor.DirectExecutionPlan plan = newExecutor(true).plan(QUESTION, "alice", null);

        assertInstanceOf(String.class, plan.params().get("dept"));
    }

    @Test
    void pendingAnswerCompletesArrayTypedParamAsList() {
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"dept\":\"deptName\",\"version\":\"versionPlan\"}", "达标率")));
        enableKeyDictionary(builtinDeptNameConfig());
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(QUESTION), Mockito.isNull())).thenReturn(
                state(null, List.of()));
        when(dimensionStateManager.previewState(Mockito.eq("杭州开发二部"), Mockito.isNull())).thenReturn(
                state(null, List.of("杭州开发二部")));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(
                "[{\"name\":\"dept\",\"required\":true,\"type\":\"array\"},"
                        + "{\"name\":\"version\",\"required\":true,\"type\":\"array\"}]"));
        when(hookProvider.getIfAvailable()).thenReturn(versionPlanHook);
        when(versionPlanHook.currentVersions()).thenReturn(List.of("2026年10月份版本"));

        DirectSkillExecutor executor = newExecutor(true);
        assertTrue(executor.plan(QUESTION, "alice", null, "conv-1").needsAsk());

        DirectSkillExecutor.DirectExecutionPlan plan =
                executor.plan("杭州开发二部", "alice", null, "conv-1");
        assertFalse(plan.needsAsk());
        assertEquals(List.of("杭州开发二部"), plan.params().get("dept")); // 补参回填也保形
        assertEquals(List.of("2026年10月份版本"), plan.params().get("version")); // 预装配已保形
        assertTrue(pendingMap(executor).isEmpty());
    }

    @Test
    void ambiguousResumeArrayTypedAnchorStaysList() {
        String question = "近3周的达标率是多少";
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"range\":\"timeRange\"}", "达标率")));
        enableKeyDictionary(timeRangeConfig());
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                state(null, List.of()));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(
                "[{\"name\":\"range\",\"required\":true,\"type\":\"array\"}]"));

        DirectSkillExecutor executor = newExecutor(true);
        assertTrue(executor.plan(question, "alice", null, "conv-1").needsAsk());

        DirectSkillExecutor.DirectExecutionPlan done = executor.plan("历史", "alice", null, "conv-1");
        assertFalse(done.needsAsk());
        assertEquals(List.of("LAST:3w"), done.params().get("range"));
    }

    @Test
    void snapshotFallbackKeepsArrayShapeFromPreviousRound() {
        // T1 dept 单值已传 (List) → T2 部门未提及快照兜底 → 继承 List 形状
        String t2 = "2026年9月份版本 达标率多少";
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"dept\":\"deptName\"}", "达标率")));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(QUESTION), Mockito.isNull())).thenReturn(
                state(null, List.of("杭州开发一部")));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(
                "[{\"name\":\"dept\",\"required\":true,\"type\":\"array\"}]"));

        DirectSkillExecutor executor = newExecutor(true);
        executor.plan(QUESTION, "alice", null, "conv-1");

        when(dimensionStateManager.previewState(Mockito.eq(t2), Mockito.isNull())).thenReturn(
                state(versionTime(List.of("2026年9月份版本")), List.of()));
        DirectSkillExecutor.DirectExecutionPlan t2Plan = executor.plan(t2, "alice", null, "conv-1");
        assertFalse(t2Plan.needsAsk());
        assertEquals(List.of("杭州开发一部"), t2Plan.params().get("dept"));
    }

    @Test
    void malformedSchemaJsonDoesNotThrowAndKeepsOriginalSemantics() {
        // 解析失败 = 白名单空 = 不传参不反问 (原 parseNames 语义), 不抛异常
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"dept\":\"deptName\"}", "达标率")));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(QUESTION), Mockito.isNull())).thenReturn(
                state(null, List.of("杭州开发一部")));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry("{not json"));

        DirectSkillExecutor.DirectExecutionPlan plan = newExecutor(true).plan(QUESTION, "alice", null);

        assertFalse(plan.params().containsKey("dept"));
    }

    // ==================== 时间二选一 (双时间键脚本, 2026/10/21 规则) ====================

    private static DimensionState.TimeDimension quarterTime(List<String> values) {
        return new DimensionState.TimeDimension(DimensionState.TimeDimensionType.QUARTER, values);
    }

    private static final String BOTH_TIME_MAPPING =
            "{\"version\":\"versionPlan\",\"quarter\":\"vestQuarter\","
                    + "\"qStart\":\"vestQuarter.start\",\"qEnd\":\"vestQuarter.end\"}";

    private void stubBothTimeSkill(String question, DimensionState.TimeDimension time) {
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", BOTH_TIME_MAPPING, "达标率")));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                state(time, List.of("杭州开发一部")));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(
                "[{\"name\":\"version\",\"required\":false},{\"name\":\"quarter\",\"required\":false},"
                        + "{\"name\":\"qStart\",\"required\":false},{\"name\":\"qEnd\",\"required\":false}]"));
    }

    @Test
    void bothTimeKeysVersionMentionedPassesVersionOnly() {
        // 「12月版」显式提及版本计划 → 只传 version, 季度 (含 .start/.end) 整体抑制不再派生
        stubBothTimeSkill(QUESTION, versionTime(List.of("2026年12月份版本")));

        DirectSkillExecutor.DirectExecutionPlan plan = newExecutor(true).plan(QUESTION, "alice", null);

        assertEquals("2026年12月份版本", plan.params().get("version"));
        assertFalse(plan.params().containsKey("quarter"));
        assertFalse(plan.params().containsKey("qStart"));
        assertFalse(plan.params().containsKey("qEnd"));
    }

    @Test
    void bothTimeKeysQuarterMentionedPassesQuarterOnly() {
        // 显式提及季度 → 只传 quarter, 版本 (含默认在建版本) 不传
        stubBothTimeSkill("2026年4季度 达标率多少", quarterTime(List.of("2026年4季度")));

        DirectSkillExecutor.DirectExecutionPlan plan =
                newExecutor(true).plan("2026年4季度 达标率多少", "alice", null);

        assertEquals("2026年4季度", plan.params().get("quarter"));
        assertFalse(plan.params().containsKey("version"));
    }

    @Test
    void bothTimeKeysNothingMentionedDefaultsToInBuildVersion() {
        // 都未提及 → 默认在建版本计划, 只传 version
        stubBothTimeSkill(QUESTION, null);
        when(hookProvider.getIfAvailable()).thenReturn(versionPlanHook);
        when(versionPlanHook.currentVersions()).thenReturn(List.of("2026年10月份版本"));

        DirectSkillExecutor.DirectExecutionPlan plan = newExecutor(true).plan(QUESTION, "alice", null);

        assertEquals("2026年10月份版本", plan.params().get("version"));
        assertFalse(plan.params().containsKey("quarter"));
        assertFalse(plan.params().containsKey("qStart"));
    }

    @Test
    void bothTimeKeysThisVAnchorPassesVersionOnly() {
        // 变体锚点 (口语值方案): 「在建版本/当前/今年以来」等 → THIS_V/YEAR_V 落 versionPlan,
        // 判定看 extraction 字段与锚点形态无关 → 版本胜, THIS_V 执行期展开为在建版本
        stubBothTimeSkill(QUESTION, versionTime(List.of("THIS_V")));

        DirectSkillExecutor.DirectExecutionPlan plan =
                newExecutorWithResolver(stubResolver()).plan(QUESTION, "alice", null);

        assertEquals("2026年10月份版本", plan.params().get("version"));
        assertFalse(plan.params().containsKey("quarter"));
    }

    @Test
    void bothTimeKeysThisQAnchorPassesQuarterOnly() {
        // 「当季/在建季度」→ THIS_Q 落 vestQuarter → 季度胜, 执行期展开为季度值
        stubBothTimeSkill(QUESTION, quarterTime(List.of("THIS_Q")));

        DirectSkillExecutor.DirectExecutionPlan plan =
                newExecutorWithResolver(stubResolver()).plan(QUESTION, "alice", null);

        assertFalse(plan.params().containsKey("version"));
        assertEquals("2026年4季度", plan.params().get("quarter"));
    }

    // ==================== 需求项/需求子条目特例 (2026/10/21 拍板) ====================

    private static DimensionState stateWithPeer(DimensionState.PeerDimensionType type, List<String> values) {
        return new DimensionState(null, List.of(), new DimensionState.PeerDimension(type, values), null);
    }

    private static final String BOTH_TIME_REQ_MAPPING =
            "{\"version\":\"versionPlan\",\"quarter\":\"vestQuarter\","
                    + "\"itemNo\":\"itemNo\",\"subItemNo\":\"subItemNo\"}";

    private void stubBothTimeReqSkill(String question, DimensionState state) {
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", BOTH_TIME_REQ_MAPPING, "达标率")));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(state);
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(
                "[{\"name\":\"version\",\"required\":false},{\"name\":\"quarter\",\"required\":false},"
                        + "{\"name\":\"itemNo\",\"required\":false},{\"name\":\"subItemNo\",\"required\":false}]"));
    }

    @Test
    void requirementWithoutTimePassesNoTimeParams() {
        // 需求子条目 + 未显式提及时间 → 不补默认在建版本, version/quarter 都不传 (值为空)
        stubBothTimeReqSkill("S20250926-000741 达标率多少",
                stateWithPeer(DimensionState.PeerDimensionType.SUB_REQUIREMENT,
                        List.of("S20250926-000741")));
        when(hookProvider.getIfAvailable()).thenReturn(versionPlanHook);
        when(versionPlanHook.currentVersions()).thenReturn(List.of("2026年10月份版本"));

        DirectSkillExecutor.DirectExecutionPlan plan =
                newExecutor(true).plan("S20250926-000741 达标率多少", "alice", null);

        assertEquals("S20250926-000741", plan.params().get("subItemNo"));
        assertFalse(plan.params().containsKey("version"));
        assertFalse(plan.params().containsKey("quarter"));
    }

    @Test
    void requirementWithExplicitVersionStillPassesVersion() {
        // 需求项 + 显式「12月版」→ 特例不生效, 只传版本 (互不换算 R1 同口径)
        DimensionState state = new DimensionState(versionTime(List.of("2026年12月份版本")), List.of(),
                new DimensionState.PeerDimension(DimensionState.PeerDimensionType.REQUIREMENT,
                        List.of("R2025-001")), null);
        stubBothTimeReqSkill("R2025-001 12月版达标率", state);

        DirectSkillExecutor.DirectExecutionPlan plan =
                newExecutor(true).plan("R2025-001 12月版达标率", "alice", null);

        assertEquals("2026年12月份版本", plan.params().get("version"));
        assertEquals("R2025-001", plan.params().get("itemNo"));
        assertFalse(plan.params().containsKey("quarter"));
    }

    @Test
    void requirementSingleFamilyVersionSkillSkipsDefaultToo() {
        // 单家族版本 skill 同样受特例约束: 需求项问题不补默认在建版本 (规则跟问题走, 不跟 skill 走)
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"version\":\"versionPlan\"}", "达标率")));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq("S20250926-000741 达标率多少"), Mockito.isNull()))
                .thenReturn(stateWithPeer(DimensionState.PeerDimensionType.SUB_REQUIREMENT,
                        List.of("S20250926-000741")));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(
                "[{\"name\":\"version\",\"required\":false}]"));
        when(hookProvider.getIfAvailable()).thenReturn(versionPlanHook);
        when(versionPlanHook.currentVersions()).thenReturn(List.of("2026年10月份版本"));

        DirectSkillExecutor.DirectExecutionPlan plan =
                newExecutor(true).plan("S20250926-000741 达标率多少", "alice", null);

        assertFalse(plan.params().containsKey("version"));
    }

    @Test
    void projectWithoutTimePassesNoTimeParams() {
        // 项目编号 + 未显式提及时间 → 不补默认在建版本 (docs/202611/内置维度键projectNo项目编号方案.md 拍板①)
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a",
                        "{\"version\":\"versionPlan\",\"quarter\":\"vestQuarter\",\"projectNo\":\"projectNo\"}",
                        "达标率")));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq("KJ99990273-412 达标率多少"), Mockito.isNull()))
                .thenReturn(stateWithPeer(DimensionState.PeerDimensionType.PROJECT,
                        List.of("KJ99990273-412")));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(
                "[{\"name\":\"version\",\"required\":false},{\"name\":\"quarter\",\"required\":false},"
                        + "{\"name\":\"projectNo\",\"required\":false}]"));
        when(hookProvider.getIfAvailable()).thenReturn(versionPlanHook);
        when(versionPlanHook.currentVersions()).thenReturn(List.of("2026年10月份版本"));

        DirectSkillExecutor.DirectExecutionPlan plan =
                newExecutor(true).plan("KJ99990273-412 达标率多少", "alice", null);

        assertEquals("KJ99990273-412", plan.params().get("projectNo"));
        assertFalse(plan.params().containsKey("version"));
        assertFalse(plan.params().containsKey("quarter"));
    }

    @Test
    void projectWithExplicitVersionStillPassesVersion() {
        // 项目编号 + 显式版本 → 特例不生效, 只传版本
        DimensionState state = new DimensionState(versionTime(List.of("2026年12月份版本")), List.of(),
                new DimensionState.PeerDimension(DimensionState.PeerDimensionType.PROJECT,
                        List.of("KJ20260114")), null);
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a",
                        "{\"version\":\"versionPlan\",\"projectNo\":\"projectNo\"}", "达标率")));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq("KJ20260114 12月版达标率"), Mockito.isNull()))
                .thenReturn(state);
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(
                "[{\"name\":\"version\",\"required\":false},{\"name\":\"projectNo\",\"required\":false}]"));

        DirectSkillExecutor.DirectExecutionPlan plan =
                newExecutor(true).plan("KJ20260114 12月版达标率", "alice", null);

        assertEquals("2026年12月份版本", plan.params().get("version"));
        assertEquals("KJ20260114", plan.params().get("projectNo"));
        assertFalse(plan.params().containsKey("quarter"));
    }

    // ==================== 人员台账: 我X enrich + 人名扫描 + 重名反问 (人员台账维度方案 §3/§4) ====================

    @SuppressWarnings("unchecked")
    private final ObjectProvider<com.agentscopea2a.v2.dimension.PersonalDimensionResolver>
            personalResolverProvider = mock(ObjectProvider.class);

    private DirectSkillExecutor newExecutorWithLedger(
            com.agentscopea2a.v2.dimension.PersonLedgerRepository ledger) {
        when(personalResolverProvider.getIfAvailable()).thenReturn(
                new com.agentscopea2a.v2.dimension.PersonalDimensionResolver(ledger));
        return new DirectSkillExecutor(metadataRepository, selector, usageResolver, indexRepo,
                scriptRegistryMapper, dimensionStateManager, hookProvider, dimensionKeyRepoProvider,
                null, null, personalResolverProvider, objectMapper, true);
    }

    private static com.agentscopea2a.v2.dimension.PersonLedgerRepository.PersonLedgerRow ledgerRow(
            String dept, String group, String app, String productLine, String name) {
        return ledgerRow(dept, group, app, productLine, name, new String[0]);
    }

    private static com.agentscopea2a.v2.dimension.PersonLedgerRepository.PersonLedgerRow ledgerRow(
            String dept, String group, String app, String productLine, String name, String... roles) {
        return new com.agentscopea2a.v2.dimension.PersonLedgerRepository.PersonLedgerRow(
                dept == null ? List.of() : List.of(dept),
                group == null ? List.of() : List.of(group),
                app == null ? List.of() : List.of(app),
                productLine == null ? List.of() : List.of(productLine),
                List.of(roles), name, "555153289");
    }

    private static com.agentscopea2a.v2.dimension.PersonLedgerRepository.PersonIdentity identity(
            String perNo, String dept) {
        return identity(perNo, dept, new String[0]);
    }

    private static com.agentscopea2a.v2.dimension.PersonLedgerRepository.PersonIdentity identity(
            String perNo, String dept, String... roles) {
        return new com.agentscopea2a.v2.dimension.PersonLedgerRepository.PersonIdentity(
                "张三", perNo, dept == null ? List.of() : List.of(dept), List.of(), List.of(roles));
    }

    @Test
    void myDeptEnrichesDeptNameFromLedger() {
        // 我部 → 台账部门注入 extraction (§3.3 主场景)
        String question = "我部2026年7月份版本达标率多少";
        com.agentscopea2a.v2.dimension.PersonLedgerRepository ledger =
                mock(com.agentscopea2a.v2.dimension.PersonLedgerRepository.class);
        when(ledger.byUserId("555153289")).thenReturn(ledgerRow("杭州开发三部", null, null, null, "徐三金"));
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"dept\":\"deptName\"}", "达标率")));
        allowAll("555153289");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                state(null, List.of()));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(null));

        DirectSkillExecutor.DirectExecutionPlan plan =
                newExecutorWithLedger(ledger).plan(question, "555153289", null);

        assertEquals(List.of("杭州开发三部"), plan.extraction().deptName());
        assertEquals("杭州开发三部", plan.params().get("dept"));
    }

    @Test
    void explicitDeptKeepsPriorityAndLedgerValueAppends() {
        // 显式值优先保留, 台账多值追加去重 (§3.3 merge 语义)
        String question = "我部和杭州开发二部2026年7月份版本达标率";
        com.agentscopea2a.v2.dimension.PersonLedgerRepository ledger =
                mock(com.agentscopea2a.v2.dimension.PersonLedgerRepository.class);
        when(ledger.byUserId("555153289")).thenReturn(ledgerRow("杭州开发三部", null, null, null, "徐三金"));
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"dept\":\"deptName\"}", "达标率")));
        allowAll("555153289");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                state(null, List.of("杭州开发二部")));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(null));

        DirectSkillExecutor.DirectExecutionPlan plan =
                newExecutorWithLedger(ledger).plan(question, "555153289", null);

        assertEquals(List.of("杭州开发二部", "杭州开发三部"), plan.extraction().deptName());
        assertEquals(List.of("杭州开发二部", "杭州开发三部"), plan.params().get("dept"));
    }

    @Test
    void myselfFillsPerNoFromUserIdEvenWhenLedgerUnknown() {
        // 裸"我" + 台账查无此人: perNo=userId 直传 (不查库), perName 无值
        String question = "我 达标率多少";
        com.agentscopea2a.v2.dimension.PersonLedgerRepository ledger =
                mock(com.agentscopea2a.v2.dimension.PersonLedgerRepository.class);
        when(ledger.byUserId("555153289")).thenReturn(null);
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"person\":\"perNo\"}", "达标率")));
        allowAll("555153289");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                state(null, List.of()));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(null));

        DirectSkillExecutor.DirectExecutionPlan plan =
                newExecutorWithLedger(ledger).plan(question, "555153289", null);

        assertEquals(List.of("555153289"), plan.extraction().perNo());
        assertEquals("555153289", plan.params().get("person"));
    }

    @Test
    void anonymousMyDeptDegradesWithoutInjection() {
        // 匿名 (§3.5): 命中标志保留但台账不可用 → 不注入维度, 问题回退 LLM 语义
        String question = "我部达标率";
        com.agentscopea2a.v2.dimension.PersonLedgerRepository ledger =
                mock(com.agentscopea2a.v2.dimension.PersonLedgerRepository.class);
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"dept\":\"deptName\"}", "达标率")));
        // 匿名用户 (userId=null): canUse 仅 builtin 豁免 → 用 AUTO_SYNTHESIZED 源放行
        when(indexRepo.findByName("dept_skill")).thenReturn(Optional.of(new SkillEntry(
                "dept_skill", "fp", "desc", 1, 0, null, SkillEntry.STATUS_ACTIVE,
                SkillEntry.SOURCE_AUTO_SYNTHESIZED, null, null)));
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                state(null, List.of()));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(null));

        DirectSkillExecutor.DirectExecutionPlan plan =
                newExecutorWithLedger(ledger).plan(question, null, null);

        assertTrue(plan.extraction().deptName().isEmpty());
        assertFalse(plan.params().containsKey("dept"));
        // 匿名降级: 不查台账归属 (人名字典照常可扫)
        org.mockito.Mockito.verify(ledger, org.mockito.Mockito.never()).byUserId(Mockito.anyString());
    }

    @Test
    void personNameUniqueHitFillsPerNoAndPerName() {
        // 人名唯一命中 (§4.1/§4.2): perNo=统一认证号, perName=姓名
        String question = "徐三金2026年7月份版本达标率多少";
        com.agentscopea2a.v2.dimension.PersonLedgerRepository ledger =
                mock(com.agentscopea2a.v2.dimension.PersonLedgerRepository.class);
        when(ledger.nameDictionary()).thenReturn(Set.of("徐三金"));
        when(ledger.byName("徐三金")).thenReturn(List.of(
                new com.agentscopea2a.v2.dimension.PersonLedgerRepository.PersonIdentity(
                        "徐三金", "555153289", List.of("杭州开发三部"), List.of())));
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"perNo\":\"perNo\",\"perName\":\"perName\"}", "达标率")));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                state(null, List.of()));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(null));

        DirectSkillExecutor.DirectExecutionPlan plan =
                newExecutorWithLedger(ledger).plan(question, "alice", null);

        assertEquals("555153289", plan.params().get("perNo"));
        assertEquals("徐三金", plan.params().get("perName"));
        assertEquals(List.of("555153289"), plan.extraction().perNo());
    }

    @Test
    void ambiguousPersonTriggersAskAndIndexReplyResolves() {
        // 重名反问 (§4.2 直接执行轨): askMessage + pending marker; 序号回复 → perNo 落地继续执行
        String question = "张三的达标率";
        com.agentscopea2a.v2.dimension.PersonLedgerRepository ledger =
                mock(com.agentscopea2a.v2.dimension.PersonLedgerRepository.class);
        when(ledger.nameDictionary()).thenReturn(Set.of("张三"));
        when(ledger.byName("张三")).thenReturn(List.of(identity("111", "杭州开发一部"),
                identity("222", "杭州开发二部")));
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"perNo\":\"perNo\"}", "达标率")));
        enableKeyDictionary(builtinDeptNameConfig());
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                state(null, List.of()));
        when(dimensionStateManager.previewState(Mockito.eq("2"), Mockito.isNull())).thenReturn(
                state(null, List.of()));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(
                "[{\"name\":\"perNo\",\"required\":true}]"));

        DirectSkillExecutor executor = newExecutorWithLedger(ledger);
        DirectSkillExecutor.DirectExecutionPlan ask = executor.plan(question, "alice", null, "conv-1");
        assertTrue(ask.needsAsk());
        assertTrue(ask.askMessage().contains("张三"));
        assertTrue(ask.askMessage().contains("序号"));
        assertTrue(ask.askMessage().contains("杭州开发二部")); // displaySuffix 部门提示
        var map = pendingMap(executor);
        assertEquals(1, map.size());
        assertTrue(map.values().iterator().next().missingKeys()
                .contains(DirectSkillExecutor.PERSON_MARKER_PREFIX + "张三"));

        DirectSkillExecutor.DirectExecutionPlan done = executor.plan("2", "alice", null, "conv-1");
        assertFalse(done.needsAsk());
        assertEquals("222", done.params().get("perNo"));
        assertTrue(map.isEmpty());
    }

    @Test
    void ambiguousPersonReplyByDeptHintMatches() {
        String question = "张三的达标率";
        com.agentscopea2a.v2.dimension.PersonLedgerRepository ledger =
                mock(com.agentscopea2a.v2.dimension.PersonLedgerRepository.class);
        when(ledger.nameDictionary()).thenReturn(Set.of("张三"));
        when(ledger.byName("张三")).thenReturn(List.of(identity("111", "杭州开发一部"),
                identity("222", "杭州开发二部")));
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"perNo\":\"perNo\"}", "达标率")));
        enableKeyDictionary(builtinDeptNameConfig());
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                state(null, List.of()));
        when(dimensionStateManager.previewState(Mockito.eq("杭州开发二部"), Mockito.isNull())).thenReturn(
                state(null, List.of("杭州开发二部")));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(
                "[{\"name\":\"perNo\",\"required\":true}]"));

        DirectSkillExecutor executor = newExecutorWithLedger(ledger);
        assertTrue(executor.plan(question, "alice", null, "conv-1").needsAsk());

        // 部门名包含匹配 (§4.2 答复协议): 回复部门 → 命中该部门下的张三
        DirectSkillExecutor.DirectExecutionPlan done =
                executor.plan("杭州开发二部", "alice", null, "conv-1");
        assertFalse(done.needsAsk());
        assertEquals("222", done.params().get("perNo"));
        assertTrue(pendingMap(executor).isEmpty());
    }

    @Test
    void ambiguousPersonIrrelevantReplyDropsPendingAndFallsThrough() {
        String question = "张三的达标率";
        com.agentscopea2a.v2.dimension.PersonLedgerRepository ledger =
                mock(com.agentscopea2a.v2.dimension.PersonLedgerRepository.class);
        when(ledger.nameDictionary()).thenReturn(Set.of("张三"));
        when(ledger.byName("张三")).thenReturn(List.of(identity("111", "杭州开发一部"),
                identity("222", "杭州开发二部")));
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"perNo\":\"perNo\"}", "达标率")));
        enableKeyDictionary(builtinDeptNameConfig());
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                state(null, List.of()));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(
                "[{\"name\":\"perNo\",\"required\":true}]"));

        DirectSkillExecutor executor = newExecutorWithLedger(ledger);
        assertTrue(executor.plan(question, "alice", null, "conv-1").needsAsk());

        // 答复匹配不出 → pending 丢弃走正常链路 (口径同缺参补参轮)
        assertNull(executor.plan("今天天气不错", "alice", null, "conv-1"));
        assertTrue(pendingMap(executor).isEmpty());
    }

    @Test
    void ambiguousPersonWithoutConversationIdDegradesToFallback() {
        // 无会话键: 剥离反问降级 (重名人不填值), perNo 必填缺失 → 回退 LLM
        String question = "张三的达标率";
        com.agentscopea2a.v2.dimension.PersonLedgerRepository ledger =
                mock(com.agentscopea2a.v2.dimension.PersonLedgerRepository.class);
        when(ledger.nameDictionary()).thenReturn(Set.of("张三"));
        when(ledger.byName("张三")).thenReturn(List.of(identity("111", "杭州开发一部"),
                identity("222", "杭州开发二部")));
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"perNo\":\"perNo\"}", "达标率")));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                state(null, List.of()));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(
                "[{\"name\":\"perNo\",\"required\":true}]"));

        assertNull(newExecutorWithLedger(ledger).plan(question, "alice", null));
    }

    @Test
    void personNameInMaskedSpanIsNotScannedAsPerson() {
        // 姓名含在屏蔽 span 内不误收 (§4.3): 组名里的"张三"不进 perName
        String question = "张三组的达标率";
        com.agentscopea2a.v2.dimension.PersonLedgerRepository ledger =
                mock(com.agentscopea2a.v2.dimension.PersonLedgerRepository.class);
        when(ledger.nameDictionary()).thenReturn(Set.of("张三"));
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"perNo\":\"perNo\"}", "达标率")));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                stateWithPeer(DimensionState.PeerDimensionType.TEAM, List.of("张三组")));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(null));

        DirectSkillExecutor.DirectExecutionPlan plan =
                newExecutorWithLedger(ledger).plan(question, "alice", null);

        // "张三组" 被组屏蔽 → 扫描不到人名; perNo 无值不传参
        assertTrue(plan.extraction().perName().isEmpty());
        assertTrue(plan.extraction().perNo().isEmpty());
        assertFalse(plan.params().containsKey("perNo"));
        org.mockito.Mockito.verify(ledger, org.mockito.Mockito.never()).byName(Mockito.anyString());
    }

    // ==================== perRole 岗位角色 (岗位角色内置维度方案 §3.1b/§3.1d/§4.3) ====================

    @Test
    void explicitRoleExtractOverridesLedgerIdentityRoles() {
        // 路径A 显式提取覆盖 identity.roles 兜底 (§3.3: 显式值是行过滤声明, 不并集)
        String question = "张三担任测试经理的达标率";
        com.agentscopea2a.v2.dimension.PersonLedgerRepository ledger =
                mock(com.agentscopea2a.v2.dimension.PersonLedgerRepository.class);
        when(ledger.nameDictionary()).thenReturn(Set.of("张三"));
        when(ledger.byName("张三")).thenReturn(List.of(identity("111", "杭州开发一部", "开发工程师")));
        when(ledger.roleDictionary()).thenReturn(Set.of("测试经理", "项目经理"));
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"role\":\"perRole\"}", "达标率")));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                state(null, List.of()));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(null));

        DirectSkillExecutor.DirectExecutionPlan plan =
                newExecutorWithLedger(ledger).plan(question, "alice", null);

        assertEquals(List.of("测试经理"), plan.extraction().perRole());
        assertEquals("测试经理", plan.params().get("role"));
    }

    @Test
    void roleDictionaryScanFillsPerRoleWithoutKeyword() {
        // 路径B 字典扫描 (无"担任"关键字): 人锚 (张三) 共存 → 收角色
        String question = "张三项目经理的达标率";
        com.agentscopea2a.v2.dimension.PersonLedgerRepository ledger =
                mock(com.agentscopea2a.v2.dimension.PersonLedgerRepository.class);
        when(ledger.nameDictionary()).thenReturn(Set.of("张三"));
        when(ledger.byName("张三")).thenReturn(List.of(identity("111", "杭州开发一部")));
        when(ledger.roleDictionary()).thenReturn(Set.of("项目经理", "测试经理"));
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"role\":\"perRole\"}", "达标率")));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                state(null, List.of()));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(null));

        DirectSkillExecutor.DirectExecutionPlan plan =
                newExecutorWithLedger(ledger).plan(question, "alice", null);

        assertEquals(List.of("项目经理"), plan.extraction().perRole());
        assertEquals("项目经理", plan.params().get("role"));
    }

    @Test
    void roleDictionaryScanSkippedWithoutPersonAnchor() {
        // 无具体人 (§7-4 边界): "项目经理的达标率" 按角色全量查询不收 → perRole 空
        String question = "项目经理的达标率";
        com.agentscopea2a.v2.dimension.PersonLedgerRepository ledger =
                mock(com.agentscopea2a.v2.dimension.PersonLedgerRepository.class);
        when(ledger.nameDictionary()).thenReturn(Set.of("张三"));
        when(ledger.roleDictionary()).thenReturn(Set.of("项目经理"));
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"role\":\"perRole\"}", "达标率")));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                state(null, List.of()));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(null));

        DirectSkillExecutor.DirectExecutionPlan plan =
                newExecutorWithLedger(ledger).plan(question, "alice", null);

        assertTrue(plan.extraction().perRole().isEmpty());
        assertFalse(plan.params().containsKey("role"));
    }

    @Test
    void myselfFillsPerRoleFromLedgerRoles() {
        // 裸"我" → 台账岗位角色兜底填充 (§3.3 填充时机表)
        String question = "对比一下，我 10月的达标率";
        com.agentscopea2a.v2.dimension.PersonLedgerRepository ledger =
                mock(com.agentscopea2a.v2.dimension.PersonLedgerRepository.class);
        when(ledger.byUserId("555153289")).thenReturn(
                ledgerRow("杭州开发三部", null, null, null, "徐三金", "开发工程师", "测试经理"));
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"role\":\"perRole\"}", "达标率")));
        allowAll("555153289");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                state(null, List.of()));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(null));

        DirectSkillExecutor.DirectExecutionPlan plan =
                newExecutorWithLedger(ledger).plan(question, "555153289", null);

        assertEquals(List.of("开发工程师", "测试经理"), plan.extraction().perRole());
        assertEquals(List.of("开发工程师", "测试经理"), plan.params().get("role"));
    }

    @Test
    void explicitRoleSurvivesMyDeptReanchor() {
        // R2 联动: 我部重锚清继承组/产品线, 但显式提取的角色保留 (人未提及, 显式非空)
        String question = "我部担任项目经理的达标率";
        com.agentscopea2a.v2.dimension.PersonLedgerRepository ledger =
                mock(com.agentscopea2a.v2.dimension.PersonLedgerRepository.class);
        when(ledger.byUserId("555153289")).thenReturn(ledgerRow("杭州产品部", null, null, null, "徐三金"));
        when(metadataRepository.findActive()).thenReturn(List.of(meta("dept_skill", "script_a",
                "{\"dev_dept\":\"deptName\",\"stat_group\":\"groupName\",\"role\":\"perRole\"}", "达标率")));
        allowAll("555153289");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                stateWithPeer(DimensionState.PeerDimensionType.TEAM, List.of("集成管理组")));
        stubAnalysis(question, analysis(null, false));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(null));

        DirectSkillExecutor.DirectExecutionPlan plan =
                newExecutorWithLedger(ledger).plan(question, "555153289", null);

        assertEquals(List.of("项目经理"), plan.extraction().perRole());
        assertEquals("项目经理", plan.params().get("role"));
        assertEquals("杭州产品部", plan.params().get("dev_dept"));
        assertFalse(plan.params().containsKey("stat_group")); // 重锚抑制照常
    }

    // ==================== 我X按需注入与作用域重锚 (docs/202610/我X按需注入与作用域重锚方案.md) ====================

    private static QuestionAnalysis analysis(QuestionAnalysis.ExplicitDimensions explicit,
                                             boolean hasReference) {
        QuestionAnalysis a = new QuestionAnalysis();
        a.setHasReference(hasReference);
        a.setExplicitDimensions(explicit);
        return a;
    }

    private void stubAnalysis(String question, QuestionAnalysis a) {
        when(dimensionStateManager.analyzeQuestionRuleBased(question)).thenReturn(a);
    }

    @Test
    void myDeptDoesNotInjectLedgerGroupAndProductLine() {
        // R1 核心场景 (2026-10-23 E2E): "我部4季度需求项" 只注部门, 台账组/产品线不再连带
        String question = "我部4季度需求项的平均生命周期";
        com.agentscopea2a.v2.dimension.PersonLedgerRepository ledger =
                mock(com.agentscopea2a.v2.dimension.PersonLedgerRepository.class);
        when(ledger.byUserId("555153289")).thenReturn(
                ledgerRow("杭州产品部", "集成管理组", null, "集成管理", "徐三金"));
        when(metadataRepository.findActive()).thenReturn(List.of(meta("dept_skill", "script_a",
                "{\"dev_dept\":\"deptName\",\"stat_group\":\"groupName\",\"product_line\":\"productLine\"}",
                "生命周期")));
        allowAll("555153289");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                state(null, List.of()));
        stubAnalysis(question, analysis(null, false));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(null));

        DirectSkillExecutor.DirectExecutionPlan plan =
                newExecutorWithLedger(ledger).plan(question, "555153289", null);

        assertEquals(List.of("杭州产品部"), plan.extraction().deptName());
        assertTrue(plan.extraction().groupName().isEmpty());
        assertTrue(plan.extraction().productLine().isEmpty());
        assertEquals("杭州产品部", plan.params().get("dev_dept"));
        assertFalse(plan.params().containsKey("stat_group"));
        assertFalse(plan.params().containsKey("product_line"));
    }

    @Test
    void myGroupInjectsDeptAndGroupTogether() {
        // R1 §6-1 拍板: 我组 = deptName + groupName 双注 (组名跨部门可能重名, 连带台账部门)
        String question = "我的组2026年9月份版本达标率多少";
        com.agentscopea2a.v2.dimension.PersonLedgerRepository ledger =
                mock(com.agentscopea2a.v2.dimension.PersonLedgerRepository.class);
        when(ledger.byUserId("555153289")).thenReturn(
                ledgerRow("杭州开发三部", "数据智能组", null, null, "徐三金"));
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"dev_dept\":\"deptName\",\"stat_group\":\"groupName\"}", "达标率")));
        allowAll("555153289");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                state(null, List.of()));
        stubAnalysis(question, analysis(null, false));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(null));

        DirectSkillExecutor.DirectExecutionPlan plan =
                newExecutorWithLedger(ledger).plan(question, "555153289", null);

        assertEquals(List.of("杭州开发三部"), plan.extraction().deptName());
        assertEquals(List.of("数据智能组"), plan.extraction().groupName());
        assertEquals("数据智能组", plan.params().get("stat_group"));
        assertEquals("杭州开发三部", plan.params().get("dev_dept"));
    }

    @Test
    void myAppAndMyProductLineInjectIndependently() {
        // R1 per-type 自身值 + 2026/10/30 门控方案 §2.3: 部门一项恢复全量连带 (组仍不跟入)
        String question = "我的产品线和我的应用对比";
        com.agentscopea2a.v2.dimension.PersonLedgerRepository ledger =
                mock(com.agentscopea2a.v2.dimension.PersonLedgerRepository.class);
        when(ledger.byUserId("555153289")).thenReturn(
                ledgerRow("杭州开发三部", "数据智能组", "F-GCS", "集成管理", "徐三金"));
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"dev_dept\":\"deptName\",\"pl\":\"productLine\",\"app\":\"appName\",\"stat_group\":\"groupName\"}", "对比")));
        allowAll("555153289");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                state(null, List.of()));
        stubAnalysis(question, analysis(null, false));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(null));

        DirectSkillExecutor.DirectExecutionPlan plan =
                newExecutorWithLedger(ledger).plan(question, "555153289", null);

        assertEquals(List.of("集成管理"), plan.extraction().productLine());
        assertEquals(List.of("F-GCS"), plan.extraction().appName());
        assertTrue(plan.extraction().groupName().isEmpty());
        // 双命中同属一人 → 台账部门去重合并
        assertEquals(List.of("杭州开发三部"), plan.extraction().deptName());
        assertEquals("杭州开发三部", plan.params().get("dev_dept"));
        assertFalse(plan.params().containsKey("stat_group"));
    }

    @Test
    void myselfBringsLedgerDeptWithoutPeer() {
        // 2026/10/30 拍板 A: 裸我默认带出台账部门; peer 三键同级互斥未指定一律不带
        String question = "我 10月份版本的达标率";
        com.agentscopea2a.v2.dimension.PersonLedgerRepository ledger =
                mock(com.agentscopea2a.v2.dimension.PersonLedgerRepository.class);
        when(ledger.byUserId("555153289")).thenReturn(
                ledgerRow("杭州开发三部", "数据智能组", "F-GCS", "集成管理", "徐三金"));
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"dev_dept\":\"deptName\",\"stat_group\":\"groupName\",\"person\":\"perNo\"}", "达标率")));
        allowAll("555153289");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                state(null, List.of()));
        stubAnalysis(question, analysis(null, false));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(null));

        DirectSkillExecutor.DirectExecutionPlan plan =
                newExecutorWithLedger(ledger).plan(question, "555153289", null);

        assertEquals(List.of("杭州开发三部"), plan.extraction().deptName());
        assertEquals("杭州开发三部", plan.params().get("dev_dept"));
        assertEquals(List.of("555153289"), plan.extraction().perNo());
        assertTrue(plan.extraction().groupName().isEmpty());
        assertTrue(plan.extraction().appName().isEmpty());
        assertTrue(plan.extraction().productLine().isEmpty());
        assertFalse(plan.params().containsKey("stat_group"));
    }

    @Test
    void anonymousMyselfStillFillsPerNoWithoutDept() {
        // 裸我 + 台账查无此人: perNo=userId 直传, 部门不注入 (ledger null)
        String question = "我 达标率多少";
        com.agentscopea2a.v2.dimension.PersonLedgerRepository ledger =
                mock(com.agentscopea2a.v2.dimension.PersonLedgerRepository.class);
        when(ledger.byUserId("555153289")).thenReturn(null);
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"dev_dept\":\"deptName\",\"person\":\"perNo\"}", "达标率")));
        allowAll("555153289");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                state(null, List.of()));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(null));

        DirectSkillExecutor.DirectExecutionPlan plan =
                newExecutorWithLedger(ledger).plan(question, "555153289", null);

        assertEquals(List.of("555153289"), plan.extraction().perNo());
        assertTrue(plan.extraction().deptName().isEmpty());
        assertFalse(plan.params().containsKey("dev_dept"));
    }

    @Test
    void inheritedPeerClearedByMyDeptReanchor() {
        // R2: 我部重锚 → 上一轮继承的组过滤值清空 (AND 过度过滤根因②)
        String question = "我部4季度需求项的平均生命周期";
        com.agentscopea2a.v2.dimension.PersonLedgerRepository ledger =
                mock(com.agentscopea2a.v2.dimension.PersonLedgerRepository.class);
        when(ledger.byUserId("555153289")).thenReturn(ledgerRow("杭州产品部", null, null, null, "徐三金"));
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"dev_dept\":\"deptName\",\"stat_group\":\"groupName\"}", "生命周期")));
        allowAll("555153289");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                stateWithPeer(DimensionState.PeerDimensionType.TEAM, List.of("集成管理组")));
        stubAnalysis(question, analysis(null, false));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(null));

        DirectSkillExecutor.DirectExecutionPlan plan =
                newExecutorWithLedger(ledger).plan(question, "555153289", null);

        assertTrue(plan.extraction().groupName().isEmpty());
        assertEquals("杭州产品部", plan.params().get("dev_dept"));
        assertFalse(plan.params().containsKey("stat_group"));
    }

    @Test
    void referenceExemptKeepsInheritedPeer() {
        // R3 指代豁免: "那个组呢" 依赖上一轮 state, 整体不抑制
        String question = "那个组达标率呢";
        com.agentscopea2a.v2.dimension.PersonLedgerRepository ledger =
                mock(com.agentscopea2a.v2.dimension.PersonLedgerRepository.class);
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"dev_dept\":\"deptName\",\"stat_group\":\"groupName\"}", "达标率")));
        allowAll("555153289");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                stateWithPeer(DimensionState.PeerDimensionType.TEAM, List.of("集成管理组")));
        stubAnalysis(question, analysis(null, true));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(null));

        DirectSkillExecutor.DirectExecutionPlan plan =
                newExecutorWithLedger(ledger).plan(question, "555153289", null);

        assertEquals(List.of("集成管理组"), plan.extraction().groupName());
        assertEquals("集成管理组", plan.params().get("stat_group"));
    }

    @Test
    void explicitTeamMentionSurvivesReanchor() {
        // R3: 本轮显式提及组 → 保留; 产品线未提及 → 抑制 (按维度独立)
        String question = "我部集成管理组4季度达标率";
        com.agentscopea2a.v2.dimension.PersonLedgerRepository ledger =
                mock(com.agentscopea2a.v2.dimension.PersonLedgerRepository.class);
        when(ledger.byUserId("555153289")).thenReturn(ledgerRow("杭州产品部", null, null, null, "徐三金"));
        when(metadataRepository.findActive()).thenReturn(List.of(meta("dept_skill", "script_a",
                "{\"dev_dept\":\"deptName\",\"stat_group\":\"groupName\",\"product_line\":\"productLine\"}", "达标率")));
        allowAll("555153289");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                stateWithPeer(DimensionState.PeerDimensionType.TEAM, List.of("集成管理组")));
        stubAnalysis(question, analysis(new QuestionAnalysis.ExplicitDimensions(null, null,
                new DimensionState.PeerDimension(DimensionState.PeerDimensionType.TEAM,
                        List.of("集成管理组")), null), false));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(null));

        DirectSkillExecutor.DirectExecutionPlan plan =
                newExecutorWithLedger(ledger).plan(question, "555153289", null);

        assertEquals(List.of("集成管理组"), plan.extraction().groupName());
        assertEquals("集成管理组", plan.params().get("stat_group"));
        assertFalse(plan.params().containsKey("product_line"));
    }

    @Test
    void explicitDeptTriggersReanchorClearingInheritedPeer() {
        // R2b §6-2 拍板: 显式部门问句同一重锚语义 ("杭州三部4季度" ≈ "我部4季度")
        String question = "杭州开发三部4季度达标率";
        com.agentscopea2a.v2.dimension.PersonLedgerRepository ledger =
                mock(com.agentscopea2a.v2.dimension.PersonLedgerRepository.class);
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"dev_dept\":\"deptName\",\"stat_group\":\"groupName\"}", "达标率")));
        allowAll("555153289");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                new DimensionState(null, List.of("杭州开发三部"),
                        new DimensionState.PeerDimension(DimensionState.PeerDimensionType.TEAM,
                                List.of("集成管理组")), null));
        stubAnalysis(question, analysis(new QuestionAnalysis.ExplicitDimensions(null,
                List.of("杭州开发三部"), null, null), false));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(null));

        DirectSkillExecutor.DirectExecutionPlan plan =
                newExecutorWithLedger(ledger).plan(question, "555153289", null);

        assertEquals(List.of("杭州开发三部"), plan.extraction().deptName());
        assertTrue(plan.extraction().groupName().isEmpty());
        assertFalse(plan.params().containsKey("stat_group"));
    }

    @Test
    void drillDownOutputTriggersReanchor() {
        // R4: "我部各组" 分组输出语义 → 组过滤值抑制, drill_down 照传
        String question = "杭州三部10月版各组达标率";
        com.agentscopea2a.v2.dimension.PersonLedgerRepository ledger =
                mock(com.agentscopea2a.v2.dimension.PersonLedgerRepository.class);
        when(metadataRepository.findActive()).thenReturn(List.of(meta("dept_skill", "script_a",
                "{\"dev_dept\":\"deptName\",\"stat_group\":\"groupName\",\"drill\":\"drillDown\"}", "达标率")));
        enableKeyDictionary(new DimensionKeyConfig(1L, "drillDown", "下钻维度", false,
                List.of(new DimensionKeyConfig.AliasMapping("各组", "组维度")), true, null));
        allowAll("555153289");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                new DimensionState(null, List.of("杭州开发三部"),
                        new DimensionState.PeerDimension(DimensionState.PeerDimensionType.TEAM,
                                List.of("集成管理组")), null));
        stubAnalysis(question, analysis(new QuestionAnalysis.ExplicitDimensions(null,
                List.of("杭州开发三部"), null, null), false));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(null));

        DirectSkillExecutor.DirectExecutionPlan plan =
                newExecutorWithLedger(ledger).plan(question, "555153289", null);

        assertEquals("组维度", plan.params().get("drill"));
        assertEquals("杭州开发三部", plan.params().get("dev_dept"));
        assertFalse(plan.params().containsKey("stat_group"));
    }

    @Test
    void snapshotFallbackSkipsFineGrainedParams() {
        // L3 §6-3 拍板: 细粒度组织维度 (groupName 等) 快照补缺一律不补, 防止绕过重锚抑制;
        // 部门/时间补缺保留 (既有 snapshotFallbackFillsMissingRequiredParamFromSameSkill 覆盖)
        String t1 = "杭州开发一部 2026年7月份版本 达标率多少";
        String t2 = "2026年9月份版本 达标率多少";
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"stat_group\":\"groupName\",\"version\":\"versionPlan\"}", "达标率")));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(t1), Mockito.isNull())).thenReturn(
                stateWithPeer(DimensionState.PeerDimensionType.TEAM, List.of("集成管理组")));
        when(dimensionStateManager.previewState(Mockito.eq(t2), Mockito.isNull())).thenReturn(
                state(null, List.of()));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(
                "[{\"name\":\"stat_group\",\"required\":true},{\"name\":\"version\",\"required\":true}]"));

        DirectSkillExecutor executor = newExecutor(true);
        executor.plan(t1, "alice", null, "conv-1");

        DirectSkillExecutor.DirectExecutionPlan t2Plan = executor.plan(t2, "alice", null, "conv-1");
        // stat_group 不补缺 → 必填缺失走反问补参 (而不是把上轮组名悄悄补回来)
        assertTrue(t2Plan.needsAsk());
        assertFalse(t2Plan.params().containsKey("stat_group"));
    }

    // ============ 维度值台账真值校验 (维度值台账真值校验方案 §4, 2026/10/27) ============

    @Test
    void truthCheckDropsGarbageGroupValueToMissingAsk() {
        // B③ 终局校验: 正则轨垃圾值「房贷两个组」不在台账真值域 → 丢弃 → 必填缺失走反问
        String question = "房贷两个组的风险";
        com.agentscopea2a.v2.dimension.PersonLedgerRepository ledger =
                mock(com.agentscopea2a.v2.dimension.PersonLedgerRepository.class);
        when(ledger.dimensionTruthDictionary("统计组")).thenReturn(Set.of("个贷汽车场景建设组"));
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"stat_group\":\"groupName\"}", "风险")));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                stateWithPeer(DimensionState.PeerDimensionType.TEAM, List.of("房贷两个组")));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(
                "[{\"name\":\"stat_group\",\"required\":true}]"));

        DirectSkillExecutor.DirectExecutionPlan plan =
                newExecutorWithLedger(ledger).plan(question, "alice", null, "conv-1");

        assertTrue(plan.needsAsk());
        assertTrue(plan.missingAsk().contains("groupName"));
        assertTrue(plan.extraction().groupName().isEmpty());
    }

    @Test
    void truthScanFillsStandardGroupFromQuestionText() {
        // B① 正向扫描: 「软负载均衡团队」不合 EXPLICIT_TEAM 组后缀形状, 正则轨提不出;
        // 台账真值域整名 contains 命中 → groupName 直接落地
        String question = "软负载均衡团队的达标率";
        com.agentscopea2a.v2.dimension.PersonLedgerRepository ledger =
                mock(com.agentscopea2a.v2.dimension.PersonLedgerRepository.class);
        when(ledger.dimensionTruthDictionary("统计组")).thenReturn(Set.of("软负载均衡团队"));
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"stat_group\":\"groupName\"}", "达标率")));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                state(null, List.of()));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(null));

        DirectSkillExecutor.DirectExecutionPlan plan =
                newExecutorWithLedger(ledger).plan(question, "alice", null);

        assertEquals(List.of("软负载均衡团队"), plan.extraction().groupName());
        assertEquals("软负载均衡团队", plan.params().get("stat_group"));
    }

    @Test
    void truthCheckFailOpenKeepsLegacyBehavior() {
        // fail-open: 台账字典查询失败返回 null → 该键扫描与校验全关, 垃圾值照旧透传 (不误杀)
        String question = "房贷两个组的风险";
        com.agentscopea2a.v2.dimension.PersonLedgerRepository ledger =
                mock(com.agentscopea2a.v2.dimension.PersonLedgerRepository.class);
        when(ledger.dimensionTruthDictionary("统计组")).thenReturn(null);
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"stat_group\":\"groupName\"}", "风险")));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                stateWithPeer(DimensionState.PeerDimensionType.TEAM, List.of("房贷两个组")));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(null));

        DirectSkillExecutor.DirectExecutionPlan plan =
                newExecutorWithLedger(ledger).plan(question, "alice", null);

        assertEquals(List.of("房贷两个组"), plan.extraction().groupName());
        assertEquals("房贷两个组", plan.params().get("stat_group"));
    }

    @Test
    void mutexLongestValueWinsAcrossThreeKeys() {
        // 互斥裁决 (拍板 2026/10/27): 三键互斥, 最长命中值所在键胜 —
        // 「杭州二部FMBM资金交易组」(12字) 压「数据产品线」(5字) → 产品线丢弃
        String question = "杭州二部FMBM资金交易组和数据产品线的达标率";
        com.agentscopea2a.v2.dimension.PersonLedgerRepository ledger =
                mock(com.agentscopea2a.v2.dimension.PersonLedgerRepository.class);
        when(ledger.dimensionTruthDictionary("统计组")).thenReturn(Set.of("杭州二部FMBM资金交易组"));
        when(ledger.dimensionTruthDictionary("产品线")).thenReturn(Set.of("数据产品线"));
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"stat_group\":\"groupName\",\"product_line\":\"productLine\"}", "达标率")));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                stateWithPeer(DimensionState.PeerDimensionType.TEAM, List.of("杭州二部FMBM资金交易组")));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(null));

        DirectSkillExecutor.DirectExecutionPlan plan =
                newExecutorWithLedger(ledger).plan(question, "alice", null);

        assertEquals(List.of("杭州二部FMBM资金交易组"), plan.extraction().groupName());
        assertEquals("杭州二部FMBM资金交易组", plan.params().get("stat_group"));
        assertTrue(plan.extraction().productLine().isEmpty());
        assertFalse(plan.params().containsKey("product_line"));
    }

    @Test
    void vocabUnionKeepsConfiguredStandardNotInLedger() {
        // 真值域并集 (§4.2): 词表配置的同义词标准值并入台账 distinct —
        // 台账暂无该组人员时不被 B③ 误杀
        String question = "移动展业组的达标率";
        com.agentscopea2a.v2.dimension.PersonLedgerRepository ledger =
                mock(com.agentscopea2a.v2.dimension.PersonLedgerRepository.class);
        when(ledger.dimensionTruthDictionary("统计组")).thenReturn(Set.of("个贷汽车场景建设组"));
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"stat_group\":\"groupName\"}", "达标率")));
        enableKeyDictionary(new DimensionKeyConfig(2L, "groupName", "组（团队）", true,
                List.of(new DimensionKeyConfig.AliasMapping("移动展业组", "移动展业组")), true, null));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                stateWithPeer(DimensionState.PeerDimensionType.TEAM, List.of("移动展业组")));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(null));

        DirectSkillExecutor.DirectExecutionPlan plan =
                newExecutorWithLedger(ledger).plan(question, "alice", null);

        assertEquals(List.of("移动展业组"), plan.extraction().groupName());
        assertEquals("移动展业组", plan.params().get("stat_group"));
    }

    @Test
    void truthScanHitCountsAsExplicitMentionForReanchorSuppression() {
        // R2 联动 (§4.3): B① 扫描命中计入显式提及 — 「软负载均衡团队」不合组名正则形状,
        // hasExplicitTeamHit 探不到, 不计入会被重锚抑制误清
        String question = "云计算实验室软负载均衡团队的风险";
        com.agentscopea2a.v2.dimension.PersonLedgerRepository ledger =
                mock(com.agentscopea2a.v2.dimension.PersonLedgerRepository.class);
        when(ledger.dimensionTruthDictionary("统计组")).thenReturn(Set.of("软负载均衡团队"));
        when(metadataRepository.findActive()).thenReturn(List.of(meta("dept_skill", "script_a",
                "{\"dev_dept\":\"deptName\",\"stat_group\":\"groupName\"}", "风险")));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                state(null, List.of("云计算实验室")));
        stubAnalysis(question, analysis(new QuestionAnalysis.ExplicitDimensions(null,
                List.of("云计算实验室"), null, null), false));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(null));

        DirectSkillExecutor.DirectExecutionPlan plan =
                newExecutorWithLedger(ledger).plan(question, "alice", null);

        assertEquals(List.of("软负载均衡团队"), plan.extraction().groupName());
        assertEquals("软负载均衡团队", plan.params().get("stat_group"));
        assertEquals("云计算实验室", plan.params().get("dev_dept"));
    }

    // ============ 应用码英文形态豁免 B③ (应用退出台账真值校验方案 v2, 2026/11/10) ============

    @Test
    void appCodeEnglishShapeBypassesTruthDomainCheck() {
        // 基地应用 F-GCS 不在台账「应用」真值域 → 豁免不剔, 原样传参
        String question = "F-GCS的达标率";
        com.agentscopea2a.v2.dimension.PersonLedgerRepository ledger =
                mock(com.agentscopea2a.v2.dimension.PersonLedgerRepository.class);
        when(ledger.dimensionTruthDictionary("应用")).thenReturn(Set.of("F-ATP"));
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"app\":\"appName\"}", "达标率")));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                stateWithPeer(DimensionState.PeerDimensionType.APPLICATION, List.of("F-GCS")));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(null));

        DirectSkillExecutor.DirectExecutionPlan plan =
                newExecutorWithLedger(ledger).plan(question, "alice", null);

        assertEquals(List.of("F-GCS"), plan.extraction().appName());
        assertEquals("F-GCS", plan.params().get("app"));
    }

    @Test
    void appCodeExemptionAppliesOnlyToAppNameKey() {
        // 豁免仅挂 appName 键: groupName 键的同形态值仍对照真值域, 域外照剔
        String question = "F-GCS的达标率";
        com.agentscopea2a.v2.dimension.PersonLedgerRepository ledger =
                mock(com.agentscopea2a.v2.dimension.PersonLedgerRepository.class);
        when(ledger.dimensionTruthDictionary("统计组")).thenReturn(Set.of("个贷汽车场景建设组"));
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"stat_group\":\"groupName\"}", "达标率")));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                stateWithPeer(DimensionState.PeerDimensionType.TEAM, List.of("F-GCS")));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(null));

        DirectSkillExecutor.DirectExecutionPlan plan =
                newExecutorWithLedger(ledger).plan(question, "alice", null);

        assertTrue(plan.extraction().groupName().isEmpty());
    }

    // ============ B① 扫描值子串消解 (真值扫描子串误加修复方案, 2026/11/11) ============

    @Test
    void truthScanSubstringAbsorbedByLongerExtractedValue() {
        // 扫描值是既有提取值的真子串 → 消解不加: 问句含 F-DTX-OPS (正则提取),
        // 台账真值 F-DTX 子串命中被吸收, 不再双值
        String question = "F-DTX-OPS的达标率";
        com.agentscopea2a.v2.dimension.PersonLedgerRepository ledger =
                mock(com.agentscopea2a.v2.dimension.PersonLedgerRepository.class);
        when(ledger.dimensionTruthDictionary("应用")).thenReturn(Set.of("F-DTX"));
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"app\":\"appName\"}", "达标率")));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                stateWithPeer(DimensionState.PeerDimensionType.APPLICATION, List.of("F-DTX-OPS")));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(null));

        DirectSkillExecutor.DirectExecutionPlan plan =
                newExecutorWithLedger(ledger).plan(question, "alice", null);

        assertEquals(List.of("F-DTX-OPS"), plan.extraction().appName());
        assertEquals("F-DTX-OPS", plan.params().get("app"));
    }

    @Test
    void truthScanKeepsLegitDualMention() {
        // 合法双提全保留: 两值都是正则显式提取, 扫描值消解不碰显式值
        String question = "F-DTX和F-DTX-OPS的达标率";
        com.agentscopea2a.v2.dimension.PersonLedgerRepository ledger =
                mock(com.agentscopea2a.v2.dimension.PersonLedgerRepository.class);
        when(ledger.dimensionTruthDictionary("应用")).thenReturn(Set.of("F-DTX", "F-DTX-OPS"));
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"app\":\"appName\"}", "达标率")));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                stateWithPeer(DimensionState.PeerDimensionType.APPLICATION,
                        List.of("F-DTX", "F-DTX-OPS")));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(null));

        DirectSkillExecutor.DirectExecutionPlan plan =
                newExecutorWithLedger(ledger).plan(question, "alice", null);

        assertEquals(List.of("F-DTX", "F-DTX-OPS"), plan.extraction().appName());
    }

    // ============ 维度值→部门反填 (2026/10/27 拍板, 台账 Q8 deptsByDimension) ============

    @Test
    void groupHitBackfillsDeptFromLedgerWhenDeptMissing() {
        // 车贷/房贷型纯组问句: 组值命中但没提部门 → 台账按组查部门自动填充
        String question = "个贷汽车场景建设组和个贷住房场景建设组的达标率";
        com.agentscopea2a.v2.dimension.PersonLedgerRepository ledger =
                mock(com.agentscopea2a.v2.dimension.PersonLedgerRepository.class);
        when(ledger.dimensionTruthDictionary("统计组"))
                .thenReturn(Set.of("个贷汽车场景建设组", "个贷住房场景建设组"));
        when(ledger.deptsByDimension("统计组", "个贷汽车场景建设组")).thenReturn(List.of("杭州开发三部"));
        when(ledger.deptsByDimension("统计组", "个贷住房场景建设组")).thenReturn(List.of("杭州开发二部"));
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"dept\":\"deptName\",\"stat_group\":\"groupName\"}", "达标率")));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                stateWithPeer(DimensionState.PeerDimensionType.TEAM,
                        List.of("个贷汽车场景建设组", "个贷住房场景建设组")));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(null));

        DirectSkillExecutor.DirectExecutionPlan plan =
                newExecutorWithLedger(ledger).plan(question, "alice", null);

        assertEquals(List.of("杭州开发三部", "杭州开发二部"), plan.extraction().deptName());
        assertEquals(List.of("个贷汽车场景建设组", "个贷住房场景建设组"), plan.extraction().groupName());
        org.mockito.Mockito.verify(ledger, org.mockito.Mockito.times(1))
                .deptsByDimension("统计组", "个贷汽车场景建设组");
        org.mockito.Mockito.verify(ledger, org.mockito.Mockito.times(1))
                .deptsByDimension("统计组", "个贷住房场景建设组");
        org.mockito.Mockito.verify(ledger, org.mockito.Mockito.never()).deptsByDimension(
                org.mockito.ArgumentMatchers.eq("产品线"), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void productLineHitBackfillsDeptFromLedgerWhenDeptMissing() {
        // 同机制覆盖产品线: 纯产品线问句 → 按产品线值查部门反填
        String question = "数据产品线的达标率";
        com.agentscopea2a.v2.dimension.PersonLedgerRepository ledger =
                mock(com.agentscopea2a.v2.dimension.PersonLedgerRepository.class);
        when(ledger.dimensionTruthDictionary("产品线")).thenReturn(Set.of("数据产品线"));
        when(ledger.deptsByDimension("产品线", "数据产品线")).thenReturn(List.of("杭州开发二部"));
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"dept\":\"deptName\",\"product_line\":\"productLine\"}", "达标率")));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                stateWithPeer(DimensionState.PeerDimensionType.PRODUCT_LINE, List.of("数据产品线")));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(null));

        DirectSkillExecutor.DirectExecutionPlan plan =
                newExecutorWithLedger(ledger).plan(question, "alice", null);

        assertEquals(List.of("杭州开发二部"), plan.extraction().deptName());
        assertEquals("杭州开发二部", plan.params().get("dept"));
        assertEquals(List.of("数据产品线"), plan.extraction().productLine());
        org.mockito.Mockito.verify(ledger, org.mockito.Mockito.never()).deptsByDimension(
                org.mockito.ArgumentMatchers.eq("统计组"), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void explicitDeptSkipsLedgerDeptBackfill() {
        // 显式部门优先: deptName 已填时不打 Q8 (显式优先, 反填只补缺)
        String question = "杭州开发二部个贷汽车场景建设组的达标率";
        com.agentscopea2a.v2.dimension.PersonLedgerRepository ledger =
                mock(com.agentscopea2a.v2.dimension.PersonLedgerRepository.class);
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"dept\":\"deptName\",\"stat_group\":\"groupName\"}", "达标率")));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                new DimensionState(null, List.of("杭州开发二部"),
                        new DimensionState.PeerDimension(DimensionState.PeerDimensionType.TEAM,
                                List.of("个贷汽车场景建设组")), null));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(null));

        DirectSkillExecutor.DirectExecutionPlan plan =
                newExecutorWithLedger(ledger).plan(question, "alice", null);

        assertEquals(List.of("杭州开发二部"), plan.extraction().deptName());
        org.mockito.Mockito.verify(ledger, org.mockito.Mockito.never()).deptsByDimension(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void deptParamNotRequiredSkipsLedgerDeptBackfill() {
        // 需求 1 门控 (2026/10/30 部门与上级维度带出门控方案 §2.1): 问句仅提组、
        // 脚本部门参数非必输 → 不反填部门, 参数只传组
        String question = "个贷汽车场景建设组的达标率";
        com.agentscopea2a.v2.dimension.PersonLedgerRepository ledger =
                mock(com.agentscopea2a.v2.dimension.PersonLedgerRepository.class);
        when(ledger.dimensionTruthDictionary("统计组"))
                .thenReturn(Set.of("个贷汽车场景建设组"));
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"dept\":\"deptName\",\"stat_group\":\"groupName\"}", "达标率")));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                stateWithPeer(DimensionState.PeerDimensionType.TEAM,
                        List.of("个贷汽车场景建设组")));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(
                "[{\"name\":\"dept\",\"required\":false},{\"name\":\"stat_group\",\"required\":true}]"));

        DirectSkillExecutor.DirectExecutionPlan plan =
                newExecutorWithLedger(ledger).plan(question, "alice", null);

        assertTrue(plan.extraction().deptName().isEmpty());
        assertFalse(plan.params().containsKey("dept"));
        assertEquals("个贷汽车场景建设组", plan.params().get("stat_group"));
        org.mockito.Mockito.verify(ledger, org.mockito.Mockito.never()).deptsByDimension(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void deptParamRequiredStillBackfillsFromLedger() {
        // 拍板 C 反向: 部门参数必输 → 维持 2026/10/27 Q8 反填 (避免缺部门反问)
        String question = "个贷汽车场景建设组的达标率";
        com.agentscopea2a.v2.dimension.PersonLedgerRepository ledger =
                mock(com.agentscopea2a.v2.dimension.PersonLedgerRepository.class);
        when(ledger.dimensionTruthDictionary("统计组"))
                .thenReturn(Set.of("个贷汽车场景建设组"));
        when(ledger.deptsByDimension("统计组", "个贷汽车场景建设组")).thenReturn(List.of("杭州开发三部"));
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"dept\":\"deptName\",\"stat_group\":\"groupName\"}", "达标率")));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                stateWithPeer(DimensionState.PeerDimensionType.TEAM,
                        List.of("个贷汽车场景建设组")));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(
                "[{\"name\":\"dept\",\"required\":true},{\"name\":\"stat_group\",\"required\":true}]"));

        DirectSkillExecutor.DirectExecutionPlan plan =
                newExecutorWithLedger(ledger).plan(question, "alice", null);

        assertEquals(List.of("杭州开发三部"), plan.extraction().deptName());
        assertEquals("杭州开发三部", plan.params().get("dept"));
    }

    @Test
    void deptParamUnboundSkipsLedgerDeptBackfill() {
        // mapping 未绑 deptName → 反填产物无处落地, 不打台账 Q8
        String question = "个贷汽车场景建设组的达标率";
        com.agentscopea2a.v2.dimension.PersonLedgerRepository ledger =
                mock(com.agentscopea2a.v2.dimension.PersonLedgerRepository.class);
        when(ledger.dimensionTruthDictionary("统计组"))
                .thenReturn(Set.of("个贷汽车场景建设组"));
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", "{\"stat_group\":\"groupName\"}", "达标率")));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                stateWithPeer(DimensionState.PeerDimensionType.TEAM,
                        List.of("个贷汽车场景建设组")));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(
                "[{\"name\":\"stat_group\",\"required\":true}]"));

        DirectSkillExecutor.DirectExecutionPlan plan =
                newExecutorWithLedger(ledger).plan(question, "alice", null);

        assertEquals("个贷汽车场景建设组", plan.params().get("stat_group"));
        org.mockito.Mockito.verify(ledger, org.mockito.Mockito.never()).deptsByDimension(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    // ==================== 追问层 (docs/202611/追问轮维度继承直接执行方案.md §4, D1-D7 拍板) ====================

    private static final String FOLLOWUP_T1 = "杭州开发二部 2026年7月份版本 达标率多少";
    private static final String FOLLOWUP_T2 = "9月的呢";

    /** 规则分析产物桩: C3 维度信号 / 重锚抑制的显式提及判定都吃它。 */
    private static QuestionAnalysis analysisWith(DimensionState.TimeDimension time,
                                                 List<String> depts) {
        QuestionAnalysis analysis = new QuestionAnalysis();
        analysis.setExplicitDimensions(
                new QuestionAnalysis.ExplicitDimensions(time, depts, null, null));
        return analysis;
    }

    /** 首轮直执行环境: selector 命中「达标率」层级词, dept+version 必填全有 → 记快照。 */
    private void stubFollowupFirstRound() {
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a",
                        "{\"dept\":\"deptName\",\"version\":\"versionPlan\"}", "达标率")));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(FOLLOWUP_T1), Mockito.isNull()))
                .thenReturn(state(versionTime(List.of("2026年7月份版本")), List.of("杭州开发二部")));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(
                "[{\"name\":\"dept\",\"required\":true},{\"name\":\"version\",\"required\":true}]"));
    }

    /** 追问轮 (selector 空) 的状态桩: preview 继承了时间但丢部门 (G2 场景), 规则分析
     *  产出时间显式维度 (C3 信号源)。 */
    private void stubFollowupRound(String question, List<String> depts) {
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull()))
                .thenReturn(state(versionTime(List.of("2026年9月份版本")), depts));
        when(dimensionStateManager.analyzeQuestionRuleBased(question)).thenReturn(
                analysisWith(new DimensionState.TimeDimension(
                        DimensionState.TimeDimensionType.VERSION, List.of("2026年9月份版本")),
                        depts));
    }

    @Test
    void followupReplacesMentionedAndInheritsRest() {
        stubFollowupFirstRound();
        DirectSkillExecutor executor = newExecutor(true).setFollowupDirectEnabled(true);
        executor.plan(FOLLOWUP_T1, "alice", null, "conv1");

        stubFollowupRound(FOLLOWUP_T2, List.of());
        DirectSkillExecutor.DirectExecutionPlan followup =
                executor.plan(FOLLOWUP_T2, "alice", null, "conv1");

        assertEquals("dept_skill", followup.skillName());
        assertEquals("2026年9月份版本", followup.params().get("version"));   // 本轮提及 → 替换
        assertEquals("杭州开发二部", followup.params().get("dept"));         // 未提及 → 快照继承
    }

    @Test
    void followupReanchorDeletesFineGrainedFromSnapshot() {
        String t1 = "杭州开发二部车贷组 2026年7月份版本 达标率多少";
        String t2 = "三部呢";
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a",
                        "{\"dept\":\"deptName\",\"version\":\"versionPlan\",\"group\":\"groupName\"}",
                        "达标率")));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(t1), Mockito.isNull())).thenReturn(
                new DimensionState(versionTime(List.of("2026年7月份版本")), List.of("杭州开发二部"),
                        new DimensionState.PeerDimension(DimensionState.PeerDimensionType.TEAM,
                                List.of("车贷组")), null));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(
                "[{\"name\":\"dept\",\"required\":true},{\"name\":\"version\",\"required\":true},"
                        + "{\"name\":\"group\",\"required\":true}]"));
        DirectSkillExecutor executor = newExecutor(true).setFollowupDirectEnabled(true);
        DirectSkillExecutor.DirectExecutionPlan first = executor.plan(t1, "alice", null, "conv1");
        assertEquals("车贷组", first.params().get("group"));

        // 追问轮: 状态继承了 TEAM 粒度 (模拟状态残留), 本轮显式提及部门 → 重锚抑制清空组
        when(dimensionStateManager.previewState(Mockito.eq(t2), Mockito.isNull())).thenReturn(
                new DimensionState(versionTime(List.of("2026年7月份版本")), List.of("杭州开发三部"),
                        new DimensionState.PeerDimension(DimensionState.PeerDimensionType.TEAM,
                                List.of("车贷组")), null));
        when(dimensionStateManager.analyzeQuestionRuleBased(t2)).thenReturn(
                analysisWith(null, List.of("杭州开发三部")));
        when(dimensionStateManager.hasExplicitTeamHit(t2)).thenReturn(false);
        when(dimensionStateManager.hasExplicitProductLineHit(t2)).thenReturn(false);

        DirectSkillExecutor.DirectExecutionPlan followup =
                executor.plan(t2, "alice", null, "conv1");

        assertEquals("杭州开发三部", followup.params().get("dept"));
        assertEquals("2026年7月份版本", followup.params().get("version"));
        // D3 拍板: 重锚抑制清空的细粒度维度, 继承时从快照基底删除 (防「三部的车贷组」错账)
        assertFalse(followup.params().containsKey("group"));
    }

    @Test
    void followupWithoutDimensionSignalFallsBackToLlm() {
        stubFollowupFirstRound();
        DirectSkillExecutor executor = newExecutor(true).setFollowupDirectEnabled(true);
        executor.plan(FOLLOWUP_T1, "alice", null, "conv1");

        // preview 状态带继承维度, 但问题本身无维度信号 → C3 只看问题新提及, 不看状态继承
        String t2 = "随便聊聊天";
        when(dimensionStateManager.previewState(Mockito.eq(t2), Mockito.isNull())).thenReturn(
                state(versionTime(List.of("2026年9月份版本")), List.of()));
        when(dimensionStateManager.analyzeQuestionRuleBased(t2)).thenReturn(new QuestionAnalysis());

        assertNull(executor.plan(t2, "alice", null, "conv1"));
    }

    @Test
    void followupNewTaskVerbsFallBackToLlm() {
        stubFollowupFirstRound();
        DirectSkillExecutor executor = newExecutor(true).setFollowupDirectEnabled(true);
        executor.plan(FOLLOWUP_T1, "alice", null, "conv1");

        // 有维度信号 (C3 过) 但命中新任务动词 → C4b 安全网拦下
        String t2 = "把二部的数据整理成周报";
        stubFollowupRound(t2, List.of("杭州开发二部"));

        assertNull(executor.plan(t2, "alice", null, "conv1"));
    }

    @Test
    void followupCompareQuestionFallsBackToLlm() {
        stubFollowupFirstRound();
        DirectSkillExecutor executor = newExecutor(true).setFollowupDirectEnabled(true);
        executor.plan(FOLLOWUP_T1, "alice", null, "conv1");

        // D5 拍板: 对比类回退 LLM (hint result 摘要做对比更稳)
        String t2 = "9月和7月比呢";
        stubFollowupRound(t2, List.of());

        assertNull(executor.plan(t2, "alice", null, "conv1"));
    }

    @Test
    void followupTooLongQuestionFallsBackToLlm() {
        stubFollowupFirstRound();
        DirectSkillExecutor executor = newExecutor(true).setFollowupDirectEnabled(true);
        executor.plan(FOLLOWUP_T1, "alice", null, "conv1");

        String t2 = FOLLOWUP_T2 + "另外还想请帮我看看很多其他的事情".repeat(4);
        assertTrue(t2.length() > 60);
        stubFollowupRound(t2, List.of());

        assertNull(executor.plan(t2, "alice", null, "conv1"));
    }

    @Test
    void followupDomainHitFallsBackToLlm() {
        stubFollowupFirstRound();
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a",
                        "{\"dept\":\"deptName\",\"version\":\"versionPlan\"}", "达标率"),
                new SkillRoutingMetadata("meeting_skill", "s", List.of(), List.of("部门例会"),
                        List.of(), "admin", true, LocalDateTime.now(),
                        List.of(), List.of(), List.of(), null, null)));
        DirectSkillExecutor executor = newExecutor(true).setFollowupDirectEnabled(true);
        executor.plan(FOLLOWUP_T1, "alice", null, "conv1");

        // D1 拍板: domain 命中 = 领域点名, 不是纯维度追问 → LLM
        String t2 = "部门例会 9月的呢";
        stubFollowupRound(t2, List.of());

        assertNull(executor.plan(t2, "alice", null, "conv1"));
    }

    @Test
    void followupWithoutSnapshotFallsBackToLlm() {
        stubFollowupFirstRound();
        DirectSkillExecutor executor = newExecutor(true).setFollowupDirectEnabled(true);
        stubFollowupRound(FOLLOWUP_T2, List.of());

        assertNull(executor.plan(FOLLOWUP_T2, "alice", null, "conv-fresh"));
    }

    @Test
    void followupDisabledKeepsLegacyBehavior() {
        stubFollowupFirstRound();
        DirectSkillExecutor executor = newExecutor(true);   // 便捷构造器默认关
        executor.plan(FOLLOWUP_T1, "alice", null, "conv1");
        stubFollowupRound(FOLLOWUP_T2, List.of());

        assertNull(executor.plan(FOLLOWUP_T2, "alice", null, "conv1"));
    }

    @Test
    void followupPermissionFailFailsClosed() {
        stubFollowupFirstRound();
        DirectSkillExecutor executor = newExecutor(true).setFollowupDirectEnabled(true);
        executor.plan(FOLLOWUP_T1, "alice", null, "conv1");
        stubFollowupRound(FOLLOWUP_T2, List.of());
        when(usageResolver.findUsableRetrievalNames("alice")).thenReturn(Set.of("other_skill"));

        assertNull(executor.plan(FOLLOWUP_T2, "alice", null, "conv1"));
    }

    @Test
    void followupSnapshotSkillDeactivatedFallsBackToLlm() {
        stubFollowupFirstRound();
        DirectSkillExecutor executor = newExecutor(true).setFollowupDirectEnabled(true);
        executor.plan(FOLLOWUP_T1, "alice", null, "conv1");
        stubFollowupRound(FOLLOWUP_T2, List.of());
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("other_skill", null, "{\"dept\":\"deptName\"}", "遗留")));

        assertNull(executor.plan(FOLLOWUP_T2, "alice", null, "conv1"));
    }

    @Test
    void followupSuccessRefreshesSnapshotForChainedRounds() {
        stubFollowupFirstRound();
        DirectSkillExecutor executor = newExecutor(true).setFollowupDirectEnabled(true);
        executor.plan(FOLLOWUP_T1, "alice", null, "conv1");

        stubFollowupRound(FOLLOWUP_T2, List.of());
        executor.plan(FOLLOWUP_T2, "alice", null, "conv1");

        DirectSkillExecutor.LastDirectExec snapshot = executor.lastExec("alice", "conv1");
        assertEquals(FOLLOWUP_T2, snapshot.question());
        assertEquals("2026年9月份版本", snapshot.params().get("version"));
    }

    @Test
    void followupSkippedWithoutConversationKey() {
        stubFollowupFirstRound();
        DirectSkillExecutor executor = newExecutor(true).setFollowupDirectEnabled(true);
        executor.plan(FOLLOWUP_T1, "alice", null, "conv1");
        stubFollowupRound(FOLLOWUP_T2, List.of());

        assertNull(executor.plan(FOLLOWUP_T2, "alice", null));   // 3 参旧签名: 无会话键不启用
    }

    // ---- 时间家族合并裁决 (bug 2026/11/05: 追问轮默认在建版本顶掉快照显式时间) ----

    /** 双时间家族 skill (quarter+version+dept 全必填) + 在建版本 hook: 复现默认版本补全的前提。 */
    private void stubFollowupBothTimeFirstRound() {
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a",
                        "{\"quarter\":\"vestQuarter\",\"version\":\"versionPlan\",\"dept\":\"deptName\"}",
                        "达标率")));
        allowAll("alice");
        when(hookProvider.getIfAvailable()).thenReturn(versionPlanHook);
        when(versionPlanHook.currentVersions()).thenReturn(
                List.of("2026年10月份版本", "2026年11月份版本", "2026年12月份版本"));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(
                "[{\"name\":\"quarter\",\"required\":false},{\"name\":\"version\",\"required\":false},"
                        + "{\"name\":\"dept\",\"required\":true}]"));
    }

    /** 追问轮只提部门: 提取里两个时间家族都空 (与线上 trace 一致), C3 信号走显式部门。 */
    private void stubFollowupDeptOnlyRound(String question, List<String> depts) {
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull()))
                .thenReturn(state(null, depts));
        when(dimensionStateManager.analyzeQuestionRuleBased(question)).thenReturn(
                analysisWith(null, depts));
    }

    @Test
    void followupNoTimeMentionInheritsSnapshotQuarterWithoutDefaultVersions() {
        stubFollowupBothTimeFirstRound();
        String t1 = "杭州开发二部 1、2、3季度达标率";
        when(dimensionStateManager.previewState(Mockito.eq(t1), Mockito.isNull())).thenReturn(
                state(quarterTime(List.of("2026年1季度", "2026年2季度", "2026年3季度")),
                        List.of("杭州开发二部")));
        DirectSkillExecutor executor = newExecutor(true).setFollowupDirectEnabled(true);
        DirectSkillExecutor.DirectExecutionPlan first = executor.plan(t1, "alice", null, "conv1");
        assertEquals(List.of("2026年1季度", "2026年2季度", "2026年3季度"),
                first.params().get("quarter"));
        assertFalse(first.params().containsKey("version"));   // 时间二选一: 季度胜, 版本族抑制

        stubFollowupDeptOnlyRound("三部的呢", List.of("杭州开发三部"));
        DirectSkillExecutor.DirectExecutionPlan followup =
                executor.plan("三部的呢", "alice", null, "conv1");

        assertEquals(List.of("2026年1季度", "2026年2季度", "2026年3季度"),
                followup.params().get("quarter"));                    // 快照季度继承
        assertFalse(followup.params().containsKey("version"));        // 不补默认在建版本
        assertEquals("杭州开发三部", followup.params().get("dept"));
    }

    @Test
    void followupNoTimeMentionKeepsSnapshotExplicitVersionOverDefaults() {
        stubFollowupFirstRound();
        when(hookProvider.getIfAvailable()).thenReturn(versionPlanHook);
        when(versionPlanHook.currentVersions()).thenReturn(
                List.of("2026年10月份版本", "2026年11月份版本", "2026年12月份版本"));
        DirectSkillExecutor executor = newExecutor(true).setFollowupDirectEnabled(true);
        executor.plan(FOLLOWUP_T1, "alice", null, "conv1");   // 快照 version=2026年7月份版本

        stubFollowupDeptOnlyRound("三部呢", List.of("杭州开发三部"));
        DirectSkillExecutor.DirectExecutionPlan followup =
                executor.plan("三部呢", "alice", null, "conv1");

        assertEquals("2026年7月份版本", followup.params().get("version")); // 快照显式版本, 不被默认在建版本顶掉
        assertEquals("杭州开发三部", followup.params().get("dept"));
    }

    @Test
    void followupReanchorToVersionDeletesBaseQuarter() {
        stubFollowupBothTimeFirstRound();
        String t1 = "杭州开发二部 1、2、3季度达标率";
        when(dimensionStateManager.previewState(Mockito.eq(t1), Mockito.isNull())).thenReturn(
                state(quarterTime(List.of("2026年1季度", "2026年2季度", "2026年3季度")),
                        List.of("杭州开发二部")));
        DirectSkillExecutor executor = newExecutor(true).setFollowupDirectEnabled(true);
        executor.plan(t1, "alice", null, "conv1");

        String t2 = "三部 10月呢";
        when(dimensionStateManager.previewState(Mockito.eq(t2), Mockito.isNull())).thenReturn(
                state(versionTime(List.of("2026年10月份版本")), List.of("杭州开发三部")));
        when(dimensionStateManager.analyzeQuestionRuleBased(t2)).thenReturn(
                analysisWith(versionTime(List.of("2026年10月份版本")), List.of("杭州开发三部")));

        DirectSkillExecutor.DirectExecutionPlan followup =
                executor.plan(t2, "alice", null, "conv1");

        assertEquals("2026年10月份版本", followup.params().get("version")); // 本轮重锚版本
        assertFalse(followup.params().containsKey("quarter"));              // 快照季度族随重锚删除 (二选一)
        assertEquals("杭州开发三部", followup.params().get("dept"));
    }

    @Test
    void followupDrillDownQuestionExecutesDirectly() {
        // 追问仅命中 drillDown 词表 (各个组呢) — 规则分析无显式维度 (粒度词被垃圾守卫拒),
        // C3 信号必须认动态键命中, 否则下钻追问漏回 LLM; 快照继承的组值被组维度粒度消解
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a",
                        "{\"dept\":\"deptName\",\"version\":\"versionPlan\",\"group\":\"groupName\","
                                + "\"group_by\":\"drillDown\"}",
                        "达标率")));
        enableKeyDictionary(drillDownConfig());
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(FOLLOWUP_T1), Mockito.isNull())).thenReturn(
                new DimensionState(versionTime(List.of("2026年7月份版本")), List.of("杭州开发二部"),
                        new DimensionState.PeerDimension(DimensionState.PeerDimensionType.TEAM,
                                List.of("车贷组")), null));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(
                "[{\"name\":\"dept\",\"required\":true},{\"name\":\"version\",\"required\":true},"
                        + "{\"name\":\"group\",\"required\":false},{\"name\":\"group_by\",\"required\":false}]"));
        DirectSkillExecutor executor = newExecutor(true).setFollowupDirectEnabled(true);
        DirectSkillExecutor.DirectExecutionPlan first =
                executor.plan(FOLLOWUP_T1, "alice", null, "conv1");
        assertEquals("车贷组", first.params().get("group"));

        String t2 = "各个组呢";
        when(dimensionStateManager.previewState(Mockito.eq(t2), Mockito.isNull())).thenReturn(
                new DimensionState(versionTime(List.of("2026年7月份版本")), List.of("杭州开发二部"),
                        new DimensionState.PeerDimension(DimensionState.PeerDimensionType.TEAM,
                                List.of("车贷组")), null));
        when(dimensionStateManager.analyzeQuestionRuleBased(t2)).thenReturn(new QuestionAnalysis());
        when(dimensionStateManager.hasExplicitTeamHit(t2)).thenReturn(false);
        when(dimensionStateManager.hasExplicitProductLineHit(t2)).thenReturn(false);

        DirectSkillExecutor.DirectExecutionPlan followup =
                executor.plan(t2, "alice", null, "conv1");

        assertEquals("dept_skill", followup.skillName());
        assertEquals("组维度", followup.params().get("group_by"));            // 本轮下钻参数 (单值折叠)
        assertFalse(followup.params().containsKey("group"));                  // 组维度粒度消解快照组值
        assertEquals("杭州开发二部", followup.params().get("dept"));          // 未提及 → 快照继承
        assertEquals("2026年7月份版本", followup.params().get("version"));    // 未重锚时间 → 快照继承
    }

    @Test
    void followupCustomDynamicKeyHitIsNotDimensionSignal() {
        // 传参型自定义键 (qGate) 命中不算维度信号 (2026-11-05 白名单拍板):
        // 「Q1卡口的呢」回退 LLM, 不允许按快照原参数重跑 (问东答西)
        stubFollowupFirstRound();
        enableKeyDictionary(qGateConfig());
        DirectSkillExecutor executor = newExecutor(true).setFollowupDirectEnabled(true);
        executor.plan(FOLLOWUP_T1, "alice", null, "conv1");

        String t2 = "Q1卡口的呢";
        when(dimensionStateManager.previewState(Mockito.eq(t2), Mockito.isNull())).thenReturn(
                state(null, List.of()));
        when(dimensionStateManager.analyzeQuestionRuleBased(t2)).thenReturn(new QuestionAnalysis());

        assertNull(executor.plan(t2, "alice", null, "conv1"));
    }

    @Test
    void followupSignalKeysConfigurableExtendsWhitelist() {
        // 选型 B (2026-11-05): 白名单经 followup-signal-keys 配置注入 — qGate 加入后
        // 传参型追问也走直执行 (纯后端决策, 不开放前端)
        stubFollowupFirstRound();
        enableKeyDictionary(qGateConfig());
        DirectSkillExecutor executor = newExecutor(true).setFollowupDirectEnabled(true)
                .setFollowupSignalKeys(" qGate , ");
        executor.plan(FOLLOWUP_T1, "alice", null, "conv1");

        String t2 = "Q1卡口的呢";
        when(dimensionStateManager.previewState(Mockito.eq(t2), Mockito.isNull())).thenReturn(
                state(null, List.of()));
        when(dimensionStateManager.analyzeQuestionRuleBased(t2)).thenReturn(new QuestionAnalysis());

        DirectSkillExecutor.DirectExecutionPlan followup =
                executor.plan(t2, "alice", null, "conv1");

        assertEquals("dept_skill", followup.skillName());
        assertEquals("杭州开发二部", followup.params().get("dept"));       // 快照继承 (qGate 未绑 param)
        assertEquals("2026年7月份版本", followup.params().get("version"));
    }

    // ==================== 同级维度消解 (docs/202611/同级维度消解-维度提及无值快照删除与词表冲突方案.md §6) ====================

    /** 家族映射仓储桩 (D1 拍板 dimension_family_config): value/family 交错传参。 */
    private DimensionFamilyConfigRepository familyConfigRepository(String... valueFamilyPairs) {
        DimensionFamilyConfigRepository repo = mock(DimensionFamilyConfigRepository.class);
        List<DimensionFamilyConfig> rows = new ArrayList<>();
        for (int i = 0; i + 1 < valueFamilyPairs.length; i += 2) {
            rows.add(new DimensionFamilyConfig((long) (rows.size() + 1),
                    valueFamilyPairs[i], valueFamilyPairs[i + 1], true, null));
        }
        when(repo.findAllEnabled()).thenReturn(rows);
        return repo;
    }

    /** 首轮 (dept+version+group 快照, group 非必填) + drillDown 词表 + 家族映射公共桩。 */
    private DirectSkillExecutor stubFamilyFirstRound(DimensionFamilyConfigRepository familyRepo,
                                                     DimensionKeyConfig... keyConfigs) {
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a",
                        "{\"dept\":\"deptName\",\"version\":\"versionPlan\",\"group\":\"groupName\","
                                + "\"group_by\":\"drillDown\",\"dim\":\"dimension\"}",
                        "达标率")));
        enableKeyDictionary(keyConfigs);
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(FOLLOWUP_T1), Mockito.isNull())).thenReturn(
                new DimensionState(versionTime(List.of("2026年7月份版本")), List.of("杭州开发二部"),
                        new DimensionState.PeerDimension(DimensionState.PeerDimensionType.TEAM,
                                List.of("车贷组")), null));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(
                "[{\"name\":\"dept\",\"required\":false},{\"name\":\"version\",\"required\":true},"
                        + "{\"name\":\"group\",\"required\":false},{\"name\":\"group_by\",\"required\":false},"
                        + "{\"name\":\"dim\",\"required\":false}]"));
        return newExecutor(true).setFollowupDirectEnabled(true)
                .setFamilyConfigRepository(familyRepo);
    }

    /** 形态③追问轮状态桩: preview 丢 peer 槽位 (PEER 级继承规则, 方案 §2.1 盲区) —
     *  preSuppression 捕获前组值已消失, D3 diff 盲区。 */
    private void stubFamilyFollowupRound(String question, List<String> depts) {
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull())).thenReturn(
                state(versionTime(List.of("2026年7月份版本")), depts));
        when(dimensionStateManager.analyzeQuestionRuleBased(question)).thenReturn(new QuestionAnalysis());
        when(dimensionStateManager.hasExplicitTeamHit(question)).thenReturn(false);
        when(dimensionStateManager.hasExplicitProductLineHit(question)).thenReturn(false);
    }

    @Test
    void followupDimensionMentionWithoutValueRemovesInheritedGroup() {
        // 主 bug 回归 (方案 §3.4):「各组的呢」快照组值残留 — 形态③ preview 继承即丢,
        // D3 diff 盲区; 家族消解按「提及无值」删快照组参数, dept/version 照常继承
        DirectSkillExecutor executor = stubFamilyFirstRound(
                familyConfigRepository("组维度", "groupName", "部门维度", "deptName", "杭研维度", "deptName"),
                new DimensionKeyConfig(10L, "drillDown", "下钻维度", false, List.of(
                        new DimensionKeyConfig.AliasMapping("各组", "组维度"),
                        new DimensionKeyConfig.AliasMapping("各个组", "组维度"),
                        new DimensionKeyConfig.AliasMapping("各个部门", "部门维度")),
                        true, null));
        DirectSkillExecutor.DirectExecutionPlan first =
                executor.plan(FOLLOWUP_T1, "alice", null, "conv1");
        assertEquals("车贷组", first.params().get("group"));

        String t2 = "各组的呢";
        stubFamilyFollowupRound(t2, List.of("杭州开发二部"));
        DirectSkillExecutor.DirectExecutionPlan followup =
                executor.plan(t2, "alice", null, "conv1");

        assertEquals("dept_skill", followup.skillName());
        assertEquals("组维度", followup.params().get("group_by"));
        assertFalse(followup.params().containsKey("group"));                  // 家族消解删除 (主 bug)
        assertEquals("杭州开发二部", followup.params().get("dept"));          // 未提及 → 快照继承
        assertEquals("2026年7月份版本", followup.params().get("version"));
    }

    @Test
    void followupDimensionKeySignalRemovesInheritedGroup() {
        // 双词表收敛 + D5 拍板: 仅 dimension 键命中的追问 (「各统计组呢」) 走追问层,
        // dimension 词表值 统计组 与 drillDown 组维度 同轴收敛到 groupName 家族
        DirectSkillExecutor executor = stubFamilyFirstRound(
                familyConfigRepository("组维度", "groupName", "统计组", "groupName"),
                new DimensionKeyConfig(23L, "dimension", "统计维度", false, List.of(
                        new DimensionKeyConfig.AliasMapping("统计组", "统计组")),
                        true, null));
        executor.plan(FOLLOWUP_T1, "alice", null, "conv1");

        String t2 = "各统计组呢";
        stubFamilyFollowupRound(t2, List.of("杭州开发二部"));
        DirectSkillExecutor.DirectExecutionPlan followup =
                executor.plan(t2, "alice", null, "conv1");

        assertEquals("dept_skill", followup.skillName());
        assertEquals("统计组", followup.params().get("dim"));
        assertFalse(followup.params().containsKey("group"));                  // 双词表同轴消解
        assertEquals("杭州开发二部", followup.params().get("dept"));
    }

    @Test
    void followupGroupValueReMentionedKeepsSnapshotGroup() {
        // 值重提保留 (方案 §3.1):「车贷组各个组呢」组值显式重提 → extraction 家族非空 → 不删
        DirectSkillExecutor executor = stubFamilyFirstRound(
                familyConfigRepository("组维度", "groupName"),
                new DimensionKeyConfig(10L, "drillDown", "下钻维度", false, List.of(
                        new DimensionKeyConfig.AliasMapping("各个组", "组维度")),
                        true, null));
        executor.plan(FOLLOWUP_T1, "alice", null, "conv1");

        String t2 = "车贷组各个组呢";
        when(dimensionStateManager.previewState(Mockito.eq(t2), Mockito.isNull())).thenReturn(
                new DimensionState(versionTime(List.of("2026年7月份版本")), List.of("杭州开发二部"),
                        new DimensionState.PeerDimension(DimensionState.PeerDimensionType.TEAM,
                                List.of("车贷组")), null));
        when(dimensionStateManager.analyzeQuestionRuleBased(t2)).thenReturn(new QuestionAnalysis());
        when(dimensionStateManager.hasExplicitTeamHit(t2)).thenReturn(true);   // 显式重提
        when(dimensionStateManager.hasExplicitProductLineHit(t2)).thenReturn(false);

        DirectSkillExecutor.DirectExecutionPlan followup =
                executor.plan(t2, "alice", null, "conv1");

        assertEquals("车贷组", followup.params().get("group"));               // 保留重锚
        assertEquals("组维度", followup.params().get("group_by"));
    }

    @Test
    void followupDeptDrillRemovesInheritedDept() {
        // 「各个部门呢」→ 杭研维度/部门维度家族消解快照部门 (D2: 问题无显式部门 → 删);
        // 下钻口径家族参数 schema 非必填 (D6 拍板), 不触发缺参反问
        DirectSkillExecutor executor = stubFamilyFirstRound(
                familyConfigRepository("部门维度", "deptName", "杭研维度", "deptName"),
                new DimensionKeyConfig(10L, "drillDown", "下钻维度", false, List.of(
                        new DimensionKeyConfig.AliasMapping("各个部门", "部门维度")),
                        true, null));
        executor.plan(FOLLOWUP_T1, "alice", null, "conv1");

        String t2 = "各个部门呢";
        stubFamilyFollowupRound(t2, List.of("杭州开发二部"));
        DirectSkillExecutor.DirectExecutionPlan followup =
                executor.plan(t2, "alice", null, "conv1");

        assertEquals("dept_skill", followup.skillName());
        assertEquals("部门维度", followup.params().get("group_by"));
        assertFalse(followup.params().containsKey("dept"));                   // 部门家族消解
        assertTrue(followup.missingAsk().isEmpty());                          // D6: schema 非必填不反问
    }

    @Test
    void followupExplicitDeptKeepsDeptOnDeptDrill() {
        // D2 拍板: 部门家族「无值」判定用问题派生口径 —「杭州开发三部各个部门呢」
        // 问题字面有显式部门 → 不删, 且本轮重锚替换为三部
        DirectSkillExecutor executor = stubFamilyFirstRound(
                familyConfigRepository("部门维度", "deptName"),
                new DimensionKeyConfig(10L, "drillDown", "下钻维度", false, List.of(
                        new DimensionKeyConfig.AliasMapping("各个部门", "部门维度")),
                        true, null));
        executor.plan(FOLLOWUP_T1, "alice", null, "conv1");

        String t2 = "杭州开发三部各个部门呢";
        when(dimensionStateManager.previewState(Mockito.eq(t2), Mockito.isNull())).thenReturn(
                state(versionTime(List.of("2026年7月份版本")), List.of("杭州开发三部")));
        when(dimensionStateManager.analyzeQuestionRuleBased(t2)).thenReturn(
                analysisWith(null, List.of("杭州开发三部")));
        when(dimensionStateManager.hasExplicitTeamHit(t2)).thenReturn(false);
        when(dimensionStateManager.hasExplicitProductLineHit(t2)).thenReturn(false);

        DirectSkillExecutor.DirectExecutionPlan followup =
                executor.plan(t2, "alice", null, "conv1");

        assertEquals("杭州开发三部", followup.params().get("dept"));          // 显式部门保留 (重锚)
        assertEquals("部门维度", followup.params().get("group_by"));
    }

    @Test
    void followupUnknownDrillValueNoDeletion() {
        // fail-safe (D1 拍板): 家族映射查不到的值 → 不删任何快照参数 (宁漏勿错)
        DirectSkillExecutor executor = stubFamilyFirstRound(
                familyConfigRepository("组维度", "groupName"),
                new DimensionKeyConfig(10L, "drillDown", "下钻维度", false, List.of(
                        new DimensionKeyConfig.AliasMapping("自定义切片", "自定义切片"),
                        new DimensionKeyConfig.AliasMapping("各个组", "组维度")),
                        true, null));
        DirectSkillExecutor.DirectExecutionPlan first =
                executor.plan(FOLLOWUP_T1, "alice", null, "conv1");
        assertEquals("车贷组", first.params().get("group"));

        String t2 = "按自定义切片看呢";
        stubFamilyFollowupRound(t2, List.of("杭州开发二部"));
        DirectSkillExecutor.DirectExecutionPlan followup =
                executor.plan(t2, "alice", null, "conv1");

        assertEquals("dept_skill", followup.skillName());
        assertEquals("车贷组", followup.params().get("group"));               // 未知值 → 快照不删
        assertEquals("自定义切片", followup.params().get("group_by"));
    }

    @Test
    void followupGroupReanchorStripsInheritedDrillDown() {
        // 下钻粒度冲突消解 (2026/11/06 E2E 三轮 bug): R1 杭二7月 → R2 每个人的呢
        // (group_by=个人维度 入快照) → R3 信贷核心组的呢: 组值(细粒度)有值 → 规则2
        // 删快照继承的 group_by (本轮未重提 drillDown, D1 豁免不成立);
        // R3 信号源 = 台账真值扫描 groupHit (内网实链路同源)
        com.agentscopea2a.v2.dimension.PersonLedgerRepository ledger =
                mock(com.agentscopea2a.v2.dimension.PersonLedgerRepository.class);
        when(ledger.dimensionTruthDictionary("统计组"))
                .thenReturn(Set.of("车贷组", "信贷核心组"));
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a",
                        "{\"dept\":\"deptName\",\"version\":\"versionPlan\",\"group\":\"groupName\","
                                + "\"group_by\":\"drillDown\",\"dim\":\"dimension\"}",
                        "达标率")));
        enableKeyDictionary(new DimensionKeyConfig(10L, "drillDown", "下钻维度", false, List.of(
                new DimensionKeyConfig.AliasMapping("每个人", "个人维度"),
                new DimensionKeyConfig.AliasMapping("各个组", "组维度")),
                true, null));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(FOLLOWUP_T1), Mockito.isNull())).thenReturn(
                new DimensionState(versionTime(List.of("2026年7月份版本")), List.of("杭州开发二部"),
                        new DimensionState.PeerDimension(DimensionState.PeerDimensionType.TEAM,
                                List.of("车贷组")), null));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(
                "[{\"name\":\"dept\",\"required\":false},{\"name\":\"version\",\"required\":true},"
                        + "{\"name\":\"group\",\"required\":false},{\"name\":\"group_by\",\"required\":false},"
                        + "{\"name\":\"dim\",\"required\":false}]"));
        DirectSkillExecutor executor = newExecutorWithLedger(ledger).setFollowupDirectEnabled(true)
                .setFamilyConfigRepository(familyConfigRepository(
                        "个人维度", "perName,perNo,perRole", "组维度", "groupName"));
        executor.plan(FOLLOWUP_T1, "alice", null, "conv1");

        String t2 = "每个人的呢";
        stubFamilyFollowupRound(t2, List.of("杭州开发二部"));
        DirectSkillExecutor.DirectExecutionPlan r2 = executor.plan(t2, "alice", null, "conv1");
        assertEquals("个人维度", r2.params().get("group_by"));
        assertEquals("车贷组", r2.params().get("group"));                     // 未提及组 → 快照继承

        String t3 = "信贷核心组的呢";
        when(dimensionStateManager.previewState(Mockito.eq(t3), Mockito.isNull())).thenReturn(
                new DimensionState(versionTime(List.of("2026年7月份版本")), List.of("杭州开发二部"),
                        new DimensionState.PeerDimension(DimensionState.PeerDimensionType.TEAM,
                                List.of("信贷核心组")), null));
        when(dimensionStateManager.analyzeQuestionRuleBased(t3)).thenReturn(new QuestionAnalysis());
        when(dimensionStateManager.hasExplicitTeamHit(t3)).thenReturn(true);   // 组值重锚
        when(dimensionStateManager.hasExplicitProductLineHit(t3)).thenReturn(false);
        DirectSkillExecutor.DirectExecutionPlan r3 = executor.plan(t3, "alice", null, "conv1");

        assertEquals("信贷核心组", r3.params().get("group"));                 // 本轮重锚保留
        assertFalse(r3.params().containsKey("group_by"));                     // 继承粒度与组值冲突 → 删
        assertEquals("杭州开发二部", r3.params().get("dept"));
        assertEquals("2026年7月份版本", r3.params().get("version"));
    }

    @Test
    void followupExplicitPersonDrillKeptWithGroup() {
        // D1 豁免拍板: 「信贷核心组每个人的呢」本轮显式重提 drillDown=个人维度 → 保留,
        // 与组值共存 (规则2 只清继承的粒度参数, 不清本轮显式重提)
        DirectSkillExecutor executor = stubFamilyFirstRound(
                familyConfigRepository("个人维度", "perName,perNo,perRole"),
                new DimensionKeyConfig(10L, "drillDown", "下钻维度", false, List.of(
                        new DimensionKeyConfig.AliasMapping("每个人", "个人维度")),
                        true, null));
        executor.plan(FOLLOWUP_T1, "alice", null, "conv1");

        String t2 = "信贷核心组每个人的呢";
        when(dimensionStateManager.previewState(Mockito.eq(t2), Mockito.isNull())).thenReturn(
                new DimensionState(versionTime(List.of("2026年7月份版本")), List.of("杭州开发二部"),
                        new DimensionState.PeerDimension(DimensionState.PeerDimensionType.TEAM,
                                List.of("信贷核心组")), null));
        when(dimensionStateManager.analyzeQuestionRuleBased(t2)).thenReturn(new QuestionAnalysis());
        when(dimensionStateManager.hasExplicitTeamHit(t2)).thenReturn(true);
        when(dimensionStateManager.hasExplicitProductLineHit(t2)).thenReturn(false);
        DirectSkillExecutor.DirectExecutionPlan r2 = executor.plan(t2, "alice", null, "conv1");

        assertEquals("信贷核心组", r2.params().get("group"));
        assertEquals("个人维度", r2.params().get("group_by"));                // D1 豁免: 显式重提保留
    }

    @Test
    void followupSameFamilyConflictStripsInheritedDeptDrill() {
        // 同家族冲突 (dimension_family_config 判定): R1 杭二 → R2 三部各个部门呢
        // (部门值×部门维度, D1 豁免入快照) → R3 四部呢: 快照继承的 group_by=部门维度
        // 与本轮部门值同家族 (deptName) → 删
        DirectSkillExecutor executor = stubFamilyFirstRound(
                familyConfigRepository("部门维度", "deptName"),
                new DimensionKeyConfig(10L, "drillDown", "下钻维度", false, List.of(
                        new DimensionKeyConfig.AliasMapping("各个部门", "部门维度")),
                        true, null));
        executor.plan(FOLLOWUP_T1, "alice", null, "conv1");

        String t2 = "杭州开发三部各个部门呢";
        when(dimensionStateManager.previewState(Mockito.eq(t2), Mockito.isNull())).thenReturn(
                state(versionTime(List.of("2026年7月份版本")), List.of("杭州开发三部")));
        when(dimensionStateManager.analyzeQuestionRuleBased(t2)).thenReturn(
                analysisWith(null, List.of("杭州开发三部")));
        when(dimensionStateManager.hasExplicitTeamHit(t2)).thenReturn(false);
        when(dimensionStateManager.hasExplicitProductLineHit(t2)).thenReturn(false);
        DirectSkillExecutor.DirectExecutionPlan r2 = executor.plan(t2, "alice", null, "conv1");
        assertEquals("杭州开发三部", r2.params().get("dept"));
        assertEquals("部门维度", r2.params().get("group_by"));                // D1 豁免 (显式重提)

        String t3 = "四部呢";
        when(dimensionStateManager.previewState(Mockito.eq(t3), Mockito.isNull())).thenReturn(
                state(versionTime(List.of("2026年7月份版本")), List.of("杭州开发四部")));
        when(dimensionStateManager.analyzeQuestionRuleBased(t3)).thenReturn(
                analysisWith(null, List.of("杭州开发四部")));
        when(dimensionStateManager.hasExplicitTeamHit(t3)).thenReturn(false);
        when(dimensionStateManager.hasExplicitProductLineHit(t3)).thenReturn(false);
        DirectSkillExecutor.DirectExecutionPlan r3 = executor.plan(t3, "alice", null, "conv1");

        assertEquals("杭州开发四部", r3.params().get("dept"));
        assertFalse(r3.params().containsKey("group_by"));                     // 同家族冲突 → 删继承粒度
    }

    /** drillDown 动态键 (粒度词词表, 业务维护): 「各个组→组维度」等。 */
    private static DimensionKeyConfig drillDownConfig() {
        return new DimensionKeyConfig(10L, "drillDown", "下钻维度", false, List.of(
                new DimensionKeyConfig.AliasMapping("各个组", "组维度"),
                new DimensionKeyConfig.AliasMapping("各个部门", "部门维度"),
                new DimensionKeyConfig.AliasMapping("各产品线", "产品线维度")),
                true, null);
    }

    // ==================== 「分布」触发默认下钻一级 (docs/202611/分布触发默认下钻一级方案.md) ====================

    private static final String DISTRIBUTION_MAPPING =
            "{\"dept\":\"deptName\",\"drillDown\":\"drillDown\"}";
    private static final String DISTRIBUTION_SCHEMA =
            "[{\"name\":\"dept\",\"required\":true},{\"name\":\"drillDown\",\"required\":false}]";

    private void mockDistributionPlan(String question, DimensionState preview, String mapping,
                                      String schema) {
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a", mapping, "缺陷")));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(question), Mockito.isNull()))
                .thenReturn(preview);
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(schema));
    }

    /** 场景2: 问题含「分布」+ 仅部门有值 + 细粒度全无值 → 默认补 drillDown=[组维度] (P1 单值; 2026/11/09 拍板由应用改统计组)。 */
    @Test
    void distributionDrillFillsDrillDownForDept() {
        String question = "杭州开发一部 2026年7月份版本 缺陷问题分布情况";
        mockDistributionPlan(question,
                state(versionTime(List.of("2026年7月份版本")), List.of("杭州开发一部")),
                DISTRIBUTION_MAPPING, DISTRIBUTION_SCHEMA);

        DirectSkillExecutor.DirectExecutionPlan plan = newExecutor(true).plan(question, "alice", null);

        assertEquals("杭州开发一部", plan.params().get("dept"));
        assertEquals("组维度", plan.params().get("drillDown"));
    }

    /** 场景4/5: 组有值 (dept+组多级同值) → 取链上最深一级 (P2 拍板) → 补 [个人维度]。 */
    @Test
    void distributionDrillGroupValueFillsPersonDimension() {
        String question = "信贷核心组 2026年7月份版本 缺陷问题分布情况";
        DimensionState preview = new DimensionState(versionTime(List.of("2026年7月份版本")),
                List.of("杭州开发一部"),
                new DimensionState.PeerDimension(
                        DimensionState.PeerDimensionType.TEAM, List.of("信贷核心组")), null);
        mockDistributionPlan(question, preview, DISTRIBUTION_MAPPING, DISTRIBUTION_SCHEMA);

        DirectSkillExecutor.DirectExecutionPlan plan = newExecutor(true).plan(question, "alice", null);

        assertEquals("个人维度", plan.params().get("drillDown"));
    }

    /** 场景3: 人有值 = 链底, 无更下一级 → 不补。 */
    @Test
    void distributionDrillPersonValueNoFill() {
        String question = "张三 2026年7月份版本 缺陷问题分布情况";
        DimensionState preview = new DimensionState(versionTime(List.of("2026年7月份版本")),
                List.of("杭州开发一部"), null, List.of("张三"));
        mockDistributionPlan(question, preview, DISTRIBUTION_MAPPING, DISTRIBUTION_SCHEMA);

        DirectSkillExecutor.DirectExecutionPlan plan = newExecutor(true).plan(question, "alice", null);

        assertFalse(plan.params().containsKey("drillDown"));
    }

    /** 场景1/8: 显式粒度词词表命中优先, 不被默认值覆盖。 */
    @Test
    void distributionDrillExplicitGranularityNotOverridden() {
        String question = "杭州开发一部 缺陷问题分布情况 按组列";
        mockDistributionPlan(question,
                state(versionTime(List.of("2026年7月份版本")), List.of("杭州开发一部")),
                DISTRIBUTION_MAPPING, DISTRIBUTION_SCHEMA);
        enableKeyDictionary(new DimensionKeyConfig(13L, "drillDown", "下钻维度", false,
                List.of(new DimensionKeyConfig.AliasMapping("按组列", "组维度")), true, null));

        DirectSkillExecutor.DirectExecutionPlan plan = newExecutor(true).plan(question, "alice", null);

        assertEquals("组维度", plan.params().get("drillDown"));
    }

    /** P6 拍板: 开关关闭不补。 */
    @Test
    void distributionDrillDisabledNoFill() {
        String question = "杭州开发一部 2026年7月份版本 缺陷问题分布情况";
        mockDistributionPlan(question,
                state(versionTime(List.of("2026年7月份版本")), List.of("杭州开发一部")),
                DISTRIBUTION_MAPPING, DISTRIBUTION_SCHEMA);

        DirectSkillExecutor.DirectExecutionPlan plan = newExecutor(true)
                .setDistributionDrillEnabled(false).plan(question, "alice", null);

        assertFalse(plan.params().containsKey("drillDown"));
    }

    /** 场景8: 零维度锚点不补 (无人/组/应用/产品线/部门有值)。 */
    @Test
    void distributionDrillNoDimensionAnchorNoFill() {
        String question = "缺陷问题分布情况";
        mockDistributionPlan(question, state(versionTime(List.of("2026年7月份版本")), List.of()),
                DISTRIBUTION_MAPPING, "[{\"name\":\"drillDown\",\"required\":false}]");

        DirectSkillExecutor.DirectExecutionPlan plan = newExecutor(true).plan(question, "alice", null);

        assertFalse(plan.params().containsKey("drillDown"));
    }

    /** 场景10: 仅配置 dimension (统计维度) 参数的 skill → 补 dimension 词表值「统计组」
     *  (2026/11/09 拍板修订, 原为「应用」); drillDown/dimension 二选一 (拍板确认), 按 mapping 实际配置的参数补。 */
    @Test
    void distributionDrillDimensionParamFillsDimensionVocabValue() {
        String question = "杭州开发一部 2026年7月份版本 缺陷问题分布情况";
        mockDistributionPlan(question,
                state(versionTime(List.of("2026年7月份版本")), List.of("杭州开发一部")),
                "{\"dept\":\"deptName\",\"dimension\":\"dimension\"}",
                "[{\"name\":\"dept\",\"required\":true},{\"name\":\"dimension\",\"required\":false}]");

        DirectSkillExecutor.DirectExecutionPlan plan = newExecutor(true).plan(question, "alice", null);

        assertFalse(plan.params().containsKey("drillDown"));
        assertEquals("统计组", plan.params().get("dimension"));
    }

    /** E2E bug 回归 (2026-11-09): 「分布情况呢?」零其他维度信号 → hasDimensionSignal
     *  不认 → 回退 LLM 丢快照继承+默认下钻。修复后: 分布触发词构成追问信号,
     *  追问轨继承 dept/version 并默认补 drillDown=[组维度] (2026/11/09 拍板部门默认改统计组)。 */
    @Test
    void followupDistributionQuestionInheritsAndFillsDefaultDrill() {
        when(metadataRepository.findActive()).thenReturn(List.of(
                meta("dept_skill", "script_a",
                        "{\"dept\":\"deptName\",\"version\":\"versionPlan\",\"drill\":\"drillDown\"}",
                        "达标率")));
        allowAll("alice");
        when(dimensionStateManager.previewState(Mockito.eq(FOLLOWUP_T1), Mockito.isNull())).thenReturn(
                state(versionTime(List.of("2026年7月份版本")), List.of("杭州开发二部")));
        when(scriptRegistryMapper.selectByScriptId("script_a")).thenReturn(registryEntry(
                "[{\"name\":\"dept\",\"required\":true},{\"name\":\"version\",\"required\":true},"
                        + "{\"name\":\"drill\",\"required\":false}]"));
        DirectSkillExecutor executor = newExecutor(true).setFollowupDirectEnabled(true);
        executor.plan(FOLLOWUP_T1, "alice", null, "conv1");

        String t2 = "分布情况呢？";
        when(dimensionStateManager.previewState(Mockito.eq(t2), Mockito.isNull())).thenReturn(
                state(null, List.of()));
        when(dimensionStateManager.analyzeQuestionRuleBased(t2)).thenReturn(new QuestionAnalysis());
        when(dimensionStateManager.hasExplicitTeamHit(t2)).thenReturn(false);
        when(dimensionStateManager.hasExplicitProductLineHit(t2)).thenReturn(false);

        DirectSkillExecutor.DirectExecutionPlan r2 = executor.plan(t2, "alice", null, "conv1");

        assertEquals("dept_skill", r2.skillName());
        assertEquals("杭州开发二部", r2.params().get("dept"));           // 快照继承
        assertEquals("2026年7月份版本", r2.params().get("version"));      // 时间家族继承 (未重锚)
        assertEquals("组维度", r2.params().get("drill"));              // 分布默认下钻 (P1/P2; 部门默认=组维度)
    }
}
