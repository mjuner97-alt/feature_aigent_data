/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.agentscopea2a.v2.skillManager.scheduler;

import com.agentscopea2a.v2.config.HarnessRunnerProperties;
import com.agentscopea2a.v2.runner.HarnessA2aRunnerV2;
import com.agentscopea2a.v2.skillManager.entity.SkillJob;
import com.agentscopea2a.v2.skillManager.entity.SkillJobExecution;
import com.agentscopea2a.v2.skillManager.mapper.SkillJobMapper;
import com.agentscopea2a.v2.skillManager.mapper.SkillMapper;
import com.agentscopea2a.v2.tools.WriteMarkdownTool;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * SkillJob 调度核心。
 *
 * <p>使用固定大小线程池 + 有界队列排队执行，支持并行度和失败重试配置。
 * 外部任务完成后通过 HTTP 接口触发，多个触发请求会自动排队。
 *
 * <p>同一 Job 同时只允许一个执行实例（排队或运行中），重复触发直接拒绝，
 * 避免重复调 AI / 重复生成 MD。
 *
 * <p>执行记录生命周期：触发时即落一条 PENDING 记录（拿到真实 id 返回前端），
 * 调度执行时复用该记录更新为 RUNNING -> SUCCESS/FAILED/SKIPPED，
 * 重试不新建记录，只在同一条上更新状态。
 *
 * <p>并行度、重试次数、退避时间等参数以常量定义在本类中，便于集中维护。
 */
