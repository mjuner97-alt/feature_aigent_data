/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.agentscopea2a.v2.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Schema management for the episodic memory table. Extracted from
 * {@code MySqlEpisodicMemory} (P2-1) so DDL strings + INFORMATION_SCHEMA probes
 * live separately from the search / record logic.
 *
 * <p>Two responsibilities:
 * <ul>
 *   <li>{@link #ensureInitialized()} - lazy, synchronized, idempotent CREATE TABLE</li>
 *   <li>{@link #columnExists(Connection, String)} - probe used by the ALTER TABLE
 *       migration path (MySQL 8.0 doesn't support ADD COLUMN IF NOT EXISTS)</li>
 * </ul>
 */
final class EpisodicTableInitializer {

    private static final Logger log = LoggerFactory.getLogger(EpisodicTableInitializer.class);

    private final ConnectionSupplier connectionSupplier;
    private final String tableName;
    private volatile boolean initialized;

    EpisodicTableInitializer(ConnectionSupplier connectionSupplier, String tableName) {
        this.connectionSupplier = connectionSupplier;
        this.tableName = tableName;
    }

    /**
     * Lazy, synchronized, idempotent. First call creates the table (and runs any
     * ALTER TABLE migrations for legacy schemas); subsequent calls are a volatile read.
     */
    synchronized void ensureInitialized() {
        if (!this.initialized) {
            createTableIfNotExists();
            this.initialized = true;
        }
    }

    private void createTableIfNotExists() {
        String sql =
                "CREATE TABLE IF NOT EXISTS "
                        + this.tableName
                        + " (  id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                        + "  session_id VARCHAR(255) NOT NULL,"
                        + "  user_id VARCHAR(128) NOT NULL DEFAULT '',"
                        + "  role VARCHAR(50) NOT NULL,"
                        + "  content TEXT NOT NULL,"
                        + "  embedding LONGTEXT DEFAULT NULL,"
                        + "  status VARCHAR(16) DEFAULT 'active',"
                        + "  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                        + "  FULLTEXT INDEX ft_content (content),"
                        + "  INDEX idx_embedding (embedding(255)),"
                        + "  INDEX idx_status (status),"
                        + "  INDEX idx_user_id (user_id)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
        try (Connection conn = connectionSupplier.get();
                Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
            // Add status column if upgrading from an older schema that lacks it.
            // MySQL 8.0 does NOT support "IF NOT EXISTS" in ALTER TABLE ADD COLUMN
            // (that syntax is MariaDB-specific). We probe INFORMATION_SCHEMA first
            // and only run ALTER when the column is missing.
            if (!columnExists(conn, "status")) {
                try {
                    stmt.execute(
                            "ALTER TABLE " + this.tableName
                                    + " ADD COLUMN status VARCHAR(16) DEFAULT 'active'"
                                    + " AFTER embedding");
                } catch (SQLException ignored) {
                    log.debug("ALTER TABLE ADD COLUMN status skipped: {}", ignored.getMessage());
                }
            }
            // Add tool_call_details column for skill distillation context (paths B/C)
            if (!columnExists(conn, "tool_call_details")) {
                try {
                    stmt.execute(
                            "ALTER TABLE " + this.tableName
                                    + " ADD COLUMN tool_call_details TEXT"
                                    + " COMMENT '工具调用链路详情JSON,供skill蒸馏使用'"
                                    + " AFTER content");
                } catch (SQLException ignored) {
                    log.debug("ALTER TABLE ADD COLUMN tool_call_details skipped: {}", ignored.getMessage());
                }
            }
            // Add user_id column for tenant isolation (2026/07/21 fix). EpisodicRetrievalMiddleware
            // previously called search() with no user_id filter — all users' conversations leaked
            // into every other user's "## 历史参考案例" prefix. Now search() accepts a userId and
            // filters with WHERE user_id = ?. For legacy rows written before this column existed,
            // we backfill user_id by parsing the "user:<userId>:<conversationId>" prefix from
            // session_id. Rows whose session_id doesn't match this format keep user_id='' (they'll
            // only match searches that explicitly pass userId='' or skip the filter).
            if (!columnExists(conn, "user_id")) {
                try {
                    stmt.execute(
                            "ALTER TABLE " + this.tableName
                                    + " ADD COLUMN user_id VARCHAR(128) NOT NULL DEFAULT ''"
                                    + " AFTER session_id");
                    stmt.execute(
                            "ALTER TABLE " + this.tableName
                                    + " ADD INDEX idx_user_id (user_id)");
                    // Backfill: extract user_id from session_id format "user:<userId>:<rest>".
                    // SUBSTRING_INDEX(session_id, ':', 2) returns "user:<userId>", then strip the
                    // "user:" prefix (5 chars) to get <userId>. Rows without the "user:" prefix
                    // get '' (which matches only explicit '' userId searches, effectively orphaned).
                    int backfilled = stmt.executeUpdate(
                            "UPDATE " + this.tableName
                                    + " SET user_id = TRIM(LEADING 'user:' FROM"
                                    + "   SUBSTRING_INDEX(session_id, ':', 2))"
                                    + " WHERE user_id = ''"
                                    + "   AND session_id LIKE 'user:%:%'");
                    log.info("EpisodicTableInitializer: backfilled user_id for {} legacy rows in {}",
                            backfilled, this.tableName);
                } catch (SQLException ignored) {
                    log.warn("ALTER TABLE ADD COLUMN user_id / backfill skipped: {}", ignored.getMessage());
                }
            }
            log.info("Ensured episodic memory table '{}' exists", this.tableName);
        } catch (SQLException e) {
            log.error("Failed to create episodic memory table: {}", e.getMessage());
            throw new RuntimeException("Failed to initialize episodic memory table", e);
        }
    }

    /**
     * Parse {@code user_id} from the {@code session_id} format {@code "user:<userId>:<conversationId>"}.
     * Returns {@code ""} when {@code session_id} is null/blank or doesn't follow this format
     * (e.g. legacy sessions like {@code "pool-1-thread-1_1234567890"} from {@code syncTurn}).
     * Used by {@code recordSession*} to populate the {@code user_id} column on insert.
     */
    static String parseUserIdFromSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return "";
        if (!sessionId.startsWith("user:")) return "";
        // Strip "user:" prefix, then take everything up to the next ':' as userId.
        String rest = sessionId.substring(5); // after "user:"
        int idx = rest.indexOf(':');
        if (idx <= 0) return "";
        return rest.substring(0, idx);
    }

    /**
     * Check whether a column exists in the given table by querying
     * INFORMATION_SCHEMA.COLUMNS. This avoids MySQL 8.0's lack of support for
     * "IF NOT EXISTS" in ALTER TABLE ADD COLUMN (MariaDB-only syntax).
     */
    boolean columnExists(Connection conn, String columnName) {
        String sql = "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_SCHEMA = (SELECT DATABASE()) "
                + "AND TABLE_NAME = ? AND COLUMN_NAME = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, this.tableName);
            ps.setString(2, columnName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            log.debug("columnExists check failed for {}: {}", columnName, e.getMessage());
            return false; // fail-safe: assume it doesn't exist, ALTER will fail harmlessly
        }
    }
}
