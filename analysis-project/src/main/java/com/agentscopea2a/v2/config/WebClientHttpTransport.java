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
package com.agentscopea2a.v2.config;

import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.core.model.transport.TransportConstants;
import io.agentscope.diagnostics.LlmFileTrace;
import io.netty.channel.ChannelOption;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/**
 * 基于 Spring WebClient (reactor-netty) 的 {@link HttpTransport} 实现。
 *
 * <p>用途：作为 {@code FallbackModelDecorator} 的 fallback 传输——主模型走默认的
 * JdkHttpTransport（HTTP/2，存在 JDK HTTP2 + SSE 卡死问题），超时/失败时用 WebClient
 * 以 HTTP/1.1 重新请求同一模型端点（与用户实测 WebClient 每次都能连通一致）。
 *
 * <p>对外契约与 JdkHttpTransport 完全一致（OpenAIClient 依赖此契约）：
 * <ul>
 *   <li>{@link #stream} 返回已剥掉 {@code data:} 前缀、且不含 {@code [DONE]} 的 SSE data 行
 *       （NDJSON 模式透传整行）</li>
 *   <li>HTTP 非 2xx 映射为 {@link HttpTransportException}（带 statusCode，供
 *       {@code ExecutionConfig.RETRYABLE_ERRORS} 判断 429/5xx）</li>
 *   <li>非流式 {@link #execute} 供非 streaming 模型调用（如 memory 分类器）</li>
 * </ul>
 *
 * <p>超时策略：connectTimeout / responseTimeout 只覆盖到「响应头」；流式 body 不设整体超时，
 * 中途卡死由 {@code ModelUtils} 的 chunk 间隔超时（默认 40s）兜底——与 JDK 实现一致。
 */
@Component
public class WebClientHttpTransport implements HttpTransport {

    private static final String SSE_DATA_PREFIX = "data:";
    private static final String SSE_DONE_MARKER = "[DONE]";

    private final WebClient webClient;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public WebClientHttpTransport() {
        // reactor-netty 默认 HTTP/1.1。responseTimeout 只到响应头，流式 body 不截断。
        // 连接超时用 ChannelOption（reactor-netty 1.1 无 HttpClient.connectTimeout(Duration)）。
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 30_000)
                .responseTimeout(Duration.ofSeconds(30))
                .followRedirect(true);
        this.webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    // ── 非流式 ──────────────────────────────────────────────────────────────

    @Override
    public HttpResponse execute(HttpRequest request) throws HttpTransportException {
        if (closed.get()) {
            throw new HttpTransportException("Transport has been closed");
        }
        String traceId = LlmFileTrace.id();
        long started = System.nanoTime();
        LlmFileTrace.write(traceId, "WebClientHttpTransport", "非流式请求", "url=" + request.getUrl() + " method=" + request.getMethod());
        try {
            HttpResponse response = doExchange(request).block();
            LlmFileTrace.write(traceId, "WebClientHttpTransport", "非流式响应",
                    "status=" + response.getStatusCode() + " elapsedMs=" + elapsedMs(started));
            return response;
        } catch (RuntimeException e) {
            if (e instanceof HttpTransportException hte) {
                throw hte;
            }
            LlmFileTrace.write(traceId, "WebClientHttpTransport", "非流式异常",
                    "elapsedMs=" + elapsedMs(started) + " type=" + e.getClass().getName() + " message=" + LlmFileTrace.shortText(e.getMessage()));
            throw new HttpTransportException("HTTP request failed: " + e.getMessage(), e);
        }
    }

    /**
     * 非流式交换：读完整响应体，非 2xx 抛 {@link HttpTransportException}。
     */
    private Mono<HttpResponse> doExchange(HttpRequest request) {
        return buildRequest(request)
                .exchangeToMono(resp -> {
                    int status = resp.statusCode().value();
                    return resp.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .map(body -> {
                                if (status >= 200 && status < 300) {
                                    return HttpResponse.builder()
                                            .statusCode(status)
                                            .body(body)
                                            .build();
                                }
                                throw new HttpTransportException(
                                        "HTTP request failed with status " + status + " | " + body,
                                        status, body);
                            });
                });
    }

    // ── 流式 (SSE / NDJSON) ─────────────────────────────────────────────────

