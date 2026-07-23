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
import com.agentscopea2a.dto.response.ContentDto;
import com.agentscopea2a.dto.response.TextManagerResponseDto;
import com.agentscopea2a.dto.response.TextPayload;
import com.agentscopea2a.dto.response.TextResponseDto;
import com.agentscopea2a.dto.response.ThinkManagerResponseDto;
import com.agentscopea2a.dto.response.ThinkPayload;
import com.agentscopea2a.dto.response.ThinkResponseDto;
import com.agentscopea2a.entity.AiChatResult;
import com.agentscopea2a.v2.artifact.ArtifactContext;
import com.agentscopea2a.v2.artifact.ArtifactStore;
import com.agentscopea2a.v2.exception.TooManyRequestsException;
import com.agentscopea2a.v2.hooks.ToolCallTrackingHook;
import com.agentscopea2a.v2.memory.EpisodicMemory;
import com.agentscopea2a.v2.runner.HarnessA2aRunnerV2;
import com.agentscopea2a.v2.tools.ToolCallCollector;
import com.agentscopea2a.v2.verify.TriggerLevelResolver;
import com.agentscopea2a.v2.verify.VerificationContext;
import com.agentscopea2a.v2.verify.VerificationRecorder;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.SubagentExposedEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.harness.agent.sandbox.SandboxException;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;


@Service
public class V2ChatStreamServiceImpl implements V2ChatStreamService {

    private static final Logger log = LoggerFactory.getLogger(V2ChatStreamServiceImpl.class);
    /** SSE 连接超时时间：10 分钟（单位毫秒），覆盖长思考 / 工具调用场景 */
    private static final long SSE_TIMEOUT = 600_000L;

    /** 默认 agent 身份字段（请求未带时回填），与 v1 保持一致 */
    private static final String DEFAULT_AGENT_ID = "7";
    private static final String DEFAULT_AGENT_NAME = "数字QA助手";
    private static final String DEFAULT_FROM_TYPE = "HXY";

    private final HarnessA2aRunnerV2 runner;
    private final ArtifactStore artifactStore;
    private final EpisodicMemory episodicMemory;
    private final TriggerLevelResolver triggerLevelResolver;
    private final VerificationRecorder verificationRecorder;


    /**
     * 按 {@code "<userId>:<conversationId>"} 维度记录进行中的调用。
     * 用于：
     * <ul>
     *   <li>同会话并发请求拒绝（putIfAbsent 语义）</li>
     *   <li>{@code /v2/ai/chat/interrupt} 端点查找当前订阅、等待其清理完成</li>
     * </ul>
     */
    private final ConcurrentHashMap<String, InFlightCall> inFlightCalls = new ConcurrentHashMap<>();

    public V2ChatStreamServiceImpl(HarnessA2aRunnerV2 runner, ArtifactStore artifactStore,
                                    EpisodicMemory episodicMemory,
                                    TriggerLevelResolver triggerLevelResolver,
                                    VerificationRecorder verificationRecorder) {        this.runner = runner;
        this.artifactStore = artifactStore;
        this.episodicMemory = episodicMemory;
        this.triggerLevelResolver = triggerLevelResolver;
        this.verificationRecorder = verificationRecorder;
    }

    /**
     * 单次流式请求的上下文状态，参考 v1 ChatStreamServiceImpl 的 StreamContext 模式。
     * 把 per-request 的可变状态收拢成一个对象，便于 processChunk / handleStream* 统一访问。
     */
    private static class StreamContext {
        final SseEmitter emitter;
        final ChatRequest req;
        /** 累积所有"思考"内容（TextBlockDeltaEvent 流式 token），用于最终落库 */
        final StringBuilder thinkContent = new StringBuilder();
        /** 累积最终结果内容（AgentResultEvent 终止事件），成功时分片发送给前端 */
        final StringBuilder answerContent = new StringBuilder();
        /** 本次回答的稳定 UUID，沿用 conversationId，便于前端按对话追溯 */
        final String ansUUID;
        final String conversationId;
        final String userId;
        final String agentId;
        final String agentName;
        final String formType;
        /** RuntimeContext，供 cleanup 访问 artifactStore 等 */
        final RuntimeContext runtimeCtx;
        /** 工具调用采集器，供 cleanup 持久化 episodic 记忆 + 事件转发 processPayload */
        final ToolCallCollector collector;
        /** V3.0 验证上下文，供 cleanup 落盘事件流 */
        final VerificationContext verificationCtx;
        /** 用户原始消息，供 cleanup 组装 episodic session messages */
        final Msg userMsg;
        /** episodic session 维度标识 */
        final String episodicSessionId;
        /** 持有 Reactor Disposable，供 cleanup 取消订阅 */
        final AtomicReference<Disposable> subscription = new AtomicReference<>();
        /** 保证 cleanup 只执行一次（onCompletion / onTimeout / onError 可能多次触发） */
        final AtomicBoolean cleaned = new AtomicBoolean(false);
        /** 是否已发送过"执行中"，用于保证"执行中"和"已执行"成对出现 */
        final AtomicBoolean hasSentExecuting = new AtomicBoolean(false);

