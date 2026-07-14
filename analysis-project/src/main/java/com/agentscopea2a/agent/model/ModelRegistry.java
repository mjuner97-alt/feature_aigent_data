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

import com.agentscopea2a.entity.UserModelConfig;
import io.agentscope.core.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * 模型注册表 — 把 {@link ModelProperties#getInstances()} 中声明的每个实例懒加载为
 * {@link Model} 并缓存,以便 supervisor 与各 subagent 可以同时使用<b>不同的</b>模型。
 *
 * <p>使用方式:
 * <pre>{@code
 *   Model main      = registry.getDefault();              // = harness.a2a.model.default
 *   Model supervisor= registry.getSupervisor();           // = harness.a2a.model.supervisor (回退到 default)
 *   Model coder     = registry.getForSubagent("code_interpreter"); // 走 subagents 映射,缺省回退 default
 *   Model byName    = registry.get("claude-coder");       // 直接按实例名取
 * }</pre>
 *
 * <p>所有 provider / api-key / base-url / model name 均来自配置文件,不再读取环境变量。
 */
public class ModelRegistry {

    private static final Logger log = LoggerFactory.getLogger(ModelRegistry.class);

    private final ModelProperties props;
    private final Map<String, Model> cache = new HashMap<>();

    public ModelRegistry(ModelProperties props) {
        this.props = props;
        validate();
    }

    private void validate() {
        if (props.getInstances() == null || props.getInstances().isEmpty()) {
            throw new IllegalStateException(
                    "harness.a2a.model.instances 至少要声明一个实例 — 例如 "
                            + "harness.a2a.model.instances.default.provider=glm");
        }
        String dflt = props.getDefaultModel();
        if (dflt == null || dflt.isBlank() || !props.getInstances().containsKey(dflt)) {
            throw new IllegalStateException(
                    "harness.a2a.model.default='"
                            + dflt
                            + "' 不在 instances 中。已声明: "
                            + props.getInstances().keySet());
        }
        String sv = props.getSupervisor();
        if (sv != null && !sv.isBlank() && !props.getInstances().containsKey(sv)) {
            throw new IllegalStateException(
                    "harness.a2a.model.supervisor='"
                            + sv
                            + "' 不在 instances 中。已声明: "
                            + props.getInstances().keySet());
        }
        for (Map.Entry<String, String> e : props.getSubagents().entrySet()) {
            if (!props.getInstances().containsKey(e.getValue())) {
                throw new IllegalStateException(
                        "harness.a2a.model.subagents."
                                + e.getKey()
                                + "='"
                                + e.getValue()
                                + "' 不在 instances 中。已声明: "
                                + props.getInstances().keySet());
            }
        }
    }

    /** 按实例名获取 Model。同一名字会复用缓存,因此每个连接只创建一次。 */
    public synchronized Model get(String instanceName) {
        if (instanceName == null || instanceName.isBlank()) {
            throw new IllegalArgumentException("instanceName must not be blank");
        }
        Model cached = cache.get(instanceName);
        if (cached != null) {
            return cached;
        }
        ModelProperties.Instance inst = props.getInstances().get(instanceName);
        if (inst == null) {
            throw new IllegalArgumentException(
                    "未知模型实例 '"
                            + instanceName
                            + "' — 已声明: "
                            + props.getInstances().keySet());
        }
        Model m = build(instanceName, inst);
        cache.put(instanceName, m);
        return m;
    }

    /** 默认模型(supervisor 也回退到这个)。 */
    public Model getDefault() {
        return get(props.getDefaultModel());
    }

    /** Supervisor 主模型 — 走 {@code supervisor} 配置,空则回退到 default。 */
    public Model getSupervisor() {
        String sv = props.getSupervisor();
        return (sv == null || sv.isBlank()) ? getDefault() : get(sv);
    }

    /**
     * 给定 subagent 名取对应模型 — 命中 {@code subagents.<name>} 用对应实例,否则用 default。
     */
    public Model getForSubagent(String subagentName) {
        String mapped = props.getSubagents().get(subagentName);
        return (mapped == null || mapped.isBlank()) ? getDefault() : get(mapped);
    }

    /**
     * 根据用户ID获取对应的 FallbackModelDecorator。
     * <p>优先使用用户自定义配置（token/model/url），无配置时使用默认模型。
     * 无论哪种情况，都会包装为 FallbackModelDecorator 以支持自动降级。
     *
     * @param userId          用户ID
     * @param userConfigLookup 用户配置查找函数，返回 null 表示无用户配置
     * @param fallbackProps   通用降级配置
     * @return FallbackModelDecorator 实例（已包装降级逻辑）
     */
    public FallbackModelDecorator getModelForUser(
            Long userId,
            java.util.function.Function<Long, UserModelConfig> userConfigLookup,
            FallbackModelProperties fallbackProps) {

        // 尝试获取用户配置
        UserModelConfig userConfig = null;
        if (userId != null && userConfigLookup != null) {
            try {
                userConfig = userConfigLookup.apply(userId);
            } catch (Exception e) {
                log.warn("查询用户模型配置异常 userId={}: {}", userId, e.getMessage());
            }
        }

        Model primaryModel;
        boolean hasUserConfig = userConfig != null;

        if (hasUserConfig) {
            log.info("使用用户自定义模型配置 userId={} provider={} model={}",
                    userId, userConfig.getProvider(), userConfig.getModelName());
            primaryModel = ModelBuilders.buildFromUserConfig(
                    userConfig.getProvider(),
                    userConfig.getToken(),
                    userConfig.getRequestUrl(),
                    userConfig.getModelName());
        } else {
            log.info("用户无自定义配置，使用默认模型 userId={}", userId);
            primaryModel = getDefault();
        }

        // 构建通用降级 Model
        Model fallbackModel = ModelBuilders.buildFromUserConfig(
                fallbackProps.getProvider(),
                fallbackProps.getApiKey(),
                fallbackProps.getBaseUrl(),
                fallbackProps.getModelName());

        return new FallbackModelDecorator(primaryModel, fallbackModel, fallbackProps);
    }

    private Model build(String instanceName, ModelProperties.Instance inst) {
        String provider = inst.getProvider() == null ? "" : inst.getProvider().toLowerCase();
        if (inst.getApiKey() == null || inst.getApiKey().isBlank()) {
            throw new IllegalStateException(
                    "harness.a2a.model.instances." + instanceName + ".api-key 为空");
        }
        if (inst.getName() == null || inst.getName().isBlank()) {
            throw new IllegalStateException(
                    "harness.a2a.model.instances." + instanceName + ".name 为空");
        }
        String baseUrl = nullIfBlank(inst.getBaseUrl());
        log.info(
                "Build model instance='{}' provider={} name={} baseUrl={} keyPrefix={}...",
                instanceName,
                provider,
                inst.getName(),
                baseUrl,
                inst.getApiKey().substring(0, Math.min(12, inst.getApiKey().length())));
        return switch (provider) {
            case "anthropic" -> ModelBuilders.anthropic(inst.getApiKey(), baseUrl, inst.getName());
            case "openai" -> ModelBuilders.openAI(inst.getApiKey(), baseUrl, inst.getName());
            case "glm" -> ModelBuilders.glm(inst.getApiKey(), baseUrl, inst.getName());
            default ->
                    throw new IllegalArgumentException(
                            "harness.a2a.model.instances."
                                    + instanceName
                                    + ".provider='"
                                    + provider
                                    + "' 未知。支持: anthropic / openai / glm");
        };
    }

    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
