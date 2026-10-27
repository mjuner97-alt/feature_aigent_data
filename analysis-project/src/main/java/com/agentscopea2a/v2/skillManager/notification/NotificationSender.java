package com.agentscopea2a.v2.skillManager.notification;

/**
 * 通知发送 SPI。默认由 {@link StubNotificationSender} 对接内部 HTTP 邮件网关。
 * 长任务通过 FlowNotificationDispatcher 控制模拟或真实发送。
 *
 * <p>本接口在通知专用线程被调用；若发送耗时较长，建议实现内部自行异步/超时控制，
 * 避免堆积。实现必须在 HTTP 非 2xx、超时或业务拒绝时抛出异常；正常返回会被记录为 SUCCESS。
 * 异常由通知服务捕获并记录，不影响 job 执行结果。
 *
 * <p>payload 携带 {@code triggerType}（MANUAL 手动 / EXTERNAL 按名外部触发 / METRIC 按指标自动触发），
 * 实现方可据此区分通知渠道、内容或收件人——例如 MANUAL 走触发人私聊、METRIC 走指标订阅群。
 */
public interface NotificationSender {

    /** Sends a password recovery code using the same default contact block as long-task mail. */
    default void sendPasswordReset(String userId, String code) { }

    /**
     * 发送通知。
     *
     * @param payload 已渲染内容 + 文件信息（filePath/fileName 指向本次生成的 MD 报告，fileUrl 为其下载链接，
     *                triggerType 标识触发来源以便分支处理）
     */
    void send(NotificationPayload payload);

    /** Build the final request once, before persistence; never include authentication headers. */
    default java.util.Map<String, Object> prepare(NotificationPayload payload) {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("title", payload.jobName());
        body.put("content", payload.content());
        body.put("toUserList", payload.userIdList() == null ? java.util.List.of() : java.util.List.copyOf(payload.userIdList()));
        body.put("toCCUserList", payload.ccUserIdList() == null ? java.util.List.of() : java.util.List.copyOf(payload.ccUserIdList()));
        return body;
    }

    /** Return the gateway response, or null when a legacy adapter provides no receipt. */
    default String sendPrepared(NotificationPayload payload, java.util.Map<String, Object> request) {
        send(payload);
        return null;
    }
}
