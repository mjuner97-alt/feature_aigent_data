package com.agentscopea2a.v2.tools;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * script_exec stdout 的 HTML 净化 v3（统一样式上移 Java 层 + 保留脚本个性化，
 * 见 docs/202611/脚本表格样式上移hooks层统一方案.md §4.1 与 v3 修订记录）。
 *
 * <p>所有脚本产物（chat 工具调用 / 直接问数 executeStructured / skill-flow
 * executeForFlow / 调试页 executeForDebug）的唯一必经点，不依赖 hooks 与 LLM，
 * 覆盖三类 HTML 形态（v3.2 起补齐裸片段）：</p>
 * <ul>
 *   <li><b>剥 script</b>：{@code <script>} 仍全部剥除（安全边界不变）；</li>
 *   <li><b>作用域重写（v2 全剥 → v3 重写）</b>：脚本 {@code <style>} <b>保留</b>，
 *       选择器重写到 {@code .unified-table} 作用域——{@code #report}（HtmlReportRenderer
 *       保留名，撞名即污染长任务）改挂统一壳；html/body/:root 也改挂统一壳；
 *       其余裸选择器加前缀。脚本个性化（自定义类、加粗、表头自适应、标红）
 *       随选择器一起进长任务 head，只命中自带 wrapper，宿主样式不受污染；</li>
 *   <li><b>兜底皮肤</b>：块含 {@code <table>} 且未带统一皮肤（rgb(192,0,0) 红头标记）
 *       时注入 BASE_TABLE_CSS；自带皮肤的脚本不注入，个性化与皮肤按 CSS 特异性分层；</li>
 *   <li><b>包壳</b>：含 {@code <table>} 的块补
 *       {@code <div class="unified-table"><div class="table-wrap">} 壳作为挂靠点
 *       （render_html_custom 透传后 Python 不再包壳）。</li>
 * </ul>
 *
 * <p>三类形态 = ```html 围栏块 / &lt;!DOCTYPE&gt; 完整文档 / 裸片段（json 信封 +
 * style + div + 裸 to_html 表，v3.2 新增 pass）。调用点在 {@code extractDownloads}
 * 之后、{@code formatResult} 之前——下载块/json/markdown/exit 信封不经过本类。
 * 全部变换幂等，重复调用结果不变。</p>
 */
public final class ScriptHtmlSanitizer {

    /** ```html 围栏块（保留围栏标记本身，只净化内部内容）。 */
    private static final Pattern HTML_FENCE = Pattern.compile(
            "(?i)(```\\s*html?\\s*\\n)([\\s\\S]*?)(\\n?```)");
    /** 裸完整文档（q2_1 §4.5 独占 stdout 模式）。 */
    private static final Pattern COMPLETE_DOC = Pattern.compile(
            "(?i)<!doctype\\s+html\\b[\\s\\S]*?</html\\s*>");
    private static final Pattern STYLE_BLOCK = Pattern.compile(
            "(<style\\b[^>]*>)([\\s\\S]*?)(</style\\s*>)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCRIPT_BLOCK = Pattern.compile(
            "<script\\b[^>]*>[\\s\\S]*?</script\\s*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCRIPT_TAG = Pattern.compile(
            "</?script\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern BODY_OPEN = Pattern.compile("(?i)<body\\b[^>]*>");
    private static final Pattern BODY_CLOSE = Pattern.compile("(?i)</body\\s*>");
    private static final Pattern HEAD_CLOSE = Pattern.compile("(?i)</head\\s*>");
    /** 脚本自带 wrapper 的 id="report"（_html_table 旧版外壳）：report 是 HtmlReportRenderer
     *  保留名，串进长任务会形成嵌套重复 id；样式已重写作用域，外壳统一改名 unified-table。 */
    private static final Pattern ID_REPORT = Pattern.compile("\\sid=[\"']report[\"']");
    /** 裸片段起点（非 ```html 围栏、非完整文档的散装 HTML，如 json 信封+style+div+裸 to_html 表）。 */
    private static final Pattern BARE_REGION_START = Pattern.compile(
            "(?i)<(style|table|div)\\b");
    /** 裸片段终点：围栏标记或下一个完整文档开头，二者截断防越界包裹。 */
    private static final Pattern BARE_REGION_STOP = Pattern.compile(
            "(?i)```|<!doctype");
    /** 统一壳标记：用于判断"已包壳"。不能全文搜 unified-table——v3 起脚本 CSS
     *  经作用域重写后到处都是 .unified-table 选择器文本。 */
    private static final Pattern WRAPPER_MARK = Pattern.compile(
            "(?i)class=[\"']unified-table[\"']");
    private static final Pattern CSS_COMMENT = Pattern.compile("/\\*[\\s\\S]*?\\*/");
    /** 平面 CSS 规则（选择器组 + 声明体）；@media 等含嵌套时内层规则仍能命中、外层头不动。 */
    private static final Pattern CSS_RULE = Pattern.compile("([^{}]+)\\{([^{}]*)\\}");
    /** 文档级选择器：脚本指望它管全局字体/背景，改挂统一壳才生效。 */
    private static final Pattern ROOTISH = Pattern.compile("^(?i)(html|body|:root)$");
    /** @keyframes 步进选择器，加前缀会破坏动画，原样保留。 */
    private static final Pattern KEYFRAME_STEP = Pattern.compile(
            "^(?i)(from|to|\\d+(?:\\.\\d+)?%)$");
    /** 双重前缀塌缩：#report 替换产物已有统一壳时不能再加前缀（内网 wenshu_chadui_all
     *  bug：.unified-table .unified-table th 匹配不到单层壳 → 皮肤全失效）；
     *  同时自愈已产出的历史脏 stdout（幂等重放即修复）。 */
    private static final Pattern DOUBLED_SCOPE = Pattern.compile(
            "(?i)(\\.unified-table[\\s>+~]+)+\\.unified-table");
    /** 统一皮肤识别标记（_html_table 红头表色）：自带皮肤的脚本不再注入兜底。 */
    static final Pattern SKIN_MARK = Pattern.compile(
            "rgb\\(\\s*192\\s*,\\s*0\\s*,\\s*0\\s*\\)");

    /** scriptTableCss 的样式挂靠点：report 侧无规则指向这两个 class，壳在报告里是透明空壳。 */
    static final String WRAPPER_OPEN = "<div class=\"unified-table\">\n<div class=\"table-wrap\">\n";
    static final String WRAPPER_CLOSE = "\n</div>\n</div>";

    /**
     * 兜底表格皮肤（取自 workspace/scripts/_html_table.py ab85a19 版 CSS，
     * 供"脚本只出裸表不带样式"的场景兜底；经 normalizeCss 归一，保证与
     * scopeCss 重写幂等兼容）。td 的 color:#333 不带 !important——
     * 保留业务数据标红等个性标记；td padding 留空给脚本个性化。
     */
    private static final String BASE_CSS_RAW = """
            .unified-table { box-sizing: border-box; }
            .unified-table * { box-sizing: border-box; }
            .unified-table { font-family: "Microsoft YaHei", "PingFang SC", sans-serif; color:#333; }
            .unified-table .table-wrap { overflow-x:auto; width:100%; }
            .unified-table table {
              border-collapse: collapse !important; background:#ffffff !important;
              width: max-content !important; min-width: 0 !important;
              max-width:100% !important; margin:10px 0;
            }
            .unified-table th {
              background: rgb(192, 0, 0) !important; color:#fff !important;
              font-weight:600 !important; border:1px solid #bfbfbf !important;
              text-align:center !important;
            }
            .unified-table td {
              color:#333; border:1px solid #bfbfbf !important; text-align:center !important;
              word-break:break-word; overflow-wrap:anywhere;
            }
            .unified-table tr:nth-child(even) td { background:#fafafa !important; }
            .unified-table tr:hover td { background:#fff3e0 !important; }
            .unified-table tr.summary-row td { font-weight:600 !important; background:#f0f0f0 !important; }
            .unified-table td.rowhead { text-align:left !important; }
            .unified-table td.red, .unified-table .red { color:red !important; }
            .unified-table .section-title-sm { font-size:1rem; margin-top:16px; margin-bottom:6px; color:#000; }
            """;
    static final String BASE_TABLE_CSS =
            "<style>" + normalizeCss(BASE_CSS_RAW) + "</style>";

    private ScriptHtmlSanitizer() {
    }

    public static String sanitize(String stdout) {
        if (stdout == null || stdout.isBlank()) return stdout;
        return sanitizeBareFragments(sanitizeBareDoc(sanitizeFences(stdout)));
    }

    /** 净化 ```html 围栏块内部，围栏标记原样保留（前端按围栏路由到片段 iframe）。 */
    private static String sanitizeFences(String stdout) {
        Matcher fence = HTML_FENCE.matcher(stdout);
        if (!fence.find()) return stdout;
        StringBuffer out = new StringBuffer(stdout.length());
        do {
            String cleaned = fence.group(2);
            cleaned = stripScripts(cleaned);
            cleaned = scopeCss(cleaned);
            cleaned = ensureSkin(cleaned, false);
            cleaned = wrapFragment(cleaned);
            fence.appendReplacement(out, Matcher.quoteReplacement(
                    fence.group(1) + cleaned + fence.group(3)));
        } while (fence.find());
        fence.appendTail(out);
        return out.toString();
    }

    /** 净化裸完整文档（独占 stdout 模式）：剥 script + 作用域重写 + 兜底皮肤 + body 包壳。 */
    private static String sanitizeBareDoc(String stdout) {
        Matcher doc = COMPLETE_DOC.matcher(stdout);
        if (!doc.find()) return stdout;
        String cleaned = doc.group();
        cleaned = stripScripts(cleaned);
        cleaned = scopeCss(cleaned);
        cleaned = ensureSkin(cleaned, true);
        cleaned = wrapCompleteDocBody(cleaned);
        if (cleaned.equals(doc.group())) return stdout;
        return new StringBuilder(stdout.length() + 128)
                .append(stdout, 0, doc.start()).append(cleaned)
                .append(stdout, doc.end(), stdout.length())
                .toString();
    }

    /**
     * 裸片段 pass（v3.2）：围栏块/完整文档之外的散装 HTML（json 信封 + style +
     * div + 裸 to_html 表）。区域内必须含 &lt;table&gt; 才处理（无表纯说明片段不动，
     * 个性化 CSS 保持全局生效）；处理与围栏路径同一套变换，对已包壳/已 scope
     * 的内容幂等旁路。下载链接文档（无表格）不满足起点条件，原样保留。
     */
    private static String sanitizeBareFragments(String stdout) {
        Matcher start = BARE_REGION_START.matcher(stdout);
        if (!start.find()) return stdout;
        StringBuffer out = new StringBuffer(stdout.length());
        int pos = 0;
        do {
            int from = start.start();
            if (from < pos) continue;
            Matcher stop = BARE_REGION_STOP.matcher(stdout);
            int end = stop.find(from) ? stop.start() : stdout.length();
            if (end <= from) continue;
            String region = stdout.substring(from, end);
            if (!region.toLowerCase().contains("<table")) continue;
            String cleaned = stripScripts(region);
            cleaned = scopeCss(cleaned);
            cleaned = ensureSkin(cleaned, false);
            cleaned = wrapFragment(cleaned);
            out.append(stdout, pos, from).append(cleaned);
            pos = end;
        } while (start.find());
        out.append(stdout.substring(pos));
        return out.toString();
    }

    static String stripScripts(String html) {
        String cleaned = SCRIPT_BLOCK.matcher(html).replaceAll("");
        return SCRIPT_TAG.matcher(cleaned).replaceAll("");
    }

    /** 作用域重写：只动 {@code <style>} 内的选择器，标签与声明体原样保留。 */
    static String scopeCss(String html) {
        Matcher style = STYLE_BLOCK.matcher(html);
        if (!style.find()) return html;
        StringBuffer out = new StringBuffer(html.length());
        do {
            style.appendReplacement(out, Matcher.quoteReplacement(
                    style.group(1) + rewriteRules(style.group(2)) + style.group(3)));
        } while (style.find());
        style.appendTail(out);
        return out.toString();
    }

    static String rewriteRules(String css) {
        String noComments = CSS_COMMENT.matcher(css).replaceAll("");
        Matcher rule = CSS_RULE.matcher(noComments);
        StringBuffer out = new StringBuffer(noComments.length());
        while (rule.find()) {
            rule.appendReplacement(out, Matcher.quoteReplacement(
                    rewriteSelectorList(rule.group(1)) + "{" + rule.group(2) + "}"));
        }
        rule.appendTail(out);
        return out.toString();
    }

    /** 选择器组按逗号逐个重写（已 scope 的跳过 → 幂等）。 */
    static String rewriteSelectorList(String selectors) {
        String[] parts = selectors.split(",");
        StringBuilder sb = new StringBuilder(selectors.length());
        for (String part : parts) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(rewriteOne(part));
        }
        return sb.toString();
    }

    static String rewriteOne(String selector) {
        String sel = selector.trim();
        if (sel.isEmpty()) return sel;
        String lower = sel.toLowerCase();
        if (sel.startsWith("@")) return sel;
        if (KEYFRAME_STEP.matcher(lower).matches()) return sel;
        if (ROOTISH.matcher(lower).matches()) return ".unified-table";
        // 塌缩须先于幂等守卫: 守卫只看 contains, 会把双重前缀的脏输入原样放行
        sel = DOUBLED_SCOPE.matcher(sel).replaceAll(".unified-table");
        if (lower.contains("unified-table")) return sel;
        sel = sel.replaceAll("(?i)#report\\b", ".unified-table");
        return sel.toLowerCase().contains("unified-table") ? sel : ".unified-table " + sel;
    }

    /**
     * 兜底皮肤注入：块含 {@code <table>} 且未带统一皮肤时注入 BASE_TABLE_CSS
     * （完整文档插到 &lt;/head&gt; 前，其余前置）。已带皮肤的块不动 → 幂等。
     */
    static String ensureSkin(String html, boolean completeDoc) {
        if (!html.toLowerCase().contains("<table") || SKIN_MARK.matcher(html).find()) {
            return html;
        }
        if (completeDoc) {
            Matcher head = HEAD_CLOSE.matcher(html);
            if (head.find()) {
                return html.substring(0, head.start()) + BASE_TABLE_CSS
                        + html.substring(head.start());
            }
        }
        return BASE_TABLE_CSS + html;
    }

    private static String normalizeCss(String css) {
        return rewriteRules(CSS_COMMENT.matcher(css).replaceAll(""));
    }

    /** 片段包壳：id="report" 外壳改名 unified-table；裸表格补全套壳；echarts/纯文本块原样返回。 */
    static String wrapFragment(String fragment) {
        String normalized = ID_REPORT.matcher(fragment).replaceAll(" class=\"unified-table\"");
        if (WRAPPER_MARK.matcher(normalized).find()
                || !normalized.toLowerCase().contains("<table")) {
            return normalized;
        }
        return WRAPPER_OPEN + normalized.strip() + WRAPPER_CLOSE;
    }

    /** 完整文档包壳：id="report" 改名 + &lt;body&gt; 内容无壳时补壳（无 body 或已包壳时不动）。 */
    static String wrapCompleteDocBody(String doc) {
        String normalized = ID_REPORT.matcher(doc).replaceAll(" class=\"unified-table\"");
        if (WRAPPER_MARK.matcher(normalized).find()
                || !normalized.toLowerCase().contains("<table")) {
            return normalized;
        }
        Matcher open = BODY_OPEN.matcher(normalized);
        if (!open.find()) return normalized;
        Matcher close = BODY_CLOSE.matcher(normalized);
        int bodyEnd = close.find(open.end()) ? close.start() : normalized.length();
        return new StringBuilder(normalized.length() + 128)
                .append(normalized, 0, open.end()).append(WRAPPER_OPEN)
                .append(normalized, open.end(), bodyEnd).append(WRAPPER_CLOSE)
                .append(normalized, bodyEnd, normalized.length())
                .toString();
    }
}
