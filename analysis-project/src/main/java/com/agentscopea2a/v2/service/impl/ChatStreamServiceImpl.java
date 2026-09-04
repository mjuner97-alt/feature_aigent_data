
package com.agentscopea2a.v2.service.impl;

import cn.hutool.core.util.ObjectUtil;
import com.agentscopea2a.dto.ChatRequest;
import com.agentscopea2a.dto.QuestionAnswerDto;
import com.agentscopea2a.dto.response.*;
import com.agentscopea2a.mapper.gauss.MainAgentMapper;
import com.agentscopea2a.v2.artifact.ArtifactContext;
import com.agentscopea2a.v2.artifact.ArtifactStore;
import com.agentscopea2a.v2.exception.TooManyRequestsException;
import com.agentscopea2a.v2.memory.EpisodicMemory;
import com.agentscopea2a.v2.runner.HarnessA2aRunnerV2;
import com.agentscopea2a.v2.service.ChatStreamService;
import com.agentscopea2a.v2.service.ChatRuntimeConfig;
import com.agentscopea2a.v2.service.ChatRuntimeConfigService;
import com.agentscopea2a.v2.tools.ToolResultRegistry;
import com.agentscopea2a.v2.hooks.ChatScriptExecResultHook;
import com.agentscopea2a.v2.trace.collector.TraceSession;
import com.agentscopea2a.v2.trace.assembler.TraceAssembler;
import com.agentscopea2a.v2.trace.model.AssembledTrace;
import com.agentscopea2a.v2.trace.writer.TraceBatchWriter;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.*;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ModelUtils;
import io.agentscope.core.message.TextBlock;
import io.agentscope.harness.agent.sandbox.SandboxException;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.agentscopea2a.v2.config.AiChatRuntimeConfigKeys.CHUNK_GAP_TIMEOUT_SECONDS;
import static com.agentscopea2a.v2.config.AiChatRuntimeConfigKeys.SCRIPT_EXEC_ENABLED;
import static com.agentscopea2a.v2.config.AiChatRuntimeConfigKeys.STREAM_TIMEOUT_SECONDS;

/**
 * 流式聊天服务实现，基于 SSE 推送 Agent 事件流
 */
