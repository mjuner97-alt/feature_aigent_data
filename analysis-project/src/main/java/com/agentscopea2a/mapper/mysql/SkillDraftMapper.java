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
package com.agentscopea2a.mapper.mysql;

import com.agentscopea2a.entity.SkillDraft;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Skill 草稿 Mapper - 维护 {@code skill_draft} 表的草稿暂存、状态流转与审批人待办查询。
 * 包路径受 {@code MySQLConfig.@MapperScan(basePackages = "com.agentscopea2a.mapper.mysql")} 约束。
 */
@Mapper
public interface SkillDraftMapper {

    int insert(SkillDraft draft);

    SkillDraft selectById(@Param("id") Long id);

    SkillDraft selectPendingBySkillId(@Param("skillId") Long skillId);

    int updateStatus(@Param("id") Long id, @Param("status") String status,
                     @Param("approver") String approver, @Param("comment") String comment);

    int updateContent(SkillDraft draft);

    List<SkillDraft> selectPendingByApprover(@Param("approverUserId") String approverUserId);
}
