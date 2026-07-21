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

import com.agentscopea2a.v2.memory.EpisodicMemoryConfig;
import com.agentscopea2a.v2.memory.EpisodicMemory;
import com.agentscopea2a.v2.memory.MemoryHydrator;
import com.agentscopea2a.v2.memory.MysqlMemoryStore;
import com.agentscopea2a.v2.memory.MySqlEpisodicMemory;
import com.agentscopea2a.v2.middleware.EpisodicRetrievalMiddleware;
import com.agentscopea2a.v2.middleware.MemoryLedgerMirrorMiddleware;
import com.agentscopea2a.v2.middleware.PerUserMemoryContextMiddleware;
import com.agentscopea2a.v2.skills.EmbeddingClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * v2 情景记忆配置。
 *
 * <p>将 {@link MySqlEpisodicMemory} 从 v1 的 {@code agent/memory} 包迁移到 v2 的
 * {@code v2/memory} 包，并创建 {@link EpisodicRetrievalMiddleware} 供
 * {@link com.agentscopea2a.v2.runner.HarnessA2aRunnerV2} 挂载。
 *
 * <p>EmbeddingClient 为可选依赖：当 {@code harness.embedding.enabled=true} 时
 * {@link com.agentscopea2a.v2.skills.OpenAiCompatEmbeddingClient} bean 存在，
 * MySqlEpisodicMemory 将启用向量检索；否则退化为纯 FTS5 全文检索。
 */
@Configuration
public class V2MemoryConfig {

    private static final Logger log = LoggerFactory.getLogger(V2MemoryConfig.class);

    @Bean
    public EpisodicMemoryConfig episodicMemoryConfig(
            @Value("${harness.a2a.memory.digestion.episodic-table-name:QualitySupervisor_episodic_memory}") String table,
            @Value("${harness.episodic.database:}") String database,
            @Value("${harness.episodic.search-limit:10}") int searchLimit,
            @Value("${harness.episodic.vector-search-enabled:false}") boolean vectorSearchEnabled,
            @Value("${harness.episodic.vector-min-cosine:0.55}") float vectorMinCosine) {
        return EpisodicMemoryConfig.builder()
                .jdbcUrl("datasource-provided")
                .tableName(table)
                .database(database)
                .searchLimit(searchLimit)
                .vectorSearchEnabled(vectorSearchEnabled)
                .vectorMinCosine(vectorMinCosine)
                .build();
    }

    @Bean
    public EpisodicMemory episodicMemory(
            DataSource dataSource,
            EpisodicMemoryConfig config,
            ObjectProvider<EmbeddingClient> embeddingClientProvider) {
        EmbeddingClient embeddingClient = embeddingClientProvider.getIfAvailable();
        return new MySqlEpisodicMemory(dataSource, config, embeddingClient);
    }

    @Bean
    public EpisodicRetrievalMiddleware episodicRetrievalMiddleware(EpisodicMemory episodicMemory) {
        return new EpisodicRetrievalMiddleware(episodicMemory);
    }

    /**
     * Per-user MEMORY.md injector. Reads the current user's MEMORY.md body from
     * {@link MysqlMemoryStore} and appends it as a {@code ## 用户记忆} section to the
     * system prompt. Replaces the framework's shared-root {@code <memory_context>}
     * block (which we neutralize by deleting the root MEMORY.md file).
     *
     * <p>Only wired when mysql-mirror is enabled - without the per-user store there's
     * nothing tenant-scoped to inject, and the framework's root-file path is the only
     * source of memory content.
     */
    @Bean
    @ConditionalOnProperty(
            prefix = "harness.a2a.memory.mysql-mirror",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = false)
    public PerUserMemoryContextMiddleware perUserMemoryContextMiddleware(MysqlMemoryStore mysqlMemoryStore) {
        log.info("PerUserMemoryContextMiddleware: wired (injects per-user MEMORY.md from agent_memory table)");
        return new PerUserMemoryContextMiddleware(mysqlMemoryStore);
    }

    /**
     * Per-user MEMORY.md + ledger persistence. Only wired when the mysql-mirror is enabled;
     * otherwise the legacy file-only memory path is used and DDL never runs.
     *
     * <p>Stage 8: migrated from {@code com.agentscopea2a.agent.memory.MysqlMemoryStore}
     * (Maven-excluded v1 package) to v2.
     */
    @Bean
    @ConditionalOnProperty(
            prefix = "harness.a2a.memory.mysql-mirror",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = false)
    public MysqlMemoryStore mysqlMemoryStore(DataSource dataSource) {
        MysqlMemoryStore store = new MysqlMemoryStore(dataSource);
        store.ensureSchema();
        return store;
    }

    /**
     * DB -> file hydrator for {@code <workspace>/memory/<userId>/MEMORY.md}.
     *
     * <p>Stage 8: migrated from {@code com.agentscopea2a.agent.memory.MemoryHydrator}
     * (Maven-excluded v1 package) to v2.
     */
    @Bean
    @ConditionalOnProperty(
            prefix = "harness.a2a.memory.mysql-mirror",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = false)
    public MemoryHydrator memoryHydrator(
            MysqlMemoryStore mysqlMemoryStore,
            @Value("${harness.a2a.workspace.path:.agentscope/workspace/harness-a2a}") String workspacePath) {
        Path workspaceMemoryRoot = Paths.get(workspacePath).toAbsolutePath().resolve("memory");
        return new MemoryHydrator(workspaceMemoryRoot, mysqlMemoryStore);
    }

    /**
     * v2 replacement for v1 {@code MemoryFileWatcher}. Mirrors the JAR's flat daily ledger
     * {@code memory/YYYY-MM-DD.md} into MySQL {@code agent_memory_ledger} with per-user
     * attribution. Restores {@code tailLedger} / {@code findActiveUsers} / cross-replica sync
     * scenarios that broke when {@code MemoryFileWatcher} was not migrated.
     *
     * <p>Also writes a local {@code memory/<userId>/<date>.md} file per agent call. The JAR's
     * {@code MemoryFlushManager} attempts the same write to the sandbox container via
     * {@code SandboxBackedFilesystem.uploadFiles}, but on Windows the inline base64 payload
     * trips the 8KB CreateProcess limit (error=206). This local write bypasses the sandbox
     * so the daily file is visible on the host filesystem for {@code /debug/memory} inspection.
     */
    @Bean
    @ConditionalOnProperty(
            prefix = "harness.a2a.memory.mysql-mirror",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = false)
    public MemoryLedgerMirrorMiddleware memoryLedgerMirrorMiddleware(
            MysqlMemoryStore mysqlMemoryStore,
            @Value("${harness.a2a.workspace.path:.agentscope/workspace/harness-a2a}") String workspacePath) {
        Path workspaceMemoryRoot = Paths.get(workspacePath).toAbsolutePath().resolve("memory");
        log.info("MemoryLedgerMirrorMiddleware: wired (mirrors memory/<userId>/YYYY-MM-DD.md -> agent_memory_ledger + local file at {})", workspaceMemoryRoot);
        return new MemoryLedgerMirrorMiddleware(mysqlMemoryStore, workspaceMemoryRoot);
    }
}