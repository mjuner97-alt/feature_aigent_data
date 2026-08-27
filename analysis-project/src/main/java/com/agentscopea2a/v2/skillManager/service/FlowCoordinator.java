package com.agentscopea2a.v2.skillManager.service;

import com.agentscopea2a.v2.runner.HarnessA2aRunnerV2;
import com.agentscopea2a.v2.skillManager.config.SkillFlowProperties;
import com.agentscopea2a.v2.skillManager.entity.*;
import com.agentscopea2a.v2.skillManager.mapper.SkillFlowMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeoutException;

/**
 * Skill Flow 执行协调器(后台工作线程的心脏):
 * <ol>
 *   <li>{@link #scan} 定时扫描可运行节点(QUEUED/RETRY_WAIT/租约过期),经
 *       {@link FlowNodeClaimService} 抢占认领后提交到工作线程池;</li>
 *   <li>{@link #executeNode} 渲染节点问题模板 -> 调 AI 执行该节点 skill,成功后推进;</li>
 *   <li>{@link #advance} 推进:放行等待节点、处理取消、全部终态后触发汇总与通知;</li>
 *   <li>{@link #expirePreviousDays} 跨天兜底:仍在等指标(WAITING_METRICS)的执行直接判失败。</li>
 * </ol>
 * 节点级并发用许可信号量(workerPermits)限流,流程级并发上限由执行快照 maxParallelismSnapshot 控制。
 */
@Component
public class FlowCoordinator {

    private static final Logger log = LoggerFactory.getLogger(FlowCoordinator.class);

    /** Whether this instance is allowed to scan and execute long-task workers. */
    @Value("${harness.a2a.skill-flow.worker-enabled:true}")
    private boolean workerEnabled;

    private final SkillFlowMapper mapper;
    private final HarnessA2aRunnerV2 runner;
    private final ObjectMapper json;
    private final Clock clock;
    private final FlowTemplateEngine templates = new FlowTemplateEngine();
    /** 本实例的工作者标识,写入租约字段,多实例部署时用于区分谁认领了节点。 */
    private final String workerId = UUID.randomUUID().toString();
    /** 固定大小工作线程池:同一时刻最多 workerCount 个节点在执行。 */
    private final ExecutorService workers;
    /**
     * 执行超时后的恢复线程池。
     *
     * <p>租约是单次节点执行的超时保护，不是续租机制：模型调用超过租约后，
     * 原尝试视为失效，恢复尝试必须能绕过仍被旧调用占用的普通 worker 立即启动。
     */
    private final ExecutorService recoveryWorkers;
    /** 工作容量信号量:池子满时 scan 不再认领新节点,避免认领后无处执行。 */
    private final Semaphore workerPermits;
    private final FlowCompletionService completionService;
    private final FlowNodeClaimService claimService;
    private final NodeAttemptCompletionService attemptCompletionService;

    public FlowCoordinator(SkillFlowMapper mapper, HarnessA2aRunnerV2 runner, ObjectMapper json, Clock skillFlowClock,
                           FlowCompletionService completionService, FlowNodeClaimService claimService,
                           NodeAttemptCompletionService attemptCompletionService) {
        this.mapper = mapper;
        this.runner = runner;
        this.json = json;
        this.clock = skillFlowClock;
        this.completionService = completionService;
        this.claimService = claimService;
        this.attemptCompletionService = attemptCompletionService;
        int workerCount = Math.max(1, SkillFlowProperties.WORKER_COUNT);
        this.workerPermits = new Semaphore(workerCount);
        this.workers = Executors.newFixedThreadPool(workerCount, r -> {
            Thread t = new Thread(r, "skill-flow-worker");
            t.setDaemon(true);
            return t;
        });
        this.recoveryWorkers = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "skill-flow-recovery-worker");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 新进程启动时立即回收旧进程遗留的 RUNNING 节点。
     * 租约仍用于运行期间的故障保护,但重启场景不再被迫等待完整租约周期。
     */
    @jakarta.annotation.PostConstruct
    void recoverNodesAfterRestart() {
        int recovered = mapper.recoverAbandonedRunningNodes(workerId, LocalDateTime.now(clock));
        if (recovered > 0) {
            log.warn("Recovered {} abandoned long-task nodes after worker restart", recovered);
        }
    }

