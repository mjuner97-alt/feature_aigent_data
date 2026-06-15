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

import io.agentscope.core.model.Model;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 全局静态门面 — 让任何代码(无论是不是 Spring Bean)都能直接拿到配置好的 {@link Model},
 * 不必在每个类里手动 {@code @Autowired ModelRegistry}。
 *
 * <p>底层完全复用 {@link ModelRegistry} 的缓存与校验逻辑;本类仅在容器启动时把它"钉"
 * 到静态字段上,后续 {@code ModelUtil.getDefault()} 等调用直接转发。
 *
 * <p>典型用法:
 * <pre>{@code
 *   Model main       = ModelUtil.getDefault();              // = harness.a2a.model.default
 *   Model supervisor = ModelUtil.getSupervisor();           // = harness.a2a.model.supervisor (回退 default)
 *   Model coder      = ModelUtil.forSubagent("code_interpreter");
 *   Model byName     = ModelUtil.get("glm-main");           // 按实例名直接取
 * }</pre>
 *
 * <p><b>注意</b>: 必须等 Spring 容器加载完本 Bean 之后才能使用。在 Bean 构造函数 / static
 * 块中调用会拿到 null。
 */
@Component
public class ModelUtil {

    /** Spring 启动后由 {@link #init()} 注入。 */
    private static volatile ModelRegistry REGISTRY;

    private final ModelRegistry registryRef;

    @Autowired
    public ModelUtil(ModelRegistry registry) {
        this.registryRef = registry;
    }

    @PostConstruct
    void init() {
        REGISTRY = this.registryRef;
    }

    private static ModelRegistry registry() {
        ModelRegistry r = REGISTRY;
        if (r == null) {
            throw new IllegalStateException(
                    "ModelUtil 尚未初始化 — 请确保 Spring 容器已启动,且不要在 static 块或"
                            + " Bean 构造方法中调用 ModelUtil");
        }
        return r;
    }

    /** 默认模型(对应 {@code harness.a2a.model.default})。 */
    public static Model getDefault() {
        return registry().getDefault();
    }

    /** Supervisor 主模型 — 走 {@code harness.a2a.model.supervisor},空则回退到 default。 */
    public static Model getSupervisor() {
        return registry().getSupervisor();
    }

    /** 按 subagent 名取对应模型 — 命中 {@code subagents.<name>} 用映射,否则用 default。 */
    public static Model forSubagent(String subagentName) {
        return registry().getForSubagent(subagentName);
    }

    /** 按实例名直接取(对应 {@code harness.a2a.model.instances.<name>})。 */
    public static Model get(String instanceName) {
        return registry().get(instanceName);
    }

    /** 直接拿到底层 {@link ModelRegistry} — 需要更细粒度操作时使用。 */
    public static ModelRegistry registryInstance() {
        return registry();
    }
}