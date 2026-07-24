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
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.message.ToolUseBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * P0-5: Cross-tenant path guard for {@code python_exec}.
 *
 * <p>Sister middleware to {@link ArtifactAccessMiddleware}. The shared sandbox container mounts
 * the artifacts directory for all tenants, so a hallucinating or prompt-injected
 * {@code python_exec} call could do {@code pd.read_csv('../other-user/artifact.csv')} and read
 * another tenant's data. {@link ArtifactAccessMiddleware} only intercepts {@code read_file} /
 * {@code write_file} / {@code shell_execute} - it does NOT look inside the {@code code} parameter
 * of {@code python_exec}. This middleware closes that gap.
 *
 * <p>Strategy:
 * <ol>
 *   <li>On every {@code onActing}, find {@code python_exec} tool calls in {@link ActingInput#toolCalls()}.
 *   <li>Extract the {@code code} parameter (string).
 *   <li>Scan for any substring starting with {@code artifactStore.agentPathRoot()}.
 *   <li>If such a substring does NOT start with the per-call allowed prefix
 *       ({@code artifactStore.agentPathPrefixFor(ctx)}), the call is cross-tenant - rewrite
 *       {@code code} to a failing Python snippet that prints a clean error to stderr and exits 77.
 * </ol>
 *
 * <p>The LLM sees the error in the next {@code ToolResultBlock} and self-corrects, matching
 * {@link ArtifactAccessMiddleware}'s "deny and rewrite, don't drop" pattern.
 *
 * <p><b>Multi-user safety</b>: This middleware is a singleton bean shared across all agent calls.
 * It does NOT cache {@link RuntimeContext} or {@link ArtifactContext} in instance fields - both
 * are resolved per-call from the {@code ctx} parameter passed to {@link #onActing}. This avoids
 * the {@code private volatile RuntimeContext currentCtx} cross-talk anti-pattern documented in
 * optimization-analysis.md P1-2.
 */
public class PythonExecAccessMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(PythonExecAccessMiddleware.class);

    private static final String PYTHON_EXEC_TOOL = "python_exec";
    private static final String CODE_PARAM = "code";

    private final ArtifactStore artifactStore;
    private final ArtifactContext fixedContext;

    /** Test-only ctor: ctx is read from RuntimeContext. */
    public PythonExecAccessMiddleware(ArtifactStore artifactStore) {
        this(artifactStore, null);
    }

    public PythonExecAccessMiddleware(ArtifactStore artifactStore, ArtifactContext fixedContext) {
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
            if (!PYTHON_EXEC_TOOL.equals(toolUse.getName())) {
                continue;
            }
            String code = extractString(toolUse.getInput(), CODE_PARAM);
            if (code == null || code.isBlank()) {
                continue;
            }

            // Step 1: Repair incomplete artifact paths — LLM sometimes writes
            // /workspace/artifacts/somefile.csv instead of /workspace/artifacts/user/task/somefile.csv
            String repairedCode = repairIncompleteArtifactPaths(code, artifactsRoot, allowedPrefix);

            // Step 2: Check for cross-tenant paths (after repair, so incomplete paths
            // that were repaired are no longer flagged as cross-tenant)
            String codeToScan = repairedCode != null ? repairedCode : code;
            String foreignPath = findForeignArtifactPath(codeToScan, artifactsRoot, allowedPrefix);
            if (foreignPath != null) {
                log.warn("Blocked cross-tenant python_exec: code references {} outside {} (user={}, task={})",
                        foreignPath, allowedPrefix, artifactCtx.userBucket(), artifactCtx.taskBucket());

                Map<String, Object> newInput = new HashMap<>(toolUse.getInput());
                newInput.put(CODE_PARAM,
                        "import sys\n"
                                + "sys.stderr.write('python_exec denied by PythonExecAccessMiddleware: "
                                + "cross-tenant artifact path in code (user=" + artifactCtx.userBucket()
                                + " task=" + artifactCtx.taskBucket() + ") path=" + foreignPath + "\\n')\n"
                                + "sys.exit(77)\n");
                ToolUseBlock replacement = new ToolUseBlock(
                        toolUse.getId(), toolUse.getName(), newInput,
                        toolUse.getContent(), toolUse.getMetadata(), toolUse.getState());
                if (rewritten == null) {
                    rewritten = new ArrayList<>(toolCalls);
                }
                rewritten.set(i, replacement);
                continue;
            }

            // Step 3: If we repaired incomplete paths but no cross-tenant violation,
            // apply the repaired code
            if (repairedCode != null) {
                Map<String, Object> newInput = new HashMap<>(toolUse.getInput());
                newInput.put(CODE_PARAM, repairedCode);
                ToolUseBlock replacement = new ToolUseBlock(
                        toolUse.getId(), toolUse.getName(), newInput,
                        toolUse.getContent(), toolUse.getMetadata(), toolUse.getState());
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

    // ==================== Helpers ====================

    /**
     * Repair incomplete artifact paths in python_exec code.
     * LLMs sometimes write paths like {@code /workspace/artifacts/somefile.csv}
     * instead of the correct {@code /workspace/artifacts/userBucket/taskBucket/somefile.csv}.
     * This method finds such incomplete paths and inserts the allowedPrefix.
     *
     * @return the repaired code, or null if no repair was needed
     */
    private static String repairIncompleteArtifactPaths(String code, String artifactsRoot, String allowedPrefix) {
        if (artifactsRoot == null || artifactsRoot.isEmpty() || code == null) {
            return null;
        }
        // Find all occurrences of artifactsRoot in the code
        int from = 0;
        StringBuilder sb = null;
        while (true) {
            int idx = code.indexOf(artifactsRoot, from);
            if (idx < 0) break;
            int end = findPathEnd(code, idx + artifactsRoot.length());
            String candidate = code.substring(idx, end);

            if (candidate.startsWith(allowedPrefix)) {
                // Already correct — skip
                from = end;
                continue;
            }
            // Check if this is an incomplete path: starts with artifactsRoot but
            // doesn't start with any user/task bucket pattern.
            // An incomplete path looks like: /workspace/artifacts/somefile.csv
            // (missing the userBucket/taskBucket/ segments)
            // It could also be a cross-tenant path: /workspace/artifacts/otheruser/...
            // We distinguish by checking if the path segment after artifactsRoot/ is NOT
            // a user bucket (i.e., it's a filename without bucket structure).
            String afterRoot = candidate.substring(artifactsRoot.length());
            // If afterRoot starts with something that looks like a filename (no slash),
            // it's an incomplete path — insert the allowedPrefix
            if (!afterRoot.contains("/")) {
                // Incomplete path: /workspace/artifacts/filename.csv
                // → /workspace/artifacts/userBucket/taskBucket/filename.csv
                if (sb == null) {
                    sb = new StringBuilder(code.length() + 64);
                }
                sb.append(code, from, idx);
                sb.append(allowedPrefix);
                sb.append(afterRoot);
                from = end;
            } else {
                // Has slashes — could be another user's bucket or a partial bucket path
                // Don't repair; let cross-tenant check handle it
                from = end;
            }
        }
        if (sb != null) {
            // Append remaining code after the last replacement
            sb.append(code, from, code.length());
            return sb.toString();
        }
        return null;
    }

    private ArtifactContext resolveContext(RuntimeContext ctx) {
        if (fixedContext != null) {
            return fixedContext;
        }
        // Priority: pinned ArtifactContext from main agent's RuntimeContext.
        // This ensures sub-agents (whose sessionId="sub-xxx") use the parent's
        // artifact bucket instead of their own isolated bucket.
        if (ctx != null) {
            ArtifactContext pinned = ctx.get(ArtifactContext.class);
            if (pinned != null) {
                return pinned;
            }
        }
        return ArtifactContext.from(ctx);
    }

    /**
     * Returns the first substring of {@code code} that starts with {@code artifactsRoot} but NOT
     * with {@code allowedPrefix}, or {@code null} if no such substring exists.
     *
     * <p>Path-aware scan: once {@code artifactsRoot} is found, the candidate path extends until
     * the next whitespace, quote, or parenthesis - this catches {@code pd.read_csv('/workspace/...')}
     * as well as bare {@code open('/workspace/...')} calls. False positives (path embedded in a
     * longer string) only trigger a deny, which fails safely.
     */
    private static String findForeignArtifactPath(String code, String artifactsRoot, String allowedPrefix) {
        if (artifactsRoot == null || artifactsRoot.isEmpty()) {
            return null;
        }
        int from = 0;
        while (true) {
            int idx = code.indexOf(artifactsRoot, from);
            if (idx < 0) return null;
            int end = findPathEnd(code, idx + artifactsRoot.length());
            String candidate = code.substring(idx, end);
            if (!candidate.startsWith(allowedPrefix)) {
                return candidate;
            }
            from = end;
        }
    }

    /** Returns the index just past the end of a path starting at {@code start}. */
    private static int findPathEnd(String code, int start) {
        int end = start;
        while (end < code.length()) {
            char c = code.charAt(end);
            if (Character.isWhitespace(c) || c == '"' || c == '\'' || c == ')' || c == ','
                    || c == ';' || c == '`' || c == '\n' || c == '\r') {
                break;
            }
            end++;
        }
        return end;
    }

    private static String extractString(Map<String, Object> input, String key) {
        if (input == null) return null;
        Object raw = input.get(key);
        return raw == null ? null : raw.toString();
    }
}
