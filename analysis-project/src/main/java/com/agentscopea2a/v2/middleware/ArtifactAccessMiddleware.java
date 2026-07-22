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
package com.agentscopea2a.v2.middleware;

import com.agentscopea2a.v2.artifact.ArtifactContext;
import com.agentscopea2a.v2.artifact.ArtifactStore;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * v2 middleware that replaces v1 {@code ArtifactAccessHook}.
 *
 * <p>Stops cross-tenant access into the artifacts directory by intercepting tool calls via
 * {@link MiddlewareBase#onActing}. The artifact bind mount is shared across all tenants; without
 * this guard a hallucinating or injected {@code code_interpreter} could read another user's CSVs.
 *
 * <p>Intercepts three tools:
 * <ul>
 *   <li>{@code read_file} — checks the {@code path} param is inside the current task bucket
 *   <li>{@code write_file} — same path check for writes
 *   <li>{@code shell_execute} — scans {@code command} and {@code working_directory} for foreign paths
 * </ul>
 *
 * <p>Denied calls are rewritten to safe sentinel values (/{@code __forbidden__}/... for paths,
 * failing echo command for shell_execute) rather than dropped — the LLM sees a clean error in
 * the transcript and self-corrects.
 */
public class ArtifactAccessMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(ArtifactAccessMiddleware.class);

    private static final String READ_FILE_TOOL = "read_file";
    private static final String WRITE_FILE_TOOL = "write_file";
    private static final String SHELL_EXECUTE_TOOL = "shell_execute";

    private static final String PATH_PARAM = "path";
    private static final String COMMAND_PARAM = "command";
    private static final String WORKING_DIR_PARAM = "working_directory";

    private final ArtifactStore artifactStore;

    /**
     * Fixed artifact context for multi-subagent bucket sharing. When non-null, all artifact
     * access is validated against this single bucket regardless of the per-call RuntimeContext.
     * This is how the A2A supervisor pins every subagent to the same request-scoped bucket.
     */
    private final ArtifactContext fixedContext;

    /** Test-only ctor: ctx is read from RuntimeContext. */
    public ArtifactAccessMiddleware(ArtifactStore artifactStore) {
        this(artifactStore, null);
    }

    public ArtifactAccessMiddleware(ArtifactStore artifactStore, ArtifactContext fixedContext) {
        this.artifactStore = artifactStore;
        this.fixedContext = fixedContext;
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                      Function<ActingInput, Flux<AgentEvent>> next) {
        List<ToolUseBlock> toolCalls = input.toolCalls();
        if (toolCalls == null || toolCalls.isEmpty()) {
            return next.apply(input);
        }

        ArtifactContext artifactCtx = resolveContext(ctx);
        String artifactsRoot = artifactStore.agentPathRoot();
        String allowedPrefix = artifactStore.agentPathPrefixFor(artifactCtx);

        List<ToolUseBlock> rewritten = null;
        for (int i = 0; i < toolCalls.size(); i++) {
            ToolUseBlock toolUse = toolCalls.get(i);
            String toolName = toolUse.getName();
            ToolUseBlock replacement = null;

            if (READ_FILE_TOOL.equals(toolName)) {
                replacement = enforcePath(toolUse, PATH_PARAM, "read", artifactCtx, artifactsRoot, allowedPrefix);
            } else if (WRITE_FILE_TOOL.equals(toolName)) {
                replacement = enforcePath(toolUse, PATH_PARAM, "write", artifactCtx, artifactsRoot, allowedPrefix);
            } else if (SHELL_EXECUTE_TOOL.equals(toolName)) {
                replacement = enforceShellExecute(toolUse, artifactCtx, artifactsRoot, allowedPrefix);
            }

            if (replacement != null) {
                if (rewritten == null) {
                    rewritten = new ArrayList<>(toolCalls);
                }
                rewritten.set(i, replacement);
            }
        }

        if (rewritten != null) {
            ActingInput modified = new ActingInput(rewritten);
            return next.apply(modified);
        }
        return next.apply(input);
    }

    // ==================== Path enforcement ====================

    private ToolUseBlock enforcePath(ToolUseBlock toolUse, String paramName, String verb,
                                      ArtifactContext ctx, String artifactsRoot, String allowedPrefix) {
        String requested = extractString(toolUse.getInput(), paramName);
        if (requested == null || requested.isBlank()) {
            return null;
        }

        // Only enforce inside the artifacts tree
        if (!requested.startsWith(artifactsRoot)) {
            return null;
        }
        if (requested.startsWith(allowedPrefix)) {
            return null;
        }

        log.warn("Blocked cross-tenant artifact {}: requested={} allowed={} (user={}, task={})",
                verb, requested, allowedPrefix, ctx.userBucket(), ctx.taskBucket());

        Map<String, Object> newInput = new java.util.HashMap<>(toolUse.getInput());
        newInput.put(paramName, "/__forbidden__/" + ctx.userBucket() + "/" + ctx.taskBucket());
        return rebuildBlock(toolUse, newInput);
    }

    private ToolUseBlock enforceShellExecute(ToolUseBlock toolUse, ArtifactContext ctx,
                                              String artifactsRoot, String allowedPrefix) {
        String command = extractString(toolUse.getInput(), COMMAND_PARAM);
        String workingDir = extractString(toolUse.getInput(), WORKING_DIR_PARAM);

        if ((command == null || command.isBlank()) && (workingDir == null || workingDir.isBlank())) {
            return null;
        }

        // Fix: LLM sometimes passes absolute paths like /workspace/xxx for working_directory.
        // The framework's ShellExecuteTool rejects absolute paths. Strip the artifacts root
        // prefix to convert /workspace/user-task/xxx → user-task/xxx (relative path).
        // Also fix: strip leading / for any absolute path that isn't cross-tenant.
        Map<String, Object> newInput = null;
        if (workingDir != null && !workingDir.isBlank()) {
            String fixedDir = fixWorkingDirectory(workingDir, artifactsRoot, allowedPrefix);
            if (fixedDir != null && !fixedDir.equals(workingDir)) {
                newInput = new java.util.HashMap<>(toolUse.getInput());
                newInput.put(WORKING_DIR_PARAM, fixedDir);
                log.info("Fixed working_directory: {} -> {} (tool=shell_execute)", workingDir, fixedDir);
            }
        }

        // Cross-tenant violation check (after working_directory fix)
        String effectiveDir = newInput != null
                ? extractString(newInput, WORKING_DIR_PARAM)
                : workingDir;
        String violation = null;
        if (effectiveDir != null && effectiveDir.startsWith(artifactsRoot) && !effectiveDir.startsWith(allowedPrefix)) {
            violation = "working_directory=" + effectiveDir;
        } else if (command != null && containsForeignArtifactPath(command, artifactsRoot, allowedPrefix)) {
            violation = "command references " + artifactsRoot + " outside " + allowedPrefix;
        }

        if (violation != null) {
            log.warn("Blocked cross-tenant shell_execute: {} (user={}, task={})",
                    violation, ctx.userBucket(), ctx.taskBucket());
            if (newInput == null) {
                newInput = new java.util.HashMap<>(toolUse.getInput());
            }
            newInput.put(COMMAND_PARAM,
                    "echo 'shell_execute denied by ArtifactAccessMiddleware: cross-tenant artifact path in"
                            + " command (user=" + ctx.userBucket() + " task=" + ctx.taskBucket()
                            + ")' >&2; exit 77");
            newInput.remove(WORKING_DIR_PARAM);
            return rebuildBlock(toolUse, newInput);
        }

        // No violation, but we may have fixed the working_directory
        if (newInput != null) {
            return rebuildBlock(toolUse, newInput);
        }
        return null;
    }

    /**
     * Fix working_directory paths that the LLM provides as absolute paths.
     * The framework's ShellExecuteTool rejects absolute paths, ~, and paths containing ..
     * This method converts:
     * <ul>
     *   <li>{@code /workspace/user-task/subdir} → {@code user-task/subdir}</li>
     *   <li>{@code /tmp/xxx} → {@code tmp/xxx}</li>
     *   <li>{@code relative/path} → unchanged (already valid)</li>
     * </ul>
     *
     * @return the fixed path, or null if the path cannot be made relative
     */
    private String fixWorkingDirectory(String workingDir, String artifactsRoot, String allowedPrefix) {
        if (workingDir == null || workingDir.isBlank()) return null;
        String dir = workingDir.strip();
        // Already relative — no fix needed
        if (!dir.startsWith("/")) return dir;
        // Cross-tenant — will be blocked by enforceShellExecute
        if (dir.startsWith(artifactsRoot) && !dir.startsWith(allowedPrefix)) return dir;
        // Starts with allowed prefix — strip to make it relative
        if (dir.startsWith(allowedPrefix)) {
            String relative = dir.substring(allowedPrefix.length());
            if (relative.startsWith("/")) relative = relative.substring(1);
            return relative.isEmpty() ? "." : relative;
        }
        // Other absolute paths — strip leading /
        String relative = dir.substring(1);
        return relative.isEmpty() ? "." : relative;
    }

    /**
     * Rebuild a ToolUseBlock preserving the original content and metadata fields.
     * The 3-arg constructor {@code new ToolUseBlock(id, name, newInput)} sets content=null,
     * which causes ToolValidator to reject the call with "argument content is null".
     */
    private static ToolUseBlock rebuildBlock(ToolUseBlock original, Map<String, Object> newInput) {
        return ToolUseBlock.builder()
                .id(original.getId())
                .name(original.getName())
                .input(newInput)
                .content(original.getContent())
                .metadata(original.getMetadata())
                .state(original.getState())
                .build();
    }

    // ==================== Helpers ====================

    private ArtifactContext resolveContext(RuntimeContext ctx) {
        if (fixedContext != null) {
            return fixedContext;
        }
        return ArtifactContext.from(ctx);
    }

    private static boolean containsForeignArtifactPath(String command, String artifactsRoot, String allowedPrefix) {
        int from = 0;
        while (true) {
            int idx = command.indexOf(artifactsRoot, from);
            if (idx < 0) return false;
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