package com.agentscopea2a.v2.skillManager.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** A page or notification report-link access event and its query projection. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillFlowNotificationLinkClick {
    private Long id;
    private Long notificationId;
    private Long flowExecutionId;
    private Long jobExecutionId;
    private String executionType;
    private String recipientUserId;
    private String actorUserId;
    private String tokenHash;
    private String source;
    private String eventType;
    private Boolean loggedIn;
    private LocalDateTime eventAt;
    private String clientIp;
    private String userAgent;
    private LocalDateTime createdAt;

    // Token target and summary projection fields.
    private String notificationRecipient;
    private String notificationChannel;
    private String reportPath;
    private String destination;
    private String flowName;
    private LocalDateTime executionCreatedAt;
    private LocalDateTime executionStartedAt;
    private LocalDateTime executionCompletedAt;
    private String displayName;
    private LocalDateTime firstEventAt;
    private LocalDateTime latestEventAt;
    private Long eventCount;
}
