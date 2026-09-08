package com.agentscopea2a.v2.toolrouting;

import java.util.List;

/** Safe discovery result that intentionally omits templates, source code, and parameter schemas. */
public record ToolIndexCandidate(
        String toolId,
        ToolRoutingToolType toolType,
        String description,
        List<String> metricTags,
        List<String> dimensionTags,
        int priority,
        String executeWith) {
}
