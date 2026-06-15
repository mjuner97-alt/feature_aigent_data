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
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Stops cross-tenant {@code read_file} access into the artifacts directory.
 *
 * <p>The artifact bind mount is shared across all tenants (Docker can't bind-mount a different
 * per-tenant host directory into the same container path, and reusing a single container per
 * scope is more efficient than per-task containers). Path-based isolation alone is not enough —
 * a hallucinating / injected {@code code_interpreter} could trivially {@code read_file} into
 * another user's bucket.
 *
 * <p>This hook intercepts {@link PreActingEvent} for the {@code read_file} tool and synthesizes
 * a {@code Forbidden} {@link ToolResultBlock} when the path is inside the artifacts root but
 * outside the current task's bucket. Reads of non-artifact paths (e.g. {@code /workspace/run.py})
 * pass through untouched.
 *
 * <p>To deny the call without actually running the underlying tool, we hijack the
 * {@link ToolUseBlock} parameters so the original tool either errors out or reads a guaranteed-
 * empty replacement. The harness exposes hook-driven short-circuiting through this trick
 * because there's no {@code PreActingEvent.shortCircuit(result)} API yet (same pattern as
 * {@code ResponseCacheHook.CacheHitException} for short-circuiting the whole call).
 */
public class ArtifactAccessHook implements Hook, RuntimeContextAware {

    private static final Logger log = LoggerFactory.getLogger(ArtifactAccessHook.class);
    private static final String READ_FILE_TOOL = "read_file";

    /** Conventional parameter name in harness's FilesystemTool. */
    private static final String PATH_PARAM = "path";

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
        if (toolUse == null || !READ_FILE_TOOL.equals(toolUse.getName())) {
            return Mono.just(event);
        }
        String requested = extractPath(toolUse.getInput());
        if (requested == null || requested.isBlank()) {
            return Mono.just(event);
        }

        ArtifactContext ctx =
                fixedContext != null ? fixedContext : ArtifactContext.from(runtimeContext);
        String artifactsRoot = artifactStore.agentPathRoot();
        String allowedPrefix = artifactStore.agentPathPrefixFor(ctx);

        // Only enforce inside the artifacts tree. Reading run.py / arbitrary other workspace
        // files is fine — that's how code_interpreter writes and re-reads its own scripts.
        if (!requested.startsWith(artifactsRoot)) {
            return Mono.just(event);
        }
        if (requested.startsWith(allowedPrefix)) {
            return Mono.just(event);
        }

        log.warn(
                "Blocked cross-tenant artifact read: requested={} allowed={} (user={}, task={})",
                requested,
                allowedPrefix,
                ctx.userBucket(),
                ctx.taskBucket());

        // Replace the path argument with a sentinel that read_file will fail open on; the failure
        // message + log line make the violation visible to the user.
        Map<String, Object> input = new java.util.HashMap<>(toolUse.getInput());
        input.put(PATH_PARAM, "/__forbidden__/" + ctx.userBucket() + "/" + ctx.taskBucket());
        ToolUseBlock rewritten = new ToolUseBlock(toolUse.getId(), toolUse.getName(), input);
        pre.setToolUse(rewritten);

        return Mono.just(event);
    }

    private static String extractPath(Map<String, Object> input) {
        if (input == null) return null;
        Object raw = input.get(PATH_PARAM);
        return raw == null ? null : raw.toString();
    }
}
