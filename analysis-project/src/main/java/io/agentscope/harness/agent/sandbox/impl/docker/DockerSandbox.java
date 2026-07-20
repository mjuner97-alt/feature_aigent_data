/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package io.agentscope.harness.agent.sandbox.impl.docker;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.sandbox.*;
import io.agentscope.harness.agent.sandbox.layout.BindMountEntry;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class DockerSandbox extends AbstractBaseSandbox {

    private static final Logger log = LoggerFactory.getLogger(DockerSandbox.class);
    private static final int OUTPUT_TRUNCATE_BYTES = 524288;
    private static final int ERROR_TRUNCATE_BYTES = 65536;
    private static final int CONTAINER_START_TIMEOUT_SECONDS = 60;
    private static final int CONTAINER_STOP_TIMEOUT_SECONDS = 60;
    private static final int TAR_TIMEOUT_SECONDS = 120;

    private final DockerSandboxState dockerState;

    public DockerSandbox(DockerSandboxState dockerState) {
        super(dockerState);
        this.dockerState = dockerState;
    }

    @Override
    public void start() throws Exception {
        doEnsureContainerRunning();
        super.start();
    }

    @Override
    public void shutdown() throws Exception {
        String containerId = dockerState.getContainerId();
        if (containerId == null || containerId.isBlank()) {
            return;
        }
        if (!dockerState.isContainerOwned()) {
            log.debug("[sandbox-docker] Skipping shutdown: container is user-managed: {}", containerId);
            return;
        }
        try {
            runDockerCliBlocking(CONTAINER_STOP_TIMEOUT_SECONDS, "stop", "--time=30", containerId);
            log.debug("[sandbox-docker] Container stopped: {}", containerId);
        } catch (Exception e) {
            log.warn("[sandbox-docker] Failed to stop container {}: {}", containerId, e.getMessage());
        }
        try {
            runDockerCliBlocking(30, "rm", "--force", containerId);
            log.debug("[sandbox-docker] Container removed: {}", containerId);
        } catch (Exception e) {
            log.warn("[sandbox-docker] Failed to remove container {}: {}", containerId, e.getMessage());
        }
    }

    @Override
    protected ExecResult doExec(RuntimeContext runtimeContext, String command, int timeoutSeconds)
            throws Exception {
        List<String> args =
                List.of(
                        "exec",
                        "-w",
                        dockerState.getWorkspaceRoot(),
                        dockerState.getContainerId(),
                        "sh",
                        "-c",
                        command);
        Process p = DockerCliRunner.start(args);
        p.getOutputStream().close();
        ExecutorService executor = Executors.newFixedThreadPool(2, daemonFactory("sandbox-docker-exec"));
        Future<String> stdout = executor.submit(() -> readStream(p.getInputStream(), OUTPUT_TRUNCATE_BYTES));
        Future<String> stderr = executor.submit(() -> readStream(p.getErrorStream(), OUTPUT_TRUNCATE_BYTES));
        executor.shutdown();
        boolean done = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!done) {
            p.destroyForcibly();
            executor.shutdownNow();
            throw new SandboxException.ExecTimeoutException(command, timeoutSeconds);
        }
        String out = stdout.get();
        String err = stderr.get();
        int exitCode = p.exitValue();
        boolean truncated =
                out.length() >= OUTPUT_TRUNCATE_BYTES || err.length() >= OUTPUT_TRUNCATE_BYTES;
        ExecResult result = new ExecResult(exitCode, out, err, truncated);
        if (!result.ok()) {
            throw new SandboxException.ExecException(exitCode, out, err);
        }
        return result;
    }

    @Override
    protected InputStream doPersistWorkspace() throws Exception {
        List<String> args = new ArrayList<>();
        args.add("exec");
        args.add(dockerState.getContainerId());
        args.add("tar");
        args.addAll(WorkspaceMountSupport.tarExcludeArgsForBindMounts(getState().getWorkspaceSpec()));
        args.add("-cf");
        args.add("-");
        args.add("-C");
        args.add(dockerState.getWorkspaceRoot());
        args.add(".");
        Process p = DockerCliRunner.start(args);
        p.getOutputStream().close();
        ExecutorService executor = Executors.newSingleThreadExecutor(daemonFactory("sandbox-docker-tar"));
        Future<String> stderr = executor.submit(() -> readStream(p.getErrorStream(), OUTPUT_TRUNCATE_BYTES));
        executor.shutdown();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        p.getInputStream().transferTo(out);
        boolean done = p.waitFor(TAR_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!done) {
            p.destroyForcibly();
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_ARCHIVE_WRITE_ERROR,
                    "Timed out while persisting workspace from container " + dockerState.getContainerId());
        }
        int exitCode = p.exitValue();
        if (exitCode != 0) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_ARCHIVE_WRITE_ERROR,
                    "docker tar failed with exit code " + exitCode + ": " + stderr.get());
        }
        return new ByteArrayInputStream(out.toByteArray());
    }

    @Override
    protected void doHydrateWorkspace(InputStream inputStream) throws Exception {
        runDockerCliBlocking(30, "exec", dockerState.getContainerId(), "mkdir", "-p", dockerState.getWorkspaceRoot());
        List<String> args =
                List.of(
                        "exec",
                        "-i",
                        dockerState.getContainerId(),
                        "tar",
                        "-xf",
                        "-",
                        "-C",
                        dockerState.getWorkspaceRoot());
        Process p = DockerCliRunner.start(args);
        ExecutorService executor = Executors.newFixedThreadPool(2, daemonFactory("sandbox-docker-hydrate"));
        Future<?> stdin =
                executor.submit(
                        () -> {
                            try (var processIn = p.getOutputStream()) {
                                inputStream.transferTo(processIn);
                            }
                            return null;
                        });
        Future<String> stderr = executor.submit(() -> readStream(p.getErrorStream(), OUTPUT_TRUNCATE_BYTES));
        executor.shutdown();
        stdin.get(TAR_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        boolean done = p.waitFor(TAR_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!done) {
            p.destroyForcibly();
            executor.shutdownNow();
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_ARCHIVE_READ_ERROR,
                    "Timed out while hydrating workspace into container " + dockerState.getContainerId());
        }
        int exitCode = p.exitValue();
        if (exitCode != 0) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_ARCHIVE_READ_ERROR,
                    "docker tar extract failed with exit code " + exitCode + ": " + stderr.get());
        }
    }

    @Override
    protected void doSetupWorkspace() throws Exception {
        runDockerCliBlocking(30, "exec", dockerState.getContainerId(), "mkdir", "-p", dockerState.getWorkspaceRoot());
    }

    @Override
    protected void doDestroyWorkspace() throws Exception {
        String containerId = dockerState.getContainerId();
        String workspaceRoot = dockerState.getWorkspaceRoot();
        if (containerId == null || containerId.isBlank()) {
            return;
        }
        try {
            runDockerCliBlocking(30, "exec", containerId, "rm", "-rf", workspaceRoot);
        } catch (Exception e) {
            log.warn(
                    "[sandbox-docker] Failed to destroy workspace {} in container {}: {}",
                    workspaceRoot,
                    containerId,
                    e.getMessage());
        }
    }

    @Override
    protected String getWorkspaceRoot() {
        return dockerState.getWorkspaceRoot();
    }

    private void doEnsureContainerRunning() throws Exception {
        String containerId = dockerState.getContainerId();
        if (containerId != null && !containerId.isBlank()) {
            ContainerState state = inspectContainerState(containerId);
            if (state == ContainerState.RUNNING) {
                log.debug("[sandbox-docker] Container already running: {}", containerId);
                return;
            }
            if (state == ContainerState.STOPPED) {
                log.debug("[sandbox-docker] Restarting stopped container: {}", containerId);
                runDockerCliBlocking(CONTAINER_START_TIMEOUT_SECONDS, "start", containerId);
                return;
            }
            log.warn("[sandbox-docker] Container {} not found, creating a new one", containerId);
            dockerState.setWorkspaceRootReady(false);
        }
        createAndStartContainer();
    }

    private void createAndStartContainer() throws Exception {
        String containerName = "sandbox-" + dockerState.getSessionId();
        dockerState.setContainerName(containerName);
        List<String> args = buildDockerRunCommand(containerName);
        log.debug(
                "[sandbox-docker] Creating container: image={}, name={}",
                dockerState.getImage(),
                containerName);
        Process p = DockerCliRunner.start(args);
        p.getOutputStream().close();
        ExecutorService executor =
                Executors.newFixedThreadPool(2, daemonFactory("sandbox-docker-create"));
        Future<String> stdout = executor.submit(() -> readStream(p.getInputStream(), ERROR_TRUNCATE_BYTES));
        Future<String> stderr = executor.submit(() -> readStream(p.getErrorStream(), ERROR_TRUNCATE_BYTES));
        executor.shutdown();
        boolean done = p.waitFor(CONTAINER_START_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!done) {
            p.destroyForcibly();
            executor.shutdownNow();
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_START_ERROR,
                    "Timed out while starting docker image " + dockerState.getImage());
        }
        int exitCode = p.exitValue();
        String out = stdout.get().trim();
        String err = stderr.get().trim();
        if (exitCode != 0) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_START_ERROR,
                    "docker run failed with exit code " + exitCode + ": " + err);
        }
        if (out.isBlank()) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_START_ERROR,
                    "docker run did not return a container id: " + err);
        }
        dockerState.setContainerId(out);
        log.info("[sandbox-docker] Container started: id={}, name={}", out, containerName);
    }

    private List<String> buildDockerRunCommand(String containerName) {
        List<String> args = new ArrayList<>();
        args.add("run");
        args.add("-d");
        args.add("--name");
        args.add(containerName);
        Map<String, String> env =
                getState().getWorkspaceSpec() == null
                        ? null
                        : getState().getWorkspaceSpec().getEnvironment();
        if (env != null) {
            for (Map.Entry<String, String> entry : env.entrySet()) {
                args.add("-e");
                args.add(entry.getKey() + "=" + entry.getValue());
            }
        }
        if (dockerState.getMemorySizeBytes() != null) {
            args.add("--memory=" + dockerState.getMemorySizeBytes());
        }
        if (dockerState.getCpuCount() != null) {
            args.add("--cpus=" + dockerState.getCpuCount());
        }
        if (dockerState.getExposedPorts() != null) {
            for (int port : dockerState.getExposedPorts()) {
                args.add("-p");
                args.add(port + ":" + port);
            }
        }
        String network = dockerState.getNetwork();
        args.add("--network=" + (network == null || network.isBlank() ? "none" : network));
        if (dockerState.getAdditionalRunArgs() != null) {
            args.addAll(dockerState.getAdditionalRunArgs());
        }
        if (getState().getWorkspaceSpec() != null) {
            for (Map.Entry<String, WorkspaceEntry> entry :
                    getState().getWorkspaceSpec().getEntries().entrySet()) {
                if (entry.getValue() instanceof BindMountEntry bindMount) {
                    String hostPath = WorkspaceMountSupport.normalizedHostPath(bindMount.getHostPath());
                    if (hostPath.isEmpty()) {
                        log.warn("[sandbox-docker] Skipping bind mount at key {}: blank hostPath", entry.getKey());
                        continue;
                    }
                    String containerPath =
                            WorkspaceMountSupport.containerMountPath(
                                    dockerState.getWorkspaceRoot(), entry.getKey());
                    args.add("-v");
                    args.add(hostPath + ":" + containerPath + ":" + (bindMount.isReadOnly() ? "ro" : "rw"));
                }
            }
        }
        args.add(dockerState.getImage());
        args.add("sh");
        args.add("-c");
        args.add("while :; do sleep 3600; done");
        return args;
    }

    private ContainerState inspectContainerState(String containerId) {
        try {
            DockerCliRunner.CommandResult result =
                    DockerCliRunner.run(10, "inspect", "-f", "{{.State.Running}}", containerId);
            if (result.exitCode() != 0) {
                return ContainerState.UNKNOWN;
            }
            return "true".equals(result.stdout().trim()) ? ContainerState.RUNNING : ContainerState.STOPPED;
        } catch (Exception e) {
            log.debug(
                    "[sandbox-docker] Failed to inspect container {} via {}: {}",
                    containerId,
                    DockerCliRunner.describeMode(),
                    e.getMessage());
            return ContainerState.UNKNOWN;
        }
    }

    private void runDockerCliBlocking(int timeoutSeconds, String... args) throws Exception {
        DockerCliRunner.CommandResult result = DockerCliRunner.run(timeoutSeconds, args);
        if (result.exitCode() != 0) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_START_ERROR,
                    "docker command failed with exit code "
                            + result.exitCode()
                            + ": "
                            + result.stderr());
        }
    }

    private static String readStream(InputStream input, int maxBytes) {
        byte[] buffer = new byte[maxBytes];
        int offset = 0;
        try {
            while (offset < maxBytes) {
                int read = input.read(buffer, offset, maxBytes - offset);
                if (read == -1) {
                    break;
                }
                offset += read;
            }
            if (offset == maxBytes) {
                input.skip(Long.MAX_VALUE);
            }
        } catch (IOException e) {
            return "";
        }
        return new String(buffer, 0, offset, StandardCharsets.UTF_8);
    }

    private static java.util.concurrent.ThreadFactory daemonFactory(String name) {
        return task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    private enum ContainerState {
        RUNNING,
        STOPPED,
        UNKNOWN
    }
}
