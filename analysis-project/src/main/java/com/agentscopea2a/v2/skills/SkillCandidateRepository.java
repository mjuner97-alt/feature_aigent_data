/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.agentscopea2a.v2.skills;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 基于 openGauss 的技能<b>候选</b>注册表 - 这是技能合成的暂存区,
 * 用于统计同一个用户问题指纹出现的次数,再决定是否蒸馏出一个真正的技能。
 *
 * <p>延迟初始化 schema,与 {@link SkillIndexRepository} 一致;表在首次调用时创建,
 * 保证 openGauss 短暂不可达时启动仍然健壮。与 {@code skill_index} 的区别:
 *
 * <ul>
 *   <li>{@code skill_index}     - <i>已持久化</i>技能的唯一事实来源(文件 + DB)。
 *   <li>{@code skill_candidate} - 记录"该指纹已命中 N 次,尚未合成"。行的状态只会一次性
 *       转换为 {@code pending -> synthesized | rejected | blacklist}。
 * </ul>
 *
 * <p>Bean 由 {@link com.agentscopea2a.v2.config.V2SkillConfig} 创建。
 */
public class SkillCandidateRepository {

    private static final Logger log = LoggerFactory.getLogger(SkillCandidateRepository.class);

    private static final String DDL =
            "CREATE TABLE IF NOT EXISTS skill_candidate ("
                    + "  fingerprint VARCHAR(255) PRIMARY KEY,"
                    + "  user_id VARCHAR(64) NOT NULL,"
                    + "  hit_count INT NOT NULL DEFAULT 0,"
                    + "  last_query TEXT,"
                    + "  last_trace_id VARCHAR(64) NULL,"
                    + "  metric_tag VARCHAR(64) DEFAULT NULL,"
                    + "  status VARCHAR(16) NOT NULL DEFAULT 'pending',"
                    + "  synth_skill VARCHAR(128) NULL,"
                    + "  updated_at TIMESTAMP NOT NULL DEFAULT now()"
                    + ")";

    private final DataSource dataSource;
    private volatile boolean tableEnsured = false;

