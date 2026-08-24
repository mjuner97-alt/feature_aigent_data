package com.agentscopea2a.v2.skillManager.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 流程完成通知记录实体:deliveryKey 唯一索引保证首告不重发,重发记录单独一行。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillFlowNotification {
    private Long id;
    private Long flowExecutionId;
    private String deliveryKey;
    private FlowNotificationStatus status;
    private String recipient;
    private String channel;
    private String requestJson;
    private String responseJson;
    private String errorMessage;
    private LocalDateTime sentAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
