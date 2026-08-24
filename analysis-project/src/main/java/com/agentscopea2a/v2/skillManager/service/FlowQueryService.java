package com.agentscopea2a.v2.skillManager.service;

import com.agentscopea2a.v2.config.SkillStorageProperties;
import com.agentscopea2a.v2.skillManager.entity.*;
import com.agentscopea2a.v2.skillManager.mapper.SkillDependencyMetricMapper;
import com.agentscopea2a.v2.skillManager.mapper.SkillFlowMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Skill Flow 执行记录查询服务(读侧):
 * 列表/详情/节点明细/指标就绪情况/通知记录/HTML 报告下载。
 * 执行记录仅触发用户本人可见({@link #owned} 统一校验)。
 */
@Service
public class FlowQueryService {

    private final SkillFlowMapper mapper;
    private final SkillDependencyMetricMapper metrics;
    private final ObjectMapper json;
    private final FlowSummaryPromptRenderer promptRenderer;
    private final FlowTemplateEngine templates = new FlowTemplateEngine();
    /** 报告根目录(${skill.job.base-dir}),用于报告文件定位与越权防护。 */
    private final Path reportRoot;

    public FlowQueryService(SkillFlowMapper mapper, SkillDependencyMetricMapper metrics,
                            ObjectMapper json, SkillStorageProperties storage,
                            FlowSummaryPromptRenderer promptRenderer) {
        this.mapper = mapper;
        this.metrics = metrics;
        this.json = json;
        this.promptRenderer = promptRenderer;
        this.reportRoot = Paths.get(storage.getJobReportDir()).normalize().toAbsolutePath();
    }

    /** 执行列表(仅本人触发),status/createdBy 可选过滤。 */
    public List<ExecutionDto> list(String status, String createdBy, String userId) {
        return mapper.selectExecutions(status, createdBy, userId).stream().map(this::dto).toList();
    }

    /** 执行详情。 */
    public ExecutionDto get(Long id, String userId) {
        return dto(owned(id, userId));
    }

    /** 节点执行明细(含每次尝试的 audit 记录)。 */
    public List<NodeDto> nodes(Long id, String userId) {
        SkillFlowExecution execution = owned(id, userId);
        return mapper.selectNodeExecutions(id).stream()
                .map(n -> new NodeDto(n.getId(), n.getNodeKey(), n.getSkillName(), n.getQuestionTemplateSnapshot(),
                        renderedQuestion(execution, n), Boolean.TRUE.equals(n.getRequired()),
                        n.getStatus().name(), n.getAttemptCount(), n.getMaxAttempts(), n.getErrorCode(),
                        n.getErrorMessage(), n.getStartedAt(), n.getCompletedAt(), mapper.selectAttempts(n.getId())))
                .toList();
    }

    /** 该次执行依赖的指标就绪情况:哪些指标就绪/未就绪、分别影响哪些节点。 */
    public List<MetricDto> readiness(Long id, String userId) {
        SkillFlowExecution e = owned(id, userId);
        // 指标 -> 受影响节点列表
        Map<Long, List<String>> affected = new LinkedHashMap<>();
        for (SkillFlowNode node : mapper.selectNodesByFlowId(e.getFlowId())) {
            for (Long metricId : mapper.selectMetricIdsByNodeId(node.getId())) {
                affected.computeIfAbsent(metricId, k -> new ArrayList<>()).add(node.getNodeKey());
            }
        }
        return affected.entrySet().stream().map(entry -> {
            SkillDependencyMetric metric = metrics.selectById(entry.getKey());
            SkillMetricReadiness ready = mapper.selectMetricReadiness(entry.getKey(), e.getDataDate());
            return new MetricDto(entry.getKey(), metric == null ? null : metric.getCode(),
                    metric == null ? null : metric.getName(),
                    ready == null ? "NOT_READY" : ready.getStatus().name(),
                    ready == null ? null : ready.getReadyAt(), entry.getValue());
        }).toList();
    }

    /** 该次执行的通知发送记录。 */
    public List<SkillFlowNotification> notifications(Long id, String userId) {
        owned(id, userId);
        return mapper.selectNotifications(id);
    }

    /** 读取 HTML 报告文件:解析后强制限制在 本人目录 内,防路径穿越读他人文件。 */
    public Resource report(Long id, String userId) {
        SkillFlowExecution e = owned(id, userId);
        if (e.getReportPath() == null || e.getReportPath().isBlank()) {
            throw new IllegalStateException("FlowReportNotFound: " + id);
        }
        Path expectedUserRoot = reportRoot.resolve(userId).normalize();
        Path report = reportRoot.resolve(e.getReportPath()).normalize().toAbsolutePath();
        if (!report.startsWith(expectedUserRoot) || !Files.isRegularFile(report)) {
            throw new IllegalStateException("FlowReportNotFound: " + id);
        }
        return new FileSystemResource(report);
    }

    /** 取执行记录并校验:仅触发用户本人可读。 */
    private SkillFlowExecution owned(Long id, String userId) {
        SkillFlowExecution e = mapper.selectFlowExecutionById(id);
        if (e == null) throw new IllegalStateException("FlowExecutionNotFound: " + id);
        if (!Objects.equals(userId, e.getTriggerUserId())) throw new IllegalStateException("FlowAccessDenied: " + id);
        return e;
    }

    private ExecutionDto dto(SkillFlowExecution e) {
        List<SkillFlowNodeExecution> nodes = mapper.selectNodeExecutions(e.getId());
        return new ExecutionDto(e.getId(), e.getFlowId(), e.getFlowName(), e.getFlowCode(), e.getStatus().name(),
                e.getTriggerUserId(), e.getOriginalQuestion(), e.getDataDate(), e.getRequiredMetricCount(),
                e.getReadyMetricCount(), nodes.size(), (int) nodes.stream().filter(n -> n.getStatus().terminal()).count(),
                e.getSummaryQuestionTemplateSnapshot(), renderedSummaryQuestion(e, nodes),
                readJson(e.getSummaryJson()), e.getReportPath(),
                e.getCreatedAt(), e.getStartedAt(), e.getCompletedAt());
    }

    /**
     * 实际汇总提问:优先取落库值(汇总阶段渲染持久化);
     * 老数据没有落库值时,终态执行按节点结果现场重渲染一次,变量缺失等渲染失败返回 null。
     */
    private String renderedSummaryQuestion(SkillFlowExecution execution, List<SkillFlowNodeExecution> nodes) {
        if (execution.getRenderedSummaryQuestion() != null) return execution.getRenderedSummaryQuestion();
        if (!execution.getStatus().terminal()) return null; // 未到汇总阶段,尚无实际提问
        try {
            return promptRenderer.render(execution, nodes);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 节点实际提问:优先取落库值(尝试完成时持久化);
     * 历史数据/尚未执行的节点没有落库值时,按快照模板现场渲染(与执行时格式一致),渲染失败返回 null。
     */
    private String renderedQuestion(SkillFlowExecution execution, SkillFlowNodeExecution node) {
        if (node.getRenderedQuestion() != null) return node.getRenderedQuestion();
        try {
            String rendered = templates.render(node.getQuestionTemplateSnapshot(),
                    new FlowTemplateEngine.Context(Map.of(
                            "server_date", execution.getDataDate().toString(),
                            "original_question", Objects.toString(execution.getOriginalQuestion(), ""),
                            "flow_name", execution.getFlowName(),
                            "skill_name", Objects.toString(node.getSkillName(), ""))));
            return FlowCoordinator.buildPrompt(node, rendered);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Object readJson(String value) {
        if (value == null) return null;
        try {
            return json.readValue(value, new TypeReference<>() {});
        } catch (Exception ignored) {
            return value;
        }
    }

    /** 执行记录列表/详情返回体。 */
    public record ExecutionDto(Long id, Long flowId, String flowName, String flowCode, String status,
                               String triggerUserId, String originalQuestion, LocalDate dataDate,
                               Integer requiredMetricCount, Integer readyMetricCount,
                               Integer totalNodeCount, Integer completedNodeCount,
                               String summaryQuestionTemplateSnapshot, String renderedSummaryQuestion,
                               Object summaryJson, String reportPath,
                               LocalDateTime createdAt, LocalDateTime startedAt, LocalDateTime completedAt) {}

    /** 节点执行明细返回体(attempts 为每次尝试的审计记录;节点全并行,无依赖)。 */
    public record NodeDto(Long id, String nodeKey, String skillName, String questionTemplateSnapshot,
                          String renderedQuestion, boolean required, String status,
                          Integer attemptCount, Integer maxAttempts, String errorCode, String errorMessage,
                          LocalDateTime startedAt, LocalDateTime completedAt, List<SkillFlowNodeAttempt> attempts) {}

    /** 指标就绪返回体:affectedSkills 为依赖该指标的节点 key 列表。 */
    public record MetricDto(Long metricId, String metricCode, String metricName, String status,
                            LocalDateTime readyAt, List<String> affectedSkills) {}
}
