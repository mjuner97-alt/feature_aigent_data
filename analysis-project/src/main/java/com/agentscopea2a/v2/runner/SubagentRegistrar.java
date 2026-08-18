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
import com.agentscopea2a.v2.hooks.ArtifactHandoffHook;
import com.agentscopea2a.v2.hooks.PythonExecRetryHook;
import com.agentscopea2a.v2.hooks.ToolCallTrackingHook;
import com.agentscopea2a.v2.memory.MysqlMemoryStore;
import com.agentscopea2a.v2.middleware.ArtifactAccessMiddleware;
import com.agentscopea2a.v2.middleware.PythonExecAccessMiddleware;
import com.agentscopea2a.v2.middleware.SubagentEventForwardingMiddleware;
import com.agentscopea2a.v2.middleware.ToolResultTruncationMiddleware;
import com.agentscopea2a.v2.tools.ArithTool;
import com.agentscopea2a.v2.tools.PerUserMemoryGetTool;
import com.agentscopea2a.v2.tools.PythonExecTool;
import com.agentscopea2a.v2.tools.PresentationRenderTool;
import com.agentscopea2a.v2.tools.ScriptExecTool;
import com.agentscopea2a.v2.tools.ScriptListTool;
import com.agentscopea2a.v2.tools.SkillSaveTool;
import com.agentscopea2a.v2.tools.SqlListTool;
import com.agentscopea2a.v2.tools.SqlRegistryExecTool;
import com.agentscopea2a.v2.tools.ToolRoutersIndex;
import com.agentscopea2a.v2.trace.collector.AiChatRestToolCallTrackingToDbHook;
import com.agentscopea2a.v2.verify.L2EventCollectorHook;
import com.agentscopea2a.v2.config.WorkspaceMaterializer;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.subagent.AgentSpecLoader;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Manually loads subagent declarations from {@code <workspace>/agent-subagents/} and registers
 * per-subagent factories on the {@link HarnessAgent.Builder}.
 *
 * <p>This replicates v1 {@code SupervisorService.registerSubagentFromSpec} in v2. The
 * {@code agent-subagents/} directory name is intentionally different from the JAR's expected
 * {@code subagents/} to avoid {@code DynamicSubagentsMiddleware} auto-loading the same specs
 * twice. Manual registration also provides fail-fast tool-name validation at startup.
 *
 * <p>Each subagent gets a fresh {@link Toolkit} containing only the tools declared in its
 * YAML frontmatter {@code tools:} list. The parent model and workspace are shared.
 *
 * <p><b>Meta-tool routing</b>: business tools ({@code quality_query_*}, {@code data_*})
 * are NOT registered on subagents directly. They live inside {@link ToolRoutersIndex} and
 * are dispatched via the {@code router_tool(paramsJson)} meta-tool. Subagent specs declare
 * {@code tools: tool_router} (logical name) and the registrar resolves it to the
 * {@link ToolRoutersIndex} bean, which exposes both {@code router_tool} and
 * {@code toolMetaInfo} {@code @Tool} methods. This mirrors v1 SupervisorService.buildToolRegistry.
 */
@Component
public class SubagentRegistrar {

    private static final Logger log = LoggerFactory.getLogger(SubagentRegistrar.class);

    private final Map<String, Object> toolRegistry = new HashMap<>();

    /**
     * Shared hard rules loaded from {@code skills/_common/SKILL.md} at startup.
     * Prepended to every subagent's sysPrompt so CSV path / arith / empty result /
     * direct-call rules don't need to be repeated in each *_metrics SKILL.md.
     * Empty string if file missing (graceful degradation).
     */
    private final String commonRules;
    private final List<SubagentDeclaration> specs;

