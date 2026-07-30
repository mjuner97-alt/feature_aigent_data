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
package com.agentscopea2a.v2.skillManager.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Skill 审批人员实体 - 对应 skill_approver 表。
 * 记录某个用户在哪个组织维度(GROUP/DEPARTMENT/PRODUCT_LINE/COMPANY)担任审批人。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillApprover {
    private Long id;
    private String userId;
    private String approverName;
    /** 审批范围类型: GROUP / DEPARTMENT / PRODUCT_LINE / COMPANY */
    private String approvalScopeType;
    /** 审批范围名称(如开发一组/研发部/数据产品线/杭研) */
    private String approvalScopeName;
    /** 状态: ACTIVE / INACTIVE */
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
