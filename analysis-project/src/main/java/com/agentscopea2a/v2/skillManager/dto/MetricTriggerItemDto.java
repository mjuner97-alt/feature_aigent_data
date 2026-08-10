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
 * 批量触发中单个 Job 的结果。
 */
public record MetricTriggerItemDto(
        Long jobId,
        String name,
        /** 排队后拿到的执行记录 id；REJECTED 时为 null */
        Long executionId,
        /** QUEUED | REJECTED */
        String status,
        /** REJECTED 时填，如 JobAlreadyRunning */
        String reason
) {}
