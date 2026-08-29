package com.agentscopea2a.v2.capability;

import java.util.List;

/** Database-managed capability used for coarse-grained Skill recall. */
public record CapabilityMetadata(
        String name,
        String shortSummary,
        List<String> aliases,
        List<String> keywords,
        List<String> domainTags,
        int priority,
        boolean active) {
    public CapabilityMetadata {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        domainTags = domainTags == null ? List.of() : List.copyOf(domainTags);
    }
}
