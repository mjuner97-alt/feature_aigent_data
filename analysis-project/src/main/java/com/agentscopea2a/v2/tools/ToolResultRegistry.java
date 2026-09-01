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
package com.agentscopea2a.v2.tools;

import com.agentscopea2a.mapper.gauss.ChatToolResultMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * /ai/chat 工具结果引用池：refId -&gt; 图表块原文（纯 String，不做 DTO 封装）。
 *
 * <p>写入方：{@link com.agentscopea2a.v2.hooks.ToolCallTrackingHook} 在 PostActing 时
 * 登记 script_exec 产出的图表块，并异步落 Gauss 表 ai_chat_tool_result（重启兜底）。
 * 读取方：
 * <ul>
 *   <li>{@code ChatStreamServiceImpl.handleStreamSuccess}：推送 agent_result 前把
 *       {@code {{TOOL_RESULT:tr_xxx}}} 标记内联解析成原文（实时链路）</li>
 *   <li>{@code GET /ai/chat/tool-result/{refId}}：历史回放懒拉取，内存未命中回查 DB</li>
 * </ul>
 *
 * <p>refId 每次工具调用新生成（UUID 截断），连续对话/同会话/同工具都不复用，
 * 历史引用永不被后续轮次覆盖。
 *
 * <p>内存 map 生命周期 = JVM 进程，带 TTL + 容量上限懒清理；持久层数据在 DB。
 */
