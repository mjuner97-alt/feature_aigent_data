package com.agentscopea2a.v2.skills;

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
import java.util.Map;
import java.util.Optional;

/** OpenGauss-backed runtime routing metadata repository. */
public class SkillRoutingMetadataRepository {

    private static final Logger log = LoggerFactory.getLogger(SkillRoutingMetadataRepository.class);
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final long cacheTtlMillis;
    private final SkillRoutingTagDictionary tagDictionary;
    private volatile boolean tableEnsured;
    private volatile List<SkillRoutingMetadata> cachedAll;
    private volatile long cachedAt;

    public SkillRoutingMetadataRepository(DataSource dataSource, ObjectMapper objectMapper) {
        this(dataSource, objectMapper, 0L);
    }

    public SkillRoutingMetadataRepository(DataSource dataSource, ObjectMapper objectMapper, long cacheTtlMillis) {
        this(dataSource, objectMapper, cacheTtlMillis, null);
    }

    /** 校验领域/业务主题标签须在 Skill 侧独立词典中登记且启用; 词典为空时退化为自由填写。 */
    public SkillRoutingMetadataRepository(DataSource dataSource, ObjectMapper objectMapper, long cacheTtlMillis,
                                          SkillRoutingTagDictionary tagDictionary) {
        this.dataSource = new org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy(dataSource);
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
        String sql = "SELECT skill_name, short_summary, keywords, domain_tags, topic_tags, maintainer AS creator, "
                + "active, updated_at, level1_keywords, level2_keywords, level3_keywords, script_id, param_mapping "
                + "FROM skill_routing_metadata ORDER BY skill_name";
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
        String sql = "SELECT skill_name, short_summary, keywords, domain_tags, topic_tags, maintainer AS creator, "
                + "active, updated_at, level1_keywords, level2_keywords, level3_keywords, script_id, param_mapping "
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
        StringBuilder sql = new StringBuilder("SELECT x.name, x.description, r.short_summary, r.keywords, r.domain_tags, r.topic_tags, "
                + "r.level1_keywords, r.level2_keywords, r.level3_keywords, r.script_id, r.param_mapping, "
                + creatorExpr + " AS creator, r.active, r.updated_at, r.skill_name IS NOT NULL configured FROM (SELECT s.retrieval_name AS name, s.description, s.owner_user_id FROM skill_manage s WHERE s.retrieval_name IS NOT NULL AND s.status='ACTIVE' AND s.deleted_at IS NULL UNION SELECT i.name, i.description, NULL AS owner_user_id FROM skill_index i WHERE i.status='active' AND NOT EXISTS (SELECT 1 FROM skill_manage s2 WHERE s2.retrieval_name=i.name AND s2.status='ACTIVE' AND s2.deleted_at IS NULL)) x LEFT JOIN skill_routing_metadata r ON r.skill_name=x.name WHERE 1=1");
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

    /** Same scope/filters as {@link #findAllWithSkillManage}, but counted in the database. */
    public int countAllWithSkillManage(String keyword, Boolean active, boolean mine, String userId) {
        ensureTable();
        String creatorExpr = "CASE WHEN x.owner_user_id IS NULL OR TRIM(x.owner_user_id) = '' THEN '通用' ELSE x.owner_user_id END";
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM (SELECT s.retrieval_name AS name, s.description, s.owner_user_id FROM skill_manage s WHERE s.retrieval_name IS NOT NULL AND s.status='ACTIVE' AND s.deleted_at IS NULL UNION SELECT i.name, i.description, NULL AS owner_user_id FROM skill_index i WHERE i.status='active' AND NOT EXISTS (SELECT 1 FROM skill_manage s2 WHERE s2.retrieval_name=i.name AND s2.status='ACTIVE' AND s2.deleted_at IS NULL)) x LEFT JOIN skill_routing_metadata r ON r.skill_name=x.name WHERE 1=1");
        if (keyword != null && !keyword.isBlank()) sql.append(" AND (LOWER(x.name) LIKE ? OR LOWER(COALESCE(x.description,'')) LIKE ? OR LOWER(" + creatorExpr + ") LIKE ?)");
        if (active != null) sql.append(" AND COALESCE(r.active, TRUE)=?");
        if (mine) {
            if (userId == null || userId.isBlank()) return 0;
            sql.append(" AND LOWER(" + creatorExpr + ") = ?");
        }
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int p = 1;
            if (keyword != null && !keyword.isBlank()) { String q = "%" + keyword.trim().toLowerCase() + "%"; ps.setString(p++, q); ps.setString(p++, q); ps.setString(p++, q); }
            if (active != null) ps.setBoolean(p++, active);
            if (mine) ps.setString(p++, userId == null ? "" : userId.trim().toLowerCase());
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        } catch (SQLException e) { log.warn("countAllWithSkillManage failed: {}", e.getMessage()); return 0; }
    }

