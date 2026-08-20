package com.agentscopea2a.v2.skillManager.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 流程执行记录实体:触发即插入,快照流程当时的模板/并发度/通知开关(改编排不影响在跑的执行)。
 * 指标未就绪时停在 WAITING_METRICS,activeGuardKey 保证(用户,会话,流程,日期)幂等。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillFlowExecution {
    private Long id;
    private Long flowId;
    private String flowCode;
    private String flowName;
    private String summaryQuestionTemplateSnapshot;
    private Integer maxParallelismSnapshot;
    private Boolean notifyEnabledSnapshot;
    private FlowTriggerType triggerType;
    private String triggerUserId;
    private String conversationId;
    private String originalQuestion;
    private LocalDate dataDate;
    private FlowExecutionStatus status;
    private String activeGuardKey;
    private Integer requiredMetricCount;
    private Integer readyMetricCount;
    private String missingMetricsJson;
    private String summaryJson;
    private String reportPath;
    private LocalDateTime cancelRequestedAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
