package com.agentscopea2a.v2.skillManager.notification;

/**
 * 通知发送 SPI。默认由 {@link StubNotificationSender} 提供仅日志实现。
 *
 * <p>接入内部系统时，提供自己的 {@code @Component} 实现本接口即可，
 * 由于 stub 标注了 {@code @ConditionalOnMissingBean}，自定义实现会自动替换 stub。
 *
 * <p>本接口在通知专用线程被调用；若发送耗时较长，建议实现内部自行异步/超时控制，
 * 避免堆积。发送失败应内部捕获并记录，不影响 job 执行结果。
 *
 * <p>payload 携带 {@code triggerType}（MANUAL 手动 / EXTERNAL 按名外部触发 / METRIC 按指标自动触发），
 * 实现方可据此区分通知渠道、内容或收件人——例如 MANUAL 走触发人私聊、METRIC 走指标订阅群。
 */
public interface NotificationSender {

    /**
     * 发送通知。
     *
     * @param payload 已渲染内容 + 文件信息（filePath/fileName 指向本次生成的 MD 报告，fileUrl 为其下载链接，
     *                triggerType 标识触发来源以便分支处理）
     */
    void send(NotificationPayload payload);
}
