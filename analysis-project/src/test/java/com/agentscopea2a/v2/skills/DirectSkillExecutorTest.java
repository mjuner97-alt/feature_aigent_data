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
        // 8 内置含 versionPlan/vestQuarter → 版本/季度 .start/.end 后缀恒在
        assertEquals(13, newExecutor(true).activeKeys().size());
        enableKeyDictionary(qGateConfig());
        java.util.Set<String> keys = newExecutor(true).activeKeys();
        assertEquals(14, keys.size());
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
        // 8 内置 + timeRange + timeRange .start/.end + versionPlan/vestQuarter 各 .start/.end
        assertEquals(16, keys.size());
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
                v.mapping(), v.filledParams(), v.missingKeys(),
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
        // versionPlan/vestQuarter 本就在内置键里, 字典行去重: 9 内置 + timeRange + 6 后缀
        assertEquals(16, keys.size());
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
}
