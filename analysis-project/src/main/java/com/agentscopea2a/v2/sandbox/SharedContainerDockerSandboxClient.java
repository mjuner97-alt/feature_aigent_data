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
package com.agentscopea2a.v2.sandbox;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandbox;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClientOptions;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxState;
import io.agentscope.harness.agent.sandbox.json.HarnessSandboxJacksonModule;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extended {@code DockerSandboxClient} that adds shared-container mode and fixes upstream
 * deserialization bugs.
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
 *   <li>The serialized JSON includes implementation fields not declared on the
 *       {@code SandboxSnapshot} interface. On the way back the deserializer trips on these as
 *       unknown properties. Fix: configure the ObjectMapper with
 *       {@code FAIL_ON_UNKNOWN_PROPERTIES=false} so harness library version drift can't break
 *       the round-trip.
 * </ol>
 *
 * <p>Shared-container mode: when {@link #setSharedContainerName} has been called with a non-blank
 * value, every {@link #create} resolves the daemon-side container by name into a
 * {@code containerId}, sets {@code containerOwned=false} (so {@link DockerSandbox#shutdown} skips
 * stop/rm), and returns. Combined with {@code IsolationScope.GLOBAL} this collapses the entire
 * fleet onto one long-lived container.
 *
 * <p>This class extends the v2 {@code DockerSandboxClient} rather than shadowing it, so it
 * coexists cleanly with the JAR version.
 */
public class SharedContainerDockerSandboxClient
        implements SandboxClient<DockerSandboxClientOptions> {

    private static final Logger log =
            LoggerFactory.getLogger(SharedContainerDockerSandboxClient.class);

    /**
     * Static config slot for the shared-container name. Set once at boot from
     * {@code V2SandboxConfig} when {@code sandbox.shared-container-name} is configured.
     */
    private static volatile String sharedContainerName = "";

    public static void setSharedContainerName(String name) {
        sharedContainerName = name == null ? "" : name.trim();
    }

    public static String getSharedContainerName() {
        return sharedContainerName;
    }

    private final ObjectMapper objectMapper;

    public SharedContainerDockerSandboxClient() {
        this.objectMapper =
                new ObjectMapper()
                        .findAndRegisterModules()
                        .registerModule(new HarnessSandboxJacksonModule())
                        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public SharedContainerDockerSandboxClient(ObjectMapper objectMapper) {
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
            log.warn(
                    "[sandbox-docker] sharedContainerName='{}' did not resolve via 'docker"
                            + " inspect'; falling back to per-request create",
                    shared);
        }

        state.setContainerOwned(true);
        log.debug("[sandbox-docker] Creating new sandbox: id={}, image={}", sessionId, image);
        return new DockerSandbox(state);
    }

    @Override
    public Sandbox resume(SandboxState state) {
        if (!(state instanceof DockerSandboxState dockerState)) {
            throw new IllegalArgumentException(
                    "Expected DockerSandboxState, got " + state.getClass().getName());
        }
        // Shared-container mode: persisted containerId may be stale. Re-resolve from daemon.
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
            throw new io.agentscope.harness.agent.sandbox.SandboxException.SandboxConfigurationException(
                    "Failed to serialize Docker sandbox state", e);
        }
    }

    @Override
    public SandboxState deserializeState(String json) {
        try {
            // Fix: deserialize to DockerSandboxState (concrete) instead of SandboxState (abstract)
            return objectMapper.readValue(json, DockerSandboxState.class);
        } catch (Exception e) {
            throw new io.agentscope.harness.agent.sandbox.SandboxException.SandboxConfigurationException(
                    "Failed to deserialize Docker sandbox state", e);
        }
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
}