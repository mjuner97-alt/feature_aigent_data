package com.agentscopea2a.v2.pptanomaly;

import com.agentscopea2a.config.datasource.GaussConfig;
import com.agentscopea2a.constant.AgentToolConstant;
import com.agentscopea2a.v2.auth.mapper.DeveloperPlPersonInfoMapper;
import com.agentscopea2a.mapper.gauss.PptAnomalyMapper;
import com.agentscopea2a.v2.skillManager.notification.NotificationPayload;
import com.agentscopea2a.v2.skillManager.notification.NotificationSender;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
    private static final DateTimeFormatter FEEDBACK_DEADLINE_FORMAT = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm");
    private static final Logger log = LoggerFactory.getLogger(PptFeedbackNotifier.class);
    /** 与长任务报告一样保存在 workspace 下，由业务模块指定目录。 */
    private static final java.nio.file.Path LOCAL_HTML_DIRECTORY =
            java.nio.file.Path.of("/workspace/ppt-anomaly/notification-html");
    private final PptAnomalyMapper persistence;
    private final NotificationSender sender;
    private final ObjectMapper json;
    private final DeveloperPlPersonInfoMapper people;
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
    public PptFeedbackNotifier(PptAnomalyMapper persistence, NotificationSender sender, ObjectMapper json, DeveloperPlPersonInfoMapper people,
                               @Qualifier(GaussConfig.TX_NAME) PlatformTransactionManager transactionManager,
                               @Value("${harness.a2a.csv-download.base-url:http://localhost:${server.port:8080}${server.servlet.context-path:}}") String baseUrl) {
        this.persistence = persistence;
        this.sender = sender;
        this.json = json;
        this.people = people;
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
        dispatcher.execute(this::dispatchCompletionReports);
    }

    /** 由持有版本锁的反馈事务登记已完成部门，提交后再发送。 */
    void enqueueCompletedDepartments(long versionId, List<String> departments) {
        if (departments.isEmpty()) return;
        var findings = persistence.findings(versionId);
        boolean queued = false;
        for (String department : new java.util.LinkedHashSet<>(departments)) {
            var items = findings.stream().filter(item -> belongsToDepartment(item.departments(), department)).toList();
            if (!items.isEmpty() && items.stream().allMatch(item -> PptFeedbackMailTable.isAnalyzed(item.status()))) {
                queued |= persistence.enqueueCompletionReport(versionId, department) > 0;
            }
        }
        if (queued) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatcher.execute(PptFeedbackNotifier.this::dispatchCompletionReports);
                }
            });
        }
    }

    void dispatchCompletionReports() {
        List<PptAnomalyMapper.CompletionReport> reports;
        try {
            reports = persistence.pendingCompletionReports();
        } catch (RuntimeException e) {
            log.warn("例会材料完成报告通知扫描失败", e);
            return;
        }
        for (var report : reports) {
            try {
                transaction.executeWithoutResult(status -> sendCompletionReport(report));
            } catch (RuntimeException e) {
                log.warn("例会材料完成报告通知发送失败，版本 {}，部门 {}", report.versionId(), report.department(), e);
            }
        }
    }

    private void sendCompletionReport(PptAnomalyMapper.CompletionReport report) {
        long version = report.versionId();
        String department = report.department();
        if (!"ANALYZED".equals(persistence.lockVersion(version)) || !persistence.completionReportPending(report.id())) return;
        var saved = persistence.reportRecipients(department);
        var to = saved == null ? List.<String>of() : configuredRecipients(saved.toJson());
        var analysis = departmentAnalysis(version, department, true);
        if (analysis.findingCount() == 0 || analysis.pendingCount() > 0 || to.isEmpty()) {
            persistence.cancelCompletionReport(report.id());
            return;
        }
        var cc = configuredRecipients(saved.ccJson()).stream().filter(id -> !to.contains(id)).toList();
        String label = persistence.noticeMaterial(version).mailLabel();
        String url = link(version, department, baseUrl);
        String subject = reportSubject(label, department, analysis.pendingCount());
        String body = reportBody(label, department, url, analysis);
        var payload = new NotificationPayload("HTML", body, null, null, url, null, subject,
                null, null, version, "SUCCESS", LocalDateTime.now(CHINA), to, "METRIC", cc);
        sender.sendPrepared(payload, sender.prepareRendered(payload), LOCAL_HTML_DIRECTORY);
        persistence.markCompletionReportSent(report.id(), subject, body, recipientJson(to), recipientJson(cc));
    }

    private List<String> configuredRecipients(String stored) {
        try {
            return PptAnomalyController.recipientIds(json.readValue(stored,
                    new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {}));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法读取报告通知名单", e);
        }
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
            var material = persistence.noticeMaterial(notice.versionId());
            String subject = "【分析经办人调整】《" + material.mailLabel() + "》V1稿待分析事项的通知";
            List<String> to = List.of(notice.recipient());
            List<String> previousHandlers = previousAssignmentHandlers(notice.previousHandlers());
            var cc = java.util.stream.Stream.concat(supervisors(notice.department(), to).stream(), previousHandlers.stream())
                    .filter(id -> !to.contains(id)).distinct().toList();
            String previousLabel = previousAssignmentLabel(notice.previousDepartment(), previousHandlers);
            String body = "<p>您好：</p><p style=\"text-indent:2em;\">您有新增分析的关注事项（原经办人："
                    + HtmlUtils.htmlEscape(previousLabel)
                    + "），共1项，请于1个小时内<a href=\"" + HtmlUtils.htmlEscape(url)
                    + "\">点击链接</a>反馈分析结果。</p>" + feedbackFooter(notice.department());
            var payload = new NotificationPayload("HTML", body, null, null, url, null, subject,
                    null, null, notice.versionId(), "SUCCESS", LocalDateTime.now(CHINA),
                    to, "METRIC", cc);
            try {
                sender.sendPrepared(payload, sender.prepareRendered(payload), LOCAL_HTML_DIRECTORY);
            } catch (RuntimeException e) {
                persistence.finishAssignmentNotice(notice.eventId(), notice.recipient(), "FAILED");
                log.warn("例会事项分派通知发送失败，事项 {}", notice.findingId(), e);
                return;
            }
            var sentAt = LocalDateTime.now(CHINA);
            persistence.markFeedbackNotified(List.of(notice.findingId()), sentAt, feedbackDeadline(sentAt, 1));
            persistence.recordFeedbackMail(notice.versionId(), notice.department(), subject, body,
                    recipientJson(to), recipientJson(cc), "SYSTEM");
            persistence.finishAssignmentNotice(notice.eventId(), notice.recipient(), "SENT");
        });
    }

    private List<String> previousAssignmentHandlers(String storedHandlers) {
        if (storedHandlers == null) return List.of();
        try {
            return json.readValue(storedHandlers, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法读取分派通知的原经办人", e);
        }
    }

    private String previousAssignmentLabel(String department, List<String> handlers) {
        if (handlers.isEmpty()) return "无";
        var names = new LinkedHashMap<String, String>();
        for (var person : people.selectByUserIds(handlers)) {
            if (person.getName() != null && !person.getName().isBlank()) {
                names.putIfAbsent(person.getUserId(), person.getName());
            }
        }
        String handlerNames = handlers.stream().map(id -> names.getOrDefault(id, id)).collect(Collectors.joining("、"));
        return department == null || department.isBlank() ? handlerNames : department + " " + handlerNames;
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
                boolean firstVersion = material.versionNo() == 1;
                String subject = automaticSubject(material);
                String department = departmentOf(first);
                String url = link(first.versionId(), department, notificationBaseUrl);
                var analysis = departmentAnalysis(first.versionId(), department, false);
                var sentAt = LocalDateTime.now(CHINA);
                String deadline = feedbackDeadline(sentAt, 2).format(firstVersion
                        ? FEEDBACK_DEADLINE_FORMAT : DateTimeFormatter.ofPattern("HH:mm"));
                String body = "<p>本期材料涉及到" + HtmlUtils.htmlEscape(department)
                        + "关注事项共" + analysis.findingCount() + "项，请于" + deadline
                        + "内<a href=\"" + HtmlUtils.htmlEscape(url) + "\">点击链接</a>反馈分析结果。"
                        + (firstVersion ? "" : "如已完成分析，请确认本次材料变更是否需要调整反馈内容。")
                        + "</p>" + analysis.attachedTable() + feedbackFooter(department);
                List<PptAnomalyMapper.FeedbackNotice> firstTime = new ArrayList<>();
                for (var notice : current) {
                    if (persistence.recordSimulatedNotice(notice, subject, body, null) > 0) {
                        firstTime.add(notice);
                    }
                }
                if (!firstTime.isEmpty()) {
                    send(firstTime, subject, body, url, sentAt);
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
    private void send(List<PptAnomalyMapper.FeedbackNotice> group, String subject, String body, String url,
                      LocalDateTime sentAt) {
        group = group.stream().filter(notice -> "PENDING".equals(notice.kind())).toList();
        if (group.isEmpty()) return;
        var first = group.get(0);
        // 同一经办人可能负责多条事项，发送前将接收人认证号去重。
        List<String> recipients = group.stream().map(PptAnomalyMapper.FeedbackNotice::recipient).distinct().toList();
        List<String> cc = supervisors(departmentOf(first), recipients);
        var payload = new NotificationPayload("HTML", body, null, null, url, null, subject,
                null, null, first.versionId(), "SUCCESS", sentAt, recipients, "METRIC", cc);
        var request = sender.prepareRendered(payload);
        sender.sendPrepared(payload, request, LOCAL_HTML_DIRECTORY);
        persistence.markFeedbackNotified(group.stream().map(PptAnomalyMapper.FeedbackNotice::findingId).distinct().toList(),
                LocalDateTime.now(CHINA), feedbackDeadline(sentAt, 2));
        persistence.recordFeedbackMail(first.versionId(), departmentOf(first), subject, body,
                recipientJson(recipients), recipientJson(cc), "SYSTEM");
    }

    private List<String> supervisors(String department, List<String> recipients) {
        var config = persistence.feedbackSupervisorConfig(false);
        if (config == null) return List.of();
        try {
            return PptAnomalyService.readSupervisorDepartments(json, config.departmentAssignments()).entrySet().stream()
                    .filter(entry -> entry.getValue().contains(department) && !recipients.contains(entry.getKey()))
                    .map(Map.Entry::getKey).toList();
        } catch (IllegalStateException e) {
            log.warn("无法读取反馈督办部门，邮件仅发送给经办人", e);
            return List.of();
        }
    }

    private String feedbackFooter(String department) {
        String address = HtmlUtils.htmlEscape(AgentToolConstant.PPT_FEEDBACK_DETAIL_URL);
        String details = "<p>明细地址：<a href=\"" + address + "\">" + address + "</a></p>";
        var ids = supervisors(department, List.of());
        if (ids.isEmpty()) return details;
        Map<String, String> names = new LinkedHashMap<>();
        for (var person : people.selectByUserIds(ids)) {
            if (person.getName() != null && !person.getName().isBlank()) {
                names.putIfAbsent(person.getUserId(), person.getName().strip());
            }
        }
        String contacts = ids.stream().map(names::get).filter(java.util.Objects::nonNull)
                .collect(Collectors.joining("、"));
        return contacts.isEmpty() ? details : details + "<p>联系人：" + HtmlUtils.htmlEscape(contacts) + "</p>";
    }

    private static LocalDateTime feedbackDeadline(LocalDateTime sentAt, int hours) {
        return sentAt.plusHours(hours).withSecond(0).withNano(0);
    }

    private static int weekMinute(LocalDateTime time) {
        return (time.getDayOfWeek().getValue() - 1) * 1440 + time.getHour() * 60 + time.getMinute();
    }

    private static String automaticSubject(PptAnomalyMapper.NoticeMaterial material) {
        return "《" + material.mailLabel() + "》V" + material.versionNo() + "稿分析事项的通知";
    }

    /** 按北京时间每分钟检查到期事项，实际间隔取决于每条事项上次发送成功的时间。 */
    @Scheduled(cron = "0 * * * * *", zone = CHINA_ZONE)
    public void scanFeedbackReminders() {
        if (!reminderScanQueued.compareAndSet(false, true)) return;
        dispatcher.execute(() -> {
            try {
                var now = LocalDateTime.now(CHINA);
                for (var scope : persistence.dueFeedbackReminderScopes(now, weekMinute(now))) {
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
            var due = persistence.dueFeedbackReminders(scope, now, weekMinute(now));
            if (due.isEmpty()) return;
            var material = persistence.noticeMaterial(scope.versionId());
            var byDeadline = due.stream().collect(Collectors.groupingBy(
                    PptAnomalyMapper.FeedbackReminder::deadline, LinkedHashMap::new, Collectors.toList()));
            byDeadline.values().forEach(group -> sendReminder(scope.versionId(), material, scope.department(), group));
        });
    }

    void sendReminder(long version, PptAnomalyMapper.NoticeMaterial material, String department,
                              List<PptAnomalyMapper.FeedbackReminder> group) {
        var findings = new LinkedHashMap<Long, PptAnomalyMapper.FeedbackReminder>();
        group.forEach(item -> findings.putIfAbsent(item.findingId(), item));
        List<Long> ids = List.copyOf(findings.keySet());
        var to = group.stream().map(PptAnomalyMapper.FeedbackReminder::recipient).distinct().toList();
        var cc = supervisors(department, to);
        String url = link(version, department, baseUrl);
        String subject = "【催复，存在" + ids.size() + "项待分析反馈】" + automaticSubject(material);
        var now = LocalDateTime.now(CHINA);
        String body = reminderBody(version, department, ids.size(), url, group.get(0).deadline(), now);
        var payload = new NotificationPayload("HTML", body, null, null, url, null, subject,
                null, null, version, "SUCCESS", LocalDateTime.now(CHINA), to, "METRIC", cc);
        LocalDateTime sentAt = null;
        try {
            sender.sendPrepared(payload, sender.prepareRendered(payload), LOCAL_HTML_DIRECTORY);
            sentAt = LocalDateTime.now(CHINA);
        } catch (RuntimeException e) {
            log.warn("例会反馈逾期提醒发送失败，版本 {}，部门 {}", version, department, e);
        }
        if (sentAt != null) persistence.markFeedbackReminded(ids, sentAt);
        persistence.recordFeedbackReminder(version, department, subject, body, recipientJson(to), recipientJson(cc),
                writeJson(ids), sentAt == null ? "FAILED" : "SENT", sentAt);
    }

    void sendManual(long versionId, String materialLabel, String department, List<String> to, List<String> cc, String operator) {
        String url = link(versionId, department, requestBaseUrl());
        var analysis = departmentAnalysis(versionId, department, true);
        String subject = reportSubject(materialLabel, department, analysis.pendingCount());
        String body = reportBody(materialLabel, department, url, analysis);
        var payload = new NotificationPayload("HTML", body, null, null, url, null, subject,
                null, null, versionId, "SUCCESS", LocalDateTime.now(), to, "MANUAL", cc);
        transaction.executeWithoutResult(status -> {
            if (!"ANALYZED".equals(persistence.lockVersion(versionId))) {
                throw new IllegalStateException("材料未完成识别");
            }
            // 结果通知独立记录，不能充当待反馈通知的送达凭据。
            sender.sendPrepared(payload, sender.prepareRendered(payload), LOCAL_HTML_DIRECTORY);
            persistence.recordFeedbackMail(versionId, department, subject, body,
                    recipientJson(to), recipientJson(cc), operator);
        });
    }

    private String reportSubject(String materialLabel, String department, long pendingCount) {
        String prefix = pendingCount == 0 ? "【材料分析】" : "【材料分析，存在" + pendingCount + "项待分析反馈】";
        return prefix + "《" + materialLabel + "》-" + department;
    }

    private String reportBody(String materialLabel, String department, String url, DepartmentAnalysis analysis) {
        return "<p>各位好：</p><p style=\"text-indent:2em;\">《" + HtmlUtils.htmlEscape(materialLabel)
                + "》材料分析如下，本期材料涉及到部门关注事项共" + analysis.findingCount()
                + "项，请<a href=\"" + HtmlUtils.htmlEscape(url) + "\">点击链接</a>参阅。</p>"
                + analysis.attachedTable() + feedbackFooter(department);
    }

    String reminderBody(long version, String department, int pending, String url,
                        LocalDateTime deadline, LocalDateTime now) {
        var analysis = departmentAnalysis(version, department, false);
        return "<p>本期材料涉及到" + HtmlUtils.htmlEscape(department) + "共" + analysis.findingCount()
                + "项，要求于" + deadline.format(FEEDBACK_DEADLINE_FORMAT)
                + "内<a href=\"" + HtmlUtils.htmlEscape(url) + "\">点击链接</a>反馈分析结果，截至目前"
                + (now.isAfter(deadline) ? "（已逾期）" : "")
                + "，仍存在" + pending + "项待分析反馈，请尽快处理。</p>"
                + analysis.table() + feedbackFooter(department);
    }

    private DepartmentAnalysis departmentAnalysis(long version, String department, boolean hideCompletedPendingColumn) {
        var findings = persistence.findings(version).stream()
                .filter(item -> belongsToDepartment(item.departments(), department)).toList();
        var ids = findings.stream().map(PptAnomalyMapper.FindingRow::id).collect(Collectors.toSet());
        var handlers = persistence.resultHandlers(version).stream().filter(item -> ids.contains(item.findingId())).toList();
        var userIds = handlers.stream().map(PptAnomalyMapper.ResultHandler::userId)
                .filter(id -> id != null && !id.isBlank()).distinct().toList();
        var names = new LinkedHashMap<String, String>();
        if (!userIds.isEmpty()) {
            for (var person : people.selectByUserIds(userIds)) {
                if (person.getName() != null && !person.getName().isBlank()) names.putIfAbsent(person.getUserId(), person.getName());
            }
        }
        long pendingCount = findings.stream().filter(item -> !PptFeedbackMailTable.isAnalyzed(item.status()))
                .map(PptAnomalyMapper.FindingRow::id).distinct().count();
        return new DepartmentAnalysis(ids.size(), pendingCount,
                PptFeedbackMailTable.render(findings, handlers, names, !hideCompletedPendingColumn || pendingCount > 0));
    }

    private record DepartmentAnalysis(int findingCount, long pendingCount, String table) {
        String attachedTable() {
            return "<p>附关注事项分析情况：</p>" + table;
        }
    }

    private boolean belongsToDepartment(String departments, String department) {
        if (departments == null) return false;
        try {
            return json.readValue(departments, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {})
                    .contains(department);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法读取事项部门，不能生成通知数量", e);
        }
    }

    /** 链接保留来源版本及收件部门，详情页固定到通知对应版本，避免新版本改变事项数量。 */
    private String link(long versionId, String department, String notificationBaseUrl) {
        return notificationBaseUrl + "/ppt-anomaly/overview?department="
                + java.net.URLEncoder.encode(department, java.nio.charset.StandardCharsets.UTF_8)
                + "#version=" + versionId + "&exact=1&scope=all";
    }

    /** 规范化通知部门名称，历史空归属使用默认名称。 */
    private String departmentOf(PptAnomalyMapper.FeedbackNotice notice) {
        return notice.department() == null || notice.department().isBlank()
                ? "未分配部门" : notice.department().strip();
    }
}
