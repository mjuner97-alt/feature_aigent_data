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
package com.agentscopea2a.v2.skillManager.dto;

import com.agentscopea2a.v2.skillManager.entity.SkillJob;

import java.time.LocalDateTime;

/**
 * SkillJob 详情响应 DTO。outputPath 不暴露给前端（磁盘路径由后端按 userId + baseDir 拼）。
 */
public record SkillJobDto(
        Long id,
        String name,
        Long skillId,
        String questionTemplate,
        Boolean enabled,
        Long metricId,
        String metricCode,
        String metricName,
        String createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static SkillJobDto of(SkillJob job) {
        return new SkillJobDto(
                job.getId(), job.getName(), job.getSkillId(),
                job.getQuestionTemplate(),
                job.getEnabled(),
                job.getMetricId(), job.getMetricCode(), job.getMetricName(),
                job.getCreatedBy(), job.getCreatedAt(), job.getUpdatedAt());
    }
}
