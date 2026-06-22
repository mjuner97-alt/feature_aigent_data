/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package io.agentscope.harness.agent.sandbox.impl.docker;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.json.HarnessSandboxJacksonModule;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Local override of upstream {@code DockerSandboxClient} that fixes USER-scope container reuse
 * AND adds shared-single-container support for the global-scope deployment.
 *
 * <p>Two upstream bugs both showed up as {@code Failed to load persisted state for scope ...
 * — Failed to deserialize Docker sandbox state}, which silently fell back to creating a fresh
 * container per request, defeating {@code IsolationScope.USER}:
 *
 * <ol>
 *   <li>Upstream calls {@code objectMapper.readValue(json, SandboxState.class)} where
 *       {@link SandboxState} is {@code abstract}. The JSON was produced from a concrete
 *       {@link DockerSandboxState} so Jackson cannot instantiate the base. Fix: deserialize to
 *       {@link DockerSandboxState} directly, then return as the base type.
 *   <li>The serialized JSON includes {@code NoopSandboxSnapshot.id} (and possibly other
 *       implementation fields not declared on the {@code SandboxSnapshot} interface). On the
 *       way back the abstract-base deserializer trips on these as unknown properties. Fix:
 *       configure the ObjectMapper with {@code FAIL_ON_UNKNOWN_PROPERTIES=false} so harness
 *       library version drift can't break the round-trip.
 * </ol>
 *
 * <p>Shared-container mode (added 2026-06-13): when {@link #setSharedContainerName} has been
 * called with a non-blank value, every {@link #create} resolves the daemon-side container by
 * name into a {@code containerId}, sets {@code containerOwned=false} (so any shutdown is a
 * no-op), and returns. {@code DockerSandbox.doEnsureContainerRunning} then sees the container
 * is RUNNING and skips {@code docker run} entirely. Combined with {@code IsolationScope.GLOBAL}
 * this collapses the entire fleet onto one long-lived container.
 *
 * <p>Lives at {@code src/main/java/...} so {@code target/classes} on the classpath shadows the
 * version inside {@code agentscope-harness-1.1.0-RC1.jar}. If we ever bump the harness version,
 * diff this file against the upstream version of {@code DockerSandboxClient}; the only changes
 * vs. upstream are the {@code FAIL_ON_UNKNOWN_PROPERTIES} disable, the deserialize target
 * class, and the shared-container branch.
 */
public class DockerSandboxClient implements SandboxClient<DockerSandboxClientOptions> {

    private static final Logger log = LoggerFactory.getLogger(DockerSandboxClient.class);

    /**
     * Static config slot for the shared-container name. Set once at boot from
     * {@code FilesystemConfig} when {@code sandbox.shared-container-name} is configured. Static
     * because {@link DockerSandboxClient} is constructed reflectively by the harness via
     * {@code DockerSandboxClientOptions.createClient()} — there is no constructor seam to inject
     * Spring properties. Reading the field is volatile-cheap; writing happens once during
     * Spring context init before any request-handling thread reads it.
     */
    private static volatile String sharedContainerName = "";

    public static void setSharedContainerName(String name) {
        sharedContainerName = name == null ? "" : name.trim();
    }

    public static String getSharedContainerName() {
        return sharedContainerName;
    }

    private final ObjectMapper objectMapper;

    public DockerSandboxClient() {
        this.objectMapper =
                new ObjectMapper()
                        .findAndRegisterModules()
                        .registerModule(new HarnessSandboxJacksonModule())
                        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public DockerSandboxClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Sandbox create(
            WorkspaceSpec workspaceSpec,
            SandboxSnapshotSpec snapshotSpec,
            DockerSandboxClientOptions options) {
        String sessionId = UUID.randomUUID().toString();
        String image =
                options != null && options.getImage() != null ? options.getImage() : "ubuntu:22.04";
        String workspaceRoot =
                options != null && options.getWorkspaceRoot() != null
                        ? options.getWorkspaceRoot()
                        : "/workspace";

        DockerSandboxState state = new DockerSandboxState();
        state.setSessionId(sessionId);
        state.setWorkspaceSpec(workspaceSpec);
        state.setImage(image);
        state.setWorkspaceRoot(workspaceRoot);
        state.setWorkspaceRootReady(false);
        if (options != null) {
            state.setMemorySizeBytes(options.getMemorySizeBytes());
            state.setCpuCount(options.getCpuCount());
            state.setExposedPorts(options.getExposedPorts());
            state.setNetwork(options.getNetwork());
            state.setAdditionalRunArgs(options.getAdditionalRunArgs());
        }
        if (snapshotSpec != null) {
            state.setSnapshot(snapshotSpec.build(sessionId));
        }

        // Shared-container mode: short-circuit to the pre-created daemon-side container.
        // - containerOwned=false → DockerSandbox.shutdown skips stop/rm
        // - containerName + containerId pre-set → doEnsureContainerRunning sees RUNNING and
        //   never invokes `docker run`
        String shared = sharedContainerName;
        if (shared != null && !shared.isEmpty()) {
            String resolvedId = resolveContainerId(shared);
            if (resolvedId != null && !resolvedId.isBlank()) {
                state.setContainerName(shared);
                state.setContainerId(resolvedId);
                state.setContainerOwned(false);
                state.setWorkspaceRootReady(true);
                log.info(
                        "[sandbox-docker] Attaching to shared container: name={} id={}",
                        shared,
                        resolvedId);
                return new DockerSandbox(state);
            }
            // Couldn't resolve — fall through to normal create. This keeps a misconfigured
            // shared container from bricking the demo; we just lose the cold-start saving.
            log.warn(
                    "[sandbox-docker] sharedContainerName='{}' did not resolve via 'docker"
                            + " inspect'; falling back to per-request create",
                    shared);
        }

        state.setContainerOwned(true);
        log.debug("[sandbox-docker] Creating new sandbox: id={}, image={}", sessionId, image);
        return new DockerSandbox(state);
    }

    private static String resolveContainerId(String name) {
        try {
            DockerCliRunner.CommandResult result =
                    DockerCliRunner.run(15, "inspect", "-f", "{{.Id}}", name);
            if (result.exitCode() != 0) {
                return null;
            }
            String out = result.stdout().trim();
            return out.isBlank() ? null : out;
        } catch (Exception e) {
            log.warn(
                    "[sandbox-docker] docker inspect '{}' failed via {}: {}",
                    name,
                    DockerCliRunner.describeMode(),
                    e.getMessage());
            return null;
        }
    }

    @Override
    public Sandbox resume(SandboxState state) {
        if (!(state instanceof DockerSandboxState dockerState)) {
            throw new IllegalArgumentException(
                    "Expected DockerSandboxState, got " + state.getClass().getName());
        }
        // Shared-container mode: persisted containerId may be stale (the operator could have
        // recreated the named container between JVM runs). Re-resolve from the daemon every
        // resume so we never carry a dangling id into doEnsureContainerRunning's inspect path.
        String shared = sharedContainerName;
        if (shared != null && !shared.isEmpty()) {
            String resolvedId = resolveContainerId(shared);
            if (resolvedId != null && !resolvedId.isBlank()) {
                if (!resolvedId.equals(dockerState.getContainerId())) {
                    log.info(
                            "[sandbox-docker] Resume: refreshing stale containerId for shared"
                                    + " container '{}': {} -> {}",
                            shared,
                            dockerState.getContainerId(),
                            resolvedId);
                    dockerState.setContainerId(resolvedId);
                }
                dockerState.setContainerName(shared);
                dockerState.setContainerOwned(false);
                dockerState.setWorkspaceRootReady(true);
            }
        }
        log.debug(
                "[sandbox-docker] Resuming sandbox: id={}, containerId={}",
                dockerState.getSessionId(),
                dockerState.getContainerId());
        return new DockerSandbox(dockerState);
    }

    @Override
    public void delete(Sandbox sandbox) {
        // upstream is empty too; container lifecycle handled via Sandbox.shutdown()
    }

    @Override
    public String serializeState(SandboxState state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (Exception e) {
            throw new SandboxException.SandboxConfigurationException(
                    "Failed to serialize Docker sandbox state", e);
        }
    }

    @Override
    public SandboxState deserializeState(String json) {
        try {
            return objectMapper.readValue(json, DockerSandboxState.class);
        } catch (Exception e) {
            throw new SandboxException.SandboxConfigurationException(
                    "Failed to deserialize Docker sandbox state", e);
        }
    }
}
