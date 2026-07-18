/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.agentscopea2a.v2.model;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.micrometer.core.annotation.Timed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Model 装饰器 - 封装「主模型 -> fallback 模型」降级逻辑。
 *
 * <p>每次 LLM 调用都经过此装饰器：
 * <ul>
 *   <li>主模型调用成功 -> 直接返回</li>
 *   <li>HTTP 401/403（Token 无效）-> 立即用 fallback Model 重试，不重试主模型</li>
 *   <li>HTTP 5xx / 超时 -> 先重试主模型（最多 N 次），仍失败则降级到 fallback Model</li>
 *   <li>其他异常 -> 直接抛出，不降级</li>
 * </ul>
 *
 * <p>只替换失败的那次 LLM 调用，Agent 的 ReAct 推理链和中间状态不受影响。
 *
 * <p>由 {@code HarnessA2aRunnerV2} 构造时手动 {@code new FallbackModelDecorator(primary, fallback)}
 * 包装 {@code OpenAIChatModel}，不通过 Spring {@code @Bean} 装配。fallback 模型由
 * {@code harness.a2a.model.instances.fallback.*} 配置构建。
 */
public class FallbackModelDecorator implements Model {

    private static final Logger log = LoggerFactory.getLogger(FallbackModelDecorator.class);

    private static final Pattern HTTP_AUTH_ERROR = Pattern.compile("(?i)(401|403|unauthorized|forbidden|invalid.*api.*key|authentication.*failed)");
    private static final Pattern HTTP_SERVER_ERROR = Pattern.compile("(?i)(5\\d{2}|server.*error|internal.*error|rate.?limit)");
    private static final Pattern TIMEOUT_ERROR = Pattern.compile("(?i)(timeout|timed out|elapsed time exceeded)");
    /** 主模型内部重试已耗尽 - 不应再重试主模型，直接降级。 */
    private static final Pattern RETRY_EXHAUSTED = Pattern.compile("(?i)(retries exhausted|max.*attempts|too many requests)");

    private final Model primaryModel;
    private final Model fallbackModel;
    private int maxRetries = 3;
    private long retryIntervalMs = 2000L;

    public FallbackModelDecorator(Model primaryModel, Model fallbackModel) {
        this.primaryModel = primaryModel;
        this.fallbackModel = fallbackModel;
    }

    @Override
    public String getModelName() {
        return primaryModel.getModelName();
    }

    @Override
    @Timed(value = "model.fallback.stream", description = "FallbackModelDecorator stream duration (incl. retries + fallback switch)", percentiles = {0.5, 0.9, 0.99})
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return doStreamWithFallback(messages, tools, options, 0);
    }

    private Flux<ChatResponse> doStreamWithFallback(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options, int attemptCount) {
        return primaryModel.stream(messages, tools, options)
                .onErrorResume(error -> {
                    log.warn("主模型调用失败(attempt={}/{}), error={}, 将重试或降级",
                            attemptCount + 1, maxRetries, error.getMessage());
                    return retryOrFallback(messages, tools, options, attemptCount, error);
                });
    }

    private Flux<ChatResponse> retryOrFallback(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options, int attemptCount, Throwable error) {
        String msg = extractCauseMessage(error);
        if (isAuthError(error)) {
            log.info("检测到认证错误，将直接降级到通用模型: {}", msg);
            return fallbackDirectly(messages, tools, options, error);
        }
        if (isRetryExhausted(error)) {
            log.info("主模型内部重试已耗尽，将直接降级到通用模型: {}", msg);
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

    /** 主模型内部重试已耗尽（如 "Retries exhausted"），不应再重试，直接降级。 */
    private boolean isRetryExhausted(Throwable error) {
        String msg = extractCauseMessage(error);
        if (msg == null) return false;
        return RETRY_EXHAUSTED.matcher(msg).find();
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
