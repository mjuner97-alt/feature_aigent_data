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
import com.agentscopea2a.v2.hooks.PythonExecRetryHook;
import com.agentscopea2a.v2.hooks.ToolCallTrackingHook;
import com.agentscopea2a.v2.memory.MysqlMemoryStore;
import com.agentscopea2a.v2.middleware.ArtifactAccessMiddleware;
import com.agentscopea2a.v2.middleware.PythonExecAccessMiddleware;
import com.agentscopea2a.v2.middleware.SubagentEventForwardingMiddleware;
import com.agentscopea2a.v2.tools.ArithTool;
import com.agentscopea2a.v2.tools.PerUserMemoryGetTool;
import com.agentscopea2a.v2.tools.PythonExecTool;
import com.agentscopea2a.v2.tools.SkillSaveTool;
import com.agentscopea2a.v2.tools.ToolRoutersIndex;
import com.agentscopea2a.v2.config.WorkspaceMaterializer;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.subagent.AgentSpecLoader;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
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
            ObjectProvider<ArtifactHandoffHook> artifactHandoffHookProvider,
            ObjectProvider<ArtifactAccessMiddleware> artifactAccessMiddlewareProvider,
            ObjectProvider<PythonExecAccessMiddleware> pythonExecAccessMiddlewareProvider,
            ObjectProvider<PythonExecRetryHook> pythonExecRetryHookProvider,
            ObjectProvider<ToolCallTrackingHook> toolCallTrackingHookProvider,
            ObjectProvider<MysqlMemoryStore> mysqlMemoryStoreProvider) {

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
        this.artifactHandoffHook = artifactHandoffHookProvider.getIfAvailable();
        this.artifactAccessMiddleware = artifactAccessMiddlewareProvider.getIfAvailable();
        this.pythonExecAccessMiddleware = pythonExecAccessMiddlewareProvider.getIfAvailable();
        this.pythonExecRetryHook = pythonExecRetryHookProvider.getIfAvailable();
        this.subagentEventForwardingMiddleware = new SubagentEventForwardingMiddleware();
    this.toolCallTrackingHook = toolCallTrackingHookProvider.getIfAvailable();
        this.mysqlMemoryStore = mysqlMemoryStoreProvider.getIfAvailable();
        log.info("SubagentRegistrar: toolRegistry built with {} entries: {}; hooks - handoff={} access={} pyGuard={} retry={} eventForwarding=true",
                toolRegistry.size(), toolRegistry.keySet(),
                artifactHandoffHook != null, artifactAccessMiddleware != null,
                pythonExecAccessMiddleware != null, pythonExecRetryHook != null);

        Path workspace = Paths.get(workspacePath).toAbsolutePath();
        workspace = WorkspaceMaterializer.ensureMaterialized(workspace);
        Path dir = workspace.resolve("agent-subagents");
        this.specs = AgentSpecLoader.loadFromDirectory(dir, workspace);

        for (SubagentDeclaration spec : specs) {
            List<String> tools = spec.getTools() != null ? spec.getTools() : List.of();
            for (String t : tools) {
                if (!toolRegistry.containsKey(t)) {
                    throw new IllegalStateException(
                            "Subagent '" + spec.getName() + "' declares unknown tool '" + t
                                    + "'. Known tools: " + toolRegistry.keySet());
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
        String sysPrompt = spec.getInlineAgentsBody();
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
                    .sysPrompt(sysPrompt)
                    .maxIters(steps)
                    .disableSubagents()
                    .disableMemoryHooks();

            // Plan mode: only analyze_data needs structured plan + task list tracking.
            // query_data and generate_skill have simple linear workflows where plan mode
            // is over-engineering. See docs/Plan-Machie/plan-mode-subagent-migration.md.
            boolean enablePlan = "analyze_data".equals(id);
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
            subMiddlewares.add(subagentEventForwardingMiddleware);
            if (!subMiddlewares.isEmpty()) {
                sub.middlewares(subMiddlewares);
            }
            if (hasPythonExec && pythonExecRetryHook != null) {
                sub.hook(pythonExecRetryHook);
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

            HarnessAgent built = sub.build();

            // Replace the framework's memory_get with PerUserMemoryGetTool on subagents too.
            // The framework auto-registers MemoryGetTool (HarnessAgent.java:2232) on all agents
            // unless disableMemoryTools() is called. Subagents don't disable it, so they inherit
            // the shared-root filesystem fallback that causes cross-tenant leaks. Same pattern
            // as the main agent's replacement in HarnessA2aRunnerV2.
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

            log.debug("Built subagent '{}' with tools={} planMode={} handoff={} access={} pyGuard={} retry={} toolTracking={} eventForwarding=true",
                    id, registered, enablePlan,
                    artifactHandoffHook != null,
                    artifactAccessMiddleware != null,
                    hasPythonExec && pythonExecAccessMiddleware != null,
                    hasPythonExec && pythonExecRetryHook != null,
                    toolCallTrackingHook != null);
            return built;
        });
    }
}
