package com.agentscopea2a.v2.dimension;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 维度键字典仓储（表 {@code dimension_key_config}，建在 gaussCustomerDataSource，
 * 与 dimension_alias 同库），照搬 {@link DimensionAliasRepository} 的
 * ensureTable + TTL 快照缓存模式（docs/history/skill-direct-exec-plan.md §9.2）。
 *
 * <p>表首次创建时自动灌入内置 8 键种子行（builtin=true，aliases 为空数组——内置键
 * 提取走 DimensionStateManager 结构化规则，不走词表；seed 键集与
 * {@code DirectSkillExecutor.DIMENSION_KEYS} 同源，两处需同步维护）。
 * 读失败时沿用上次快照（param_mapping 保存校验与直接执行提取都在热路径上）。
 */
public class DimensionKeyConfigRepository {

    private static final Logger log = LoggerFactory.getLogger(DimensionKeyConfigRepository.class);

    private static final TypeReference<List<DimensionKeyConfig.AliasMapping>> ALIAS_LIST_TYPE =
            new TypeReference<>() {};

    /** 内置 8 键 seed（key → 展示名），与 DirectSkillExecutor.DIMENSION_KEYS 同源。 */
    private static final Map<String, String> BUILTIN_KEYS = buildBuiltinKeys();

    private static Map<String, String> buildBuiltinKeys() {
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put("versionPlan", "版本计划");
        keys.put("vestQuarter", "季度");
        keys.put("deptName", "部门");
        keys.put("groupName", "组（团队）");
        keys.put("appName", "应用");
        keys.put("productLine", "产品线");
        keys.put("itemNo", "需求项");
        keys.put("perName", "人");
        return java.util.Collections.unmodifiableMap(keys);
    }

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final long cacheTtlMillis;
    private volatile boolean tableEnsured;
    private volatile List<DimensionKeyConfig> cachedEnabled;

