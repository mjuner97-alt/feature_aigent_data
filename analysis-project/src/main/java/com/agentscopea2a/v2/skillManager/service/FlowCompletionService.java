package com.agentscopea2a.v2.skillManager.service;

import com.agentscopea2a.v2.config.SkillStorageProperties;
import com.agentscopea2a.v2.runner.HarnessA2aRunnerV2;
import com.agentscopea2a.v2.skillManager.entity.*;
import com.agentscopea2a.v2.skillManager.mapper.SkillFlowMapper;
import com.agentscopea2a.v2.skillManager.notification.NotificationPayload;
import com.agentscopea2a.v2.skillManager.notification.NotificationSender;
import com.agentscopea2a.v2.skillManager.report.FlowReportStorage;
import com.agentscopea2a.v2.skillManager.report.HtmlReportRenderer;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Skill Flow 收尾服务:流程全部节点到达终态后做两件事——
 * <ol>
 *   <li>{@link #summarize}:用汇总模板把各节点结果交给 AI 生成总结,渲染成自包含 HTML 报告落盘;</li>
 *   <li>{@link #sendInitial} / {@link #resend}:给本次执行的通知对象发完成通知。</li>
 * </ol>
 */
@Service
public class FlowCompletionService {

    private static final Logger log = LoggerFactory.getLogger(FlowCompletionService.class);

    /** 汇总 AI 调用最大尝试次数(首次 + 2 次重试),可重试错误(超时/429/5xx 等)按 2s/4s 退避。 */
    private static final int SUMMARY_MAX_ATTEMPTS = 3;

    private final HarnessA2aRunnerV2 runner;
    private final ObjectMapper json;
    private final HtmlReportRenderer renderer;
    private final SkillFlowMapper mapper;
    private final NotificationSender sender;
    private final Clock clock;
    private final FlowSummaryPromptRenderer promptRenderer;
    private final FlowReportStorage reportStorage;
    @Value("${harness.a2a.csv-download.base-url:}")
    private String reportBaseUrl;
    /** 报告根目录(${skill.job.base-dir}),报告以 用户目录/flow-{id}-report.html 存放。 */
    private final Path reportRoot;

    public FlowCompletionService(HarnessA2aRunnerV2 runner, ObjectMapper json, HtmlReportRenderer renderer,
                                 SkillFlowMapper mapper, NotificationSender sender,
                                 @Qualifier("skillFlowClock") Clock skillFlowClock,
                                 SkillStorageProperties storage, FlowSummaryPromptRenderer promptRenderer,
                                 FlowReportStorage reportStorage) {
        this.runner = runner;
        this.json = json;
        this.renderer = renderer;
        this.mapper = mapper;
        this.sender = sender;
        this.clock = skillFlowClock;
        this.promptRenderer = promptRenderer;
        this.reportStorage = reportStorage;
        this.reportRoot = Paths.get(storage.getJobReportDir()).normalize().toAbsolutePath();
    }

    /** 汇总结果:summaryJson 入库,reportPath 为报告文件相对路径。 */
    public record Summary(String summaryJson, String reportPath) {}

    /** 按节点配置顺序拼接结果并生成 HTML 报告，不调用汇总模型。 */
    public Summary summarize(SkillFlowExecution flow, List<SkillFlowNodeExecution> nodes) {
        try {
            String text = orderedReportText(nodes);
            flow.setRenderedSummaryQuestion(null);
            mapper.updateExecution(flow);
            List<Map<String, String>> results = nodes.stream()
                    .map(n -> Map.of("nodeKey", Objects.toString(n.getNodeKey(), ""),
                            "nodeName", n.getNodeName() == null || n.getNodeName().isBlank() ? Objects.toString(n.getSkillName(), "") : n.getNodeName(),
                            "skillName", Objects.toString(n.getSkillName(), ""),
                            "status", n.getStatus() == null ? "UNKNOWN" : n.getStatus().name(),
                            "result", Objects.toString(n.getResultJson(), ""))).toList();
            try {
                String reportPath = reportStorage.write(flow.getTriggerUserId(), flow.getId(),
                        renderer.render(text, Objects.toString(flow.getFlowName(), "长任务报告")));
                return new Summary(json.writeValueAsString(Map.of("results", results)), reportPath);
            } catch (FlowReportStorage.ReportStorageException e) {
                // 报告属于收尾附件，磁盘不足不能反向把已经成功的节点和流程改成失败。
                // 节点结果仍保存在 summaryJson，释放空间后可通过“重新生成汇总”补建报告。
                log.error("Flow report persistence failed: code={}, flowId={}, reason={}",
                        e.code(), flow.getId(), e.getMessage(), e);
                Map<String, Object> degraded = new LinkedHashMap<>();
                degraded.put("results", results);
                degraded.put("reportError", Map.of("code", e.code(),
                        "message", Objects.toString(e.getMessage(), "报告写入失败")));
                return new Summary(json.writeValueAsString(degraded), null);
            }
        } catch (Exception e) {
            throw new IllegalStateException("FlowSummaryFailed: "
                    + Objects.toString(e.getMessage(), e.getClass().getSimpleName()), e);
        }
    }

    private String orderedReportText(List<SkillFlowNodeExecution> nodes) {
        StringBuilder report = new StringBuilder();
        for (int index = 0; index < nodes.size(); index++) {
            SkillFlowNodeExecution node = nodes.get(index);
            report.append("## ").append(index + 1).append(". ")
                    .append(node.getNodeName() == null || node.getNodeName().isBlank()
                            ? Objects.toString(node.getSkillName(), node.getNodeKey()) : node.getNodeName()).append('\n');
            report.append(extractResultText(node.getResultJson())).append("\n\n");
        }
        return report.toString();
    }

    /** 节点结果以 {"text": "..."} 保存，报告只渲染 text，避免把内部 JSON 暴露给用户。 */
    private String extractResultText(String resultJson) {
        if (resultJson == null || resultJson.isBlank()) return "无结果";
        try {
            var root = json.readTree(resultJson);
            var text = root == null ? null : root.get("text");
            return text == null || text.isNull() ? resultJson : text.asText();
        } catch (Exception ignored) {
            return resultJson;
        }
    }

    /**
     * 执行完成后的首次通知:所有触发类型均通知其对应用户。
     * CHAT 通知对话触发人, MANUAL 通知点击执行的人, AUTO_METRIC 通知流程创建人
     * （三类场景的接收人均已在 triggerUserId 中快照）。deliveryKey 固定为
     * flow:{id}:INITIAL,借唯一索引天然幂等(复用执行记录或重试均不重复通知)。
     */
    public void sendInitial(SkillFlowExecution execution) {
        if (execution.getStatus() == FlowExecutionStatus.CANCELLED) return;
        send(execution, "flow:" + execution.getId() + ":INITIAL");
    }

    /** 手动重发通知:仅终态(且非取消)的执行可用。 */
    public void resend(SkillFlowExecution execution) {
        if (!execution.getStatus().terminal()
                || execution.getStatus() == FlowExecutionStatus.CANCELLED) {
            throw new IllegalStateException("FlowNotificationResendUnavailable: " + execution.getId());
        }
        send(execution, "flow:" + execution.getId() + ":RESEND:" + UUID.randomUUID());
    }

    /** 落通知记录 -> 真正发送 -> 回写结果状态;deliveryKey 重复(首次已发过)则直接跳过。 */
    private void send(SkillFlowExecution execution, String key) {
        SkillFlowNotification record = SkillFlowNotification.builder()
                .flowExecutionId(execution.getId()).deliveryKey(key)
                .status(FlowNotificationStatus.PENDING)
                .recipient(execution.getTriggerUserId()).channel("DEFAULT")
                .requestJson(execution.getSummaryJson()).build();
        try {
            mapper.insertNotification(record);
        } catch (DuplicateKeyException ignored) {
            return;
        }
        try {
            String filePath = resolveReportPath(execution);
            sender.send(new NotificationPayload("HTML", notificationHtml(execution, filePath),
                    filePath, reportFileName(execution),
                    reportUrl(execution), execution.getFlowId(), execution.getFlowName(), null, null,
                    execution.getId(), execution.getStatus().name(), LocalDateTime.now(clock),
                    List.of(execution.getTriggerUserId()), "FLOW"));
            record.setStatus(FlowNotificationStatus.SENT);
            record.setSentAt(LocalDateTime.now(clock));
        } catch (Exception e) {
            record.setStatus(FlowNotificationStatus.FAILED);
            record.setErrorMessage(e.getMessage());
        }
        mapper.updateNotification(record);
    }

    /** 解析报告绝对路径,并限制在报告根目录内(防路径穿越)。 */
    private String resolveReportPath(SkillFlowExecution execution) {
        if (execution.getReportPath() == null || execution.getReportPath().isBlank()) return "";
        Path path = reportRoot.resolve(execution.getReportPath()).normalize().toAbsolutePath();
        if (!path.startsWith(reportRoot)) throw new IllegalStateException("invalid report path");
        return path.toString();
    }

    /** 长任务通知正文只发送报告地址，避免邮件客户端丢失报告中的图片和脚本。 */
    private String notificationHtml(SkillFlowExecution execution, String filePath) {
        String url = reportUrl(execution);
        String title = escapeHtml(Objects.toString(execution.getFlowName(), "长任务报告"));
        if (url.isBlank()) {
            return "<html><body><h3>" + title + "</h3><p>报告地址暂不可用，请登录系统查看。</p></body></html>";
        }
        String escapedUrl = escapeHtml(url);
        return "<html><body><h3>" + title + "</h3>"
                + "<p>长任务已完成，请点击以下地址查看完整报告：</p>"
                + "<p><a href=\"" + escapedUrl + "\">打开长任务报告</a></p></body></html>";
    }

    private String summaryText(SkillFlowExecution execution) {
        String summaryJson = execution.getSummaryJson();
        if (summaryJson == null || summaryJson.isBlank()) {
            return "暂无结果";
        }
        try {
            var root = json.readTree(summaryJson);
            var results = root == null ? null : root.get("results");
            if (results != null && results.isArray()) {
                StringBuilder text = new StringBuilder();
                int index = 1;
                for (var result : results) {
                    text.append("## ").append(index++).append(". ")
                            .append(result.path("nodeName").asText(result.path("skillName").asText("节点结果")))
                            .append("\n\n")
                            .append(extractResultText(result.path("result").asText("")))
                            .append("\n\n");
                }
                if (!text.isEmpty()) {
                    return text.toString();
                }
            }
        } catch (Exception e) {
            log.warn("Parse flow summary failed for notification: executionId={}, reason={}",
                    execution.getId(), e.getMessage());
        }
        return summaryJson;
    }

    private static String reportFileName(SkillFlowExecution execution) {
        return execution.getReportPath() == null ? "" : Paths.get(execution.getReportPath()).getFileName().toString();
    }

    /** 报告查看链接:/api/skill-flow-executions/{id}/report,端点不限制下载人,邮件点击可直接打开。 */
    private String reportUrl(SkillFlowExecution execution) {
        if (execution.getId() == null) {
            return "";
        }
        String path = "/api/skill-flow-executions/" + execution.getId() + "/report";
        if (reportBaseUrl == null || reportBaseUrl.isBlank()) {
            return path;
        }
        return reportBaseUrl.endsWith("/")
                ? reportBaseUrl.substring(0, reportBaseUrl.length() - 1) + path
                : reportBaseUrl + path;
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    /** 从 AI 事件流提取回答文本:优先取最终结果,否则拼接增量 delta。 */
    private String extract(List<AgentEvent> events) {
        if (events == null) return "";
        for (AgentEvent e : events) {
            if (e instanceof AgentResultEvent r && r.getResult() != null) {
                String t = r.getResult().getTextContent();
                if (t != null && !t.isBlank()) return t;
            }
        }
        StringBuilder b = new StringBuilder();
        for (AgentEvent e : events) {
            if (e instanceof TextBlockDeltaEvent d && d.getDelta() != null) b.append(d.getDelta());
        }
        return b.toString();
    }
}
