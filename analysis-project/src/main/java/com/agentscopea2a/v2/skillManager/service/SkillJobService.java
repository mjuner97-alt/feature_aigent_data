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
import com.agentscopea2a.v2.skillManager.entity.SkillJob;
import com.agentscopea2a.v2.skillManager.entity.SkillJobExecution;
import com.agentscopea2a.v2.skillManager.mapper.SkillJobMapper;
import com.agentscopea2a.v2.skillManager.scheduler.SkillJobScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

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

    public SkillJobService(SkillJobMapper mapper, SkillJobScheduler scheduler) {
        this.mapper = mapper;
        this.scheduler = scheduler;
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

        // 自动生成输出路径: /data/skill-files/{userId}/
        String outputPath = "/data/skill-files/" + userId + "/";

        SkillJob job = SkillJob.builder()
                .name(req.name())
                .skillId(req.skillId())
                .questionTemplate(req.questionTemplate())
                .outputPath(outputPath)
                .enabled(true)
                .createdBy(userId)
                .build();
        mapper.insertSkillJob(job);

        log.info("[SkillJob] create OK: id={}, name={}, outputPath={}", job.getId(), job.getName(), outputPath);
        return SkillJobDto.of(job);
    }

    /** 列表查询 */
    public List<SkillJobDto> list(Boolean enabled, String keyword, String createdBy) {
        return mapper.selectJobList(enabled, keyword, createdBy).stream().map(SkillJobDto::of).toList();
    }

    /** 查询详情 */
    public SkillJobDto get(Long id) {
        SkillJob job = mapper.selectJobById(id);
        if (job == null) {
            throw new IllegalStateException("JobNotFound: 任务不存在 (id=" + id + ")");
        }
        return SkillJobDto.of(job);
    }

    /** 更新 Job，仅更新非 null 字段；createdBy 与 skillId 不可变，仅创建人本人可改 */
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
        // skillId 不可修改：想换 Skill 只能删除后重建
        if (req.questionTemplate() != null) job.setQuestionTemplate(req.questionTemplate());
        if (req.outputPath() != null && !req.outputPath().isBlank()) {
            job.setOutputPath(req.outputPath());
        }
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
                .jobId(jobId).triggerType("MANUAL").status("PENDING").build();
        mapper.insertExecution(exec);
        if (!scheduler.submit(jobId, exec.getId())) {
            throw new IllegalStateException("JobAlreadyRunning: 任务正在执行中，请稍后再试 (id=" + jobId + ")");
        }
        log.info("[SkillJob] trigger: jobId={}, name={}, executionId={}, userId={}",
                jobId, job.getName(), exec.getId(), userId);
        return SkillJobExecutionDto.of(exec);
    }

    /**
     * 按任务名触发 Job 执行（外部系统调用入口）。
     * 任务名在表中唯一，外部系统用任务名而非 ID 来调起。
     *
     * <p>先落一条 PENDING 执行记录拿到真实 id 返回前端，再异步提交；同一 Job 已有实例在跑则拒绝。
     */
    public SkillJobExecutionDto triggerByName(String name, String userId) {
        SkillJob job = mapper.selectJobByName(name);
        if (job == null) {
            throw new IllegalStateException("JobNotFound: 任务不存在 (name=" + name + ")");
        }
        SkillJobExecution exec = SkillJobExecution.builder()
                .jobId(job.getId()).triggerType("EXTERNAL").status("PENDING").build();
        mapper.insertExecution(exec);
        if (!scheduler.submit(job.getId(), exec.getId())) {
            throw new IllegalStateException("JobAlreadyRunning: 任务正在执行中，请稍后再试 (id=" + job.getId() + ")");
        }
        log.info("[SkillJob] triggerByName: name={}, jobId={}, executionId={}, userId={}",
                name, job.getId(), exec.getId(), userId);
        return SkillJobExecutionDto.of(exec);
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
     * 下载执行记录对应的 MD 文件。
     * 校验 userId 归属（通过 Job 的 createdBy）后返回磁盘文件 Resource。
     * resolvedOutputPath 为绝对路径（/data/skill-files/{userId}/xxx.md）。
     */
    public Resource downloadExecutionFile(SkillJobExecutionDto exec, String userId) {
        if (exec.resolvedOutputPath() == null || exec.resolvedOutputPath().isBlank()) {
            throw new IllegalStateException("FileNotFound: 执行记录无输出路径 (execId=" + exec.id() + ")");
        }

        // 校验 userId 归属：仅 createdBy 本人可下载其生成的 MD 文件（执行结果属创建人私有）
        SkillJob job = mapper.selectJobById(exec.jobId());
        if (job == null ) {
            throw new IllegalStateException("FileNotFoundOrAccessDenied: " + exec.id());
        }
        if (!userId.equals(job.getCreatedBy())) {
            throw new IllegalStateException("JobAccessDenied: 仅创建人可下载此执行结果 (execId=" + exec.id() + ")");
        }

        // resolvedOutputPath 是绝对路径，直接解析
        Path mdFile = Paths.get(exec.resolvedOutputPath()).normalize().toAbsolutePath();
        // 路径穿越防护：必须在 /data/skill-files/{userId}/ 下
        Path expectedBase = Paths.get("/data/skill-files/" + userId).normalize().toAbsolutePath();
        if (!mdFile.startsWith(expectedBase)) {
            throw new IllegalStateException("PathTraversal: " + exec.resolvedOutputPath());
        }
        if (!Files.exists(mdFile) || !Files.isRegularFile(mdFile)) {
            throw new IllegalStateException("FileNotOnDisk: " + exec.resolvedOutputPath());
        }

        return new FileSystemResource(mdFile);
    }
}
