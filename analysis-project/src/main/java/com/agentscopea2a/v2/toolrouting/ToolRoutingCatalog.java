package com.agentscopea2a.v2.toolrouting;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Immutable executable catalog snapshot used by discovery and prompt rendering. */
public record ToolRoutingCatalog(
        List<ToolRoutingMetadata> tools,
        Set<String> topicTags,
        Set<String> metricTags,
        Set<String> dimensionTags) {

    public ToolRoutingCatalog {
        tools = tools == null ? List.of() : List.copyOf(tools);
        topicTags = immutableSet(topicTags);
        metricTags = immutableSet(metricTags);
        dimensionTags = immutableSet(dimensionTags);
    }

    private static Set<String> immutableSet(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                result.add(value.trim());
            }
        }
        return Set.copyOf(result);
    }
}
