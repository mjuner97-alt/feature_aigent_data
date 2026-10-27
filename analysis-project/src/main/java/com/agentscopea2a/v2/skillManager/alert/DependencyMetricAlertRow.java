package com.agentscopea2a.v2.skillManager.alert;

import java.time.LocalDateTime;

public record DependencyMetricAlertRow(String metricName, String metricCode, boolean ready, LocalDateTime readyAt) {}
