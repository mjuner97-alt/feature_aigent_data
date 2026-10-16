package com.agentscopea2a.v2.skills;

/**
 * 检索旁路词一行配置（表 {@code skill_bypass_keyword}，docs/history/skill-retrieval-logic.md §8.2）。
 *
 * <p>命中任一启用旁路词的问题 → skill 检索整体置空，LLM 直接走工具层（拍板①）；
 * 问题显式点名任一 active skill 名时豁免（拍板④）。{@code groupName} 仅归类备注
 * （分析类 / 表格类 / 质量板块），不参与匹配。
 */
public record SkillBypassKeyword(
        Long id,
        String groupName,
        String keyword,
        boolean enabled,
        String remark) {

    public SkillBypassKeyword {
        groupName = groupName == null ? "" : groupName.trim();
        keyword = keyword == null ? "" : keyword.trim();
        remark = remark == null ? "" : remark.trim();
    }

    public boolean valid() {
        return !keyword.isEmpty();
    }
}
