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
 * 创建 SkillJob 的请求体。
 * name/skillId/questionTemplate 为必填，metricId 可选（关联后随指标就绪触发），outputPath 由系统自动生成。
 * 若传 metricId，须为已启用（enabled=true）的预置依赖指标。
 */
public record SkillJobCreateRequest(
        String name,
        Long skillId,
        String questionTemplate,
        Long metricId,
        /** 按星期配置执行时间，JSON 格式如 {"WED":["09:00"]} */
        String scheduleRules
) {}
