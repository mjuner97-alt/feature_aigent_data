package com.agentscopea2a.v2.skillManager.service;

import com.agentscopea2a.v2.skillManager.flowcache.FlowNodeCacheService;
import com.agentscopea2a.v2.runner.HarnessA2aRunnerV2;
import com.agentscopea2a.v2.skillManager.config.SkillFlowProperties;
import com.agentscopea2a.v2.skillManager.entity.*;
import com.agentscopea2a.v2.skillManager.mapper.SkillFlowMapper;
import com.agentscopea2a.v2.tools.ToolResultRegistry;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeoutException;

/**
 * Skill Flow 执行协调器(后台工作线程的心脏):
 * <ol>
 *   <li>{@link #scan} 定时扫描可运行节点(QUEUED/RETRY_WAIT/租约过期),经
 *       {@link FlowNodeClaimService} 抢占认领后提交到工作线程池;同一周期内先兜底补建
 *       指标就绪但推送触发丢失的每日自动执行;</li>
 *   <li>{@link #executeNode} 渲染节点问题模板 -> 调 AI 执行该节点 skill,成功后推进;</li>
 *   <li>{@link #advance} 推进:放行等待节点、处理取消、全部终态后触发汇总与通知;</li>
 *   <li>{@link #expirePreviousDays} 跨天兜底:仍在等指标(WAITING_METRICS)的执行直接判失败。</li>
 * </ol>
 * 节点级并发用许可信号量(workerPermits)限流,流程级并发上限由执行快照 maxParallelismSnapshot 控制。
 */
