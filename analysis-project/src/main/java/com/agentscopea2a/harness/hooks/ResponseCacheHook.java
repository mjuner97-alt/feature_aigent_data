/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.agentscopea2a.harness.hooks;

import com.agentscopea2a.harness.cache.ResponseCacheService;
import com.agentscopea2a.agent.dimension.DimensionState;
import com.agentscopea2a.agent.dimension.DimensionStateManager;
import com.agentscopea2a.agent.dimension.QuestionAnalysis;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Optional;

/**
 * Hook that intercepts agent execution for dimension-based response caching.
 *
 * <p><b>PreCallEvent</b>: Extracts dimensions from the user question via {@link
 * DimensionStateManager#analyzeQuestionRuleBased(String)}, generates a cache key (scoped by
 * userId for multi-tenant safety), and checks the cache. On cache hit, throws {@link
 * CacheHitException} to short-circuit agent execution (skipping LLM calls and tool invocations).
 *
 * <p><b>PostCallEvent</b>: After a cache miss, writes the agent's response to the cache.
 *
 * <p><b>Metrics</b> (P2-2): all paths increment Micrometer counters under {@code
 * harness.a2a.cache.*} — visible via {@code /actuator/metrics}. Tags include {@code outcome=
 * hit|miss|write|error}.
 *
 * <p><b>Usage</b>: Each agent request must use a fresh hook instance (via {@code new
 * ResponseCacheHook(...)}). The hook uses instance fields to pass the cache key from PreCall to
 * PostCall, which is safe because each agent handles one request at a time.
 *
 * <p><b>Priority</b>: 0 (highest priority, runs before all other hooks).
 *
 * @see ResponseCacheService
 * @see DimensionStateManager
 */