    public DimensionKeyConfigRepository(DataSource dataSource, ObjectMapper objectMapper, long cacheTtlMillis) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
        this.cacheTtlMillis = cacheTtlMillis;
    }

    /** 启用行（TTL 快照；失败沿用上次快照），供直接执行提取与保存校验。 */
    public List<DimensionKeyConfig> findAllEnabled() {
        List<DimensionKeyConfig> snapshot = cachedEnabled;
        if (snapshot != null && cacheTtlMillis > 0
                && System.currentTimeMillis() - lastLoadAt < cacheTtlMillis) {
            return snapshot;
        }
        ensureTable();
        List<DimensionKeyConfig> result = new ArrayList<>();
        String sql = "SELECT id, key, label, builtin, aliases, enabled, remark "
                + "FROM dimension_key_config WHERE enabled ORDER BY id";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                DimensionKeyConfig row = map(rs);
                if (row != null) result.add(row);
            }
            cachedEnabled = List.copyOf(result);
            lastLoadAt = System.currentTimeMillis();
            return cachedEnabled;
        } catch (SQLException e) {
            log.warn("findAllEnabled dimension_key_config failed (keep last snapshot): {}", e.getMessage());
            return snapshot != null ? snapshot : List.copyOf(result);
        }
    }

    /** 管理 API 全量行（含 enabled=false），绕过缓存直接读库。 */
    public List<DimensionKeyConfig> findAllForAdmin() {
        ensureTable();
        List<DimensionKeyConfig> result = new ArrayList<>();
        String sql = "SELECT id, key, label, builtin, aliases, enabled, remark "
                + "FROM dimension_key_config ORDER BY builtin DESC, id";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                DimensionKeyConfig row = map(rs);
                if (row != null) result.add(row);
            }
        } catch (SQLException e) {
            log.warn("findAllForAdmin dimension_key_config failed: {}", e.getMessage());
        }
        return result;
    }

    public boolean insert(DimensionKeyConfig row) {
        ensureTable();
        String sql = "INSERT INTO dimension_key_config (key, label, builtin, aliases, enabled, remark) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, row);
            boolean ok = ps.executeUpdate() > 0;
            if (ok) invalidateCache();
            return ok;
        } catch (SQLException e) {
            log.warn("insert dimension_key_config [{}] failed: {}", row.key(), e.getMessage());
            return false;
        }
    }

    public boolean update(DimensionKeyConfig row) {
        ensureTable();
        String sql = "UPDATE dimension_key_config SET key=?, label=?, aliases=?, enabled=?, remark=?, "
                + "updated_at=now() WHERE id=? AND builtin=FALSE";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, row.key());
            ps.setString(2, row.label());
            ps.setString(3, serializeAliases(row.aliases()));
            ps.setBoolean(4, row.enabled());
            ps.setString(5, row.remark());
            ps.setLong(6, row.id());
            boolean ok = ps.executeUpdate() > 0;
            if (ok) invalidateCache();
            return ok;
        } catch (SQLException e) {
            log.warn("update dimension_key_config id={} failed: {}", row.id(), e.getMessage());
            return false;
        }
    }

    /** 软删：enabled=false；内置键不允许停用。 */
    public boolean setEnabled(long id, boolean enabled) {
        ensureTable();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "UPDATE dimension_key_config SET enabled=?, updated_at=now() "
                + "WHERE id=? AND (builtin=FALSE OR ?=TRUE)")) {
            ps.setBoolean(1, enabled);
            ps.setLong(2, id);
            ps.setBoolean(3, enabled);
            boolean ok = ps.executeUpdate() > 0;
            if (ok) invalidateCache();
            return ok;
        } catch (SQLException e) {
            log.warn("setEnabled dimension_key_config id={} failed: {}", id, e.getMessage());
            return false;
        }
    }

    /** Drops the TTL snapshot so the next read hits the database. */
    public void invalidateCache() {
        cachedEnabled = null;
        lastLoadAt = 0;
    }

    private volatile long lastLoadAt;

    private void bind(PreparedStatement ps, DimensionKeyConfig row) throws SQLException {
        ps.setString(1, row.key());
        ps.setString(2, row.label());
        ps.setBoolean(3, row.builtin());
        ps.setString(4, serializeAliases(row.aliases()));
        ps.setBoolean(5, row.enabled());
        ps.setString(6, row.remark());
    }

    private String serializeAliases(List<DimensionKeyConfig.AliasMapping> aliases) {
        if (aliases == null || aliases.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(aliases.stream().filter(DimensionKeyConfig.AliasMapping::valid).toList());
        } catch (Exception e) {
            throw new IllegalArgumentException("DimensionKeyAliasesInvalid: " + e.getMessage(), e);
        }
    }

    private DimensionKeyConfig map(ResultSet rs) throws SQLException {
        List<DimensionKeyConfig.AliasMapping> aliases = parseAliases(rs.getString("aliases"));
        return new DimensionKeyConfig(
                rs.getLong("id"), rs.getString("key"), rs.getString("label"),
                rs.getBoolean("builtin"), aliases, rs.getBoolean("enabled"), rs.getString("remark"));
    }

    private List<DimensionKeyConfig.AliasMapping> parseAliases(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<DimensionKeyConfig.AliasMapping> parsed = objectMapper.readValue(json, ALIAS_LIST_TYPE);
            return parsed == null ? List.of()
                    : parsed.stream().filter(a -> a != null && a.valid()).toList();
        } catch (Exception e) {
            log.warn("Invalid dimension_key_config.aliases JSON: {}", e.getMessage());
            return List.of();
        }
    }

    private void ensureTable() {
        if (tableEnsured) return;
        synchronized (this) {
            if (tableEnsured) return;
            try (Connection c = dataSource.getConnection()) {
                boolean fresh = !tableExists(c);
                if (fresh) {
                    createTable(c);
                    seed(c);
                }
                tableEnsured = true;
            } catch (SQLException e) {
                log.warn("dimension_key_config DDL failed (will retry): {}", e.getMessage());
            }
        }
    }

    /** 通过探测查询判存，避免依赖 openGauss 的 information_schema 方言差异。 */
    private boolean tableExists(Connection c) {
        try (Statement st = c.createStatement()) {
            st.execute("SELECT 1 FROM dimension_key_config LIMIT 1");
            return true;
        } catch (SQLException notExists) {
            return false;
        }
    }

    private void createTable(Connection c) throws SQLException {
        String ddl = "CREATE TABLE IF NOT EXISTS dimension_key_config ("
                + "id BIGSERIAL PRIMARY KEY, "
                + "key VARCHAR(64) NOT NULL UNIQUE, "
                + "label VARCHAR(128) NOT NULL, "
                + "builtin BOOLEAN NOT NULL DEFAULT FALSE, "
                + "aliases TEXT, "
                + "enabled BOOLEAN NOT NULL DEFAULT TRUE, "
                + "remark VARCHAR(255), "
                + "created_at TIMESTAMP NOT NULL DEFAULT now(), "
                + "updated_at TIMESTAMP NOT NULL DEFAULT now())";
        try (Statement st = c.createStatement()) {
            st.execute(ddl);
        }
    }

    private void seed(Connection c) throws SQLException {
        String sql = "INSERT INTO dimension_key_config (key, label, builtin, aliases, enabled, remark) "
                + "VALUES (?, ?, TRUE, '[]', TRUE, '内置键（提取走结构化规则，不走词表）')";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (Map.Entry<String, String> entry : BUILTIN_KEYS.entrySet()) {
                ps.setString(1, entry.getKey());
                ps.setString(2, entry.getValue());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        log.info("dimension_key_config created and seeded with {} builtin keys", BUILTIN_KEYS.size());
    }
}
