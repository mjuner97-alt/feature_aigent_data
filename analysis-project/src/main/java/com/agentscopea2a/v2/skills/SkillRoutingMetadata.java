package com.agentscopea2a.v2.skills;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Runtime routing metadata for one registered Skill.
 *
 * <p>匹配语义: 分级关键词层层下钻 (level1Keywords → level2Keywords → level3Keywords →
 * keywords 叶层, 逐层收窄+空层回退), 或问题显式点名 skillName; domainTags 做互斥域过滤
 * 与排序加成 (命中领域的 skill 优先于通用 skill); topicTags 仅作内部管理归类, 不参与匹配。
 * 指标标签与 priority 已随评分体系简化移除。
 */
public record SkillRoutingMetadata(
        String skillName,
        String shortSummary,
        List<String> keywords,
        List<String> domainTags,
        List<String> topicTags,
        String creator,
        boolean active,
        LocalDateTime updatedAt,
        List<String> level1Keywords,
        List<String> level2Keywords,
        List<String> level3Keywords) {

    public SkillRoutingMetadata {
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        domainTags = domainTags == null ? List.of() : List.copyOf(domainTags);
        topicTags = topicTags == null ? List.of() : List.copyOf(topicTags);
        level1Keywords = level1Keywords == null ? List.of() : List.copyOf(level1Keywords);
        level2Keywords = level2Keywords == null ? List.of() : List.copyOf(level2Keywords);
        level3Keywords = level3Keywords == null ? List.of() : List.copyOf(level3Keywords);
        creator = creator == null ? "" : creator;
    }

    /** Legacy 8-arg shape (no level keywords) for draft rows and existing call sites. */
    public SkillRoutingMetadata(String skillName, String shortSummary, List<String> keywords,
                                List<String> domainTags, List<String> topicTags, String creator,
                                boolean active, LocalDateTime updatedAt) {
        this(skillName, shortSummary, keywords, domainTags, topicTags, creator, active, updatedAt,
                List.of(), List.of(), List.of());
    }
}
