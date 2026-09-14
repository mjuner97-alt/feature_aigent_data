package com.agentscopea2a.v2.skillManager.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 评审维度 - 一个发布目标对应的单条初审(初评)决策记录。
 *
 * <p>一次评审请求可包含多个发布目标,每个目标各自独立评审、独立状态流转。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillReviewDimension {
    private Long id;                  // 主键
    private Long requestId;           // 所属评审请求 ID
    private String targetType;        // 发布目标类型(如 COMPANY/DEPARTMENT/GROUP/USER)
    private String targetId;          // 发布目标 ID
    private String targetName;        // 发布目标名称
    private String status;            // 初审状态(PENDING/APPROVED/REJECTED/CANCELLED)
    private String reviewComment;     // 初审意见
    private LocalDateTime reviewedAt; // 初审处理时间
    private LocalDateTime createdAt;  // 创建时间
    private LocalDateTime updatedAt;  // 更新时间
}
