package com.agentscopea2a.v2.skillManager.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Skill Flow 编排节点表实体:一个节点绑定一个 skill。
 * dependsOnJson 为前置节点 nodeKey 列表(构成 DAG);questionTemplate 支持 {变量} 占位。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillFlowNode {
    private Long id;
    private Long flowId;
    private String nodeKey;
    private Long skillId;
    private String questionTemplate;
    private String dependsOnJson;
    private Boolean required;
    private Integer maxAttempts;
    private Integer sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
