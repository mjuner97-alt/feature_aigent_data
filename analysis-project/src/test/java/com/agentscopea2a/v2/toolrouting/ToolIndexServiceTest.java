package com.agentscopea2a.v2.toolrouting;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolIndexServiceTest {

    private final ToolIndexService service = new ToolIndexService();

    @Test
    void matchesAnyMetricThenAllDimensionsInStablePriorityOrder() {
        ToolRoutingCatalog catalog = catalog();

        ToolIndexResponse response = service.index(catalog, new ToolIndexRequest(
                List.of("质量"), List.of("质量分", "达标率"), List.of("部门", "季度"), List.of(), 10));

        assertEquals(2, response.matchedCount());
        assertFalse(response.truncated());
        assertEquals(List.of("script_quality", "sql_quality"), response.candidates().stream()
                .map(ToolIndexCandidate::toolId).toList());
        assertEquals(List.of("季度", "版本", "部门"), response.availableDimensionTags());
    }

    @Test
    void reportsUnknownTagsAndDoesNotFallbackToFullCatalog() {
        ToolIndexResponse response = service.index(catalog(), new ToolIndexRequest(
                List.of("质量"), List.of("不存在的指标"), List.of("不存在的维度"), List.of(), 10));

        assertEquals(List.of("不存在的指标"), response.unknownMetricTags());
        assertEquals(List.of("不存在的维度"), response.unknownDimensionTags());
        assertTrue(response.candidates().isEmpty());
        assertEquals(0, response.matchedCount());
    }

    @Test
    void limitsResultsAfterCountingAllMatches() {
        ToolIndexResponse response = service.index(catalog(), new ToolIndexRequest(
                List.of("质量"), List.of("质量分"), List.of(), List.of(), 1));

        assertEquals(3, response.matchedCount());
        assertTrue(response.truncated());
        assertEquals(List.of("script_quality"), response.candidates().stream()
                .map(ToolIndexCandidate::toolId).toList());
    }

    @Test
    void discoversMetricsWithinTopicBeforeReturningTools() {
        ToolRoutingCatalog catalog = new ToolRoutingCatalog(
                List.of(
                        metadata("qi_q2", ToolRoutingToolType.SCRIPT, 100,
                                List.of("QI卡口"), List.of("Q2-1"), List.of("部门")),
                        metadata("delivery_q2", ToolRoutingToolType.SQL, 50,
                                List.of("交付治理"), List.of("Q2-1"), List.of("版本"))),
                Set.of("QI卡口", "交付治理"), Set.of("Q2-1"), Set.of("部门", "版本"));

        ToolIndexResponse topicResponse = service.index(catalog, new ToolIndexRequest(
                List.of("QI卡口"), List.of(), List.of(), List.of(), 10));
        ToolIndexResponse metricResponse = service.index(catalog, new ToolIndexRequest(
                List.of("QI卡口"), List.of("Q2-1"), List.of(), List.of(), 10));

        assertEquals(List.of("Q2-1"), topicResponse.availableMetricTags());
        assertTrue(topicResponse.candidates().isEmpty());
        assertEquals(List.of("qi_q2"), metricResponse.candidates().stream()
                .map(ToolIndexCandidate::toolId).toList());
        assertEquals(List.of("部门"), metricResponse.availableDimensionTags());
    }

    @Test
    void doesNotFallbackWhenTopicIsUnknown() {
        ToolIndexResponse response = service.index(catalog(), new ToolIndexRequest(
                List.of("不存在的主题"), List.of("质量分"), List.of(), List.of(), 10));

        assertEquals(List.of("不存在的主题"), response.unknownTopicTags());
        assertTrue(response.availableMetricTags().isEmpty());
        assertTrue(response.candidates().isEmpty());
    }

    private static ToolRoutingCatalog catalog() {
        return new ToolRoutingCatalog(
                List.of(
                        metadata("sql_quality", ToolRoutingToolType.SQL, 20, List.of("质量"),
                                List.of("质量分"), List.of("部门", "季度")),
                        metadata("script_quality", ToolRoutingToolType.SCRIPT, 100, List.of("质量"),
                                List.of("质量分", "达标率"), List.of("部门", "季度", "版本")),
                        metadata("api_quality", ToolRoutingToolType.API, 10, List.of("质量"),
                                List.of("质量分"), List.of("版本"))),
                Set.of("质量"), Set.of("质量分", "达标率"),
                Set.of("部门", "季度", "版本"));
    }

    private static ToolRoutingMetadata metadata(
            String toolId, ToolRoutingToolType type, int priority, List<String> topics,
            List<String> metrics, List<String> dimensions) {
        return new ToolRoutingMetadata(toolId, type, toolId + " description", topics, metrics, dimensions,
                priority, true, null);
    }
}
