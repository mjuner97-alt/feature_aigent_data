package com.agentscopea2a.v2.skillManager.alert;

import java.time.LocalDateTime;

/**
 * 单项依赖指标预警展示数据。
 * 对应源码路径：{@code src/main/java/com/agentscopea2a/v2/skillManager/alert/DependencyMetricAlertRow.java}。
 * @param metricName 指标名称
 * @param metricCode 指标编码
 * @param ready 是否已就绪
 * @param readyAt 指标就绪时间，未就绪时可以为空
 */
public record DependencyMetricAlertRow(String metricName, String metricCode, boolean ready, LocalDateTime readyAt) {}
