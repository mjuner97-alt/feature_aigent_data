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

import com.agentscopea2a.v2.memory.EpisodicMemory;
import com.agentscopea2a.v2.memory.EpisodicResult;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * v2 middleware：在 system prompt 中注入情景记忆检索上下文。
 *
 * <p>替代 v1 的 {@code EpisodicLongTermMemoryAdapter} 挂载逻辑。
 * 每轮对话前从 {@link EpisodicMemory} 检索与用户问题相关的历史对话片段，
 * 作为自然语言前缀追加到 system prompt。
 *
 * <p>工作流程：
 * <ol>
 *   <li>从 RuntimeContext 提取当前用户问题</li>
 *   <li>调用 {@link EpisodicMemory#search(String, int)} 检索相关片段</li>
 *   <li>将检索结果格式化为自然语言前缀追加到 system prompt</li>
 * </ol>
 *
 * <p>检索失败时静默降级：返回原始 system prompt，不中断对话。
 */
public class EpisodicRetrievalMiddleware implements MiddlewareBase {

    private static final String INPUT_KEY = "lastQuestion";
    private static final int DEFAULT_SEARCH_LIMIT = 5;

    private final EpisodicMemory episodicMemory;
    private final int searchLimit;

    public EpisodicRetrievalMiddleware(EpisodicMemory episodicMemory) {
        this(episodicMemory, DEFAULT_SEARCH_LIMIT);
    }

    public EpisodicRetrievalMiddleware(EpisodicMemory episodicMemory, int searchLimit) {
        this.episodicMemory = episodicMemory;
        this.searchLimit = searchLimit;
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String systemPrompt) {
        return Mono.fromCallable(() -> {
            String userQuestion = extractUserQuestion(ctx);
            if (userQuestion == null || userQuestion.isBlank()) {
                return systemPrompt;
            }

            try {
                // Scope search to the current user — without this filter, the FTS/vector query
                // returns matches from ALL users' past conversations, leaking other users'
                // dialogue into this user's "## 历史参考案例" prefix. V2ChatStreamServiceImpl
                // builds RuntimeContext with userId set (buildRuntimeContext line 535-539),
                // so ctx.getUserId() is the per-request tenant identity. When userId is
                // null/blank (anonymous / legacy caller), we skip retrieval entirely rather
                // than fall back to global search — better no recall than cross-tenant leak.
                String userId = ctx != null ? ctx.getUserId() : null;
                if (userId == null || userId.isBlank()) {
                    return systemPrompt;
                }
                List<EpisodicResult> results =
                        episodicMemory.search(userId, userQuestion, searchLimit).block();

                if (results == null || results.isEmpty()) {
                    return systemPrompt;
                }

                String episodicPrefix = formatEpisodicContext(results);
                return systemPrompt + "\n\n" + episodicPrefix;
            } catch (Exception e) {
                // 检索失败时静默降级，不中断对话
                return systemPrompt;
            }
        });
    }

    /**
     * 从 RuntimeContext 提取用户问题文本。
     *
     * <p>v2 RuntimeContext 不提供 getLastUserMessage()，通过 {@code ctx.get("lastQuestion", String.class)}
     * 读取（由 {@code V2ChatStreamServiceImpl.buildRuntimeContext} 在请求开始时写入）。fallback 返回空
     * 字符串，使调用方跳过检索。
     */
    private String extractUserQuestion(RuntimeContext ctx) {
        String input = ctx.get(INPUT_KEY, String.class);
        if (input != null && !input.isBlank()) {
            return input;
        }
        // Fallback: 从 conversationId 获取（仅作占位）
        String conversationId = ctx.getSessionId();
        return conversationId != null ? "" : null;
    }

    private String formatEpisodicContext(List<EpisodicResult> results) {
        StringBuilder sb = new StringBuilder("## 历史参考案例\n");
        for (EpisodicResult result : results) {
            sb.append("- ").append(result.snippet());
            if (result.relevance() > 0) {
                sb.append(" (相关度: ").append(String.format("%.2f", result.relevance())).append(")");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }
}