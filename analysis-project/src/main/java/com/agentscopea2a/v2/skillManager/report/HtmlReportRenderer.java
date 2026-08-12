package com.agentscopea2a.v2.skillManager.report;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将 Skill Job 的 Markdown 分析结果渲染成自包含 HTML 报告。
 *
 * <p>渲染流程：
 * <ol>
 *   <li>抽取 {@code ```echarts {option JSON}```} 代码块，每个块对应一张 echarts 图表；
 *       块之间的 Markdown 片段分别转 HTML。</li>
 *   <li>Markdown -> HTML：移植自前端 {@code Markdown.vue} 的手写渲染逻辑
 *       （标题/GFM 管道表格/列表/代码块/行内代码/粗斜体/引用/hr/链接/段落），无需引入第三方依赖。</li>
 *   <li>组装完整 HTML 文档：内联 CSS（表格斑马纹/边框/表头底色）+ 内联 echarts.min.js
 *       + {@code setOption} 脚本。echarts.min.js 仅在存在图表时内联，无图省 1.1MB。</li>
 * </ol>
 *
 * <p>生成的 HTML 自包含：邮件附件、下载到本地、断网打开均能渲染表格和图表。
 * echarts.min.js 从 classpath {@code report-assets/echarts.min.js} 读取，启动时加载一次缓存。
 */
@Component
public class HtmlReportRenderer {

    private static final Logger log = LoggerFactory.getLogger(HtmlReportRenderer.class);

    /** classpath 下的 echarts.min.js 资源路径。 */
    private static final String ECHARTS_RESOURCE = "report-assets/echarts.min.js";

    /** 匹配 ```echarts ... ``` 代码块，捕获块内 JSON（大小写不敏感，兼容 ```ECHARTS）。 */
    private static final Pattern ECHARTS_BLOCK =
            Pattern.compile("```echarts\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);

    /** 普通代码块（非 echarts）：```lang\n code ```。 */
    private static final Pattern CODE_BLOCK =
            Pattern.compile("```(\\w*)\\n([\\s\\S]*?)```");

    // ── 行级 Markdown 正则（MULTILINE：^ $ 匹配行首行尾）──────────────────────
    private static final Pattern HR = Pattern.compile("^---+\\s*$", Pattern.MULTILINE);
    private static final Pattern H6 = Pattern.compile("^######\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern H5 = Pattern.compile("^#####\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern H4 = Pattern.compile("^####\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern H3 = Pattern.compile("^###\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern H2 = Pattern.compile("^##\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern H1 = Pattern.compile("^#\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern BLOCKQUOTE = Pattern.compile("^&gt;\\s?(.+)$", Pattern.MULTILINE);
    private static final Pattern UL_ITEM = Pattern.compile("^[\\s]*[-*]\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern OL_ITEM = Pattern.compile("^[\\s]*(\\d+)\\.\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern UL_WRAP = Pattern.compile("((?:<li[^>]*>.*?</li>\\s*)+)", Pattern.MULTILINE);
    private static final Pattern BOLD = Pattern.compile("\\*\\*([^*]+)\\*\\*");
    private static final Pattern ITALIC = Pattern.compile("\\*([^*]+)\\*");
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)\\]\\(([^)]+)\\)");
    /** 段落：不以块级标签开头的行包 &lt;div&gt;。 */
    private static final Pattern PARAGRAPH =
            Pattern.compile("^(?!<[hou]|<li|<div|<pre|<blockquote|<table|<ul|<ol|<hr)(.+)$", Pattern.MULTILINE);

    // ── 表格行识别 ──────────────────────────────────────────────────────────
    private static final Pattern TABLE_HEADER = Pattern.compile("^\\s*\\|.*\\|\\s*$");
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^\\s*\\|[\\s:;-]+\\|.*$");

