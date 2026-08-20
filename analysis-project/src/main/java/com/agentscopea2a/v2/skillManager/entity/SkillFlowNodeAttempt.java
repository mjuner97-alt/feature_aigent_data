package com.agentscopea2a.v2.skillManager.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 节点单次尝试的审计记录实体:每次执行(或重试)一条,含耗时与错误信息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillFlowNodeAttempt {
    private Long id;
    private Long nodeExecutionId;
    private Integer attemptNo;
    private FlowNodeAttemptStatus status;
    private Boolean retryable;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private Long durationMs;
    private LocalDateTime createdAt;
}
