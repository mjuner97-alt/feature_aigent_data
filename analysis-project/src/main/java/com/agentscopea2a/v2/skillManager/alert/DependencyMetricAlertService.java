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

/**
 * 依赖指标就绪情况预警服务，支持定时检查和手动触发。
 * <p>
 * 在生产环境的工作日汇总已启用预警的指标状态，根据运行时配置解析收件人和抄送人，
 * 发送包含指标就绪情况的 HTML 邮件，并记录投递结果。
 */
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

    /**
     * 初始化指标查询、运行时配置、调度控制及通知投递所需的依赖。
     *
     * @param metricMapper 依赖指标配置查询接口
     * @param alertMapper 指标就绪状态查询及预警投递记录接口
     * @param configMapper 运行时配置查询接口
     * @param schedulerGuard 调度开关检查服务
     * @param sender 通知发送器
     * @param orgService 组织用户校验服务
     * @param clock 获取检查时间所用的时钟
     * @param environment 用于判断当前是否为生产环境
     */
    public DependencyMetricAlertService(SkillDependencyMetricMapper metricMapper, DependencyMetricAlertMapper alertMapper,
            AiChatRuntimeConfigMapper configMapper, FlowSchedulerGuard schedulerGuard, NotificationSender sender,
            MockOrgService orgService, Clock clock, Environment environment) {
        this.metricMapper = metricMapper; this.alertMapper = alertMapper; this.configMapper = configMapper;
        this.schedulerGuard = schedulerGuard; this.orgService = orgService; this.clock = clock;
        this.sender = sender;
        this.environment = environment;
    }

    /**
     * 每周一至周五北京时间 10:00 触发检查，实际检查时间由注入的时钟提供。
     */
    @Scheduled(cron = "0 0 10 * * MON-FRI", zone = "Asia/Shanghai")
    public void scheduledCheck() { checkAndNotify(LocalDateTime.now(clock)); }

    /**
     * 按指定时间执行受调度开关控制的检查，并输出检查结果日志。
     *
     * @param now 检查时间，用于判断工作日、查询当日状态及生成邮件内容
     */
    public void checkAndNotify(LocalDateTime now) {
        DependencyMetricAlertResult result = doCheck(now, false);
        log.info("[DependencyMetricAlert] scheduled check: status={}, reason={}, metricCount={}, readyCount={}, errorMessage={}",
                result.status(), result.reason(), result.metricCount(), result.readyCount(), result.errorMessage());
    }

    /**
     * 手动触发入口(GET /api/dependency-metric-alert/check 测试用):
     * 按实际检查时间复用定时逻辑；force=true 仅跳过调度开关，仍限生产环境及周一至周五。
     *
     * @param force 是否跳过调度开关检查
     * @return 检查及投递结果，包含跳过原因、指标数量和收件人校验信息
     */
    public DependencyMetricAlertResult manualCheck(boolean force) {
        return doCheck(LocalDateTime.now(clock), force);
    }

    /**
     * 执行环境与调度校验、指标状态汇总、收件人校验和邮件投递。
     * <p>
     * 已就绪数量仅用于汇总结果，不作为是否发送邮件的条件。
     *
     * @param now 本次检查时间
     * @param force 是否仅绕过调度开关限制
     * @return 本次检查的跳过、发送成功或发送失败结果
     * @throws IllegalStateException 无法创建预警投递记录时抛出
     */
    private DependencyMetricAlertResult doCheck(LocalDateTime now, boolean force) {
        // 强制触发也必须满足生产环境和工作日限制。
        if (!environment.acceptsProfiles(Profiles.of("prod")))
            return DependencyMetricAlertResult.skipped("依赖指标预警仅在生产环境(prod)启用", 0, 0);
        if (now.getDayOfWeek() == DayOfWeek.SATURDAY || now.getDayOfWeek() == DayOfWeek.SUNDAY)
            return DependencyMetricAlertResult.skipped("依赖指标预警仅在周一至周五发送", 0, 0);
        // 非强制触发时，还需要通过统一的调度开关校验。
        if (!force && !schedulerGuard.isAllowed())
            return DependencyMetricAlertResult.skipped("调度开关未开启，请检查 scheduler.enabled 和 long_task_scheduler_enabled", 0, 0);
        // 仅汇总启用了预警的指标；没有配置时直接跳过。
        var metrics = Optional.ofNullable(metricMapper.selectAllAlertEnabled()).orElse(List.of());
        if (metrics.isEmpty()) return DependencyMetricAlertResult.skipped("未配置启用的依赖指标", 0, 0);
        LocalDate date = now.toLocalDate();
        // 查询当日状态并按指标 ID 建立索引；忽略空状态，同一指标重复时保留首条。
        var readiness = Optional.ofNullable(alertMapper.selectReadiness(metrics.stream().map(m -> m.getId()).toList(), date)).orElse(List.of())
                .stream().filter(r -> r.getStatus() != null).collect(Collectors.toMap(SkillMetricReadiness::getMetricId, Function.identity(), (a,b) -> a));
        // 未查询到状态的指标也展示在邮件中，并按未就绪处理。
        var rows = metrics.stream().map(m -> {
            SkillMetricReadiness r = readiness.get(m.getId());
            return new DependencyMetricAlertRow(m.getName(), m.getCode(), r != null && "READY".equals(r.getStatus().name()), r == null ? null : r.getReadyAt());
        }).toList();
        long readyCount = rows.stream().filter(DependencyMetricAlertRow::ready).count();
        // 分别读取主送和抄送配置，校验用户是否存在，并保留无效用户以便排查。
        List<String> configuredRecipients = configuredRecipients(AiChatRuntimeConfigKeys.DEPENDENCY_METRIC_ALERT_RECIPIENTS);
        List<String> configuredCc = configuredRecipients(AiChatRuntimeConfigKeys.DEPENDENCY_METRIC_ALERT_CC_RECIPIENTS);
        List<String> recipients = orgService.filterExistingUserIds(configuredRecipients);
        List<String> validCc = orgService.filterExistingUserIds(configuredCc);
        List<String> invalidRecipients = configuredRecipients.stream().filter(id -> !recipients.contains(id)).toList();
        List<String> invalidCc = configuredCc.stream().filter(id -> !validCc.contains(id)).toList();
        // 同一用户同时出现在主送和抄送中时，保留主送身份，避免重复投递。
        List<String> ccRecipients = validCc.stream().filter(id -> !recipients.contains(id)).toList();
        log.info("[DependencyMetricAlert] resolved recipients={}, ccRecipients={}, invalidRecipients={}, invalidCcRecipients={}",
                recipients, ccRecipients, invalidRecipients, invalidCc);
        // 必须至少有一位有效主送人，仅配置抄送人时不发送。
        if (recipients.isEmpty()) return new DependencyMetricAlertResult(false,
                "未配置收件人或收件人均为无效用户", metrics.size(), (int) readyCount, recipients,
                "SKIPPED", null, ccRecipients, invalidRecipients, invalidCc);
        // 生成指标状态邮件，并在发送前保存投递内容及收件人快照。
        String content = DependencyMetricAlertRenderer.renderHtml(rows, date, now);
        DependencyMetricAlertDelivery delivery = new DependencyMetricAlertDelivery();
        delivery.setAlertDate(date);
        delivery.setRecipientSummary(String.join(",", recipients));
        delivery.setCcRecipientSummary(String.join(",", ccRecipients));
        delivery.setContent(content);
        // 投递记录必须创建成功并回填主键，后续才能记录发送结果。
        if (alertMapper.claim(delivery) != 1 || delivery.getId() == null)
            throw new IllegalStateException("创建预警投递记录失败");
        // 使用统一通知载体传递 HTML 正文、邮件主题、业务类型及主送/抄送名单。
        NotificationPayload payload = new NotificationPayload("HTML", content, null, null, null, null,
                "数据依赖指标未就绪预警(" + date.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日")) + ")",
                null, null, null, "ALERT", now, recipients, "METRIC_ALERT", ccRecipients);
        // 发送完成后更新投递状态；异常时记录错误信息并返回失败结果。
        try { sender.send(payload); alertMapper.update(delivery.getId(), "SUCCESS", null); }
        catch (Exception e) { alertMapper.update(delivery.getId(), "FAILED", e.getMessage());
            return new DependencyMetricAlertResult(false, "邮件发送失败", metrics.size(), (int) readyCount,
                    recipients, "FAILED", e.getMessage(), ccRecipients, invalidRecipients, invalidCc); }
        return new DependencyMetricAlertResult(true, "预警邮件已发送", metrics.size(), (int) readyCount,
                recipients, "SUCCESS", null, ccRecipients, invalidRecipients, invalidCc);
    }

    /**
     * 读取指定运行时配置，并解析为收件人 ID 列表。
     *
     * @param key 主送或抄送收件人的配置键
     * @return 解析后的收件人 ID 列表，配置缺失或配置值为空时按空字符串解析
     */
    private List<String> configuredRecipients(String key) {
        String raw = Optional.ofNullable(configMapper.selectByConfigKey(key))
                .map(AiChatRuntimeConfig::getConfigValue).orElse("");
        return DependencyMetricAlertRenderer.parseRecipients(raw);
    }
}
