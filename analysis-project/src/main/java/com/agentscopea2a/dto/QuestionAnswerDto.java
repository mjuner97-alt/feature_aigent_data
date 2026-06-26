package com.agentscopea2a.dto;

import lombok.Data;

@Data
public class QuestionAnswerDto {
    private String question;
    private String result;
    private String userId;
    private String userName;
    private String ansFlag;
    private String ansUUID;
    private String hitFlag;
    private String dislikeFlag;
    private String errorMsg;
    private String insDate;
    private String fromType;
    private String workflowRunId;
    /**
     * 会话ID
     */
    private String conversationId;
    private String taskId;
    private String agentName;
    private String agentId;
    private String isMainEntry;

    private String think;
    private String answer;
}