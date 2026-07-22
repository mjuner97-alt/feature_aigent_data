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
import com.agentscopea2a.v2.model.ModelProvider;
import com.agentscopea2a.v2.tools.PythonExecTool;
import com.agentscopea2a.v2.tools.ToolRoutersIndex;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Invokes the {@code critic} sub-agent (V3.0 design §9, Adversarial Critic + counterfactual).
 * Spawns the standalone read-only agent with the independent critic model, hands it the candidate
 * conclusion + artifact paths (so it can {@code pd.read_csv} for the counterfactual recompute), and
 * parses the adversarial score + holes. Circuit-breaks to {@link CriticResult#unverified} on failure.
 */
@Component
public class CriticAgentInvoker extends VerificationAgentRunner {

    private static final Logger log = LoggerFactory.getLogger(CriticAgentInvoker.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SYS_PROMPT_RESOURCE = "/workspace/verify-agents/critic.md";

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final CriticChallengeStats criticStats;

    public CriticAgentInvoker(ModelProvider modelProvider,
                              ToolRoutersIndex toolRoutersIndex,
                              ObjectProvider<PythonExecTool> pythonExecProvider,
                              ObjectProvider<ArtifactHandoffHook> artifactHandoffProvider,
                              ObjectProvider<ArtifactAccessMiddleware> artifactAccessProvider,
                              ObjectProvider<PythonExecAccessMiddleware> pythonExecAccessProvider,
                              HarnessRunnerProperties properties,
                              CriticChallengeStats criticStats) {
        super(modelProvider, toolRoutersIndex, pythonExecProvider, artifactHandoffProvider,
                artifactAccessProvider, pythonExecAccessProvider, properties);
        this.criticStats = criticStats;
    }

    public Mono<CriticResult> critic(VerificationContext vctx, RuntimeContext ctx) {
        if (!config.getCritic().isEnabled() || !config.getCritic().isCounterfactual()) {
            return Mono.just(CriticResult.unverified("critic disabled"));
        }
        if (consecutiveFailures.get() >= config.getCircuitBreakerFailures()) {
            return Mono.just(CriticResult.unverified("circuit-breaker open"));
        }
        if (vctx.incrementVerifyCalls() > config.getMaxVerifyCallsPerRequest()) {
            return Mono.just(CriticResult.unverified("max-verify-calls exceeded"));
        }

        HarnessAgent agent = buildAgent("critic", SYS_PROMPT_RESOURCE, config.getCriticModelKey(), 3);
        String prompt = buildPrompt(vctx);
        return runAgent(agent, prompt, ctx)
                .map(this::parse)
                .onErrorResume(ex -> Mono.just(CriticResult.unverified("critic error: " + ex.getMessage())));
    }

    @SuppressWarnings("unchecked")
    private CriticResult parse(String text) {
        if (text == null || text.isBlank()) {
            consecutiveFailures.incrementAndGet();
            return CriticResult.unverified("empty critic output");
        }
        String json = extractJson(text);
        try {
            Map<String, Object> m = MAPPER.readValue(json, Map.class);
            consecutiveFailures.set(0);
            int score = asInt(m.get("adversarialScore"), 100);
            boolean fragile = false;
            String cfNote = "";
            Object cf = m.get("counterfactual");
            if (cf instanceof Map<?, ?> cfm) {
                int fragileSubsets = asInt(cfm.get("fragileSubsets"), 0);
                fragile = fragileSubsets > 0 || Boolean.FALSE.equals(cfm.get("holdsOnFullSet"));
                cfNote = java.util.Objects.toString(cfm.get("reversedIn"), "");
            }
            List<CriticResult.Hole> holes = new ArrayList<>();
            Object h = m.get("holes");
            if (h instanceof List<?> hl) {
                for (Object o : hl) {
                    if (o instanceof Map<?, ?> hm) {
                        holes.add(new CriticResult.Hole(
                                java.util.Objects.toString(hm.get("type"), ""),
                                java.util.Objects.toString(hm.get("description"), ""),
                                java.util.Objects.toString(hm.get("evidence"), "")));
                    }
                }
            }
            String summary = String.valueOf(m.getOrDefault("summary", ""));
            return new CriticResult(score, fragile, cfNote, holes, summary);
        } catch (Exception e) {
            consecutiveFailures.incrementAndGet();
            log.warn("CriticAgentInvoker: parse failed: {}", e.getMessage());
            return CriticResult.unverified("critic parse failed: " + e.getMessage());
        }
    }

    private static int asInt(Object o, int def) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) {
            try { return Integer.parseInt(s.trim()); } catch (Exception ignored) {}
        }
        return def;
    }

    private String buildPrompt(VerificationContext vctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("[用户问题] ").append(vctx.getUserQuery()).append('\n');
        sb.append("[候选结论] ").append(vctx.getCandidateConclusion()).append('\n');
        sb.append("[可用 artifact CSV 路径(只读 pd.read_csv 核对/反事实重算)]\n");
        for (String p : vctx.getArtifactAgentPaths()) {
            sb.append("  ").append(p).append('\n');
        }
        sb.append("\n请按 critic.md 主动找漏洞并做反事实检验,输出严格 JSON。");
        String hint = criticStats.focusHint();
        if (hint != null && !hint.isBlank()) {
            sb.append("\n").append(hint);
        }
        return sb.toString();
    }

    private static String extractJson(String text) {
        String t = text.trim();
        if (t.startsWith("```")) {
            int firstNewline = t.indexOf('\n');
            if (firstNewline > 0) t = t.substring(firstNewline + 1);
            int fence = t.lastIndexOf("```");
            if (fence > 0) t = t.substring(0, fence);
        }
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return t.substring(start, end + 1);
        }
        return t;
    }
}
