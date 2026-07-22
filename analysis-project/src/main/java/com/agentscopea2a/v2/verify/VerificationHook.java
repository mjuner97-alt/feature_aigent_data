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
import com.agentscopea2a.v2.util.HookRuntimeContext;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.hook.RuntimeContextAware;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Supervisor-side verification hook (V3.0 design §12, priority 46 - after
 * {@code ToolCallTrackingHook}(45), before {@code SkillSynthesisHook}(50)). Mirrors the
 * ToolCallTrackingHook pattern: resolves the per-request {@link RuntimeContext} via
 * {@link HookRuntimeContext#resolve()}, retrieves the {@link VerificationContext}, and:
 *
 * <ul>
 *   <li>collects L1 events + Decision Trace into the context (cheap, every event);</li>
 *   <li>triggers the {@link VerifyLoopOrchestrator} at checkpoints:
 *     <ul>
 *       <li><b>subagent-exit</b> - {@code agent_spawn} PostActing (sub-agent result returned);</li>
 *       <li><b>per-critical-tool</b> - PostActing on a configured critical tool;</li>
 *       <li><b>supervisor-exit</b> - PostCall (final answer).</li>
 *     </ul></li>
 * </ul>
 *
 * <p>Anti-recursion: skips verify/critic agents ({@link VerificationContext#isVerifierAgent}).
 * Bean created by {@code V2InfraConfig}; wired onto the main agent via {@code harnessHooks}.
 */
@SuppressWarnings("deprecation")
public class VerificationHook implements Hook, RuntimeContextAware {

    private static final Logger log = LoggerFactory.getLogger(VerificationHook.class);
    private static final String AGENT_SPAWN = "agent_spawn";

    private final VerifyLoopOrchestrator orchestrator;
    private final HarnessRunnerProperties.Verification config;
    private final Set<String> criticalTools;

    private volatile RuntimeContext currentCtx;

    public VerificationHook(VerifyLoopOrchestrator orchestrator, HarnessRunnerProperties properties) {
        this.orchestrator = orchestrator;
        this.config = properties.getVerification();
        this.criticalTools = parseSet(config.getCriticalTools());
    }

    @Override
    public int priority() {
        return 46;
    }

    @Override
    public void setRuntimeContext(RuntimeContext context) {
        this.currentCtx = context;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        // Anti-recursion: never verify the verifier/critic themselves.
        String actor = event.getAgent() != null ? event.getAgent().getName() : null;
        if (VerificationContext.isVerifierAgent(actor)) {
            return Mono.just(event);
        }

        // Cheap event collection first (every event type).
        return HookRuntimeContext.resolve()
                .doOnNext(ctx -> collect(event, ctx, actor))
                .switchIfEmpty(Mono.fromRunnable(() -> {
                    if (currentCtx != null) {
                        collect(event, currentCtx, actor);
                    }
                }))
                .then(Mono.just(event))
                // Checkpoint triggers (only when a vctx is bound and verification is enabled).
                .flatMap(e -> maybeTrigger(e, actor));
    }

    // -------- cheap collection --------

    private void collect(HookEvent event, RuntimeContext ctx, String actor) {
        VerificationContext vctx = ctx.get(VerificationContext.VERIFY_CTX_KEY);
        if (vctx == null) {
            return;
        }
        if (event instanceof PreActingEvent pre) {
            ToolUseBlock tu = pre.getToolUse();
            if (tu == null || tu.getName() == null) return;
            Map<String, Object> payload = new HashMap<>();
            payload.put("tool", tu.getName());
            payload.put("input", tu.getInput());
            vctx.emit(AgentExecutionEvent.TOOL_CALL_STARTED, actor, payload);
        } else if (event instanceof PostActingEvent post) {
            ToolUseBlock tu = post.getToolUse();
            ToolResultBlock tr = post.getToolResult();
            if (tu == null || tu.getName() == null) return;
            String output = extractText(tr == null ? null : tr.getOutput());
            Map<String, Object> payload = new HashMap<>();
            payload.put("tool", tu.getName());
            payload.put("output", output);
            payload.put("isError", output != null && (output.toLowerCase().contains("error") || output.contains("错误")));
            payload.put("isEmpty", output == null || output.isBlank());
            vctx.emit(AgentExecutionEvent.TOOL_CALL_COMPLETED, actor, payload);
        } else if (event instanceof PostReasoningEvent r) {
            vctx.addDecisionTrace(DecisionTraceExtractor.extract(actor, r.getReasoningMessage()));
        }
    }

    // -------- checkpoint triggers --------

    @SuppressWarnings("unchecked")
    private <T extends HookEvent> Mono<T> maybeTrigger(T event, String actor) {
        if (!config.isEnabled()) {
            return Mono.just(event);
        }
        if (event instanceof PostCallEvent pc) {
            if (!config.checkpointEnabled("supervisor-exit")) return Mono.just(event);
            return resolveCtx().flatMap(ctx -> runSupervisorExit(pc, ctx)).then(Mono.just(event));
        }
        if (event instanceof PostReasoningEvent pr) {
            // corrective gotoReasoning loop - only in corrective mode, only on the final candidate
            // (a reasoning step with no pending tool_use = the supervisor is emitting its answer).
            if (!"corrective".equalsIgnoreCase(config.getMode())) {
                return Mono.just(event);
            }
            if (hasToolUse(pr.getReasoningMessage())) {
                return Mono.just(event);
            }
            return resolveCtx().flatMap(ctx -> runSupervisorReasoning(pr, ctx)).then(Mono.just(event));
        }
        if (event instanceof PostActingEvent post) {
            ToolUseBlock tu = post.getToolUse();
            String tool = tu == null ? null : tu.getName();
            if (AGENT_SPAWN.equals(tool) && config.checkpointEnabled("subagent-exit")) {
                return resolveCtx().flatMap(ctx -> runSubagentExit(post, ctx)).then(Mono.just(event));
            }
            if (tool != null && criticalTools.contains(tool) && config.isCriticalToolsEnabled()
                    && config.checkpointEnabled("per-critical-tool")) {
                return resolveCtx().flatMap(ctx -> runPerCriticalTool(post, ctx)).then(Mono.just(event));
            }
        }
        return Mono.just(event);
    }

    private Mono<RuntimeContext> resolveCtx() {
        return HookRuntimeContext.resolve();
    }

    private Mono<Void> runSupervisorExit(PostCallEvent event, RuntimeContext ctx) {
        VerificationContext vctx = ctx.get(VerificationContext.VERIFY_CTX_KEY);
        if (vctx == null) return Mono.empty();
        Msg finalMsg = event.getFinalMessage();
        String conclusion = finalMsg == null ? "" : finalMsg.getTextContent();
        if (conclusion == null) conclusion = "";
        vctx.setCandidateConclusion(conclusion, "supervisor");
        return orchestrator.onSupervisorExit(vctx, event, ctx)
                .onErrorResume(e -> {
                    log.warn("VerificationHook: supervisor-exit failed (degrading): {}", e.getMessage());
                    return Mono.empty();
                });
    }

    private Mono<Void> runSubagentExit(PostActingEvent event, RuntimeContext ctx) {
        VerificationContext vctx = ctx.get(VerificationContext.VERIFY_CTX_KEY);
        if (vctx == null) return Mono.empty();
        String conclusion = extractText(event.getToolResult() == null ? null : event.getToolResult().getOutput());
        vctx.setCandidateConclusion(conclusion, "subagent:" + event.getToolUse().getId());
        return orchestrator.onSubagentExit(vctx, event, ctx)
                .onErrorResume(e -> {
                    log.warn("VerificationHook: subagent-exit failed (degrading): {}", e.getMessage());
                    return Mono.empty();
                });
    }

    private Mono<Void> runPerCriticalTool(PostActingEvent event, RuntimeContext ctx) {
        VerificationContext vctx = ctx.get(VerificationContext.VERIFY_CTX_KEY);
        if (vctx == null) return Mono.empty();
        String conclusion = extractText(event.getToolResult() == null ? null : event.getToolResult().getOutput());
        vctx.setCandidateConclusion(conclusion, "tool:" + event.getToolUse().getName());
        return orchestrator.onPerCriticalTool(vctx, event, ctx)
                .onErrorResume(e -> {
                    log.warn("VerificationHook: per-critical-tool failed (degrading): {}", e.getMessage());
                    return Mono.empty();
                });
    }

    private Mono<Void> runSupervisorReasoning(PostReasoningEvent event, RuntimeContext ctx) {
        VerificationContext vctx = ctx.get(VerificationContext.VERIFY_CTX_KEY);
        if (vctx == null) return Mono.empty();
        Msg reasoning = event.getReasoningMessage();
        String conclusion = reasoning == null ? "" : reasoning.getTextContent();
        if (conclusion == null || conclusion.isBlank()) return Mono.empty();
        vctx.setCandidateConclusion(conclusion, "supervisor-reasoning");
        return orchestrator.onSupervisorReasoning(vctx, event, ctx)
                .onErrorResume(e -> {
                    log.warn("VerificationHook: supervisor-reasoning failed (degrading): {}", e.getMessage());
                    return Mono.empty();
                });
    }

    /** True if the reasoning message carries a tool_use block (acting follows, not a final answer). */
    private static boolean hasToolUse(Msg msg) {
        if (msg == null || msg.getContent() == null) return false;
        for (ContentBlock b : msg.getContent()) {
            if (b instanceof ToolUseBlock) return true;
        }
        return false;
    }

    private static String extractText(java.util.List<ContentBlock> blocks) {
        if (blocks == null) return "";
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : blocks) {
            if (b instanceof TextBlock tb && tb.getText() != null) {
                sb.append(tb.getText()).append('\n');
            }
        }
        return sb.toString().trim();
    }

    private static Set<String> parseSet(String csv) {
        if (csv == null || csv.isBlank()) return new HashSet<>();
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }
}
