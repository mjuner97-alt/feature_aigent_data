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
package com.agentscopea2a.harness.hooks;

import com.agentscopea2a.harness.artifact.ArtifactContext;
import com.agentscopea2a.harness.artifact.ArtifactStore;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.hook.RuntimeContextAware;
import io.agentscope.core.message.ToolUseBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Stops cross-tenant access into the artifacts directory.
 *
 * <p>The artifact bind mount is shared across all tenants (Docker can't bind-mount a different
 * per-tenant host directory into the same container path, and reusing a single container per
 * scope is more efficient than per-task containers). Path-based isolation alone is not enough —
 * a hallucinating / injected {@code code_interpreter} could trivially {@code read_file} into
 * another user's bucket, or worse, {@code shell_execute("cat /workspace/artifacts/bob/.../x.csv")}.
 *
 * <p>This hook intercepts {@link PreActingEvent} for three tools:
 *
 * <ul>
 *   <li>{@code read_file} — checks the {@code path} parameter is inside the current task's
 *       artifact bucket (or outside the artifacts tree entirely, which is fine).
 *   <li>{@code write_file} — same {@code path} check applied to writes, so an LLM can't write
 *       a payload into another tenant's bucket either.
 *   <li>{@code shell_execute} — scans the {@code command} string and the
 *       {@code working_directory} for any reference to {@link ArtifactStore#agentPathRoot()}
 *       that does NOT start with the current task's bucket prefix. This is a substring filter
 *       not a shell parser — it errs on the side of blocking (any literal cross-tenant path in
 *       the command line is denied), which is correct for an LLM-driven tool whose only
 *       legitimate need is to read its own task's CSVs.
 * </ul>
 *
 * <p>To deny the call without actually running the underlying tool, we hijack the
 * {@link ToolUseBlock} parameters so the original tool either errors out or reads/runs a
 * guaranteed-empty replacement. The harness exposes hook-driven short-circuiting through this
 * trick because there's no {@code PreActingEvent.shortCircuit(result)} API yet (same pattern
 * as {@code ResponseCacheHook.CacheHitException} for short-circuiting the whole call).
 */
public class ArtifactAccessHook implements Hook, RuntimeContextAware {

    private static final Logger log = LoggerFactory.getLogger(ArtifactAccessHook.class);
    private static final String READ_FILE_TOOL = "read_file";
    private static final String WRITE_FILE_TOOL = "write_file";
    private static final String SHELL_EXECUTE_TOOL = "shell_execute";

    /** Conventional parameter name in harness's FilesystemTool. */
    private static final String PATH_PARAM = "path";

    /** ShellExecuteTool parameter names. */
    private static final String COMMAND_PARAM = "command";
    private static final String WORKING_DIR_PARAM = "working_directory";

    private final ArtifactStore artifactStore;

    /**
     * Same pinning as in {@link ArtifactHandoffHook}: when set, "your own task's artifacts" is
     * defined by this fixed bucket, not by whatever {@link RuntimeContext} happens to be in
     * play. Required because subagents have per-spawn session ids ({@code sub-...}) but must
     * share the parent A2A task's bucket. See {@code docs/artifact-multi-tenancy.md}.
     */
    private final ArtifactContext fixedContext;

    /** Captured from {@link #setRuntimeContext} — only consulted when {@link #fixedContext} is null. */
    private volatile RuntimeContext runtimeContext;

    /** Test-only ctor: ctx is read from the framework-injected {@link RuntimeContext}. */
    public ArtifactAccessHook(ArtifactStore artifactStore) {
        this(artifactStore, null);
    }

    public ArtifactAccessHook(ArtifactStore artifactStore, ArtifactContext fixedContext) {
        this.artifactStore = artifactStore;
        this.fixedContext = fixedContext;
    }

    @Override
    public void setRuntimeContext(RuntimeContext context) {
        this.runtimeContext = context;
    }

    @Override
    public int priority() {
        // Run before tool dispatch but after binding (so the bucket resolves to the right tenant).
        return 8;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (!(event instanceof PreActingEvent pre)) {
            return Mono.just(event);
        }
        ToolUseBlock toolUse = pre.getToolUse();
        if (toolUse == null) {
            return Mono.just(event);
        }
        String name = toolUse.getName();
        if (READ_FILE_TOOL.equals(name)) {
            return handleReadFile(pre, toolUse, (T) event);
        }
        if (WRITE_FILE_TOOL.equals(name)) {
            return handleWriteFile(pre, toolUse, (T) event);
        }
        if (SHELL_EXECUTE_TOOL.equals(name)) {
            return handleShellExecute(pre, toolUse, (T) event);
        }
        return Mono.just(event);
    }

    private <T extends HookEvent> Mono<T> handleReadFile(
            PreActingEvent pre, ToolUseBlock toolUse, T event) {
        return enforcePath(pre, toolUse, event, PATH_PARAM, "read");
    }

    /**
     * Block cross-tenant {@code write_file}. Without shell_execute we already wouldn't expect
     * writes to escape the bucket (the tool only takes a {@code path}), but once shell_execute
     * is in the mix an LLM could legitimately ask "write /workspace/artifacts/bob/.../leak.csv"
     * via the write_file tool to circumvent the shell scan. Same enforcement: outside artifacts
     * tree → pass through (run.py writes are fine), inside artifacts tree → must be in own bucket.
     */
    private <T extends HookEvent> Mono<T> handleWriteFile(
            PreActingEvent pre, ToolUseBlock toolUse, T event) {
        return enforcePath(pre, toolUse, event, PATH_PARAM, "write");
    }

