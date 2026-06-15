/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.agentscopea2a.agent.model;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 多模型配置 — 全部来源于 {@code harness.a2a.model.*} 配置项,无任何环境变量回退。
 *
 * <p>本配置支持 <b>同时定义多个模型实例,并被不同 agent / subagent 同时使用</b>。每个实例都
 * 是一个 {@link Instance}(独立的 provider / api-key / base-url / 模型名)。{@link #defaultModel}
 * 给出当未显式指定时使用的默认模型名;{@link #subagents} 允许给特定 subagent 单独绑定一个不同
 * 的模型名。
 *
 * <pre>{@code
 * # 默认模型 (字段名是 defaultModel — "default" 是 Java 关键字,所以属性名写 default-model)
 * harness.a2a.model.default-model=glm-main
 *
 * # 实例 #1 - 主对话用 GLM
 * harness.a2a.model.instances.glm-main.provider=glm
 * harness.a2a.model.instances.glm-main.api-key=xxx
 * harness.a2a.model.instances.glm-main.base-url=https://...
 * harness.a2a.model.instances.glm-main.name=glm-5.1
 *
 * # 实例 #2 - 代码 subagent 用 Anthropic
 * harness.a2a.model.instances.claude-coder.provider=anthropic
 * harness.a2a.model.instances.claude-coder.api-key=ark-xxx
 * harness.a2a.model.instances.claude-coder.base-url=https://ark.cn-beijing.volces.com/api/coding
 * harness.a2a.model.instances.claude-coder.name=claude-sonnet-4-5
 *
 * # 把某个 subagent 绑定到指定模型(未列出的子代理使用 default)
 * harness.a2a.model.subagents.code_interpreter=claude-coder
 * harness.a2a.model.subagents.analyze_data=glm-main
 * }</pre>
 *
 * <p><b>Supervisor 主模型</b>: 通过 {@link #supervisor}(默认 = {@link #defaultModel})指定。
 *
 * <p>provider 取值: {@code anthropic} / {@code openai} / {@code glm} — 与 {@code Models} 工厂一致。
 */
@Component
@ConfigurationProperties(prefix = "harness.a2a.model")
public class ModelProperties {

    /** 当未显式指定时使用的默认实例名。必须存在于 {@link #instances} 中。 */
    private String defaultModel = "default";

    /** Supervisor(主对话)使用的实例名。null/空时回退到 {@link #defaultModel}。 */
    private String supervisor;

    /** 命名模型实例集合,key = 实例名,value = 实例配置。 */
    private Map<String, Instance> instances = new LinkedHashMap<>();

    /**
     * subagent 名 → 实例名 的覆盖映射。
     * 例: {@code subagents.code_interpreter=claude-coder}
     */
    private Map<String, String> subagents = new LinkedHashMap<>();

    public String getDefaultModel() {
        return defaultModel;
    }

    public void setDefaultModel(String defaultModel) {
        this.defaultModel = defaultModel;
    }

    public String getSupervisor() {
        return supervisor;
    }

    public void setSupervisor(String supervisor) {
        this.supervisor = supervisor;
    }

    public Map<String, Instance> getInstances() {
        return instances;
    }

    public void setInstances(Map<String, Instance> instances) {
        this.instances = instances;
    }

    public Map<String, String> getSubagents() {
        return subagents;
    }

    public void setSubagents(Map<String, String> subagents) {
        this.subagents = subagents;
    }

    /** 单个模型实例配置 — 一个 instance = 一个独立的 LLM 连接。 */
    public static class Instance {
        /** 必填: anthropic / openai / glm。 */
        private String provider;

        /** 必填: API Key。 */
        private String apiKey;

        /** 可选: base URL,留空使用 provider 默认地址。 */
        private String baseUrl;

        /** 必填: 模型名,如 glm-5.1 / claude-sonnet-4-5 / gpt-4o。 */
        private String name;

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

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }
}
