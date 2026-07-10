/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.agentscopea2a.harness.config;

import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.spec.DockerFilesystemSpec;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.sandbox.RedisSandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.SandboxDistributedOptions;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerCliRunner;
import io.agentscope.harness.agent.sandbox.layout.BindMountEntry;
import io.agentscope.harness.agent.store.BaseStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.UnifiedJedis;

/**
 * Optional filesystem / distributed-store wiring for two profiles:
 *
 * <ul>
 *   <li><b>Sandbox</b> — set {@code harness.a2a.sandbox.enabled=true} and a
 *       {@link DockerFilesystemSpec} routes {@code FilesystemTool} / {@code ShellExecuteTool} /
 *       {@code SkillSaveTool} into a per-session Docker container.
 *   <li><b>Distributed</b> — set {@code harness.a2a.distributed.enabled=true} and a
 *       {@link RemoteFilesystemSpec} backed by Redis routes MEMORY.md / sessions / skills
 *       through a shared {@link BaseStore}.
 * </ul>
 *
 * <p>Both default to OFF — without enabling either, this configuration creates no beans and the
 * supervisor falls back to harness's default local filesystem.
 */
@Configuration
public class FilesystemConfig {

    private static final Logger log = LoggerFactory.getLogger(FilesystemConfig.class);

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(
            prefix = "harness.a2a.distributed",
            name = "enabled",
            havingValue = "true")
    public UnifiedJedis distributedJedis(SandboxProperties props) {
        SandboxProperties.Distributed d = props.getDistributed();
        String url = "redis://" + d.getRedisHost() + ":" + d.getRedisPort();
        log.info("Distributed mode ON — connecting Jedis to {}", url);
        if (d.getRedisPassword() != null && !d.getRedisPassword().isBlank()) {
            return new JedisPooled(d.getRedisHost(), d.getRedisPort(), null, d.getRedisPassword());
        }
        return new JedisPooled(d.getRedisHost(), d.getRedisPort());
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "harness.a2a.distributed",
            name = "enabled",
            havingValue = "true")
    public BaseStore distributedStore(UnifiedJedis jedis) {
        return new JedisBaseStore(jedis, "harness-a2a");
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "harness.a2a.distributed",
            name = "enabled",
            havingValue = "true")
    public SandboxExecutionGuard sandboxExecutionGuard(UnifiedJedis jedis) {
        return RedisSandboxExecutionGuard.builder(jedis).build();
    }

    @Bean
    @Primary
    @ConditionalOnProperty(prefix = "harness.a2a.sandbox", name = "enabled", havingValue = "true")
    public SandboxFilesystemSpec sandboxFilesystem(SandboxProperties props, Path workspace)
            throws IOException {
        SandboxProperties.Sandbox s = props.getSandbox();
        // Shared-container hook: the local source override at
        // io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClient shadows the
        // bundled JAR class via target/classes precedence and exposes setSharedContainerName.
        // Calling this once at boot collapses every IsolationScope.GLOBAL request onto a single
        // pre-created daemon-side container (operator runs `docker run -d --name <name> ...`
        // beforehand; this JVM never creates or destroys it).
        io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClient.setSharedContainerName(
                s.getSharedContainerName());
        DockerCliRunner.configure(
                s.isRemoteDockerEnabled(),
                s.getRemoteDockerSshTarget(),
                s.getRemoteDockerSshOptions(),
                s.getRemoteDockerTimeoutSeconds());
        if (s.isRemoteDockerEnabled()) {
            log.info(
                    "Remote Docker mode ON — executing docker CLI through ssh target={} timeout={}s",
                    s.getRemoteDockerSshTarget(),
                    s.getRemoteDockerTimeoutSeconds());
        }
        if (s.getSharedContainerName() != null && !s.getSharedContainerName().isBlank()) {
            log.info(
                    "Shared sandbox container configured: name={} (this JVM will attach via"
                            + " 'docker inspect' and never create or destroy the container)",
                    s.getSharedContainerName());
        }
        IsolationScope scope = parseScope(s.getIsolationScope(), IsolationScope.SESSION);
        log.info(
                "Sandbox mode ON (supervisor) — image={} workspaceRoot={} scope={}",
                s.getImage(),
                s.getWorkspaceRoot(),
                scope);
        return buildSandboxSpec(props, workspace, scope);
    }

    @Bean(name = "subagentSandboxFilesystem")
    @ConditionalOnProperty(prefix = "harness.a2a.sandbox", name = "enabled", havingValue = "true")
    public SandboxFilesystemSpec subagentSandboxFilesystem(SandboxProperties props, Path workspace)
            throws IOException {
        SandboxProperties.Sandbox s = props.getSandbox();
        String requested = s.getSubagentIsolationScope();
        if (requested == null || requested.isBlank()) {
            return null;
        }
        IsolationScope supervisor = parseScope(s.getIsolationScope(), IsolationScope.SESSION);
        IsolationScope sub = parseScope(requested, supervisor);
        if (sub == supervisor) {
            return null;
        }
        log.info(
                "Sandbox mode ON (subagent override) — image={} workspaceRoot={} scope={}",
                s.getImage(),
                s.getWorkspaceRoot(),
                sub);
        return buildSandboxSpec(props, workspace, sub);
    }

