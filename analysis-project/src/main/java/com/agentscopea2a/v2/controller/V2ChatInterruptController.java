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
import reactor.core.Disposable;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Interrupt-only endpoint — triggers InterruptControl and waits for the
 * in-flight call to terminate, then returns a JSON confirmation.
 *
 * <p>The frontend should:
 * <ol>
 *   <li>Call this endpoint to interrupt the current call</li>
 *   <li>Close the original /v2/ai/chat SSE stream</li>
 *   <li>Show the normal chat input for the user to type a supplement message</li>
 *   <li>Send the supplement as a regular /v2/ai/chat request to resume</li>
 * </ol>
 *
 * <p>This replaces the previous single-request interrupt+resume design. Splitting
 * interrupt and resume into separate operations is more natural because the user
 * doesn't know what to redirect to until they see the interruption happen.
 *
 * <p>Flow:
 * <ol>
 *   <li>Resolve {@code (userId, conversationId)} from the request body</li>
 *   <li>Fetch the in-flight call descriptor from {@link V2ChatStreamService#getInFlightCall}</li>
 *   <li>Call {@code agent.getDelegate().interrupt(userId, sessionId, null)}
 *       - sets {@link InterruptControl} flag to true</li>
 *   <li>If an in-flight call exists, wait up to 180s for its completion future:
 *       <ul>
 *         <li>On complete: in-flight call has terminated — safe to return</li>
 *         <li>On timeout: force-dispose the in-flight subscription to stop burning
 *             tokens, then return anyway</li>
 *       </ul>
 *   <li>Return JSON {@code {"status": "interrupted"}}</li>
 * </ol>
 */
@RestController
@RequestMapping("/v2/ai/chat")
@CrossOrigin(origins = "*", maxAge = 3600)
public class V2ChatInterruptController {

    private static final Logger log = LoggerFactory.getLogger(V2ChatInterruptController.class);

    /**
     * Max wait for the in-flight call to terminate after interrupt.
     *
     * <p>The framework's {@code checkInterrupted()} fires at ReAct iteration
     * boundaries, so an in-flight call stuck in pre-iteration middlewares
     * (MemoryMaintenanceMiddleware doing SSH file ops, SkillEvolutionHook doing
     * LLM-based metric classification, etc.) can't be interrupted until it
     * reaches the next iteration. For slow models (qwen3:8b on CPU) + heavy
     * middleware setup, this can take 60-90s.
     *
     * <p>180s gives enough headroom for one full middleware cycle on slow
     * models. If the in-flight still hasn't terminated after 180s, we fall back
     * to force-dispose.
     */
    private static final long INTERRUPT_WAIT_SECONDS = 180;

    private final ObjectProvider<HarnessA2aRunnerV2> runnerProvider;
    private final V2ChatStreamService chatStreamService;

    public V2ChatInterruptController(ObjectProvider<HarnessA2aRunnerV2> runnerProvider,
                                     V2ChatStreamService chatStreamService) {
        this.runnerProvider = runnerProvider;
        this.chatStreamService = chatStreamService;
    }

    /**
     * Interrupt-only request body. No supplement — the user types the redirect
     * message in the normal chat input after seeing the interruption.
     */
    public record InterruptRequest(
            @com.fasterxml.jackson.annotation.JsonProperty("user_id")
            @com.fasterxml.jackson.annotation.JsonAlias("userId")
            String userId,
            @com.fasterxml.jackson.annotation.JsonAlias("conversation_id")
            String conversationId
    ) {}

    @PostMapping(value = "/interrupt", produces = "application/json")
    public ResponseEntity<Map<String, String>> interrupt(@RequestBody InterruptRequest req) {
        // ── Parameter validation ─────────────────────────────────────────────
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
        }
        String userId = req.userId();
        String sessionId = req.conversationId();

        if (sessionId == null || sessionId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "conversationId is required; the original /v2/ai/chat must explicitly pass conversation_id to use interrupt");
        }

        HarnessA2aRunnerV2 runner = runnerProvider.getIfAvailable();
        if (runner == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "HarnessA2aRunnerV2 bean not available");
        }

        log.info("v2 /chat/interrupt: sessionId={}, userId={}", sessionId, userId);

        // ── Step 1: fetch in-flight descriptor (may be null) ─────────────────
        V2ChatStreamService.InFlightCall inFlight = chatStreamService.getInFlightCall(userId, sessionId);

        // ── Step 2: trigger interrupt (InterruptControl flag = true) ──────────
        try {
            HarnessAgent agent = runner.getAgent();
            // Use the specific userId + sessionId to target the correct session.
            // The no-arg interrupt() uses defaultSessionId which may not match.
            agent.getDelegate().interrupt(userId, sessionId);
            log.info("v2 /chat/interrupt: interrupt triggered for sessionId={}, userId={}, hasInFlight={}",
                    sessionId, userId, inFlight != null);
        } catch (Exception e) {
            log.warn("v2 /chat/interrupt: interrupt() failed for sessionId={}: {}",
                    sessionId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "interrupt failed: " + e.getMessage(), e);
        }

        // ── Step 3: wait for in-flight to terminate (if any) ───────────────
        if (inFlight != null) {
            try {
                inFlight.completion().get(INTERRUPT_WAIT_SECONDS, TimeUnit.SECONDS);
                log.info("v2 /chat/interrupt: in-flight terminated for sessionId={}", sessionId);
            } catch (TimeoutException te) {
                // Force-dispose the stuck subscription to stop burning tokens.
                Disposable d = inFlight.subscription().get();
                if (d != null && !d.isDisposed()) {
                    d.dispose();
                    log.warn("v2 /chat/interrupt: forcibly disposed in-flight subscription "
                            + "for sessionId={} after {}s timeout", sessionId, INTERRUPT_WAIT_SECONDS);
                }
            } catch (Exception e) {
                log.warn("v2 /chat/interrupt: in-flight completion returned error for sessionId={}: {}",
                        sessionId, e.getMessage());
            }
        }

        log.info("v2 /chat/interrupt: completed for sessionId={}", sessionId);
        return ResponseEntity.ok(Map.of("status", "interrupted", "conversationId", sessionId));
    }
}