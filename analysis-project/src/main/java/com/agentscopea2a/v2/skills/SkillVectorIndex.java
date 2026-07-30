/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.agentscopea2a.v2.skills;

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
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * PR3 - 在 PR1 建立的 {@code skill_index} 表之上做指纹检索。
 *
 * <p>L1(指纹)是普通的 PK / 唯一键查询,亚毫秒级。
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

    private final DataSource dataSource;
    private final boolean cacheEnabled;
    private final int cacheRefreshSeconds;

    /** JVM-level cache of active skills for user isolation filtering. Thread-safe via synchronized writes. */
    private volatile List<CachedSkill> skillCache = Collections.emptyList();

    public SkillVectorIndex(DataSource dataSource, boolean cacheEnabled, int cacheRefreshSeconds) {
        this.dataSource = dataSource;
        this.cacheEnabled = cacheEnabled;
        this.cacheRefreshSeconds = cacheRefreshSeconds;
    }

    /** Cached skill entry holding source and owner_user_id for user isolation. */
    private record CachedSkill(String name, String description, String source, String ownerUserId) {}

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
        String sql = "SELECT name, description, source, owner_user_id FROM skill_index"
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

    /**
     * Update or append a single entry in the JVM cache (write-through).
     *
     * <p>Synchronized on the cache monitor so concurrent calls don't lose updates.
     */
    private synchronized void upsertCacheEntry(String name, String description, String ownerUserId) {
        List<CachedSkill> current = new ArrayList<>(this.skillCache);
        current.removeIf(s -> s.name().equals(name));
        current.add(new CachedSkill(name, description, lookupSource(name), ownerUserId));
        this.skillCache = List.copyOf(current);
    }

    /**
     * No-op: embedding column removed from skill_index table.
     * Kept for backward compatibility with callers.
     */
    public void upsertVector(String name, String fingerprint, float[] embedding) {
        log.debug("upsertVector no-op for {} (embedding column removed)", name);
    }

    /**
     * No-op: embedding column removed from skill_index table.
     * Kept for backward compatibility with callers.
     */
    public void upsertEmbeddingOnly(String name, float[] embedding) {
        log.debug("upsertEmbeddingOnly no-op for {} (embedding column removed)", name);
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


}
