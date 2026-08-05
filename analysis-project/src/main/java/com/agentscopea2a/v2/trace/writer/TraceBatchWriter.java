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
 * 失败时重试一次，重试仍失败则丢弃。
 *
 * <p>不再使用定时调度攒批：中间事件由 {@code TraceSession} 在请求期间缓存（records 列表），
 * 请求结束时 {@code TraceAssembler.assemble} 后调用 {@link #write} 一次性落库，成功/失败/超时
 * 均执行（cleanup 在所有终止路径触发）。
 *
 * <p>写入顺序：先插 trace_event（数据量大，更容易失败），再插 trace_conversation。
 * ClickHouse 无事务，先写 event 可避免"有 conversation 无 event"的数据不一致——
 * 若 event 写入失败则 conversation 也不会写入，保证查到 conversation 时一定有对应的 event。
 */
@Component
public class TraceBatchWriter {

    private static final Logger log = LoggerFactory.getLogger(TraceBatchWriter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 写入失败后重试次数（不含首次尝试） */
    private static final int MAX_RETRIES = 1;

    private final TraceCkMapper ckMapper;

    public TraceBatchWriter(TraceCkMapper ckMapper) {
        this.ckMapper = ckMapper;
    }

    /**
     * 写入单条组装后的 trace：先插入 ClickHouse（两表），失败则重试一次，仍失败则丢弃。
     *
     * <p>由请求结束（cleanup）时调用，不再依赖定时调度。无论本次请求成功还是报错都会执行
     * （cleanup 在 onCompletion/onTimeout/onError 三条终止路径都会触发）。
     *
     * @param trace 组装后的 trace，null 时直接返回
     */
    public void write(AssembledTrace trace) {
        if (trace == null) return;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                batchInsert(List.of(trace));
                return; // 成功则直接返回
            } catch (Exception e) {
                if (attempt < MAX_RETRIES) {
                    log.warn("TraceBatchWriter insert failed (attempt {}/{}), retrying: conversationId={}: {}",
                            attempt + 1, MAX_RETRIES + 1,
                            trace.conversation().getConversationId(), e.getMessage());
                } else {
                    log.error("TraceBatchWriter insert failed after {} attempts, discarding: conversationId={}: {}",
                            MAX_RETRIES + 1, trace.conversation().getConversationId(), e.getMessage(), e);
                }
            }
        }
    }

    /**
     * 把每个 AssembledTrace 的 eventJsons 拆解为 trace_event 行。
     * ClickHouse 无事务，先写 event 再写 conversation，避免"有 conversation 无 event"的不一致。
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
        // 先写 event（数据量大，更容易失败），再写 conversation
        // 这样如果 event 写入失败，conversation 也不会写入，避免"有 conversation 无 event"
        if (!eventRows.isEmpty()) {
            ckMapper.insertEvents(eventRows);
        }
        if (!conversations.isEmpty()) {
            ckMapper.insertConversation(conversations);
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
