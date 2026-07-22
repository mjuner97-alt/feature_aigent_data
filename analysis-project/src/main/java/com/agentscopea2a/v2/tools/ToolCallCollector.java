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
package com.agentscopea2a.v2.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Request-scoped collector of tool call details (L1 supervisor tools + L2 sub-agent tools).
 *
 * <p>Created at request start in the v2 streaming service,
 * populated by hooks during agent execution, and consumed at request end for:
 * <ul>
 *   <li>Path C: injecting tool context into {@code generate_skill} sub-agent system prompt</li>
 *   <li>Path B: writing to {@code episodic_memory.tool_call_details} for later online distillation</li>
 *   <li>Live SSE: {@link #recordByToolCallId} + {@link #getByToolCallId} lets
 *       {@code V2ChatStreamServiceImpl.buildProcessPayload} attach input/output to
 *       tool_call_start / tool_result_end SSE events so the frontend ActivityFeed
 *       can show collapsible tool input/output.</li>
 * </ul>
 *
 * <p>Thread-safe for concurrent L1 and L2 collection. Uses a {@link ThreadLocal} so that
 * the streaming service can retrieve the current request's collector
 * without passing it through every constructor.
 */
public class ToolCallCollector {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final int INPUT_MAX_LEN = 500;
    private static final int OUTPUT_MAX_LEN = 150;
    /** Max length for the SSE-attached input/output (kept larger than L1 path's 500/150
     * because these go to the frontend collapsible viewer, not to LLM context). */
    private static final int SSE_INPUT_MAX_LEN = 4000;
    private static final int SSE_OUTPUT_MAX_LEN = 4000;

    /** ThreadLocal holder so SupervisorService can access the current request's collector. */
    private static final ThreadLocal<ToolCallCollector> CURRENT = new ThreadLocal<>();

    private final String userQuery;
    private final List<ToolCallDetail> details = new ArrayList<>();

    /**
     * Live tool-call-id → detail map for SSE lookup. Populated by
     * {@link com.agentscopea2a.v2.hooks.ToolCallTrackingHook} on PreActing (with input)
     * and updated on PostActing (with output). Read by
     * {@code V2ChatStreamServiceImpl.buildProcessPayload} when it needs to attach
     * input/output to a tool_call_start or tool_result_end SSE event.
     *
     * <p>Uses a {@link ConcurrentHashMap} because PreActing/PostActing hooks fire on
     * reactor scheduler threads, while the SSE handler reads from the executor thread.
     */
    private final Map<String, ToolCallDetail> byToolCallId = new ConcurrentHashMap<>();

    public ToolCallCollector(String userQuery) {
        this.userQuery = userQuery != null ? userQuery : "";
    }

    // -------- ThreadLocal management --------

    /** Bind this collector to the current thread. Also updates the static reference used
     *  by SupervisorService for generate_skill context injection (via getCurrentContext). */
    public void bind() {
        CURRENT.set(this);
    }

    /** Unbind this collector from the current thread. Call after request processing. */
    public void unbind() {
        CURRENT.remove();
    }

    /** Get the current request's collector, or null if none is bound. */
    public static ToolCallCollector getCurrent() {
        return CURRENT.get();
    }

    /**
     * Get the current request's tool context prompt (for injecting into sub-agent system prompts).
     * Returns empty string when no collector is bound or no details were collected.
     */
    public static String getCurrentContext() {
        ToolCallCollector c = CURRENT.get();
        if (c == null) return "";
        return c.toContextPrompt();
    }

    /**
     * Get the current request's tool context JSON (for persisting to episodic memory).
     * Returns empty string when no collector is bound or no details were collected.
     */
    public static String getCurrentJson() {
        ToolCallCollector c = CURRENT.get();
        if (c == null) return "";
        return c.toJson();
    }

    /** Record an L1 (supervisor-level) tool call. */
    public synchronized void recordL1(String tool, String input, String output) {
        details.add(new ToolCallDetail(tool, "L1", truncate(input, INPUT_MAX_LEN), simplifyOutput(tool, output)));
    }

    /**
     * Update the output on the <b>last</b> L1 entry whose tool name matches.
     * Used by {@link com.agentscopea2a.v2.hooks.ToolCallTrackingHook} to fill in
     * the output after {@link #recordL1} was called during PreActing.
     *
     * <p>If the last L1 entry for {@code tool} already has a non-blank output, this is
     * a no-op (avoids overwriting when PostActing fires the second time).
     */
    public synchronized void updateLastL1Output(String tool, String output) {
        for (int i = details.size() - 1; i >= 0; i--) {
            ToolCallDetail d = details.get(i);
            if ("L1".equals(d.level()) && tool.equals(d.tool()) && d.output().isBlank()) {
                details.set(i, new ToolCallDetail(tool, "L1", d.input(), simplifyOutput(tool, output)));
                return;
            }
        }
    }

    /** Record an L2 (sub-agent-level) tool call. */
    public synchronized void recordL2(String subAgentId, String tool, String input, String output) {
        details.add(new ToolCallDetail(subAgentId + ":" + tool, "L2",
                truncate(input, INPUT_MAX_LEN), truncate(output, OUTPUT_MAX_LEN)));
    }

    /**
     * Record a tool call's input keyed by toolCallId for live SSE lookup.
     * Called by {@link com.agentscopea2a.v2.hooks.ToolCallTrackingHook} on PreActing.
     * The input is truncated to {@link #SSE_INPUT_MAX_LEN} (large enough for collapsible
     * frontend display, but bounded to prevent runaway payloads from stalling SSE).
     */
    public void recordByToolCallId(String toolCallId, String toolName, String input) {
        if (toolCallId == null) return;
        byToolCallId.put(toolCallId, new ToolCallDetail(toolName, "L1",
                truncate(input, SSE_INPUT_MAX_LEN), ""));
    }

    /**
     * Update the output for a tool call previously recorded via
     * {@link #recordByToolCallId}. Called by
     * {@link com.agentscopea2a.v2.hooks.ToolCallTrackingHook} on PostActing.
     */
    public void updateOutputByToolCallId(String toolCallId, String output) {
        if (toolCallId == null) return;
        ToolCallDetail existing = byToolCallId.get(toolCallId);
        if (existing == null) return;
        byToolCallId.put(toolCallId, new ToolCallDetail(
                existing.tool(), existing.level(), existing.input(),
                truncate(output, SSE_OUTPUT_MAX_LEN)));
    }

    /**
     * Get the input/output detail for a tool call by its toolCallId.
     * Returns null if no detail was recorded (e.g. the call predates hook firing,
     * or the toolCallId didn't match any recorded PreActing event).
     */
    public ToolCallDetail getByToolCallId(String toolCallId) {
        if (toolCallId == null) return null;
        return byToolCallId.get(toolCallId);
    }

    public String getUserQuery() {
        return userQuery;
    }

    /** Render tool call chain as an LLM-friendly text block. */
    public synchronized String toContextPrompt() {
        if (details.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n\n## 本次请求的工具调用链路\n\n");
        sb.append("用户问题: ").append(userQuery).append("\n\n");
        int step = 1;
        for (ToolCallDetail d : details) {
            sb.append(step).append(". [").append(d.level()).append("] ").append(d.tool()).append("\n");
            if (d.input() != null && !d.input().isBlank()) {
                sb.append("   - 输入: ").append(d.input()).append("\n");
            }
            if (d.output() != null && !d.output().isBlank()) {
                sb.append("   - 输出: ").append(d.output()).append("\n");
            }
            step++;
        }
        sb.append("\n请基于以上真实工具调用来分析，确保工具名和参数格式完全正确。");
        return sb.toString();
    }

    /** Serialize details to JSON for persistence in episodic_memory. */
    public synchronized String toJson() {
        if (details.isEmpty()) return "";
        try {
            return MAPPER.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            return "";
        }
    }

    /** Parse details from JSON (e.g. when reading back from episodic_memory). */
    public static String fromJson(String json) {
        return json != null ? json : "";
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...";
    }

    /**
     * For agent_spawn, simplify output to status=ok/error/unknown.
     * Otherwise apply standard truncation.
     */
    private static String simplifyOutput(String tool, String output) {
        if (output == null || output.isBlank()) return "";
        if ("agent_spawn".equals(tool)) {
            String lower = output.toLowerCase();
            if (lower.contains("error") || lower.contains("exception") || lower.contains("fail")) {
                return "status=error: " + truncate(output, OUTPUT_MAX_LEN);
            }
            if (lower.contains("ok") || lower.contains("success")) {
                return "status=ok";
            }
            return "status=unknown";
        }
        return truncate(output, OUTPUT_MAX_LEN);
    }

    public record ToolCallDetail(String tool, String level, String input, String output) {}
}
