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
package com.agentscopea2a.v2.config;

import com.agentscopea2a.v2.dimension.DimensionStateManager;
import com.agentscopea2a.v2.dimension.LlmDimensionService;
import com.agentscopea2a.v2.dimension.OpenAILlmDimensionService;
import com.agentscopea2a.v2.middleware.DimensionStateMiddleware;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * v2 维度状态配置。
 *
 * <p>将维度分析服务从 v1 的 {@code com.agentscopea2a.agent.dimension} 包
 * 迁移到 v2 的 {@code com.agentscopea2a.v2.dimension} 包，
 * 并使用 v2 的 {@link Model} 接口替代 v1 的 session 持久化。
 */
@Configuration
public class V2DimensionConfig {

    @Bean
    public LlmDimensionService llmDimensionService(
            @Value("${harness.a2a.model.instances.light-classifier.api-key}") String apiKey,
            @Value("${harness.a2a.model.instances.light-classifier.base-url}") String baseUrl,
            @Value("${harness.a2a.model.instances.light-classifier.name}") String modelName) {
        // 复用 light-classifier 实例（qwen3:8b）做维度分析，降低 token 成本
        Model dimensionModel = OpenAIChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .stream(true)
                .build();
        return new OpenAILlmDimensionService(dimensionModel);
    }

    @Bean
    public DimensionStateManager dimensionStateManager(LlmDimensionService llmService) {
        return new DimensionStateManager(llmService);
    }

    @Bean
    public DimensionStateMiddleware dimensionStateMiddleware(DimensionStateManager dimensionStateManager) {
        return new DimensionStateMiddleware(dimensionStateManager);
    }
}