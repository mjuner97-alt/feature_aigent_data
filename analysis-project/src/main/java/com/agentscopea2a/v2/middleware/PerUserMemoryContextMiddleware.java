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
package com.agentscopea2a.v2.middleware;

import com.agentscopea2a.v2.memory.MysqlMemoryStore;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import reactor.core.publisher.Mono;

/**
 * Injects the per-user MEMORY.md body into the system prompt as a
 * {@code ## 用户记忆 (Per-User Memory)} section.
 *
 * <p><b>Why this exists:</b> the harness's built-in {@code WorkspaceContextMiddleware}
 * calls {@code workspaceManager.readMemoryMd(rc)} which falls back to the SHARED root
 * {@code <workspace>/MEMORY.md} when the per-session filesystem layer is empty. That
 * root file aggregates content from all users (merged by SkillSynthesis / memory
 * consolidation), so without this middleware every user sees every other user's
 * curated memory injected into their system prompt as {@code <memory_context>}.
 *
 * <p><b>Strategy:</b> delete the root {@code MEMORY.md} (so the framework's
 * {@code <memory_context>} block is empty) and have this middleware append the
 * per-user body from {@link MysqlMemoryStore} before the framework middleware runs.
 * Per-user content lives in the {@code agent_memory} table keyed by
 * {@code (user_id, kind='memory_md', key_name='MEMORY.md')}, written by
 * {@code MemoryHydrator} / {@code MemoryLedgerMirrorMiddleware}.
 *
 * <p><b>Tenant isolation:</b> when {@code ctx.getUserId()} is null/blank (anonymous
 * caller, legacy path), the middleware injects nothing. Better no recall than
 * cross-tenant leak - same posture as {@link EpisodicRetrievalMiddleware}.
 */
public class PerUserMemoryContextMiddleware implements MiddlewareBase {

    private static final String SECTION_HEADER = "## 用户记忆 (Per-User Memory)";

    private final MysqlMemoryStore mysqlMemoryStore;

    public PerUserMemoryContextMiddleware(MysqlMemoryStore mysqlMemoryStore) {
        this.mysqlMemoryStore = mysqlMemoryStore;
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String systemPrompt) {
        return Mono.fromCallable(() -> {
            String userId = ctx != null ? ctx.getUserId() : null;
            if (userId == null || userId.isBlank()) {
                return systemPrompt;
            }

            String body;
            try {
                body = mysqlMemoryStore
                        .read(userId, MysqlMemoryStore.KIND_MEMORY_MD, "MEMORY.md")
                        .orElse("");
            } catch (Exception e) {
                // DB read failures shouldn't break the chat - degrade to no per-user
                // memory injection. The framework's <memory_context> block (now empty
                // because root MEMORY.md is deleted) will still be present.
                return systemPrompt;
            }
            if (body == null || body.isBlank()) {
                return systemPrompt;
            }

            String base = systemPrompt != null ? systemPrompt : "";
            String separator = base.isEmpty() || base.endsWith("\n") ? "" : "\n";
            return base + separator + SECTION_HEADER + "\n" + body.strip();
        });
    }
}
