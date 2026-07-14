package com.agentscopea2a.agent.model;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Model 装饰器 - 封装「用户 Token -> 通用 Token」降级逻辑。
 *
 * <p>每次 LLM 调用都经过此装饰器：
 * <ul>
 *   <li>主模型调用成功 -> 直接返回</li>
 *   <li>HTTP 401/403（Token 无效）-> 立即用通用 Model 重试，不重试主模型</li>
 *   <li>HTTP 5xx / 超时 -> 先重试主模型（最多 N 次），仍失败则降级到通用 Model</li>
 *   <li>其他异常 -> 直接抛出，不降级</li>
 * </ul>
 *
 * <p>只替换失败的那次 LLM 调用，Agent 的 ReAct 推理链和中间状态不受影响。
 */
public class FallbackModelDecorator implements Model {

    private static final Logger log = LoggerFactory.getLogger(FallbackModelDecorator.class);

    private static final Pattern HTTP_AUTH_ERROR = Pattern.compile("(?i)(401|403|unauthorized|forbidden|invalid.*api.*key|authentication.*failed)");
    private static final Pattern HTTP_SERVER_ERROR = Pattern.compile("(?i)(5\\d{2}|server.*error|internal.*error|rate.?limit)");
    private static final Pattern TIMEOUT_ERROR = Pattern.compile("(?i)(timeout|timed out|elapsed time exceeded)");

    private final Model primaryModel;
    private final Model fallbackModel;
    private final int maxRetries;
    private final long retryIntervalMs;

    public FallbackModelDecorator(Model primaryModel, Model fallbackModel, FallbackModelProperties props) {
        this.primaryModel = primaryModel;
        this.fallbackModel = fallbackModel;
        this.maxRetries = props.getMaxRetries();
        this.retryIntervalMs = props.getRetryIntervalMs();
    }

    @Override
    public String getModelName() {
        return primaryModel.getModelName();
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return doStreamWithFallback(messages, tools, options, 0);
    }

    private Flux<ChatResponse> doStreamWithFallback(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options, int attemptCount) {
        return primaryModel.stream(messages, tools, options)
                .onErrorResume(error -> shouldTryPrimaryRetry(error), error -> {
                    log.warn("主模型调用失败(attempt={}/{}), error={}, 将重试或降级",
                            attemptCount + 1, maxRetries, error.getMessage());
                    return retryOrFallback(messages, tools, options, attemptCount, error);
                });
    }

    private boolean shouldTryPrimaryRetry(Throwable error) {
        String msg = extractCauseMessage(error);
        if (msg == null) return false;
        if (isAuthError(error)) {
            log.info("检测到认证错误，将直接降级到通用模型: {}", msg);
            return false;
        }
        return true;
    }

    private Flux<ChatResponse> retryOrFallback(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options, int attemptCount, Throwable error) {
        String msg = extractCauseMessage(error);
        if (isAuthError(error)) {
            return fallbackDirectly(messages, tools, options, error);
        }
        if (attemptCount < maxRetries) {
            log.info("主模型可重试错误(attempt={}/{}), 等待{}ms后重试: {}",
                    attemptCount + 1, maxRetries, retryIntervalMs, msg);
            return Mono.delay(Duration.ofMillis(retryIntervalMs))
                    .flatMapMany(tick -> doStreamWithFallback(messages, tools, options, attemptCount + 1));
        }
        log.warn("主模型重试{}次后仍失败，降级到通用模型: {}", maxRetries, msg);
        return fallbackDirectly(messages, tools, options, error);
    }

    private Flux<ChatResponse> fallbackDirectly(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options, Throwable originalError) {
        log.info("降级到通用模型，原始错误: {}", extractCauseMessage(originalError));
        return fallbackModel.stream(messages, tools, options)
                .onErrorResume(error -> {
                    log.error("通用模型也失败了！原始错误: {}, 降级错误: {}",
                            extractCauseMessage(originalError), extractCauseMessage(error));
                    return Mono.error(error);
                });
    }

    private boolean isAuthError(Throwable error) {
        String msg = extractCauseMessage(error);
        if (msg == null) return false;
        return HTTP_AUTH_ERROR.matcher(msg).find() || TIMEOUT_ERROR.matcher(msg).find();
    }

    private String extractCauseMessage(Throwable t) {
        Throwable cur = t;
        for (int i = 0; i < 5 && cur != null; i++) {
            if (cur.getMessage() != null && !cur.getMessage().isEmpty()) {
                return cur.getMessage();
            }
            cur = cur.getCause();
        }
        return t.getClass().getName();
    }
}
