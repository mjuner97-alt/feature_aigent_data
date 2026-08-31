package com.agentscopea2a.v2.skillManager.service;

import com.agentscopea2a.v2.skillManager.entity.*;
import com.agentscopea2a.v2.skillManager.mapper.SkillFlowMapper;
import com.agentscopea2a.v2.skillManager.mapper.SkillMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Skill Flow 执行生命周期服务:
 * <ul>
     *   <li>{@link #trigger}:对话触发按(用户, 会话, 流程, 数据日期)幂等执行,不等待指标;</li>
 *   <li>{@link #cancelLatest}:取消会话内最近一次执行(用户回复"直接回答"时调用)。</li>
 * </ul>
 * 执行记录会快照流程当时的模板/并发度/通知开关,后续改编排不影响在跑的执行。
 */
@Service
public class FlowExecutionService {

    private final SkillFlowMapper mapper;
    private final SkillMapper skillMapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public FlowExecutionService(SkillFlowMapper mapper, SkillMapper skillMapper,
                                ObjectMapper objectMapper, Clock skillFlowClock,
                                ApplicationEventPublisher events) {
        this.mapper = mapper;
        this.skillMapper = skillMapper;
        this.objectMapper = objectMapper;
        this.clock = skillFlowClock;
        this.events = events;
    }

    /** 触发结果:created=false 表示当日已有同一(用户,会话,流程)的活跃执行,直接复用。 */
    public record TriggerResult(SkillFlowExecution execution, boolean created) {}

    /**
     * 触发一次流程执行:
     * guardKey = 用户:会话:流程:数据日期,唯一索引兜底,同一天同一会话只跑一次;
     * 同时为每个节点生成节点执行记录(无依赖且指标就绪的节点直接 QUEUED,否则 PENDING 等待推进)。
     */
    @Transactional("gaussTransactionManager")
    public TriggerResult trigger(Long flowId, String userId, String conversationId, String question) {
        SkillFlow flow = mapper.selectFlowById(flowId);
        if (flow == null || !Boolean.TRUE.equals(flow.getEnabled())) throw new IllegalStateException("FlowNotFoundOrDisabled: " + flowId);
        LocalDate dataDate = LocalDate.now(clock);
        String guard = userId + ":" + conversationId + ":" + flowId + ":" + dataDate;
        // 对话入口直接执行,不因指标未就绪而阻塞本次请求。
        return createExecution(flow, userId, conversationId, question, dataDate, FlowTriggerType.CHAT, guard, false);
    }

    /** 指标到达后，为依赖该指标且全部日指标已就绪的流程创建每日自动执行。 */
    @Transactional("gaussTransactionManager")
    public void triggerReadyFlows(Long metricId, LocalDate dataDate) {
        for (SkillFlow flow : mapper.selectEnabledFlowsByMetricId(metricId)) {
            if (!SkillJobService.runsOn(flow.getScheduleRules(), dataDate.getDayOfWeek())) continue;
            String conversationId = "auto:" + flow.getId() + ":" + dataDate;
            // 自动指标触发必须等全部依赖指标 READY 后才创建执行。
            createExecution(flow, flow.getCreatedBy(), conversationId, flow.getTaskQuestion(), dataDate,
                    FlowTriggerType.AUTO_METRIC, conversationId, true);
        }
    }

    /**
     * 手动触发(流程列表"执行"按钮):仅 owner;conversationId 独立成 manual 域,
     * 与聊天会话互不干扰;确认执行后不等待指标就绪。
     */
    @Transactional("gaussTransactionManager")
    public TriggerResult triggerManual(Long flowId, String userId) {
        SkillFlow flow = mapper.selectFlowById(flowId);
        if (flow == null || !Boolean.TRUE.equals(flow.getEnabled())) throw new IllegalStateException("FlowNotFoundOrDisabled: " + flowId);
        if (!userId.equals(flow.getCreatedBy())) throw new IllegalStateException("FlowAccessDenied: only the owner may run this flow");
        LocalDate dataDate = LocalDate.now(clock);
        String conversationId = "manual:" + flowId + ":" + dataDate;
        String guard = userId + ":" + conversationId + ":" + flowId + ":" + dataDate;
        // 用户已确认手动执行,直接入队,不等待指标就绪。
        return createExecution(flow, userId, conversationId, flow.getTaskQuestion(), dataDate,
                FlowTriggerType.MANUAL, guard, false);
    }

    private TriggerResult createExecution(SkillFlow flow, String userId, String conversationId, String question,
                                          LocalDate dataDate, FlowTriggerType triggerType, String guard,
                                          boolean requireAllMetrics) {
        SkillFlowExecution existing = mapper.selectActiveExecution(guard);
        if (existing != null) return new TriggerResult(existing, false);
        // 汇总全部节点依赖的指标,逐一检查就绪状态
        List<SkillFlowNode> nodes = mapper.selectNodesByFlowId(flow.getId());
        Set<Long> metrics = new LinkedHashSet<>();
        nodes.forEach(n -> metrics.addAll(mapper.selectMetricIdsByNodeId(n.getId())));
        List<Long> missing = metrics.stream().filter(id -> {
            SkillMetricReadiness ready = mapper.selectMetricReadiness(id, dataDate);
            return ready == null || ready.getStatus() != MetricReadinessStatus.READY;
        }).toList();
        // 只有自动指标触发开启门控; CHAT/MANUAL 即使有缺失指标也继续创建并排队。
        if (requireAllMetrics && !missing.isEmpty()) return new TriggerResult(null, false);
        SkillFlowExecution execution = SkillFlowExecution.builder()
                .flowId(flow.getId()).flowCode(flow.getCode()).flowName(flow.getName())
                .summaryQuestionTemplateSnapshot(flow.getSummaryQuestionTemplate())
                .maxParallelismSnapshot(flow.getMaxParallelism())
                .notifyEnabledSnapshot(flow.getNotifyEnabled()).triggerType(triggerType)
                .triggerUserId(userId).conversationId(conversationId).originalQuestion(question).dataDate(dataDate)
                // 非自动触发直接 QUEUED;自动触发有缺失指标时才进入 WAITING_METRICS。
                .status(!requireAllMetrics || missing.isEmpty()
                        ? FlowExecutionStatus.QUEUED : FlowExecutionStatus.WAITING_METRICS)
                .activeGuardKey(guard).requiredMetricCount(metrics.size())
                .readyMetricCount(metrics.size() - missing.size())
                .missingMetricsJson(json(missing)).build();
        // 普通插入 + 捕获唯一索引冲突:openGauss 的 INSERT ... ON DUPLICATE KEY UPDATE
        // 不支持 RETURNING,无法与 useGeneratedKeys 共用拿回自增 id,故并发兜底改为异常路径。
        try {
            mapper.insertFlowExecution(execution);
        } catch (DuplicateKeyException e) {
            // 唯一索引冲突:并发下别人先插入了,复用对方记录
            return new TriggerResult(mapper.selectActiveExecution(guard), false);
        }
        boolean firstRunnableNode = true;
        for (SkillFlowNode node : nodes) {
            Skill skill = skillMapper.selectById(node.getSkillId());
            String skillName = skill == null ? "Skill #" + node.getSkillId() : skill.getName();
            String retrievalName = skill == null || skill.getRetrievalName() == null || skill.getRetrievalName().isBlank()
                    ? skillName : skill.getRetrievalName();
            FlowNodeExecutionStatus status = !requireAllMetrics || missing.isEmpty()
                    ? (firstRunnableNode ? FlowNodeExecutionStatus.QUEUED : FlowNodeExecutionStatus.PENDING)
                    : FlowNodeExecutionStatus.PENDING;
            if (status == FlowNodeExecutionStatus.QUEUED) firstRunnableNode = false;
            mapper.insertNodeExecution(SkillFlowNodeExecution.builder().flowExecutionId(execution.getId())
                    .nodeKey(node.getNodeKey()).skillId(node.getSkillId()).skillName(skillName).skillRetrievalName(retrievalName)
                    .questionTemplateSnapshot(node.getQuestionTemplate()).dependsOnJson(node.getDependsOnJson())
                    .required(node.getRequired()).status(status).attemptCount(0).maxAttempts(node.getMaxAttempts()).build());
        }
        if (execution.getStatus() == FlowExecutionStatus.QUEUED) {
            events.publishEvent(new FlowQueuedEvent(execution.getId()));
        }
        return new TriggerResult(execution, true);
    }

    /**
     * 指标就绪回调(Skill Job 在外部指标到达时调用):
     * 重新计算所有等待中执行的就绪门控,全部就绪则放行进入执行队列。
     */
    @Transactional("gaussTransactionManager")
    public void metricBecameReady(Long metricId, LocalDate dataDate) {
        for (SkillFlowExecution execution : mapper.selectWaitingExecutions()) {
            if (!dataDate.equals(execution.getDataDate())) continue;
            recomputeGate(execution);
        }
    }

    /** 重算就绪门控:全部指标就绪时,流程置 QUEUED,仅放行第一个节点。 */
    @Transactional("gaussTransactionManager")
    public void recomputeGate(SkillFlowExecution execution) {
        List<Long> missing = readLongList(execution.getMissingMetricsJson()).stream()
                .filter(id -> {
                    SkillMetricReadiness ready = mapper.selectMetricReadiness(id, execution.getDataDate());
                    return ready == null || ready.getStatus() != MetricReadinessStatus.READY;
                }).toList();
        execution.setReadyMetricCount(Math.max(0, execution.getRequiredMetricCount() - missing.size()));
        execution.setMissingMetricsJson(json(missing));
        if (missing.isEmpty() && execution.getStatus() == FlowExecutionStatus.WAITING_METRICS) {
            execution.setStatus(FlowExecutionStatus.QUEUED);
            List<SkillFlowNodeExecution> nodes = mapper.selectNodeExecutions(execution.getId());
            if (!nodes.isEmpty()) {
                nodes.get(0).setStatus(FlowNodeExecutionStatus.QUEUED);
                mapper.updateNodeExecution(nodes.get(0));
            }
            events.publishEvent(new FlowQueuedEvent(execution.getId()));
        }
        mapper.updateExecution(execution);
    }

    /** 取消该会话内最近一次执行:在跑的转 CANCEL_REQUESTED(等工作线程善后),其余直接 CANCELLED。 */
    @Transactional("gaussTransactionManager")
    public Optional<SkillFlowExecution> cancelLatest(String userId, String conversationId) {
        SkillFlowExecution execution = mapper.selectLatestConversationExecution(userId, conversationId);
        if (execution == null) return Optional.empty();
        if (!execution.getStatus().terminal()) {
            execution.setCancelRequestedAt(LocalDateTime.now(clock));
            execution.setStatus(execution.getStatus() == FlowExecutionStatus.RUNNING
                    ? FlowExecutionStatus.CANCEL_REQUESTED : FlowExecutionStatus.CANCELLED);
            if (execution.getStatus() == FlowExecutionStatus.CANCELLED) {
                execution.setActiveGuardKey(null);
                execution.setCompletedAt(LocalDateTime.now(clock));
            }
            mapper.updateExecution(execution);
            for (SkillFlowNodeExecution node : mapper.selectNodeExecutions(execution.getId())) {
                if (!node.getStatus().terminal() && node.getStatus() != FlowNodeExecutionStatus.RUNNING) {
                    node.setStatus(FlowNodeExecutionStatus.CANCELLED);
                    node.setCompletedAt(LocalDateTime.now(clock));
                    mapper.updateNodeExecution(node);
                }
            }
        }
        return Optional.of(execution);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<Long> readLongList(String value) {
        try {
            return objectMapper.readValue(value == null ? "[]" : value, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
