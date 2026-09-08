package com.agentscopea2a.v2.toolrouting;

/** Deployment status shown read-only in the administration UI. */
public record ToolRoutingStatusResponse(
        boolean globallyEnabled,
        int configuredTools,
        int enabledRoutes) {
}
