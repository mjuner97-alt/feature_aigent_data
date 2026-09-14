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
import com.agentscopea2a.v2.service.ChatStreamTimeoutWatchdog;
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
import java.util.Map;

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
     * hook can send supplementary tool output SSE events directly from PostActing.
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

    /** Suppress regular tool_output events while retaining renderable script_output. */
    public static final String SCRIPT_OUTPUT_ONLY_CTX_KEY = "scriptOutputOnly";

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
        if (event instanceof PreActingEvent pre) {
            upgradeAnalysisTimeoutIfNeeded(pre.getToolUse(), ctx);
        }
        ToolCallCollector collector = ctx.get(COLLECTOR_CTX_KEY);
        if (collector == null) {
            return;
        }
        if (event instanceof PreActingEvent pre) {
            handlePreActing(pre, collector, ctx);
        } else if (event instanceof PostActingEvent post) {
            handlePostActing(post, collector, ctx);
        }
    }

    /**
     * 根据当前工具调用判断是否需要把请求级超时档位升级为 analyze_data 分析档。
     *
     * <p>背景：</p>
     * <ul>
     *     <li>/ai/chat 请求默认使用普通档超时（例如 30 分钟），覆盖普通问答、简单问数和 Skill 流程。</li>
     *     <li>当模型实际派生 analyze_data 子 Agent 时，任务耗时可能显著变长，需要升级到分析档
     *         （例如 60 分钟），且不能中断当前请求。</li>
     *     <li>升级由看门狗负责，升级后仍以请求进入接口的时间为起点重新计算剩余时间，不会重置计时。</li>
     * </ul>
     *
     * <p>方法职责：</p>
     * <ol>
     *     <li>判断当前 toolUse 是否是派发 analyze_data 子 Agent 的调用；不是则直接返回，保持普通档。</li>
     *     <li>从 RuntimeContext 中取出当前请求的看门狗实例。</li>
     *     <li>调用 {@link ChatStreamTimeoutWatchdog#upgradeToAnalysis()} 尝试升级档位。</li>
     *     <li>升级成功后，把当前档位写回 RuntimeContext，供日志和链路观测使用。</li>
     * </ol>
     *
     * <p>幂等性：</p>
     * <ul>
     *     <li>看门狗内部的 {@code upgradeToAnalysis()} 使用 CAS 保证档位只能从 NORMAL 升级到
     *         ANALYZE_DATA 一次，重复调用会返回 false，不会重复调度或重复记录日志。</li>
     *     <li>本方法本身不保证幂等，但依赖看门狗的幂等性，因此可以安全地在每次工具调用时调用。</li>
     * </ul>
     *
     * <p>空值处理：</p>
     * <ul>
     *     <li>toolUse 为 null 时直接返回，避免空指针。</li>
     *     <li>RuntimeContext 中没有看门狗时（例如非 /ai/chat 请求或看门狗未初始化），直接跳过升级，
     *         不影响主流程。</li>
     * </ul>
     *
     * @param toolUse 当前模型发起的工具调用块，包含工具名和输入参数
     * @param ctx     当前请求的 AgentScope RuntimeContext，用于存取请求级看门狗和档位标记
     */
    private void upgradeAnalysisTimeoutIfNeeded(ToolUseBlock toolUse, RuntimeContext ctx) {
        // 只有模型实际派生 analyze_data 时才升级到 60 分钟；Skill 和普通问答继续使用 30 分钟。
        if (toolUse == null || !isAnalyzeDataSpawn(toolUse.getName(), toolUse.getInput())) return;

        // 取出请求级看门狗。非 /ai/chat 请求或未初始化看门狗时返回 null，此时不做升级。
        ChatStreamTimeoutWatchdog watchdog = ctx.get(ChatStreamTimeoutWatchdog.RUNTIME_CONTEXT_KEY);

        // upgradeToAnalysis() 内部通过 CAS 保证只升级一次；返回 true 表示本次调用真正完成了升级。
        if (watchdog != null && watchdog.upgradeToAnalysis()) {
            // 记录当前档位，便于日志、链路追踪和问题排查时确认请求实际使用的超时策略。
            ctx.put(ChatStreamTimeoutWatchdog.PROFILE_CONTEXT_KEY,
                    ChatStreamTimeoutWatchdog.Profile.ANALYZE_DATA.name());
            log.info("[ToolCallTracking] /ai/chat timeout profile upgraded to ANALYZE_DATA");
        }
    }

    /**
     * 判断一次工具调用是否是派发 analyze_data 子 Agent。
     *
     * <p>判定规则：</p>
     * <ol>
     *     <li>工具名必须严格等于 {@code agent_spawn}（区分大小写）。</li>
     *     <li>输入参数 input 不能为 null。</li>
     *     <li>输入参数中必须包含 {@code agent_id} 字段，且其值为字符串。</li>
     *     <li>{@code agent_id} 去除首尾空白后，忽略大小写等于 {@code analyze_data}。</li>
     * </ol>
     *
     * <p>示例：以下输入都会被认为是 analyze_data 派发：</p>
     * <ul>
     *     <li>{@code {"agent_id": "analyze_data"}}</li>
     *     <li>{@code {"agent_id": " Analyze_Data "}}</li>
     * </ul>
     *
     * <p>以下输入不会被识别：</p>
     * <ul>
     *     <li>工具名不是 agent_spawn，例如 skill_call、simple_query。</li>
     *     <li>input 为 null，或没有 agent_id 字段。</li>
     *     <li>agent_id 不是字符串，例如数字或嵌套对象。</li>
     *     <li>agent_id 是其他子 Agent，例如 report_generator。</li>
     * </ul>
     *
     * @param toolName 工具名称，例如 agent_spawn
     * @param input    工具输入参数，通常是 Map 结构
     * @return true 表示这是一次派发 analyze_data 子 Agent 的调用；否则返回 false
     */
    static boolean isAnalyzeDataSpawn(String toolName, Map<?, ?> input) {
        // 仅识别 agent_spawn 工具；其他工具一律不触发档位升级。
        if (!"agent_spawn".equals(toolName) || input == null) return false;

        // 读取 agent_id 并做类型校验，避免 ClassCastException 或误判。
        Object target = input.get("agent_id");

        // 仅当 agent_id 是字符串且 trim 后忽略大小写等于 analyze_data 时，才认为是分析档派发。
        return target instanceof String value && "analyze_data".equalsIgnoreCase(value.trim());
    }

    // -------- PreActing: record tool name + input --------

    private void handlePreActing(PreActingEvent event, ToolCallCollector collector, RuntimeContext ctx) {
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

        // Publish the Skill only after the trusted loader returned a non-empty result.
        // A failed or denied skill load must not affect subsequent tool calls.
        if ("load_skill_through_path".equals(toolName) && toolUse.getInput() != null
                && result.getState() != io.agentscope.core.message.ToolResultState.ERROR
                && result.getState() != io.agentscope.core.message.ToolResultState.DENIED) {
            Object name = toolUse.getInput().get("name");
            if (name instanceof String skillName && !skillName.isBlank()
                    && !"_common".equalsIgnoreCase(skillName.trim())
                    && !"tool_index".equalsIgnoreCase(skillName.trim())) {
                ctx.put("activeSkillName", skillName.trim());
            }
        }

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
        if ("script_exec".equals(toolName)) {
            // Always send the complete non-empty result to the activity sidebar,
            // including plain-text/empty-data diagnostics without ECharts/HTML.
            // The chat pane independently extracts only renderable blocks.
            sendScriptOutputSseEvent(ctx, toolUse.getId(), toolName, output);
        } else if (!Boolean.TRUE.equals(ctx.get(SCRIPT_OUTPUT_ONLY_CTX_KEY))) {
            sendToolOutputSseEvent(ctx, toolUse.getId(), toolName, output);
        }
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

    /**
     * Send the complete script result as a first-class SSE event. Script output is
     * often the actual report (Markdown/HTML/ECharts), so the chat client must see
     * it without waiting for the model to quote it in its final answer. Unlike the
     * activity-panel event above, this payload is intentionally not truncated.
     */
    private void sendScriptOutputSseEvent(RuntimeContext ctx, String toolCallId,
                                          String toolName, String output) {
        SseEmitter emitter = ctx.get(EMITTER_CTX_KEY);
        SseMeta meta = ctx.get(SSE_META_CTX_KEY);
        if (emitter == null || meta == null) return;
        try {
            AiChatResult payload = AiChatResult.builder()
                    .code(0)
                    .ansUUID(meta.ansUUID())
                    .lineResult("script_exec 输出")
                    .formType(meta.formType())
                    .agentId(meta.agentId())
                    .agentName(meta.agentName())
                    .eventType("script_output")
                    .toolCallId(toolCallId)
                    .toolCallName(toolName)
                    .toolOutput(output)
                    .conversationId(meta.conversationId())
                    .build();
            String json = SSE_MAPPER.writeValueAsString(payload);
            emitter.send(SseEmitter.event().name("script_output").data(json));
        } catch (Exception e) {
            log.warn("[ToolCallTracking] script_output SSE send failed for toolCallId={}: {}",
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