    /** Loads one configurable Skill from skill_manage with optional routing metadata. */
    public Optional<SkillRoutingMetadataView> findOneWithSkillManage(String skillName) {
        ensureTable();
        String sql = "SELECT x.name, x.description, r.short_summary, r.keywords, r.domain_tags, r.topic_tags, "
                + "r.level1_keywords, r.level2_keywords, r.level3_keywords, r.script_id, r.param_mapping, "
                + "CASE WHEN x.owner_user_id IS NULL OR TRIM(x.owner_user_id) = '' THEN '通用' ELSE x.owner_user_id END AS creator, r.active, r.updated_at, r.skill_name IS NOT NULL configured FROM (SELECT s.retrieval_name AS name, s.description, s.owner_user_id FROM skill_manage s WHERE s.retrieval_name IS NOT NULL AND s.status='ACTIVE' AND s.deleted_at IS NULL UNION SELECT i.name, i.description, NULL AS owner_user_id FROM skill_index i WHERE i.status='active' AND NOT EXISTS (SELECT 1 FROM skill_manage s2 WHERE s2.retrieval_name=i.name AND s2.status='ACTIVE' AND s2.deleted_at IS NULL)) x LEFT JOIN skill_routing_metadata r ON r.skill_name=x.name WHERE x.name=?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, skillName);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Optional.of(mapView(rs)) : Optional.empty(); }
        } catch (SQLException e) { log.warn("findOneWithSkillIndex({}) failed: {}", skillName, e.getMessage()); return Optional.empty(); }
    }

    public boolean upsert(SkillRoutingMetadata metadata) {
        if (tagDictionary != null) {
            tagDictionary.validateEnabled(ToolRoutingTagType.DOMAIN, metadata.domainTags());
            tagDictionary.validateEnabled(ToolRoutingTagType.TOPIC, metadata.topicTags());
        }
        ensureTable();
        String sql = "INSERT INTO skill_routing_metadata "
                + "(skill_name, short_summary, keywords, domain_tags, topic_tags, maintainer, active, updated_at, "
                + "level1_keywords, level2_keywords, level3_keywords, script_id, param_mapping) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, now(), ?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE short_summary=VALUES(short_summary), "
                + "keywords=VALUES(keywords), domain_tags=VALUES(domain_tags), topic_tags=VALUES(topic_tags), "
                + "maintainer=VALUES(maintainer), "
                + "active=VALUES(active), updated_at=now(), "
                + "level1_keywords=VALUES(level1_keywords), level2_keywords=VALUES(level2_keywords), "
                + "level3_keywords=VALUES(level3_keywords), script_id=VALUES(script_id), "
                + "param_mapping=VALUES(param_mapping)";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, metadata.skillName());
            ps.setString(2, nullToEmpty(metadata.shortSummary()));
            ps.setString(3, json(metadata.keywords()));
            ps.setString(4, json(metadata.domainTags()));
            ps.setString(5, json(metadata.topicTags()));
            ps.setString(6, metadata.creator());
            ps.setBoolean(7, metadata.active());
            ps.setString(8, json(metadata.level1Keywords()));
            ps.setString(9, json(metadata.level2Keywords()));
            ps.setString(10, json(metadata.level3Keywords()));
            ps.setString(11, metadata.scriptId());
            ps.setString(12, metadata.paramMapping());
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
                // 分级关键词层层下钻 (2026/10/13): L1 最宽泛 → L3 最具体, keywords 仍为叶层;
                // 存量行回填 '[]' 后走原单层语义, 行为不变。
                ensureColumn(c, "level1_keywords TEXT NOT NULL DEFAULT '[]'");
                ensureColumn(c, "level2_keywords TEXT NOT NULL DEFAULT '[]'");
                ensureColumn(c, "level3_keywords TEXT NOT NULL DEFAULT '[]'");
                // Skill 直接执行 (2026/10/13): script_id NULL = 不参与直接执行 (天然开关);
                // param_mapping JSON 对象 key=脚本参数名 value=维度键。两列可空——该库 '' 视为 NULL。
                ensureColumn(c, "script_id VARCHAR(128)");
                ensureColumn(c, "param_mapping TEXT");
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
                parse(rs.getString("domain_tags")), parse(rs.getString("topic_tags")),
                rs.getString("creator"),
                rs.getBoolean("active"),
                timestamp(rs.getTimestamp("updated_at")),
                parse(rs.getString("level1_keywords")), parse(rs.getString("level2_keywords")),
                parse(rs.getString("level3_keywords")),
                rs.getString("script_id"), rs.getString("param_mapping"));
    }

    private SkillRoutingMetadataView mapView(ResultSet rs) throws SQLException {
        boolean configured = rs.getBoolean("configured");
        String summary = configured ? rs.getString("short_summary") : rs.getString("description");
        return new SkillRoutingMetadataView(rs.getString("name"), rs.getString("description"), summary,
                configured ? parse(rs.getString("keywords")) : List.of(), configured ? parse(rs.getString("domain_tags")) : List.of(),
                configured ? parse(rs.getString("topic_tags")) : List.of(),
                rs.getString("creator"),
                !configured || rs.getBoolean("active"), timestamp(rs.getTimestamp("updated_at")), configured,
                configured ? parse(rs.getString("level1_keywords")) : List.of(),
                configured ? parse(rs.getString("level2_keywords")) : List.of(),
                configured ? parse(rs.getString("level3_keywords")) : List.of(),
                configured ? rs.getString("script_id") : null,
                configured ? parseParamMapping(rs.getString("param_mapping")) : Map.of());
    }

    /** param_mapping 存 JSON 对象字符串; 解析失败降级空 map (编辑弹窗可重配, 不炸页面)。 */
    private Map<String, String> parseParamMapping(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try {
            Map<String, String> parsed = objectMapper.readValue(value,
                    new TypeReference<Map<String, String>>() {});
            return parsed == null ? Map.of() : Map.copyOf(parsed);
        } catch (Exception e) {
            log.warn("Invalid skill_routing_metadata param_mapping JSON: {}", e.getMessage());
            return Map.of();
        }
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
