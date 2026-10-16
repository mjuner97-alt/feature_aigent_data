package com.agentscopea2a.v2.skillManager.notification;

/**
 * 长任务通知邮件正文模板渲染:文字部分由编辑页配置,后端只负责拼接报告链接。
 *
 * <p>模板中的 {@link #LINK_PLACEHOLDER} 占位符替换为
 * {@code <a href="报告地址">任务名(年月日)</a>}(可多次);未写占位符时链接独立成段
 * 追加在正文之后;模板为空时返回调用方给的系统默认文案。用户文字先整体 HTML 转义
 * 再替换占位符——占位符字面量不含需转义字符,两步顺序安全。
 */
public final class NotifyBodyTemplate {
    /** 报告链接占位符:编辑页写入正文,发送时替换为可点击的报告下载链接。 */
    public static final String LINK_PLACEHOLDER = "{链接}";

    private NotifyBodyTemplate() {}

    /** 渲染完整 HTML 文档;模板为空(blank)时原样返回 defaultHtml。 */
    public static String renderHtml(String template, String title, String url, String defaultHtml) {
        if (template == null || template.isBlank()) return defaultHtml;
        String anchor = anchor(title, url);
        String body = escape(template).replace("\r\n", "\n").replace("\n", "<br>");
        if (body.contains(LINK_PLACEHOLDER)) {
            body = body.replace(LINK_PLACEHOLDER, anchor);
        } else {
            // 未写占位符:链接独立成段追加在正文之后,报告地址不可用时提示登录系统查看。
            body += url == null || url.isBlank()
                    ? "<p>报告地址暂不可用，请登录系统查看。</p>"
                    : "<p>" + anchor + "</p>";
        }
        return "<html><body>" + body + "</body></html>";
    }

    /** 链接锚点:报告地址可用时为可点击 <a>;不可用时退化为纯文本标题。 */
    private static String anchor(String title, String url) {
        String text = escape(String.valueOf(title));
        if (url == null || url.isBlank()) return text;
        return "<a href=\"" + escape(url) + "\">" + text + "</a>";
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
