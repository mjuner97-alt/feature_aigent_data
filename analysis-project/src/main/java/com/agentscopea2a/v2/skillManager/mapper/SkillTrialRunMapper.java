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

import com.agentscopea2a.v2.skillManager.entity.SkillTrialRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Skill 试运行记录 Mapper - 访问 skill_trial_run 表。
 * 包路径受 {@code GaussConfig.@MapperScan} 约束。
 */
@Mapper
public interface SkillTrialRunMapper {

    /** 新增一条试运行记录,回填自增 id。 */
    int insert(SkillTrialRun record);

    /**
     * 按 Skill 查询试运行记录(倒序,限量),用于试运行结果查看。
     *
     * @param afterTime 可选,仅取该时间之后的记录(skill 最后修改时间之后);null 不过滤
     */
    List<SkillTrialRun> selectBySkillId(
            @Param("skillId") Long skillId,
            @Param("limit") int limit,
            @Param("afterTime") LocalDateTime afterTime);
}
