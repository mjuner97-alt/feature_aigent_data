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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class DependencyMetricAlertService {
    private static final Logger log = LoggerFactory.getLogger(DependencyMetricAlertService.class);
    private final SkillDependencyMetricMapper metricMapper;
    private final DependencyMetricAlertMapper alertMapper;
    private final AiChatRuntimeConfigMapper configMapper;
    private final FlowSchedulerGuard schedulerGuard;
    private final NotificationSender sender;
    private final MockOrgService orgService;
    private final Clock clock;
    private final Environment environment;

    public DependencyMetricAlertService(SkillDependencyMetricMapper metricMapper, DependencyMetricAlertMapper alertMapper,
            AiChatRuntimeConfigMapper configMapper, FlowSchedulerGuard schedulerGuard, NotificationSender sender,
            MockOrgService orgService, Clock clock, Environment environment) {
        this.metricMapper = metricMapper; this.alertMapper = alertMapper; this.configMapper = configMapper;
        this.schedulerGuard = schedulerGuard; this.orgService = orgService; this.clock = clock;
        this.sender = sender;
        this.environment = environment;
    }

    @Scheduled(cron = "0 0 10 * * MON-FRI", zone = "Asia/Shanghai")
    public void scheduledCheck() { checkAndNotify(LocalDateTime.now(clock)); }

    public void checkAndNotify(LocalDateTime now) { doCheck(now, false); }

    /**
     * 手动触发入口(GET /api/dependency-metric-alert/check 测试用):
     * 按今天 11:00 复用定时逻辑;force=true 仅跳过调度开关与 11 点时间门控，仍限生产环境及周一至周五。
     */
    public DependencyMetricAlertResult manualCheck(boolean force) {
        return doCheck(LocalDate.now(clock).atTime(11, 0), force);
    }

    private DependencyMetricAlertResult doCheck(LocalDateTime now, boolean force) {
        if (!environment.acceptsProfiles(Profiles.of("prod")))
            return DependencyMetricAlertResult.skipped("依赖指标预警仅在生产环境(prod)启用", 0, 0);
        if (now.getDayOfWeek() == DayOfWeek.SATURDAY || now.getDayOfWeek() == DayOfWeek.SUNDAY)
            return DependencyMetricAlertResult.skipped("依赖指标预警仅在周一至周五发送", 0, 0);
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
        List<String> configuredRecipients = configuredRecipients(AiChatRuntimeConfigKeys.DEPENDENCY_METRIC_ALERT_RECIPIENTS);
        List<String> configuredCc = configuredRecipients(AiChatRuntimeConfigKeys.DEPENDENCY_METRIC_ALERT_CC_RECIPIENTS);
        List<String> recipients = orgService.filterExistingUserIds(configuredRecipients);
        List<String> validCc = orgService.filterExistingUserIds(configuredCc);
        List<String> invalidRecipients = configuredRecipients.stream().filter(id -> !recipients.contains(id)).toList();
        List<String> invalidCc = configuredCc.stream().filter(id -> !validCc.contains(id)).toList();
        // TO takes precedence when the same person appears in both lists.
        List<String> ccRecipients = validCc.stream().filter(id -> !recipients.contains(id)).toList();
        log.info("[DependencyMetricAlert] resolved recipients={}, ccRecipients={}, invalidRecipients={}, invalidCcRecipients={}",
                recipients, ccRecipients, invalidRecipients, invalidCc);
        if (recipients.isEmpty()) return new DependencyMetricAlertResult(false,
                "未配置收件人或收件人均为无效用户", metrics.size(), (int) readyCount, recipients,
                "SKIPPED", null, ccRecipients, invalidRecipients, invalidCc);
        String content = DependencyMetricAlertRenderer.renderHtml(rows, date, now);
        DependencyMetricAlertDelivery delivery = new DependencyMetricAlertDelivery();
        delivery.setAlertDate(date);
        delivery.setRecipientSummary(String.join(",", recipients));
        delivery.setCcRecipientSummary(String.join(",", ccRecipients));
        delivery.setContent(content);
        if (alertMapper.claim(delivery) != 1 || delivery.getId() == null)
            throw new IllegalStateException("创建预警投递记录失败");
        NotificationPayload payload = new NotificationPayload("HTML", content, null, null, null, null,
                "数据依赖指标未就绪预警(" + date.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日")) + ")",
                null, null, null, "ALERT", now, recipients, "METRIC_ALERT", ccRecipients);
        try { sender.send(payload); alertMapper.update(delivery.getId(), "SUCCESS", null); }
        catch (Exception e) { alertMapper.update(delivery.getId(), "FAILED", e.getMessage());
            return new DependencyMetricAlertResult(false, "邮件发送失败", metrics.size(), (int) readyCount,
                    recipients, "FAILED", e.getMessage(), ccRecipients, invalidRecipients, invalidCc); }
        return new DependencyMetricAlertResult(true, "预警邮件已发送", metrics.size(), (int) readyCount,
                recipients, "SUCCESS", null, ccRecipients, invalidRecipients, invalidCc);
    }

    private List<String> configuredRecipients(String key) {
        String raw = Optional.ofNullable(configMapper.selectByConfigKey(key))
                .map(AiChatRuntimeConfig::getConfigValue).orElse("");
        return DependencyMetricAlertRenderer.parseRecipients(raw);
    }
}
