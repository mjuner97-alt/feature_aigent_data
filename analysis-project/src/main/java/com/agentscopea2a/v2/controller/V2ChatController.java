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
package com.agentscopea2a.v2.controller;

import com.agentscopea2a.dto.ChatRequest;
import com.agentscopea2a.v2.routing.V2SessionRouter;
import com.agentscopea2a.v2.service.V2ChatStreamService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * v2 入口控制器：SSE 流式接口，委托给 {@link V2ChatStreamService}。
 *
 * <p>阶段 1-2 提供了最小冒烟测试端点。阶段 6 升级为完整的流式处理：
 * <ul>
 *   <li>ToolCallCollector 绑定/解绑</li>
 *   <li>ArtifactStore cleanupTask</li>
 *   <li>AgentEvent -> AiChatResult SSE 映射</li>
 *   <li>Cache HIT 短路（透明 - ResponseCacheMiddleware 已在中间件层处理）</li>
 * </ul>
 *
 * <p>阶段 8：接入 {@link V2SessionRouter} 灰度路由守卫。当
 * {@code harness.routing.v2-percentage} 小于 100 且当前 conversationId 命中 v1 桶时，
 * 返回 503 Service Unavailable - 真正的 v1 fallback 路由推迟到 Stage 9
 * (v1 controller 重新启用后)。
 */
@RestController
@RequestMapping("/v2/ai")
@CrossOrigin(origins = "*", maxAge = 3600)
public class V2ChatController {

    private static final Logger log = LoggerFactory.getLogger(V2ChatController.class);

    private final V2ChatStreamService chatStreamService;
    private final V2SessionRouter sessionRouter;

    public V2ChatController(V2ChatStreamService chatStreamService, V2SessionRouter sessionRouter) {
        this.chatStreamService = chatStreamService;
        this.sessionRouter = sessionRouter;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatRequest req) {
        log.info("v2 /chat: conversationId={}, userId={}", req.getConversationId(), req.getUserId());
        String input = req.getQuestion();
        if (input == null || input.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "question must not be blank");
        }
        String conversationId = req.getConversationId();
        if (conversationId != null && !sessionRouter.shouldUseV2(conversationId)) {
            // Stage 8: v1 controller is Maven-excluded; gray-switch fallback is deferred.
            // Stage 9: when v1 controller is re-enabled, redirect to v1 endpoint here.
            log.warn("v2 /chat: conversationId={} routed to v1 bucket but v1 controller not available",
                    conversationId);
            throw new V1RoutingNotAvailableException(conversationId);
        }
        return chatStreamService.stream(req);
    }

    /**
     * Thrown when {@link V2SessionRouter} routes a conversation to the v1 bucket but the
     * v1 controller is Maven-excluded. Stage 9 will re-enable the v1 endpoint and replace
     * this exception with a redirect.
     */
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public static class V1RoutingNotAvailableException extends RuntimeException {
        public V1RoutingNotAvailableException(String conversationId) {
            super("v1 routing not available in Stage 8 (conversationId=" + conversationId + ")");
        }
    }
}
