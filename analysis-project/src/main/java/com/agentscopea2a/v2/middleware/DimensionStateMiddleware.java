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

import com.agentscopea2a.v2.dimension.DimensionState;
import com.agentscopea2a.v2.dimension.DimensionStateManager;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import reactor.core.publisher.Mono;

/**
 * v2 middleware：在 system prompt 中注入维度状态上下文。
 *
 * <p>替代 v1 的 {@code ResponseCacheHook} 维度注入逻辑（缓存逻辑推迟到阶段 5）。
 *
 * <p>工作流程：
 * <ol>
 *   <li>从 {@link RuntimeContext} 加载上一轮的维度状态</li>
 *   <li>使用规则分析处理用户问题（零 LLM 开销）</li>
 *   <li>将维度上下文作为自然语言前缀追加到 system prompt</li>
 * </ol>
 *
 * <p>v2 版本使用 {@link RuntimeContext#put}/{@link RuntimeContext#get} 进行状态持久化，
 * 替代 v1 的 {@code Session}/{@code SessionKey}。
 */
public class DimensionStateMiddleware implements MiddlewareBase {

    private static final String STATE_KEY = "dimensionState";

    private final DimensionStateManager dimensionStateManager;

    public DimensionStateMiddleware(DimensionStateManager dimensionStateManager) {
        this.dimensionStateManager = dimensionStateManager;
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String systemPrompt) {
        return Mono.fromCallable(() -> {
            // 提取用户问题：从 RuntimeContext 获取最后一条用户消息
            String userQuestion = extractUserQuestion(ctx);

            if (userQuestion == null || userQuestion.isBlank()) {
                return systemPrompt;
            }

            // 规则分析 + 继承 + 指代消解 + 组装（纯本地，零 LLM）
            // 用 processQuestionInContext 而非 loadFrom + processQuestion + getCurrentState + saveTo
            // 序列，避免单例 DimensionStateManager 的 currentState 实例字段被并发请求覆盖（P1-2）
            DimensionStateManager.ProcessResult result =
                    dimensionStateManager.processQuestionInContext(ctx, userQuestion);
            DimensionState state = result.newState();

            if (state != null && state.hasDimensions()) {
                String dimensionPrefix = formatDimensionContext(state);
                return systemPrompt + "\n\n" + dimensionPrefix;
            }
            return systemPrompt;
        });
    }

    /**
     * 从 RuntimeContext 提取用户问题文本。
     *
     * <p>v2 RuntimeContext 不提供 getLastUserMessage()，通过 {@code ctx.get("lastQuestion", String.class)}
     * 读取（由 {@code V2ChatStreamServiceImpl.buildRuntimeContext} 在请求开始时写入）。fallback 返回空
     * 字符串，使调用方跳过维度状态处理。
     */
    private String extractUserQuestion(RuntimeContext ctx) {
        // 方式1: 从 RuntimeContext extra map 获取（V2ChatStreamServiceImpl put "lastQuestion"）
        String input = ctx.get("lastQuestion", String.class);
        if (input != null && !input.isBlank()) {
            return input;
        }
        // 方式2: 从 conversationId 获取（仅作 fallback）
        String conversationId = ctx.getSessionId();
        return conversationId != null ? "" : null;
    }

    private String formatDimensionContext(DimensionState state) {
        StringBuilder sb = new StringBuilder("## 当前对话维度上下文\n");

        if (state.getTimeDimension() != null && !state.getTimeDimension().isEmpty()) {
            String label = state.getTimeDimension().getType()
                    == DimensionState.TimeDimensionType.QUARTER ? "季度" : "版本计划";
            sb.append(label).append("：")
                    .append(String.join("、", state.getTimeDimension().getValues()))
                    .append("\n");
        }
        if (state.getDepartments() != null && !state.getDepartments().isEmpty()) {
            sb.append("部门：").append(String.join("、", state.getDepartments())).append("\n");
        }
        if (state.getPeerDimension() != null && !state.getPeerDimension().isEmpty()) {
            String label = switch (state.getPeerDimension().getType()) {
                case TEAM -> "组";
                case APPLICATION -> "应用";
                case PRODUCT_LINE -> "产品线";
                case REQUIREMENT -> "需求项";
            };
            sb.append(label).append("：")
                    .append(String.join("、", state.getPeerDimension().getValues()))
                    .append("\n");
        }
        if (state.getPersons() != null && !state.getPersons().isEmpty()) {
            sb.append("人：").append(String.join("、", state.getPersons())).append("\n");
        }

        return sb.toString().trim();
    }
}