    /**
     * Creates a new SkillCandidateRepository with the given DataSource.
     * Call {@link #ensureTable()} after construction to create the schema.
     */
    public SkillCandidateRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Eager DDL — call once at boot so the first {@code incrementHit} call doesn't pay for the
     * {@code CREATE TABLE IF NOT EXISTS} round-trip. Failure is non-fatal;
     * {@link #ensureTable()} still runs on every call and will retry.
     */
    public void initSchema() {
        ensureTable();
    }

    /**
     * Upsert + bump {@code hit_count} for the given fingerprint. Rows that have already moved out
     * of {@code pending} (synthesised / rejected / blacklisted) are <b>not</b> bumped — so a
     * skill that's already been produced doesn't keep tripping the threshold forever.
     *
     * @return the row after the bump, or empty when the row is non-pending or the SQL failed
     */
    public Optional<SkillCandidate> incrementHit(
            String fingerprint, String userId, String query, String traceId) {
        ensureTable();
        // ON DUPLICATE KEY UPDATE is atomic at the row level so concurrent JVM instances each
        // counting the same fingerprint don't race. We only bump when still 'pending'; the
        // CASE clause keeps the row untouched once it's been promoted (or rejected).
        // openGauss 不支持 ON CONFLICT,使用 MySQL 兼容的 ON DUPLICATE KEY UPDATE,
        // 引用插入值用 VALUES(col) 而非 EXCLUDED.col。
        String sql =
                "INSERT INTO skill_candidate (fingerprint, user_id, hit_count, last_query, last_trace_id, status)"
                        + " VALUES (?, ?, 1, ?, ?, 'pending')"
                        + " ON DUPLICATE KEY UPDATE"
                        + "   hit_count = CASE WHEN skill_candidate.status = 'pending' THEN skill_candidate.hit_count + 1 ELSE skill_candidate.hit_count END,"
                        + "   last_query = CASE WHEN skill_candidate.status = 'pending' THEN VALUES(last_query) ELSE skill_candidate.last_query END,"
                        + "   last_trace_id = CASE WHEN skill_candidate.status = 'pending' THEN VALUES(last_trace_id) ELSE skill_candidate.last_trace_id END";
        // Manual transaction so INSERT + subsequent SELECT use the same connection - guarantees
        // read-your-writes within this call. Connection.close() rolls back if we never commit.
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, fingerprint);
                    ps.setString(2, userId == null ? "_anon" : userId);
                    ps.setString(3, query);
                    ps.setString(4, traceId);
                    ps.executeUpdate();
                }
                Optional<SkillCandidate> result = findByFingerprintInConnection(c, fingerprint);
                c.commit();
                result.ifPresent(row -> log.info(
                        "[BUMP] fingerprint={} userId={} hit_count={} status={}",
                        fingerprint, userId, row.hitCount(), row.status()));
                return result;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            log.warn("incrementHit({}) failed: {}", fingerprint, e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<SkillCandidate> findByFingerprint(String fingerprint) {
        ensureTable();
        try (Connection c = dataSource.getConnection()) {
            return findByFingerprintInConnection(c, fingerprint);
        } catch (SQLException e) {
            log.warn("findByFingerprint({}) failed: {}", fingerprint, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<SkillCandidate> findByFingerprintInConnection(Connection c, String fingerprint)
            throws SQLException {
        String sql =
                "SELECT fingerprint, user_id, hit_count, last_query, last_trace_id, metric_tag,"
                        + " status, synth_skill, updated_at FROM skill_candidate WHERE fingerprint = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, fingerprint);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(map(rs));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Atomic claim: only the JVM whose UPDATE actually changed the row from {@code pending} →
     * {@code synthesized} wins. Concurrent JVMs see {@code affected_rows == 0} and back off.
     *
     * @return {@code true} when this caller claimed the row; {@code false} when another did
     */
    public boolean markSynthesized(String fingerprint, String skillName) {
        ensureTable();
        String sql =
                "UPDATE skill_candidate SET status = 'synthesized', synth_skill = ?"
                        + " WHERE fingerprint = ? AND status = 'pending'";
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, skillName);
            ps.setString(2, fingerprint);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.warn("markSynthesized({}, {}) failed: {}", fingerprint, skillName, e.getMessage());
            return false;
        }
    }

    public void markRejected(String fingerprint, String reason) {
        ensureTable();
        // Reason is logged, not stored — keeps the schema simple. Re-introduce a column if
        // failures need to be analysed historically.
        String sql =
                "UPDATE skill_candidate SET status = 'rejected'"
                        + " WHERE fingerprint = ? AND status = 'pending'";
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, fingerprint);
            int rows = ps.executeUpdate();
            if (rows > 0) {
                log.info("Candidate {} rejected: {}", fingerprint, reason);
            }
        } catch (SQLException e) {
            log.warn("markRejected({}) failed: {}", fingerprint, e.getMessage());
        }
    }

    private SkillCandidate map(ResultSet rs) throws SQLException {
        Timestamp updated = rs.getTimestamp("updated_at");
        return new SkillCandidate(
                rs.getString("fingerprint"),
                rs.getString("user_id"),
                rs.getInt("hit_count"),
                rs.getString("last_query"),
                rs.getString("last_trace_id"),
                rs.getString("metric_tag"),
                rs.getString("status"),
                rs.getString("synth_skill"),
                updated == null ? LocalDateTime.now() : updated.toLocalDateTime());
    }

    /**
     * Async callback from MetricClassificationService — writes the metric_tag column.
     * Only writes when metric_tag IS NULL to avoid overwriting existing classification.
     */
    public void updateMetricTag(String fingerprint, String metricTag) {
        ensureTable();
        String sql = "UPDATE skill_candidate SET metric_tag = ? WHERE fingerprint = ? AND metric_tag IS NULL";
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, metricTag);
            ps.setString(2, fingerprint);
            int rows = ps.executeUpdate();
            if (rows > 0) {
                log.info("[METRIC_TAG] fingerprint={} tag={}", fingerprint, metricTag);
            }
        } catch (SQLException e) {
            log.debug("updateMetricTag({}) failed: {}", fingerprint, e.getMessage());
        }
    }

    private void ensureTable() {
        if (tableEnsured) return;
        synchronized (this) {
            if (tableEnsured) return;
            try (Connection c = dataSource.getConnection();
                    Statement s = c.createStatement()) {
                s.execute(DDL);
                tableEnsured = true;
                log.info("skill_candidate table ensured");
            } catch (SQLException e) {
                log.warn(
                        "skill_candidate DDL failed (will retry on next call): {}",
                        e.getMessage());
            }
        }
    }
}
