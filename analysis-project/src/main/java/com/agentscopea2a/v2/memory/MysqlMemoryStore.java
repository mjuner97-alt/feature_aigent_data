/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.agentscopea2a.v2.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * MySQL persistence layer for per-user MEMORY.md and ledger jsonl entries.
 *
 * <p>Two tables, both keyed by {@code user_id} so all writes carry tenant identity:
 *
 * <ul>
 *   <li>{@code agent_memory} - single row per (user_id, kind, key_name). UPSERT semantics for
 *       MEMORY.md style "summary that gets overwritten." version increments on each write so
 *       optimistic concurrency control is available if needed.
 *   <li>{@code agent_memory_ledger} - append-only event log. Every line in a daily jsonl
 *       becomes one row. Solves the {@code O_APPEND}-vs-single-row conflict that bites the
 *       shared-container deployment when two sessions flush concurrently. {@code source}
 *       column carries provenance (host JVM hook vs. container-side watcher).
 * </ul>
 *
 * <p>Schema is created lazily on first use OR eagerly by the config class via
 * {@link #ensureSchema()} so a fresh DB just works. Tables use {@code utf8mb4}
 * for Chinese memory bodies.
 *
 * <p><b>Bean wiring:</b> Created by {@link com.agentscopea2a.v2.config.V2MemoryConfig}
 * when {@code harness.a2a.memory.mysql-mirror.enabled=true} - not component-scanned.
 * The {@code @Repository}/{@code @ConditionalOnProperty}/{@code @Qualifier} annotations
 * have been removed in favor of explicit construction in the config class.
 */
public class MysqlMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(MysqlMemoryStore.class);

    public static final String KIND_MEMORY_MD = "memory_md";
    public static final String KIND_LEDGER_JSONL = "ledger_jsonl";

    private final DataSource dataSource;

    public MysqlMemoryStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Create the {@code agent_memory} and {@code agent_memory_ledger} tables if they don't
     * already exist. Called eagerly by {@link com.agentscopea2a.v2.config.V2MemoryConfig}
     * at bean creation.
     */
    public void ensureSchema() {
        String memoryDdl =
                "CREATE TABLE IF NOT EXISTS agent_memory ("
                        + "  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                        + "  user_id VARCHAR(128) NOT NULL,"
                        + "  kind VARCHAR(32) NOT NULL,"
                        + "  key_name VARCHAR(128) NOT NULL,"
                        + "  body MEDIUMTEXT NOT NULL,"
                        + "  version INT NOT NULL DEFAULT 1,"
                        + "  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP "
                        + "    ON UPDATE CURRENT_TIMESTAMP,"
                        + "  UNIQUE KEY uk_user_kind_key (user_id, kind, key_name),"
                        + "  KEY idx_user_updated (user_id, updated_at)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
        String ledgerDdl =
                "CREATE TABLE IF NOT EXISTS agent_memory_ledger ("
                        + "  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                        + "  user_id VARCHAR(128) NOT NULL,"
                        + "  date_key VARCHAR(16) NOT NULL,"
                        + "  source VARCHAR(32) NOT NULL,"
                        + "  line MEDIUMTEXT NOT NULL,"
                        + "  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                        + "  KEY idx_user_date (user_id, date_key, id)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
        try (Connection c = dataSource.getConnection();
                Statement st = c.createStatement()) {
            st.execute(memoryDdl);
            st.execute(ledgerDdl);
            log.info("agent_memory + agent_memory_ledger tables ensured");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to init memory tables", e);
        }
    }

    /**
     * Upsert a single (user, kind, key) row's body. Used for MEMORY.md style replace-in-place.
     * version auto-increments so consumers can detect changes.
     */
    public void upsert(String userId, String kind, String keyName, String body) {
        String sql =
                "INSERT INTO agent_memory (user_id, kind, key_name, body) VALUES (?,?,?,?) "
                        + "ON DUPLICATE KEY UPDATE body=VALUES(body), version=version+1, "
                        + "updated_at=CURRENT_TIMESTAMP";
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setString(2, kind);
            ps.setString(3, keyName);
            ps.setString(4, body == null ? "" : body);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(
                    "Failed to upsert memory: user=" + userId + " key=" + keyName, e);
        }
    }

    /** Read the body for one row; empty if absent. */
    public Optional<String> read(String userId, String kind, String keyName) {
        String sql =
                "SELECT body FROM agent_memory WHERE user_id=? AND kind=? AND key_name=? LIMIT 1";
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setString(2, kind);
            ps.setString(3, keyName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.ofNullable(rs.getString("body"));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(
                    "Failed to read memory: user=" + userId + " key=" + keyName, e);
        }
        return Optional.empty();
    }

    public List<Entry> listForUser(String userId, String kind, int limit) {
        String sql =
                "SELECT user_id, kind, key_name, body, version, updated_at "
                        + "FROM agent_memory WHERE user_id=? AND kind=? "
                        + "ORDER BY updated_at DESC LIMIT ?";
        List<Entry> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setString(2, kind);
            ps.setInt(3, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp ts = rs.getTimestamp("updated_at");
                    out.add(
                            new Entry(
                                    rs.getString("user_id"),
                                    rs.getString("kind"),
                                    rs.getString("key_name"),
                                    rs.getString("body"),
                                    rs.getInt("version"),
                                    ts == null ? null : ts.toLocalDateTime()));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list memory for user " + userId, e);
        }
        return out;
    }

    /** Append a single jsonl line. Concurrent inserts are safe by virtue of being separate rows. */
    public void appendLedgerLine(String userId, String dateKey, String source, String line) {
        String sql =
                "INSERT INTO agent_memory_ledger (user_id, date_key, source, line) "
                        + "VALUES (?,?,?,?)";
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setString(2, dateKey);
            ps.setString(3, source);
            ps.setString(4, line);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(
                    "Failed to append ledger: user=" + userId + " date=" + dateKey, e);
        }
    }

    /** Latest-N ledger rows for one user (for /debug/memory rendering). */
    public List<LedgerRow> tailLedger(String userId, int limit) {
        String sql =
                "SELECT user_id, date_key, source, line, created_at "
                        + "FROM agent_memory_ledger WHERE user_id=? "
                        + "ORDER BY id DESC LIMIT ?";
        List<LedgerRow> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setInt(2, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp ts = rs.getTimestamp("created_at");
                    out.add(
                            new LedgerRow(
                                    rs.getString("user_id"),
                                    rs.getString("date_key"),
                                    rs.getString("source"),
                                    rs.getString("line"),
                                    ts == null ? null : ts.toLocalDateTime()));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to tail ledger for user " + userId, e);
        }
        return out;
    }

    /**
     * Read all ledger rows for a specific user + date, ordered by insertion id (ascending
     * so the daily file reads chronologically). Used by {@code PerUserMemoryGetTool} when
     * the LLM requests {@code memory/YYYY-MM-DD.md}.
     */
    public List<LedgerRow> readLedgerForDate(String userId, String dateKey) {
        String sql =
                "SELECT user_id, date_key, source, line, created_at "
                        + "FROM agent_memory_ledger WHERE user_id=? AND date_key=? "
                        + "ORDER BY id ASC";
        List<LedgerRow> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setString(2, dateKey);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp ts = rs.getTimestamp("created_at");
                    out.add(
                            new LedgerRow(
                                    rs.getString("user_id"),
                                    rs.getString("date_key"),
                                    rs.getString("source"),
                                    rs.getString("line"),
                                    ts == null ? null : ts.toLocalDateTime()));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(
                    "Failed to read ledger for user=" + userId + " date=" + dateKey, e);
        }
        return out;
    }

    public record Entry(
            String userId,
            String kind,
            String keyName,
            String body,
            int version,
            LocalDateTime updatedAt) {}

    public record LedgerRow(
            String userId, String dateKey, String source, String line, LocalDateTime createdAt) {}

    /**
     * Delete ledger rows for a user older than the given cutoff date. Used by the nightly
     * memory-digestion Phase 1 to purge expired jsonl entries that the
     * MemoryMaintenanceHook has already archived to disk.
     *
     * @return number of deleted rows
     */
    public int deleteLedgerBefore(String userId, LocalDate cutoff) {
        String sql = "DELETE FROM agent_memory_ledger WHERE user_id=? AND date_key < ?";
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setString(2, cutoff.toString());
            int deleted = ps.executeUpdate();
            if (deleted > 0) {
                log.debug("deleteLedgerBefore({}, {}): deleted {} rows", userId, cutoff, deleted);
            }
            return deleted;
        } catch (SQLException e) {
            log.warn("deleteLedgerBefore({}, {}) failed: {}", userId, cutoff, e.getMessage());
            return 0;
        }
    }

    /**
     * Delete all rows matching a kind prefix (e.g. {@code ledger-jsonl}) for a user older than
     * cutoff. Less granular than {@link #deleteLedgerBefore} - used by the digestion pipeline to
     * clean up stale BYOK-encrypted ledger fragments.
     *
     * @return number of deleted rows
     */
    public int deleteByKindPrefix(String userId, LocalDate cutoff) {
        String sql = "DELETE FROM agent_memory WHERE user_id=? AND kind LIKE ? AND updated_at < ?";
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userId);
            // Only target ledger-like kinds, never touch memory_md
            ps.setString(2, "ledger%");
            ps.setString(3, cutoff.atStartOfDay().toString());
            int deleted = ps.executeUpdate();
            if (deleted > 0) {
                log.debug("deleteByKindPrefix({}, {}): deleted {} rows", userId, cutoff, deleted);
            }
            return deleted;
        } catch (SQLException e) {
            log.warn("deleteByKindPrefix({}, {}) failed: {}", userId, cutoff, e.getMessage());
            return 0;
        }
    }

    /**
     * Query distinct user IDs that have activity in agent_memory_ledger within the past N days.
     * Used by the nightly digestion to discover active users when episodic_memory lacks a
     * dedicated user_id column.
     *
     * @param activeDays number of days to look back
     * @return distinct user IDs
     */
    public List<String> findActiveUsers(int activeDays) {
        String sql = "SELECT DISTINCT user_id FROM agent_memory_ledger"
                + " WHERE created_at >= NOW() - INTERVAL ? DAY"
                + " AND user_id IS NOT NULL AND user_id != ''"
                + " ORDER BY user_id";
        List<String> users = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, Math.max(1, activeDays));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    users.add(rs.getString("user_id"));
                }
            }
        } catch (SQLException e) {
            log.warn("findActiveUsers({}) failed: {}", activeDays, e.getMessage());
        }
        return users;
    }
}
