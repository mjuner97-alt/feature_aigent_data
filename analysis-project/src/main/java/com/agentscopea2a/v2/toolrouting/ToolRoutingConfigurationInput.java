package com.agentscopea2a.v2.toolrouting;

import java.util.List;

/** One management submission for routing metadata and its Skill bindings. */
public record ToolRoutingConfigurationInput(ToolRoutingMetadataInput metadata, List<String> skillNames) {
}
