package com.agentscopea2a.v2.skillManager.notification;

/**
 * 通知发送 SPI。默认由 {@link StubNotificationSender} 提供仅日志实现。
 *
 * <p>接入内部系统时，提供自己的 {@code @Component} 实现本接口即可，
 * 由于 stub 标注了 {@code @ConditionalOnMissingBean}，自定义实现会自动替换 stub。
 *
 * <p>本接口在通知专用线程被调用；若发送耗时较长，建议实现内部自行异步/超时控制，
 * 避免堆积。发送失败应内部捕获并记录，不影响 job 执行结果。
 */
public interface NotificationSender {

    /**
     * 发送通知。
     *
     * @param payload 已渲染内容 + 文件信息（filePath/fileName 指向本次生成的 MD 报告）
     */
    void send(NotificationPayload payload);
}
