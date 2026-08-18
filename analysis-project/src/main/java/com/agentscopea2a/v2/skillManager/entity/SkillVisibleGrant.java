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
 * Skill 私有可见性授权实体 - 对应表 {@code skill_visible_grant}。
 *
 * <p>无审批:owner 直接指定谁能看到/可用一个 {@code PRIVATE} skill。
 * 授权对象四种: {@code USER}(统一认证号) / {@code DEPARTMENT}(部门名) / {@code GROUP}(统计组名)
 * / {@code VIRTUAL_GROUP}(虚拟组名,组定义见 skill_virtual_group 表)。
 * 命中授权的用户对私有 skill 自动可用(used),无需手动引用。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillVisibleGrant {
    private Long id;
    private Long skillId;
    /** USER | DEPARTMENT | GROUP | VIRTUAL_GROUP */
    private String grantType;
    /** USER=统一认证号 / DEPARTMENT=部门名 / GROUP=统计组名 / VIRTUAL_GROUP=虚拟组名 */
    private String targetId;
    /** 授权人(owner) */
    private String grantedBy;
    private LocalDateTime createdAt;
}