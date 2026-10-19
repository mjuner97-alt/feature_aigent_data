package com.agentscopea2a.v2.skills;

/**
 * 关键词优先级一行配置（表 {@code skill_keyword_line}，
 * docs/202610/skill关键词优先级行模型存储方案.md v3 平铺表）。
 *
 * <p>一行 = 页面一行：L1 词集 | L2 词集 | L3 词集（顿号分隔，格内多词彼此
 * 等价、无次序）。L1 无优先级——仅作分组身份与漏斗入围门槛，不参与排序；
 * 行序（{@code rowOrder}，由服务端按提交位次生成）即该行所有 L2/L3 词的
 * 优先级，小者优先。L1 与 L2/L3 至少各非空一格。
 */
public record SkillKeywordPriorityLine(
        Long id,
        String level1Keywords,
        String level2Keywords,
        String level3Keywords,
        Integer rowOrder) {

    public SkillKeywordPriorityLine {
        level1Keywords = level1Keywords == null ? "" : level1Keywords.trim();
        level2Keywords = level2Keywords == null ? "" : level2Keywords.trim();
        level3Keywords = level3Keywords == null ? "" : level3Keywords.trim();
    }
}
