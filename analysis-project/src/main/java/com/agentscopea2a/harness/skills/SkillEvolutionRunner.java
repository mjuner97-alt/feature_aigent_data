/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.agentscopea2a.harness.skills;

import com.agentscopea2a.harness.tools.SkillSaveTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PR4 — failure-feedback closed loop.
 *
 * <p>Owns three responsibilities the {@code SkillEvolutionHook} delegates here:
 *
 * <ol>
 *   <li><b>Counter book-keeping</b> — {@link #recordSuccess(List)} /
 *       {@link #recordFailure(List)} bump {@code skill_index.success_count} /
 *       {@code failure_count}. After each failure, evaluates the per-skill threshold and
 *       dispatches an async evolve or marks the skill blacklist.
 *   <li><b>Async evolve dispatch</b> — runs {@link SkillDistiller#evolve} on
 *       {@code boundedElastic()}; the request hot path never waits on an LLM call. Each
 *       per-skill evolution is guarded by a CAS in {@link #markEvolving(String)} so two
 *       concurrent calls don't both spend an LLM round-trip.
 *   <li><b>Cross-turn rejection cache</b> — {@link #cachePendingJudgement} / {@link
 *       #consumePendingJudgement} let the hook record turn N's retrieved skills and look them
 *       up in turn N+1 when the user's next message is detected as a rejection.
 * </ol>
 *
 * <p>All paths are best-effort: SQL errors / IO errors / LLM errors are logged and swallowed.
 * PR4 must never break a user-visible request, and a missed evolution just means we'll retry
 * on the next failure.
 */
@Component
public class SkillEvolutionRunner {

    private static final Logger log = LoggerFactory.getLogger(SkillEvolutionRunner.class);

    /** How much of the last failure trace to inline into the evolve prompt. */
    private static final int FAILED_TRACE_CHARS = 500;

    /** Pending-judgement cache cap. LRU eviction so a single hot session can't starve others. */
    private static final int PENDING_CACHE_MAX = 1024;
    private static final ObjectMapper MAPPER =
            new ObjectMapper();

    /** MySQL DDL for pending judgement table. */
    private static final String PENDING_DDL =
            "CREATE TABLE IF NOT EXISTS skill_pending_judgement ("
                    + "  session_key VARCHAR(255) PRIMARY KEY,"
                    + "  skills_json TEXT NOT NULL,"
                    + "  exemplar_question VARCHAR(1024) DEFAULT NULL,"
                    + "  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

    private volatile boolean pendingTableEnsured = false;

    private final SkillIndexRepository indexRepo;
    private final SkillVectorIndex vectorIndex;
    private final SkillDistiller distiller;
    private final EmbeddingClient embeddingClient;
    private final DataSource dataSource;
    private final Path skillsDir;
    private final boolean enabled;
    private final double failRateEvolve;
    private final double failRateBlacklist;
    private final int minUsesEvolve;
    private final int minUsesBlacklist;
    private final Map<String, Boolean> evolving = new ConcurrentHashMap<>();
    private final Map<String, PendingJudgement> pendingL1 = Collections.synchronizedMap(new LinkedHashMap<String, PendingJudgement>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, PendingJudgement> eldest) {
            return size() > PENDING_CACHE_MAX;
        }
    });

    public SkillEvolutionRunner(
            SkillIndexRepository indexRepo,
            ObjectProvider<SkillVectorIndex> vectorIndexProvider,
            SkillDistiller distiller,
            ObjectProvider<EmbeddingClient> embeddingClientProvider,
            @Qualifier("mysqlDataSource") DataSource dataSource,
            @Value("${harness.a2a.workspace.path}") String workspaceRoot,
            @Value("${harness.skills.evolution.enabled:false}") boolean enabled,
            @Value("${harness.skills.evolution.fail-rate-evolve:0.3}") double failRateEvolve,
            @Value("${harness.skills.evolution.fail-rate-blacklist:0.6}") double failRateBlacklist,
            @Value("${harness.skills.evolution.min-uses-evolve:5}") int minUsesEvolve,
            @Value("${harness.skills.evolution.min-uses-blacklist:10}") int minUsesBlacklist) {
        this.indexRepo = indexRepo;
        this.vectorIndex = vectorIndexProvider.getIfAvailable();
        this.distiller = distiller;
        this.embeddingClient = embeddingClientProvider.getIfAvailable();
        this.dataSource = dataSource;
        this.skillsDir = Path.of(workspaceRoot).resolve("skills-auto");
        this.enabled = enabled;
        this.failRateEvolve = failRateEvolve;
        this.failRateBlacklist = failRateBlacklist;
        this.minUsesEvolve = minUsesEvolve;
        this.minUsesBlacklist = minUsesBlacklist;
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * Bump success counters. No threshold logic on the success path — only failures can trigger
     * evolve / blacklist.
     */
    public void recordSuccess(List<String> skillNames) {
        if (!enabled || skillNames == null) return;
        for (String name : skillNames) {
            if (name == null || name.isBlank()) continue;
            indexRepo.incrementSuccess(name);
        }
    }

    /**
     * Bump failure counters, then for each skill check the threshold gates. Evolve and blacklist
     * are mutually exclusive — blacklist's higher threshold wins.
     *
     * @param skillNames the skills that participated in the failing turn
     * @param exemplarQuestion the user question that triggered the failure (for evolve prompt)
     * @param failedTrace best-effort failure snippet (e.g. last python_exec stderr). May be null
     */
    public void recordFailure(
            List<String> skillNames, String exemplarQuestion, String failedTrace) {
        if (!enabled || skillNames == null) return;
        log.info("recordFailure called: skills={} exemplar='{}'", skillNames, exemplarQuestion);
        String trace = truncate(failedTrace, FAILED_TRACE_CHARS);
        for (String name : skillNames) {
            if (name == null || name.isBlank()) continue;
            indexRepo.incrementFailure(name);
            evaluateThresholds(name, exemplarQuestion, trace);
        }
    }

    private void evaluateThresholds(String name, String exemplarQuestion, String failedTrace) {
        Optional<SkillIndexRepository.SkillStats> snap = indexRepo.findStats(name);
        if (snap.isEmpty()) return;
        SkillIndexRepository.SkillStats s = snap.get();
        if (!"active".equals(s.status())) return;
        // User skills are counted but never evolved/blacklisted. The user authored the body,
        // so we must not rewrite it; blacklisting would silently hide user work. Counters
        // still accumulate so operators can observe user-skill effectiveness in skill_index.
        if (SkillEntry.SOURCE_USER_GENERATED.equals(s.source())) {
            log.debug(
                    "Skill '{}' is user-generated; skipping evolve/blacklist (counter only): success={} failure={}",
                    name,
                    s.successCount(),
                    s.failureCount());
            return;
        }
        double rate = s.failureRate();
        if (rate > failRateBlacklist && s.totalUses() >= minUsesBlacklist) {
            boolean done = indexRepo.markBlacklist(name);
            if (done) {
                log.warn(
                        "Skill '{}' BLACKLISTED: failure_rate={} success={} failure={}",
                        name,
                        rate,
                        s.successCount(),
                        s.failureCount());
            }
            return;
        }
        if (rate > failRateEvolve && s.totalUses() >= minUsesEvolve) {
            dispatchEvolve(name, exemplarQuestion, failedTrace);
        }
    }

    private void dispatchEvolve(String name, String exemplarQuestion, String failedTrace) {
        if (!markEvolving(name)) {
            log.info("Skill '{}' already evolving in another thread/JVM; skipping", name);
            return;
        }
        // Cross-JVM CAS: MySQL-level lock. If this fails, another JVM already holds the lock.
        if (!indexRepo.tryAcquireEvolveLock(name)) {
            markEvolved(name);
            log.info("Skill '{}' locked by another JVM; skipping", name);
            return;
        }
        Mono.fromRunnable(() -> doEvolve(name, exemplarQuestion, failedTrace))
                .subscribeOn(Schedulers.boundedElastic())
                .doFinally(sig -> {
                    indexRepo.releaseEvolveLock(name);
                    markEvolved(name);
                })
                .subscribe(
                        v -> {},
                        ex -> log.warn("Async evolve crashed for '{}': {}", name, ex.getMessage()));
    }

    private void doEvolve(String name, String exemplarQuestion, String failedTrace) {
        log.info("Skill evolution triggered for '{}'", name);
        String oldBody = readSkillBody(name);
        if (oldBody == null) {
            log.warn("Cannot read SKILL.md for '{}'; aborting evolve", name);
            return;
        }
        SkillDistiller.DistilledSkill evolved =
                distiller.evolve(name, oldBody, exemplarQuestion, failedTrace).block();
        if (evolved == null) {
            log.warn("Distiller returned null for evolve('{}'); skipping save", name);
            return;
        }
        try {
            // Preserve the canonical fingerprint by passing null embeddingClient and stamping
            // ourselves below — same pattern as SkillSynthesisRunner.
            SkillSaveTool saver = new SkillSaveTool(skillsDir, indexRepo, vectorIndex, null, SkillEntry.SOURCE_AUTO_SYNTHESIZED);
            saver.saveSkill(evolved.name(), evolved.description(), evolved.body());

            if (vectorIndex != null && embeddingClient != null) {
                String embedText = SkillSynthesisRunner.buildEmbedText(evolved);
                float[] vec = embeddingClient.embed(embedText);
                if (vec != null) {
                    // PR4 — refresh embedding only; keep the PR2-stamped fingerprint intact so
                    // L1 keeps routing the same fingerprint to this (now evolved) skill.
                    vectorIndex.upsertEmbeddingOnly(name, vec);
                    log.debug("Refreshed embedding for evolved skill {}", name);
                }
            }
            // Give the new version a clean evaluation window.
            indexRepo.resetCounts(name);
            log.info("Skill '{}' evolved to next version (counters reset)", name);
        } catch (Exception ex) {
            log.warn("evolve save failed for '{}': {}", name, ex.getMessage());
        }
    }

    /** True when this thread won the CAS to evolve this skill. */
    public boolean markEvolving(String name) {
        return evolving.putIfAbsent(name, Boolean.TRUE) == null;
    }

    public void markEvolved(String name) {
        evolving.remove(name);
    }

    // ==================== Pending judgement (cross-turn rejection lookback) ===================

    /**
     * Stash turn N's retrieved skills + the exemplar question, so turn N+1's PreCall can come
     * back and credit/debit them based on whether the user said "wrong" or moved on. Idempotent;
     * an existing entry under {@code key} is overwritten.
     *
     * <p>Writes to both L1 (in-memory LRU) and L2 (MySQL) for cross-JVM durability.
     */
    public void cachePendingJudgement(String key, List<String> skills, String exemplarQuestion) {
        if (key == null || key.isBlank() || skills == null || skills.isEmpty()) return;
        log.info("cachePendingJudgement: key={} skills={}", key, skills);
        PendingJudgement pj = new PendingJudgement(List.copyOf(skills), exemplarQuestion, System.currentTimeMillis());
        // L1: in-memory
        pendingL1.put(key, pj);
        // L2: MySQL (best-effort)
        storePendingToDb(key, pj);
    }

    /**
     * Pops the pending judgement for this session — checks L1 first, then L2 (MySQL) for
     * cross-JVM durability. Returns null when no prior turn cached one.
     */
    public PendingJudgement consumePendingJudgement(String key) {
        if (key == null) return null;
        // L1: in-memory fast path
        PendingJudgement pj = pendingL1.remove(key);
        if (pj != null) {
            log.info("consumePendingJudgement L1 hit: key={} skills={}", key, pj.skills());
            // Clean up L2 asynchronously
            removePendingFromDb(key);
            return pj;
        }
        // L2: MySQL fallback (cross-JVM handoff)
        pj = loadPendingFromDb(key);
        if (pj != null) {
            log.info("consumePendingJudgement L2 hit: key={} skills={}", key, pj.skills());
            removePendingFromDb(key);
        } else {
            log.info("consumePendingJudgement miss: key={}", key);
        }
        return pj;
    }

    // ==================== MySQL-backed pending judgement store ====================

    private void ensurePendingTable() {
        if (pendingTableEnsured) return;
        synchronized (this) {
            if (pendingTableEnsured) return;
            try (Connection c = dataSource.getConnection();
                    Statement s = c.createStatement()) {
                s.execute(PENDING_DDL);
                pendingTableEnsured = true;
                log.info("skill_pending_judgement table ensured");
            } catch (SQLException e) {
                log.warn("skill_pending_judgement DDL failed: {}", e.getMessage());
            }
        }
    }

    /**
     * Cleanup - relieves pressure on {@code skill_pending_judgement} table by deleting rows older
     * than 5 minutes. This prevents unbounded growth of the column in the unlikely case of the
     * pending judgement cache failing to clean up or if a session key collision occurs.
     */
    @Scheduled(fixedDelayString = "${harness.skills.evolution.pending-cleanup-interval:300000}")
    public void cleanupPendingJudgementTable() {
        if (!pendingTableEnsured) return;
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM skill_pending_judgement WHERE created_at < NOW() - INTERVAL 5 MINUTE")) {
            int deleted = ps.executeUpdate();
            if (deleted > 0) {
                log.debug("Cleaned up {} expired pending judgement rows", deleted);
            }
        } catch (SQLException e) {
            log.warn("Pending judgement table cleanup failed: {}", e.getMessage());
        }
    }

    private void storePendingToDb(String key, PendingJudgement pj) {
        try {
            ensurePendingTable();
            String skillsJson = MAPPER.writeValueAsString(pj.skills());
            String sql = "REPLACE INTO skill_pending_judgement (session_key, skills_json, exemplar_question)"
                    + " VALUES (?, ?, ?)";
            try (Connection c = dataSource.getConnection();
                    PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, key);
                ps.setString(2, skillsJson);
                ps.setString(3, pj.exemplarQuestion());
                ps.executeUpdate();
            }
        } catch (Exception ex) {
            log.debug("storePendingToDb failed: {}", ex.getMessage());
        }
    }

    private PendingJudgement loadPendingFromDb(String key) {
        try {
            ensurePendingTable();
            String sql = "SELECT skills_json, exemplar_question FROM skill_pending_judgement"
                    + " WHERE session_key = ?";
            try (Connection c = dataSource.getConnection();
                    PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String json = rs.getString("skills_json");
                        String q = rs.getString("exemplar_question");
                        List<String> skills = MAPPER.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
                        return new PendingJudgement(skills, q, System.currentTimeMillis());
                    }
                }
            }
        } catch (Exception ex) {
            log.debug("loadPendingFromDb failed: {}", ex.getMessage());
        }
        return null;
    }

    private void removePendingFromDb(String key) {
        try {
            ensurePendingTable();
            String sql = "DELETE FROM skill_pending_judgement WHERE session_key = ?";
            try (Connection c = dataSource.getConnection();
                    PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, key);
                ps.executeUpdate();
            }
        } catch (Exception ex) {
            log.debug("removePendingFromDb failed: {}", ex.getMessage());
        }
    }

    public record PendingJudgement(
            List<String> skills, String exemplarQuestion, long timestampMillis) {}

    // ==================== helpers ====================

    private String readSkillBody(String name) {
        Path p = skillsDir.resolve(name).resolve("SKILL.md");
        if (!Files.isRegularFile(p)) return null;
        try {
            String full = Files.readString(p, StandardCharsets.UTF_8);
            // Strip the system-managed YAML frontmatter — the evolve LLM shouldn't see the
            // version/timestamp metadata, only the body it needs to rewrite.
            return stripFrontmatter(full);
        } catch (IOException ex) {
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

    private static String truncate(String s, int max) {
        if (s == null) return null;
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...[truncated]";
    }

    /** Visible-for-test: peek the in-flight evolve set without mutating it. */
    Set<String> evolvingSnapshot() {
        return Set.copyOf(evolving.keySet());
    }
}
