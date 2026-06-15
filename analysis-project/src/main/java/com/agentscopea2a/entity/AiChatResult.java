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
    /** The current chunk's text. */
    private String lineResult;
    /** Cumulative response text up to and including {@code lineResult}. */
    private String resultAll;
    /** Source/channel tag from the request (default "HXY"). */
    private String formType;
    /** Agent panel id (default "7"). */
    private String agentId;
    /** Agent display name (default "QA助手"). */
    private String agentName;
    /** Conversation id passed to the model — defaults to a fresh UUID. */
    private String conversationId;
}
