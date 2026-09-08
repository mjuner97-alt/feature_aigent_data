package com.agentscopea2a.v2.toolrouting;

import java.util.List;

/** Result of one deterministic tool catalog lookup. */
public record ToolIndexResponse(
        ToolIndexFilters filters,
        int matchedCount,
        boolean truncated,
        List<String> unknownTopicTags,
        List<String> unknownMetricTags,
        List<String> unknownDimensionTags,
        List<String> availableMetricTags,
        List<String> availableDimensionTags,
        List<ToolIndexCandidate> candidates) {

    public record ToolIndexFilters(
            List<String> topicTags,
            List<String> metricTags,
            List<String> dimensionTags,
            List<String> toolTypes) {
    }
}
