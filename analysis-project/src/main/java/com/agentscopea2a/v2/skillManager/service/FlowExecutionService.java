package com.agentscopea2a.v2.skillManager.service;

import com.agentscopea2a.v2.skillManager.entity.*;
import com.agentscopea2a.v2.skillManager.mapper.SkillFlowMapper;
import com.agentscopea2a.v2.skillManager.mapper.SkillMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
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
 *   <li>{@link #trigger}:按(用户, 会话, 流程, 数据日期)幂等触发一次执行;指标未全部就绪时先挂
 *       WAITING_METRICS(指标就绪门控),就绪后由 {@link #metricBecameReady} 放行;</li>
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

    public FlowExecutionService(SkillFlowMapper mapper, SkillMapper skillMapper,
                                ObjectMapper objectMapper, Clock skillFlowClock) {
        this.mapper = mapper;
        this.skillMapper = skillMapper;
        this.objectMapper = objectMapper;
        this.clock = skillFlowClock;
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
        SkillFlowExecution existing = mapper.selectActiveExecution(guard);
        if (existing != null) return new TriggerResult(existing, false);
        // 汇总全部节点依赖的指标,逐一检查就绪状态
        List<SkillFlowNode> nodes = mapper.selectNodesByFlowId(flowId);
        Set<Long> metrics = new LinkedHashSet<>();
        nodes.forEach(n -> metrics.addAll(mapper.selectMetricIdsByNodeId(n.getId())));
        List<Long> missing = metrics.stream().filter(id -> {
            SkillMetricReadiness ready = mapper.selectMetricReadiness(id, dataDate);
            return ready == null || ready.getStatus() != MetricReadinessStatus.READY;
        }).toList();
        SkillFlowExecution execution = SkillFlowExecution.builder()
                .flowId(flowId).flowCode(flow.getCode()).flowName(flow.getName())
                .summaryQuestionTemplateSnapshot(flow.getSummaryQuestionTemplate())
                .maxParallelismSnapshot(flow.getMaxParallelism())
                .notifyEnabledSnapshot(flow.getNotifyEnabled()).triggerType(FlowTriggerType.CHAT)
                .triggerUserId(userId).conversationId(conversationId).originalQuestion(question).dataDate(dataDate)
                .status(missing.isEmpty() ? FlowExecutionStatus.QUEUED : FlowExecutionStatus.WAITING_METRICS)
                .activeGuardKey(guard).requiredMetricCount(metrics.size())
                .readyMetricCount(metrics.size() - missing.size())
                .missingMetricsJson(json(missing)).build();
        if (mapper.insertFlowExecution(execution) == 0) {
            // 唯一索引冲突:并发下别人先插入了,复用对方记录
            return new TriggerResult(mapper.selectActiveExecution(guard), false);
        }
        for (SkillFlowNode node : nodes) {
            Skill skill = skillMapper.selectById(node.getSkillId());
            String skillName = skill == null ? "Skill #" + node.getSkillId() : skill.getName();
            String retrievalName = skill == null || skill.getRetrievalName() == null || skill.getRetrievalName().isBlank()
                    ? skillName : skill.getRetrievalName();
            FlowNodeExecutionStatus status = missing.isEmpty() && readList(node.getDependsOnJson()).isEmpty()
                    ? FlowNodeExecutionStatus.QUEUED : FlowNodeExecutionStatus.PENDING;
            mapper.insertNodeExecution(SkillFlowNodeExecution.builder().flowExecutionId(execution.getId())
                    .nodeKey(node.getNodeKey()).skillId(node.getSkillId()).skillName(skillName).skillRetrievalName(retrievalName)
                    .questionTemplateSnapshot(node.getQuestionTemplate()).dependsOnJson(node.getDependsOnJson())
                    .required(node.getRequired()).status(status).attemptCount(0).maxAttempts(node.getMaxAttempts()).build());
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

    /** 重算就绪门控:全部指标就绪时,流程置 QUEUED 并把无依赖节点置 QUEUED。 */
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
            for (SkillFlowNodeExecution node : mapper.selectNodeExecutions(execution.getId())) {
                if (readList(node.getDependsOnJson()).isEmpty()) {
                    node.setStatus(FlowNodeExecutionStatus.QUEUED);
                    mapper.updateNodeExecution(node);
                }
            }
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

    List<String> readList(String value) {
        try {
            return objectMapper.readValue(value == null ? "[]" : value, new TypeReference<>() {});
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
