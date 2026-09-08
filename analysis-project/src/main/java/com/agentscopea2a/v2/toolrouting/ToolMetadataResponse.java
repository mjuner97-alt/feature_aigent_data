package com.agentscopea2a.v2.toolrouting;

import java.util.List;

/** Unified safe metadata response for one routed SQL, API, or script. */
public record ToolMetadataResponse(
        String toolId,
        ToolRoutingToolType toolType,
        String description,
        List<String> topicTags,
        List<String> metricTags,
        List<String> dimensionTags,
        int priority,
        String executeWith,
        List<ToolParameterMetadata> parameters,
        ToolInvocation invocation) {
}
