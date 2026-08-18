package com.agentscopea2a.v2.skillManager.dto;

import com.agentscopea2a.v2.skillManager.entity.SkillJobNotification;

import java.time.LocalDateTime;

/** Notification delivery record exposed for an execution. */
public record SkillJobNotificationDto(
        Long id,
        Long jobId,
        Long executionId,
        String requestType,
        String status,
        String triggerType,
        String senderName,
        String recipientSummary,
        String contentType,
        String content,
        String fileName,
        String fileUrl,
        String errorMsg,
        LocalDateTime requestedAt,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        LocalDateTime createdAt
) {
    public static SkillJobNotificationDto of(SkillJobNotification notification) {
        return new SkillJobNotificationDto(
                notification.getId(), notification.getJobId(), notification.getExecutionId(),
                notification.getRequestType(), notification.getStatus(), notification.getTriggerType(),
                notification.getSenderName(),
                notification.getRecipientSummary(), notification.getContentType(), notification.getContent(),
                notification.getFileName(), notification.getFileUrl(),
                notification.getErrorMsg(), notification.getRequestedAt(), notification.getStartedAt(),
                notification.getCompletedAt(), notification.getCreatedAt());
    }
}