@Component
@ConditionalOnProperty(prefix = "harness.a2a.skill-job", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SkillJobScheduler implements WriteCallback {

    private static final Logger log = LoggerFactory.getLogger(SkillJobScheduler.class);

    // ==================== 可调常量 ====================

    /** 并行执行线程数：同时最多几个 Job 并行执行 */
    private static final int MAX_PARALLEL = 2;

    /** 执行队列容量：超出后拒绝新提交（防止触发暴增时无限堆积） */
    private static final int QUEUE_CAPACITY = 100;

    /** 失败后最大重试次数（不含首次执行） */
    private static final int MAX_RETRY_ATTEMPTS = 3;

    /** 重试初始退避时间（毫秒），后续每次乘以 {@link #RETRY_BACKOFF_MULTIPLIER} */
    private static final long RETRY_INITIAL_BACKOFF_MS = 2_000L;

    /** 退避时间倍数（指数退避） */
    private static final double RETRY_BACKOFF_MULTIPLIER = 2.0;

    // ==================== ====================

    private final HarnessA2aRunnerV2 runner;
    private final SkillJobMapper mapper;
    private final SkillMapper skillMapper;
    private final HarnessRunnerProperties.SkillJobConfig config;
    private final Path workspace;
    private final WriteMarkdownTool writeMarkdownTool;

    /** 固定大小线程池 + 有界队列：控制并行度，队列满时拒绝新提交 */
    private final ExecutorService executor = new ThreadPoolExecutor(
            MAX_PARALLEL, MAX_PARALLEL,
            0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(QUEUE_CAPACITY),
            r -> {
                Thread t = new Thread(r, "skill-job-executor");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy());

    /**
     * 重试退避调度器：单线程 daemon，仅用于延迟后把重试重新入队到 {@link #executor}。
     * 退避等待期间不占用 worker 线程，避免重试 sleep 拖垮实际并行度。
     */
    private final ScheduledExecutorService retryScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "skill-job-retry");
        t.setDaemon(true);
        return t;
    });

    /** 正在执行或排队中的 jobId 集合，用于去重，避免同一 Job 重复触发并行执行 */
    private final ConcurrentHashMap<Long, Boolean> runningFlags = new ConcurrentHashMap<>();

    /** per-Execution 的 MD 写入标记，WriteMarkdownTool 回调时置 true */
    private final ConcurrentHashMap<Long, Boolean> mdWrittenFlags = new ConcurrentHashMap<>();

    /** per-Execution 的实际写入文件绝对路径，WriteMarkdownTool 回调时设置 */
    private final ConcurrentHashMap<Long, String> mdWrittenPaths = new ConcurrentHashMap<>();

    public SkillJobScheduler(
            HarnessA2aRunnerV2 runner,
            SkillJobMapper mapper,
            SkillMapper skillMapper,
            HarnessRunnerProperties properties,
            WriteMarkdownTool writeMarkdownTool) {
        this.runner = runner;
        this.mapper = mapper;
        this.skillMapper = skillMapper;
        this.config = properties.getSkillJob();
        this.workspace = Paths.get(properties.getWorkspace().getPath()).toAbsolutePath();
        this.writeMarkdownTool = writeMarkdownTool;
        log.info("SkillJobScheduler: workspace={}, timeout={}s, maxParallel={}, queueCap={}, maxRetry={}, backoff={}ms*x{}",
                workspace, config.getExecutionTimeoutSeconds(),
                MAX_PARALLEL, QUEUE_CAPACITY, MAX_RETRY_ATTEMPTS, RETRY_INITIAL_BACKOFF_MS, RETRY_BACKOFF_MULTIPLIER);
    }

    /**
     * 应用启动时恢复僵尸执行记录：把进程退出时仍处于 RUNNING/PENDING 的记录标记为 FAILED。
     * 否则这些记录会永远停留在"运行中"，前端列表堆积假记录。
     */
    @PostConstruct
    public void recoverStaleExecutions() {
        try {
            int n = mapper.markStaleRunningAsFailed();
            if (n > 0) {
                log.warn("Recovered {} stale RUNNING/PENDING execution(s) to FAILED on startup", n);
            }
        } catch (Exception e) {
            log.warn("Failed to recover stale executions on startup: {}", e.getMessage());
        }
    }

    /**
     * 提交 Job 到队列排队执行。调用后立即返回。
     *
     * <p>同一 Job 若已有实例在排队或运行中，直接拒绝（返回 false），避免重复执行。
     * 队列满时抛 {@code JobQueueFull}。
     *
     * @param executionId 调用方预先落库的 PENDING 执行记录 id
     * @return true=已入队；false=已有实例在跑，拒绝重复触发
     */
    public boolean submit(Long jobId, Long executionId) {
        if (runningFlags.putIfAbsent(jobId, Boolean.TRUE) != null) {
            log.warn("Job {} already running/queued, skip duplicate submit", jobId);
            return false;
        }
        try {
            executor.submit(() -> executeJobAttempt(jobId, executionId, 1, null));
            return true;
        } catch (RejectedExecutionException e) {
            runningFlags.remove(jobId);
            log.error("Job {} submit rejected, queue full (cap={})", jobId, QUEUE_CAPACITY, e);
            throw new IllegalStateException("JobQueueFull: 执行队列已满，请稍后重试 (jobId=" + jobId + ")");
        }
    }

    /**
     * 执行一次 Job 尝试（可重入：重试由 {@link #retryScheduler} 延迟后重新入队到 {@link #executor}）。
     *
     * <p>重试退避等待不再用 {@code Thread.sleep} 阻塞 worker 线程，而是 schedule 延迟任务把
     * 下一次尝试重新提交到主线程池，等待期间 worker 可服务其它 Job。
     *
     * <p>首次失败原因通过 {@code firstError} 透传：放弃重试时若首因与末次错误不同，写回 execution，
     * 避免被末次错误覆盖导致根因丢失。
     *
     * <p>无论终态如何（成功/跳过/放弃），只要本次未调度重试，{@code finally} 即清理
     * {@code runningFlags}，确保 Job 可被再次触发——此前终态从不清理导致 Job 首次执行后永久卡死。
     * 调度了重试则保留标记，等重试的终态尝试清理。
     *
     * @param attempt     当前尝试序号，从 1 开始
     * @param firstError  首次失败的 errorMsg（重试透传用），首次为 null
     */
    private void executeJobAttempt(Long jobId, Long executionId, int attempt, String firstError) {
        boolean retryScheduled = false;
        try {
            SkillJobExecution execution = doExecuteJob(jobId, executionId, attempt);
            // doExecuteJob 正常不会返回 null，防御性处理避免 NPE
            if (execution == null) {
                log.error("Job {} execution {} returned null, abort retry", jobId, executionId);
                return;
            }
            if ("SUCCESS".equals(execution.getStatus())) {
                return;
            }
            // SKIPPED：永久性跳过，重试无意义
            if ("SKIPPED".equals(execution.getStatus())) {
                log.info("Job {} skipped ({})", jobId, execution.getErrorMsg());
                return;
            }
            // FAILED：捕获首次失败原因，判断是否重试
            if (firstError == null && execution.getErrorMsg() != null) {
                firstError = execution.getErrorMsg();
            }
            if (attempt <= MAX_RETRY_ATTEMPTS) {
                long backoffMs = calculateBackoff(attempt);
                log.warn("Job {} attempt {}/{} failed, retry in {}ms (off worker thread)",
                        jobId, attempt, MAX_RETRY_ATTEMPTS, backoffMs);
                scheduleRetry(jobId, executionId, attempt + 1, firstError, backoffMs);
                retryScheduled = true;
            } else {
                log.error("Job {} failed after {} attempts, giving up", jobId, attempt);
                preserveFirstError(execution, firstError);
            }
        } catch (Exception e) {
            log.error("Job {} attempt {}/{} unexpected error", jobId, attempt, MAX_RETRY_ATTEMPTS, e);
            if (firstError == null) firstError = e.getMessage();
            if (attempt <= MAX_RETRY_ATTEMPTS) {
                long backoffMs = calculateBackoff(attempt);
                log.warn("Job {} retrying in {}ms (off worker thread)", jobId, backoffMs);
                scheduleRetry(jobId, executionId, attempt + 1, firstError, backoffMs);
                retryScheduled = true;
            } else {
                log.error("Job {} failed after {} attempts, giving up", jobId, attempt);
            }
        } finally {
            // 仅在本次未调度重试时清理 runningFlags；调度了重试则保留至重试终态清理
            if (!retryScheduled) {
                runningFlags.remove(jobId);
            }
        }
    }

    /**
     * 延迟后将重试尝试重新入队到 {@link #executor}。退避等待在 {@link #retryScheduler} 上完成，
     * 不占用 worker 线程。调度器不可用时直接放弃并清理 runningFlags。
     */
    private void scheduleRetry(Long jobId, Long executionId, int nextAttempt, String firstError, long backoffMs) {
        try {
            retryScheduler.schedule(() -> resubmitRetry(jobId, executionId, nextAttempt, firstError),
                    backoffMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            log.error("Job {} retry scheduler unavailable, giving up", jobId, e);
            runningFlags.remove(jobId);
            markExecutionFailed(executionId, "重试调度失败（调度器已关闭）", firstError);
        }
    }

    /** 把重试尝试提交回主线程池（受 MAX_PARALLEL 并发约束）；队列满则放弃并清理。 */
    private void resubmitRetry(Long jobId, Long executionId, int attempt, String firstError) {
        try {
            executor.submit(() -> executeJobAttempt(jobId, executionId, attempt, firstError));
        } catch (RejectedExecutionException e) {
            log.error("Job {} retry resubmit rejected (queue full), giving up", jobId, e);
            runningFlags.remove(jobId);
            markExecutionFailed(executionId, "重试重新入队失败（执行队列已满）", firstError);
        }
    }

    /**
     * 放弃重试时把首次失败原因写回 execution，避免被末次错误覆盖导致根因丢失。
     * 首因与末次相同则不重复写。
     */
    private void preserveFirstError(SkillJobExecution execution, String firstError) {
        if (firstError == null || firstError.isBlank()) return;
        if (firstError.equals(execution.getErrorMsg())) return;
        execution.setErrorMsg("首次失败: " + firstError + " | 末次失败: " + execution.getErrorMsg());
        try {
            mapper.updateExecutionStatus(execution);
        } catch (Exception ex) {
            log.warn("Failed to persist first-error for execution {}", execution.getId(), ex);
        }
    }

    /** 重试无法继续时把 execution 标记为 FAILED 并附上首因。 */
    private void markExecutionFailed(Long executionId, String reason, String firstError) {
        try {
            SkillJobExecution exec = mapper.selectExecutionById(executionId);
            if (exec == null) return;
            exec.setStatus("FAILED");
            exec.setErrorMsg(reason + (firstError != null && !firstError.isBlank()
                    ? "；首次失败: " + firstError : ""));
            if (exec.getCompletedAt() == null) exec.setCompletedAt(LocalDateTime.now());
            mapper.updateExecutionStatus(exec);
        } catch (Exception ex) {
            log.warn("Failed to mark execution {} FAILED after retry give-up", executionId, ex);
        }
    }

    /**
     * 计算第 N 次重试的退避时间（指数退避）。
     * attempt=1 -> RETRY_INITIAL_BACKOFF_MS
     * attempt=2 -> RETRY_INITIAL_BACKOFF_MS * MULTIPLIER
     * attempt=3 -> RETRY_INITIAL_BACKOFF_MS * MULTIPLIER^2
     */
    private long calculateBackoff(int attempt) {
        return (long) (RETRY_INITIAL_BACKOFF_MS * Math.pow(RETRY_BACKOFF_MULTIPLIER, attempt - 1));
    }

    /**
     * 执行一次 Job（复用调用方预先落库的 PENDING execution 记录，全程 update 同一条）：
     * 1. 查配置 -> 2. 模板替换 -> 3. 标记 RUNNING -> 4. 调用 AI Runner -> 5. 校验 MD 文件 -> 6. 更新终态
     *
     * <p>永久性错误（任务不存在/已禁用/未配置/创建人无 Skill 权限）标记为 SKIPPED 并返回，
     * 不抛异常、不重试、不返回 null。
     *
     * @return 执行记录（含最终状态）；仅当 executionId 查不到记录时返回 null
     */
    private SkillJobExecution doExecuteJob(Long jobId, Long executionId, int attempt) {
        SkillJobExecution execution = mapper.selectExecutionById(executionId);
        if (execution == null) {
            log.error("Execution {} not found for job {}", executionId, jobId);
            return null;
        }
        try {
            // 1. 查询 Job 配置
            SkillJob job = mapper.selectJobById(jobId);
            if (job == null) {
                log.error("Job {} not found", jobId);
                return markSkipped(execution, "JobNotFound: 任务不存在 (id=" + jobId + ")");
            }
            // 定时/外部触发统一以 createdBy 身份执行，确保只能执行其有权限的 Skill
            String userId = job.getCreatedBy();
            if (!Boolean.TRUE.equals(job.getEnabled())) {
                log.info("Job {} is disabled, skip", jobId);
                return markSkipped(execution, "JobDisabled: 任务已禁用 (id=" + jobId + ")");
            }

            // 校验关键字段：预定义任务必须先在页面编辑完善后才能执行
            if (job.getSkillId() == null || job.getQuestionTemplate() == null || job.getQuestionTemplate().isBlank()) {
                log.warn("Job {} not configured yet (skillId={}, questionTemplate={}), skip",
                        jobId, job.getSkillId(), job.getQuestionTemplate());
                return markSkipped(execution, "JobNotConfigured: 任务尚未配置完整 (skillId/提问模板缺失)");
            }

            // 会话 ID 提前生成
            String conversationId = "job-" + jobId + "-" + System.currentTimeMillis();

            // 执行前校验 createdBy 仍拥有该 Skill 的使用权限：Skill 可能已被删除/取消引用/禁用/撤回发布
            if (!skillAvailableToUser(job.getSkillId(), userId)) {
                log.warn("Job {} skipped: createdBy={} no longer has access to skill {}",
                        jobId, userId, job.getSkillId());
                return markSkipped(execution, "SkillPermissionDenied: 创建人已无该 Skill 的使用权限");
            }

            // 2. 拼接完整提问：调用{skillName} + 用户问题（MD写入由Java代码直接调用WriteMarkdownTool）
            String skillName = resolveSkillName(job.getSkillId());
            String resolvedOutputPath = resolveOutputPath(job.getOutputPath());
            String question = buildQuestion(job.getQuestionTemplate(), skillName);

            // 3. 标记为 RUNNING（复用同一条 execution 记录，重试不新建记录）
            execution.setStatus("RUNNING");
            execution.setConversationId(conversationId);
            execution.setResolvedOutputPath(resolvedOutputPath);
            execution.setStartedAt(LocalDateTime.now());
            execution.setMdFileWritten(false);
            execution.setMdFileExists(false);
            execution.setErrorMsg(attempt > 1 ? "重试第" + attempt + "次" : null);
            execution.setCompletedAt(null);
            mapper.updateExecutionStatus(execution);

            // 4. 调用 AI Runner，使用 Job 创建人的 userId，确保只能执行自己有权限的 Skill
            RuntimeContext ctx = RuntimeContext.builder()
                    .sessionId(conversationId)
                    .userId(userId)
                    .build();

            try {
                Msg userMsg = Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text(question).build())
                        .build();
                // 收集所有事件，用于提取AI最终文本结果
                List<AgentEvent> events = runner.streamEvents(List.of(userMsg), ctx)
                        .collectList()
                        .block(Duration.ofSeconds(config.getExecutionTimeoutSeconds()));

                // 提取AI最终文本结果
                String agentResult = extractFinalText(events);

                // 直接用Java代码调用WriteMarkdownTool写入，不走模型tool_call
                if (agentResult != null && !agentResult.isBlank() && resolvedOutputPath != null && !resolvedOutputPath.isBlank()) {
                    String mdFileName = buildMdFileName(job);
                    boolean writeOk = writeMarkdownTool.writeMarkdown(mdFileName, agentResult, execution.getId(), userId);
                    if (!writeOk) {
                        log.warn("Job {} direct write markdown failed: userId={}, fileName={}", jobId, userId, mdFileName);
                    } else {
                        log.info("Job {} direct write markdown success: userId={}, fileName={}", jobId, userId, mdFileName);
                    }
                } else {
                    log.warn("Job {} agent returned empty result or no output path, skip write", jobId);
                }
            } catch (Exception e) {
                log.error("Job {} execution failed (attempt {})", jobId, attempt, e);
                execution.setStatus("FAILED");
                execution.setErrorMsg(e.getMessage());
                execution.setCompletedAt(LocalDateTime.now());
                mapper.updateExecutionStatus(execution);
                return execution;
            }

            // 5. 校验 MD 文件是否生成（优先使用回调返回的实际写入路径）
            String actualFilePath = mdWrittenPaths.get(execution.getId());
            boolean verified = verifyResult(actualFilePath);
            execution.setMdFileWritten(mdWrittenFlags.getOrDefault(execution.getId(), false));
            execution.setMdFileExists(verified);
            if (actualFilePath != null && !actualFilePath.isBlank()) {
                execution.setResolvedOutputPath(actualFilePath);
            }
            execution.setStatus(verified ? "SUCCESS" : "FAILED");
            if (verified) {
                execution.setErrorMsg(attempt > 1 ? "重试第" + attempt + "次后成功" : null);
            } else {
                execution.setErrorMsg("MD文件未生成" + (attempt <= MAX_RETRY_ATTEMPTS ? "，将重试" : ""));
            }
            execution.setCompletedAt(LocalDateTime.now());
            mapper.updateExecutionStatus(execution);

            log.info("Job {} attempt {} done, status={}, file={}", jobId, attempt, execution.getStatus(), actualFilePath);
        } catch (Exception e) {
            log.error("Job {} attempt {} execution error", jobId, attempt, e);
            execution.setStatus("FAILED");
            execution.setErrorMsg(e.getMessage());
            execution.setCompletedAt(LocalDateTime.now());
            try {
                mapper.updateExecutionStatus(execution);
            } catch (Exception ex) {
                log.error("Failed to persist FAILED status for execution {}", execution.getId(), ex);
            }
        } finally {
            mdWrittenFlags.remove(execution.getId());
            mdWrittenPaths.remove(execution.getId());
        }
        return execution;
    }

    /** 将 execution 标记为 SKIPPED（永久性跳过，不重试）并落库 */
    private SkillJobExecution markSkipped(SkillJobExecution execution, String reason) {
        LocalDateTime now = LocalDateTime.now();
        execution.setStatus("SKIPPED");
        execution.setErrorMsg(reason);
        execution.setStartedAt(now);
        execution.setCompletedAt(now);
        mapper.updateExecutionStatus(execution);
        return execution;
    }

    /**
     * 校验 MD 文件是否存在且非空。
     * filePath 为 WriteMarkdownTool 回调返回的绝对路径。
     */
    private boolean verifyResult(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return false;
        }
        try {
            Path mdFile = Paths.get(filePath).normalize().toAbsolutePath();
            // 基本安全：路径必须在 /data/skill-files/ 下
            Path baseDir = Paths.get("/data/skill-files/").normalize().toAbsolutePath();
            if (!mdFile.startsWith(baseDir)) {
                log.warn("Path traversal detected: {}", filePath);
                return false;
            }
            return Files.exists(mdFile) && Files.size(mdFile) > 0;
        } catch (Exception e) {
            log.warn("Failed to verify MD file: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 拼接完整提问内容：
     * "调用{skillName}，{用户问题}。"
     * MD写入由Java代码直接调用WriteMarkdownTool完成，不再依赖AI调用tool。
     */
    private String buildQuestion(String userQuestion, String skillName) {
        String q = (userQuestion == null || userQuestion.isBlank()) ? "进行分析" : userQuestion.trim();
        return "调用" + skillName + "，" + q + "。";
    }

    /** 解析输出路径，替换 {date} 变量 */
    private String resolveOutputPath(String outputPath) {
        if (outputPath == null || outputPath.isBlank()) return "";
        return outputPath.replace("{date}", LocalDate.now().toString());
    }

    /**
     * 构建 MD 文件名（相对 /data/skill-files/{userId}/ 的子路径）。
     * 规则：{jobName}_{date}_{timestamp}.md，确保每次执行生成独立文件。
     */
    private String buildMdFileName(SkillJob job) {
        String safeName = job.getName() != null ? job.getName().replaceAll("[^a-zA-Z0-9_\\-]", "_") : "job";
        return safeName + "_" + LocalDate.now() + "_" + System.currentTimeMillis() + ".md";
    }

    private String resolveSkillName(Long skillId) {
        if (skillId == null) return "unknown";
        try {
            var skill = skillMapper.selectById(skillId);
            return skill != null ? skill.getName() : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * 校验 createdBy 是否仍拥有指定 Skill 的使用权限（available）。
     * 与 SkillManageService.list() 的 available 判定一致：
     * ACTIVE 未软删 && (本人创建 || 已引用 || 所属维度已发布) && 未被该用户禁用。
     * 查询异常时按"无权限"处理，避免误以失效身份执行。
     */
    private boolean skillAvailableToUser(Long skillId, String userId) {
        if (skillId == null || userId == null || userId.isBlank()) {
            return false;
        }
        try {
            return skillMapper.selectSkillAvailableForUser(skillId, userId);
        } catch (Exception e) {
            log.warn("Failed to check skill availability for skill={} user={}: {}",
                    skillId, userId, e.getMessage());
            return false;
        }
    }

    /** WriteCallback 实现：收到回调后标记对应 execution 的 MD 写入状态和实际路径 */
    @Override
    public void onMarkdownWritten(String filePath, Long executionId) {
        log.info("Markdown written to {}, executionId={}", filePath, executionId);
        markMdWritten(executionId);
        if (executionId != null && filePath != null && !filePath.isBlank()) {
            mdWrittenPaths.put(executionId, filePath);
        }
    }

    /** 标记指定 execution 的 MD 写入状态 */
    public void markMdWritten(Long executionId) {
        if (executionId != null) {
            mdWrittenFlags.put(executionId, true);
        }
    }

    /**
     * 从 AgentEvent 列表中提取 AI 最终文本结果。
     * 优先取 AgentResultEvent 的文本内容，回退到累积 TextBlockDeltaEvent。
     */
    private String extractFinalText(List<AgentEvent> events) {
        if (events == null) return "";
        // 优先取终端 AgentResultEvent
        for (AgentEvent e : events) {
            if (e instanceof AgentResultEvent re && re.getResult() != null) {
                String t = re.getResult().getTextContent();
                if (t != null && !t.isBlank()) {
                    return t;
                }
            }
        }
        // 回退：累积文本 delta
        StringBuilder sb = new StringBuilder();
        for (AgentEvent e : events) {
            if (e instanceof TextBlockDeltaEvent d && d.getDelta() != null) {
                sb.append(d.getDelta());
            }
        }
        return sb.toString();
    }
}
