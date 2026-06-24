/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.agentscopea2a.harness.skills;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;

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
 * MySQL-backed registry for skill metadata. Single source of truth for skill version /
 * usage / status; the SKILL.md YAML frontmatter is regenerated from these rows whenever
 * {@code SkillSaveTool} writes a file.
 *
 * <p>Lazy schema initialisation (mirrors {@code ResponseCacheService}) keeps boot resilient
 * when MySQL is briefly unreachable. {@code embedding}, {@code success_count}, {@code
 * failure_count} columns are reserved for PR3/PR4 — PR1 only writes the observability
 * baseline (version + usage_count).
 */
@Repository
public class SkillIndexRepository {

    private static final Logger log = LoggerFactory.getLogger(SkillIndexRepository.class);

    private static final String DDL =
            "CREATE TABLE IF NOT EXISTS skill_index ("
                    + "  name VARCHAR(128) PRIMARY KEY,"
                    + "  fingerprint VARCHAR(255) NULL COMMENT 'PR3 L1 lookup key, NULL until then',"
                    + "  description TEXT,"
                    + "  embedding LONGTEXT NULL COMMENT 'PR3 reserved; JSON-encoded float[] for MySQL<8.4',"
                    + "  version INT NOT NULL DEFAULT 1,"
                    + "  usage_count INT NOT NULL DEFAULT 0,"
                    + "  success_count INT NOT NULL DEFAULT 0,"
                    + "  failure_count INT NOT NULL DEFAULT 0,"
                    + "  last_used TIMESTAMP NULL,"
                    + "  status VARCHAR(16) NOT NULL DEFAULT 'active',"
                    + "  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP "
                    + "             ON UPDATE CURRENT_TIMESTAMP,"
                    + "  KEY idx_status (status)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

    private final DataSource dataSource;
    private volatile boolean tableEnsured = false;

    public SkillIndexRepository(@Qualifier("mysqlDataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Optional<SkillEntry> findByName(String name) {
        ensureTable();
        String sql =
                "SELECT name, description, version, usage_count, last_used, status, updated_at"
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
    public int upsertOnSave(String name, String description) {
        ensureTable();
        String sql =
                "INSERT INTO skill_index (name, description, version, status)"
                        + " VALUES (?, ?, 1, 'active')"
                        + " ON DUPLICATE KEY UPDATE"
                        + "   description = VALUES(description),"
                        + "   version = version + 1,"
                        + "   status = 'active'";
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, description == null ? "" : description);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("upsertOnSave({}) failed: {}", name, e.getMessage());
            return -1;
        }
        return findByName(name).map(SkillEntry::version).orElse(-1);
    }

    /**
     * Bump {@code usage_count} and stamp {@code last_used}. PR2/PR3 will call this whenever
     * the skill is actually loaded or selected — kept here so the API surface stays stable.
     */
    public void recordUsage(String name) {
        ensureTable();
        String sql =
                "UPDATE skill_index SET usage_count = usage_count + 1, last_used = NOW()"
                        + " WHERE name = ?";
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("recordUsage({}) failed: {}", name, e.getMessage());
        }
    }

    private SkillEntry map(ResultSet rs) throws SQLException {
        Timestamp lastUsed = rs.getTimestamp("last_used");
        Timestamp updated = rs.getTimestamp("updated_at");
        return new SkillEntry(
                rs.getString("name"),
                rs.getString("description"),
                rs.getInt("version"),
                rs.getInt("usage_count"),
                lastUsed == null ? null : lastUsed.toLocalDateTime(),
                rs.getString("status"),
                updated == null ? LocalDateTime.now() : updated.toLocalDateTime());
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
}
