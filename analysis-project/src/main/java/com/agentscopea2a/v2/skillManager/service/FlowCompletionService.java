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

    private final HarnessA2aRunnerV2 runner;
    private final ObjectMapper json;
    private final HtmlReportRenderer renderer;
    private final SkillFlowMapper mapper;
    private final NotificationSender sender;
    private final Clock clock;
    /** 报告根目录(${skill.job.base-dir}),报告以 用户目录/flow-{id}-report.html 存放。 */
    private final Path reportRoot;
    private final FlowTemplateEngine templates = new FlowTemplateEngine();

    public FlowCompletionService(HarnessA2aRunnerV2 runner, ObjectMapper json, HtmlReportRenderer renderer,
                                 SkillFlowMapper mapper, NotificationSender sender,
                                 @Qualifier("skillFlowClock") Clock skillFlowClock,
                                 SkillStorageProperties storage) {
        this.runner = runner;
        this.json = json;
        this.renderer = renderer;
        this.mapper = mapper;
        this.sender = sender;
        this.clock = skillFlowClock;
        this.reportRoot = Paths.get(storage.getJobReportDir()).normalize().toAbsolutePath();
    }

    /** 汇总结果:summaryJson 入库,reportPath 为报告文件相对路径。 */
    public record Summary(String summaryJson, String reportPath) {}

    /**
     * 生成流程汇总:用汇总模板渲染问题 -> 调 AI 汇总 -> 渲染 HTML 报告写入报告目录。
     * AI 汇总失败时抛出 IllegalStateException,由调用方决定降级策略。
     */
    public Summary summarize(SkillFlowExecution flow, List<SkillFlowNodeExecution> nodes) {
        try {
            String all = json.writeValueAsString(nodes.stream().map(n -> Map.of(
                    "nodeKey", n.getNodeKey(), "skill", n.getSkillName(),
                    "status", n.getStatus().name(), "result", Objects.toString(n.getResultJson(), ""),
                    "error", Objects.toString(n.getErrorMessage(), ""))).toList());
            String prompt = templates.render(flow.getSummaryQuestionTemplateSnapshot(),
                    new FlowTemplateEngine.Context(Map.of(
                            "server_date", flow.getDataDate().toString(),
                            "original_question", flow.getOriginalQuestion(),
                            "flow_name", flow.getFlowName(),
                            "all_results", all)));
            RuntimeContext context = RuntimeContext.builder()
                    .sessionId("flow-" + flow.getId() + "-summary").userId(flow.getTriggerUserId()).build();
            List<AgentEvent> events = runner.streamEvents(List.of(Msg.builder()
                            .role(MsgRole.USER).content(TextBlock.builder().text(prompt).build()).build()),
                    context).collectList().block(Duration.ofMinutes(10));
            String text = extract(events);
            if (text == null || text.isBlank()) text = all;
            // 报告落在触发用户的目录下,防止越权读取其它用户文件
            Path relative = Paths.get(flow.getTriggerUserId(), "flow-" + flow.getId() + "-report.html");
            Path target = reportRoot.resolve(relative).normalize();
            if (!target.startsWith(reportRoot)) throw new IllegalStateException("invalid report path");
            Files.createDirectories(target.getParent());
            Files.writeString(target, renderer.render(text, flow.getFlowName()), StandardCharsets.UTF_8);
            return new Summary(json.writeValueAsString(Map.of("text", text)), relative.toString().replace('\\', '/'));
        } catch (Exception e) {
            throw new IllegalStateException("FlowSummaryFailed: " + e.getMessage(), e);
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
