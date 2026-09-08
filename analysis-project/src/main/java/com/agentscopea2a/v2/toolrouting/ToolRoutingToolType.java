package com.agentscopea2a.v2.toolrouting;

/** Types of atomic capabilities discoverable through the unified tool catalog. */
public enum ToolRoutingToolType {
    SQL("sql_registry_exec"),
    API("router_tool"),
    SCRIPT("script_exec");

    private final String executeWith;

    ToolRoutingToolType(String executeWith) {
        this.executeWith = executeWith;
    }

    public String executeWith() {
        return executeWith;
    }
}
