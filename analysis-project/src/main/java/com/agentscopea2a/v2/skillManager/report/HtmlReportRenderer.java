package com.agentscopea2a.v2.skillManager.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * <p>多份完整 HTML 文档与 Markdown 混排时（如 Skill Flow 汇总报告按节点拼接），
 * 每份 HTML 文档渲染成独立子文档后以 iframe 嵌入（srcdoc，报告仍自包含）：
 * 各文档样式/脚本隔离在各自 iframe 内，避免拼接到同一页面时互相覆盖导致图表被挤压；
 * 子报告宽度为 100%，高度由脚本按内容测量；图表按子文档自然布局渲染不被压缩。</p>
 *
 * <p>生成的 HTML 自包含：邮件附件、下载到本地、断网打开均能渲染表格和图表。
 * echarts.min.js 从 classpath {@code report-assets/echarts.min.js} 读取，启动时加载一次缓存。
 */
@Component
public class HtmlReportRenderer {
    private final ThreadLocal<Boolean> fullscreenDisabled = ThreadLocal.withInitial(() -> false);

    /** 长任务与普通报告共用唯一的样式配置，避免维护两套相互覆盖的 CSS。 */
    public String renderLongTask(String content, String title, boolean fullscreen) {
        return fullscreen ? render(content, title) : renderWithoutFullscreen(content, title);
    }

