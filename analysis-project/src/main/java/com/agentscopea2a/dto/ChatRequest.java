/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.agentscopea2a.dto;

import com.agentscopea2a.service.ChatStreamService;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Inbound payload for {@code POST /chatA2A}.
 *
 * <p>All identity fields are optional on the wire; defaults are filled in
 * {@link ChatStreamService}.
 *
 * <ul>
 *   <li>Missing {@code conversationId} → service generates a UUID. When provided it is forwarded
 *       to the model so multi-turn context is preserved.
 *   <li>Missing {@code agentId} → defaults to {@code "7"}; {@code agentName} → {@code "QA助手"};
 *       {@code formType} → {@code "HXY"}.
 *   <li>Missing {@code agentId} but non-blank {@code chatId} → {@code conversationId} is set from
 *       {@code chatId} (so legacy clients that only know about a chat handle still get session
 *       continuity).
 * </ul>
 */
public record ChatRequest(
        @JsonProperty("message") String message,
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("agent_id") String agentId,
        @JsonProperty("agent_name") String agentName,
        @JsonProperty("from_type") String formType,
        @JsonProperty("conversation_id") String conversationId,
        @JsonProperty("chat_id") String chatId) {}
