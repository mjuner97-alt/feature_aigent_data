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

import com.agentscopea2a.v2.dto.InterruptResumeRequest;
import com.agentscopea2a.v2.runner.HarnessA2aRunnerV2;
import com.agentscopea2a.v2.service.V2ChatStreamService;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Two-step interrupt endpoint (step 1: interrupt only).
 *
 * <p>{@code POST /v2/ai/chat/interrupt} accepts {@code {user_id, conversationId}}
 * (NO supplement), calls the 2-arg {@code agent.getDelegate().interrupt(userId, sessionId)}
 * to set {@link io.agentscope.core.interruption.InterruptControl} flag, and returns
 * a small JSON response {@code {status, conversationId}}.
 *
 * <p>The frontend then types a follow-up message in the normal input box and
 * sends it via {@code POST /v2/ai/chat} to resume - the framework's
 * {@code beforeAgentExecution} reloads state (with the recovery msg appended
 * from {@code handleInterrupt}), {@code interruptControl.reset()} clears the
 * flag, and the LLM continues with the new user input + saved history.
 *
 * <p>Why two-step instead of single-request interrupt+supplement+auto-resume:
 * the single-request design couples the interrupt action with the supplement
 * text - the user must type the supplement before seeing the interrupt take
 * effect, which is unnatural. Two-step lets the user click "interrupt" first,
 * see the in-flight call terminate (InterruptControl.flag = true in the state
 * panel), then type the redirect info at their own pace.
 *
 * <p>The {@link InterruptResumeRequest} DTO still has a {@code supplement} field
 * for backward compatibility (kept around for rollback safety) but the field is
 * silently ignored by this endpoint.
 *
 * <p>See {@code docs/rc2-to-rc5/interrupt-resume-single-endpoint-plan.md} for
 * the historical single-request design (now reverted to two-step).
 */
@RestController
@RequestMapping("/v2/ai/chat")
@CrossOrigin(origins = "*", maxAge = 3600)
public class V2ChatInterruptController {

    private static final Logger log = LoggerFactory.getLogger(V2ChatInterruptController.class);

    private final ObjectProvider<HarnessA2aRunnerV2> runnerProvider;
    private final V2ChatStreamService chatStreamService;

    public V2ChatInterruptController(ObjectProvider<HarnessA2aRunnerV2> runnerProvider,
                                     V2ChatStreamService chatStreamService) {
        this.runnerProvider = runnerProvider;
        this.chatStreamService = chatStreamService;
    }

    @PostMapping("/interrupt")
    public ResponseEntity<Map<String, Object>> interrupt(@RequestBody InterruptResumeRequest req) {
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
        }
        String userId = req.getUserId();
        String sessionId = req.getConversationId();
        if (sessionId == null || sessionId.isBlank()) {
            // conversationId must be explicitly passed by the original /v2/ai/chat;
            // server-generated UUID sessions cannot be interrupted because the client doesn't
            // know the id.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "conversationId is required; the original /v2/ai/chat must explicitly pass conversation_id to use interrupt");
        }

        HarnessA2aRunnerV2 runner = runnerProvider.getIfAvailable();
        if (runner == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "HarnessA2aRunnerV2 bean not available");
        }

        log.info("v2 /chat/interrupt: sessionId={}, userId={}, hasInFlight={}",
                sessionId, userId, chatStreamService.getInFlightCall(userId, sessionId) != null);

        // 2-arg interrupt(userId, sessionId) - sets InterruptControl.flag = true and
        // marks the in-flight call (if any) for termination at the next ReAct iteration
        // boundary. The supplement is NOT injected here - the user will type it as a
        // normal follow-up message via /v2/ai/chat to resume.
        try {
            HarnessAgent agent = runner.getAgent();
            agent.getDelegate().interrupt(userId, sessionId);
        } catch (Exception e) {
            log.warn("v2 /chat/interrupt: interrupt() failed for sessionId={}: {}",
                    sessionId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "interrupt failed: " + e.getMessage(), e);
        }

        return ResponseEntity.ok(Map.of(
                "status", "interrupted",
                "conversationId", sessionId));
    }
}
