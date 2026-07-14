/**
 * 通用模型降级配置 — 当用户自定义 Token 失效时回退使用的默认模型。绑定 harness.a2a.fallback.* 配置项。
 */
package com.agentscopea2a.agent.model;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "harness.a2a.fallback")
public class FallbackModelProperties {

    /** 模型提供商 (glm/openai/anthropic) */
    private String provider = "glm";

    /** API Key */
    private String apiKey;

    /** Base URL（可选，留空使用 provider 默认地址） */
    private String baseUrl;

    /** 模型名 */
    private String modelName;

    /** 可重试错误的最大重试次数（5xx / 超时） */
    private int maxRetries = 3;

    /** 单次重试间隔（毫秒） */
    private long retryIntervalMs = 2000L;

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public long getRetryIntervalMs() {
        return retryIntervalMs;
    }

    public void setRetryIntervalMs(long retryIntervalMs) {
        this.retryIntervalMs = retryIntervalMs;
    }
}
