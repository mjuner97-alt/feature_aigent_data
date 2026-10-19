package com.agentscopea2a.v2.skills;

/**
 * 全局关键词优先级展开形（检索消费，docs/202610/skill关键词优先级行模型存储方案.md）。
 *
 * <p>由 {@link SkillKeywordPriorityRepository#expand} 从平铺行模型展开：L2/L3 词 →
 * (level, keyword, sortOrder=行序)；L1 词不展开（无优先级，仅分组/入围身份）。
 * {@code parentKeyword} 为兼容保留字段，新链路不再填。{@code sortOrder} 小者优先。
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
        return !keyword.isEmpty() && level >= 1 && level <= 3;
    }
}
