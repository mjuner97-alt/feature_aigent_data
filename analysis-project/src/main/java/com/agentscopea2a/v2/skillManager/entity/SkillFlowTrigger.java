package com.agentscopea2a.v2.skillManager.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Skill Flow 触发词表实体:keyword 归一化(normalizedKeyword)后全局唯一,
 * 聊天消息命中即路由到对应流程。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillFlowTrigger {
    private Long id;
    private Long flowId;
    private String keyword;
    private String normalizedKeyword;
    private Integer priority;
    private Boolean enabled;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
