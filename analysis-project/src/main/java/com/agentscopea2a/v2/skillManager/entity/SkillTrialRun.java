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
 * Skill 试运行记录实体 - 对应 skill_trial_run 表。
 * Skill 详情页试运行区块:用户录入问数话术后前端调 /ai/chat 问数,
 * 每次试运行落一条记录;skillName 由后端按 skillId 回填,不信任前端传值。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillTrialRun {
    private Long id;
    /** Skill ID, 关联 skill_manage.id */
    private Long skillId;
    /** Skill 名称(后端回填) */
    private String skillName;
    /** 问数会话ID(前端生成,可跳转 /chat?session= 复盘) */
    private String conversationId;
    /** 用户录入的问数话术 */
    private String question;
    /** 试运行人 (X-User-Id) */
    private String userId;
    private LocalDateTime createdAt;
}