    /**
     * Hooks/middleware held as singleton beans shared with the main agent. Wired on every
     * subagent via {@link #registerSubagentFromSpec} factory lambda. Mirrors v1
     * {@code SupervisorService.registerSubagentFromSpec} lines 562-569 - without these,
     * subagent {@code router_tool} tabular results bypass artifactization (no CSV handoff
     * to {@code code_interpreter}) and a hallucinating {@code python_exec} could cross-read
     * another user's artifacts. {@code RuntimeContextAware} (ArtifactHandoffHook) and the
     * {@code ctx} parameter in {@code onActing} (ArtifactAccessMiddleware) ensure per-request
     * context resolves correctly even though the bean is shared.
     */
    private final ArtifactHandoffHook artifactHandoffHook;
    private final ArtifactAccessMiddleware artifactAccessMiddleware;
    private final PythonExecAccessMiddleware pythonExecAccessMiddleware;
    private final PythonExecRetryHook pythonExecRetryHook;
    /** V3.0: collects L2 (sub-agent) tool-call events into the shared VerificationContext. */
    private final L2EventCollectorHook l2EventCollectorHook;
    /**
     * Bridges subagent AgentEvents to the parent's SSE emitter. Required because
     * the framework's {@code AgentSpawnTool.execLocalSync} writes the parent
     * emitter into the subagent's Reactor context, but the subagent's
     * {@code publishEvent} only writes to its own filtered Flux, never to the
     * parent emitter. This middleware taps {@code onReasoning/onModelCall/onActing}
     * and mirrors each event to the parent emitter with the subagent name as
     * {@code source}, so the parent's SSE stream sees subagent text_block_delta
     * / tool_call_start / etc. in real time. See
     * {@link com.agentscopea2a.v2.middleware.SubagentEventForwardingMiddleware}
     * class javadoc for the framework limitation rationale.
     */
    private final SubagentEventForwardingMiddleware subagentEventForwardingMiddleware;
    /**
     * Records tool calls (name + input + output) into the per-request ToolCallCollector
     * stored on RuntimeContext. Installed on subagents so that tool_call_start events
     * mirrored by SubagentEventForwardingMiddleware carry toolInput (e.g. todo_write's
     * task list JSON), enabling the frontend to display subagent task details in
     * PlanPanel/TodoListPanel. Without this hook, subagent tool_call_start events have
     * toolInput=null because the parent's ToolCallCollector doesn't record subagent calls.
     */
    private final ToolCallTrackingHook toolCallTrackingHook;
    /**
     * Trace 采集 Hook（与主 agent 共用单例）。子 agent 共享请求级 RuntimeContext，故能拿到
     * 同一个 TraceSession，捕获子 agent 的 LLM 输入/思考/输出、工具入参/返回（source 字段为
     * 子 agent 名以区分）。priority=47，在 L2EventCollectorHook(44)/ToolCallTrackingHook(45) 之后。
     */
    private final AiChatRestToolCallTrackingToDbHook traceCollectorHook;
    /**
     * Truncates previously-consumed tool results (e.g. {@code load_skill_through_path}
     * SKILL.md full text) to reduce LLM context bloat. Singleton bean shared with the
     * main agent; per-call state is derived from RuntimeContext inside the middleware.
     */
    private final ToolResultTruncationMiddleware toolResultTruncationMiddleware;
    /**
     * Per-user memory store for replacing the framework's {@code memory_get} tool on
     * subagents. When non-null, each subagent's {@code memory_get} is replaced with
     * {@link PerUserMemoryGetTool} to prevent cross-tenant memory leaks via the shared
     * root MEMORY.md fallback in {@code WorkspaceManager.readWithOverride()}.
     */
    private final MysqlMemoryStore mysqlMemoryStore;

