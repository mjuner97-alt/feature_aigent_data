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
import com.agentscopea2a.v2.dto.InterruptResumeRequest;
import com.agentscopea2a.v2.runner.HarnessA2aRunnerV2;
import com.agentscopea2a.v2.service.V2ChatStreamService;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Single-request interrupt + supplement + auto-resume endpoint.
 *
 * <p>{@code POST /v2/ai/chat/interrupt} lets a user mid-flight abort the current
 * agent call for a session, supply redirect info, and resume via a single HTTP
 * request - the server handles the interrupt -> wait-for-terminate -> start-resume-stream
 * orchestration. The frontend does NOT need to send a follow-up {@code /v2/ai/chat};
 * the response SSE stream IS the resume.
 *
 * <p>Flow:
 * <ol>
 *   <li>Resolve {@code (userId, conversationId, supplement)} from the request body
 *   <li>Fetch the in-flight call descriptor (if any) from {@link V2ChatStreamService#getInFlightCall}
 *   <li>Call {@code agent.getDelegate().interrupt(userId, sessionId, supplementMsg)}
 *       - sets {@link io.agentscope.core.interruption.InterruptControl} flag and stores
 *       the supplement as {@code userMessage} for audit (NOT auto-injected into context)
 *   <li>If an in-flight call exists, wait up to 30s for its completion future:
 *       <ul>
 *         <li>On complete: in-flight call has terminated (handleInterrupt + saveStateToSession
 *             flushed to MySQL) - safe to start the resume stream
 *         <li>On timeout: force-dispose the in-flight subscription to stop burning
 *             tokens, return 504 Gateway Timeout
 *       </ul>
 *   <li>Start a new {@link V2ChatStreamService#stream} call with {@code supplement} as
 *       the user input - framework's {@code callSerializationKey} queues this behind
 *       any still-terminating in-flight lifecycle, then {@code beforeAgentExecution}
 *       reloads state (with the recovery msg appended), {@code interruptControl.reset()}
 *       clears the flag, and the LLM continues with the supplement + saved history
 *   <li>Return the new {@link SseEmitter}; response header {@code X-Resume-Stream: true}
 *       tells the frontend this is a resume stream and the original /v2/ai/chat SSE
 *       should be closed (revision #5)
 * </ol>
 *
 * <p>See {@code docs/rc2-to-rc5/interrupt-resume-single-endpoint-plan.md} for the full
 * design including limitations (sub-agent internal resume is NOT supported).
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
     * to force-dispose + 504.
     */
    private static final long INTERRUPT_WAIT_SECONDS = 180;

    private final ObjectProvider<HarnessA2aRunnerV2> runnerProvider;
    private final V2ChatStreamService chatStreamService;

    public V2ChatInterruptController(ObjectProvider<HarnessA2aRunnerV2> runnerProvider,
                                     V2ChatStreamService chatStreamService) {
        this.runnerProvider = runnerProvider;
        this.chatStreamService = chatStreamService;
    }

    @PostMapping(value = "/interrupt", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> interruptAndResume(@RequestBody InterruptResumeRequest req) {
        // ── Parameter validation ─────────────────────────────────────────────
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
        }
        String userId = req.getUserId();
        String sessionId = req.getConversationId();
        String supplement = req.getSupplement();

        if (sessionId == null || sessionId.isBlank()) {
            // Revision #6: conversationId must be explicitly passed by the original /v2/ai/chat;
            // server-generated UUID sessions cannot be interrupted because the client doesn't
            // know the id.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "conversationId is required; the original /v2/ai/chat must explicitly pass conversation_id to use interrupt");
        }
        if (supplement == null || supplement.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "supplement is required; for a no-supplement stop, just close the SSE connection");
        }

        HarnessA2aRunnerV2 runner = runnerProvider.getIfAvailable();
        if (runner == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "HarnessA2aRunnerV2 bean not available");
        }

        log.info("v2 /chat/interrupt: sessionId={}, userId={}, supplementLen={}",
                sessionId, userId, supplement.length());

        // ── Step 1: fetch in-flight descriptor (may be null) ─────────────────
        V2ChatStreamService.InFlightCall inFlight = chatStreamService.getInFlightCall(userId, sessionId);

        // ── Step 2: trigger interrupt with supplement as userMessage (audit only) ─
        Msg supplementMsg = Msg.builder().role(MsgRole.USER)
                .content(TextBlock.builder().text(supplement).build())
                .build();
        try {
            HarnessAgent agent = runner.getAgent(userId != null ? Long.parseLong(userId) : null);
            agent.getDelegate().interrupt(userId, sessionId, supplementMsg);
            log.info("v2 /chat/interrupt: interrupt triggered for sessionId={}, hasInFlight={}",
                    sessionId, inFlight != null);
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

                Disposable d = inFlight.subscription().get();
                if (d != null && !d.isDisposed()) {
                    d.dispose();
                    log.warn("v2 /chat/interrupt: forcibly disposed in-flight subscription "
                            + "for sessionId={} after {}s timeout, proceeding to resume stream",
                            sessionId, INTERRUPT_WAIT_SECONDS);
                }
                // Don't throw 504 - fall through to start the resume stream.
                // The inFlight entry has been removed by cleanup (synchronously
                // triggered by dispose above), so stream()'s putIfAbsent will succeed.
            } catch (Exception e) {
                // completion future completed exceptionally - in-flight ended in error,
                // but state may still be saved; proceed to start resume stream anyway
                log.warn("v2 /chat/interrupt: in-flight completion returned error for sessionId={}: {}",
                        sessionId, e.getMessage());
            }
        }

        // ── Step 4: start the resume stream with supplement as input ────────
        ChatRequest resumeReq = ChatRequest.builder()
                .question(supplement)
                .conversationId(sessionId)
                .userId(userId)
                .build();
        SseEmitter emitter = chatStreamService.stream(resumeReq);

        // Revision #5: header tells the frontend this is a resume stream so it
        // knows to close the original /v2/ai/chat SSE connection.
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Resume-Stream", "true");
        headers.add("X-Original-Conversation-Id", sessionId);
        return ResponseEntity.ok().headers(headers).body(emitter);
    }
}
