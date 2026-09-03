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
package com.agentscopea2a.v2.trace.collector;

import com.agentscopea2a.v2.trace.model.TraceEventRecord;
import com.agentscopea2a.v2.util.HookRuntimeContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.ErrorEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.hook.RuntimeContextAware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.UUID;

/**
 * ai/chat 接口专用的 Trace 落库 Hook：捕获携带完整 payload 的 Hook 事件（Pre/PostCall、
 * Pre/PostReasoning、Pre/PostActing、Error），序列化为 {@link TraceEventRecord} 存入
 * {@link TraceSession}，最终异步写入 ClickHouse trace_event 表。
 *
 * <p><b>不发送 SSE</b>——本 hook 只做离线 trace 落库，不向前端推送任何事件。
 *
 * <p>设计依据：框架 AgentEvent 流的 {@code *EndEvent} 不携带内容，内容只在按 token 触发的
 * {@code *DeltaEvent}（一次推理几百上千条）里。而 Hook 事件每个操作触发一次且带完整 payload
 * （LLM 输入/思考/输出、工具入参/返回），与框架自带 {@code JsonlTraceExporter} 的默认采集集
 * （{PRE_CALL, POST_CALL, PRE_REASONING, POST_REASONING, PRE_ACTING, POST_ACTING, ERROR}）一致。
 * priority=47，紧跟 VerificationHook(46)，确保 PostActing 的 toolResult 为最终值。
 *
 * <p>仅 v1 {@code /ai/chat} 使用：v1 在 RuntimeContext 创建 TraceSession；v2 {@code /v2/ai/chat}
 * 不创建 TraceSession，本 hook 在 {@code ctx.get(TraceSession.KEY)} 为 null 时自动 no-op。
 */
@SuppressWarnings("deprecation")
public class AiChatRestToolCallTrackingToDbHook implements Hook, RuntimeContextAware {

