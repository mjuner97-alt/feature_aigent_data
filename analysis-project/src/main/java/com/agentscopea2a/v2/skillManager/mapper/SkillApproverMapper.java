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

import com.agentscopea2a.v2.skillManager.entity.SkillApprover;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Skill 审批人员 Mapper - 访问 skill_approver 表。
 * 包路径受 {@code GaussConfig.@MapperScan} 约束。
 */
@Mapper
public interface SkillApproverMapper {

    /**
     * 根据审批范围类型和名称查询 ACTIVE 审批人。
     * 一条 scope 可能配置多个审批人(或签),返回全部。
     */
    List<SkillApprover> selectByScope(
            @Param("approvalScopeType") String approvalScopeType,
            @Param("approvalScopeName") String approvalScopeName);

    /**
     * 查询指定用户全部 ACTIVE 审批范围记录。
     */
    List<SkillApprover> selectByUserId(@Param("userId") String userId);

    /**
     * 查询全部 ACTIVE 审批人 user_id(去重),用于判断某用户是否为审批人。
     */
    List<String> selectAllApproverUserIds();
}
