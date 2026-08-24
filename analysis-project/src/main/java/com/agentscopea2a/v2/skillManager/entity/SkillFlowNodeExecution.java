package com.agentscopea2a.v2.skillManager.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 节点执行记录实体:流程执行的快照(渲染后问题、模板快照、最大尝试次数),
 * 含租约(leaseOwner/leaseExpiresAt)与重试调度(nextRunAt)字段。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillFlowNodeExecution {
    private Long id;
    private Long flowExecutionId;
    private String nodeKey;
    private Long skillId;
    private String skillName;
    private String skillRetrievalName;
    private String questionTemplateSnapshot;
    private String renderedQuestion;
    private String dependsOnJson;
    private Boolean required;
    private FlowNodeExecutionStatus status;
    private Integer attemptCount;
    private Integer maxAttempts;
    private LocalDateTime nextRunAt;
    private String leaseOwner;
    private LocalDateTime leaseExpiresAt;
    private String resultJson;
    private String artifactPath;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