    /**
     * Shared path-parameter enforcement for read_file / write_file. Both tools fail-open when
     * given a nonsensical path — overwriting the param with a sentinel under
     * {@code /__forbidden__/...} is enough to make the tool error out and surface the violation
     * in the transcript.
     */
    private <T extends HookEvent> Mono<T> enforcePath(
            PreActingEvent pre, ToolUseBlock toolUse, T event, String paramName, String verb) {
        String requested = extractString(toolUse.getInput(), paramName);
        if (requested == null || requested.isBlank()) {
            return Mono.just(event);
        }

        ArtifactContext ctx =
                fixedContext != null ? fixedContext : ArtifactContext.from(runtimeContext);
        String artifactsRoot = artifactStore.agentPathRoot();
        String allowedPrefix = artifactStore.agentPathPrefixFor(ctx);

        // Only enforce inside the artifacts tree. Reading/writing run.py / arbitrary other
        // workspace files is fine — that's how code_interpreter writes and re-reads its own scripts.
        if (!requested.startsWith(artifactsRoot)) {
            return Mono.just(event);
        }
        if (requested.startsWith(allowedPrefix)) {
            return Mono.just(event);
        }

        log.warn(
                "Blocked cross-tenant artifact {}: requested={} allowed={} (user={}, task={})",
                verb,
                requested,
                allowedPrefix,
                ctx.userBucket(),
                ctx.taskBucket());

        Map<String, Object> input = new java.util.HashMap<>(toolUse.getInput());
        input.put(paramName, "/__forbidden__/" + ctx.userBucket() + "/" + ctx.taskBucket());
        ToolUseBlock rewritten = new ToolUseBlock(toolUse.getId(), toolUse.getName(), input);
        pre.setToolUse(rewritten);

        return Mono.just(event);
    }

    /**
     * Substring-scan the command line and working directory for any literal artifact path that
     * is not in the current task's bucket. This is intentionally crude — we don't try to parse
     * shell quoting, redirections, or env-var expansion. The legitimate use of shell_execute in
     * code_interpreter is {@code python3 /workspace/run.py}, which doesn't reference the
     * artifacts tree directly (run.py opens CSVs via pandas with hard-coded paths the supervisor
     * already vetted via the handoff message). Any literal "/workspace/artifacts/..." in the
     * command line that doesn't start with the allowed bucket prefix is therefore noise from a
     * hallucination or an injection attempt, and we deny.
     */
    private <T extends HookEvent> Mono<T> handleShellExecute(
            PreActingEvent pre, ToolUseBlock toolUse, T event) {
        String command = extractString(toolUse.getInput(), COMMAND_PARAM);
        String workingDir = extractString(toolUse.getInput(), WORKING_DIR_PARAM);
        if ((command == null || command.isBlank())
                && (workingDir == null || workingDir.isBlank())) {
            return Mono.just(event);
        }

        ArtifactContext ctx =
                fixedContext != null ? fixedContext : ArtifactContext.from(runtimeContext);
        String artifactsRoot = artifactStore.agentPathRoot();
        String allowedPrefix = artifactStore.agentPathPrefixFor(ctx);

        String violation = null;
        if (workingDir != null
                && workingDir.startsWith(artifactsRoot)
                && !workingDir.startsWith(allowedPrefix)) {
            violation = "working_directory=" + workingDir;
        } else if (command != null && containsForeignArtifactPath(command, artifactsRoot, allowedPrefix)) {
            violation = "command references " + artifactsRoot + " outside " + allowedPrefix;
        }
        if (violation == null) {
            return Mono.just(event);
        }

        log.warn(
                "Blocked cross-tenant shell_execute: {} (user={}, task={})",
                violation,
                ctx.userBucket(),
                ctx.taskBucket());

        // Rewrite to a guaranteed-failing command so the LLM sees a clean error message, the
        // tool result lands in transcript, and the violation is auditable.
        Map<String, Object> input = new java.util.HashMap<>(toolUse.getInput());
        input.put(
                COMMAND_PARAM,
                "echo 'shell_execute denied by ArtifactAccessHook: cross-tenant artifact path in"
                        + " command (user="
                        + ctx.userBucket()
                        + " task="
                        + ctx.taskBucket()
                        + ")' >&2; exit 77");
        // Drop the offending working_directory so the failure surfaces from the harness's
        // default cwd rather than triggering a separate "no such directory" branch.
        input.remove(WORKING_DIR_PARAM);
        ToolUseBlock rewritten = new ToolUseBlock(toolUse.getId(), toolUse.getName(), input);
        pre.setToolUse(rewritten);
        return Mono.just(event);
    }

    /**
     * Returns true iff the command line contains any occurrence of {@code artifactsRoot} whose
     * matched path-prefix is NOT {@code allowedPrefix}. We walk every match instead of just the
     * first one — an LLM trying to be clever could write
     * {@code cat /workspace/artifacts/alice/t/own.csv /workspace/artifacts/bob/.../leak.csv}.
     */
    private static boolean containsForeignArtifactPath(
            String command, String artifactsRoot, String allowedPrefix) {
        int from = 0;
        while (true) {
            int idx = command.indexOf(artifactsRoot, from);
            if (idx < 0) return false;
            // Substring starting at the matched root — check whether the very next characters
            // continue with the allowed prefix.
            String tail = command.substring(idx);
            if (!tail.startsWith(allowedPrefix)) {
                return true;
            }
            from = idx + allowedPrefix.length();
        }
    }

    private static String extractString(Map<String, Object> input, String key) {
        if (input == null) return null;
        Object raw = input.get(key);
        return raw == null ? null : raw.toString();
    }
}
