package com.agentscopea2a.v2.dimension;

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
 * 维度家族映射仓储（表 {@code dimension_family_config}，建在 gaussCustomerDataSource），
 * 照搬 {@link DimensionKeyConfigRepository} 的 ensureTable + TTL 快照缓存模式
 * （docs/202611/同级维度消解-维度提及无值快照删除与词表冲突方案.md §3.2）。
 *
 * <p>表首次创建时预置 17 行初始映射（双词表收敛：drillDown 值 组维度/... 与 dimension
 * 值 部门/... 同轴收敛到 dim 家族）。存量表启动做存在性补种（完全无该 value 才插入，
 * 软删不复活）。读失败时沿用上次快照（消解判定在追问热路径上）。
 */
public class DimensionFamilyConfigRepository {

    private static final Logger log = LoggerFactory.getLogger(DimensionFamilyConfigRepository.class);

    /** family 代码闭集：对应 extraction 细粒度字段，不能发明新家族。 */
    public static final List<String> ALLOWED_FAMILIES = List.of(
            "groupName", "appName", "productLine", "perName", "perNo", "perRole", "deptName");

    /** 空表初始映射（value → family），双词表收敛 seed（方案 §3.2 表）。 */
    private static final Map<String, String> SEED_ROWS = buildSeedRows();

    private static Map<String, String> buildSeedRows() {
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("组维度", "groupName");
        rows.put("应用维度", "appName");
        rows.put("产品线维度", "productLine");
        rows.put("个人维度", "perName,perNo,perRole");
        rows.put("人维度", "perName,perNo,perRole");
        rows.put("杭研维度", "deptName");
        rows.put("部门维度", "deptName");
        rows.put("项目经理维度", "perRole");
        rows.put("产品经理维度", "perRole");
        rows.put("架构师维度", "perRole");
        rows.put("开发经理维度", "perRole");
        rows.put("测试经理维度", "perRole");
        rows.put("部门", "deptName");
        rows.put("统计组", "groupName");
        rows.put("应用", "appName");
        rows.put("产品线", "productLine");
        rows.put("人员", "perName,perNo,perRole");
        return java.util.Collections.unmodifiableMap(rows);
    }

    private final DataSource dataSource;
    private final long cacheTtlMillis;
    private volatile boolean tableEnsured;
    private volatile List<DimensionFamilyConfig> cachedEnabled;
    private volatile long lastLoadAt;

    public DimensionFamilyConfigRepository(DataSource dataSource, long cacheTtlMillis) {
        this.dataSource = dataSource;
        this.cacheTtlMillis = cacheTtlMillis;
    }

