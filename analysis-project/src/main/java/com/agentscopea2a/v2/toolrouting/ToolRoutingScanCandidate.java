package com.agentscopea2a.v2.toolrouting;

import java.util.List;

/** Read-only result of scanning an authoritative registration source. */
public record ToolRoutingScanCandidate(
        String toolId,
        ToolRoutingToolType toolType,
        String name,
        String description,
        String creator,
        boolean sourceAvailable,
        boolean configured,
        boolean routeEnabled,
        List<String> issueCodes) {

    public ToolRoutingScanCandidate(String toolId, ToolRoutingToolType toolType, String name,
                                    String description, boolean sourceAvailable, boolean configured,
                                    boolean routeEnabled, List<String> issueCodes) {
        this(toolId, toolType, name, description, "", sourceAvailable, configured, routeEnabled, issueCodes);
    }
}