        StreamContext(SseEmitter emitter, ChatRequest req, RuntimeContext runtimeCtx,
                      ToolCallCollector collector, Msg userMsg, String episodicSessionId,
                      VerificationContext verificationCtx) {
            this.emitter = emitter;
            this.req = req;
            this.runtimeCtx = runtimeCtx;
            this.collector = collector;
            this.userMsg = userMsg;
            this.episodicSessionId = episodicSessionId;
            this.verificationCtx = verificationCtx;
            this.conversationId = req.getConversationId();
            this.userId = req.getUserId();
            this.ansUUID = req.getConversationId();
            this.agentId = StringUtils.defaultIfBlank(req.getAgentId(), DEFAULT_AGENT_ID);
            this.agentName = StringUtils.defaultIfBlank(req.getAgentName(), DEFAULT_AGENT_NAME);
            this.formType = StringUtils.defaultIfBlank(req.getFromType(), DEFAULT_FROM_TYPE);
        }
    }

    /**
     * 响应策略：决定思考/文本/错误分别用哪套 DTO（参考 v1 ResponseStrategy 模式）。
     * <ul>
     *   <li>{@link #managerStrategy} - ThinkManagerResponseDto / TextManagerResponseDto，带 code/ansUUID/conversationId/fromType
     *   <li>{@link #publicStrategy}  - ThinkResponseDto / TextResponseDto，无 Manager 专属字段
     * </ul>
     * 是否传入 agentName 决定返回 DTO 风格（与 v1 判断逻辑一致）。
     */
    private interface ResponseStrategy {
        void sendThink(StreamContext ctx, ThinkPayload payload);
        void sendText(StreamContext ctx, TextPayload payload);
        void sendError(StreamContext ctx, Throwable error);
    }

    /** 复用 payload 的 content/action/topic 构造 ContentDto（参考 v1 contentOf）。 */
    private static ContentDto contentOf(ThinkPayload payload) {
        ContentDto contentDto = new ContentDto();
        contentDto.setContent(payload.getContent());
        contentDto.setAction(payload.getAction());
        contentDto.setTopic(payload.getTopic());
        return contentDto;
    }

    private final ResponseStrategy managerStrategy = new ResponseStrategy() {
        @Override
        public void sendThink(StreamContext ctx, ThinkPayload payload) {
            ThinkManagerResponseDto dto = new ThinkManagerResponseDto();
            dto.setData(contentOf(payload));
            dto.setFinish(payload.isFinish());
            dto.setCode(200);
            // 思考阶段的 ansUUID 使用独立 uuid，不依赖前端传入的 conversationId
            dto.setAnsUUID(ctx.ansUUID);
            dto.setConversationId(ctx.conversationId);
            dto.setFromType(ctx.formType);
            // SSE event name = "text_block_delta" so frontend chat.ts can route it
            // to the "token" branch (same event name as the old AiChatResult format).
            // The JSON body uses the new ThinkPayload/TextPayload DTO structure
            // (type="think", data.content, data.action, data.topic, finish).
            safeSendEvent(ctx.emitter, "text_block_delta", dto);
        }

        @Override
        public void sendText(StreamContext ctx, TextPayload payload) {
            TextManagerResponseDto dto = new TextManagerResponseDto();
            ContentDto contentDto = new ContentDto();
            contentDto.setContent(payload.getContent());
            contentDto.setAction("");
            contentDto.setTopic("");
            dto.setData(contentDto);
            dto.setFinish(payload.isFinish());
            dto.setCode(200);
            // 最终文本结果的 ansUUID 与 conversationId 一致，便于前端按对话追溯
            dto.setAnsUUID(ctx.ansUUID);
            dto.setConversationId(ctx.conversationId);
            dto.setFromType(ctx.formType);
            // SSE event name = "done" so frontend chat.ts recognizes the terminal event.
            // The JSON body uses the new TextPayload DTO (type="text", data.content, finish).
            safeSendEvent(ctx.emitter, "done", dto);
        }

        @Override
        public void sendError(StreamContext ctx, Throwable error) {
            TextManagerResponseDto dto = new TextManagerResponseDto();
            ContentDto contentDto = new ContentDto();
            contentDto.setContent(buildErrorMessage(error));
            dto.setData(contentDto);
            dto.setFinish(true);
            dto.setCode(500);
            dto.setAnsUUID(ctx.ansUUID);
            dto.setConversationId(ctx.conversationId);
            dto.setFromType(ctx.formType);
            safeSendEvent(ctx.emitter, "done", dto);
        }
    };

