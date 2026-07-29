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
package com.agentscopea2a.controller;

import com.agentscopea2a.v2.runner.HarnessA2aRunnerV2;
import com.agentscopea2a.v2.service.ChatStreamService;
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
import reactor.core.Disposable;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Interrupt-only endpoint for the /ai/chat entry (upgrade frontend).
 *
 * <p>Mirrors {@link com.agentscopea2a.v2.controller.V2ChatInterruptController} but:
 * <ul>
 *   <li>Serves {@code /ai/chat/interrupt} (upgrade frontend), not {@code /v2/ai/chat/interrupt}</li>
 *   <li>Injects {@link ChatStreamService} (upgrade entry's in-flight map),
 *       not {@code V2ChatStreamService}</li>
 * </ul>
 *
 * <p>Protocol: JSON {@code {"status":"interrupted","conversationId":"..."}} (interrupt-only;
 * frontend sends a follow-up {@code POST /ai/chat} to resume).
 *
 * <p>Two-entry design: Plan-Machine frontend keeps the original single-endpoint
 * SSE interrupt+resume on {@code /v2/ai/chat/interrupt} (see
 * {@code interrupt_resume_endpoint_verified.md}); upgrade frontend uses split
 * interrupt/resume on {@code /ai/chat/interrupt}.
 */
@RestController
@RequestMapping("/ai/chat")
@CrossOrigin(origins = "*", maxAge = 3600)
public class ChatInterruptController {

    private static final Logger log = LoggerFactory.getLogger(ChatInterruptController.class);

    private static final long INTERRUPT_WAIT_SECONDS = 180;

    private final ObjectProvider<HarnessA2aRunnerV2> runnerProvider;
    private final ChatStreamService chatStreamService;

    public ChatInterruptController(ObjectProvider<HarnessA2aRunnerV2> runnerProvider,
                                   ChatStreamService chatStreamService) {
        this.runnerProvider = runnerProvider;
        this.chatStreamService = chatStreamService;
    }

    public record InterruptRequest(
            @com.fasterxml.jackson.annotation.JsonProperty("user_id")
            @com.fasterxml.jackson.annotation.JsonAlias("userId")
            String userId,
            @com.fasterxml.jackson.annotation.JsonAlias("conversation_id")
            String conversationId
    ) {}

    @PostMapping(value = "/interrupt", produces = "application/json")
    public ResponseEntity<Map<String, String>> interrupt(@RequestBody InterruptRequest req) {
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
        }
        String userId = req.userId();
        String sessionId = req.conversationId();

        if (sessionId == null || sessionId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "conversationId is required; the original /ai/chat must explicitly pass conversation_id to use interrupt");
        }

        HarnessA2aRunnerV2 runner = runnerProvider.getIfAvailable();
        if (runner == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "HarnessA2aRunnerV2 bean not available");
        }

        log.info("ai /chat/interrupt: sessionId={}, userId={}", sessionId, userId);

        ChatStreamService.InFlightCall inFlight = chatStreamService.getInFlightCall(userId, sessionId);

        try {
            HarnessAgent agent = runner.getAgent();
            agent.getDelegate().interrupt(userId, sessionId);
            log.info("ai /chat/interrupt: interrupt triggered for sessionId={}, userId={}, hasInFlight={}",
                    sessionId, userId, inFlight != null);
        } catch (Exception e) {
            log.warn("ai /chat/interrupt: interrupt() failed for sessionId={}: {}",
                    sessionId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "interrupt failed: " + e.getMessage(), e);
        }

        if (inFlight != null) {
            try {
                inFlight.completion().get(INTERRUPT_WAIT_SECONDS, TimeUnit.SECONDS);
                log.info("ai /chat/interrupt: in-flight terminated for sessionId={}", sessionId);
            } catch (TimeoutException te) {
                Disposable d = inFlight.subscription().get();
                if (d != null && !d.isDisposed()) {
                    d.dispose();
                    log.warn("ai /chat/interrupt: forcibly disposed in-flight subscription "
                            + "for sessionId={} after {}s timeout", sessionId, INTERRUPT_WAIT_SECONDS);
                }
            } catch (Exception e) {
                log.warn("ai /chat/interrupt: in-flight completion returned error for sessionId={}: {}",
                        sessionId, e.getMessage());
            }
        }

        log.info("ai /chat/interrupt: completed for sessionId={}", sessionId);
        return ResponseEntity.ok(Map.of("status", "interrupted", "conversationId", sessionId));
    }
}
