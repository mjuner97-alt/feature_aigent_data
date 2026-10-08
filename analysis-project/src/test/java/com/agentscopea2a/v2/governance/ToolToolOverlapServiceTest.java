package com.agentscopea2a.v2.governance;

import com.agentscopea2a.v2.skills.EmbeddingClient;
import com.agentscopea2a.v2.toolrouting.ToolMetadataResponse;
import com.agentscopea2a.v2.toolrouting.ToolParameterMetadata;
import com.agentscopea2a.v2.toolrouting.ToolRoutingMetadata;
import com.agentscopea2a.v2.toolrouting.ToolRoutingMetadataRepository;
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

    @BeforeEach
    void setUp() {
        toolRoutingRepo = mock(ToolRoutingMetadataRepository.class);
        unifiedService = mock(UnifiedToolMetadataService.class);
        when(toolRoutingRepo.findEnabled()).thenReturn(List.of());
    }

    private ToolToolOverlapService degradedService() {
        // EmbeddingClient=null -> semanticAvailable=false, warm=true -> degraded (cosine 不可算)
        GovernanceEmbeddingCache cache = new GovernanceEmbeddingCache(null, null, toolRoutingRepo);
        cache.startWarmup();
        return new ToolToolOverlapService(toolRoutingRepo, cache, unifiedService,
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
        return new ToolToolOverlapService(toolRoutingRepo, cache, unifiedService,
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
    }

    @Test
    void mediumCosineWithSameSignatureEscalatesToHigh() {
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

        assertEquals("HIGH", report(service).get(0).level());
        assertTrue(report(service).get(0).signatureSame());
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
}