    /** 定时扫描并调度可运行节点;总开关/工作开关任一关闭则直接空转。 */
    @Scheduled(fixedDelay = SkillFlowProperties.SCAN_INTERVAL_MS)
    public void scan() {
        if (!SkillFlowProperties.ENABLED || !workerEnabled) return;
        LocalDateTime now = LocalDateTime.now(clock);
        expirePreviousDays();
        expireExhaustedNodes(now);
        dispatchRunnableNodes();
    }

    /**
     * 最终超时兜底：节点已经达到最大尝试次数，且最后一次执行租约也已到期，
     * 说明工作者死亡或模型调用长期无响应。此时不再重试，直接标记节点失败并推进流程，
     * 避免节点和流程永久显示“执行中”。尚未达到最大次数的过期节点由普通扫描逻辑重新认领重试。
     */
    private void expireExhaustedNodes(LocalDateTime now) {
        for (SkillFlowNodeExecution node : mapper.selectExpiredExhaustedNodes(now)) {
            mapper.failRunningAttemptsForNode(node.getId(), now);
            node.setStatus(FlowNodeExecutionStatus.FAILED);
            node.setErrorCode("LEASE_EXPIRED");
            node.setErrorMessage("Worker lease expired before completion");
            node.setCompletedAt(now);
            clearLease(node);
            mapper.updateNodeExecution(node);
            advance(node.getFlowExecutionId());
        }
    }

