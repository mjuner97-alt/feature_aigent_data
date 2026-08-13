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

import com.agentscopea2a.v2.skillManager.dto.*;
import com.agentscopea2a.v2.skillManager.entity.SkillDependencyMetric;
import com.agentscopea2a.v2.skillManager.entity.SkillJob;
import com.agentscopea2a.v2.skillManager.entity.SkillJobExecution;
import com.agentscopea2a.v2.skillManager.mapper.SkillDependencyMetricMapper;
import com.agentscopea2a.v2.skillManager.mapper.SkillJobMapper;
import com.agentscopea2a.v2.skillManager.scheduler.SkillJobScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

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

    private final SkillJobMapper mapper;
    private final SkillJobScheduler scheduler;
    private final SkillDependencyMetricMapper metricMapper;
    private final MockOrgService mockOrgService;

    /** skill 文件磁盘根目录(${skill.file.base-dir})，与 SkillFileService/WriteMarkdownTool 一致，不写死。 */
    @Value("${skill.file.base-dir:/data/skill-files}")
    private String baseDir;

    /** skill 文件镜像根目录(${skill.file.mirror-dir})，报告主文件被删后下载回退读镜像；可为空则跳过。 */
    @Value("${skill.file.mirror-dir:/data/skill-files-mirror}")
    private String mirrorDir;

    public SkillJobService(SkillJobMapper mapper, SkillJobScheduler scheduler,
                           SkillDependencyMetricMapper metricMapper, MockOrgService mockOrgService) {
        this.mapper = mapper;
        this.scheduler = scheduler;
        this.metricMapper = metricMapper;
        this.mockOrgService = mockOrgService;
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

        // 自动生成输出路径(相对 baseDir): {userId}/。绝对路径由 baseDir 拼,不写死 /data/skill-files/
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
        return SkillJobExecutionDto.of(exec);
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
        return SkillJobExecutionDto.of(exec);
    }

    /** 列出启用的依赖指标（供前端下拉，admin 预置只读） */
    public List<SkillDependencyMetricDto> listMetrics() {
        return metricMapper.selectAllEnabled().stream().map(SkillDependencyMetricDto::of).toList();
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
        List<SkillJob> jobs = mapper.selectEnabledJobsByMetricId(metric.getId());
        List<MetricTriggerItemDto> results = new ArrayList<>();
        for (SkillJob job : jobs) {
            results.add(triggerOneForMetric(job, caller));
        }
        log.info("[SkillJob] triggerByMetric: code={}, metricId={}, total={}, caller={}",
                code, metric.getId(), results.size(), caller);
        return new MetricTriggerBatchDto(code, results.size(), results);
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

    /** 查询执行记录列表 */
    public List<SkillJobExecutionDto> listExecutions(Long jobId, String status) {
        return mapper.selectExecutionsByJobId(jobId, status).stream().map(SkillJobExecutionDto::of).toList();
    }

    /** 查询单条执行记录 */
    public SkillJobExecutionDto getExecution(Long execId) {
        SkillJobExecution exec = mapper.selectExecutionById(execId);
        if (exec == null) {
            throw new IllegalStateException("JobNotFound: 执行记录不存在 (id=" + execId + ")");
        }
        return SkillJobExecutionDto.of(exec);
    }

    /**
     * 下载/查看执行记录对应的报告文件（内部纯文件服务：路径解析 + 路径穿越防护，无归属校验）。
     * 供短链端点 {@code downloadByShortCode} 调用（shortCode 即访问凭据，无需 userId）。
     * resolvedOutputPath 存相对路径({userId}/{mdFileName})，拼 baseDir 解析绝对路径；
     * 历史绝对路径记录也可解析(Paths.get 第二参数为绝对路径时忽略 baseDir)，自动兼容老数据。
     * 路径穿越 base = baseDir/{createdBy}。
     */
    public Resource downloadExecutionFile(SkillJobExecutionDto exec) {
        if (exec.resolvedOutputPath() == null || exec.resolvedOutputPath().isBlank()) {
            throw new IllegalStateException("FileNotFound: 执行记录无输出路径 (execId=" + exec.id() + ")");
        }

        SkillJob job = mapper.selectJobById(exec.jobId());
        if (job == null) {
            throw new IllegalStateException("FileNotFoundOrAccessDenied: " + exec.id());
        }

        // resolvedOutputPath 相对路径({userId}/{mdFileName})，拼 baseDir 解析；
        // 历史绝对路径(老数据)Paths.get 会忽略 baseDir 直接照旧解析，自动兼容。
        Path mdFile = Paths.get(baseDir, exec.resolvedOutputPath()).normalize().toAbsolutePath();
        // 路径穿越防护：必须在 baseDir/{userId}/ 下
        Path expectedBase = Paths.get(baseDir, job.getCreatedBy()).normalize().toAbsolutePath();
        if (!mdFile.startsWith(expectedBase)) {
            throw new IllegalStateException("PathTraversal: " + exec.resolvedOutputPath());
        }
        if (!Files.exists(mdFile) || !Files.isRegularFile(mdFile)) {
            // 兜底: 主文件被删, 从镜像副本读 (保留镜像策略下副本仍在);
            // 新版 resolvedOutputPath 是相对路径 {userId}/{file}, 镜像同名可回退; 老绝对路径记录 Paths.get 忽略 mirrorDir 自然无效
            Path mirror = Paths.get(mirrorDir, exec.resolvedOutputPath()).normalize().toAbsolutePath();
            if (Files.exists(mirror) && Files.isRegularFile(mirror)) {
                log.warn("SkillJob report primary missing, serving from mirror: {} -> {}", mdFile, mirror);
                return new FileSystemResource(mirror);
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
}
