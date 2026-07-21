package com.agentscopea2a.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiChatResult {
    /** 0 = success / streaming chunk; non-zero = error code. */
    private Integer code;
    /** Populated when {@code code != 0}. */
    private String errorMsg;
    /** Per-conversation answer UUID. Stable across one request's chunks. */
    private String ansUUID;
    /** The current chunk's text. Phase is conveyed via the SSE event name. */
    private String lineResult;
    /** Cumulative answer text (TextBlock only) up to and including {@link #lineResult}. */
    private String resultAll;
    /** Source/channel tag from the request (default "HXY"). */
    private String formType;
    /** Agent panel id (default "7"). */
    private String agentId;
    /** Agent display name (default "QA助手"). */
    private String agentName;
    /**
     * Source agent name for the event - null for main agent events, set to the
     * subagent name (e.g. "analyze_data") for subagent events. Extracted from
     * {@code AgentEvent.getSource()} so the frontend can render subagent activity
     * distinctly from main agent text.
     */
    private String source;
    /** Conversation id passed to the model. */
    private String conversationId;

    // ── Process-event fields (process-event-streaming.md) ───────────────────
    /** Event type name (lowercased): "tool_call_start", "subagent_exposed", ... */
    private String eventType;
    /** Tool call id (tool_call_* / tool_result_* events). */
    private String toolCallId;
    /** Tool name (python_exec / agent_spawn / skill_router ...). */
    private String toolCallName;
    /** ToolResultState.name() (only for tool_result_end). */
    private String toolCallState;
    /**
     * Tool input arguments (only for tool_call_start / tool_result_* events).
     * Populated from {@link com.agentscopea2a.v2.tools.ToolCallCollector#getByToolCallId}
     * via the {@link com.agentscopea2a.v2.hooks.ToolCallTrackingHook} PreActing capture.
     * Rendered by the frontend ActivityFeed inside a collapsible panel.
     */
    private String toolInput;
    /**
     * Tool output result (only for tool_result_end events).
     * Populated from {@link com.agentscopea2a.v2.tools.ToolCallCollector#getByToolCallId}
     * via the {@link com.agentscopea2a.v2.hooks.ToolCallTrackingHook} PostActing capture.
     * Rendered by the frontend ActivityFeed inside a collapsible panel.
     */
    private String toolOutput;
    /** Subagent id (only for subagent_exposed). */
    private String subagentId;
    /** Subagent label (only for subagent_exposed). */
    private String subagentLabel;
    /** AgentStartEvent.name (only for agent_start). */
    private String agentNameRaw;
    /** AgentStartEvent.role (only for agent_start). */
    private String agentRole;
}