public class ResponseCacheHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(ResponseCacheHook.class);
    private static final String METRIC_NAME = "harness.a2a.cache";

    private final ResponseCacheService cacheService;
    private final DimensionStateManager dimManager;

    /**
     * Optional runtime context captured at construction so the cache key can be scoped to {@code
     * userId} — prevents cross-user response leaks even when two users ask the same dimensional
     * question. May be {@code null} for the legacy single-tenant code path.
     */
    private final RuntimeContext runtimeContext;

    private final MeterRegistry meterRegistry;

    // Set in PreCall on cache miss, read in PostCall for cache write.
    // Safe because each agent gets its own hook instance and handles one request at a time.
    private String pendingCacheKey;
    private String pendingQuestion;

    /** Legacy single-tenant ctor — kept for binary compat. */
    public ResponseCacheHook(ResponseCacheService cacheService, DimensionStateManager dimManager) {
        this(cacheService, dimManager, null, null);
    }

    /**
     * Multi-tenant + metered ctor (P2-2). Cache key is scoped by the runtime context's userId
     * (falls back to sessionId then to a global bucket); every hit/miss/write/error increments
     * a tagged counter on {@code meterRegistry}.
     *
     * @param cacheService MySQL-backed cache service for reading/writing cached responses
     * @param dimManager dimension state manager for rule-based dimension extraction
     * @param runtimeContext per-request context; nullable to keep tests / non-A2A paths simple
     * @param meterRegistry Micrometer registry; nullable to disable metrics
     */
    public ResponseCacheHook(
            ResponseCacheService cacheService,
            DimensionStateManager dimManager,
            RuntimeContext runtimeContext,
            MeterRegistry meterRegistry) {
        this.cacheService = cacheService;
        this.dimManager = dimManager;
        this.runtimeContext = runtimeContext;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public int priority() {
        // Run before all other hooks (ProgressiveMemoryHook=5, LoggingHook=10,
        // DataGroundingHook=15)
        return 0;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        Mono<? extends HookEvent> result;
        if (event instanceof PreCallEvent e) {
            result = handlePreCall(e);
        } else if (event instanceof PostCallEvent e) {
            result = handlePostCall(e);
        } else {
            return Mono.just(event);
        }
        return (Mono<T>) result;
    }

    // ==================== PreCall: Cache Check ====================

    private Mono<PreCallEvent> handlePreCall(PreCallEvent event) {
        try {
            String question = ResponseCacheService.extractUserQuestion(event.getInputMessages());
            if (question.isEmpty()) {
                return Mono.just(event);
            }

            // Rule-based dimension analysis (zero LLM overhead)
            QuestionAnalysis analysis = dimManager.analyzeQuestionRuleBased(question);
            DimensionState state = buildFromExplicit(analysis);

            // Generate cache key — prefix with userId so two tenants asking the same dimensional
            // question land in separate cache rows.
            String intent = ResponseCacheService.classifyIntent(question);
            Optional<String> dimKey = cacheService.generateCacheKey(intent, state, question);
            if (dimKey.isEmpty()) {
                log.debug("No cacheable dimensions for question: {}", question);
                return Mono.just(event);
            }

            String cacheKey = scopedKey(dimKey.get());
            Optional<String> cached = cacheService.get(cacheKey);
            if (cached.isPresent()) {
                log.info("Cache HIT for key={}, short-circuiting agent execution", cacheKey);
                count("hit");
                return Mono.error(new CacheHitException(cached.get()));
            }

            // Cache miss — store key for PostCall write
            log.info("Cache MISS for key={}", cacheKey);
            count("miss");
            pendingCacheKey = cacheKey;
            pendingQuestion = question;
            return Mono.just(event);

        } catch (Exception e) {
            // Cache logic failure must never block agent execution
            log.warn("Cache check failed, proceeding without cache: {}", e.getMessage());
            count("error");
            return Mono.just(event);
        }
    }

    // ==================== PostCall: Cache Write ====================

    private Mono<PostCallEvent> handlePostCall(PostCallEvent event) {
        if (pendingCacheKey == null) {
            return Mono.just(event);
        }

        try {
            if (event.getFinalMessage() != null) {
                String responseText = event.getFinalMessage().getTextContent();
                if (responseText != null && !responseText.isEmpty()) {
                    cacheService.put(pendingCacheKey, pendingQuestion, responseText);
                    log.info("Response cached for key={}", pendingCacheKey);
                    count("write");
                }
            }
        } catch (Exception e) {
            log.warn("Failed to cache response for key={}: {}", pendingCacheKey, e.getMessage());
            count("error");
        } finally {
            pendingCacheKey = null;
            pendingQuestion = null;
        }

        return Mono.just(event);
    }

    // ==================== Helpers ====================

    /**
     * Builds a {@link DimensionState} from the explicit dimensions in a {@link QuestionAnalysis}.
     *
     * <p>For cache key generation, we only use explicitly mentioned dimensions (no inheritance).
     */
    private static DimensionState buildFromExplicit(QuestionAnalysis analysis) {
        DimensionState state = new DimensionState();
        if (analysis.getExplicitDimensions() == null) return state;

        QuestionAnalysis.ExplicitDimensions explicit = analysis.getExplicitDimensions();
        if (explicit.getTimeDimension() != null) {
            state.setTimeDimension(explicit.getTimeDimension());
        }
        if (explicit.getDepartments() != null && !explicit.getDepartments().isEmpty()) {
            state.setDepartments(explicit.getDepartments());
        }
        if (explicit.getPeerDimension() != null) {
            state.setPeerDimension(explicit.getPeerDimension());
        }
        if (explicit.getPersons() != null && !explicit.getPersons().isEmpty()) {
            state.setPersons(explicit.getPersons());
        }
        return state;
    }

    /** Scope the dimension cache key with userId / sessionId so tenants don't see each other. */
    private String scopedKey(String dimKey) {
        return tenantBucket() + "|" + dimKey;
    }

    private String tenantBucket() {
        if (runtimeContext == null) {
            return "_global";
        }
        String uid = runtimeContext.getUserId();
        if (uid != null && !uid.isBlank()) {
            return "u:" + uid;
        }
        String sid = runtimeContext.getSessionId();
        if (sid != null && !sid.isBlank()) {
            return "s:" + sid;
        }
        return "_anon";
    }

    private void count(String outcome) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder(METRIC_NAME).tag("outcome", outcome).register(meterRegistry).increment();
    }

    // ==================== CacheHitException ====================

    /**
     * Thrown from {@link #handlePreCall(PreCallEvent)} when a cached response is found. The {@code
     * AgentRunner} catches this via {@code .onErrorResume(CacheHitException.class, ...)} and
     * returns the cached response as a streaming event.
     *
     * <p>Hook framework doesn't yet expose a {@code PreCallEvent.shortCircuit(result)} API — when
     * it does, replace this with a proper return value (see docs/enhancement-proposal.md P2-2).
     * The exception path is intentional control-flow, not an error.
     */
    public static class CacheHitException extends RuntimeException {

        private final String cachedResponse;

        public CacheHitException(String cachedResponse) {
            super("Cache hit - short-circuiting agent execution");
            this.cachedResponse = cachedResponse;
        }

        public String getCachedResponse() {
            return cachedResponse;
        }
    }
}
