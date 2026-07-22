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
import com.agentscopea2a.v2.tools.KnownEntities;
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

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Invokes the {@code verify} sub-agent (V3.0 design §8, Semantic Engine). Spawns the standalone
 * read-only agent with the independent verify model, assembles the audit prompt (user query +
 * deterministic precheck + Semantic Contract snapshot + event stream + Decision Trace + candidate
 * conclusion + known entities), runs it, and parses the strict JSON verdict.
 *
 * <p>Circuit breaker: after {@code circuit-breaker-failures} consecutive failures (timeout / parse
 * error / empty), short-circuits to an UNVERIFIED verdict so the main chain is never blocked.
 */
@Component
public class VerifyAgentInvoker extends VerificationAgentRunner {

    private static final Logger log = LoggerFactory.getLogger(VerifyAgentInvoker.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SYS_PROMPT_RESOURCE = "/workspace/verify-agents/verify.md";

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

    public VerifyAgentInvoker(ModelProvider modelProvider,
                              ToolRoutersIndex toolRoutersIndex,
                              ObjectProvider<PythonExecTool> pythonExecProvider,
                              ObjectProvider<ArtifactHandoffHook> artifactHandoffProvider,
                              ObjectProvider<ArtifactAccessMiddleware> artifactAccessProvider,
                              ObjectProvider<PythonExecAccessMiddleware> pythonExecAccessProvider,
                              HarnessRunnerProperties properties) {
        super(modelProvider, toolRoutersIndex, pythonExecProvider, artifactHandoffProvider,
                artifactAccessProvider, pythonExecAccessProvider, properties);
    }

    public Mono<VerificationVerdict> verify(VerificationContext vctx,
                                            DeterministicChecker.Result precheck,
                                            RuntimeContext ctx) {
        return verify(vctx, precheck, ctx, config.getVerifyModelKey());
    }

    /**
     * Overload that lets a caller (e.g. Replay MODEL mode) pin a specific model instance key instead
     * of the configured {@code verify-model-key}. Falls back to the configured key when null.
     */
    public Mono<VerificationVerdict> verify(VerificationContext vctx,
                                            DeterministicChecker.Result precheck,
                                            RuntimeContext ctx,
                                            String modelKey) {
        if (consecutiveFailures.get() >= config.getCircuitBreakerFailures()) {
            return Mono.just(VerificationVerdict.unverified("circuit-breaker open", "supervisor-exit", vctx.getCandidateSource()));
        }
        if (vctx.incrementVerifyCalls() > config.getMaxVerifyCallsPerRequest()) {
            return Mono.just(VerificationVerdict.unverified("max-verify-calls exceeded", "supervisor-exit", vctx.getCandidateSource()));
        }

        String effectiveKey = (modelKey == null || modelKey.isBlank()) ? config.getVerifyModelKey() : modelKey;
        HarnessAgent agent = buildAgent("verify", SYS_PROMPT_RESOURCE, effectiveKey, 4);
        String prompt = buildPrompt(vctx, precheck);
        return runAgent(agent, prompt, ctx)
                .map(text -> parseVerdict(text, vctx))
                .onErrorResume(ex -> Mono.just(VerificationVerdict.unverified(
                        "verify error: " + ex.getMessage(), "supervisor-exit", vctx.getCandidateSource())));
    }

    private VerificationVerdict parseVerdict(String text, VerificationContext vctx) {
        if (text == null || text.isBlank()) {
            consecutiveFailures.incrementAndGet();
            return VerificationVerdict.unverified("empty verify output", "supervisor-exit", vctx.getCandidateSource());
        }
        String json = extractJson(text);
        try {
            VerificationVerdict v = MAPPER.readValue(json, VerificationVerdict.class);
            consecutiveFailures.set(0);
            return v;
        } catch (Exception e) {
            consecutiveFailures.incrementAndGet();
            log.warn("VerifyAgentInvoker: parse failed: {}; raw={}", e.getMessage(),
                    text.length() > 200 ? text.substring(0, 200) + "..." : text);
            VerificationVerdict v = VerificationVerdict.unverified("verify parse failed", "supervisor-exit", vctx.getCandidateSource());
            v.setSummary("verify output unparseable: " + e.getMessage());
            return v;
        }
    }

    /** Extract the first {...} JSON object from a (possibly markdown-wrapped) LLM output. */
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

    private String buildPrompt(VerificationContext vctx, DeterministicChecker.Result precheck) {
        StringBuilder sb = new StringBuilder();
        sb.append("[用户问题] ").append(vctx.getUserQuery()).append('\n');
        sb.append("[触发级别] ").append(vctx.getTriggerLevel()).append('\n');
        sb.append("[Rule Engine 预检] ").append(precheck == null ? "无" : precheck.toReport()).append('\n');
        sb.append("[Semantic Contract 快照] ").append(renderContract(vctx.getContractSnapshot())).append('\n');
        sb.append("[事件流(快照)]\n").append(renderEventStream(vctx)).append('\n');
        sb.append("[Decision Trace] ").append(renderDecisionTrace(vctx)).append('\n');
        sb.append("[候选结论] 来源:").append(vctx.getCandidateSource())
                .append("  内容: ").append(vctx.getCandidateConclusion()).append('\n');
        sb.append("[已知实体] 部门:").append(KnownEntities.DEPARTMENTS)
                .append(" 应用:").append(KnownEntities.APPLICATIONS).append('\n');
        sb.append("请按 verify.md 三模式审查(业务语义以契约为准),输出严格 JSON(含 TrustScore + 子维度 + metricsUsed + repairHint)。");
        return sb.toString();
    }

    private String renderContract(SemanticContracts.Snapshot snapshot) {
        if (snapshot == null || snapshot.metrics() == null || snapshot.metrics().isEmpty()) {
            return "(无契约)";
        }
        StringBuilder sb = new StringBuilder();
        for (SemanticContracts.MetricContract m : snapshot.metrics()) {
            sb.append(m.metricId()).append("(direction=").append(m.directionHigher())
                    .append(",allow=").append(m.aggregationAllow())
                    .append(",deny=").append(m.aggregationDeny()).append(") ");
        }
        return sb.toString().trim();
    }

    private String renderEventStream(VerificationContext vctx) {
        var events = vctx.getEventStream();
        int from = Math.max(0, events.size() - 30); // last 30 events to bound prompt size
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < events.size(); i++) {
            AgentExecutionEvent e = events.get(i);
            Object tool = e.payload() == null ? null : e.payload().get("tool");
            Object output = e.payload() == null ? null : e.payload().get("output");
            String outStr = output == null ? "" : output.toString();
            if (outStr.length() > 200) outStr = outStr.substring(0, 200) + "...";
            sb.append(e.eventId()).append(" ").append(e.type()).append(" ")
                    .append(tool == null ? e.actor() : tool)
                    .append(": ").append(outStr).append('\n');
        }
        return sb.toString();
    }

    private String renderDecisionTrace(VerificationContext vctx) {
        if (vctx.getDecisionTrace().isEmpty()) return "(无)";
        StringBuilder sb = new StringBuilder();
        for (DecisionTraceEntry d : vctx.getDecisionTrace()) {
            sb.append(d.agentId()).append(": ").append(d.decision()).append("; ");
        }
        return sb.toString();
    }
}
