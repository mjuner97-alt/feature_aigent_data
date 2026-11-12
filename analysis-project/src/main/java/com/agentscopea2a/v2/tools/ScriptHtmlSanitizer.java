package com.agentscopea2a.v2.tools;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * script_exec stdout 的 HTML 净化（统一样式上移 Java 层，
 * 见 docs/202611/脚本表格样式上移hooks层统一方案.md §4.1）。
 *
 * <p>所有脚本产物（chat 工具调用 / 直接问数 executeStructured / skill-flow
 * executeForFlow / 调试页 executeForDebug）的唯一必经点，不依赖 hooks 与 LLM：</p>
 * <ul>
 *   <li><b>剥</b>：```html 围栏块/完整文档内的 {@code <style>}/{@code <script>} 全部剥除——
 *       脚本不再自带样式；长任务报告由 HtmlReportRenderer 的 REPORT_CSS 唯一上色，
 *       chat 由 scriptTableCss 在展示边界注入，两宿主同源不再漂移；</li>
 *   <li><b>包</b>：含 {@code <table>} 的块包
 *       {@code <div class="unified-table"><div class="table-wrap">} 壳，作为
 *       scriptTableCss 的样式挂靠点（render_html_custom 透传后 Python 不再包壳）。</li>
 * </ul>
 *
 * <p>调用点在 {@code extractDownloads} 之后、{@code formatResult} 之前——
 * 下载块/json/markdown/exit 信封不经过本类。全部变换幂等，重复调用结果不变。</p>
 */
public final class ScriptHtmlSanitizer {

    /** ```html 围栏块（保留围栏标记本身，只净化内部内容）。 */
    private static final Pattern HTML_FENCE = Pattern.compile(
            "(?i)(```\\s*html?\\s*\\n)([\\s\\S]*?)(\\n?```)");
    /** 裸完整文档（q2_1 §4.5 独占 stdout 模式）。 */
    private static final Pattern COMPLETE_DOC = Pattern.compile(
            "(?i)<!doctype\\s+html\\b[\\s\\S]*?</html\\s*>");
    private static final Pattern STYLE_BLOCK = Pattern.compile(
            "<style\\b[^>]*>[\\s\\S]*?</style\\s*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLE_TAG = Pattern.compile(
            "</?style\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCRIPT_BLOCK = Pattern.compile(
            "<script\\b[^>]*>[\\s\\S]*?</script\\s*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCRIPT_TAG = Pattern.compile(
            "</?script\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern BODY_OPEN = Pattern.compile("(?i)<body\\b[^>]*>");
    private static final Pattern BODY_CLOSE = Pattern.compile("(?i)</body\\s*>");
    /** 脚本自带 wrapper 的 id="report"（_html_table 旧版外壳）：report 是 HtmlReportRenderer
     *  保留名，串进长任务会形成嵌套重复 id；样式已剥，外壳只剩挂靠点职责，统一改名为
     *  unified-table class。 */
    private static final Pattern ID_REPORT = Pattern.compile("\\sid=[\"']report[\"']");

    /** scriptTableCss 的样式挂靠点：report 侧无规则指向这两个 class，壳在报告里是透明空壳。 */
    static final String WRAPPER_OPEN = "<div class=\"unified-table\">\n<div class=\"table-wrap\">\n";
    static final String WRAPPER_CLOSE = "\n</div>\n</div>";

    private ScriptHtmlSanitizer() {
    }

    public static String sanitize(String stdout) {
        if (stdout == null || stdout.isBlank()) return stdout;
        return sanitizeBareDoc(sanitizeFences(stdout));
    }

    /** 净化 ```html 围栏块内部，围栏标记原样保留（前端按围栏路由到片段 iframe）。 */
    private static String sanitizeFences(String stdout) {
        Matcher fence = HTML_FENCE.matcher(stdout);
        if (!fence.find()) return stdout;
        StringBuffer out = new StringBuffer(stdout.length());
        do {
            String cleaned = stripStyleScript(fence.group(2));
            cleaned = wrapFragment(cleaned);
            fence.appendReplacement(out, Matcher.quoteReplacement(
                    fence.group(1) + cleaned + fence.group(3)));
        } while (fence.find());
        fence.appendTail(out);
        return out.toString();
    }

    /** 净化裸完整文档（独占 stdout 模式）：剥样式脚本 + body 内容包壳。 */
    private static String sanitizeBareDoc(String stdout) {
        Matcher doc = COMPLETE_DOC.matcher(stdout);
        if (!doc.find()) return stdout;
        String cleaned = stripStyleScript(doc.group());
        String wrapped = wrapCompleteDocBody(cleaned);
        if (wrapped.equals(cleaned)) return stdout;
        return new StringBuilder(stdout.length() + 128)
                .append(stdout, 0, doc.start()).append(wrapped).append(stdout, doc.end(), stdout.length())
                .toString();
    }

    static String stripStyleScript(String html) {
        String cleaned = STYLE_BLOCK.matcher(html).replaceAll("");
        cleaned = STYLE_TAG.matcher(cleaned).replaceAll("");
        cleaned = SCRIPT_BLOCK.matcher(cleaned).replaceAll("");
        return SCRIPT_TAG.matcher(cleaned).replaceAll("");
    }

    /** 片段包壳：id="report" 外壳改名 unified-table；裸表格补全套壳；echarts/纯文本块原样返回。 */
    static String wrapFragment(String fragment) {
        String normalized = ID_REPORT.matcher(fragment).replaceAll(" class=\"unified-table\"");
        String lower = normalized.toLowerCase();
        if (lower.contains("unified-table") || !lower.contains("<table")) return normalized;
        return WRAPPER_OPEN + normalized.strip() + WRAPPER_CLOSE;
    }

    /** 完整文档包壳：id="report" 改名 + &lt;body&gt; 内容无壳时补壳（无 body 或已包壳时不动）。 */
    static String wrapCompleteDocBody(String doc) {
        String normalized = ID_REPORT.matcher(doc).replaceAll(" class=\"unified-table\"");
        String lower = normalized.toLowerCase();
        if (lower.contains("unified-table") || !lower.contains("<table")) return normalized;
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
