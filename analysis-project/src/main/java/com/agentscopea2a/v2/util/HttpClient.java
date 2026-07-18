/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.agentscopea2a.v2.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 工具路由层使用的 HTTP 客户端封装。
 *
 * <p>基于 JDK 自带的 {@link java.net.http.HttpClient},仅暴露 GET / POST(JSON)两个接口,
 * 屏蔽底层细节;后续如需 Header / 超时 / 重试策略,统一在本类里加。
 */
public class HttpClient {

    private static final Logger log = LoggerFactory.getLogger(HttpClient.class);

    /** 单例,复用底层 connection pool。 */
    private static final AtomicReference<java.net.http.HttpClient> CLIENT = new AtomicReference<>();

    private HttpClient() {}

    private static java.net.http.HttpClient client() {
        return CLIENT.updateAndGet(
                existing ->
                        existing != null
                                ? existing
                                : java.net.http.HttpClient.newBuilder()
                                        .connectTimeout(Duration.ofSeconds(10))
                                        .build());
    }

    /** GET 请求,返回 body 字符串。 */
    public static String get(String url, Map<String, String> headers) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .GET();
            if (headers != null) {
                headers.forEach(b::header);
            }
            HttpResponse<String> resp =
                    client().send(b.build(), HttpResponse.BodyHandlers.ofString());
            return resp.body();
        } catch (IOException | InterruptedException e) {
            log.warn("HTTP GET 失败: url={}, err={}", url, e.getMessage());
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** POST JSON,返回 body 字符串。 */
    public static String postJson(String url, String jsonBody, Map<String, String> headers) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody == null ? "" : jsonBody));
            if (headers != null) {
                headers.forEach(b::header);
            }
            HttpResponse<String> resp =
                    client().send(b.build(), HttpResponse.BodyHandlers.ofString());
            return resp.body();
        } catch (IOException | InterruptedException e) {
            log.warn("HTTP POST 失败: url={}, err={}", url, e.getMessage());
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
