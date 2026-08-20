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
 * 虚拟组定义实体 - 对应组头表 {@code skill_virtual_group_def}。
 *
 * <p>一行 = 一个虚拟组(组名主键)。组的定义与成员分离:
 * 成员在 {@code skill_virtual_group}(一行 = 一个成员),空组(无成员)合法存在。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillVirtualGroupDef {
    /** 虚拟组名(组的唯一标识,主键) */
    private String groupName;
    /** 建组人 */
    private String createdBy;
    private LocalDateTime createdAt;
}