    private SandboxFilesystemSpec buildSandboxSpec(
            SandboxProperties props, Path workspace, IsolationScope scope) throws IOException {
        SandboxProperties.Sandbox s = props.getSandbox();
        SandboxProperties.Artifacts.Remote remote = props.getArtifacts().getRemote();
        SandboxProperties.Skills.Remote skillsRemote = props.getSkills().getRemote();
        SandboxProperties.Memory.Remote memoryRemote = props.getMemory().getRemote();
        WorkspaceSpec workspaceSpec = new WorkspaceSpec();
        workspaceSpec.setRoot(s.getWorkspaceRoot());
        if (s.isMountSkills()) {
            if (skillsRemote.isEnabled() && !skillsRemote.getRemoteRoot().isBlank()) {
                BindMountEntry e = new BindMountEntry();
                e.setHostPath(skillsRemote.getRemoteRoot());
                e.setReadOnly(false);
                workspaceSpec.getEntries().put("skills", e);
            } else {
                workspaceSpec
                        .getEntries()
                        .put("skills", hostBindMount(workspace.resolve("skills")));
            }
            // Mount skills-auto/ (auto-synthesized business skills) for sandbox retrieval
            Path skillsAuto = workspace.resolve("skills-auto");
            if (Files.isDirectory(skillsAuto)) {
                workspaceSpec.getEntries().put("skills-auto", hostBindMount(skillsAuto));
            }
            // Mount skills-user/ (user-authored skills) for sandbox retrieval
            Path skillsUser = workspace.resolve("skills-user");
            if (Files.isDirectory(skillsUser)) {
                workspaceSpec.getEntries().put("skills-user", hostBindMount(skillsUser));
            }
        }
        if (s.isMountMemory()) {
            if (memoryRemote.isEnabled() && !memoryRemote.getRemoteRoot().isBlank()) {
                BindMountEntry e = new BindMountEntry();
                e.setHostPath(memoryRemote.getRemoteRoot());
                e.setReadOnly(false);
                workspaceSpec.getEntries().put("memory", e);
            } else {
                workspaceSpec
                        .getEntries()
                        .put("memory", hostBindMount(workspace.resolve("memory")));
            }
        }
        if (s.isMountArtifacts()) {
            BindMountEntry artifactsEntry;
            if (remote.isEnabled() && !remote.getRemoteRoot().isBlank()) {
                artifactsEntry = new BindMountEntry();
                artifactsEntry.setHostPath(remote.getRemoteRoot());
                artifactsEntry.setReadOnly(false);
            } else {
                artifactsEntry = hostBindMount(workspace.resolve("artifacts"));
            }
            workspaceSpec.getEntries().put("artifacts", artifactsEntry);
        }

        DockerFilesystemSpec spec =
                new DockerFilesystemSpec()
                        .image(s.getImage())
                        .workspaceRoot(s.getWorkspaceRoot())
                        .workspaceSpec(workspaceSpec);
        spec.workspaceProjectionRoots(List.of("AGENTS.md", "knowledge", "agent-subagents"));
        spec.isolationScope(scope);
        return spec;
    }

    private static BindMountEntry hostBindMount(Path hostDir) throws IOException {
        Files.createDirectories(hostDir);
        BindMountEntry entry = new BindMountEntry();
        entry.setHostPath(hostDir.toAbsolutePath().normalize().toString());
        entry.setReadOnly(false);
        return entry;
    }

    @Bean
    @ConditionalOnProperty(prefix = "harness.a2a.sandbox", name = "enabled", havingValue = "true")
    public SandboxDistributedOptions sandboxDistributedOptions(SandboxProperties props) {
        SandboxDistributedOptions.Builder b = SandboxDistributedOptions.builder();
        if (props.getDistributed().isEnabled()) {
            b.requireDistributed(true);
        } else {
            b.requireDistributed(false);
        }
        return b.build();
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "harness.a2a.distributed",
            name = "enabled",
            havingValue = "true")
    public RemoteFilesystemSpec remoteFilesystem(BaseStore store, SandboxProperties props) {
        IsolationScope scope =
                parseScope(props.getDistributed().getIsolationScope(), IsolationScope.USER);
        log.info("Distributed mode ON — RemoteFilesystemSpec with scope={}", scope);
        return new RemoteFilesystemSpec(store).isolationScope(scope).addSharedPrefix("skills/");
    }

    private static IsolationScope parseScope(String name, IsolationScope fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        try {
            return IsolationScope.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown IsolationScope '{}', using {}", name, fallback);
            return fallback;
        }
    }
}
