/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.agentscopea2a.v2.skills;

import jakarta.annotation.PostConstruct;
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
 * 基于 openGauss 的技能元数据注册表。是技能版本 / 使用次数 / 状态的唯一事实来源;
 * 每当 {@code SkillSaveTool} 写入文件时,SKILL.md 的 YAML frontmatter 都会从这些行重新生成。
 *
 * <p>延迟初始化 schema(与 {@code ResponseCacheService} 一致),保证 openGauss 短暂不可达时
 * 启动仍然健壮。{@code success_count}、{@code failure_count} 列预留给
 * PR4 - PR1 只写入可观测性基线(version + usage_count)。
 *
 * <p><b>Bean 装配:</b> 由 {@link com.agentscopea2a.v2.config.V2ToolConfig} 创建 - 不走组件扫描。
 * 旧的 {@code @Repository}/{@code @Qualifier} 注解已移除,因为该 bean 现在在配置类中显式构造。
 */
public class SkillIndexRepository {

    private static final Logger log = LoggerFactory.getLogger(SkillIndexRepository.class);

    private static final String DDL =
            "CREATE TABLE IF NOT EXISTS skill_index ("
                    + "  name VARCHAR(128) PRIMARY KEY,"
                    + "  fingerprint VARCHAR(255) NULL,"
                    + "  description TEXT,"
                    + "  version INT NOT NULL DEFAULT 1,"
                    + "  usage_count INT NOT NULL DEFAULT 0,"
                    + "  success_count INT NOT NULL DEFAULT 0,"
                    + "  failure_count INT NOT NULL DEFAULT 0,"
                    + "  last_used TIMESTAMP NULL,"
                    + "  status VARCHAR(16) NOT NULL DEFAULT 'active',"
                    + "  source VARCHAR(16) NOT NULL DEFAULT 'auto_synthesized',"
                    + "  owner_user_id VARCHAR(64) DEFAULT NULL,"
                    + "  updated_at TIMESTAMP NOT NULL DEFAULT now()"
                    + ")";

    private final DataSource dataSource;
    private volatile boolean tableEnsured = false;

