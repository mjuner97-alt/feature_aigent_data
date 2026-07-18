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
package com.agentscopea2a.v2.util;

import io.agentscope.core.agent.RuntimeContext;
import reactor.core.publisher.Mono;

/**
 * Resolves the per-request {@link RuntimeContext} from Reactor's {@code ContextView} for hooks
 * that need it.
 *
 * <p><b>Why this exists</b>: The framework's {@code RuntimeContextAware.setRuntimeContext(ctx)}
 * is called once per request lifecycle, but hook beans are singletons shared across concurrent
 * requests. Two concurrent requests overwrite each other's instance field, so a hook that reads
 * {@code this.currentCtx} can see another user's context. The framework also writes the
 * per-request ctx into Reactor's context (key {@link #RUNTIME_CONTEXT_KEY}), which is
 * per-subscription and not shared - so reading from there is safe.
 *
 * <p>Usage in a hook:
 * <pre>{@code
 * @Override
 * public <T extends HookEvent> Mono<T> onEvent(T event) {
 *     if (!(event instanceof PostActingEvent post)) return Mono.just(event);
 *     return HookRuntimeContext.resolve()
 *         .flatMap(ctx -> doWork(post, ctx))
 *         .switchIfEmpty(Mono.just(event));
 * }
 * }</pre>
 *
 * <p>The fallback to {@code setRuntimeContext(ctx)} is kept for tests that drive the hook
 * synchronously without a Reactor context - production code goes through Reactor context.
 */
public final class HookRuntimeContext {

    /** Reactor context key the framework uses to propagate RuntimeContext per subscription. */
    public static final String RUNTIME_CONTEXT_KEY = "io.agentscope.core.agent.AgentBase.runtimeContext";

    private HookRuntimeContext() {}

    /**
     * Returns a Mono that emits the per-request {@link RuntimeContext} from Reactor's context,
     * or an empty Mono if no context is bound (e.g., in unit tests without Reactor setup).
     */
    public static Mono<RuntimeContext> resolve() {
        return Mono.deferContextual(ctxView -> {
            Object raw = ctxView.getOrEmpty(RUNTIME_CONTEXT_KEY).orElse(null);
            return raw instanceof RuntimeContext rc ? Mono.just(rc) : Mono.empty();
        });
    }
}
