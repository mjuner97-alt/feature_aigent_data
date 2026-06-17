/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.agentscopea2a.harness.workspace;

import com.agentscopea2a.harness.config.SandboxProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.ConfigurationCondition;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Bidirectionally mirrors {@code <workspace>/skills} and {@code <workspace>/memory} between the
 * local JVM host and a remote Linux machine reachable over SSH. Only enabled when sandbox is on
 * AND at least one of {@code harness.a2a.skills.remote.enabled} /
 * {@code harness.a2a.memory.remote.enabled} is true.
 *
 * <p>Lifecycle: initial reconciliation at startup, periodic ({@code @Scheduled}, default 20s),
 * and a final flush at shutdown.
 */
@Service
@Conditional(RemoteWorkspaceSyncService.EnabledCondition.class)
public class RemoteWorkspaceSyncService {

    private static final Logger log = LoggerFactory.getLogger(RemoteWorkspaceSyncService.class);

    private final List<RemoteDirSyncer> syncers;

    @Autowired
    public RemoteWorkspaceSyncService(SandboxProperties props, Path workspace) {
        this.syncers = buildSyncers(props, workspace);
    }

    private static List<RemoteDirSyncer> buildSyncers(SandboxProperties props, Path workspace) {
        List<RemoteDirSyncer> out = new ArrayList<>();
        SandboxProperties.Skills.Remote skills = props.getSkills().getRemote();
        if (skills.isEnabled()) {
            requireFields("skills", skills.getSshTarget(), skills.getRemoteRoot());
            out.add(
                    new RemoteDirSyncer(
                            skills.getSshTarget(),
                            workspace.resolve("skills"),
                            skills.getRemoteRoot(),
                            skills.getSshOptions(),
                            skills.getTimeoutSeconds()));
        }
        SandboxProperties.Memory.Remote memory = props.getMemory().getRemote();
        if (memory.isEnabled()) {
            requireFields("memory", memory.getSshTarget(), memory.getRemoteRoot());
            out.add(
                    new RemoteDirSyncer(
                            memory.getSshTarget(),
                            workspace.resolve("memory"),
                            memory.getRemoteRoot(),
                            memory.getSshOptions(),
                            memory.getTimeoutSeconds()));
        }
        return out;
    }

    private static void requireFields(String label, String sshTarget, String remoteRoot) {
        if (sshTarget == null || sshTarget.isBlank() || remoteRoot == null || remoteRoot.isBlank()) {
            throw new IllegalStateException(
                    "harness.a2a." + label + ".remote.enabled=true requires sshTarget and "
                            + "remoteRoot to be set.");
        }
    }

    @PostConstruct
    public void initialPull() {
        for (RemoteDirSyncer s : syncers) {
            s.syncBoth();
        }
        log.info("RemoteWorkspaceSyncService: initial sync complete ({} dir(s))", syncers.size());
    }

    @Scheduled(fixedDelayString = "${harness.a2a.workspace.sync.intervalMs:20000}")
    public void periodicSync() {
        for (RemoteDirSyncer s : syncers) {
            s.syncBoth();
        }
    }

    @PreDestroy
    public void finalFlush() {
        for (RemoteDirSyncer s : syncers) {
            s.syncBoth();
        }
        log.info("RemoteWorkspaceSyncService: shutdown sync complete");
    }

    /**
     * Bean only when sandbox is on AND (skills.remote OR memory.remote) is on. Programmatic
     * Condition because {@code @ConditionalOnProperty} can't OR two flags.
     */
    public static final class EnabledCondition implements ConfigurationCondition {
        @Override
        public ConfigurationPhase getConfigurationPhase() {
            return ConfigurationPhase.REGISTER_BEAN;
        }

        @Override
        public boolean matches(
                org.springframework.context.annotation.ConditionContext context,
                AnnotatedTypeMetadata metadata) {
            var env = context.getEnvironment();
            boolean sandbox = env.getProperty("harness.a2a.sandbox.enabled", Boolean.class, false);
            if (!sandbox) {
                return false;
            }
            boolean skills =
                    env.getProperty("harness.a2a.skills.remote.enabled", Boolean.class, false);
            boolean memory =
                    env.getProperty("harness.a2a.memory.remote.enabled", Boolean.class, false);
            return skills || memory;
        }
    }
}
