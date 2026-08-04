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

import com.agentscopea2a.v2.trace.collector.TraceSession;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.sql.Timestamp;

/** 会话汇总 POJO，对应 ClickHouse trace_conversation 表 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TraceConversation {

    private String conversationId;
    private String traceId;
    private String userId;
    private String source;
    private String agentId;
    private String agentName;
    /** ClickHouse DateTime64(3)：用 Timestamp 映射，避免 long 在驱动侧被当作秒解析（读时也无法 parse 成 long）。 */
    private Timestamp startTs;
    private Timestamp endTs;
    private long durationMs;
    private String status;
    private String errorMessage;
    private int eventCount;
    private long tokenInput;
    private long tokenOutput;
    private String model;

    /** 从 {@link TraceSession} 构造汇总（endTs 用 currentTimeMillis 是 cleanup 时点）。 */
    public static TraceConversation from(TraceSession s) {
        long start = s.getRequestStartTs();
        long end = s.getRequestEndTs();
        return new TraceConversation(
                nz(s.getConversationId()),
                nz(s.getTraceId()),
                nz(s.getUserId()),
                nz(s.getSource()),
                "",    // agentId 暂未采集
                "",    // agentName 暂未采集
                new Timestamp(start),
                new Timestamp(end),
                end - start,
                nz(s.getStatus()),
                nz(s.getErrorMessage()),
                s.eventCount(),
                s.getTokenInput(),
                s.getTokenOutput(),
                nz(s.getModelName()));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