    private final ResponseStrategy publicStrategy = new ResponseStrategy() {
        @Override
        public void sendThink(StreamContext ctx, ThinkPayload payload) {
            ThinkResponseDto dto = new ThinkResponseDto();
            dto.setData(contentOf(payload));
            dto.setFinish(payload.isFinish());
            safeSendEvent(ctx.emitter, "text_block_delta", dto);
        }

        @Override
        public void sendText(StreamContext ctx, TextPayload payload) {
            TextResponseDto dto = new TextResponseDto();
            ContentDto contentDto = new ContentDto();
            contentDto.setContent(payload.getContent());
            contentDto.setAction("");
            contentDto.setTopic("");
            dto.setData(contentDto);
            dto.setFinish(payload.isFinish());
            safeSendEvent(ctx.emitter, "done", dto);
        }

        @Override
        public void sendError(StreamContext ctx, Throwable error) {
            TextResponseDto dto = new TextResponseDto();
            ContentDto contentDto = new ContentDto();
            contentDto.setContent(buildErrorMessage(error));
            dto.setData(contentDto);
            dto.setFinish(true);
            safeSendEvent(ctx.emitter, "done", dto);
        }
    };

    /**
     * 统一流式入口（参考 v1 stream 模式）。
     *
     * <p>是否传入 agentName 决定返回 DTO 风格：
     * <ul>
     *   <li>有 agentName  -> Manager 风格（ThinkManagerResponseDto / TextManagerResponseDto）
     *   <li>无 agentName  -> Public 风格（ThinkResponseDto / TextResponseDto），并回填默认 agentId/agentName/fromType
     * </ul>
     * 判断必须在回填默认值之前，否则会被默认值覆盖（与 v1 一致）。
     */
    @Override
    public SseEmitter stream(ChatRequest req) {
        // 判断 managerMode 必须在回填默认值之前，否则会被默认值覆盖
        boolean managerMode = StringUtils.isNoneEmpty(req.getAgentName());
        if (!managerMode) {
            req.setAgentId(DEFAULT_AGENT_ID);
            req.setAgentName(DEFAULT_AGENT_NAME);
            req.setFromType(DEFAULT_FROM_TYPE);
        }
        ResponseStrategy strategy = managerMode ? managerStrategy : publicStrategy;

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);

        String text = req.getQuestion();
        String userId = req.getUserId();
        String conversationId = req.getConversationId();

        // 构造调用键：同一 (userId, conversationId) 只允许一个进行中的流式调用
        String callKey = callKey(userId, conversationId);
        InFlightCall inFlight = new InFlightCall();
        // putIfAbsent：若已存在同会话的进行中调用，直接拒绝，防止并发覆盖 / 重复消耗 LLM token
        InFlightCall existing = inFlightCalls.putIfAbsent(callKey, inFlight);
        if (existing != null) {
            emitter.completeWithError(new TooManyRequestsException(
                    "Session " + conversationId + " already has an in-flight call; "
                            + "wait for it to finish or use POST /v2/ai/chat/interrupt to redirect"));
            return emitter;
        }

        // 工具调用采集器：记录本轮对话触发的工具调用上下文，供 episodic 记忆持久化使用
        ToolCallCollector collector = new ToolCallCollector(text);

        // 构造用户消息（纯文本内容块）
        Msg userMsg = Msg.builder().role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();

        // 构造运行时上下文：携带 sessionId / userId / lastQuestion 供中间件 / hooks 访问
        RuntimeContext ctx = buildRuntimeContext(conversationId, userId, text);

