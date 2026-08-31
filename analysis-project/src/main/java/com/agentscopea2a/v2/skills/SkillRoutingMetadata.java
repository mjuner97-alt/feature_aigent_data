package com.agentscopea2a.v2.skills;

import java.time.LocalDateTime;
import java.util.List;

/** Runtime routing metadata for one registered Skill. */
public record SkillRoutingMetadata(
        String skillName,
        String shortSummary,
        List<String> aliases,
        List<String> keywords,
        List<String> metricTags,
        List<String> domainTags,
        List<String> dataSourceTags,
        int priority,
        boolean active,
        LocalDateTime updatedAt) {

    public SkillRoutingMetadata {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        metricTags = metricTags == null ? List.of() : List.copyOf(metricTags);
        domainTags = domainTags == null ? List.of() : List.copyOf(domainTags);
        dataSourceTags = dataSourceTags == null ? List.of() : List.copyOf(dataSourceTags);
    }
}
