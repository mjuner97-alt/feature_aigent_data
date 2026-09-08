package com.agentscopea2a.v2.skills;

import java.util.List;

/** Editable routing fields; Skill content is intentionally excluded. */
public record SkillRoutingMetadataInput(
        String shortSummary,
        List<String> keywords,
        List<String> domainTags,
        List<String> topicTags,
        List<String> metricTags,
        int priority,
        boolean active) {
}