    /** 内联 CSS：复用前端 Markdown.vue LIGHT 主题，表格斑马纹/边框/表头底色。 */
    private static final String CSS = """
            *{box-sizing:border-box}
            body{font-family:-apple-system,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif;font-size:14px;line-height:1.6;color:#1e293b;background:#fff;margin:0;padding:24px}
            .report{max-width:1100px;margin:0 auto}
            h1,h2,h3,h4,h5,h6{color:#0f172a;margin:16px 0 8px;line-height:1.3}
            h1{font-size:1.6rem;border-bottom:1px solid #e2e8f0;padding-bottom:6px}
            h2{font-size:1.35rem;border-bottom:1px solid #e2e8f0;padding-bottom:4px}
            h3{font-size:1.18rem}
            h4{font-size:1.05rem}
            h5{font-size:0.95rem}
            h6{font-size:0.9rem}
            p{margin:6px 0}
            hr{border:none;border-top:1px solid #e2e8f0;margin:12px 0}
            blockquote{margin:8px 0;padding:6px 12px;border-left:3px solid #cbd5e1;background:#f8fafc;color:#475569}
            code{background:#f1f5f9;color:#be185d;padding:1px 5px;border-radius:4px;font-family:ui-monospace,"SFMono-Regular",Menlo,monospace;font-size:0.88em}
            pre{background:#0f172a;color:#e2e8f0;border:1px solid #334155;padding:10px 14px;border-radius:6px;overflow-x:auto;margin:8px 0}
            pre code{background:transparent;color:inherit;padding:0;font-size:0.85rem}
            table{border-collapse:collapse;width:100%;font-size:0.88rem;margin:8px 0}
            th,td{border:1px solid #e2e8f0;padding:6px 10px;text-align:left;vertical-align:top}
            th{background:#f8fafc;font-weight:600;color:#1e293b}
            tbody tr:nth-child(even){background:#f8fafc}
            ul,ol{margin:6px 0;padding-left:22px}
            li{margin:2px 0}
            a{color:#6366f1;text-decoration:none}
            a:hover{text-decoration:underline}
            .echarts-chart{width:100%;height:400px;margin:12px 0}
            """;

    /** 启动时加载一次的 echarts.min.js 全文，内联进每个含图表的报告。 */
    private String echartsJs;

    @PostConstruct
    public void init() {
        try (InputStream in = new ClassPathResource(ECHARTS_RESOURCE).getInputStream()) {
            echartsJs = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            log.info("HtmlReportRenderer: loaded echarts.min.js ({} bytes) from classpath {}",
                    echartsJs.length(), ECHARTS_RESOURCE);
        } catch (Exception e) {
            // fail-fast：缺 echarts.min.js 会导致图表静默不渲染，启动即暴露
            throw new IllegalStateException(
                    "HtmlReportRenderer: failed to load " + ECHARTS_RESOURCE + " from classpath", e);
        }
    }

    /**
     * 渲染 Markdown 为自包含 HTML 文档。
     *
     * @param markdown AI 输出的 Markdown（含可能的 {@code ```echarts} 图表块）
     * @param title    报告标题（HTML &lt;title&gt;，会 HTML 转义）
     * @return 完整 HTML 文档字符串
     */
    public String render(String markdown, String title) {
        String md = markdown == null ? "" : markdown;
        String safeTitle = escapeHtml(title == null ? "报告" : title);

        // 1. 按 echarts 块切分：块间片段转 HTML，块位置插入图表占位 div
        StringBuilder body = new StringBuilder();
        List<ChartBlock> charts = new ArrayList<>();
        Matcher m = ECHARTS_BLOCK.matcher(md);
        int last = 0;
        int idx = 0;
        while (m.find()) {
            if (m.start() > last) {
                body.append(markdownToHtml(md.substring(last, m.start())));
            }
            String json = m.group(1).trim();
            String chartId = "echarts-" + idx;
            body.append("<div class=\"echarts-chart\" id=\"").append(chartId).append("\"></div>\n");
            charts.add(new ChartBlock(chartId, json));
            idx++;
            last = m.end();
        }
        if (last < md.length()) {
            body.append(markdownToHtml(md.substring(last)));
        }

        return assembleHtml(safeTitle, body.toString(), charts);
    }

    // ── 组装完整 HTML 文档 ────────────────────────────────────────────────────

