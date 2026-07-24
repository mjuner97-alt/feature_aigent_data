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
package com.agentscopea2a.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 引用关系实体。语义:creator 引用 target_skill_id(=source_skill_id)。
 * 表 {@code skill_reference} 由 Flyway 迁移 {@code V20260723.3__create_skill_reference.sql} 创建。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillReference {
    private Long id;
    private Long sourceSkillId;
    private Long targetSkillId;
    private String creator;
    private LocalDateTime createdAt;
}
