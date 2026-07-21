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

import com.agentscopea2a.v2.config.HarnessRunnerProperties;
import com.agentscopea2a.v2.model.FallbackModelDecorator;
import com.agentscopea2a.v2.tools.V2ToolGroupAdapter;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.Model;
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
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
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
 *
 * <p>P1-1 重构：原 30+ 参数构造器拆为：
 * <ul>
 *   <li>{@link HarnessRunnerProperties} - 11 个 @Value 收口（model + workspace）</li>
 *   <li>{@code List<MiddlewareBase>} - 由 {@code HarnessAgentPartsConfig} 装配</li>
 *   <li>{@code List<Hook>} - 由 {@code HarnessAgentPartsConfig} 装配</li>
 *   <li>5 个 ObjectProvider - 仅剩 filesystem/store/toolkit/subagent 这种真正可选依赖</li>
 * </ul>
 */
@Component
public class HarnessA2aRunnerV2 {

    private static final Logger log = LoggerFactory.getLogger(HarnessA2aRunnerV2.class);

    private final HarnessAgent agent;

    public HarnessA2aRunnerV2(
            HarnessRunnerProperties runnerProperties,
            DataSource dataSource,
            SkillManageConfig skillManageConfig,
            SkillCuratorConfig skillCuratorConfig,
            LocalApprovalGate localApprovalGate,
            SkillVisibilityFilter skillVisibilityFilter,
            List<MiddlewareBase> middlewares,
            List<Hook> hooks,
            ObjectProvider<V2ToolGroupAdapter> toolGroupAdapterProvider,
            ObjectProvider<SandboxFilesystemSpec> sandboxFilesystemProvider,
            ObjectProvider<RemoteFilesystemSpec> remoteFilesystemProvider,
            ObjectProvider<DistributedStore> distributedStoreProvider,
            ObjectProvider<SubagentRegistrar> subagentRegistrarProvider) {
        HarnessRunnerProperties.ModelInstance main = runnerProperties.getModel().getInstances().getGlmMain();
        HarnessRunnerProperties.ModelInstance light = runnerProperties.getModel().getInstances().getLightClassifier();
        HarnessRunnerProperties.ModelInstance fallback = runnerProperties.getModel().getInstances().getFallback();

        OpenAIChatModel model = OpenAIChatModel.builder()
                .apiKey(main.getApiKey())
                .baseUrl(main.getBaseUrl())
                .modelName(main.getName())
                .stream(true)
                .build();

        OpenAIChatModel smallModel = OpenAIChatModel.builder()
                .apiKey(light.getApiKey())
                .baseUrl(light.getBaseUrl())
                .modelName(light.getName())
                .stream(true)
                .build();

        // Fallback model - wraps the primary model so that 401/403/5xx/timeout errors
        // automatically retry against a secondary model. When fallback.* config is
        // blank, no fallback is applied (primary model used directly).
        Model wrappedModel;
        if (fallback.getApiKey() != null && !fallback.getApiKey().isBlank()
                && fallback.getBaseUrl() != null && !fallback.getBaseUrl().isBlank()
                && fallback.getName() != null && !fallback.getName().isBlank()) {
            OpenAIChatModel fallbackModel = OpenAIChatModel.builder()
                    .apiKey(fallback.getApiKey())
                    .baseUrl(fallback.getBaseUrl())
                    .modelName(fallback.getName())
                    .stream(true)
                    .build();
            wrappedModel = new FallbackModelDecorator(model, fallbackModel);
            log.info("HarnessA2aRunnerV2: FallbackModelDecorator wired (primary={}, fallback={})",
                    main.getName(), fallback.getName());
        } else {
            wrappedModel = model;
            log.info("HarnessA2aRunnerV2: no fallback model configured, using primary only ({})", main.getName());
        }

        Path workspace = Paths.get(runnerProperties.getWorkspace().getPath()).toAbsolutePath();

        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name("QualitySupervisorV2")
                .model(wrappedModel)
                .workspace(workspace)
                .stateStore(new SanitizingAgentStateStore(new MysqlAgentStateStore(dataSource, true)))
                .memory(MemoryConfig.builder()
                        .model(smallModel)
                        // 临时测速:consolidationMinGap=365d 让 MemoryMaintenanceMiddleware
                        // maybeRunMaintenance 节流命中直接 return,跳过 19s 的 LLM consolidation。
                        // 连带 expireDailyFiles/pruneOldSessions 也跳 (纯文件操作,测速期无影响)。
                        // 回滚:把下面这行删掉即恢复默认 30min。
                        .consolidationMinGap(Duration.ofDays(365))
                        // 跨租户隔离:关掉框架的 per-call flush。MemoryFlushMiddleware 默认
                        // ALWAYS 模式会在每次 call 结束把 LLM 总结的 memory 写到
                        // /workspace/MEMORY.md (根,共享) + /workspace/memory/<date>.md
                        // (根,共享)。shared-container 模式下所有用户共享同一个容器 /workspace,
                        // 这些根文件会跨用户串扰 - bob 调 memory_search 会看到 alice 的 daily
                        // ledger 条目。PerUserMemoryContextMiddleware 已经从 DB 注入 per-user
                        // MEMORY.md 到 system prompt,MemoryLedgerMirrorMiddleware 也已 per-user
                        // 落盘 + mirror 到 agent_memory_ledger。框架的 flush 在这套架构下是
                        // 冗余且有害的,关掉它。MemoryMaintenanceMiddleware 仍然注册(只是
                        // consolidationMinGap=365d 让它跳过 consolidation),daily 文件保留/清理
                        // 逻辑不受影响。
                        .flushTrigger(MemoryConfig.FlushTrigger.never())
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

        // Enable AsyncToolMiddleware so long-running tool calls get offloaded to the
        // background with a placeholder ToolResultBlock, then delivered to the LLM as a
        // HintBlock via InboxMiddleware when complete. Required for HintBlock / async tool
        // placeholder behavior tested in §3.4. Backed by local filesystem message bus.
        //
        // Timeout tuned to 600s (was 30s) so that agent_spawn calls dispatching subagents
        // (analyze_data, query_data) are NOT offloaded mid-flight. When offloaded at 30s,
        // subagent events (tool_call_start / text_block_delta / etc.) stop flowing through
        // the parent's AgentEventEmitter into the SSE stream — the frontend ActivityFeed
        // only sees the main agent's meta events (agent_spawn / wait_async_results) and
        // the subagent's internal activity is invisible to the user. With 600s timeout,
        // agent_spawn runs synchronously in the parent stream and the framework's
        // `execLocalSync` path forwards all child events via `event.withSource(sourcePath)`,
        // so the frontend can render subagent activity in real time. The AsyncToolMiddleware
        // is still wired (and the bus still available) so that genuinely runaway tool calls
        // (e.g. python_exec infinite loop) still trip the 600s offload as a safety net.
        // See docs/Plan-Machie/process-event-streaming.md §"子 agent 内部活动透传".
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
            builder.asyncToolTimeout(java.time.Duration.ofSeconds(600));
            log.info("HarnessA2aRunnerV2: AsyncToolMiddleware wired (timeout=600s, bus={})",
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

        for (Hook hook : hooks) {
            builder.hook(hook);
        }

        // v2 Toolkit - replaces ToolRoutersIndex's flat router_tool dispatch with native
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

        // Replace the JAR's PlanExitTool with AutoApprovePlanExitTool so plan_exit no longer
        // triggers the framework's HITL ASK pause. The JAR's PlanExitTool.checkPermissions
        // returns PermissionDecision.ask(...), which emits a RequireUserConfirmEvent and stops
        // the agent. Without a frontend HITL approval UI + /confirm endpoint, the user's
        // follow-up message gets interpreted as a fresh user msg, the framework auto-generates
        // an error result for the pending plan_exit call, and the agent never enters BUILD mode.
        // AutoApprovePlanExitTool returns allow() instead, so plan_exit flows directly into
        // BUILD mode and the agent continues executing the plan. See AutoApprovePlanExitTool
        // class javadoc for the full rationale.
        replacePlanExitWithAutoApprove(this.agent);

        log.info("HarnessA2aRunnerV2 initialized: workspace={}, model={}, stateStore=SanitizingAgentStateStore(MysqlAgentStateStore), " +
                        "memoryModel={}, skillCurator=enabled",
                workspace, main.getName(), light.getName());
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

    /**
     * Reflectively read the private {@code planModeManager} field from the built
     * {@link HarnessAgent} and swap the JAR's {@code plan_exit} tool with
     * {@link com.agentscopea2a.v2.tool.AutoApprovePlanExitTool}. The replacement
     * preserves tool name/schema/description (so model behavior is unchanged) but
     * returns {@code allow} instead of {@code ask} from {@code checkPermissions},
     * so the agent flows directly into BUILD mode without the HITL pause.
     *
     * <p>Reflection is required because {@code HarnessAgent.planModeManager} is a
     * private final field with no public accessor, and {@code PlanModeManager} is
     * constructed inside {@code HarnessAgent.build()} (not injectable via builder).
     * We need the SAME {@code PlanModeManager} instance because it holds the
     * per-session plan state that {@code PlanModeMiddleware} reads.
     */
    private static void replacePlanExitWithAutoApprove(HarnessAgent agent) {
        try {
            java.lang.reflect.Field f = HarnessAgent.class.getDeclaredField("planModeManager");
            f.setAccessible(true);
            io.agentscope.harness.agent.workspace.plan.PlanModeManager planModeManager =
                    (io.agentscope.harness.agent.workspace.plan.PlanModeManager) f.get(agent);
            if (planModeManager == null) {
                log.warn("HarnessA2aRunnerV2: planModeManager is null (plan mode disabled?), skipping plan_exit replacement");
                return;
            }
            io.agentscope.core.tool.Toolkit toolkit = agent.getToolkit();
            toolkit.removeTool("plan_exit");
            toolkit.registerTool(new com.agentscopea2a.v2.tool.AutoApprovePlanExitTool(planModeManager));
            log.info("HarnessA2aRunnerV2: replaced JAR PlanExitTool with AutoApprovePlanExitTool (plan_exit no longer HITL-asks)");
        } catch (NoSuchFieldException e) {
            log.warn("HarnessA2aRunnerV2: HarnessAgent.planModeManager field not found — framework version changed? plan_exit replacement skipped: {}",
                    e.getMessage());
        } catch (IllegalAccessException e) {
            log.warn("HarnessA2aRunnerV2: cannot access HarnessAgent.planModeManager (security manager?): plan_exit replacement skipped: {}",
                    e.getMessage());
        } catch (Throwable t) {
            log.warn("HarnessA2aRunnerV2: plan_exit replacement failed (falling back to JAR PlanExitTool with HITL ASK): {}",
                    t.getMessage());
        }
    }
}