@Component
@ConditionalOnProperty(prefix = "harness.a2a.skill-flow", name = "enabled", havingValue = "true", matchIfMissing = true)
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
    private final ToolResultRegistry toolResultRegistry;
    /** Python 节点直接执行注册脚本用(与脚本调试运行同一链路,含 params_schema 校验)。 */
    private final com.agentscopea2a.v2.tools.ScriptExecTool scriptExecTool;
    /** 流程执行生命周期服务:scan 周期里兜底补建"指标已就绪但未被推送触发"的每日自动执行。 */
    private final FlowExecutionService flowExecutionService;
    /** 节点结果缓存:同流程同节点同问题同数据日期的长任务结果复用(缓存异常自动降级为 miss)。 */
    private final FlowNodeCacheService nodeCache;
    /** 要求绕过缓存重新执行的节点执行 ID(手动重跑单个节点时置入,执行时取出)。 */
    private final Set<Long> refreshRequested = ConcurrentHashMap.newKeySet();

    public FlowCoordinator(SkillFlowMapper mapper, HarnessA2aRunnerV2 runner, ObjectMapper json, Clock skillFlowClock,
                           FlowCompletionService completionService, FlowNodeClaimService claimService,
                           NodeAttemptCompletionService attemptCompletionService,
                           ToolResultRegistry toolResultRegistry, FlowNodeCacheService nodeCache,
                           FlowExecutionService flowExecutionService,
                           com.agentscopea2a.v2.tools.ScriptExecTool scriptExecTool) {
        this.mapper = mapper;
        this.runner = runner;
        this.json = json;
        this.clock = skillFlowClock;
        this.completionService = completionService;
        this.claimService = claimService;
        this.attemptCompletionService = attemptCompletionService;
        this.toolResultRegistry = toolResultRegistry;
        this.nodeCache = nodeCache;
        this.flowExecutionService = flowExecutionService;
        this.scriptExecTool = scriptExecTool;
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

    /**
     * 定时心跳扫描(每 30 秒一轮,上一轮跑完后间隔 30 秒再跑下一轮),是长任务后台的"总调度循环":
     * <ol>
     *   <li>兜底补建自动执行 —— 指标已就绪但外部推送触发丢失的流程,在这里被补建为
     *       今日 AUTO_METRIC 执行(推模式正常时约 1 秒触发,扫描只是保险丝);</li>
     *   <li>跨天清理 —— 数据日期已过的等待执行判失败,释放占位;</li>
     *   <li>超时回收 —— 达到最大重试次数且租约过期的节点判失败,推进流程;</li>
     *   <li>派发执行 —— 认领可运行节点提交给工作线程池,这一步让上面三步产生的
     *       可运行节点(新建的 QUEUED/重试的 RETRY_WAIT/租约过期的 RUNNING)立即开跑,
     *       不用再等下一轮扫描。</li>
     * </ol>
     * 顺序有讲究:先清理(2、3 步)再派发(4 步),清理腾出的位置和状态在同一轮就能被派发利用;
     * 兜底补建(1 步)放最前,让新建执行的事务先提交,其 AFTER_COMMIT 事件会直接触发派发,
     * 4 步对它只是双保险。
     * </ol>
     * 开关:本 Bean 受 harness.a2a.skill-flow.enabled 控制(默认开启),关闭时整个协调器
     * 不注册,定时扫描自然不会执行。
     */
    @Scheduled(fixedDelay = SkillFlowProperties.SCAN_INTERVAL_MS)
    public void scan() {
        // 统一取当前时间:租约过期判断、跨天判断都用同一个时刻,避免一轮扫描内时钟漂移
        LocalDateTime now = LocalDateTime.now(clock);
        expirePreviousDays();       // 跨天兜底:仍在等指标(WAITING_METRICS)的执行直接判失败
        expireExhaustedNodes(now);  // 最终超时兜底:已达最大尝试次数且租约到期的节点直接判失败并推进流程
        dispatchRunnableNodes();    // 认领并调度可运行节点

        // 第 1 步:兜底补建自动触发执行。
        // 查"启用 + 当日全部依赖指标 READY + 当天还没有 AUTO_METRIC 执行"的流程,
        // 逐个走 createExecution 补建(事务提交后发 FlowQueuedEvent,AFTER_COMMIT 监听器会立刻派发)。
        // 与外部调 triggerByMetric 的推模式互补:推丢了、异步异常了,最迟 30 秒在这里补偿。
        flowExecutionService.scanAutoTriggerFlows();

        // 第 2 步:跨天兜底。数据日期是昨天或更早、还卡在等指标状态的执行,整体判 METRIC_TIMEOUT 失败,
        // 不让昨天的僵尸执行永远挂着。
        expirePreviousDays();

        // 第 3 步:最终超时兜底。节点已到最大尝试次数且租约也过期 = worker 死了或模型调用僵死,
        // 不再重试,直接判节点失败并推进流程(流程才能走到终态/汇总)。
        expireExhaustedNodes(now);

        // 第 4 步:派发可运行节点。QUEUED(新入队)/RETRY_WAIT(到重试时间)/RUNNING 但租约过期(要恢复)
        // 的节点,在 worker 许可用量允许的前提下抢占认领并提交线程池执行。
        // 正常路径下流程事务提交后就有事件监听立即派发,这里主要是兜底:事件丢失、
        // 上一轮 worker 许可不足、重启恢复等场景靠这 30 秒一扫收敛。
        dispatchRunnableNodes();
    }

    /**
     * 最终超时兜底：节点已经达到最大尝试次数，且最后一次执行租约也已到期，
     * 说明工作者死亡或模型调用长期无响应。此时不再重试，直接标记节点失败并推进流程，
     * 避免节点和流程永久显示“执行中”。尚未达到最大次数的过期节点由普通扫描逻辑重新认领重试。
     */
    private void expireExhaustedNodes(LocalDateTime now) {
        // 查询条件(见 selectExpiredExhaustedNodes):status=RUNNING + attempt_count>=max_attempts
        // + lease_expires_at < now,即"重试预算用完且最后一次尝试也失联"的节点
        for (SkillFlowNodeExecution node : mapper.selectExpiredExhaustedNodes(now)) {
            // 把该节点遗留的 RUNNING 尝试记录(audit 表)统一判失败,保持审计数据一致
            mapper.failRunningAttemptsForNode(node.getId(), now);
            // 节点落终态 FAILED,错误码 LEASE_EXPIRED 标明"不是业务失败,是基础设施失联"
            node.setStatus(FlowNodeExecutionStatus.FAILED);
            node.setErrorCode("LEASE_EXPIRED");
            node.setErrorMessage("Worker lease expired before completion");
            node.setCompletedAt(now);
            clearLease(node);            // 清空租约字段,节点不再被任何 worker 认领
            mapper.updateNodeExecution(node);
            // 推进流程:该节点终态后,下一个 PENDING 节点可以放行;若全部节点已终态则触发汇总收尾
            advance(node.getFlowExecutionId());
        }
    }

    /** 正常路径：流程事务提交后立即派发；定时 scan 仅作为恢复兜底。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onFlowQueued(FlowQueuedEvent event) {
        dispatchRunnableNodes();
    }

    private void dispatchRunnableNodes() {
        LocalDateTime now = LocalDateTime.now(clock);
        // 候选集 = QUEUED / 到重试时间的 RETRY_WAIT / 租约过期的 RUNNING(可恢复重试),
        // 且受 next_run_at(退避时间)、租约有效期、流程级 maxParallelism 三重过滤。
        // 候选只是"有资格跑",还必须 claim 原子抢占成功才真正执行。
        // selectRunnableNodes SQL 逻辑(mybatis/mapper/gauss/SkillFlowMapper.xml)：
        // 1) 状态候选：QUEUED / RETRY_WAIT；或「RUNNING 但租约已过期且 attempt_count < max_attempts」
        //    （原执行超时/失联，尝试次数未用尽仍可重试恢复）；
        // 2) 退避到期：next_run_at 为 NULL 或 <= now（失败重试的退避间隔未到不捞）；
        // 3) 租约空闲：lease_expires_at 为 NULL 或 < now（不捞走仍被其他 worker 持租执行中的节点）；
        // 4) 并行度门控：同一流程内「RUNNING 且租约未过期」的活跃节点数
        //    < max_parallelism_snapshot（流程执行记录上的快照，NULL 兜底 1）；
        // 5) 按 id 升序 LIMIT 100，防止单轮扫描刷库。
        // 注意：返回的只是「有资格跑」的候选，还须经 claimService.claim 原子抢占
        // （条件 UPDATE 切 RUNNING + 写租约，谁先执行谁赢）成功才真正提交执行，
        // 多实例部署时由此保证同一节点只被一个 worker 执行。safeNodes 仅过滤 null 元素做防御。
        List<SkillFlowNodeExecution> runnable = safeNodes(mapper.selectRunnableNodes(now));
        log.debug("Skill flow dispatch scan found {} runnable node(s)", runnable.size());
        for (SkillFlowNodeExecution node : runnable) {
            // RUNNING 且租约已过期 = 原执行超时/失联;这类恢复尝试走独立线程池,不占普通池许可
            boolean expiredAttempt = node.getStatus() == FlowNodeExecutionStatus.RUNNING
                    && node.getLeaseExpiresAt() != null
                    && node.getLeaseExpiresAt().isBefore(now);
            // 普通路径:池子满了(tryAcquire 失败)就停止本轮认领,剩下的留给下一轮扫描。
            // 恢复路径(租约过期的 RUNNING)绕过许可检查,保证模型调用僵死时重试仍能立即启动。
            if (!expiredAttempt && !workerPermits.tryAcquire()) break;
            // 原子抢占:把节点标记为本 worker 认领并写入新租约;失败说明被其他实例/线程抢先,让出许可
            if (claimService.claim(node.getId(), workerId, now.plusSeconds(SkillFlowProperties.LEASE_SECONDS), now)) {
                try {
                    // 失联恢复走 recoveryWorkers(弹性线程池,与普通池隔离),
                    // 否则旧调用占着普通池 worker,恢复任务永远排不上队
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
        String requestId = null;
        FlowNodeCacheService.Claim cacheClaim = null;
        String stage = "LOAD_EXECUTION";
        try {
            if (nodeId == null) throw new IllegalArgumentException("NodeExecutionIdMissing");
            node = mapper.selectNodeExecution(nodeId);
            if (node == null) return;
            Long flowExecutionId = Objects.requireNonNull(node.getFlowExecutionId(), "FlowExecutionIdMissing");
            flow = mapper.selectFlowExecutionById(flowExecutionId);
            // 流程已请求取消/已取消:节点直接置 CANCELLED
            if (flow == null || flow.getStatus() == FlowExecutionStatus.CANCEL_REQUESTED
                    || flow.getStatus() == FlowExecutionStatus.CANCELLED) {
                cancel(node, flow);
                return;
            }
            Objects.requireNonNull(flow.getStatus(), "FlowStatusMissing");
            Objects.requireNonNull(node.getStatus(), "NodeStatusMissing");
            started = LocalDateTime.now(clock);
            stage = "PREPARE_ATTEMPT";
            // 历史数据可能没有 attempt_count；避免 Integer 自动拆箱触发 NPE。
            attempt = node.getAttemptCount() == null ? 0 : node.getAttemptCount();
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
            stage = "BUILD_PROMPT";
            boolean scriptNode = node.getScriptId() != null && !node.getScriptId().isBlank();
            String template = node.getQuestionTemplateSnapshot();
            // Python 节点问题模板选填:空模板用"执行脚本 <scriptId>"做审计展示;Skill 节点沿用原始问题兜底
            String rendered = template == null || template.isBlank()
                    ? (scriptNode ? "执行脚本 " + node.getScriptId() : Objects.toString(flow.getOriginalQuestion(), ""))
                    : templates.render(template,
                    new FlowTemplateEngine.Context(Map.of(
                            // Map.of rejects null values; old flow snapshots may omit optional fields.
                            "server_date", Objects.toString(flow.getDataDate(), LocalDate.now(clock).toString()),
                            "original_question", Objects.toString(flow.getOriginalQuestion(), ""),
                            "flow_name", Objects.toString(flow.getFlowName(), ""),
                            "skill_name", Objects.toString(node.getSkillName(), ""))));
            String question = scriptNode ? rendered : buildPrompt(node, rendered);
            node.setRenderedQuestion(question);
            stage = "NODE_CACHE_LOOKUP";
            // 节点结果缓存:缓存 key 维度 = 节点(流程+nodeKey+脚本/Skill) + 版本(脚本 id / skillId) + 渲染后问题(输入) + 数据日期。
            // 手动重跑单个节点会置入 refreshRequested,要求绕过缓存重新执行。
            String cacheNodeId = scriptNode
                    ? "flow-" + flow.getFlowId() + "-" + node.getNodeKey() + "-script-" + node.getScriptId()
                    : "flow-" + flow.getFlowId() + "-" + node.getNodeKey() + "-skill-" + node.getSkillId();
            String cacheVersion = scriptNode ? node.getScriptId() : String.valueOf(node.getSkillId());
            boolean forceRefresh = refreshRequested.remove(nodeId);
            String result = nodeCache.hit(cacheNodeId, cacheVersion, question, null, flow.getDataDate(), forceRefresh)
                    .orElse(null); // miss/降级均为 null,照常执行
            boolean cacheHit = result != null;
            if (!cacheHit) {
                cacheClaim = nodeCache.claim(cacheNodeId, cacheVersion, question, null, flow.getDataDate(), forceRefresh);
                if (scriptNode) {
                    // Python 脚本节点:不走 AI,按脚本注册直接执行,参数来自执行快照
                    stage = "RUN_SCRIPT";
                    result = runScriptDirect(node);
                    if (result == null || result.isBlank()) throw new IllegalStateException("Script returned empty result");
                    // 只有抢占成功的执行才把结果写回缓存;输家照常返回结果但不落库、不覆盖赢家文件。
                    if (cacheClaim.won()) nodeCache.success(cacheClaim, result);
                } else {
                stage = "CREATE_RUNTIME_CONTEXT";
                String triggerUserId = requireText(flow.getTriggerUserId(), "TriggerUserIdMissing");
                RuntimeContext context = RuntimeContext.builder()
                        .sessionId("flow-" + flow.getId() + "-" + node.getNodeKey())
                        .userId(triggerUserId).build();
                requestId = java.util.UUID.randomUUID().toString();
                context.put(com.agentscopea2a.v2.hooks.ChatScriptExecResultHook.ENABLED_CTX_KEY, Boolean.TRUE);
                context.put(com.agentscopea2a.v2.hooks.ChatScriptExecResultHook.REQUEST_ID_CTX_KEY, requestId);
                stage = "RUN_SKILL";
                List<AgentEvent> events = runner.streamEvents(List.of(Msg.builder()
                                .role(MsgRole.USER).content(TextBlock.builder().text(question).build()).build()),
                        context).collectList().block(Duration.ofMinutes(
                        SkillFlowProperties.NODE_EXECUTION_TIMEOUT_MINUTES));
                stage = "EXTRACT_RESULT";
                result = toolResultRegistry.resolveAndAppendCurrentResults(
                        extract(events), toolResultRegistry.getRequestRefs(requestId));
                if (result == null || result.isBlank()) throw new IllegalStateException("Skill returned empty result");
                // 只有抢占成功的执行才把结果写回缓存;输家照常返回结果但不落库、不覆盖赢家文件。
                if (cacheClaim.won()) nodeCache.success(cacheClaim, result);
                }
            } else {
                log.info("Skill flow node {} served from node cache", nodeId);
            }
            // 软取消检查点:节点已跑完但结果尚未落库,此时流程已请求取消则丢弃结果——
            // 节点置 CANCELLED 并触发 advance 善后(后续节点不再执行,流程落终态 CANCELLED)。
            flow = mapper.selectFlowExecutionById(flow.getId());
            if (flow.getStatus() == FlowExecutionStatus.CANCEL_REQUESTED
                    || flow.getStatus() == FlowExecutionStatus.CANCELLED) {
                completeAudit(audit, FlowNodeAttemptStatus.CANCELLED, false, "CANCELLED",
                        "Flow cancelled before result persisted", started);
                cancel(node, flow);
                return;
            }
            String resultJson = json(Map.of("text", result));
            stage = "PERSIST_RESULT";
            completeAudit(audit, FlowNodeAttemptStatus.SUCCESS, false, null, null, started);
            if (attemptCompletionService.completeSuccess(node, attempt, attemptLeaseOwner,
                    resultJson, LocalDateTime.now(clock))) {
                stage = "ADVANCE_FLOW";
                advance(flow.getId());
            } else {
                log.info("Ignoring stale success for node {} attempt {}", nodeId, attempt);
            }
        } catch (Exception error) {
            // 长任务失败:回收自己抢占的缓存锁,避免留下 10 分钟的僵尸 BUILDING 挡住后续重跑。
            if (cacheClaim != null) {
                try {
                    nodeCache.failure(cacheClaim);
                } catch (Exception cacheCleanupError) {
                    log.warn("Failed to release node cache claim: nodeId={}", nodeId, cacheCleanupError);
                }
            }
            Long flowId = flow != null ? flow.getId() : node != null ? node.getFlowExecutionId() : null;
            FlowFailureDiagnostic diagnostic = FlowFailureDiagnostic.capture(error, stage, flowId, node);
            log.error("Skill flow node execution failed: errorId={}, stage={}, nodeId={}, flowId={}, attempt={}",
                    diagnostic.errorId(), diagnostic.stage(), nodeId, flowId, attempt, error);
            // 可重试错误(超时/429/5xx 等)且未到最大尝试次数 -> RETRY_WAIT,指数退避(1s/2s/4s/8s)
            // 老数据可能没有最大重试次数；按单次执行处理，不能让错误处理本身再次抛 NPE，
            // 否则节点会永远停留在 RUNNING，流程也无法进入最终状态。
            int maxAttempts = node == null || node.getMaxAttempts() == null ? 1 : Math.max(1, node.getMaxAttempts());
            boolean retryable = retryable(error) && attempt < maxAttempts;
            String errorCode = retryable ? "RETRYABLE_ERROR" : "EXECUTION_FAILED";
            String errorMessage = diagnostic.displayMessage();
            if (retryable && node != null) {
                node.setNextRunAt(LocalDateTime.now(clock).plusSeconds(1L << Math.min(attempt, 3)));
            }
            if (audit != null) {
                try {
                    completeAudit(audit, FlowNodeAttemptStatus.FAILED, retryable, errorCode, errorMessage, started);
                } catch (Exception auditError) {
                    // 错误收尾也可能遇到数据库异常；记录二次故障，但继续尝试回写节点终态。
                    log.error("Failed to persist skill flow attempt failure: nodeId={}, attempt={}",
                            nodeId, attempt, auditError);
                }
            } else {
                log.warn("Skill flow node {} failed before attempt record was created", nodeId);
            }
            if (node != null && flow != null) {
                try {
                    boolean completed = attemptCompletionService.completeFailure(node, attempt, attemptLeaseOwner,
                            retryable, errorCode, errorMessage, LocalDateTime.now(clock));
                    if (completed && !retryable) {
                        advance(flow.getId());
                    } else if (!completed) {
                        log.info("Ignoring stale failure for node {} attempt {}", nodeId, attempt);
                    }
                } catch (Exception persistenceError) {
                    log.error("Failed to persist skill flow node failure: nodeId={}, flowId={}, attempt={}",
                            nodeId, flow.getId(), attempt, persistenceError);
                }
            } else {
                log.error("Skill flow node {} failure could not be persisted (node/flow unavailable): {}",
                        nodeId, errorMessage);
            }
        } finally {
            if (requestId != null) {
                try {
                    toolResultRegistry.clearRequestRefs(requestId);
                } catch (Exception cleanupError) {
                    log.error("Failed to clear tool result references: nodeId={}, requestId={}",
                            nodeId, requestId, cleanupError);
                }
            }
        }
    }

    /** 推进流程状态:取消善后、放行等待节点、全部终态后汇总收尾。 */
    private void advance(Long flowId) {
        SkillFlowExecution flow = mapper.selectFlowExecutionById(flowId);
        if (flow == null) return;
        FlowExecutionStatus flowStatus = Objects.requireNonNull(flow.getStatus(), "FlowStatusMissing");
        List<SkillFlowNodeExecution> nodes = safeNodes(mapper.selectNodeExecutions(flow.getId()));
        if (nodes.isEmpty()) throw new IllegalStateException("FlowNodesMissing");
        // 取消中:所有未终态节点置 CANCELLED,流程落终态 CANCELLED
        if (flowStatus == FlowExecutionStatus.CANCEL_REQUESTED || flowStatus == FlowExecutionStatus.CANCELLED) {
            for (SkillFlowNodeExecution node : nodes) {
                if (node.getStatus() == null || !node.getStatus().terminal()) {
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
        boolean releasedNextNode = false;
        if (!activeNode) {
            var nextNode = nodes.stream().filter(n -> n.getStatus() == FlowNodeExecutionStatus.PENDING).findFirst();
            if (nextNode.isPresent()) {
                SkillFlowNodeExecution node = nextNode.get();
                node.setStatus(FlowNodeExecutionStatus.QUEUED);
                mapper.updateNodeExecution(node);
                releasedNextNode = true;
            }
        }
        // 节点完成后放行的后继节点不能只等定时扫描，否则正常串行流程会额外
        // 等待一个 scan 周期（当前为 30 秒）；扫描仅作为事件丢失/重启兜底。
        if (releasedNextNode) {
            dispatchRunnableNodes();
        }
        // 全部节点终态:抢占汇总权(claimExecutionForSummary 保证只汇总一次)后收尾
        nodes = safeNodes(mapper.selectNodeExecutions(flow.getId()));
        if (!nodes.isEmpty() && nodes.stream().allMatch(n -> n.getStatus() != null && n.getStatus().terminal())
                && mapper.claimExecutionForSummary(flow.getId()) == 1) {
            SkillFlowExecution summarizing = mapper.selectFlowExecutionById(flow.getId());
            if (summarizing == null) throw new IllegalStateException("SummarizingExecutionMissing");
            finalizeFlow(summarizing, nodes);
        }
    }

    /** 全部节点终态后的收尾:定最终状态 -> 汇总+报告 -> 发通知。 */
    private void finalizeFlow(SkillFlowExecution flow, List<SkillFlowNodeExecution> nodes) {
        Objects.requireNonNull(flow, "FlowExecutionMissing");
        List<SkillFlowNodeExecution> safeNodeList = safeNodes(nodes);
        if (safeNodeList.isEmpty()) throw new IllegalStateException("FlowNodesMissing");
        boolean requiredFailed = safeNodeList.stream().anyMatch(n -> Boolean.TRUE.equals(n.getRequired())
                && n.getStatus() != FlowNodeExecutionStatus.SUCCESS);
        boolean anyFailed = safeNodeList.stream().anyMatch(n -> n.getStatus() != FlowNodeExecutionStatus.SUCCESS);
        // 必需节点失败 -> FAILED;仅非必需节点失败 -> PARTIAL_SUCCESS;全成功 -> SUCCESS
        flow.setStatus(requiredFailed ? FlowExecutionStatus.FAILED
                : anyFailed ? FlowExecutionStatus.PARTIAL_SUCCESS : FlowExecutionStatus.SUCCESS);
        try {
            FlowCompletionService.Summary summary = completionService.summarize(flow, safeNodeList);
            flow.setSummaryJson(summary.summaryJson());
            flow.setReportPath(summary.reportPath());
        } catch (RuntimeException e) {
            // 汇总失败不吞掉执行结果:summary 里保留各节点状态明细
            FlowFailureDiagnostic diagnostic = FlowFailureDiagnostic.capture(
                    e, "GENERATE_SUMMARY", flow.getId(), null);
            log.error("Flow summary failed: errorId={}, stage={}, flowId={}",
                    diagnostic.errorId(), diagnostic.stage(), flow.getId(), e);
            flow.setSummaryJson(json(Map.of("summaryError", diagnostic.displayMessage(),
                    "nodes", safeNodeList.stream().map(n -> Map.of(
                            "nodeKey", Objects.toString(n.getNodeKey(), ""),
                            "nodeName", n.getNodeName() == null || n.getNodeName().isBlank() ? Objects.toString(n.getSkillName(), "") : n.getNodeName(),
                            "status", statusName(n),
                            "error", Objects.toString(n.getErrorMessage(), ""))).toList())));
            if (flow.getStatus() == FlowExecutionStatus.SUCCESS) flow.setStatus(FlowExecutionStatus.FAILED);
        }
        flow.setCompletedAt(LocalDateTime.now(clock));
        releaseRepeatableGuard(flow);
        mapper.updateExecution(flow);
        try {
            completionService.sendInitial(flow);
        } catch (RuntimeException e) {
            // 通知是终态后的附加动作，失败不能反向污染最后一个已成功节点。
            FlowFailureDiagnostic diagnostic = FlowFailureDiagnostic.capture(
                    e, "SEND_NOTIFICATION", flow.getId(), null);
            log.error("Flow notification failed: errorId={}, stage={}, flowId={}",
                    diagnostic.errorId(), diagnostic.stage(), flow.getId(), e);
        }
    }

    public void retrySummary(Long flowId) {
        SkillFlowExecution flow = mapper.selectFlowExecutionForUpdate(flowId);
        if (flow == null || flow.getStatus() == null || !(flow.getStatus() == FlowExecutionStatus.SUCCESS
                || flow.getStatus() == FlowExecutionStatus.FAILED
                || flow.getStatus() == FlowExecutionStatus.PARTIAL_SUCCESS))
            throw new IllegalStateException("执行当前不可重新生成汇总");
        List<SkillFlowNodeExecution> nodes = safeNodes(mapper.selectNodeExecutions(flowId));
        if (nodes.isEmpty()) throw new IllegalStateException("FlowNodesMissing");
        if (nodes.stream().anyMatch(n -> n.getStatus() == null || !n.getStatus().terminal()))
            throw new IllegalStateException("节点尚未全部结束");
        flow.setStatus(FlowExecutionStatus.SUMMARIZING);
        flow.setSummaryJson(null); flow.setReportPath(null); flow.setCompletedAt(null);
        mapper.updateExecution(flow);
        finalizeFlow(flow, nodes);
    }

    /**
     * 手动重跑单个节点。
     *
     * <p>该方法只负责把节点恢复为可调度状态，并唤醒调度器；真正的节点执行仍由
     * {@link #dispatchRunnableNodes()} 认领后提交给 worker 线程池完成。
     *
     * <p>允许从已经结束的流程中重跑，也允许在 RUNNING 流程中重跑已经结束的节点；
     * 正在执行或等待执行的节点不能重复重跑，避免同一节点产生并发执行。
     *
     * @param flowId 节点所属的流程执行 ID
     * @param nodeId 要重跑的节点执行 ID
     */
    public void retryNode(Long flowId, Long nodeId) {
        // 锁定流程记录，串行化同一流程上的重跑、取消等状态修改。
        SkillFlowExecution flow = mapper.selectFlowExecutionForUpdate(flowId);
        SkillFlowNodeExecution node = mapper.selectNodeExecution(nodeId);
        // 同时校验节点归属，防止拿其他流程的 nodeId 发起重跑。
        if (flow == null || node == null || !Objects.equals(flowId, node.getFlowExecutionId())) throw new IllegalArgumentException("节点不存在");
        // 流程必须仍在运行，或已经进入某个终态；中间状态（例如汇总中）不允许重跑。
        if (flow.getStatus() == null
                || (!flow.getStatus().terminal() && flow.getStatus() != FlowExecutionStatus.RUNNING))
            throw new IllegalStateException("执行当前不可重跑");
        // 仅终态节点可重跑，避免覆盖正在运行节点的租约、结果或尝试次数。
        if (!hasTerminalStatus(node))
            throw new IllegalStateException("节点当前不可重跑");
        // 清空上次执行的结果、错误、时间和租约，并恢复完整重试预算。
        resetForRetry(node);
        // 手动重跑单个节点 = 明确要求重新执行,绕过节点缓存(否则会直接秒回上次的缓存结果)。
        refreshRequested.add(nodeId);
        mapper.updateNodeExecution(node);
        // 终态流程必须先恢复为 RUNNING，否则 FlowNodeClaimService 会拒绝认领 QUEUED 节点，
        // 节点将永久停在“排队中”。旧汇总也已失效，等待节点完成后重新生成。
        flow.setStatus(FlowExecutionStatus.RUNNING);
        flow.setSummaryJson(null);
        flow.setReportPath(null);
        flow.setCompletedAt(null);
        mapper.updateExecution(flow);
        // 重新计算流程状态并放行满足依赖条件的节点。
        advance(flowId);
        // 立即扫描一次，尽快认领刚刚重新入队的节点。
        dispatchRunnableNodes();
        // 延迟再扫一次，兜底处理事务提交可见性或旧 worker 尚未释放许可的竞态。
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
        List<SkillFlowNodeExecution> failed = safeNodes(mapper.selectNodeExecutions(flowId)).stream()
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
        // 等指标执行的判死只发生在"跨天"时刻:数据日期 < 今天 说明这一天已经过去,
        // 指标再也不会为那个日期就绪(就绪记录按天登记,过期作废)。
        List<SkillFlowExecution> waiting = mapper.selectWaitingExecutions();
        for (SkillFlowExecution flow : waiting == null ? List.<SkillFlowExecution>of() : waiting) {
            // data_date 为空按今天处理(老数据容错),不误杀
            LocalDate dataDate = flow.getDataDate() == null ? today : flow.getDataDate();
            if (!dataDate.isBefore(today)) continue;   // 今天或未来的执行,不归跨天兜底管
            // 该执行下所有未终态的节点统一判 BLOCKED(依赖指标超时),错误码 METRIC_TIMEOUT
            for (SkillFlowNodeExecution node : safeNodes(mapper.selectNodeExecutions(flow.getId()))) {
                if (node.getStatus() == null || !node.getStatus().terminal()) {
                    node.setStatus(FlowNodeExecutionStatus.BLOCKED);
                    node.setErrorCode("METRIC_TIMEOUT");
                    node.setCompletedAt(LocalDateTime.now(clock));
                    mapper.updateNodeExecution(node);
                }
            }
            // 流程整体落 FAILED,summary 里记录超时错误码和缺失指标明细,便于排查哪天哪个指标没来
            flow.setStatus(FlowExecutionStatus.FAILED);
            flow.setSummaryJson(json(Map.of("errorCode", "METRIC_TIMEOUT", "missingMetrics",
                    Objects.toString(flow.getMissingMetricsJson(), ""))));
            releaseRepeatableGuard(flow);   // 释放幂等 guard,当天/以后可再次触发
            flow.setCompletedAt(LocalDateTime.now(clock));
            mapper.updateExecution(flow);
            // 发通知告知失败(通知失败不影响状态,发送内部自行兜底)
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

    /**
     * 组装发给 AI 的最终问题:优先用 Skill 检索名——框架按检索名加载/调用 Skill
     * (见 DatabaseSkillRepository),单 Skill 任务 SkillJobScheduler 同样用检索名;
     * 检索名缺失时回退展示名,两者都为空才抛 SkillNameMissing(而不是 NPE)。
     * 查询侧兜底渲染共用同一格式。
     */
    static String buildPrompt(SkillFlowNodeExecution node, String renderedQuestion) {
        String skillName = node.getSkillRetrievalName();
        if (skillName == null || skillName.isBlank()) {
            skillName = node.getSkillName();
        }
        if (skillName == null || skillName.isBlank()) {
            throw new IllegalStateException("SkillNameMissing");
        }
        return "调用" + skillName.trim() + "，" + renderedQuestion;
    }

    /**
     * Python 节点直接执行注册脚本:不走 AI,参数取执行快照的 script_params_json,
     * 校验与执行复用 ScriptExecTool(同脚本调试运行链路);失败(exit!=0/拒绝执行)抛异常走重试分类。
     */
    private String runScriptDirect(SkillFlowNodeExecution node) {
        Map<String, Object> params;
        try {
            params = node.getScriptParamsJson() == null || node.getScriptParamsJson().isBlank()
                    ? Map.of()
                    : json.readValue(node.getScriptParamsJson(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("ScriptParamsInvalid: " + e.getOriginalMessage());
        }
        String output = scriptExecTool.executeForDebug(node.getScriptId(), params);
        String text = output == null ? "" : output;
        // 成败判定与 ScriptDebugService 一致:stdout 带 exit= 非 0,或出现拒绝执行/启动失败字样
        java.util.regex.Matcher exit = java.util.regex.Pattern.compile("\\bexit=(-?\\d+)").matcher(text);
        if (exit.find() && Integer.parseInt(exit.group(1)) != 0) {
            throw new IllegalStateException("ScriptFailed exit=" + exit.group(1) + ": " + abbreviate(text));
        }
        if (text.contains("拒绝执行") || text.contains("启动失败")) {
            throw new IllegalStateException("ScriptRejected: " + abbreviate(text));
        }
        return text;
    }

    /** 错误信息截断:脚本输出可能很长,异常消息里只保留开头部分。 */
    private static String abbreviate(String text) {
        return text.length() <= 500 ? text : text.substring(0, 500) + "…(截断)";
    }

    /**
     * 错误可重试分类:网络超时/连接失败/429/5xx/明确标注 temporary 的错误算可重试,
     * 沿 cause 链逐层检查;汇总收尾重试共用同一分类。
     */
    static boolean retryable(Throwable error) {
        Throwable current = error;
        while (current != null) {
            // 历史数据/框架状态异常可能表现为 NPE。允许一次恢复性重试，
            // 由 NODE_MAX_ATTEMPTS=2 严格限制总次数，避免无限循环。
            if (current instanceof NullPointerException) return true;
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
            if (event instanceof AgentResultEvent result && result.getResult() != null) {
                String textContent = result.getResult().getTextContent();
                if (textContent != null && !textContent.isBlank()) return textContent;
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

    /**
     * 流程终态后释放幂等 guard,让同 guard key(用户:会话:流程:日期)能再次触发新一轮执行。
     *
     * <p>对所有触发类型一律释放(含 AUTO_METRIC):执行活跃期间 guard 保留,并发重复触发仍被
     * createExecution 的幂等复用挡住;终态后不清会导致当天后续触发被终态记录永久占用,
     * selectActiveExecution 不区分状态,复用到终态记录后静默不创建新执行。</p>
     */
    private void releaseRepeatableGuard(SkillFlowExecution flow) {
        flow.setActiveGuardKey(null);
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

    static List<SkillFlowNodeExecution> safeNodes(List<SkillFlowNodeExecution> nodes) {
        return nodes == null ? List.of() : nodes.stream().filter(Objects::nonNull).toList();
    }

    static String requireText(String value, String errorMessage) {
        if (value == null || value.isBlank()) throw new IllegalStateException(errorMessage);
        return value;
    }

    static String statusName(SkillFlowNodeExecution node) {
        return node == null || node.getStatus() == null ? "UNKNOWN" : node.getStatus().name();
    }

    static boolean hasTerminalStatus(SkillFlowNodeExecution node) {
        return node != null && node.getStatus() != null && node.getStatus().terminal();
    }

    @PreDestroy
    void shutdown() {
        workers.shutdown();
        recoveryWorkers.shutdown();
    }
}
