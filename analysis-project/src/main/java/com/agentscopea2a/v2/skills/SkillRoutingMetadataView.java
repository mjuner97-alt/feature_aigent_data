package com.agentscopea2a.v2.skills;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

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
        List<String> level3Keywords,
        String scriptId,
        Map<String, String> paramMapping,
        List<SkillDimensionCollisionService.CollisionRow> dimensionCollisions) {

    /** Legacy 10-arg shape (no level keywords, no direct-exec fields) for existing call sites. */
    public SkillRoutingMetadataView(String skillName, String description, String shortSummary,
                                    List<String> keywords, List<String> domainTags, List<String> topicTags,
                                    String creator, boolean active, LocalDateTime updatedAt, boolean configured) {
        this(skillName, description, shortSummary, keywords, domainTags, topicTags, creator, active,
                updatedAt, configured, List.of(), List.of(), List.of(), null, Map.of(), List.of());
    }

    /** Legacy 13-arg shape (no direct-exec fields) for existing call sites. */
    public SkillRoutingMetadataView(String skillName, String description, String shortSummary,
                                    List<String> keywords, List<String> domainTags, List<String> topicTags,
                                    String creator, boolean active, LocalDateTime updatedAt, boolean configured,
                                    List<String> level1Keywords, List<String> level2Keywords,
                                    List<String> level3Keywords) {
        this(skillName, description, shortSummary, keywords, domainTags, topicTags, creator, active,
                updatedAt, configured, level1Keywords, level2Keywords, level3Keywords, null, Map.of(), List.of());
    }

    /** Legacy 15-arg shape (no collision audit) for existing call sites. */
    public SkillRoutingMetadataView(String skillName, String description, String shortSummary,
                                    List<String> keywords, List<String> domainTags, List<String> topicTags,
                                    String creator, boolean active, LocalDateTime updatedAt, boolean configured,
                                    List<String> level1Keywords, List<String> level2Keywords,
                                    List<String> level3Keywords, String scriptId, Map<String, String> paramMapping) {
        this(skillName, description, shortSummary, keywords, domainTags, topicTags, creator, active,
                updatedAt, configured, level1Keywords, level2Keywords, level3Keywords, scriptId, paramMapping,
                List.of());
    }

    /** PUT 保存响应附撞车审计（docs/202610/检索关键词与维度值冲突治理方案.md §5）。 */
    public SkillRoutingMetadataView withDimensionCollisions(
            List<SkillDimensionCollisionService.CollisionRow> collisions) {
        return new SkillRoutingMetadataView(skillName, description, shortSummary, keywords, domainTags,
                topicTags, creator, active, updatedAt, configured, level1Keywords, level2Keywords,
                level3Keywords, scriptId, paramMapping,
                collisions == null ? List.of() : List.copyOf(collisions));
    }
}
