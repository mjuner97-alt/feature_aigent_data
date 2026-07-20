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

import com.agentscopea2a.v2.sandbox.DockerCliRunner;
import com.agentscopea2a.v2.sandbox.SharedContainerDockerSandboxClient;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerFilesystemSpec;
import io.agentscope.harness.agent.sandbox.layout.BindMountEntry;
import io.agentscope.extensions.mysql.sandbox.JdbcSandboxExecutionGuard;
import io.agentscope.extensions.mysql.snapshot.JdbcSnapshotSpec;
import io.agentscope.extensions.mysql.MysqlDistributedStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * v2 sandbox / distributed-store wiring.
 *
 * <p>Replaces the v1 {@code FilesystemConfig} (which is in an excluded package and doesn't compile).
 * Uses the v2 API:
 *
 * <ul>
 *   <li>{@code DockerFilesystemSpec} with correct v2 import path
 *       ({@code io.agentscope.harness.agent.sandbox.impl.docker})
 *   <li>{@code DistributedStore} replaces removed {@code SandboxDistributedOptions}
 *   <li>{@code MysqlDistributedStore.create(dataSource)} for JDBC-backed distributed mode
 *   <li>{@code SharedContainerDockerSandboxClient} replaces the shadow override
 *   <li>{@code DockerCliRunner} moved to {@code com.agentscopea2a.v2.sandbox} package
 * </ul>
 */
@Configuration
public class V2SandboxConfig {

    private static final Logger log = LoggerFactory.getLogger(V2SandboxConfig.class);

    // ── Sandbox properties ─────────────────────────────────────────────

    @Bean
    @ConfigurationProperties(prefix = "harness.a2a")
    public SandboxPropertiesV2 sandboxPropertiesV2() {
        return new SandboxPropertiesV2();
    }

    @Bean
    @ConfigurationProperties(prefix = "harness.a2a.distributed")
    public DistributedPropertiesV2 distributedPropertiesV2() {
        return new DistributedPropertiesV2();
    }

    // ── Primary sandbox filesystem (supervisor scope) ───────────────────

    @Bean
    @Primary
    @ConditionalOnProperty(prefix = "harness.a2a.sandbox", name = "enabled", havingValue = "true")
    public SandboxFilesystemSpec sandboxFilesystem(
            SandboxPropertiesV2 sandboxProps,
            DistributedPropertiesV2 distributedProps,
            @Value("${harness.a2a.workspace.path:.agentscope/workspace/harness-a2a}") String workspacePath,
            DataSource dataSource) throws IOException {

        SandboxPropertiesV2.Sandbox s = sandboxProps.getSandbox();

        // Shared-container hook: set the static field on our custom client
        SharedContainerDockerSandboxClient.setSharedContainerName(s.getSharedContainerName());

        // SSH-remote Docker mode configuration
        DockerCliRunner.configure(
                s.isRemoteDockerEnabled(),
                s.getRemoteDockerSshTarget(),
                s.getRemoteDockerSshOptions(),
                s.getRemoteDockerTimeoutSeconds());

        // Shadow DockerCliRunner (JAR package io.agentscope.harness.agent.sandbox.impl.docker)
        // is used by shadow DockerSandbox / DockerSandboxClient. It's a separate static config
        // slot from the v2 DockerCliRunner above - without this call it stays at default
        // Config(false, ...) and falls back to Runtime.exec("docker"), which fails on Windows
        // hosts without docker.exe (CreateProcess error=2).
        io.agentscope.harness.agent.sandbox.impl.docker.DockerCliRunner.configure(
                s.isRemoteDockerEnabled(),
                s.getRemoteDockerSshTarget(),
                s.getRemoteDockerSshOptions(),
                s.getRemoteDockerTimeoutSeconds());

        if (s.isRemoteDockerEnabled()) {
            log.info(
                    "Remote Docker mode ON — executing docker CLI through ssh target={} timeout={}s",
                    s.getRemoteDockerSshTarget(),
                    s.getRemoteDockerTimeoutSeconds());
            // Warn if DOCKER_HOST env var is set - the JAR's internal DockerSandboxClient may
            // pick it up instead of our DockerCliRunner SSH target, causing connection mismatches.
            String dockerHost = System.getenv("DOCKER_HOST");
            if (dockerHost != null && !dockerHost.isBlank()) {
                log.warn(
                        "DOCKER_HOST env var is set to '{}' - this may override DockerCliRunner SSH"
                                + " target '{}'. If sandbox connections go to the wrong host,"
                                + " unset DOCKER_HOST or align it with remote-docker-ssh-target.",
                        dockerHost,
                        s.getRemoteDockerSshTarget());
            }
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

        Path workspace = Paths.get(workspacePath).toAbsolutePath();

        DockerFilesystemSpec spec = buildSandboxSpec(sandboxProps, workspace, scope, dataSource);
        return spec;
    }

    // ── Subagent sandbox filesystem (separate isolation scope) ──────────

    @Bean(name = "subagentSandboxFilesystem")
    @ConditionalOnProperty(prefix = "harness.a2a.sandbox", name = "enabled", havingValue = "true")
    public SandboxFilesystemSpec subagentSandboxFilesystem(
            SandboxPropertiesV2 sandboxProps,
            DistributedPropertiesV2 distributedProps,
            @Value("${harness.a2a.workspace.path:.agentscope/workspace/harness-a2a}") String workspacePath,
            DataSource dataSource) throws IOException {

        SandboxPropertiesV2.Sandbox s = sandboxProps.getSandbox();
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

        Path workspace = Paths.get(workspacePath).toAbsolutePath();
        DockerFilesystemSpec spec = buildSandboxSpec(sandboxProps, workspace, sub, dataSource);
        return spec;
    }

    // ── Distributed store (MySQL-backed) ────────────────────────────────

    @Bean
    @ConditionalOnProperty(prefix = "harness.a2a.distributed", name = "enabled", havingValue = "true")
    public DistributedStore distributedStore(DataSource dataSource) {
        log.info("Distributed mode ON — using MysqlDistributedStore");
        return MysqlDistributedStore.create(dataSource);
    }

    // ── Remote filesystem for distributed mode ───────────────────────────

    @Bean
    @ConditionalOnProperty(prefix = "harness.a2a.distributed", name = "enabled", havingValue = "true")
    public RemoteFilesystemSpec remoteFilesystem(
            DistributedStore distributedStore,
            DistributedPropertiesV2 distributedProps) {
        IsolationScope scope =
                parseScope(distributedProps.getIsolationScope(), IsolationScope.USER);
        log.info("Distributed mode ON — RemoteFilesystemSpec with scope={}", scope);
        return new RemoteFilesystemSpec(distributedStore.baseStore())
                .isolationScope(scope)
                .addSharedPrefix("skills/");
    }

    // ── Internal helpers ────────────────────────────────────────────────

    private DockerFilesystemSpec buildSandboxSpec(
            SandboxPropertiesV2 props,
            Path workspace,
            IsolationScope scope,
            DataSource dataSource) throws IOException {

        SandboxPropertiesV2.Sandbox s = props.getSandbox();
        SandboxPropertiesV2.Artifacts.Remote remote = props.getArtifacts().getRemote();
        SandboxPropertiesV2.Skills.Remote skillsRemote = props.getSkills().getRemote();
        SandboxPropertiesV2.Memory.Remote memoryRemote = props.getMemory().getRemote();

        WorkspaceSpec workspaceSpec = new WorkspaceSpec();
        workspaceSpec.setRoot(s.getWorkspaceRoot());
        if (s.isMountSkills()) {
            if (skillsRemote.isEnabled() && !skillsRemote.getRemoteRoot().isBlank()) {
                BindMountEntry e = new BindMountEntry();
                e.setHostPath(skillsRemote.getRemoteRoot());
                e.setReadOnly(false);
                workspaceSpec.getEntries().put("skills", e);
            } else {
                workspaceSpec.getEntries().put("skills", hostBindMount(workspace.resolve("skills")));
            }
            Path skillsAuto = workspace.resolve("skills-auto");
            if (Files.isDirectory(skillsAuto)) {
                workspaceSpec.getEntries().put("skills-auto", hostBindMount(skillsAuto));
            }
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
                workspaceSpec.getEntries().put("memory", hostBindMount(workspace.resolve("memory")));
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
                        .workspaceSpec(workspaceSpec)
                        .client(new SharedContainerDockerSandboxClient());

        // Project host-side workspace roots into the container via tar archive.
        // In shared-container mode the externally set bind mounts only cover skills/,
        // skills-auto/, memory/, artifacts/. The other roots below would be invisible
        // in the container unless explicitly projected. skills-user/ holds e2e-saved
        // skills, knowledge-dynamic/ holds QI_KNOWLEDGE.md (loaded by KnowledgeRetrievalHook
        // host-side; projecting it lets code_interpreter read it too).
        spec.workspaceProjectionRoots(List.of(
                "AGENTS.md",
                "knowledge",
                "knowledge-dynamic",
                "agent-subagents",
                "skills-auto",
                "skills-user"));

        // Add distributed snapshot/guard if distributed mode is on. These MUST be added before
        // isolationScope() so they can observe (and not override) the scope. The previous order
        // (scope first, then snapshot/guard) let the JAR's distributed state loader fall back to
        // GLOBAL scope (__global__ key), which MySQL state store rejects.
        if (props.getDistributed().isEnabled() && dataSource != null) {
            spec.snapshotSpec(new JdbcSnapshotSpec(dataSource));
            spec.executionGuard(JdbcSandboxExecutionGuard.builder(dataSource).build());
            log.info("Distributed sandbox: snapshotSpec + executionGuard wired for scope={}", scope);
        }

        // Set isolation scope LAST so it is the final authority on the spec. This prevents
        // snapshotSpec/executionGuard from silently resetting it to GLOBAL.
        spec.isolationScope(scope);
        log.info("Sandbox spec finalized: image={} scope={} distributed={}",
                s.getImage(), scope, props.getDistributed().isEnabled());

        return spec;
    }

    private static BindMountEntry hostBindMount(Path hostDir) throws IOException {
        Files.createDirectories(hostDir);
        BindMountEntry entry = new BindMountEntry();
        entry.setHostPath(hostDir.toAbsolutePath().normalize().toString());
        entry.setReadOnly(false);
        return entry;
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

    // ── Properties inner classes ─────────────────────────────────────────

    /**
     * V2 sandbox properties, mirroring the v1 {@code SandboxProperties} structure.
     * The v1 class is in an excluded package, so we need our own.
     */
    public static class SandboxPropertiesV2 {
        private Sandbox sandbox = new Sandbox();
        private Distributed distributed = new Distributed();
        private Artifacts artifacts = new Artifacts();
        private Skills skills = new Skills();
        private Memory memory = new Memory();

        public Sandbox getSandbox() { return sandbox; }
        public void setSandbox(Sandbox sandbox) { this.sandbox = sandbox; }
        public Distributed getDistributed() { return distributed; }
        public void setDistributed(Distributed distributed) { this.distributed = distributed; }
        public Artifacts getArtifacts() { return artifacts; }
        public void setArtifacts(Artifacts artifacts) { this.artifacts = artifacts; }
        public Skills getSkills() { return skills; }
        public void setSkills(Skills skills) { this.skills = skills; }
        public Memory getMemory() { return memory; }
        public void setMemory(Memory memory) { this.memory = memory; }

        public static class Sandbox {
            private boolean enabled = false;
            private String image = "deepanalyze-vllm:latest";
            private String workspaceRoot = "/workspace";
            private String isolationScope = "SESSION";
            private String subagentIsolationScope = "";
            private boolean mountSkills = true;
            private boolean mountMemory = true;
            private boolean mountArtifacts = true;
            private String sharedContainerName = "";
            private boolean remoteDockerEnabled = false;
            private String remoteDockerSshTarget = "";
            private java.util.List<String> remoteDockerSshOptions = java.util.List.of();
            private long remoteDockerTimeoutSeconds = 60;

            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }
            public String getImage() { return image; }
            public void setImage(String image) { this.image = image; }
            public String getWorkspaceRoot() { return workspaceRoot; }
            public void setWorkspaceRoot(String workspaceRoot) { this.workspaceRoot = workspaceRoot; }
            public String getIsolationScope() { return isolationScope; }
            public void setIsolationScope(String isolationScope) { this.isolationScope = isolationScope; }
            public String getSubagentIsolationScope() { return subagentIsolationScope; }
            public void setSubagentIsolationScope(String subagentIsolationScope) { this.subagentIsolationScope = subagentIsolationScope; }
            public boolean isMountSkills() { return mountSkills; }
            public void setMountSkills(boolean mountSkills) { this.mountSkills = mountSkills; }
            public boolean isMountMemory() { return mountMemory; }
            public void setMountMemory(boolean mountMemory) { this.mountMemory = mountMemory; }
            public boolean isMountArtifacts() { return mountArtifacts; }
            public void setMountArtifacts(boolean mountArtifacts) { this.mountArtifacts = mountArtifacts; }
            public String getSharedContainerName() { return sharedContainerName; }
            public void setSharedContainerName(String sharedContainerName) { this.sharedContainerName = sharedContainerName == null ? "" : sharedContainerName.trim(); }
            public boolean isRemoteDockerEnabled() { return remoteDockerEnabled; }
            public void setRemoteDockerEnabled(boolean remoteDockerEnabled) { this.remoteDockerEnabled = remoteDockerEnabled; }
            public String getRemoteDockerSshTarget() { return remoteDockerSshTarget; }
            public void setRemoteDockerSshTarget(String remoteDockerSshTarget) { this.remoteDockerSshTarget = remoteDockerSshTarget == null ? "" : remoteDockerSshTarget.trim(); }
            public java.util.List<String> getRemoteDockerSshOptions() { return remoteDockerSshOptions; }
            public void setRemoteDockerSshOptions(java.util.List<String> remoteDockerSshOptions) { this.remoteDockerSshOptions = remoteDockerSshOptions == null ? java.util.List.of() : remoteDockerSshOptions; }
            public long getRemoteDockerTimeoutSeconds() { return remoteDockerTimeoutSeconds; }
            public void setRemoteDockerTimeoutSeconds(long remoteDockerTimeoutSeconds) { this.remoteDockerTimeoutSeconds = remoteDockerTimeoutSeconds; }
        }

        public static class Distributed {
            private boolean enabled = false;
            private String isolationScope = "USER";

            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }
            public String getIsolationScope() { return isolationScope; }
            public void setIsolationScope(String isolationScope) { this.isolationScope = isolationScope; }
        }

        public static class Artifacts {
            private Remote remote = new Remote();
            public Remote getRemote() { return remote; }
            public void setRemote(Remote remote) { this.remote = remote; }

            public static class Remote {
                private boolean enabled = false;
                private String sshTarget = "";
                private String remoteRoot = "";
                private java.util.List<String> sshOptions = new java.util.ArrayList<>();
                private long timeoutSeconds = 30;

                public boolean isEnabled() { return enabled; }
                public void setEnabled(boolean enabled) { this.enabled = enabled; }
                public String getSshTarget() { return sshTarget; }
                public void setSshTarget(String sshTarget) { this.sshTarget = sshTarget; }
                public String getRemoteRoot() { return remoteRoot; }
                public void setRemoteRoot(String remoteRoot) { this.remoteRoot = remoteRoot; }
                public java.util.List<String> getSshOptions() { return sshOptions; }
                public void setSshOptions(java.util.List<String> sshOptions) { this.sshOptions = sshOptions; }
                public long getTimeoutSeconds() { return timeoutSeconds; }
                public void setTimeoutSeconds(long timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
            }
        }

        public static class Skills {
            private Remote remote = new Remote();
            public Remote getRemote() { return remote; }
            public void setRemote(Remote remote) { this.remote = remote; }

            public static class Remote {
                private boolean enabled = false;
                private String sshTarget = "";
                private String remoteRoot = "";
                private java.util.List<String> sshOptions = new java.util.ArrayList<>();
                private long timeoutSeconds = 30;

                public boolean isEnabled() { return enabled; }
                public void setEnabled(boolean enabled) { this.enabled = enabled; }
                public String getSshTarget() { return sshTarget; }
                public void setSshTarget(String sshTarget) { this.sshTarget = sshTarget; }
                public String getRemoteRoot() { return remoteRoot; }
                public void setRemoteRoot(String remoteRoot) { this.remoteRoot = remoteRoot; }
                public java.util.List<String> getSshOptions() { return sshOptions; }
                public void setSshOptions(java.util.List<String> sshOptions) { this.sshOptions = sshOptions; }
                public long getTimeoutSeconds() { return timeoutSeconds; }
                public void setTimeoutSeconds(long timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
            }
        }

        public static class Memory {
            private Remote remote = new Remote();
            public Remote getRemote() { return remote; }
            public void setRemote(Remote remote) { this.remote = remote; }

            public static class Remote {
                private boolean enabled = false;
                private String sshTarget = "";
                private String remoteRoot = "";
                private java.util.List<String> sshOptions = new java.util.ArrayList<>();
                private long timeoutSeconds = 30;

                public boolean isEnabled() { return enabled; }
                public void setEnabled(boolean enabled) { this.enabled = enabled; }
                public String getSshTarget() { return sshTarget; }
                public void setSshTarget(String sshTarget) { this.sshTarget = sshTarget; }
                public String getRemoteRoot() { return remoteRoot; }
                public void setRemoteRoot(String remoteRoot) { this.remoteRoot = remoteRoot; }
                public java.util.List<String> getSshOptions() { return sshOptions; }
                public void setSshOptions(java.util.List<String> sshOptions) { this.sshOptions = sshOptions; }
                public long getTimeoutSeconds() { return timeoutSeconds; }
                public void setTimeoutSeconds(long timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
            }
        }
    }

    /**
     * Distributed mode properties. Mirrors v1 {@code SandboxProperties.Distributed}
     * with only the fields relevant to distributed store (Redis config is not needed
     * for MySQL-backed distributed mode).
     */
    public static class DistributedPropertiesV2 {
        private boolean enabled = false;
        private String isolationScope = "USER";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getIsolationScope() { return isolationScope; }
        public void setIsolationScope(String isolationScope) { this.isolationScope = isolationScope; }
    }
}