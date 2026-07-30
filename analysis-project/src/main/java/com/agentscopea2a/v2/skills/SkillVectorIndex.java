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
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * PR3 - 在 PR1 建立的 {@code skill_index} 表之上做向量 / 指纹检索。
 *
 * <p>L1(指纹)是普通的 PK / 唯一键查询,亚毫秒级。L2(向量)会把每个 {@code active}
 * 技能的 embedding 加载到 JVM 内并在进程内计算余弦相似度 - 对于我们当前几十个技能的
 * 规模完全够用;如果将来目录增长到上千个,可以把 {@code topK} 实现换成 openGauss 的
 * 向量类型而不改变接口。
 *
 * <p>Embedding 以 JSON {@code float[]} 形式存储在 PR1 已经预留的 {@code embedding TEXT}
 * 列中。保持列类型稳定意味着 PR3 是纯读/写代码 - 无需 ALTER TABLE - 未来的向量迁移
 * 也只需要做一个类型替换。
 *
 * <p>所有写路径都是尽力而为:SQL 失败时记录告警并返回;检索会回退到 L1 或既有的
 * 全量注入路径。
 *
 * <p><b>Bean 装配:</b> 由 {@link com.agentscopea2a.v2.config.V2SkillConfig} 创建 - 不走
 * 组件扫描。旧的 {@code @Repository}/{@code @Qualifier}/{@code @DependsOn} 注解已移除,
 * 因为该 bean 现在在配置类中显式构造。
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
    private record CachedSkill(String name, String description,  String source, String ownerUserId) {}

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
        String sql = "SELECT name, description, embedding, source, owner_user_id FROM skill_index"
                + " WHERE status = 'active' ";
        List<CachedSkill> list = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {

                list.add(new CachedSkill(
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getString("source"),
                        rs.getString("owner_user_id")));
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

    /** 保留旧签名兼容:不带引用列表。 */
    public Optional<String> findByFingerprint(String fingerprint, String source, String userId) {
        return findByFingerprint(fingerprint, source, userId, null);
    }


    public Optional<String> findByFingerprint(String fingerprint, String source, String userId,
                                               Set<String> visibleNames) {
        if (fingerprint == null || fingerprint.isBlank()) return Optional.empty();
        // visibleNames 非空:一次性按 name IN 查询(已含自引用,无需 owner_user_id 过滤)
        if (visibleNames != null && !visibleNames.isEmpty()) {
            return findByFingerprintInNames(fingerprint, source, visibleNames);
        }
        // visibleNames 为空:只查全局的(owner_user_id IS NULL),兼容匿名用户
        String sql = "SELECT name FROM skill_index"
                + " WHERE fingerprint = ? AND status = 'active' AND source = ?"
                + " AND owner_user_id IS NULL LIMIT 1";
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, fingerprint);
            ps.setString(2, source);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(rs.getString("name"));
            }
        } catch (SQLException e) {
            log.warn("findByFingerprint({}, {}, {}) failed: {}", fingerprint, source, userId, e.getMessage());
        }
        return Optional.empty();
    }

    /** 在指定 name 集合中按指纹查找 active skill。 */
    private Optional<String> findByFingerprintInNames(String fingerprint, String source, Set<String> names) {
        if (names == null || names.isEmpty()) return Optional.empty();
        String placeholders = names.stream().map(n -> "?").collect(java.util.stream.Collectors.joining(","));
        String sql = "SELECT name FROM skill_index"
                + " WHERE fingerprint = ? AND status = 'active' AND source = ?"
                + " AND name IN (" + placeholders + ") LIMIT 1";
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, fingerprint);
            ps.setString(2, source);
            int idx = 3;
            for (String n : names) {
                ps.setString(idx++, n);
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(rs.getString("name"));
            }
        } catch (SQLException e) {
            log.warn("findByFingerprintInNames failed: {}", e.getMessage());
        }
        return Optional.empty();
    }

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
                upsertCacheEntry(name, embedding, null, lookupOwnerUserId(name));
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
                upsertCacheEntry(name, embedding, null, lookupOwnerUserId(name));
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
    private synchronized void upsertCacheEntry(String name, float[] embedding, String description, String ownerUserId) {
        float n = norm(embedding);
        if (n == 0f) return;
        String source = lookupSource(name);
        List<CachedSkill> current = new ArrayList<>(this.skillCache);
        current.removeIf(s -> s.name().equals(name));
        current.add(new CachedSkill(name, description,source, ownerUserId));
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

    /**
     * Best-effort owner_user_id lookup for write-through cache updates.
     */
    private String lookupOwnerUserId(String name) {
        for (CachedSkill s : this.skillCache) {
            if (s.name().equals(name)) return s.ownerUserId();
        }
        String sql = "SELECT owner_user_id FROM skill_index WHERE name = ?";
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("owner_user_id");
            }
        } catch (SQLException e) {
            log.debug("lookupOwnerUserId({}) failed: {}", name, e.getMessage());
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
