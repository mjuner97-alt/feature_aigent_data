package com.agentscopea2a.v2.skillManager.alert;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class DependencyMetricAlertRenderer {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private DependencyMetricAlertRenderer() {}
    public static List<String> parseRecipients(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String value : raw.split(",")) { String v = value.trim(); if (!v.isEmpty()) out.add(v); }
        return List.copyOf(out);
    }
    public static String renderHtml(List<DependencyMetricAlertRow> rows, LocalDate date, LocalDateTime checkedAt) {
        int ready = (int) rows.stream().filter(DependencyMetricAlertRow::ready).count();
        StringBuilder html = new StringBuilder("<html><body><h3>依赖指标就绪预警</h3>");
        html.append("<p>检查日期：").append(escape(date.toString())).append("　检查时间：").append(escape(checkedAt.format(TIME))).append("</p>");
        html.append("<p>共检查 ").append(rows.size()).append(" 项，已就绪 ").append(ready).append(" 项，未就绪 ").append(rows.size()-ready).append(" 项。</p>");
        html.append("<table border=\"1\" cellspacing=\"0\" cellpadding=\"6\"><thead><tr><th>指标名称</th><th>指标编码</th><th>状态</th><th>就绪时间</th></tr></thead><tbody>");
        for (DependencyMetricAlertRow row : rows) {
            if (!row.ready()) html.append("<tr style=\"color:red;background:#fff1f0\">"); else html.append("<tr>");
            html.append("<td>").append(escape(row.metricName())).append("</td><td>").append(escape(row.metricCode())).append("</td><td>").append(row.ready()?"已就绪":"未就绪").append("</td><td>").append(row.readyAt()==null?"—":escape(row.readyAt().format(TIME))).append("</td></tr>");
        }
        return html.append("</tbody></table></body></html>").toString();
    }
    private static String escape(String value) { return value == null ? "" : value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;"); }
}