        // 把工具调用采集器放进上下文，供 ToolCallTrackingHook 在工具调用时写入
        ctx.put(ToolCallTrackingHook.COLLECTOR_CTX_KEY, collector);

        // V3.0: 创建请求级 VerificationContext 并放入 RuntimeContext，供 VerificationHook 读取
        VerificationContext verificationCtx = new VerificationContext(conversationId, userId, text);
        verificationCtx.setTriggerLevel(triggerLevelResolver.resolveLevel(text));
        ctx.put(VerificationContext.VERIFY_CTX_KEY, verificationCtx);

        // ParentEmitterCarrier: holds the parent agent's AgentEventEmitter so subagent
        // middleware (SubagentEventForwardingMiddleware) can mirror subagent events to
        // the parent's SSE stream. The emitter is populated mid-stream by the
        // Flux.deferContextual wrapper below (which reads it from the Reactor context
        // where ReActAgent.buildAgentStream wrote it). The carrier itself is put into
        // RuntimeContext here so AgentSpawnTool.execLocalSync's
        // RuntimeContext.builder(ctx).from(ctx) clones it into the subagent's context.
        com.agentscopea2a.v2.middleware.ParentEmitterCarrier parentEmitterCarrier =
                new com.agentscopea2a.v2.middleware.ParentEmitterCarrier();
        ctx.put(com.agentscopea2a.v2.middleware.ParentEmitterCarrier.class, parentEmitterCarrier);

        // Store the SseEmitter on RuntimeContext so ToolCallTrackingHook can send a
        // supplementary "tool_output" SSE event directly from PostActing. This is
        // necessary because the framework's tool_result_end AgentEvent fires BEFORE
        // PostActing (the hook chain runs after the agent's acting middleware returns),
        // so when the SSE handler reads the collector at tool_result_end time, the
        // output hasn't been captured yet. By having PostActing send the output as a
        // separate SSE event (keyed by toolCallId), the frontend can match it to the
        // existing ActivityFeed row and render the collapsible "出参" panel.
        ctx.put(ToolCallTrackingHook.EMITTER_CTX_KEY, emitter);
        ctx.put(ToolCallTrackingHook.SSE_META_CTX_KEY,
                new ToolCallTrackingHook.SseMeta(
                        // ansUUID / agentId / agentName / formType are already resolved in StreamContext
                        // after the managerMode check above; read from req directly for consistency.
                        req.getConversationId(),
                        StringUtils.defaultIfBlank(req.getAgentId(), DEFAULT_AGENT_ID),
                        StringUtils.defaultIfBlank(req.getAgentName(), DEFAULT_AGENT_NAME),
                        StringUtils.defaultIfBlank(req.getFromType(), DEFAULT_FROM_TYPE),
                        conversationId));

        // Episodic memory session_id: "user:<userId>:<conversationId>" so that:
        // 1) TraceMiner.loadSessions can group by session_id (each request = one session)
        // 2) extractUserId can parse userId from the "user:userId:..." prefix
        // 3) findActiveUsers fallback (session_id LIKE 'user:%') still discovers the rows
        String episodicUserId = userId != null && !userId.isBlank() ? userId : "anonymous";
        String episodicSessionId = "user:" + episodicUserId + ":" + conversationId;

        // 把 per-request 状态收拢进 StreamContext（参考 v1 流处理模式）
        StreamContext streamCtx = new StreamContext(emitter, req, ctx, collector, userMsg, episodicSessionId, verificationCtx);

        // 清理逻辑：取消订阅、清理 artifact、持久化 episodic 记忆、移除进行中调用标记
        Runnable cleanup = buildCleanup(streamCtx, callKey, inFlight);

