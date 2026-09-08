package com.agentscopea2a.v2.toolrouting;

import java.util.List;

/** Structured filters supplied by the LLM to the deterministic catalog index. */
public record ToolIndexRequest(
        List<String> topicTags,
        List<String> metricTags,
        List<String> dimensionTags,
        List<String> toolTypes,
        Integer limit) {

    public ToolIndexRequest {
        topicTags = topicTags == null ? List.of() : List.copyOf(topicTags);
        metricTags = metricTags == null ? List.of() : List.copyOf(metricTags);
        dimensionTags = dimensionTags == null ? List.of() : List.copyOf(dimensionTags);
        toolTypes = toolTypes == null ? List.of() : List.copyOf(toolTypes);
    }
}
