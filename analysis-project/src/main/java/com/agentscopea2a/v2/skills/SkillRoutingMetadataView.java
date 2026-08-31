package com.agentscopea2a.v2.skills;

import java.time.LocalDateTime;
import java.util.List;

/** Skill index data combined with optional routing configuration for management UI. */
public record SkillRoutingMetadataView(
        String skillName,
        String description,
        String shortSummary,
        List<String> aliases,
        List<String> keywords,
        List<String> metricTags,
        List<String> domainTags,
        List<String> dataSourceTags,
        int priority,
        boolean active,
        LocalDateTime updatedAt,
        boolean configured) {
}
