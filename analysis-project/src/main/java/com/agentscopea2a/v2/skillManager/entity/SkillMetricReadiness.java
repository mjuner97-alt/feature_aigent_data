package com.agentscopea2a.v2.skillManager.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 依赖指标就绪登记表实体:外部指标到达时 upsert 一条当日 READY 记录,次日零点过期。
 * Skill Flow 的指标门控与 Skill Job 的指标触发都以此判断。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillMetricReadiness {
    private Long id;
    private Long metricId;
    private String metricCode;
    private LocalDate dataDate;
    private MetricReadinessStatus status;
    private LocalDateTime readyAt;
    private LocalDateTime expiresAt;
    private String metadataJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
