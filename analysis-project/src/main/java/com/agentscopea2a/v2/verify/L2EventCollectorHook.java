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

import com.agentscopea2a.v2.util.HookRuntimeContext;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.hook.RuntimeContextAware;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * L2 (sub-agent) event collector (V3.0 design §3.3). Wired onto business sub-agents by
 * {@code SubagentRegistrar}; mirrors L1 collection but writes into the shared request-scoped
 * {@link VerificationContext} (sub-agents share the request's {@link RuntimeContext}, same as
 * {@code ToolCallCollector.recordL2}). Collects the sub-agent's internal tool calls + decision
 * trace so the supervisor-side verifier can see the full chain, not just the agent_spawn result.
 *
 * <p>Priority 44 - just before {@code ToolCallTrackingHook}(45). Not wired onto verify/critic
 * (anti-recursion).
 */
@SuppressWarnings("deprecation")
public class L2EventCollectorHook implements Hook, RuntimeContextAware {

    private static final Logger log = LoggerFactory.getLogger(L2EventCollectorHook.class);

    private volatile RuntimeContext currentCtx;

    @Override
    public int priority() {
        return 44;
    }

    @Override
    public void setRuntimeContext(RuntimeContext context) {
        this.currentCtx = context;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        return HookRuntimeContext.resolve()
                .doOnNext(ctx -> handle(event, ctx))
                .switchIfEmpty(Mono.fromRunnable(() -> {
                    if (currentCtx != null) {
                        handle(event, currentCtx);
                    }
                }))
                .then(Mono.just(event));
    }

    private void handle(HookEvent event, RuntimeContext ctx) {
        VerificationContext vctx = ctx.get(VerificationContext.VERIFY_CTX_KEY);
        if (vctx == null) {
            return; // sub-agent not in a verified request
        }
        String actor = event.getAgent() != null ? event.getAgent().getName() : "subagent";
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

    private static String extractText(List<ContentBlock> blocks) {
        if (blocks == null) return "";
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : blocks) {
            if (b instanceof TextBlock tb && tb.getText() != null) {
                sb.append(tb.getText()).append('\n');
            }
        }
        return sb.toString().trim();
    }
}
