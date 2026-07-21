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
package com.agentscopea2a.v2.hooks;

import com.agentscopea2a.entity.AiChatResult;
import com.agentscopea2a.v2.tools.ToolCallCollector;
import com.agentscopea2a.v2.util.HookRuntimeContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.hook.RuntimeContextAware;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Records every L1 (supervisor-level) tool invocation into the request-scoped
 * {@link ToolCallCollector} via {@link PreActingEvent} (tool name + input) and
 * {@link PostActingEvent} (output/result).
 *
 * <p><b>Context propagation:</b> The collector is stored on the per-call
 * {@link RuntimeContext} under key {@link #COLLECTOR_CTX_KEY} by
 * {@link com.agentscopea2a.v2.service.V2ChatStreamServiceImpl}. This hook implements
 * {@link RuntimeContextAware} so the framework pushes the current call's context before
 * the call starts and clears it after. This replaces the previous ThreadLocal approach,
 * which broke because reactive streams cross thread boundaries (the hook fires on
 * reactor scheduler threads, not the executor thread where the ThreadLocal was bound).
 *
 * <p>Priority 45 - after framework-internal acting hooks but before
 * SkillEvolutionHook(60).
 *
 * <p><b>Note:</b> Uses deprecated Hook/PreActingEvent/PostActingEvent API because
 * v2 MiddlewareBase doesn't offer both pre- and post-tool-call hooks in a single unit.
 *
 * <p>Bean created by {@link com.agentscopea2a.v2.config.V2InfraConfig}.
 */
@SuppressWarnings("deprecation")
public class ToolCallTrackingHook implements Hook, RuntimeContextAware {

    private static final Logger log = LoggerFactory.getLogger(ToolCallTrackingHook.class);

    /** Key under which the per-request ToolCallCollector is stored on RuntimeContext. */
    public static final String COLLECTOR_CTX_KEY = "toolCallCollector";

    /**
     * Key under which the per-request SseEmitter is stored on RuntimeContext, so this
     * hook can send a supplementary "tool_output" SSE event directly from PostActing.
     * The framework's tool_result_end AgentEvent fires BEFORE PostActing, so the SSE
     * handler's collector lookup at tool_result_end time returns an empty output. By
     * emitting a separate tool_output event here (keyed by toolCallId), the frontend
     * can match it to the existing ActivityFeed row and populate the "出参" panel.
     */
    public static final String EMITTER_CTX_KEY = "sseEmitter";

    /**
     * Key under which the per-request SSE metadata (ansUUID, agentId, agentName,
     * formType, conversationId) is stored on RuntimeContext. Used by the
     * supplementary tool_output SSE event so it carries the same metadata as the
     * regular process events.
     */
    public static final String SSE_META_CTX_KEY = "sseMeta";

    /** SSE metadata carrier — populated by V2ChatStreamServiceImpl at request start. */
    public record SseMeta(String ansUUID, String agentId, String agentName,
                          String formType, String conversationId) {}

    private static final ObjectMapper SSE_MAPPER = new ObjectMapper();
    private static final int SSE_OUTPUT_MAX_LEN = 4000;

    /**
     * Fallback only - populated by {@link RuntimeContextAware#setRuntimeContext} for tests that
     * drive the hook synchronously without Reactor context. Production code resolves ctx from
     * Reactor's ContextView via {@link HookRuntimeContext#resolve()} to avoid the multi-user
     * cross-talk documented in optimization-analysis.md P1-2.
     */
    private volatile RuntimeContext currentCtx;

    /**
     * No-arg constructor - the per-request collector is retrieved from the
     * {@link RuntimeContext} pushed by the framework via {@link RuntimeContextAware}.
     */
    public ToolCallTrackingHook() {
    }

    @Override
    public int priority() {
        return 45;
    }

    @Override
    public void setRuntimeContext(RuntimeContext context) {
        this.currentCtx = context;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        return HookRuntimeContext.resolve()
                .doOnNext(ctx -> track(event, ctx))
                .switchIfEmpty(Mono.fromRunnable(() -> {
                    if (currentCtx != null) {
                        track(event, currentCtx);
                    }
                }))
                .then(Mono.just(event));
    }

    private void track(HookEvent event, RuntimeContext ctx) {
        ToolCallCollector collector = ctx.get(COLLECTOR_CTX_KEY);
        if (collector == null) {
            return;
        }
        if (event instanceof PreActingEvent pre) {
            handlePreActing(pre, collector);
        } else if (event instanceof PostActingEvent post) {
            handlePostActing(post, collector, ctx);
        }
    }

    // -------- PreActing: record tool name + input --------

    private void handlePreActing(PreActingEvent event, ToolCallCollector collector) {
        ToolUseBlock toolUse = event.getToolUse();
        if (toolUse == null) return;

        String toolName = toolUse.getName();
        if (toolName == null) return;

        String input = formatInput(toolUse.getInput());
        log.info("[ToolCallTracking] PreActing tool={} inputLen={}", toolName, input.length());
        collector.recordL1(toolName, input, "");
        // Also record by toolCallId for live SSE lookup — V2ChatStreamServiceImpl
        // uses this to attach input to the tool_call_start SSE event.
        collector.recordByToolCallId(toolUse.getId(), toolName, input);
    }

    // -------- PostActing: fill in the output --------

    private void handlePostActing(PostActingEvent event, ToolCallCollector collector, RuntimeContext ctx) {
        ToolUseBlock toolUse = event.getToolUse();
        ToolResultBlock result = event.getToolResult();
        if (toolUse == null || result == null) return;

        String toolName = toolUse.getName();
        if (toolName == null) return;

        String output = extractText(result.getOutput());
        log.info("[ToolCallTracking] PostActing tool={} outputLen={} blank={}",
                toolName, output.length(), output.isBlank());
        if (output.isBlank()) return;

        collector.updateLastL1Output(toolName, output);
        // Also update by toolCallId for live SSE lookup — V2ChatStreamServiceImpl
        // uses this to attach output to the tool_result_end SSE event.
        collector.updateOutputByToolCallId(toolUse.getId(), output);

        // Send a supplementary "tool_output" SSE event directly from PostActing.
        // The framework's tool_result_end AgentEvent fires BEFORE PostActing, so the
        // SSE handler's collector lookup at tool_result_end time returns an empty
        // output. By emitting a separate tool_output event here (keyed by toolCallId),
        // the frontend can match it to the existing ActivityFeed row and populate
        // the "出参" panel. See EMITTER_CTX_KEY javadoc for the full rationale.
        sendToolOutputSseEvent(ctx, toolUse.getId(), toolName, output);
    }

    /**
     * Send a supplementary "tool_output" SSE event to the SseEmitter stored on
     * RuntimeContext (if present). Failures are logged and swallowed — this is a
     * best-effort UX enhancement, not a critical-path event.
     */
    private void sendToolOutputSseEvent(RuntimeContext ctx, String toolCallId,
                                       String toolName, String output) {
        SseEmitter emitter = ctx.get(EMITTER_CTX_KEY);
        SseMeta meta = ctx.get(SSE_META_CTX_KEY);
        if (emitter == null || meta == null) return;
        try {
            String truncated = output.length() > SSE_OUTPUT_MAX_LEN
                    ? output.substring(0, SSE_OUTPUT_MAX_LEN) + "..."
                    : output;
            AiChatResult payload = AiChatResult.builder()
                    .code(0)
                    .ansUUID(meta.ansUUID())
                    .lineResult("📤 " + toolName + " 输出")
                    .formType(meta.formType())
                    .agentId(meta.agentId())
                    .agentName(meta.agentName())
                    .eventType("tool_output")
                    .toolCallId(toolCallId)
                    .toolCallName(toolName)
                    .toolOutput(truncated)
                    .conversationId(meta.conversationId())
                    .build();
            String json = SSE_MAPPER.writeValueAsString(payload);
            emitter.send(SseEmitter.event().name("tool_output").data(json));
        } catch (Exception e) {
            log.warn("[ToolCallTracking] tool_output SSE send failed for toolCallId={}: {}",
                    toolCallId, e.getMessage());
        }
    }

    private static String formatInput(Object input) {
        if (input == null) return "";
        if (input instanceof java.util.Map<?, ?> map) {
            StringBuilder sb = new StringBuilder();
            for (java.util.Map.Entry<?, ?> e : map.entrySet()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(e.getKey()).append("=").append(e.getValue());
            }
            return sb.toString();
        }
        String s = input.toString();
        return s.length() > 500 ? s.substring(0, 500) + "..." : s;
    }

    private static String extractText(List<ContentBlock> blocks) {
        if (blocks == null) return "";
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : blocks) {
            if (b instanceof TextBlock tb && tb.getText() != null) {
                sb.append(tb.getText()).append('\n');
            }
        }
        return sb.toString().trim();
    }
}