    /**
     * 样式修改入口：通常只需要修改 :root 中的数值。
     * 每个普通选择器只定义一次，不再在 assembleHtml 中追加覆盖样式。
     * class 只用于页面内唯一的 div；重复的图表、表格使用 data-* 标记。
     * 节点输入 HTML 自带的 class 保持原样，这里只管理渲染器生成的元素。
     */
    private static final String REPORT_CSS = """
            :root {
                /* 正文字号：px 是像素；改大文字就变大，不改变标题字号。 */
                --report-body-font-size: 16px;
                /* 段落上下间距：左右间距固定为 0。 */
                --report-paragraph-gap: 4px;
                /* 表格上下间距；不包括单元格内部留白。 */
                --report-table-gap: 4px;
                /* 图表与上下内容之间的距离。 */
                --report-chart-gap: 8px;
                /* 汇总中每个 iframe 与上下章节之间的距离。 */
                --report-frame-gap: 8px;
                /* 每个 iframe 整份正文四周的内部留白；不是单独给表格加。 */
                --report-frame-padding:0 12px;
                /* 目录宽度以及目录和正文之间的空隙。 */
                --report-toc-width: 248px;
                --report-toc-gap: 16px;
                /* 普通图表高度；图表 option.chartHeight 仍可指定单张图表的高度。 */
                --report-chart-height: 460px;
                /* 单元格内部留白：两个数依次是上下、左右。 */
                --report-header-padding: 8px 14px;
                --report-cell-padding: 6px 12px;
                /* 表头背景颜色：rgb 的三个数分别是红、绿、蓝。 */
                --report-header-background: rgb(192, 0, 0);
                /* 下列模式参数由 Java 决定：页面滚动，iframe 则按内容撑高。 */
                --report-content-height: @@HEIGHT@@;
                --report-content-overflow: @@OVERFLOW@@;
                --report-content-offset: @@OFFSET@@;
                --report-content-padding: @@PADDING@@;
            }

            /* 统一盒子尺寸算法：宽度包含 padding 和边框，不会因留白而撑宽。 */
            * {
                box-sizing: border-box;
            }
            html {
                max-width: 100%;
                overflow-x: hidden; /* 防止整个页面出现横向滚动条。 */
            }
            body {
                margin: 0 !important; /* 清除浏览器默认外边距。 */
                padding: 0 !important; /* 留白统一交给 .report，避免重复缩进。 */
                font-family: -apple-system, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif;
                font-size: var(--report-body-font-size); /* var(...) 读取顶部配置。 */
                line-height: 1.7; /* 行高为文字大小的 1.7 倍。 */
                color: #000;
                background: #fff;
                overflow-x: hidden;
            }

            /* 页面外壳：该 class 在每份文档里只绑定到这一个 div。 */
            .report-shell {
                height: var(--report-content-height);
                overflow: var(--report-content-overflow);
            }
            /* 正文容器：字号、整体留白、宽度和滚动只在这里定义。 */
            .report {
                position: relative; /* 让全屏按钮相对正文定位。 */
                height: var(--report-content-height);
                margin-left: var(--report-content-offset);
                width: calc(100% - var(--report-content-offset));
                padding: var(--report-content-padding) !important;
                overflow-x: hidden;
                overflow-y: var(--report-content-overflow);
                text-align: left;
            }
            /* 全屏是明确的特殊状态：只改变配置变量，不再重写普通布局属性。 */
            .report:fullscreen {
                --report-content-height: 100vh;
                --report-content-offset: 0px;
                --report-content-padding: 48px 16px 16px;
                --report-content-overflow: auto;
                background: #fff;
            }
            /* #report 表示 id=report；排除标题及其内部文字、按钮，统一正文大小。
               !important 只用于需要统一的内容，覆盖节点 HTML 自带的普通样式。 */
            #report :not(:is(h1, h2, h3, h4, h5, h6, h1 *, h2 *, h3 *, h4 *, h5 *, h6 *, button, button *)) {
                font-size: var(--report-body-font-size) !important;
            }

            /* 目录及目录列表都只绑定到一个 div。 */
            #report-toc {
                position: fixed; /* 固定在浏览器左侧，正文滚动时目录不移动。 */
                z-index: 10; /* 目录位于正文外壳上方，确保滚轮和点击能到达目录。 */
                inset: 0 auto 0 0;
                width: var(--report-toc-width);
                height: 100vh !important; /* 固定可视高度，不随目录条数向下撑长。 */
                max-height: 100vh !important;
                padding: 18px 12px;
                background: #fff;
                border-right: 1px solid #e2e8f0;
                overflow-y: scroll !important; /* 始终预留纵向滚动条空间，目录超出时可向下滚动。 */
                overflow-x: hidden !important;
                overscroll-behavior: contain; /* 滚到目录末尾时不带动正文滚动。 */
                pointer-events: auto !important; /* 允许鼠标与目录交互。 */
                /* 不设置 scrollbar-width/color，避免新版 Chrome、Edge 忽略下面的 4px 样式。 */
            }
            /* Chrome、Edge 的目录滚动条宽度：数值越小越细。 */
            #report-toc::-webkit-scrollbar {
                width: 4px;
            }
            #report-toc::-webkit-scrollbar-track {
                background: transparent; /* 轨道透明，保留滚动条空间。 */
            }
            #report-toc::-webkit-scrollbar-thumb {
                background: #cbd5e1; /* 可拖动滑块的颜色。 */
                border-radius: 4px;
            }
            #report-toc .report-toc-list {
                display: flex;
                flex-direction: column; /* 目录条目竖向排列。 */
                height: auto !important;
                min-height: max-content !important; /* 列表保持全部条目的高度，外层才有内容可滚动。 */
                max-height: none !important;
                gap: 3px; /* 条目之间的距离。 */
                padding-bottom: 24px;
            }
            #report-toc a {
                display: block;
                flex-shrink: 0; /* 条目不被压缩，以正常高度撑开目录列表。 */
                padding: 5px 7px;
                color: #475569;
                border-radius: 5px;
                font-size: 13px;
                line-height: 1.35;
                text-decoration: none;
                overflow-wrap: anywhere;
            }
            #report-toc a:hover {
                background: #eff6ff;
                color: #2563eb;
            }
            /* Markdown 标题保留原来的字号；完整 HTML 标题使用节点自己的样式。 */
            body[data-report-markdown] :is(h1, h2, h3, h4, h5, h6) {
                color: #0f172a;
                margin: 16px 0 8px;
                line-height: 1.3;
            }
            body[data-report-markdown] h1 {
                font-size: 1.6rem;
                padding-bottom: 6px;
            }
            body[data-report-markdown] h2 {
                font-size: 1.35rem;
                padding-bottom: 4px;
            }
            body[data-report-markdown] h3 {
                font-size: 1.18rem;
            }
            body[data-report-markdown] h4 {
                font-size: 1.05rem;
            }
            body[data-report-markdown] h5 {
                font-size: 0.95rem;
            }
            body[data-report-markdown] h6 {
                font-size: 0.9rem;
            }
            /* 报告总标题居中，与正文靠左的规则分开。 */
            #report .report-outline-title {
                display: block;
                width: 100%;
                margin: 16px 0 24px;
                text-align: center !important;
                border: 0 !important;
                padding-bottom: 6px;
            }
            #report p {
                margin: var(--report-paragraph-gap) 0 !important;
            }
            #report hr {
                border: none;
                border-top: 1px solid #e2e8f0;
                margin: 12px 0;
            }
            #report blockquote {
                margin: 8px 0;
                padding: 6px 12px;
                border-left: 3px solid #cbd5e1;
                background: #f8fafc;
                color: #475569;
            }
            #report code {
                background: #f1f5f9;
                color: #be185d;
                padding: 1px 5px;
                border-radius: 4px;
                font-family: ui-monospace, "SFMono-Regular", Menlo, monospace;
            }
            #report pre {
                background: #0f172a;
                color: #e2e8f0;
                border: 1px solid #334155;
                padding: 10px 14px;
                border-radius: 6px;
                overflow-x: auto;
                margin: 8px 0;
            }
            #report pre code {
                background: transparent;
                color: inherit;
                padding: 0;
            }
            #report :is(ul, ol) {
                margin: 6px 0;
                padding-left: 22px;
            }
            #report li {
                margin: 2px 0;
            }
            #report a {
                color: #6366f1;
                text-decoration: none;
            }
            #report a:hover {
                text-decoration: underline;
            }

            /* 重复表格不用 class；[data-report-table-scroll] 选中表格滚动容器。 */
            #report [data-report-table-scroll] {
                display: block;
                width: 100%;
                max-width: 100%;
                min-width: 0;
                padding: 0; /* 表格不单独缩进，整体留白在顶部 frame-padding 调整。 */
                margin: 0;
                overflow-x: auto; /* 宽表可左右滚动。 */
                overflow-y: visible;
                scrollbar-width: thin;
                scrollbar-color: #cbd5e1 transparent;
            }
            /* 表格样式唯一入口：不要再在其他 CSS 块里定义 table。 */
            #report table {
                border-collapse: collapse !important; /* 相邻单元格共用边框。 */
                width: max-content !important; /* 按内容宽度显示，不强行铺满页面。 */
                min-width: 0 !important;
                max-width: 100% !important;
                table-layout: auto !important;
                margin: var(--report-table-gap) 0 !important;
                box-shadow: none !important;
            }
            #report :is(th, td) {
                border: 1px solid #bfbfbf !important;
                text-align: center; /* 未显式指定时单元格文字居中；显式对齐样式可以覆盖。 */
                vertical-align: middle !important;
                white-space: normal !important;
                overflow-wrap: anywhere !important;
                word-break: break-word !important;
            }
            #report th {
                background: var(--report-header-background) !important;
                color: #fff !important;
                font-weight: 600 !important;
                padding: var(--report-header-padding) !important;
            }
            #report td {
                background: var(--report-row-background, #fff) !important;
                color: #000; /* 不加 important，保留业务数据的红色等标记。 */
                padding: var(--report-cell-padding) !important;
            }
            #report tbody tr:nth-child(even) {
                --report-row-background: #fafafa; /* 偶数行背景，形成斑马纹。 */
            }
            #report tbody tr:hover {
                --report-row-background: #fff3e0; /* 鼠标移到整行时的背景。 */
            }
            #report table .red {
                color: red !important;
            }

            /* 重复图表通过 data 属性选中；图表外壳与绘图区域分别只定义一次。 */
            /* 图表占正文宽度的 80%，随页面宽度自适应，并靠左显示。 */
            #report [data-report-chart-shell] {
                /* 让全屏按钮相对图表容器定位。 */
                position: relative;
            
                /* 修改这个百分比即可调整图表宽度。 */
                width: 80%;
            
                /* 上下保留间距，左侧不留空白，剩余空白放右侧。 */
                margin: var(--report-chart-gap) 0;
                margin-left: 0;
                margin-right: auto;
            
                /* 保留全屏状态使用的内边距。 */
                padding: var(--report-chart-padding, 0);
            
                /* 白色背景，内部文字默认靠左。 */
                background: #fff;
                text-align: left;
            }
            #report [data-report-chart] {
                display: block;
                width: 100%;
                height: var(--report-chart-height);
                margin-left: 0;
                margin-right: auto;
            }
            /* 图表失败时在原位置显示错误，点击即可查看，不必打开浏览器控制台。 */
            #report [data-report-chart-error] {
                padding: 12px;
                border: 1px solid #ef4444;
                background: #fef2f2;
                color: #991b1b;
                overflow-wrap: anywhere;
            }
            #report [data-report-chart-shell]:fullscreen {
                --report-chart-padding: 48px 16px 16px;
                --report-chart-height: calc(100vh - 64px);
            }
            /* 两种全屏按钮共用一个规则，通过现有 data 属性绑定，不重复 class。 */
            #report :is([data-chart-fullscreen], [data-html-fullscreen]) {
                position: absolute;
                z-index: 2;
                top: 12px;
                right: 12px;
                width: 36px;
                height: 36px;
                border: 1px solid #d7dee8;
                border-radius: 8px;
                background: rgba(255, 255, 255, .96);
                color: #475569;
                cursor: pointer;
                font-size: 20px;
                line-height: 34px;
                text-align: center;
            }
            #report :is([data-chart-fullscreen], [data-html-fullscreen]):hover {
                color: #2563eb;
                border-color: #93c5fd;
                background: #eff6ff;
            }

            /* 每份子报告独占一行；同一汇总里多个 iframe 不重复绑定 class。 */
            #report [data-report-frame-shell] {
                display: block;
                clear: both;
                width: 100%;
                margin: var(--report-frame-gap) 0;
            }
            #report [data-report-frame] {
                display: block;
                width: 100%;
                height: 0; /* 初始高度，载入后由脚本按子文档实际内容计算。 */
                border: 0;
                outline: 0;
                background: #fff;
                overflow: hidden;
            }
            /* iframe 内节点容器取消自动居中；不改变标题自己的对齐样式。 */
            body[data-report-frame-document] #report :is(div, section, article, main, figure) {
                margin-left: 0 !important;
                margin-right: 0 !important;
                text-align: left;
                justify-content: flex-start; /* flex 主轴起点：默认横向布局时靠左。 */
                align-items: flex-start; /* flex 交叉轴起点：竖向布局时靠左。 */
            }
            """;

