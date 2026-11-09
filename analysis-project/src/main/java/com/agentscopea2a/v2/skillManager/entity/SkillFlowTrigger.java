package com.agentscopea2a.v2.skillManager.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Skill Flow 触发词表实体：keyword 归一化(normalizedKeyword)后流程内唯一，
 * 跨流程命中时由聊天入口提供候选选择。
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
