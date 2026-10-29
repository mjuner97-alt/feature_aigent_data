package com.agentscopea2a.mapper.ck;

import com.agentscopea2a.v2.skillManager.entity.SkillFlowNotificationLinkClick;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface NotificationLinkClickMapper {
    void insertEvent(@Param("notificationId") Long notificationId, @Param("executionId") Long executionId,
                     @Param("jobExecutionId") Long jobExecutionId, @Param("actorUserId") String actorUserId,
                     @Param("tokenHash") String tokenHash, @Param("source") String source, @Param("eventType") String eventType,
                     @Param("loggedIn") Boolean loggedIn, @Param("eventAt") LocalDateTime eventAt,
                     @Param("clientIp") String clientIp, @Param("userAgent") String userAgent,
                     @Param("environment") String environment);
    void completePending(@Param("accessToken") String accessToken, @Param("actorUserId") String actorUserId,
                         @Param("eventAt") LocalDateTime eventAt, @Param("environment") String environment);
    List<SkillFlowNotificationLinkClick> findSummary(@Param("filters") Map<String, Object> filters);
}
