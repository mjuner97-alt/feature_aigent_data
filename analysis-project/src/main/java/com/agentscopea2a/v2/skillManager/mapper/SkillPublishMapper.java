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

import com.agentscopea2a.v2.skillManager.entity.SkillPublish;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Skill 发布 Mapper - 维护 {@code skill_publish} 表的发布记录、审批状态流转与待办/已批准查询。
 * 包路径受 {@code MySQLConfig.@MapperScan(basePackages = "com.agentscopea2a.mapper.mysql")} 约束。
 */
@Mapper
public interface SkillPublishMapper {

    int insert(SkillPublish publish);

    SkillPublish selectById(@Param("id") Long id);

    int updateStatus(@Param("id") Long id, @Param("status") String status,
                     @Param("approver") String approver, @Param("comment") String comment);

    List<SkillPublish> selectBySkillId(@Param("skillId") Long skillId);

    boolean hasApprovedBySkillId(@Param("skillId") Long skillId);

    /** 判断指定 Skill 是否存在 PENDING 状态的发布记录(审批中不可编辑/删除)。 */
    boolean hasPendingBySkillId(@Param("skillId") Long skillId);

    List<SkillPublish> selectApprovedBySkillId(@Param("skillId") Long skillId);

    List<SkillPublish> selectPendingByApprover(@Param("approverUserId") String approverUserId);

    /** 查询指定审批人处理过的发布记录(APPROVED/REJECTED,按 approver 字段匹配)。 */
    List<SkillPublish> selectHistoryByApprover(@Param("approverUserId") String approverUserId);

    List<SkillPublish> selectApprovedBySkillIds(@Param("skillIds") List<Long> skillIds);
}