    /** 正常路径：流程事务提交后立即派发；定时 scan 仅作为恢复兜底。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onFlowQueued(FlowQueuedEvent event) {
        if (!SkillFlowProperties.ENABLED || !workerEnabled) return;
        dispatchRunnableNodes();
    }

    private void dispatchRunnableNodes() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<SkillFlowNodeExecution> runnable = mapper.selectRunnableNodes(now);
        log.debug("Skill flow dispatch scan found {} runnable node(s)", runnable.size());
        for (SkillFlowNodeExecution node : runnable) {
            // RUNNING 且租约已过期 = 原执行超时/失联；不再等待旧线程结束，直接走重试恢复池。
            boolean expiredAttempt = node.getStatus() == FlowNodeExecutionStatus.RUNNING
                    && node.getLeaseExpiresAt() != null
                    && node.getLeaseExpiresAt().isBefore(now);
            if (!expiredAttempt && !workerPermits.tryAcquire()) break; // 普通池已满,本轮不再认领
            if (claimService.claim(node.getId(), workerId, now.plusSeconds(SkillFlowProperties.LEASE_SECONDS), now)) {
                try {
                    ExecutorService executor = expiredAttempt ? recoveryWorkers : workers;
                    executor.submit(() -> {
                        try {
                            executeNode(node.getId());
                        } catch (Exception e) {
                            // executeNode 内部已兜底,这里防御未被捕获的异常逃逸,至少留下日志
                            log.error("Skill flow node {} worker task crashed", node.getId(), e);
                        } finally {
                            // 恢复任务没有占用普通池许可，因此这里不能释放普通许可。
                            if (!expiredAttempt) workerPermits.release();
                        }
                    });
                } catch (RejectedExecutionException e) {
                    if (!expiredAttempt) workerPermits.release();
                    throw e;
                }
            } else {
                if (!expiredAttempt) workerPermits.release(); // 认领失败(被别人抢走/并发超限),让出许可
            }
        }
    }

    /** 执行单个节点:渲染模板 -> 调 skill -> 写结果并推进;失败按可重试分类决定重试或终止。 */
    private void executeNode(Long nodeId) {
        // 整个方法都包在 try/catch 内:开审计记录前的任何异常(数据库抖动、唯一键冲突等)
        // 也会走失败收尾并留日志,而不是把已认领的节点静默卡成无人处理的 RUNNING 僵尸。
        SkillFlowNodeExecution node = null;
        SkillFlowExecution flow = null;
        SkillFlowNodeAttempt audit = null;
        LocalDateTime started = LocalDateTime.now(clock);
        int attempt = 0;
        String attemptLeaseOwner = null;
        try {
            node = mapper.selectNodeExecution(nodeId);
            if (node == null) return;
            flow = mapper.selectFlowExecutionById(node.getFlowExecutionId());
            // 流程已请求取消/已取消:节点直接置 CANCELLED
            if (flow == null || flow.getStatus() == FlowExecutionStatus.CANCEL_REQUESTED
                    || flow.getStatus() == FlowExecutionStatus.CANCELLED) {
                cancel(node, flow);
                return;
            }
            started = LocalDateTime.now(clock);
            attempt = node.getAttemptCount();
            attemptLeaseOwner = node.getLeaseOwner();
            // 认领成功但可能有上次遗留的 RUNNING 尝试记录,先作废再开新尝试
            mapper.failRunningAttemptsForNode(nodeId, started);
            // 手动重跑会把 attempt_count 归零重新获得重试预算,而审计表对 (节点, 尝试号) 有唯一索引;
            // 尝试号必须避开历史记录,否则唯一键冲突会在 try 之前抛出,节点永远停留在 RUNNING。
            int attemptNo = Math.max(attempt, mapper.selectMaxAttemptNo(nodeId) + 1);
            audit = SkillFlowNodeAttempt.builder()
                    .nodeExecutionId(nodeId).attemptNo(attemptNo)
                    .status(FlowNodeAttemptStatus.RUNNING).retryable(false).startedAt(started).build();
            mapper.insertAttempt(audit);
            // 状态流转放 try 内:流转失败(如缺列)时走失败路径留下错误信息,而不是静默把节点卡成僵尸 RUNNING
            flow.setStatus(FlowExecutionStatus.RUNNING);
            if (flow.getStartedAt() == null) flow.setStartedAt(started);
            mapper.updateExecution(flow);
            String template = node.getQuestionTemplateSnapshot();
            // 老数据节点模板快照可能为空:兜底直接用原始问题提问,不让节点执行报错
            String rendered = template == null || template.isBlank()
                    ? Objects.toString(flow.getOriginalQuestion(), "")
                    : templates.render(template,
                    new FlowTemplateEngine.Context(Map.of(
                            "server_date", flow.getDataDate().toString(),
                            "original_question", flow.getOriginalQuestion(),
                            "flow_name", flow.getFlowName(),
                            "skill_name", node.getSkillName())));
            String question = buildPrompt(node, rendered);
            node.setRenderedQuestion(question);
            RuntimeContext context = RuntimeContext.builder()
                    .sessionId("flow-" + flow.getId() + "-" + node.getNodeKey())
                    .userId(flow.getTriggerUserId()).build();
            List<AgentEvent> events = runner.streamEvents(List.of(Msg.builder()
                            .role(MsgRole.USER).content(TextBlock.builder().text(question).build()).build()),
                    context).collectList().block(Duration.ofMinutes(10));
            String result = extract(events);
            if (result == null || result.isBlank()) throw new IllegalStateException("Skill returned empty result");
            String resultJson = json(Map.of("text", result));
            completeAudit(audit, FlowNodeAttemptStatus.SUCCESS, false, null, null, started);
            if (attemptCompletionService.completeSuccess(node, attempt, attemptLeaseOwner,
                    resultJson, LocalDateTime.now(clock))) {
                advance(flow.getId());
            } else {
                log.info("Ignoring stale success for node {} attempt {}", nodeId, attempt);
            }
        } catch (Exception error) {
            // 可重试错误(超时/429/5xx 等)且未到最大尝试次数 -> RETRY_WAIT,指数退避(1s/2s/4s/8s)
            // 老数据可能没有最大重试次数；按单次执行处理，不能让错误处理本身再次抛 NPE，
            // 否则节点会永远停留在 RUNNING，流程也无法进入最终状态。
            int maxAttempts = node == null || node.getMaxAttempts() == null ? 1 : Math.max(1, node.getMaxAttempts());
            boolean retryable = retryable(error) && attempt < maxAttempts;
            String errorCode = retryable ? "RETRYABLE_ERROR" : "EXECUTION_FAILED";
            String errorMessage = errorMessage(error);
            if (retryable && node != null) {
                node.setNextRunAt(LocalDateTime.now(clock).plusSeconds(1L << Math.min(attempt, 3)));
            }
            if (audit != null) {
                completeAudit(audit, FlowNodeAttemptStatus.FAILED, retryable, errorCode, errorMessage, started);
            } else {
                // 审计记录尚未建立(读取节点/写尝试记录阶段就失败):必须留痕,否则无从排查
                log.error("Skill flow node {} failed before attempt record was created", nodeId, error);
            }
            if (node != null && flow != null) {
                boolean completed = attemptCompletionService.completeFailure(node, attempt, attemptLeaseOwner,
                        retryable, errorCode, errorMessage, LocalDateTime.now(clock));
                if (completed && !retryable) {
                    advance(flow.getId());
                } else if (!completed) {
                    log.info("Ignoring stale failure for node {} attempt {}", nodeId, attempt);
                }
            } else {
                log.error("Skill flow node {} failure could not be persisted (node/flow unavailable): {}",
                        nodeId, errorMessage);
            }
        }
    }

