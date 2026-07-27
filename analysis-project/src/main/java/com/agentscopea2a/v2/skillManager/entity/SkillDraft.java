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
 * Skill 草稿实体，保存技能编辑过程中的暂存内容与提交/审批时间线。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillDraft {
    private Long id;
    private Long skillId;
    private String name;
    private String description;
    private String content;
    private String category;
    private String tags;
    private String status;
    private String submitter;
    private String approver;
    private String approveComment;
    private LocalDateTime submittedAt;
    private LocalDateTime approvedAt;
}
