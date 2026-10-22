package com.agentscopea2a.v2.skillManager.notification;

import java.util.Locale;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validation and HTML rendering for optional per-flow notification contacts. */
public final class NotificationContact {
    private NotificationContact() {}
    private static final Pattern SPLIT_CONTACT = Pattern.compile(
            "(?is)(<p[^>]*>\\s*联系人：.*?</p>)\\s*(<p[^>]*>\\s*联系方式：.*?</p>)");

    public static String trimToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    public static void validate(String name, String phone) {
        name = trimToNull(name);
        phone = trimToNull(phone);
        if (name == null) throw new IllegalStateException("FlowValidationFailed: 请填写联系人姓名");
        if (phone == null) throw new IllegalStateException("FlowValidationFailed: 请填写联系电话");
        if (phone.length() != 11) throw new IllegalStateException("FlowValidationFailed: 请输入 11 位手机号");
    }

    public static boolean hasCustom(String name, String phone) {
        return trimToNull(name) != null && trimToNull(phone) != null;
    }

    /** Choose exactly one contact block and append before body end, if present. */
    public static String append(String content, String name, String phone, String defaultHtml) {
        return append(content, name, phone, defaultHtml, null);
    }

    public static String append(String content, String name, String phone, String defaultHtml, LocalDate executionDate) {
        String contact;
        if (hasCustom(name, phone)) {
            contact = "<p>联系人：" + escape(name.trim()) + "　联系方式：" + escape(phone.trim()) + "</p>";
        } else if (trimToNull(name) != null) {
            contact = "<p>联系人：" + escape(name.trim()) + "</p>";
        } else {
            contact = defaultHtml == null ? "" : normalizeContactHtml(defaultHtml);
        }
        String html = String.valueOf(content);
        if (executionDate != null) {
            String date = executionDate.minusDays(1).format(DateTimeFormatter.ofPattern("yyyy年MM月dd日"));
            contact = "<p>报告数据截至日期：" + date + "</p>" + contact;
        }
        if (contact.isBlank()) return html;
        int bodyEnd = html.toLowerCase(Locale.ROOT).lastIndexOf("</body>");
        return bodyEnd < 0 ? html + contact
                : html.substring(0, bodyEnd) + contact + html.substring(bodyEnd);
    }

    /** 兼容系统默认联系人模板把姓名和联系方式拆成两个段落的旧配置。 */
    private static String normalizeContactHtml(String html) {
        Matcher matcher = SPLIT_CONTACT.matcher(html);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String first = matcher.group(1).replaceFirst("(?is)</p>\\s*$", "");
            String second = matcher.group(2).replaceFirst("(?is)^<p[^>]*>\\s*", "")
                    .replaceFirst("(?is)</p>\\s*$", "");
            matcher.appendReplacement(result, Matcher.quoteReplacement(first + "　" + second + "</p>"));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
