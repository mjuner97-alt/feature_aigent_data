package com.agentscopea2a.v2.toolrouting;

import java.util.List;

/** Read-only reflection metadata for one API tool registered in ToolRoutersIndex. */
public record ApiToolMetadata(String toolId, String description, List<ToolParameterMetadata> parameters) {
}
