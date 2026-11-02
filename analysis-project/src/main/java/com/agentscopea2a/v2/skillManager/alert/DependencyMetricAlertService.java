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

/**
 * 依赖指标每日就绪预警服务。
 *
 * <p>每天 11:00 查询开启预警的依赖指标，汇总当日就绪状态，按配置过滤收件人，
 * 通过 {@link NotificationSender} 发送一次 HTML 通知。对应源码路径：
 * {@code src/main/java/com/agentscopea2a/v2/skillManager/alert/DependencyMetricAlertService.java}。</p>
 */
@Service
public class DependencyMetricAlertService {
    /** 读取已开启预警的依赖指标。 */
    private final SkillDependencyMetricMapper metricMapper;
    /** 查询和记录预警发送幂等状态。 */
    private final DependencyMetricAlertMapper alertMapper;
    /** 读取系统运行时配置。 */
    private final AiChatRuntimeConfigMapper configMapper;
    /** 判断当前实例是否允许执行定时任务。 */
    private final FlowSchedulerGuard schedulerGuard;
    /** 执行实际通知发送。 */
    private final NotificationSender sender;
    /** 过滤组织内真实存在的用户。 */
    private final MockOrgService orgService;
    /** 支持测试时注入固定时间。 */
    private final Clock clock;

    /** 构造依赖指标预警服务。 */
    public DependencyMetricAlertService(SkillDependencyMetricMapper metricMapper, DependencyMetricAlertMapper alertMapper,
            AiChatRuntimeConfigMapper configMapper, FlowSchedulerGuard schedulerGuard, NotificationSender sender,
            MockOrgService orgService, Clock clock) {
        this.metricMapper = metricMapper;
        this.alertMapper = alertMapper;
        this.configMapper = configMapper;
        this.schedulerGuard = schedulerGuard;
        this.sender = sender;
        this.orgService = orgService;
        this.clock = clock;
    }

    /** Spring 定时入口；以注入时钟获取当前时间，便于测试。 */
    @Scheduled(cron = "0 0 11 * * *")
    public void scheduledCheck() {
        checkAndNotify(LocalDateTime.now(clock));
    }

    /**
     * 执行一次依赖指标检查并发送预警。
     * @param now 当前时间，通常来自 {@link #clock}
     */
    public void checkAndNotify(LocalDateTime now) {
        // 防止非主调度实例或非 11:00 调用触发重复发送。
        if (!schedulerGuard.isAllowed() || now.getHour() != 11 || now.getMinute() != 0) return;
        var metrics = Optional.ofNullable(metricMapper.selectAllAlertEnabled()).orElse(List.of());
        if (metrics.isEmpty()) return;
        LocalDate date = now.toLocalDate();

        // 将数据库返回的状态按指标 ID 建索引，后续可为每个指标快速补齐未就绪状态。
        var readiness = Optional.ofNullable(alertMapper.selectReadiness(metrics.stream().map(m -> m.getId()).toList(), date))
                .orElse(List.of()).stream().filter(r -> r.getStatus() != null)
                .collect(Collectors.toMap(SkillMetricReadiness::getMetricId, Function.identity(), (a, b) -> a));
        var rows = metrics.stream().map(m -> {
            SkillMetricReadiness r = readiness.get(m.getId());
            return new DependencyMetricAlertRow(m.getName(), m.getCode(), r != null && "READY".equals(r.getStatus().name()),
                    r == null ? null : r.getReadyAt());
        }).toList();

        // 配置值可能包含已删除用户，先通过组织服务过滤再发送。
        String raw = Optional.ofNullable(configMapper.selectByConfigKey(AiChatRuntimeConfigKeys.DEPENDENCY_METRIC_ALERT_RECIPIENTS))
                .map(AiChatRuntimeConfig::getConfigValue).orElse("");
        List<String> recipients = orgService.filterExistingUserIds(DependencyMetricAlertRenderer.parseRecipients(raw));
        if (recipients.isEmpty()) return;
        String content = DependencyMetricAlertRenderer.renderHtml(rows, date, now);

        // claim 保证同一日期、同一收件人集合只发送一次。
        if (alertMapper.claim(date, String.join(",", recipients), content) == 0) return;
        NotificationPayload payload = new NotificationPayload("HTML", content, null, null, null, null,
                "依赖指标就绪预警（" + date.format(java.time.format.DateTimeFormatter.ofPattern("yyyy年MM月dd日")) + "）",
                null, null, null, "ALERT", now, recipients, "METRIC_ALERT");
        try {
            sender.send(payload);
            alertMapper.update(date, "SUCCESS", null);
        } catch (Exception e) {
            // 记录失败原因，便于运营排查和后续重试。
            alertMapper.update(date, "FAILED", e.getMessage());
        }
    }
}
