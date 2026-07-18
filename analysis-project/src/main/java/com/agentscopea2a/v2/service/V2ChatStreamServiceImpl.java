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
import com.agentscopea2a.entity.AiChatResult;
import com.agentscopea2a.v2.artifact.ArtifactContext;
import com.agentscopea2a.v2.artifact.ArtifactStore;
import com.agentscopea2a.v2.hooks.ToolCallTrackingHook;
import com.agentscopea2a.v2.memory.EpisodicMemory;
import com.agentscopea2a.v2.runner.HarnessA2aRunnerV2;
import com.agentscopea2a.v2.tools.ToolCallCollector;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import reactor.core.Disposable;

/**
 * v2 streaming service implementation.
 *
 * <p>Uses the shared {@link HarnessA2aRunnerV2} instance (thread-safe) and creates a
 * per-request {@link RuntimeContext} with sessionId + userId. Binds/unbinds a
 * {@link ToolCallCollector} ThreadLocal for tool-call tracking hooks.
 *
 * <p>Maps {@link AgentEvent} types to frontend-compatible SSE events:
 * <ul>
 *   <li>{@code TextBlockStartEvent} / {@code TextBlockDeltaEvent} → incremental text chunks</li>
 *   <li>{@code AgentResultEvent} → final result, cache-hit short-circuit</li>
 *   <li>Terminal: emits a {@code "done"} event with the full accumulated text</li>
 * </ul>
 */
@Service
public class V2ChatStreamServiceImpl implements V2ChatStreamService {

    private static final Logger log = LoggerFactory.getLogger(V2ChatStreamServiceImpl.class);
    private static final long SSE_TIMEOUT = 600_000L;

    private final HarnessA2aRunnerV2 runner;
    private final ArtifactStore artifactStore;
    private final EpisodicMemory episodicMemory;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public V2ChatStreamServiceImpl(HarnessA2aRunnerV2 runner, ArtifactStore artifactStore,
                                    EpisodicMemory episodicMemory) {
        this.runner = runner;
        this.artifactStore = artifactStore;
        this.episodicMemory = episodicMemory;
    }

