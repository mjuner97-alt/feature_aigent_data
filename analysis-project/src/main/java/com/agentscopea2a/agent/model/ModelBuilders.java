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

import io.agentscope.core.model.*;

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
            .maxAttempts(1).timeout(Duration.ofSeconds(20))
            .initialBackoff(Duration.ofSeconds(2))
            .backoffMultiplier(20D)
            .maxBackoff(Duration.ofSeconds(30))
            .build();


        return OpenAIChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .generateOptions(GenerateOptions.builder().executionConfig(executionConfig)
                        .build())
                .stream(true)
                .build();
    }

    /** GLM(智谱),底层走 OpenAI 协议;不传 baseUrl 时使用智谱官方地址。 */
    public static Model glm(String apiKey, String baseUrl, String modelName) {
        return openAI(apiKey, baseUrl != null ? baseUrl : GLM_DEFAULT_URL, modelName);
    }

    /**
     * 根据任意参数构建 Model 实例 — 用于运行时动态构建用户自定义模型。
     *
     * @param provider 提供商: glm / openai / anthropic
     * @param apiKey   API Key
     * @param baseUrl  请求地址（null 或空时部分 provider 有默认值）
     * @param modelName 模型名
     * @return Model 实例
     */
    public static Model buildFromUserConfig(String provider, String apiKey, String baseUrl, String modelName) {
        requireNonBlank(provider, "provider");
        requireNonBlank(apiKey, "apiKey");
        requireNonBlank(modelName, "modelName");
        String url = (baseUrl == null || baseUrl.isBlank()) ? null : baseUrl;
        return switch (provider.toLowerCase()) {
            case "anthropic" -> anthropic(apiKey, url, modelName);
            case "openai" -> openAI(apiKey, url, modelName);
            case "glm" -> glm(apiKey, url, modelName);
            default -> throw new IllegalArgumentException(
                    "未知 provider='" + provider + "'。支持: anthropic / openai / glm");
        };
    }

    private static void requireNonBlank(String v, String field) {
        if (v == null || v.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be null or empty");
        }
    }
}