        // 注册 SSE 生命周期回调：三种终止路径都走同一个幂等 cleanup（参考 v1 流处理模式）
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        // 在 boundedElastic 调度器上异步启动流式订阅，避免阻塞 Servlet 容器线程
        Mono.fromRunnable(() -> {
            try {
                // 核心：触发 Agent 流式事件流（文本增量、工具调用、最终结果等事件）
                // 事件类型基类为 io.agentscope.core.event.AgentEvent
                Flux<AgentEvent> eventFlux = runner.streamEvents(List.of(userMsg), ctx);

                // 订阅事件流：onNext 处理每个事件，onError 处理异常，onComplete 处理结束
                // 参考 v1 流处理模式：processChunk 处理增量，handleStreamError/Success 处理终止
                Disposable d = eventFlux.subscribe(
                        event -> processChunk(event, streamCtx, strategy),
                        error -> handleStreamError(streamCtx, error, strategy),
                        () -> handleStreamSuccess(streamCtx, strategy));
                // 保存订阅句柄，供 cleanup 取消和 interrupt 端点强制中断使用
                streamCtx.subscription.set(d);

                // 同步暴露给 interrupt 端点：超时可强制 dispose
                inFlight.subscription().set(d);
            } catch (Exception e) {
                log.error("v2 stream failed for sessionId={}", conversationId, e);
                handleStreamError(streamCtx, e, strategy);
            }
        }).subscribeOn(Schedulers.boundedElastic()).subscribe();

