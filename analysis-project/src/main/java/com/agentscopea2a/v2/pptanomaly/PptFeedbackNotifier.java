package com.agentscopea2a.v2.pptanomaly;

import com.agentscopea2a.config.datasource.GaussConfig;
import com.agentscopea2a.mapper.gauss.PptAnomalyMapper;
import com.agentscopea2a.v2.skillManager.notification.NotificationPayload;
import com.agentscopea2a.v2.skillManager.notification.NotificationSender;
import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
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
import org.springframework.scheduling.annotation.Scheduled;
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

/** 例会材料异步通知服务，按版本及部门汇总通知并通过事务记录实现补漏去重。 */
@Component
@ConditionalOnProperty(prefix = "spring.datasource.hikari.gauss-customer", name = "enabled", havingValue = "true")
public class PptFeedbackNotifier {
    // true：上传确认并完成识别后，事务提交时排队发送通知，定时任务负责补漏。
    // false：识别完成时不生成通知，统一由定时任务按计划处理。
    @Value("${app.ppt-feedback.notify-immediately:false}")
    private boolean sendImmediately;

    private static final Logger log = LoggerFactory.getLogger(PptFeedbackNotifier.class);
    private final PptAnomalyMapper persistence;
    private final NotificationSender sender;
    private final TransactionTemplate transaction;
    // 缓存最近一次业务请求的访问地址，供无 HTTP 请求上下文的定时补漏使用。
    private volatile String baseUrl;
    // 通知一律异步发送：识别接口的事务提交后只排队，不阻塞请求线程。
    private final ExecutorService dispatcher = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ppt-feedback-notifier");
        thread.setDaemon(true);
        return thread;
    });

    /** 装配统一发送器及独立事务模板，初始化通知访问地址。 */
    public PptFeedbackNotifier(PptAnomalyMapper persistence, NotificationSender sender,
                               @Qualifier(GaussConfig.TX_NAME) PlatformTransactionManager transactionManager,
                               @Value("${harness.a2a.csv-download.base-url:http://localhost:${server.port:8080}${server.servlet.context-path:}}") String baseUrl) {
        this.persistence = persistence;
        this.sender = sender;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.baseUrl = baseUrl.strip().replaceAll("/+$", "");
    }

    /** 关闭通知异步执行器，释放应用线程资源。 */
    @PreDestroy
    void shutdown() {
        dispatcher.shutdownNow();
    }

    /** 按代码默认时间计划异步补发尚未记录的部门通知。 */
    @Scheduled(cron = "0 0 * * * *", zone = "Asia/Shanghai")
    public void sendFeedbackNotices() {
        queue(null, baseUrl);
    }

    /** 在导入事务提交后排队发送指定版本通知，并保留本次请求地址。 */
    void recordVersionNoticesIfImmediate(long versionId) {
        // 在请求线程获取地址快照，异步线程及事务提交回调使用同一地址。
        String notificationBaseUrl = requestBaseUrl();
        if (!sendImmediately) return;
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

    /** 优先采用浏览器访问来源及上下文路径，保存地址供后续补漏复用。 */
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
            String requestAddress = address.build().toUriString().replaceAll("/+$", "");
            baseUrl = requestAddress;
            return requestAddress;
        }
        return baseUrl;
    }

    /** 将版本和访问地址快照提交到通知执行器。 */
    private void queue(Long versionId, String notificationBaseUrl) {
        dispatcher.execute(() -> dispatch(versionId, notificationBaseUrl));
    }

    /** 查询未发送事项并按版本及部门分组处理。 */
    private void dispatch(Long versionId, String notificationBaseUrl) {
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
                int recorded = 0;
                // 逐项登记反馈任务便于查询通知时间，实际发送按当前部门合并为一次。
                for (var notice : current) recorded += persistence.recordSimulatedNotice(notice, subject, body);
                // 已全部发送过则跳过；发送失败时回滚占用的通知记录，后续检查可重试。
                if (recorded > 0) send(current, subject, body, notificationBaseUrl);
            });
        } catch (RuntimeException e) {
            log.warn("例会材料通知发送失败，版本 {}，部门 {}",
                    first.versionId(), departmentOf(first), e);
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
        sender.sendPrepared(payload, request);
    }

    void sendManual(long versionId, String fileName, String department, List<String> to, List<String> cc) {
        String url = link(versionId, department, requestBaseUrl());
        String subject = department + "-例会材料反馈通知";
        String body = "<p>请查看例会材料「" + HtmlUtils.htmlEscape(fileName)
                + "」的最新反馈事项。</p><p><a href=\"" + HtmlUtils.htmlEscape(url) + "\">查看最新反馈事项</a></p>";
        var payload = new NotificationPayload("HTML", body, null, null, url, null, subject,
                null, null, versionId, "SUCCESS", LocalDateTime.now(), to, "MANUAL", cc);
        transaction.executeWithoutResult(status -> {
            if (!"ANALYZED".equals(persistence.lockVersion(versionId))) {
                throw new IllegalStateException("材料未完成识别");
            }
            for (var notice : persistence.feedbackNotices(versionId)) {
                if (department.equals(departmentOf(notice))) {
                    persistence.recordSimulatedNotice(notice, subject, body);
                }
            }
            sender.send(payload);
        });
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
