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

/**
 * Thin facade kept for backward compatibility — actual building happens in
 * {@link ModelRegistry}. New code should depend on {@link ModelRegistry} directly so it can pick
 * <i>different</i> models for different agents/subagents.
 */
public final class ModelFactory {

    private ModelFactory() {}

    /**
     * 用配置中的默认模型构建 Model。等价于 {@code new ModelRegistry(props).getDefault()}。
     * 仅在没有获得 ModelRegistry 引用时使用 — 推荐直接注入 {@link ModelRegistry}。
     */
    public static Model createModel(ModelProperties props) {
        return new ModelRegistry(props).getDefault();
    }
}
