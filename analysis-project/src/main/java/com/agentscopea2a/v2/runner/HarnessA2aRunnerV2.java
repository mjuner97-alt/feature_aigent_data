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

import com.agentscopea2a.v2.config.AgentExecutionConfig;
import com.agentscopea2a.v2.config.HarnessRunnerProperties;
import com.agentscopea2a.v2.config.SkillStorageProperties;
import com.agentscopea2a.v2.memory.MysqlMemoryStore;
import com.agentscopea2a.v2.model.FallbackModelDecorator;
import com.agentscopea2a.v2.model.ModelProvider;
import com.agentscopea2a.v2.skillManager.mapper.SkillMapper;
import com.agentscopea2a.v2.skills.DatabaseSkillRepository;
import com.agentscopea2a.v2.tools.PerUserMemoryGetTool;
import com.agentscopea2a.v2.tools.V2ToolGroupAdapter;
import io.agentscope.core.a2a.server.executor.runner.AgentRequestOptions;
import io.agentscope.core.a2a.server.executor.runner.AgentRunner;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.state.AgentStateStore;
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
public class HarnessA2aRunnerV2 implements AgentRunner {

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
    private final ObjectProvider<MysqlMemoryStore> mysqlMemoryStoreProvider;
    private final SkillMapper skillMapper;
    /** skill 附件文件磁盘根目录(${skill.file.script}),传给 DatabaseSkillRepository 用于把 DB 中的相对 storage_path 解析成绝对路径。 */
    private final String skillFileBaseDir;
    /**
     * 共享 stateStore,供 read-only 状态查询端点使用 (如 V2SessionController.getState)。
     * 避免每次轮询都走 buildAgent() 的全套装配 (~240ms + DDL 检查)。
     * MysqlAgentStateStore 全 final 字段 + DataSource(HikariCP 线程安全),可安全共享。
     */
    private final AgentStateStore sharedStateStore;

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
            ModelProvider modelProvider,
            ObjectProvider<MysqlMemoryStore> mysqlMemoryStoreProvider,
            SkillMapper skillMapper,
            SkillStorageProperties storageProperties) {
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
        this.mysqlMemoryStoreProvider = mysqlMemoryStoreProvider;
        this.skillMapper = skillMapper;
        this.skillFileBaseDir = storageProperties.getScriptDir();
        this.sharedStateStore = new SanitizingAgentStateStore(new MysqlAgentStateStore(dataSource, true));

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

    // ==================== AgentRunner 接口实现 ====================
    // 供 agentscope-a2a-spring-boot-starter 的 AgentscopeA2aAutoConfiguration
    // 注入。把 AgentRequestOptions 适配成 RuntimeContext，复用 buildAgent 构建
    // per-request agent，再调 agent.stream(...) 返回 Flux<Event>（旧 API，与
    // AgentRunner.stream 签名一致）。streamEvents 返回的是 Flux<AgentEvent>（新
    // API），类型不兼容，故走 stream 路径。

    @Override
    public Flux<Event> stream(List<Msg> requestMessages, AgentRequestOptions options) {
        RuntimeContext ctx = RuntimeContext.builder()
                .sessionId(options.getTaskId())
                .userId(options.getUserId())
                .build();
        HarnessAgent agent = buildAgent(ctx);
        return agent.stream(requestMessages, ctx);
    }

    @Override
    public void stop(String taskId) {
        // V2 是 per-request 架构，无 active agent 缓存，空实现即可
    }

    @Override
    public String getAgentName() {
        return "QualitySupervisorV2";
    }

    @Override
    public String getAgentDescription() {
        return "Harness-native quality data supervisor V2 with multi-agent coordination";
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
     * 返回共享的 {@link AgentStateStore},供 read-only 状态查询端点使用。
     *
     * <p>对于只需读取 AgentState 的端点 (如 V2SessionController.getState 每 2s 轮询),
     * 用此方法而非 {@link #getAgent()}。避免每次轮询触发 buildAgent() 的全套装配
     * (toolkit wiring + subagent 注册 + skill repo init,~240ms/次 + DDL 检查)。
     *
     * <p>调用方直接 {@code stateStore.get(userId, sessionId, "agent_state", AgentState.class)}
     * 即可,语义等同 {@code ReActAgent.getAgentState(userId, sessionId)} 但跳过 agent 装配
     * 和 in-memory stateCache (stateCache 对一次性 agent 无意义,因 agent 用完即丢)。
     *
     * <p>差异:对不存在的 session,getAgentState 返回 fresh empty state (非 null),
     * stateStore.get 返回 Optional.empty。调用方需自行处理 null (返回 exists=false)。
     */
    public AgentStateStore getStateStore() {
        return sharedStateStore;
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
        String userId = extractUserId(ctx);

        // 获取带降级逻辑的主模型
        FallbackModelDecorator primaryModel = modelProvider.getModelForUser(userId);

        // Memory 使用固定的小模型(light-classifier), 不走 deepseek(分类/蒸馏用小模型更省)
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
                .skillRepository(new DatabaseSkillRepository(skillMapper, userId != null ? String.valueOf(userId) : null, skillFileBaseDir))
                .toolExecutionConfig(AgentExecutionConfig.TOOL_DEFAULTS)
                .modelExecutionConfig(AgentExecutionConfig.MODEL_DEFAULTS)
                .stateStore(sharedStateStore)
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
                        // 2026/08/05: 40/12 对 32K 窗口的 glm-5.2 偏大 (前 12 条历史
                        // 已 20K+), 调到 20/8 让 CompactionMiddleware 更早触发摘要压缩。
                        // 风险: 摘要可能丢早期 tool result 字段名, 但 ToolResultTruncationMiddleware
                        // 已先压缩过 tool result, 摘要压力小。
                        .triggerMessages(20)
                        .keepMessages(8)
                        .build())
                // 2026/07/28: 禁用 JAR 内置 ToolResultEvictionMiddleware。
                // wide_table_query 返回 >80K 字符 CSV 时, middleware 调
                // SandboxBackedFilesystem.uploadFiles 把结果写到容器内
                // /large_tool_results/..., 内部走 ssh.exe + base64 payload,
                // 命令行超过 Windows CreateProcess 8KB 上限 -> error=206。
                // 业务侧 ArtifactHandoffHook (priority 12, PostActingEvent)
                // 已经把大表格 CSV 落到 ArtifactStore 并把 tool result 替换成
                // 短 handoff 消息 (pd.read_csv(...)), eviction 在这套架构下冗余。
                // Linux 部署无此问题, 可恢复。
                // .toolResultEviction(ToolResultEvictionConfig.defaults())
                .enablePendingToolRecovery(true)
                .enableSkillManageTool(skillManageConfig)
                .enableSkillCurator(skillCuratorConfig)
                .enableSkillPromotionGate(localApprovalGate, new CompositeFilter(skillVisibilityFilter))
                // 2026/07/25: 禁掉 JAR 自动注册的文件系统/shell/memory 工具,避免 LLM 浪费 token
                // 探查文件系统。python_exec / arith / router_tool 是 v2 工具,由 SubagentRegistrar
                // 显式注册到 toolkit,不受这些 flag 影响。memory_get 在 build 后用 PerUserMemoryGetTool
                // 重新注册(per-user 隔离)。session_search / session_list / session_save 无 disable
                // flag,见下方 post-build removeTool。
                .disableFilesystemTools()
                .disableShellTool()
                .disableMemoryTools()
                .middlewares(middlewares);



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

        // 2026/07/25: 显式移除 JAR 自动注册的 session 工具(无 disable flag)。
        // 防止 LLM 把 token 烧在 session_search / session_list 探查上。
        try {
            io.agentscope.core.tool.Toolkit postBuildToolkit = agent.getToolkit();
            for (String tn : new String[]{"session_search", "session_list", "session_save"}) {
                try {
                    postBuildToolkit.removeTool(tn);
                } catch (Exception ignored) {
                    // tool not registered by JAR in this config - fine
                }
            }
        } catch (Exception e) {
            log.warn("HarnessA2aRunnerV2: failed to strip session tools: {}", e.getMessage());
        }

        // Replace the framework's memory_get tool with a per-user DB-backed version.
        // The framework's MemoryGetTool reads from workspaceManager.readManagedWorkspaceFileUtf8()
        // which falls back to the shared root MEMORY.md (readWithOverride -> readFileQuietly).
        // In shared-container mode this causes cross-tenant leaks: a new user calling
        // memory_get("MEMORY.md") sees a previous user's curated memory. PerUserMemoryGetTool
        // reads from MysqlMemoryStore (agent_memory table, keyed by user_id) instead.
        // Only wired when mysql-mirror is enabled; otherwise falls back to framework behavior.
        MysqlMemoryStore mysqlMemoryStore = mysqlMemoryStoreProvider.getIfAvailable();
        if (mysqlMemoryStore != null) {
            try {
                io.agentscope.core.tool.Toolkit toolkit = agent.getToolkit();
                toolkit.removeTool("memory_get");
                toolkit.registerTool(new PerUserMemoryGetTool(mysqlMemoryStore));
                log.info("HarnessA2aRunnerV2: replaced framework memory_get with PerUserMemoryGetTool (per-user DB-backed)");
            } catch (Exception e) {
                log.warn("HarnessA2aRunnerV2: failed to replace memory_get tool: {}", e.getMessage());
            }
        } else {
            log.info("HarnessA2aRunnerV2: MysqlMemoryStore not available (mysql-mirror disabled), keeping framework memory_get");
        }

        // Plan mode removed from main agent (supervisor is a pure router, not a planner).
        // Plan mode is now enabled on the analyze_data subagent instead — see SubagentRegistrar.
        // replacePlanExitWithAutoApprove is called in SubagentRegistrar for analyze_data only.

        log.info("HarnessA2aRunnerV2: created agent for userId={}, model={}",
                userId, primaryModel.getModelName());

        return agent;
    }

    /**
     * 从 RuntimeContext 中提取用户 ID。
     */
    private String extractUserId(RuntimeContext ctx) {
        if (ctx == null || ctx.getUserId() == null) {
            return null;
        }
        try {
            return String.valueOf(ctx.getUserId());
        } catch (NumberFormatException e) {
            log.warn("Invalid userId format: {}", ctx.getUserId());
            return null;
        }
    }

    /**
     * Reflectively swap the JAR's {@code plan_exit} tool with
     * {@link com.agentscopea2a.v2.tool.AutoApprovePlanExitTool}. Called by
     * {@link SubagentRegistrar} for subagents with plan mode enabled (e.g. analyze_data).
     *
     * <p>The replacement preserves tool name/schema/description but returns {@code allow}
     * instead of {@code ask} from {@code checkPermissions}, so the agent flows directly
     * into BUILD mode without the HITL pause.
     *
     * <p>Reflection is required because {@code HarnessAgent.planModeManager} is a
     * private final field with no public accessor, and {@code PlanModeManager} is
     * constructed inside {@code HarnessAgent.build()} (not injectable via builder).
     * We need the SAME {@code PlanModeManager} instance because it holds the
     * per-session plan state that {@code PlanModeMiddleware} reads.
     */
    public static void replacePlanExitWithAutoApprove(HarnessAgent agent) {
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
