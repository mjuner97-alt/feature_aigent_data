package com.agentscopea2a.v2.toolrouting;

import java.util.List;

/** Editable routing fields. Execution definitions remain in their authoritative registries. */
public record ToolRoutingMetadataInput(
        ToolRoutingToolType toolType,
        String description,
        List<String> topicTags,
        List<String> metricTags,
        List<String> dimensionTags,
        int priority,
        boolean enabled) {
}
