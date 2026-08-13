package com.agentscopea2a.v2.skillManager.notification;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 通知载荷：跑批执行成功后组装，交给 {@link NotificationSender} 发送。
 *
 * <p>{@code content} 已按模板渲染完成；{@code contentType} 决定纯文本(TEXT)还是 HTML。
 * {@code filePath}/{@code fileName} 为本次生成的 MD 报告文件（通知入参的文件），
 * 发送方按需读取上传/做附件；{@code fileUrl} 为该文件的下载链接（HTML 模板里
 * {@code <a href>} 直接点击用）。
 *
 * <p>{@code triggerType} 标识本次执行的触发来源（MANUAL 手动 / EXTERNAL 按名外部触发 /
 * METRIC 按指标自动触发），发送方可据此区分通知渠道/内容/收件人。
 */
public record NotificationPayload(
        String contentType,   // TEXT | HTML
        String content,
        String filePath,
        String fileName,
        String fileUrl,       // 报告下载链接（HTML 渲染 <a href> 用；读文件做附件仍用 filePath）
        Long jobId,
        String jobName,
        String metricCode,
        String metricName,
        Long executionId,
        String status,
        LocalDateTime completedAt,
        List<String> userIdList,
        String triggerType    // MANUAL | EXTERNAL | METRIC
) {
}
