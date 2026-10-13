package com.agentscopea2a.v2.skills;

import java.util.List;

/** Editable routing fields; Skill content is intentionally excluded. */
public record SkillRoutingMetadataInput(
        String shortSummary,
        List<String> keywords,
        List<String> domainTags,
        List<String> topicTags,
        boolean active,
        List<String> level1Keywords,
        List<String> level2Keywords,
        List<String> level3Keywords) {

    /** Legacy 5-arg shape (no level keywords) for existing call sites. */
    public SkillRoutingMetadataInput(String shortSummary, List<String> keywords,
                                     List<String> domainTags, List<String> topicTags, boolean active) {
        this(shortSummary, keywords, domainTags, topicTags, active, List.of(), List.of(), List.of());
    }
}
