package com.agentscopea2a.v2.skillManager.alert;

import com.agentscopea2a.entity.AiChatRuntimeConfig;
import com.agentscopea2a.mapper.gauss.AiChatRuntimeConfigMapper;
import com.agentscopea2a.v2.config.AiChatRuntimeConfigKeys;
import com.agentscopea2a.v2.skillManager.entity.SkillMetricReadiness;
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
        this.schedulerGuard = schedulerGuard; this.sender = sender; this.orgService = orgService; this.clock = clock;
    }

    @Scheduled(cron = "0 0 11 * * *")
    public void scheduledCheck() { checkAndNotify(LocalDateTime.now(clock)); }

    public void checkAndNotify(LocalDateTime now) {
        if (!schedulerGuard.isAllowed() || now.getHour() != 11 || now.getMinute() != 0) return;
        var metrics = Optional.ofNullable(metricMapper.selectAllAlertEnabled()).orElse(List.of());
        if (metrics.isEmpty()) return;
        LocalDate date = now.toLocalDate();
        var readiness = Optional.ofNullable(alertMapper.selectReadiness(metrics.stream().map(m -> m.getId()).toList(), date)).orElse(List.of())
                .stream().filter(r -> r.getStatus() != null).collect(Collectors.toMap(SkillMetricReadiness::getMetricId, Function.identity(), (a,b) -> a));
        var rows = metrics.stream().map(m -> {
            SkillMetricReadiness r = readiness.get(m.getId());
            return new DependencyMetricAlertRow(m.getName(), m.getCode(), r != null && "READY".equals(r.getStatus().name()), r == null ? null : r.getReadyAt());
        }).toList();
        String raw = Optional.ofNullable(configMapper.selectByConfigKey(AiChatRuntimeConfigKeys.DEPENDENCY_METRIC_ALERT_RECIPIENTS)).map(AiChatRuntimeConfig::getConfigValue).orElse("");
        List<String> recipients = orgService.filterExistingUserIds(DependencyMetricAlertRenderer.parseRecipients(raw));
        if (recipients.isEmpty()) return;
        String content = DependencyMetricAlertRenderer.renderHtml(rows, date, now);
        if (alertMapper.claim(date, String.join(",", recipients), content) == 0) return;
        NotificationPayload payload = new NotificationPayload("HTML", content, null, null, null, null,
                "依赖指标就绪预警", null, null, null, "ALERT", now, recipients, "METRIC_ALERT");
        try { sender.send(payload); alertMapper.update(date, "SUCCESS", null); }
        catch (Exception e) { alertMapper.update(date, "FAILED", e.getMessage()); }
    }
}
