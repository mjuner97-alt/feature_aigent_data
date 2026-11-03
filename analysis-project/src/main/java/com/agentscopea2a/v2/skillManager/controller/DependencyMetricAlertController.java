
package com.agentscopea2a.v2.skillManager.controller;

import com.agentscopea2a.v2.skillManager.alert.DependencyMetricAlertResult;
import com.agentscopea2a.v2.skillManager.alert.DependencyMetricAlertService;
import org.springframework.web.bind.annotation.*;

/**
 * 依赖指标就绪预警手动触发入口，路径前缀 /api/dependency-metric-alert。
 *
 * <p>GET /check：手动执行每日 11 点的「指标就绪检查 + 邮件发送」逻辑，用于测试。
 * 按今天 11:00 的时间点走定时同款链路（指标快照、收件人解析、claim 防重、发送）；
 * 传 force=true 时跳过调度开关（scheduler.enabled）与 11 点时间窗校验，任意时间都能测。
 */
@RestController
@RequestMapping("/api/dependency-metric-alert")
@CrossOrigin(origins = "*", maxAge = 3600)
public class DependencyMetricAlertController {

    private final DependencyMetricAlertService alertService;

    public DependencyMetricAlertController(DependencyMetricAlertService alertService) {
        this.alertService = alertService;
    }

    /**
     * 手动触发一次依赖指标就绪预警检查并发送邮件。
     *
     * @param force true 时跳过调度开关与 11 点时间窗，直接执行（测试用）
     * @return 检查结果：是否触发、指标/就绪数量、收件人、发送状态或跳过原因
     * /api/dependency-metric-alert/check?force=true
     */
    @GetMapping("/check")
    public DependencyMetricAlertResult check(
            @RequestParam(name = "force", required = false, defaultValue = "false") boolean force) {
        return alertService.manualCheck(force);
    }
}
