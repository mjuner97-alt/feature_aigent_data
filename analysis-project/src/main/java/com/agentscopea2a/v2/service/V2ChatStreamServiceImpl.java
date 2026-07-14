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

        // Thread-safe accumulator for the full response text.
        StringBuilder accumulated = new StringBuilder();

        // CAS-guarded cleanup: artifact GC, episodic persist, ThreadLocal unbind.
        AtomicBoolean cleaned = new AtomicBoolean(false);
        Runnable cleanup = () -> {
            if (!cleaned.compareAndSet(false, true)) return;
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
                    episodicMemory.recordSessionWithToolContext(conversationId, sessionMessages, toolCallJson)
                            .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                            .subscribe(
                                    null,
                                    ex -> log.warn("Episodic persist failed for sessionId={}: {}", conversationId, ex.getMessage()),
                                    () -> log.debug("Episodic persist completed for sessionId={}", conversationId));
                }
            } catch (Exception ex) {
                log.warn("Episodic persist setup failed for sessionId={}: {}", conversationId, ex.getMessage());
            }
            collector.unbind();
        };

        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        String ansUUID = UUID.randomUUID().toString();
        String agentId = req.getAgentId() != null ? req.getAgentId() : "7";
        String agentName = req.getAgentName() != null ? req.getAgentName() : "QA助手";
        String formType = req.getFromType() != null ? req.getFromType() : "HXY";

        Executors.newSingleThreadExecutor().submit(() -> {
            // Bind collector to the executor thread so ToolCallTrackingHook can access it.
            collector.bind();
            try {
                Flux<AgentEvent> eventFlux = runner.streamEvents(List.of(userMsg), ctx);

                eventFlux.doOnNext(event -> {
                    try {
                        handleEvent(event, emitter, accumulated, ansUUID, agentId, agentName, formType, conversationId);
                    } catch (Exception e) {
                        log.warn("SSE send failed for sessionId={}: {}", conversationId, e.getMessage());
                    }
                }).doOnComplete(() -> {
                    try {
                        sendDone(emitter, accumulated, ansUUID, agentId, agentName, formType, conversationId);
                    } catch (Exception e) {
                        log.warn("SSE done send failed for sessionId={}: {}", conversationId, e.getMessage());
                    }
                }).doOnError(e -> {
                    log.error("v2 stream error for sessionId={}: {}", conversationId, e.getMessage());
                    emitter.completeWithError(e);
                }).subscribe();
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