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
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ReasoningInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.function.Function;

import com.agentscopea2a.v2.middleware.ParentEmitterCarrier;

/**
 * Middleware installed on subagents to mirror their AgentEvent stream to the
 * parent agent's {@link AgentEventEmitter}, so the parent's SSE stream sees
 * subagent internal activity (text_block_delta, tool_call_start, etc.) in
 * real time with {@code event.getSource()} set to the subagent's identifier.
 *
 * <h3>Why this exists</h3>
 *
 * The framework's {@code AgentSpawnTool.execLocalSync} writes the parent's
 * emitter into the subagent's Reactor context via {@code contextWrite}, but
 * the subagent's {@code ReActAgent$CallExecution.publishEvent} only writes
 * to its OWN {@code FluxSink} (the filtered Flux from {@code callInternal}),
 * never to {@code externalEventEmitter} (which is set only in structured-call
 * paths). As a result, subagent {@code AgentEvent}s are dropped on the floor
 * of the subagent's filtered Flux, and the parent's SSE stream only sees the
 * single {@code SubagentExposedEvent} emitted by {@code withSubagentExposedEvent}.
 *
 * <p>This middleware bridges that gap by tapping into the subagent's event
 * stream at the {@link #onReasoning}, {@link #onModelCall}, and {@link #onActing}
 * middleware hooks — each returns a {@code Flux<AgentEvent>} that the subagent
 * is about to publish to its own stream. We mirror each event to the parent's
 * emitter (retrieved via {@link AgentEventEmitter#fromContext}) with the
 * subagent's name as the source tag, so the parent's SSE handler can route
 * the event to the frontend ActivityFeed and the per-subagent message bubble.
 *
 * <h3>Source tagging</h3>
 *
 * Events are tagged with {@code event.withSource(subagentName)}. The frontend
 * ChatPanel uses this source to route {@code text_block_delta} chunks to a
 * dedicated subagent bubble (see ChatPanel.tsx {@code subagentMsgIds} map)
 * and to indent the event in the ActivityFeed.
 *
 * <h3>Installation</h3>
 *
 * Wired by {@code SubagentRegistrar.registerSubagentFromSpec} on every
 * subagent builder. Not installed on the main agent (the main agent's events
 * already flow through its own SSE stream).
 *
 * <p>See docs/Plan-Machie/process-event-streaming.md §"子 agent 内部活动透传"
 * for the design rationale and the framework limitation that necessitates
 * this middleware.
 */
public class SubagentEventForwardingMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(SubagentEventForwardingMiddleware.class);

    // NOTE: Only tap at onReasoning (the outermost hook). The middleware chain is
    // nested — onReasoning wraps onModelCall wraps onActing — so events emitted
    // in inner hooks bubble up through every outer hook's doOnNext. Tapping at
    // all three hooks caused each event to be mirrored to the parent emitter
    // multiple times (text_block_delta 2x → "叠字" duplicated characters in the
    // frontend, tool_call_start 2x). Tapping only at onReasoning catches every
    // event exactly once, since all events bubble up through the outermost hook.
    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx, ReasoningInput input,
                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
        return tapWithParentEmitter(agent.getName(), ctx, next.apply(input));
    }

    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext ctx, ModelCallInput input,
                                        Function<ModelCallInput, Flux<AgentEvent>> next) {
        return next.apply(input);
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        return next.apply(input);
    }

    /**
     * Mirror each event in the subagent's stream to the parent's emitter
     * (if present in the Reactor context) with the subagent's name as source.
     * AgentResultEvent is dropped — the subagent's result is returned to the
     * parent via the normal tool result channel, not as a streamed event.
     */
    private static Flux<AgentEvent> tapWithParentEmitter(String subagentName, RuntimeContext ctx, Flux<AgentEvent> upstream) {
        // Read the parent emitter from the RuntimeContext's ParentEmitterCarrier.
        // We can't use AgentEventEmitter.fromContext(ctxView) here because the
        // subagent's ReActAgent.buildAgentStream overwrites the
        // "agentscope.agent.event.emitter" Reactor context key with the
        // subagent's own emitter (wrapping the subagent's filtered FluxSink).
        // The parent's emitter is carried via RuntimeContext (which is cloned
        // by AgentSpawnTool.execLocalSync → RuntimeContext.builder(ctx).from(ctx))
        // and exposed via ParentEmitterCarrier.
        ParentEmitterCarrier carrier = ctx.get(ParentEmitterCarrier.class);
        if (carrier == null || carrier.getEmitter() == null) {
            log.debug("SubagentEventForwarding[subagent={}] no ParentEmitterCarrier in RuntimeContext " +
                    "(either top-level agent or carrier not populated yet)", subagentName);
            return upstream;
        }
        AgentEventEmitter emitter = carrier.getEmitter();
        log.debug("SubagentEventForwarding[subagent={}] parent emitter found via RuntimeContext, mirroring events", subagentName);
        return upstream.doOnNext(evt -> {
            if (evt instanceof AgentResultEvent) {
                // Don't mirror AgentResultEvent — it's the subagent's final
                // result, which is returned to the parent as a tool result
                // and would double-count if also streamed.
                return;
            }
            try {
                AgentEvent tagged = evt.withSource(subagentName);
                emitter.emit(tagged);
            } catch (Throwable t) {
                log.warn("SubagentEventForwardingMiddleware failed to mirror event type={} for subagent={}: {}",
                        evt.getType(), subagentName, t.getMessage());
            }
        });
    }
}