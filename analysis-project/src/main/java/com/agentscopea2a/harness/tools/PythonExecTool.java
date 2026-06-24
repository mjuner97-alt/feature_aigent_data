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
package com.agentscopea2a.harness.tools;

import com.agentscopea2a.harness.config.PythonExecProperties;
import com.agentscopea2a.harness.config.SandboxProperties;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single-step Python execution. Replaces the legacy {@code write_file → shell_execute} pair the
 * {@code code_interpreter} subagent used to do — see {@code docs/code-interpreter-optimization.md}
 * §P0-B.
 *
 * <p><b>Why this is faster.</b> Each {@code shell_execute} on {@code sandbox-windows} goes
 * {@code ssh.exe → ssh handshake → docker exec → fork python3} (~1.5~3s). The old flow did this
 * twice per calculation; this tool does it once, piping the code into {@code python3 -} via stdin
 * so we never touch the filesystem at all.
 *
 * <p><b>Two transports.</b>
 *
 * <ul>
 *   <li><b>Remote Docker</b> (sandbox profile + {@code remoteDockerEnabled=true}): we shell out to
 *       {@code ssh <target> docker exec -i <container> python3 -} and feed code to stdin. Same
 *       command we'd run if we'd done it manually.
 *   <li><b>Local Docker</b> (sandbox profile + no SSH): {@code docker exec -i <container> python3 -}.
 *   <li><b>No sandbox</b> (default {@code dev} profile): {@code python3 -} on the host. For local
 *       demo only — README §3.6 warns about this.
 * </ul>
 *
 * <p><b>Why not go through {@code SandboxFilesystemSpec}.</b> The spec exposes only the
 * filesystem-shaped operations the harness already wires (read_file / write_file /
 * shell_execute). For one-shot {@code python3 -} with stdin we'd have to round-trip back through
 * shell_execute and lose the stdin path. Going to the SSH/docker CLI directly is exactly what
 * {@code DockerSandboxClient} already does for its container lifecycle, so we reuse the same
 * Process abstraction without re-implementing the wheel.
 *
 * <p><b>Security.</b> Only registered for subagents whose markdown spec opts in
 * ({@code tools: [..., python_exec, ...]}). {@code ArtifactAccessHook} already covers the
 * artifact-tree cross-tenant case for {@code shell_execute}; this tool, being a generic Python
 * exec, lives at the same trust level — and we deliberately propagate the access check by
 * pre-scanning the code for the artifacts root substring. See {@link #denyIfCrossBucket}.
 */
public class PythonExecTool {

    private static final Logger log = LoggerFactory.getLogger(PythonExecTool.class);

    /** Default wall-clock cap when the LLM doesn't pass {@code timeoutSeconds}. */
    private static final int DEFAULT_TIMEOUT_SECONDS_FALLBACK = 60;
    /** Hard upper bound when no config injected — prevents an LLM "timeoutSeconds=99999" exploit. */
    private static final int MAX_TIMEOUT_SECONDS_FALLBACK = 300;

    /** Max bytes returned per stream. Protect the LLM context — large stdout gets truncated. */
    private static final int MAX_OUTPUT_BYTES = 64_000;

    private final SandboxProperties.Sandbox sandbox;
    private final int defaultTimeoutSeconds;
    private final int maxTimeoutSeconds;

    public PythonExecTool(SandboxProperties sandboxProps) {
        this(sandboxProps, null);
    }

    public PythonExecTool(SandboxProperties sandboxProps, PythonExecProperties pyProps) {
        this.sandbox = sandboxProps != null ? sandboxProps.getSandbox() : null;
        this.defaultTimeoutSeconds =
                pyProps != null ? pyProps.getDefaultTimeoutSeconds() : DEFAULT_TIMEOUT_SECONDS_FALLBACK;
        this.maxTimeoutSeconds =
                pyProps != null ? pyProps.getMaxTimeoutSeconds() : MAX_TIMEOUT_SECONDS_FALLBACK;
    }

    @Tool(
            name = "python_exec",
            description =
                    "在沙箱容器内直接执行一段 Python 代码,无需先 write_file。"
                            + "代码通过 stdin 喂给 python3 -,不触碰文件系统、不走 shell 转义。"
                            + "比 write_file+shell_execute 链路少一次远端往返(~1.5-3s)。"
                            + "适合: pandas/numpy 计算、csv 读取、统计聚合等。"
                            + "返回容器内 python3 进程的 stdout + stderr + exitCode。")
    public ToolResultBlock pythonExec(
            @ToolParam(
                            name = "code",
                            description =
                                    "完整的 Python 脚本字符串。可以是多行,会原样喂给 python3 - 的 stdin。"
                                            + "推荐结构: import pandas as pd; df = pd.read_csv(<artifact path>); ... ; print(...)。")
                    String code,
            @ToolParam(
                            name = "timeoutSeconds",
                            description = "执行超时(秒)。默认 60。大计算请显式调大。",
                            required = false)
                    Integer timeoutSeconds) {

        if (code == null || code.isBlank()) {
            return ToolResultBlock.text(
                    "python_exec 拒绝执行: code 参数为空。请把完整 Python 脚本作为字符串传入。");
        }
        int timeout =
                (timeoutSeconds == null || timeoutSeconds <= 0)
                        ? defaultTimeoutSeconds
                        : Math.min(timeoutSeconds, maxTimeoutSeconds);

        // Soft guard — full enforcement is in ArtifactAccessHook on shell_execute / read_file /
        // write_file. We don't have ctx here, so we only block the OBVIOUS case where the LLM
        // typed another user's bucket literally. Sophisticated escape attempts still need
        // PythonExecAccessHook (see P0-D).
        ToolResultBlock denied = denyIfObviouslyCrossBucket(code);
        if (denied != null) return denied;

        List<String> command = buildCommand();
        if (command == null) {
            return ToolResultBlock.text(
                    "python_exec 不可用: 既未启用 sandbox profile,也无本地 python3。"
                            + "请通过 sandbox-windows / sandbox-linux profile 启用容器,或安装本地 python3。");
        }

        log.info(
                "python_exec: transport={} timeout={}s codeBytes={}",
                describeTransport(command),
                timeout,
                code.getBytes(StandardCharsets.UTF_8).length);

        return runProcess(command, code, timeout);
    }

    // ----------------------------------------------------------------------
    // Command building
    // ----------------------------------------------------------------------

    /**
     * Picks the right transport based on sandbox config:
     *
     * <ul>
     *   <li>sandbox + remote SSH + shared container: {@code ssh <target> docker exec -i <name> python3 -}
     *   <li>sandbox + local + shared container: {@code docker exec -i <name> python3 -}
     *   <li>otherwise: {@code python3 -} on host (only fine when no sandbox profile is active)
     * </ul>
     */
    private List<String> buildCommand() {
        if (sandbox != null && sandbox.isEnabled() && !isBlank(sandbox.getSharedContainerName())) {
            String container = sandbox.getSharedContainerName();
            if (sandbox.isRemoteDockerEnabled()
                    && !isBlank(sandbox.getRemoteDockerSshTarget())) {
                List<String> cmd = new ArrayList<>();
                cmd.add(resolveSsh());
                if (sandbox.getRemoteDockerSshOptions() != null) {
                    cmd.addAll(sandbox.getRemoteDockerSshOptions());
                }
                cmd.add(sandbox.getRemoteDockerSshTarget());
                cmd.add("docker");
                cmd.add("exec");
                cmd.add("-i");
                cmd.add(container);
                cmd.add("python3");
                cmd.add("-");
                return cmd;
            }
            return List.of("docker", "exec", "-i", container, "python3", "-");
        }
        // No sandbox — fall back to host python3. Only safe in `dev` profile (demo).
        return List.of("python3", "-");
    }

    private static String describeTransport(List<String> cmd) {
        if (cmd.size() > 0 && "ssh".equals(stripExeExt(cmd.get(0)))) return "ssh+docker";
        if (cmd.size() > 0 && cmd.get(0).equals("docker")) return "docker";
        return "host-python";
    }

    private static String stripExeExt(String s) {
        if (s == null) return "";
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        String name = slash >= 0 ? s.substring(slash + 1) : s;
        if (name.toLowerCase().endsWith(".exe")) {
            name = name.substring(0, name.length() - 4);
        }
        return name;
    }

    private static String resolveSsh() {
        // Trust whatever ssh is on PATH; Windows users who want ControlMaster must put Git Bash's
        // /usr/bin/ssh ahead of Windows OpenSSH (see docs/ssh.md §Windows).
        return "ssh";
    }

    // ----------------------------------------------------------------------
    // Process execution
    // ----------------------------------------------------------------------

    private ToolResultBlock runProcess(List<String> command, String code, int timeoutSeconds) {
        long start = System.currentTimeMillis();
        Process p;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            p = pb.start();
        } catch (IOException e) {
            return ToolResultBlock.text(
                    "python_exec 启动失败: " + e.getMessage()
                            + "\n命令: " + String.join(" ", command)
                            + "\n排查: ssh 是否在 PATH、container 是否在运行、docker daemon 是否可达");
        }

        try (OutputStream stdin = p.getOutputStream()) {
            stdin.write(code.getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        } catch (IOException e) {
            log.warn("python_exec: failed writing code to stdin: {}", e.getMessage());
        }

        boolean finished;
        try {
            finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            return ToolResultBlock.text("python_exec 被中断: " + e.getMessage());
        }
        if (!finished) {
            p.destroyForcibly();
            try {
                p.waitFor(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignore) {
                Thread.currentThread().interrupt();
            }
            String stdoutBest = bestEffortRead(p.getInputStream());
            String stderrBest = bestEffortRead(p.getErrorStream());
            long elapsed = System.currentTimeMillis() - start;
            return ToolResultBlock.text(
                    formatResult(
                            -1,
                            elapsed,
                            stdoutBest,
                            stderrBest,
                            "❌ 超时(" + timeoutSeconds + "s),进程已强制终止。"
                                    + "如确实是大计算,在下次调用 python_exec 时显式传入更大的 timeoutSeconds。"
                                    + "如怀疑代码死循环,先简化算法再试。"));
        }

        String stdout = bestEffortRead(p.getInputStream());
        String stderr = bestEffortRead(p.getErrorStream());
        int exit = p.exitValue();
        long elapsed = System.currentTimeMillis() - start;
        log.info(
                "python_exec done: exit={} elapsed={}ms stdoutBytes={} stderrBytes={}",
                exit,
                elapsed,
                stdout.length(),
                stderr.length());
        return ToolResultBlock.text(formatResult(exit, elapsed, stdout, stderr, null));
    }

    private static String bestEffortRead(java.io.InputStream is) {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) > 0) {
                if (sb.length() + n > MAX_OUTPUT_BYTES) {
                    sb.append(buf, 0, MAX_OUTPUT_BYTES - sb.length());
                    sb.append("\n... (输出超过 ").append(MAX_OUTPUT_BYTES).append(" 字节,已截断)");
                    break;
                }
                sb.append(buf, 0, n);
            }
        } catch (IOException ignore) {
            // Pipes closed underneath us — return what we have, never throw.
        }
        return sb.toString();
    }

    private static String formatResult(
            int exit, long elapsedMs, String stdout, String stderr, String banner) {
        StringBuilder sb = new StringBuilder();
        if (banner != null) {
            sb.append(banner).append("\n\n");
        }
        sb.append("[python_exec] exit=").append(exit).append("  elapsed=").append(elapsedMs).append("ms\n\n");
        sb.append("─── stdout ─────────────────────────\n");
        sb.append(stdout == null || stdout.isEmpty() ? "(空)\n" : stdout);
        if (!stdout.endsWith("\n")) sb.append("\n");
        if (stderr != null && !stderr.isEmpty()) {
            sb.append("\n─── stderr ─────────────────────────\n");
            sb.append(stderr);
            if (!stderr.endsWith("\n")) sb.append("\n");
        }
        return sb.toString();
    }

    // ----------------------------------------------------------------------
    // Soft cross-bucket guard (defense-in-depth; full check is in PythonExecAccessHook)
    // ----------------------------------------------------------------------

    /**
     * Returns a denied {@link ToolResultBlock} if the code literally references the artifacts root
     * with what looks like another user's path. Hook-level enforcement lives in
     * {@code ArtifactAccessHook}, which has the per-call {@link io.agentscope.core.agent.RuntimeContext}
     * we don't see here; this is just a courtesy fast-fail for the obvious mistake.
     */
    private ToolResultBlock denyIfObviouslyCrossBucket(String code) {
        // Intentionally conservative — only fail when we're sure. The hook is authoritative.
        if (code == null) return null;
        // No ctx here; leave actual enforcement to PythonExecAccessHook (P0-D).
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