        return emitter;
    }

    // ── Event handling ──────────────────────────────────────────────────────

    /**
     * 处理单个流式事件（参考 v1 processChunk 模式）。
     *
     * <p>业务逻辑（与 v1 语义对齐，仅事件类型不同）：
     * <ul>
     *   <li>{@link AgentResultEvent} 是终止事件：提取最终文本，仅在 answerContent 为空时
     *       替换（避免"重复输出"bug — 流式 text_block_delta 已累积完整文本，再追加会双倍）</li>
     *   <li>{@link TextBlockDeltaEvent} 是流式 token：视为"思考"，累积到
     *       {@link StreamContext#thinkContent} 并通过 {@code sendThink}（action="执行中"）即时发送</li>
     *   <li>6 种 process 事件（agent_start, tool_call_start, tool_result_start, tool_result_end,
     *       subagent_exposed, agent_end）转发到前端 ActivityFeed 做实时进度展示</li>
     *   <li>其他事件（TextBlockStartEvent 等）直接跳过</li>
     * </ul>
     */
    private void processChunk(AgentEvent event, StreamContext ctx, ResponseStrategy strategy) {
        try {
            // AgentResultEvent 是终止事件：最终结果累积到 answerContent，不立即发送
            // The streaming text_block_delta events have already accumulated the full text
            // into thinkContent as it streamed in. Replacing answerContent with the
            // AgentResultEvent's final text would be fine, but we only do so if answerContent
            // is empty (i.e. no streaming happened — e.g. non-streaming model configs).
            // This avoids the "重复输出" bug where the full report appears twice.
            if (event instanceof AgentResultEvent) {
                if (ctx.answerContent.length() == 0) {
                    String text = extractText(((AgentResultEvent) event).getResult());
                    if (StringUtils.isNotBlank(text)) {
                        ctx.answerContent.append(text);
                    }
                }
                return;
            }

            // 从流式增量事件中提取文本 chunk
            String chunk = null;
            if (event instanceof TextBlockDeltaEvent delta) {
                chunk = delta.getDelta();
            }
            // TextBlockStartEvent 是标记性事件 - 不携带文本内容，直接跳过

            // 有 chunk 且非空 → 累积并发送"执行中"
            if (StringUtils.isNotBlank(chunk)) {
                ctx.thinkContent.append(chunk);
                ctx.hasSentExecuting.set(true);
                strategy.sendThink(ctx, ThinkPayload.progress(chunk));
                return;
            }

            // ── Process events (process-event-streaming.md) ─────────────────────
            // Forward 6 event types that carry no text but tell the user what the
            // agent is doing: agent_start, tool_call_start, tool_result_start,
            // tool_result_end, subagent_exposed, agent_end. These are NOT accumulated
            // into thinkContent/answerContent; they go out as standalone SSE events
            // with their own event name (matching AgentEvent.getType()) so the
            // frontend ActivityFeed can render a live progress timeline.
            // They use AiChatResult format (with eventType/toolCall* fields) so the
            // frontend chat.ts PROCESS_EVENTS matcher picks them up.
            String eventName = event.getType() != null ? event.getType().name().toLowerCase() : "custom";
            switch (eventName) {
                case "agent_start": {
                    if (!(event instanceof AgentStartEvent e)) return;
                    AiChatResult result = AiChatResult.builder()
                            .code(0).eventType(eventName)
                            .lineResult("🤖 启动智能体：" + e.getName() + " (" + e.getRole() + ")")
                            .agentNameRaw(e.getName()).agentRole(e.getRole())
                            .source(event.getSource())
                            .build();
                    safeSendEvent(ctx.emitter, eventName, result);
                    return;
                }
                case "tool_call_start": {
                    if (!(event instanceof ToolCallStartEvent e)) return;
                    ToolCallCollector.ToolCallDetail detail =
                            ctx.collector != null ? ctx.collector.getByToolCallId(e.getToolCallId()) : null;
                    AiChatResult result = AiChatResult.builder()
                            .code(0).eventType(eventName)
                            .lineResult("🔧 调用工具：" + e.getToolCallName())
                            .toolCallId(e.getToolCallId()).toolCallName(e.getToolCallName())
                            .toolInput(detail != null ? detail.input() : null)
                            .source(event.getSource())
                            .build();
                    safeSendEvent(ctx.emitter, eventName, result);
                    return;
                }
                case "tool_result_start": {
                    if (!(event instanceof ToolResultStartEvent e)) return;
                    AiChatResult result = AiChatResult.builder()
                            .code(0).eventType(eventName)
                            .lineResult("📋 工具返回：" + e.getToolCallName())
                            .toolCallId(e.getToolCallId()).toolCallName(e.getToolCallName())
                            .source(event.getSource())
                            .build();
                    safeSendEvent(ctx.emitter, eventName, result);
                    return;
                }
                case "tool_result_end": {
                    if (!(event instanceof ToolResultEndEvent e)) return;
                    String state = e.getState() != null ? e.getState().name() : "?";
                    ToolCallCollector.ToolCallDetail detail =
                            ctx.collector != null ? ctx.collector.getByToolCallId(e.getToolCallId()) : null;
                    AiChatResult result = AiChatResult.builder()
                            .code(0).eventType(eventName)
                            .lineResult("✅ 完成：" + e.getToolCallName() + " (" + state + ")")
                            .toolCallId(e.getToolCallId()).toolCallName(e.getToolCallName())
                            .toolCallState(state)
                            .toolInput(detail != null ? detail.input() : null)
                            .toolOutput(detail != null ? detail.output() : null)
                            .source(event.getSource())
                            .build();
                    safeSendEvent(ctx.emitter, eventName, result);
                    return;
                }
                case "subagent_exposed": {
                    if (!(event instanceof SubagentExposedEvent e)) return;
                    String label = e.getLabel() != null ? e.getLabel() : e.getSubagentId();
                    AiChatResult result = AiChatResult.builder()
                            .code(0).eventType(eventName)
                            .lineResult("👥 派单子智能体：" + label)
                            .subagentId(e.getSubagentId()).subagentLabel(e.getLabel())
                            .source(event.getSource())
                            .build();
                    safeSendEvent(ctx.emitter, eventName, result);
                    return;
                }
                case "agent_end": {
                    AiChatResult result = AiChatResult.builder()
                            .code(0).eventType(eventName)
                            .lineResult("✅ 智能体完成")
                            .source(event.getSource())
                            .build();
                    safeSendEvent(ctx.emitter, eventName, result);
                    return;
                }
                default:
                    // Other events (thinking_*, model_call_*, data_block_*, text_block_start/end,
                    // tool_call_delta, tool_result_*_delta, hint_block, etc.) are not forwarded.
                    return;
            }

        } catch (Exception e) {
            // 仅在确实发送过"执行中"时才补发"已执行"，保证成对
            if (ctx.hasSentExecuting.get()) {
                strategy.sendThink(ctx, ThinkPayload.done("分析执行智能体"));
            }
            log.error("处理流式事件失败: sessionId={}", ctx.conversationId, e);
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    /**
     * 统一处理流式异常（参考 v1 handleStreamError 模式）。
     *
     * <p>补发"已执行"（若发送过"执行中"）、发送 error 事件、完成 emitter。
     * cleanup 由 SSE 生命周期回调触发。
     *
     * <p>Bug B 修复（cleanup 时序）：当响应文本已流给客户端（answerContent 非空）且异常是
     * cleanup 阶段误抛的 {@link SandboxException} 时，改为发 done 事件并正常 complete，
     * 避免 HTTP 500。其他错误（streaming 中途真异常、空响应）仍走 error 路径。
     */
    private void handleStreamError(StreamContext ctx, Throwable error, ResponseStrategy strategy) {
        log.error("处理流式异常: sessionId={}", ctx.conversationId, error);
        // Bug B：cleanup 阶段误抛的 sandbox 异常，已有最终结果，按成功收尾
        if (ctx.answerContent.length() > 0 && error instanceof SandboxException) {
            log.warn("Cleanup-phase SandboxException suppressed for sessionId={}; sending done instead of error",
                    ctx.conversationId);
            handleStreamSuccess(ctx, strategy);
            return;
        }
        try {
            // 仅在确实发送过"执行中"时才补发"已执行"，保证成对
            if (ctx.hasSentExecuting.get()) {
                strategy.sendThink(ctx, ThinkPayload.done("分析执行智能体"));
            }
            strategy.sendError(ctx, error);
        } catch (Exception e) {
            log.warn("发送错误结果失败: sessionId={}", ctx.conversationId, e);
        } finally {
            // Plan B revision #7: explicit cleanup invocation. SseEmitter's onError
            // callback may not fire reliably (Spring 6.1.4 async dispatch issue).
            // Call cleanup explicitly here - it's CAS-guarded, so duplicate calls are no-ops.
            try {
                ctx.emitter.complete();
            } catch (Exception e) {
                log.warn("emitter.complete() 失败: sessionId={}", ctx.conversationId, e);
            }
        }
    }

    /**
     * 统一处理流式成功（参考 v1 handleStreamSuccess 模式）。
     *
     * <p>先发"已执行"（若发送过"执行中"），再把最终结果 answerContent 分片输出为 text 事件，
     * 最后完成 emitter。cleanup 由 SSE 生命周期回调触发。
     */
    private void handleStreamSuccess(StreamContext ctx, ResponseStrategy strategy) {
        log.info("[COMPLETE] Request finished: conversationId={} thinkLen={} answerLen={}",
                ctx.conversationId, ctx.thinkContent.length(), ctx.answerContent.length());
        try {
            // 仅在确实发送过"执行中"时才发送"已执行"，保证成对
            if (ctx.hasSentExecuting.get()) {
                strategy.sendThink(ctx, ThinkPayload.done("分析智能体"));
            }
            // 发送最终结果：一次性发完整 answerContent（不分片）。
            // 前端收到 done 事件后用完整文本替换流式累积内容，确保最终渲染正确。
            // 之前分5字符片段发送会导致前端只取最后一个片段覆盖，丢失大部分内容。
            String finalAnswer = ctx.answerContent.toString();
            if (StringUtils.isNotBlank(finalAnswer)) {
                strategy.sendText(ctx, TextPayload.chunk(finalAnswer, true));
            }
        } catch (Exception e) {
            log.warn("发送最终结果失败: sessionId={}", ctx.conversationId, e);
        } finally {
            // Same as error path: explicit cleanup in case onCompletion
            // doesn't fire (Spring 6.1.4 SseEmitter async dispatch issue).
            try {
                ctx.emitter.complete();
            } catch (Exception e) {
                log.warn("emitter.complete() 失败: sessionId={}", ctx.conversationId, e);
            }
        }
    }

    /**
     * 发送带 event name 的 SSE 事件。
     * Spring SseEmitter.event() 构造器会同时写入 {@code event:<name>} 和 {@code data:<json>}，
     * 前端 EventSource / fetch reader 可以通过 event name 区分不同类型的事件。
     */
    private void safeSendEvent(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    /**
     * 构造 cleanup 逻辑：取消订阅、清理 artifact、持久化 episodic 记忆、移除进行中调用标记。
     * 幂等执行（CAS 保证只执行一次）。
     */
    private Runnable buildCleanup(StreamContext ctx, String callKey, InFlightCall inFlight) {
        return () -> {
            // CAS 保证幂等：只执行一次
            if (!ctx.cleaned.compareAndSet(false, true)) return;
            // 1. 取消 Reactor 订阅，停止继续消耗 LLM token
            Disposable d = ctx.subscription.get();
            if (d != null && !d.isDisposed()) {
                d.dispose();
                log.info("v2 stream cancelled for sessionId={} (client disconnect/timeout)", ctx.conversationId);
            }
            // 2. 清理本次会话产生的临时 artifact（沙箱文件等）
            try {
                artifactStore.cleanupTask(ArtifactContext.from(ctx.runtimeCtx));
            } catch (Exception ex) {
                log.warn("Artifact cleanup failed for sessionId={}: {}", ctx.conversationId, ex.getMessage());
            }
            // V3.0: 落盘验证事件流（verification_event 表，供回放/离线评估）
            try {
                verificationRecorder.recordEvents(ctx.verificationCtx);
            } catch (Exception ex) {
                log.warn("Verification event flush failed for sessionId={}: {}", ctx.conversationId, ex.getMessage());
            }
            // 3. 持久化 episodic 记忆：仅在存在工具调用上下文时记录
            try {
                String toolCallJson = ctx.collector.toJson();
                if (toolCallJson != null && !toolCallJson.isEmpty()) {
                    // 组装本次会话的消息列表：用户提问 + 助手回答（优先用最终结果，回退到思考内容）
                    List<Msg> sessionMessages = new ArrayList<>();
                    sessionMessages.add(ctx.userMsg);
                    String accumulatedText = ctx.answerContent.length() > 0
                            ? ctx.answerContent.toString()
                            : ctx.thinkContent.toString();
                    if (accumulatedText != null && !accumulatedText.isEmpty()) {
                        sessionMessages.add(Msg.builder().role(MsgRole.ASSISTANT)
                                .content(TextBlock.builder().text(accumulatedText).build())
                                .build());
                    }
                    // 异步持久化到 episodic 记忆系统，不阻塞 SSE 完成回调
                    episodicMemory.recordSessionWithToolContext(ctx.episodicSessionId, sessionMessages, toolCallJson)
                            .subscribeOn(Schedulers.boundedElastic())
                            .subscribe(
                                    null,
                                    ex -> log.warn("Episodic persist failed for sessionId={}: {}", ctx.episodicSessionId, ex.getMessage()),
                                    () -> log.debug("Episodic persist completed for sessionId={}", ctx.episodicSessionId));
                }
            } catch (Exception ex) {
                log.warn("Episodic persist setup failed for sessionId={}: {}", ctx.episodicSessionId, ex.getMessage());
            }
            // 4. 从进行中调用表移除（必须用 (key, value) 两参 remove 防止误删被并发覆盖后的新条目）
            inFlightCalls.remove(callKey, inFlight);
            // 5. 完成 InFlightCall 的 future，唤醒等待的 interrupt 端点
            inFlight.completion().complete(null);
        };
    }

    /**
     * 构造错误消息：对常见的模型重试超时等错误做友好提示，其他直接透传。
     */
    private String buildErrorMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null) {
            return "未知错误";
        }
        if (message.contains("Retries exhausted") || message.contains("Model request timeout after")) {
            return "请求已达最大重试次数，当前模型资源不足，请稍后再试。";
        }
        return message;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * 构造运行时上下文：填充 sessionId、userId（空则用 "anonymous"）、
     * 并把用户原始问题以 "lastQuestion" 为键存入上下文，供中间件 / hooks 读取。
     */
    private RuntimeContext buildRuntimeContext(String sessionId, String userId, String lastQuestion) {
        RuntimeContext.Builder builder = RuntimeContext.builder()
                .sessionId(sessionId);
        if (userId != null && !userId.isBlank()) {
            builder.userId(userId);
        } else {
            builder.userId("anonymous");
        }
        // 把用户问题放进上下文，供中间件 / hooks 访问
        builder.put("lastQuestion", lastQuestion);
        return builder.build();
    }


    /** 从 {@link Msg} 中提取纯文本内容，msg 为 null 时返回 null。 */
    private String extractText(Msg msg) {
        if (msg == null) return null;
        return msg.getTextContent();
    }


    /**
     * 构造进行中调用表的键：{@code "<userId>:<sessionId>"}。
     * userId 为空时统一使用 "__anon__"，保证匿名用户也能正确区分会话。
     */
    private static String callKey(String userId, String sessionId) {
        String uid = (userId == null || userId.isBlank()) ? "__anon__" : userId;
        return uid + ":" + sessionId;
    }

    /**
     * 查询指定会话当前是否在进行中的流式调用。
     * <p>供 {@code POST /v2/ai/chat/interrupt} 端点使用：
     * <ul>
     *   <li>返回 null：无进行中调用，interrupt 端点可直接启动新的 resume 流</li>
     *   <li>返回非 null：可等待 {@link InFlightCall#completion()} 完成（带超时），
     *       超时后可强制 dispose {@link InFlightCall#subscription()} 停止 LLM token 消耗</li>
     * </ul>
     */
    @Override
    public InFlightCall getInFlightCall(String userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        return inFlightCalls.get(callKey(userId, sessionId));
    }
}