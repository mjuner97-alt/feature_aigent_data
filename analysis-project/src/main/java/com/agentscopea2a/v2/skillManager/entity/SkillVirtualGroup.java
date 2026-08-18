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
 * 虚拟组成员实体 - 对应表 {@code skill_virtual_group}。
 *
 * <p>一行 = 一个虚拟组的一个成员(组名+userid);组的定义在
 * {@code skill_virtual_group_def}(组头表),空组(无成员)合法存在。
 * 私有 Skill 授权({@code skill_visible_grant})的 {@code VIRTUAL_GROUP} 类型
 * 按 {@code group_name} 命中:用户是组内成员即对被授权的私有 skill 可见。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillVirtualGroup {
    private Long id;
    /** 虚拟组名(组的唯一标识) */
    private String groupName;
    /** 成员统一认证号 */
    private String userId;
    /** 建组/加成员操作人 */
    private String createdBy;
    private LocalDateTime createdAt;
}