    /** 推进流程状态:取消善后、放行等待节点、全部终态后汇总收尾。 */
    private void advance(Long flowId) {
        SkillFlowExecution flow = mapper.selectFlowExecutionById(flowId);
        if (flow == null) return;
        List<SkillFlowNodeExecution> nodes = mapper.selectNodeExecutions(flow.getId());
        // 取消中:所有未终态节点置 CANCELLED,流程落终态 CANCELLED
        if (flow.getStatus() == FlowExecutionStatus.CANCEL_REQUESTED || flow.getStatus() == FlowExecutionStatus.CANCELLED) {
            for (SkillFlowNodeExecution node : nodes) {
                if (!node.getStatus().terminal()) {
                    node.setStatus(FlowNodeExecutionStatus.CANCELLED);
                    node.setCompletedAt(LocalDateTime.now(clock));
                    clearLease(node);
                    mapper.updateNodeExecution(node);
                }
            }
            flow.setStatus(FlowExecutionStatus.CANCELLED);
            flow.setSummaryJson(json(Map.of("cancelled", true)));
            releaseRepeatableGuard(flow);
            flow.setCompletedAt(LocalDateTime.now(clock));
            mapper.updateExecution(flow);
            return;
        }
        // 严格按配置顺序串行:当前节点未终态(执行中/等待重试/排队)时,后续节点保持 PENDING。
        boolean activeNode = nodes.stream().anyMatch(n -> n.getStatus() == FlowNodeExecutionStatus.RUNNING
                || n.getStatus() == FlowNodeExecutionStatus.RETRY_WAIT
                || n.getStatus() == FlowNodeExecutionStatus.QUEUED);
        if (!activeNode) {
            nodes.stream().filter(n -> n.getStatus() == FlowNodeExecutionStatus.PENDING).findFirst()
                    .ifPresent(node -> {
                        node.setStatus(FlowNodeExecutionStatus.QUEUED);
                        mapper.updateNodeExecution(node);
                    });
        }
        // 全部节点终态:抢占汇总权(claimExecutionForSummary 保证只汇总一次)后收尾
        nodes = mapper.selectNodeExecutions(flow.getId());
        if (nodes.stream().allMatch(n -> n.getStatus().terminal()) && mapper.claimExecutionForSummary(flow.getId()) == 1) {
            SkillFlowExecution summarizing = mapper.selectFlowExecutionById(flow.getId());
            finalizeFlow(summarizing, nodes);
        }
    }

    /** 全部节点终态后的收尾:定最终状态 -> 汇总+报告 -> 发通知。 */
    private void finalizeFlow(SkillFlowExecution flow, List<SkillFlowNodeExecution> nodes) {
        boolean requiredFailed = nodes.stream().anyMatch(n -> Boolean.TRUE.equals(n.getRequired())
                && n.getStatus() != FlowNodeExecutionStatus.SUCCESS);
        boolean anyFailed = nodes.stream().anyMatch(n -> n.getStatus() != FlowNodeExecutionStatus.SUCCESS);
        // 必需节点失败 -> FAILED;仅非必需节点失败 -> PARTIAL_SUCCESS;全成功 -> SUCCESS
        flow.setStatus(requiredFailed ? FlowExecutionStatus.FAILED
                : anyFailed ? FlowExecutionStatus.PARTIAL_SUCCESS : FlowExecutionStatus.SUCCESS);
        try {
            FlowCompletionService.Summary summary = completionService.summarize(flow, nodes);
            flow.setSummaryJson(summary.summaryJson());
            flow.setReportPath(summary.reportPath());
        } catch (RuntimeException e) {
            // 汇总失败不吞掉执行结果:summary 里保留各节点状态明细
            log.error("Flow {} summary failed", flow.getId(), e);
            flow.setSummaryJson(json(Map.of("summaryError", e.getMessage(),
                    "nodes", nodes.stream().map(n -> Map.of(
                            "nodeKey", n.getNodeKey(), "status", n.getStatus().name(),
                            "error", Objects.toString(n.getErrorMessage(), ""))).toList())));
            if (flow.getStatus() == FlowExecutionStatus.SUCCESS) flow.setStatus(FlowExecutionStatus.FAILED);
        }
        flow.setCompletedAt(LocalDateTime.now(clock));
        releaseRepeatableGuard(flow);
        mapper.updateExecution(flow);
        completionService.sendInitial(flow);
    }

