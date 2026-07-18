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

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Inbound payload for {@code POST /ai/chat}.
 *
 * <p>All identity fields are optional on the wire; defaults are filled in
 * by the streaming service.
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
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {

    private String input;

    @JsonAlias("conversation_id")
    private String conversationId;

    @JsonProperty("user_id")
    @JsonAlias("userId")
    private String userId;

    @JsonAlias("agent_id")
    private String agentId;

    @JsonAlias("agent_name")
    private String agentName;

    @JsonAlias("from_type")
    private String fromType;

    @JsonAlias("chat_id")
    private String chatId;

    private String question;
}
