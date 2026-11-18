package com.agentscopea2a.v2.pptanomaly;

import com.agentscopea2a.config.datasource.GaussConfig;
import com.agentscopea2a.mapper.gauss.PptAnomalyMapper;
import com.agentscopea2a.v2.skillManager.notification.NotificationPayload;
import com.agentscopea2a.v2.skillManager.notification.NotificationSender;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.io.IOException;
import java.util.UUID;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private static final Logger log = LoggerFactory.getLogger(PptFeedbackNotifier.class);
    /** 例会通知独立控制：true 调用邮件网关，false 仅保存本地 HTML 供联调。 */
    private static final boolean HTTP_ENABLED = true;
    private static final Path LOCAL_HTML_DIRECTORY = Path.of("data/ppt-anomaly/notification-html");
    private static final DateTimeFormatter LOCAL_FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private final PptAnomalyMapper persistence;
    private final NotificationSender sender;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;
    private final String baseUrl;
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

    /** 同一版本的待反馈和已消除事项按部门汇总。 */
    private Map<String, List<PptAnomalyMapper.FeedbackNotice>> groupByDepartment(List<PptAnomalyMapper.FeedbackNotice> notices) {
        var groups = new LinkedHashMap<String, List<PptAnomalyMapper.FeedbackNotice>>();
        for (var notice : notices) {
            // 部门汇总键包含版本，待反馈与已消除事项共用一份部门通知。
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
                        .filter(notice -> departmentOf(first).equals(departmentOf(notice))).toList();
                if (current.isEmpty()) return;
                String subject = subject(first);
                String body = body(first, notificationBaseUrl);
                String department = departmentOf(first);
                var previous = previousMailTimes(first.versionId(), department, subject);
                List<PptAnomalyMapper.FeedbackNotice> firstTime = new ArrayList<>();
                for (var notice : current) {
                    LocalDateTime notifiedAt = previous.get(notice.recipient());
                    if (persistence.recordSimulatedNotice(notice, subject, body, notifiedAt) > 0 && notifiedAt == null) {
                        firstTime.add(notice);
                    }
                }
                if (!firstTime.isEmpty()) send(firstTime, subject, body, notificationBaseUrl);
            });
        } catch (RuntimeException e) {
            log.warn("例会材料通知发送失败，版本 {}，部门 {}",
                    first.versionId(), departmentOf(first), e);
        }
    }

    private Map<String, LocalDateTime> previousMailTimes(long versionId, String department, String subject) {
        var times = new LinkedHashMap<String, LocalDateTime>();
        for (var notice : persistence.previousFeedbackNoticeTimes(versionId, subject)) {
            times.put(notice.recipient(), notice.notifiedAt());
        }
        for (var mail : persistence.sentFeedbackMails(versionId, department, subject)) {
            addMailTimes(times, mail.toUsers(), mail.sentAt());
            addMailTimes(times, mail.ccUsers(), mail.sentAt());
        }
        return times;
    }

    private void addMailTimes(Map<String, LocalDateTime> times, String recipients, LocalDateTime sentAt) {
        try {
            List<String> ids = json.readValue(recipients, new TypeReference<>() {});
            for (String id : ids) times.merge(id, sentAt, (old, current) -> current.isAfter(old) ? current : old);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法读取邮件收件人记录", e);
        }
    }

    private String recipientJson(List<String> recipients) {
        try {
            return json.writeValueAsString(recipients);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法保存邮件收件人记录", e);
        }
    }

    /** 准备通知请求并调用统一发送器执行发送。 */
    private void send(List<PptAnomalyMapper.FeedbackNotice> group, String subject, String body, String notificationBaseUrl) {
        var first = group.get(0);
        // 同一经办人可能负责多条事项，发送前将接收人认证号去重。
        List<String> recipients = group.stream().map(PptAnomalyMapper.FeedbackNotice::recipient).distinct().toList();
        var payload = new NotificationPayload("HTML", body, null, null, link(first, notificationBaseUrl), null, subject,
                null, null, first.versionId(), "SUCCESS", LocalDateTime.now(), recipients, "METRIC");
        var request = sender.prepare(payload);
        deliver(payload, request);
        persistence.recordFeedbackMail(first.versionId(), departmentOf(first), subject, body,
                recipientJson(recipients), "[]", "SYSTEM");
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
            deliver(payload, sender.prepare(payload));
            persistence.recordFeedbackMail(versionId, department, subject, body,
                    recipientJson(to), recipientJson(cc), operator);
        });
    }

    /** 与长任务一样，由业务通知服务决定是否调用统一 HTTP 发送器。 */
    private void deliver(NotificationPayload payload, Map<String, Object> request) {
        if (HTTP_ENABLED) {
            sender.sendPrepared(payload, request);
            return;
        }
        String title = String.valueOf(request.getOrDefault("title", "例会材料通知"));
        String content = String.valueOf(request.getOrDefault("content", ""));
        String safeTitle = title.replaceAll("[^\\p{L}\\p{N}._-]", "_");
        if (safeTitle.isBlank()) safeTitle = "notification";
        Path directory = LOCAL_HTML_DIRECTORY.toAbsolutePath().normalize();
        Path file = directory.resolve(safeTitle + "-" + LocalDateTime.now().format(LOCAL_FILE_TIME)
                + "-" + UUID.randomUUID() + ".html");
        String html = "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><title>"
                + HtmlUtils.htmlEscape(title) + "</title></head><body>" + content + "</body></html>";
        try {
            Files.createDirectories(directory);
            Files.writeString(file, html, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            log.info("例会通知模拟发送，本地 HTML：{}", file);
        } catch (IOException e) {
            throw new IllegalStateException("保存例会通知预览失败", e);
        }
    }

    /** 生成带部门名称的通知标题，供邮件和本地 HTML 文件命名。 */
    private String subject(PptAnomalyMapper.FeedbackNotice first) {
        return departmentOf(first) + "-例会材料反馈通知";
    }

    /** 生成简短提醒正文和反馈详情页链接。 */
    private String body(PptAnomalyMapper.FeedbackNotice notice, String notificationBaseUrl) {
        return "<p>你有待关注的例会材料事项，请反馈。</p><p><a href=\""
                + HtmlUtils.htmlEscape(link(notice, notificationBaseUrl)) + "\">查看最新反馈事项</a></p>";
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
