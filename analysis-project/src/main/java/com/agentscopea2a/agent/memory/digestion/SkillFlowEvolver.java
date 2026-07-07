/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.agentscopea2a.agent.memory.digestion;

import com.agentscopea2a.harness.skills.EmbeddingClient;
import com.agentscopea2a.harness.skills.SkillDistiller;
import com.agentscopea2a.harness.skills.SkillEntry;
import com.agentscopea2a.harness.skills.SkillIndexRepository;
import com.agentscopea2a.harness.skills.SkillSynthesisRunner;
import com.agentscopea2a.harness.skills.SkillVectorIndex;
import com.agentscopea2a.harness.tools.SkillSaveTool;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.scheduler.Schedulers;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 3 of the nightly digestion pipeline: evaluates tool-call traces from
 * user_trace_summary against the per-fingerprint failure rate, and dispatches skill
 * evolution or new-skill distillation when the threshold is crossed.
 */
public class SkillFlowEvolver {

    private static final Logger log = LoggerFactory.getLogger(SkillFlowEvolver.class);

    /** Minimum failure rate to trigger evolution. */
    private static final double FAIL_RATE_EVOLVE = 0.3;

    /**
     * Minimum total uses before evaluating a fingerprint.
     *
     * <p>Aligned with PR4's {@code harness.skills.evolution.min-uses-evolve=5} — the nightly
     * pipeline must not be more aggressive than the online path, otherwise a single transient
     * failure (network blip, one-off tool timeout) would trigger a noisy LLM distillation.
     * Document §4.14 also specifies {@code ≥ 3}; we use the stricter {@code 5} to match PR4
     * and avoid drift between the two thresholds (see review.md §1.5).
     */
    private static final int MIN_TRACES = 5;

    private final SkillIndexRepository indexRepo;
    private final SkillDistiller distiller;
    private final SkillVectorIndex vectorIndex;
    private final EmbeddingClient embeddingClient;
    private final DataSource dataSource;
    private final Path skillsDir;

    /**
     * Local in-process guard mirroring {@link
     * com.agentscopea2a.harness.skills.SkillEvolutionRunner#markEvolving} so the offline
     * pipeline doesn't race with itself on the same skill. Cross-JVM protection is provided by
     * {@link SkillIndexRepository#tryAcquireEvolveLock}.
     */
    private final java.util.Map<String, Boolean> evolving = new ConcurrentHashMap<>();

    public SkillFlowEvolver(SkillIndexRepository indexRepo,
                            SkillDistiller distiller,
                            SkillVectorIndex vectorIndex,
                            EmbeddingClient embeddingClient,
                            DataSource dataSource,
                            String workspaceRoot) {
        this.indexRepo = indexRepo;
        this.distiller = distiller;
        this.vectorIndex = vectorIndex;
        this.embeddingClient = embeddingClient;
        this.dataSource = dataSource;
        this.skillsDir = Path.of(workspaceRoot).resolve("skills-auto");
    }

    /**
     * Evaluate all pending traces and dispatch evolutions where needed.
     */
    public int evolve(List<TraceSummary> traces) {
        if (traces == null || traces.isEmpty()) return 0;
        int triggered = 0;
        for (TraceSummary t : traces) {
            try {
                if (evaluate(t)) triggered++;
            } catch (Exception e) {
                log.warn("SkillFlowEvolver.evaluate({}) failed: {}", t.fingerprint, e.getMessage());
            }
        }
        return triggered;
    }

