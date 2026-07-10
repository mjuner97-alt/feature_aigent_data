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
package com.agentscopea2a.agent.memory.digestion;

import com.agentscopea2a.harness.skills.EmbeddingClient;
import com.agentscopea2a.harness.skills.FingerprintCalculator;
import com.agentscopea2a.harness.skills.MetricClassificationService;
import com.agentscopea2a.harness.skills.SkillDistiller;
import com.agentscopea2a.harness.skills.SkillDistiller.DistilledSkill;
import com.agentscopea2a.harness.skills.SkillEvolutionRunner;
import com.agentscopea2a.harness.skills.SkillEntry;
import com.agentscopea2a.harness.skills.SkillIndexRepository;
import com.agentscopea2a.harness.skills.SkillSynthesisRunner;
import com.agentscopea2a.harness.skills.SkillVectorIndex;
import com.agentscopea2a.harness.tools.SkillSaveTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 3 of the nightly digestion pipeline: evaluates pending trace groups and dispatches
 * skill evolution or distillation.
 *
 * <p>Two dispatch paths:
 * <ul>
 *   <li><b>Evolve</b> — when a trace matches an existing active skill, rewrites the SKILL.md
 *       via {@link SkillDistiller#evolve}. Uses a local + MySQL cross-JVM lock to ensure
 *       at-most-once evolution per skill name. Resets success/failure counters after a
 *       successful evolve so the new version gets a clean evaluation window.</li>
 *   <li><b>Distill</b> — when a trace has no matching skill, creates a new one. When
 *       {@code viaSubagent=true} (default), the distillation runs through a
 *       {@code generate_skill} subagent via {@link SkillSynthesisRunner#distillForDigestion},
 *       avoiding thinking-tag pollution. When {@code false}, falls back to direct LLM via
 *       {@link SkillDistiller#distillWithContext}.</li>
 * </ul>
 *
 * <p>Dual fingerprint strategy for matching traces to skills:
 * <ol>
 *   <li>First try {@code runtime_fingerprint} (metric-based, e.g. {@code _global|query|defect_density})
 *       against {@code skill_index.fingerprint} — this is the L1 primary key.</li>
 *   <li>Fall back to {@code tool_sequence_fingerprint} (e.g. {@code agent_spawn|tool_index|router_tool})
 *       against the dedicated {@code skill_index.tool_sequence_fingerprint} column.</li>
 * </ol>
 *
 * <p>Bean condition: {@code harness.a2a.memory.digestion.enabled=true}
 */
@Component
@ConditionalOnProperty(
        prefix = "harness.a2a.memory.digestion",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false)
public class SkillFlowEvolver {

    private static final Logger log = LoggerFactory.getLogger(SkillFlowEvolver.class);

    private final SkillIndexRepository indexRepo;
    private final SkillDistiller distiller;
    private final SkillVectorIndex vectorIndex;
    private final EmbeddingClient embeddingClient;
    private final DataSource dataSource;
    private final Path skillsDir;
    private final SkillSynthesisRunner synthesisRunner;
    private final FingerprintCalculator fingerprintCalc;
    private final MetricClassificationService metricClassifier;
    private final boolean viaSubagent;
    private final int minTraces;

    /** Local CAS for in-process evolve-lock (complements MySQL-level cross-JVM lock). */
    private final Map<String, Boolean> evolving = new ConcurrentHashMap<>();

    /**
     * Record representing a trace summary loaded from {@code user_trace_summary}.
     * Used by both Phase 3 (evolve/distill) and Phase 4 (consolidate).
     *
     * @param fingerprint        tool-sequence fingerprint (e.g. "agent_spawn|tool_index|router_tool")
     * @param runtimeFingerprint  metric fingerprint for L1 lookup (e.g. "_global|query|defect_density"),
     *                           may be null for legacy rows
     * @param toolSequence        comma-separated tool sequence
     * @param successCount        number of successful sessions
     * @param failureCount        number of failed sessions
     * @param sampleQuery         last assistant snippet from a failed session
     * @param userQuery           first user question from the session
     * @param toolCallDetails     JSON-serialized tool call details
     */
    public record TraceSummary(
            String fingerprint,
            String runtimeFingerprint,
            String toolSequence,
            int successCount,
            int failureCount,
            String sampleQuery,
            String userQuery,
            String toolCallDetails) {}

    public SkillFlowEvolver(
            SkillIndexRepository indexRepo,
            SkillDistiller distiller,
            ObjectProvider<SkillVectorIndex> vectorIndexProvider,
            ObjectProvider<EmbeddingClient> embeddingClientProvider,
            DataSource dataSource,
            @Value("${harness.a2a.workspace.path}") String workspaceRoot,
            SkillSynthesisRunner synthesisRunner,
            FingerprintCalculator fingerprintCalc,
            MetricClassificationService metricClassifier,
            @Value("${harness.a2a.memory.digestion.via-subagent:true}") boolean viaSubagent,
            @Value("${harness.a2a.memory.digestion.min-traces:5}") int minTraces) {
        this.indexRepo = indexRepo;
        this.distiller = distiller;
        this.vectorIndex = vectorIndexProvider.getIfAvailable();
        this.embeddingClient = embeddingClientProvider.getIfAvailable();
        this.dataSource = dataSource;
        this.skillsDir = Path.of(workspaceRoot).resolve("skills-auto");
        this.synthesisRunner = synthesisRunner;
        this.fingerprintCalc = fingerprintCalc;
        this.metricClassifier = metricClassifier;
        this.viaSubagent = viaSubagent;
        this.minTraces = minTraces;
    }

    /**
     * Evaluate all pending trace groups and dispatch evolve or distill as appropriate.
     *
     * @param traces pending trace summaries loaded from user_trace_summary
     * @return number of skills that were actually evolved or distilled
     */
    public int evolve(List<TraceSummary> traces) {
        if (traces == null || traces.isEmpty()) return 0;
        int count = 0;
        for (TraceSummary t : traces) {
            try {
                if (!evaluate(t)) continue;
                // If a user skill already covers this trace, skip entirely - night-time
                // digestion must not touch user skills (neither evolve nor distill a duplicate).
                if (matchesUserSkill(t)) {
                    continue;
                }
                String skillName = findSkillForTrace(t);
                if (skillName != null) {
                    dispatchEvolve(skillName, t);
                } else {
                    dispatchDistill(t);
                }
                count++;
            } catch (Exception e) {
                log.warn("Evolve dispatch failed for fingerprint={}: {}", t.fingerprint(), e.getMessage());
            }
        }
        return count;
    }

    /**
     * Check whether a user-generated skill already matches this trace (by either fingerprint).
     * If so, night-time digestion skips entirely - the user has already authored a skill for
     * this scenario, so evolving or distilling an auto counterpart would be redundant (and
     * evolving would risk overwriting the user's intent).
     */
    private boolean matchesUserSkill(TraceSummary t) {
        if (t.runtimeFingerprint() != null && !t.runtimeFingerprint().isBlank()) {
            Optional<String> userName = indexRepo.findNameByFingerprint(
                    t.runtimeFingerprint(), SkillEntry.SOURCE_USER_GENERATED);
            if (userName.isPresent()) {
                log.info("trace matched user skill '{}'; skipping digestion", userName.get());
                return true;
            }
        }
        if (t.fingerprint() != null && !t.fingerprint().isBlank()) {
            Optional<String> userName = indexRepo.findNameByToolSequenceFingerprint(
                    t.fingerprint(), SkillEntry.SOURCE_USER_GENERATED);
            if (userName.isPresent()) {
                log.info("trace matched user skill '{}'; skipping digestion", userName.get());
                return true;
            }
        }
        return false;
    }

    /**
     * Evaluate whether a trace group should be evolved/distilled.
     * A trace is actionable when it has enough total uses and a failure rate above the threshold.
     */
    private boolean evaluate(TraceSummary t) {
        int total = t.successCount() + t.failureCount();
        double failRate = total == 0 ? 0.0 : (double) t.failureCount() / total;
        return total >= minTraces && failRate > 0.3;
    }

    /**
     * Dual fingerprint lookup: first try runtime_fingerprint (L1 primary key),
     * then fall back to tool_sequence_fingerprint (dedicated column).
     */
    private String findSkillForTrace(TraceSummary t) {
        // Priority 1: runtime_fingerprint → skill_index.fingerprint (L1 lookup)
        if (t.runtimeFingerprint() != null && !t.runtimeFingerprint().isBlank()) {
            Optional<String> name = indexRepo.findNameByFingerprint(
                    t.runtimeFingerprint(), SkillEntry.SOURCE_AUTO_SYNTHESIZED);
            if (name.isPresent()) {
                log.debug("Matched trace to auto skill '{}' via runtime_fingerprint '{}'",
                        name.get(), t.runtimeFingerprint());
                return name.get();
            }
        }
        // Priority 2: tool_sequence_fingerprint → skill_index.tool_sequence_fingerprint
        if (t.fingerprint() != null && !t.fingerprint().isBlank()) {
            Optional<String> name = indexRepo.findNameByToolSequenceFingerprint(
                    t.fingerprint(), SkillEntry.SOURCE_AUTO_SYNTHESIZED);
            if (name.isPresent()) {
                log.debug("Matched trace to auto skill '{}' via tool_sequence_fingerprint '{}'",
                        name.get(), t.fingerprint());
                return name.get();
            }
        }
        return null;
    }

    /**
     * Distill a new skill from the trace. When {@code viaSubagent=true}, delegates to
     * {@link SkillSynthesisRunner#distillForDigestion} which runs a generate_skill subagent.
     * Otherwise, falls back to direct LLM distillation.
     */
    private void dispatchDistill(TraceSummary t) {
        String userQuery = t.userQuery() != null ? t.userQuery() : t.sampleQuery();
        String toolCallContext = SkillSynthesisRunner.buildEnrichedContext(userQuery, t.toolCallDetails());

        // Compute metricTag from userQuery (rule-based, no LLM needed)
        String metricTag = null;
        if (userQuery != null && !userQuery.isBlank()) {
            metricTag = metricClassifier.ruleBasedTag(userQuery);
        }

        // Compute runtimeFingerprint if not already present
        String runtimeFp = t.runtimeFingerprint();
        if ((runtimeFp == null || runtimeFp.isBlank()) && userQuery != null && !userQuery.isBlank()) {
            runtimeFp = fingerprintCalc.computeMetric("query", userQuery);
        }

        if (viaSubagent) {
            // New path: subagent-based distillation via SkillSynthesisRunner
            String result = synthesisRunner.distillForDigestion(
                    t.fingerprint(), runtimeFp, userQuery, toolCallContext, metricTag);
            if (result != null) {
                log.info("Distilled new skill '{}' via subagent from toolSeqFp={}", result, t.fingerprint());
            } else {
                log.warn("Subagent distillation returned null for toolSeqFp={}", t.fingerprint());
            }
        } else {
            // Legacy path: direct LLM distillation
            DistilledSkill distilled;
            if (toolCallContext != null && !toolCallContext.isBlank()) {
                distilled = distiller.distillWithContext(
                        userQuery, t.fingerprint(), toolCallContext, metricTag).block();
            } else {
                distilled = distiller.distill(userQuery, t.fingerprint(), metricTag).block();
            }
            if (distilled != null) {
                saveDistilled(distilled, t.fingerprint(), runtimeFp, metricTag);
                log.info("Distilled new skill '{}' via direct LLM from toolSeqFp={}",
                        distilled.name(), t.fingerprint());
            } else {
                log.warn("Direct LLM distillation returned null for toolSeqFp={}", t.fingerprint());
            }
        }
    }

    /**
     * Evolve an existing skill by rewriting its SKILL.md. Uses local + MySQL cross-JVM lock
     * to ensure at-most-once evolution per skill name. Resets success/failure counters after
     * a successful evolve so the new version gets a clean evaluation window.
     */
    private void dispatchEvolve(String skillName, TraceSummary t) {
        // Local CAS — prevent concurrent evolution in the same JVM
        if (!markEvolving(skillName)) {
            log.info("Skill '{}' already evolving locally; skipping", skillName);
            return;
        }
        // Cross-JVM CAS — MySQL-level lock
        if (!indexRepo.tryAcquireEvolveLock(skillName)) {
            markEvolved(skillName);
            log.info("Skill '{}' locked by another JVM; skipping", skillName);
            return;
        }
        try {
            String oldBody = readSkillBody(skillName);
            if (oldBody == null) {
                log.warn("Cannot read SKILL.md for '{}'; aborting evolve", skillName);
                return;
            }
            String failedContext = buildFailedContext(t);
            String userQuery = t.userQuery() != null ? t.userQuery() : t.sampleQuery();
            DistilledSkill evolved = distiller.evolve(skillName, oldBody, userQuery, failedContext).block();
            if (evolved != null) {
                saveEvolved(skillName, evolved);
                log.info("Skill '{}' evolved to next version (counters reset)", skillName);
            } else {
                log.warn("Distiller returned null for evolve('{}'); skipping save", skillName);
            }
        } catch (Exception e) {
            log.warn("Evolve failed for '{}': {}", skillName, e.getMessage());
        } finally {
            indexRepo.releaseEvolveLock(skillName);
            markEvolved(skillName);
        }
    }

    /**
     * Save an evolved skill: upsert SKILL.md, refresh embedding, reset counters.
     * Modeled after {@link SkillEvolutionRunner#doEvolve}.
     */
    private void saveEvolved(String name, DistilledSkill evolved) {
        try {
            // Pass null embeddingClient — we refresh the embedding synchronously below with richer text.
            // SkillSaveTool's async embed uses name+description which is less discriminative; the
            // synchronous embed here uses description + sample_questions for better bge-zh vectors.
            SkillSaveTool saver = new SkillSaveTool(skillsDir, indexRepo, vectorIndex, null, SkillEntry.SOURCE_AUTO_SYNTHESIZED);
            saver.saveSkill(evolved.name(), evolved.description(), evolved.body());

            if (vectorIndex != null && embeddingClient != null) {
                String embedText = SkillSynthesisRunner.buildEmbedText(evolved);
                float[] vec = embeddingClient.embed(embedText);
                if (vec != null) {
                    // Refresh embedding only; keep the PR2-stamped fingerprint intact so L1
                    // keeps routing the same fingerprint to this (now evolved) skill.
                    vectorIndex.upsertEmbeddingOnly(name, vec);
                }
            }
            // Give the new version a clean evaluation window
            indexRepo.resetCounts(name);
        } catch (Exception ex) {
            log.warn("Evolve save failed for '{}': {}", name, ex.getMessage());
        }
    }

    /**
     * Save a newly distilled skill: dedup check → disk write → embedding → fingerprint stamp.
     * Does NOT use the skill_candidate CAS because night-time digestion reads from
     * user_trace_summary (not skill_candidate), so there are no candidate rows to claim.
     */
    private void saveDistilled(DistilledSkill distilled, String toolSeqFp,
                                String runtimeFp, String metricTag) {
        // Dedup: if a skill with this name already exists, skip
        Optional<SkillEntry> existing = indexRepo.findByName(distilled.name());
        if (existing.isPresent()) {
            log.info("Skill '{}' already exists; skipping distill", distilled.name());
            return;
        }

        // metricTag injection
        if (metricTag != null && !metricTag.isBlank()) {
            distilled = SkillSynthesisRunner.withMetricTag(distilled, metricTag);
        }

        // Use SkillSynthesisRunner's public save method (CAS + disk + embedding)
        synthesisRunner.saveDistilledSkill(distilled, runtimeFp, metricTag);

        // Write tool_sequence_fingerprint to the dedicated column (not the primary fingerprint column)
        if (toolSeqFp != null && !toolSeqFp.isBlank()) {
            indexRepo.upsertToolSequenceFingerprint(distilled.name(), toolSeqFp);
        }
    }

    // ==================== Local CAS helpers ====================

    private boolean markEvolving(String name) {
        return evolving.putIfAbsent(name, Boolean.TRUE) == null;
    }

    private void markEvolved(String name) {
        evolving.remove(name);
    }

    // ==================== Context builders ====================

    private String readSkillBody(String name) {
        Path p = skillsDir.resolve(name).resolve("SKILL.md");
        if (!Files.isRegularFile(p)) return null;
        try {
            String full = Files.readString(p, StandardCharsets.UTF_8);
            return stripFrontmatter(full);
        } catch (Exception ex) {
            log.debug("readSkillBody({}) failed: {}", name, ex.getMessage());
            return null;
        }
    }

    private static String stripFrontmatter(String content) {
        if (content == null) return "";
        String trimmed = content.stripLeading();
        if (!trimmed.startsWith("---")) return content;
        int second = trimmed.indexOf("\n---", 3);
        if (second < 0) return content;
        int after = trimmed.indexOf('\n', second + 1);
        return after < 0 ? "" : trimmed.substring(after + 1);
    }

    /**
     * Build a failure context string from the trace summary for the evolve prompt.
     * Uses tool_call_details when available (structured), falls back to sampleQuery.
     */
    private String buildFailedContext(TraceSummary t) {
        if (t.toolCallDetails() != null && !t.toolCallDetails().isBlank()) {
            String enriched = SkillSynthesisRunner.buildEnrichedContext(
                    t.userQuery() != null ? t.userQuery() : t.sampleQuery(),
                    t.toolCallDetails());
            return enriched.length() > 500 ? enriched.substring(0, 500) + "..." : enriched;
        }
        // Fallback: use the sampleQuery
        if (t.sampleQuery() != null && !t.sampleQuery().isBlank()) {
            return "Last assistant response: " + t.sampleQuery();
        }
        return "(no failure context available)";
    }
}