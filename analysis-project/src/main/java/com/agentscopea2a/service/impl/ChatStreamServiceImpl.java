/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.agentscopea2a.service.impl;

import com.agentscopea2a.harness.artifact.ArtifactContext;
import com.agentscopea2a.harness.artifact.ArtifactStore;
import com.agentscopea2a.harness.cache.ResponseCacheService;
import com.agentscopea2a.agent.dimension.DimensionStateManager;
import com.agentscopea2a.dto.ChatRequest;
import com.agentscopea2a.entity.AiChatResult;
import com.agentscopea2a.harness.hooks.ResponseCacheHook;
import com.agentscopea2a.service.ChatStreamService;
import com.agentscopea2a.service.SupervisorService;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.state.SimpleSessionKey;
import io.agentscope.harness.agent.HarnessAgent;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Default {@link ChatStreamService} implementation.
 *
 * <p>Two send strategies, picked per-request from the resolved {@code agentId}:
 *
 * <ul>
 *   <li><b>structured</b> (always now, since {@code agentId} defaults to {@code "7"}) — every
 *       chunk is wrapped in {@link AiChatResult} with {@code ansUUID}, agent identity, and
 *       cumulative text.
 *   <li><b>raw</b> — only used if some future caller forces {@code agentId} to blank explicitly;
 *       sends the chunk text as the SSE data verbatim. Kept as a fallback so the wire format is
 *       still trivially debuggable with curl.
 * </ul>
 */