    public void retrySummary(Long flowId) {
        SkillFlowExecution flow = mapper.selectFlowExecutionForUpdate(flowId);
        if (flow == null || !(flow.getStatus() == FlowExecutionStatus.SUCCESS
                || flow.getStatus() == FlowExecutionStatus.FAILED
                || flow.getStatus() == FlowExecutionStatus.PARTIAL_SUCCESS))
            throw new IllegalStateException("执行当前不可重新生成汇总");
        List<SkillFlowNodeExecution> nodes = mapper.selectNodeExecutions(flowId);
        if (nodes.stream().anyMatch(n -> !n.getStatus().terminal())) throw new IllegalStateException("节点尚未全部结束");
        flow.setStatus(FlowExecutionStatus.SUMMARIZING);
        flow.setSummaryJson(null); flow.setReportPath(null); flow.setCompletedAt(null);
        mapper.updateExecution(flow);
        finalizeFlow(flow, nodes);
    }

    public void retryNode(Long flowId, Long nodeId) {
        SkillFlowExecution flow = mapper.selectFlowExecutionForUpdate(flowId);
        SkillFlowNodeExecution node = mapper.selectNodeExecution(nodeId);
        if (flow == null || node == null || !Objects.equals(flowId, node.getFlowExecutionId())) throw new IllegalArgumentException("节点不存在");
        boolean flowRetryable = flow.getStatus().terminal() || flow.getStatus() == FlowExecutionStatus.RUNNING;
        if (!flowRetryable || !node.getStatus().terminal() || node.getStatus() == FlowNodeExecutionStatus.SUCCESS)
            throw new IllegalStateException("节点当前不可重跑");
        resetForRetry(node);
        mapper.updateNodeExecution(node);
        flow.setStatus(FlowExecutionStatus.RUNNING); flow.setSummaryJson(null); flow.setReportPath(null); flow.setCompletedAt(null);
        mapper.updateExecution(flow);
        dispatchRunnableNodes();
        scheduleRetryDispatch();
    }

    /**
     * 批量重跑失败节点:FAILED/BLOCKED 节点按原执行顺序重置,
     * 首个直接 QUEUED,其余 PENDING 由 advance 顺序放行(与首跑的串行模型一致)。
     *
     * @return 本次重跑的节点数
     */
    public int retryFailedNodes(Long flowId) {
        SkillFlowExecution flow = mapper.selectFlowExecutionForUpdate(flowId);
        if (flow == null || (flow.getStatus() != FlowExecutionStatus.FAILED
                && flow.getStatus() != FlowExecutionStatus.PARTIAL_SUCCESS))
            throw new IllegalStateException("执行当前不可批量重跑");
        List<SkillFlowNodeExecution> failed = mapper.selectNodeExecutions(flowId).stream()
                .filter(n -> n.getStatus() == FlowNodeExecutionStatus.FAILED
                        || n.getStatus() == FlowNodeExecutionStatus.BLOCKED)
                .toList();
        if (failed.isEmpty()) throw new IllegalStateException("没有可重跑的失败任务");
        for (SkillFlowNodeExecution node : failed) {
            resetForRetry(node);
            // 批量重试的失败节点全部重新入队，交由并发上限和 claim 事务统一调度。
            node.setStatus(FlowNodeExecutionStatus.QUEUED);
            mapper.updateNodeExecution(node);
        }
        flow.setStatus(FlowExecutionStatus.RUNNING); flow.setSummaryJson(null); flow.setReportPath(null); flow.setCompletedAt(null);
        mapper.updateExecution(flow);
        // 先推进一次状态，再立即扫描；即使首轮 worker 许可暂时不足，定时扫描也能继续认领。
        advance(flowId);
        dispatchRunnableNodes();
        scheduleRetryDispatch();
        return failed.size();
    }

    /** 重置节点为待执行:清空结果/错误/租约,并归零尝试次数,让重跑重新获得完整的重试预算。 */
    private void resetForRetry(SkillFlowNodeExecution node) {
        node.setStatus(FlowNodeExecutionStatus.QUEUED);
        node.setAttemptCount(0);
        node.setNextRunAt(null); node.setCompletedAt(null);
        node.setStartedAt(null); node.setErrorCode(null); node.setErrorMessage(null); node.setResultJson(null);
        clearLease(node);
    }

