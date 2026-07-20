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
package com.agentscopea2a.v2.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for {@code POST /v2/ai/chat/interrupt} - the single-request
 * interrupt + supplement + auto-resume endpoint.
 *
 * <p>The frontend closes the original {@code /v2/ai/chat} SSE stream and sends
 * this request with the user's supplement/redirect info. The server interrupts
 * the in-flight call, waits for it to terminate (handleInterrupt + saveState),
 * then starts a new streaming call with {@code supplement} as the new user
 * message. The returned {@link org.springframework.web.servlet.mvc.method.annotation.SseEmitter}
 * carries the agent's continuation based on the supplement + saved history.
 *
 * <ul>
 *   <li>{@code userId} - must match the original /v2/ai/chat user_id (for session slot resolution)
 *   <li>{@code conversationId} - must match the original /v2/ai/chat conversation_id
 *       (the original request MUST have explicitly passed conversation_id; server-generated
 *       UUID sessions cannot be interrupted because the client doesn't know the id)
 *   <li>{@code supplement} - the user's redirect/adjustment info, becomes the next user message
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InterruptResumeRequest {

    @JsonProperty("user_id")
    @JsonAlias("userId")
    private String userId;

    @JsonAlias("conversation_id")
    private String conversationId;

    private String supplement;
}
