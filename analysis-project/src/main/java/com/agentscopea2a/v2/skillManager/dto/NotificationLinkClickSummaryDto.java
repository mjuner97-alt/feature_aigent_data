package com.agentscopea2a.v2.skillManager.dto;

import com.agentscopea2a.v2.skillManager.entity.SkillFlowNotificationLinkClick;
import java.time.LocalDateTime;

public record NotificationLinkClickSummaryDto(Long executionId, String taskDisplayName, LocalDateTime executionAt,
        String userId, String userName, String source, String eventType, Boolean loggedIn,
        LocalDateTime firstEventAt, LocalDateTime latestEventAt, Long count) {
    public static NotificationLinkClickSummaryDto from(SkillFlowNotificationLinkClick r) {
        Long executionId = r.getFlowExecutionId() != null ? r.getFlowExecutionId() : r.getJobExecutionId();
        return new NotificationLinkClickSummaryDto(executionId, r.getDisplayName(), r.getExecutionCreatedAt(),
                r.getActorUserId() != null ? r.getActorUserId() : r.getRecipientUserId(),
                null, r.getSource(), r.getEventType(), r.getLoggedIn(), r.getFirstEventAt(),
                r.getLatestEventAt(), r.getEventCount());
    }
}