    private boolean evaluate(TraceSummary t) {
        int total = t.successCount + t.failureCount;
        if (total < MIN_TRACES) return false;

        double failRate = total > 0 ? (double) t.failureCount / total : 0.0;
        if (failRate < FAIL_RATE_EVOLVE) return false;

        Optional<SkillEntry> existing = findSkillForFingerprint(t.fingerprint);
        if (existing.isPresent()) {
            String skillName = existing.get().name();
            log.info("SkillFlowEvolver: evolving existing skill '{}' (failRate={}, fingerprint={})",
                    skillName, String.format("%.2f", failRate), t.fingerprint);
            dispatchEvolve(skillName, t);
            return true;
        } else {
            log.info("SkillFlowEvolver: distilling new skill for fingerprint={} (failRate={})",
                    t.fingerprint, String.format("%.2f", failRate));
            dispatchDistill(t);
            return true;
        }
    }

    private Optional<SkillEntry> findSkillForFingerprint(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank() || dataSource == null) return Optional.empty();
        String sql = "SELECT name FROM skill_index WHERE fingerprint = ? LIMIT 1";
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, fingerprint);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return indexRepo.findByName(rs.getString("name"));
                }
            }
        } catch (SQLException e) {
            log.debug("findSkillForFingerprint({}) failed: {}", fingerprint, e.getMessage());
        }
        return Optional.empty();
    }

    private void dispatchEvolve(String skillName, TraceSummary t) {
        // Local CAS — prevents the same JVM from dispatching two evolve calls for one skill.
        if (evolving.putIfAbsent(skillName, Boolean.TRUE) != null) {
            log.info("Skill '{}' already evolving locally; skipping offline evolve", skillName);
            return;
        }
        // Cross-JVM CAS — prevents two JVMs (e.g. online PR4 + offline Phase 3) from
        // simultaneously writing SKILL.md and corrupting it. Mirrors SkillEvolutionRunner.
        if (!indexRepo.tryAcquireEvolveLock(skillName)) {
            evolving.remove(skillName);
            log.info("Skill '{}' locked by another JVM; skipping offline evolve", skillName);
            return;
        }
        String oldBody = readSkillBody(skillName);
        if (oldBody == null) {
            log.warn("Cannot read SKILL.md for '{}'; skipping evolve", skillName);
            indexRepo.releaseEvolveLock(skillName);
            evolving.remove(skillName);
            return;
        }
        String failedContext = extractFailureFromDetails(t.toolCallDetails());
        String userQuery = t.userQuery() != null && !t.userQuery.isBlank()
                ? t.userQuery() : t.sampleQuery();
        reactor.core.publisher.Mono.fromRunnable(() -> {
                    try {
                        SkillDistiller.DistilledSkill evolved =
                                distiller.evolve(skillName, oldBody, userQuery, failedContext).block();
                        if (evolved != null) {
                            log.info("Evolved skill '{}' from offline digestion", evolved.name());
                            saveDistilled(evolved, t.fingerprint());
                        }
                    } finally {
                        indexRepo.releaseEvolveLock(skillName);
                        evolving.remove(skillName);
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        v -> {},
                        ex -> log.warn("Async evolve crashed for '{}': {}", skillName, ex.getMessage()));
    }

    private void dispatchDistill(TraceSummary t) {
        String enrichedContext = buildEnrichedContext(t.userQuery(), t.toolCallDetails());
        String userQuery = t.userQuery() != null && !t.userQuery().isBlank()
                ? t.userQuery() : t.sampleQuery();
        reactor.core.publisher.Mono.fromRunnable(() -> {
                    SkillDistiller.DistilledSkill distilled =
                            distiller.distillWithContext(userQuery, t.fingerprint(), enrichedContext).block();
                    if (distilled != null) {
                        log.info("Distilled new skill '{}' from offline digestion with full trace context",
                                distilled.name());
                        saveDistilled(distilled, t.fingerprint());
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        v -> {},
                        ex -> log.warn("Async distill crashed for '{}': {}", t.fingerprint, ex.getMessage()));
    }

    /**
     * Persist a DistilledSkill to disk via SkillSaveTool.
     *
     * <p>The {@code fingerprint} parameter from {@link TraceSummary} is a <b>tool-sequence</b>
     * fingerprint (e.g. {@code agent_spawn|tool_index|router_tool}) used only by the offline
     * pipeline to correlate traces. It must NOT be written to {@code skill_index.fingerprint},
     * which is reserved for the <b>runtime dimension fingerprint</b> (e.g.
     * {@code u:alice|query|time=...}) that {@link
     * com.agentscopea2a.harness.hooks.SkillRetrievalHook} queries at L1. {@link
     * com.agentscopea2a.harness.tools.SkillSaveTool#saveSkill} already stamps the correct
     * runtime fingerprint internally, so we leave it alone here.
     */
    private void saveDistilled(SkillDistiller.DistilledSkill d, String fingerprint) {
        try {
            // Pass the embeddingClient so newly-distilled skills get an embedding at save time.
            // Previously this was null, which meant L2 vector search could never retrieve a
            // skill that was distilled by the offline pipeline (see review.md §6.2.5).
            SkillSaveTool saver = new SkillSaveTool(skillsDir, indexRepo, vectorIndex, embeddingClient);
            saver.saveSkill(d.name(), d.description(), d.body());
            log.info("Saved distilled skill '{}' to disk (tool-seq fingerprint={})", d.name(), fingerprint);
        } catch (Exception ex) {
            log.warn("Failed to save distilled skill '{}': {}", d.name(), ex.getMessage());
        }
    }

    private String readSkillBody(String skillName) {
        Path skillFile = skillsDir.resolve(skillName).resolve("SKILL.md");
        if (!java.nio.file.Files.isRegularFile(skillFile)) return null;
        try {
            return java.nio.file.Files.readString(skillFile, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.debug("readSkillBody({}) failed: {}", skillName, e.getMessage());
            return null;
        }
    }

    private String buildEnrichedContext(String userQuery, String toolCallDetailsJson) {
        if (toolCallDetailsJson == null || toolCallDetailsJson.isBlank()) return "";
        try {
            List<ToolCallDetail> details = new ObjectMapper()
                    .readValue(toolCallDetailsJson, new TypeReference<List<ToolCallDetail>>() {});
            if (details.isEmpty()) return "";
            StringBuilder sb = new StringBuilder("\n\n**工具调用链路详情**:\n");
            int step = 1;
            for (ToolCallDetail d : details) {
                sb.append("\n").append(step).append(". [").append(d.level()).append("] ")
                        .append(d.tool()).append("\n");
                if (d.input() != null && !d.input().isBlank()) {
                    sb.append("   - 输入: ").append(d.input()).append("\n");
                }
                if (d.output() != null && !d.output().isBlank()) {
                    sb.append("   - 输出: ").append(d.output()).append("\n");
                }
                step++;
            }
            return sb.toString();
        } catch (Exception e) {
            log.debug("buildEnrichedContext failed: {}", e.getMessage());
            return "";
        }
    }

    private String extractFailureFromDetails(String toolCallDetailsJson) {
        if (toolCallDetailsJson == null || toolCallDetailsJson.isBlank()) return "";
        try {
            List<ToolCallDetail> details = new ObjectMapper()
                    .readValue(toolCallDetailsJson, new TypeReference<List<ToolCallDetail>>() {});
            StringBuilder sb = new StringBuilder();
            for (ToolCallDetail d : details) {
                if (d.output() != null && (d.output().contains("error") || d.output().contains("Exception")
                        || d.output().contains("败") || d.output().contains("错误"))) {
                    sb.append("[").append(d.level()).append("] ").append(d.tool())
                            .append(": ").append(d.output()).append("\n");
                }
            }
            return sb.length() > 500 ? sb.substring(0, 500) + "…" : sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    record ToolCallDetail(String tool, String level, String input, String output) {}

    public record TraceSummary(
            String fingerprint,
            String toolSequence,
            int successCount,
            int failureCount,
            String sampleQuery,
            String userQuery,
            String toolCallDetails) {}
}
