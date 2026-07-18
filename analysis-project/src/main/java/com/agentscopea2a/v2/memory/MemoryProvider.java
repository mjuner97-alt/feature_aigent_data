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
package com.agentscopea2a.v2.memory;

import io.agentscope.core.message.Msg;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Pluggable memory provider interface for multi-strategy memory management.
 *
 * <p>v2 relocation: moved from {@code io.agentscope.core.memory} (shadow override)
 * to {@code com.agentscopea2a.v2.memory} (business interface). The v2 jar does not
 * ship {@code MemoryProvider}, so this is now a first-class business interface.
 *
 * <p>Each MemoryProvider encapsulates a specific memory implementation:
 * <ul>
 *   <li>{@code BuiltInMemoryProvider} — File-based memory (MEMORY.md, USER.md)</li>
 *   <li>{@code SemanticMemoryProvider} — Wraps existing {@link io.agentscope.core.memory.LongTermMemory}</li>
 *   <li>Custom providers — User-defined implementations</li>
 * </ul>
 *
 * <p><b>Lifecycle:</b>
 * <ol>
 *   <li>{@link #systemPromptBlock()} — Called once at agent initialization</li>
 *   <li>{@link #prefetch(String)} — Called before each agent turn</li>
 *   <li>{@link #syncTurn(Msg, Msg)} — Called after each agent turn</li>
 *   <li>{@link #onPreCompress(List)} — Called before context compression</li>
 *   <li>{@link #onSessionEnd(List)} — Called when session terminates</li>
 * </ol>
 */
public interface MemoryProvider {

    /**
     * Gets the unique name of this provider.
     *
     * <p>Used for tool routing and logging. Must be unique across all providers registered
     * with a {@link MemoryManager}.
     *
     * @return Provider name (e.g., "builtin", "semantic")
     */
    String getName();

    /**
     * Gets the system prompt block for this provider.
     *
     * <p>Called once during agent initialization to inject provider-specific instructions
     * into the system prompt.
     *
     * @return System prompt content, or empty string if none
     */
    default String systemPromptBlock() {
        return "";
    }

    /**
     * Prefetches memory context before agent reasoning.
     *
     * <p>Called at the start of each agent turn with the user's query. Implementations
     * should search their memory stores and return relevant context as formatted text.
     *
     * @param query The user's current query
     * @return Mono emitting relevant memory context (may be empty)
     */
    default Mono<String> prefetch(String query) {
        return Mono.just("");
    }

    /**
     * Synchronizes the current turn to memory.
     *
     * <p>Called after each agent turn completes. Implementations should extract and persist
     * meaningful information from the conversation pair.
     *
     * @param userMsg The user's message
     * @param assistantMsg The agent's response message
     * @return Mono that completes when sync is finished
     */
    default Mono<Void> syncTurn(Msg userMsg, Msg assistantMsg) {
        return Mono.empty();
    }

    /**
     * Callback before context compression.
     *
     * <p>Called when the agent's context window is approaching capacity and messages
     * will be compressed. Implementations should extract key information from the
     * messages that will be compressed.
     *
     * @param messages The messages about to be compressed
     * @return Mono emitting extracted summary (may be empty)
     */
    default Mono<String> onPreCompress(List<Msg> messages) {
        return Mono.just("");
    }

    /**
     * Callback when session ends.
     *
     * <p>Called when the agent session terminates. Implementations should perform
     * final cleanup and extraction.
     *
     * @param messages All messages from the session
     * @return Mono that completes when cleanup is finished
     */
    default Mono<Void> onSessionEnd(List<Msg> messages) {
        return Mono.empty();
    }

    /**
     * Gets tool objects provided by this MemoryProvider.
     *
     * <p>Providers can expose agent-callable tools (e.g., memory search, skill list).
     * Tools are automatically registered when the provider is added to {@link MemoryManager}.
     *
     * <p>Tool objects must contain methods annotated with {@code @Tool}.
     *
     * @return List of tool objects (may be empty)
     */
    default List<Object> getToolObjects() {
        return List.of();
    }
}
