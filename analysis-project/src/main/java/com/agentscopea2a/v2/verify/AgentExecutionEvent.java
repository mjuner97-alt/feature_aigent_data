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
package com.agentscopea2a.v2.verify;

import java.util.Map;

/**
 * One node in the request's execution trace. Type names align with the framework's
 * {@code HookEventType} so the {@code verification_event} table mirrors the JSONL output of
 * the framework's {@code JsonlTraceExporter} (see V3.0 design §6.1).
 *
 * @param eventId        unique id (evt_0001 style)
 * @param type           one of the {@code *_TYPE} constants below
 * @param actor          agent name (supervisor / analyze_data / verify / critic ...)
 * @param parentEventId  causal parent (for tool-call hierarchy)
 * @param sessionId      RuntimeContext.getSessionId()
 * @param timestamp      HookEvent.getTimestamp() (framework-provided, no self-rolled clock)
 * @param payload        event-specific fields
 */
public record AgentExecutionEvent(
        String eventId,
        String type,
        String actor,
        String parentEventId,
        String sessionId,
        long timestamp,
        Map<String, Object> payload) {

    public static final String AGENT_STARTED = "AGENT_STARTED";
    public static final String AGENT_FINISHED = "AGENT_FINISHED";
    public static final String TOOL_CALL_STARTED = "TOOL_CALL_STARTED";
    public static final String TOOL_CALL_COMPLETED = "TOOL_CALL_COMPLETED";
    public static final String ARTIFACT_PRODUCED = "ARTIFACT_PRODUCED";
    public static final String DECISION_MADE = "DECISION_MADE";
    public static final String ERROR_OCCURRED = "ERROR_OCCURRED";
}
