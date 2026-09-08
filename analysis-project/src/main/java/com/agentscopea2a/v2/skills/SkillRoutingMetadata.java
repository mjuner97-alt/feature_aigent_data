package com.agentscopea2a.v2.skills;

import java.time.LocalDateTime;
import java.util.List;

/** Runtime routing metadata for one registered Skill. */
public record SkillRoutingMetadata(
        String skillName,
        String shortSummary,
        List<String> keywords,
        List<String> domainTags,
        List<String> topicTags,
        List<String> metricTags,
        String creator,
        int priority,
        boolean active,
        LocalDateTime updatedAt) {

    public SkillRoutingMetadata {
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        domainTags = domainTags == null ? List.of() : List.copyOf(domainTags);
        topicTags = topicTags == null ? List.of() : List.copyOf(topicTags);
        metricTags = metricTags == null ? List.of() : List.copyOf(metricTags);
        creator = creator == null ? "" : creator;
    }
}
