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
package com.agentscopea2a.v2.service;

import com.agentscopea2a.dto.ChatRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * v2 streaming service: accepts a {@link ChatRequest}, runs the agent pipeline,
 * and returns an {@link SseEmitter} that pushes incremental response chunks to
 * the frontend.
 *
 * <p>Replaces v1 {@code ChatStreamServiceV_3Impl} with a simpler, shared-agent
 * architecture (one {@link HarnessAgent} instance, per-request {@link RuntimeContext}).
 */
public interface V2ChatStreamService {

    /**
     * Stream the agent's response for the given request.
     *
     * @param req the chat request (input text, conversationId, userId, etc.)
     * @return an {@link SseEmitter} that pushes events until the agent completes
     */
    SseEmitter stream(ChatRequest req);
}