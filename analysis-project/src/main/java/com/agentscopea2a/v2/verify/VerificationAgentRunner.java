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
package com.agentscopea2a.v2.verify;

import com.agentscopea2a.v2.config.HarnessRunnerProperties;
import com.agentscopea2a.v2.hooks.ArtifactHandoffHook;
import com.agentscopea2a.v2.middleware.ArtifactAccessMiddleware;
import com.agentscopea2a.v2.middleware.PythonExecAccessMiddleware;
import com.agentscopea2a.v2.model.FallbackModelDecorator;
import com.agentscopea2a.v2.model.ModelProvider;
import com.agentscopea2a.v2.tools.PythonExecTool;
import com.agentscopea2a.v2.tools.ToolRoutersIndex;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import reactor.core.publisher.Mono;

/**
 * Shared infrastructure for the verify / critic sub-agent invokers (V3.0 design §8/§9). Builds a
 * standalone, read-only {@link HarnessAgent} (fresh builder, not inheriting the supervisor's hooks)
 * with the independent verify/critic model, a minimal toolkit ({@code tool_router} + {@code python_exec}),
 * and the artifact handoff + cross-tenant guard wiring - then runs it and extracts the final text.
 *
 * <p>The sub-agent runs on a <b>fresh {@link RuntimeContext}</b> carrying the same session/user
 * (so it can read the request's tenant-scoped artifacts) but without the request's
 * {@code VerificationContext} / {@code ToolCallCollector} (isolation + anti-recursion).
 */
abstract class VerificationAgentRunner {

    private static final Logger log = LoggerFactory.getLogger(VerificationAgentRunner.class);

    protected final ModelProvider modelProvider;
    protected final ToolRoutersIndex toolRoutersIndex;
    protected final ObjectProvider<PythonExecTool> pythonExecProvider;
    protected final ObjectProvider<ArtifactHandoffHook> artifactHandoffProvider;
    protected final ObjectProvider<ArtifactAccessMiddleware> artifactAccessProvider;
    protected final ObjectProvider<PythonExecAccessMiddleware> pythonExecAccessProvider;
    protected final HarnessRunnerProperties properties;
    protected final HarnessRunnerProperties.Verification config;

    VerificationAgentRunner(ModelProvider modelProvider,
                            ToolRoutersIndex toolRoutersIndex,
                            ObjectProvider<PythonExecTool> pythonExecProvider,
                            ObjectProvider<ArtifactHandoffHook> artifactHandoffProvider,
                            ObjectProvider<ArtifactAccessMiddleware> artifactAccessProvider,
                            ObjectProvider<PythonExecAccessMiddleware> pythonExecAccessProvider,
                            HarnessRunnerProperties properties) {
        this.modelProvider = modelProvider;
        this.toolRoutersIndex = toolRoutersIndex;
        this.pythonExecProvider = pythonExecProvider;
        this.artifactHandoffProvider = artifactHandoffProvider;
        this.artifactAccessProvider = artifactAccessProvider;
        this.pythonExecAccessProvider = pythonExecAccessProvider;
        this.properties = properties;
        this.config = properties.getVerification();
    }

    /** Build the standalone read-only sub-agent. */
    protected HarnessAgent buildAgent(String name, String sysPromptResourcePath, String modelKey, int maxIters) {
        String sysPrompt = readSysPrompt(sysPromptResourcePath);
        FallbackModelDecorator model = modelProvider.getModelByKey(modelKey);

        Toolkit tk = new Toolkit();
        tk.registerTool(toolRoutersIndex);
        PythonExecTool py = pythonExecProvider.getIfAvailable();
        if (py != null) {
            tk.registerTool(py);
        }

        Path workspace = Paths.get(properties.getWorkspace().getPath()).toAbsolutePath();

        HarnessAgent.Builder sub = HarnessAgent.builder()
                .name(name)
                .model(model)
                .workspace(workspace)
                .toolkit(tk)
                .sysPrompt(sysPrompt)
                .maxIters(maxIters)
                .disableSubagents()
                .disableMemoryHooks();

        ArtifactHandoffHook handoff = artifactHandoffProvider.getIfAvailable();
        if (handoff != null) {
            sub.hook(handoff);
        }
        List<io.agentscope.core.middleware.MiddlewareBase> middlewares = new ArrayList<>();
        ArtifactAccessMiddleware aam = artifactAccessProvider.getIfAvailable();
        if (aam != null) {
            middlewares.add(aam);
        }
        PythonExecAccessMiddleware pam = pythonExecAccessProvider.getIfAvailable();
        if (pam != null) {
            middlewares.add(pam);
        }
        if (!middlewares.isEmpty()) {
            sub.middlewares(middlewares);
        }
        return sub.build();
    }

    /** Run the agent and reduce the event stream to the final assistant text. */
    protected Mono<String> runAgent(HarnessAgent agent, String prompt, RuntimeContext requestCtx) {
        RuntimeContext verifyCtx = RuntimeContext.builder()
                .sessionId(requestCtx.getSessionId())
                .userId(requestCtx.getUserId())
                .build();
        Msg userMsg = Msg.builder().role(MsgRole.USER)
                .content(TextBlock.builder().text(prompt).build())
                .build();
        Duration timeout = Duration.ofSeconds(config.getVerifyTimeoutSeconds());
        return agent.streamEvents(List.of(userMsg), verifyCtx)
                .collectList()
                .map(this::extractFinalText)
                .timeout(timeout)
                .onErrorResume(ex -> {
                    log.warn("{}: agent run failed/timed out: {}", agent.getClass().getSimpleName(), ex.getMessage());
                    return Mono.just("");
                });
    }

    private String extractFinalText(List<AgentEvent> events) {
        // Prefer the terminal AgentResultEvent result.
        for (AgentEvent e : events) {
            if (e instanceof AgentResultEvent re && re.getResult() != null) {
                String t = re.getResult().getTextContent();
                if (t != null && !t.isBlank()) {
                    return t;
                }
            }
        }
        // Fall back to accumulated text deltas.
        StringBuilder sb = new StringBuilder();
        for (AgentEvent e : events) {
            if (e instanceof TextBlockDeltaEvent d && d.getDelta() != null) {
                sb.append(d.getDelta());
            }
        }
        return sb.toString();
    }

    /** Read a classpath markdown spec, strip YAML frontmatter, return the body as sysPrompt. */
    protected String readSysPrompt(String resourcePath) {
        try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
            if (in == null) {
                log.warn("VerificationAgentRunner: sysPrompt resource not found: {}", resourcePath);
                return "";
            }
            String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (raw.startsWith("---")) {
                int end = raw.indexOf("\n---", 3);
                if (end > 0) {
                    raw = raw.substring(end + 4);
                }
            }
            return raw.trim();
        } catch (Exception e) {
            log.warn("VerificationAgentRunner: failed to read sysPrompt {}: {}", resourcePath, e.getMessage());
            return "";
        }
    }
}
