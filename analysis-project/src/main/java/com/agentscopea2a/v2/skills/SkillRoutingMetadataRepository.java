package com.agentscopea2a.v2.skills;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** OpenGauss-backed runtime routing metadata repository. */
public class SkillRoutingMetadataRepository {

    private static final Logger log = LoggerFactory.getLogger(SkillRoutingMetadataRepository.class);
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final long cacheTtlMillis;
    private volatile boolean tableEnsured;
    private volatile List<SkillRoutingMetadata> cachedAll;
    private volatile long cachedAt;

    public SkillRoutingMetadataRepository(DataSource dataSource, ObjectMapper objectMapper) {
        this(dataSource, objectMapper, 0L);
    }

    public SkillRoutingMetadataRepository(DataSource dataSource, ObjectMapper objectMapper, long cacheTtlMillis) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
        this.cacheTtlMillis = cacheTtlMillis;
    }

    @PostConstruct
    void initSchema() {
        ensureTable();
    }

    /**
     * Active routing metadata only. Reads go through the TTL snapshot cache when
     * {@code cacheTtlMillis > 0}; write paths ({@link #upsert}/{@link #rename}/
     * {@link #deactivate}) invalidate it so admin-page saves take effect immediately.
     */
    public List<SkillRoutingMetadata> findActive() {
        return findAll().stream().filter(SkillRoutingMetadata::active).toList();
    }

    /**
     * All routing metadata rows including {@code active=false}. The caller needs the
     * full set to distinguish "never configured" (hot-load a new SKILL.md should stay
     * visible) from "explicitly disabled" (must stay hidden).
     */
    public List<SkillRoutingMetadata> findAll() {
        ensureTable();
        List<SkillRoutingMetadata> snapshot = cachedAll;
        if (snapshot != null && cacheTtlMillis > 0
                && System.currentTimeMillis() - cachedAt < cacheTtlMillis) {
            return snapshot;
        }
        String sql = "SELECT skill_name, short_summary, aliases, keywords, metric_tags, domain_tags, "
                + "data_source_tags, priority, active, updated_at "
                + "FROM skill_routing_metadata ORDER BY priority DESC, skill_name";
        List<SkillRoutingMetadata> result = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(map(rs));
            }
            cachedAll = List.copyOf(result);
            cachedAt = System.currentTimeMillis();
        } catch (SQLException e) {
            log.warn("findAll skill routing metadata failed: {}", e.getMessage());
        }
        return result;
    }

    /** Drops the TTL snapshot so the next read hits the database. */
    public void invalidateCache() {
        cachedAll = null;
    }

    public Optional<SkillRoutingMetadata> findBySkillName(String skillName) {
        ensureTable();
        String sql = "SELECT skill_name, short_summary, aliases, keywords, metric_tags, domain_tags, "
                + "data_source_tags, priority, active, updated_at "
                + "FROM skill_routing_metadata WHERE skill_name = ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, skillName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            log.warn("findBySkillName({}) failed: {}", skillName, e.getMessage());
            return Optional.empty();
        }
    }

    public boolean skillExists(String skillName) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM skill_manage WHERE retrieval_name = ? "
                        + "AND status = 'ACTIVE' AND deleted_at IS NULL "
                        + "UNION ALL SELECT 1 FROM skill_index WHERE name = ? AND status = 'active' LIMIT 1")) {
            ps.setString(1, skillName);
            ps.setString(2, skillName);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (SQLException e) {
            log.warn("skillExists({}) failed: {}", skillName, e.getMessage());
            return false;
        }
    }

    /** Lists active, non-deleted Skills from skill_manage with optional routing metadata. */
    public List<SkillRoutingMetadataView> findAllWithSkillManage(String keyword, Boolean active, int limit, int offset) {
        ensureTable();
        StringBuilder sql = new StringBuilder("SELECT x.name, x.description, r.short_summary, r.aliases, r.keywords, r.metric_tags, r.domain_tags, r.data_source_tags, r.priority, r.active, r.updated_at, r.skill_name IS NOT NULL configured FROM (SELECT s.retrieval_name AS name, s.description FROM skill_manage s WHERE s.retrieval_name IS NOT NULL AND s.status='ACTIVE' AND s.deleted_at IS NULL UNION SELECT i.name, i.description FROM skill_index i WHERE i.status='active' AND NOT EXISTS (SELECT 1 FROM skill_manage s2 WHERE s2.retrieval_name=i.name AND s2.status='ACTIVE' AND s2.deleted_at IS NULL)) x LEFT JOIN skill_routing_metadata r ON r.skill_name=x.name WHERE 1=1");
        if (keyword != null && !keyword.isBlank()) sql.append(" AND (LOWER(x.name) LIKE ? OR LOWER(COALESCE(x.description,'')) LIKE ?)");
        if (active != null) sql.append(" AND COALESCE(r.active, TRUE)=?");
        sql.append(" ORDER BY x.name LIMIT ? OFFSET ?");
        List<SkillRoutingMetadataView> result = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int p = 1;
            if (keyword != null && !keyword.isBlank()) { String q = "%" + keyword.trim().toLowerCase() + "%"; ps.setString(p++, q); ps.setString(p++, q); }
            if (active != null) ps.setBoolean(p++, active);
            ps.setInt(p++, Math.max(1, Math.min(limit, 200))); ps.setInt(p, Math.max(0, offset));
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) result.add(mapView(rs)); }
        } catch (SQLException e) { log.warn("findAllWithSkillManage failed: {}", e.getMessage()); }
        return result;
    }

    /** Loads one configurable Skill from skill_manage with optional routing metadata. */
    public Optional<SkillRoutingMetadataView> findOneWithSkillManage(String skillName) {
        ensureTable();
        String sql = "SELECT x.name, x.description, r.short_summary, r.aliases, r.keywords, r.metric_tags, r.domain_tags, r.data_source_tags, r.priority, r.active, r.updated_at, r.skill_name IS NOT NULL configured FROM (SELECT s.retrieval_name AS name, s.description FROM skill_manage s WHERE s.retrieval_name IS NOT NULL AND s.status='ACTIVE' AND s.deleted_at IS NULL UNION SELECT i.name, i.description FROM skill_index i WHERE i.status='active' AND NOT EXISTS (SELECT 1 FROM skill_manage s2 WHERE s2.retrieval_name=i.name AND s2.status='ACTIVE' AND s2.deleted_at IS NULL)) x LEFT JOIN skill_routing_metadata r ON r.skill_name=x.name WHERE x.name=?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, skillName);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Optional.of(mapView(rs)) : Optional.empty(); }
        } catch (SQLException e) { log.warn("findOneWithSkillIndex({}) failed: {}", skillName, e.getMessage()); return Optional.empty(); }
    }

    public boolean upsert(SkillRoutingMetadata metadata) {
        ensureTable();
        String sql = "INSERT INTO skill_routing_metadata "
                + "(skill_name, short_summary, aliases, keywords, metric_tags, domain_tags, "
                + "data_source_tags, priority, active, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now()) "
                + "ON DUPLICATE KEY UPDATE short_summary=VALUES(short_summary), aliases=VALUES(aliases), "
                + "keywords=VALUES(keywords), metric_tags=VALUES(metric_tags), domain_tags=VALUES(domain_tags), "
                + "data_source_tags=VALUES(data_source_tags), priority=VALUES(priority), active=VALUES(active), updated_at=now()";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, metadata.skillName());
            ps.setString(2, nullToEmpty(metadata.shortSummary()));
            ps.setString(3, json(metadata.aliases()));
            ps.setString(4, json(metadata.keywords()));
            ps.setString(5, json(metadata.metricTags()));
            ps.setString(6, json(metadata.domainTags()));
            ps.setString(7, json(metadata.dataSourceTags()));
            ps.setInt(8, metadata.priority());
            ps.setBoolean(9, metadata.active());
            ps.executeUpdate();
            invalidateCache();
            return true;
        } catch (SQLException e) {
            log.warn("upsert skill routing metadata for {} failed: {}", metadata.skillName(), e.getMessage());
            return false;
        }
    }

    public boolean rename(String oldName, String newName) {
        ensureTable();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "UPDATE skill_routing_metadata SET skill_name = ?, updated_at = now() WHERE skill_name = ?")) {
            ps.setString(1, newName);
            ps.setString(2, oldName);
            boolean renamed = ps.executeUpdate() > 0;
            if (renamed) invalidateCache();
            return renamed;
        } catch (SQLException e) {
            log.warn("rename skill routing metadata {} -> {} failed: {}", oldName, newName, e.getMessage());
            return false;
        }
    }

    public boolean deactivate(String skillName) {
        ensureTable();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "UPDATE skill_routing_metadata SET active = FALSE, updated_at = now() WHERE skill_name = ?")) {
            ps.setString(1, skillName);
            boolean deactivated = ps.executeUpdate() > 0;
            if (deactivated) invalidateCache();
            return deactivated;
        } catch (SQLException e) {
            log.warn("deactivate skill routing metadata {} failed: {}", skillName, e.getMessage());
            return false;
        }
    }

    private void ensureTable() {
        if (tableEnsured) return;
        synchronized (this) {
            if (tableEnsured) return;
            String ddl = "CREATE TABLE IF NOT EXISTS skill_routing_metadata ("
                    + "skill_name VARCHAR(128) PRIMARY KEY, short_summary VARCHAR(3000) NOT NULL DEFAULT '',"
                    + "aliases TEXT NOT NULL DEFAULT '[]', keywords TEXT NOT NULL DEFAULT '[]',"
                    + "metric_tags TEXT NOT NULL DEFAULT '[]', domain_tags TEXT NOT NULL DEFAULT '[]',"
                    + "data_source_tags TEXT NOT NULL DEFAULT '[]',"
                    + "priority INT NOT NULL DEFAULT 0, active BOOLEAN NOT NULL DEFAULT TRUE,"
                    + "updated_at TIMESTAMP NOT NULL DEFAULT now())";
            try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(ddl)) {
                ps.execute();
                tableEnsured = true;
            } catch (SQLException e) {
                log.warn("skill_routing_metadata DDL failed (will retry): {}", e.getMessage());
            }
        }
    }

    private SkillRoutingMetadata map(ResultSet rs) throws SQLException {
        return new SkillRoutingMetadata(
                rs.getString("skill_name"), rs.getString("short_summary"), parse(rs.getString("aliases")),
                parse(rs.getString("keywords")), parse(rs.getString("metric_tags")),
                parse(rs.getString("domain_tags")), parse(rs.getString("data_source_tags")),
                rs.getInt("priority"), rs.getBoolean("active"),
                timestamp(rs.getTimestamp("updated_at")));
    }

    private SkillRoutingMetadataView mapView(ResultSet rs) throws SQLException {
        boolean configured = rs.getBoolean("configured");
        String summary = configured ? rs.getString("short_summary") : rs.getString("description");
        return new SkillRoutingMetadataView(rs.getString("name"), rs.getString("description"), summary,
                configured ? parse(rs.getString("aliases")) : List.of(), configured ? parse(rs.getString("keywords")) : List.of(),
                configured ? parse(rs.getString("metric_tags")) : List.of(), configured ? parse(rs.getString("domain_tags")) : List.of(),
                configured ? parse(rs.getString("data_source_tags")) : List.of(), configured ? rs.getInt("priority") : 0,
                !configured || rs.getBoolean("active"), timestamp(rs.getTimestamp("updated_at")), configured);
    }

    private List<String> parse(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            return objectMapper.readValue(value, STRING_LIST);
        } catch (Exception e) {
            log.warn("Invalid skill routing metadata JSON array: {}", e.getMessage());
            return List.of();
        }
    }

    private String json(List<String> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot serialize skill routing metadata", e);
        }
    }

    private static LocalDateTime timestamp(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
