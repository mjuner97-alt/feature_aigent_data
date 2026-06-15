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

import com.agentscopea2a.harness.artifact.ArtifactStore;
import com.agentscopea2a.harness.cache.ResponseCacheService;
import com.agentscopea2a.harness.dimension.DimensionStateManager;
import com.agentscopea2a.harness.session.MySQLSession;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.agentscope.core.model.Model;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Infrastructure beans: pooled MySQL {@link DataSource}, MySQL session, response cache,
 * workspace path, model.
 *
 * <p>All MySQL credentials come from {@link PersistenceProperties}. Every field has a sensible
 * default so the application starts without any external configuration.
 */
@Configuration
public class  InfraConfig {

    private static final Logger log = LoggerFactory.getLogger(InfraConfig.class);

    @Value("${harness.a2a.workspace.path:.agentscope/workspace/harness-a2a}")
    private String workspacePath;

    @Value("${harness.a2a.artifacts.keep:false}")
    private boolean keepArtifacts;



    @Bean
    public MySQLSession mysqlSession(DataSource dataSource) {
        return new MySQLSession(dataSource);
    }

    @Bean
    public ResponseCacheService responseCacheService(DataSource dataSource) {
        ResponseCacheService service = new ResponseCacheService(dataSource);
        try {
            service.cleanExpired();
        } catch (Exception e) {
            log.warn(
                    "ResponseCache initial cleanExpired() failed (MySQL likely unreachable): {}",
                    e.getMessage());
        }
        return service;
    }

    @Bean
    public DimensionStateManager cacheDimensionManager() {
        return new DimensionStateManager(null); // null = rule-based only
    }

    @Bean
    public Path workspace() {
        Path target = Path.of(workspacePath);
        return WorkspaceMaterializer.ensureMaterialized(target);
    }

    /**
     * Per-tenant CSV scratchpad for cross-subagent data handoff. The agent-visible path equals
     * the host absolute path because subagents now run their {@code shell_execute} directly on
     * the host (no Docker sandbox).
     */
    @Bean
    public ArtifactStore artifactStore(Path workspace) throws IOException {
        Path artifactsRoot = workspace.resolve("artifacts");
        Files.createDirectories(artifactsRoot);
        String mountPrefix = artifactsRoot.toAbsolutePath().toString();
        log.info(
                "ArtifactStore ready: hostRoot={}, agentMountPrefix={}, keepArtifacts={}",
                artifactsRoot,
                mountPrefix,
                keepArtifacts);
        return new ArtifactStore(artifactsRoot, mountPrefix, keepArtifacts);
    }

    @Bean
    public MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }

    @Bean
    public Model model(ModelProperties props) {
        return ModelFactory.createModel(props);
    }
}
