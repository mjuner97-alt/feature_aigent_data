package com.agentscopea2a.v2.skills;

import com.agentscopea2a.v2.toolrouting.ToolRoutingTagDictionary;
import com.agentscopea2a.v2.toolrouting.ToolRoutingTagType;
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
    private final ToolRoutingTagDictionary tagDictionary;
    private volatile boolean tableEnsured;
    private volatile List<SkillRoutingMetadata> cachedAll;
    private volatile long cachedAt;

    public SkillRoutingMetadataRepository(DataSource dataSource, ObjectMapper objectMapper) {
        this(dataSource, objectMapper, 0L);
    }

    public SkillRoutingMetadataRepository(DataSource dataSource, ObjectMapper objectMapper, long cacheTtlMillis) {
        this(dataSource, objectMapper, cacheTtlMillis, null);
    }

    /**
     * Uses the unified routing dictionary when tool routing is enabled; callers that do not
     * participate in the unified rollout retain the legacy free-form metadata behavior.
     */
    public SkillRoutingMetadataRepository(DataSource dataSource, ObjectMapper objectMapper, long cacheTtlMillis,
                                          ToolRoutingTagDictionary tagDictionary) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
        this.cacheTtlMillis = cacheTtlMillis;
        this.tagDictionary = tagDictionary;
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
        String sql = "SELECT skill_name, short_summary, keywords, domain_tags, topic_tags, metric_tags, maintainer AS creator, "
                + "priority, active, updated_at "
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
        String sql = "SELECT skill_name, short_summary, keywords, domain_tags, topic_tags, metric_tags, maintainer AS creator, "
                + "priority, active, updated_at "
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

    /** Resolves the immutable creator from the Skill gallery ownership field. */
    public String creatorForSkill(String skillName) {
        if ("_common".equalsIgnoreCase(skillName) || "common".equalsIgnoreCase(skillName)) {
            return "通用";
        }
        String sql = "SELECT owner_user_id FROM skill_manage WHERE retrieval_name = ? "
                + "AND status = 'ACTIVE' AND deleted_at IS NULL LIMIT 1";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, skillName);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return "通用";
                String owner = rs.getString("owner_user_id");
                return owner == null || owner.isBlank() ? "通用" : owner;
            }
        } catch (SQLException e) {
            log.warn("creatorForSkill({}) failed: {}", skillName, e.getMessage());
            return "";
        }
    }

    /** Lists active, non-deleted Skills from skill_manage with optional routing metadata. */
    public List<SkillRoutingMetadataView> findAllWithSkillManage(String keyword, Boolean active, int limit, int offset) {
        return findAllWithSkillManage(keyword, active, false, null, limit, offset);
    }

    public List<SkillRoutingMetadataView> findAllWithSkillManage(String keyword, Boolean active, boolean mine,
                                                                  String userId, int limit, int offset) {
        ensureTable();
        String creatorExpr = "CASE WHEN x.owner_user_id IS NULL OR TRIM(x.owner_user_id) = '' THEN '通用' ELSE x.owner_user_id END";
        StringBuilder sql = new StringBuilder("SELECT x.name, x.description, r.short_summary, r.keywords, r.domain_tags, r.topic_tags, r.metric_tags, "
                + creatorExpr + " AS creator, r.priority, r.active, r.updated_at, r.skill_name IS NOT NULL configured FROM (SELECT s.retrieval_name AS name, s.description, s.owner_user_id FROM skill_manage s WHERE s.retrieval_name IS NOT NULL AND s.status='ACTIVE' AND s.deleted_at IS NULL UNION SELECT i.name, i.description, NULL AS owner_user_id FROM skill_index i WHERE i.status='active' AND NOT EXISTS (SELECT 1 FROM skill_manage s2 WHERE s2.retrieval_name=i.name AND s2.status='ACTIVE' AND s2.deleted_at IS NULL)) x LEFT JOIN skill_routing_metadata r ON r.skill_name=x.name WHERE 1=1");
        if (keyword != null && !keyword.isBlank()) sql.append(" AND (LOWER(x.name) LIKE ? OR LOWER(COALESCE(x.description,'')) LIKE ? OR LOWER(" + creatorExpr + ") LIKE ?)");
        if (active != null) sql.append(" AND COALESCE(r.active, TRUE)=?");
        if (mine) {
            if (userId == null || userId.isBlank()) return List.of();
            sql.append(" AND LOWER(" + creatorExpr + ") = ?");
        }
        sql.append(" ORDER BY x.name LIMIT ? OFFSET ?");
        List<SkillRoutingMetadataView> result = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int p = 1;
            if (keyword != null && !keyword.isBlank()) { String q = "%" + keyword.trim().toLowerCase() + "%"; ps.setString(p++, q); ps.setString(p++, q); ps.setString(p++, q); }
            if (active != null) ps.setBoolean(p++, active);
            if (mine) ps.setString(p++, userId == null ? "" : userId.trim().toLowerCase());
            ps.setInt(p++, Math.max(1, Math.min(limit, 200))); ps.setInt(p, Math.max(0, offset));
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) result.add(mapView(rs)); }
        } catch (SQLException e) { log.warn("findAllWithSkillManage failed: {}", e.getMessage()); }
        return result;
    }

    /** Loads one configurable Skill from skill_manage with optional routing metadata. */
    public Optional<SkillRoutingMetadataView> findOneWithSkillManage(String skillName) {
        ensureTable();
        String sql = "SELECT x.name, x.description, r.short_summary, r.keywords, r.domain_tags, r.topic_tags, r.metric_tags, CASE WHEN x.owner_user_id IS NULL OR TRIM(x.owner_user_id) = '' THEN '通用' ELSE x.owner_user_id END AS creator, r.priority, r.active, r.updated_at, r.skill_name IS NOT NULL configured FROM (SELECT s.retrieval_name AS name, s.description, s.owner_user_id FROM skill_manage s WHERE s.retrieval_name IS NOT NULL AND s.status='ACTIVE' AND s.deleted_at IS NULL UNION SELECT i.name, i.description, NULL AS owner_user_id FROM skill_index i WHERE i.status='active' AND NOT EXISTS (SELECT 1 FROM skill_manage s2 WHERE s2.retrieval_name=i.name AND s2.status='ACTIVE' AND s2.deleted_at IS NULL)) x LEFT JOIN skill_routing_metadata r ON r.skill_name=x.name WHERE x.name=?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, skillName);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Optional.of(mapView(rs)) : Optional.empty(); }
        } catch (SQLException e) { log.warn("findOneWithSkillIndex({}) failed: {}", skillName, e.getMessage()); return Optional.empty(); }
    }

    public boolean upsert(SkillRoutingMetadata metadata) {
        if (tagDictionary != null) {
            tagDictionary.validateEnabled(ToolRoutingTagType.DOMAIN, metadata.domainTags());
            tagDictionary.validateEnabled(ToolRoutingTagType.TOPIC, metadata.topicTags());
            tagDictionary.validateEnabled(ToolRoutingTagType.METRIC, metadata.metricTags());
        }
        ensureTable();
        String sql = "INSERT INTO skill_routing_metadata "
                + "(skill_name, short_summary, keywords, domain_tags, topic_tags, metric_tags, maintainer, priority, active, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now()) "
                + "ON DUPLICATE KEY UPDATE short_summary=VALUES(short_summary), "
                + "keywords=VALUES(keywords), domain_tags=VALUES(domain_tags), topic_tags=VALUES(topic_tags), "
                + "metric_tags=VALUES(metric_tags), maintainer=VALUES(maintainer), priority=VALUES(priority), "
                + "active=VALUES(active), updated_at=now()";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, metadata.skillName());
            ps.setString(2, nullToEmpty(metadata.shortSummary()));
            ps.setString(3, json(metadata.keywords()));
            ps.setString(4, json(metadata.domainTags()));
            ps.setString(5, json(metadata.topicTags()));
            ps.setString(6, json(metadata.metricTags()));
            ps.setString(7, metadata.creator());
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

    /**
     * Hard-deletes the routing metadata row for a skill that no longer exists on disk.
     * Used by the builtin registrar's tombstone cleanup when a workspace skill (e.g. the
     * retired {@code tool_index} wrapper) is removed in a cutover release.
     */
    public boolean delete(String skillName) {
        ensureTable();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "DELETE FROM skill_routing_metadata WHERE skill_name = ?")) {
            ps.setString(1, skillName);
            boolean deleted = ps.executeUpdate() > 0;
            if (deleted) invalidateCache();
            return deleted;
        } catch (SQLException e) {
            log.warn("delete skill routing metadata {} failed: {}", skillName, e.getMessage());
            return false;
        }
    }

    private void ensureTable() {
        if (tableEnsured) return;
        synchronized (this) {
            if (tableEnsured) return;
            String ddl = "CREATE TABLE IF NOT EXISTS skill_routing_metadata ("
                    + "skill_name VARCHAR(128) PRIMARY KEY, short_summary VARCHAR(3000) NOT NULL DEFAULT '',"
                    + "keywords TEXT NOT NULL DEFAULT '[]', domain_tags TEXT NOT NULL DEFAULT '[]',"
                    + "topic_tags TEXT NOT NULL DEFAULT '[]', metric_tags TEXT NOT NULL DEFAULT '[]',"
                    + "maintainer VARCHAR(128),"
                    + "priority INT NOT NULL DEFAULT 0, active BOOLEAN NOT NULL DEFAULT TRUE,"
                    + "updated_at TIMESTAMP NOT NULL DEFAULT now())";
            try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(ddl)) {
                ps.execute();
                // V20260827.1 建的存量表没有 topic_tags/maintainer，且 flyway-core 9.22 对汇报为
                // PostgreSQL 9.2 的 openGauss 无法执行迁移，这里按运行时约定幂等补列。
                ensureColumn(c, "topic_tags TEXT NOT NULL DEFAULT '[]'");
                // 本实例 openGauss 将空字符串视为 NULL，NOT NULL DEFAULT '' 回填会报 "contains null values"，
                // 因此 maintainer 用可空列，语义上仅作展示字段。
                ensureColumn(c, "maintainer VARCHAR(128)");
                tableEnsured = true;
            } catch (SQLException e) {
                log.warn("skill_routing_metadata DDL failed (will retry): {}", e.getMessage());
            }
        }
    }

    private static void ensureColumn(Connection c, String columnDefinition) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "ALTER TABLE skill_routing_metadata ADD COLUMN " + columnDefinition)) {
            try {
                ps.execute();
            } catch (SQLException alreadyExists) {
                if (!("42701".equals(alreadyExists.getSQLState())
                        || (alreadyExists.getMessage() != null
                                && alreadyExists.getMessage().toLowerCase().contains("already exists")))) {
                    throw alreadyExists;
                }
            }
        }
    }

    private SkillRoutingMetadata map(ResultSet rs) throws SQLException {
        return new SkillRoutingMetadata(
                rs.getString("skill_name"), rs.getString("short_summary"), parse(rs.getString("keywords")),
                parse(rs.getString("domain_tags")), parse(rs.getString("topic_tags")), parse(rs.getString("metric_tags")),
                rs.getString("creator"),
                rs.getInt("priority"), rs.getBoolean("active"),
                timestamp(rs.getTimestamp("updated_at")));
    }

    private SkillRoutingMetadataView mapView(ResultSet rs) throws SQLException {
        boolean configured = rs.getBoolean("configured");
        String summary = configured ? rs.getString("short_summary") : rs.getString("description");
        return new SkillRoutingMetadataView(rs.getString("name"), rs.getString("description"), summary,
                configured ? parse(rs.getString("keywords")) : List.of(), configured ? parse(rs.getString("domain_tags")) : List.of(),
                configured ? parse(rs.getString("topic_tags")) : List.of(), configured ? parse(rs.getString("metric_tags")) : List.of(),
                rs.getString("creator"), configured ? rs.getInt("priority") : 0,
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
