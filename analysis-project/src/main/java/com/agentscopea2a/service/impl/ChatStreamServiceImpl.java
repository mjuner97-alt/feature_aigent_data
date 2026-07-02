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
import com.agentscopea2a.dto.response.*;
import com.agentscopea2a.harness.artifact.ArtifactContext;
import com.agentscopea2a.harness.artifact.ArtifactStore;
import com.agentscopea2a.harness.cache.ResponseCacheService;
import com.agentscopea2a.harness.hooks.ResponseCacheHook;
import com.agentscopea2a.mapper.db1.MainAgentMapper;
import com.agentscopea2a.service.ChatStreamService;
import com.agentscopea2a.service.SupervisorService;
import com.agentscopea2a.util.SseEmitterCacheUtil;
import com.agentscopea2a.util.ThreadContextUtils;
import com.alibaba.fastjson.JSON;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.*;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;


@Service
public class ChatStreamServiceImpl implements ChatStreamService {

    /** Per-request streaming state, carried through processChunk / processChunkPublic / handle*. */
    private static class StreamContext {
        final SseEmitter emitter;
        final ChatRequest req;
        final AtomicBoolean startFlag = new AtomicBoolean(false);
        final AtomicBoolean agentChange = new AtomicBoolean(false);
        final AtomicBoolean secondStartFlag = new AtomicBoolean(true);
        final StringBuilder fullResult = new StringBuilder();
        final StringBuilder thinkContent = new StringBuilder();
        final StringBuilder answerContent = new StringBuilder();
        final String uuid;

        StreamContext(SseEmitter emitter, ChatRequest req, String uuid) {
            this.emitter = emitter;
            this.req = req;
            this.uuid = uuid;
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(ChatStreamServiceImpl.class);

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

        if (StringUtils.isEmpty(req.getConversationId())){
            req.setConversationId(UUID.randomUUID().toString());
        }

        SseEmitterCacheUtil.put(req.getConversationId(), emitter);
        String uuid = UUID.randomUUID().toString();

        // 状态标记
        StreamContext ctxState = new StreamContext(emitter, req, uuid);

        final RuntimeContext ctx = buildRuntimeContext(req);

        ResponseCacheHook cacheHook =
                supervisorService.newCacheHook(cacheService, cacheDimManager, ctx, meterRegistry);
        HarnessAgent agent = supervisorService.build(cacheHook, ctx);

        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text(req.getInput()).build())
                        .build();

        // Cleanup runs once on terminal signal. Mirrors HarnessA2aRunner's doFinally GC.
        AtomicReference<Boolean> cleaned = new AtomicReference<>(false);
        Runnable cleanup =
                () -> {
                    if (!cleaned.compareAndSet(false, true)) return;
                    try {
                        artifactStore.cleanupTask(ArtifactContext.from(ctx));
                    } catch (Exception ex) {
                        LOGGER.warn("Artifact cleanup failed for /ai/chat: {}", ex.getMessage());
                    }
                };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(t -> cleanup.run());

        //配置StreamOptions
        StreamOptions streamOptions =StreamOptions.builder()
                        .eventTypes(EventType.ALL)
                                .incremental(true)
                                        .build();

        agent.stream(List.of(userMsg), streamOptions, ctx)
                .onErrorResume(
                        ResponseCacheHook.CacheHitException.class,
                        e -> {
                            emitCachedResponse(ctxState, e.getCachedResponse(), true);
                            return Flux.empty();
                        })
                .subscribe(
                chunk -> processChunk(chunk, ctxState),
                error -> handleStreamError(ctxState, error, ctxState.fullResult.toString()),
                () -> handleStreamSuccess(ctxState)
        );

        return emitter;
    }

    @Override
    public SseEmitter streamPublic(ChatRequest req) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        if (StringUtils.isEmpty(req.getConversationId())){
            req.setConversationId(UUID.randomUUID().toString());
        }

        SseEmitterCacheUtil.put(req.getConversationId(), emitter);
        String uuid = UUID.randomUUID().toString();

        // 状态标记
        StreamContext ctxState = new StreamContext(emitter, req, uuid);

        final RuntimeContext ctx = buildRuntimeContext(req);

