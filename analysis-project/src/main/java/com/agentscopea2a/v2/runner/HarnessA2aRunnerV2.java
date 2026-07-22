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
import com.agentscopea2a.v2.model.ModelProvider;
import com.agentscopea2a.v2.tools.V2ToolGroupAdapter;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.MiddlewareBase;
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


@Component
public class HarnessA2aRunnerV2 {

    private static final Logger log = LoggerFactory.getLogger(HarnessA2aRunnerV2.class);

    private final HarnessRunnerProperties runnerProperties;
    private final DataSource dataSource;
    private final SkillManageConfig skillManageConfig;
    private final SkillCuratorConfig skillCuratorConfig;
    private final LocalApprovalGate localApprovalGate;
    private final SkillVisibilityFilter skillVisibilityFilter;
    private final List<MiddlewareBase> middlewares;
    private final List<Hook> hooks;
    private final ObjectProvider<V2ToolGroupAdapter> toolGroupAdapterProvider;
    private final ObjectProvider<SandboxFilesystemSpec> sandboxFilesystemProvider;
    private final ObjectProvider<RemoteFilesystemSpec> remoteFilesystemProvider;
    private final ObjectProvider<DistributedStore> distributedStoreProvider;
    private final ObjectProvider<SubagentRegistrar> subagentRegistrarProvider;
    private final ModelProvider modelProvider;

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
            ObjectProvider<SubagentRegistrar> subagentRegistrarProvider,
            ModelProvider modelProvider) {
        this.runnerProperties = runnerProperties;
        this.dataSource = dataSource;
        this.skillManageConfig = skillManageConfig;
        this.skillCuratorConfig = skillCuratorConfig;
        this.localApprovalGate = localApprovalGate;
        this.skillVisibilityFilter = skillVisibilityFilter;
        this.middlewares = middlewares;
        this.hooks = hooks;
        this.toolGroupAdapterProvider = toolGroupAdapterProvider;
        this.sandboxFilesystemProvider = sandboxFilesystemProvider;
        this.remoteFilesystemProvider = remoteFilesystemProvider;
        this.distributedStoreProvider = distributedStoreProvider;
        this.subagentRegistrarProvider = subagentRegistrarProvider;
        this.modelProvider = modelProvider;

        log.info("HarnessA2aRunnerV2 initialized: ready to create agents per request");
    }

    /**
     * 根据请求消息和上下文流式处理事件。
     *
     * @param messages 请求消息列表
     * @param ctx 运行时上下文（包含 userId 等信息）
     * @return Agent 事件流
     */
    public Flux<AgentEvent> streamEvents(List<Msg> messages, RuntimeContext ctx) {
        HarnessAgent agent = buildAgent(ctx);
        return agent.streamEvents(messages, ctx);
    }

    /**
     * 根据文本和上下文流式处理事件。
     *
     * @param text 用户输入文本
     * @param ctx 运行时上下文（包含 userId 等信息）
     * @return Agent 事件流
     */
    public Flux<AgentEvent> streamEvents(String text, RuntimeContext ctx) {
        HarnessAgent agent = buildAgent(ctx);
        return agent.streamEvents(text, ctx);
    }

    /**
     * 构建并返回一个临时 {@link HarnessAgent}，供 out-of-band 控制端点使用。
     *
     * <p>per-request 重构（commit 7b5e9b2）后，{@link #streamEvents} 每次调用都新建
     * agent 且不再保留共享实例。但 {@code /v2/ai/chat/interrupt}（中断当前会话）和
     * permission-mode 读写端点仍需一个 agent 句柄来调用实例方法——这些方法按
     * {@code (userId, sessionId)} 操作共享的 MySQL state store
     * （{@code permission_context.mode} / {@code InterruptControl} flag），因此用一个
     * 临时 agent 即可触达同一份 session state。
     *
     * <p><b>注意：</b>每次调用都会构建完整的模型 + memory + middleware + toolkit，
     * 成本较高，仅用于上述低频控制端点，不要用在请求热路径上。
     *
     * <p><b>技术债：</b>{@code interrupt} 作用在另一个正在运行的 per-request agent 实例上，
     * 跨实例是否生效取决于框架 InterruptControl 是否为 session 级共享；若不生效，
     * interrupt 端点会由 {@code InFlightCall.subscription()} 的 dispose 兜底。
     * 后续应按 per-request 架构彻底重构这三处调用，移除对本方法的依赖。
     *
     * @return 新构建的临时 agent（ctx 为 null，走默认模型）
     */
    public HarnessAgent getAgent() {
        return buildAgent(null);
    }

    /**
     * 根据运行时上下文构建新的 HarnessAgent。
     *
     * <p>关键改动：
     * <ul>
     *   <li>从 V2ModelProvider 获取带降级逻辑的模型（用户模型或默认模型）</li>
     *   <li>Memory 使用固定的 light-classifier（分类、蒸馏等场景不需要用户模型）</li>
     *   <li>每次调用都创建新实例，避免并发状态污染</li>
     * </ul>
     */
    private HarnessAgent buildAgent(RuntimeContext ctx) {
        Long userId = extractUserId(ctx);

        // 获取带降级逻辑的主模型
        FallbackModelDecorator primaryModel = modelProvider.getModelForUser(userId);

        // Memory 使用固定的 light-classifier
        HarnessRunnerProperties.ModelInstance light = runnerProperties.getModel().getInstances().getLightClassifier();
        OpenAIChatModel smallModel = OpenAIChatModel.builder()
                .apiKey(light.getApiKey())
                .baseUrl(light.getBaseUrl())
                .modelName(light.getName())
                .stream(true)
                .build();

        Path workspace = Paths.get(runnerProperties.getWorkspace().getPath()).toAbsolutePath();

        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name("QualitySupervisorV2")
                .model(primaryModel)
                .workspace(workspace)
                .stateStore(new SanitizingAgentStateStore(new MysqlAgentStateStore(dataSource, true)))
                .memory(MemoryConfig.builder()
                        .model(smallModel)
                        // 临时测速:consolidationMinGap=365d 让 MemoryMaintenanceMiddleware
                        // maybeRunMaintenance 节流命中直接 return,跳过 19s 的 LLM consolidation。
                        // 连带 expireDailyFiles/pruneOldSessions 也跳 (纯文件操作,测速期无影响)。
                        // 回滚:把下面这行删掉即恢复默认 30min。
                        .consolidationMinGap(Duration.ofDays(365))
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
            log.debug("HarnessA2aRunnerV2: AsyncToolMiddleware wired (timeout=600s, bus={})", busRoot);
        } catch (Exception e) {
            log.warn("HarnessA2aRunnerV2: failed to wire AsyncToolMiddleware: {}", e.getMessage());
        }

        SandboxFilesystemSpec sandboxFilesystem = sandboxFilesystemProvider.getIfAvailable();
        RemoteFilesystemSpec remoteFilesystem = remoteFilesystemProvider.getIfAvailable();
        if (sandboxFilesystem != null) {
            builder.filesystem(sandboxFilesystem);
            log.debug("HarnessA2aRunnerV2: sandbox filesystem wired ({})",
                    sandboxFilesystem.getClass().getSimpleName());
        } else if (remoteFilesystem != null) {
            // Distributed mode without sandbox container - RemoteFilesystemSpec routes
            // skills/memory/sessions through the MySQL-backed BaseStore so replicas converge.
            builder.filesystem(remoteFilesystem);
            log.debug("HarnessA2aRunnerV2: remote filesystem wired (scope={})",
                    remoteFilesystem.getIsolationScope());
        }

        DistributedStore distributedStore = distributedStoreProvider.getIfAvailable();
        if (distributedStore != null) {
            builder.distributedStore(distributedStore);
            log.debug("HarnessA2aRunnerV2: distributed store wired");
        }

        for (Hook hook : hooks) {
            builder.hook(hook);
        }

        // v2 Toolkit - replaces ToolRoutersIndex's flat router_tool dispatch with native
        // tool groups and the reset_equipped_tools meta-tool for LLM-driven group switching.
        V2ToolGroupAdapter toolGroupAdapter = toolGroupAdapterProvider.getIfAvailable();
        if (toolGroupAdapter != null) {
            builder.toolkit(toolGroupAdapter.getToolkit());
            log.debug("HarnessA2aRunnerV2: Toolkit wired ({} tools, groups: {})",
                    toolGroupAdapter.getToolkit().getToolNames().size(),
                    toolGroupAdapter.getToolkit().getActiveGroups());
        }

        // Subagent registration - manually loads agent-subagents/*.md and registers
        // per-subagent factories with fail-fast tool-name validation. Replicates v1
        // SupervisorService pattern; agent-subagents/ (not subagents/) avoids JAR auto-load.
        SubagentRegistrar registrar = subagentRegistrarProvider.getIfAvailable();
        if (registrar != null) {
            registrar.registerAll(builder, primaryModel, workspace, sandboxFilesystemProvider);
            log.debug("HarnessA2aRunnerV2: subagents registered via manual factory");
        }

        HarnessAgent agent = builder.build();

        // Replace the JAR's PlanExitTool with AutoApprovePlanExitTool so plan_exit no longer
        // triggers the framework's HITL ASK pause. The JAR's PlanExitTool.checkPermissions
        // returns PermissionDecision.ask(...), which emits a RequireUserConfirmEvent and stops
        // the agent. Without a frontend HITL approval UI + /confirm endpoint, the user's
        // follow-up message gets interpreted as a fresh user msg, the framework auto-generates
        // an error result for the pending plan_exit call, and the agent never enters BUILD mode.
        // AutoApprovePlanExitTool returns allow() instead, so plan_exit flows directly into
        // BUILD mode and the agent continues executing the plan. See AutoApprovePlanExitTool
        // class javadoc for the full rationale.
        replacePlanExitWithAutoApprove(agent);

        log.info("HarnessA2aRunnerV2: created agent for userId={}, model={}",
                userId, primaryModel.getModelName());

        return agent;
    }

    /**
     * 从 RuntimeContext 中提取用户 ID。
     */
    private Long extractUserId(RuntimeContext ctx) {
        if (ctx == null || ctx.getUserId() == null) {
            return null;
        }
        try {
            return Long.parseLong(ctx.getUserId());
        } catch (NumberFormatException e) {
            log.warn("Invalid userId format: {}", ctx.getUserId());
            return null;
        }
    }

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
//            toolkit.registerTool(new AutoApprovePlanExitTool(planModeManager));
            log.debug("HarnessA2aRunnerV2: replaced JAR PlanExitTool with AutoApprovePlanExitTool (plan_exit no longer HITL-asks)");
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