    /** 根据页面类型填充模式参数；每个属性仍只在 REPORT_CSS 中定义一次。 */
    private String reportCss(boolean includeToc) {
        return REPORT_CSS
                .replace("@@HEIGHT@@", includeToc ? "100vh" : "auto")
                .replace("@@OVERFLOW@@", includeToc ? "auto" : "visible")
                .replace("@@OFFSET@@", includeToc
                        ? "calc(var(--report-toc-width) + var(--report-toc-gap))" : "0px")
                .replace("@@PADDING@@", includeToc ? "0px" : "var(--report-frame-padding)");
    }

    private static final Logger log = LoggerFactory.getLogger(HtmlReportRenderer.class);
    private static final ObjectMapper JSON = new ObjectMapper();

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
    private static final Pattern HTML_CODE_BLOCK =
            Pattern.compile("```html?\\s*\\n([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);

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

    // ── 完整 HTML 文档检测/抽取（AI 偶尔直出 <html>...<body>...</body></html>）──────────
    private static final Pattern BODY_OPEN_FULL = Pattern.compile("<body\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern BODY_CLOSE = Pattern.compile("</body\\s*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLE_BLOCK = Pattern.compile("<style\\b[^>]*>[\\s\\S]*?</style>", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCRIPT_BLOCK = Pattern.compile("<script\\b[^>]*>[\\s\\S]*?</script>", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCRIPT_OPEN = Pattern.compile("<script\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCRIPT_CLOSE = Pattern.compile("</script\\s*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern TABLE_ELEMENT = Pattern.compile("<table\\b[^>]*>[\\s\\S]*?</table\\s*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMPLETE_HTML_START =
            Pattern.compile("<!doctype\\b[^>]*>|<html\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_CLOSE = Pattern.compile("</html\\s*>", Pattern.CASE_INSENSITIVE);
    /** AI 常见的 HTML 片段（没有外层 html/body 文档壳）。 */
    private static final Pattern HTML_FRAGMENT = Pattern.compile(
            "<\\s*(?:div|section|article|main|table|svg|h[1-6])\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern WHOLE_MARKDOWN_FENCE = Pattern.compile(
            "^\\s*```[^\\r\\n]*\\R([\\s\\S]*?)\\R?```\\s*$", Pattern.CASE_INSENSITIVE);
    /** AI 偶发输出缺失左尖括号的文档壳残片（例如 body> / <html>），不得作为正文显示。 */
    private static final Pattern DOCUMENT_SHELL_FRAGMENT = Pattern.compile(
            "(?im)(?<![a-z0-9])/?(?:html|head|body)\\s*>|<\\s*/?\\s*(?:html|head|body)\\s*>");

    /**
     * 折线图颜色归一化脚本：统一每条 line series 的 lineStyle.color 与 itemStyle.color，
     * 并把 legend 显式 itemStyle.color 对齐到系列色，避免图例图标（线段）与数据点圆圈颜色不一致。
     * AI 产出的 echarts option 常出现 lineStyle/itemStyle 颜色不一致导致图例与圆圈撞色，此处兜底修正。
     */
    private static final String CHART_NORMALIZE_JS = """
            function normalizeChartOption(opt,chartWidth){
              if(!opt||typeof opt!=='object') return opt;
              // ECharts 的 series 可以是单个对象，也可以是数组，先统一成数组。
              if(opt.series&&!Array.isArray(opt.series)){ opt.series=[opt.series]; }
              if(!opt.series||!opt.series.length) return opt;
              // JSON 无法携带可执行 formatter；模型输出的函数字符串会被 ECharts 当作文字显示。
              if(opt.tooltip&&typeof opt.tooltip.formatter==='string'&&/^\\s*(?:async\\s+)?function\\b|^\\s*\\(?\\s*[\\w,\\s]*\\)?\\s*=>/.test(opt.tooltip.formatter)){
                delete opt.tooltip.formatter;
              }
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
              var zoomable=false,hasBar=false;
              for(var k=0;k<opt.series.length;k++){
                var series=opt.series[k];
                if(series&&(series.type==='line'||series.type==='bar')){ zoomable=true; }
                if(series&&series.type==='bar'){ hasBar=true; }
              }
              if(!opt.dataZoom){
                if(zoomable&&opt.xAxis&&opt.yAxis){
                  opt.dataZoom=[{type:'inside',xAxisIndex:0,filterMode:'filter'}];
                }
              }
              if(hasBar&&opt.xAxis&&opt.yAxis){
                if(!opt.grid){ opt.grid={}; }
                var grids=Array.isArray(opt.grid)?opt.grid:[opt.grid];
                for(var g=0;g<grids.length;g++){
                  var grid=grids[g]; if(!grid) continue;
                  if(grid.containLabel===undefined){ grid.containLabel=true; }
                  if(grid.bottom===undefined){ grid.bottom=72; }
                }
                var xAxes=Array.isArray(opt.xAxis)?opt.xAxis:[opt.xAxis];
                for(var a=0;a<xAxes.length;a++){
                  var axis=xAxes[a]; if(!axis||axis.type!=='category') continue;
                  if(!axis.axisLabel){ axis.axisLabel={}; }
                  var axisLabel=axis.axisLabel;
                  if(axisLabel.interval===undefined){ axisLabel.interval=0; }
                  var categories=Array.isArray(axis.data)?axis.data:[];
                  var maxCategoryLength=0;
                  for(var c=0;c<categories.length;c++){
                    maxCategoryLength=Math.max(maxCategoryLength,String(categories[c]??'').length);
                  }
                  if(axisLabel.rotate===undefined||axisLabel.__reportAutoRotate){
                    var narrow=chartWidth&&chartWidth<640;
                    axisLabel.rotate=(narrow||maxCategoryLength>8)?45:0;
                    if(!axisLabel.__reportAutoRotate){
                      Object.defineProperty(axisLabel,'__reportAutoRotate',{value:true,writable:true,enumerable:false});
                    }
                  }
                }
              }
              // 坐标轴图使用百分比留白，图表容器宽高保持不变。
              // 多绘图区可能是左右分栏，不统一覆盖它们的位置，避免重叠。
              if(zoomable&&opt.xAxis&&opt.yAxis){
                if(!opt.grid){ opt.grid={}; }
                var layoutGrids=Array.isArray(opt.grid)?opt.grid:[opt.grid];
                if(layoutGrids.length===1&&layoutGrids[0]&&typeof layoutGrids[0]==='object'){
                  var layoutGrid=layoutGrids[0];
                  layoutGrid.left='2%'; // 左侧留白随图表宽度自适应。
                  layoutGrid.right='2%'; // 右侧留白，避免裁切边缘内容。
                  layoutGrid.containLabel=true; // 为坐标轴文字预留空间。
                  delete layoutGrid.width; // 宽度由左右边距计算，避免与固定 width 冲突。
                }
              }
              return opt;
            }
            """;

    /** 图表初始化与错误提示集中在这里，单张图表失败不会中断后面的图表。 */
    private static final String CHART_RENDER_JS = """
            // 柱宽修改入口：原始柱宽乘以 2/3，最终不超过 24 像素。
            var REPORT_BAR_WIDTH_RATIO = 2 / 3;
            var REPORT_BAR_MAX_WIDTH = 24;

            function applyReportBarWidths(chart, option) {
                var series = option.series || [];
                var baseline = [];
                var hasBar = false;
                for (var i = 0; i < series.length; i++) {
                    var item = series[i];
                    if (item && item.type === 'bar') {
                        hasBar = true;
                        // 每次恢复节点的原始宽度配置，让 ECharts 重新计算自动柱宽。
                        // null 清除上次缩窄后的设置，避免 resize 时越缩越细。
                        baseline.push({
                            barWidth: item.barWidth == null ? null : item.barWidth,
                            barMinWidth: item.barMinWidth == null ? null : item.barMinWidth,
                            barMaxWidth: item.barMaxWidth == null ? null : item.barMaxWidth
                        });
                    } else {
                        baseline.push({});
                    }
                }
                if (!hasBar) return;
                chart.setOption({series: baseline});

                var widths = [];
                for (var j = 0; j < series.length; j++) {
                    var model = chart.getModel().getSeriesByIndex(j);
                    // size 是 ECharts 实际计算的柱宽，兼容自动宽度、百分比和横向柱状图。
                    var originalWidth = model && model.getData().getLayout('size');
                    if (series[j] && series[j].type === 'bar'
                            && Number.isFinite(originalWidth) && originalWidth > 0) {
                        widths.push({
                            barWidth: Math.min(originalWidth * REPORT_BAR_WIDTH_RATIO, REPORT_BAR_MAX_WIDTH),
                            barMaxWidth: REPORT_BAR_MAX_WIDTH,
                            barMinWidth: 0 // 避免原来的最小柱宽抵消缩窄效果。
                        });
                    } else {
                        widths.push({});
                    }
                }
                chart.setOption({series: widths});
            }

            function showReportChartError(el, error) {
                console.error('报告图表渲染失败：' + el.id, error);
                var panel = el.querySelector('[data-report-chart-error]');
                if (!panel) {
                    panel = document.createElement('details');
                    panel.setAttribute('data-report-chart-error', '');
                    var summary = document.createElement('summary');
                    summary.textContent = '图表渲染失败，点击查看错误';
                    panel.appendChild(summary);
                    panel.appendChild(document.createElement('pre'));
                    el.appendChild(panel);
                }
                // 使用 textContent，错误信息只作为文字显示，不执行其中的 HTML。
                panel.querySelector('pre').textContent = error && error.message || String(error);
            }

            function renderReportCharts(charts) {
                function start() {
                    var entries = [];
                    for (var i = 0; i < charts.length; i++) {
                        var el = document.getElementById(charts[i].id);
                        if (!el) continue;
                        var chart = null;
                        try {
                            // 每张图单独解析 JSON：无效配置不会导致整段脚本语法错误。
                            var option = JSON.parse(charts[i].json);
                            if (!option || typeof option !== 'object' || Array.isArray(option)) {
                                throw new Error('图表配置必须是 JSON 对象');
                            }
                            var height = parseInt(option.chartHeight, 10);
                            if (height > 100) el.style.height = height + 'px';
                            delete option.chartHeight;
                            option = normalizeChartOption(option, el.clientWidth);
                            chart = echarts.init(el);
                            chart.setOption(option);
                            applyReportBarWidths(chart, option);
                            entries.push({chart: chart, option: option, el: el});
                        } catch (error) {
                            if (chart) chart.dispose();
                            showReportChartError(el, error);
                        }
                    }
                    function resizeCharts() {
                        for (var j = 0; j < entries.length; j++) {
                            var entry = entries[j];
                            if (entry.chart.isDisposed()) continue;
                            try {
                                // 先改变画布尺寸，再更新依赖宽度的标签布局。
                                entry.chart.resize();
                                entry.chart.setOption(normalizeChartOption(entry.option, entry.el.clientWidth));
                                applyReportBarWidths(entry.chart, entry.option);
                            } catch (error) {
                                showReportChartError(entry.el, error);
                            }
                        }
                    }
                    var buttons = document.querySelectorAll('[data-chart-fullscreen]');
                    for (var b = 0; b < buttons.length; b++) {
                        buttons[b].addEventListener('click', function () {
                            var shell = document.getElementById(this.getAttribute('data-chart-fullscreen'));
                            if (document.fullscreenElement === shell) {
                                document.exitFullscreen();
                            } else if (shell && shell.requestFullscreen) {
                                shell.requestFullscreen();
                            }
                        });
                    }
                    window.addEventListener('resize', resizeCharts);
                    document.addEventListener('fullscreenchange', function () {
                        setTimeout(resizeCharts, 0);
                    });
                }
                if (document.readyState === 'complete') start();
                else window.addEventListener('load', start);
            }
            """;

    /**
     * 保留 AI 子报告自带样式时过滤阴影：AI 直出的 HTML 常给表格/卡片容器加 box-shadow，
     * 汇总页面里这类阴影被用户视为多余装饰，注入前统一剔除（不影响其余样式）。
     */
    private static final Pattern BOX_SHADOW_DECL =
            Pattern.compile("(?i)(?<![\\w-])(?:-webkit-)?box-shadow\\s*:[^;}]*;?");

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

        if (containsEmbeddedCompleteHtml(md)) {
            return renderMixedContent(md, safeTitle);
        }

        // AI 偶尔把完整 HTML 包在 Markdown 围栏、JSON 字符串或生成器说明文字中。
        // 原样渲染，不走 markdown 转换；<head> 的 <style> 保留注入，<script> 移除防注入
        String completeHtml = extractCompleteHtml(md);
        if (completeHtml != null) {
            return renderCompleteHtml(completeHtml, safeTitle, title != null);
        }

        // 1. 按图表块切分（```echarts 围栏 + <echart>/<echarts> 标签两种形式）：
        //    块间片段转 HTML，块位置插入图表占位 div
        // 汇总节点会把原完整 HTML 的 <style> 保留在片段中；样式必须移到 head，
        // 否则 Markdown 段落转换会把 CSS 行包成 <p>，导致样式失效并把 CSS 显示出来。
        StringBuilder fragmentStyles = new StringBuilder();
        Matcher fragmentStyle = STYLE_BLOCK.matcher(md);
        while (fragmentStyle.find()) {
            fragmentStyles.append(fragmentStyle.group());
        }
        md = fragmentStyle.replaceAll("");
        StringBuilder body = new StringBuilder();
        List<ChartBlock> charts = splitCharts(md, body, true);
        // 节点结果常返回 HTML 片段而不是完整 HTML 文档；这条路径同样需要
        // 与完整文档一致的全屏入口，否则用户只能看到内容而无法放大。
        if (fullscreenEnabled() && HTML_FRAGMENT.matcher(md).find()) {
            String renderedBody = body.toString();
            body.setLength(0);
            body.append("<button type=\"button\" title=\"全屏查看\" aria-label=\"全屏查看\" data-html-fullscreen=\"report\">&#x26F6;</button>")
                    .append(renderedBody).append("\n");
        }

        return assembleHtml(safeTitle, body.toString(), charts, fragmentStyles.toString());
    }

    public String renderWithoutFullscreen(String markdown, String title) {
        boolean previous = fullscreenDisabled.get();
        fullscreenDisabled.set(true);
        try { return render(markdown, title); }
        finally { fullscreenDisabled.set(previous); }
    }

    private boolean fullscreenEnabled() { return !Boolean.TRUE.equals(fullscreenDisabled.get()); }

    /**
     * 渲染 Markdown 与一份或多份完整 HTML 文档混排的内容:
     * Markdown 片段照常转换;每份完整 HTML 文档各自渲染成独立子文档后以自适应高度 iframe 嵌入——
     * 各文档的样式/脚本隔离在各自 iframe 内,避免多份 HTML 拼接到同一页面时样式互相覆盖导致图表被挤压。
     */
    private String renderMixedContent(String input, String safeTitle) {
        StringBuilder body = new StringBuilder();
        List<ChartBlock> charts = new ArrayList<>();
        int frameIndex = 0;
        int cursor = 0;
        Matcher starts = COMPLETE_HTML_START.matcher(input);
        while (starts.find(cursor)) {
            int start = starts.start();
            CompleteHtmlPart part = extractCompleteHtmlPart(input, start);
            if (part == null) {
                cursor = starts.end();  // 伪起始标签：跳过避免 find(cursor) 重复命中同一位置死循环
                continue;
            }

            appendMarkdownPart(input.substring(cursor, start), body, charts);
            appendFramePart(part.html(), frameIndex++, body);
            cursor = part.end();
        }
        appendMarkdownPart(input.substring(cursor), body, charts);
        return assembleHtml(safeTitle, body.toString(), charts, "");
    }

    private boolean containsEmbeddedCompleteHtml(String input) {
        Matcher starts = COMPLETE_HTML_START.matcher(input);
        if (!starts.find()) return false;
        CompleteHtmlPart part = extractCompleteHtmlPart(input, starts.start());
        if (part == null) return false;
        // 文档前后仅空白（AI 输出常见尾随换行）不算混排，仍走完整文档渲染保持自然高度
        boolean prefixHasContent = !input.substring(0, starts.start()).isBlank();
        boolean suffixHasContent = !input.substring(part.end()).isBlank();
        return prefixHasContent || suffixHasContent;
    }

    private CompleteHtmlPart extractCompleteHtmlPart(String input, int start) {
        Matcher body = BODY_OPEN_FULL.matcher(input);
        if (!body.find(start)) return null;
        Matcher close = HTML_CLOSE.matcher(input);
        if (!close.find(body.end())) return null;
        return new CompleteHtmlPart(input.substring(start, close.end()).trim(), close.end());
    }

    private void appendMarkdownPart(String part, StringBuilder body, List<ChartBlock> charts) {
        if (part == null || part.isBlank()) return;
        // 完整 HTML 文档有时仍被工具包在 ```html 围栏中；完整文档会单独进入 iframe，
        // 围栏标记不能作为 Markdown 文本残留在 iframe 之前。
        part = part.replaceAll("(?i)(?m)^\\s*```html?\\s*$", "");
        splitCharts(part, body, true, charts);
    }

    /** 把一份完整 HTML 文档渲染成独立子文档并以固定尺寸 iframe 嵌入(srcdoc 保持报告自包含)。 */
    private void appendFramePart(String html, int index, StringBuilder body) {
        // 单份完整文档走 renderCompleteHtml:剥离脚本防注入、保留原样式(剔除阴影)、转换 ```echarts 块为图表
        String frameDoc = render(html, null);
        // allowfullscreen:子文档内图表的 ⛶ 全屏按钮需要 iframe 显式授权,否则 requestFullscreen 被浏览器拦截
        body.append("<div data-report-frame-shell>")
                .append("<iframe data-report-frame title=\"节点报告\" allowfullscreen allow=\"fullscreen\"")
                .append(" srcdoc=\"").append(escapeSrcdoc(frameDoc)).append("\"></iframe></div>\n");
    }

    /** iframe srcdoc 属性转义:先转 & 再转 < > "(属性值内的实体由浏览器解析还原后交给 iframe)。 */
    private static String escapeSrcdoc(String html) {
        return html.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private String sanitizeCompleteBody(String bodyContent) {
        String sanitized = SCRIPT_BLOCK.matcher(bodyContent).replaceAll("");
        sanitized = SCRIPT_OPEN.matcher(sanitized).replaceAll("");
        sanitized = SCRIPT_CLOSE.matcher(sanitized).replaceAll("");
        return STYLE_BLOCK.matcher(sanitized).replaceAll("");
    }

    private record CompleteHtmlPart(String html, int end) {}

    /** 从围栏、JSON 文本值或带前置说明的输入中提取一份结构完整的 HTML 文档。 */
    private String extractCompleteHtml(String input) {
        if (input == null || input.isBlank()) return null;
        String candidate = input.charAt(0) == '\ufeff' ? input.substring(1) : input;
        Matcher fence = WHOLE_MARKDOWN_FENCE.matcher(candidate);
        if (fence.matches()) candidate = fence.group(1).trim();

        String trimmed = candidate.stripLeading();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                String fromJson = findCompleteHtmlInJson(JSON.readTree(trimmed));
                if (fromJson != null) return fromJson;
            } catch (Exception ignored) {
                // 不是合法 JSON 时继续按普通文本检查，最终仍可回退 Markdown 渲染。
            }
        }
        return findCompleteHtmlDocument(candidate);
    }

    private String findCompleteHtmlInJson(JsonNode node) {
        if (node == null) return null;
        if (node.isTextual()) return findCompleteHtmlDocument(node.textValue());
        if (node.isContainerNode()) {
            for (JsonNode child : node) {
                String found = findCompleteHtmlInJson(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private String findCompleteHtmlDocument(String text) {
        if (text == null) return null;
        Matcher start = COMPLETE_HTML_START.matcher(text);
        while (start.find()) {
            Matcher body = BODY_OPEN_FULL.matcher(text);
            if (!body.find(start.start())) continue;
            Matcher close = HTML_CLOSE.matcher(text);
            int end = close.find(body.end()) ? close.end() : text.length();
            return text.substring(start.start(), end).trim();
        }
        return null;
    }

    /**
     * 渲染 AI 直出的完整 HTML 文档：抽取 {@code <body>} 内嵌 HTML 原样嵌入报告（不走 markdown 转换），
     * 保留 {@code <head>} 中的 {@code <style>} 注入以维持 AI 原样式，移除 {@code <script>} 防注入。
     *
     * <p>body 内若含 {@code ```echarts} 代码块仍渲染成图表；其余原样作为 HTML 嵌入。
     */
    private String renderCompleteHtml(String html, String safeTitle, boolean includeToc) {
        // 收集 <style>...</style>（通常在 head），注入报告保留 AI 样式（剔除 box-shadow 阴影）
        StringBuilder extraStyles = new StringBuilder();
        Matcher sm = STYLE_BLOCK.matcher(html);
        while (sm.find()) {
            extraStyles.append(sm.group());
        }

        // 抽取 <body...>...</body> 内嵌内容；无 <body> 兜底用全文
        String bodyContent = extractBodyInner(html);

        // 移除 <script>（一般没有，兜底防注入）
        bodyContent = sanitizeCompleteBody(bodyContent);
        bodyContent = wrapTablesForHorizontalScroll(bodyContent);

        // 仍扫描图表块（body 内若有 ```echarts 或 <echart> 也渲染成图），其余原样为 HTML
        StringBuilder body = new StringBuilder();
        List<ChartBlock> charts = splitCharts(bodyContent, body, false);
        String renderedBody = body.toString();
        body.setLength(0);
        if (fullscreenEnabled()) body.append("<button type=\"button\" title=\"全屏查看\" aria-label=\"全屏查看\" data-html-fullscreen=\"report\">&#x26F6;</button>");
        body.append(renderedBody).append("\n");

        return assembleHtml(safeTitle, body.toString(), charts, extraStyles.toString(), includeToc, true);
    }

    /** 给完整 HTML 中的表格补横向滚动容器，避免宽表被章节外层裁剪。 */
    private static String wrapTablesForHorizontalScroll(String html) {
        Matcher matcher = TABLE_ELEMENT.matcher(html);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String table = matcher.group();
            String prefix = html.substring(Math.max(0, matcher.start() - 40), matcher.start()).toLowerCase();
            String replacement = (prefix.contains("data-report-table-scroll") || prefix.contains("table-scroll")) ? table : "<div data-report-table-scroll>" + table + "</div>";
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** 抽取 {@code <body...>...</body>} 之间的内嵌 HTML；无 {@code <body>} 时返回原文。 */
    private String extractBodyInner(String html) {
        Matcher open = BODY_OPEN_FULL.matcher(html);
        if (!open.find()) return html;
        int start = open.end();
        Matcher close = BODY_CLOSE.matcher(html);
        int end = close.find(start) ? close.start() : html.length();
        return html.substring(start, end);
    }

    /**
     * 按图表块（```echarts 围栏 + <echart>/<echarts> 标签）切分文本：
     * 块间片段追加到 {@code body}（renderMdLeftovers=true 走 markdown 转换，false 原样为 HTML），
     * 块位置插入图表占位 div，返回各图表块（DOM id + option JSON）。
     */
    private List<ChartBlock> splitCharts(String text, StringBuilder body, boolean renderMdLeftovers) {
        List<ChartBlock> charts = new ArrayList<>();
        splitCharts(text, body, renderMdLeftovers, charts);
        return charts;
    }

    private void splitCharts(String text, StringBuilder body, boolean renderMdLeftovers,
                             List<ChartBlock> charts) {
        Matcher m = CHART_BLOCK.matcher(text);
        int last = 0;
        int idx = charts.size();
        while (m.find()) {
            if (m.start() > last) {
                body.append(renderMdLeftovers
                        ? markdownToHtml(text.substring(last, m.start()))
                        : text.substring(last, m.start()));  // raw HTML，不走 markdown
            }
            // 围栏块取 g1；标签块取 g3 并去掉标签内容里可能残留的 ``` 围栏
            String json = (m.group(1) != null ? m.group(1) : stripFences(m.group(3))).trim();
            String chartId = "echarts-" + idx;
            body.append("<div data-report-chart-shell id=\"").append(chartId).append("-shell\">");
            if (fullscreenEnabled()) body.append("<button type=\"button\" title=\"全屏查看\" aria-label=\"全屏查看\" data-chart-fullscreen=\"").append(chartId).append("-shell\">&#x26F6;</button>");
            body.append("<div data-report-chart id=\"")
                    .append(chartId).append("\"></div></div>\n");
            charts.add(new ChartBlock(chartId, json));
            idx++;
            last = m.end();
        }
        if (last < text.length()) {
            body.append(renderMdLeftovers ? markdownToHtml(text.substring(last)) : text.substring(last));
        }
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

    private String assembleHtml(String title, String body, List<ChartBlock> charts, String extraStyles) {
        return assembleHtml(title, body, charts, extraStyles, true);
    }

    private String assembleHtml(String title, String body, List<ChartBlock> charts, String extraStyles,
                                boolean includeToc) {
        return assembleHtml(title, body, charts, extraStyles, includeToc, false);
    }

    private String assembleHtml(String title, String body, List<ChartBlock> charts, String extraStyles,
                                boolean includeToc, boolean completeHtml) {
        StringBuilder sb = new StringBuilder(1024 + body.length());
        sb.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">");
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
        sb.append("<title>").append(title).append("</title>");
        // 输入节点的样式先放入同一个 style，报告统一样式随后注入。
        // 渲染器自身只有 REPORT_CSS 一个入口，不再追加一层层覆盖规则。
        sb.append("<style>");
        if (extraStyles != null && !extraStyles.isEmpty()) {
            Matcher styles = STYLE_BLOCK.matcher(extraStyles);
            while (styles.find()) {
                String block = styles.group();
                sb.append(block.substring(block.indexOf('>') + 1, block.toLowerCase().lastIndexOf("</style")));
            }
        }
        sb.append(reportCss(includeToc)).append("</style>");
        sb.append("</head><body");
        if (!completeHtml) sb.append(" data-report-markdown");
        if (!includeToc) sb.append(" data-report-frame-document");
        sb.append(">");
        if (includeToc) {
            sb.append("<div id=\"report-toc\" class=\"report-toc\" aria-label=\"报告目录\"><div class=\"report-toc-list\"></div></div>");
        }
        // 同一 HTML 文档中，各 class 只绑定一个 div；子文档有自己的独立 DOM。
        sb.append("<div id=\"report-shell\" class=\"report-shell\"><div id=\"report\" class=\"report\">");
        sb.append(body);
        sb.append("</div></div>");
        // 只测量正文高度；不能把 iframe 视口高度算进去，否则每次测量加 2px 会不断撑高。
        if (includeToc) sb.append("<script>(function(){var frames=document.querySelectorAll('[data-report-frame]');function bind(frame){if(frame.__reportHeightBound)return;frame.__reportHeightBound=true;var attempts=0,observer=null;function fit(){try{var doc=frame.contentDocument;if(!doc)return false;var report=doc.getElementById('report');if(!report)return false;var shell=doc.getElementById('report-shell');var body=doc.body;var height=Math.max(report.scrollHeight,report.getBoundingClientRect().height);if(height>0)frame.style.height=Math.ceil(height+2)+'px';if(!observer&&doc.defaultView&&doc.defaultView.ResizeObserver){observer=new doc.defaultView.ResizeObserver(fit);observer.observe(report);if(shell)observer.observe(shell);if(body)observer.observe(body);}return height>0;}catch(e){console.warn('报告子文档高度测量失败',e);return false;}}function retry(){if(fit()||attempts++>=8)return;setTimeout(retry,50*(attempts+1));}frame.addEventListener('load',function(){attempts=0;retry();try{var doc=frame.contentDocument;if(doc&&doc.defaultView)doc.defaultView.addEventListener('load',retry);}catch(e){console.warn('报告子文档加载监听失败',e);}});retry();}for(var i=0;i<frames.length;i++)bind(frames[i]);window.addEventListener('load',function(){for(var i=0;i<frames.length;i++)bind(frames[i]);});})();</script>");
        if (includeToc) sb.append("<script>(function(){var toc=document.querySelector('.report-toc'),list=toc&&toc.querySelector('.report-toc-list');if(!toc||!list)return;if(list)list.replaceChildren();var heads=document.querySelectorAll('[data-report-outline-heading]');for(var i=0;i<heads.length;i++){var h=heads[i];if(h.classList.contains('report-outline-title')||h.tagName==='H1')continue;var id='report-section-'+i;h.id=id;var a=document.createElement('a');a.href='#'+id;a.dataset.level=h.tagName.substring(1);a.style.paddingLeft=(7+Math.max(0,Number(a.dataset.level)-2)*11)+'px';a.textContent=h.textContent||('章节 '+(i+1));a.title=a.textContent;a.addEventListener('click',function(e){var target=document.getElementById(this.hash.substring(1));if(target){e.preventDefault();target.scrollIntoView({block:'start',behavior:'smooth'});}});list.appendChild(a);}if(!list.children.length)toc.style.display='none';})();</script>");
        // 表格的滚动属性全部由 REPORT_CSS 决定，不再用 JS 写行内样式覆盖。
        sb.append("<script>(function(){var buttons=document.querySelectorAll('[data-html-fullscreen]');for(var i=0;i<buttons.length;i++){buttons[i].addEventListener('click',function(){var shell=document.getElementById(this.getAttribute('data-html-fullscreen'));if(document.fullscreenElement===shell){document.exitFullscreen();}else if(shell&&shell.requestFullscreen){shell.requestFullscreen();}});}})();</script>");
        // 仅在有图表时内联 echarts.min.js；无图省 1.1MB
        if (!charts.isEmpty()) {
            sb.append("<script>").append(echartsJs).append("</script>");
            sb.append("<script>").append(CHART_NORMALIZE_JS).append("</script>");
            sb.append("<script>").append(CHART_RENDER_JS).append("</script>");
            sb.append("<script>renderReportCharts([");
            for (int i = 0; i < charts.size(); i++) {
                if (i > 0) sb.append(',');
                ChartBlock c = charts.get(i);
                sb.append("{id:").append(jsString(c.id)).append(",json:")
                        // 使用字符串传递配置，尖括号转义防止提前结束 script 标签。
                        .append(jsString(c.json).replace("<", "\\u003c")).append("}");
            }
            sb.append("]);</script>");
        }
        sb.append("</body></html>");
        return sb.toString();
    }

    // ── Markdown -> HTML body（移植自前端 Markdown.vue）──────────────────────

    private String markdownToHtml(String md) {
        if (md == null || md.isEmpty()) return "";
        // 普通 Markdown 片段中清理不完整的 HTML 文档壳，避免浏览器修复 DOM 后残片可见。
        md = DOCUMENT_SHELL_FRAGMENT.matcher(md).replaceAll("");
        StringBuilder out = new StringBuilder();
        Matcher htmlBlocks = HTML_CODE_BLOCK.matcher(md);
        int htmlLast = 0;
        boolean hasHtml = false;
        while (htmlBlocks.find()) {
            hasHtml = true;
            if (htmlBlocks.start() > htmlLast) out.append(renderInline(md.substring(htmlLast, htmlBlocks.start())));
            out.append(htmlBlocks.group(1).trim()).append('\n');
            htmlLast = htmlBlocks.end();
        }
        if (hasHtml) {
            if (htmlLast < md.length()) out.append(renderInline(md.substring(htmlLast)));
            return out.toString();
        }
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
                out.append("<div data-report-table-scroll><table data-report-default-table=\"true\"><thead><tr>");
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
                // 属性内实体按 escapeHtml 的逆序还原：先 &quot;(属性引号) 再 &amp;，
                // 否则还原出的标签形如 <h1 class=&quot;x&quot;>，属性值带引号字符，
                // CSS 选择器/属性选择器匹配不上（大纲标题居中失效即此因）。
                String replacement = "<" + slash + tag
                        + attrs.replace("&quot;", "\"").replace("&amp;", "&") + ">";
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





