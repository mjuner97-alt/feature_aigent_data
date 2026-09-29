package com.agentscopea2a.v2.skillManager.report;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class ReportSourceValidator {
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    private ReportSourceValidator() {}
    public static void validate(String html) {
        if (html == null || html.isBlank()) throw new IllegalStateException("ReportContentInvalid: HTML 内容不能为空");
        if (html.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new IllegalStateException("ReportContentTooLarge: HTML 内容不能超过 2 MB");
        String normalized = html.toLowerCase(Locale.ROOT);
        if (!normalized.contains("<html") && !normalized.contains("<!doctype html")) throw new IllegalStateException("ReportContentInvalid: 内容必须是完整 HTML 文档");
    }
}