    /** 启用行（TTL 快照；失败沿用上次快照），供追问层家族消解判定。 */
    public List<DimensionFamilyConfig> findAllEnabled() {
        List<DimensionFamilyConfig> snapshot = cachedEnabled;
        if (snapshot != null && cacheTtlMillis > 0
                && System.currentTimeMillis() - lastLoadAt < cacheTtlMillis) {
            return snapshot;
        }
        ensureTable();
        List<DimensionFamilyConfig> result = new ArrayList<>();
        String sql = "SELECT id, value, family, enabled, remark "
                + "FROM dimension_family_config WHERE enabled ORDER BY id";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(map(rs));
            }
            cachedEnabled = List.copyOf(result);
            lastLoadAt = System.currentTimeMillis();
            return cachedEnabled;
        } catch (SQLException e) {
            log.warn("findAllEnabled dimension_family_config failed (keep last snapshot): {}", e.getMessage());
            return snapshot != null ? snapshot : List.copyOf(result);
        }
    }

    /** 管理 API 全量行（含 enabled=false），绕过缓存直接读库。 */
    public List<DimensionFamilyConfig> findAllForAdmin() {
        ensureTable();
        List<DimensionFamilyConfig> result = new ArrayList<>();
        String sql = "SELECT id, value, family, enabled, remark FROM dimension_family_config ORDER BY id";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(map(rs));
            }
        } catch (SQLException e) {
            log.warn("findAllForAdmin dimension_family_config failed: {}", e.getMessage());
        }
        return result;
    }

    public boolean insert(DimensionFamilyConfig row) {
        ensureTable();
        String sql = "INSERT INTO dimension_family_config (value, family, enabled, remark) "
                + "VALUES (?, ?, ?, ?)";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, row);
            boolean ok = ps.executeUpdate() > 0;
            if (ok) invalidateCache();
            return ok;
        } catch (SQLException e) {
            log.warn("insert dimension_family_config [{}] failed: {}", row.value(), e.getMessage());
            return false;
        }
    }

    public boolean update(DimensionFamilyConfig row) {
        ensureTable();
        String sql = "UPDATE dimension_family_config SET value=?, family=?, enabled=?, remark=?, "
                + "updated_at=now() WHERE id=?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, row.value());
            ps.setString(2, row.family());
            ps.setBoolean(3, row.enabled());
            ps.setString(4, row.remark());
            ps.setLong(5, row.id());
            boolean ok = ps.executeUpdate() > 0;
            if (ok) invalidateCache();
            return ok;
        } catch (SQLException e) {
            log.warn("update dimension_family_config id={} failed: {}", row.id(), e.getMessage());
            return false;
        }
    }

    /** 软删：enabled=false。 */
    public boolean setEnabled(long id, boolean enabled) {
        ensureTable();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "UPDATE dimension_family_config SET enabled=?, updated_at=now() WHERE id=?")) {
            ps.setBoolean(1, enabled);
            ps.setLong(2, id);
            boolean ok = ps.executeUpdate() > 0;
            if (ok) invalidateCache();
            return ok;
        } catch (SQLException e) {
            log.warn("setEnabled dimension_family_config id={} failed: {}", id, e.getMessage());
            return false;
        }
    }

    /** Drops the TTL snapshot so the next read hits the database. */
    public void invalidateCache() {
        cachedEnabled = null;
        lastLoadAt = 0;
    }

    private void bind(PreparedStatement ps, DimensionFamilyConfig row) throws SQLException {
        ps.setString(1, row.value());
        ps.setString(2, row.family());
        ps.setBoolean(3, row.enabled());
        ps.setString(4, row.remark());
    }

    private DimensionFamilyConfig map(ResultSet rs) throws SQLException {
        return new DimensionFamilyConfig(
                rs.getLong("id"), rs.getString("value"), rs.getString("family"),
                rs.getBoolean("enabled"), rs.getString("remark"));
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
                } else {
                    ensureSeedRows(c);
                }
                tableEnsured = true;
            } catch (SQLException e) {
                log.warn("dimension_family_config DDL failed (will retry): {}", e.getMessage());
            }
        }
    }

    /** 存量表存在性补种：完全无该 value 的行才插入（软删/改过 family 不复活）。 */
    private void ensureSeedRows(Connection c) throws SQLException {
        java.util.Set<String> existing = new java.util.HashSet<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT value FROM dimension_family_config");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                existing.add(rs.getString(1));
            }
        }
        String sql = "INSERT INTO dimension_family_config (value, family, enabled, remark) "
                + "VALUES (?, ?, TRUE, ?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (Map.Entry<String, String> entry : SEED_ROWS.entrySet()) {
                if (existing.contains(entry.getKey())) {
                    continue;
                }
                ps.setString(1, entry.getKey());
                ps.setString(2, entry.getValue());
                ps.setString(3, "初始映射（内置 seed）");
                ps.executeUpdate();
                log.info("dimension_family_config: backfilled seed row '{}'", entry.getKey());
            }
        }
    }

    /** 通过探测查询判存，避免依赖 openGauss 的 information_schema 方言差异。 */
    private boolean tableExists(Connection c) {
        try (Statement st = c.createStatement()) {
            st.execute("SELECT 1 FROM dimension_family_config LIMIT 1");
            return true;
        } catch (SQLException notExists) {
            return false;
        }
    }

    private void createTable(Connection c) throws SQLException {
        String ddl = "CREATE TABLE IF NOT EXISTS dimension_family_config ("
                + "id BIGSERIAL PRIMARY KEY, "
                + "value VARCHAR(64) NOT NULL UNIQUE, "
                + "family VARCHAR(128) NOT NULL, "
                + "enabled BOOLEAN NOT NULL DEFAULT TRUE, "
                + "remark VARCHAR(255), "
                + "created_at TIMESTAMP NOT NULL DEFAULT now(), "
                + "updated_at TIMESTAMP NOT NULL DEFAULT now())";
        try (Statement st = c.createStatement()) {
            st.execute(ddl);
        }
    }

    private void seed(Connection c) throws SQLException {
        String sql = "INSERT INTO dimension_family_config (value, family, enabled, remark) "
                + "VALUES (?, ?, TRUE, ?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (Map.Entry<String, String> entry : SEED_ROWS.entrySet()) {
                ps.setString(1, entry.getKey());
                ps.setString(2, entry.getValue());
                ps.setString(3, "初始映射（内置 seed）");
                ps.addBatch();
            }
            ps.executeBatch();
        }
        log.info("dimension_family_config created and seeded with {} rows", SEED_ROWS.size());
    }
}
