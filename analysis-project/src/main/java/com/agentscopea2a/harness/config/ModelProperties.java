/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.agentscopea2a.harness.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LLM model configuration bound from {@code harness.a2a.model.*} in application.properties.
 *
 * <p>Supports three providers — anthropic / openai / glm — each with its own api-key, base-url
 * and model name. Switch active provider via {@code harness.a2a.model.provider=<name>}. All
 * fields have hardcoded defaults so the application can boot with zero configuration.
 *
 * <pre>{@code
 * # Use Anthropic-compatible endpoint (default)
 * harness.a2a.model.provider=anthropic
 * harness.a2a.model.anthropic.api-key=sk-...
 * harness.a2a.model.anthropic.base-url=https://api.anthropic.com
 * harness.a2a.model.anthropic.name=claude-sonnet-4-5-20250929
 *
 * # Switch to GLM
 * harness.a2a.model.provider=glm
 * harness.a2a.model.glm.api-key=...
 * }</pre>
 */
@Component
@ConfigurationProperties(prefix = "harness.a2a.model")
public class ModelProperties {

    /** Active provider: {@code anthropic} / {@code openai} / {@code glm}. */
    private String provider = "anthropic";

    private Anthropic anthropic = new Anthropic();
    private OpenAI openai = new OpenAI();
    private Glm glm = new Glm();

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public Anthropic getAnthropic() {
        return anthropic;
    }

    public void setAnthropic(Anthropic anthropic) {
        this.anthropic = anthropic;
    }

    public OpenAI getOpenai() {
        return openai;
    }

    public void setOpenai(OpenAI openai) {
        this.openai = openai;
    }

    public Glm getGlm() {
        return glm;
    }

    public void setGlm(Glm glm) {
        this.glm = glm;
    }

    /** Anthropic / Anthropic-compatible (Volcano Ark, etc.). */
    public static class Anthropic {
        private String apiKey = "ark-ad3231aa-d7f1-46fa-8bb5-226790780821-c68e3";
        private String baseUrl = "https://ark.cn-beijing.volces.com/api/coding";
        private String name = "glm-5.1";

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

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    /** OpenAI. */
    public static class OpenAI {
        private String apiKey = "";
        private String baseUrl = "";
        private String name = "gpt-4o";

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

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    /** GLM (Zhipu, OpenAI-compatible). */
    public static class Glm {
        private String apiKey = "";
        private String baseUrl = "https://open.bigmodel.cn/api/paas/v4/";
        private String name = "glm-4.6";

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

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }
}
