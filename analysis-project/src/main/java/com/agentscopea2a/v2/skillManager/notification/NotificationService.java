package com.agentscopea2a.v2.skillManager.notification;

import com.agentscopea2a.v2.skillManager.entity.SkillDependencyMetric;
import com.agentscopea2a.v2.skillManager.entity.SkillJob;
import com.agentscopea2a.v2.skillManager.entity.SkillJobExecution;
import com.agentscopea2a.v2.skillManager.mapper.SkillDependencyMetricMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
// 暂未引入 NotificationSender：以下注解先注释，避免启动时注册 bean；重新引入时取消注释即可。
// @Service
// @ConditionalOnProperty(prefix = "harness.a2a.skill-job", name = "enabled", havingValue = "true", matchIfMissing = true)
public class NotificationService {
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    /** 内置默认 HTML 模板（notify_content_template 为空且 type=HTML 时使用） */
    private static final String DEFAULT_HTML_TEMPLATE = """
            <div>
              <h3>指标分析报告已生成</h3>
              <table>
                <tr><td>依赖指标</td><td>{metric_name}（{metric_code}）</td></tr>
                <tr><td>任务名称</td><td>{job_name}</td></tr>
                <tr><td>执行记录</td><td>#{execution_id}（{status}）</td></tr>
                <tr><td>生成时间</td><td>{date}</td></tr>
                <tr><td>报告文件</td><td>{file_name}</td></tr>
                <tr><td>文件路径</td><td>{file_path}</td></tr>
              </table>
            </div>""";

    /** 内置默认纯文本模板（notify_content_template 为空且 type=TEXT 时使用） */
    private static final String DEFAULT_TEXT_TEMPLATE = """
            【指标分析报告已生成】
            依赖指标：{metric_name}（{metric_code}）
            任务名称：{job_name}
            执行记录：#{execution_id}（{status}）
            生成时间：{date}
            报告文件：{file_name}
            文件路径：{file_path}""";

    private final SkillDependencyMetricMapper metricMapper;
    private final NotificationSender sender;

    /** 通知专用单线程：避免外部系统调用阻塞 job 执行线程 */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "skill-job-notify");
        t.setDaemon(true);
        return t;
    });

    public NotificationService(SkillDependencyMetricMapper metricMapper, NotificationSender sender) {
        this.metricMapper = metricMapper;
        this.sender = sender;
    }

    /**
     * 跑批 job 执行成功后调用：查所属指标的 notify 配置，启用则异步组装并发送通知（携带 MD 文件）。
     */
    public void notifyJobCompleted(SkillJob job, SkillJobExecution execution, String filePath) {
        if (job == null || job.getMetricId() == null || execution == null || filePath == null) {
            return;
        }
        SkillDependencyMetric metric = metricMapper.selectById(job.getMetricId());
        if (metric == null || !Boolean.TRUE.equals(metric.getNotifyEnabled())) {
            return;
        }
        executor.submit(() -> doSend(job, metric, execution, filePath));
    }

    private void doSend(SkillJob job, SkillDependencyMetric metric, SkillJobExecution execution, String filePath) {
        try {
            String contentType = (metric.getNotifyContentType() != null && !metric.getNotifyContentType().isBlank())
                    ? metric.getNotifyContentType().toUpperCase() : "HTML";
            String template = (metric.getNotifyContentTemplate() != null && !metric.getNotifyContentTemplate().isBlank())
                    ? metric.getNotifyContentTemplate()
                    : defaultTemplate(contentType);
            String content = render(template, job, metric, execution, filePath);
            String fileName = fileNameOf(filePath);
            NotificationPayload payload = new NotificationPayload(
                    contentType, content, filePath, fileName,
                    job.getId(), job.getName(), metric.getCode(), metric.getName(),
                    execution.getId(), execution.getStatus(), LocalDateTime.now());
            sender.send(payload);
            log.info("[Notification] sent: metric={}, job={}, exec={}, file={}",
                    metric.getCode(), job.getName(), execution.getId(), fileName);
        } catch (Exception e) {
            log.warn("[Notification] send failed for job {} (ignored): {}", job.getId(), e.getMessage(), e);
        }
    }

    private String defaultTemplate(String contentType) {
        return "TEXT".equals(contentType) ? DEFAULT_TEXT_TEMPLATE : DEFAULT_HTML_TEMPLATE;
    }

    private String render(String template, SkillJob job, SkillDependencyMetric metric,
                          SkillJobExecution execution, String filePath) {
        return template
                .replace("{metric_name}", nullSafe(metric.getName()))
                .replace("{metric_code}", nullSafe(metric.getCode()))
                .replace("{job_name}", nullSafe(job.getName()))
                .replace("{execution_id}", String.valueOf(execution.getId()))
                .replace("{status}", nullSafe(execution.getStatus()))
                .replace("{date}", LocalDate.now().toString())
                .replace("{file_name}", fileNameOf(filePath))
                .replace("{file_path}", filePath);
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
