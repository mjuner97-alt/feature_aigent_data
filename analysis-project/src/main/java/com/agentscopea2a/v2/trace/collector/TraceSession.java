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
package com.agentscopea2a.v2.trace.collector;

import com.agentscopea2a.v2.trace.model.TraceEventRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.model.ChatUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 请求级 Trace 会话：存储 {@link AiChatRestToolCallTrackingToDbHook} 采集的 Hook 事件记录，
 * 在 cleanup 时序列化持久化。
 *
 * <p>不再存储框架 AgentEvent delta 流——内容（LLM 输入/思考/输出、工具入参/返回）由 Hook 事件
 * 完整承载，每个操作一条记录。token 统计由 {@link #recordUsage(ModelCallEndEvent)} 累加
 * （ModelCallEndEvent 是唯一携带 usage 的事件，仍从 AgentEvent 流采集，但仅此一种）。
 */
public class TraceSession {

    public static final String KEY = "trace_session";

    private static final Logger log = LoggerFactory.getLogger(TraceSession.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String conversationId;
    private final String traceId;
    private final String userId;
    private final String source;
    private final long requestStartTs;
    private final String modelName = "";
    /** 用户原始输入，序列化为事件流最前端的虚拟 USER_INPUT 事件 */
    private final String userQuery;

    /** Hook 事件记录列表（每条已含完整 payload 的 JSON） */
    private final CopyOnWriteArrayList<TraceEventRecord> records = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, ToolTimingStart> activeToolTimings = new ConcurrentHashMap<>();

    /** token 统计：由 ModelCallEndEvent 累加（不依赖 records） */
    private final AtomicLong tokenInput = new AtomicLong(0);
    private final AtomicLong tokenOutput = new AtomicLong(0);

    private final AtomicReference<String> status = new AtomicReference<>("RUNNING");
    private volatile String errorMessage = "";
    private volatile boolean sealed = false;

    public TraceSession(String conversationId, String traceId, String userId, String source, String userQuery) {
        this.conversationId = conversationId;
        this.traceId = traceId;
        this.userId = (userId == null || userId.isBlank()) ? "anonymous" : userId;
        this.source = source;
        this.userQuery = userQuery == null ? "" : userQuery;
        this.requestStartTs = System.currentTimeMillis();
    }

    /** 追加一条 Hook 事件记录，sealed 后丢弃 */
    public void addRecord(TraceEventRecord record) {
        if (sealed || record == null) return;
        records.add(record);
    }

    public void startToolTiming(String toolCallId, Instant startedAt, long startedNanos) {
        if (sealed || toolCallId == null || toolCallId.isBlank() || startedAt == null) return;
        activeToolTimings.put(toolCallId, new ToolTimingStart(startedAt, startedNanos));
    }

    public ToolTiming finishToolTiming(String toolCallId, Instant endedAt, long endedNanos) {
        if (toolCallId == null || toolCallId.isBlank() || endedAt == null) return null;
        ToolTimingStart start = activeToolTimings.remove(toolCallId);
        if (start == null) return null;
        long durationMs = Math.max(0L, (endedNanos - start.startedNanos) / 1_000_000L);
        return new ToolTiming(start.startedAt.toString(), endedAt.toString(), durationMs);
    }

    private record ToolTimingStart(Instant startedAt, long startedNanos) {}
    public record ToolTiming(String startedAt, String endedAt, long durationMs) {}

    /** 累加 ModelCallEndEvent 的 token 用量（token 统计专用），sealed 后丢弃 */
    public void recordUsage(ModelCallEndEvent event) {
        if (sealed || event == null) return;
        ChatUsage u = event.getUsage();
        if (u == null) return;
        tokenInput.addAndGet(u.getInputTokens());
        tokenOutput.addAndGet(u.getOutputTokens());
    }

    /** 状态机 */
    public void markError(String msg) {
        status.compareAndSet("RUNNING", "ERROR");
        if (msg != null) errorMessage = msg;
    }

    public void markTimeout() {
        status.compareAndSet("RUNNING", "TIMEOUT");
    }

    public void markSuccess() {
        status.compareAndSet("RUNNING", "SUCCESS");
    }

    /**
     * 序列化全部记录为 JSON 字符串列表（按 createdAt 升序）。
     * 头部插入一条 USER_INPUT 虚拟事件承载用户原始输入。调用后 sealed，阻止后续写入。
     */
    public List<String> serializeEventsJson() {
        sealed = true;
        List<TraceEventRecord> sorted = new ArrayList<>(records);
        sorted.sort((a, b) -> {
            String ca = a.createdAt();
            String cb = b.createdAt();
            if (ca == null && cb == null) return 0;
            if (ca == null) return -1;
            if (cb == null) return 1;
            return ca.compareTo(cb);
        });
        List<String> out = new ArrayList<>(sorted.size() + 1);
        // 1) 头部：用户输入（虚拟事件）
        if (!userQuery.isEmpty()) {
            try {
                com.fasterxml.jackson.databind.node.ObjectNode userNode = MAPPER.createObjectNode();
                userNode.put("type", "USER_INPUT");
                userNode.put("id", "user-input-" + conversationId);
                userNode.put("createdAt", Instant.ofEpochMilli(requestStartTs).toString());
                userNode.put("source", "user");
                userNode.put("text", userQuery);
                out.add(MAPPER.writeValueAsString(userNode));
            } catch (Exception ex) {
                log.warn("TraceSession serialize userQuery failed: {}", ex.getMessage());
            }
        }
        // 2) Hook 事件记录
        for (TraceEventRecord r : sorted) {
            out.add(r.json());
        }
        return out;
    }

    // ---- getters ----
    public String getConversationId() { return conversationId; }
    public String getTraceId() { return traceId; }
    public String getUserId() { return userId; }
    public String getSource() { return source; }
    public String getModelName() { return modelName; }
    public long getRequestStartTs() { return requestStartTs; }
    public long getRequestEndTs() { return System.currentTimeMillis(); }
    public String getStatus() { return status.get(); }
    public String getErrorMessage() { return errorMessage; }
    public boolean isSealed() { return sealed; }
    public int eventCount() { return records.size(); }
    public long getTokenInput() { return tokenInput.get(); }
    public long getTokenOutput() { return tokenOutput.get(); }

    /** 生成新 traceId */
    public static String newTraceId() {
        return "trace_" + UUID.randomUUID().toString().replace("-", "");
    }
}