    public SkillIndexRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Eager DDL — runs once at boot so the first {@code save_skill} / PR3 retrieval / PR2
     * synthesis call doesn't pay for the {@code CREATE TABLE IF NOT EXISTS} round-trip. A boot
     * failure here is non-fatal: {@link #ensureTable()} still runs on every call and will retry.
     */
    @PostConstruct
    void initSchema() {
        ensureTable();
    }

    public Optional<SkillEntry> findByName(String name) {
        ensureTable();
        String sql =
                "SELECT name, fingerprint, description, version, usage_count, last_used, status, source, owner_user_id, updated_at"
                        + " FROM skill_index WHERE name = ?";
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(map(rs));
                }
            }
        } catch (SQLException e) {
            log.warn("findByName({}) failed: {}", name, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Insert-or-bump-version. Returns the new version number that the caller should embed
     * into the SKILL.md frontmatter. Atomic via {@code ON DUPLICATE KEY UPDATE}.
     *
     * @return the final version after upsert, or -1 when the write failed (caller logs and
     *     continues — file persistence is the authoritative path; this row is observability)
     */
    public int upsertOnSave(String name, String description, String source) {
        return upsertOnSave(name, description, source, null);
    }

    /**
     * PR5 - upsert with owner_user_id for user isolation.
     *
     * @param ownerUserId nullable - NULL for global (auto_synthesized or legacy);
     *     non-null for user-scoped skills
     * @return the final version after upsert, or -1 when the write failed
     */
    public int upsertOnSave(String name, String description, String source, String ownerUserId) {
        ensureTable();
        // openGauss 不支持 PostgreSQL 的 ON CONFLICT 语法,使用 MySQL 兼容的
        // ON DUPLICATE KEY UPDATE。引用插入值用 VALUES(col) 而非 EXCLUDED.col。
        String sql =
                "INSERT INTO skill_index (name, description, version, status, source, owner_user_id)"
                        + " VALUES (?, ?, 1, 'active', ?, ?)"
                        + " ON DUPLICATE KEY UPDATE"
                        + "   description = VALUES(description),"
                        + "   version = version + 1,"
                        + "   status = 'active',"
                        + "   owner_user_id = VALUES(owner_user_id)";
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, description == null ? "" : description);
            ps.setString(3, source == null ? SkillEntry.SOURCE_AUTO_SYNTHESIZED : source);
            ps.setString(4, ownerUserId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("upsertOnSave({}, {}, {}, {}) failed: {}", name, description, source, ownerUserId, e.getMessage());
            return -1;
        }
        return findByName(name).map(SkillEntry::version).orElse(-1);
    }

    /**
     * 改名同步:将 skill_index 行主键 name 从 {@code oldName} 改为 {@code newName},并刷新
     * description。原地更新,保留 version / usage_count / success_count / failure_count /
     * fingerprint / source / owner_user_id 等全部列,仅动 name、description、version、updated_at。
     *
     * <p>用于页面 Skill 改名时让检索索引跟随同步,避免出现"旧行残留(仍 active) + 新行新建"
     * 的分裂状态——那种状态会让 {@code skill_manage.retrieval_name} 与 {@code skill_index.name}
     * 指向不同的行,看起来像改动没生效。
     *
     * <p>调用方须先确认 {@code newName} 在 skill_index 中不存在(否则触发 PK 唯一约束,
     * 本次更新抛 SQLException,方法返回 0)。
     *
     * @return 实际更新行数;0 表示 {@code oldName} 不存在或 {@code newName} 已存在(冲突)
     */
    public int renameRow(String oldName, String newName, String description) {
        ensureTable();
        String sql =
                "UPDATE skill_index SET name = ?, description = ?, version = version + 1, updated_at = now()"
                        + " WHERE name = ?";
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, newName);
            ps.setString(2, description == null ? "" : description);
            ps.setString(3, oldName);
            return ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("renameRow({} -> {}) failed: {}", oldName, newName, e.getMessage());
            return 0;
        }
    }

    /**
     * Cross-source name collision guard. {@code skill_index.name} is PRIMARY KEY, so two skills
     * with the same name cannot coexist. Source is immutable once written, so a name already
     * owned by the other source must be rejected (caller surfaces an error to the user or skips).
     *
     * @return true if the name is free OR already owned by {@code expectedSource}; false if the
     *     name exists with a different source (collision)
     */
    public boolean checkNameAvailable(String name, String expectedSource) {
        ensureTable();
        String sql = "SELECT source FROM skill_index WHERE name = ?";
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return true;
                }
                String existing = rs.getString("source");
                return existing == null || existing.equals(expectedSource);
            }
        } catch (SQLException e) {
            log.warn("checkNameAvailable({}, {}) failed: {}", name, expectedSource, e.getMessage());
            return false;
        }
    }

    /**
     * Bump {@code usage_count} and stamp {@code last_used}. PR2/PR3 will call this whenever
     * the skill is actually loaded or selected — kept here so the API surface stays stable.
     */
    public void recordUsage(String name) {
        ensureTable();
        String sql =
                "UPDATE skill_index SET usage_count = usage_count + 1, last_used = now()"
                        + " WHERE name = ?";
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("recordUsage({}) failed: {}", name, e.getMessage());
        }
    }

    /**
     * PR4 — atomic +1 on {@code success_count}. Called by {@code SkillEvolutionHook} when a
     * retrieved skill participates in a turn that has no failure signals. Returns false on SQL
     * error so the caller can degrade gracefully (we never throw out of the hook path).
     */
    public boolean incrementSuccess(String name) {
        return bumpCounter(name, "success_count");
    }

    /**
     * PR4 — atomic +1 on {@code failure_count}. Called when retry≥2 / PostCall exception /
     * cross-turn user rejection is detected against a retrieved skill.
     */
    public boolean incrementFailure(String name) {
        return bumpCounter(name, "failure_count");
    }

    private boolean bumpCounter(String name, String column) {
        ensureTable();
        // Column name is hard-coded by the caller (not user input) — safe to interpolate.
        String sql = "UPDATE skill_index SET " + column + " = " + column + " + 1 WHERE name = ?";
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, name);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.warn("{}({}) failed: {}", column, name, e.getMessage());
            return false;
        }
    }

    /**
     * PR4 — soft-delete a misbehaving skill by setting {@code status='blacklist'}. The file
     * stays on disk and the row keeps its accumulated counts so a future review can flip it back
     * to {@code 'active'}. Active skill lookups (e.g.
     * {@link #findActiveNameByFingerprint(String)}) filter on {@code status='active'},
     * so a blacklisted skill silently stops being retrieved.
     */
    public boolean markBlacklist(String name) {
        ensureTable();
        String sql = "UPDATE skill_index SET status = 'blacklist' WHERE name = ?";
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, name);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.warn("markBlacklist({}) failed: {}", name, e.getMessage());
            return false;
        }
    }

    /**
     * PR4 — zero out the success/failure counters. Called after a successful evolve so the new
     * SKILL.md version gets a clean evaluation window; the old body's failures don't follow the
     * new body into another immediate evolve cycle.
     */
    public boolean resetCounts(String name) {
        ensureTable();
        String sql = "UPDATE skill_index SET success_count = 0, failure_count = 0 WHERE name = ?";
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, name);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.warn("resetCounts({}) failed: {}", name, e.getMessage());
            return false;
        }
    }

    /** PR4 — counts + version snapshot for the evolution-threshold check. */
    public Optional<SkillStats> findStats(String name) {
        ensureTable();
        String sql =
                "SELECT name, success_count, failure_count, version, status, source"
                        + " FROM skill_index WHERE name = ?";
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(
                            new SkillStats(
                                    rs.getString("name"),
                                    rs.getInt("success_count"),
                                    rs.getInt("failure_count"),
                                    rs.getInt("version"),
                                    rs.getString("status"),
                                    rs.getString("source")));
                }
            }
        } catch (SQLException e) {
            log.warn("findStats({}) failed: {}", name, e.getMessage());
        }
        return Optional.empty();
    }

    /** Snapshot of the PR4-relevant columns. */
    public record SkillStats(
            String name,
            int successCount,
            int failureCount,
            int version,
            String status,
            String source) {
        public int totalUses() {
            return successCount + failureCount;
        }

        public double failureRate() {
            int total = totalUses();
            return total == 0 ? 0d : ((double) failureCount) / total;
        }
    }

    /**
     * Stamp (or update) the fingerprint for a given skill name. Used by offline digestion
     * (SkillFlowEvolver) so subsequent findSkillForFingerprint() lookups find the existing skill.
     */
    public void upsertFingerprint(String name, String fingerprint) {
        ensureTable();
        String sql = "UPDATE skill_index SET fingerprint = ? WHERE name = ?";
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, fingerprint);
            ps.setString(2, name);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("upsertFingerprint({}) failed: {}", name, e.getMessage());
        }
    }

    /**
     * No-op: tool_sequence_fingerprint column removed from skill_index table.
     * Kept for backward compatibility with callers.
     */
    public void upsertToolSequenceFingerprint(String name, String toolSeqFingerprint) {
        log.debug("upsertToolSequenceFingerprint no-op for {} (column removed)", name);
    }

    /**
     * Find a skill name by its runtime fingerprint (metric-based format like
     * {@code _global|query|defect_density}). Used by Phase 3 (night-time digestion) to
     * match failed traces to existing skills for evolution.
     *
     * @return skill name if found, empty otherwise
     */
    public Optional<String> findNameByFingerprint(String runtimeFingerprint) {
        return findNameByFingerprint(runtimeFingerprint, null);
    }

    /**
     * Source-filtered variant. When {@code source} is non-null, restricts the match to that
     * source (e.g. {@code "auto_synthesized"} for night-time digestion so user skills are
     * never matched/evolved by Phase 3).
     *
     * @return skill name if found, empty otherwise
     */
    public Optional<String> findNameByFingerprint(String runtimeFingerprint, String source) {
        ensureTable();
        String sql =
                source == null
                        ? "SELECT name FROM skill_index WHERE fingerprint = ? LIMIT 1"
                        : "SELECT name FROM skill_index WHERE fingerprint = ? AND source = ? LIMIT 1";
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, runtimeFingerprint);
            if (source != null) {
                ps.setString(2, source);
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(rs.getString("name"));
                }
            }
        } catch (SQLException e) {
            log.warn("findNameByFingerprint({}, {}) failed: {}", runtimeFingerprint, source, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Find an active skill name by its runtime fingerprint. Like {@link #findNameByFingerprint(String)}
     * but restricted to {@code status='active'} so blacklisted skills are never matched. Used by
     * {@link com.agentscopea2a.v2.hooks.SkillEvolutionHook} for the fingerprint fallback path.
     */
    public Optional<String> findActiveNameByFingerprint(String runtimeFingerprint) {
        if (runtimeFingerprint == null || runtimeFingerprint.isBlank()) return Optional.empty();
        ensureTable();
        String sql = "SELECT name FROM skill_index WHERE fingerprint = ? AND status = 'active' LIMIT 1";
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, runtimeFingerprint);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(rs.getString("name"));
                }
            }
        } catch (SQLException e) {
            log.warn("findActiveNameByFingerprint({}) failed: {}", runtimeFingerprint, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * No-op: tool_sequence_fingerprint column removed from skill_index table.
     * Kept for backward compatibility with callers.
     */
    public Optional<String> findNameByToolSequenceFingerprint(String toolSeqFingerprint) {
        return findNameByToolSequenceFingerprint(toolSeqFingerprint, null);
    }

    /**
     * No-op: tool_sequence_fingerprint column removed from skill_index table.
     * Kept for backward compatibility with callers.
     */
    public Optional<String> findNameByToolSequenceFingerprint(String toolSeqFingerprint, String source) {
        log.debug("findNameByToolSequenceFingerprint no-op for {} (column removed)", toolSeqFingerprint);
        return Optional.empty();
    }

    /**
     * No-op: evolving column removed from skill_index table.
     * Kept for backward compatibility with callers.
     */
    public boolean tryAcquireEvolveLock(String name) {
        log.debug("tryAcquireEvolveLock no-op for {} (column removed)", name);
        return true;
    }

    /**
     * No-op: evolving column removed from skill_index table.
     * Kept for backward compatibility with callers.
     */
    public boolean releaseEvolveLock(String name) {
        log.debug("releaseEvolveLock no-op for {} (column removed)", name);
        return true;
    }

    /**
     * No-op: embedding column removed from skill_index table.
     * Kept for backward compatibility with callers. Returns true to avoid unnecessary log output.
     */
    public boolean hasEmbedding(String name) {
        log.debug("hasEmbedding no-op for {} (column removed)", name);
        return true;
    }

    private void ensureTable() {
        if (tableEnsured) return;
        synchronized (this) {
            if (tableEnsured) return;
            try (Connection c = dataSource.getConnection();
                    Statement s = c.createStatement()) {
                s.execute(DDL);
                tableEnsured = true;
                log.info("skill_index table ensured");
            } catch (SQLException e) {
                log.warn("skill_index DDL failed (will retry on next call): {}", e.getMessage());
            }
        }
    }

    private SkillEntry map(ResultSet rs) throws SQLException {
        Timestamp lastUsed = rs.getTimestamp("last_used");
        Timestamp updated = rs.getTimestamp("updated_at");
        return new SkillEntry(
                rs.getString("name"),
                rs.getString("fingerprint"),
                rs.getString("description"),
                rs.getInt("version"),
                rs.getInt("usage_count"),
                lastUsed == null ? null : lastUsed.toLocalDateTime(),
                rs.getString("status"),
                rs.getString("source"),
                rs.getString("owner_user_id"),
                updated == null ? LocalDateTime.now() : updated.toLocalDateTime());
    }
}
