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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将 Skill Job 的 Markdown 分析结果渲染成自包含 HTML 报告。
 *
 * <p>渲染流程：
 * <ol>
 *   <li>抽取 {@code ```echarts {option JSON}```} 代码块与 {@code <echart>...</echart>} 标签块，
 *       每块对应一张 echarts 图表；块之间的 Markdown 片段分别转 HTML。</li>
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

    /**
     * 匹配图表块，两种形式（按文档顺序）：
     * <ol>
     *   <li>{@code ```echarts ... ```} 围栏块（大小写不敏感，兼容 ```ECHARTS）；</li>
     *   <li>{@code <echart>...</echart>} / {@code <echarts>...</echarts>} 标签包裹的 option JSON
     *       （不以围栏开头，AI 有时用该形式输出图表）。</li>
     * </ol>
     * 捕获组：1=围栏块内 JSON；2=标签名；3=标签内内容。
     */
    private static final Pattern CHART_BLOCK = Pattern.compile(
            "(?:```echarts\\s*([\\s\\S]*?)```)"
                    + "|(?:<(echart|echarts)\\b[^>]*>([\\s\\S]*?)</\\2\\s*>)",
            Pattern.CASE_INSENSITIVE);

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
    /**
     * 段落：不以块级标签开头的行包 &lt;p&gt;。除块级标签字面外，还排除「转义后待还原的安全标签」行
     * （行首为 &amp;lt; 后跟字母 / 斜杠 / 感叹号，如 AI 直出的 &lt;table&gt;/&lt;tr&gt;/&lt;td&gt;）。
     * 否则这些行先被包 &lt;p&gt;、unescape 还原标签后变成 &lt;p&gt;&lt;table&gt;&lt;/p&gt; 之类结构，
     * 浏览器 foster parenting 会把表格拆成阶梯状，无法正常显示为表格。
     */
    private static final Pattern PARAGRAPH =
            Pattern.compile("^(?!<[hou]|<li|<div|<pre|<blockquote|<table|<ul|<ol|<hr|&lt;[a-zA-Z!/])(.+)$", Pattern.MULTILINE);

    // ── 表格行识别 ──────────────────────────────────────────────────────────
    private static final Pattern TABLE_HEADER = Pattern.compile("^\\s*\\|.*\\|\\s*$");
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^\\s*\\|[\\s:;-]+\\|.*$");

    /** 匹配 {@code <table>...</table>}（含属性、可跨行），统一包一层横向滚动容器；不支持嵌套表格。 */
    private static final Pattern TABLE_TAG =
            Pattern.compile("<table\\b[^>]*>[\\s\\S]*?</table>", Pattern.CASE_INSENSITIVE);

    /** 安全 HTML 标签白名单：AI 输出中这些标签会被还原渲染；script/iframe/未知标签保持转义防注入。 */
    private static final Set<String> SAFE_HTML_TAGS = Set.of(
            "b", "i", "em", "strong", "u", "s", "del", "ins", "mark", "small", "sub", "sup",
            "br", "p", "span", "div", "h1", "h2", "h3", "h4", "h5", "h6",
            "ul", "ol", "li", "dl", "dt", "dd", "blockquote", "pre", "hr",
            "table", "thead", "tbody", "tfoot", "caption", "colgroup", "col", "tr", "th", "td",
            "a", "img", "figure", "figcaption", "details", "summary", "abbr", "code"
    );

    /** 匹配被 escapeHtml 转义后的 HTML 标签：&lt;tag attrs&gt; 或 &lt;/tag&gt;，用于还原白名单标签。 */
    private static final Pattern ESCAPED_TAG =
            Pattern.compile("&lt;(/?)([a-zA-Z][a-zA-Z0-9:-]*)((?:[^&]|&(?!gt;))*?)&gt;");

    /** 匹配 renderInline 产出的行内代码 span <code>...</code>，还原标签时跳过其内容（代码保持字面）。 */
    private static final Pattern INLINE_CODE_SPAN =
            Pattern.compile("<code>.*?</code>", Pattern.DOTALL);

    // ── 完整 HTML 文档检测/抽取（AI 直出或嵌入 markdown 的 <html>...<body>...</body></html>）────
    /** 完整 HTML 文档判定标志之一：含 {@code <body>} 开标签（render()/renderMdFragment 检测用）。 */
    private static final Pattern BODY_OPEN_FULL = Pattern.compile("<body\\b[^>]*>", Pattern.CASE_INSENSITIVE);

    /** 完整 HTML 文档块：可选 {@code <!DOCTYPE>} + {@code <html>...</html>}（可跨行），嵌入 markdown 时整块抽进 iframe。 */
    private static final Pattern FULL_HTML_DOC =
            Pattern.compile("(?:<!DOCTYPE[^>]*>\\s*)?<html\\b[^>]*>[\\s\\S]*?</html>", Pattern.CASE_INSENSITIVE);

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
            table{border-collapse:collapse;width:100%;min-width:max-content;font-size:0.88rem}
            .table-wrap{overflow-x:auto;margin:8px 0}
            th,td{border:1px solid #e2e8f0;padding:6px 10px;text-align:left;vertical-align:top}
            th{background:#f8fafc;font-weight:600;color:#1e293b}
            tbody tr:nth-child(even){background:#f8fafc}
            ul,ol{margin:6px 0;padding-left:22px}
            li{margin:2px 0}
            a{color:#6366f1;text-decoration:none}
            a:hover{text-decoration:underline}
            .echarts-chart{width:100%;height:400px;margin:12px 0}
            .html-doc-frame{width:100%;min-height:240px;border:1px solid #e2e8f0;border-radius:8px;background:#fff;display:block}
            """;

    /**
     * 折线图颜色归一化脚本：统一每条 line series 的 lineStyle.color 与 itemStyle.color，
     * 并把 legend 显式 itemStyle.color 对齐到系列色，避免图例图标（线段）与数据点圆圈颜色不一致。
     * AI 产出的 echarts option 常出现 lineStyle/itemStyle 颜色不一致导致图例与圆圈撞色，此处兜底修正。
     */
    private static final String CHART_NORMALIZE_JS = """
            function normalizeChartOption(opt){
              if(!opt||typeof opt!=='object'||!opt.series||!opt.series.length) return opt;
              var colorByName={};
              for(var i=0;i<opt.series.length;i++){
                var s=opt.series[i]; if(!s||s.type!=='line') continue;
                var ls=s.lineStyle, is=s.itemStyle;
                var lc=ls&&ls.color, ic=is&&is.color;
                if(lc&&ic&&lc!==ic){ s.lineStyle=ls||{}; s.lineStyle.color=ic; }
                else if(ic&&!lc){ s.lineStyle=ls||{}; s.lineStyle.color=ic; }
                else if(lc&&!ic){ s.itemStyle=is||{}; s.itemStyle.color=lc; }
                var name=s.name; if(name!=null){ colorByName[name]=ic||lc; }
              }
              if(opt.legend&&opt.legend.data&&opt.legend.data.length){
                for(var j=0;j<opt.legend.data.length;j++){
                  var d=opt.legend.data[j]; if(typeof d!=='object'||d===null) continue;
                  var sc=colorByName[d.name];
                  if(sc&&d.itemStyle&&d.itemStyle.color&&d.itemStyle.color!==sc){ d.itemStyle.color=sc; }
                }
              }
              return opt;
            }
            """;

    /**
     * iframe 高度自适应脚本：srcdoc 同源可读 contentDocument，按内容 scrollHeight 撑高 iframe，
     * 避免嵌入的完整 HTML 页面被裁剪或留大片空白。load 后立即量一次 + 300ms 再量一次兜底晚渲染的图表。
     */
    private static final String IFRAME_RESIZE_JS = """
            (function(){
              function resize(f){
                try{
                  var d=f.contentDocument; if(!d) return;
                  var h=Math.max(d.documentElement.scrollHeight, d.body?d.body.scrollHeight:0);
                  f.style.height=(h+24)+'px';
                }catch(e){}
              }
              document.querySelectorAll('iframe.html-doc-frame').forEach(function(f){
                f.addEventListener('load',function(){resize(f);setTimeout(function(){resize(f);},300);});
                resize(f);
              });
            })();
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
     * @param markdown AI 输出的 Markdown（含可能的 {@code ```echarts} 或 {@code <echart>} 图表块）
     * @param title    报告标题（HTML &lt;title&gt;，会 HTML 转义）
     * @return 完整 HTML 文档字符串
     */
    public String render(String markdown, String title) {
        String md = markdown == null ? "" : markdown;
        String safeTitle = escapeHtml(title == null ? "报告" : title);

        // AI 偶尔直出完整 HTML 文档（以 <!DOCTYPE/<html 开头且含 <body>）：整页塞进 <iframe srcdoc>
        // 隔离渲染，保留 AI 原样式/脚本/布局，不走 markdown 转换、不抽 body 不删 script。
        // （嵌入在 markdown 中间位置的完整 HTML 文档，由 markdownToHtml -> renderMdFragment 同样按 iframe 处理）
        String leading = md.stripLeading();
        if ((leading.regionMatches(true, 0, "<!DOCTYPE", 0, 9)
                || leading.regionMatches(true, 0, "<html", 0, 5))
                && BODY_OPEN_FULL.matcher(md).find()) {
            return renderCompleteHtml(md, safeTitle);
        }

        // 1. 按图表块切分（```echarts 围栏 + <echart>/<echarts> 标签两种形式）：
        //    块间片段转 HTML，块位置插入图表占位 div
        StringBuilder body = new StringBuilder();
        List<ChartBlock> charts = splitCharts(md, body, true);

        return assembleHtml(safeTitle, body.toString(), charts);
    }

    /**
     * 渲染 AI 直出的完整 HTML 文档：整页经 {@code escapeHtml} 转义后塞进 {@code <iframe srcdoc>}
     * 隔离渲染，原样保留 AI 的样式/脚本/布局，不再抽取 body 内嵌、移除 {@code <script>} --
     * 否则依赖脚本（echarts 图表 / 动态内容 / 交互）的页面会丢内容、呈现为「没渲染」。
     *
     * <p>iframe 同源（srcdoc），父页面可读 {@code contentDocument} 做高度自适应。
     * AI 若用 CDN {@code <script src>} 仍需联网（这条路径不做 echarts 内联）。
     */
    private String renderCompleteHtml(String html, String safeTitle) {
        // escapeHtml 把 & < > " 转成实体，浏览器解码 srcdoc 属性后按完整文档渲染 iframe；
        // AI 原有 <style>/<script>/<head> 全部保留在 iframe 内，父页面只承载 iframe + 高度自适应
        String body = "<iframe class=\"html-doc-frame\" srcdoc=\"" + escapeHtml(html) + "\"></iframe>"
                + "<script>" + IFRAME_RESIZE_JS + "</script>";
        return assembleHtml(safeTitle, body, List.of());
    }

    /**
     * 按图表块（```echarts 围栏 + <echart>/<echarts> 标签）切分文本：
     * 块间片段追加到 {@code body}（renderMdLeftovers=true 走 markdown 转换，false 原样为 HTML），
     * 块位置插入图表占位 div，返回各图表块（DOM id + option JSON）。
     */
    private List<ChartBlock> splitCharts(String text, StringBuilder body, boolean renderMdLeftovers) {
        List<ChartBlock> charts = new ArrayList<>();
        Matcher m = CHART_BLOCK.matcher(text);
        int last = 0;
        int idx = 0;
        while (m.find()) {
            if (m.start() > last) {
                body.append(renderMdLeftovers
                        ? markdownToHtml(text.substring(last, m.start()))
                        : text.substring(last, m.start()));  // raw HTML，不走 markdown
            }
            // 围栏块取 g1；标签块取 g3 并去掉标签内容里可能残留的 ``` 围栏
            String json = (m.group(1) != null ? m.group(1) : stripFences(m.group(3))).trim();
            String chartId = "echarts-" + idx;
            body.append("<div class=\"echarts-chart\" id=\"").append(chartId).append("\"></div>\n");
            charts.add(new ChartBlock(chartId, json));
            idx++;
            last = m.end();
        }
        if (last < text.length()) {
            body.append(renderMdLeftovers ? markdownToHtml(text.substring(last)) : text.substring(last));
        }
        return charts;
    }

    /** 去掉标签内容首尾残留的 ``` 围栏（兼容 <echart> 内又套 ```echarts 的双重包裹）。 */
    private static String stripFences(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            t = nl >= 0 ? t.substring(nl + 1) : t.substring(3);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
            t = t.trim();
        }
        return t;
    }

    // ── 组装完整 HTML 文档 ────────────────────────────────────────────────────

    private String assembleHtml(String title, String body, List<ChartBlock> charts) {
        // 每个表格包一层横向滚动容器：宽表保持自然列宽横拉（min-width:max-content），不再被 width:100% 挤窄
        // 导致文字大量换行把表格撑得很高（挤下去）。markdown 路径的 GFM 表格与嵌入 HTML 表格走这里
        // （完整 HTML 文档路径的表格在 iframe srcdoc 内，由 AI 自带样式渲染，不经此处）
        body = wrapTables(body);
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
            sb.append("<script>").append(CHART_NORMALIZE_JS).append("</script>");
            sb.append("<script>window.addEventListener('load',function(){var charts=[");
            for (int i = 0; i < charts.size(); i++) {
                if (i > 0) sb.append(',');
                ChartBlock c = charts.get(i);
                sb.append("{id:").append(jsString(c.id)).append(",option:normalizeChartOption(")
                  .append(safeOptionJson(c.json)).append(")}");
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
                out.append(renderMdFragment(md.substring(last, code.start())));
            }
            String lang = code.group(1);
            String content = escapeHtml(code.group(2));
            String langAttr = (lang != null && !lang.isEmpty()) ? " class=\"language-" + lang + "\"" : "";
            out.append("<pre><code").append(langAttr).append(">").append(content).append("</code></pre>\n");
            last = code.end();
        }
        if (last < md.length()) {
            out.append(renderMdFragment(md.substring(last)));
        }
        return out.toString();
    }

    /**
     * 片段级转换：先把片段内「完整 HTML 文档」（{@code <html>...</html>} 且含 {@code <body>}）整块抽进
     * {@code <iframe srcdoc>} 隔离渲染（保留 AI 原样式/脚本，iframe 内执行），其余文本继续走
     * {@link #renderInline} 行级渲染。抽到过完整文档时，末尾补一段 iframe 高度自适应脚本。
     *
     * <p>在 {@link #markdownToHtml} 的代码块切分之后调用：先保护 fenced code（代码样例里展示的
     * {@code <html>} 不能被误当活页），再识别真正的完整 HTML 文档块。
     */
    private String renderMdFragment(String frag) {
        if (frag == null || frag.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        Matcher doc = FULL_HTML_DOC.matcher(frag);
        int last = 0;
        boolean hasDoc = false;
        while (doc.find()) {
            if (doc.start() > last) {
                out.append(renderInline(frag.substring(last, doc.start())));
            }
            String block = doc.group();
            if (BODY_OPEN_FULL.matcher(block).find()) {
                out.append("<iframe class=\"html-doc-frame\" srcdoc=\"")
                        .append(escapeHtml(block)).append("\"></iframe>\n");
                hasDoc = true;
            } else {
                out.append(renderInline(block));  // <html> 包裹但无 <body>，按普通文本渲染
            }
            last = doc.end();
        }
        if (last < frag.length()) {
            out.append(renderInline(frag.substring(last)));
        }
        if (hasDoc) {
            out.append("<script>").append(IFRAME_RESIZE_JS).append("</script>");
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
        // 还原 AI 输出中的 HTML 标签以供浏览器渲染（白名单安全标签）；<code> 代码内容保持字面不还原
        html = unescapeSafeHtmlTags(html);
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
                out.append("<table><thead><tr>");
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
                out.append("</tbody></table>\n");
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

    /**
     * 把每个 {@code <table>...</table>} 包进 {@code <div class="table-wrap">}（CSS overflow-x:auto）。
     * 配合 {@code table{min-width:max-content}}：宽表保持自然列宽横拉滚动，不再被 {@code width:100%}
     * 挤窄导致文字大量换行把表格撑得很高（挤下去）。markdown 路径的 GFM 表格与嵌入 HTML 表格走这里
     * （完整 HTML 文档路径的表格在 iframe srcdoc 内，由 AI 自带样式渲染，不经此处）。
     */
    private static String wrapTables(String html) {
        if (html == null || html.isEmpty()) return html;
        Matcher m = TABLE_TAG.matcher(html);
        StringBuilder sb = new StringBuilder(html.length() + 64);
        while (m.find()) {
            // quoteReplacement：表格内容可能含 $ / \，避免被当作替换引用
            m.appendReplacement(sb, Matcher.quoteReplacement("<div class=\"table-wrap\">" + m.group() + "</div>"));
        }
        m.appendTail(sb);
        return sb.toString();
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

    /**
     * 还原 AI 输出中被转义的安全 HTML 标签，使其在浏览器中渲染（而非以字面 &lt;tag&gt; 展示）。
     *
     * <p>仅还原白名单内常见结构/格式标签；{@code <script>}/{@code <iframe>}/未知标签保持转义，防注入。
     * {@code <code>...</code>} 行内代码内容保持字面不还原（代码应原样展示）。
     */
    private String unescapeSafeHtmlTags(String html) {
        if (html == null || html.isEmpty()) return html;
        // 按 <code>...</code> 分段：仅对代码片段之外还原标签，代码内保持转义
        Matcher code = INLINE_CODE_SPAN.matcher(html);
        StringBuilder out = new StringBuilder(html.length());
        int last = 0;
        while (code.find()) {
            out.append(unescapeSafeTagsInSegment(html.substring(last, code.start())));
            out.append(code.group());
            last = code.end();
        }
        out.append(unescapeSafeTagsInSegment(html.substring(last)));
        return out.toString();
    }

    /** 对单个非代码片段还原白名单 HTML 标签为真实尖括号；非白名单标签保持转义。 */
    private String unescapeSafeTagsInSegment(String segment) {
        Matcher m = ESCAPED_TAG.matcher(segment);
        StringBuilder sb = new StringBuilder(segment.length());
        while (m.find()) {
            String slash = m.group(1);
            String tag = m.group(2).toLowerCase();
            String attrs = m.group(3);
            if (SAFE_HTML_TAGS.contains(tag)) {
                // 属性内 &amp; 还原为 & 保持可读；标签本体还原为真实尖括号交浏览器渲染
                String replacement = "<" + slash + tag + attrs.replace("&amp;", "&") + ">";
                m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
            }
        }
        m.appendTail(sb);
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
