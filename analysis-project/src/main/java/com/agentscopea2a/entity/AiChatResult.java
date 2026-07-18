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
}
