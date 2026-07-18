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

import com.agentscopea2a.v2.config.V2SandboxConfig;
import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Startup health check for remote-Docker SSH topology.
 *
 * <p>What this catches: Windows users running with {@code sandbox-windows} profile but whose
 * {@code ssh.exe} is the bundled Windows OpenSSH (does NOT support {@code ControlMaster}). Every
 * subsequent {@code docker exec} pays a ~300ms TCP+SSH handshake, which stacks to multi-second
 * latency on {@code code_interpreter}'s 4~8 round-trips per task.
 *
 * <p>What we do: at startup, run {@code ssh -O check <target>} once. If the master socket is
 * up, log SUCCESS. If not, log a clearly actionable WARN with the fix recipe. We never fail
 * startup — the agent still works, just slower.
 *
 * <p>Why only WARN (not fail-fast): users may legitimately not care about latency, or the master
 * could come up lazily on first real ssh. Fail-fast would be hostile.
 *
 * <p>Disable with {@code harness.a2a.sandbox.ssh-health-check.enabled=false}.
 */
@Component
@ConditionalOnProperty(
        prefix = "harness.a2a.sandbox",
        name = {"enabled", "remote-docker-enabled"},
        havingValue = "true")
public class SshHealthCheck {

    private static final Logger log = LoggerFactory.getLogger(SshHealthCheck.class);

    private final V2SandboxConfig.SandboxPropertiesV2 props;

    public SshHealthCheck(V2SandboxConfig.SandboxPropertiesV2 props) {
        this.props = props;
    }

    @PostConstruct
    void check() {
        String target = props.getSandbox().getRemoteDockerSshTarget();
        if (target == null || target.isBlank()) {
            return;
        }
        log.info("[ssh-health] Probing remote Docker SSH target '{}'…", target);
        // Step 1 — connectivity smoke
        Probe reach = run(buildBatchProbe(target));
        if (reach.exit != 0) {
            log.warn(
                    "[ssh-health] {} unreachable in BatchMode (immediate fail = no key, wrong host, or hung master). exit={}\n"
                            + "  stderr: {}\n"
                            + "  fix: `ssh-copy-id {}` then `ssh -o BatchMode=yes {} 'echo ok'` should print ok.",
                    target,
                    reach.exit,
                    truncate(reach.stderr, 400),
                    target,
                    target);
            return;
        }
        log.info("[ssh-health] {} reachable in BatchMode (~{}ms)", target, reach.elapsedMs);

        // Step 2 — ControlMaster status. -O check returns 0 when master socket alive, non-zero
        // otherwise. We don't care about the error message itself, only the exit code.
        Probe master = run(List.of(resolveSsh(), "-O", "check", target));
        if (master.exit == 0) {
            log.info(
                    "[ssh-health] ControlMaster running for {} — every subsequent ssh handshake will be ~30ms instead of ~300ms.",
                    target);
            return;
        }
        log.warn(
                "[ssh-health] ControlMaster NOT running for {} (exit={}). Each `ssh` will re-handshake (~300ms each).\n"
                        + "  Why this matters: code_interpreter does ~4-8 ssh+docker exec round-trips per analyze_data task.\n"
                        + "  Fix (one-time):\n"
                        + "    1. Ensure `which ssh` resolves to Git Bash's /usr/bin/ssh (NOT C:\\Windows\\System32\\OpenSSH\\ssh.exe).\n"
                        + "       Windows OpenSSH does not honour ControlMaster — must use OpenSSH from Git for Windows.\n"
                        + "    2. In ~/.ssh/config add:\n"
                        + "         Host {}\n"
                        + "           HostName <ip>\n"
                        + "           User <user>\n"
                        + "           ControlMaster auto\n"
                        + "           ControlPath /tmp/.ssh-mux-%C\n"
                        + "           ControlPersist 30m\n"
                        + "    3. `ssh {} 'echo ok'` once to bring up master, then this check will pass.",
                target,
                master.exit,
                target,
                target);
    }

    // --------------------------------------------------------------------
    // Process helpers
    // --------------------------------------------------------------------

    private List<String> buildBatchProbe(String target) {
        List<String> cmd = new ArrayList<>();
        cmd.add(resolveSsh());
        cmd.add("-o");
        cmd.add("BatchMode=yes");
        cmd.add("-o");
        cmd.add("ConnectTimeout=5");
        if (props.getSandbox().getRemoteDockerSshOptions() != null) {
            cmd.addAll(props.getSandbox().getRemoteDockerSshOptions());
        }
        cmd.add(target);
        cmd.add("echo ok");
        return cmd;
    }

    private static String resolveSsh() {
        return "ssh";
    }

    private static Probe run(List<String> cmd) {
        long t0 = System.currentTimeMillis();
        Process p;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
        } catch (Exception e) {
            return new Probe(-1, "", "spawn failed: " + e.getMessage(), 0);
        }
        try {
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new Probe(-1, "", "timeout", System.currentTimeMillis() - t0);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            return new Probe(-1, "", "interrupted", System.currentTimeMillis() - t0);
        }
        String out = readStream(p.getInputStream());
        String err = readStream(p.getErrorStream());
        return new Probe(p.exitValue(), out, err, System.currentTimeMillis() - t0);
    }

    private static String readStream(java.io.InputStream is) {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            char[] buf = new char[1024];
            int n;
            while ((n = r.read(buf)) > 0) {
                if (sb.length() < 4096) sb.append(buf, 0, n);
            }
        } catch (Exception ignore) {
            // best effort
        }
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private record Probe(int exit, String stdout, String stderr, long elapsedMs) {}
}
