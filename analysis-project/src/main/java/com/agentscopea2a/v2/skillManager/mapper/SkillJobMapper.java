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
package com.agentscopea2a.v2.skillManager.mapper;

import com.agentscopea2a.v2.skillManager.entity.SkillJob;
import com.agentscopea2a.v2.skillManager.entity.SkillJobExecution;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * SkillJob MyBatis Mapper，操作 skill_job 和 skill_job_execution 两张表。
 */
@Mapper
public interface SkillJobMapper {

    // ---- skill_job ----

    void insertSkillJob(SkillJob job);

    SkillJob selectJobById(@Param("id") Long id);

    SkillJob selectJobByName(@Param("name") String name);

    List<SkillJob> selectJobList(@Param("enabled") Boolean enabled,
                                @Param("keyword") String keyword,
                                @Param("createdBy") String createdBy);

    void updateJobById(SkillJob job);

    void deleteJobById(@Param("id") Long id);

    // ---- skill_job_execution ----

    void insertExecution(SkillJobExecution exec);

    SkillJobExecution selectExecutionById(@Param("id") Long id);

    List<SkillJobExecution> selectExecutionsByJobId(
            @Param("jobId") Long jobId,
            @Param("status") String status);

    void updateExecutionStatus(SkillJobExecution exec);

    /** 将残留的 RUNNING/PENDING 执行记录标记为 FAILED（应用重启时恢复僵尸记录） */
    int markStaleRunningAsFailed();
}
