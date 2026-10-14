package com.agentscopea2a.v2.skills;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Runtime routing metadata for one registered Skill.
 *
 * <p>匹配语义: 分级关键词加权打分 (level1Keywords → level2Keywords → level3Keywords →
 * keywords 叶层), 任一层命中即入围 (层级只加权不过滤), 或问题显式点名 skillName;
 * domainTags 做互斥域过滤与排序加成 (命中领域的 skill 优先于通用 skill); topicTags
 * 仅作内部管理归类, 不参与匹配。指标标签与 priority 已随评分体系简化移除。
 *
 * <p>{@code scriptId}/{@code paramMapping} 用于 Skill 直接执行 (绕过 LLM): scriptId
 * 指向 script_registry 脚本, NULL = 该 skill 不参与直接执行; paramMapping 是 JSON
 * 对象字符串, key = 脚本参数名 (原样透传, 可不规则), value = 维度键枚举 (见
 * DirectSkillExecutor), 例: {@code {"dept":"deptName","version":"versionPlan"}}。
 * 该库把 '' 视为 NULL, 两列均规范化 blank → null。
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
        List<String> level3Keywords,
        String scriptId,
        String paramMapping) {

    public SkillRoutingMetadata {
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        domainTags = domainTags == null ? List.of() : List.copyOf(domainTags);
        topicTags = topicTags == null ? List.of() : List.copyOf(topicTags);
        level1Keywords = level1Keywords == null ? List.of() : List.copyOf(level1Keywords);
        level2Keywords = level2Keywords == null ? List.of() : List.copyOf(level2Keywords);
        level3Keywords = level3Keywords == null ? List.of() : List.copyOf(level3Keywords);
        creator = creator == null ? "" : creator;
        scriptId = blankToNull(scriptId);
        paramMapping = blankToNull(paramMapping);
    }

    /** Legacy 8-arg shape (no level keywords, no direct-exec fields) for draft rows and existing call sites. */
    public SkillRoutingMetadata(String skillName, String shortSummary, List<String> keywords,
                                List<String> domainTags, List<String> topicTags, String creator,
                                boolean active, LocalDateTime updatedAt) {
        this(skillName, shortSummary, keywords, domainTags, topicTags, creator, active, updatedAt,
                List.of(), List.of(), List.of(), null, null);
    }

    /** Legacy 11-arg shape (no direct-exec fields) for existing call sites. */
    public SkillRoutingMetadata(String skillName, String shortSummary, List<String> keywords,
                                List<String> domainTags, List<String> topicTags, String creator,
                                boolean active, LocalDateTime updatedAt,
                                List<String> level1Keywords, List<String> level2Keywords,
                                List<String> level3Keywords) {
        this(skillName, shortSummary, keywords, domainTags, topicTags, creator, active, updatedAt,
                level1Keywords, level2Keywords, level3Keywords, null, null);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
