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
package com.agentscopea2a.service;

import com.agentscopea2a.harness.artifact.ArtifactContext;
import com.agentscopea2a.harness.artifact.ArtifactStore;
import com.agentscopea2a.harness.cache.ResponseCacheService;
import com.agentscopea2a.agent.model.ModelRegistry;
import com.agentscopea2a.harness.config.PersistenceProperties;
import com.agentscopea2a.agent.dimension.DimensionStateManager;
import com.agentscopea2a.harness.hooks.ArtifactAccessHook;
import com.agentscopea2a.harness.hooks.ArtifactHandoffHook;
import com.agentscopea2a.harness.hooks.DataGroundingHook;
import com.agentscopea2a.harness.hooks.ResponseCacheHook;
import com.agentscopea2a.agent.memory.EpisodicLongTermMemoryAdapter;
import com.agentscopea2a.agent.session.MySQLSession;
import com.agentscopea2a.harness.tools.QualityTools;
import com.agentscopea2a.harness.tools.SkillSaveTool;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.memory.LongTermMemoryMode;
import com.agentscopea2a.agent.memory.EpisodicMemoryConfig;
import com.agentscopea2a.agent.memory.MySqlEpisodicMemory;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import io.agentscope.harness.agent.subagent.AgentSpecLoader;
import io.agentscope.harness.agent.subagent.SubagentSpec;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Per-request {@link HarnessAgent} factory.
 *
 * <p>Subagents are <b>fully Markdown-driven</b>: prompts and tool declarations both live in
 * {@code workspace/subagents/*.md}. Adding or modifying a subagent requires editing only the
 * Markdown file — no Java change needed, provided every declared tool name exists in the
 * {@link #toolRegistry}.
 *
 * <p>Long-term memory is per-user (or per-session when no user context is available), keyed off
 * {@link RuntimeContext} at {@code build(...)} time — see {@link #initLongTermMemoryFor(String)}.
 */
@Service
public class SupervisorService {

    private static final Logger log = LoggerFactory.getLogger(SupervisorService.class);

    private static final String AGENT_NAME = "QualitySupervisor";
    private static final String LTM_BUCKET_FALLBACK = "anonymous";

    private final Path workspace;
    private final MySQLSession session;
    private final Model model;
    private final ModelRegistry modelRegistry;
    private final PersistenceProperties persistence;
    private final ArtifactStore artifactStore;


    private final Map<String, Supplier<Object>> toolRegistry;

    /** Cached, parsed subagent specs from {@code workspace/subagents/}. Loaded once at startup. */
    private final List<SubagentSpec> workspaceSubagents = new java.util.ArrayList<>();

    // Tunable thresholds — defaults match production; lowered via env for verification runs.
    @org.springframework.beans.factory.annotation.Value("${harness.a2a.compaction.trigger:40}")
    private int compactionTriggerMessages;

    @org.springframework.beans.factory.annotation.Value("${harness.a2a.compaction.keep:12}")
    private int compactionKeepMessages;

    @org.springframework.beans.factory.annotation.Value(
            "${harness.a2a.tool-eviction.max-chars:80000}")
    private int toolEvictionMaxChars;

    public SupervisorService(
            Path workspace,
            MySQLSession session,
            ResponseCacheService cacheService,
            DimensionStateManager cacheDimManager,
            Model model,
            ModelRegistry modelRegistry,
            PersistenceProperties persistence,
            ArtifactStore artifactStore) {
        this.workspace = workspace;
        this.session = session;
        this.model = model;
        this.modelRegistry = modelRegistry;
        this.persistence = persistence;
        this.artifactStore = artifactStore;
        this.toolRegistry = buildToolRegistry(workspace);
    }

    @PostConstruct
    void init() {
        Path subagentsDir = workspace.resolve("subagents");
        if (!Files.isDirectory(subagentsDir)) {
            log.warn(
                    "Subagents directory not found at {} — no Markdown subagents will be loaded",
                    subagentsDir);
        } else {
            workspaceSubagents.addAll(AgentSpecLoader.loadFromDirectory(subagentsDir));
            log.info(
                    "Loaded {} Markdown subagent spec(s) from {}: {}",
                    workspaceSubagents.size(),
                    subagentsDir,
                    workspaceSubagents.stream().map(SubagentSpec::getName).toList());
            // Verify every declared tool exists in the registry — fail fast at startup.
            for (SubagentSpec spec : workspaceSubagents) {
                List<String> tools = spec.getTools() != null ? spec.getTools() : List.of();
                for (String t : tools) {
                    if (!toolRegistry.containsKey(t)) {
                        throw new IllegalStateException(
                                "Subagent '"
                                        + spec.getName()
                                        + "' declares unknown tool '"
                                        + t
                                        + "'. Known tools: "
                                        + toolRegistry.keySet());
                    }
                }
            }
        }
        log.info("SupervisorService ready: workspace={}", workspace);
    }

    public HarnessAgent build(ResponseCacheHook cacheHook, RuntimeContext ctx) {
        Toolkit toolkit = new Toolkit();

        ArtifactContext requestArtifactCtx = ArtifactContext.from(ctx);

        LongTermMemory longTermMemory = initLongTermMemoryFor(ltmBucketFor(ctx));

        HarnessAgent.Builder b =
                HarnessAgent.builder()
                        .name(AGENT_NAME)
                        .model(model)
                        .workspace(workspace)
                        .toolkit(toolkit)
                        .session(session)
                        .longTermMemory(longTermMemory)
                        .longTermMemoryMode(LongTermMemoryMode.BOTH)
                        .compaction(
                                CompactionConfig.builder()
                                        .triggerMessages(compactionTriggerMessages)
                                        .keepMessages(compactionKeepMessages)
                                        .flushBeforeCompact(true)
                                        .build())
                        .toolResultEviction(
                                ToolResultEvictionConfig.builder()
                                        .maxResultChars(toolEvictionMaxChars)
                                        .build())
                        .enablePendingToolRecovery(true)
                        .maxIters(15);

        for (SubagentSpec spec : workspaceSubagents) {
            registerSubagentFromSpec(b, spec, requestArtifactCtx);
        }

        // -- Business hooks --
        if (cacheHook != null) {
            b.hook(cacheHook); // priority 0
        }

        b.hook(new ArtifactHandoffHook(artifactStore, requestArtifactCtx));
        b.hook(new DataGroundingHook());

        return b.build();
    }

    /** Create a new per-request ResponseCacheHook, scoped by ctx + emitting Micrometer counters. */
    public ResponseCacheHook newCacheHook(
            ResponseCacheService cacheService,
            DimensionStateManager cacheDimManager,
            RuntimeContext ctx,
            io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        return new ResponseCacheHook(cacheService, cacheDimManager, ctx, meterRegistry);
    }

    private void registerSubagentFromSpec(
            HarnessAgent.Builder parent, SubagentSpec spec, ArtifactContext pinnedArtifactCtx) {
        final String agentId = spec.getName();
        final String sysPrompt = spec.getSysPrompt();
        final int maxIters = spec.getMaxIters() > 0 ? spec.getMaxIters() : 5;
        final List<String> toolNames = spec.getTools() != null ? spec.getTools() : List.of();

        parent.subagentFactory(
                agentId,
                id -> {
                    Toolkit tk = new Toolkit();
                    for (String name : toolNames) {
                        Supplier<Object> supplier = toolRegistry.get(name);
                        if (supplier == null) {
                            // Should be impossible after the @PostConstruct check, but guard
                            // anyway — a stale workspace edit could trigger this at runtime.
                            log.warn(
                                    "Subagent '{}' references unknown tool '{}'; skipping",
                                    id,
                                    name);
                            continue;
                        }
                        tk.registerTool(supplier.get());
                    }
                    HarnessAgent.Builder sub =
                            HarnessAgent.builder()
                                    .name(id)
                                    .model(modelRegistry.getForSubagent(id))
                                    .workspace(workspace)
                                    .toolkit(tk)
                                    .sysPrompt(sysPrompt)
                                    .enablePendingToolRecovery(true)
                                    .maxIters(maxIters);

                    sub.hook(new ArtifactHandoffHook(artifactStore, pinnedArtifactCtx));
                    sub.hook(new ArtifactAccessHook(artifactStore, pinnedArtifactCtx));
                    return sub.build();
                });
    }

    private Map<String, Supplier<Object>> buildToolRegistry(Path workspace) {
        Map<String, Supplier<Object>> r = new HashMap<>();
        r.put("quality_tools", QualityTools::new);
        r.put("skill_save", () -> new SkillSaveTool(workspace.resolve("skills")));
        return Map.copyOf(r);
    }


    private static String ltmBucketFor(RuntimeContext ctx) {
        if (ctx == null) {
            return LTM_BUCKET_FALLBACK;
        }
        String userId = ctx.getUserId();
        if (userId != null && !userId.isBlank()) {
            return "user:" + userId;
        }
        String sessionId = ctx.getSessionId();
        if (sessionId != null && !sessionId.isBlank()) {
            return "session:" + sessionId;
        }
        return LTM_BUCKET_FALLBACK;
    }

    /**
     * Builds a {@link LongTermMemory} bound to a specific bucket (per user/session/anon).
     * Connection params come from {@link PersistenceProperties} — no hardcoded secrets.
     */
    private LongTermMemory initLongTermMemoryFor(String bucket) {
        EpisodicMemoryConfig episodicConfig =
                EpisodicMemoryConfig.builder()
                        .jdbcUrl(persistence.jdbcUrl())
                        .username(persistence.username())
                        .password(persistence.password())
                        .tableName(AGENT_NAME + "_episodic_memory")
                        .searchLimit(5)
                        .build();
        MySqlEpisodicMemory episodic = new MySqlEpisodicMemory(episodicConfig);
        return new EpisodicLongTermMemoryAdapter(episodic, bucket);
    }
}
