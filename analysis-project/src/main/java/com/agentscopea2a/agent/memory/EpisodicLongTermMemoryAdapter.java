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
package com.agentscopea2a.agent.memory;

import io.agentscope.core.memory.EpisodicMemory;
import io.agentscope.core.memory.EpisodicResult;
import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.message.Msg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Adapter that exposes an {@link EpisodicMemory} (e.g. {@code MySqlEpisodicMemory}) through the
 * {@link LongTermMemory} interface so it can be wired into {@code HarnessAgent.Builder.longTermMemory()}.
 *
 * <p><b>Why this exists.</b> In v1.1.0-RC1 the agent framework calls
 * {@link LongTermMemory#record(List)} after each reply and {@link LongTermMemory#retrieve(Msg)}
 * before each reasoning step. {@code MySqlEpisodicMemory} implements {@code EpisodicMemory}
 * (which extends {@code MemoryProvider}, not {@code LongTermMemory}) — so it can't be plugged in
 * directly. This adapter bridges the two:
 *
 * <ul>
 *   <li>{@code record(msgs)} → {@link EpisodicMemory#recordSession(String, List)} keyed by the
 *       supervisor's session identifier (one logical session per supervisor instance — A2A
 *       multiplexing should pass a per-request {@code sessionId} via the constructor).</li>
 *   <li>{@code retrieve(msg)} → {@link EpisodicMemory#search(String, int)} using the message
 *       text as the FTS query, formatted as a short markdown block for the system prompt.</li>
 * </ul>
 *
 * <p><b>Limitations.</b>
 *
 * <ul>
 *   <li>Session ID is fixed at construction time; if you need per-A2A-task isolation, build one
 *       adapter per {@code SupervisorAgent} (which already happens — the factory builds one per
 *       request).</li>
 *   <li>{@code search} is rule-based FTS (no semantic embeddings). If the underlying
 *       {@code MySqlEpisodicMemory} backend is upgraded to vector search later, this adapter
 *       requires no changes.</li>
 * </ul>
 */
public class EpisodicLongTermMemoryAdapter implements LongTermMemory {

    private static final Logger log = LoggerFactory.getLogger(EpisodicLongTermMemoryAdapter.class);

    /** Default number of past episodes to include in the retrieve() result. */
    private static final int DEFAULT_SEARCH_LIMIT = 5;

    private final EpisodicMemory episodic;
    private final String sessionId;
    private final int searchLimit;

    public EpisodicLongTermMemoryAdapter(EpisodicMemory episodic, String sessionId) {
        this(episodic, sessionId, DEFAULT_SEARCH_LIMIT);
    }

    public EpisodicLongTermMemoryAdapter(
            EpisodicMemory episodic, String sessionId, int searchLimit) {
        this.episodic = episodic;
        this.sessionId = sessionId;
        this.searchLimit = searchLimit;
    }

    @Override
    public Mono<Void> record(List<Msg> msgs) {
        if (msgs == null || msgs.isEmpty()) {
            return Mono.empty();
        }
        return episodic.recordSession(sessionId, msgs)
                .doOnError(
                        e ->
                                log.warn(
                                        "Episodic recordSession failed for sessionId={}: {}",
                                        sessionId,
                                        e.getMessage()));
    }

    @Override
    public Mono<String> retrieve(Msg msg) {
        if (msg == null) {
            return Mono.just("");
        }
        String query = msg.getTextContent();
        if (query == null || query.isBlank()) {
            return Mono.just("");
        }
        return episodic.search(query, searchLimit)
                .map(EpisodicLongTermMemoryAdapter::formatResults)
                .onErrorResume(
                        e -> {
                            log.warn(
                                    "Episodic search failed for query='{}': {}",
                                    query,
                                    e.getMessage());
                            return Mono.just("");
                        });
    }

    /**
     * Formats search results as a short markdown block suitable for system-prompt injection.
     * Returns an empty string when there are no results so the framework can skip prompt
     * augmentation cleanly.
     */
    private static String formatResults(List<EpisodicResult> results) {
        if (results == null || results.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("## 历史相关对话\n");
        for (EpisodicResult r : results) {
            sb.append("- [").append(r.sessionId()).append("] ").append(r.snippet()).append("\n");
        }
        return sb.toString();
    }
}