    @Override
    public Flux<String> stream(HttpRequest request) {
        if (closed.get()) {
            return Flux.error(new HttpTransportException("Transport has been closed"));
        }
        String traceId = LlmFileTrace.id();
        long started = System.nanoTime();
        boolean isNdjson = TransportConstants.STREAM_FORMAT_NDJSON.equals(
                request.getHeaders().get(TransportConstants.STREAM_FORMAT_HEADER));
        LlmFileTrace.write(traceId, "WebClientHttpTransport", "流式请求",
                "url=" + request.getUrl() + " method=" + request.getMethod());

        return buildRequest(request)
                .exchangeToFlux(resp -> {
                    int status = resp.statusCode().value();
                    if (status < 200 || status >= 300) {
                        // 读错误体（流还开着时读，避免拿不到 detail）
                        return resp.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .flatMapMany(errBody -> Flux.error(
                                        new HttpTransportException(
                                                "HTTP request failed with status " + status + " | " + errBody,
                                                status, errBody)));
                    }
                    LlmFileTrace.write(traceId, "WebClientHttpTransport", "收到响应头",
                            "status=" + status + " elapsedMs=" + elapsedMs(started));
                    return readStreamLines(resp, isNdjson, traceId);
                })
                .onErrorMap(e -> !(e instanceof HttpTransportException), e -> {
                    Throwable cause = e instanceof java.util.concurrent.CompletionException ? e.getCause() : e;
                    if (cause instanceof HttpTransportException) {
                        return (HttpTransportException) cause;
                    }
                    LlmFileTrace.write(traceId, "WebClientHttpTransport", "异常",
                            "elapsedMs=" + elapsedMs(started) + " type=" + e.getClass().getName()
                                    + " message=" + LlmFileTrace.shortText(e.getMessage()));
                    return new HttpTransportException("SSE/NDJSON stream failed: " + e.getMessage(), e);
                })
                .doOnComplete(() -> LlmFileTrace.write(traceId, "WebClientHttpTransport", "完成",
                        "elapsedMs=" + elapsedMs(started)))
                .doOnCancel(() -> LlmFileTrace.write(traceId, "WebClientHttpTransport", "取消",
                        "elapsedMs=" + elapsedMs(started)));
    }

    /**
     * 把响应体按行切分（跨 DataBuffer 的残行用 per-subscription 的 StringBuilder 拼接），
     * 再按 SSE/NDJSON 过滤出上游 OpenAIClient 期望的 data 行。
     */
    private Flux<String> readStreamLines(ClientResponse resp, boolean isNdjson, String traceId) {
        return Flux.defer(() -> {
            StringBuilder pending = new StringBuilder();
            return resp.bodyToFlux(String.class)
                    .flatMap(chunk -> {
                        pending.append(chunk);
                        String text = pending.toString();
                        int lastNl = text.lastIndexOf('\n');
                        if (lastNl < 0) {
                            return Flux.empty();
                        }
                        String completed = text.substring(0, lastNl);
                        pending.setLength(0);
                        pending.append(text.substring(lastNl + 1));
                        return completed.isEmpty() ? Flux.<String>empty() : Flux.fromArray(completed.split("\\r?\\n"));
                    })
                    .concatMap(line -> {
                        if (isNdjson) {
                            return line.isEmpty() ? Flux.<String>empty() : Flux.just(line);
                        }
                        if (!line.startsWith(SSE_DATA_PREFIX)) {
                            return Flux.empty();
                        }
                        String data = line.substring(SSE_DATA_PREFIX.length()).trim();
                        if (data.isEmpty() || SSE_DONE_MARKER.equals(data)) {
                            return Flux.empty();
                        }
                        return Flux.just(data);
                    })
                    .doOnNext(data -> LlmFileTrace.write(traceId, "WebClientHttpTransport", "SSE数据",
                            "data=" + LlmFileTrace.shortText(data)));
        });
    }

    // ── 请求构造与关闭 ──────────────────────────────────────────────────────

    private WebClient.RequestBodySpec buildRequest(HttpRequest request) {
        String method = request.getMethod().toUpperCase();
        HttpMethod httpMethod;
        try {
            httpMethod = HttpMethod.valueOf(method);
        } catch (IllegalArgumentException e) {
            httpMethod = HttpMethod.POST;
        }
        WebClient.RequestBodySpec spec = webClient.method(httpMethod).uri(request.getUrl());
        boolean hasContentType = false;
        for (Map.Entry<String, String> entry : request.getHeaders().entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            if (HttpHeaders.CONTENT_TYPE.equalsIgnoreCase(entry.getKey())) {
                hasContentType = true;
            }
            spec.header(entry.getKey(), entry.getValue());
        }
        String body = request.getBody();
        if (body != null) {
            if (!hasContentType) {
                spec.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
            }
            spec.bodyValue(body);
        }
        return spec;
    }

    @Override
    public void close() {
        closed.set(true);
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }
}
