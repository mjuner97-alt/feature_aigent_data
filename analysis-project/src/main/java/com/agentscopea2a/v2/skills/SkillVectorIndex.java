/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.agentscopea2a.v2.skills;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

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
 * <p><b>Bean wiring:</b> Created by {@link com.agentscopea2a.v2.config.V2SkillConfig} — not
 * component-scanned. The old {@code @Repository}/{@code @Qualifier}/{@code @DependsOn} annotations
 * have been removed since the bean is now explicitly constructed in the config class.
 */
public class SkillVectorIndex {

    private static final Logger log = LoggerFactory.getLogger(SkillVectorIndex.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<float[]> FLOAT_ARRAY = new TypeReference<>() {};

    private final DataSource dataSource;
    private final boolean cacheEnabled;
    private final int cacheRefreshSeconds;

    /** JVM-level cache of active skills for L2 vector search. Thread-safe via synchronized writes. */
    private volatile List<CachedSkill> skillCache = Collections.emptyList();

    public SkillVectorIndex(DataSource dataSource, boolean cacheEnabled, int cacheRefreshSeconds) {
        this.dataSource = dataSource;
        this.cacheEnabled = cacheEnabled;
        this.cacheRefreshSeconds = cacheRefreshSeconds;
    }

    /** Cached skill entry holding pre-parsed embedding + precomputed norm for fast cosine. */
    private record CachedSkill(String name, String description, float[] embedding, float norm, String source) {}

    /**
     * Periodic cache refresh. Runs at a fixed interval so L2 queries hit memory instead of SQL.
     * Skips on failure — the stale cache is still better than falling back to SQL every request.
     */
    @PostConstruct
    @Scheduled(fixedDelayString = "${harness.skills.retrieval.cache-refresh-seconds:60}000")
    public void refreshCache() {
        if (!cacheEnabled) return;
        try {
            this.skillCache = loadAllActiveSkills();
            log.debug("SkillVectorIndex cache refreshed: {} skills loaded", skillCache.size());
        } catch (Exception ex) {
            log.warn("SkillVectorIndex cache refresh failed (stale cache intact): {}", ex.getMessage());
        }
    }

    private List<CachedSkill> loadAllActiveSkills() {
        String sql = "SELECT name, description, embedding, source FROM skill_index"
                + " WHERE status = 'active' AND embedding IS NOT NULL";
        List<CachedSkill> list = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String json = rs.getString("embedding");
                if (json == null || json.isBlank()) continue;
                float[] vec;
                try {
                    vec = MAPPER.readValue(json, FLOAT_ARRAY);
                } catch (Exception ex) {
                    continue;
                }
                float n = norm(vec);
                if (n == 0f) continue;
                list.add(new CachedSkill(
                        rs.getString("name"),
                        rs.getString("description"),
                        vec,
                        n,
                        rs.getString("source")));
            }
        } catch (SQLException e) {
            log.warn("loadAllActiveSkills failed: {}", e.getMessage());
        }
        return List.copyOf(list);
    }

    /** Hit returned by L2 vector search. {@code cosine} ∈ [-1, 1]; higher = more similar. */
    public record SkillHit(String name, String description, float cosine) {}

    /**
     * L1 — exact fingerprint match against {@code skill_index.fingerprint}. PR2 (synthesis)
     * stamps this column when it persists a new skill; legacy rows (manual save_skill) have
     * NULL until they're re-saved.
     */
    public Optional<String> findByFingerprint(String fingerprint) {
        return findByFingerprint(fingerprint, null);
    }

    /**
     * Source-filtered L1 lookup. When {@code source} is non-null, restricts the match to that
     * source (e.g. {@code "user_generated"} so the retrieval path can probe user skills first
     * then fall back to auto). When {@code source} is null, matches any source.
     */
    public Optional<String> findByFingerprint(String fingerprint, String source) {
        if (fingerprint == null || fingerprint.isBlank()) return Optional.empty();
        String sql =
                source == null
                        ? "SELECT name FROM skill_index WHERE fingerprint = ? AND status = 'active' LIMIT 1"
                        : "SELECT name FROM skill_index WHERE fingerprint = ? AND status = 'active' AND source = ? LIMIT 1";
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, fingerprint);
            if (source != null) {
                ps.setString(2, source);
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(rs.getString("name"));
            }
        } catch (SQLException e) {
            log.warn("findByFingerprint({}, {}) failed: {}", fingerprint, source, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * L2 — application-layer cosine top-K over all {@code active} skills with non-null
     * embeddings. Uses the JVM-level cache when enabled; falls back to full-table SQL scan when
     * cache is disabled, empty, or a refresh is in progress.
     *
     * <p>Hits below {@code minCosine} are filtered out; the remainder are returned in
     * descending cosine order.
     */
    public List<SkillHit> topK(float[] queryVec, int k, float minCosine) {
        return topK(queryVec, k, minCosine, null);
    }

    /**
     * Source-filtered L2 top-K. When {@code source} is non-null, only skills whose
     * {@code skill_index.source} matches are considered. This is what lets the retrieval path
     * probe user skills first (L1 user -> L2 user) and only fall back to auto skills
     * (L1 auto -> L2 auto) when the user pool misses.
     */
    public List<SkillHit> topK(float[] queryVec, int k, float minCosine, String source) {
        if (queryVec == null || queryVec.length == 0 || k <= 0) return List.of();
        float qNorm = norm(queryVec);
        if (qNorm == 0f) return List.of();

        List<CachedSkill> cache = this.skillCache;
        List<SkillHit> hits;

        if (cacheEnabled && !cache.isEmpty()) {
            // Fast path: in-memory cosine over cached skills
            hits = new ArrayList<>();
            for (CachedSkill s : cache) {
                if (source != null && !source.equals(s.source())) continue;
                if (s.embedding().length != queryVec.length) continue;
                float cos = cosine(queryVec, s.embedding(), qNorm, s.norm());
                if (cos >= minCosine) {
                    hits.add(new SkillHit(s.name(), s.description(), cos));
                }
            }
        } else {
            // Fallback: full-table SQL scan
            hits = dbTopK(queryVec, qNorm, minCosine, source);
        }

        hits.sort(Comparator.comparingDouble(SkillHit::cosine).reversed());
        return hits.size() > k ? hits.subList(0, k) : hits;
    }

    /** Full-table SQL scan fallback when cache is unavailable. */
    private List<SkillHit> dbTopK(float[] queryVec, float qNorm, float minCosine, String source) {
        String sql = "SELECT name, description, embedding FROM skill_index"
                + " WHERE status = 'active' AND embedding IS NOT NULL"
                + (source == null ? "" : " AND source = ?");
        List<SkillHit> hits = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql)) {
            if (source != null) {
                ps.setString(1, source);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String embeddingJson = rs.getString("embedding");
                    if (embeddingJson == null || embeddingJson.isBlank()) continue;
                    float[] vec;
                    try {
                        vec = MAPPER.readValue(embeddingJson, FLOAT_ARRAY);
                    } catch (Exception ex) {
                        continue;
                    }
                    if (vec.length != queryVec.length) continue;
                    float cos = cosine(queryVec, vec, qNorm, norm(vec));
                    if (cos >= minCosine) {
                        hits.add(new SkillHit(rs.getString("name"), rs.getString("description"), cos));
                    }
                }
            }
        } catch (SQLException e) {
            log.warn("dbTopK failed: {}", e.getMessage());
        }
        return hits;
    }

    /**
     * Upserts the embedding + fingerprint for a skill. Called from {@link
     * com.agentscopea2a.v2.tools.SkillSaveTool} after a successful file write so newly
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
            // Write-through cache update
            if (cacheEnabled && rows > 0) {
                upsertCacheEntry(name, embedding, null);
            }
        } catch (SQLException e) {
            log.warn("upsertVector({}) failed: {}", name, e.getMessage());
        }
    }

    /**
     * PR4 — refresh the embedding column for an existing skill without touching its fingerprint.
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
            // Write-through cache update
            if (cacheEnabled && rows > 0) {
                upsertCacheEntry(name, embedding, null);
            }
        } catch (SQLException e) {
            log.warn("upsertEmbeddingOnly({}) failed: {}", name, e.getMessage());
        }
    }

    /**
     * Update or append a single entry in the JVM cache (write-through).
     *
     * <p>Synchronized on the cache monitor so concurrent {@code upsertVector} / {@code
     * upsertEmbeddingOnly} calls don't lose updates. The previous implementation did
     * {@code new ArrayList<>(skillCache) → removeIf → add → List.copyOf} without any lock —
     * two threads racing on the same snapshot could both remove the entry and both add,
     * but the second {@code skillCache =} assignment would clobber the first, losing the
     * first thread's update. Writes here are infrequent (one per skill save/evolve), so a
     * coarse lock is fine.
     */
    private synchronized void upsertCacheEntry(String name, float[] embedding, String description) {
        float n = norm(embedding);
        if (n == 0f) return;
        String source = lookupSource(name);
        List<CachedSkill> current = new ArrayList<>(this.skillCache);
        current.removeIf(s -> s.name().equals(name));
        current.add(new CachedSkill(name, description, embedding, n, source));
        this.skillCache = List.copyOf(current);
    }

    /**
     * Best-effort source lookup for write-through cache updates. Checks the in-memory cache
     * first (free), then falls back to a one-row DB SELECT. Returns null when both miss - the
     * entry still goes into the cache so cosine search works, but source-filtered queries will
     * skip it until the next periodic refresh reloads it with the authoritative source.
     */
    private String lookupSource(String name) {
        for (CachedSkill s : this.skillCache) {
            if (s.name().equals(name)) return s.source();
        }
        String sql = "SELECT source FROM skill_index WHERE name = ?";
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("source");
            }
        } catch (SQLException e) {
            log.debug("lookupSource({}) failed: {}", name, e.getMessage());
        }
        return null;
    }

    private static float norm(float[] v) {
        double s = 0d;
        for (float x : v) s += x * x;
        return (float) Math.sqrt(s);
    }

    private static float cosine(float[] a, float[] b, float aNorm, float bNorm) {
        double dot = 0d;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        double denom = aNorm * bNorm;
        return denom == 0d ? 0f : (float) (dot / denom);
    }

    /** Visible-for-test no-op when retrieval is wired but called with an empty workspace. */
    static List<SkillHit> empty() {
        return Collections.emptyList();
    }
}
