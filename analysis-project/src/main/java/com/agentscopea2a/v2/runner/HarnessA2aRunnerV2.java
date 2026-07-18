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
package com.agentscopea2a.v2.runner;

import com.agentscopea2a.v2.hooks.ArtifactHandoffHook;
import com.agentscopea2a.v2.hooks.KnowledgeRetrievalHook;
import com.agentscopea2a.v2.hooks.PythonExecRetryHook;
import com.agentscopea2a.v2.hooks.SkillEvolutionHook;
import com.agentscopea2a.v2.hooks.SkillRetrievalHook;
import com.agentscopea2a.v2.hooks.SkillSynthesisHook;
import com.agentscopea2a.v2.hooks.ToolCallTrackingHook;
import com.agentscopea2a.v2.middleware.ArtifactAccessMiddleware;
import com.agentscopea2a.v2.middleware.DimensionStateMiddleware;
import com.agentscopea2a.v2.middleware.EpisodicRetrievalMiddleware;
import com.agentscopea2a.v2.middleware.MemoryLedgerMirrorMiddleware;
import com.agentscopea2a.v2.middleware.PythonExecAccessMiddleware;
import com.agentscopea2a.v2.middleware.ResponseCacheMiddleware;
import com.agentscopea2a.v2.middleware.SessionMiddleware;
import com.agentscopea2a.v2.model.FallbackModelDecorator;
import com.agentscopea2a.v2.tools.V2ToolGroupAdapter;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import com.agentscopea2a.v2.state.SanitizingAgentStateStore;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import io.agentscope.harness.agent.skill.curator.CompositeFilter;
import io.agentscope.harness.agent.skill.curator.LocalApprovalGate;
import io.agentscope.harness.agent.skill.curator.SkillCuratorConfig;
import io.agentscope.harness.agent.skill.curator.SkillVisibilityFilter;
import io.agentscope.harness.agent.tool.SkillManageConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * v2 入口：基于 {@link HarnessAgent#builder()} 装配能力，替代 v1 {@code HarnessA2aRunner}。
 *
 * <p>阶段 1-2：workspace + memory + compaction + toolResultEviction + model +
 * enablePlanMode + enableTaskList + stateStore + MemoryConfig 小模型。
 *
 * <p>阶段 3：+ SkillCurator pipeline + 业务 middleware。
 *
 * <p>每次请求复用同一个 {@link HarnessAgent}（线程安全），通过 {@link RuntimeContext} 区分 session。
 */
@Component
public class HarnessA2aRunnerV2 {

    private static final Logger log = LoggerFactory.getLogger(HarnessA2aRunnerV2.class);

    private final HarnessAgent agent;

    public HarnessA2aRunnerV2(
            @Value("${harness.a2a.model.instances.glm-main.api-key}") String apiKey,
            @Value("${harness.a2a.model.instances.glm-main.base-url}") String baseUrl,
            @Value("${harness.a2a.model.instances.glm-main.name}") String modelName,
            @Value("${harness.a2a.workspace.path:.agentscope/workspace/harness-a2a}") String workspacePath,
            @Value("${harness.a2a.model.instances.light-classifier.api-key}") String lightApiKey,
            @Value("${harness.a2a.model.instances.light-classifier.base-url}") String lightBaseUrl,
            @Value("${harness.a2a.model.instances.light-classifier.name}") String lightModelName,
            @Value("${harness.a2a.model.instances.fallback.api-key:}") String fallbackApiKey,
            @Value("${harness.a2a.model.instances.fallback.base-url:}") String fallbackBaseUrl,
            @Value("${harness.a2a.model.instances.fallback.name:}") String fallbackModelName,
            DataSource dataSource,
            SkillManageConfig skillManageConfig,
            SkillCuratorConfig skillCuratorConfig,
            LocalApprovalGate localApprovalGate,
            SkillVisibilityFilter skillVisibilityFilter,
            DimensionStateMiddleware dimensionStateMiddleware,
            EpisodicRetrievalMiddleware episodicRetrievalMiddleware,
            ResponseCacheMiddleware responseCacheMiddleware,
            ArtifactAccessMiddleware artifactAccessMiddleware,
            SessionMiddleware sessionMiddleware,
            ObjectProvider<PythonExecAccessMiddleware> pythonExecAccessMiddlewareProvider,
            ObjectProvider<MemoryLedgerMirrorMiddleware> memoryLedgerMirrorProvider,
            ObjectProvider<ArtifactHandoffHook> artifactHandoffHookProvider,
            ObjectProvider<PythonExecRetryHook> pythonExecRetryHookProvider,
            ObjectProvider<ToolCallTrackingHook> toolCallTrackingHookProvider,
            ObjectProvider<SkillRetrievalHook> skillRetrievalHookProvider,
            ObjectProvider<SkillSynthesisHook> skillSynthesisHookProvider,
            ObjectProvider<SkillEvolutionHook> skillEvolutionHookProvider,
            ObjectProvider<KnowledgeRetrievalHook> knowledgeRetrievalHookProvider,
            ObjectProvider<V2ToolGroupAdapter> toolGroupAdapterProvider,
            ObjectProvider<SandboxFilesystemSpec> sandboxFilesystemProvider,
            ObjectProvider<RemoteFilesystemSpec> remoteFilesystemProvider,
            ObjectProvider<DistributedStore> distributedStoreProvider,
            ObjectProvider<SubagentRegistrar> subagentRegistrarProvider) {
        OpenAIChatModel model = OpenAIChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .stream(true)
                .build();

        OpenAIChatModel smallModel = OpenAIChatModel.builder()
                .apiKey(lightApiKey)
                .baseUrl(lightBaseUrl)
                .modelName(lightModelName)
                .stream(true)
                .build();

        // Fallback model - wraps the primary model so that 401/403/5xx/timeout errors
        // automatically retry against a secondary model. When fallback.* config is
        // blank, no fallback is applied (primary model used directly).
        Model wrappedModel;
        if (fallbackApiKey != null && !fallbackApiKey.isBlank()
                && fallbackBaseUrl != null && !fallbackBaseUrl.isBlank()
                && fallbackModelName != null && !fallbackModelName.isBlank()) {
            OpenAIChatModel fallbackModel = OpenAIChatModel.builder()
                    .apiKey(fallbackApiKey)
                    .baseUrl(fallbackBaseUrl)
                    .modelName(fallbackModelName)
                    .stream(true)
                    .build();
            wrappedModel = new FallbackModelDecorator(model, fallbackModel);
            log.info("HarnessA2aRunnerV2: FallbackModelDecorator wired (primary={}, fallback={})",
                    modelName, fallbackModelName);
        } else {
            wrappedModel = model;
            log.info("HarnessA2aRunnerV2: no fallback model configured, using primary only ({})", modelName);
        }

        Path workspace = Paths.get(workspacePath).toAbsolutePath();

        List<MiddlewareBase> middlewares = new ArrayList<MiddlewareBase>(List.of(
                responseCacheMiddleware,
                dimensionStateMiddleware,
                episodicRetrievalMiddleware,
                artifactAccessMiddleware,
                sessionMiddleware
        ));
        MemoryLedgerMirrorMiddleware ledgerMirror = memoryLedgerMirrorProvider.getIfAvailable();
        if (ledgerMirror != null) {
            middlewares.add(ledgerMirror);
            log.info("HarnessA2aRunnerV2: MemoryLedgerMirrorMiddleware wired");
        }
        PythonExecAccessMiddleware pythonExecGuard = pythonExecAccessMiddlewareProvider.getIfAvailable();
        if (pythonExecGuard != null) {
            middlewares.add(pythonExecGuard);
            log.info("HarnessA2aRunnerV2: PythonExecAccessMiddleware wired (P0-5 cross-tenant guard)");
        }

        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name("QualitySupervisorV2")
                .model(wrappedModel)
                .workspace(workspace)
                .stateStore(new SanitizingAgentStateStore(new MysqlAgentStateStore(dataSource, true)))
                .memory(MemoryConfig.builder()
                        .model(smallModel)
                        .build())
                .compaction(CompactionConfig.builder()
                        .triggerMessages(40)
                        .keepMessages(12)
                        .build())
                .toolResultEviction(ToolResultEvictionConfig.defaults())
                .enablePlanMode()
                .planFileDirectory("plans")
                .enableTaskList(true)
                .enablePendingToolRecovery(true)
                .enableSkillManageTool(skillManageConfig)
                .enableSkillCurator(skillCuratorConfig)
                .enableSkillPromotionGate(localApprovalGate, new CompositeFilter(skillVisibilityFilter))
                .middlewares(middlewares);

        // Enable AsyncToolMiddleware so long-running tool calls (>30s) get offloaded to the
        // background with a placeholder ToolResultBlock, then delivered to the LLM as a
        // HintBlock via InboxMiddleware when complete. Required for HintBlock / async tool
        // placeholder behavior tested in §3.4. Backed by local filesystem message bus.
        Path busRoot = workspace.resolve(".bus");
        try {
            java.nio.file.Files.createDirectories(busRoot);
            io.agentscope.harness.agent.filesystem.local.LocalFilesystem busFs =
                    new io.agentscope.harness.agent.filesystem.local.LocalFilesystem(busRoot);
            io.agentscope.harness.agent.bus.WorkspaceMessageBus messageBus =
                    new io.agentscope.harness.agent.bus.WorkspaceMessageBus(busFs, "/bus");
            io.agentscope.harness.agent.bus.WorkspaceAsyncToolRegistry asyncToolRegistry =
                    new io.agentscope.harness.agent.bus.WorkspaceAsyncToolRegistry(busFs, "/async-tools");
            builder.messageBus(messageBus);
            builder.asyncToolRegistry(asyncToolRegistry);
            builder.asyncToolTimeout(java.time.Duration.ofSeconds(30));
            log.info("HarnessA2aRunnerV2: AsyncToolMiddleware wired (timeout=30s, bus={})",
                    busRoot);
        } catch (Exception e) {
            log.warn("HarnessA2aRunnerV2: failed to wire AsyncToolMiddleware: {}", e.getMessage());
        }

        SandboxFilesystemSpec sandboxFilesystem = sandboxFilesystemProvider.getIfAvailable();
        RemoteFilesystemSpec remoteFilesystem = remoteFilesystemProvider.getIfAvailable();
        if (sandboxFilesystem != null) {
            builder.filesystem(sandboxFilesystem);
            log.info("HarnessA2aRunnerV2: sandbox filesystem wired ({})",
                    sandboxFilesystem.getClass().getSimpleName());
        } else if (remoteFilesystem != null) {
            // Distributed mode without sandbox container - RemoteFilesystemSpec routes
            // skills/memory/sessions through the MySQL-backed BaseStore so replicas converge.
            builder.filesystem(remoteFilesystem);
            log.info("HarnessA2aRunnerV2: remote filesystem wired (scope={})",
                    remoteFilesystem.getIsolationScope());
        }

        DistributedStore distributedStore = distributedStoreProvider.getIfAvailable();
        if (distributedStore != null) {
            builder.distributedStore(distributedStore);
            log.info("HarnessA2aRunnerV2: distributed store wired");
        }

        // Artifact handoff hook — uses v2 Hook API (PostActingEvent) for tool-result rewriting.
        // The Hook API is deprecated but still the only way to intercept tool results after execution.
        ArtifactHandoffHook handoffHook = artifactHandoffHookProvider.getIfAvailable();
        if (handoffHook != null) {
            builder.hook(handoffHook);
            log.info("HarnessA2aRunnerV2: ArtifactHandoffHook wired (priority=12)");
        }

        // Python exec retry hook — annotates failed python_exec results with retry hints.
        // Uses deprecated Hook API (PostActingEvent) for tool-result rewriting.
        PythonExecRetryHook retryHook = pythonExecRetryHookProvider.getIfAvailable();
        if (retryHook != null) {
            builder.hook(retryHook);
            log.info("HarnessA2aRunnerV2: PythonExecRetryHook wired (priority=13)");
        }

        // Tool call tracking hook — records L1 tool call inputs and outputs via ThreadLocal.
        // Uses deprecated Hook API (PreActingEvent + PostActingEvent) for symmetric interception.
        ToolCallTrackingHook trackingHook = toolCallTrackingHookProvider.getIfAvailable();
        if (trackingHook != null) {
            builder.hook(trackingHook);
            log.info("HarnessA2aRunnerV2: ToolCallTrackingHook wired (priority=45)");
        }

        // Skill retrieval hook (PR3) - retrieves matched skills from skills-auto/ and skills-user/
        // into the system prompt at PreCall. This is the ONLY path that injects user/auto skills;
        // SkillVectorIndexVisibilityFilter only filters JAR-builtin skills. Runs at priority -50
        // (before WorkspaceContextHook) so retrieved skills appear first in the system prompt.
        SkillRetrievalHook retrievalHook = skillRetrievalHookProvider.getIfAvailable();
        if (retrievalHook != null) {
            builder.hook(retrievalHook);
            log.info("HarnessA2aRunnerV2: SkillRetrievalHook wired (priority=-50)");
        }

        // Skill synthesis hook (PR2, cache-MISS path) - bumps skill_candidate.hit_count on
        // every PreCall and dispatches async distillation when threshold is crossed. Without
        // this hook, online bump-to-distill never fires; only nightly digestion can. Runs at
        // priority 50 (after SkillRetrievalHook -50 so the retrieval attribute is available
        // for the synthesis hook to log, and after ToolCallTrackingHook 45).
        SkillSynthesisHook synthesisHook = skillSynthesisHookProvider.getIfAvailable();
        if (synthesisHook != null) {
            builder.hook(synthesisHook);
            log.info("HarnessA2aRunnerV2: SkillSynthesisHook wired (priority=50)");
        }

        // Skill evolution hook (PR4, failure-feedback closed loop) - credits success/failure
        // counts to retrieved skills based on python_exec retry signals (PostCall) and
        // cross-turn user rejection keywords (PreCall). Dispatches async evolve via
        // SkillEvolutionRunner when failure_rate exceeds the threshold. Without this hook,
        // skill_index.success/failure_count never increment on chat requests; only nightly
        // digestion can trigger evolution. Runs at priority 60 (after synthesis bump at 50
        // so the candidate row exists before evolution can fire).
        SkillEvolutionHook evolutionHook = skillEvolutionHookProvider.getIfAvailable();
        if (evolutionHook != null) {
            builder.hook(evolutionHook);
            log.info("HarnessA2aRunnerV2: SkillEvolutionHook wired (priority=60)");
        }

        // Knowledge dynamic retrieval hook - injects files from knowledge-dynamic/ into the
        // system prompt when the user's question matches keywords in knowledge-index.yaml.
        // knowledge/ is always loaded by the JAR's WorkspaceContextHook; knowledge-dynamic/
        // is on-demand only. Runs at priority -40 (between SkillRetrievalHook -50 and
        // WorkspaceContextHook ~0) so dynamic knowledge appears after skills but before
        // static workspace context.
        KnowledgeRetrievalHook knowledgeHook = knowledgeRetrievalHookProvider.getIfAvailable();
        if (knowledgeHook != null) {
            builder.hook(knowledgeHook);
            log.info("HarnessA2aRunnerV2: KnowledgeRetrievalHook wired (priority=-40)");
        }

        // v2 Toolkit — replaces ToolRoutersIndex's flat router_tool dispatch with native
        // tool groups and the reset_equipped_tools meta-tool for LLM-driven group switching.
        V2ToolGroupAdapter toolGroupAdapter = toolGroupAdapterProvider.getIfAvailable();
        if (toolGroupAdapter != null) {
            builder.toolkit(toolGroupAdapter.getToolkit());
            log.info("HarnessA2aRunnerV2: Toolkit wired ({} tools, groups: {})",
                    toolGroupAdapter.getToolkit().getToolNames().size(),
                    toolGroupAdapter.getToolkit().getActiveGroups());
        }

        // Subagent registration - manually loads agent-subagents/*.md and registers
        // per-subagent factories with fail-fast tool-name validation. Replicates v1
        // SupervisorService pattern; agent-subagents/ (not subagents/) avoids JAR auto-load.
        SubagentRegistrar registrar = subagentRegistrarProvider.getIfAvailable();
        if (registrar != null) {
            registrar.registerAll(builder, model, workspace, sandboxFilesystemProvider);
            log.info("HarnessA2aRunnerV2: subagents registered via manual factory");
        }

        this.agent = builder.build();

        log.info("HarnessA2aRunnerV2 initialized: workspace={}, model={}, stateStore=SanitizingAgentStateStore(MysqlAgentStateStore), " +
                        "memoryModel={}, skillCurator=enabled",
                workspace, modelName, lightModelName);
    }

    public Flux<AgentEvent> streamEvents(List<Msg> messages, RuntimeContext ctx) {
        return agent.streamEvents(messages, ctx);
    }

    public Flux<AgentEvent> streamEvents(String text, RuntimeContext ctx) {
        return agent.streamEvents(text, ctx);
    }

    public HarnessAgent getAgent() {
        return agent;
    }
}
