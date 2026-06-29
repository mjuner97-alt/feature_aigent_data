/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.agentscopea2a.service;

import com.agentscopea2a.dto.ChatRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Streams a chat response over an {@link SseEmitter}.
 *
 * <p>Implementations own request normalization (defaults for {@code agentId}, {@code agentName},
 * {@code formType}, {@code conversationId}), supervisor wiring, and the SSE wire format.
 */
public interface ChatStreamServiceV_2 {

    /**
     * Build the supervisor agent for {@code req} and stream its events back through a fresh
     * {@link SseEmitter}. The returned emitter is also responsible for per-task artifact cleanup
     * on every terminal signal (complete / timeout / error).
     */
    SseEmitter stream(ChatRequest req);

    SseEmitter streamPublic(ChatRequest req);
}
