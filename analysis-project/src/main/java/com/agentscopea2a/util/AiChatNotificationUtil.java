package com.agentscopea2a.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * AI Chat 告警的公共通知出口。
 *
 * <p>当前只记录结构化告警日志；接入钉钉、飞书或 PagerDuty 时，只需在
 * {@link #send(String, String, String, String)} 中补充实际发送逻辑。通知故障必须被吞掉，
 * 以免影响用户的聊天请求。</p>
 */
public final class AiChatNotificationUtil {

    private static final Logger log = LoggerFactory.getLogger(AiChatNotificationUtil.class);
    private static final ThreadPoolExecutor NOTIFICATION_EXECUTOR = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(128),
            new ThreadPoolExecutor.AbortPolicy());

    private AiChatNotificationUtil() {
    }

    public static void send(String alertType, String conversationId, String userId, String message) {
        submit(() -> log.warn("[数字QA聊天告警] 类型={} 会话ID={} 用户ID={} 消息={}",
                alertType, safe(conversationId), safe(userId), safe(message)));
    }

    /**
     * 模型调用层的公共通知入口。该层没有 HTTP 会话上下文，因此不记录用户问题或 API Key。
     */
    public static void sendModelCallFailure(String alertType, String model, String phase, Throwable error) {
        sendModelCallFailure(alertType, null, model, phase, null, null, error);
    }

    public static void sendModelCallFailure(String alertType, String conversationId, String model,
                                            String phase, String timeoutKind, Duration timeout,
                                            Throwable error) {
        String message = error == null || error.getMessage() == null ? "未知错误" : error.getMessage();
        submit(() -> log.warn("[AI模型告警] 类型={} 会话ID={} 模型={} 阶段={} 超时类型={} 超时时间={} 错误类型={} 消息={}",
                safe(alertType), safe(conversationId), safe(model), safe(phase), safe(timeoutKind),
                timeout == null ? "-" : timeout, error == null ? "-" : error.getClass().getSimpleName(), safe(message)));
    }

    private static void submit(Runnable notification) {
        try {
            NOTIFICATION_EXECUTOR.execute(notification);
        } catch (RejectedExecutionException ex) {
            log.warn("[数字QA告警] 通知队列已满");
        } catch (Exception ex) {
            log.warn("[数字QA告警] 通知发送失败：{}", ex.getMessage());
        }
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
