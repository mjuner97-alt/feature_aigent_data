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
package com.agentscopea2a.v2.trace.writer;

import com.agentscopea2a.mapper.ck.TraceCkMapper;
import com.agentscopea2a.v2.trace.model.AssembledTrace;
import com.agentscopea2a.v2.trace.model.TraceConversation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Trace 写入器：在请求结束（cleanup）时把组装后的 {@link AssembledTrace} 直接写入 ClickHouse，
 * 失败时降级到本地文件。
 *
 * <p>不再使用定时调度攒批：中间事件由 {@code TraceSession} 在请求期间缓存（records 列表），
 * 请求结束时 {@code TraceAssembler.assemble} 后调用 {@link #write} 一次性落库，成功/失败/超时
 * 均执行（cleanup 在所有终止路径触发）。ClickHouse 两表独立插入，任一失败抛异常后降级写文件。
 */
@Component
public class TraceBatchWriter {

    private static final Logger log = LoggerFactory.getLogger(TraceBatchWriter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TraceCkMapper ckMapper;
    private final TraceFallbackWriter fallbackWriter;

    public TraceBatchWriter(TraceCkMapper ckMapper, TraceFallbackWriter fallbackWriter) {
        this.ckMapper = ckMapper;
        this.fallbackWriter = fallbackWriter;
    }

    /**
     * 写入单条组装后的 trace：先插入 ClickHouse（两表），失败则降级到本地文件。
     *
     * <p>由请求结束（cleanup）时调用，不再依赖定时调度。无论本次请求成功还是报错都会执行
     * （cleanup 在 onCompletion/onTimeout/onError 三条终止路径都会触发）。
     *
     * @param trace 组装后的 trace，null 时直接返回
     */
    public void write(AssembledTrace trace) {
        if (trace == null) return;
        try {
            batchInsert(List.of(trace));
        } catch (Exception e) {
            log.error("TraceBatchWriter insert failed, falling back: conversationId={}: {}",
                    trace.conversation().getConversationId(), e.getMessage(), e);
            try {
                fallbackWriter.write(trace);
            } catch (Exception fe) {
                log.error("TraceFallbackWriter also failed for conversationId={}: {}",
                        trace.conversation().getConversationId(), fe.getMessage(), fe);
            }
        }
    }

    /**
     * 把每个 AssembledTrace 的 eventJsons 拆解为 trace_event 行。
     * ClickHouse 无事务，两表独立插入，任一失败抛异常由调用方降级。
     */
    private void batchInsert(List<AssembledTrace> batch) {
        List<TraceConversation> conversations = new ArrayList<>(batch.size());
        List<Map<String, Object>> eventRows = new ArrayList<>(batch.size() * 8);
        for (AssembledTrace t : batch) {
            conversations.add(t.conversation());
            for (String json : t.eventJsons()) {
                eventRows.add(toEventRow(t.conversation().getConversationId(),
                        t.conversation().getTraceId(), json));
            }
        }
        if (!conversations.isEmpty()) {
            ckMapper.insertConversation(conversations);
        }
        if (!eventRows.isEmpty()) {
            ckMapper.insertEvents(eventRows);
        }
    }

    /**
     * 从 AgentEvent JSON 中提取扁平字段（eventId / eventType / eventName /
     * source / createdAt）。不解析嵌套字段，原 JSON 完整存入 event_json。
     */
    private static Map<String, Object> toEventRow(String conversationId, String traceId, String json) {
        Map<String, Object> row = new HashMap<>(9);
        row.put("event_id", "");
        row.put("conversation_id", conversationId);
        row.put("trace_id", traceId);
        row.put("event_type", "");
        row.put("event_name", "");
        row.put("source", "");
        // DateTime64(3) 列：用 Timestamp 写入，驱动按日期处理；直接写 long 会被当作秒解析
        row.put("timestamp", new Timestamp(System.currentTimeMillis()));
        row.put("duration_ms", 0);
        row.put("event_json", json);
        try {
            JsonNode n = MAPPER.readTree(json);
            row.put("event_id", textOrEmpty(n, "id"));
            row.put("event_type", textOrEmpty(n, "type"));
            row.put("event_name", textOrEmpty(n, "type"));  // 同 type
            row.put("source", textOrEmpty(n, "source"));
            String createdAt = textOrEmpty(n, "createdAt");
            if (!createdAt.isEmpty()) {
                try {
                    row.put("timestamp", new Timestamp(java.time.Instant.parse(createdAt).toEpochMilli()));
                } catch (Exception ignored) {
                    // 保留 currentTimeMillis 兜底
                }
            }
        } catch (Exception e) {
            // 解析失败：用 defaults
        }
        return row;
    }

    private static String textOrEmpty(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? "" : v.asText("");
    }
}
