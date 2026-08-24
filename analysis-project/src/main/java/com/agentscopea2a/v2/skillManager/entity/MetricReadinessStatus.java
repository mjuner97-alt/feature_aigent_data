package com.agentscopea2a.v2.skillManager.entity;

/**
 * 依赖指标就绪状态:READY 就绪 / EXPIRED 已过期(跨天作废)。
 */
public enum MetricReadinessStatus {
    READY,
    EXPIRED
}
