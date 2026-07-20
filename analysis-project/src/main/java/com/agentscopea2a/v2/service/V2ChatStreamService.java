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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import reactor.core.Disposable;

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

    /**
     * Returns the in-flight call descriptor for the given {@code (userId, sessionId)}
     * session, or {@code null} if no call is currently active.
     *
     * <p>Used by {@code POST /v2/ai/chat/interrupt} to:
     * <ul>
     *   <li>Detect whether an in-flight call exists (null -> skip wait, start new stream directly)
     *   <li>Wait for the in-flight call's completion future (with timeout) before starting
     *       the resume stream - this ensures {@code handleInterrupt + saveStateToSession}
     *       has flushed to MySQL before the new call's {@code activateSlotForContext} reloads state
     *   <li>Force-dispose the in-flight subscription if the wait times out (30s)
     *       to stop burning LLM tokens on a stuck call
     * </ul>
     *
     * <p>The returned {@link InFlightCall#completion()} is completed (or completed
     * exceptionally) when the corresponding {@link SseEmitter} cleanup runs - i.e.
     * on {@code onCompletion}/{@code onTimeout}/{@code onError}. The cleanup
     * removes the entry from the in-flight map BEFORE completing the future, so
     * a concurrent {@code stream()} call cannot register a new future that the
     * stale cleanup would then wipe (see Plan B revision #3 in
     * {@code docs/rc2-to-rc5/interrupt-resume-single-endpoint-plan.md}).
     *
     * @param userId    the user identity (nullable - normalised to "__anon__" internally)
     * @param sessionId the session/conversation id
     * @return the in-flight descriptor, or {@code null} if no active call
     */
    InFlightCall getInFlightCall(String userId, String sessionId);

    /**
     * In-flight call descriptor exposed for the interrupt endpoint.
     *
     * <ul>
     *   <li>{@code completion()} - completes when the SSE stream terminates (done/error/timeout/disconnect).
     *       Used by the interrupt endpoint to wait for the in-flight call's
     *       {@code handleInterrupt + saveStateToSession} to flush before starting
     *       the resume stream.
     *   <li>{@code subscription()} - holds the Reactor {@link Disposable} so the
     *       interrupt endpoint can force-cancel the in-flight subscription on
     *       timeout (revision #4).
     * </ul>
     */
    final class InFlightCall {
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private final AtomicReference<Disposable> subscription = new AtomicReference<>();

        public CompletableFuture<Void> completion() { return completion; }
        public AtomicReference<Disposable> subscription() { return subscription; }
    }
}
