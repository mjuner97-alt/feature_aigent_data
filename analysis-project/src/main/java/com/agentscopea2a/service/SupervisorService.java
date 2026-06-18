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
import com.agentscopea2a.agent.memory.MemoryHydrator;
import com.agentscopea2a.agent.session.MySQLSession;
import com.agentscopea2a.harness.tools.QualityTools;
import com.agentscopea2a.harness.tools.SkillSaveTool;
import com.agentscopea2a.agent.tools.routers.ToolRoutersIndex;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.memory.LongTermMemoryMode;
import com.agentscopea2a.agent.memory.EpisodicMemoryConfig;
import com.agentscopea2a.agent.memory.MySqlEpisodicMemory;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import io.agentscope.harness.agent.sandbox.SandboxDistributedOptions;
import io.agentscope.harness.agent.subagent.AgentSpecLoader;
import io.agentscope.harness.agent.subagent.SubagentSpec;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private final DataSource dataSource;
    private final ToolRoutersIndex toolRoutersIndex;

    /**
     * Optional DB→file hydrator for MEMORY.md; {@code null} when MySQL mirror is disabled.
     * Wired via {@link ObjectProvider} so the bean only materialises when {@code
     * harness.a2a.memory.mysql-mirror.enabled=true} — leaves the legacy file-only path intact
     * by default.
     */
    private final MemoryHydrator memoryHydrator;

    /**
     * Optional sandbox/remote filesystem specs. All four resolve to {@code null} when the
     * corresponding {@code @ConditionalOnProperty} bean isn't materialised — the legacy local-FS
     * path stays default. Wired through {@link ObjectProvider} so that sandbox profile remains
     * fully off by default.
     */
    private final SandboxFilesystemSpec sandboxFilesystem;
    private final SandboxFilesystemSpec subagentSandboxFilesystem;
    private final RemoteFilesystemSpec remoteFilesystem;
    private final SandboxDistributedOptions sandboxDistributed;

    /**
     * Single-thread daemon executor used to run {@link MemoryHydrator#hydrate(String)}
     * off the request hot-path. The hydrator does one SELECT and a possible file write;
     * agent build() must not wait for it. Sized at 1 because hydration is
     * idempotent and serial-by-user is fine — concurrent hydrates of the same user would
     * race the file write anyway.
     */
    private final ExecutorService memoryHydrateExecutor =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread t = new Thread(r, "memory-hydrate");
                        t.setDaemon(true);
                        return t;
                    });


    private final Map<String, Supplier<Object>> toolRegistry;

    /** Cached, parsed subagent specs from {@code workspace/subagents/}. Loaded once at startup. */
    private final List<SubagentSpec> workspaceSubagents = new java.util.ArrayList<>();

    // 数据校验 hook 开关 — 关闭后 supervisor 不再在 PostCall/PostActing 跑正则扫描小数。
    // 默认 true 保持原有行为(防 LLM 编造数字)。压测或确认数据可信时可关。
    @Value("${harness.a2a.data-grounding.enabled:true}")
    private boolean dataGroundingEnabled;

    /**
     * Process-wide episodic memory instance. Built once in {@link #init()} (warmup runs the DDL
     * exactly once), then reused by every request via {@link #initLongTermMemoryFor(String)}.
     * Avoids re-running {@code CREATE TABLE IF NOT EXISTS} per request and lets the underlying
     * HikariCP {@link DataSource} pool connections instead of opening a fresh JDBC handshake on
     * every search/record. The volatile is for the warmup-thread → request-thread handoff.
     */
    private volatile MySqlEpisodicMemory sharedEpisodicMemory;

    // Tunable thresholds — defaults match production; lowered via env for verification runs.
    @org.springframework.beans.factory.annotation.Value("${harness.a2a.compaction.trigger:40}")
    private int compactionTriggerMessages;

    @org.springframework.beans.factory.annotation.Value("${harness.a2a.compaction.keep:12}")
    private int compactionKeepMessages;

    @org.springframework.beans.factory.annotation.Value(
            "${harness.a2a.tool-eviction.max-chars:80000}")
    private int toolEvictionMaxChars;

    @Value("${harness.a2a.tool-execution.timeout-seconds:300}")
    private long toolExecutionTimeoutSeconds;

    @Value("${harness.a2a.response-cache.enabled:true}")
    private boolean responseCacheEnabled;

    public SupervisorService(
            Path workspace,
            MySQLSession session,
            ResponseCacheService cacheService,
            DimensionStateManager cacheDimManager,
            Model model,
            ModelRegistry modelRegistry,
            PersistenceProperties persistence,
            @Qualifier("mysqlDataSource") DataSource dataSource,
            ArtifactStore artifactStore,
            ObjectProvider<MemoryHydrator> memoryHydratorProvider,
            ToolRoutersIndex toolRoutersIndex,
            ObjectProvider<SandboxFilesystemSpec> sandboxFilesystemProvider,
            @Qualifier("subagentSandboxFilesystem")
                    ObjectProvider<SandboxFilesystemSpec> subagentSandboxFilesystemProvider,
            ObjectProvider<RemoteFilesystemSpec> remoteFilesystemProvider,
            ObjectProvider<SandboxDistributedOptions> sandboxDistributedProvider) {
        this.workspace = workspace;
        this.session = session;
        this.model = model;
        this.modelRegistry = modelRegistry;
        this.persistence = persistence;
        this.dataSource = dataSource;
        this.artifactStore = artifactStore;
        this.toolRoutersIndex = toolRoutersIndex;
        this.memoryHydrator = memoryHydratorProvider.getIfAvailable();
        this.sandboxFilesystem = sandboxFilesystemProvider.getIfAvailable();
        // The @Bean returns null when subagent scope == supervisor scope; ObjectProvider will
        // also report null when the conditional bean isn't created at all. Either way: null.
        this.subagentSandboxFilesystem = subagentSandboxFilesystemProvider.getIfAvailable();
        this.remoteFilesystem = remoteFilesystemProvider.getIfAvailable();
        this.sandboxDistributed = sandboxDistributedProvider.getIfAvailable();
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

        // Warm the episodic memory table off the request path. Without this, the first
        // /chatA2A request pays ~13s for JDBC connect + CREATE TABLE IF NOT EXISTS the
        // moment build() instantiates MySqlEpisodicMemory. We fire on a daemon thread so
        // Spring's startup is not blocked either — failure here is non-fatal (a broken DB
        // would surface on first real call anyway, with the same error semantics).
        Thread warmup = new Thread(this::warmEpisodicMemory, "episodic-memory-warmup");
        warmup.setDaemon(true);
        warmup.start();
    }

    /**
     * Forces a connection + {@code CREATE TABLE IF NOT EXISTS} on the episodic memory table
     * so the first user request doesn't pay for it. Driven by a tiny no-op search since
     * {@code ensureInitialized()} is package-private to {@link MySqlEpisodicMemory}.
     */
    private void warmEpisodicMemory() {
        try {
            EpisodicMemoryConfig cfg =
                    EpisodicMemoryConfig.builder()
                            .jdbcUrl(persistence.jdbcUrl())
                            .username(persistence.username())
                            .password(persistence.password())
                            .tableName(AGENT_NAME + "_episodic_memory")
                            .searchLimit(5)
                            .build();
            MySqlEpisodicMemory probe = new MySqlEpisodicMemory(dataSource, cfg);
            // Triggers ensureInitialized() via the search path. Result is discarded.
            probe.search("__warmup__", 1).block();
            this.sharedEpisodicMemory = probe;
            log.info("Episodic memory table warmed: {}", cfg.getTableName());
        } catch (Exception e) {
            log.warn(
                    "Episodic memory warmup failed (will retry lazily on first request): {}",
                    e.getMessage());
        }
    }

    public HarnessAgent build(ResponseCacheHook cacheHook, RuntimeContext ctx) {
        Toolkit toolkit = new Toolkit();

        ArtifactContext requestArtifactCtx = ArtifactContext.from(ctx);

        // Kick off DB → file MEMORY.md hydration off the hot path. The hydrator only writes
        // the file when it's missing/blank — every other case is a single SELECT — so this
        // is sub-millisecond steady-state, but we still off-load it because we don't want
        // a transient DB hiccup to delay agent start. No-op when memory mirror is disabled.
        if (memoryHydrator != null && ctx != null && ctx.getUserId() != null) {
            String userId = ctx.getUserId();
            memoryHydrateExecutor.submit(
                    () -> {
                        try {
                            memoryHydrator.hydrate(userId);
                        } catch (Exception ex) {
                            log.warn("Async hydrate({}) failed: {}", userId, ex.getMessage());
                        }
                    });
        }

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
                        .toolExecutionConfig(toolExecutionConfig())
                        .enablePendingToolRecovery(true)
                        .maxIters(15);

        // Sandbox / remote filesystem wiring. All optional — beans are null when their
        // @ConditionalOnProperty toggles are off, in which case the harness uses local FS.
        if (sandboxFilesystem != null) {
            b.filesystem(sandboxFilesystem);
            if (sandboxDistributed != null) {
                b.sandboxDistributed(sandboxDistributed);
            }
        } else if (remoteFilesystem != null) {
            // Distributed mode without a sandbox container — RemoteFilesystemSpec routes
            // skills/memory through the shared BaseStore so two replicas converge.
            b.filesystem(remoteFilesystem);
        }

        for (SubagentSpec spec : workspaceSubagents) {
            registerSubagentFromSpec(b, spec, requestArtifactCtx);
        }

        // -- Business hooks --
        if (cacheHook != null) {
            b.hook(cacheHook); // priority 0
        }

        b.hook(new ArtifactHandoffHook(artifactStore, requestArtifactCtx));
        if (dataGroundingEnabled) {
            b.hook(new DataGroundingHook());
        }

        return b.build();
    }

    /** Create a new per-request ResponseCacheHook, scoped by ctx + emitting Micrometer counters. */
    public ResponseCacheHook newCacheHook(
            ResponseCacheService cacheService,
            DimensionStateManager cacheDimManager,
            RuntimeContext ctx,
            io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        return new ResponseCacheHook(cacheService, cacheDimManager, ctx, meterRegistry, responseCacheEnabled);
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
                                    .toolExecutionConfig(toolExecutionConfig())
                                    .enablePendingToolRecovery(true)
                                    .maxIters(maxIters)
                                    // 子 agent 跳过 MemoryFlushHook + MemoryMaintenanceHook:
                                    // 两者每次 PostCall 都触发额外 LLM 调用与 SSH 推送,阻塞
                                    // 父 agent 的 task_output / agent_spawn 返回。父 supervisor
                                    // 仍保留 hook,MEMORY.md 与每日 ledger 由它统一维护。
                                    .disableMemoryHooks();

                    // Subagents inherit the supervisor's sandbox spec by default; an optional
                    // override (USER / GLOBAL scope) takes precedence when configured.
                    SandboxFilesystemSpec subSpec =
                            subagentSandboxFilesystem != null
                                    ? subagentSandboxFilesystem
                                    : sandboxFilesystem;
                    if (subSpec != null) {
                        sub.filesystem(subSpec);
                        if (sandboxDistributed != null) {
                            sub.sandboxDistributed(sandboxDistributed);
                        }
                    } else if (remoteFilesystem != null) {
                        sub.filesystem(remoteFilesystem);
                    }

                    sub.hook(new ArtifactHandoffHook(artifactStore, pinnedArtifactCtx));
                    sub.hook(new ArtifactAccessHook(artifactStore, pinnedArtifactCtx));
                    return sub.build();
                });
    }

    private Map<String, Supplier<Object>> buildToolRegistry(Path workspace) {
        Map<String, Supplier<Object>> r = new HashMap<>();
        r.put("quality_tools", QualityTools::new);
        r.put("skill_save", () -> new SkillSaveTool(workspace.resolve("skills")));
        r.put("tool_router", () -> toolRoutersIndex);
        return Map.copyOf(r);
    }

    private ExecutionConfig toolExecutionConfig() {
        return ExecutionConfig.builder()
                .timeout(Duration.ofSeconds(toolExecutionTimeoutSeconds))
                .build();
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
     *
     * <p>Reuses the warmed-up {@link #sharedEpisodicMemory} when available so each request shares
     * the pooled HikariCP {@link DataSource} instead of opening a brand-new JDBC connection.
     * Falls back to a fresh DriverManager-backed instance only if warmup hasn't completed yet
     * (very early request) or failed (broken DB).
     */
    private LongTermMemory initLongTermMemoryFor(String bucket) {
        MySqlEpisodicMemory episodic = this.sharedEpisodicMemory;
        if (episodic == null) {
            EpisodicMemoryConfig episodicConfig =
                    EpisodicMemoryConfig.builder()
                            .jdbcUrl(persistence.jdbcUrl())
                            .username(persistence.username())
                            .password(persistence.password())
                            .tableName(AGENT_NAME + "_episodic_memory")
                            .searchLimit(5)
                            .build();
            episodic = new MySqlEpisodicMemory(dataSource, episodicConfig);
        }
        return new EpisodicLongTermMemoryAdapter(episodic, bucket);
    }
}
