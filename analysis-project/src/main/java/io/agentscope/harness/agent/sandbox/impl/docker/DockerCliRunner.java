/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package io.agentscope.harness.agent.sandbox.impl.docker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class DockerCliRunner {

    private static final Logger log = LoggerFactory.getLogger(DockerCliRunner.class);
    private static volatile Config config = new Config(false, "", List.of(), 60);

    private DockerCliRunner() {}

    public static void configure(
            boolean remoteEnabled,
            String sshTarget,
            List<String> sshOptions,
            long timeoutSeconds) {
        config =
                new Config(
                        remoteEnabled,
                        sshTarget == null ? "" : sshTarget.trim(),
                        sshOptions == null ? List.of() : List.copyOf(sshOptions),
                        timeoutSeconds > 0 ? timeoutSeconds : 60);
    }

    public static boolean isRemoteEnabled() {
        return config.remoteEnabled;
    }

    public static String describeMode() {
        Config c = config;
        if (!c.remoteEnabled) {
            return "local docker CLI";
        }
        return "remote docker over ssh(" + c.sshTarget + ")";
    }

    static Process start(String... dockerArgs) throws IOException {
        return start(Arrays.asList(dockerArgs));
    }

    static Process start(List<String> dockerArgs) throws IOException {
        return new ProcessBuilder(command(dockerArgs)).start();
    }

    static CommandResult run(int timeoutSeconds, String... dockerArgs) throws IOException {
        return run(timeoutSeconds, Arrays.asList(dockerArgs));
    }

    static CommandResult run(int timeoutSeconds, List<String> dockerArgs) throws IOException {
        Process p = start(dockerArgs);
        p.getOutputStream().close();
        ExecutorService executor = Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "docker-cli-runner");
            thread.setDaemon(true);
            return thread;
        });
        Future<String> stdout = executor.submit(() -> readAll(p.getInputStream()));
        Future<String> stderr = executor.submit(() -> readAll(p.getErrorStream()));
        executor.shutdown();
        boolean exited;
        try {
            exited = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!exited) {
                p.destroyForcibly();
                executor.shutdownNow();
                throw new IOException("docker command timed out after " + timeoutSeconds + "s");
            }
            return new CommandResult(p.exitValue(), stdout.get(), stderr.get());
        } catch (InterruptedException ie) {
            p.destroyForcibly();
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while running docker command", ie);
        } catch (Exception e) {
            p.destroyForcibly();
            executor.shutdownNow();
            if (e instanceof IOException ioe) {
                throw ioe;
            }
            throw new IOException("Failed to read docker command output", e);
        }
    }

    static long defaultTimeoutSeconds() {
        return config.timeoutSeconds;
    }

    private static String readAll(java.io.InputStream input) throws IOException {
        return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static List<String> command(List<String> dockerArgs) {
        Config c = config;
        if (!c.remoteEnabled) {
            List<String> argv = new ArrayList<>(dockerArgs.size() + 1);
            argv.add("docker");
            argv.addAll(stripDockerPrefix(dockerArgs));
            return argv;
        }
        if (c.sshTarget.isBlank()) {
            throw new IllegalStateException("remote docker is enabled but ssh target is blank");
        }
        List<String> argv = new ArrayList<>();
        argv.add("ssh");
        argv.add("-o");
        argv.add("BatchMode=yes");
        argv.addAll(c.sshOptions);
        argv.add(c.sshTarget);
        argv.add(remoteDockerCommand(stripDockerPrefix(dockerArgs)));
        return argv;
    }

    private static List<String> stripDockerPrefix(List<String> dockerArgs) {
        if (!dockerArgs.isEmpty() && "docker".equals(dockerArgs.get(0))) {
            return dockerArgs.subList(1, dockerArgs.size());
        }
        return dockerArgs;
    }

    private static String remoteDockerCommand(List<String> dockerArgs) {
        StringBuilder sb = new StringBuilder("docker");
        for (String arg : dockerArgs) {
            sb.append(' ').append(sh(arg));
        }
        return sb.toString();
    }

    private static String sh(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    record CommandResult(int exitCode, String stdout, String stderr) {}

    private record Config(
            boolean remoteEnabled, String sshTarget, List<String> sshOptions, long timeoutSeconds) {}
}
