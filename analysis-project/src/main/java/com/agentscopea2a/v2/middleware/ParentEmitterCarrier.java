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

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventEmitter;
import reactor.core.publisher.FluxSink;

/**
 * Mutable carrier that holds a reference to the parent agent's
 * {@link AgentEventEmitter}, installed on the parent's
 * {@link io.agentscope.core.agent.RuntimeContext} via
 * {@code RuntimeContext.put(ParentEmitterCarrier.class, ...)}.
 *
 * <p>The parent's emitter is created INSIDE the framework's
 * {@code ReActAgent.buildAgentStream} (wraps the parent's FluxSink), so it
 * isn't available when {@code V2ChatStreamServiceImpl.stream()} builds the
 * parent's {@code RuntimeContext}. Instead, we capture the emitter from the
 * Reactor context mid-stream (via a {@code Flux.deferContextual} wrapper
 * around {@code runner.streamEvents()}) and store it in this carrier. The
 * same carrier reference is also put into the {@code RuntimeContext}, so
 * when {@code AgentSpawnTool.execLocalSync} clones the parent's
 * {@code RuntimeContext} into the subagent's, the carrier (and its now-
 * populated emitter) is visible to {@link SubagentEventForwardingMiddleware}
 * running inside the subagent.
 *
 * <p>The {@link #emitter()} field is volatile because it's written from the
 * Reactor subscriber thread (the {@code Flux.deferContextual} lambda) and
 * read from the subagent's middleware thread.
 *
 * <p>See {@link SubagentEventForwardingMiddleware} class javadoc for the
 * framework limitation that necessitates this carrier.
 */
public class ParentEmitterCarrier {

    private volatile AgentEventEmitter emitter;

    public AgentEventEmitter getEmitter() {
        return emitter;
    }

    public void setEmitter(AgentEventEmitter emitter) {
        this.emitter = emitter;
    }

    /**
     * Convenience factory: create an {@link AgentEventEmitter} that forwards
     * {@code emit()} calls to the given {@link FluxSink}. Used when we need
     * to inject an emitter into a context that doesn't have one yet (e.g.,
     * before {@code runner.streamEvents()} has populated its own emitter).
     */
    public static AgentEventEmitter forSink(FluxSink<AgentEvent> sink) {
        return sink::next;
    }
}