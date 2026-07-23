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
package com.agentscopea2a.v2.model;

import com.agentscopea2a.entity.UserModelConfig;
import com.agentscopea2a.mapper.mysql.UserModelConfigMapper;
import com.agentscopea2a.v2.config.HarnessRunnerProperties;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.anthropic.AnthropicChatModel;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v2 模型提供者 - 根据用户 ID 动态选择模型并包装降级逻辑。
 *
 * <p>优先级：
 * <ol>
 *   <li>用户自定义模型（从数据库读取，带缓存）</li>
 *   <li>系统默认模型（配置文件中的 glm-main）</li>
 * </ol>
 *
 * <p>无论哪种情况，都会包装为 {@link FallbackModelDecorator} 以支持自动降级。
 */
@Component
public class ModelProvider {

    private static final Logger log = LoggerFactory.getLogger(ModelProvider.class);

    /** 用户模型配置缓存有效期：5 分钟 */
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;

    private final UserModelConfigMapper userModelConfigMapper;
    private final HarnessRunnerProperties harnessRunnerProperties;
    private final Model defaultModel;

    /** userId -> 缓存条目（含配置和过期时间戳） */
    private final Map<Long, CacheEntry> userModelCache = new ConcurrentHashMap<>();

    public ModelProvider(
            UserModelConfigMapper userModelConfigMapper,
            HarnessRunnerProperties harnessRunnerProperties,
            @Value("${agentscope.llm.api-key:}") String llmApiKey,
            @Value("${agentscope.llm.api-url:}") String llmApiUrl,
            @Value("${agentscope.llm.model:}") String llmModel) {
        this.userModelConfigMapper = userModelConfigMapper;
        this.harnessRunnerProperties = harnessRunnerProperties;

        // 统一从 agentscope.llm.* 构建默认模型 (deepseek)
        this.defaultModel = OpenAIChatModel.builder()
                .apiKey(llmApiKey)
                .baseUrl(llmApiUrl)
                .modelName(llmModel)
                .stream(true)
                .build();

        log.info("V2ModelProvider initialized: defaultModel={} baseUrl={}", llmModel, llmApiUrl);
    }

    /**
     * 根据用户 ID 获取带降级逻辑的模型。
     *
     * @param userId 用户 ID，null 或无配置时使用默认模型
     * @return FallbackModelDecorator 实例
     */
    public FallbackModelDecorator getModelForUser(Long userId) {
        Model primaryModel = null;
        boolean hasUserConfig = false;

        if (userId != null) {
            try {
                UserModelConfig userConfig = getCachedUserConfig(userId);
                if (userConfig != null && userConfig.getToken() != null && !userConfig.getToken().isBlank()) {
                    hasUserConfig = true;
                    log.info("使用用户自定义模型配置 userId={} provider={} model={}",
                            userId, userConfig.getProvider(), userConfig.getModelName());
                    primaryModel = buildUserModel(userConfig);
                }
            } catch (Exception e) {
                log.warn("查询用户模型配置异常 userId={}: {}", userId, e.getMessage());
            }
        }

        if (!hasUserConfig) {
            log.info("用户无自定义配置，使用默认模型 userId={}", userId);
            primaryModel = defaultModel;
        }

        return new FallbackModelDecorator(primaryModel, defaultModel);
    }

    /**
     * 获取缓存的用户模型配置，过期则回源数据库并刷新缓存。
     */
    private UserModelConfig getCachedUserConfig(Long userId) {
        CacheEntry entry = userModelCache.get(userId);
        if (entry != null && !entry.isExpired()) {
            return entry.config;
        }
        // 缓存未命中或已过期，查库
        UserModelConfig config = userModelConfigMapper.selectByUserId(userId);
        if (config != null) {
            userModelCache.put(userId, new CacheEntry(config));
            log.debug("用户模型配置已缓存 userId={}", userId);
        } else {
            // 缓存空值防止缓存穿透（短 TTL）
            userModelCache.put(userId, new CacheEntry(null, 30_000L));
        }
        return config;
    }

