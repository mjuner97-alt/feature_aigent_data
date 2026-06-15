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
package io.agentscope.core.model;

/**
 * Static factory for creating {@link Model} instances from environment variables.
 *
 * <p>Each provider reads credentials from a triple of environment variables:
 * <table>
 *   <tr><th>Provider</th><th>API Key</th><th>Base URL</th><th>Model</th></tr>
 *   <tr><td>anthropic</td><td>{@code ANTHROPIC_API_KEY}</td><td>{@code ANTHROPIC_BASE_URL}</td><td>{@code ANTHROPIC_MODEL}</td></tr>
 *   <tr><td>openai</td><td>{@code OPENAI_API_KEY}</td><td>{@code OPENAI_BASE_URL}</td><td>{@code OPENAI_MODEL}</td></tr>
 *   <tr><td>glm</td><td>{@code GLM_API_KEY}</td><td>{@code GLM_BASE_URL}</td><td>{@code GLM_MODEL}</td></tr>
 * </table>
 *
 * <p>Usage:
 * <pre>{@code
 * Model model = Models.anthropic();              // auto from env vars
 * Model model = Models.openAI("sk-xx", null, "gpt-4");  // explicit params
 * Model model = Models.fromEnv("glm");           // dispatch by provider name
 * }</pre>
 */
public final class Models {

    // ---------- Anthropic ----------

    private static final String ANTHROPIC_KEY_ENV = "ANTHROPIC_API_KEY";
    private static final String ANTHROPIC_URL_ENV = "ANTHROPIC_BASE_URL";
    private static final String ANTHROPIC_MODEL_ENV = "ANTHROPIC_MODEL";
    private static final String ANTHROPIC_DEFAULT_MODEL = "claude-sonnet-4-5-20250929";

    // ---------- OpenAI ----------

    private static final String OPENAI_KEY_ENV = "OPENAI_API_KEY";
    private static final String OPENAI_URL_ENV = "OPENAI_BASE_URL";
    private static final String OPENAI_MODEL_ENV = "OPENAI_MODEL";
    private static final String OPENAI_DEFAULT_MODEL = "gpt-4o";

    // ---------- GLM (Zhipu, OpenAI-compatible) ----------

    private static final String GLM_KEY_ENV = "GLM_API_KEY";
    private static final String GLM_URL_ENV = "GLM_BASE_URL";
    private static final String GLM_MODEL_ENV = "GLM_MODEL";
    private static final String GLM_DEFAULT_URL = "https://open.bigmodel.cn/api/paas/v4/";
    private static final String GLM_DEFAULT_MODEL = "glm-4.6";

    private Models() {}

    // ==================== Anthropic ====================

    /**
     * Creates an {@code AnthropicChatModel} from environment variables.
     *
     * <p>Required: {@code ANTHROPIC_API_KEY}.
     * Optional: {@code ANTHROPIC_BASE_URL}, {@code ANTHROPIC_MODEL}
     * (defaults to {@code claude-sonnet-4-5-20250929}).
     *
     * @return configured model
     * @throws IllegalStateException if API key is missing
     */
    public static Model anthropic() {
        return anthropic(
                requireEnv(ANTHROPIC_KEY_ENV),
                envOrNull(ANTHROPIC_URL_ENV),
                envOrDefault(ANTHROPIC_MODEL_ENV, ANTHROPIC_DEFAULT_MODEL));
    }

