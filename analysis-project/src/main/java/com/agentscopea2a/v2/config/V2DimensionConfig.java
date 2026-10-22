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
package com.agentscopea2a.v2.config;

import com.agentscopea2a.v2.dimension.AliasResolver;
import com.agentscopea2a.v2.dimension.DimensionAliasRepository;
import com.agentscopea2a.v2.dimension.DimensionStateManager;
import com.agentscopea2a.v2.dimension.LlmDimensionService;
import com.agentscopea2a.v2.dimension.OpenAILlmDimensionService;
import com.agentscopea2a.v2.middleware.DimensionStateMiddleware;
import com.agentscopea2a.v2.toolrouting.ToolRoutingCatalogService;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.Set;

/**
 * v2 维度状态配置。
 *
 * <p>将维度分析服务从 v1 的 {@code com.agentscopea2a.agent.dimension} 包
 * 迁移到 v2 的 {@code com.agentscopea2a.v2.dimension} 包，
 * 并使用 v2 的 {@link Model} 接口替代 v1 的 session 持久化。
 */
@Configuration
public class V2DimensionConfig {

    @Bean
    public LlmDimensionService llmDimensionService(
            @Value("${harness.a2a.model.instances.light-classifier.api-key}") String apiKey,
            @Value("${harness.a2a.model.instances.light-classifier.base-url}") String baseUrl,
            @Value("${harness.a2a.model.instances.light-classifier.name}") String modelName) {
        // 复用 light-classifier 实例（qwen3:8b）做维度分析，降低 token 成本
        Model dimensionModel = OpenAIChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .stream(true)
                .build();
        return new OpenAILlmDimensionService(dimensionModel);
    }

    @Bean
    public DimensionStateManager dimensionStateManager(
            LlmDimensionService llmService,
            AliasResolver aliasResolver,
            ObjectProvider<ToolRoutingCatalogService> catalogProvider,
            org.springframework.beans.factory.ObjectProvider<com.agentscopea2a.v2.dimension.DimensionKeyConfigRepository> dimensionKeyRepoProvider) {
        // §13.4 组名修剪指标词表：目录 bean 缺失或快照失败时降级为空表（只修剪虚词）
        // 口语值方案 §3.2：内置白名单键词表合并供给器（字典缺失/读失败 → 纯正则降级）
        java.util.function.Supplier<java.util.List<com.agentscopea2a.v2.dimension.DimensionKeyConfig>>
                keySupplier = dimensionKeyRepoProvider.getIfAvailable() != null
                ? dimensionKeyRepoProvider.getObject()::findAllEnabled
                : null;
        return new DimensionStateManager(llmService, aliasResolver, () -> {
            ToolRoutingCatalogService svc = catalogProvider.getIfAvailable();
            if (svc == null) {
                return Set.of();
            }
            try {
                return svc.snapshot().metricTags();
            } catch (RuntimeException e) {
                return Set.of();
            }
        }, keySupplier);
    }

    /**
     * 维度同义词仓储（gaussCustomerDataSource，与 skill_index 同库）；
     * 表不存在时首连自动建表 + 灌种子数据，TTL 5 分钟快照。
     */
    @Bean
    public DimensionAliasRepository dimensionAliasRepository(
            @Qualifier("gaussCustomerDataSource") DataSource dataSource,
            @Value("${harness.a2a.dimension.alias-cache-ttl-millis:300000}") long cacheTtlMillis) {
        return new DimensionAliasRepository(dataSource, cacheTtlMillis);
    }

    @Bean
    public AliasResolver aliasResolver(DimensionAliasRepository dimensionAliasRepository) {
        return new AliasResolver(dimensionAliasRepository::findAllEnabled);
    }

    /**
     * 维度键字典仓储（docs/history/skill-direct-exec-plan.md §9.2）：内置 9 键 seed +
     * 用户新增键的词表提取配置；TTL 快照同 dimension_alias 模式。
     */
    @Bean
    public com.agentscopea2a.v2.dimension.DimensionKeyConfigRepository dimensionKeyConfigRepository(
            @Qualifier("gaussCustomerDataSource") DataSource dataSource,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            @Value("${harness.a2a.dimension.key-cache-ttl-millis:300000}") long cacheTtlMillis) {
        return new com.agentscopea2a.v2.dimension.DimensionKeyConfigRepository(dataSource, objectMapper, cacheTtlMillis);
    }

    @Bean
    public DimensionStateMiddleware dimensionStateMiddleware(
            DimensionStateManager dimensionStateManager,
            @Value("${harness.a2a.dimension.max-chars:2000}") int maxChars,
            com.agentscopea2a.v2.dimension.PersonalDimensionResolver personalDimensionResolver) {
        return new DimensionStateMiddleware(dimensionStateManager, maxChars, personalDimensionResolver);
    }

    /**
     * 火车日历快照查询（口语值方案 §3.3）：gauss-common 数据源同 VersionPlanKnowledgeHook，
     * TTL 30 分钟（版本切换粒度是天级）。
     */
    @Bean
    public com.agentscopea2a.v2.dimension.VersionCalendarRepository versionCalendarRepository(
            @Qualifier("gaussCommonDataSource") DataSource dataSource,
            @Value("${harness.a2a.dimension.calendar-cache-ttl-seconds:1800}") long cacheTtlSeconds) {
        return new com.agentscopea2a.v2.dimension.VersionCalendarRepository(
                new org.springframework.jdbc.core.JdbcTemplate(dataSource), cacheTtlSeconds);
    }

    /**
     * 人员台账数据访问层（人员台账维度方案 §2）：gauss-common 数据源同
     * VersionCalendarRepository，TTL 30 分钟（台账月度更新，天级粒度足够）。
     */
    @Bean
    public com.agentscopea2a.v2.dimension.PersonLedgerRepository personLedgerRepository(
            @Qualifier("gaussCommonDataSource") DataSource dataSource,
            @Value("${harness.a2a.dimension.person-ledger-cache-ttl-seconds:1800}") long cacheTtlSeconds) {
        return new com.agentscopea2a.v2.dimension.PersonLedgerRepository(
                new org.springframework.jdbc.core.JdbcTemplate(dataSource), cacheTtlSeconds);
    }

    /** "我X" 口语维度解析（人员台账维度方案 §3）+ 人名字典扫描（§4.1）。 */
    @Bean
    public com.agentscopea2a.v2.dimension.PersonalDimensionResolver personalDimensionResolver(
            com.agentscopea2a.v2.dimension.PersonLedgerRepository personLedgerRepository) {
        return new com.agentscopea2a.v2.dimension.PersonalDimensionResolver(personLedgerRepository);
    }

    /**
     * 版本/季度锚点解析（口语值方案 §3.3）：在建版本查询复用 VersionPlanKnowledgeHook
     * 的 TTL 缓存（hook 缺失时锚点 THIS_V/THIS_Q 降级为空 → 参数不传回退 LLM）。
     */
    @Bean
    public com.agentscopea2a.v2.dimension.VersionPlanAnchorResolver versionPlanAnchorResolver(
            com.agentscopea2a.v2.dimension.VersionCalendarRepository versionCalendarRepository,
            ObjectProvider<com.agentscopea2a.v2.hooks.VersionPlanKnowledgeHook> hookProvider) {
        return new com.agentscopea2a.v2.dimension.VersionPlanAnchorResolver(
                versionCalendarRepository,
                () -> {
                    com.agentscopea2a.v2.hooks.VersionPlanKnowledgeHook hook = hookProvider.getIfAvailable();
                    return hook == null ? java.util.List.of() : hook.currentVersions();
                });
    }
}
