package com.agentscopea2a.v2.skillManager.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** A single delivery attempt for a completed skill-job execution. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillJobNotification {
    private Long id;
    private Long jobId;
    private Long executionId;
    /** INITIAL for normal completion delivery; RESEND for operator-triggered delivery. */
    private String requestType;
    /** PENDING, SENDING, SUCCESS, or FAILED. */
    private String status;
    private String triggerType;
    private String senderName;
    private String recipientSummary;
    private String contentType;
    private String content;
    private String fileName;
    private String fileUrl;
    private String errorMsg;
    private LocalDateTime requestedAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    /** 执行中心汇总查询字段，不参与通知记录写入。 */
    private Integer attemptCount;
}
