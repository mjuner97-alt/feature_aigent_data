package com.agentscopea2a.v2.skillManager.report;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 长任务专用报告渲染器。
 *
 * <p>汇总前保留每个节点的内容边界，节点独立进入 iframe，避免片段样式或残缺 HTML
 * 影响其他节点与大纲；Markdown、表格、ECharts 和安全清理逻辑复用 HtmlReportRenderer。
 */
@Component
public class LongTaskReportRenderer {
    private static final Pattern HTML_DOCUMENT = Pattern.compile(
            "(?is)<!doctype\\s+html\\b[\\s\\S]*?</html\\s*>|<html\\b[\\s\\S]*?</html\\s*>");
    private static final Pattern BODY = Pattern.compile("(?is)<body\\b[^>]*>([\\s\\S]*?)</body\\s*>");
    private static final Pattern STYLE = Pattern.compile("(?is)<style\\b[^>]*>([\\s\\S]*?)</style\\s*>");
    /** 兼容节点 HTML 中常见的 style 标签拼写错误，避免 CSS 被当作正文显示。 */
    private static final Pattern STYPE = Pattern.compile("(?is)<stype\\b[^>]*>([\\s\\S]*?)</stype\\s*>");
    private static final Pattern SCRIPT = Pattern.compile("(?is)<script\\b[^>]*>[\\s\\S]*?</script\\s*>");
    private static final Pattern OUTER_LAYOUT = Pattern.compile(
            "(?i)(?:width|min-width|max-width|height|min-height|max-height|margin|padding|background(?:-color)?|box-shadow)\\s*:[^;}]*;?");

    private final HtmlReportRenderer delegate;

    public LongTaskReportRenderer(HtmlReportRenderer delegate) {
        this.delegate = delegate;
    }

    public static String isolateNodeContent(String content) {
        return HtmlReportRenderer.isolateNodeContent(
                STYPE.matcher(content == null ? "" : content).replaceAll("<style>$1</style>"));
    }

    public String render(String content, String title) {
        String normalized = normalizeCompleteHtml(content == null ? "" : content);
        return delegate.render(normalized, title);
    }

    public String renderWithoutFullscreen(String content, String title) {
        String normalized = normalizeCompleteHtml(content == null ? "" : content);
        return delegate.renderWithoutFullscreen(normalized, title);
    }

    private String normalizeCompleteHtml(String input) {
        // 保留每个完整 HTML 文档的边界，让 HtmlReportRenderer 走 iframe 隔离路径。
        // 不能先把多个文档压成一个 body，否则节点 CSS 会互相覆盖。
        return STYPE.matcher(input).replaceAll("<style>$1</style>");
    }

    private String normalizeStyles(String css) {
        String normalized = css.replaceAll("(?i)\\bhtml\\b|\\bbody\\b", ".long-task-node");
        Matcher rootRule = Pattern.compile("(?is)(\\.long-task-node\\s*\\{)([^}]*)\\}").matcher(normalized);
        StringBuffer out = new StringBuffer();
        while (rootRule.find()) {
            String declarations = OUTER_LAYOUT.matcher(rootRule.group(2)).replaceAll("");
            rootRule.appendReplacement(out, Matcher.quoteReplacement(rootRule.group(1) + declarations + "}"));
        }
        rootRule.appendTail(out);
        return out.toString();
    }
}
