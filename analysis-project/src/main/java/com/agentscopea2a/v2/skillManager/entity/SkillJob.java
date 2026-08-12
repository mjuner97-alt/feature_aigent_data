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
 * SkillJob 定时任务配置实体，对应 skill_job 表。
 * 绑定 Skill + 提问模板 + MD 输出路径。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillJob {
    /** 主键 */
    private Long id;
    /** 任务名称（唯一） */
    private String name;
    /** 关联的 Skill ID */
    private Long skillId;
    /** 关联 Skill 名称（join 展示，非持久化）；跨用户查看 job 列表时用，避免前端按当前用户 skill 列表解析不到 */
    private String skillName;
    /** 提问模板，支持 {date}/{skill_name} 变量替换 */
    private String questionTemplate;
    /** MD 输出路径（相对 workspace），如 reports/daily/{date}.md */
    private String outputPath;
    /** 是否启用 */
    private Boolean enabled;
    /** 依赖指标 ID（多对一）；可选，关联后随指标就绪触发（triggerByMetric） */
    private Long metricId;
    /** 依赖指标编码（join 展示，非持久化） */
    private String metricCode;
    /** 依赖指标名称（join 展示，非持久化） */
    private String metricName;
    /** 创建人 userId */
    private String createdBy;
    /** 创建时间 */
    private LocalDateTime createdAt;
    /** 更新时间 */
    private LocalDateTime updatedAt;
}
