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
 * Skill 实体 - Skill 管理平台的主表记录，承载技能元数据、归属与点赞计数。
 *
 * <p>由 {@code com.agentscopea2a.v2.service.SkillService} 管理；表 {@code skill_manage}
 * 通过 {@code src/main/resources/mybatis/mapper/mysql/SkillManageMapper.xml} 映射，
 * DDL 见 {@code src/main/resources/db/migration/V20260723.1__create_skill_manage.sql}。
 *
 * <p>包路径受 {@code MySQLConfig.setTypeAliasesPackage("com.agentscopea2a.entity")} 约束，
 * 必须放在此包以便 MyBatis 解析 XML 中的类型别名。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Skill {
    private Long id;
    private String name;
    private String description;
    private String content;
    private String category;
    private String tags;
    private String ownerUserId;
    private String status;
    private Long likeCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;
}
