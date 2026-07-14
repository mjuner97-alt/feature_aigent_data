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

import com.agentscopea2a.agent.model.FallbackModelProperties;
import com.agentscopea2a.agent.model.ModelProperties;
import com.agentscopea2a.agent.model.ModelRegistry;
import com.agentscopea2a.harness.artifact.ArtifactIo;
import com.agentscopea2a.harness.artifact.ArtifactStore;
import com.agentscopea2a.harness.artifact.LocalArtifactIo;
import com.agentscopea2a.harness.artifact.SshArtifactIo;
import com.agentscopea2a.harness.cache.ResponseCacheService;
import com.agentscopea2a.agent.dimension.DimensionStateManager;
import com.agentscopea2a.agent.session.MySQLSession;
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
     * Per-tenant CSV scratchpad for cross-subagent data handoff. When
     * {@code harness.a2a.artifacts.remote.enabled=true}, writes go to a remote host over SSH so
     * the bytes land where a remote Docker daemon can bind-mount them; otherwise local FS.
     */
    @Bean
    public ArtifactStore artifactStore(Path workspace, SandboxProperties sandboxProps)
            throws IOException {
        Path artifactsRoot = workspace.resolve("artifacts");
        Files.createDirectories(artifactsRoot);
        String mountPrefix = artifactsRoot.toAbsolutePath().toString();
        SandboxProperties.Artifacts.Remote remote = sandboxProps.getArtifacts().getRemote();
        ArtifactIo io;
        if (remote.isEnabled()
                && remote.getSshTarget() != null
                && !remote.getSshTarget().isBlank()
                && remote.getRemoteRoot() != null
                && !remote.getRemoteRoot().isBlank()) {
            io =
                    new SshArtifactIo(
                            remote.getSshTarget(),
                            remote.getRemoteRoot(),
                            remote.getSshOptions(),
                            remote.getTimeoutSeconds());
            log.info(
                    "ArtifactStore ready (REMOTE SSH): target={} remoteRoot={} agentMountPrefix={} keep={}",
                    remote.getSshTarget(),
                    remote.getRemoteRoot(),
                    mountPrefix,
                    keepArtifacts);
        } else {
            io = new LocalArtifactIo(artifactsRoot);
            log.info(
                    "ArtifactStore ready (LOCAL): hostRoot={}, agentMountPrefix={}, keepArtifacts={}",
                    artifactsRoot,
                    mountPrefix,
                    keepArtifacts);
        }
        return new ArtifactStore(artifactsRoot, io, mountPrefix, keepArtifacts);
    }

    @Bean
    public MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }

    @Bean
    public ModelRegistry modelRegistry(ModelProperties props) {
        return new ModelRegistry(props);
    }

    /**
     * Primary {@link Model} bean — resolved from the default instance in
     * {@link ModelRegistry}. Used by components that need a single Model injection
     * (e.g. {@code SkillDistiller}, {@code SupervisorService}).
     */
    @Bean
    @Primary
    public Model model(ModelRegistry modelRegistry) {
        return modelRegistry.getDefault();
    }

    /**
     * Fallback model properties bean — used by {@link com.agentscopea2a.agent.model.FallbackModelDecorator}
     * to define fallback chains when a user's primary model fails.
     */
    @Bean
    public FallbackModelProperties fallbackModelProperties() {
        return new FallbackModelProperties();
    }
}
