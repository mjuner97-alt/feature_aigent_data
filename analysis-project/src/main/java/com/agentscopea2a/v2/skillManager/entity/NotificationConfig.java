package com.agentscopea2a.v2.skillManager.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 通用通知配置表(notification_config)实体。
 * 一个业务对象(SKILL_JOB/SKILL_FLOW)对应一条配置记录。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationConfig {

    /** 通知目标类型,例如 SKILL_JOB、SKILL_FLOW。 */
    public static final String TARGET_TYPE_SKILL_JOB = "SKILL_JOB";
    /** 通知目标类型,例如 SKILL_JOB、SKILL_FLOW。 */
    public static final String TARGET_TYPE_SKILL_FLOW = "SKILL_FLOW";

    /** 通知配置主键。 */
    private Long id;

    /** 通知目标类型,例如 SKILL_JOB、SKILL_FLOW。 */
    private String targetType;

    /** 通知目标业务主键,与 target_type 共同确定业务对象,由 Service 层按 target_type 校验,无数据库外键。 */
    private Long targetId;

    /** 该通知配置记录是否启用(非流程完成通知开关,通知开关仍由任务/流程自身配置负责)。 */
    private Boolean enabled;

    /** 配置创建人用户 ID。 */
    private String createdBy;

    /** 配置创建时间。 */
    private LocalDateTime createdAt;

    /** 配置最后更新时间。 */
    private LocalDateTime updatedAt;
}
