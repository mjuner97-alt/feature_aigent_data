package com.agentscopea2a.v2.skillManager.service;

import com.agentscopea2a.v2.config.SkillStorageProperties;
import com.agentscopea2a.v2.runner.HarnessA2aRunnerV2;
import com.agentscopea2a.v2.skillManager.entity.*;
import com.agentscopea2a.v2.skillManager.mapper.SkillFlowMapper;
import com.agentscopea2a.v2.skillManager.notification.NotificationPayload;
import com.agentscopea2a.v2.skillManager.notification.NotificationSender;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Skill Flow 收尾服务:流程全部节点到达终态后做两件事——
 * <ol>
 *   <li>{@link #summarize}:用汇总模板把各节点结果交给 AI 生成总结,渲染成自包含 HTML 报告落盘;</li>
 *   <li>{@link #sendInitial} / {@link #resend}:按执行快照(notifyEnabledSnapshot)给触发用户发完成通知。</li>
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
    /** 报告根目录(${skill.job.base-dir}),报告以 用户目录/flow-{id}-report.html 存放。 */
    private final Path reportRoot;

    public FlowCompletionService(HarnessA2aRunnerV2 runner, ObjectMapper json, HtmlReportRenderer renderer,
                                 SkillFlowMapper mapper, NotificationSender sender,
                                 @Qualifier("skillFlowClock") Clock skillFlowClock,
                                 SkillStorageProperties storage, FlowSummaryPromptRenderer promptRenderer) {
        this.runner = runner;
        this.json = json;
        this.renderer = renderer;
        this.mapper = mapper;
        this.sender = sender;
        this.clock = skillFlowClock;
        this.promptRenderer = promptRenderer;
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
            Path relative = Paths.get(flow.getTriggerUserId(), "flow-" + flow.getId() + "-report.html");
            Path target = reportRoot.resolve(relative).normalize();
            if (!target.startsWith(reportRoot)) throw new IllegalStateException("invalid report path");
            Path parent = target.getParent();
            if (parent != null && Files.notExists(parent)) {
                Files.createDirectories(parent);
            }
            Files.writeString(target, renderer.render(text, flow.getFlowName()), StandardCharsets.UTF_8);
            return new Summary(json.writeValueAsString(Map.of("results", nodes.stream()
                    .map(n -> Map.of("nodeKey", Objects.toString(n.getNodeKey(), ""),
                            "skillName", Objects.toString(n.getSkillName(), ""),
                            "status", n.getStatus().name(),
                            "result", Objects.toString(n.getResultJson(), ""))).toList())),
                    relative.toString().replace('\\', '/'));
        } catch (Exception e) {
            throw new IllegalStateException("FlowSummaryFailed: " + e.getMessage(), e);
        }
    }

    private String orderedReportText(List<SkillFlowNodeExecution> nodes) {
        StringBuilder report = new StringBuilder();
        for (int index = 0; index < nodes.size(); index++) {
            SkillFlowNodeExecution node = nodes.get(index);
            report.append("## ").append(index + 1).append(". ")
                    .append(Objects.toString(node.getSkillName(), node.getNodeKey())).append('\n');
            report.append("状态：").append(node.getStatus().name()).append("\n\n");
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

    /** 执行完成后的首次通知:deliveryKey 固定为 flow:{id}:INITIAL,借唯一索引天然幂等(重试不重发)。 */
    public void sendInitial(SkillFlowExecution execution) {
        if (!Boolean.TRUE.equals(execution.getNotifyEnabledSnapshot()) || execution.getStatus() == FlowExecutionStatus.CANCELLED) return;
        send(execution, "flow:" + execution.getId() + ":INITIAL");
    }

    /** 手动重发通知:仅终态(且非取消)的执行可用。 */
    public void resend(SkillFlowExecution execution) {
        if (!Boolean.TRUE.equals(execution.getNotifyEnabledSnapshot())
                || !execution.getStatus().terminal()
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
            sender.send(new NotificationPayload("HTML", Objects.toString(execution.getSummaryJson(), ""),
                    filePath, execution.getReportPath() == null ? "" : Paths.get(execution.getReportPath()).getFileName().toString(),
                    "", execution.getFlowId(), execution.getFlowName(), null, null,
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