    public SubagentRegistrar(
            @Value("${harness.a2a.workspace.path:.agentscope/workspace/harness-a2a}") String workspacePath,
            ToolRoutersIndex toolRoutersIndex,
            ObjectProvider<PythonExecTool> pythonExecToolProvider,
            ObjectProvider<SkillSaveTool> skillSaveToolProvider,
            ObjectProvider<ArithTool> arithToolProvider,
            ObjectProvider<SqlListTool> sqlListToolProvider,
            ObjectProvider<SqlRegistryExecTool> sqlRegistryExecToolProvider,
            ObjectProvider<ScriptListTool> scriptListToolProvider,
            ObjectProvider<ScriptExecTool> scriptExecToolProvider,
            ObjectProvider<PresentationRenderTool> presentationRenderToolProvider,
            ObjectProvider<ArtifactHandoffHook> artifactHandoffHookProvider,
            ObjectProvider<ArtifactAccessMiddleware> artifactAccessMiddlewareProvider,
            ObjectProvider<PythonExecAccessMiddleware> pythonExecAccessMiddlewareProvider,
            ObjectProvider<PythonExecRetryHook> pythonExecRetryHookProvider,
            ObjectProvider<L2EventCollectorHook> l2EventCollectorHookProvider,
            ObjectProvider<ToolCallTrackingHook> toolCallTrackingHookProvider,
            ObjectProvider<AiChatRestToolCallTrackingToDbHook> traceCollectorHookProvider,
            ObjectProvider<MysqlMemoryStore> mysqlMemoryStoreProvider,
            ObjectProvider<ToolResultTruncationMiddleware> toolResultTruncationMiddlewareProvider) {

        // v1-style: subagents hold only meta-tool beans. Business tools (quality_query_* /
        // data_*) are encapsulated inside ToolRoutersIndex and dispatched via
        // router_tool(paramsJson={"toolId":"..."}). Spec declares `tools: tool_router` and
        // the registrar resolves it to the ToolRoutersIndex bean, which exposes both
        // router_tool + toolMetaInfo @Tool methods on the subagent's Toolkit.
        toolRegistry.put("tool_router", toolRoutersIndex);
        PythonExecTool py = pythonExecToolProvider.getIfAvailable();
        if (py != null) {
            toolRegistry.put("python_exec", py);
        }
        SkillSaveTool ss = skillSaveToolProvider.getIfAvailable();
        if (ss != null) {
            toolRegistry.put("skill_save", ss);
        }
        ArithTool at = arithToolProvider.getIfAvailable();
        if (at != null) {
            toolRegistry.put("arith", at);
        }
        // sql_list + sql_registry_exec 直接注册给子 agent, 跳过 router_tool. 让 analyze_data 子 agent
        // 调 sql_list 看可用 sql_id 后直接调 sql_registry_exec(sqlId, params) 执行预注册复杂 SQL
        // (GROUP BY / CASE WHEN / JOIN 等).
        SqlListTool slt = sqlListToolProvider.getIfAvailable();
        if (slt != null) {
            toolRegistry.put("sql_list", slt);
        }
        SqlRegistryExecTool sre = sqlRegistryExecToolProvider.getIfAvailable();
        if (sre != null) {
            toolRegistry.put("sql_registry_exec", sre);
        }
        // script_list + script_exec 直接注册给子 agent, 跳过 router_tool. 与 sql_list / sql_registry_exec
        // 对齐, 让 analyze_data 子 agent 调 script_list 看可用 script_id 后直接调
        // script_exec(scriptId, params) 执行预注册 Python 脚本 (SQL 取数 + pandas 算指标一次完成),
        // 替代 sql_registry_exec + python_exec 两步走, 避免 LLM 写 pandas 代码卡死.
        ScriptListTool sl = scriptListToolProvider.getIfAvailable();
        if (sl != null) {
            toolRegistry.put("script_list", sl);
        }
        ScriptExecTool se = scriptExecToolProvider.getIfAvailable();
        if (se != null) {
            toolRegistry.put("script_exec", se);
        }
        PresentationRenderTool presentation = presentationRenderToolProvider.getIfAvailable();
        if (presentation != null) {
            toolRegistry.put("presentation_render", presentation);
        }
        this.artifactHandoffHook = artifactHandoffHookProvider.getIfAvailable();
        this.artifactAccessMiddleware = artifactAccessMiddlewareProvider.getIfAvailable();
        this.pythonExecAccessMiddleware = pythonExecAccessMiddlewareProvider.getIfAvailable();
        this.pythonExecRetryHook = pythonExecRetryHookProvider.getIfAvailable();
        this.l2EventCollectorHook = l2EventCollectorHookProvider.getIfAvailable();
        this.subagentEventForwardingMiddleware = new SubagentEventForwardingMiddleware();
        this.toolCallTrackingHook = toolCallTrackingHookProvider.getIfAvailable();
        this.traceCollectorHook = traceCollectorHookProvider.getIfAvailable();
        this.mysqlMemoryStore = mysqlMemoryStoreProvider.getIfAvailable();
        this.toolResultTruncationMiddleware = toolResultTruncationMiddlewareProvider.getIfAvailable();
        log.info("SubagentRegistrar: toolRegistry built with {} entries: {}; hooks - handoff={} access={} pyGuard={} retry={} l2Collector={} eventForwarding=true toolTracking={} trace={} truncation={}",
                toolRegistry.size(), toolRegistry.keySet(),
                artifactHandoffHook != null, artifactAccessMiddleware != null,
                pythonExecAccessMiddleware != null, pythonExecRetryHook != null, l2EventCollectorHook != null, toolCallTrackingHook != null, traceCollectorHook != null, toolResultTruncationMiddleware != null);

        Path workspace = Paths.get(workspacePath).toAbsolutePath();
        workspace = WorkspaceMaterializer.ensureMaterialized(workspace);
        Path dir = workspace.resolve("agent-subagents");
        this.specs = AgentSpecLoader.loadFromDirectory(dir, workspace);
        this.commonRules = loadCommonRules(workspace);

        for (SubagentDeclaration spec : specs) {
            List<String> tools = spec.getTools() != null ? spec.getTools() : List.of();
            for (String t : tools) {
                if (!toolRegistry.containsKey(t)) {
                    // Graceful degradation: warn + skip (the factory lambda already skips missing
                    // tools when building the toolkit). Lets the app start when an optional tool
                    // (e.g. python_exec, gated by sandbox.enabled) is absent.
                    log.warn("Subagent '{}' declares unavailable tool '{}' (skipping). Available: {}",
                            spec.getName(), t, toolRegistry.keySet());
                }
            }
        }
        log.info("SubagentRegistrar: loaded {} subagent specs from {}: {}",
                specs.size(), dir,
                specs.stream().map(SubagentDeclaration::getName).toList());
    }

