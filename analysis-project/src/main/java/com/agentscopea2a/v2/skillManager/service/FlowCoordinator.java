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

    private final SkillFlowMapper mapper;
    private final HarnessA2aRunnerV2 runner;
    private final ObjectMapper json;
    private final Clock clock;
    private final FlowTemplateEngine templates = new FlowTemplateEngine();
    /** 本实例的工作者标识,写入租约字段,多实例部署时用于区分谁认领了节点。 */
    private final String workerId = UUID.randomUUID().toString();
    /** 固定大小工作线程池:同一时刻最多 workerCount 个节点在执行。 */
    private final ExecutorService workers;
    /** 工作容量信号量:池子满时 scan 不再认领新节点,避免认领后无处执行。 */
    private final Semaphore workerPermits;
    private final FlowCompletionService completionService;
    private final FlowNodeClaimService claimService;

    public FlowCoordinator(SkillFlowMapper mapper, HarnessA2aRunnerV2 runner, ObjectMapper json, Clock skillFlowClock,
                           FlowCompletionService completionService, FlowNodeClaimService claimService) {
        this.mapper = mapper;
        this.runner = runner;
        this.json = json;
        this.clock = skillFlowClock;
        this.completionService = completionService;
        this.claimService = claimService;
        int workerCount = Math.max(1, SkillFlowProperties.WORKER_COUNT);
        this.workerPermits = new Semaphore(workerCount);
        this.workers = Executors.newFixedThreadPool(workerCount, r -> {
            Thread t = new Thread(r, "skill-flow-worker");
            t.setDaemon(true);
            return t;
        });
    }

    /** 定时扫描并调度可运行节点;总开关/工作开关任一关闭则直接空转。 */
    @Scheduled(fixedDelay = SkillFlowProperties.SCAN_INTERVAL_MS)
    public void scan() {
        if (!SkillFlowProperties.ENABLED || !SkillFlowProperties.WORKER_ENABLED) return;
        expirePreviousDays();
        dispatchRunnableNodes();
    }

    /** 正常路径：流程事务提交后立即派发；定时 scan 仅作为恢复兜底。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onFlowQueued(FlowQueuedEvent event) {
        if (!SkillFlowProperties.ENABLED || !SkillFlowProperties.WORKER_ENABLED) return;
        dispatchRunnableNodes();
    }

    private void dispatchRunnableNodes() {
        LocalDateTime now = LocalDateTime.now(clock);
        for (SkillFlowNodeExecution node : mapper.selectRunnableNodes(now)) {
            if (!workerPermits.tryAcquire()) break; // 池子已满,本轮不再认领
            if (claimService.claim(node.getId(), workerId, now.plusSeconds(SkillFlowProperties.LEASE_SECONDS), now)) {
                try {
                    workers.submit(() -> {
                        try {
                            executeNode(node.getId());
                        } finally {
                            workerPermits.release();
                        }
                    });
                } catch (RejectedExecutionException e) {
                    workerPermits.release();
                    throw e;
                }
            } else {
                workerPermits.release(); // 认领失败(被别人抢走/并发超限),让出许可
            }
        }
    }

    /** 执行单个节点:渲染模板 -> 调 skill -> 写结果并推进;失败按可重试分类决定重试或终止。 */
    private void executeNode(Long nodeId) {
        SkillFlowNodeExecution node = mapper.selectNodeExecution(nodeId);
        if (node == null) return;
        SkillFlowExecution flow = mapper.selectFlowExecutionById(node.getFlowExecutionId());
        // 流程已请求取消/已取消:节点直接置 CANCELLED
        if (flow == null || flow.getStatus() == FlowExecutionStatus.CANCEL_REQUESTED
                || flow.getStatus() == FlowExecutionStatus.CANCELLED) {
            cancel(node, flow);
            return;
        }
        LocalDateTime started = LocalDateTime.now(clock);
        int attempt = node.getAttemptCount();
        // 认领成功但可能有上次遗留的 RUNNING 尝试记录,先作废再开新尝试
        mapper.failRunningAttemptsForNode(nodeId, started);
        SkillFlowNodeAttempt audit = SkillFlowNodeAttempt.builder()
                .nodeExecutionId(nodeId).attemptNo(attempt)
                .status(FlowNodeAttemptStatus.RUNNING).retryable(false).startedAt(started).build();
        mapper.insertAttempt(audit);
        flow.setStatus(FlowExecutionStatus.RUNNING);
        if (flow.getStartedAt() == null) flow.setStartedAt(started);
        mapper.updateExecution(flow);
        try {
            String rendered = templates.render(node.getQuestionTemplateSnapshot(),
                    new FlowTemplateEngine.Context(Map.of(
                            "server_date", flow.getDataDate().toString(),
                            "original_question", flow.getOriginalQuestion(),
                            "flow_name", flow.getFlowName(),
                            "skill_name", node.getSkillName())));
            String question = buildPrompt(node.getSkillRetrievalName(), rendered);
            node.setRenderedQuestion(question);
            RuntimeContext context = RuntimeContext.builder()
                    .sessionId("flow-" + flow.getId() + "-" + node.getNodeKey())
                    .userId(flow.getTriggerUserId()).build();
            List<AgentEvent> events = runner.streamEvents(List.of(Msg.builder()
                            .role(MsgRole.USER).content(TextBlock.builder().text(question).build()).build()),
                    context).collectList().block(Duration.ofMinutes(10));
            String result = extract(events);
            if (result == null || result.isBlank()) throw new IllegalStateException("Skill returned empty result");
            node.setResultJson(json(Map.of("text", result)));
            node.setStatus(FlowNodeExecutionStatus.SUCCESS);
            node.setCompletedAt(LocalDateTime.now(clock));
            clearLease(node);
            completeAudit(audit, FlowNodeAttemptStatus.SUCCESS, false, null, null, started);
            mapper.updateNodeExecution(node);
            advance(flow.getId());
        } catch (Exception error) {
            // 可重试错误(超时/429/5xx 等)且未到最大尝试次数 -> RETRY_WAIT,指数退避(1s/2s/4s/8s)
            boolean retryable = retryable(error) && attempt < node.getMaxAttempts();
            node.setErrorCode(retryable ? "RETRYABLE_ERROR" : "EXECUTION_FAILED");
            node.setErrorMessage(errorMessage(error));
            clearLease(node);
            if (retryable) {
                node.setStatus(FlowNodeExecutionStatus.RETRY_WAIT);
                node.setNextRunAt(LocalDateTime.now(clock).plusSeconds(1L << Math.min(attempt, 3)));
            } else {
                node.setStatus(FlowNodeExecutionStatus.FAILED);
                node.setCompletedAt(LocalDateTime.now(clock));
            }
            completeAudit(audit, FlowNodeAttemptStatus.FAILED, retryable, node.getErrorCode(), node.getErrorMessage(), started);
            mapper.updateNodeExecution(node);
            if (!retryable) advance(flow.getId());
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
        // 节点全并行:PENDING(等指标期间遗留,含旧依赖快照)直接放行进入队列
        for (SkillFlowNodeExecution node : nodes) {
            if (node.getStatus() == FlowNodeExecutionStatus.PENDING) {
                node.setStatus(FlowNodeExecutionStatus.QUEUED);
                mapper.updateNodeExecution(node);
            }
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

    /** 组装发给 AI 的最终问题:点名要调用的 skill(检索名)+ 渲染后的用户问题。 */
    private static String buildPrompt(String skillRetrievalName, String renderedQuestion) {
        if (skillRetrievalName == null || skillRetrievalName.isBlank()) {
            throw new IllegalStateException("SkillRetrievalNameMissing");
        }
        return "调用" + skillRetrievalName.trim() + "，" + renderedQuestion;
    }

    /**
     * 错误可重试分类:网络超时/连接失败/429/5xx/明确标注 temporary 的错误算可重试,
     * 沿 cause 链逐层检查。
     */
    private static boolean retryable(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SocketTimeoutException || current instanceof ConnectException
                    || current instanceof TimeoutException) return true;
            String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase();
            if (message.contains("429") || message.contains("rate limit")
                    || message.matches(".*\\b5\\d\\d\\b.*") || message.contains("temporar")) return true;
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
    }
}
