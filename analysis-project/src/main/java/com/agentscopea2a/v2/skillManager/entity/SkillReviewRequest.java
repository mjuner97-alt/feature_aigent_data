package com.agentscopea2a.v2.skillManager.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 评审请求 - 不可变的候选配置快照及其两阶段(初审 + 终审)评审状态。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillReviewRequest {
    private Long id;                       // 主键
    private Long skillId;                  // 被评审的技能 ID
    private String snapshotJson;           // 冻结的候选配置快照(规范 JSON)
    private String snapshotHash;           // 快照内容 SHA-256(校验与审计用)
    private String status;                 // 评审状态(DRAFT/DIMENSION_REVIEW/FINAL_REVIEW/APPROVED/REJECTED/WITHDRAWN)
    private String submitterUserId;        // 提交人(技能 owner)
    private LocalDateTime submittedAt;     // 提交时间
    private String finalComment;           // 终审意见
    private LocalDateTime finalReviewedAt; // 终审处理时间
    private LocalDateTime createdAt;       // 创建时间
    private LocalDateTime updatedAt;       // 更新时间
}
