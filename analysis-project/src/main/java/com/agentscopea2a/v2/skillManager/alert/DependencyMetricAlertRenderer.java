package com.agentscopea2a.v2.skillManager.alert;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 依赖指标预警内容渲染工具。
 *
 * <p>负责解析收件人配置并生成 HTML 预警正文。对应源码路径：
 * {@code src/main/java/com/agentscopea2a/v2/skillManager/alert/DependencyMetricAlertRenderer.java}。</p>
 */
public final class DependencyMetricAlertRenderer {
    /** 展示检查时间和指标就绪时间的格式。 */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 展示预警标题日期的格式。 */
    private static final DateTimeFormatter TITLE_DATE = DateTimeFormatter.ofPattern("yyyy年MM月dd日");

    /** 工具类不允许实例化。 */
    private DependencyMetricAlertRenderer() {}

    /**
     * 将逗号分隔的收件人配置清洗为去重后的用户 ID 列表。
     * @param raw 原始收件人配置
     * @return 去除空白、空值并保持原顺序的不可变列表
     */
    public static List<String> parseRecipients(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String value : raw.split(",")) {
            String v = value.trim();
            if (!v.isEmpty()) out.add(v);
        }
        return List.copyOf(out);
    }

    /**
     * 根据依赖指标状态生成 HTML 预警正文。
     * @param rows 指标状态行
     * @param date 业务日期
     * @param checkedAt 检查时间
     * @return 可直接交给通知渠道发送的 HTML 内容
     */
    public static String renderHtml(List<DependencyMetricAlertRow> rows, LocalDate date, LocalDateTime checkedAt) {
        int ready = (int) rows.stream().filter(DependencyMetricAlertRow::ready).count();
        StringBuilder html = new StringBuilder("<html><body><h3>依赖指标就绪预警（")
                .append(escape(date.format(TITLE_DATE))).append("）</h3>");
        html.append("<p>检查时间：").append(escape(checkedAt.format(TIME))).append("</p>");
        html.append("<p>共检查 ").append(rows.size()).append(" 项，已就绪 ").append(ready)
                .append(" 项，未就绪 ").append(rows.size() - ready).append(" 项。</p>");
        // 未就绪指标排在前面，便于收件人优先关注异常项；就绪指标附后。
        List<DependencyMetricAlertRow> ordered = new ArrayList<>(rows);
        ordered.sort(Comparator.comparingInt(r -> r.ready() ? 1 : 0));
        html.append("<table border=\"1\" cellspacing=\"0\" cellpadding=\"6\"><thead><tr><th>指标名称</th><th>状态</th><th>就绪时间</th></tr></thead><tbody>");
        for (DependencyMetricAlertRow row : ordered) {
            // 未就绪指标使用醒目颜色，便于收件人快速定位异常项。
            if (!row.ready()) html.append("<tr style=\"color:red;background:#fff1f0\">"); else html.append("<tr>");
            html.append("<td>").append(escape(row.metricName()))
                    .append("</td><td>").append(row.ready() ? "已就绪" : "未就绪").append("</td><td>")
                    .append(row.readyAt() == null ? "—" : escape(row.readyAt().format(TIME))).append("</td></tr>");
        }
        return html.append("</tbody></table></body></html>").toString();
    }

    /** 对用户可控文本进行 HTML 转义，避免正文结构被破坏。 */
    private static String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