    /**
     * Registers a {@code subagentFactory} on the builder for each loaded declaration. Must be
     * called after {@code .toolkit()} and before {@code .build()}.
     *
     * @param builder              the parent HarnessAgent builder
     * @param model                the parent model (shared with subagents)
     * @param workspace            the parent workspace path
     * @param sandboxFsProvider    provider for sandbox filesystem spec (may be null in non-sandbox mode)
     */
    public void registerAll(
            HarnessAgent.Builder builder,
            Model model,
            Path workspace,
            ObjectProvider<SandboxFilesystemSpec> sandboxFsProvider) {
        for (SubagentDeclaration spec : specs) {
            registerSubagentFromSpec(builder, spec, model, workspace, sandboxFsProvider);
        }
        log.info("SubagentRegistrar: registered {} subagent factories on builder", specs.size());
    }

    private void registerSubagentFromSpec(
            HarnessAgent.Builder parent,
            SubagentDeclaration spec,
            Model model,
            Path workspace,
            ObjectProvider<SandboxFilesystemSpec> sandboxFsProvider) {
        String agentId = spec.getName();
        String basePrompt = spec.getInlineAgentsBody();
        // Prepend shared hard rules (_common/SKILL.md) so each *_metrics skill
        // doesn't need to repeat CSV path / arith / empty result / direct-call rules.
        String sysPrompt = (commonRules != null && !commonRules.isEmpty())
                ? commonRules + "\n\n---\n\n" + basePrompt
                : basePrompt;
        int steps = spec.getSteps() > 0 ? spec.getSteps() : 5;
        List<String> toolNames = spec.getTools() != null ? spec.getTools() : List.of();

        parent.subagentFactory(agentId, id -> {
            Toolkit tk = new Toolkit();
            List<String> registered = new ArrayList<>();
            for (String name : toolNames) {
                Object tool = toolRegistry.get(name);
                if (tool != null) {
                    // Unwrap Spring CGLIB proxies before registration. Toolkit.registerTool()
                    // scans clazz.getDeclaredMethods() for @Tool annotations; on a CGLIB proxy
                    // (e.g. ToolRoutersIndex proxied by TimedAspect because router_tool has
                    // @Timed), getDeclaredMethods() returns the proxy's synthetic bridge
                    // methods which don't carry @Tool — so router_tool / toolMetaInfo would
                    // silently fail to register on subagents. AopProxyUtils.getSingletonTarget
                    // returns the real target instance behind a singleton proxy.
                    Object target = org.springframework.aop.framework.AopProxyUtils
                            .getSingletonTarget(tool);
                    if (target == null) {
                        target = tool;
                    } else if (target.getClass() != tool.getClass()) {
                        log.info(
                                "SubagentRegistrar: unwrapped CGLIB proxy {} -> {} for tool '{}'",
                                tool.getClass().getName(), target.getClass().getName(), name);
                    }
                    tk.registerTool(target);
                    registered.add(name);
                } else {
                    log.warn("Subagent '{}' references unknown tool '{}'; skipping", id, name);
                }
            }

            HarnessAgent.Builder sub = HarnessAgent.builder()
                    .name(id)
                    .model(model)
                    .workspace(workspace)
                    .toolkit(tk)
                    .modelExecutionConfig(AgentExecutionConfig.MODEL_DEFAULTS)
                    .toolExecutionConfig(AgentExecutionConfig.TOOL_DEFAULTS)
                    .sysPrompt(sysPrompt)
                    .maxIters(steps)
                    .disableSubagents()
                    .disableMemoryHooks()
                    // 2026/07/25: 禁掉 JAR 自动注册的文件系统/shell/memory 工具。子 agent
                    // 的业务工具 (python_exec / arith / router_tool) 由上方 toolRegistry 显式
                    // 注册到 toolkit,不受影响。memory_get 在 build 后用 PerUserMemoryGetTool
                    // 重新注册(per-user 隔离)。session_* 工具见下方 post-build removeTool。
                    .disableFilesystemTools()
                    .disableShellTool()
                    .disableMemoryTools();

            // 2026/07/25: 关闭 analyze_data 子 agent 的 plan mode 并禁掉 plan_enter / plan_exit /
            // plan_write 工具。原先启用 plan mode 是为了"查询+计算+报告"多步任务结构化跟踪,但实测
            // LLM 经常卡在 plan_enter 循环 + HITL ASK 阻塞,反而拖慢 E2E。改为纯 ReAct 走 5 步固定
            // workflow (load_skill -> router_tool -> python_exec -> arith -> 回复),用 todo_write
            // (JAR TodoTools,不在禁用清单) 做轻量跟踪即可。
            boolean enablePlan = false;
            // 保留旧分支结构以便回滚 -- 把上面这行改回 `boolean enablePlan = "analyze_data".equals(id);`
            // 即可恢复 plan mode + replacePlanExitWithAutoApprove 路径。
            if (enablePlan) {
                sub.enablePlanMode()
                   .planFileDirectory("plans/subagents/" + id)
                   .enableTaskList(true);
            }

            SandboxFilesystemSpec fs = sandboxFsProvider != null ? sandboxFsProvider.getIfAvailable() : null;
            if (fs != null) {
                sub.filesystem(fs);
            }

            // v1 parity (SupervisorService:562-569): subagentFactory builds a fresh builder,
            // so main agent's hooks are NOT inherited - each subagent must wire its own chain.
            // - ArtifactHandoffHook: rewrites router_tool tabular results into CSV artifact
            //   references so code_interpreter can pd.read_csv(handle) instead of copying
            //   markdown tables into the next agent_spawn prompt.
            // - ArtifactAccessMiddleware: cross-tenant path guard - prevents a hallucinating
            //   python_exec from reading another user's artifacts in the shared bind mount.
            // - PythonExecRetryHook: only for subagents declaring python_exec (code_interpreter);
            //   no-op for others because the hook checks toolUse.name == "python_exec".
            // RuntimeContextAware (ArtifactHandoffHook) and onActing's ctx parameter
            // (ArtifactAccessMiddleware) ensure per-request context resolves correctly even
            // though the singleton bean is shared between main agent and all subagents.
            boolean hasPythonExec = toolNames.contains("python_exec");
            if (artifactHandoffHook != null) {
                sub.hook(artifactHandoffHook);
            }
            List<io.agentscope.core.middleware.MiddlewareBase> subMiddlewares = new ArrayList<>();
            if (artifactAccessMiddleware != null) {
                subMiddlewares.add(artifactAccessMiddleware);
            }
            // P0-5: PythonExecAccessMiddleware only needed on subagents that declare python_exec
            // (code_interpreter). Other subagents never invoke python_exec, so the guard is dead
            // weight and skips the per-call scan.
            if (hasPythonExec && pythonExecAccessMiddleware != null) {
                subMiddlewares.add(pythonExecAccessMiddleware);
            }
            // SubagentEventForwardingMiddleware: taps onReasoning/onModelCall/onActing and
            // mirrors each AgentEvent to the parent's emitter (retrieved from Reactor context
            // written by AgentSpawnTool.execLocalSync) with the subagent's name as source.
            // Without this, the parent SSE stream only sees SubagentExposedEvent — subagent
            // text_block_delta / tool_call_start / etc. are dropped on the floor of the
            // subagent's filtered Flux inside callInternal.
//            subMiddlewares.add(subagentEventForwardingMiddleware);
            // ToolResultTruncationMiddleware: shortens previously-consumed tool results
            // (e.g. load_skill_through_path SKILL.md) to reduce LLM context bloat on
            // subagent multi-round ReAct loops. The last ToolResultBlock is always left
            // intact (the LLM is about to consume it); only older ones are shortened.
            if (toolResultTruncationMiddleware != null) {
                subMiddlewares.add(toolResultTruncationMiddleware);
            }
            if (!subMiddlewares.isEmpty()) {
                sub.middlewares(subMiddlewares);
            }
            if (hasPythonExec && pythonExecRetryHook != null) {
                sub.hook(pythonExecRetryHook);
            }
            // V3.0: collect L2 (sub-agent) tool-call events into the shared VerificationContext.
            if (l2EventCollectorHook != null) {
                sub.hook(l2EventCollectorHook);
            }

            // ToolCallTrackingHook: records tool calls (name + input) into the per-request
            // ToolCallCollector on RuntimeContext. The parent's SSE handler (V2ChatStreamServiceImpl)
            // looks up toolInput from this collector for tool_call_start events. Without this hook
            // on subagents, subagent tool_call_start events carry toolInput=null, so the frontend
            // ActivityFeed and PlanPanel/TodoListPanel can't display subagent task details (e.g.
            // todo_write's task list JSON, plan_enter/plan_write parameters).
            if (toolCallTrackingHook != null) {
                sub.hook(toolCallTrackingHook);
            }

            // AiChatRestToolCallTrackingToDbHook: 捕获子 agent 的 Hook 事件完整 payload 到共享 TraceSession。
            // 子 agent 共享请求级 RuntimeContext（见 L2EventCollectorHook 注释），故能拿到主 agent
            // 创建的 TraceSession；source 字段为子 agent 名，前端据此区分来源。
            if (traceCollectorHook != null) {
                sub.hook(traceCollectorHook);
            }

            HarnessAgent built = sub.build();

            // 2026/07/25: 显式移除 JAR 自动注册的 session / plan 工具(无 disable flag)。
            // 防止子 agent 把 token 烧在 session_search / session_list / plan_enter 等探查上。
            try {
                Toolkit builtTk0 = built.getToolkit();
                for (String tn : new String[]{
                        "session_search", "session_list", "session_save",
                        "plan_enter", "plan_exit", "plan_write"
                }) {
                    try {
                        builtTk0.removeTool(tn);
                    } catch (Exception ignored) {
                        // tool not registered by JAR - fine
                    }
                }
            } catch (Exception e) {
                log.warn("Subagent '{}': failed to strip session/plan tools: {}", id, e.getMessage());
            }

            // Replace the framework's memory_get with PerUserMemoryGetTool on subagents too.
            // The framework auto-registers MemoryGetTool (HarnessAgent.java:2232) on all agents
            // unless disableMemoryTools() is called. disableMemoryTools() is now called above,
            // so memory_get isn't auto-registered; removeTool() is a no-op safety net.
            // PerUserMemoryGetTool is still registered for per-user isolation.
            if (mysqlMemoryStore != null) {
                try {
                    Toolkit builtTk = built.getToolkit();
                    builtTk.removeTool("memory_get");
                    builtTk.registerTool(new PerUserMemoryGetTool(mysqlMemoryStore));
                } catch (Exception e) {
                    log.warn("Subagent '{}': failed to replace memory_get tool: {}", id, e.getMessage());
                }
            }

            // Replace the JAR's PlanExitTool with AutoApprovePlanExitTool so plan_exit
            // no longer triggers the framework's HITL ASK pause. Without this, the
            // subagent would block on plan_exit awaiting human approval (which has no
            // frontend UI), causing agent_spawn to time out at 600s.
            if (enablePlan) {
                HarnessA2aRunnerV2.replacePlanExitWithAutoApprove(built);
            }

            log.debug("Built subagent '{}' with tools={} planMode={} handoff={} access={} pyGuard={} retry={} l2Collector={} toolTracking={} truncation={} eventForwarding=true",
                    id, registered, enablePlan,
                    artifactHandoffHook != null,
                    artifactAccessMiddleware != null,
                    hasPythonExec && pythonExecAccessMiddleware != null,
                    hasPythonExec && pythonExecRetryHook != null,
                    l2EventCollectorHook != null,
                    toolCallTrackingHook != null,
                    toolResultTruncationMiddleware != null);
            return built;
        });
    }

