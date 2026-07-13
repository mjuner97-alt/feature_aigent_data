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
package com.agentscopea2a.agent.model;

import io.agentscope.core.model.AnthropicChatModel;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;

import java.time.Duration;


public final class ModelBuilders {

    /** GLM(智谱)默认 base URL — 仅在调用方未显式给 baseUrl 时兜底。 */
    private static final String GLM_DEFAULT_URL = "https://open.bigmodel.cn/api/paas/v4/";

    private ModelBuilders() {}

    /** Anthropic / Anthropic-protocol 兼容端点(火山方舟 coding 通道等)。 */
    public static Model anthropic(String apiKey, String baseUrl, String modelName) {
        requireNonBlank(apiKey, "apiKey");
        requireNonBlank(modelName, "modelName");
        return AnthropicChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .stream(true)
                .build();
    }

    /** OpenAI / OpenAI-compatible(包括 DeepSeek、火山方舟 OpenAI 通道等)。 */
    public static Model openAI(String apiKey, String baseUrl, String modelName) {
        requireNonBlank(apiKey, "apiKey");
        requireNonBlank(modelName, "modelName");

        ExecutionConfig executionConfig = ExecutionConfig.builder()
            .maxAttempts(3).timeout(Duration.ofSeconds(20))
            .initialBackoff(Duration.ofSeconds(2))
            .backoffMultiplier(20D)
            .maxBackoff(Duration.ofSeconds(30))
            .build();


        return OpenAIChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .stream(true)
                .build();
    }

    /** GLM(智谱),底层走 OpenAI 协议;不传 baseUrl 时使用智谱官方地址。 */
    public static Model glm(String apiKey, String baseUrl, String modelName) {
        return openAI(apiKey, baseUrl != null ? baseUrl : GLM_DEFAULT_URL, modelName);
    }

    private static void requireNonBlank(String v, String field) {
        if (v == null || v.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be null or empty");
        }
    }
}
