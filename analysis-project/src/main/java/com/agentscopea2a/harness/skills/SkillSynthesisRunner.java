/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.agentscopea2a.harness.skills;

import com.agentscopea2a.harness.tools.SkillSaveTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Path;

/**
 * Shared "count → maybe distill" pipeline for both the Cache MISS path
 * ({@code SkillSynthesisHook}) and the Cache HIT path ({@code ResponseCacheHook}).
 *
 * <p>Centralising the distillation logic prevents drift between the two call sites — both
 * end up bumping {@code skill_candidate.hit_count} and, when threshold is crossed, dispatching
 * an async {@link SkillDistiller#distill}. {@link SkillCandidateRepository#markSynthesized} is
 * the atomic CAS that prevents the two paths (or two JVMs) from racing.
 *
 * <p>Workspace path is captured at construction; everything else is shared injected beans.
 * Vector index + embedding client are optional (PR3 may be off).
 */
@Component
public class SkillSynthesisRunner {

    private static final Logger log = LoggerFactory.getLogger(SkillSynthesisRunner.class);

    private final SkillCandidateRepository candidateRepo;
    private final SkillIndexRepository indexRepo;
    private final SkillDistiller distiller;
    private final SkillVectorIndex vectorIndex;
    private final EmbeddingClient embeddingClient;
    private final Path skillsDir;
    private final boolean enabled;
    private final int hitThreshold;

    public SkillSynthesisRunner(
            SkillCandidateRepository candidateRepo,
            SkillIndexRepository indexRepo,
            SkillDistiller distiller,
            ObjectProvider<SkillVectorIndex> vectorIndexProvider,
            ObjectProvider<EmbeddingClient> embeddingClientProvider,
            @Value("${harness.a2a.workspace.path}") String workspaceRoot,
            @Value("${harness.skills.auto-synth.enabled:false}") boolean enabled,
            @Value("${harness.skills.auto-synth.threshold:3}") int hitThreshold) {
        this.candidateRepo = candidateRepo;
        this.indexRepo = indexRepo;
        this.distiller = distiller;
        this.vectorIndex = vectorIndexProvider.getIfAvailable();
        this.embeddingClient = embeddingClientProvider.getIfAvailable();
        this.skillsDir = Path.of(workspaceRoot).resolve("skills-auto");
        this.enabled = enabled;
        this.hitThreshold = hitThreshold;
    }

    public boolean enabled() {
        return enabled;
    }

    public int threshold() {
        return hitThreshold;
    }

    /**
     * Bump the candidate row, then — if the bumped row is pending and over threshold — fire an
     * async distill+save on {@link Schedulers#boundedElastic()}. Safe to call from any hook;
     * the {@code markSynthesized} CAS guarantees at-most-once distillation across paths/JVMs.
     *
     * @return the candidate row after the bump (may be empty when MySQL is unreachable)
     */
    public java.util.Optional<SkillCandidate> bumpAndMaybeSynthesize(
            String fingerprint, String userId, String question, String traceId) {
        if (!enabled) return java.util.Optional.empty();
        java.util.Optional<SkillCandidate> bumped =
                candidateRepo.incrementHit(fingerprint, userId, question, traceId);
        bumped.ifPresent(c -> maybeDispatch(fingerprint, userId, question, c));
        return bumped;
    }

    /**
     * Variant for callers that have already bumped the counter (e.g. an earlier PreCall) and
     * just want the threshold check + async dispatch to run. Idempotent — the CAS in
     * {@link SkillCandidateRepository#markSynthesized} ensures double-calls are harmless.
     */
    public void maybeSynthesize(
            String fingerprint, String userId, String question, SkillCandidate candidate) {
        if (!enabled) return;
        if (candidate == null) return;
        maybeDispatch(fingerprint, userId, question, candidate);
    }

    private void maybeDispatch(
            String fingerprint, String userId, String question, SkillCandidate candidate) {
        if (!SkillCandidate.STATUS_PENDING.equals(candidate.status())) return;
        if (candidate.hitCount() < hitThreshold) return;
        reactor.core.publisher.Mono.fromRunnable(
                        () -> distillAndSave(fingerprint, userId, question, candidate))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        v -> {},
                        ex -> log.warn("Async synthesis crashed: {}", ex.getMessage()));
    }

    private void distillAndSave(
            String fingerprint, String userId, String question, SkillCandidate candidate) {
        log.info(
                "Skill synthesis triggered: fingerprint={} hit={} user={}",
                fingerprint,
                candidate.hitCount(),
                userId);
        SkillDistiller.DistilledSkill distilled = distiller.distill(question, fingerprint).block();
        if (distilled == null) {
            candidateRepo.markRejected(fingerprint, "distiller_returned_null");
            return;
        }
        // Atomic claim — only one path/JVM moves the row from pending → synthesized.
        if (!candidateRepo.markSynthesized(fingerprint, distilled.name())) {
            log.info("Candidate {} already claimed by another writer; skipping save", fingerprint);
            return;
        }
        try {
            // Pass null embeddingClient — the runner stamps the embedding+fingerprint
            // synchronously below with the richer (desc + sample_questions) text. Letting
            // SkillSaveTool's async path also embed would race and overwrite the canonical
            // fingerprint with null.
            SkillSaveTool saver = new SkillSaveTool(skillsDir, indexRepo, vectorIndex, null);
            saver.saveSkill(distilled.name(), distilled.description(), distilled.body());
            // Stamp the canonical fingerprint synchronously — embedding text includes
            // sample_questions so bge-zh has enough lexical surface to spread cosine across
            // L2's min-cosine threshold (PR3.7).
            if (vectorIndex != null && embeddingClient != null) {
                String embedText = buildEmbedText(distilled);
                float[] vec = embeddingClient.embed(embedText);
                if (vec != null) vectorIndex.upsertVector(distilled.name(), fingerprint, vec);
            }
            log.info("Auto-synthesised skill '{}' from fingerprint {}", distilled.name(), fingerprint);
        } catch (Exception ex) {
            log.warn("SkillSaveTool failed for '{}': {}", distilled.name(), ex.getMessage());
        }
    }

    /**
     * Embedding source text. Concatenates description with the distilled sample_questions so
     * short Chinese skills get enough lexical breadth for bge-zh-v1.5 to produce a
     * discriminative vector. Falls back to {@code name + description} when samples are
     * missing (legacy distill output / parse failure).
     */
    static String buildEmbedText(SkillDistiller.DistilledSkill d) {
        StringBuilder sb = new StringBuilder();
        sb.append(d.description() == null ? "" : d.description().trim());
        if (d.sampleQuestions() != null && !d.sampleQuestions().isEmpty()) {
            sb.append("\n\n");
            for (String q : d.sampleQuestions()) {
                if (q == null || q.isBlank()) continue;
                sb.append("- ").append(q.trim()).append('\n');
            }
        } else {
            // Old path — description alone tends to be too short for bge-zh; prefix the
            // skill name to add at least one more semantic token.
            sb.insert(0, (d.name() == null ? "" : d.name()) + " ");
        }
        return sb.toString().trim();
    }
}