    /**
     * 清除指定用户的模型缓存，供外部调用（如管理端修改配置后主动刷新）。
     */
    public void invalidateUserCache(Long userId) {
        userModelCache.remove(userId);
        log.info("用户模型缓存已清除 userId={}", userId);
    }

    /**
     * 清除所有用户模型缓存。
     */
    public void invalidateAllCache() {
        userModelCache.clear();
        log.info("所有用户模型缓存已清除");
    }

    /**
     * 根据用户配置构建模型实例。
     */
    private Model buildUserModel(UserModelConfig config) {
        String provider = config.getProvider() != null ? config.getProvider().toLowerCase() : "openai";
        String apiKey = config.getToken();
        String baseUrl = config.getRequestUrl();
        String modelName = config.getModelName();

        log.debug("构建用户模型: provider={} model={} baseUrl={}", provider, modelName, baseUrl);

        return switch (provider) {
            case "anthropic" -> AnthropicChatModel.builder()
                    .apiKey(apiKey)
                    .baseUrl(baseUrl)
                    .modelName(modelName)
                    .stream(true)
                    .build();
            case "glm" -> {
                // GLM 走 OpenAI 协议
                String url = baseUrl != null && !baseUrl.isBlank() ? baseUrl : "https://open.bigmodel.cn/api/paas/v4/";
                yield OpenAIChatModel.builder()
                        .apiKey(apiKey)
                        .baseUrl(url)
                        .modelName(modelName)
                        .stream(true)
                        .build();
            }
            case "openai", "" -> OpenAIChatModel.builder()
                    .apiKey(apiKey)
                    .baseUrl(baseUrl)
                    .modelName(modelName)
                    .stream(true)
                    .build();
            default -> {
                log.warn("未知 provider='{}', 回退到 OpenAI 协议", provider);
                yield OpenAIChatModel.builder()
                        .apiKey(apiKey)
                        .baseUrl(baseUrl)
                        .modelName(modelName)
                        .stream(true)
                        .build();
            }
        };
    }

    /**
     * V3.0: resolve an independent model instance by config key (verify / critic) so the
     * verification sub-agents are isolated from business-model downgrade and don't contend for the
     * business model's quota. Falls back to the default model when the instance is not configured.
     * Returns a {@link FallbackModelDecorator} wrapping the resolved primary with the default model
     * as fallback, so verify/critic degrade gracefully instead of hard-failing.
     */
    public FallbackModelDecorator getModelByKey(String instanceKey) {
        if (instanceKey == null || instanceKey.isBlank()) {
            return new FallbackModelDecorator(defaultModel, defaultModel);
        }
        HarnessRunnerProperties.Instances inst = harnessRunnerProperties.getModel().getInstances();
        HarnessRunnerProperties.ModelInstance mi = switch (instanceKey) {
            case "verify" -> inst.getVerify();
            case "critic" -> inst.getCritic();
            case "fallback" -> inst.getFallback();
            default -> null;
        };
        if (mi == null || !mi.isConfigured()) {
            log.info("ModelProvider: instance '{}' not configured, using default model", instanceKey);
            return new FallbackModelDecorator(defaultModel, defaultModel);
        }
        Model m = OpenAIChatModel.builder()
                .apiKey(mi.getApiKey())
                .baseUrl(mi.getBaseUrl())
                .modelName(mi.getName())
                .stream(true)
                .build();
        log.info("ModelProvider: resolved independent model instance '{}' name={}", instanceKey, mi.getName());
        return new FallbackModelDecorator(m, defaultModel);
    }

    /**
     * 获取默认模型（用于 memory 等场景）。
     */
    public Model getDefaultModel() {
        return defaultModel;
    }

    /**
     * 缓存条目：持有配置和过期时间戳。
     */
    private static class CacheEntry {
        final UserModelConfig config;
        final long expireAtMs;

        CacheEntry(UserModelConfig config) {
            this(config, CACHE_TTL_MS);
        }

        CacheEntry(UserModelConfig config, long ttlMs) {
            this.config = config;
            this.expireAtMs = System.currentTimeMillis() + ttlMs;
        }

        boolean isExpired() {
            return System.currentTimeMillis() >= expireAtMs;
        }
    }
}
