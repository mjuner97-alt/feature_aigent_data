package com.agentscopea2a.v2.skillManager.report;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class ReportSourceValidator {
    // 报告可能内嵌图表脚本及多个子报告，允许保存最大 10 MB 的 UTF-8 HTML。
    public static final int MAX_BYTES = 10 * 1024 * 1024;
    private ReportSourceValidator() {}
    public static void validate(String html) {
        if (html == null || html.isBlank()) throw new IllegalStateException("ReportContentInvalid: HTML 内容不能为空");
        if (html.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new IllegalStateException("ReportContentTooLarge: HTML 内容不能超过 10 MB");
        String normalized = html.toLowerCase(Locale.ROOT);
        if (!normalized.contains("<html") && !normalized.contains("<!doctype html")) throw new IllegalStateException("ReportContentInvalid: 内容必须是完整 HTML 文档");
    }
}