    /**
     * Creates an {@code AnthropicChatModel} with explicit parameters.
     *
     * @param apiKey    API key (required)
     * @param baseUrl   base URL, null for Anthropic default
     * @param modelName model name (required)
     * @return configured model
     */
    public static Model anthropic(String apiKey, String baseUrl, String modelName) {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalArgumentException("apiKey must not be null or empty");
        }
        if (modelName == null || modelName.isEmpty()) {
            throw new IllegalArgumentException("modelName must not be null or empty");
        }
        return AnthropicChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .stream(true)
                .build();
    }

    // ==================== OpenAI ====================

    /**
     * Creates an {@code OpenAIChatModel} from environment variables.
     *
     * <p>Required: {@code OPENAI_API_KEY}.
     * Optional: {@code OPENAI_BASE_URL}, {@code OPENAI_MODEL}
     * (defaults to {@code gpt-4o}).
     *
     * @return configured model
     * @throws IllegalStateException if API key is missing
     */
    public static Model openAI() {
        return openAI(
                requireEnv(OPENAI_KEY_ENV),
                envOrNull(OPENAI_URL_ENV),
                envOrDefault(OPENAI_MODEL_ENV, OPENAI_DEFAULT_MODEL));
    }

    /**
     * Creates an {@code OpenAIChatModel} with explicit parameters.
     *
     * @param apiKey    API key (required)
     * @param baseUrl   base URL, null for OpenAI default
     * @param modelName model name (required)
     * @return configured model
     */
    public static Model openAI(String apiKey, String baseUrl, String modelName) {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalArgumentException("apiKey must not be null or empty");
        }
        if (modelName == null || modelName.isEmpty()) {
            throw new IllegalArgumentException("modelName must not be null or empty");
        }
        return OpenAIChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .stream(true)
                .build();
    }

    // ==================== GLM ====================

    /**
     * Creates a GLM model (OpenAI-compatible) from environment variables.
     *
     * <p>Required: {@code GLM_API_KEY}.
     * Optional: {@code GLM_BASE_URL} (defaults to Zhipu API),
     * {@code GLM_MODEL} (defaults to {@code glm-4.6}).
     *
     * @return configured model
     * @throws IllegalStateException if API key is missing
     */
    public static Model glm() {
        return openAI(
                requireEnv(GLM_KEY_ENV),
                envOrDefault(GLM_URL_ENV, GLM_DEFAULT_URL),
                envOrDefault(GLM_MODEL_ENV, GLM_DEFAULT_MODEL));
    }

    /**
     * Creates a GLM model with explicit parameters.
     *
     * @param apiKey    API key (required)
     * @param baseUrl   base URL, null for Zhipu default
     * @param modelName model name (required)
     * @return configured model
     */
    public static Model glm(String apiKey, String baseUrl, String modelName) {
        return openAI(apiKey, baseUrl != null ? baseUrl : GLM_DEFAULT_URL, modelName);
    }

    // ==================== Generic dispatch ====================

    /**
     * Creates a model by provider name, reading credentials from environment variables.
     *
     * @param provider one of {@code "anthropic"}, {@code "openai"}, {@code "glm"}
     * @return configured model
     * @throws IllegalArgumentException for unknown provider
     * @throws IllegalStateException if required env vars are missing
     */
    public static Model fromEnv(String provider) {
        return switch (provider.toLowerCase()) {
            case "anthropic" -> anthropic();
            case "openai" -> openAI();
            case "glm" -> glm();
            default ->
                    throw new IllegalArgumentException(
                            "Unknown provider: '"
                                    + provider
                                    + "'. Supported: anthropic, openai, glm");
        };
    }

    // ==================== Helpers ====================

    private static String requireEnv(String key) {
        String value = System.getenv(key);
        if (value != null && !value.isEmpty()) {
            return value;
        }
        // Fallback: try ANTHROPIC_AUTH_TOKEN when ANTHROPIC_API_KEY is not set
        if ("ANTHROPIC_API_KEY".equals(key)) {
            String fallback = System.getenv("ANTHROPIC_AUTH_TOKEN");
            if (fallback != null && !fallback.isEmpty()) {
                return fallback;
            }
        }
        throw new IllegalStateException("Required environment variable '" + key + "' is not set.");
    }

    private static String envOrNull(String key) {
        String value = System.getenv(key);
        return (value != null && !value.isEmpty()) ? value : null;
    }

    private static String envOrDefault(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value != null && !value.isEmpty()) ? value : defaultValue;
    }
}
