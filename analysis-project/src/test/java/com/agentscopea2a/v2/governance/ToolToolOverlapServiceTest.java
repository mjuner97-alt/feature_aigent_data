package com.agentscopea2a.v2.governance;

import com.agentscopea2a.v2.skills.EmbeddingClient;
import com.agentscopea2a.v2.toolrouting.ToolMetadataResponse;
import com.agentscopea2a.v2.toolrouting.ToolParameterMetadata;
import com.agentscopea2a.v2.toolrouting.ToolRoutingMetadata;
import com.agentscopea2a.v2.toolrouting.ToolRoutingMetadataRepository;
import com.agentscopea2a.v2.toolrouting.ToolRoutingScanCandidate;
import com.agentscopea2a.v2.toolrouting.ToolRoutingScanService;
import com.agentscopea2a.v2.toolrouting.ToolRoutingToolType;
import com.agentscopea2a.v2.toolrouting.UnifiedToolMetadataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolToolOverlapServiceTest {

    private ToolRoutingMetadataRepository toolRoutingRepo;
    private UnifiedToolMetadataService unifiedService;
    private ToolRoutingScanService scanService;

    @BeforeEach
    void setUp() {
        toolRoutingRepo = mock(ToolRoutingMetadataRepository.class);
        unifiedService = mock(UnifiedToolMetadataService.class);
        scanService = mock(ToolRoutingScanService.class);
        when(toolRoutingRepo.findEnabled()).thenReturn(List.of());
        when(scanService.scan()).thenReturn(List.of());
    }

    private ToolRoutingMetadata toolOfType(ToolRoutingToolType type, String toolId, String description, List<String> topicTags) {
        return new ToolRoutingMetadata(toolId, type, description,
                topicTags, List.of("metric"), List.of(), 0, true, null);
    }

    @Test
    void sameToolIdCrossTypeDuplicateIsHighWithoutLayerEvidence() {
        // 同 toolId 重复注册 (SCRIPT + SQL): 调用层按 toolId 寻址不可区分, 不依赖四层证据直接 HIGH
        when(toolRoutingRepo.findEnabled()).thenReturn(List.of(
                toolOfType(ToolRoutingToolType.SCRIPT, "q2_1_dup", "pandas 算聚合数", List.of("QI卡口")),
                toolOfType(ToolRoutingToolType.SQL, "q2_1_dup", "返回打分字段", List.of("QI卡口"))));
        ToolToolOverlapService service = degradedService();

        List<ToolToolOverlapView> items = report(service);
        assertEquals(1, items.size());
        assertEquals("HIGH", items.get(0).level());
        assertEquals("SCRIPT", items.get(0).toolTypeA());
        assertEquals("SQL", items.get(0).toolTypeB());
        assertEquals("q2_1_dup", items.get(0).toolIdA());
        assertTrue(items.get(0).suggestion().contains("重复注册"));
        assertTrue(items.get(0).suggestion().contains("toolId"));
    }

    @Test
    void sameToolIdCollisionFromRegistryScanIsDetectedEvenWithoutMetadataRow() {
        // 元数据表无同 (id, type) 行的另一类型注册, 由扫描候选 (DUPLICATE_TOOL_ID) 合成
        // 记录补进范围, 仍要检出 HIGH; 启用状态如实: 元数据侧已启用, 合成侧未配置=未启用
        when(toolRoutingRepo.findEnabled()).thenReturn(List.of(
                toolOfType(ToolRoutingToolType.SCRIPT, "q2_1_dup", "pandas 算聚合数", List.of("QI卡口"))));
        when(scanService.scan()).thenReturn(List.of(
                scanCandidate(ToolRoutingToolType.SCRIPT, "q2_1_dup", "pandas 算聚合数", true),
                scanCandidate(ToolRoutingToolType.SQL, "q2_1_dup", "返回打分字段", true),
                scanCandidate(ToolRoutingToolType.SQL, "normal_tool", "普通工具", false)));
        ToolToolOverlapService service = degradedService();

        List<ToolToolOverlapView> items = report(service);
        assertEquals(1, items.size(), "only the duplicate-id pair enters the report");
        assertEquals("HIGH", items.get(0).level());
        assertEquals("q2_1_dup", items.get(0).toolIdA());
        assertEquals("SCRIPT", items.get(0).toolTypeA());
        assertEquals("SQL", items.get(0).toolTypeB());
        assertTrue(items.get(0).enabledA());
        assertFalse(items.get(0).enabledB(), "unconfigured synthetic side must not show as enabled");
        assertTrue(items.get(0).suggestion().contains("重复注册"));
    }

    @Test
    void unconfiguredSameToolIdPairIsReportedWithDisabledState() {
        // 双方都未配置 (无元数据行): 注册冲突是注册层缺陷, 与开关状态无关, 照常报告,
        // 但两侧都标未启用 (与工具路由页状态一致), 不再硬编码已启用
        when(toolRoutingRepo.findEnabled()).thenReturn(List.of());
        when(toolRoutingRepo.findAll()).thenReturn(List.of());
        when(scanService.scan()).thenReturn(List.of(
                scanCandidate(ToolRoutingToolType.SCRIPT, "q2_2_dup", "脚本侧", true),
                scanCandidate(ToolRoutingToolType.SQL, "q2_2_dup", "SQL侧", true)));
        ToolToolOverlapService service = degradedService();

        List<ToolToolOverlapView> items = report(service);
        assertEquals(1, items.size());
        assertEquals("HIGH", items.get(0).level());
        assertFalse(items.get(0).enabledA());
        assertFalse(items.get(0).enabledB());
    }

    private ToolRoutingScanCandidate scanCandidate(ToolRoutingToolType type, String toolId,
                                                   String description, boolean duplicate) {
        return new ToolRoutingScanCandidate(toolId, type, toolId, description, "", "",
                true, false, false, duplicate ? List.of("DUPLICATE_TOOL_ID") : List.of());
    }

    @Test
    void sameToolIdCrossTypeBothDisabledStillReportedWithTrueState() {
        // 已配置但双方都停用的同 toolId 对: 注册缺陷与开关无关仍报告 (否则重叠页角标消失,
        // 整改闭环断在配置期), 但启用标签如实显示未启用; 非 same-toolId 的双禁用对仍被过滤
        ToolRoutingMetadata disabledScript = new ToolRoutingMetadata("q2_1_dup", ToolRoutingToolType.SCRIPT,
                "pandas", List.of("QI卡口"), List.of(), List.of(), 0, false, null);
        ToolRoutingMetadata disabledSql = new ToolRoutingMetadata("q2_1_dup", ToolRoutingToolType.SQL,
                "sql", List.of("QI卡口"), List.of(), List.of(), 0, false, null);
        ToolRoutingMetadata disabledOther = new ToolRoutingMetadata("other_tool", ToolRoutingToolType.SQL,
                "other", List.of("QI卡口"), List.of(), List.of(), 0, false, null);
        when(toolRoutingRepo.findAll()).thenReturn(List.of(disabledScript, disabledSql, disabledOther));
        ToolToolOverlapService service = degradedService();

        List<ToolToolOverlapView> items = report(service);
        assertEquals(1, items.size(), "only the same-toolId pair survives the both-disabled filter");
        assertFalse(items.get(0).enabledA());
        assertFalse(items.get(0).enabledB());
    }

    private ToolToolOverlapService degradedService() {
        // EmbeddingClient=null -> semanticAvailable=false, warm=true -> degraded (cosine 不可算)
        GovernanceEmbeddingCache cache = new GovernanceEmbeddingCache(null, null, toolRoutingRepo);
        cache.startWarmup();
        return new ToolToolOverlapService(toolRoutingRepo, cache, unifiedService, scanService,
                0.80, 0.88, 0.85, 300000);
    }

    /** 非降级服务: 按描述文本映射到固定向量, cosine 由测试方控制。 */
    private ToolToolOverlapService liveService(Map<String, float[]> vectorByText) {
        EmbeddingClient embedding = mock(EmbeddingClient.class);
        when(embedding.embed(anyString())).thenAnswer(inv ->
                vectorByText.getOrDefault((String) inv.getArgument(0), new float[] {1f, 0f}));
        GovernanceEmbeddingCache cache = new GovernanceEmbeddingCache(embedding, null, toolRoutingRepo);
        cache.startWarmup();
        awaitWarm(cache);
        return new ToolToolOverlapService(toolRoutingRepo, cache, unifiedService, scanService,
                0.80, 0.88, 0.85, 300000);
    }

    private static void awaitWarm(GovernanceEmbeddingCache cache) {
        long deadline = System.currentTimeMillis() + 5000;
        while (!cache.warm() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertTrue(cache.warm(), "embedding cache warmup timed out");
    }

    private ToolRoutingMetadata tool(String toolId, String description, List<String> topicTags) {
        return new ToolRoutingMetadata(toolId, ToolRoutingToolType.SQL, description,
                topicTags, List.of("metric"), List.of(), 0, true, null);
    }

    private ToolRoutingMetadata toolWithDims(String toolId, String description,
                                             List<String> topicTags, List<String> dimensionTags) {
        return new ToolRoutingMetadata(toolId, ToolRoutingToolType.SQL, description,
                topicTags, List.of("metric"), dimensionTags, 0, true, null);
    }

    private void stubParams(String toolId, List<ToolParameterMetadata> params) {
        when(unifiedService.find(toolId)).thenReturn(new ToolMetadataResponse(
                toolId, ToolRoutingToolType.SQL, "desc", List.of("topic"), List.of("metric"),
                List.of(), 0, "sql", params, null));
    }

    private static List<ToolToolOverlapView> report(ToolToolOverlapService service) {
        return service.report().items();
    }

    @Test
    void sameCandidateSetAndIndistinguishableDescriptionIsHigh() {
        when(toolRoutingRepo.findEnabled()).thenReturn(List.of(
                tool("t_alpha", "描述甲", List.of("topic")),
                tool("t_beta", "描述乙", List.of("topic"))));
        ToolToolOverlapService service = liveService(Map.of(
                "描述甲", new float[] {1f, 0f},
                "描述乙", new float[] {1f, 0f}));

        List<ToolToolOverlapView> items = report(service);
        assertEquals(1, items.size());
        assertEquals("HIGH", items.get(0).level());
        assertTrue(items.get(0).differingLayers().isEmpty());
        assertTrue(items.get(0).suggestion().contains("t_alpha"));
        assertTrue(items.get(0).suggestion().contains("与工具"));
    }

    @Test
    void sameCandidateSetMediumCosineButDistinguishableSignatureIsMedium() {
        // cosine 0.85 (0.80~0.88) 且参数签名可区分 -> MEDIUM
        when(toolRoutingRepo.findEnabled()).thenReturn(List.of(
                tool("t_alpha", "描述甲", List.of("topic")),
                tool("t_beta", "描述乙", List.of("topic"))));
        stubParams("t_alpha", List.of(new ToolParameterMetadata("dept", "string", true, "d")));
        stubParams("t_beta", List.of(new ToolParameterMetadata("month", "string", true, "d")));
        ToolToolOverlapService service = liveService(Map.of(
                "描述甲", new float[] {1f, 0f},
                "描述乙", new float[] {0.85f, (float) Math.sqrt(1 - 0.85 * 0.85)}));

        List<ToolToolOverlapView> items = report(service);
        assertEquals(1, items.size());
        assertEquals("MEDIUM", items.get(0).level());
        assertFalse(items.get(0).signatureSame());
        assertTrue(items.get(0).differingLayers().contains("描述"));
    }

    @Test
    void mediumCosineDoesNotEscalateToHighEvenWithSameSignature() {
        // v2: cosine 0.80~0.88 区间即 L4 "有差异", 不再与签名叠加升 HIGH
        when(toolRoutingRepo.findEnabled()).thenReturn(List.of(
                tool("t_alpha", "描述甲", List.of("topic")),
                tool("t_beta", "描述乙", List.of("topic"))));
        List<ToolParameterMetadata> params = List.of(
                new ToolParameterMetadata("dept", "string", true, "d"));
        stubParams("t_alpha", params);
        stubParams("t_beta", params);
        ToolToolOverlapService service = liveService(Map.of(
                "描述甲", new float[] {1f, 0f},
                "描述乙", new float[] {0.85f, (float) Math.sqrt(1 - 0.85 * 0.85)}));

        List<ToolToolOverlapView> items = report(service);
        assertEquals("MEDIUM", items.get(0).level());
        assertTrue(items.get(0).signatureSame());
        assertTrue(items.get(0).differingLayers().contains("描述"));
    }

    @Test
    void dimensionVariantToolsWithIntersectingDimsAreCappedAtMedium() {
        // 生产复现 (BacklogIssueNumByApp/ByDept): 描述全同 (cosine=1.0)、topic/metric 全同,
        // dimension_tags 相交但不同 (都含"版本计划") -> 差异层=维度标签, MEDIUM 不阻断
        when(toolRoutingRepo.findEnabled()).thenReturn(List.of(
                toolWithDims("BacklogIssueNumByApp", "统计遗留问题数量", List.of("遗留问题"), List.of("应用", "版本计划")),
                toolWithDims("BacklogIssueNumByDept", "统计遗留问题数量", List.of("遗留问题"), List.of("部门", "版本计划"))));
        ToolToolOverlapService service = liveService(Map.of());

        List<ToolToolOverlapView> items = report(service);
        assertEquals(1, items.size());
        assertEquals("MEDIUM", items.get(0).level());
        assertTrue(items.get(0).differingLayers().contains("维度标签"));
        assertTrue(items.get(0).suggestion().contains("维度标签不同"));
        assertTrue(items.get(0).suggestion().contains("应用、版本计划"));
        assertTrue(items.get(0).suggestion().contains("部门、版本计划"));
    }

    @Test
    void metricTagDifferenceCapsAtMedium() {
        // metric_tags 不等 -> 差异层=指标标签
        when(toolRoutingRepo.findEnabled()).thenReturn(List.of(
                new ToolRoutingMetadata("t_alpha", ToolRoutingToolType.SQL, "描述甲",
                        List.of("topic"), List.of("遗留问题"), List.of(), 0, true, null),
                new ToolRoutingMetadata("t_beta", ToolRoutingToolType.SQL, "描述乙",
                        List.of("topic"), List.of("缺陷"), List.of(), 0, true, null)));
        ToolToolOverlapService service = liveService(Map.of(
                "描述甲", new float[] {1f, 0f},
                "描述乙", new float[] {1f, 0f}));

        List<ToolToolOverlapView> items = report(service);
        assertEquals(1, items.size());
        assertEquals("MEDIUM", items.get(0).level());
        assertTrue(items.get(0).differingLayers().contains("指标标签"));
        assertTrue(items.get(0).suggestion().contains("指标标签不同"));
    }

    @Test
    void intersectingButUnequalTopicsCapAtMedium() {
        // topic 相交但不相等 -> 差异层=业务主题
        when(toolRoutingRepo.findEnabled()).thenReturn(List.of(
                tool("t_alpha", "描述甲", List.of("topic", "topic_x")),
                tool("t_beta", "描述乙", List.of("topic", "topic_y"))));
        ToolToolOverlapService service = liveService(Map.of(
                "描述甲", new float[] {1f, 0f},
                "描述乙", new float[] {1f, 0f}));

        List<ToolToolOverlapView> items = report(service);
        assertEquals(1, items.size());
        assertEquals("MEDIUM", items.get(0).level());
        assertTrue(items.get(0).differingLayers().contains("业务主题"));
    }

    @Test
    void sameDimensionStillHigh() {
        // dimension_tags 相同 -> L3 无差异, 描述无差异 -> 四层全同 -> HIGH
        when(toolRoutingRepo.findEnabled()).thenReturn(List.of(
                toolWithDims("t_alpha", "描述甲", List.of("topic"), List.of("应用")),
                toolWithDims("t_beta", "描述乙", List.of("topic"), List.of("应用"))));
        ToolToolOverlapService service = liveService(Map.of(
                "描述甲", new float[] {1f, 0f},
                "描述乙", new float[] {1f, 0f}));

        assertEquals("HIGH", report(service).get(0).level());
        assertTrue(report(service).get(0).differingLayers().isEmpty());
    }

    @Test
    void dimensionEscapeAppliesToSignatureEvidenceInDegraded() {
        // degraded 下 T0+T2 命中, 但维度标签相交而不同 -> 差异层豁免同样生效, 封顶 MEDIUM
        when(toolRoutingRepo.findEnabled()).thenReturn(List.of(
                toolWithDims("t_alpha", "描述甲", List.of("topic"), List.of("应用", "版本计划")),
                toolWithDims("t_beta", "描述乙", List.of("topic"), List.of("部门", "版本计划"))));
        List<ToolParameterMetadata> params = List.of(
                new ToolParameterMetadata("dept", "string", true, "d"));
        stubParams("t_alpha", params);
        stubParams("t_beta", params);

        List<ToolToolOverlapView> items = report(degradedService());
        assertEquals("MEDIUM", items.get(0).level());
        assertTrue(items.get(0).differingLayers().contains("维度标签"));
    }

    @Test
    void disjointTopicsNeverOverlapEvenWithIdenticalDescriptions() {
        when(toolRoutingRepo.findEnabled()).thenReturn(List.of(
                tool("t_alpha", "描述甲", List.of("topic_a")),
                tool("t_beta", "描述甲", List.of("topic_b"))));
        ToolToolOverlapService service = liveService(Map.of());

        assertTrue(report(service).isEmpty());
    }

    @Test
    void similarNamesAloneAreLow() {
        when(toolRoutingRepo.findEnabled()).thenReturn(List.of(
                tool("query_data", "描述甲", List.of("topic_a")),
                tool("query_data1", "描述乙", List.of("topic_b"))));
        ToolToolOverlapService service = liveService(Map.of());

        List<ToolToolOverlapView> items = report(service);
        assertEquals(1, items.size());
        assertEquals("LOW", items.get(0).level());
        assertTrue(items.get(0).aliasHit());
    }

    @Test
    void degradedStillBlocksOnIdenticalSignature() {
        // degraded 下 cosine 不可算, 但 T0+T2 完全一致仍 HIGH
        when(toolRoutingRepo.findEnabled()).thenReturn(List.of(
                tool("t_alpha", "描述甲", List.of("topic")),
                tool("t_beta", "描述乙", List.of("topic"))));
        List<ToolParameterMetadata> params = List.of(
                new ToolParameterMetadata("dept", "string", true, "d"));
        stubParams("t_alpha", params);
        stubParams("t_beta", params);

        ToolToolOverlapService service = degradedService();
        assertTrue(service.report().degraded());
        assertEquals("HIGH", report(service).get(0).level());
        assertTrue(report(service).get(0).signatureSame());
    }

    @Test
    void degradedWithoutSignatureEvidenceDoesNotProduceHigh() {
        when(toolRoutingRepo.findEnabled()).thenReturn(List.of(
                tool("t_alpha", "描述甲", List.of("topic")),
                tool("t_beta", "描述乙", List.of("topic"))));
        stubParams("t_alpha", List.of(new ToolParameterMetadata("dept", "string", true, "d")));
        stubParams("t_beta", List.of(new ToolParameterMetadata("month", "string", true, "d")));

        assertTrue(report(degradedService()).isEmpty());
    }

    @Test
    void invalidateClearsCache() {
        when(toolRoutingRepo.findEnabled()).thenReturn(List.of(
                tool("t_alpha", "描述甲", List.of("topic_a")),
                tool("t_beta", "描述乙", List.of("topic_b"))));
        ToolToolOverlapService service = degradedService();
        assertTrue(report(service).isEmpty());

        // 元数据变化后 invalidate -> 重新计算
        when(toolRoutingRepo.findEnabled()).thenReturn(List.of(
                tool("t_alpha", "描述甲", List.of("topic")),
                tool("t_beta", "描述乙", List.of("topic"))));
        List<ToolParameterMetadata> params = List.of(
                new ToolParameterMetadata("dept", "string", true, "d"));
        stubParams("t_alpha", params);
        stubParams("t_beta", params);
        service.invalidate();

        assertEquals("HIGH", report(service).get(0).level());
    }

    @Test
    void summaryCountsHighOnBothSides() {
        when(toolRoutingRepo.findEnabled()).thenReturn(List.of(
                tool("t_alpha", "描述甲", List.of("topic")),
                tool("t_beta", "描述乙", List.of("topic"))));
        List<ToolParameterMetadata> params = List.of(
                new ToolParameterMetadata("dept", "string", true, "d"));
        stubParams("t_alpha", params);
        stubParams("t_beta", params);

        ToolToolOverlapService.OverlapSummary summary = degradedService().summary();
        assertEquals(1, summary.high());
        assertEquals(1L, summary.highByTool().get("t_alpha"));
        assertEquals(1L, summary.highByTool().get("t_beta"));
    }

    @Test
    void summaryPutsSameToolIdCollisionInDuplicateByToolNotHighByTool() {
        // 同 toolId 注册冲突: 不进 highByTool (前端据此置灰启用开关), 单独给 duplicateByTool 角标信号
        when(toolRoutingRepo.findEnabled()).thenReturn(List.of(
                toolOfType(ToolRoutingToolType.SCRIPT, "q2_1_dup", "pandas", List.of("QI卡口"))));
        when(scanService.scan()).thenReturn(List.of(
                scanCandidate(ToolRoutingToolType.SCRIPT, "q2_1_dup", "pandas", true),
                scanCandidate(ToolRoutingToolType.SQL, "q2_1_dup", "sql", true)));

        ToolToolOverlapService.OverlapSummary summary = degradedService().summary();
        assertEquals(1, summary.high());
        assertFalse(summary.highByTool().containsKey("q2_1_dup"));
        assertEquals(1L, summary.duplicateByTool().get("q2_1_dup"));
    }
}
