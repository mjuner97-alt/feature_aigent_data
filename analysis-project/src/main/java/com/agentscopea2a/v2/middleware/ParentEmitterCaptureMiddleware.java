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
package com.agentscopea2a.v2.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventEmitter;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ReasoningInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.function.Function;

/**
 * Middleware installed on the MAIN (parent) agent that captures the parent's
 * {@link AgentEventEmitter} from the Reactor context and stores it in the
 * {@link ParentEmitterCarrier} (read from {@link RuntimeContext}).
 *
 * <h3>Why this exists</h3>
 *
 * The parent's {@code AgentEventEmitter} is created inside
 * {@code ReActAgent.buildAgentStream}'s {@code Flux.create} lambda and
 * written into the Reactor context via {@code contextWrite}. It isn't
 * accessible from {@code V2ChatStreamServiceImpl.stream()} when the
 * {@code RuntimeContext} is built. This middleware runs INSIDE the parent's
 * middleware chain (where the Reactor context already has the emitter) and
 * captures it into the carrier as a side effect of {@code Flux.deferContextual}.
 *
 * <p>Once the carrier is populated, subagents dispatched via {@code agent_spawn}
 * can read it from their cloned {@code RuntimeContext} (cloned because
 * {@code AgentSpawnTool.execLocalSync} calls
 * {@code RuntimeContext.builder(ctx).from(ctx)} which copies typed attributes)
 * and use it to mirror their events to the parent's SSE stream. See
 * {@link SubagentEventForwardingMiddleware} for the mirroring logic.
 *
 * <h3>Installation</h3>
 *
 * Wired on the main agent only, NOT on subagents (subagents don't need to
 * capture the parent emitter — they read it from their RuntimeContext).
 * Installed by {@code HarnessA2aRunnerV2} after the other middlewares.
 */
public class ParentEmitterCaptureMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(ParentEmitterCaptureMiddleware.class);

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx, ReasoningInput input,
                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
        return captureEmitter(ctx, next.apply(input));
    }

    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext ctx, ModelCallInput input,
                                        Function<ModelCallInput, Flux<AgentEvent>> next) {
        return captureEmitter(ctx, next.apply(input));
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        return captureEmitter(ctx, next.apply(input));
    }

    /**
     * Read the parent's AgentEventEmitter from the Reactor context (written by
     * ReActAgent.buildAgentStream under "agentscope.agent.event.emitter") and
     * store it in the ParentEmitterCarrier from the RuntimeContext. The capture
     * happens at subscription time via {@code Flux.deferContextual}. The
     * upstream Flux is returned unchanged.
     */
    private static Flux<AgentEvent> captureEmitter(RuntimeContext ctx, Flux<AgentEvent> upstream) {
        ParentEmitterCarrier carrier = ctx.get(ParentEmitterCarrier.class);
        if (carrier == null) {
            // Carrier not installed (e.g., test setup without V2ChatStreamServiceImpl).
            // Skip capture — subagents won't be able to mirror events, but the main
            // agent's stream still works normally.
            log.debug("ParentEmitterCaptureMiddleware: no ParentEmitterCarrier in RuntimeContext " +
                    "(sessionId={}) — subagent event mirroring disabled for this call",
                    ctx.getSessionId());
            return upstream;
        }
        return Flux.deferContextual(ctxView -> {
            if (carrier.getEmitter() == null) {
                var parentEmitter = AgentEventEmitter.fromContext(ctxView);
                if (parentEmitter.isPresent()) {
                    carrier.setEmitter(parentEmitter.get());
                    log.debug("ParentEmitterCaptureMiddleware: captured parent emitter for agent={}",
                            ctx.getSessionId());
                }
            }
            return upstream;
        });
    }
}