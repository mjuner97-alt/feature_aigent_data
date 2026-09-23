package com.agentscopea2a.v2.skillManager.report;

import com.agentscopea2a.v2.skillManager.entity.FlowNodeExecutionStatus;
import com.agentscopea2a.v2.skillManager.entity.SkillFlowNodeExecution;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 报告大纲 composer:消费执行记录上的大纲快照(report_outline_snapshot)与节点结果,
 * 按大纲树输出 Markdown 报告正文(大纲 1/2/3 级渲染为 ##/###/####)。
 *
 * <p>关键规则(见 docs/superpowers/specs/2026-09-20-long-task-report-outline-design.md):</p>
 * <ul>
 *   <li>标题编号按树路径计算,各层级样式 chinese/arabic/none 可分别设置,缺省一级中文、二三级阿拉伯;</li>
 *   <li>报告主标题(大纲 title)渲染为居中 h1,尾部拼接生成日期(应用时钟当日,如 "报告 (2026年9月23日)");</li>
 *   <li>章节按 nodeKeys 顺序渲染绑定节点的结果(同一章节可挂多个节点,兼容旧单 nodeKey);</li>
 *   <li>结果开头第一个标题(Markdown/HTML)与叶子标题相同(剥编号、去空白、忽略大小写)时移除该标题;</li>
 *   <li>结果内部标题整体降为正文小节层级(最小层级映射到 h5,相对层级保留,封顶 h6),不覆盖大纲层级;</li>
 *   <li>失败/缺失节点渲染统一占位正文,不中断其他章节;</li>
 *   <li>快照为空或解析失败返回 null,调用方回退旧版按节点顺序拼接的兼容逻辑。</li>
 * </ul>
 */
@Component
public class ReportOutlineComposer {

    /** 是否在最终报告正文中显示执行节点名称；按当前产品要求关闭。 */
    private static final boolean SHOW_NODE_NAMES_IN_REPORT = false;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Markdown 行首标题(#~######)。 */
    private static final Pattern MD_HEADING_LINE = Pattern.compile("^\\s*(#{1,6})\\s+(.+?)\\s*$");
    /** 行首 HTML 标题(h1~h6,单行)。 */
    private static final Pattern HTML_HEADING_LINE =
            Pattern.compile("^\\s*<h([1-6])\\b[^>]*>([\\s\\S]*?)</h\\1>\\s*$", Pattern.CASE_INSENSITIVE);
    /** 标题前的编号前缀(中文数字/阿拉伯数字 + 、.．:等分隔),比较标题时剥离。 */
    private static final Pattern NUMBERING_PREFIX =
            Pattern.compile("^(?:[0-9一二三四五六七八九十百]+[、.．:：]\\s*)+");

    /** 大纲快照 JSON 结构(与 SkillFlowDefinitionRequest.ReportOutline 同构,独立定义避免 DTO 耦合)。 */
    record Outline(String title, Numbering numbering, List<Item> items) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Numbering(String level1, String level2, String level3) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Item(String id, String title, Integer level, String nodeKey, List<String> nodeKeys, List<Item> children) {
        Item {
            nodeKeys = nodeKeys == null || nodeKeys.isEmpty()
                    ? (nodeKey == null || nodeKey.isBlank() ? List.of() : List.of(nodeKey.trim()))
                    : nodeKeys.stream().filter(key -> key != null && !key.isBlank()).map(String::trim).toList();
        }
    }

    /** 报告标题尾部拼接的生成日期格式:2026年9月23日。 */
    private static final DateTimeFormatter TITLE_DATE = DateTimeFormatter.ofPattern("yyyy年M月d日");

    private final ObjectMapper json;
    private final Clock clock;

    public ReportOutlineComposer(ObjectMapper json, @Qualifier("skillFlowClock") Clock clock) {
        this.json = json;
        this.clock = clock;
    }

    /**
     * 按大纲快照组装报告 Markdown。
     *
     * @return 报告正文;快照为空/解析失败/无条目时返回 null(调用方走旧版兼容拼接)。
     */
    public String compose(String outlineJson, List<SkillFlowNodeExecution> nodes) {
        if (outlineJson == null || outlineJson.isBlank()) return null;
        Outline outline;
        try {
            outline = json.readValue(outlineJson, Outline.class);
        } catch (Exception e) {
            return null;
        }
        if (outline == null || outline.items() == null || outline.items().isEmpty()) return null;
        Map<String, SkillFlowNodeExecution> byNodeKey = new HashMap<>();
        for (SkillFlowNodeExecution node : nodes) {
            if (node.getNodeKey() != null) byNodeKey.putIfAbsent(node.getNodeKey(), node);
        }
        StringBuilder report = new StringBuilder();
        if (outline.title() != null && !outline.title().isBlank()) {
            // 标题尾部拼接报告生成日期,如 "XX报告 (2026年9月23日)"
            String datedTitle = outline.title().trim() + " ("
                    + TITLE_DATE.format(java.time.LocalDate.now(clock)) + ")";
            report.append("<h1 class=\"report-outline-title\" data-report-outline-heading=\"true\">")
                    .append(escapeHtml(datedTitle))
                    .append("</h1>\n\n");
        }
        Numbering numbering = outline.numbering() == null ? new Numbering(null, null, null) : outline.numbering();
        composeItems(outline.items(), numbering, new java.util.ArrayList<>(), byNodeKey, report, 1);
        return report.isEmpty() ? null : report.toString();
    }

    /** 深度优先遍历:counters[0..2] 为各层级当前计数,进入兄弟项自增、更深层清零。 */
    private void composeItems(List<Item> items, Numbering numbering, List<Integer> counters,
                              Map<String, SkillFlowNodeExecution> byNodeKey, StringBuilder report, int depth) {
        for (Item item : items) {
            // The tree is the source of truth.  Ignore stale/legacy level values so
            // arbitrarily deep outlines render consistently and cannot be clamped.
            int level = depth;
            while (counters.size() < level) counters.add(0);
            counters.set(level - 1, counters.get(level - 1) + 1);
            while (counters.size() > level) counters.remove(counters.size() - 1);
            report.append("<h").append(level + 1).append(" data-report-outline-heading=\"true\">")
                    .append(escapeHtml(renderNumbering(level, counters, numbering)
                            + Objects.toString(item.title(), "").trim()))
                    .append("</h").append(level + 1).append(">\n");
            // 章节下绑定的全部节点按序各渲染一段正文(兼容旧单 nodeKey 字段),有子级再继续递归
            for (String key : item.nodeKeys()) {
                if (!key.isEmpty()) {
                    report.append(sectionBody(byNodeKey.get(key), item.title())).append("\n\n");
                }
            }
            if (item.children() != null && !item.children().isEmpty()) {
                composeItems(item.children(), numbering, counters, byNodeKey, report, depth + 1);
            }
        }
    }

    /** 大纲层级 -> Markdown 标题前缀(1/2/3 级 -> ##/###/####,与现有报告 ## 节点名 风格一致)。 */
    private static String headingPrefix(int level) {
        return "#".repeat(level + 1) + " ";
    }

    /** 渲染某层编号:chinese=一、;arabic=路径阿拉伯计数 1. / 1.1.(尾部空格);none=无编号。 */
    private static String renderNumbering(int level, List<Integer> counters, Numbering numbering) {
        return switch (modeOf(numbering, level)) {
            case "none" -> "";
            case "chinese" -> chineseNumber(counters.get(level - 1)) + "、";
            default -> arabicNumbering(level, counters) + " ";
        };
    }

    /** 层级编号样式解析:未配置时缺省一级 chinese、二三级 arabic。 */
    private static String modeOf(Numbering numbering, int level) {
        String mode = switch (level) {
            case 1 -> numbering.level1();
            case 2 -> numbering.level2();
            default -> numbering.level3();
        };
        return mode == null || mode.isBlank() ? (level == 1 ? "chinese" : "arabic") : mode;
    }

    /** 阿拉伯编号取完整路径计数(含中文样式的祖先层级,如一级"一、"下二级为 1.1)用 . 连接:一级 "1",二级 "1.1",三级 "1.1.1"。 */
    private static String arabicNumbering(int level, List<Integer> counters) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < level; i++) {
            if (i > 0) sb.append('.');
            sb.append(counters.get(i));
        }
        return sb.toString();
    }

    /** 叶子章节正文:失败/缺失给统一占位;成功结果先剥重复外层标题,再把内部标题降为正文小节层级。 */
    private String sectionBody(SkillFlowNodeExecution node, String leafTitle) {
        if (node == null || node.getStatus() != FlowNodeExecutionStatus.SUCCESS) return "执行失败，暂无结果";
        String text = extractResultText(node.getResultJson());
        if (text == null || text.isBlank()) return "暂无结果";
        String body = demoteHeadings(stripDuplicateLeadingTitle(text, leafTitle)).strip();
        if (SHOW_NODE_NAMES_IN_REPORT && node.getNodeName() != null && !node.getNodeName().isBlank()) {
            body = "### " + node.getNodeName().trim() + "\n" + body;
        }
        return body;
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    /** 节点结果以 {"text": "..."} 保存,只渲染 text;无 text 字段时回退原文。 */
    String extractResultText(String resultJson) {
        if (resultJson == null || resultJson.isBlank()) return null;
        try {
            var root = JSON.readTree(resultJson);
            var text = root == null ? null : root.get("text");
            return text == null || text.isNull() ? resultJson : text.asText();
        } catch (Exception ignored) {
            return resultJson;
        }
    }

    /**
     * 结果开头的第一个标题(Markdown #~###### 或 HTML h1~h6)与大纲叶子标题相同
     * (剥编号前缀、去空白、忽略大小写)时移除该标题行;不同或没有标题原样返回。
     */
    static String stripDuplicateLeadingTitle(String text, String title) {
        if (text == null) return null;
        String stripped = text.stripLeading();
        if (stripped.isEmpty() || title == null || title.isBlank()) return text;
        int lineEnd = stripped.indexOf('\n');
        String firstLine = lineEnd < 0 ? stripped : stripped.substring(0, lineEnd);
        String headingText = null;
        Matcher md = MD_HEADING_LINE.matcher(firstLine);
        if (md.matches()) {
            headingText = md.group(2);
        } else {
            Matcher html = HTML_HEADING_LINE.matcher(firstLine);
            if (html.matches()) headingText = html.group(2);
        }
        if (headingText == null) return text;
        String normalizedHeading = normalizeTitle(headingText);
        String normalizedTitle = normalizeTitle(title);
        if (normalizedHeading.isEmpty() || !normalizedHeading.equals(normalizedTitle)) return text;
        return lineEnd < 0 ? "" : stripped.substring(lineEnd + 1).stripLeading();
    }

    /** 标题比较口径:剥编号前缀、去空白、转小写。 */
    private static String normalizeTitle(String value) {
        String s = value == null ? "" : value.trim().toLowerCase().replaceAll("\\s+", "");
        return NUMBERING_PREFIX.matcher(s).replaceFirst("");
    }

    /**
     * 内部标题整体降为正文小节层级:内容中最小的标题层级映射到 h5,其余按相对层级顺延、封顶 h6,
     * 保证不会占据大纲的 h2~h4 层级。内容没有 h1~h4 时原样返回。
     */
    static String demoteHeadings(String text) {
        String[] lines = text.split("\n", -1);
        int minLevel = 7;
        for (String line : lines) {
            Matcher md = MD_HEADING_LINE.matcher(line);
            if (md.matches()) minLevel = Math.min(minLevel, md.group(1).length());
        }
        if (minLevel >= 5) return text;
        int shift = 5 - minLevel;
        StringBuilder out = new StringBuilder(text.length());
        for (String line : lines) {
            Matcher md = MD_HEADING_LINE.matcher(line);
            if (md.matches()) {
                int newLevel = Math.min(6, md.group(1).length() + shift);
                out.append("#".repeat(newLevel)).append(' ').append(md.group(2)).append('\n');
            } else {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }

    /** 序号转中文数字(一、二、…、十、十一、…、九十九);超出范围回退阿拉伯数字。 */
    public static String chineseNumber(int number) {
        if (number <= 0 || number >= 100) return String.valueOf(number);
        String[] digits = {"", "一", "二", "三", "四", "五", "六", "七", "八", "九"};
        if (number < 10) return digits[number];
        String tens = number / 10 == 1 ? "十" : digits[number / 10] + "十";
        return tens + digits[number % 10];
    }
}
