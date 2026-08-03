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
package com.agentscopea2a.v2.trace.model;

/**
 * Trace 事件记录：承载一条已序列化的 Hook 事件 JSON 及其创建时间。
 *
 * <p>createdAt 为 ISO-8601 字符串（{@code Instant.now().toString()}），可直接按字典序排序，
 * 与框架 AgentEvent 的 createdAt 格式一致。json 为完整事件 JSON（含 type/createdAt/source/id
 * 及 payload 字段），落库时原样写入 trace_event.event_json。
 *
 * @param createdAt 事件创建时间（ISO-8601），用于排序
 * @param json      完整事件 JSON 字符串
 */
public record TraceEventRecord(String createdAt, String json) {
}
