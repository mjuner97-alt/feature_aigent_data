package com.agentscopea2a.v2.skillManager.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * {@link NotificationSender} 默认 stub 实现：仅打印日志，不真正发送。
 *
 * <p>接入内部系统时，提供自己的 {@code @Component} 实现 {@link NotificationSender}，
 * 本 stub 因 {@code @ConditionalOnMissingBean} 自动退让。
 */
// 接入内部通知系统前先用本 stub：发送仅打日志；提供自定义 @Component 后本 bean 自动退让。
@Component
public class StubNotificationSender implements NotificationSender {
    private static final Logger log = LoggerFactory.getLogger(StubNotificationSender.class);

    @Override
    public void send(NotificationPayload p) {
        // TODO: 接入内部通知系统（HTTP / 邮件 / IM 等）。
        //   按 p.contentType() 选择纯文本(TEXT)或 HTML 渲染；
        //   报告文件见 p.filePath() / p.fileName()，需读取后随通知发出（附件/链接）；
        //   下载链接见 p.fileUrl()（HTML 里直接渲染 <a href="...">）。
        log.info("[Notification STUB] -> metric={}, job={}, exec={}, status={}, file={}, url={}, type={}",
                p.metricCode(), p.jobName(), p.executionId(), p.status(), p.filePath(), p.fileUrl(), p.contentType());
        log.info("[Notification STUB] content:\n{}", p.content());
    }
}