        ResponseCacheHook cacheHook =
                supervisorService.newCacheHook(cacheService, cacheDimManager, ctx, meterRegistry);
        HarnessAgent agent = supervisorService.build(cacheHook, ctx);

        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text(req.getInput()).build())
                        .build();

        // Cleanup runs once on terminal signal. Mirrors HarnessA2aRunner's doFinally GC.
        AtomicReference<Boolean> cleaned = new AtomicReference<>(false);
        Runnable cleanup =
                () -> {
                    if (!cleaned.compareAndSet(false, true)) return;
                    try {
                        artifactStore.cleanupTask(ArtifactContext.from(ctx));
                    } catch (Exception ex) {
                        LOGGER.warn("Artifact cleanup failed for /ai/chat: {}", ex.getMessage());
                    }
                };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(t -> cleanup.run());

        //配置StreamOptions
        StreamOptions streamOptions =StreamOptions.builder()
                .eventTypes(EventType.ALL)
                .incremental(true)
                .build();

        agent.stream(List.of(userMsg), streamOptions, ctx)
                .onErrorResume(
                        ResponseCacheHook.CacheHitException.class,
                        e -> {
                            emitCachedResponse(ctxState, e.getCachedResponse(), false);
                            return Flux.empty();
                        })
                .subscribe(
                        chunk -> processChunkPublic(chunk, ctxState),
                        error -> handleStreamErrorPublic(ctxState, error, ctxState.fullResult.toString()),
                        () -> handleStreamSuccessPublic(ctxState)
                );

        return emitter;
    }

    /**
     * 处理单个流式 Chunk — 通用版（使用 TextResponseDto / ThinkManagerResponseDto）
     *
     * <p>业务逻辑同 {@link #processChunk}，只是 SSE 响应 DTO 不同（无 code/ansUUID 等 Manager 专属字段）。
     *
     * <p>执行智能体后续 chunk：TextBlock → 结果（action="已执行"，用 &lt;text&gt; 标签包裹），
     * 非 TextBlock → 思考（action="执行中"，用 思考 标签包裹）
     */
    private void processChunkPublic(Event chunk, StreamContext ctx) {
        try {
            System.out.println("主智能体回答: " + JSON.toJSONString(chunk));

            // last 类型表示流结束，直接返回，不继续处理
            if (chunk.isLast()) {
                return;
            }


            String agentName = chunk.getMessage().getName();
            ContentBlock contentBlock = chunk.getMessage().getContent().get(0);
            String extractedContent = extractContentFromBlock(contentBlock);

            // 获取完整结果
            if (StringUtils.isNotBlank(extractedContent)) {
                ctx.fullResult.append(extractedContent);
            }

            // 3. 分析智能体分支（非"执行智能体"）— 全部视为思考
            if (!SupervisorService.AGENT_NAME.equalsIgnoreCase(agentName) || ! (contentBlock instanceof  TextBlock)) {
                    ctx.thinkContent.append(extractedContent);
                    sendThinkResponsePublic(ctx.emitter, extractedContent, "执行中", "分析智能体", false, ctx.req.getConversationId(), ctx.uuid);
                return;
            }

            // 4. 执行智能体分支 — 区分思考块和结果块
            if (!ctx.agentChange.getAndSet(true)) {
                // 首次进入执行智能体 — 输出为思考
                ctx.thinkContent.append(extractedContent);
                sendThinkResponsePublic(ctx.emitter, " ", "已执行", "分析智能体", false, ctx.req.getConversationId(), ctx.uuid);
                sendThinkResponsePublic(ctx.emitter, extractedContent, "执行中", "执行智能体", false, ctx.req.getConversationId(), ctx.uuid);
                return;
            }

            // 后续执行智能体 chunk — 只区分 TextBlock（结果）和非 TextBlock（思考）
            if (contentBlock instanceof TextBlock) {
                if (ctx.secondStartFlag.getAndSet(false)) {
                    // 首次输出文本结果：先发一个 think（action="已执行"），再发 text（topic/action 为空）
                    ctx.answerContent.append(extractedContent);
                    sendThinkResponsePublic(ctx.emitter, " ", "已执行", "执行智能体", false, ctx.req.getConversationId(), ctx.uuid);
                    sendTextResponsePublic(ctx.emitter, extractedContent, "", "", false, ctx.req.getConversationId(), ctx.uuid);
                } else {
                    // 后续文本结果
                    ctx.answerContent.append(extractedContent);
                    sendTextResponsePublic(ctx.emitter, extractedContent, "", "", chunk.isLast(), ctx.req.getConversationId(), ctx.uuid);
                }
            } else {
                // 非 TextBlock（ThinkingBlock / ToolUseBlock / ToolResultBlock 等）均视为思考
                ctx.thinkContent.append(extractedContent);
                sendThinkResponsePublic(ctx.emitter, extractedContent, "执行中", "执行智能体", false, ctx.req.getConversationId(), ctx.uuid);
            }

        } catch (Exception e) {
            LOGGER.error("主智能体执行失败异常: ", e);
        }
    }

    private void sendThinkResponsePublic(SseEmitter emitter, String content, String action, String topic, boolean finish, String conversationId, String uuid) {
        ThinkResponseDto thinkResponseDto = new ThinkResponseDto();
        ContentDto contentDto = new ContentDto();
        contentDto.setContent(content);
        contentDto.setAction(action);
        contentDto.setTopic(topic);
        thinkResponseDto.setData(contentDto);
        thinkResponseDto.setFinish(finish);
        safeSend(emitter, thinkResponseDto, MediaType.APPLICATION_JSON);
    }


    /**
     * 处理单个流式 Chunk — Manager版（使用 ThinkManagerResponseDto / TextManagerResponseDto）
     *
     * <p>业务逻辑：
     * <ul>
     *   <li>分析智能体（非"执行智能体"）：所有输出均视为"思考"，用 思考 标签包裹，action="执行中"
     *   <li>执行智能体：TextBlock → 结果（action="已执行"，用 &lt;text&gt; 标签包裹），
     *       非 TextBlock → 思考（action="执行中"）
     * </ul>
     * thinkContent 拼接所有思考内容，answerContent 拼接所有结果内容，用于最终落库。
     */
    private void processChunk(Event chunk, StreamContext ctx) {
        try {
            System.out.println("主智能体回答: " + JSON.toJSONString(chunk));

            // last 类型表示流结束，直接返回，不继续处理
            if (chunk.isLast()) {
                return;
            }


            String agentName = chunk.getMessage().getName();
            ContentBlock contentBlock = chunk.getMessage().getContent().get(0);
            String extractedContent = extractContentFromBlock(contentBlock);

            // 获取完整结果
            if (StringUtils.isNotBlank(extractedContent)) {
                ctx.fullResult.append(extractedContent);
            }

            // 3. 分析智能体分支（非"执行智能体"）— 全部视为思考
            if (!"执行智能体".equalsIgnoreCase(agentName)) {
                ctx.thinkContent.append(extractedContent);
                sendThinkResponse(ctx.emitter, extractedContent, "执行中", "分析智能体", false, ctx.req.getConversationId(), ctx.uuid);
                return;
            }

            // 4. 执行智能体分支 — 区分思考块和结果块
            if (!ctx.agentChange.getAndSet(true)) {
                // 首次进入执行智能体 — 输出为思考
                ctx.thinkContent.append(extractedContent);
                sendThinkResponse(ctx.emitter, " ", "已执行", "分析智能体", false, ctx.req.getConversationId(), ctx.uuid);
                sendThinkResponse(ctx.emitter, extractedContent, "执行中", "执行智能体", false, ctx.req.getConversationId(), ctx.uuid);
                return;
            }

            // 后续执行智能体 chunk — 只区分 TextBlock（结果）和非 TextBlock（思考）
            if (contentBlock instanceof TextBlock) {
                if (ctx.secondStartFlag.getAndSet(false)) {
                    // 首次输出文本结果：先发一个 think（action="已执行"），再发 text（topic/action 为空）
                    ctx.answerContent.append(extractedContent);
                    sendThinkResponse(ctx.emitter, " ", "已执行", "执行智能体", false, ctx.req.getConversationId(), ctx.uuid);
                    sendTextResponse(ctx.emitter, extractedContent, "", "", false, ctx.req.getConversationId(), ctx.uuid);
                } else {
                    // 后续文本结果
                    ctx.answerContent.append(extractedContent);
                    sendTextResponse(ctx.emitter, extractedContent, "", "", chunk.isLast(), ctx.req.getConversationId(), ctx.uuid);
                }
            } else {
                // 非 TextBlock（ThinkingBlock / ToolUseBlock / ToolResultBlock 等）均视为思考
                ctx.thinkContent.append(extractedContent);
                sendThinkResponse(ctx.emitter, extractedContent, "执行中", "执行智能体", false, ctx.req.getConversationId(), ctx.uuid);
            }

        } catch (Exception e) {
            LOGGER.error("主智能体执行失败异常: ", e);
        }
    }

    private void sendThinkResponse(SseEmitter emitter, String content, String action, String topic, boolean finish, String conversationId, String uuid) {
        ThinkManagerResponseDto thinkResponseDto = new ThinkManagerResponseDto();
        ContentDto contentDto = new ContentDto();
        contentDto.setContent(content);
        contentDto.setAction(action);
        contentDto.setTopic(topic);
        thinkResponseDto.setData(contentDto);
        thinkResponseDto.setFinish(finish);
        thinkResponseDto.setCode(200);
        // 思考阶段的 ansUUID 使用独立 uuid，不依赖前端传入的 conversationId
        thinkResponseDto.setAnsUUID(uuid);
        thinkResponseDto.setConversationId(conversationId);
        safeSend(emitter, thinkResponseDto, MediaType.APPLICATION_JSON);
    }


    /**
     * 发送 TextManagerResponseDto — 最终文本结果
     */
    private void sendTextResponse(SseEmitter emitter, String content, String action, String topic, boolean finish, String conversationId, String uuid) {
        TextManagerResponseDto textResponseDto = new TextManagerResponseDto();
        ContentDto contentDto = new ContentDto();
        contentDto.setContent(content);
        contentDto.setAction(action);
        contentDto.setTopic(topic);
        textResponseDto.setData(contentDto);
        textResponseDto.setFinish(finish);
        textResponseDto.setCode(200);
        // 最终文本结果的 ansUUID 与 conversationId 一致，便于前端按对话追溯
        textResponseDto.setAnsUUID(uuid);
        textResponseDto.setConversationId(conversationId);
        safeSend(emitter, textResponseDto, MediaType.APPLICATION_JSON);
    }

    /**
     * 发送 TextResponseDto
     */
    private void sendTextResponsePublic(SseEmitter emitter, String content, String action, String topic, boolean finish, String conversationId, String uuid) {
        TextResponseDto textResponseDto = new TextResponseDto();
        ContentDto contentDto = new ContentDto();
        contentDto.setContent(content);
        contentDto.setAction(action);
        contentDto.setTopic(topic);
        textResponseDto.setData(contentDto);
        textResponseDto.setFinish(finish);
        safeSend(emitter, textResponseDto, MediaType.APPLICATION_JSON);
    }

    private String extractContentFromBlock(ContentBlock block) {
        String content = "";
        if (block instanceof ThinkingBlock) {
            content = ((ThinkingBlock) block).getThinking();
        } else if (block instanceof TextBlock) {
            content = ((TextBlock) block).getText();
        } else if (block instanceof ToolUseBlock) {
//            content = ((ToolUseBlock) block).getContent();
        } else if (block instanceof ToolResultBlock) {
//            content = ((ToolResultBlock) block).getContent();
        }
        // Apply replacements if content is not null/empty
        if (StringUtils.isNotBlank(content)) {
            content = applyReplacements(content);
        }
        return content;
    }

    private String applyReplacements(String content) {
        // Example replacements - customize as needed
        String result = content;
        // 1. 去除首尾空白
        result = result.replaceAll("<think>", "").replaceAll("</think>", "");
        return result;
    }

    /**
     * 缓存命中回放：直接把 cachedResponse 当作"已执行"产物注入 SSE 流。
     * ResponseCacheHook.CacheHitException} 短路 agent 执行。本方法承接它,绕开
     * {@link #processChunk}/{@link #processChunkPublic}(它们的多 chunk 状态机不适合
     * 一次性灌入完整回答),按"分析已执行 → 执行已执行 → 最终文本(finish=true)"的
     * 顺序补齐三帧,前端 UX 与 MISS 走完整链路时基本一致。
     *
     * <p>同时往 {@link StreamContext#answerContent} 写入 cached 文本,后续
     * {@link #handleStreamSuccess}/{@link #handleStreamSuccessPublic} 的 DB 落库才能拿到答案。
     *
     * @param ctx 当前请求流式上下文
     * @param cached 缓存里的完整回答文本
     * @param manager true → Manager 路径(/ai/chat 之外的旧入口,DTO 带 code/ansUUID);
     *                false → 通用 /ai/chat 路径
     */
    private void emitCachedResponse(StreamContext ctx, String cached, boolean manager) {
        if (StringUtils.isBlank(cached)) {
            return;
        }
        LOGGER.info(
                "Cache HIT replay for conv={} manager={} bytes={}",
                ctx.req.getConversationId(),
                manager,
                cached.length());
        ctx.fullResult.append(cached);
        ctx.answerContent.append(cached);
        String conv = ctx.req.getConversationId();
        try {
            if (manager) {
                sendThinkResponse(ctx.emitter, " ", "已执行", "分析智能体", false, conv, ctx.uuid);
                sendThinkResponse(ctx.emitter, " ", "已执行", "执行智能体", false, conv, ctx.uuid);
                sendTextResponse(ctx.emitter, cached, "", "", true, conv, ctx.uuid);
            } else {
                sendThinkResponsePublic(ctx.emitter, " ", "已执行", "分析智能体", false, conv, ctx.uuid);
                sendThinkResponsePublic(ctx.emitter, " ", "已执行", "执行智能体", false, conv, ctx.uuid);
                sendTextResponsePublic(ctx.emitter, cached, "", "", true, conv, ctx.uuid);
            }
            ctx.emitter.complete();
        } catch (Exception e) {
            LOGGER.warn("Cache replay send failed for conv={}: {}", conv, e.getMessage());
            ctx.emitter.completeWithError(e);
        } finally {
            ThreadContextUtils.clearContext();
            saveAnswerIntoDB(ctx);
        }
    }

    /**
     * 统一处理流式异常 — Manager版
     */
    private void handleStreamError(StreamContext ctx, Throwable error, String fullResult) {
        // 先检查是否是 CacheHitException 被 reactor 包装后落入此路径
        ResponseCacheHook.CacheHitException cacheHit = unwrapCacheHit(error);
        if (cacheHit != null) {
            emitCachedResponse(ctx, cacheHit.getCachedResponse(), true);
            return;
        }

        LOGGER.error("处理流式异常: {}", error.getMessage(), error);
        try {
            TextManagerResponseDto errorDto = new TextManagerResponseDto();
            ContentDto contentDto = new ContentDto();
            contentDto.setContent(buildErrorMessage(error));
            errorDto.setData(contentDto);
            errorDto.setFinish(true);
            safeSend(ctx.emitter, errorDto, MediaType.APPLICATION_JSON);
        } catch (Exception e) {
            LOGGER.warn("发送错误结果失败", e);
        } finally {
            ctx.emitter.complete();
            ThreadContextUtils.clearContext();
            saveAnswerIntoDB(ctx);
        }
    }

    /**
     * 统一处理流式异常 — 通用版
     */
    private void handleStreamErrorPublic(StreamContext ctx, Throwable error, String fullResult) {
        // 先检查是否是 CacheHitException 被 reactor 包装后落入此路径
        ResponseCacheHook.CacheHitException cacheHit = unwrapCacheHit(error);
        if (cacheHit != null) {
            emitCachedResponse(ctx, cacheHit.getCachedResponse(), false);
            return;
        }

        LOGGER.error("处理流式异常: {}", error.getMessage(), error);
        try {
            TextResponseDto errorDto = new TextResponseDto();
            ContentDto contentDto = new ContentDto();
            contentDto.setContent("系统处理异常: " + error.getMessage());
            errorDto.setData(contentDto);
            errorDto.setFinish(true);
            safeSend(ctx.emitter, errorDto, MediaType.APPLICATION_JSON);
        } catch (Exception e) {
            LOGGER.warn("发送错误结果失败", e);
        } finally {
            ctx.emitter.complete();
            ThreadContextUtils.clearContext();
            saveAnswerIntoDB(ctx);
        }
    }

    private String buildErrorMessage(Throwable error) {
        if (error.getMessage().contains("Retries exhausted") || error.getMessage().contains("Model request timeout after")) {
            return "请求已达最大重试次数，当前千问模型资源不足，请稍后再试。";
        } else if (error instanceof ModelException) {
            return "模型请求出错: " + error.getMessage();
        } else {
            return error.getMessage();
        }
    }

    /**
     * 检查异常链中是否包含 CacheHitException（reactor 可能包装多层异常）。
     */
    private static ResponseCacheHook.CacheHitException unwrapCacheHit(Throwable t) {
        Throwable cur = t;
        for (int i = 0; i < 8 && cur != null; i++) {
            if (cur instanceof ResponseCacheHook.CacheHitException ch) return ch;
            cur = cur.getCause();
        }
        return null;
    }



    public void safeSend(SseEmitter emitter,Object data,MediaType mediaType){
        try {
            emitter.send(data,mediaType);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 统一处理流式成功 — Manager版
     */
    private void handleStreamSuccess(StreamContext ctx) {
        LOGGER.info("处理流式成功: {}", ctx.req.getConversationId());
        ctx.emitter.complete();
        ThreadContextUtils.clearContext();
        saveAnswerIntoDB(ctx);
    }

    /**
     * 统一处理流式成功 — 通用版
     */
    private void handleStreamSuccessPublic(StreamContext ctx) {
        LOGGER.info("处理流式成功(通用版): {}", ctx.req.getConversationId());
        ctx.emitter.complete();
        ThreadContextUtils.clearContext();
        saveAnswerIntoDB(ctx);
    }

    /**
     * 保存问答记录到数据库，包含 think（思考）和 answer（结果）两个独立字段
     */
    private void saveAnswerIntoDB(StreamContext ctx) {
        if (ctx.req == null || StringUtils.isEmpty(ctx.req.getConversationId())) {
            return;
        }
        QuestionAnswerDto dto = createAnswerInit(ctx.req, ctx.thinkContent.toString(), ctx.answerContent.toString());
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
