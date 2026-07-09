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
package com.agentscopea2a.harness.skills;

import com.agentscopea2a.agent.dimension.DimensionState;
import com.agentscopea2a.agent.dimension.DimensionStateManager;
import com.agentscopea2a.agent.dimension.QuestionAnalysis;
import com.agentscopea2a.harness.cache.ResponseCacheService;
import io.agentscope.core.agent.RuntimeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Unified fingerprint calculator for skill synthesis, cache, retrieval, and evolution.
 *
 * <p>Provides two fingerprint schemes:
 *
 * <ul>
 *   <li><b>Metric-based</b> ({@link #computeMetric}): {@code _global|intent|metricTag} — the primary
 *       scheme used by PR2/PR3/PR4 for skill candidate accumulation, L1 lookup, and evolution.
 *       Uses {@link #SKILL_SCOPE} ({@code "_global"}) as tenant so all users asking about the same
 *       metric accumulate on the same fingerprint row. Metric tag comes from
 *       {@link MetricClassificationService#ruleBasedTag(String)}, falling back to {@code "general"}
 *       when no metric is detected. This scheme groups questions by <em>what metric</em> the user
 *       is asking about, independent of dimensional qualifiers like time range or department.
 *   <li><b>Dimension-based</b> ({@link #compute} + {@link #format}): {@code tenant|intent|dimKey} —
 *       the legacy scheme retained for ResponseCache cache-key generation. Dimension keys encode
 *       explicit time/department/peer/person qualifiers extracted from the question.
 * </ul>
 *
 * <p>All hooks should use {@link #computeMetric(String, String)} (with {@link #SKILL_SCOPE}) for
 * skill-related fingerprinting. The dimension-based scheme is kept only for cache-key generation.
 * The per-user overload {@link #computeMetric(String, String, String)} is retained for
 * ResponseCache key generation.
 */
@Component
public class FingerprintCalculator {

    private static final Logger log = LoggerFactory.getLogger(FingerprintCalculator.class);

    private final DimensionStateManager dimManager;
    private final MetricClassificationService metricClassifier;

    public FingerprintCalculator(DimensionStateManager dimManager,
                                  MetricClassificationService metricClassifier) {
        this.dimManager = dimManager;
        this.metricClassifier = metricClassifier;
    }

    // ==================== Metric-based fingerprint (primary) ====================

    /**
     * Global scope constant for skill fingerprinting. Skills are shared knowledge —
     * all users accumulate hits and retrieve skills on the same fingerprint, regardless
     * of who asked the question. This avoids duplicate skill distillation when multiple
     * users ask about the same metric.
     *
     * <p>Contrast with {@link #tenantBucket(RuntimeContext)} which produces per-user
     * buckets like {@code "u:alice"} — those are used for response cache keys, not
     * skill fingerprints.
     */
    public static final String SKILL_SCOPE = "_global";

    /**
     * Compute a metric-level fingerprint for skill synthesis, retrieval, and evolution.
     *
     * <p>Uses {@link #SKILL_SCOPE} ({@code "_global"}) as the tenant prefix so that all
     * users asking about the same metric accumulate on the same fingerprint row. This
     * prevents duplicate skill distillation and ensures L1 lookup works across users.
     *
     * <p>The metric tag is determined by {@link MetricClassificationService#ruleBasedTag(String)},
     * which performs fast keyword matching against the configured metric categories. When no metric
     * keyword matches, the tag falls back to {@code "general"}.
     *
     * <p>This method never returns null.
     *
     * @param intent   classified intent ("query", "analyze", "skill")
     * @param question the raw user question text
     * @return fingerprint string, never null, format: {@code _global|intent|metricTag}
     */
    public String computeMetric(String intent, String question) {
        Objects.requireNonNull(intent, "intent");
        return computeMetric(SKILL_SCOPE, intent, question);
    }

    /**
     * Compute a metric-level fingerprint with an explicit tenant.
     *
     * <p>This overload is kept for cases that need a specific tenant bucket (e.g. response
     * cache keys). For skill synthesis, retrieval, and evolution, prefer {@link #computeMetric(String, String)}
     * which uses the global scope.
     *
     * @param tenant   tenant bucket (e.g. "u:alice", "s:session1", "_anon", "_global")
     * @param intent   classified intent ("query", "analyze", "skill")
     * @param question the raw user question text
     * @return fingerprint string, never null
     */
    public String computeMetric(String tenant, String intent, String question) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(intent, "intent");
        String metricTag = metricClassifier.ruleBasedTag(question);
        if (metricTag == null || metricTag.isBlank()) {
            log.debug("[FINGERPRINT] ruleBasedTag returned null/blank for question (length={}): '{}'",
                    question != null ? question.length() : 0, question);
            metricTag = "general";
        }
        String fingerprint = tenant + "|" + intent + "|" + metricTag;
        log.debug("[FINGERPRINT] computeMetric: question='{}' → metricTag='{}' → fingerprint='{}'",
                question, metricTag, fingerprint);
        return fingerprint;
    }

    /**
     * Convenience overload: compute metric fingerprint with per-user tenant.
     *
     * <p><b>For skill fingerprinting, prefer {@link #computeMetric(String, String)} which uses
     * {@link #SKILL_SCOPE} ({@code "_global"}) so all users accumulate on the same row.</b>
     * This overload uses per-user tenant ({@code "u:alice"}) and is intended only for
     * ResponseCache key generation.
     *
     * @return fingerprint string with per-user tenant, never null
     * @deprecated For skill fingerprints, use {@link #computeMetric(String, String)} instead.
     */
    @Deprecated
    public String computeMetric(String question, RuntimeContext ctx) {
        String tenant = tenantBucket(ctx);
        String intent = ResponseCacheService.classifyIntent(question);
        if (intent == null || intent.isEmpty()) {
            intent = "query";
        }
        return computeMetric(tenant, intent, question);
    }

    // ==================== Dimension-based fingerprint (legacy, for cache keys) ====================

    /**
     * Compute the dimension-based fingerprint parts for a question.
     *
     * <p>Retained for ResponseCache cache-key generation. Do <em>not</em> use this for
     * skill-related fingerprinting — use {@link #computeMetric} instead.
     *
     * @param question the user question to analyze
     * @param ctx      runtime context for tenant scoping; may be null (falls back to {@code _global})
     * @return the fingerprint parts, never null
     * @deprecated Use {@link #computeMetric} for skill fingerprinting. This method is retained only
     *             for cache-key generation.
     */
    @Deprecated
    public Parts compute(String question, RuntimeContext ctx) {
        QuestionAnalysis analysis = dimManager.analyzeQuestionRuleBased(question);
        DimensionState state = buildFromExplicit(analysis);
        String intent = ResponseCacheService.classifyIntent(question);
        String dimKey = state == null ? "" : state.toCacheKey();
        String tenant = tenantBucket(ctx);
        return new Parts(tenant, intent, dimKey, state);
    }

    /**
     * Format dimension-based fingerprint parts into a string.
     *
     * @return the fingerprint string, or null if intent is empty
     * @deprecated Use {@link #computeMetric} for skill fingerprinting.
     */
    @Deprecated
    public static String format(Parts parts) {
        if (parts.intent() == null || parts.intent().isEmpty()) return null;
        if (parts.dimKey() == null || parts.dimKey().isEmpty()) {
            return parts.tenant() + "|" + parts.intent() + "|<no-dim>";
        }
        return parts.tenant() + "|" + parts.intent() + "|" + parts.dimKey;
    }

    /**
     * Convenience method: compute + format dimension-based fingerprint in one call.
     *
     * @return the fingerprint string, or null if the question has no intent
     * @deprecated Use {@link #computeMetric(String, String, String)} for skill fingerprinting.
     *             This method is retained only for backward-compatible L1 lookup during transition.
     */
    @Deprecated
    public String fingerprint(String question, RuntimeContext ctx) {
        Parts p = compute(question, ctx);
        return format(p);
    }

    // ==================== Shared utilities ====================

    /**
     * The unified suffix for dimension-less fingerprints. Retained for backward compatibility
     * with existing {@code skill_candidate} and {@code skill_index} rows that use this suffix.
     *
     * @deprecated New code should use {@link #computeMetric} which never needs a "no-dim" suffix.
     */
    @Deprecated
    public static final String NO_DIM_SUFFIX = "<no-dim>";

    /**
     * Determine the tenant bucket for fingerprint scoping. Shared by all hooks.
     *
     * @param ctx runtime context; may be null (falls back to {@code _global})
     * @return tenant bucket string
     */
    public static String tenantBucket(RuntimeContext ctx) {
        if (ctx == null) return "_global";
        String uid = ctx.getUserId();
        if (uid != null && !uid.isBlank()) return "u:" + uid;
        String sid = ctx.getSessionId();
        if (sid != null && !sid.isBlank()) return "s:" + sid;
        return "_anon";
    }

    /**
     * Build a {@link DimensionState} from explicit dimensions only (no inheritance). Mirrors the
     * logic that was duplicated across three hooks.
     */
    static DimensionState buildFromExplicit(QuestionAnalysis analysis) {
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

    /** Immutable fingerprint parts (dimension-based). */
    public record Parts(String tenant, String intent, String dimKey, DimensionState state) {}
}