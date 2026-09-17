package com.agentscopea2a.util;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/** 记录 {@code POST /ai/chat} 请求的端到端执行结果指标。 */
@Component
public class AiChatMonitor {

    private static final String ALERT_ENABLED_PROPERTY = "${harness.chat.monitor.alert-enabled:false}";
    private static final String ALERT_TYPES_PROPERTY = "${harness.chat.monitor.alert-types:STREAM_TIMEOUT,MODEL_CALL_TIMEOUT}";
    private static final String ALERT_COOLDOWN_SECONDS_PROPERTY = "${harness.chat.monitor.alert-cooldown-seconds:300}";
    private static final String METRIC = "ai_chat_requests";
    private final MeterRegistry meterRegistry;
    private final boolean alertEnabled;
    private final Set<String> alertTypes;
    private final long alertCooldownMs;
    private final ConcurrentHashMap<String, AtomicLong> lastAlertAt = new ConcurrentHashMap<>();

    public AiChatMonitor(MeterRegistry meterRegistry,
                         @Value(ALERT_ENABLED_PROPERTY) boolean alertEnabled,
                         @Value(ALERT_TYPES_PROPERTY) String alertTypes,
                         @Value(ALERT_COOLDOWN_SECONDS_PROPERTY) long alertCooldownSeconds) {
        this.meterRegistry = meterRegistry;
        this.alertEnabled = alertEnabled;
        this.alertTypes = Set.of(alertTypes.toUpperCase(Locale.ROOT).split(","));
        this.alertCooldownMs = Math.max(0, alertCooldownSeconds) * 1_000L;
    }

    public long start() {
        Counter.builder(METRIC).tag("outcome", "started").register(meterRegistry).increment();
        return System.nanoTime();
    }

    public void success(long startedAtNanos) {
        record("success", startedAtNanos);
    }

    public void failure(long startedAtNanos, String conversationId, String userId, Throwable error) {
        String type = classify(error);
        record("error", startedAtNanos);
        Counter.builder("ai_chat_errors").tag("type", type).register(meterRegistry).increment();
        if (alertEnabled && alertTypes.contains(type) && acquireAlertPermit(type)) {
            AiChatNotificationUtil.send(userId, message(error));
        }
    }

    /** 记录完整 {@code /ai/chat} SSE 生命周期的超时结果。 */
    public void streamTimeout(long startedAtNanos, String conversationId, String userId, Throwable error) {
        String type = "STREAM_TIMEOUT";
        record("error", startedAtNanos);
        Counter.builder("ai_chat_errors").tag("type", type).register(meterRegistry).increment();
        if (alertEnabled && alertTypes.contains(type) && acquireAlertPermit(type)) {
            AiChatNotificationUtil.send(userId, message(error));
        }
    }

    private void record(String outcome, long startedAtNanos) {
        Counter.builder(METRIC).tag("outcome", outcome).register(meterRegistry).increment();
        Timer.builder("ai_chat_duration").tag("outcome", outcome).register(meterRegistry)
                .record(Math.max(0, System.nanoTime() - startedAtNanos), TimeUnit.NANOSECONDS);
    }

    private boolean acquireAlertPermit(String type) {
        AtomicLong last = lastAlertAt.computeIfAbsent(type, ignored -> new AtomicLong(0));
        long now = System.currentTimeMillis();
        long previous = last.get();
        return now - previous >= alertCooldownMs && last.compareAndSet(previous, now);
    }

    private static String classify(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof TimeoutException || current instanceof SocketTimeoutException) return "MODEL_CALL_TIMEOUT";
            if (current instanceof ConnectException) return "NETWORK";
            String message = message(current).toLowerCase(Locale.ROOT);
            if (message.contains("429") || message.contains("rate limit")) return "RATE_LIMIT";
            if (message.contains("timeout") || message.contains("timed out")) return "MODEL_CALL_TIMEOUT";
            if (message.contains("http 5") || message.contains("status=5") || message.contains(" 500")
                    || message.contains(" 502") || message.contains(" 503") || message.contains(" 504")) return "UPSTREAM_5XX";
            if (message.contains("connection refused") || message.contains("connection reset")
                    || message.contains("unknown host")) return "NETWORK";
        }
        return "UNKNOWN";
    }

    private static String message(Throwable error) {
        return error == null || error.getMessage() == null ? "未知错误" : error.getMessage();
    }
}
