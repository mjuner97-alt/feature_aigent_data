package com.agentscopea2a.v2.skillManager.service;

import com.agentscopea2a.entity.ScriptRegistryEntry;
import com.agentscopea2a.mapper.gauss.ScriptRegistryMapper;
import com.agentscopea2a.v2.skillManager.config.SkillFlowProperties;
import com.agentscopea2a.v2.skillManager.entity.*;
import com.agentscopea2a.v2.skillManager.mapper.SkillFlowMapper;
import com.agentscopea2a.v2.skillManager.mapper.SkillMapper;
import com.agentscopea2a.v2.skillManager.notification.NotificationReceivers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Skill Flow 执行生命周期服务:
 * <ul>
 *   <li>{@link #trigger}:对话触发按(用户, 会话, 流程, 数据日期)幂等执行,不等待指标;</li>
 *   <li>{@link #cancelLatest}:取消会话内最近一次执行(用户回复"直接回答"时调用);</li>
 *   <li>{@link #cancel}:按执行 ID 取消(前端长任务列表/详情的"终止"按钮)。</li>
 * </ul>
 * 执行记录会快照流程当时的模板/并发度/通知开关,后续改编排不影响在跑的执行。
 */
@Service
public class FlowExecutionService {

    private static final Logger log = LoggerFactory.getLogger(FlowExecutionService.class);

    private final SkillFlowMapper mapper;
    private final SkillMapper skillMapper;
    private final ScriptRegistryMapper scriptRegistryMapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final NotificationRecipientService recipientService;

    @Autowired
    public FlowExecutionService(SkillFlowMapper mapper, SkillMapper skillMapper,
                                ScriptRegistryMapper scriptRegistryMapper,
                                ObjectMapper objectMapper, Clock skillFlowClock,
                                ApplicationEventPublisher events,
                                NotificationRecipientService recipientService) {
        this.mapper = mapper;
        this.skillMapper = skillMapper;
        this.scriptRegistryMapper = scriptRegistryMapper;
        this.objectMapper = objectMapper;
        this.clock = skillFlowClock;
        this.events = events;
        this.recipientService = recipientService;
    }

    /** Compatibility constructor for callers created before script metadata was added. */
    public FlowExecutionService(SkillFlowMapper mapper, SkillMapper skillMapper,
                                ObjectMapper objectMapper, Clock skillFlowClock,
                                ApplicationEventPublisher events,
                                NotificationRecipientService recipientService) {
        this(mapper, skillMapper, null, objectMapper, skillFlowClock, events, recipientService);
    }

    /** 触发结果:created=false 表示当日已有同一(用户,会话,流程)的活跃执行,直接复用。 */
    public record TriggerResult(SkillFlowExecution execution, boolean created) {}

    /**
     * 触发一次流程执行:
     * guardKey = 用户:会话:流程:数据日期,唯一索引兜底,同一天同一会话只跑一次;
     * 同时为每个节点生成节点执行记录(无依赖且指标就绪的节点直接 QUEUED,否则 PENDING 等待推进)。
     */
    @Transactional("gaussCustomerTransactionManager")
    public TriggerResult trigger(Long flowId, String userId, String conversationId, String question) {
        SkillFlow flow = mapper.selectFlowById(flowId);
        if (flow == null || !Boolean.TRUE.equals(flow.getEnabled())) throw new IllegalStateException("FlowNotFoundOrDisabled: " + flowId);
        LocalDate dataDate = LocalDate.now(clock);
        String guard = userId + ":" + conversationId + ":" + flowId + ":" + dataDate;
        // 对话入口直接执行,不因指标未就绪而阻塞本次请求。
        return createExecution(flow, userId, conversationId, question, dataDate, FlowTriggerType.CHAT, guard, false);
    }

    /** 指标到达后，为依赖该指标且全部日指标已就绪的流程创建每日自动执行。 */
    @Transactional("gaussCustomerTransactionManager")
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
     * 定时兜底扫描(由 FlowCoordinator 的 30 秒 scan 周期调用):
     * 为"当日全部依赖指标已 READY 但还没有 AUTO_METRIC 执行"的启用流程补建每日自动执行。
     *
     * <p>与推模式({@link #triggerReadyFlows})互补:外部触发调用丢失、异步门控重算失败、
     * 事件未送达等任一环抖动,最迟一个扫描周期(30 秒)内由本方法补偿。
     * 当天已有 AUTO_METRIC 执行(含终态)的流程不重复创建——每天最多自动执行一次;
     * 单个流程补建失败只记日志,不影响其余流程。</p>
     */
    public void scanAutoTriggerFlows() {
        // 与推模式用同一个 clock 取"今天",保证就绪日期、guard key、扫描判断的日期口径一致
        LocalDate dataDate = LocalDate.now(clock);
        // 候选已在 SQL 里过滤(见 selectAutoTriggerCandidates):启用未删、created_by 非空、
        // 节点挂了指标、当天没有 AUTO_METRIC 执行(含终态)、全部依赖指标当天 READY。
        // 所以这里循环的每一个流程都"该建而未建",直接逐个补建即可。
        for (SkillFlow flow : mapper.selectAutoTriggerCandidates(dataDate)) {
            try {
                autoTriggerFlow(flow.getId());
            } catch (RuntimeException e) {
                // 单个流程建失败(数据库抖动、并发冲突等)只记日志,不影响其余流程,
                // 下一轮扫描(30 秒后)会再次尝试——候选查询的 NOT EXISTS 条件天然支持重试
                log.error("[SkillFlow] auto trigger scan failed to create execution: flowId={}, code={}",
                        flow.getId(), flow.getCode(), e);
            }
        }
    }

    /** 为单个流程创建当日自动执行(幂等:候选查询已排除当日有 AUTO_METRIC 执行的流程)。 */
    @Transactional("gaussCustomerTransactionManager")
    public void autoTriggerFlow(Long flowId) {
        // 重新按 id 查一遍而不是直接用候选行:scan 循环和事务之间可能有几百毫秒间隙,
        // 期间流程可能被停用/删除,过期数据不该再触发
        SkillFlow flow = mapper.selectFlowById(flowId);
        if (flow == null || !Boolean.TRUE.equals(flow.getEnabled())) return;
        LocalDate dataDate = LocalDate.now(clock);
        // 星期排程过滤(schedule_rules 为空 = 每天都跑);与推模式 triggerReadyFlows 同一判定
        if (!SkillJobService.runsOn(flow.getScheduleRules(), dataDate.getDayOfWeek())) return;
        // guard/conversation 与推模式同格式 auto:{flowId}:{日期},两路触发互为幂等屏障
        String conversationId = "auto:" + flow.getId() + ":" + dataDate;
        // requireAllMetrics=true:自动触发必须全部依赖指标 READY 才建执行(缺则静默返回);
        // 建成即 QUEUED 并发 FlowQueuedEvent,事务提交后 FlowCoordinator 立即派发首节点
        createExecution(flow, flow.getCreatedBy(), conversationId, flow.getTaskQuestion(), dataDate,
                FlowTriggerType.AUTO_METRIC, conversationId, true);
    }

    /**
     * 手动触发(流程列表"执行"按钮):仅 owner;conversationId 独立成 manual 域,
     * 与聊天会话互不干扰;确认执行后不等待指标就绪。
     */
    @Transactional("gaussCustomerTransactionManager")
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
        if (existing != null) {
            // 仅"真正活跃"的执行(排队/运行/等指标/汇总中)防重;
            // "取消中"(CANCEL_REQUESTED)可能因 worker 中断永久滞留且仍持有 guard,放行新执行。
            if (existing.getStatus() != null && existing.getStatus() != FlowExecutionStatus.CANCEL_REQUESTED
                    && !existing.getStatus().terminal()) {
                return new TriggerResult(existing, false);
            }
            // 陈旧 guard 兜底:终态或取消中的执行不应阻塞新执行,释放其 guard(满足唯一索引)后继续创建。
            existing.setActiveGuardKey(null);
            mapper.updateExecution(existing);
        }
        // ── 判定「长任务依赖的所有指标是否都就绪」──────────────────────────────
        // 1) 取该流程全部节点依赖的指标 id 去重(node_metric 关联表),得到 required 集合;
        // 2) 逐一到 skill_metric_readiness 就绪登记表按「指标 id + 数据日期」查当日记录,
        //    记录不存在或状态 != READY 即计入 missing——全部不在 missing 中 = 所有依赖指标都就绪。
        // 就绪记录由外部指标到达时 upsert(见 MetricReadinessService#recordReady),次日跨天作废。
        // 快照写入 requiredMetricCount / readyMetricCount / missingMetricsJson,供执行详情展示与门控重算。
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
                // 关联的流程定义信息(编号 + 名称),便于执行记录自描述展示
                .flowId(flow.getId()).flowCode(flow.getCode()).flowName(flow.getName())
                // 快照:把流程定义当时的配置固化到执行记录,
                // 后续流程定义被修改不影响本次执行(总结问题模板 / 最大并行度)
                .summaryQuestionTemplateSnapshot(flow.getSummaryQuestionTemplate())
                .maxParallelismSnapshot(flow.getMaxParallelism())
                // 通知配置快照:执行期间以快照为准,发通知时不再回读流程定义
                .notifyEnabledSnapshot(flow.getNotifyEnabled())
                .notifyReceiversSnapshot(resolveNotifyReceiversSnapshot(flow, triggerType))
                .notifyReceiverTriggersSnapshot(flow.getNotifyReceiverTriggers())
                // 触发来源:触发类型(CHAT/MANUAL/METRIC 等)与触发人信息
                .triggerType(triggerType)
                // 触发上下文:触发人、会话 id、原始提问、数据日期(用于指标就绪判断)
                .triggerUserId(userId).conversationId(conversationId).originalQuestion(question).dataDate(dataDate)
                // 非自动触发直接 QUEUED;自动触发有缺失指标时才进入 WAITING_METRICS。
                .status(!requireAllMetrics || missing.isEmpty()
                        ? FlowExecutionStatus.QUEUED : FlowExecutionStatus.WAITING_METRICS)
                // 指标门控信息:防重 guard key、所需指标总数、当前已就绪数、缺失指标 id 列表(JSON)
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
            // Python 脚本节点不绑 Skill:名称取脚本注册表,skillId 留空
            String normalizedScriptId = node.getScriptId() == null ? null : node.getScriptId().trim();
            boolean scriptNode = normalizedScriptId != null && !normalizedScriptId.isBlank();
            String skillName;
            String retrievalName;
            if (scriptNode) {
                ScriptRegistryEntry script = scriptRegistryMapper.selectByScriptId(normalizedScriptId);
                skillName = script == null ? normalizedScriptId : script.getName();
                retrievalName = skillName;
            } else {
                Skill skill = skillMapper.selectById(node.getSkillId());
                skillName = skill == null ? "Skill #" + node.getSkillId() : skill.getName();
                retrievalName = skill == null || skill.getRetrievalName() == null || skill.getRetrievalName().isBlank()
                        ? skillName : skill.getRetrievalName();
            }
            FlowNodeExecutionStatus status = !requireAllMetrics || missing.isEmpty()
                    ? (firstRunnableNode ? FlowNodeExecutionStatus.QUEUED : FlowNodeExecutionStatus.PENDING)
                    : FlowNodeExecutionStatus.PENDING;
            if (status == FlowNodeExecutionStatus.QUEUED) firstRunnableNode = false;
            mapper.insertNodeExecution(SkillFlowNodeExecution.builder().flowExecutionId(execution.getId())
                    .nodeKey(node.getNodeKey()).nodeName(FlowDefinitionService.resolveNodeDisplayName(node.getNodeName(), skillName))
                    .skillId(scriptNode ? null : node.getSkillId()).skillName(skillName).skillRetrievalName(retrievalName)
                    .scriptId(scriptNode ? normalizedScriptId : null)
                    .scriptParamsJson(scriptNode ? node.getScriptParamsJson() : null)
                    // Python 节点问题模板选填;列 NOT NULL,空值落空串
                    .questionTemplateSnapshot(Objects.toString(node.getQuestionTemplate(), ""))
                    .dependsOnJson(node.getDependsOnJson())
                    .required(node.getRequired()).status(status).attemptCount(0)
                    .maxAttempts(SkillFlowProperties.NODE_MAX_ATTEMPTS).build());
        }
        if (execution.getStatus() == FlowExecutionStatus.QUEUED) {
            events.publishEvent(new FlowQueuedEvent(execution.getId()));
        }
        return new TriggerResult(execution, true);
    }

    /**
     * 指标就绪回调入口(Skill Job 在外部指标到达时调用,推送式广播)。
     *
     * <p>判定语义：某一天可能有多个流程执行同时停在 WAITING_METRICS——
     * 有的等指标 A、有的等指标 B、有的 A/B 都等。任一指标就绪只对「等它的执行」有意义,
     * 因此这里把当前所有 WAITING_METRICS 的执行捞出来,逐个交给 {@link #recomputeGate} 重新判定:
     * <ul>
     *   <li>执行依赖的指标还没到齐 → 只更新 readyMetricCount 快照,继续等待;</li>
     *   <li>全部到齐(missing 清空) → 放行动作,见 {@link #recomputeGate} 的 javadoc。</li>
     * </ul>
     * 只处理数据日期匹配的执行:就绪记录按「指标 + 数据日期」登记,昨天的指标救不了今天的执行
     * (跨天由 {@code FlowCoordinator#expirePreviousDays} 兜底判失败)。
     */
    @Transactional("gaussCustomerTransactionManager")
    public void metricBecameReady(Long metricId, LocalDate dataDate) {
        // 广播式重算:不区分该指标被谁依赖,统一让所有等待中的执行各自复查一遍(简单且不会漏)
        for (SkillFlowExecution execution : mapper.selectWaitingExecutions()) {
            if (!dataDate.equals(execution.getDataDate())) continue;  // 数据日期不同,与本次就绪无关
            recomputeGate(execution);
        }
    }

    /**
     * 重算就绪门控(推送式,指标到达时由 {@link #metricBecameReady} 逐个等待中的执行调用):
     * 基于执行记录上快照的 missingMetricsJson 重新逐项查就绪登记表,
     * missing 清空(全部依赖指标就绪)且流程仍停在 WAITING_METRICS 时触发放行动作:
     * <ol>
     *   <li>流程状态 WAITING_METRICS -&gt; QUEUED;</li>
     *   <li>仅把第一个节点 PENDING -&gt; QUEUED(其余节点由依赖推进逐步放行);</li>
     *   <li>发布 {@link FlowQueuedEvent}——FlowCoordinator 在事务提交后
     *       ({@code @TransactionalEventListener(AFTER_COMMIT)}) 收到即调用 dispatchRunnableNodes 立即派发;</li>
     *   <li>落库更新 readyMetricCount / missingMetricsJson 快照。</li>
     * </ol>
     * 定时 scan 只做恢复兜底,不重算门控;等待中的执行只靠本推送路径放行。
     */
    @Transactional("gaussCustomerTransactionManager")
    public void recomputeGate(SkillFlowExecution execution) {
        // 只对快照里缺失过的指标重查就绪登记表,已就绪的无需重复检查
        List<Long> missing = readLongList(execution.getMissingMetricsJson()).stream()
                .filter(id -> {
                    SkillMetricReadiness ready = mapper.selectMetricReadiness(id, execution.getDataDate());
                    return ready == null || ready.getStatus() != MetricReadinessStatus.READY;
                }).toList();
        execution.setReadyMetricCount(Math.max(0, execution.getRequiredMetricCount() - missing.size()));
        execution.setMissingMetricsJson(json(missing));
        // missing 为空 = 长任务依赖的所有指标都已就绪,执行放行动作(见方法 javadoc)
        if (missing.isEmpty() && execution.getStatus() == FlowExecutionStatus.WAITING_METRICS) {
            execution.setStatus(FlowExecutionStatus.QUEUED);
            List<SkillFlowNodeExecution> nodes = mapper.selectNodeExecutions(execution.getId());
            if (!nodes.isEmpty()) {
                // 仅放行第一个节点,其余节点等依赖关系逐级推进
                nodes.get(0).setStatus(FlowNodeExecutionStatus.QUEUED);
                mapper.updateNodeExecution(nodes.get(0));
            }
            // 事务提交后 FlowCoordinator.onFlowQueued 立即派发可运行节点
            events.publishEvent(new FlowQueuedEvent(execution.getId()));
        }
        mapper.updateExecution(execution);
    }

    /** 取消该会话内最近一次执行:在跑的转 CANCEL_REQUESTED(等工作线程善后),其余直接 CANCELLED。 */
    @Transactional("gaussCustomerTransactionManager")
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

    /**
     * 按执行 ID 取消(前端长任务列表/详情的"终止"按钮):直接落终态 CANCELLED,
     * 前端立即显示"已取消"。正在执行的节点由 worker 跑完后在软取消检查点
     * (FlowCoordinator#executeNode)丢弃结果,后续节点不会再被认领执行。
     * 与 {@link FlowCoordinator#advance} 对 CANCELLED 的善后分支保持兼容。
     */
    @Transactional("gaussCustomerTransactionManager")
    public SkillFlowExecution cancel(Long executionId) {
        // 锁定流程行,串行化同一执行上的取消/重跑等状态修改(与 FlowCoordinator.retryNode 同款)
        SkillFlowExecution execution = mapper.selectFlowExecutionForUpdate(executionId);
        if (execution == null) throw new IllegalArgumentException("执行不存在: " + executionId);
        if (execution.getStatus() == null || execution.getStatus().terminal())
            throw new IllegalStateException("执行已结束,无法取消");
        LocalDateTime now = LocalDateTime.now(clock);
        execution.setCancelRequestedAt(now);
        execution.setStatus(FlowExecutionStatus.CANCELLED);
        execution.setActiveGuardKey(null);
        execution.setCompletedAt(now);
        mapper.updateExecution(execution);
        // 所有未终态节点(含正在执行的)一并置 CANCELLED;在跑节点的 worker 结束后
        // 会发现流程已取消,直接丢弃结果,不会覆盖这里的终态。
        for (SkillFlowNodeExecution node : mapper.selectNodeExecutions(execution.getId())) {
            if (node.getStatus() != null && !node.getStatus().terminal()) {
                node.setStatus(FlowNodeExecutionStatus.CANCELLED);
                node.setCompletedAt(now);
                mapper.updateNodeExecution(node);
            }
        }
        return execution;
    }

    /**
     * 执行创建时解析收件人名单并固化为逗号分隔快照(读取优先级,见设计文档「兼容与迁移」):
     * <ol>
     *   <li>{@code notification_config} 存在 → 只读 {@code notification_recipient} 关系表;
     *       名单为空 = 用户已主动清空,不回退旧字段;</li>
     *   <li>{@code notification_config} 不存在 → 兼容读取旧 {@code skill_flow.notify_receivers}
     *       逗号字段(记录兼容日志,待旧字段清理后移除)。</li>
     * </ol>
     * 快照沿用现有 {@code skill_flow_execution.notify_receivers_snapshot} 字段,
     * 发送侧({@code FlowCompletionService})只读快照,后续配置修改不影响已启动执行。
     */
    private String resolveNotifyReceiversSnapshot(SkillFlow flow, FlowTriggerType triggerType) {
        List<String> userIds = recipientService
                .findConfiguredUserIds(NotificationConfig.TARGET_TYPE_SKILL_FLOW, flow.getId(),
                        triggerType == FlowTriggerType.MANUAL || triggerType == FlowTriggerType.CHAT
                                ? "DEFAULT" : (triggerType == null ? "DEFAULT" : triggerType.name()))
                .orElseGet(() -> {
                    log.info("[SkillFlow] notification_config missing for flow {}, "
                            + "snapshot falls back to legacy notify_receivers (compat)", flow.getId());
                    return NotificationReceivers.parse(flow.getNotifyReceivers());
                });
        return NotificationReceivers.toCsv(userIds);
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
