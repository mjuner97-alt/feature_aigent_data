package com.agentscopea2a.v2.skillManager.notification;

import java.time.LocalDateTime;

/**
 * 通知载荷：跑批执行成功后组装，交给 {@link NotificationSender} 发送。
 *
 * <p>{@code content} 已按模板渲染完成；{@code contentType} 决定纯文本(TEXT)还是 HTML。
 * {@code filePath}/{@code fileName} 为本次生成的 MD 报告文件（通知入参的文件），
 * 发送方按需读取上传。
 */
public record NotificationPayload(
        String contentType,   // TEXT | HTML
        String content,
        String filePath,
        String fileName,
        Long jobId,
        String jobName,
        String metricCode,
        String metricName,
        Long executionId,
        String status,
        LocalDateTime completedAt
) {
}
