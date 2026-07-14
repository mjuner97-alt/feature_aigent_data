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
import com.agentscopea2a.v2.middleware.ArtifactAccessMiddleware;
import com.agentscopea2a.v2.middleware.DimensionStateMiddleware;
import com.agentscopea2a.v2.middleware.EpisodicRetrievalMiddleware;
import com.agentscopea2a.v2.middleware.ResponseCacheMiddleware;
import com.agentscopea2a.v2.middleware.SessionMiddleware;
import com.agentscopea2a.v2.tools.V2ToolGroupAdapter;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.HarnessAgent;
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
            ObjectProvider<ArtifactHandoffHook> artifactHandoffHookProvider,
            ObjectProvider<PythonExecRetryHook> pythonExecRetryHookProvider,
            ObjectProvider<ToolCallTrackingHook> toolCallTrackingHookProvider,
            ObjectProvider<V2ToolGroupAdapter> toolGroupAdapterProvider,
            ObjectProvider<SandboxFilesystemSpec> sandboxFilesystemProvider,
            ObjectProvider<DistributedStore> distributedStoreProvider) {
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

        Path workspace = Paths.get(workspacePath).toAbsolutePath();

        List<MiddlewareBase> middlewares = new ArrayList<MiddlewareBase>(List.of(
                responseCacheMiddleware,
                dimensionStateMiddleware,
                episodicRetrievalMiddleware,
                artifactAccessMiddleware,
                sessionMiddleware
        ));

        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name("QualitySupervisorV2")
                .model(model)
                .workspace(workspace)
                .stateStore(new MysqlAgentStateStore(dataSource))
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

        SandboxFilesystemSpec sandboxFilesystem = sandboxFilesystemProvider.getIfAvailable();
        if (sandboxFilesystem != null) {
            builder.filesystem(sandboxFilesystem);
            log.info("HarnessA2aRunnerV2: sandbox filesystem wired ({})", sandboxFilesystem.getClass().getSimpleName());
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

        // v2 Toolkit — replaces ToolRoutersIndex's flat router_tool dispatch with native
        // tool groups and the reset_equipped_tools meta-tool for LLM-driven group switching.
        V2ToolGroupAdapter toolGroupAdapter = toolGroupAdapterProvider.getIfAvailable();
        if (toolGroupAdapter != null) {
            builder.toolkit(toolGroupAdapter.getToolkit());
            log.info("HarnessA2aRunnerV2: Toolkit wired ({} tools, groups: {})",
                    toolGroupAdapter.getToolkit().getToolNames().size(),
                    toolGroupAdapter.getToolkit().getActiveGroups());
        }

        this.agent = builder.build();

        log.info("HarnessA2aRunnerV2 initialized: workspace={}, model={}, stateStore=MysqlAgentStateStore, " +
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