    @Override
    public SseEmitter stream(ChatRequest req) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);

        String conversationId = resolveConversationId(req);
        String text = resolveInput(req);
        String userId = req.getUserId();

        log.info("v2 stream: sessionId={}, userId={}, textLen={}", conversationId, userId, text.length());

        // Per-request ToolCallCollector — will be bound to the executor thread so hooks can access it.
        ToolCallCollector collector = new ToolCallCollector(text);

        Msg userMsg = Msg.builder().role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();

        RuntimeContext ctx = buildRuntimeContext(conversationId, userId, text);
        // Store the per-request ToolCallCollector on RuntimeContext so ToolCallTrackingHook
        // (which implements RuntimeContextAware) can access it across reactive thread boundaries.
        // The previous ThreadLocal approach broke because the hook fires on reactor scheduler
        // threads, not the executor thread where the ThreadLocal was bound.
        ctx.put(ToolCallTrackingHook.COLLECTOR_CTX_KEY, collector);

        // Episodic memory session_id must be "user:<userId>:<conversationId>" so that:
        // 1) TraceMiner.loadSessions can group by session_id (each request = one session)
        // 2) extractUserId can parse userId from the "user:userId:..." prefix
        // 3) findActiveUsers fallback (session_id LIKE 'user:%') still discovers the rows
        // Using a bare "user:userId" merges all of a user's requests into one session,
        // which prevents per-request trace mining.
        String episodicUserId = userId != null && !userId.isBlank() ? userId : "anonymous";
        String episodicSessionId = "user:" + episodicUserId + ":" + conversationId;

        // Thread-safe accumulator for the full response text.
        StringBuilder accumulated = new StringBuilder();

        // Holds the reactive stream subscription so we can cancel it when the client disconnects.
        // Without this, the agent keeps running (and burning LLM tokens) after the SSE emitter
        // fires onCompletion/onTimeout/onError. The assignment happens on the executor thread
        // inside subscribe(); cleanup reads it from the SSE callback thread.
        AtomicReference<Disposable> subscription = new AtomicReference<>();

        // CAS-guarded cleanup: cancel stream, artifact GC, episodic persist, ThreadLocal unbind.
        AtomicBoolean cleaned = new AtomicBoolean(false);
        Runnable cleanup = () -> {
            if (!cleaned.compareAndSet(false, true)) return;
            // Cancel the reactive stream first so the agent stops processing immediately.
            // dispose() is a no-op if the stream already completed or was never subscribed.
            Disposable d = subscription.get();
            if (d != null && !d.isDisposed()) {
                d.dispose();
                log.info("v2 stream cancelled for sessionId={} (client disconnect/timeout)", conversationId);
            }
            try {
                artifactStore.cleanupTask(ArtifactContext.from(ctx));
            } catch (Exception ex) {
                log.warn("Artifact cleanup failed for sessionId={}: {}", conversationId, ex.getMessage());
            }
            // Persist tool call context to episodic memory for skill distillation (paths B/C).
            // This must happen before unbind() so the collector data is still available.
            try {
                String toolCallJson = collector.toJson();
                if (toolCallJson != null && !toolCallJson.isEmpty()) {
                    List<Msg> sessionMessages = new ArrayList<>();
                    sessionMessages.add(userMsg);
                    String accumulatedText = accumulated.toString();
                    if (accumulatedText != null && !accumulatedText.isEmpty()) {
                        sessionMessages.add(Msg.builder().role(MsgRole.ASSISTANT)
                                .content(TextBlock.builder().text(accumulatedText).build())
                                .build());
                    }
                    episodicMemory.recordSessionWithToolContext(episodicSessionId, sessionMessages, toolCallJson)
                            .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                            .subscribe(
                                    null,
                                    ex -> log.warn("Episodic persist failed for sessionId={}: {}", episodicSessionId, ex.getMessage()),
                                    () -> log.debug("Episodic persist completed for sessionId={}", episodicSessionId));
                }
            } catch (Exception ex) {
                log.warn("Episodic persist setup failed for sessionId={}: {}", episodicSessionId, ex.getMessage());
            }
        };

        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        String ansUUID = UUID.randomUUID().toString();
        String agentId = req.getAgentId() != null ? req.getAgentId() : "7";
        String agentName = req.getAgentName() != null ? req.getAgentName() : "QA助手";
        String formType = req.getFromType() != null ? req.getFromType() : "HXY";

        Executors.newSingleThreadExecutor().submit(() -> {
            // ToolCallCollector is now propagated via RuntimeContext (set above), not ThreadLocal.
            // The framework pushes the context to ToolCallTrackingHook via RuntimeContextAware
            // before the call starts, so no bind() is needed here.
            try {
                Flux<AgentEvent> eventFlux = runner.streamEvents(List.of(userMsg), ctx);

                // Subscribe with explicit onNext/onError/onComplete consumers. subscribe() returns
                // a Disposable which we capture so the cleanup Runnable can cancel the stream when
                // the client disconnects (onCompletion/onTimeout/onError). Without this, the agent
                // keeps running and burning LLM tokens after the SSE connection drops.
                Disposable d = eventFlux.subscribe(
                        event -> {
                            try {
                                handleEvent(event, emitter, accumulated, ansUUID, agentId, agentName, formType, conversationId);
                            } catch (Exception e) {
                                log.warn("SSE send failed for sessionId={}: {}", conversationId, e.getMessage());
                            }
                        },
                        error -> {
                            // Bug B fix: SandboxLifecycleMiddleware 释放 sandbox 后，WorkspaceMessageBus
                            // 偶发访问 filesystem 抛 SandboxException。此时响应文本已通过 text_block_delta
                            // 流给客户端，应发 done 正常收尾，而非 completeWithError 导致 HTTP 500。
                            if (accumulated.length() > 0
                                    && error instanceof io.agentscope.harness.agent.sandbox.SandboxException) {
                                log.warn("v2 stream post-response sandbox error suppressed for sessionId={}: {}",
                                        conversationId, error.getMessage());
                                try {
                                    sendDone(emitter, accumulated, ansUUID, agentId, agentName, formType, conversationId);
                                } catch (Exception e) {
                                    log.warn("SSE done send failed during error recovery for sessionId={}: {}",
                                            conversationId, e.getMessage());
                                    emitter.complete();
                                }
                            } else {
                                log.error("v2 stream error for sessionId={}: {}", conversationId, error.getMessage());
                                emitter.completeWithError(error);
                            }
                        },
                        () -> {
                            try {
                                sendDone(emitter, accumulated, ansUUID, agentId, agentName, formType, conversationId);
                            } catch (Exception e) {
                                log.warn("SSE done send failed for sessionId={}: {}", conversationId, e.getMessage());
                            }
                        });
                subscription.set(d);
            } catch (Exception e) {
                log.error("v2 stream failed for sessionId={}", conversationId, e);
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    // ── Event handling ──────────────────────────────────────────────────────

    private void handleEvent(AgentEvent event, SseEmitter emitter,
                             StringBuilder accumulated, String ansUUID,
                             String agentId, String agentName, String formType,
                             String conversationId) throws Exception {

        // AgentResultEvent is the terminal event — handled in doOnComplete via sendDone
        if (event instanceof AgentResultEvent) {
            // Extract final text if present
            String text = extractText(((AgentResultEvent) event).getResult());
            if (text != null && !text.isEmpty()) {
                accumulated.append(text);
            }
            return;
        }

        // Extract incremental text from streaming events
        String chunk = null;
        if (event instanceof TextBlockDeltaEvent delta) {
            chunk = delta.getDelta();
        }
        // TextBlockStartEvent is a marker — it carries no text content

        if (chunk == null || chunk.isEmpty()) return;

        accumulated.append(chunk);

        AiChatResult result = AiChatResult.builder()
                .code(0)
                .ansUUID(ansUUID)
                .lineResult(chunk)
                .resultAll(accumulated.toString())
                .formType(formType)
                .agentId(agentId)
                .agentName(agentName)
                .source(event.getSource())
                .conversationId(conversationId)
                .build();

        String json = objectMapper.writeValueAsString(result);
        String eventName = event.getType() != null ? event.getType().name().toLowerCase() : "text";
        emitter.send(SseEmitter.event().name(eventName).data(json));
    }

    private void sendDone(SseEmitter emitter, StringBuilder accumulated,
                          String ansUUID, String agentId, String agentName,
                          String formType, String conversationId) throws Exception {
        AiChatResult done = AiChatResult.builder()
                .code(0)
                .ansUUID(ansUUID)
                .lineResult("")
                .resultAll(accumulated.toString())
                .formType(formType)
                .agentId(agentId)
                .agentName(agentName)
                .conversationId(conversationId)
                .build();

        String json = objectMapper.writeValueAsString(done);
        emitter.send(SseEmitter.event().name("done").data(json));
        emitter.complete();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private RuntimeContext buildRuntimeContext(String sessionId, String userId, String lastQuestion) {
        RuntimeContext.Builder builder = RuntimeContext.builder()
                .sessionId(sessionId);
        if (userId != null && !userId.isBlank()) {
            builder.userId(userId);
        } else {
            builder.userId("anonymous");
        }
        // Store the user's question so middleware/hooks can access it
        builder.put("lastQuestion", lastQuestion);
        return builder.build();
    }

    private String resolveConversationId(ChatRequest req) {
        if (req.getConversationId() != null && !req.getConversationId().isEmpty()) {
            return req.getConversationId();
        }
        if (req.getChatId() != null && !req.getChatId().isEmpty()) {
            return req.getChatId();
        }
        return UUID.randomUUID().toString();
    }

    private String resolveInput(ChatRequest req) {
        if (req.getInput() != null) return req.getInput();
        if (req.getQuestion() != null) return req.getQuestion();
        return "";
    }

    private String extractText(Msg msg) {
        if (msg == null) return null;
        return msg.getTextContent();
    }
}