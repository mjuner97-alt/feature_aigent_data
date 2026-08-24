package com.agentscopea2a.v2.skillManager.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 节点门槛指标关联表实体:节点关联的依赖指标须全部 READY,节点才会进入执行队列。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillFlowNodeMetric {
    private Long id;
    private Long flowNodeId;
    private Long metricId;
    private LocalDateTime createdAt;
}
