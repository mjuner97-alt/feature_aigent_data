package com.agentscopea2a.v2.skillManager.notification;

import com.agentscopea2a.v2.service.UrlShortenerService;
import com.agentscopea2a.v2.skillManager.entity.SkillDependencyMetric;
import com.agentscopea2a.v2.skillManager.entity.SkillJob;
import com.agentscopea2a.v2.skillManager.entity.SkillJobExecution;
import com.agentscopea2a.v2.skillManager.entity.SkillJobNotification;
import com.agentscopea2a.v2.skillManager.mapper.SkillDependencyMetricMapper;
import com.agentscopea2a.v2.skillManager.mapper.SkillJobMapper;
import com.agentscopea2a.v2.skillManager.service.MockOrgService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 跑批执行成功后的通知服务。
 *
 * <p>模板与格式由所属依赖指标的 {@code notify_*} 配置决定（admin 预置）：
 * <ul>
 *   <li>notify_enabled=TRUE 才发；</li>
 *   <li>notify_content_type=TEXT/HTML；</li>
 *   <li>notify_content_template 为空则用代码内置默认模板（已提供 TEXT / HTML 两版）。</li>
 * </ul>
 * 实际发送动作委托 {@link NotificationSender}（默认 stub，接入内部系统时替换）。
 *
 * <p>发送在独立单线程异步执行，不阻塞 job worker；best-effort：失败仅告警，不影响 job 结果。
 */
// 发送委托 NotificationSender（默认 StubNotificationSender 仅打日志）；接入内部系统时提供自定义 @Component 实现自动替换。
@Service
public class NotificationService {
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    /**
     * 内置默认 HTML 模板（notify_content_template 为空且 type=HTML 时使用）。
     * 邮件客户端会剥离 <script>（ECharts 图表必然丢失），正文只放摘要+报告地址，不嵌报告全文。
     */
    private static final String DEFAULT_HTML_TEMPLATE = """
            <html><body>
              <h3>指标分析报告已生成</h3>
              <p>任务已完成，请点击以下地址查看完整报告（含图表）：</p>
              <p><a href="{file_url}">打开分析报告</a></p>
              <table>
                <tr><td>依赖指标</td><td>{metric_name}（{metric_code}）</td></tr>
                <tr><td>任务名称</td><td>{job_name}</td></tr>
                <tr><td>生成时间</td><td>{date}</td></tr>
              </table>
            </body></html>""";

    /** 内置默认纯文本模板（notify_content_template 为空且 type=TEXT 时使用） */
    private static final String DEFAULT_TEXT_TEMPLATE = """
            【指标分析报告已生成】
            依赖指标：{metric_name}（{metric_code}）
            任务名称：{job_name}
            生成时间：{date}
            报告地址：{file_url}""";

    private final SkillDependencyMetricMapper metricMapper;
    private final SkillJobMapper jobMapper;
    private final NotificationSender sender;
    private final UrlShortenerService urlShortenerService;
    private final MockOrgService orgService;

    /**
     * 报告下载链接的 base URL（对应 {@code harness.a2a.skill-job.download-base-url}）。
     * 空=输出相对路径（前端 vite proxy / 同域）；设置=拼完整域名（独立域名 / 邮件外链时用）。
     */
    @Value("${harness.a2a.csv-download.base-url:}")
    private String downloadBaseUrl;