    private static final Logger log = LoggerFactory.getLogger(AiChatRestToolCallTrackingToDbHook.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 单个字符串字段最大长度，超长截断（保护 ClickHouse event_json 行大小）。 */
    private static final int MAX_FIELD_LEN = 65536;

    /** fallback only（单例并发会覆盖，仅测试用），生产走 HookRuntimeContext.resolve()。 */
    private volatile RuntimeContext currentCtx;

    @Override
    public int priority() {
        return 47;
    }

    @Override
    public void setRuntimeContext(RuntimeContext context) {
        this.currentCtx = context;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        return HookRuntimeContext.resolve()
                .doOnNext(ctx -> capture(event, ctx))
                .switchIfEmpty(Mono.fromRunnable(() -> {
                    if (currentCtx != null) {
                        capture(event, currentCtx);
                    }
                }))
                .then(Mono.just(event));
    }

    private void capture(HookEvent event, RuntimeContext ctx) {
        TraceSession session = ctx.get(TraceSession.KEY);
        if (session == null || session.isSealed()) {
            return;
        }
        try {
            Instant capturedAt = Instant.now();
            long capturedNanos = System.nanoTime();
            TraceSession.ToolTiming timing = null;
            if (event instanceof PreActingEvent pre) {
                String id = pre.getToolUse() == null ? "" : pre.getToolUse().getId();
                session.startToolTiming(id, capturedAt, capturedNanos);
            } else if (event instanceof PostActingEvent post) {
                String id = post.getToolUse() == null ? "" : post.getToolUse().getId();
                timing = session.finishToolTiming(id, capturedAt, capturedNanos);
            }
            String json = toJson(event, capturedAt.toString(), timing);
            if (json != null) {
                session.addRecord(new TraceEventRecord(capturedAt.toString(), json));
            }
        } catch (Exception e) {
            log.warn("AiChatRestToolCallTrackingToDbHook capture failed: {} {}", event.getType(), e.getMessage());
        }
    }

    private String toJson(HookEvent event, String createdAt, TraceSession.ToolTiming timing) throws Exception {
        ObjectNode node = MAPPER.createObjectNode();
        String type = event.getType().name();
        node.put("id", UUID.randomUUID().toString().replace("-", ""));
        node.put("type", type);
        node.put("createdAt", createdAt);
        node.put("source", sourceOf(event));
        if (timing != null) {
            node.put("startedAt", timing.startedAt());
            node.put("endedAt", timing.endedAt());
            node.put("durationMs", timing.durationMs());
        } else if (event instanceof PreActingEvent) {
            node.put("startedAt", createdAt);
        }

        switch (type) {
            case "PRE_CALL" -> {
                PreCallEvent e = (PreCallEvent) event;
                node.set("input_messages", trunc(MAPPER.valueToTree(e.getInputMessages())));
            }
            case "POST_CALL" -> {
                PostCallEvent e = (PostCallEvent) event;
                node.set("final_message", trunc(MAPPER.valueToTree(e.getFinalMessage())));
            }
            case "PRE_REASONING" -> {
                PreReasoningEvent e = (PreReasoningEvent) event;
                node.put("model_name", e.getModelName());
                // 系统提示词单独走 getSystemMessage()，不在 input_messages 里（框架设计），
                // 需单独记录，否则 LLM 输入只剩 user input。
                node.set("system_message", trunc(MAPPER.valueToTree(e.getSystemMessage())));
                node.set("input_messages", trunc(MAPPER.valueToTree(e.getInputMessages())));
            }
            case "POST_REASONING" -> {
                PostReasoningEvent e = (PostReasoningEvent) event;
                node.put("model_name", e.getModelName());
                node.set("reasoning_message", trunc(MAPPER.valueToTree(e.getReasoningMessage())));
            }
            case "PRE_ACTING" -> {
                PreActingEvent e = (PreActingEvent) event;
                node.set("tool_use", trunc(MAPPER.valueToTree(e.getToolUse())));
            }
            case "POST_ACTING" -> {
                PostActingEvent e = (PostActingEvent) event;
                node.set("tool_use", trunc(MAPPER.valueToTree(e.getToolUse())));
                node.set("tool_result", trunc(MAPPER.valueToTree(e.getToolResult())));
            }
            case "ERROR" -> {
                ErrorEvent e = (ErrorEvent) event;
                Throwable err = e.getError();
                node.put("error_class", err == null ? "" : err.getClass().getName());
                node.put("error_message", err == null ? "" : String.valueOf(err.getMessage()));
                node.put("stacktrace", err == null ? "" : stackTraceToString(err));
            }
            default -> {
                // 非 trace 关心的事件，不记录
                return null;
            }
        }
        return MAPPER.writeValueAsString(node);
    }

    private static String sourceOf(HookEvent event) {
        try {
            var agent = event.getAgent();
            return agent == null ? "" : String.valueOf(agent.getName());
        } catch (Exception e) {
            return "";
        }
    }

    /** 递归截断超长字符串字段，保持 JSON 结构有效。 */
    private static JsonNode trunc(JsonNode node) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (node.isTextual()) {
            String s = node.asText();
            if (s.length() > MAX_FIELD_LEN) {
                int half = MAX_FIELD_LEN / 2;
                return MAPPER.getNodeFactory().textNode(
                        s.substring(0, half) + "\n[truncated]\n" + s.substring(s.length() - half));
            }
            return node;
        }
        if (node.isObject()) {
            ObjectNode copy = MAPPER.createObjectNode();
            node.fields().forEachRemaining(e -> copy.set(e.getKey(), trunc(e.getValue())));
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = MAPPER.createArrayNode();
            for (JsonNode el : node) {
                copy.add(trunc(el));
            }
            return copy;
        }
        return node;
    }

    private static String stackTraceToString(Throwable error) {
        StringWriter sw = new StringWriter();
        try (PrintWriter pw = new PrintWriter(sw)) {
            error.printStackTrace(pw);
        }
        return sw.toString();
    }
}
