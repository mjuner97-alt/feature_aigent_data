/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.agentscopea2a.harness.workspace;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Two-way directory synchroniser between a local path on the JVM host and a remote path on a
 * Linux machine reachable over SSH. Used when DOCKER_HOST=ssh://... so subagent skill/memory
 * writes that land on the remote disk are mirrored back to the host JVM and vice versa.
 *
 * <p>Conflict policy: last-writer-wins by mtime, with a 1-second tolerance to absorb
 * filesystem-clock drift. Atomic via {@code <target>.tmp.<pid>} + rename.
 */
public final class RemoteDirSyncer {

    private static final Logger log = LoggerFactory.getLogger(RemoteDirSyncer.class);

    private static final long MTIME_TOLERANCE_SECONDS = 1;

    private final String sshTarget;
    private final Path localRoot;
    private final String remoteRoot;
    private final List<String> sshOptions;
    private final long timeoutSeconds;

    public RemoteDirSyncer(
            String sshTarget,
            Path localRoot,
            String remoteRoot,
            List<String> sshOptions,
            long timeoutSeconds) {
        this.sshTarget = sshTarget;
        this.localRoot = localRoot;
        this.remoteRoot = stripTrailingSlash(remoteRoot);
        this.sshOptions = sshOptions == null ? List.of() : sshOptions;
        this.timeoutSeconds = timeoutSeconds;
    }

    public void syncBoth() {
        Map<String, FileMeta> local;
        Map<String, FileMeta> remote;
        try {
            ensureRemoteDir();
            Files.createDirectories(localRoot);
            local = listLocal();
            remote = listRemote();
        } catch (IOException e) {
            log.warn(
                    "RemoteDirSyncer({} ↔ {}@{}): listing failed — skipping pass: {}",
                    localRoot,
                    sshTarget,
                    remoteRoot,
                    e.getMessage());
            return;
        }
        Set<String> all = new HashSet<>();
        all.addAll(local.keySet());
        all.addAll(remote.keySet());

        int pushed = 0;
        int pulled = 0;
        for (String rel : all) {
            FileMeta l = local.get(rel);
            FileMeta r = remote.get(rel);
            try {
                if (l == null) {
                    pullFile(rel);
                    pulled++;
                } else if (r == null) {
                    pushFile(rel);
                    pushed++;
                } else {
                    long delta = l.mtimeSeconds - r.mtimeSeconds;
                    if (delta > MTIME_TOLERANCE_SECONDS) {
                        pushFile(rel);
                        pushed++;
                    } else if (-delta > MTIME_TOLERANCE_SECONDS) {
                        pullFile(rel);
                        pulled++;
                    }
                }
            } catch (IOException e) {
                log.warn("Sync failed for {}: {}", rel, e.getMessage());
            }
        }
        if (pushed + pulled > 0) {
            log.info(
                    "RemoteDirSyncer({} ↔ {}:{}): pushed={} pulled={}",
                    localRoot.getFileName(),
                    sshTarget,
                    remoteRoot,
                    pushed,
                    pulled);
        }
    }

    public void pushAll() {
        try {
            ensureRemoteDir();
            Files.createDirectories(localRoot);
            Map<String, FileMeta> local = listLocal();
            int n = 0;
            for (String rel : local.keySet()) {
                try {
                    pushFile(rel);
                    n++;
                } catch (IOException e) {
                    log.warn("Push failed for {}: {}", rel, e.getMessage());
                }
            }
            if (n > 0) {
                log.info(
                        "RemoteDirSyncer({} → {}:{}): pushed all {} files",
                        localRoot.getFileName(),
                        sshTarget,
                        remoteRoot,
                        n);
            }
        } catch (IOException e) {
            log.warn(
                    "RemoteDirSyncer({} → {}:{}): pushAll failed: {}",
                    localRoot.getFileName(),
                    sshTarget,
                    remoteRoot,
                    e.getMessage());
        }
    }

    private void pushFile(String rel) throws IOException {
        Path src = localRoot.resolve(rel);
        if (!Files.isRegularFile(src)) {
            return;
        }
        byte[] raw = Files.readAllBytes(src);
        long mtime = Files.getLastModifiedTime(src).to(TimeUnit.SECONDS);
        String remoteFile = remoteRoot + "/" + rel.replace('\\', '/');
        String remoteDir = remoteFile.substring(0, remoteFile.lastIndexOf('/'));
        String remoteTmp = remoteFile + ".tmp." + ProcessHandle.current().pid();
        String cmd =
                "mkdir -p "
                        + sh(remoteDir)
                        + " && base64 -d > "
                        + sh(remoteTmp)
                        + " && touch -d @"
                        + mtime
                        + " "
                        + sh(remoteTmp)
                        + " && mv -f "
                        + sh(remoteTmp)
                        + " "
                        + sh(remoteFile);
        Process p = startSsh(cmd);
        try (OutputStream stdin = p.getOutputStream()) {
            stdin.write(Base64.getEncoder().encode(raw));
            stdin.flush();
        }
        waitOrKill(p, "pushFile " + rel);
    }

