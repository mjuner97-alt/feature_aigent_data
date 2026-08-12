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

/**
 * 更新 SkillJob 的请求体，所有字段可选，仅更新非 null 字段。
 * skillId / metricId 可修改：前端编辑表单可更换关联 Skill 或依赖指标。
 *   - metricId 取值约定：正值 = 关联该指标；0 = 清除关联（不关联）；null = 不修改（保留原值）。
 *   - 这样既支持"更换指标"，也支持"取消关联"，又兼容 toggleEnabled 等部分更新（不传 metricId 即保留）。
 * createdBy 不可变；outputPath 由后端按 userId + baseDir 拼，不暴露。
 */
public record SkillJobUpdateRequest(
        String name,
        Long skillId,
        Long metricId,
        String questionTemplate,
        Boolean enabled
) {}
