/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.agentscopea2a.v2.artifact;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ArtifactIo} that writes CSVs straight onto a remote Docker host over SSH.
 *
 * <p>When the JVM runs on a developer laptop but Docker runs on a remote host, sandbox bind
 * mounts resolve their host-side paths on the <i>remote</i> daemon's filesystem. This impl
 * uploads the CSV bytes (base64 over ssh stdin) to the same absolute path on the remote machine
 * before returning the ref.
 *
 * <p>Atomicity: same {@code .tmp + rename} pattern as the local impl, executed remotely in a
 * single SSH command: {@code mkdir -p && base64 -d > .tmp && mv -f .tmp target}.
 */
public final class SshArtifactIo implements ArtifactIo {

    private static final Logger log = LoggerFactory.getLogger(SshArtifactIo.class);

    private final String sshTarget;
    private final String remoteRoot;
    private final List<String> sshOptions;
    private final long timeoutSeconds;

    public SshArtifactIo(
            String sshTarget,
            String remoteRoot,
            List<String> sshOptions,
            long timeoutSeconds) {
        this.sshTarget = sshTarget;
        this.remoteRoot = stripTrailingSlash(remoteRoot);
        this.sshOptions = sshOptions == null ? List.of() : sshOptions;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public void writeAtomic(String userBucket, String taskBucket, String filename, String content)
            throws IOException {
        // Single shell pipeline so the remote side never sees a half-written file. We pipe the
        // payload as base64 over ssh stdin and `base64 -d` on the remote side, then `mv -f`
        // atomically swings the name. Base64 survives any code-page or line-ending transformation
        // that Windows ssh.exe might apply to the stdin pipe.
        String remoteDir = remoteRoot + "/" + userBucket + "/" + taskBucket;
        String remoteTarget = remoteDir + "/" + filename;
        String remoteTmp = remoteTarget + ".tmp";
        String cmd =
                "mkdir -p "
                        + sh(remoteDir)
                        + " && base64 -d > "
                        + sh(remoteTmp)
                        + " && mv -f "
                        + sh(remoteTmp)
                        + " "
                        + sh(remoteTarget);

        Process p = startSsh(cmd);
        try (OutputStream stdin = p.getOutputStream()) {
            byte[] raw = content.getBytes(StandardCharsets.UTF_8);
            stdin.write(Base64.getEncoder().encode(raw));
            stdin.flush();
        }
        waitOrKill(p, "writeAtomic " + remoteTarget);
        log.debug("Wrote remote artifact {}@{}:{}", sshTarget, "", remoteTarget);
    }

    @Override
    public void deleteBucket(String userBucket, String taskBucket) {
        if (userBucket == null
                || userBucket.isBlank()
                || taskBucket == null
                || taskBucket.isBlank()) {
            log.warn(
                    "Refusing to delete remote bucket with blank user/task ({}/{})",
                    userBucket,
                    taskBucket);
            return;
        }
        String remoteDir = remoteRoot + "/" + userBucket + "/" + taskBucket;
        String cmd = "rm -rf " + sh(remoteDir);
        try {
            Process p = startSsh(cmd);
            p.getOutputStream().close();
            waitOrKill(p, "deleteBucket " + remoteDir);
            log.debug("Cleaned remote artifact bucket {}", remoteDir);
        } catch (IOException e) {
            log.warn("Failed to clean remote bucket {}: {}", remoteDir, e.getMessage());
        }
    }

    @Override
    public String describePath(String userBucket, String taskBucket, String filename) {
        return sshTarget + ":" + remoteRoot + "/" + userBucket + "/" + taskBucket + "/" + filename;
    }

    /**
     * 通过 SSH cat 拉取远端 CSV 原始字节. 用于 CSV 下载短链场景 - dev profile CSV 写在远端
     * docker-host, RedirectController 必须走这条路径才能读到. 用 base64 wrap 保证字节流过
     * Windows ssh.exe stdin/stdout 时不被 code-page 转换污染 (与 writeAtomic 同样套路).
     * 返回 null 表示远端文件不存在.
     *
     * <p><b>Windows 管道死锁规避</b>: 不能用 {@code p.getInputStream().readAllBytes()} 在
     * {@code waitFor} 之后读 - Windows pipe buffer ~4KB, base64 编码后 CSV 普遍 >4KB,
     * SSH 进程写满 pipe 后阻塞等读端 drain, 但 Java 端在 {@code waitFor} 阻塞没读 stdout,
     * 死锁 -> 30s timeout. 改用 {@code redirectOutput(tempFile)} 让 OS 直接把 stdout 落盘,
     * 进程退出后再读文件.
     */
    @Override
    public byte[] read(String userBucket, String taskBucket, String filename) throws IOException {
        String remoteTarget = remoteRoot + "/" + userBucket + "/" + taskBucket + "/" + filename;
        // test -f 避免对不存在 / 目录 cat 报错, 退出码非 0 时 waitOrKill 会抛 IOException
        String cmd = "test -f " + sh(remoteTarget) + " && base64 " + sh(remoteTarget);

        // 自己构造 ProcessBuilder 而非复用 startSsh, 因为要 redirectOutput 到临时文件.
        // writeAtomic 不需要 redirect (它写 stdin 不读 stdout), 走 startSsh 即可.
        java.util.List<String> argv = new java.util.ArrayList<>();
        argv.add("ssh");
        argv.add("-o");
        argv.add("BatchMode=yes");
        argv.addAll(sshOptions);
        argv.add(sshTarget);
        argv.add(cmd);

        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.redirectErrorStream(true);
        java.nio.file.Path tmp = java.nio.file.Files.createTempFile("ssh-read-", ".b64");
        pb.redirectOutput(tmp.toFile());

        Process p = pb.start();
        p.getOutputStream().close();
        try {
            waitOrKill(p, "read " + remoteTarget);
            byte[] raw = java.nio.file.Files.readAllBytes(tmp);
            if (raw.length == 0) {
                return null;
            }
            String b64 = new String(raw, StandardCharsets.UTF_8).strip();
            if (b64.isEmpty()) {
                return null;
            }
            // base64(1) 默认每 76 字符换行, getDecoder() 不接受换行符会抛
            // "Illegal base64 character a" (0x0a). 用 getMimeDecoder() 兼容 \r\n / \n.
            return Base64.getMimeDecoder().decode(b64);
        } finally {
            java.nio.file.Files.deleteIfExists(tmp);
        }
    }

    private Process startSsh(String remoteCmd) throws IOException {
        java.util.List<String> argv = new java.util.ArrayList<>();
        argv.add("ssh");
        argv.add("-o");
        argv.add("BatchMode=yes");
        argv.addAll(sshOptions);
        argv.add(sshTarget);
        argv.add(remoteCmd);

        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.redirectErrorStream(true);
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
            throw new IOException(
                    "ssh timed out after " + timeoutSeconds + "s while " + what);
        }
        int rc = p.exitValue();
        if (rc != 0) {
            String tail = drainTail(p);
            throw new IOException(
                    "ssh failed (rc=" + rc + ") while " + what + ": " + tail);
        }
    }

    private String drainTail(Process p) {
        try {
            byte[] buf = p.getInputStream().readAllBytes();
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
}