    private String assembleHtml(String title, String body, List<ChartBlock> charts) {
        StringBuilder sb = new StringBuilder(1024 + body.length());
        sb.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">");
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
        sb.append("<title>").append(title).append("</title>");
        sb.append("<style>").append(CSS).append("</style>");
        sb.append("</head><body><div class=\"report\">");
        sb.append(body);
        sb.append("</div>");
        // 仅在有图表时内联 echarts.min.js；无图省 1.1MB
        if (!charts.isEmpty()) {
            sb.append("<script>").append(echartsJs).append("</script>");
            sb.append("<script>window.addEventListener('load',function(){var charts=[");
            for (int i = 0; i < charts.size(); i++) {
                if (i > 0) sb.append(',');
                ChartBlock c = charts.get(i);
                sb.append("{id:").append(jsString(c.id)).append(",option:").append(safeOptionJson(c.json)).append("}");
            }
            sb.append("];for(var i=0;i<charts.length;i++){var el=document.getElementById(charts[i].id);");
            sb.append("if(el){echarts.init(el).setOption(charts[i].option);}}});</script>");
        }
        sb.append("</body></html>");
        return sb.toString();
    }

    // ── Markdown -> HTML body（移植自前端 Markdown.vue）──────────────────────

    private String markdownToHtml(String md) {
        if (md == null || md.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        Matcher code = CODE_BLOCK.matcher(md);
        int last = 0;
        while (code.find()) {
            if (code.start() > last) {
                out.append(renderInline(md.substring(last, code.start())));
            }
            String lang = code.group(1);
            String content = escapeHtml(code.group(2));
            String langAttr = (lang != null && !lang.isEmpty()) ? " class=\"language-" + lang + "\"" : "";
            out.append("<pre><code").append(langAttr).append(">").append(content).append("</code></pre>\n");
            last = code.end();
        }
        if (last < md.length()) {
            out.append(renderInline(md.substring(last)));
        }
        return out.toString();
    }

    private String renderInline(String text) {
        String html = escapeHtml(text);
        html = HR.matcher(html).replaceAll("<hr>");
        // 标题：长前缀先匹配（###### 优先于 #），避免 # 误吃 ######
        html = H6.matcher(html).replaceAll("<h6>$1</h6>");
        html = H5.matcher(html).replaceAll("<h5>$1</h5>");
        html = H4.matcher(html).replaceAll("<h4>$1</h4>");
        html = H3.matcher(html).replaceAll("<h3>$1</h3>");
        html = H2.matcher(html).replaceAll("<h2>$1</h2>");
        html = H1.matcher(html).replaceAll("<h1>$1</h1>");
        html = renderTables(html);
        html = BLOCKQUOTE.matcher(html).replaceAll("<blockquote>$1</blockquote>");
        html = UL_ITEM.matcher(html).replaceAll("<li>$1</li>");
        html = OL_ITEM.matcher(html).replaceAll("<li>$2</li>");
        html = UL_WRAP.matcher(html).replaceAll("<ul>$1</ul>");
        html = BOLD.matcher(html).replaceAll("<strong>$1</strong>");
        html = ITALIC.matcher(html).replaceAll("<em>$1</em>");
        html = INLINE_CODE.matcher(html).replaceAll("<code>$1</code>");
        html = LINK.matcher(html).replaceAll("<a href=\"$2\" target=\"_blank\" rel=\"noreferrer\">$1</a>");
        html = PARAGRAPH.matcher(html).replaceAll("<p>$1</p>");
        return html;
    }

    /** GFM 管道表格：header 行 + 分隔行 + 数据行。逐行扫描，遇表格段组装 &lt;table&gt;。 */
    private String renderTables(String html) {
        String[] lines = html.split("\n", -1);
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < lines.length) {
            if (i + 1 < lines.length
                    && TABLE_HEADER.matcher(lines[i]).find()
                    && TABLE_SEPARATOR.matcher(lines[i + 1]).find()) {
                String[] header = splitRow(lines[i]);
                i += 2;
                List<String[]> rows = new ArrayList<>();
                while (i < lines.length && TABLE_HEADER.matcher(lines[i]).find()) {
                    rows.add(splitRow(lines[i]));
                    i++;
                }
                out.append("<div style=\"overflow-x:auto\"><table><thead><tr>");
                for (String h : header) {
                    out.append("<th>").append(h).append("</th>");
                }
                out.append("</tr></thead><tbody>");
                for (String[] row : rows) {
                    out.append("<tr>");
                    for (String c : row) {
                        out.append("<td>").append(c).append("</td>");
                    }
                    out.append("</tr>");
                }
                out.append("</tbody></table></div>\n");
            } else {
                out.append(lines[i]).append('\n');
                i++;
            }
        }
        return out.toString();
    }

    /** 拆分管道表格行：去首尾 |，按 | 切，每个单元格 trim。 */
    private String[] splitRow(String line) {
        String t = line.trim();
        if (t.startsWith("|")) t = t.substring(1);
        if (t.endsWith("|")) t = t.substring(0, t.length() - 1);
        String[] cells = t.split("\\|", -1);
        for (int j = 0; j < cells.length; j++) cells[j] = cells[j].trim();
        return cells;
    }

    // ── 安全工具 ──────────────────────────────────────────────────────────

    private static String escapeHtml(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** echarts option JSON 嵌入 &lt;script&gt; 内：转义 {@code </script} 防 HTML 解析中断。 */
    private static String safeOptionJson(String json) {
        if (json == null) return "{}";
        // 大小写不敏感替换 </script -> <\/script，JSON 字符串内合法、HTML 解析不再误判结束标签
        return json.replaceAll("(?i)</script", "<\\\\/script");
    }

    /** 把字符串拼成 JS 字符串字面量（双引号包裹，转义敏感字符）。 */
    private static String jsString(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /** 一个 echarts 图表块：DOM id + option JSON 文本。 */
    private record ChartBlock(String id, String json) {}
}
