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

import com.agentscopea2a.v2.cache.ResponseCacheService;
import com.agentscopea2a.v2.dimension.DimensionState;
import com.agentscopea2a.v2.dimension.DimensionStateManager;
import com.agentscopea2a.v2.dimension.QuestionAnalysis;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * v2 response cache middleware that replaces v1 {@code ResponseCacheHook}.
 *
 * <p>Uses {@link MiddlewareBase#onAgent} to check cache before agent execution and write
 * to cache after agent completion. On cache HIT, short-circuits the entire agent execution
 * by returning a synthetic response event.
 *
 * <p>Cache keys are generated from dimension state (via {@link DimensionStateManager}) and
 * intent classification, exactly matching the v1 logic.
 *
 * <p>Note: The v1 "bump and synthesize" path (PR4 evolution on cache HIT) is deferred to
 * Stage 6+ when FingerprintCalculator and SkillEvolutionRunner are migrated.
 */
public class ResponseCacheMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(ResponseCacheMiddleware.class);

    private final ResponseCacheService cacheService;
    private final DimensionStateManager dimManager;
    private final boolean enabled;
    private final Counter hitCounter;
    private final Counter missCounter;
    private final Counter writeCounter;

    public ResponseCacheMiddleware(ResponseCacheService cacheService,
                                    DimensionStateManager dimManager,
                                    MeterRegistry meterRegistry,
                                    boolean enabled) {
        this.cacheService = cacheService;
        this.dimManager = dimManager;
        this.enabled = enabled;
        this.hitCounter = meterRegistry.counter("response_cache.hit");
        this.missCounter = meterRegistry.counter("response_cache.miss");
        this.writeCounter = meterRegistry.counter("response_cache.write");
    }

    public ResponseCacheMiddleware(ResponseCacheService cacheService,
                                    DimensionStateManager dimManager,
                                    MeterRegistry meterRegistry) {
        this(cacheService, dimManager, meterRegistry, true);
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                     Function<AgentInput, Flux<AgentEvent>> next) {
        if (!enabled) {
            return next.apply(input);
        }

        String question = ResponseCacheService.extractUserQuestion(input.msgs());
        if (question == null || question.isEmpty()) {
            return next.apply(input);
        }

        // Analyze question for cache key generation
        QuestionAnalysis analysis = dimManager.analyzeQuestionRuleBased(question);
        DimensionState state = buildFromExplicit(analysis);
        String intent = ResponseCacheService.classifyIntent(question);

        Optional<String> dimKey = cacheService.generateCacheKey(intent, state, question);
        if (dimKey.isEmpty()) {
            return next.apply(input);
        }

        String cacheKey = scopedKey(dimKey.get(), ctx);

        // === Cache HIT: short-circuit agent execution ===
        Optional<String> cached = cacheService.get(cacheKey);
        if (cached.isPresent()) {
            log.info("[cache] HIT for key={}", cacheKey);
            hitCounter.increment();
            return buildCachedResponseEvents(cached.get(), agent.getName());
        }

        // === Cache MISS: execute agent and cache the result ===
        log.debug("[cache] MISS for key={}", cacheKey);
        missCounter.increment();

        StringBuilder responseAccumulator = new StringBuilder();
        return Flux.defer(() -> next.apply(input))
                .doOnNext(event -> {
                    // Accumulate text from agent events
                    String text = extractTextFromEvent(event);
                    if (text != null && !text.isEmpty()) {
                        responseAccumulator.append(text);
                    }
                })
                .doOnComplete(() -> {
                    String responseText = responseAccumulator.toString();
                    if (!responseText.isEmpty()) {
                        cacheService.put(cacheKey, question, responseText);
                        writeCounter.increment();
                        log.debug("[cache] Written for key={}, {} chars", cacheKey, responseText.length());
                    }
                });
    }

    // ==================== Helpers ====================

    private static DimensionState buildFromExplicit(QuestionAnalysis analysis) {
        DimensionState state = new DimensionState();
        QuestionAnalysis.ExplicitDimensions explicit = analysis.getExplicitDimensions();
        if (explicit != null) {
            explicit.build(); // assemble nested objects from flat fields
            if (explicit.getTimeDimension() != null) {
                state.setTimeDimension(explicit.getTimeDimension());
            }
            if (explicit.getDepartments() != null) {
                state.setDepartments(explicit.getDepartments());
            }
            if (explicit.getPeerDimension() != null) {
                state.setPeerDimension(explicit.getPeerDimension());
            }
            if (explicit.getPersons() != null) {
                state.setPersons(explicit.getPersons());
            }
        }
        return state;
    }

    private String scopedKey(String dimKey, RuntimeContext ctx) {
        String bucket = tenantBucket(ctx);
        return bucket + "|" + dimKey;
    }

    private String tenantBucket(RuntimeContext ctx) {
        String userId = ctx.getUserId();
        String sessionId = ctx.getSessionId();
        if (userId != null && !userId.isBlank()) {
            return "u:" + userId;
        }
        if (sessionId != null && !sessionId.isBlank()) {
            return "s:" + sessionId;
        }
        return "_anon";
    }

    /**
     * Builds a synthetic Flux of AgentEvents from a cached response text.
     * Produces: TextBlockStart → TextBlockDelta → TextBlockEnd → AgentResultEvent
     */
    private Flux<AgentEvent> buildCachedResponseEvents(String cachedText, String agentName) {
        String replyId = UUID.randomUUID().toString();
        String blockId = UUID.randomUUID().toString();

        // Build the result Msg using textContent (not .text() which doesn't exist on Builder)
        Msg resultMsg = Msg.builder()
                .role(MsgRole.ASSISTANT)
                .textContent(cachedText)
                .build();

        // Use 3-arg constructor: (id, source, result) — withSource() returns AgentEvent not AgentResultEvent
        AgentResultEvent resultEvent = new AgentResultEvent(replyId, agentName, resultMsg);

        // Stream: start delta → end delta → result
        return Flux.just(
                new TextBlockStartEvent(replyId, blockId),
                new TextBlockDeltaEvent(replyId, blockId, cachedText),
                new TextBlockEndEvent(replyId, blockId),
                resultEvent
        );
    }

    private String extractTextFromEvent(AgentEvent event) {
        if (event instanceof TextBlockDeltaEvent delta) {
            return delta.getDelta();
        }
        if (event instanceof AgentResultEvent agentResult) {
            Msg resultMsg = agentResult.getResult();
            if (resultMsg != null) {
                return resultMsg.getTextContent();
            }
        }
        return null;
    }
}