    /** 通知专用单线程：避免外部系统调用阻塞 job 执行线程 */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "skill-job-notify");
        t.setDaemon(true);
        return t;
    });

    public NotificationService(SkillDependencyMetricMapper metricMapper, SkillJobMapper jobMapper,
                               NotificationSender sender,
                               UrlShortenerService urlShortenerService,
                               MockOrgService orgService) {
        this.metricMapper = metricMapper;
        this.jobMapper = jobMapper;
        this.sender = sender;
        this.urlShortenerService = urlShortenerService;
        this.orgService = orgService;
    }

    /**
     * 跑批 job 执行成功后调用：按触发类型决定是否通知，启用则异步组装并发送通知（携带报告文件 .html）。
     *
     * <p>门控规则：
     * <ul>
     *   <li>MANUAL（手动触发）：总是通知，不看指标、不看 notify 开关；由 sender 按 triggerType 自行决定是否真正发出。</li>
     *   <li>METRIC/EXTERNAL：必须有指标且 {@code notify_enabled=TRUE} 才发（原逻辑）。</li>
     * </ul>
     * 无指标 job 仅 MANUAL 会进入通知，渲染走默认模板。
     */
    public void notifyJobCompleted(SkillJob job, SkillJobExecution execution, String filePath) {
        if (job == null || execution == null) {
            return;
        }
        String triggerType = execution.getTriggerType();
        boolean manual = "MANUAL".equals(triggerType);
        SkillDependencyMetric metric = job.getMetricId() != null
                ? metricMapper.selectById(job.getMetricId()) : null;
        boolean notifyEnabled = metric != null && Boolean.TRUE.equals(metric.getNotifyEnabled());
        // Automatic/external notifications always follow the metric notification switch,
        // regardless of whether execution succeeded or failed.
        if (!manual && !notifyEnabled) {
            recordSkipped(job, execution, filePath, triggerType,
                    metric == null ? "未关联通知指标" : "指标通知开关未开启");
            return;
        }
        try {
            enqueue(job, metric, execution, filePath, triggerType, "INITIAL");
        } catch (Exception e) {
            // Notification persistence/delivery remains best-effort and must never change job success.
            log.warn("[Notification] enqueue failed for job {} (ignored): {}", job.getId(), e.getMessage(), e);
        }
    }

    /** Force a new delivery attempt for an existing successful execution. */
    public SkillJobNotification resend(SkillJob job, SkillJobExecution execution, String filePath) {
        if (job == null || execution == null || filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException("NotificationResendInvalid: 缺少任务、执行记录或报告文件");
        }
        SkillDependencyMetric metric = job.getMetricId() != null
                ? metricMapper.selectById(job.getMetricId()) : null;
        return enqueue(job, metric, execution, filePath, execution.getTriggerType(), "RESEND");
    }

    private SkillJobNotification enqueue(SkillJob job, SkillDependencyMetric metric,
                                         SkillJobExecution execution, String filePath,
                                         String triggerType, String requestType) {
        String contentType = (metric != null && metric.getNotifyContentType() != null && !metric.getNotifyContentType().isBlank())
                ? metric.getNotifyContentType().toUpperCase() : "HTML";
        // 模板为空时回退到内置默认模板（HTML 链接式，避免邮件丢图表；TEXT 同理只给地址）。
        String template = metric != null ? metric.getNotifyContentTemplate() : null;
        String fileUrl = buildFileUrl(execution.getId());
        String content = render(template, contentType, fileUrl, job, metric, execution, filePath);
        String fileName = fileNameOf(filePath);
        // 收件人:配置了名单则整名单一次批量发送(toUserList),未配置兜底发创建人
        List<String> receivers = receiversOf(job);
        NotificationPayload payload = new NotificationPayload(
                contentType, content, filePath, fileName, fileUrl,
                job.getId(), job.getName(),
                metric != null ? metric.getCode() : null,
                metric != null ? metric.getName() : null,
                execution.getId(), execution.getStatus(), LocalDateTime.now(), receivers,
                triggerType);
        SkillJobNotification notification = SkillJobNotification.builder()
                .jobId(job.getId())
                .executionId(execution.getId())
                .requestType(requestType)
                .status("PENDING")
                .triggerType(triggerType)
                .senderName(sender.getClass().getSimpleName())
                .recipientSummary(String.join(",", payload.userIdList()))
                .contentType(contentType)
                .content(content)
                .fileName(fileName)
                .fileUrl(fileUrl)
                .requestedAt(LocalDateTime.now())
                .build();
        jobMapper.insertNotification(notification);
        executor.submit(() -> doSend(notification, payload));
        return notification;
    }

    /** 收件人解析:配置了名单(剔除人员表已失效的工号)则整名单一次批量发送;创建人始终合并在内,无需加入名单。 */
    private List<String> receiversOf(SkillJob job) {
        List<String> configured = orgService.filterExistingUserIds(
                NotificationReceivers.parse(job.getNotifyReceivers()));
        List<String> receivers = new ArrayList<>(configured);
        if (job.getCreatedBy() != null && !receivers.contains(job.getCreatedBy())) {
            receivers.add(job.getCreatedBy());
        }
        return receivers;
    }

    private void recordSkipped(SkillJob job, SkillJobExecution execution, String filePath,
                               String triggerType, String reason) {
        try {
            LocalDateTime now = LocalDateTime.now();
            SkillJobNotification notification = SkillJobNotification.builder()
                    .jobId(job.getId())
                    .executionId(execution.getId())
                    .requestType("INITIAL")
                    .status("SKIPPED")
                    .triggerType(triggerType)
                    .senderName(sender.getClass().getSimpleName())
                    .recipientSummary(job.getCreatedBy())
                    .fileName(fileNameOf(filePath))
                    .errorMsg(reason)
                    .requestedAt(now)
                    .completedAt(now)
                    .build();
            jobMapper.insertNotification(notification);
            log.info("[Notification] skipped: job={}, exec={}, reason={}",
                    job.getId(), execution.getId(), reason);
        } catch (Exception e) {
            log.warn("[Notification] could not persist skipped delivery for job {}: {}",
                    job.getId(), e.getMessage(), e);
        }
    }

    private void doSend(SkillJobNotification notification, NotificationPayload payload) {
        LocalDateTime startedAt = LocalDateTime.now();
        try {
            jobMapper.markNotificationSending(notification.getId(), startedAt);
        } catch (Exception e) {
            log.error("[Notification] aborting untracked send because notification {} could not enter SENDING",
                    notification.getId(), e);
            return;
        }
        try {
            sender.send(payload);
        } catch (Exception e) {
            LocalDateTime completedAt = LocalDateTime.now();
            try {
                jobMapper.completeNotification(notification.getId(), "FAILED", errorMessage(e), completedAt);
            } catch (Exception recordError) {
                log.error("[Notification] could not persist failure for notification {}", notification.getId(), recordError);
            }
            log.warn("[Notification] send failed: notificationId={}, job={}, exec={}, error={}",
                    notification.getId(), payload.jobId(), payload.executionId(), e.getMessage(), e);
            return;
        }
        LocalDateTime completedAt = LocalDateTime.now();
        try {
            jobMapper.completeNotification(notification.getId(), "SUCCESS", null, completedAt);
        } catch (Exception e) {
            // The HTTP call already succeeded. Keep SENDING rather than writing a false FAILED result.
            log.error("[Notification] sent but could not persist SUCCESS for notification {}",
                    notification.getId(), e);
        }
        log.info("[Notification] sent: notificationId={}, job={}, exec={}, requestType={}, file={}",
                notification.getId(), payload.jobName(), payload.executionId(),
                notification.getRequestType(), payload.fileName());
    }

    private static String errorMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName()
                : e.getClass().getSimpleName() + ": " + message;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    /**
     * 渲染通知正文。邮件客户端会剥离 {@code <script>}，报告里的 ECharts 图表必然丢失，
     * 因此默认模板（同 {@code FlowCompletionService} 的 flow 通知）只放摘要+报告地址，不嵌报告全文。
     * admin 配置的自定义模板仍按占位符替换；HTML 类型下所有注入值做转义，仅 {file_link} 保留自身标签。
     */
    private String render(String template, String contentType, String fileUrl, SkillJob job, SkillDependencyMetric metric,
                          SkillJobExecution execution, String filePath) {
        boolean html = "HTML".equals(contentType);
        String fileName = fileNameOf(filePath);
        String fileLink = html
                ? "<a href=\"" + escapeHtml(fileUrl) + "\">" + escapeHtml(fileName) + "</a>"
                : fileUrl;
        if (template == null || template.isBlank()) {
            template = html ? DEFAULT_HTML_TEMPLATE : DEFAULT_TEXT_TEMPLATE;
        }
        String metricName = metric != null ? nullSafe(metric.getName()) : "";
        String metricCode = metric != null ? nullSafe(metric.getCode()) : "";
        String jobName = nullSafe(job.getName());
        String status = nullSafe(execution.getStatus());
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String path = nullSafe(filePath);
        if (html) {
            metricName = escapeHtml(metricName);
            metricCode = escapeHtml(metricCode);
            jobName = escapeHtml(jobName);
            status = escapeHtml(status);
            date = escapeHtml(date);
            path = escapeHtml(path);
            fileUrl = escapeHtml(fileUrl);
        }
        return template
                .replace("{metric_name}", metricName)
                .replace("{metric_code}", metricCode)
                .replace("{job_name}", jobName)
                .replace("{status}", status)
                .replace("{date}", date)
                .replace("{file_name}", html ? escapeHtml(fileName) : fileName)
                .replace("{file_path}", path)
                .replace("{file_url}", fileUrl)
                .replace("{file_link}", fileLink);
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    /**
     * 构建报告下载链接：用 {@link UrlShortenerService} 给本次执行生成 16 位 BASE62 短码
     * （shortCode 即访问凭据，不可枚举），链接形如 {baseUrl}/api/skill-jobs/download?shortCode=xxx。
     * shortCode -> "skilljob-exec:{execId}" 存入 url_shortener 表，
     * 由 {@code SkillJobController.downloadByShortCode} 解析后定位文件。
     * downloadBaseUrl 为空则输出相对路径，由接入方/前端补主机。
     */
    private String buildFileUrl(Long executionId) {
        if (executionId == null) {
            return "";
        }
        String shortCode = urlShortenerService.shorten("skilljob-exec:" + executionId);
        if (shortCode == null) {
            // 短链入库失败：退回 execId 相对路径兜底（该端点需 X-User-Id 头，邮件外链场景不可用，仅保字段非空）
            log.warn("shorten failed for execution {}, fallback to relative execId path", executionId);
            return "/api/skill-jobs/executions/" + executionId + "/download";
        }
        String path = "/api/skill-jobs/download?shortCode=" + shortCode;
        if (downloadBaseUrl == null || downloadBaseUrl.isBlank()) {
            // 邮件客户端无法解析相对路径：未配置域名时通知里的链接点不开，这里显式告警提示配置
            log.warn("harness.a2a.csv-download.base-url 未配置，通知中的报告链接为相对路径({})，邮件收件人无法直接打开", path);
            return path;
        }
        return stripTrailingSlash(downloadBaseUrl) + path;
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String fileNameOf(String filePath) {
        if (filePath == null) {
            return "";
        }
        int idx = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
        return idx >= 0 ? filePath.substring(idx + 1) : filePath;
    }

    private static String nullSafe(String s) {
        return s != null ? s : "";
    }
}
