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

import com.agentscopea2a.v2.skillManager.entity.SkillDependencyMetric;

import java.time.LocalDateTime;

/**
 * 依赖指标详情响应 DTO（只读）。
 */
public record SkillDependencyMetricDto(
        Long id,
        String code,
        String name,
        String description,
        Boolean enabled,
        Boolean notifyEnabled,
        String notifyContentType,
        String notifyContentTemplate,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static SkillDependencyMetricDto of(SkillDependencyMetric m) {
        return new SkillDependencyMetricDto(
                m.getId(), m.getCode(), m.getName(),
                m.getDescription(), m.getEnabled(),
                m.getNotifyEnabled(), m.getNotifyContentType(), m.getNotifyContentTemplate(),
                m.getCreatedAt(), m.getUpdatedAt());
    }
}
