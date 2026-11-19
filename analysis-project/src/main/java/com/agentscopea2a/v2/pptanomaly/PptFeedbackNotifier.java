package com.agentscopea2a.v2.pptanomaly;

import com.agentscopea2a.config.datasource.GaussConfig;
import com.agentscopea2a.mapper.gauss.PptAnomalyMapper;
import com.agentscopea2a.v2.skillManager.notification.NotificationPayload;
import com.agentscopea2a.v2.skillManager.notification.NotificationSender;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.stream.Collectors;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponentsBuilder;

/** 例会材料异步通知服务，按版本及部门汇总通知并通过事务记录去重。 */
@Component
@ConditionalOnProperty(prefix = "spring.datasource.hikari.gauss-customer", name = "enabled", havingValue = "true")
public class PptFeedbackNotifier {
    private static final String CHINA_ZONE = "Asia/Shanghai";
    private static final ZoneId CHINA = ZoneId.of(CHINA_ZONE);
    private static final LocalTime REMINDER_START = LocalTime.of(10, 0);
    private static final LocalTime REMINDER_END = LocalTime.of(18, 0);
    private static final Logger log = LoggerFactory.getLogger(PptFeedbackNotifier.class);
    /** 与长任务报告一样保存在 workspace 下，由业务模块指定目录。 */
    private static final java.nio.file.Path LOCAL_HTML_DIRECTORY =
            java.nio.file.Path.of("/workspace/ppt-anomaly/notification-html");
    private final PptAnomalyMapper persistence;
    private final NotificationSender sender;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;
    private final String baseUrl;
    private final AtomicBoolean reminderScanQueued = new AtomicBoolean();
    // 通知一律异步发送：识别接口的事务提交后只排队，不阻塞请求线程。
    private final ExecutorService dispatcher = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ppt-feedback-notifier");
        thread.setDaemon(true);
        return thread;
    });

    /** 装配统一发送器及独立事务模板，初始化通知访问地址。 */
    public PptFeedbackNotifier(PptAnomalyMapper persistence, NotificationSender sender, ObjectMapper json,
                               @Qualifier(GaussConfig.TX_NAME) PlatformTransactionManager transactionManager,
                               @Value("${harness.a2a.csv-download.base-url:http://localhost:${server.port:8080}${server.servlet.context-path:}}") String baseUrl) {
        this.persistence = persistence;
        this.sender = sender;
        this.json = json;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.baseUrl = baseUrl.strip().replaceAll("/+$", "");
    }

    /** 关闭通知异步执行器，释放应用线程资源。 */
    @PreDestroy
    void shutdown() {
        dispatcher.shutdownNow();
    }

    /** 在业务事务提交后排队发送指定版本通知，并保留本次请求地址。 */
    void notifyVersionAfterCommit(long versionId) {
        // 在请求线程获取地址快照，异步线程及事务提交回调使用同一地址。
        String notificationBaseUrl = requestBaseUrl();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                /** 在业务事务提交后将通知加入异步队列。 */
                @Override
                public void afterCommit() {
                    queue(versionId, notificationBaseUrl);
                }
            });
            return;
        }
        queue(versionId, notificationBaseUrl);
    }

    /** 控制器在保存事务完成后唤醒；定时扫描保证重启后仍可处理持久化任务。 */
    void queueAssignmentNotices() {
        dispatcher.execute(this::dispatchAssignmentNotices);
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public void retryAssignmentNotices() {
        queueAssignmentNotices();
    }

    void dispatchAssignmentNotices() {
        try {
            for (var notice : persistence.pendingAssignmentNotices()) sendAssignmentNotice(notice);
        } catch (RuntimeException e) {
            log.warn("例会事项分派通知扫描失败", e);
        }
    }

    void sendAssignmentNotice(PptAnomalyMapper.AssignmentNotice candidate) {
        transaction.executeWithoutResult(status -> {
            // 与归属修改、删除使用相同锁顺序，避免发送已移除人员或已删除事项的通知。
            if (!"ANALYZED".equals(persistence.lockVersion(candidate.versionId()))) return;
            var notice = persistence.lockAssignmentNotice(candidate.eventId(), candidate.recipient());
            if (notice == null) return;
            var finding = persistence.findingAssignment(notice.versionId(), notice.findingId());
            if (finding == null || !persistence.assignmentHandlers(notice.findingId()).contains(notice.recipient())) {
                persistence.finishAssignmentNotice(notice.eventId(), notice.recipient(), "CANCELLED");
                return;
            }
            String url = baseUrl + "/ppt-anomaly/overview#version=" + notice.versionId()
                    + "&finding=" + notice.findingId() + "&scope=all";
            String body = "<p>你有新增的待办事项，请<a href=\"" + HtmlUtils.htmlEscape(url)
                    + "\">点击查看</a>。</p>";
            var payload = new NotificationPayload("HTML", body, null, null, url, null, notice.fileName(),
                    null, null, notice.versionId(), "SUCCESS", LocalDateTime.now(),
                    List.of(notice.recipient()), "METRIC", List.of());
            try {
                sender.sendPrepared(payload, sender.prepare(payload), LOCAL_HTML_DIRECTORY);
            } catch (RuntimeException e) {
                persistence.finishAssignmentNotice(notice.eventId(), notice.recipient(), "FAILED");
                log.warn("例会事项分派通知发送失败，事项 {}", notice.findingId(), e);
                return;
            }
            persistence.markFeedbackNotified(List.of(notice.findingId()), LocalDateTime.now(CHINA));
            persistence.recordFeedbackMail(notice.versionId(), notice.department(), notice.fileName(), body,
                    recipientJson(List.of(notice.recipient())), "[]", "SYSTEM");
            persistence.finishAssignmentNotice(notice.eventId(), notice.recipient(), "SENT");
        });
    }

    /** 优先采用当前请求的浏览器访问来源及上下文路径。 */
    private String requestBaseUrl() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            var request = attributes.getRequest();
            UriComponentsBuilder address = ServletUriComponentsBuilder.fromRequestUri(request)
                    .replacePath(request.getContextPath()).replaceQuery(null);
            // 浏览器来源用于保留外部访问协议和端口，适配前后端代理访问。
            String origin = request.getHeader("Origin");
            if (origin != null && (origin.startsWith("http://") || origin.startsWith("https://"))) {
                address = UriComponentsBuilder.fromOriginHeader(origin).path(request.getContextPath());
            }
            return address.build().toUriString().replaceAll("/+$", "");
        }
        return baseUrl;
    }

    /** 将版本和访问地址快照提交到通知执行器。 */
    private void queue(long versionId, String notificationBaseUrl) {
        dispatcher.execute(() -> dispatch(versionId, notificationBaseUrl));
    }

    /** 查询未发送事项并按版本及部门分组处理。 */
    private void dispatch(long versionId, String notificationBaseUrl) {
        List<PptAnomalyMapper.FeedbackNotice> notices;
        try {
            notices = persistence.feedbackNotices(versionId);
        } catch (RuntimeException e) {
            log.warn("例会材料通知查询失败，版本 {}", versionId, e);
            return;
        }
        for (var group : groupByDepartment(notices).values()) {
            sendGroup(group, notificationBaseUrl);
        }
    }

    /** 同一版本的待反馈事项按部门汇总。 */
    private Map<String, List<PptAnomalyMapper.FeedbackNotice>> groupByDepartment(List<PptAnomalyMapper.FeedbackNotice> notices) {
        var groups = new LinkedHashMap<String, List<PptAnomalyMapper.FeedbackNotice>>();
        for (var notice : notices) {
            // 部门汇总键包含版本，每个部门独立生成通知。
            String key = notice.versionId() + "|" + departmentOf(notice);
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(notice);
        }
        return groups;
    }

    /** 在独立事务和版本锁保护下登记并发送一份部门通知。 */
    private void sendGroup(List<PptAnomalyMapper.FeedbackNotice> group, String notificationBaseUrl) {
        var first = group.get(0);
        try {
            transaction.executeWithoutResult(status -> {
                if (!"ANALYZED".equals(persistence.lockVersion(first.versionId()))) return;
                // 取得版本锁后重新查询，避免并发检查使用已发送的候选记录。
                var current = persistence.feedbackNotices(first.versionId()).stream()
                        .filter(notice -> "PENDING".equals(notice.kind()))
                        .filter(notice -> departmentOf(first).equals(departmentOf(notice))).toList();
                if (current.isEmpty()) return;
                var material = persistence.noticeMaterial(first.versionId());
                String subject = subject(material);
                String body = body(current, notificationBaseUrl);
                List<PptAnomalyMapper.FeedbackNotice> firstTime = new ArrayList<>();
                for (var notice : current) {
                    if (persistence.recordSimulatedNotice(notice, subject, body, null) > 0) {
                        firstTime.add(notice);
                    }
                }
                if (!firstTime.isEmpty()) {
                    String actualBody = body(firstTime, notificationBaseUrl);
                    send(firstTime, subject, actualBody, notificationBaseUrl);
                }
            });
        } catch (RuntimeException e) {
            log.warn("例会材料通知发送失败，版本 {}，部门 {}",
                    first.versionId(), departmentOf(first), e);
        }
    }

    private String recipientJson(List<String> recipients) {
        return writeJson(recipients);
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法保存邮件收件人记录", e);
        }
    }

    /** 准备通知请求并调用统一发送器执行发送。 */
    private void send(List<PptAnomalyMapper.FeedbackNotice> group, String subject, String body, String notificationBaseUrl) {
        group = group.stream().filter(notice -> "PENDING".equals(notice.kind())).toList();
        if (group.isEmpty()) return;
        var first = group.get(0);
        // 同一经办人可能负责多条事项，发送前将接收人认证号去重。
        List<String> recipients = group.stream().map(PptAnomalyMapper.FeedbackNotice::recipient).distinct().toList();
        List<String> cc = supervisors(recipients);
        var payload = new NotificationPayload("HTML", body, null, null, link(first, notificationBaseUrl), null, subject,
                null, null, first.versionId(), "SUCCESS", LocalDateTime.now(), recipients, "METRIC", cc);
        var request = sender.prepare(payload);
        sender.sendPrepared(payload, request, LOCAL_HTML_DIRECTORY);
        persistence.markFeedbackNotified(group.stream().map(PptAnomalyMapper.FeedbackNotice::findingId).distinct().toList(),
                LocalDateTime.now(CHINA));
        persistence.recordFeedbackMail(first.versionId(), departmentOf(first), subject, body,
                recipientJson(recipients), recipientJson(cc), "SYSTEM");
    }

    private List<String> supervisors(List<String> recipients) {
        var config = persistence.feedbackSupervisorConfig(false);
        if (config == null) return List.of();
        try {
            var idsType = new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {};
            var disabled = new java.util.HashSet<>(json.readValue(config.disabledUserIds(), idsType));
            return json.readValue(config.userIds(), idsType).stream()
                    .filter(id -> !disabled.contains(id) && !recipients.contains(id)).distinct().toList();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法读取反馈督办人配置", e);
        }
    }

    static boolean canSendReminderAt(LocalTime time) {
        return !time.isBefore(REMINDER_START) && time.isBefore(REMINDER_END);
    }

    /** 按北京时间每分钟检查到期事项，实际间隔取决于每条事项上次发送成功的时间。 */
    @Scheduled(cron = "0 * 10-17 * * *", zone = CHINA_ZONE)
    public void scanFeedbackReminders() {
        if (!reminderScanQueued.compareAndSet(false, true)) return;
        dispatcher.execute(() -> {
            try {
                var now = LocalDateTime.now(CHINA);
                if (!canSendReminderAt(now.toLocalTime())) return;
                for (var scope : persistence.dueFeedbackReminderScopes(now)) {
                    if (!canSendReminderAt(LocalTime.now(CHINA))) break;
                    try {
                        sendFeedbackReminders(scope);
                    } catch (RuntimeException e) {
                        log.warn("例会反馈逾期提醒处理失败，版本 {}，部门 {}", scope.versionId(), scope.department(), e);
                    }
                }
            } catch (RuntimeException e) {
                log.warn("例会反馈逾期提醒扫描失败", e);
            } finally {
                reminderScanQueued.set(false);
            }
        });
    }

    private void sendFeedbackReminders(PptAnomalyMapper.ReminderScope scope) {
        transaction.executeWithoutResult(status -> {
            if (!"ANALYZED".equals(persistence.lockVersion(scope.versionId()))) return;
            var now = LocalDateTime.now(CHINA);
            if (!canSendReminderAt(now.toLocalTime())) return;
            var due = persistence.dueFeedbackReminders(scope, now);
            if (due.isEmpty() || !canSendReminderAt(LocalTime.now(CHINA))) return;
            String fileName = persistence.noticeMaterial(scope.versionId()).fileName();
            sendReminder(scope.versionId(), fileName, scope.department(), due);
        });
    }

    void sendReminder(long version, String fileName, String department,
                              List<PptAnomalyMapper.FeedbackReminder> group) {
        var findings = new LinkedHashMap<Long, PptAnomalyMapper.FeedbackReminder>();
        group.forEach(item -> findings.putIfAbsent(item.findingId(), item));
        List<Long> ids = List.copyOf(findings.keySet());
        var to = group.stream().map(PptAnomalyMapper.FeedbackReminder::recipient).distinct().toList();
        var cc = supervisors(to);
        String url = link(version, department, baseUrl);
        String subject = "【反馈逾期提醒】" + fileName;
        String items = findings.values().stream().map(item -> "<li>P" + item.page() + " "
                + HtmlUtils.htmlEscape(item.title() == null ? "未命名事项" : item.title()) + "</li>")
                .collect(Collectors.joining());
        String body = "<p>" + HtmlUtils.htmlEscape(department) + "有 " + ids.size()
                + " 项待反馈事项已逾期，请尽快提交反馈。</p><ul>" + items
                + "</ul><p><a href=\"" + HtmlUtils.htmlEscape(url) + "\">点击查看</a></p>";
        var payload = new NotificationPayload("HTML", body, null, null, url, null, subject,
                null, null, version, "SUCCESS", LocalDateTime.now(CHINA), to, "METRIC", cc);
        LocalDateTime sentAt = null;
        try {
            sender.sendPrepared(payload, sender.prepare(payload), LOCAL_HTML_DIRECTORY);
            sentAt = LocalDateTime.now(CHINA);
        } catch (RuntimeException e) {
            log.warn("例会反馈逾期提醒发送失败，版本 {}，部门 {}", version, department, e);
        }
        if (sentAt != null) persistence.markFeedbackReminded(ids, sentAt);
        persistence.recordFeedbackReminder(version, department, subject, body, recipientJson(to), recipientJson(cc),
                writeJson(ids), sentAt == null ? "FAILED" : "SENT", sentAt);
    }

    void sendManual(long versionId, String fileName, String department, List<String> to, List<String> cc, String operator) {
        String url = link(versionId, department, requestBaseUrl());
        String subject = department + "-例会材料反馈结果通知";
        String body = "<p>请查看例会材料「" + HtmlUtils.htmlEscape(fileName)
                + "」的最新反馈事项。</p><p><a href=\"" + HtmlUtils.htmlEscape(url) + "\">查看最新反馈事项</a></p>";
        var payload = new NotificationPayload("HTML", body, null, null, url, null, subject,
                null, null, versionId, "SUCCESS", LocalDateTime.now(), to, "MANUAL", cc);
        transaction.executeWithoutResult(status -> {
            if (!"ANALYZED".equals(persistence.lockVersion(versionId))) {
                throw new IllegalStateException("材料未完成识别");
            }
            // 结果通知独立记录，不能充当待反馈通知的送达凭据。
            sender.sendPrepared(payload, sender.prepare(payload), LOCAL_HTML_DIRECTORY);
            persistence.recordFeedbackMail(versionId, department, subject, body,
                    recipientJson(to), recipientJson(cc), operator);
        });
    }

    /** 材料名称仅去除末尾的 PPTX 扩展名，V1 为通知模板中的固定稿次。 */
    private String subject(PptAnomalyMapper.NoticeMaterial material) {
        return "《" + material.fileName().replaceFirst("(?i)\\.pptx$", "") + "》V1稿待分析事项的通知";
    }

    /** 同一事项有多名经办人时只计一项。 */
    private String body(List<PptAnomalyMapper.FeedbackNotice> notices, String notificationBaseUrl) {
        var first = notices.get(0);
        long count = notices.stream().filter(notice -> "PENDING".equals(notice.kind()))
                .map(PptAnomalyMapper.FeedbackNotice::findingId).distinct().count();
        return "<p>各位：</p><p style=\"text-indent:2em\">本期材料涉及到"
                + HtmlUtils.htmlEscape(departmentOf(first)) + "共" + count + "项，请在收到本通知后 1 小时内<a href=\"" + HtmlUtils.htmlEscape(link(first, notificationBaseUrl))
                + "\">点击链接</a>分析反馈。</p>";
    }

    /** 链接保留来源版本及收件部门，详情页定位到同批次最新已识别版本。 */
    private String link(PptAnomalyMapper.FeedbackNotice first, String notificationBaseUrl) {
        return link(first.versionId(), departmentOf(first), notificationBaseUrl);
    }

    private String link(long versionId, String department, String notificationBaseUrl) {
        return notificationBaseUrl + "/ppt-anomaly/overview?department="
                + java.net.URLEncoder.encode(department, java.nio.charset.StandardCharsets.UTF_8)
                + "#version=" + versionId;
    }

    /** 规范化通知部门名称，历史空归属使用默认名称。 */
    private String departmentOf(PptAnomalyMapper.FeedbackNotice notice) {
        return notice.department() == null || notice.department().isBlank()
                ? "未分配部门" : notice.department().strip();
    }
}
