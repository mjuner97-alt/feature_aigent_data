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
package com.agentscopea2a.v2.trace.service;

import com.agentscopea2a.mapper.ck.TraceCkMapper;
import com.agentscopea2a.v2.trace.model.TraceConversation;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Trace 查询服务，提供会话列表和单会话详情查询 */
@Service
public class TraceQueryService {

    private final TraceCkMapper traceCkMapper;

    public TraceQueryService(TraceCkMapper traceCkMapper) {
        this.traceCkMapper = traceCkMapper;
    }

    /** 会话列表（分页）。 */
    public Map<String, Object> listConversations(String source, int page, int size) {
        int offset = page * size;
        List<TraceConversation> rows = traceCkMapper.listConversations(source, offset, size);
        List<Map<String, Object>> items = rows == null || rows.isEmpty()
                ? Collections.emptyList()
                : rows.stream().map(TraceQueryService::toSummary).toList();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("conversations", items);
        resp.put("total", items.size());
        resp.put("page", page);
        resp.put("size", size);
        return resp;
    }

    /** 单会话详情（含事件 JSON 列表，按 timestamp ASC）。 */
    public Map<String, Object> getDetail(String conversationId) {
        TraceConversation c = traceCkMapper.getConversation(conversationId);
        if (c == null) return null;
        List<String> events = traceCkMapper.listEventJsons(conversationId);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("conversation", toSummary(c));
        resp.put("events", events == null ? Collections.emptyList() : events);
        return resp;
    }

    // -------- helpers --------

    private static Map<String, Object> toSummary(TraceConversation c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("conversationId", nv(c.getConversationId()));
        m.put("traceId", nv(c.getTraceId()));
        m.put("userId", nv(c.getUserId()));
        m.put("source", nv(c.getSource()));
        m.put("agentId", nv(c.getAgentId()));
        m.put("agentName", nv(c.getAgentName()));
        m.put("startTime", toIso(c.getStartTs()));
        m.put("endTime", toIso(c.getEndTs()));
        m.put("durationMs", c.getDurationMs());
        m.put("status", nv(c.getStatus()));
        m.put("errorMessage", nv(c.getErrorMessage()));
        m.put("eventCount", c.getEventCount());
        m.put("tokenInput", c.getTokenInput());
        m.put("tokenOutput", c.getTokenOutput());
        m.put("model", nv(c.getModel()));
        return m;
    }

    private static String nv(String s) { return s == null ? "" : s; }

    private static String toIso(Timestamp ts) {
        if (ts == null) return "";
        return ts.toInstant().toString();
    }
}
