package com.agentscopea2a.v2.dimension;

import com.agentscopea2a.v2.dimension.DimensionState.PeerDimensionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 维度同义词配置仓储（表 {@code dimension_alias}，建在 gaussCustomerDataSource，
 * 与 skill_index / skill_routing_metadata 同库），照搬
 * {@code SkillRoutingMetadataRepository} 的 ensureTable + TTL 快照缓存模式。
 *
 * <p>表首次创建时自动灌入 {@link DimensionAliasSeed#builtin()} 种子数据；
 * 读失败时沿用上次快照（解析链路在每轮请求热路径上，不能因 DB 抖动把别名全清空）。
 */
public class DimensionAliasRepository {

    private static final Logger log = LoggerFactory.getLogger(DimensionAliasRepository.class);

    private final DataSource dataSource;
    private final long cacheTtlMillis;
    private volatile boolean tableEnsured;
    private volatile List<DimensionAlias> cachedAll;

    public DimensionAliasRepository(DataSource dataSource, long cacheTtlMillis) {
        this.dataSource = dataSource;
        this.cacheTtlMillis = cacheTtlMillis;
    }

    /**
     * 全量启用行（含跨维度同词的多行），供 {@link AliasResolver} 建索引。
     * 读走 TTL 快照；失败沿用上次快照。
     */
    public List<DimensionAlias> findAllEnabled() {
        List<DimensionAlias> snapshot = cachedAll;
        if (snapshot != null && cacheTtlMillis > 0
                && System.currentTimeMillis() - lastLoadAt() < cacheTtlMillis) {
            return snapshot;
        }
        ensureTable();
        List<DimensionAlias> result = new ArrayList<>();
        String sql = "SELECT id, dimension, alias, standard_name, trigger_keyword, enabled, remark "
                + "FROM dimension_alias ORDER BY id";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                DimensionAlias row = map(rs);
                if (row != null) result.add(row);
            }
            cachedAll = List.copyOf(result);
            lastLoadAt = System.currentTimeMillis();
            return cachedAll;
        } catch (SQLException e) {
            log.warn("findAll dimension_alias failed (keep last snapshot): {}", e.getMessage());
            return snapshot != null ? snapshot : List.copyOf(result);
        }
    }

    /** 管理 API 全量行（含 enabled=false），绕过缓存直接读库。 */
    public List<DimensionAlias> findAllForAdmin() {
        ensureTable();
        List<DimensionAlias> result = new ArrayList<>();
        String sql = "SELECT id, dimension, alias, standard_name, trigger_keyword, enabled, remark "
                + "FROM dimension_alias ORDER BY dimension, alias";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                DimensionAlias row = map(rs);
                if (row != null) result.add(row);
            }
        } catch (SQLException e) {
            log.warn("findAllForAdmin dimension_alias failed: {}", e.getMessage());
        }
        return result;
    }

    public boolean insert(DimensionAlias row) {
        ensureTable();
        String sql = "INSERT INTO dimension_alias "
                + "(dimension, alias, standard_name, trigger_keyword, enabled, remark) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, row);
            boolean ok = ps.executeUpdate() > 0;
            if (ok) invalidateCache();
            return ok;
        } catch (SQLException e) {
            log.warn("insert dimension_alias [{}/{}] failed: {}", row.dimension(), row.alias(), e.getMessage());
            return false;
        }
    }

    public boolean update(DimensionAlias row) {
        ensureTable();
        String sql = "UPDATE dimension_alias SET dimension=?, alias=?, standard_name=?, "
                + "trigger_keyword=?, enabled=?, remark=?, updated_at=now() WHERE id=?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, row);
            ps.setLong(7, row.id());
            boolean ok = ps.executeUpdate() > 0;
            if (ok) invalidateCache();
            return ok;
        } catch (SQLException e) {
            log.warn("update dimension_alias id={} failed: {}", row.id(), e.getMessage());
            return false;
        }
    }

    /** 软删：enabled=false，默认不物理删。 */
    public boolean setEnabled(long id, boolean enabled) {
        ensureTable();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "UPDATE dimension_alias SET enabled=?, updated_at=now() WHERE id=?")) {
            ps.setBoolean(1, enabled);
            ps.setLong(2, id);
            boolean ok = ps.executeUpdate() > 0;
            if (ok) invalidateCache();
            return ok;
        } catch (SQLException e) {
            log.warn("setEnabled dimension_alias id={} failed: {}", id, e.getMessage());
            return false;
        }
    }

    /** Drops the TTL snapshot so the next read hits the database. */
    public void invalidateCache() {
        cachedAll = null;
        lastLoadAt = 0;
    }

    private volatile long lastLoadAt;

    private long lastLoadAt() {
        return lastLoadAt;
    }

    private void bind(PreparedStatement ps, DimensionAlias row) throws SQLException {
        ps.setString(1, row.dimension().name());
        ps.setString(2, row.alias());
        ps.setString(3, row.standardName());
        ps.setString(4, row.triggerKeyword());
        ps.setBoolean(5, row.enabled());
        ps.setString(6, row.remark());
    }

    private DimensionAlias map(ResultSet rs) throws SQLException {
        PeerDimensionType dimension;
        try {
            dimension = PeerDimensionType.valueOf(rs.getString("dimension"));
        } catch (IllegalArgumentException badEnum) {
            log.warn("Unknown dimension_alias.dimension={}, row skipped", rs.getString("dimension"));
            return null;
        }
        return new DimensionAlias(
                rs.getLong("id"), dimension, rs.getString("alias"), rs.getString("standard_name"),
                rs.getString("trigger_keyword"), rs.getBoolean("enabled"), rs.getString("remark"));
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
                log.warn("dimension_alias DDL failed (will retry): {}", e.getMessage());
            }
        }
    }

    /** 通过探测查询判存，避免依赖 openGauss 的 information_schema 方言差异。 */
    private boolean tableExists(Connection c) {
        try (Statement st = c.createStatement()) {
            st.execute("SELECT 1 FROM dimension_alias LIMIT 1");
            return true;
        } catch (SQLException notExists) {
            return false;
        }
    }

    private void createTable(Connection c) throws SQLException {
        String ddl = "CREATE TABLE IF NOT EXISTS dimension_alias ("
                + "id BIGSERIAL PRIMARY KEY, "
                + "dimension VARCHAR(16) NOT NULL, "
                + "alias VARCHAR(64) NOT NULL, "
                + "standard_name VARCHAR(128) NOT NULL, "
                + "trigger_keyword VARCHAR(64), "
                + "enabled BOOLEAN NOT NULL DEFAULT TRUE, "
                + "remark VARCHAR(255), "
                + "created_at TIMESTAMP NOT NULL DEFAULT now(), "
                + "updated_at TIMESTAMP NOT NULL DEFAULT now(), "
                + "CONSTRAINT uk_dim_alias_std UNIQUE (dimension, alias, standard_name))";
        try (Statement st = c.createStatement()) {
            st.execute(ddl);
        }
    }

    private void seed(Connection c) throws SQLException {
        String sql = "INSERT INTO dimension_alias "
                + "(dimension, alias, standard_name, trigger_keyword, enabled, remark) "
                + "VALUES (?, ?, ?, ?, TRUE, ?)";
        List<DimensionAlias> rows = DimensionAliasSeed.builtin();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (DimensionAlias row : rows) {
                ps.setString(1, row.dimension().name());
                ps.setString(2, row.alias());
                ps.setString(3, row.standardName());
                ps.setString(4, row.triggerKeyword());
                ps.setString(5, row.remark());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        log.info("dimension_alias created and seeded with {} builtin rows", rows.size());
    }
}
