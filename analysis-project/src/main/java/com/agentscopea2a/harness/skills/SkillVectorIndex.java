/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.agentscopea2a.harness.skills;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * PR3 — vector / fingerprint lookup over the {@code skill_index} table laid out in PR1.
 *
 * <p>L1 (fingerprint) is a plain PK / unique-key lookup, sub-millisecond. L2 (vector) loads
 * every {@code active} skill's embedding into the JVM and computes cosine in-process — fine for
 * the dozens-of-skills scale we ship at; if the catalogue ever grows to thousands, swap the
 * {@code topK} implementation for MySQL 9.0+ {@code cos_distance(VECTOR(...))} without changing
 * the interface.
 *
 * <p>Embeddings are stored as JSON {@code float[]} in the {@code embedding LONGTEXT} column PR1
 * already provisioned. Keeping the column type stable means PR3 is pure read/write code — no
 * ALTER TABLE — and an eventual MySQL-vector migration only needs a type swap.
 *
 * <p>All write paths are best-effort: a SQL failure logs a warning and returns; retrieval falls
 * back to L1 or to the existing full-injection path.
 *
 * <p><b>Bean init order</b>: {@code @DependsOn("skillIndexRepository")} so the {@code skill_index}
 * table is created by {@link SkillIndexRepository#initSchema()} before any L1/L2 query runs. On a
 * fresh DB without this guard, the first request after boot would hit a "table doesn't exist"
 * SQL error (caught + warned, but the request would silently fall through to the L2 / full-injection
 * fallback when L1 should have been authoritative).
 */
@Repository
@DependsOn("skillIndexRepository")
public class SkillVectorIndex {

    private static final Logger log = LoggerFactory.getLogger(SkillVectorIndex.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<float[]> FLOAT_ARRAY = new TypeReference<>() {};

    private final DataSource dataSource;

    public SkillVectorIndex(@Qualifier("mysqlDataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Hit returned by L2 vector search. {@code cosine} ∈ [-1, 1]; higher = more similar. */
    public record SkillHit(String name, String description, float cosine) {}

    /**
     * L1 — exact fingerprint match against {@code skill_index.fingerprint}. PR2 (synthesis)
     * stamps this column when it persists a new skill; legacy rows (manual save_skill) have
     * NULL until they're re-saved.
     */
    public Optional<String> findByFingerprint(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) return Optional.empty();
        String sql =
                "SELECT name FROM skill_index WHERE fingerprint = ? AND status = 'active' LIMIT 1";
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, fingerprint);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(rs.getString("name"));
            }
        } catch (SQLException e) {
            log.warn("findByFingerprint({}) failed: {}", fingerprint, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * L2 — application-layer cosine top-K over all {@code active} skills with non-null
     * embeddings. Hits below {@code minCosine} are filtered out; the remainder are returned in
     * descending cosine order.
     *
     * <p>Why in-process: keeps the path dependency-free on a specific MySQL version, and
     * cosine on a few hundred 1536-dim vectors is microseconds. The whole call is gated by
     * {@code harness.skills.retrieval.enabled} so default-off deployments don't pay for the
     * full-table scan.
     */
    public List<SkillHit> topK(float[] queryVec, int k, float minCosine) {
        if (queryVec == null || queryVec.length == 0 || k <= 0) return List.of();
        String sql =
                "SELECT name, description, embedding FROM skill_index"
                        + " WHERE status = 'active' AND embedding IS NOT NULL";
        List<SkillHit> hits = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            float queryNorm = norm(queryVec);
            if (queryNorm == 0f) return List.of();
            while (rs.next()) {
                String embeddingJson = rs.getString("embedding");
                if (embeddingJson == null || embeddingJson.isBlank()) continue;
                float[] vec;
                try {
                    vec = MAPPER.readValue(embeddingJson, FLOAT_ARRAY);
                } catch (Exception ex) {
                    // Skip malformed rows rather than aborting the whole retrieval — a single
                    // bad row shouldn't make every request fall back to full injection.
                    log.debug("Embedding parse failed for {}: {}", rs.getString("name"), ex.getMessage());
                    continue;
                }
                if (vec.length != queryVec.length) continue;
                float cos = cosine(queryVec, vec, queryNorm);
                if (cos >= minCosine) {
                    hits.add(
                            new SkillHit(
                                    rs.getString("name"), rs.getString("description"), cos));
                }
            }
        } catch (SQLException e) {
            log.warn("topK failed: {}", e.getMessage());
            return List.of();
        }
        hits.sort(Comparator.comparingDouble(SkillHit::cosine).reversed());
        return hits.size() > k ? hits.subList(0, k) : hits;
    }

    /**
     * Upserts the embedding + fingerprint for a skill. Called from {@link
     * com.agentscopea2a.harness.tools.SkillSaveTool} after a successful file write so newly
     * persisted skills become retrievable on the next request.
     *
     * @param fingerprint nullable — PR3 retrieval falls back to L2 when not provided
     */
    public void upsertVector(String name, String fingerprint, float[] embedding) {
        if (name == null || name.isBlank() || embedding == null || embedding.length == 0) return;
        String json;
        try {
            json = MAPPER.writeValueAsString(embedding);
        } catch (Exception ex) {
            log.warn("Embedding serialise failed for {}: {}", name, ex.getMessage());
            return;
        }
        // skill_index row already exists (PR1 SkillSaveTool inserts before we get here); plain
        // UPDATE keeps the version / usage_count columns untouched.
        String sql = "UPDATE skill_index SET embedding = ?, fingerprint = ? WHERE name = ?";
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, json);
            ps.setString(2, fingerprint);
            ps.setString(3, name);
            int rows = ps.executeUpdate();
            if (rows == 0) {
                log.warn("upsertVector found no skill_index row for {} — embedding skipped", name);
            }
        } catch (SQLException e) {
            log.warn("upsertVector({}) failed: {}", name, e.getMessage());
        }
    }

    /**
     * PR4 — refresh the embedding column for an existing skill without touching its fingerprint.
     * Used by {@code SkillEvolutionRunner} after a successful evolve so the new SKILL.md body is
     * retrievable via L2, while PR2-stamped fingerprints survive intact so L1 keeps working.
     */
    public void upsertEmbeddingOnly(String name, float[] embedding) {
        if (name == null || name.isBlank() || embedding == null || embedding.length == 0) return;
        String json;
        try {
            json = MAPPER.writeValueAsString(embedding);
        } catch (Exception ex) {
            log.warn("Embedding serialise failed for {}: {}", name, ex.getMessage());
            return;
        }
        String sql = "UPDATE skill_index SET embedding = ? WHERE name = ?";
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, json);
            ps.setString(2, name);
            int rows = ps.executeUpdate();
            if (rows == 0) {
                log.warn("upsertEmbeddingOnly found no skill_index row for {}", name);
            }
        } catch (SQLException e) {
            log.warn("upsertEmbeddingOnly({}) failed: {}", name, e.getMessage());
        }
    }

    private static float norm(float[] v) {
        double s = 0d;
        for (float x : v) s += x * x;
        return (float) Math.sqrt(s);
    }

    private static float cosine(float[] a, float[] b, float aNorm) {
        double dot = 0d;
        double bNorm = 0d;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            bNorm += b[i] * b[i];
        }
        double denom = aNorm * Math.sqrt(bNorm);
        return denom == 0d ? 0f : (float) (dot / denom);
    }

    /** Visible-for-test no-op when retrieval is wired but called with an empty workspace. */
    static List<SkillHit> empty() {
        return Collections.emptyList();
    }
}
