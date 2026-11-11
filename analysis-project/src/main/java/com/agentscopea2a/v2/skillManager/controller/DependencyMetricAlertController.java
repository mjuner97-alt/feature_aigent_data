
package com.agentscopea2a.v2.skillManager.controller;

import com.agentscopea2a.v2.skillManager.alert.DependencyMetricAlertResult;
import com.agentscopea2a.v2.skillManager.alert.DependencyMetricAlertService;
import org.springframework.web.bind.annotation.*;

/**
 * 依赖指标就绪预警手动触发入口，路径前缀 /api/dependency-metric-alert。
 *
 * <p>GET /check：手动执行每日 10 点的「指标就绪检查 + 邮件发送」逻辑，用于测试。
 * 按实际检查时间走定时同款链路（指标快照、收件人解析、创建投递记录、发送）；
 * 传 force=true 时跳过调度开关。执行时刻仅由定时器控制，内部不校验小时、分钟。
 * 所有调用仍限生产环境(prod)及周一至周五，force=true 也不能绕过。
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
     * @param force true 时跳过调度开关，仍限生产环境及周一至周五
     * @return 检查结果：是否触发、指标/就绪数量、收件人、发送状态或跳过原因
     * /api/dependency-metric-alert/check?force=true
     */
    @GetMapping("/check")
    public DependencyMetricAlertResult check(
            @RequestParam(name = "force", required = false, defaultValue = "false") boolean force) {
        return alertService.manualCheck(force);
    }
}
