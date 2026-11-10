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

import java.util.List;

/**
 * 更新 SkillJob 的请求体，所有字段可选，仅更新非 null 字段。
 * 依赖指标统一继承关联 Skill；保留的任务级指标字段仅兼容旧请求，不再使用。
 * createdBy 不可变；outputPath 由后端按 userId + baseDir 拼，不暴露。
 */
public record SkillJobUpdateRequest(
        String name,
        Long skillId,
        Long metricId,
        List<Long> metricIds,
        Boolean metricOverrideConfigured,
        String questionTemplate,
        Boolean enabled,
        /** 按星期配置执行时间；传 null 表示保留原配置 */
        String scheduleRules,
        /** 传 null 保留，传空列表清空，传非空列表替换 */
        List<String> notifyReceivers
) {}
