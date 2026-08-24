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
package com.agentscopea2a.v2.skillManager.service;

import com.agentscopea2a.v2.config.SkillStorageProperties;
import com.agentscopea2a.v2.skillManager.entity.SkillMetricReadiness;
import com.agentscopea2a.v2.skillManager.dto.*;
import com.agentscopea2a.v2.skillManager.entity.SkillDependencyMetric;
import com.agentscopea2a.v2.skillManager.entity.SkillJob;
import com.agentscopea2a.v2.skillManager.entity.SkillJobExecution;
import com.agentscopea2a.v2.skillManager.entity.SkillJobNotification;
import com.agentscopea2a.v2.skillManager.mapper.SkillDependencyMetricMapper;
import com.agentscopea2a.v2.skillManager.mapper.SkillJobMapper;
import com.agentscopea2a.v2.skillManager.notification.NotificationService;
import com.agentscopea2a.v2.skillManager.scheduler.SkillJobScheduler;
import com.agentscopea2a.v2.util.SkillFileMirror;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;

/**
 * SkillJob 业务逻辑层。
 * 处理 Job CRUD 和手动触发执行。
 *
 * 外部任务完成后调用 trigger 接口，Job 进入单线程队列排队执行。
 */
@Service
@ConditionalOnProperty(prefix = "harness.a2a.skill-job", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SkillJobService {

    private static final Logger log = LoggerFactory.getLogger(SkillJobService.class);
    private static final int MAX_EDITABLE_REPORT_BYTES = 2 * 1024 * 1024;

    private final SkillJobMapper mapper;
    private final SkillJobScheduler scheduler;
    private final SkillDependencyMetricMapper metricMapper;
    private final MockOrgService mockOrgService;
    private final NotificationService notificationService;

    /** 指标就绪登记服务:外部指标到达时记录 READY 状态,Skill Flow 的指标门控以此解锁。 */
    @Autowired(required = false)
    private MetricReadinessService metricReadinessService;

    /** Skill Flow 执行服务:指标就绪后异步重算等待中执行的门控(与 Job 主流程解耦,失败不影响 Job)。 */
    @Autowired(required = false)
    private FlowExecutionService flowExecutionService;

    /** Skill Job 报告主目录(${skill.job.base-dir})。 */
    private final String baseDir;

    /** Skill Job 报告备份目录(${skill.job.backup-dir})，主文件不存在时从此目录读取。 */
    private final String backupDir;

    public SkillJobService(SkillJobMapper mapper, SkillJobScheduler scheduler,
                           SkillDependencyMetricMapper metricMapper, MockOrgService mockOrgService,
                           NotificationService notificationService,
                           SkillStorageProperties storageProperties) {
        this.mapper = mapper;
        this.scheduler = scheduler;
        this.metricMapper = metricMapper;
        this.mockOrgService = mockOrgService;
        this.notificationService = notificationService;
        this.baseDir = storageProperties.getJobReportDir();
        this.backupDir = storageProperties.getJobBackupDir();
    }

    // ==================== CRUD ====================

    /** 创建 Job，自动生成 outputPath */
    @Transactional("gaussTransactionManager")
    public SkillJobDto create(SkillJobCreateRequest req, String userId) {
        log.info("[SkillJob] create: name={}, skillId={}, userId={}", req.name(), req.skillId(), userId);

        SkillJob existing = mapper.selectJobByName(req.name());
        if (existing != null) {
            throw new IllegalStateException("JobNameConflict: 任务名称 '" + req.name() + "' 已存在");
        }

        // 校验依赖指标：可选；若传了则须存在且启用（admin 预置，用户只读）
        if (req.metricId() != null) {
            SkillDependencyMetric metric = metricMapper.selectById(req.metricId());
            if (metric == null) {
                throw new IllegalStateException("MetricNotFound: 依赖指标不存在 (id=" + req.metricId() + ")");
            }
            if (!Boolean.TRUE.equals(metric.getEnabled())) {
                throw new IllegalStateException("MetricDisabled: 依赖指标已停用，不可选用 (id=" + req.metricId() + ")");
            }
        }

        // 自动生成输出路径(相对 skill.job.base-dir): {userId}/。
        String outputPath = userId + "/";

        SkillJob job = SkillJob.builder()
                .name(req.name())
                .skillId(req.skillId())
                .questionTemplate(req.questionTemplate())
                .outputPath(outputPath)
                .enabled(true)
                .createdBy(userId)
                .metricId(req.metricId())
                .build();
        mapper.insertSkillJob(job);

        log.info("[SkillJob] create OK: id={}, name={}, outputPath={}", job.getId(), job.getName(), outputPath);
        return SkillJobDto.of(job);
    }

    /** 列表查询 */
    public List<SkillJobDto> list(Boolean enabled, String keyword, String createdBy) {
        List<SkillJob> jobs = mapper.selectJobList(enabled, keyword, createdBy);
        // 批量解析创建人姓名(列表行展示"姓名 (userId)"),一次性查询避免 N+1
        Set<String> creatorIds = jobs.stream()
                .map(SkillJob::getCreatedBy)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, String> creatorNames = mockOrgService.getUserNameMap(creatorIds);
        return jobs.stream()
                .map(j -> SkillJobDto.of(j, creatorNames.get(j.getCreatedBy())))
                .toList();
    }

    /** 查询详情 */
    public SkillJobDto get(Long id) {
        SkillJob job = mapper.selectJobById(id);
        if (job == null) {
            throw new IllegalStateException("JobNotFound: 任务不存在 (id=" + id + ")");
        }
        return SkillJobDto.of(job);
    }

    /** 更新 Job，仅更新非 null 字段；createdBy 不可变，仅创建人本人可改。
     *  skillId / metricId 可改：metricId=0 表示清除关联（不关联），null 表示保留原值。
     *  更换 skillId 不影响 outputPath（outputPath 按 userId 生成，与 skill 无关）。 */
    @Transactional("gaussTransactionManager")
    public SkillJobDto update(Long id, SkillJobUpdateRequest req, String userId) {
        SkillJob job = mapper.selectJobById(id);
        if (job == null) {
            throw new IllegalStateException("JobNotFound: 任务不存在 (id=" + id + ")");
        }
        // 仅 createdBy 本人可修改
        if (!userId.equals(job.getCreatedBy())) {
            throw new IllegalStateException("JobAccessDenied: 仅创建人可修改此任务 (id=" + id + ")");
        }
        // createdBy 不可变：不再覆盖（此前 update 会把 createdBy 改成当前操作人，属越权改写）
        if (req.name() != null) job.setName(req.name());
        // skillId 可修改：正值才更新（0/负数忽略，避免占位符误写）
        if (req.skillId() != null && req.skillId() > 0) job.setSkillId(req.skillId());
        // metricId 可修改：0 = 清除关联（不关联）；正值 = 关联该指标（须存在且启用）；null = 保留原值
        if (req.metricId() != null) {
            Long mid = req.metricId() == 0L ? null : req.metricId();
            if (mid != null) {
                SkillDependencyMetric metric = metricMapper.selectById(mid);
                if (metric == null) {
                    throw new IllegalStateException("MetricNotFound: 依赖指标不存在 (id=" + mid + ")");
                }
                if (!Boolean.TRUE.equals(metric.getEnabled())) {
                    throw new IllegalStateException("MetricDisabled: 依赖指标已停用，不可选用 (id=" + mid + ")");
                }
            }
            job.setMetricId(mid);
        }
        if (req.questionTemplate() != null) job.setQuestionTemplate(req.questionTemplate());
        if (req.enabled() != null) job.setEnabled(req.enabled());
        mapper.updateJobById(job);
        return SkillJobDto.of(job);
    }

    /** 删除 Job，仅创建人本人可删 */
    @Transactional("gaussTransactionManager")
    public void delete(Long id, String userId) {
        SkillJob job = mapper.selectJobById(id);
        if (job == null) {
            throw new IllegalStateException("JobNotFound: 任务不存在 (id=" + id + ")");
        }
        if (!userId.equals(job.getCreatedBy())) {
            throw new IllegalStateException("JobAccessDenied: 仅创建人可删除此任务 (id=" + id + ")");
        }
        mapper.deleteJobById(id);
        log.info("[SkillJob] delete: id={}, userId={}", id, userId);
    }

    // ==================== 执行 ====================

    /**
     * 按 ID 触发 Job 执行（提交到线程池排队）。
     * 手动触发仅创建人本人可用；实际执行以 createdBy 身份进行，并在执行前再次校验其 Skill 权限。
     *
     * <p>先落一条 PENDING 执行记录拿到真实 id 返回前端，再异步提交；同一 Job 已有实例在跑则拒绝。
     */
    public SkillJobExecutionDto trigger(Long jobId, String userId) {
        SkillJob job = mapper.selectJobById(jobId);
        if (job == null) {
            throw new IllegalStateException("JobNotFound: 任务不存在 (id=" + jobId + ")");
        }
        if (!userId.equals(job.getCreatedBy())) {
            throw new IllegalStateException("JobAccessDenied: 仅创建人可手动触发此任务 (id=" + jobId + ")");
        }
        SkillJobExecution exec = SkillJobExecution.builder()
                .mdFileWritten(false)
                .mdFileExists(false)
                .jobId(jobId).triggerType("MANUAL").status("PENDING").build();
        mapper.insertExecution(exec);
        // 入队失败（队列满）也会清掉 PENDING，避免残留孤儿记录卡住后续触发
        if (!trySubmitOrCleanup(jobId, exec.getId(), "MANUAL")) {
            throw new IllegalStateException("JobAlreadyRunning: 该任务已有手动执行在进行中，请等其完成后再触发 (id=" + jobId + ")");
        }
        log.info("[SkillJob] trigger: jobId={}, name={}, executionId={}, userId={}",
                jobId, job.getName(), exec.getId(), userId);
        return SkillJobExecutionDto.of(exec, queueAheadOf(exec));
    }

    /**
     * 按任务名触发 Job 执行（外部系统调用入口）。
     * 任务名在表中唯一，外部系统用任务名而非 ID 来调起。
     * 不接收 userId 入参：执行身份统一取自 Job 的 createdBy（关联该 Skill 的创建人），
     * 调度器 {@code doExecuteJob} 亦以 createdBy 身份执行并校验其 Skill 权限，外部系统无需传 userId。
     *
     * <p>先落一条 PENDING 执行记录拿到真实 id 返回前端，再异步提交；同一 Job 已有实例在跑则拒绝。
     */
    public SkillJobExecutionDto triggerByName(String name) {
        SkillJob job = mapper.selectJobByName(name);
        if (job == null) {
            throw new IllegalStateException("JobNotFound: 任务不存在 (name=" + name + ")");
        }
        SkillJobExecution exec = SkillJobExecution.builder()
                .mdFileWritten(false)
                .mdFileExists(false)
                .jobId(job.getId()).triggerType("EXTERNAL").status("PENDING").build();
        mapper.insertExecution(exec);
        if (!trySubmitOrCleanup(job.getId(), exec.getId(), "EXTERNAL")) {
            throw new IllegalStateException("JobAlreadyRunning: 任务正在执行中，请稍后再试 (id=" + job.getId() + ")");
        }
        // 执行身份取自 Job 的 createdBy，调度器 doExecuteJob 同样以 createdBy 跑
        log.info("[SkillJob] triggerByName: name={}, jobId={}, executionId={}, createdBy={}",
                name, job.getId(), exec.getId(), job.getCreatedBy());
        return SkillJobExecutionDto.of(exec, queueAheadOf(exec));
    }

    /** 列出启用的依赖指标（供前端下拉，admin 预置只读） */
    public List<SkillDependencyMetricDto> listMetrics(String keyword) {
        String trimmed = keyword == null ? null : keyword.trim();
        return metricMapper.selectAllEnabled(trimmed).stream().map(SkillDependencyMetricDto::of).toList();
    }

    /**
     * 按依赖指标触发（外部系统调用入口）：指标就绪后一把触发所有"启用且关联该指标"的 job。
     * 跨用户触发是预期行为：每个 job 仍以各自 createdBy 身份执行，执行时 skillAvailableToUser 各自校验权限。
     * triggerType=METRIC；未关联指标的 job 不参与（可手动单发）。
     */
    public MetricTriggerBatchDto triggerByMetric(String code, String userId) {
        SkillDependencyMetric metric = metricMapper.selectByCode(code);
        if (metric == null) {
            throw new IllegalStateException("MetricNotFound: 依赖指标不存在 (code=" + code + ")");
        }
        if (!Boolean.TRUE.equals(metric.getEnabled())) {
            throw new IllegalStateException("MetricDisabled: 依赖指标已停用，不可触发 (code=" + code + ")");
        }
        // 外部系统调用可不传 X-User-Id；此处仅用于日志追溯，实际执行身份取自每个 job 的 createdBy
        String caller = (userId == null || userId.isBlank()) ? "MANAGER" : userId;
        //给长任务登记指标 READY。
        recordFlowReadinessBestEffort(metric);
        //触发旧的独立
        List<SkillJob> jobs = mapper.selectEnabledJobsByMetricId(metric.getId());
        List<MetricTriggerItemDto> results = new ArrayList<>();
        for (SkillJob job : jobs) {
            results.add(triggerOneForMetric(job, caller));
        }
        log.info("[SkillJob] triggerByMetric: code={}, metricId={}, total={}, caller={}",
                code, metric.getId(), results.size(), caller);
        return new MetricTriggerBatchDto(code, results.size(), results);
    }

    /**
     * 指标到达时为 SkillFlow 长任务登记 READY，并驱动依赖该指标的流程（尽力而为，不影响旧的独立 Job 触发）：
     * <ol>
     *   <li>调用 {@code MetricReadinessService#recordReady} 按「指标 + 数据日期」upsert 一条 READY 记录；</li>
     *   <li>异步执行两步：{@code metricBecameReady} 重算所有等待中执行的就绪门控（全部就绪则放行入队），
     *       {@code triggerReadyFlows} 为依赖该指标且当日全部指标就绪的流程创建每日自动执行。</li>
     * </ol>
     * 任何失败只记日志不上抛——本方法在 {@link #triggerByMetric} 里位于独立 Job 批量触发之前，
     * READY 登记或门控重算失败都不能阻断旧链路（独立 Job 继续触发）。
     * 两个依赖均为 @Autowired(required=false) 可选注入，未启用 SkillFlow 时直接跳过。
     */
    private void recordFlowReadinessBestEffort(SkillDependencyMetric metric) {
        // SkillFlow 功能未启用（未注入就绪登记服务）则整体跳过
        if (metricReadinessService == null) return;
        try {
            // 同步落 READY 记录：流程创建/放行判断都依赖这条记录存在，失败则无需再走后续两步
            SkillMetricReadiness readiness = metricReadinessService.recordReady(metric);
            if (flowExecutionService != null) {
                // 门控重算与流程触发放到异步线程，不占用本次外部触发请求的响应时间
                CompletableFuture.runAsync(() -> {
                    try {
                        // 先放行已等待中的执行，再创建今日新自动执行，顺序不可颠倒
                        flowExecutionService.metricBecameReady(metric.getId(), readiness.getDataDate());
                        flowExecutionService.triggerReadyFlows(metric.getId(), readiness.getDataDate());
                    } catch (RuntimeException e) {
                        log.error("[SkillFlow] gate recalculation failed: metric={}, date={}",
                                metric.getCode(), readiness.getDataDate(), e);
                    }
                });
            }
        } catch (RuntimeException e) {
            // READY 落库失败仅记录，独立 Job 的批量触发继续执行
            log.error("[SkillFlow] READY persistence failed; independent jobs continue: metric={}",
                    metric.getCode(), e);
        }
    }

    /** 单个 job 在批量触发中的处理：落 PENDING -> submit；已在跑则清孤儿记录并标记 REJECTED */
    private MetricTriggerItemDto triggerOneForMetric(SkillJob job, String userId) {
        SkillJobExecution exec = SkillJobExecution.builder()
                .mdFileWritten(false)
                .mdFileExists(false)
                .jobId(job.getId()).triggerType("METRIC").status("PENDING").build();
        mapper.insertExecution(exec);
        try {
            if (!scheduler.submit(job.getId(), exec.getId(), "METRIC")) {
                mapper.deleteExecutionById(exec.getId());
                return new MetricTriggerItemDto(job.getId(), job.getName(), null, "REJECTED", "JobAlreadyRunning");
            }
        } catch (RuntimeException e) {
            // 队列满等入队失败：清掉 PENDING 避免孤儿记录，单 job 记 REJECTED，不中断整批触发
            mapper.deleteExecutionById(exec.getId());
            log.warn("Job {} metric-trigger enqueue failed: {}", job.getId(), e.getMessage());
            return new MetricTriggerItemDto(job.getId(), job.getName(), null, "REJECTED", "JobQueueFull");
        }
        log.info("[SkillJob] triggerByMetric member: jobId={}, name={}, executionId={}, userId={}",
                job.getId(), job.getName(), exec.getId(), userId);
        return new MetricTriggerItemDto(job.getId(), job.getName(), exec.getId(), "QUEUED", null);
    }

    /**
     * 提交执行队列并保证不残留 PENDING 孤儿记录。
     * 入队成功返回 true；同 lane 已在跑返回 false（PENDING 已清理）；入队失败（队列满）清理 PENDING 后重抛，
     * 避免僵尸记录在重启前一直卡住该 Job / 批量。
     */
    private boolean trySubmitOrCleanup(Long jobId, Long execId, String triggerType) {
        try {
            boolean submitted = scheduler.submit(jobId, execId, triggerType);
            if (!submitted) {
                mapper.deleteExecutionById(execId);
            }
            return submitted;
        } catch (RuntimeException e) {
            mapper.deleteExecutionById(execId);
            throw e;
        }
    }

    /** 查询执行记录列表（PENDING 行附带排队位置"前面还有N个"） */
    public List<SkillJobExecutionDto> listExecutions(Long jobId, String status) {
        return enrichQueueAhead(mapper.selectExecutionsByJobId(jobId, status));
    }

    /** 查询所有正在排队或运行中的执行记录，供任务中心展示。 */
    public List<SkillJobExecutionDto> listInflightExecutions() {
        return enrichQueueAhead(mapper.selectInflightExecutions());
    }

    /** 执行中心列表：创建人只按 userId 精确筛选，姓名仅用于结果展示。 */
    public List<SkillJobExecutionDto> listExecutionCenter(String status, String createdBy) {
        List<SkillJobExecution> executions = mapper.selectExecutionCenter(status, createdBy);
        if (!executions.isEmpty()) {
            try {
                Map<Long, SkillJobNotification> summaries = mapper.selectNotificationSummaries(
                                executions.stream().map(SkillJobExecution::getId).toList()).stream()
                        .collect(Collectors.toMap(SkillJobNotification::getExecutionId, n -> n));
                executions.forEach(execution -> {
                    SkillJobNotification summary = summaries.get(execution.getId());
                    if (summary != null) {
                        execution.setLatestNotificationStatus(summary.getStatus());
                        execution.setNotificationAttemptCount(summary.getAttemptCount());
                    }
                });
            } catch (RuntimeException e) {
                // 执行记录是主视图；通知表未部署/临时不可用时也不能让执行中心整页为空。
                log.warn("[SkillJob] notification summary unavailable, execution center continues without it: {}",
                        e.getMessage());
            }
        }
        Map<String, String> creatorNames = mockOrgService.getUserNameMap(executions.stream()
                .map(SkillJobExecution::getCreatedBy)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet()));
        Map<Long, Integer> queueAhead = executions.stream().anyMatch(e -> "PENDING".equals(e.getStatus()))
                ? buildQueueAheadMap() : Map.of();
        return executions.stream()
                .map(e -> SkillJobExecutionDto.ofCenterItem(
                        e,
                        "PENDING".equals(e.getStatus()) ? queueAhead.getOrDefault(e.getId(), 0) : null,
                        creatorNames.get(e.getCreatedBy())))
                .toList();
    }

    /** 查询单条执行记录（PENDING 附带排队位置） */
    public SkillJobExecutionDto getExecution(Long execId) {
        SkillJobExecution exec = mapper.selectExecutionById(execId);
        if (exec == null) {
            throw new IllegalStateException("JobNotFound: 执行记录不存在 (id=" + execId + ")");
        }
        return SkillJobExecutionDto.of(exec, queueAheadOf(exec));
    }

    /** List delivery attempts for an execution. Recipient information is visible only to the job owner. */
    public List<SkillJobNotificationDto> listNotifications(Long execId, String userId) {
        SkillJobExecution execution = requireOwnedExecution(execId, userId);
        return mapper.selectNotificationsByExecutionId(execution.getId()).stream()
                .map(SkillJobNotificationDto::of)
                .toList();
    }

    /** Queue a new notification attempt without re-running the skill job. */
    public SkillJobNotificationDto resendNotification(Long execId, String userId) {
        SkillJobExecution execution = requireOwnedExecution(execId, userId);
        if (!"SUCCESS".equals(execution.getStatus()) || !Boolean.TRUE.equals(execution.getMdFileExists())) {
            throw new IllegalStateException("NotificationResendUnavailable: 仅可补发已成功且报告文件存在的执行 (execId="
                    + execId + ")");
        }
        SkillJob job = mapper.selectJobById(execution.getJobId());
        Resource report = downloadExecutionFile(SkillJobExecutionDto.of(execution), userId);
        try {
            SkillJobNotification notification = notificationService.resend(
                    job, execution, report.getFile().getAbsolutePath());
            log.info("[SkillJob] notification resend queued: execId={}, notificationId={}, userId={}",
                    execId, notification.getId(), userId);
            return SkillJobNotificationDto.of(notification);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("NotificationResendUnavailable: 无法读取报告文件 (execId=" + execId + ")", e);
        }
    }

    private SkillJobExecution requireOwnedExecution(Long execId, String userId) {
        SkillJobExecution execution = mapper.selectExecutionById(execId);
        if (execution == null) {
            throw new IllegalStateException("JobNotFound: 执行记录不存在 (id=" + execId + ")");
        }
        SkillJob job = mapper.selectJobById(execution.getJobId());
        if (job == null) {
            throw new IllegalStateException("JobNotFound: 任务不存在 (id=" + execution.getJobId() + ")");
        }
        if (userId == null || !userId.equals(job.getCreatedBy())) {
            throw new IllegalStateException("JobAccessDenied: 仅创建人可查看或补发通知 (execId=" + execId + ")");
        }
        return execution;
    }

    // ==================== 排队位置 ====================

    /**
     * 计算当前所有 PENDING 执行记录的排队位置（"前面还有几个在跑/排队"）。
     * 池分组与调度器一致：MANUAL 独立一组，METRIC/EXTERNAL 同属批量一组。
     * ahead = 同池 status=RUNNING 的数量 + 同池 status=PENDING 且 id 更小的数量。
     * 一次查 inflight + 内存分组计数，避免 N+1。
     */
    private Map<Long, Integer> buildQueueAheadMap() {
        List<SkillJobExecution> inflight = mapper.selectInflightExecutions();
        if (inflight.isEmpty()) {
            return Map.of();
        }
        // 按池分组：MANUAL -> true，其余(METRIC/EXTERNAL) -> false
        Map<Boolean, List<SkillJobExecution>> byPool = inflight.stream()
                .collect(Collectors.groupingBy(e -> "MANUAL".equals(e.getTriggerType())));
        Map<Long, Integer> result = new HashMap<>();
        for (Map.Entry<Boolean, List<SkillJobExecution>> entry : byPool.entrySet()) {
            List<SkillJobExecution> pool = entry.getValue();
            long running = pool.stream().filter(e -> "RUNNING".equals(e.getStatus())).count();
            for (SkillJobExecution e : pool) {
                if (!"PENDING".equals(e.getStatus())) continue;
                long pendingBefore = pool.stream()
                        .filter(x -> "PENDING".equals(x.getStatus()) && x.getId() < e.getId())
                        .count();
                result.put(e.getId(), (int) (running + pendingBefore));
            }
        }
        return result;
    }

    /** 给一批执行记录构造 DTO：仅当存在 PENDING 时查一次 inflight 填排队位置，其余行 queueAhead=null。 */
    private List<SkillJobExecutionDto> enrichQueueAhead(List<SkillJobExecution> execs) {
        boolean hasPending = execs.stream().anyMatch(e -> "PENDING".equals(e.getStatus()));
        if (!hasPending) {
            return execs.stream().map(SkillJobExecutionDto::of).toList();
        }
        Map<Long, Integer> aheadMap = buildQueueAheadMap();
        return execs.stream()
                .map(e -> SkillJobExecutionDto.of(e,
                        "PENDING".equals(e.getStatus()) ? aheadMap.get(e.getId()) : null))
                .toList();
    }

    /** 单条执行记录的排队位置：仅 PENDING 计算，其余返回 null。 */
    private Integer queueAheadOf(SkillJobExecution exec) {
        if (!"PENDING".equals(exec.getStatus())) return null;
        return buildQueueAheadMap().getOrDefault(exec.getId(), 0);
    }

    /**
     * 下载/查看执行记录对应的报告文件（内部纯文件服务：路径解析 + 路径穿越防护，无归属校验）。
     * 供短链端点 {@code downloadByShortCode} 调用（shortCode 即访问凭据，无需 userId）。
     * resolvedOutputPath 存相对路径({userId}/{mdFileName})，先从报告主目录读取，
     * 主文件不存在时再从报告备份目录读取。
     * 路径穿越 base = baseDir/{createdBy}。
     */
    public Resource downloadExecutionFile(SkillJobExecutionDto exec) {
        if (exec.resolvedOutputPath() == null || exec.resolvedOutputPath().isBlank()) {
            throw new IllegalStateException("FileNotFound: 执行记录无输出路径 (execId=" + exec.id() + ")");
        }

        Path relativeOutputPath = Paths.get(exec.resolvedOutputPath());
        if (relativeOutputPath.isAbsolute()) {
            throw new IllegalStateException("FileNotOnDisk: 不再支持旧的绝对报告路径: "
                    + exec.resolvedOutputPath());
        }

        SkillJob job = mapper.selectJobById(exec.jobId());
        if (job == null) {
            throw new IllegalStateException("FileNotFoundOrAccessDenied: " + exec.id());
        }

        // resolvedOutputPath 相对路径({userId}/{mdFileName})，拼报告主目录解析。
        Path mdFile = Paths.get(baseDir).resolve(relativeOutputPath).normalize().toAbsolutePath();
        // 路径穿越防护：必须在 baseDir/{userId}/ 下
        Path expectedBase = Paths.get(baseDir, job.getCreatedBy()).normalize().toAbsolutePath();
        if (!mdFile.startsWith(expectedBase)) {
            throw new IllegalStateException("PathTraversal: " + exec.resolvedOutputPath());
        }
        if (!Files.exists(mdFile) || !Files.isRegularFile(mdFile)) {
            Path backupFile = Paths.get(backupDir).resolve(relativeOutputPath).normalize().toAbsolutePath();
            Path expectedBackupBase = Paths.get(backupDir, job.getCreatedBy()).normalize().toAbsolutePath();
            if (!backupFile.startsWith(expectedBackupBase)) {
                throw new IllegalStateException("PathTraversal: " + exec.resolvedOutputPath());
            }
            if (Files.exists(backupFile) && Files.isRegularFile(backupFile)) {
                log.warn("SkillJob report primary missing, serving from backup: {} -> {}", mdFile, backupFile);
                return new FileSystemResource(backupFile);
            }
            throw new IllegalStateException("FileNotOnDisk: " + exec.resolvedOutputPath());
        }

        return new FileSystemResource(mdFile);
    }

    /**
     * 下载/查看执行记录对应的报告文件（前端入口：校验 userId 归属后委托给无校验版本）。
     * 仅 createdBy 本人可访问其生成的报告文件。
     */
    public Resource downloadExecutionFile(SkillJobExecutionDto exec, String userId) {
        SkillJob job = mapper.selectJobById(exec.jobId());
        if (job == null) {
            throw new IllegalStateException("FileNotFoundOrAccessDenied: " + exec.id());
        }
        if (!userId.equals(job.getCreatedBy())) {
            throw new IllegalStateException("JobAccessDenied: 仅创建人可下载此执行结果 (execId=" + exec.id() + ")");
        }
        return downloadExecutionFile(exec);
    }

    /** Read the current HTML source for an execution owned by the requesting user. */
    public String readExecutionReportSource(Long execId, String userId) {
        OwnedReport report = resolveOwnedHtmlReport(execId, userId);
        Path readable = Files.isRegularFile(report.primary()) ? report.primary() : report.backup();
        try {
            return Files.readString(readable, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("ReportReadFailed: 无法读取报告内容 (execId=" + execId + ")", e);
        }
    }

    /** Atomically replace an owned execution report and mirror the saved file to backup storage. */
    public String updateExecutionReportSource(Long execId, String userId, String html) {
        OwnedReport report = resolveOwnedHtmlReport(execId, userId);
        validateEditableHtml(html);
        Path temporary = null;
        try {
            Files.createDirectories(report.primary().getParent());
            temporary = Files.createTempFile(report.primary().getParent(),
                    report.primary().getFileName().toString() + ".", ".edit.tmp");
            Files.writeString(temporary, html, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, report.primary(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, report.primary(), StandardCopyOption.REPLACE_EXISTING);
            }
            SkillFileMirror.mirror(baseDir, backupDir, report.relativePath());
            return Files.readString(report.primary(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("ReportWriteFailed: 无法保存报告 (execId=" + execId + ")", e);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException e) {
                    log.warn("Could not delete Skill Job edit temp file for execId={}: {}", execId, e.getMessage());
                }
            }
        }
    }

    private OwnedReport resolveOwnedHtmlReport(Long execId, String userId) {
        SkillJobExecution execution = mapper.selectExecutionById(execId);
        if (execution == null) {
            throw new IllegalStateException("JobNotFound: 执行记录不存在 (id=" + execId + ")");
        }
        SkillJob job = mapper.selectJobById(execution.getJobId());
        if (job == null || userId == null || !userId.equals(job.getCreatedBy())) {
            throw new IllegalStateException("JobAccessDenied: 仅创建人可访问报告 (execId=" + execId + ")");
        }
        String relativePath = execution.getResolvedOutputPath();
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalStateException("ReportNotFound: 报告尚未生成 (execId=" + execId + ")");
        }
        Path relative = Paths.get(relativePath).normalize();
        if (relative.isAbsolute() || relative.getFileName() == null
                || !relative.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".html")) {
            throw new IllegalStateException("ReportInvalid: 报告路径无效 (execId=" + execId + ")");
        }
        Path primary = Paths.get(baseDir).resolve(relative).normalize().toAbsolutePath();
        Path primaryUserRoot = Paths.get(baseDir, job.getCreatedBy()).normalize().toAbsolutePath();
        Path backup = Paths.get(backupDir).resolve(relative).normalize().toAbsolutePath();
        Path backupUserRoot = Paths.get(backupDir, job.getCreatedBy()).normalize().toAbsolutePath();
        if (!primary.startsWith(primaryUserRoot) || !backup.startsWith(backupUserRoot)) {
            throw new IllegalStateException("ReportInvalid: 报告路径无效 (execId=" + execId + ")");
        }
        if (!Files.isRegularFile(primary) && !Files.isRegularFile(backup)) {
            throw new IllegalStateException("ReportNotFound: 报告文件不存在 (execId=" + execId + ")");
        }
        return new OwnedReport(primary, backup, relative.toString());
    }

    private static void validateEditableHtml(String html) {
        if (html == null || html.isBlank()) {
            throw new IllegalStateException("ReportContentInvalid: HTML 内容不能为空");
        }
        int byteLength = html.getBytes(StandardCharsets.UTF_8).length;
        if (byteLength > MAX_EDITABLE_REPORT_BYTES) {
            throw new IllegalStateException("ReportContentTooLarge: HTML 内容不能超过 2 MB");
        }
        String normalized = html.toLowerCase(Locale.ROOT);
        if (!normalized.contains("<html") && !normalized.contains("<!doctype html")) {
            throw new IllegalStateException("ReportContentInvalid: 内容必须是完整 HTML 文档");
        }
    }

    private record OwnedReport(Path primary, Path backup, String relativePath) {}
}
