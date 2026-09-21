package com.agentscopea2a.v2.skillManager.service;

import com.agentscopea2a.v2.skillManager.dto.SkillFlowDefinitionRequest.ReportOutline;
import com.agentscopea2a.v2.skillManager.dto.SkillFlowDefinitionRequest.ReportOutlineItem;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 报告大纲结构校验器(纯函数):创建/更新/启用流程时由 {@link FlowDefinitionService} 复用。
 * 规则:最多三级且不能跳级(层级标志必须与树位置一致)、id/title 非空且唯一、
 * 章节可绑定多个节点(nodeKeys,兼容旧 nodeKey 字段)且每个节点最多绑定一次、
 * 启用大纲时全部节点必须绑定到某个章节、条目总数受限。
 */
final class ReportOutlineValidator {

    /** 大纲条目总数上限:报告章节不可能超过这个规模,防异常配置。 */
    static final int MAX_ITEMS = 50;

    private static final Set<String> NUMBERING_STYLES = Set.of("chinese", "arabic", "none");

    private ReportOutlineValidator() {}

    /** @return 全部校验错误(中文);为空表示大纲未配置或结构合法。 */
    static List<String> validate(ReportOutline outline, Set<String> nodeKeys) {
        List<String> errors = new ArrayList<>();
        if (outline == null || outline.items().isEmpty()) return errors;
        if (outline.numbering() != null) {
            checkNumberingStyle("level1", outline.numbering().level1(), errors);
            checkNumberingStyle("level2", outline.numbering().level2(), errors);
            checkNumberingStyle("level3", outline.numbering().level3(), errors);
        }
        int[] count = {0};
        walk(outline.items(), 0, nodeKeys, new HashSet<>(), new HashSet<>(), new HashSet<>(), count, errors);
        if (count[0] > MAX_ITEMS) errors.add("报告大纲条目不能超过 " + MAX_ITEMS + " 个");
        for (String nodeKey : nodeKeys) {
            if (!outlineBinds(outline, nodeKey)) errors.add("节点 " + nodeKey + " 未绑定到报告大纲");
        }
        return errors;
    }

    /** depth 为父项层级(根为 0):子项层级必须恰好 depth+1,同时挡住超三级与跳级。 */
    private static void walk(List<ReportOutlineItem> items, int depth, Set<String> nodeKeys,
                             Set<String> ids, Set<String> titles, Set<String> boundKeys,
                             int[] count, List<String> errors) {
        for (ReportOutlineItem item : items) {
            count[0]++;
            int level = depth + 1;
            String id = item.id() == null ? "" : item.id().trim();
            String title = item.title() == null ? "" : item.title().trim();
            if (level > 3) {
                errors.add("报告大纲最多三级,条目「" + title + "」层级非法");
            } else {
                validateItem(item, id, title, level, nodeKeys, ids, titles, boundKeys, errors);
                if (!item.children().isEmpty()) {
                    walk(item.children(), level, nodeKeys, ids, titles, boundKeys, count, errors);
                }
            }
        }
    }

    private static void validateItem(ReportOutlineItem item, String id, String title, int level,
                                     Set<String> nodeKeys, Set<String> ids, Set<String> titles,
                                     Set<String> boundKeys, List<String> errors) {
        if (id.isEmpty()) errors.add("报告大纲条目 id 不能为空");
        else if (!ids.add(id)) errors.add("报告大纲条目 id 重复: " + id);
        if (title.isEmpty()) errors.add("报告大纲条目「" + id + "」标题不能为空");
        else if (!titles.add(title)) errors.add("报告大纲标题重复: " + title);
        if (item.level() != null && item.level() != level) {
            errors.add("报告大纲条目「" + title + "」层级标志与树位置不一致(不能跳级)");
        }
        // DTO 紧凑构造器已把旧 nodeKey 合并进 nodeKeys,这里只需按 nodeKeys 校验
        for (String key : item.nodeKeys()) {
            if (!nodeKeys.contains(key)) {
                errors.add("报告大纲绑定了不存在的节点: " + key);
            } else if (!boundKeys.add(key)) {
                errors.add("节点 " + key + " 被绑定多次");
            }
        }
    }

    private static boolean outlineBinds(ReportOutline outline, String nodeKey) {
        for (ReportOutlineItem item : outline.items()) {
            if (binds(item, nodeKey)) return true;
        }
        return false;
    }

    private static boolean binds(ReportOutlineItem item, String nodeKey) {
        if (nodeKey != null && item.nodeKeys().contains(nodeKey)) return true;
        for (ReportOutlineItem child : item.children()) {
            if (binds(child, nodeKey)) return true;
        }
        return false;
    }

    private static void checkNumberingStyle(String level, String style, List<String> errors) {
        if (style != null && !NUMBERING_STYLES.contains(style)) {
            errors.add("报告大纲编号样式非法: " + level + "=" + style);
        }
    }
}
