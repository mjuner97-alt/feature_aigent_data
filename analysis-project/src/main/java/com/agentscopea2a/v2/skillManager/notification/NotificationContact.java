package com.agentscopea2a.v2.skillManager.notification;

import java.util.Locale;

/** Validation and HTML rendering for optional per-flow notification contacts. */
public final class NotificationContact {
    private NotificationContact() {}

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
        String contact;
        if (hasCustom(name, phone)) {
            contact = "<p>联系人：" + escape(name.trim()) + "<br>联系方式：" + escape(phone.trim()) + "</p>";
        } else if (trimToNull(name) != null) {
            contact = "<p>联系人：" + escape(name.trim()) + "</p>";
        } else {
            contact = defaultHtml == null ? "" : defaultHtml;
        }
        String html = String.valueOf(content);
        if (contact.isBlank()) return html;
        int bodyEnd = html.toLowerCase(Locale.ROOT).lastIndexOf("</body>");
        return bodyEnd < 0 ? html + contact
                : html.substring(0, bodyEnd) + contact + html.substring(bodyEnd);
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
