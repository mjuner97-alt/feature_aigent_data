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
 * 依赖指标实体，对应 skill_dependency_metric 表。
 *
 * <p>admin 预置（直接 SQL），用户只读，无 CRUD 接口。
 * 建 Job 时从预置列表选一个；外部系统按 {@code code} 调 {@code triggerByMetric} 批量触发。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillDependencyMetric {
    /** 主键 */
    private Long id;
    /** 业务编码（唯一），外部 triggerByMetric 用它 */
    private String code;
    /** 展示名（下拉显示） */
    private String name;
    /** 说明 */
    private String description;
    /** 是否启用：false 后不可被新建 Job 选中且不可被触发 */
    private Boolean enabled;
    /** 跑批(METRIC触发)成功后是否发送通知（admin 预置，默认 FALSE） */
    private Boolean notifyEnabled;
    /** 通知内容格式：TEXT 纯文本 / HTML（默认 HTML） */
    private String notifyContentType;
    /** 通知内容模板（支持 {job_name}/{metric_name}/{metric_code}/{execution_id}/{status}/{date}/{file_name}/{file_path} 变量；为空用内置默认模板） */
    private String notifyContentTemplate;
    /** 创建时间 */
    private LocalDateTime createdAt;
    /** 更新时间 */
    private LocalDateTime updatedAt;
}