    /**
     * Load {@code skills/_common/SKILL.md} body (stripped of YAML frontmatter) at startup.
     * The content is prepended to every subagent's sysPrompt so each *_metrics skill
     * doesn't need to repeat CSV path / arith / empty-result / direct-call rules.
     * Returns empty string if file missing (graceful degradation - subagents just
     * lose the shared rules, still functional via per-skill rules).
     */
    private static String loadCommonRules(Path workspace) {
        if (workspace == null) return "";
        Path commonPath = workspace.resolve("skills").resolve("_common").resolve("SKILL.md");
        if (!Files.exists(commonPath)) {
            log.warn("SubagentRegistrar: _common/SKILL.md not found at {}, subagent sysPrompt will not include shared rules",
                    commonPath);
            return "";
        }
        try {
            String content = Files.readString(commonPath);
            // Strip YAML frontmatter (--- ... ---) if present
            if (content.startsWith("---")) {
                int end = content.indexOf("\n---", 3);
                if (end > 0) {
                    content = content.substring(end + 4).stripLeading();
                }
            }
            log.info("SubagentRegistrar: loaded _common/SKILL.md ({} chars) from {}",
                    content.length(), commonPath);
            return content;
        } catch (Exception e) {
            log.warn("SubagentRegistrar: failed to read _common/SKILL.md: {}", e.getMessage());
            return "";
        }
    }
}
