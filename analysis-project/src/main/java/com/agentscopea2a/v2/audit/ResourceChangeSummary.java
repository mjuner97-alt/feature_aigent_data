package com.agentscopea2a.v2.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Human-readable, bounded summaries; full before/after values remain in the audit detail. */
final class ResourceChangeSummary {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> LABELS = Map.ofEntries(
        Map.entry("name", "名称"), Map.entry("description", "描述"), Map.entry("taskQuestion", "任务问题"),
        Map.entry("summaryQuestionTemplate", "汇总问题"), Map.entry("enabled", "启用状态"),
        Map.entry("autoTriggerEnabled", "自动触发"), Map.entry("scheduleRules", "执行星期"),
        Map.entry("scriptId", "Python脚本"), Map.entry("scriptParamsJson", "脚本参数"),
        Map.entry("questionTemplate", "节点问题"), Map.entry("nodeName", "节点名称"),
        Map.entry("reportOutline", "报告大纲"), Map.entry("teamReport", "部门/团队配置"),
        Map.entry("targets", "报告对象"), Map.entry("reportType", "报告类型"),
        Map.entry("departmentName", "部门名称"), Map.entry("teamName", "团队名称"),
        Map.entry("selected", "自动执行对象"), Map.entry("triggers", "触发关键词"),
        Map.entry("executors", "可执行人"), Map.entry("notifyEnabled", "通知开关"),
        Map.entry("notifyReceivers", "收件人"), Map.entry("notifySettings", "通知设置"),
        Map.entry("notifyContactName", "联系人"), Map.entry("notifyContactPhone", "联系电话"),
        Map.entry("notifyBodyTemplate", "通知正文"), Map.entry("typeId", "任务类型"),
        Map.entry("title", "标题"), Map.entry("sortOrder", "顺序"), Map.entry("maxParallelism", "并发数")
    );

    static String summarize(List<Map<String, Object>> details, String fallback) {
        List<String> lines = new ArrayList<>();
        for (var detail : details) {
            String before = string(detail.get("before_value")), after = string(detail.get("after_value"));
            if (before.equals(after)) continue;
            String field = string(detail.get("field_name"));
            String label = string(detail.get("field_label"));
            if (label.isBlank()) label = field;
            if ("definition".equals(field)) {
                try { diff("", parse(before), parse(after), lines, 0); }
                catch (Exception ignored) { lines.add("长任务完整配置已修改（请查看完整快照）"); }
            } else if ("script_content".equals(field)) {
                lines.add(sourceChange(before, after));
            } else {
                String target = string(detail.get("target_name"));
                lines.add((target.isBlank() ? "" : target + " / ") + label + "：" + value(before) + " → " + value(after));
            }
        }
        if (lines.isEmpty()) return fallback == null ? "" : fallback;
        return limit(String.join("\n", new LinkedHashSet<>(lines)), 12000);
    }

    private static JsonNode parse(String value) throws Exception {
        return value.isBlank() || "（初始）".equals(value) ? JSON.nullNode() : JSON.readTree(value);
    }

    private static void diff(String path, JsonNode before, JsonNode after, List<String> lines, int depth) {
        if (Objects.equals(before, after)) return;
        if (lines.size() >= 60) {
            if (lines.size() == 60) lines.add("其余修改请查看完整配置快照");
            return;
        }
        if (depth < 12 && before != null && after != null && before.isObject() && after.isObject()) {
            Set<String> keys = new LinkedHashSet<>();
            before.fieldNames().forEachRemaining(keys::add); after.fieldNames().forEachRemaining(keys::add);
            for (String key : keys) {
                if ("nodes".equals(key) && before.path(key).isArray() && after.path(key).isArray()) {
                    var oldNodes = nodes(before.path(key)); var newNodes = nodes(after.path(key));
                    Set<String> nodeKeys = new LinkedHashSet<>(oldNodes.keySet()); nodeKeys.addAll(newNodes.keySet());
                    for (String nodeKey : nodeKeys) {
                        var oldNode = oldNodes.get(nodeKey); var newNode = newNodes.get(nodeKey);
                        var node = newNode == null ? oldNode : newNode;
                        String name = node.path("nodeName").asText(nodeKey);
                        diff("执行节点 / " + (name.isBlank() ? nodeKey : name), oldNode, newNode, lines, depth + 1);
                    }
                } else {
                    String childPath = path.isBlank() ? LABELS.getOrDefault(key, key) : path + " / " + LABELS.getOrDefault(key, key);
                    if ("scriptParamsJson".equals(key)) {
                        try { diff(childPath, parse(before.path(key).asText("")), parse(after.path(key).asText("")), lines, depth + 1); }
                        catch (Exception ignored) { diff(childPath, before.get(key), after.get(key), lines, 12); }
                    } else diff(childPath, before.get(key), after.get(key), lines, depth + 1);
                }
            }
        } else lines.add((path.isBlank() ? "完整配置" : path) + "：" + value(before) + " → " + value(after));
    }

    private static Map<String, JsonNode> nodes(JsonNode values) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        for (var node : values) result.put(node.path("nodeKey").asText(String.valueOf(result.size())), node);
        return result;
    }

    private static String sourceChange(String before, String after) {
        String[] oldLines = before.split("\\R", -1), newLines = after.split("\\R", -1);
        int prefix = 0;
        while (prefix < oldLines.length && prefix < newLines.length && oldLines[prefix].equals(newLines[prefix])) prefix++;
        int oldEnd = oldLines.length, newEnd = newLines.length;
        while (oldEnd > prefix && newEnd > prefix && oldLines[oldEnd - 1].equals(newLines[newEnd - 1])) { oldEnd--; newEnd--; }
        return "脚本内容（第 " + (prefix + 1) + " 行起）：\n修改前："
            + value(String.join("\n", Arrays.copyOfRange(oldLines, prefix, oldEnd)))
            + "\n修改后：" + value(String.join("\n", Arrays.copyOfRange(newLines, prefix, newEnd)))
            + "\n（变更片段，完整源码见详情）";
    }

    private static String value(Object value) {
        if (value == null || (value instanceof JsonNode node && node.isNull())) return "空";
        String text = value instanceof JsonNode node && node.isTextual() ? node.asText() : value.toString();
        if (text.isBlank()) return "空";
        if ("true".equals(text)) return "是";
        if ("false".equals(text)) return "否";
        return limit(text, 600);
    }

    static String csvCell(Object value) {
        String text = string(value);
        // Neutralize spreadsheet formulas, including formulas hidden behind whitespace.
        String trimmed = text.stripLeading();
        if (!trimmed.isEmpty() && "=+-@".indexOf(trimmed.charAt(0)) >= 0) text = "'" + text;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private static String string(Object value) { return Objects.toString(value, ""); }
    private static String limit(String value, int max) { return value.length() <= max ? value : value.substring(0, max) + "…（内容已截断，完整内容见详情）"; }
}
