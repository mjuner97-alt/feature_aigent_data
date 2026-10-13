package com.agentscopea2a.v2.skills;

import java.time.LocalDateTime;
import java.util.List;

/** Skill index data combined with optional routing configuration for management UI. */
public record SkillRoutingMetadataView(
        String skillName,
        String description,
        String shortSummary,
        List<String> keywords,
        List<String> domainTags,
        List<String> topicTags,
        String creator,
        boolean active,
        LocalDateTime updatedAt,
        boolean configured,
        List<String> level1Keywords,
        List<String> level2Keywords,
        List<String> level3Keywords) {

    /** Legacy 10-arg shape (no level keywords) for existing call sites. */
    public SkillRoutingMetadataView(String skillName, String description, String shortSummary,
                                    List<String> keywords, List<String> domainTags, List<String> topicTags,
                                    String creator, boolean active, LocalDateTime updatedAt, boolean configured) {
        this(skillName, description, shortSummary, keywords, domainTags, topicTags, creator, active,
                updatedAt, configured, List.of(), List.of(), List.of());
    }
}
