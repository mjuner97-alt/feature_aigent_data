package com.agentscopea2a.v2.toolrouting;

/** One input field required by an executable atomic tool. */
public record ToolParameterMetadata(String name, String type, boolean required, String description) {
}
