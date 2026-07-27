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
 * Skill 发布记录实体，记录技能向不同目标的发布流程与审批状态。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillPublish {
    private Long id;
    private Long skillId;
    private String targetType;
    private String targetId;
    private String targetName;
    private String status;
    private String submitter;
    private String approver;
    private LocalDateTime approveTime;
    private String currentApproverUserId;
    private String lastApprovalComment;
    private LocalDateTime lastApprovalAt;
    private LocalDateTime createdAt;
    // 审批列表展示用冗余字段(由 selectPendingByApprover LEFT JOIN skill_manage 填充)
    private String name;
    private String description;
    private String category;
}
