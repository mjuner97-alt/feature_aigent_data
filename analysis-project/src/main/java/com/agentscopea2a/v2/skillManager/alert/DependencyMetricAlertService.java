package com.agentscopea2a.v2.skillManager.alert;

import com.agentscopea2a.entity.AiChatRuntimeConfig;
import com.agentscopea2a.mapper.gauss.AiChatRuntimeConfigMapper;
import com.agentscopea2a.v2.config.AiChatRuntimeConfigKeys;
import com.agentscopea2a.v2.skillManager.entity.SkillMetricReadiness;
import com.agentscopea2a.v2.skillManager.entity.DependencyMetricAlertDelivery;
import com.agentscopea2a.v2.skillManager.mapper.DependencyMetricAlertMapper;
import com.agentscopea2a.v2.skillManager.mapper.SkillDependencyMetricMapper;
import com.agentscopea2a.v2.skillManager.notification.NotificationPayload;
import com.agentscopea2a.v2.skillManager.notification.NotificationSender;
import com.agentscopea2a.v2.skillManager.service.FlowSchedulerGuard;
import com.agentscopea2a.v2.skillManager.service.MockOrgService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class DependencyMetricAlertService {
    private final SkillDependencyMetricMapper metricMapper;
    private final DependencyMetricAlertMapper alertMapper;
    private final AiChatRuntimeConfigMapper configMapper;
    private final FlowSchedulerGuard schedulerGuard;
    private final NotificationSender sender;
    private final MockOrgService orgService;
    private final Clock clock;

    public DependencyMetricAlertService(SkillDependencyMetricMapper metricMapper, DependencyMetricAlertMapper alertMapper,
            AiChatRuntimeConfigMapper configMapper, FlowSchedulerGuard schedulerGuard, NotificationSender sender,
            MockOrgService orgService, Clock clock) {
        this.metricMapper = metricMapper; this.alertMapper = alertMapper; this.configMapper = configMapper;
        this.schedulerGuard = schedulerGuard; this.orgService = orgService; this.clock = clock;
        this.sender = sender;
    }

    @Scheduled(cron = "0 0 11 * * *")
    public void scheduledCheck() { checkAndNotify(LocalDateTime.now(clock)); }

    public void checkAndNotify(LocalDateTime now) { doCheck(now, false); }

    /**
     * 手动触发入口(GET /api/dependency-metric-alert/check 测试用):
     * 按今天 11:00 复用定时逻辑,日期与定时链路一致;force=true 时跳过调度开关与 11 点时间门控。
     */
    public DependencyMetricAlertResult manualCheck(boolean force) {
        return doCheck(LocalDate.now(clock).atTime(11, 0), force);
    }

    private DependencyMetricAlertResult doCheck(LocalDateTime now, boolean force) {
        if (!force && (!schedulerGuard.isAllowed() || now.getHour() != 11 || now.getMinute() != 0))
            return DependencyMetricAlertResult.skipped("调度开关未开启(scheduler.enabled=false)或不在 11:00 时间窗", 0, 0);
        var metrics = Optional.ofNullable(metricMapper.selectAllAlertEnabled()).orElse(List.of());
        if (metrics.isEmpty()) return DependencyMetricAlertResult.skipped("未配置启用的依赖指标", 0, 0);
        LocalDate date = now.toLocalDate();
        var readiness = Optional.ofNullable(alertMapper.selectReadiness(metrics.stream().map(m -> m.getId()).toList(), date)).orElse(List.of())
                .stream().filter(r -> r.getStatus() != null).collect(Collectors.toMap(SkillMetricReadiness::getMetricId, Function.identity(), (a,b) -> a));
        var rows = metrics.stream().map(m -> {
            SkillMetricReadiness r = readiness.get(m.getId());
            return new DependencyMetricAlertRow(m.getName(), m.getCode(), r != null && "READY".equals(r.getStatus().name()), r == null ? null : r.getReadyAt());
        }).toList();
        long readyCount = rows.stream().filter(DependencyMetricAlertRow::ready).count();
        String raw = Optional.ofNullable(configMapper.selectByConfigKey(AiChatRuntimeConfigKeys.DEPENDENCY_METRIC_ALERT_RECIPIENTS)).map(AiChatRuntimeConfig::getConfigValue).orElse("");
        List<String> recipients = orgService.filterExistingUserIds(DependencyMetricAlertRenderer.parseRecipients(raw));
        if (recipients.isEmpty()) return DependencyMetricAlertResult.skipped("未配置收件人或收件人均为无效用户", metrics.size(), (int) readyCount);
        String content = DependencyMetricAlertRenderer.renderHtml(rows, date, now);
        DependencyMetricAlertDelivery delivery = new DependencyMetricAlertDelivery();
        delivery.setAlertDate(date);
        delivery.setRecipientSummary(String.join(",", recipients));
        delivery.setContent(content);
        if (alertMapper.claim(delivery) != 1 || delivery.getId() == null)
            throw new IllegalStateException("创建预警投递记录失败");
        NotificationPayload payload = new NotificationPayload("HTML", content, null, null, null, null,
                "依赖指标就绪预警", null, null, null, "ALERT", now, recipients, "METRIC_ALERT");
        try { sender.send(payload); alertMapper.update(delivery.getId(), "SUCCESS", null); }
        catch (Exception e) { alertMapper.update(delivery.getId(), "FAILED", e.getMessage());
            return new DependencyMetricAlertResult(false, "邮件发送失败", metrics.size(), (int) readyCount, recipients, "FAILED", e.getMessage()); }
        return new DependencyMetricAlertResult(true, "预警邮件已发送", metrics.size(), (int) readyCount, recipients, "SUCCESS", null);
    }
}