@Service
public class ToolResultRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolResultRegistry.class);

    /** 引用标记格式：{@code {{TOOL_RESULT:tr_xxxxxxxxxxxx}}} */
    public static final String MARKER_TEMPLATE = "{{TOOL_RESULT:%s}}";

    private static final Pattern MARKER_PATTERN =
            Pattern.compile("\\{\\{TOOL_RESULT:(tr_[0-9a-f]{1,12})\\}\\}");

    /** 内存条目 TTL：覆盖"同请求内登记->推出时解析"的热路径 + 短期回放 */
    private static final long TTL_MILLIS = 2 * 60 * 60 * 1000L;

    /** 内存容量上限：超限触发懒清理（DB 不受影响） */
    private static final int MAX_ENTRIES = 1024;

    private final ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> createdAt = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, java.util.concurrent.CopyOnWriteArrayList<String>> requestRefs = new ConcurrentHashMap<>();

    public void addRequestRef(String requestId, String refId) {
        if (requestId != null && refId != null) requestRefs.computeIfAbsent(requestId, k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(refId);
    }

    public List<String> getRequestRefs(String requestId) {
        if (requestId == null) return List.of();
        List<String> refs = requestRefs.get(requestId);
        return refs == null ? List.of() : List.copyOf(refs);
    }

    public void clearRequestRefs(String requestId) { if (requestId != null) requestRefs.remove(requestId); }

    /** Gauss mapper 可缺席（gauss 数据源关闭的部署形态），届时退化为纯内存 */
    private final ObjectProvider<ChatToolResultMapper> mapperProvider;

    public ToolResultRegistry(ObjectProvider<ChatToolResultMapper> mapperProvider) {
        this.mapperProvider = mapperProvider;
    }

    /**
     * 登记一份图表/HTML 块原文，返回新分配的 refId；
     * 原文先写入 JVM 内存，供本次 /ai/chat 最终返回快速解析，随后异步落库。
     */
    public String register(String conversationId, String toolCallId, String toolName, String content) {
        evictIfNeeded();
        String refId = "tr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        store.put(refId, content == null ? "" : content);
        createdAt.put(refId, System.currentTimeMillis());

        ChatToolResultMapper mapper = mapperProvider.getIfAvailable();
        if (mapper != null) {
            Mono.fromRunnable(() -> {
                        try {
                            mapper.insertToolResult(refId, conversationId, toolCallId, toolName, content);
                        } catch (Exception ex) {
                            log.warn("ToolResult DB persist failed for refId={}: {}", refId, ex.getMessage());
                        }
                    })
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe();
        }
        return refId;
    }

    /**
     * 按 refId 取原文：内存优先，未命中（重启/TTL 清理）回查 DB。
     * 供 REST 懒拉取端点使用。
     */
    public String get(String refId) {
        if (refId == null) return null;
        String content = store.get(refId);
        if (content != null) return content;
        ChatToolResultMapper mapper = mapperProvider.getIfAvailable();
        if (mapper == null) return null;
        try {
            return mapper.selectContentByRefId(refId);
        } catch (Exception ex) {
            log.warn("ToolResult DB query failed for refId={}: {}", refId, ex.getMessage());
            return null;
        }
    }

    /**
     * 推出 agent_result 前调用：同请求内刚登记的条目必然在内存。
     * 未命中（理论上不应发生）替换为失效提示，不把原始标记漏给用户。
     */
    public String resolveMarkers(String text) {
        if (text == null || text.isEmpty() || store.isEmpty()) return text;
        Matcher m = MARKER_PATTERN.matcher(text);
        if (!m.find()) return text;
        StringBuilder sb = new StringBuilder();
        m.reset();
        while (m.find()) {
            String content = store.get(m.group(1));
            String replacement = content != null
                    ? Matcher.quoteReplacement(content)
                    : "（结果引用 " + m.group(1) + " 已失效）";
            m.appendReplacement(sb, replacement);
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 解析即将持久化或展示给用户的最终结果中的工具结果引用。
     * 统一入口供实时聊天、Skill 定时任务和长任务节点复用。
     */
    public String resolveFinalResult(String text) {
        return resolveMarkers(text);
    }

    /**
     * /ai/chat 最终出参专用：仅展开当前请求登记的引用，并补上模型遗漏的可渲染结果。
     * 历史轮次的 ref 即使仍在内存池，也不会被本轮答案解析或追加。
     */
    public String resolveAndAppendCurrentResults(String text, List<String> currentRefs) {
        String resolved = resolveCurrentMarkers(text, currentRefs);
        if (currentRefs == null || currentRefs.isEmpty()) return resolved;

        StringBuilder answer = new StringBuilder(resolved);
        // 保持工具调用顺序；重复 ref 只展示一次，避免模型引用后又被重复追加。
        for (String ref : new LinkedHashSet<>(currentRefs)) {
            String content = store.get(ref);
            if (content == null || content.isBlank() || answer.toString().contains(content)) continue;
            if (!answer.isEmpty()) answer.append("\n\n");
            answer.append(content);
        }
        return answer.toString();
    }

    /** 只允许当前请求的 marker 从内存池取值，其他 marker 直接移除。 */
    private String resolveCurrentMarkers(String text, List<String> currentRefs) {
        if (text == null || text.isEmpty()) return text;
        if (currentRefs == null || currentRefs.isEmpty()) return MARKER_PATTERN.matcher(text).replaceAll("");
        Set<String> allowed = new LinkedHashSet<>(currentRefs);
        Matcher m = MARKER_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String ref = m.group(1);
            String replacement = allowed.contains(ref) ? store.get(ref) : "";
            if (replacement == null) replacement = "";
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 懒清理：条目超上限时移除超 TTL 的旧条目；仍超限则整体清空兜底（DB 兜着）。 */
    private void evictIfNeeded() {
        if (store.size() < MAX_ENTRIES) return;
        long deadline = System.currentTimeMillis() - TTL_MILLIS;
        Iterator<Map.Entry<String, Long>> it = createdAt.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> e = it.next();
            if (e.getValue() < deadline) {
                store.remove(e.getKey());
                it.remove();
            }
        }
        if (store.size() >= MAX_ENTRIES) {
            log.warn("ToolResultRegistry memory store still over limit after TTL eviction ({}), clearing all", store.size());
            store.clear();
            createdAt.clear();
        }
    }
}
