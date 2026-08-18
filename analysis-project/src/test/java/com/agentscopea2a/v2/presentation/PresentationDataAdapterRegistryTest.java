package com.agentscopea2a.v2.presentation;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PresentationDataAdapterRegistryTest {
    private final PresentationDataAdapterRegistry registry = new PresentationDataAdapterRegistry();

    @Test
    void jsonEnvelopePassesArbitraryVariablesAndSummaryWithoutBusinessKnowledge() {
        PresentationDataAdapterRegistry.AdaptedData adapted = registry.adapt(
                "json-envelope-v1",
                List.of(Map.of(
                        "variables_json", "{\"title\":\"任意报告\",\"labels\":[\"A\"],\"values\":[12.5],\"records\":[{\"name\":\"项目A\"}]}",
                        "summary_json", "{\"customMetric\":12.5,\"status\":\"ok\"}")),
                Map.of(), "不会使用的标题");

        JsonNode variables = adapted.variables();
        assertEquals("任意报告", variables.path("title").asText());
        assertEquals(12.5, variables.path("values").get(0).asDouble());
        assertEquals("项目A", variables.path("records").get(0).path("name").asText());
        assertEquals(12.5, adapted.summary().path("customMetric").asDouble());
        assertEquals("ok", adapted.summary().path("status").asText());
    }

    @Test
    void rejectsUnknownAdapter() {
        assertThrows(IllegalArgumentException.class,
                () -> registry.adapt("missing", List.of(), Map.of(), "报告"));
    }

    @Test
    void rejectsMalformedOrMultirowEnvelope() {
        assertThrows(IllegalArgumentException.class,
                () -> registry.adapt("json-envelope-v1", List.of(), Map.of(), "报告"));
        assertThrows(IllegalArgumentException.class,
                () -> registry.adapt("json-envelope-v1",
                        List.of(Map.of("variables_json", "[]", "summary_json", "{}")),
                        Map.of(), "报告"));
    }
}