@Service
public class ChatStreamServiceImpl implements ChatStreamService {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamServiceImpl.class);
    private static final Logger llmTraceLog = LoggerFactory.getLogger("llm.trace");
    /**
     * 看门狗时间60s
     * 容器 async 超时兜底：比看门狗晚 60 秒，正常情况永不触发。
     * 真正的超时处理由 {@link #SSE_TIMEOUT_WATCHDOG} 负责——容器超时触发时响应已被销毁，
     * emitter.send 必然失败（连接已关），前端收不到任何事件；看门狗在连接存活期间
     * 主动下发超时错误再 complete，前端才能收到。
     */
    private static final long CONTAINER_TIMEOUT_GRACE = 60_000L;

    /**
     * SSE 超时看门狗调度器：守护线程，到点后先发 error 事件再关连接。
     * 单线程即可（任务只做 send + complete，耗时毫秒级）。
     */
    private static final ScheduledExecutorService SSE_TIMEOUT_WATCHDOG =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sse-timeout-watchdog");
                t.setDaemon(true);
                return t;
            });

    /** 默认 agent 身份字段（请求未带时回填），与 v1 保持一致 */
    private static final String DEFAULT_AGENT_ID = "7";
    private static final String DEFAULT_AGENT_NAME = "数字QA助手";
    private static final String DEFAULT_FROM_TYPE = "HXY";

    private final HarnessA2aRunnerV2 runner;
    private final ArtifactStore artifactStore;
    private final EpisodicMemory episodicMemory;
    private final TraceAssembler traceAssembler;
    private final TraceBatchWriter traceBatchWriter;
    private final ToolResultRegistry toolResultRegistry;
    private final ChatRuntimeConfigService chatRuntimeConfigService;

    @Autowired
    private MainAgentMapper mainAgentMapper;

    /** /ai/chat 回答末尾追加的管理后台地址(md),留空则不追加。见 application.properties harness.chat.management-url */
    @Value("${harness.chat.management-url:}")
    private String managementUrl;


    private final String AGENT_RETURN_NAME = "分析执行智能体";



    /**
     * 按 {@code "<userId>:<conversationId>"} 维度记录进行中的调用。
     * 用于：
     * <ul>
     *   <li>同会话并发请求拒绝（putIfAbsent 语义）</li>
     *   <li>{@code /v2/ai/chat/interrupt} 端点查找当前订阅、等待其清理完成</li>
     * </ul>
     */
    private final ConcurrentHashMap<String, InFlightCall> inFlightCalls = new ConcurrentHashMap<>();

    public ChatStreamServiceImpl(HarnessA2aRunnerV2 runner, ArtifactStore artifactStore,
                                 EpisodicMemory episodicMemory,
                                 TraceAssembler traceAssembler,
                                 TraceBatchWriter traceBatchWriter,
                                 ToolResultRegistry toolResultRegistry,
                                 ChatRuntimeConfigService chatRuntimeConfigService) {
        this.runner = runner;
        this.artifactStore = artifactStore;
        this.episodicMemory = episodicMemory;
        this.traceAssembler = traceAssembler;
        this.traceBatchWriter = traceBatchWriter;
        this.toolResultRegistry = toolResultRegistry;
        this.chatRuntimeConfigService = chatRuntimeConfigService;
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
        /** 用户原始消息，供 cleanup 组装 episodic session messages */
        final Msg userMsg;
        /** episodic session 维度标识 */
        final String episodicSessionId;
        /** 持有 Reactor Disposable，供 cleanup 取消订阅 */
        final AtomicReference<Disposable> subscription = new AtomicReference<>();
        /** 保证 cleanup 只执行一次（onCompletion / onTimeout / onError 可能多次触发） */
        final AtomicBoolean cleaned = new AtomicBoolean(false);
        /** SSE 超时看门狗句柄，供 cleanup 在正常完成时取消 */
        final AtomicReference<ScheduledFuture<?>> timeoutWatchdog = new AtomicReference<>();
        /** 是否已发送过"执行中"，用于保证"执行中"和"已执行"成对出现 */
        final AtomicBoolean hasSentExecuting = new AtomicBoolean(false);
        /** 是否已向前端发送过可见 think 内容（等待/工具提示或真实模型增量）。 */
        final AtomicBoolean hasSentVisibleThink = new AtomicBoolean(false);
        /** 已发生的模型调用轮数（ModelCallStartEvent 计数），用于多轮调用时提示第几轮。 */
        final AtomicInteger modelCallCount = new AtomicInteger(0);
        /** 请求级 Trace 会话，直接存储框架 AgentEvent，供 cleanup 组装 */
        final TraceSession traceCtx;
        final String requestId;

        StreamContext(SseEmitter emitter, ChatRequest req, RuntimeContext runtimeCtx, Msg userMsg, String episodicSessionId,
                      TraceSession traceCtx, String requestId) {
            this.emitter = emitter;
            this.req = req;
            this.runtimeCtx = runtimeCtx;
            this.userMsg = userMsg;
            this.episodicSessionId = episodicSessionId;
            this.conversationId = req.getConversationId();
            this.userId = req.getUserId();
            this.ansUUID = req.getConversationId();
            this.agentId = StringUtils.defaultIfBlank(req.getAgentId(), DEFAULT_AGENT_ID);
            this.agentName = StringUtils.defaultIfBlank(req.getAgentName(), DEFAULT_AGENT_NAME);
            this.formType = StringUtils.defaultIfBlank(req.getFromType(), DEFAULT_FROM_TYPE);
            this.traceCtx = traceCtx;
            this.requestId = requestId;
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
            safeSend(ctx.emitter, dto, MediaType.APPLICATION_JSON);
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
            safeSend(ctx.emitter, dto, MediaType.APPLICATION_JSON);
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
            safeSend(ctx.emitter, dto, MediaType.APPLICATION_JSON);
        }
    };

    private final ResponseStrategy publicStrategy = new ResponseStrategy() {
        @Override
        public void sendThink(StreamContext ctx, ThinkPayload payload) {
            ThinkResponseDto dto = new ThinkResponseDto();
            dto.setData(contentOf(payload));
            dto.setFinish(payload.isFinish());
            safeSend(ctx.emitter, dto, MediaType.APPLICATION_JSON);
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
            safeSend(ctx.emitter, dto, MediaType.APPLICATION_JSON);
        }

        @Override
        public void sendError(StreamContext ctx, Throwable error) {
            TextResponseDto dto = new TextResponseDto();
            ContentDto contentDto = new ContentDto();
            contentDto.setContent(buildErrorMessage(error));
            dto.setData(contentDto);
            dto.setFinish(true);
            safeSend(ctx.emitter, dto, MediaType.APPLICATION_JSON);
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

        String text = req.getQuestion();
        String userId = req.getUserId();
        String conversationId = req.getConversationId();
        ChatRuntimeConfig runtimeConfig =
                chatRuntimeConfigService.resolve(userId, conversationId);
        long streamTimeoutMs = TimeUnit.SECONDS.toMillis(
                runtimeConfig.getIntOrDefault(STREAM_TIMEOUT_SECONDS, 1200));
        ModelUtils.configureChunkGapTimeoutSeconds(
                runtimeConfig.getIntOrDefault(CHUNK_GAP_TIMEOUT_SECONDS, 120));

        // 容器级超时比看门狗晚 60 秒，只做兜底；真正的超时处理走下方看门狗。
        SseEmitter emitter = new SseEmitter(streamTimeoutMs + CONTAINER_TIMEOUT_GRACE);

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

        // 构造用户消息（纯文本内容块）
        Msg userMsg = Msg.builder().role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();

        // 构造运行时上下文：携带 sessionId / userId / lastQuestion 供中间件 / hooks 访问
        RuntimeContext ctx = buildRuntimeContext(conversationId, userId, text);
        ctx.put(ChatRuntimeConfigService.RUNTIME_CONFIG_CTX_KEY, runtimeConfig);
        ctx.put(ChatScriptExecResultHook.ENABLED_CTX_KEY,
                runtimeConfig.getBooleanOrDefault(SCRIPT_EXEC_ENABLED, false));
        String requestId = UUID.randomUUID().toString();
        ctx.put(ChatScriptExecResultHook.REQUEST_ID_CTX_KEY, requestId);

        // 工具结果引用开关：仅 /ai/chat 路径开启。ToolResultRefHook 据此把 script_exec
        // 的图表块登记进结果池并替换成 stub（/v2/ai/chat 不设此 key，行为不变）。

        // Trace 监控：创建请求级 TraceSession 并放入 RuntimeContext。
        TraceSession traceCtx = new TraceSession(conversationId, UUID.randomUUID().toString(), userId, "v1_chat", text);
        ctx.put(TraceSession.KEY, traceCtx);

        com.agentscopea2a.v2.middleware.ParentEmitterCarrier parentEmitterCarrier =
                new com.agentscopea2a.v2.middleware.ParentEmitterCarrier();
        ctx.put(com.agentscopea2a.v2.middleware.ParentEmitterCarrier.class, parentEmitterCarrier);


        String episodicUserId = userId != null && !userId.isBlank() ? userId : "anonymous";
        String episodicSessionId = "user:" + episodicUserId + ":" + conversationId;

        // 把 per-request 状态收拢进 StreamContext（参考 v1 流处理模式）
        StreamContext streamCtx = new StreamContext(emitter, req, ctx, userMsg, episodicSessionId, traceCtx, requestId);

        // 先发送一条真实的请求状态，避免模型首 token 较慢时前端看起来像卡死。
        sendVisibleThinkStatus(streamCtx, strategy, "正在调用模型，请稍候...\n");

        // 清理逻辑：取消订阅、清理 artifact、持久化 episodic 记忆、移除进行中调用标记
        Runnable cleanup = buildCleanup(streamCtx, callKey, inFlight);

        // 注册 SSE 生命周期回调：三种终止路径都走同一个幂等 cleanup。
        // 注意：onCompletion 不调用 handleStreamSuccess/handleStreamError，
        // 因为 Reactor 的 onComplete/onError 已经分别调用了它们。
        // 如果 onCompletion 再调一次，handleStreamSuccess 会被执行两次，
        // 导致 done 事件重复发送（前端收到"你好你好"的重复输出 bug）。
        emitter.onCompletion(cleanup);
        emitter.onTimeout(() -> {
            // 兜底路径：正常情况看门狗会先于容器超时（晚 60s）完成处理。走到这里说明
            // 连接已被容器关闭，此时任何 emitter.send 都到不了客户端，只做 trace 标记
            // 和资源清理，不再尝试 send（send 必然抛异常且前端收不到）。
            try {
                if (streamCtx.traceCtx != null) {
                    streamCtx.traceCtx.markTimeout();
                }
            } catch (Exception te) {
                log.warn("Trace markTimeout failed for sessionId={}: {}", streamCtx.conversationId, te.getMessage());
            }
            cleanup.run();
        });
        emitter.onError(e -> {
            handleStreamError(streamCtx, e, strategy);
            cleanup.run();
        });

        // 超时看门狗：到点时 emitter 尚未 complete、连接仍存活，此刻 send 超时错误
        // 是可达客户端的（这是与容器 async 超时的本质区别--容器超时后响应已销毁，
        // emitter.send 必然失败，前端收不到任何事件）。先发错误、再关连接、最后统一
        // cleanup（幂等）。
        streamCtx.timeoutWatchdog.set(SSE_TIMEOUT_WATCHDOG.schedule(() -> {
            if (streamCtx.cleaned.get()) return; // 已正常完成 / 已被其他路径清理
            log.warn("SSE watchdog timeout for sessionId={}", conversationId);
            // 1. Trace 状态标记 TIMEOUT（在 cleanup 的 assemble 之前）
            try {
                if (streamCtx.traceCtx != null) {
                    streamCtx.traceCtx.markTimeout();
                }
            } catch (Exception te) {
                log.warn("Trace markTimeout failed for sessionId={}: {}", streamCtx.conversationId, te.getMessage());
            }
            // 2. 先取消订阅，停止继续消耗 LLM token（dispose 是取消，不会触发 onError 回调）
            Disposable d = streamCtx.subscription.get();
            if (d != null && !d.isDisposed()) {
                d.dispose();
                log.info("v2 stream cancelled for sessionId={} (watchdog timeout)", streamCtx.conversationId);
            }
            // 3. 连接仍存活，发送超时错误事件（含"已执行"补发，保证与"执行中"成对）
            try {
                if (streamCtx.hasSentExecuting.get()) {
                    strategy.sendThink(streamCtx, ThinkPayload.done(AGENT_RETURN_NAME));
                }
                strategy.sendError(streamCtx, new RuntimeException(
                        "流程超时：本次对话处理时间过长（已等待 " + (streamTimeoutMs / 60_000) + " 分钟），会话已自动结束。"
                                + "请稍后重试，或尝试精简问题以缩短处理时间。"));
            } catch (Exception e) {
                log.warn("发送超时错误失败: sessionId={}", streamCtx.conversationId, e);
            }
            // 4. 关闭 emitter 并统一清理（saveAnswerIntoDB / trace 落库等照常执行）
            try {
                streamCtx.emitter.complete();
            } catch (Exception e) {
                log.warn("emitter.complete() 失败: sessionId={}", streamCtx.conversationId, e);
            }
            cleanup.run();
        }, streamTimeoutMs, TimeUnit.MILLISECONDS));

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
     *   <li>{@link AgentResultEvent} 是终止事件：提取最终文本累积到 {@link StreamContext#answerContent}，
     *       不立即发送，最终结果在 {@link #handleStreamSuccess} 中分片输出
     *   <li>{@link TextBlockDeltaEvent} 是流式 token：视为"思考"，累积到
     *       {@link StreamContext#thinkContent} 并通过 {@code sendThink}（action="执行中"）即时发送
     *   <li>{@link TextBlockStartEvent} 标记文本块开始：发送"执行中"标记
     *   <li>{@link TextBlockEndEvent} 标记文本块结束：发送"已执行"标记
     * </ul>
     * thinkContent 拼接所有思考内容，answerContent 拼接最终结果内容，用于最终落库。
     * 异常时调用 {@link #handleStreamError} 保证"执行中/已执行"成对和 emitter 正确关闭。
     */
    private void processChunk(AgentEvent event, StreamContext ctx, ResponseStrategy strategy) {
        try {
            // Trace 监控：仅采集 ModelCallEndEvent 的 token 用量。
            // 内容（LLM 输入/思考/输出、工具入参/返回）由 AiChatRestToolCallTrackingToDbHook 采集 Hook 事件承载，
            // 此处不再无差别收集 AgentEvent delta 流。try-catch + log.warn 保证 trace 失败不影响主链路。
            try {
                if (ctx.traceCtx != null && event instanceof ModelCallEndEvent mce) {
                    ctx.traceCtx.recordUsage(mce);
                }
            } catch (Exception te) {
                log.warn("TraceSession recordUsage failed for sessionId={}: {}", ctx.conversationId, te.getMessage());
            }
            // AgentResultEvent 是终止事件：最终结果累积到 answerContent，不立即发送
            if (event instanceof AgentResultEvent) {
                String text = extractText(((AgentResultEvent) event).getResult());
                if (StringUtils.isNotBlank(text)) {
                    ctx.answerContent.setLength(0);
                    ctx.answerContent.append(text);
                }
                return;
            }

            // 工具生命周期事件不写入 thinkContent，只作为前端即时状态提示。
            if (event instanceof ModelCallStartEvent) {
                // 多轮模型调用：首轮提示已在 stream() 开头发出，此处只补第 2 轮及以后的状态，
                // 让用户在"工具执行完成"后知道模型又被调用了一次。
                if (ctx.modelCallCount.incrementAndGet() > 1) {
                    sendVisibleThinkStatus(ctx, strategy, "正在调用模型，请稍候...\n");
                }
            }
            if (event instanceof ToolCallStartEvent toolStart) {
                String toolName = toolStart.getToolCallName();
                sendVisibleThinkStatus(ctx, strategy, "正在调用工具：" + toolName + "..." + "\n");
            } else if (event instanceof ToolResultEndEvent toolEnd) {
                String toolName = toolEnd.getToolCallName();
                sendVisibleThinkStatus(ctx, strategy, "工具执行完成：" + toolName + "\n");
            }

            // 从流式增量事件中提取文本 chunk
            String chunk = null;
            if (event instanceof TextBlockDeltaEvent delta) {
                chunk = delta.getDelta();
            }else if (event instanceof ThinkingBlockDeltaEvent delta){
                chunk = delta.getDelta();
            }
            // TextBlockStartEvent 是标记性事件 - 不携带文本内容，直接跳过

            // 没有 chunk 或 chunk 为空，直接返回（不发送 SSE）
            if (StringUtils.isBlank(chunk)) return;

            // 其他所有输出都视为思考，累积并发送"执行中"
            ctx.thinkContent.append(chunk);
            ctx.hasSentExecuting.set(true);
            strategy.sendThink(ctx, ThinkPayload.progress(chunk));
            ctx.hasSentVisibleThink.set(true);

        } catch (Exception e) {
            // 仅在确实发送过"执行中"时才补发"已执行"，保证成对
            if (ctx.hasSentExecuting.get()) {
                strategy.sendThink(ctx, ThinkPayload.done(AGENT_RETURN_NAME));
            }
            log.error("处理流式事件失败: sessionId={}", ctx.conversationId, e);
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    /** 发送仅供前端展示的运行状态，不追加到持久化的 thinkContent。 */
    private void sendVisibleThinkStatus(StreamContext ctx, ResponseStrategy strategy, String status) {
        if (StringUtils.isBlank(status)) return;
        String content = ctx.hasSentVisibleThink.get() ? "\n" + status : status;
        strategy.sendThink(ctx, ThinkPayload.progress(content));
        ctx.hasSentVisibleThink.set(true);
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
        // Trace 状态标记：标记 ERROR（在 cleanup 的 assemble 之前调用，设计 5.2）。
        try {
            if (ctx.traceCtx != null) {
                ctx.traceCtx.markError(error == null ? "unknown error" : error.getMessage());
            }
        } catch (Exception te) {
            log.warn("Trace markError failed for sessionId={}: {}", ctx.conversationId, te.getMessage());
        }
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
                strategy.sendThink(ctx, ThinkPayload.done(AGENT_RETURN_NAME));
            }
            strategy.sendError(ctx, error);
        } catch (Exception e) {
            log.warn("发送错误结果失败: sessionId={}", ctx.conversationId, e);
        } finally {
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
                strategy.sendThink(ctx, ThinkPayload.done(AGENT_RETURN_NAME));
            }
            // 流式输出最终结果：每 5 个字符一片
            String finalAnswer = ctx.answerContent.toString();
            // 仅展开本请求的 refs，历史会话残留的 marker 不得串入当前轮。
            // 即使模型未输出 marker，本轮 HTML/ECharts 也会在末尾补齐。
            List<String> currentRefs = toolResultRegistry.getRequestRefs(ctx.requestId);
            finalAnswer = toolResultRegistry.resolveAndAppendCurrentResults(finalAnswer, currentRefs);
            // 保存与前端展示相同的最终答案，刷新历史时也能看到本轮图表。
            ctx.answerContent.setLength(0);
            ctx.answerContent.append(finalAnswer == null ? "" : finalAnswer);
            // 回答末尾追加管理后台地址(md)。配置留空则不追加。
            // 管理后台地址仍只作用于 SSE 下发，不写入问答库。
//            if (StringUtils.isNotBlank(managementUrl)) {
//                String notice = "\n\n---\n管理后台页面：[点击进入](" + managementUrl + ")";
//                finalAnswer = StringUtils.isBlank(finalAnswer) ? notice : finalAnswer + notice;
//            }

            if (StringUtils.isNotBlank(finalAnswer)) {
                int len = finalAnswer.length();
                int pos = 0;
                while (pos < len) {
                    int end = Math.min(pos + 5, len);
                    String chunk = finalAnswer.substring(pos, end);
                    boolean isLast = (end == len);
                    strategy.sendText(ctx, TextPayload.chunk(chunk, isLast));
                    pos = end;
                }
            } else {
                // AgentResultEvent may be terminal while carrying no text (for example when
                // the model returns an empty completion). Never close the SSE stream silently:
                // the client needs a terminal error frame to stop its loading state and expose
                // a useful retryable failure.
                log.warn("Empty agent completion: sessionId={} thinkLen={} refs={}",
                        ctx.conversationId, ctx.thinkContent.length(), currentRefs.size());
                strategy.sendError(ctx, new IllegalStateException("模型返回为空,请稍后重试..."));
            }
        } catch (Exception e) {
            log.warn("发送最终结果失败: sessionId={}", ctx.conversationId, e);
        } finally {
            // Trace 状态标记：标记 SUCCESS（在 cleanup 的 assemble 之前调用，设计 5.2）。
            // markSuccess 仅在 RUNNING 时生效，已为终态（ERROR/TIMEOUT）时不覆盖。
            try {
                if (ctx.traceCtx != null) {
                    ctx.traceCtx.markSuccess();
                }
            } catch (Exception te) {
                log.warn("Trace markSuccess failed for sessionId={}: {}", ctx.conversationId, te.getMessage());
            }
            try {
                ctx.emitter.complete();
            } catch (Exception e) {
                log.warn("emitter.complete() 失败: sessionId={}", ctx.conversationId, e);
            }
        }
    }

    private static List<String> currentChatToolResultRefs(RuntimeContext runtimeCtx) {
        Object refs = runtimeCtx.get(ChatScriptExecResultHook.REFERENCES_CTX_KEY);
        if (!(refs instanceof List<?> values)) return List.of();
        return values.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }

    /**
     * 发送 SSE 数据的包装方法（参考 v1 safeSend 模式）：
     * 把 IOException 转为 RuntimeException，避免在每个发送点重复 try/catch。
     */
    private void safeSend(SseEmitter emitter, Object data, MediaType mediaType) {
        try {
            emitter.send(data, mediaType);
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
            toolResultRegistry.clearRequestRefs(ctx.requestId);
            // 0. 取消超时看门狗（正常完成时无需触发；watchdog 自身调用 cleanup 时 cancel 是无害的空操作）
            ScheduledFuture<?> watchdog = ctx.timeoutWatchdog.get();
            if (watchdog != null) {
                watchdog.cancel(false);
            }
            // 1. 取消 Reactor 订阅，停止继续消耗 LLM token
            Disposable d = ctx.subscription.get();
            if (d != null && !d.isDisposed()) {
                d.dispose();
                log.info("v2 stream cancelled for sessionId={} (client disconnect/timeout)", ctx.conversationId);
            }
            // 1b. Trace 组装并直接落库（不再走定时队列）。中间事件由 TraceSession 在请求期间
            //     缓存（records），assemble 后一次性写入；成功/失败/超时均执行（cleanup 在所有
            //     终止路径触发）。状态标记已在 handleStreamSuccess/handleStreamError/onTimeout 完成。
            //     异步执行，不阻塞 SSE 完成回调（与 answer 落库一致）。
            try {
                if (ctx.traceCtx != null) {
                    AssembledTrace trace = traceAssembler.assemble(ctx.traceCtx);
                    Mono.fromRunnable(() -> {
                        try {
                            traceBatchWriter.write(trace);
                        } catch (Exception ex) {
                            log.warn("Trace write failed for sessionId={}: {}", ctx.conversationId, ex.getMessage());
                        }
                    }).subscribeOn(Schedulers.boundedElastic()).subscribe();
                }
            } catch (Exception ex) {
                log.warn("Trace assemble failed for sessionId={}: {}", ctx.conversationId, ex.getMessage());
            }
            // 2. 清理本次会话产生的临时 artifact（沙箱文件等）
            try {
                artifactStore.cleanupTask(ArtifactContext.from(ctx.runtimeCtx));
            } catch (Exception ex) {
                log.warn("Artifact cleanup failed for sessionId={}: {}", ctx.conversationId, ex.getMessage());
            }
            // 3. 持久化问答记录（think/answer）到 DB。内容由 processChunk 在 reactor 线程累积，
            //    此处经 SSE 完成回调的 happens-before 可见；answerContent 为空时回退 thinkContent。
            //    异步执行，不阻塞 SSE 完成回调（与 episodic 持久化一致）。
            Mono.fromRunnable(() -> {
                try {
                    saveAnswerIntoDB(ctx);
                } catch (Exception ex) {
                    log.warn("Answer persist failed for sessionId={}: {}", ctx.conversationId, ex.getMessage());
                }
            }).subscribeOn(Schedulers.boundedElastic()).subscribe();
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
        return friendlyErrorMessage(error);
    }

    static String friendlyErrorMessage(Throwable error) {
//        if (isRetryOrTimeout(error)) {
//            return "请求已达最大重试次数，当前模型资源不足，请稍后再试。";
//        }
//        return "模型服务暂时不可用，请稍后重试";
        return "请求模型超时,请稍后重试";
    }

    private static boolean isRetryOrTimeout(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String text = (current.getClass().getName() + " " + current.getMessage()).toLowerCase(Locale.ROOT);
            if (text.contains("retries exhausted") || text.contains("retry exhausted")
                    || text.contains("model request timeout") || text.contains("timeout")
                    || text.contains("timed out") || text.contains("read timed out")
                    || text.contains("超时")) {
                return true;
            }
        }
        return false;
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


    /**
     * 保存问答记录到数据库，包含 think（思考）和 answer（结果）两个独立字段。
     *
     * <p>内容由 {@link #processChunk} 在 reactor 线程累积：{@code thinkContent} 收集所有流式
     * 增量（TextBlockDeltaEvent + ThinkingBlockDeltaEvent），{@code answerContent} 仅由
     * {@link AgentResultEvent} 的最终结果文本填充。当最终结果 Msg 不含 TextBlock（以 tool_use
     * 收尾、或 supervisor 返回非文本结果）时 {@code answerContent} 为空，此时回退到
     * {@code thinkContent}（流式增量已累积完整答案，与 V2ChatStreamServiceImpl 一致），保证
     * 最终答案不丢。两者皆空（流式未产出文本，如早期异常）时跳过入库，避免写空行。
     */
    private void saveAnswerIntoDB(StreamContext ctx) {
        if (ctx.req == null || StringUtils.isEmpty(ctx.req.getConversationId())) {
            return;
        }
        String think = ctx.thinkContent.toString();
        String answer = ctx.answerContent.toString();
        log.info("saveAnswerIntoDB: sessionId={} thinkLen={} answerLen={}",
                ctx.conversationId, think.length(), answer.length());
        if (StringUtils.isBlank(answer)) {
            answer = think;
        }
        if (StringUtils.isBlank(think) && StringUtils.isBlank(answer)) {
            log.warn("saveAnswerIntoDB skipped: think/answer both blank for sessionId={}", ctx.conversationId);
            return;
        }
        QuestionAnswerDto dto = createAnswerInit(ctx.req, think, answer);
        saveAnswerIntoDB(dto);
    }

    private QuestionAnswerDto createAnswerInit(ChatRequest chatReqDTO, String thinkContent, String answerContent) {
        QuestionAnswerDto questionAnswerDTO = new QuestionAnswerDto();
        questionAnswerDTO.setUserId(chatReqDTO.getUserId());
        questionAnswerDTO.setQuestion(chatReqDTO.getQuestion());
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
