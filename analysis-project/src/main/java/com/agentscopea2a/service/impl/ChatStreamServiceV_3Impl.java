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

import cn.hutool.core.util.ObjectUtil;
import com.agentscopea2a.agent.dimension.DimensionStateManager;
import com.agentscopea2a.dto.ChatRequest;
import com.agentscopea2a.dto.QuestionAnswerDto;
import com.agentscopea2a.entity.AiChatResult;
import com.agentscopea2a.harness.artifact.ArtifactContext;
import com.agentscopea2a.harness.artifact.ArtifactStore;
import com.agentscopea2a.harness.cache.ResponseCacheService;
import com.agentscopea2a.harness.hooks.ResponseCacheHook;
import com.agentscopea2a.mapper.db1.MainAgentMapper;
import com.agentscopea2a.service.ChatStreamServiceV_3;
import com.agentscopea2a.service.SupervisorService;
import com.agentscopea2a.util.SseEmitterCacheUtil;
import com.agentscopea2a.util.ThreadContextUtils;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.model.ModelException;
import io.agentscope.core.state.SimpleSessionKey;
import io.agentscope.harness.agent.HarnessAgent;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Default {@link ChatStreamServiceV_3} implementation.
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
public class ChatStreamServiceV_3Impl implements ChatStreamServiceV_3 {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamServiceV_3Impl.class);

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

    public ChatStreamServiceV_3Impl(
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

    // mapper 可选：MainAgentMapper 的 XML 实际为空且 GaussConfig 默认 disabled，
    // 真实环境下 bean 可能缺失；这里降级为 required=false，缺失时跳过 DB 落库。
    @Autowired(required = false)
    private MainAgentMapper mainAgentMapper;

    /**
     * 构建 RuntimeContext。
     *
     * <p>sessionId 沿用 conversationId（与历史行为一致）；userId 仅在 ChatRequest 显式传入且
     * 非空时透传，让 ResponseCacheHook.tenantBucket() 落到 {@code u:<userId>} 分桶，
     * 跨 session 同 user 同问题可命中缓存。
     */
    private RuntimeContext buildRuntimeContext(ChatRequest req) {
        RuntimeContext.Builder b =
                RuntimeContext.builder()
                        .sessionId(req.getConversationId())
                        .sessionKey(SimpleSessionKey.of(req.getConversationId()));
        if (StringUtils.isNotBlank(req.getUserId())) {
            b.userId(req.getUserId());
        }
        return b.build();
    }

    @Override
    public SseEmitter stream(ChatRequest req) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        // Apply all defaulting/derivation rules in one place.
        Resolved resolved = normalize(req);

        // 注册 emitter 到缓存，便于外部按 conversationId 查询/管理
        SseEmitterCacheUtil.put(req.getConversationId(), emitter);

        final RuntimeContext ctx = buildRuntimeContext(req);

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
        // 思考块独立累积，与 answer 分开落库（对齐 ChatStreamServiceV_2Impl 的 think/answer 拆分）
        final StringBuilder thinkAccumulated = new StringBuilder();

        // Cleanup runs once on terminal signal. Mirrors HarnessA2aRunner's doFinally GC.
        AtomicReference<Boolean> cleaned = new AtomicReference<>(false);
        Runnable cleanup =
                () -> {
                    if (!cleaned.compareAndSet(false, true)) return;
                    try {
                        artifactStore.cleanupTask(ArtifactContext.from(ctx));
                    } catch (Exception ex) {
                        log.warn("Artifact cleanup failed for /ai/chat: {}", ex.getMessage());
                    }
                };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(t -> cleanup.run());

        // 配置 StreamOptions，开启增量事件模式
        StreamOptions streamOptions = StreamOptions.builder()
                .eventTypes(EventType.ALL)
                .incremental(true)
                .build();

        agent.stream(List.of(userMsg), streamOptions, ctx)
                .onErrorResume(
                        ResponseCacheHook.CacheHitException.class,
                        e -> emitCachedResponse(emitter, resolved, ansUUID, accumulated, req, thinkAccumulated, e.getCachedResponse()))
                .subscribe(
                        event -> sendChunk(emitter, resolved, event, ansUUID, accumulated, thinkAccumulated),
                        err -> handleStreamError(emitter, resolved, err, ansUUID, accumulated, thinkAccumulated, req),
                        () -> {
                            sendDone(emitter, resolved, ansUUID, accumulated);
                            emitter.complete();
                            ThreadContextUtils.clearContext();
                            saveAnswerIntoDB(req, thinkAccumulated.toString(), accumulated.toString());
                        });

        return emitter;
    }

    @Override
    public SseEmitter streamPublic(ChatRequest req) {
        // streamPublic 使用与 stream() 相同的 AiChatResult 格式输出
        return stream(req);
    }


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
        String formType = isNotBlank(req.getFromType()) ? req.getFromType() : DEFAULT_FROM_TYPE;

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
            StringBuilder accumulated,
            StringBuilder thinkAccumulated) {

        EventType type = event.getType();
        String text = extractText(event.getMessage());
        // 思考内容独立累积，不通过 SSE 下发（保持原行为），仅落库
        String thinking = extractThinking(event.getMessage());
        if (!thinking.isEmpty()) {
            thinkAccumulated.append(thinking);
        }
        // Log every event regardless of whether it will be sent to the frontend.
        log.info(
                "stream event: agentId={} type={} last={} textLen={} preview={}",
                resolved.agentId,
                type,
                event.isLast(),
                text.length(),
                preview(text));
        if (type == EventType.AGENT_RESULT) {
            return;
        }
        if (event.isLast()) {
            return;
        }
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

    /**
     * 缓存命中回放：直接把 cachedResponse 当作完整结果通过 SSE 流回放。
     *
     * <p>{@link ResponseCacheHook#handlePreCall} 命中时抛 {@link
     * ResponseCacheHook.CacheHitException} 短路 agent 执行。本方法承接它，按
     * "text(cached) → done" 的顺序补齐帧，前端 UX 与 MISS 走完整链路时基本一致。
     *
     * <p>同时往 accumulated 写入 cached 文本，后续 complete 回调的 DB 落库能拿到答案。
     */
    private Flux<Event> emitCachedResponse(
            SseEmitter emitter, Resolved resolved, String ansUUID,
            StringBuilder accumulated, ChatRequest req,
            StringBuilder thinkAccumulated, String cached) {
        log.info("Cache HIT for /ai/chat");
        accumulated.setLength(0);
        accumulated.append(cached);
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
                                .name("text")
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
                emitter.send(SseEmitter.event().name("text").data(cached));
                emitter.send(SseEmitter.event().name("done").data("[DONE]"));
            }
            emitter.complete();
        } catch (IOException ioe) {
            emitter.completeWithError(ioe);
        } finally {
            ThreadContextUtils.clearContext();
            saveAnswerIntoDB(req, thinkAccumulated.toString(), accumulated.toString());
        }
        return Flux.empty();
    }

    private void handleStreamError(
            SseEmitter emitter,
            Resolved resolved,
            Throwable err,
            String ansUUID,
            StringBuilder accumulated,
            StringBuilder thinkAccumulated,
            ChatRequest req) {
        ResponseCacheHook.CacheHitException cacheHit = unwrapCacheHit(err);
        if (cacheHit != null) {
            log.info("Cache HIT for /ai/chat");
            String cached = cacheHit.getCachedResponse();
            // 缓存命中视为正式回答，覆盖到 accumulated 用于落库
            accumulated.setLength(0);
            accumulated.append(cached);
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
                                    .name("text")
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
                    emitter.send(SseEmitter.event().name("text").data(cached));
                    emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                }
                emitter.complete();
            } catch (IOException ioe) {
                emitter.completeWithError(ioe);
            } finally {
                ThreadContextUtils.clearContext();
                saveAnswerIntoDB(req, thinkAccumulated.toString(), accumulated.toString());
            }
            return;
        }
        log.error("Agent error", err);
        sendError(emitter, resolved, buildErrorMessage(err));
        emitter.complete();
        ThreadContextUtils.clearContext();
        saveAnswerIntoDB(req, thinkAccumulated.toString(), accumulated.toString());
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

    /** 提取 Msg 中所有 ThinkingBlock 的内容，用于落库时填充 think 字段。 */
    private static String extractThinking(Msg msg) {
        if (msg == null || msg.getContent() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (var block : msg.getContent()) {
            if (block instanceof ThinkingBlock tb) {
                String thinking = tb.getThinking();
                if (thinking != null) {
                    sb.append(thinking);
                }
            }
        }
        return sb.toString();
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** Truncates and one-lines text for log output so a single event stays on one line. */
    private static String preview(String text) {
        if (text == null || text.isEmpty()) return "";
        String oneLine = text.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() <= 120 ? oneLine : oneLine.substring(0, 120) + "…";
    }

    private static String firstNonBlank(String... candidates) {
        if (candidates == null) return UUID.randomUUID().toString();
        for (String c : candidates) {
            if (isNotBlank(c)) return c;
        }
        return UUID.randomUUID().toString();
    }

    /**
     * 构建分类友好的错误消息。
     *
     * <p>针对不同异常类型返回可读的中文提示，避免将原始异常栈泄露给前端。
     */
    private String buildErrorMessage(Throwable error) {
        if (error.getMessage().contains("Retries exhausted") || error.getMessage().contains("Model request timeout after")) {
            return "请求已达最大重试次数，当前千问模型资源不足，请稍后再试。";
        } else if (error instanceof ModelException) {
            return "模型请求出错: " + error.getMessage();
        } else {
            return error.getMessage();
        }
    }

    // ---------- DB 落库（对齐 ChatStreamServiceV_2Impl#saveAnswerIntoDB） -----------------

    /**
     * 保存问答记录到数据库，包含 think（思考）和 answer（结果）两个独立字段。
     *
     * <p>mapper 在 GaussConfig 关闭时可能不存在（{@code required = false}），此时静默跳过。
     */
    private void saveAnswerIntoDB(ChatRequest req, String thinkContent, String answerContent) {
        if (req == null || StringUtils.isEmpty(req.getConversationId())) {
            return;
        }
        QuestionAnswerDto dto = createAnswerInit(req, thinkContent, answerContent);
        saveAnswerIntoDB(dto);
    }

    private QuestionAnswerDto createAnswerInit(ChatRequest chatReqDTO, String thinkContent, String answerContent) {
        QuestionAnswerDto questionAnswerDTO = new QuestionAnswerDto();
        questionAnswerDTO.setUserId(chatReqDTO.getUserId());
        questionAnswerDTO.setQuestion(chatReqDTO.getInput());
        questionAnswerDTO.setAnsUUID(chatReqDTO.getConversationId());
        questionAnswerDTO.setConversationId(chatReqDTO.getConversationId());
        questionAnswerDTO.setFromType(chatReqDTO.getFromType());
        questionAnswerDTO.setThink(thinkContent);
        questionAnswerDTO.setAnswer(answerContent);
        return questionAnswerDTO;
    }

    private void saveAnswerIntoDB(QuestionAnswerDto questionAnswerDTO) {
        if (mainAgentMapper == null) {
            return;
        }
        if (ObjectUtil.isNotNull(questionAnswerDTO) && StringUtils.isNotEmpty(questionAnswerDTO.getConversationId())) {
            // 根据id查询是否有记录
            QuestionAnswerDto historyQuestionAnswer = mainAgentMapper.selectAnswerRecordByTaskId(questionAnswerDTO.getConversationId());
            if (ObjectUtil.isEmpty(historyQuestionAnswer)) {
                questionAnswerDTO.setAnsUUID(questionAnswerDTO.getConversationId());
                mainAgentMapper.insertAiUserTable(questionAnswerDTO);
                mainAgentMapper.insertAnswerTable(questionAnswerDTO);
            }
            mainAgentMapper.insertToQualityAnalysisConversationAnswer(questionAnswerDTO);
        }
    }
}
