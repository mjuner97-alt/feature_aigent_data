package com.agentscopea2a.v2.skills;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Runtime routing metadata for one registered Skill.
 *
 * <p>匹配语义: 仅 keywords 命中用户问题 (或问题显式点名 skillName) 才返回该 Skill;
 * domainTags 做互斥域过滤与排序加成 (命中领域的 skill 优先于通用 skill);
 * topicTags 仅作内部管理归类, 不参与匹配。指标标签与 priority 已随评分体系简化移除。
 */
public record SkillRoutingMetadata(
        String skillName,
        String shortSummary,
        List<String> keywords,
        List<String> domainTags,
        List<String> topicTags,
        String creator,
        boolean active,
        LocalDateTime updatedAt) {

    public SkillRoutingMetadata {
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        domainTags = domainTags == null ? List.of() : List.copyOf(domainTags);
        topicTags = topicTags == null ? List.of() : List.copyOf(topicTags);
        creator = creator == null ? "" : creator;
    }
}
