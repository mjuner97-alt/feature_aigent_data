package com.agentscopea2a.v2.skillManager.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 评审审计记录 - 仅追加的受限审计数据,记录评审过程中各阶段的操作人(真实认证用户)。
 *
 * <p>统计"实际初评人"必须从该表读取,不能信任 request/dimension 上的其他字段。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillReviewAudit {
    private Long id;                 // 主键
    private Long requestId;          // 所属评审请求 ID
    private Long dimensionId;        // 关联的初评维度 ID(维度级动作才有)
    private String action;           // 审计动作类型(如 SUBMIT/DIMENSION_APPROVED/FINAL_APPROVED 等)
    private String actorUserId;      // 真实操作人(认证用户 ID)
    private String previousStatus;   // 动作前状态
    private String newStatus;        // 动作后状态
    private String comment;          // 操作人填写的意见
    private String snapshotHash;     // 操作时对应的快照哈希
    private LocalDateTime createdAt; // 审计时间
}
