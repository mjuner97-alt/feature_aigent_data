package com.agentscopea2a.v2.skillManager.dto;

import java.util.List;

/**
 * 流程定义级指标就绪预检返回体(手动执行前查,区别于执行记录级的就绪查询)。
 * status 为 SkillMetricReadiness 状态名,无记录时 NOT_READY;affectedNodeKeys 为依赖该指标的节点。
 */
public record FlowMetricReadinessDto(Long metricId, String metricCode, String metricName,
                                     String status, List<String> affectedNodeKeys) {}