    private void pullFile(String rel) throws IOException {
        String remoteFile = remoteRoot + "/" + rel.replace('\\', '/');
        String cmd =
                "stat -c %Y "
                        + sh(remoteFile)
                        + " && base64 -w 0 "
                        + sh(remoteFile);
        Process p = startSsh(cmd);
        try {
            p.getOutputStream().close();
        } catch (IOException ignore) {
        }
        byte[] out;
        try {
            out = p.getInputStream().readAllBytes();
        } catch (IOException e) {
            p.destroyForcibly();
            throw e;
        }
        waitOrKill(p, "pullFile " + rel);
        String text = new String(out, StandardCharsets.UTF_8);
        int nl = text.indexOf('\n');
        if (nl < 0) {
            throw new IOException("malformed pull response for " + rel + ": " + text);
        }
        long mtime;
        try {
            mtime = Long.parseLong(text.substring(0, nl).trim());
        } catch (NumberFormatException nfe) {
            throw new IOException("malformed mtime for " + rel + ": " + text.substring(0, nl));
        }
        String b64 = text.substring(nl + 1).trim();
        byte[] raw = Base64.getDecoder().decode(b64);

        Path dst = localRoot.resolve(rel);
        Files.createDirectories(dst.getParent());
        Path tmp = dst.resolveSibling(dst.getFileName() + ".tmp");
        Files.write(tmp, raw);
        Files.setLastModifiedTime(tmp, FileTime.from(mtime, TimeUnit.SECONDS));
        try {
            Files.move(tmp, dst, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicFailed) {
            Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Map<String, FileMeta> listLocal() throws IOException {
        Map<String, FileMeta> out = new HashMap<>();
        if (!Files.isDirectory(localRoot)) {
            return out;
        }
        try (Stream<Path> walk = Files.walk(localRoot)) {
            walk.filter(Files::isRegularFile)
                    .sorted(Comparator.naturalOrder())
                    .forEach(
                            p -> {
                                try {
                                    String rel =
                                            localRoot
                                                    .relativize(p)
                                                    .toString()
                                                    .replace('\\', '/');
                                    long mt =
                                            Files.getLastModifiedTime(p)
                                                    .to(TimeUnit.SECONDS);
                                    out.put(
                                            rel,
                                            new FileMeta(rel, Files.size(p), mt));
                                } catch (IOException e) {
                                    log.debug("local stat failed: {}", p);
                                }
                            });
        }
        return out;
    }

    private Map<String, FileMeta> listRemote() throws IOException {
        String cmd =
                "if [ -d "
                        + sh(remoteRoot)
                        + " ]; then cd "
                        + sh(remoteRoot)
                        + " && find . -type f -printf '%P\\t%s\\t%T@\\n'; fi";
        Process p = startSsh(cmd);
        try {
            p.getOutputStream().close();
        } catch (IOException ignore) {
        }
        byte[] out;
        try {
            out = p.getInputStream().readAllBytes();
        } catch (IOException e) {
            p.destroyForcibly();
            throw e;
        }
        waitOrKill(p, "listRemote");
        Map<String, FileMeta> map = new HashMap<>();
        for (String line : new String(out, StandardCharsets.UTF_8).split("\n")) {
            if (line.isBlank()) continue;
            String[] parts = line.split("\t", 3);
            if (parts.length < 3) continue;
            try {
                long size = Long.parseLong(parts[1].trim());
                String mtimeStr = parts[2].trim();
                int dot = mtimeStr.indexOf('.');
                long mtime = Long.parseLong(dot >= 0 ? mtimeStr.substring(0, dot) : mtimeStr);
                String rel = parts[0].replace('\\', '/');
                map.put(rel, new FileMeta(rel, size, mtime));
            } catch (NumberFormatException nfe) {
                log.debug("skipping malformed remote list line: {}", line);
            }
        }
        return map;
    }

    public Map<String, FileMeta> remoteList() throws IOException {
        return listRemote();
    }

    private void ensureRemoteDir() throws IOException {
        Process p = startSsh("mkdir -p " + sh(remoteRoot));
        try {
            p.getOutputStream().close();
        } catch (IOException ignore) {
        }
        waitOrKill(p, "ensureRemoteDir");
    }

    private Process startSsh(String remoteCmd) throws IOException {
        List<String> argv = new ArrayList<>();
        argv.add("ssh");
        argv.add("-o");
        argv.add("BatchMode=yes");
        argv.addAll(sshOptions);
        argv.add(sshTarget);
        argv.add(remoteCmd);
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.redirectErrorStream(false);
        return pb.start();
    }

    private void waitOrKill(Process p, String what) throws IOException {
        boolean exited;
        try {
            exited = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while " + what, ie);
        }
        if (!exited) {
            p.destroyForcibly();
            throw new IOException("ssh timed out after " + timeoutSeconds + "s while " + what);
        }
        int rc = p.exitValue();
        if (rc != 0) {
            String err = drainStderr(p);
            throw new IOException("ssh failed (rc=" + rc + ") while " + what + ": " + err);
        }
    }

    private String drainStderr(Process p) {
        try {
            byte[] buf = p.getErrorStream().readAllBytes();
            String s = new String(buf, StandardCharsets.UTF_8).strip();
            return s.length() > 400 ? s.substring(s.length() - 400) : s;
        } catch (Exception ignore) {
            return "(no stderr)";
        }
    }

    private static String sh(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static String stripTrailingSlash(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    public record FileMeta(String relativePath, long sizeBytes, long mtimeSeconds) {}
}
