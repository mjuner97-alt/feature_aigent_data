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
package io.agentscope.core.memory;

import io.agentscope.core.message.Msg;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Episodic memory interface for cross-session conversation search.
 *
 * <p>Episodic memory enables agents to recall and search past conversation sessions,
 * providing "what happened before" context. This complements semantic memory
 * (which stores extracted facts) by preserving the original conversation flow.
 *
 * <p><b>Memory Types:</b>
 * <ul>
 *   <li>{@code Semantic Memory} — Facts and preferences (via {@link LongTermMemory})</li>
 *   <li>{@code Episodic Memory} — Conversation history (via this interface)</li>
 *   <li>{@code Procedural Memory} — Skills and how-to (future)</li>
 * </ul>
 *
 * <p><b>Design Reference:</b> Hermes Agent SessionDB with FTS5 full-text search.
 *
 * <p><b>Usage Example:</b>
 * <pre>{@code
 * EpisodicMemory episodic = new MySqlEpisodicMemory(dataSource);
 *
 * MemoryManager manager = new MemoryManager();
 * manager.addProvider(new BuiltInMemoryProvider("Assistant"));
 * manager.addProvider(episodic);
 *
 * ReActAgent agent = ReActAgent.builder()
 *     .name("Assistant")
 *     .model(model)
 *     .memoryManager(manager)
 *     .build();
 * }</pre>
 *
 * @see MemoryProvider
 * @see EpisodicResult
 */
public interface EpisodicMemory extends MemoryProvider {

    /**
     * Records a complete session's messages to episodic memory.
     *
     * <p>Typically called at session end to persist the full conversation for
     * future retrieval. Implementations should filter to USER and ASSISTANT
     * messages only.
     *
     * @param sessionId The unique session identifier
     * @param messages The conversation messages from this session
     * @return Mono that completes when recording is finished
     */
    Mono<Void> recordSession(String sessionId, List<Msg> messages);

    /**
     * Searches episodic memory for relevant past conversations.
     *
     * <p>Uses full-text search to find conversations matching the query,
     * returning the most relevant results across all sessions.
     *
     * @param query The search query
     * @param limit Maximum number of results to return
     * @return Mono emitting a list of search results, ordered by relevance
     */
    Mono<List<EpisodicResult>> search(String query, int limit);

    /**
     * Gets all messages from a specific session.
     *
     * @param sessionId The session identifier
     * @return Mono emitting the list of messages from that session
     */
    Mono<List<Msg>> getSession(String sessionId);
}
