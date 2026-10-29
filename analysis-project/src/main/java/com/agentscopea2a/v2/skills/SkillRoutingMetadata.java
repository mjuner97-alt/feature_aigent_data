package com.agentscopea2a.v2.skills;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Runtime routing metadata for one registered Skill.
 *
 * <p>匹配语义: 分级关键词加权打分 (level1Keywords → level2Keywords → level3Keywords →
 * keywords 叶层), 按自身配置层级 AND 判定, 或问题显式点名 skillName; domainTags 是
 * 领域归属声明 (2026/10/28 domain 点名漏斗): 问题命中领域词时目录只保留配了命中领域
 * 标签的 skill (通用 skill 一并出局), 命中领域的 skill 免叶层 keyword 入围并排前;
 * topicTags 仅作内部管理归类, 不参与匹配。指标标签与 priority 已随评分体系简化移除。
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
