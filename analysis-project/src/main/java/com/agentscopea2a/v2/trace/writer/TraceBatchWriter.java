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
import com.agentscopea2a.v2.trace.config.TraceProperties;
import com.agentscopea2a.v2.trace.model.AssembledTrace;
import com.agentscopea2a.v2.trace.model.TraceConversation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** 定时批量写入器，从队列攒批写入 ClickHouse，失败时降级到本地文件 */
@Component
public class TraceBatchWriter {

    private static final Logger log = LoggerFactory.getLogger(TraceBatchWriter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TraceQueue queue;
    private final TraceCkMapper ckMapper;
    private final TraceFallbackWriter fallbackWriter;
    private final TraceProperties properties;

    public TraceBatchWriter(TraceQueue queue,
                            TraceCkMapper ckMapper,
                            TraceFallbackWriter fallbackWriter,
                            TraceProperties properties) {
        this.queue = queue;
        this.ckMapper = ckMapper;
        this.fallbackWriter = fallbackWriter;
        this.properties = properties;
    }

    // 调度间隔硬编码 2s（与 TraceProperties.batch.intervalSeconds 默认值一致），不再读 application.properties
    @Scheduled(fixedDelay = 2000L)
    public void drainAndWrite() throws InterruptedException {
        List<AssembledTrace> batch = drainBatch();
        if (batch.isEmpty()) return;
        log.debug("TraceBatchWriter draining {} traces", batch.size());
        try {
            batchInsert(batch);
        } catch (Exception e) {
            log.error("TraceBatchWriter batch insert failed, falling back {} traces: {}",
                    batch.size(), e.getMessage(), e);
            for (AssembledTrace t : batch) {
                try {
                    fallbackWriter.write(t);
                } catch (Exception fe) {
                    log.error("TraceFallbackWriter also failed for conversationId={}: {}",
                            t.conversation().getConversationId(), fe.getMessage(), fe);
                }
            }
        }
    }

    private List<AssembledTrace> drainBatch() throws InterruptedException {
        int batchSize = properties.getBatch().getSize();
        int intervalSeconds = properties.getBatch().getIntervalSeconds();
        List<AssembledTrace> batch = new ArrayList<>(Math.min(batchSize, 16));

        AssembledTrace first = queue.poll(intervalSeconds, TimeUnit.SECONDS);
        if (first == null) return batch;
        batch.add(first);

        while (batch.size() < batchSize) {
            AssembledTrace t = queue.poll(0, TimeUnit.MILLISECONDS);
            if (t == null) break;
            batch.add(t);
        }
        return batch;
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
        row.put("timestamp", System.currentTimeMillis());
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
                    row.put("timestamp", java.time.Instant.parse(createdAt).toEpochMilli());
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
