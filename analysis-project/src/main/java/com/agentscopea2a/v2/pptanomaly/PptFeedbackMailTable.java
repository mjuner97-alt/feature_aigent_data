package com.agentscopea2a.v2.pptanomaly;

import com.agentscopea2a.mapper.gauss.PptAnomalyMapper;
import org.springframework.web.util.HtmlUtils;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 首次通知及循环提醒共用的部门经办人分析进度表。 */
final class PptFeedbackMailTable {
    private PptFeedbackMailTable() {}

    static String render(List<PptAnomalyMapper.FindingRow> findings,
                         List<PptAnomalyMapper.ResultHandler> handlers, Map<String, String> names) {
        var items = new LinkedHashMap<Long, PptAnomalyMapper.FindingRow>();
        findings.forEach(item -> items.putIfAbsent(item.id(), item));
        var rows = new TreeMap<String, Map<Long, PptAnomalyMapper.FindingRow>>();
        var assigned = new java.util.HashSet<Long>();
        for (var handler : handlers) {
            var item = items.get(handler.findingId());
            if (item == null || handler.userId() == null || handler.userId().isBlank()) continue;
            rows.computeIfAbsent(handler.userId(), key -> new LinkedHashMap<>()).putIfAbsent(item.id(), item);
            assigned.add(item.id());
        }
        var unassigned = items.values().stream().filter(item -> !assigned.contains(item.id())).toList();
        var html = new StringBuilder("<table cellpadding=\"10\" cellspacing=\"0\" border=\"1\" style=\"border-collapse:collapse;width:100%;max-width:800px;border:1px solid #cbd5e1;font-family:Arial,Microsoft YaHei,sans-serif;font-size:14px;text-align:center\">")
                .append("<thead><tr style=\"background:#edf2f7\"><th scope=\"col\">人员</th><th scope=\"col\">共涉及分析数</th><th scope=\"col\">已分析反馈数</th><th scope=\"col\">待分析数</th></tr></thead><tbody>");
        rows.forEach((user, tasks) -> html.append(row(names.containsKey(user) ? names.get(user) + "（" + user + "）" : user,
                List.copyOf(tasks.values()), false)));
        if (!unassigned.isEmpty()) html.append(row("待分派", unassigned, false));
        html.append("</tbody><tfoot>").append(row("合计", List.copyOf(items.values()), true)).append("</tfoot></table>");
        return html.append("<p style=\"font-size:12px;color:#64748b\">按当前事项反馈状态统计。共同经办的事项分别计入各经办人，合计按事项去重；待分派事项单独列示。</p>").toString();
    }

    private static String row(String name, List<PptAnomalyMapper.FindingRow> items, boolean total) {
        long done = items.stream().filter(item -> "CONFIRMED".equals(item.status()) || "ADJUSTED".equals(item.status())).count();
        return "<tr" + (total ? " style=\"font-weight:bold;background:#f8fafc\"" : "") + "><td>"
                + HtmlUtils.htmlEscape(name) + "</td><td>" + items.size() + "</td><td>" + done
                + "</td><td>" + (items.size() - done) + "</td></tr>";
    }
}
