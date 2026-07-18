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
package com.agentscopea2a.v2.hooks;

import com.agentscopea2a.v2.cache.ResponseCacheService;
import com.agentscopea2a.v2.skills.FingerprintCalculator;
import com.agentscopea2a.v2.skills.MetricClassificationService;
import com.agentscopea2a.v2.skills.SkillSynthesisRunner;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.hook.RuntimeContextAware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * v2 port of the v1 PR2 SkillSynthesisHook (cache-MISS path).
 *
 * <p>PreCall bumps the {@code skill_candidate} row and dispatches async distillation when
 * the threshold is crossed. The cache-HIT path was deprecated (ResponseCache HIT disabled);
 * only the MISS path is migrated.
 *
 * <p>Uses {@link RuntimeContextAware} for per-call context (replaces v1 constructor
 * injection of {@link RuntimeContext}). The JAR pushes the context via
 * {@link #setRuntimeContext(RuntimeContext)} before each call starts.
 *
 * <p>Bumping at PreCall (not PostCall) lets us behave identically whether or not the
 * framework reaches PostCall - some short-circuit paths (CacheHitException, tool timeouts)
 * skip PostCall entirely. Distillation itself is async on {@code boundedElastic()}, so the
 * request never waits for an LLM call.
 */
public class SkillSynthesisHook implements Hook, RuntimeContextAware {

    private static final Logger log = LoggerFactory.getLogger(SkillSynthesisHook.class);

    private final SkillSynthesisRunner runner;
    private final MetricClassificationService metricClassifier;
    private final FingerprintCalculator fingerprintCalculator;

    private volatile RuntimeContext currentCtx;

    public SkillSynthesisHook(
            SkillSynthesisRunner runner,
            MetricClassificationService metricClassifier,
            FingerprintCalculator fingerprintCalculator) {
        this.runner = runner;
        this.metricClassifier = metricClassifier;
        this.fingerprintCalculator = fingerprintCalculator;
    }

    @Override
    public void setRuntimeContext(RuntimeContext context) {
        this.currentCtx = context;
    }

    @Override
    public int priority() {
        // Run after ResponseCacheHook(0) and SkillRetrievalHook(-50); HIT path never reaches us,
        // MISS path always does. Distillation dispatch is async so this priority is just for
        // the bump + metric classification trigger.
        return 50;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (!runner.enabled()) return Mono.just(event);
        if (event instanceof PreCallEvent e) {
            try {
                handlePreCall(e);
            } catch (Exception ex) {
                log.debug("SkillSynthesisHook PreCall skipped: {}", ex.getMessage());
            }
        }
        return Mono.just(event);
    }

    private void handlePreCall(PreCallEvent event) {
        String question = ResponseCacheService.extractUserQuestion(event.getInputMessages());
        if (question == null || question.isEmpty()) return;

        // Metric-level fingerprint: _global|intent|metricTag - groups questions by metric
        // across all users, not by dimension or per-user.
        String intent = ResponseCacheService.classifyIntent(question);
        String fingerprint = fingerprintCalculator.computeMetric(intent, question);

        // userId for bumpAndMaybeSynthesize is per-user (for DB tracking), but the
        // fingerprint itself uses _global scope so all users accumulate on the same row.
        RuntimeContext ctx = this.currentCtx;
        String userId = FingerprintCalculator.tenantBucket(ctx);
        runner.bumpAndMaybeSynthesize(fingerprint, userId, question, /* traceId */ null)
                .ifPresent(c -> log.info(
                        "[MISS path] candidate {} hit={} status={} thr={}",
                        fingerprint, c.hitCount(), c.status(), runner.threshold()));

        // Async metric classification (lightweight LLM) -> writes skill_candidate.metric_tag.
        if (metricClassifier != null && metricClassifier.enabled()) {
            Mono.fromRunnable(() -> metricClassifier.classifyAndUpdateAsync(question, fingerprint))
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe(
                            v -> {},
                            ex -> log.debug(
                                    "Metric classification async failed for {}: {}",
                                    fingerprint,
                                    ex.getMessage()));
        }
    }
}
