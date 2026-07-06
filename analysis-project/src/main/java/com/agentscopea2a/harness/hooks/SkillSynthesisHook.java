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
package com.agentscopea2a.harness.hooks;

import com.agentscopea2a.harness.cache.ResponseCacheService;
import com.agentscopea2a.harness.skills.FingerprintCalculator;
import com.agentscopea2a.harness.skills.MetricClassificationService;
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
 * <p>Fingerprint format: {@code _global|intent|metricTag} (metric-level, see
 * {@link FingerprintCalculator#computeMetric}). All users accumulate on the same
 * fingerprint row, regardless of dimensional qualifiers (time range, department).
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
    private final MetricClassificationService metricClassifier;
    private final FingerprintCalculator fingerprintCalculator;
    private final RuntimeContext runtimeContext;

    public SkillSynthesisHook(
            SkillSynthesisRunner runner,
            MetricClassificationService metricClassifier,
            FingerprintCalculator fingerprintCalculator,
            RuntimeContext runtimeContext) {
        this.runner = runner;
        this.metricClassifier = metricClassifier;
        this.fingerprintCalculator = fingerprintCalculator;
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
        if (event instanceof PostCallEvent) {
            return (Mono<T>) Mono.just(event);
        }
        return Mono.just(event);
    }

    private Mono<PreCallEvent> handlePreCall(PreCallEvent event) {
        try {
            String question = ResponseCacheService.extractUserQuestion(event.getInputMessages());
            if (question.isEmpty()) return Mono.just(event);

            // Metric-level fingerprint: _global|intent|metricTag — groups questions by metric
            // across all users, not by dimension or per-user. See fingerprint-metric-redesign.md.
            String intent = ResponseCacheService.classifyIntent(question);
            String fingerprint = fingerprintCalculator.computeMetric(intent, question);

            // userId for bumpAndMaybeSynthesize is per-user (for DB tracking), but the
            // fingerprint itself uses _global scope so all users accumulate on the same row.
            String userId = FingerprintCalculator.tenantBucket(runtimeContext);
            runner.bumpAndMaybeSynthesize(fingerprint, userId, question, /* traceId */ null)
                    .ifPresent(
                            c ->
                                    log.info(
                                            "[MISS path] candidate {} hit={} status={} thr={}",
                                            fingerprint,
                                            c.hitCount(),
                                            c.status(),
                                            runner.threshold()));

            // Async metric classification (lightweight LLM) → writes skill_candidate.metric_tag.
            // The ruleBasedTag used for fingerprint is fast and synchronous; this LLM call
            // enriches the tag for distillation prompts and is best-effort.
            if (metricClassifier != null && metricClassifier.enabled()) {
                Mono.fromRunnable(() -> metricClassifier.classifyAndUpdateAsync(question, fingerprint))
                        .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                        .subscribe(
                                v -> {},
                                ex -> log.debug("Metric classification async failed for {}: {}", fingerprint, ex.getMessage()));
            }
        } catch (Exception ex) {
            log.debug("PreCall fingerprint extraction failed (skipping synthesis): {}", ex.getMessage());
        }
        return Mono.just(event);
    }
}