package com.agentscopea2a.v2.skills;

/**
 * 全局关键词优先级一行配置（表 {@code skill_keyword_priority}，docs/history/skill-retrieval-logic.md §8.1 拍板⑧）。
 *
 * <p>优先级配在「词」上、全局生效（skill 不单独配分数）：{@code level} ∈ {1,2,3}；
 * level≥2 时 {@code parentKeyword} = 所属上一层词（「哪个二级属于哪个一级」的分组关系
 * 由优先级页维护，skill 词表只管成员资格）；{@code sortOrder} 小者优先，由页面拖曳
 * 顺序生成、服务端按列表位次写。匹配/入围逻辑不变（仍看各 skill 自己的词表），
 * 本表只参与排序。
 */
public record SkillKeywordPriority(
        Long id,
        int level,
        String parentKeyword,
        String keyword,
        Integer sortOrder) {

    public SkillKeywordPriority {
        parentKeyword = parentKeyword == null ? "" : parentKeyword.trim();
        keyword = keyword == null ? "" : keyword.trim();
    }

    public boolean valid() {
        return !keyword.isEmpty() && level >= 1 && level <= 3
                && (level == 1 || !parentKeyword.isEmpty());
    }
}
