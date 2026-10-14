package com.agentscopea2a.v2.skills;

import com.agentscopea2a.entity.ScriptRegistryEntry;
import com.agentscopea2a.mapper.gauss.ScriptRegistryMapper;
import com.agentscopea2a.v2.dimension.DimensionKeyConfig;
import com.agentscopea2a.v2.dimension.DimensionKeyConfigRepository;
import com.agentscopea2a.v2.dimension.DimensionState;
import com.agentscopea2a.v2.dimension.DimensionStateManager;
import com.agentscopea2a.v2.hooks.VersionPlanKnowledgeHook;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDateTime;
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
    void missingVersionFilledWithCurrentInBuildVersion() {
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

        assertEquals("2026年10月份版本", plan.params().get("version"));
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
        assertEquals(8, newExecutor(true).activeKeys().size());
        enableKeyDictionary(qGateConfig());
        java.util.Set<String> keys = newExecutor(true).activeKeys();
        assertEquals(9, keys.size());
        assertTrue(keys.contains("qGate"));
        assertTrue(keys.contains("deptName"));
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
}
