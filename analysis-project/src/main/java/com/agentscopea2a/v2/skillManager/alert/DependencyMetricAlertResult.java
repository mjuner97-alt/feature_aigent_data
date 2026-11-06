package com.agentscopea2a.v2.skillManager.alert;

import java.util.List;

/**
 * 依赖指标就绪预警检查结果：手动触发(GET 测试接口)时返回给调用方,
 * 说明本次检查走到了哪一步——未触发时给出原因,触发时给出指标/收件人数量与发送状态。
 */
public record DependencyMetricAlertResult(
        boolean triggered,
        String reason,
        int metricCount,
        int readyCount,
        List<String> recipients,
        String status,
        String errorMessage,
        List<String> ccRecipients,
        List<String> invalidRecipients,
        List<String> invalidCcRecipients) {

    public DependencyMetricAlertResult(boolean triggered, String reason, int metricCount, int readyCount,
            List<String> recipients, String status, String errorMessage) {
        this(triggered, reason, metricCount, readyCount, recipients, status, errorMessage,
                List.of(), List.of(), List.of());
    }

    static DependencyMetricAlertResult skipped(String reason, int metricCount, int readyCount) {
        return new DependencyMetricAlertResult(false, reason, metricCount, readyCount, List.of(), "SKIPPED", null);
    }
}
