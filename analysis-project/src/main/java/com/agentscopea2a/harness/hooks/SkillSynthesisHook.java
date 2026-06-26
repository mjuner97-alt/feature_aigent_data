/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.agentscopea2a.harness.hooks;

import com.agentscopea2a.agent.dimension.DimensionState;
import com.agentscopea2a.agent.dimension.DimensionStateManager;
import com.agentscopea2a.agent.dimension.QuestionAnalysis;
import com.agentscopea2a.harness.cache.ResponseCacheService;
import com.agentscopea2a.harness.skills.SkillCandidate;
import com.agentscopea2a.harness.skills.SkillSynthesisRunner;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PreCallEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * PR2 / cache-MISS path of automatic skill synthesis. PreCall bumps the candidate row and
 * dispatches async distillation when threshold is crossed (the cache-HIT path is handled
 * symmetrically in {@link ResponseCacheHook} so HIT and MISS share the counter).
 *
 * <p>Bumping at PreCall (not PostCall) lets us behave identically whether or not the framework
 * reaches PostCall — important because some short-circuit paths (CacheHitException, tool
 * timeouts) skip PostCall entirely. Distillation itself is async on {@code boundedElastic()},
 * so the request never waits for an LLM call.
 *
 * <p>Both paths funnel through {@link SkillSynthesisRunner}, whose {@code markSynthesized} CAS
 * guarantees at-most-once distillation per fingerprint across hooks and JVMs.
 */
public class SkillSynthesisHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(SkillSynthesisHook.class);

    private final SkillSynthesisRunner runner;
    private final DimensionStateManager dimManager;
    private final RuntimeContext runtimeContext;

    public SkillSynthesisHook(
            SkillSynthesisRunner runner,
            DimensionStateManager dimManager,
            RuntimeContext runtimeContext) {
        this.runner = runner;
        this.dimManager = dimManager;
        this.runtimeContext = runtimeContext;
    }

    @Override
    public int priority() {
        // Run after ResponseCacheHook(0); HIT path never reaches us, MISS path always does.
        return 50;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (!runner.enabled()) return Mono.just(event);
        if (event instanceof PreCallEvent e) {
            return (Mono<T>) handlePreCall(e);
        }
        if (event instanceof PostCallEvent e) {
            return (Mono<T>) Mono.just(e);
        }
        return Mono.just(event);
    }

    private Mono<PreCallEvent> handlePreCall(PreCallEvent event) {
        try {
            String question = ResponseCacheService.extractUserQuestion(event.getInputMessages());
            if (question.isEmpty()) return Mono.just(event);
            QuestionAnalysis analysis = dimManager.analyzeQuestionRuleBased(question);
            DimensionState state = buildFromExplicit(analysis);
            if (state == null || !state.hasDimensions()) return Mono.just(event);
            String intent = ResponseCacheService.classifyIntent(question);
            String dimKey = state.toCacheKey();
            if (dimKey.isEmpty()) return Mono.just(event);
            String userId = userBucket();
            String fingerprint = userId + "|" + intent + "|" + dimKey;
            runner.bumpAndMaybeSynthesize(fingerprint, userId, question, /* traceId */ null)
                    .ifPresent(
                            c ->
                                    log.info(
                                            "[MISS path] candidate {} hit={} status={} thr={}",
                                            fingerprint,
                                            c.hitCount(),
                                            c.status(),
                                            runner.threshold()));
        } catch (Exception ex) {
            log.debug("PreCall fingerprint extraction failed (skipping synthesis): {}", ex.getMessage());
        }
        return Mono.just(event);
    }

    /** Mirror of {@code ResponseCacheHook.buildFromExplicit} so the fingerprint shape matches. */
    private static DimensionState buildFromExplicit(QuestionAnalysis analysis) {
        DimensionState state = new DimensionState();
        if (analysis == null || analysis.getExplicitDimensions() == null) return state;
        QuestionAnalysis.ExplicitDimensions e = analysis.getExplicitDimensions();
        if (e.getTimeDimension() != null) state.setTimeDimension(e.getTimeDimension());
        if (e.getDepartments() != null && !e.getDepartments().isEmpty()) {
            state.setDepartments(e.getDepartments());
        }
        if (e.getPeerDimension() != null) state.setPeerDimension(e.getPeerDimension());
        if (e.getPersons() != null && !e.getPersons().isEmpty()) state.setPersons(e.getPersons());
        return state;
    }

    private String userBucket() {
        if (runtimeContext == null) return "_global";
        String uid = runtimeContext.getUserId();
        if (uid != null && !uid.isBlank()) return "u:" + uid;
        String sid = runtimeContext.getSessionId();
        if (sid != null && !sid.isBlank()) return "s:" + sid;
        return "_anon";
    }
}