@Service
public class ChatStreamServiceImpl implements ChatStreamService {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamServiceImpl.class);

    /** SseEmitter timeout. 0 = never time out (rely on agent completion / errors). */
    private static final long SSE_TIMEOUT_MS = 0L;

    /** Defaults filled in when the request omits these identity fields. */
    private static final String DEFAULT_AGENT_ID = "7";

    private static final String DEFAULT_AGENT_NAME = "QA助手";
    private static final String DEFAULT_FROM_TYPE = "HXY";

    private final SupervisorService supervisorService;
    private final ResponseCacheService cacheService;
    private final DimensionStateManager cacheDimManager;
    private final MeterRegistry meterRegistry;
    private final ArtifactStore artifactStore;

    public ChatStreamServiceImpl(
            SupervisorService supervisorService,
            ResponseCacheService cacheService,
            DimensionStateManager cacheDimManager,
            MeterRegistry meterRegistry,
            ArtifactStore artifactStore) {
        this.supervisorService = supervisorService;
        this.cacheService = cacheService;
        this.cacheDimManager = cacheDimManager;
        this.meterRegistry = meterRegistry;
        this.artifactStore = artifactStore;
    }

    @Override
    public SseEmitter stream(ChatRequest req) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        // Apply all defaulting/derivation rules in one place.
        Resolved resolved = normalize(req);

        String sid = firstNonBlank(req.getSessionId(), resolved.conversationId);
        final RuntimeContext ctx =
                RuntimeContext.builder()
                        .sessionId(sid)
                        .sessionKey(SimpleSessionKey.of(sid))
                        .build();

        ResponseCacheHook cacheHook =
                supervisorService.newCacheHook(cacheService, cacheDimManager, ctx, meterRegistry);
        HarnessAgent agent = supervisorService.build(cacheHook, ctx);

        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text(req.getInput()).build())
                        .build();

        final String ansUUID = UUID.randomUUID().toString();
        final StringBuilder accumulated = new StringBuilder();

        // Cleanup runs once on terminal signal. Mirrors HarnessA2aRunner's doFinally GC.
        AtomicReference<Boolean> cleaned = new AtomicReference<>(false);
        Runnable cleanup =
                () -> {
                    if (!cleaned.compareAndSet(false, true)) return;
                    try {
                        artifactStore.cleanupTask(ArtifactContext.from(ctx));
                    } catch (Exception ex) {
                        log.warn("Artifact cleanup failed for /chatA2A: {}", ex.getMessage());
                    }
                };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(t -> cleanup.run());

        agent.stream(userMsg, ctx)
                .subscribe(
                        event -> sendChunk(emitter, resolved, event, ansUUID, accumulated),
                        err -> handleStreamError(emitter, resolved, err, ansUUID),
                        () -> {
                            sendDone(emitter, resolved, ansUUID, accumulated);
                            emitter.complete();
                        });

        return emitter;
    }

    // ---------- request normalization ------------------------------------------------------

    /**
     * Apply all the request-shape rules in one place:
     *
     * <ul>
     *   <li>{@code agentId} blank → "7"; {@code agentName} blank → "QA助手"; {@code formType}
     *       blank → "HXY".
     *   <li>{@code conversationId} blank → fresh UUID, UNLESS the request had no agentId AND a
     *       non-blank {@code chatId}, in which case {@code chatId} is promoted to
     *       {@code conversationId} (legacy chat-handle compatibility).
     *   <li>{@code structured} = true whenever {@code agentId} resolves non-blank (which, with
     *       the default above, is always).
     * </ul>
     */
    static Resolved normalize(ChatRequest req) {
        if (req == null) {
            return new Resolved(
                    DEFAULT_AGENT_ID,
                    DEFAULT_AGENT_NAME,
                    DEFAULT_FROM_TYPE,
                    UUID.randomUUID().toString(),
                    true);
        }
        boolean agentIdProvided = isNotBlank(req.getAgentId());
        String agentId = agentIdProvided ? req.getAgentId() : DEFAULT_AGENT_ID;
        String agentName = isNotBlank(req.getAgentName()) ? req.getAgentName() : DEFAULT_AGENT_NAME;
        String formType = isNotBlank(req.getFormType()) ? req.getFormType() : DEFAULT_FROM_TYPE;

        String conversationId;
        if (isNotBlank(req.getConversationId())) {
            // Forwarded as-is so the model sees a stable id.
            conversationId = req.getConversationId();
        } else if (!agentIdProvided && isNotBlank(req.getChatId())) {
            // No agentId, but a chatId is present → promote chatId.
            conversationId = req.getChatId();
        } else {
            conversationId = UUID.randomUUID().toString();
        }

        // structured iff agentId is non-blank; with the default it always is, but keep the
        // branch for callers that explicitly clear it.
        boolean structured = isNotBlank(agentId);
        return new Resolved(agentId, agentName, formType, conversationId, structured);
    }

    /** Resolved per-request identity, computed once and reused for every chunk. */
    record Resolved(
            String agentId,
            String agentName,
            String formType,
            String conversationId,
            boolean structured) {}

    // ---------- SSE write paths ------------------------------------------------------------

    private static void sendChunk(
            SseEmitter emitter,
            Resolved resolved,
            Event event,
            String ansUUID,
            StringBuilder accumulated) {

        EventType type = event.getType();
        if (type == EventType.AGENT_RESULT) {
            return;
        }
        if (event.isLast()) {
            return;
        }
        String text = extractText(event.getMessage());
        if (text.isEmpty()) return;
        try {
            String name = type.name().toLowerCase();
            if (resolved.structured) {
                accumulated.append(text);
                AiChatResult chunk =
                        AiChatResult.builder()
                                .code(0)
                                .ansUUID(ansUUID)
                                .lineResult(text)
                                .resultAll(accumulated.toString())
                                .formType(resolved.formType)
                                .agentId(resolved.agentId)
                                .agentName(resolved.agentName)
                                .conversationId(resolved.conversationId)
                                .build();
                emitter.send(
                        SseEmitter.event()
                                .id(ansUUID)
                                .name(name)
                                .data(chunk, MediaType.APPLICATION_JSON));
            } else {
                emitter.send(SseEmitter.event().name(name).data(text));
            }
        } catch (IOException e) {
            log.warn("SSE send failed for agentId={}: {}", resolved.agentId, e.getMessage());
            emitter.completeWithError(e);
        }
    }

    private static void sendDone(
            SseEmitter emitter,
            Resolved resolved,
            String ansUUID,
            StringBuilder accumulated) {
        try {
            if (resolved.structured) {
                AiChatResult done =
                        AiChatResult.builder()
                                .code(0)
                                .ansUUID(ansUUID)
                                .lineResult("")
                                .resultAll(accumulated.toString())
                                .formType(resolved.formType)
                                .agentId(resolved.agentId)
                                .agentName(resolved.agentName)
                                .conversationId(resolved.conversationId)
                                .build();
                emitter.send(
                        SseEmitter.event()
                                .id(ansUUID)
                                .name("done")
                                .data(done, MediaType.APPLICATION_JSON));
            } else {
                emitter.send(SseEmitter.event().name("done").data("[DONE]"));
            }
        } catch (IOException e) {
            log.warn("SSE done frame failed for agentId={}: {}", resolved.agentId, e.getMessage());
        }
    }

    private void handleStreamError(
            SseEmitter emitter, Resolved resolved, Throwable err, String ansUUID) {
        ResponseCacheHook.CacheHitException cacheHit = unwrapCacheHit(err);
        if (cacheHit != null) {
            log.info("Cache HIT for /chatA2A");
            String cached = cacheHit.getCachedResponse();
            try {
                if (resolved.structured) {
                    AiChatResult chunk =
                            AiChatResult.builder()
                                    .code(0)
                                    .ansUUID(ansUUID)
                                    .lineResult(cached)
                                    .resultAll(cached)
                                    .formType(resolved.formType)
                                    .agentId(resolved.agentId)
                                    .agentName(resolved.agentName)
                                    .conversationId(resolved.conversationId)
                                    .build();
                    emitter.send(
                            SseEmitter.event()
                                    .id(ansUUID)
                                    .name("reasoning")
                                    .data(chunk, MediaType.APPLICATION_JSON));
                    AiChatResult done =
                            AiChatResult.builder()
                                    .code(0)
                                    .ansUUID(ansUUID)
                                    .lineResult("")
                                    .resultAll(cached)
                                    .formType(resolved.formType)
                                    .agentId(resolved.agentId)
                                    .agentName(resolved.agentName)
                                    .conversationId(resolved.conversationId)
                                    .build();
                    emitter.send(
                            SseEmitter.event()
                                    .id(ansUUID)
                                    .name("done")
                                    .data(done, MediaType.APPLICATION_JSON));
                } else {
                    emitter.send(SseEmitter.event().name("reasoning").data(cached));
                    emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                }
                emitter.complete();
            } catch (IOException ioe) {
                emitter.completeWithError(ioe);
            }
            return;
        }
        log.error("Agent error", err);
        sendError(emitter, resolved, "Agent error: " + err.getMessage());
        emitter.complete();
    }

    private static void sendError(SseEmitter emitter, Resolved resolved, String message) {
        try {
            if (resolved != null && resolved.structured) {
                AiChatResult err =
                        AiChatResult.builder()
                                .code(500)
                                .ansUUID(UUID.randomUUID().toString())
                                .errorMsg(message)
                                .formType(resolved.formType)
                                .agentId(resolved.agentId)
                                .agentName(resolved.agentName)
                                .conversationId(resolved.conversationId)
                                .build();
                emitter.send(
                        SseEmitter.event()
                                .name("error")
                                .data(err, MediaType.APPLICATION_JSON));
            } else {
                emitter.send(SseEmitter.event().name("error").data(message));
            }
        } catch (IOException ignore) {
            // Connection already closed — nothing to do.
        }
    }

    // ---------- helpers --------------------------------------------------------------------

    private static ResponseCacheHook.CacheHitException unwrapCacheHit(Throwable t) {
        Throwable cur = t;
        for (int i = 0; i < 8 && cur != null; i++) {
            if (cur instanceof ResponseCacheHook.CacheHitException ch) return ch;
            cur = cur.getCause();
        }
        return null;
    }

    private static String extractText(Msg msg) {
        if (msg == null || msg.getContent() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (var block : msg.getContent()) {
            if (block instanceof TextBlock tb) {
                sb.append(tb.getText());
            }
        }
        return sb.toString();
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String firstNonBlank(String... candidates) {
        if (candidates == null) return UUID.randomUUID().toString();
        for (String c : candidates) {
            if (isNotBlank(c)) return c;
        }
        return UUID.randomUUID().toString();
    }
}