    /** 重试请求返回后再补一次扫描，覆盖事务提交可见性和旧 worker 释放许可的竞态。 */
    private void scheduleRetryDispatch() {
        recoveryWorkers.submit(() -> {
            try { Thread.sleep(200L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            dispatchRunnableNodes();
        });
    }

    /** 跨天兜底:数据日期已过但还在等指标的执行,整体判 METRIC_TIMEOUT 失败并通知。 */
    private void expirePreviousDays() {
        LocalDate today = LocalDate.now(clock);
        for (SkillFlowExecution flow : mapper.selectWaitingExecutions()) {
            if (!flow.getDataDate().isBefore(today)) continue;
            for (SkillFlowNodeExecution node : mapper.selectNodeExecutions(flow.getId())) {
                if (!node.getStatus().terminal()) {
                    node.setStatus(FlowNodeExecutionStatus.BLOCKED);
                    node.setErrorCode("METRIC_TIMEOUT");
                    node.setCompletedAt(LocalDateTime.now(clock));
                    mapper.updateNodeExecution(node);
                }
            }
            flow.setStatus(FlowExecutionStatus.FAILED);
            flow.setSummaryJson(json(Map.of("errorCode", "METRIC_TIMEOUT", "missingMetrics", flow.getMissingMetricsJson())));
            releaseRepeatableGuard(flow);
            flow.setCompletedAt(LocalDateTime.now(clock));
            mapper.updateExecution(flow);
            completionService.sendInitial(flow);
        }
    }

    private void cancel(SkillFlowNodeExecution node, SkillFlowExecution flow) {
        node.setStatus(FlowNodeExecutionStatus.CANCELLED);
        node.setCompletedAt(LocalDateTime.now(clock));
        clearLease(node);
        mapper.updateNodeExecution(node);
        if (flow != null) advance(flow.getId());
    }

    /** 组装发给 AI 的最终问题:使用 Skill 展示名称 + 渲染后的用户问题;查询侧兜底渲染共用同一格式。 */
    static String buildPrompt(SkillFlowNodeExecution node, String renderedQuestion) {
        String skillName = node.getSkillName();
        if (skillName == null || skillName.isBlank()) {
            throw new IllegalStateException("SkillNameMissing");
        }
        return "调用" + skillName.trim() + "，" + renderedQuestion;
    }

    /**
     * 错误可重试分类:网络超时/连接失败/429/5xx/明确标注 temporary 的错误算可重试,
     * 沿 cause 链逐层检查;汇总收尾重试共用同一分类。
     */
    static boolean retryable(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SocketTimeoutException || current instanceof ConnectException
                    || current instanceof TimeoutException) return true;
            String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase();
            if (message.contains("429") || message.contains("rate limit")
                    || message.contains("timeout") || message.contains("temporar")
                    || message.matches(".*\\b5\\d\\d\\b.*")) return true;
            current = current.getCause();
        }
        return false;
    }

    private String json(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 从 AI 事件流提取回答文本:优先取最终结果,否则拼接增量 delta。 */
    private String extract(List<AgentEvent> events) {
        if (events == null) return "";
        for (AgentEvent event : events) {
            if (event instanceof AgentResultEvent result && result.getResult() != null
                    && !result.getResult().getTextContent().isBlank()) {
                return result.getResult().getTextContent();
            }
        }
        StringBuilder text = new StringBuilder();
        for (AgentEvent event : events) {
            if (event instanceof TextBlockDeltaEvent delta && delta.getDelta() != null) text.append(delta.getDelta());
        }
        return text.toString();
    }

    private void clearLease(SkillFlowNodeExecution node) {
        node.setLeaseOwner(null);
        node.setLeaseExpiresAt(null);
    }

    private void releaseRepeatableGuard(SkillFlowExecution flow) {
        if (flow.getTriggerType() != FlowTriggerType.AUTO_METRIC) flow.setActiveGuardKey(null);
    }

    /** 回写尝试(audit)记录的最终结果。 */
    private void completeAudit(SkillFlowNodeAttempt attempt, FlowNodeAttemptStatus status, boolean retryable,
                               String code, String message, LocalDateTime started) {
        attempt.setStatus(status);
        attempt.setRetryable(retryable);
        attempt.setErrorCode(code);
        attempt.setErrorMessage(message);
        attempt.setCompletedAt(LocalDateTime.now(clock));
        attempt.setDurationMs(Duration.between(started, attempt.getCompletedAt()).toMillis());
        mapper.updateAttempt(attempt);
    }

    private static String errorMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName()
                : e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    @PreDestroy
    void shutdown() {
        workers.shutdown();
        recoveryWorkers.shutdown();
    }
}
