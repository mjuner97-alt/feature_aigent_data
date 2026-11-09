package com.agentscopea2a.v2.dimension;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 维度信号动态键白名单存储（表 {@code dimension_signal_config}，建在 gaussCustomerDataSource），
 * 目前仅一行 {@code config_key='followup_signal_keys'}，保存 C3 追问信号白名单 CSV
 * （默认 drillDown,dimension,timeRange）。前端维度键字典页第三 Tab 可编辑，
 * 保存后立即 {@code DirectSkillExecutor#setFollowupSignalKeys} 生效并跨重启持久化。
 *
 * <p>ensureTable + 存量补种模式同 {@link DimensionFamilyConfigRepository}。
 * 读路径仅启动注入与管理页 GET，不在追问热路径上，无 TTL 缓存。
 */
public class DimensionSignalConfigRepository {

    private static final Logger log = LoggerFactory.getLogger(DimensionSignalConfigRepository.class);

    public static final String KEY_FOLLOWUP_SIGNAL_KEYS = "followup_signal_keys";
    /** 与 DirectSkillExecutor 字段默认值 / application.properties 保持一致。 */
    public static final String DEFAULT_FOLLOWUP_SIGNAL_KEYS = "drillDown,dimension,timeRange";

    private final DataSource dataSource;
    private volatile boolean tableEnsured;

    public DimensionSignalConfigRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** 当前白名单 CSV；无行/读失败返回 null（调用方回退配置默认值）。 */
    public String loadFollowupSignalKeys() {
        ensureTable();
        String sql = "SELECT signal_keys FROM dimension_signal_config WHERE config_key = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, KEY_FOLLOWUP_SIGNAL_KEYS);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String v = rs.getString(1);
                    return (v == null || v.isBlank()) ? null : v.trim();
                }
            }
            return null;
        } catch (SQLException e) {
            log.warn("load dimension_signal_config failed: {}", e.getMessage());
            return null;
        }
    }

    /** 保存白名单 CSV（upsert），成功返回 true。 */
    public boolean saveFollowupSignalKeys(String csv) {
        ensureTable();
        String upsert = "UPDATE dimension_signal_config SET signal_keys=?, updated_at=now() "
                + "WHERE config_key=?";
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(upsert)) {
                ps.setString(1, csv);
                ps.setString(2, KEY_FOLLOWUP_SIGNAL_KEYS);
                if (ps.executeUpdate() > 0) {
                    return true;
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO dimension_signal_config (config_key, signal_keys) VALUES (?, ?)")) {
                ps.setString(1, KEY_FOLLOWUP_SIGNAL_KEYS);
                ps.setString(2, csv);
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            log.warn("save dimension_signal_config failed: {}", e.getMessage());
            return false;
        }
    }

    private void ensureTable() {
        if (tableEnsured) return;
        synchronized (this) {
            if (tableEnsured) return;
            try (Connection c = dataSource.getConnection()) {
                if (!tableExists(c)) {
                    createTable(c);
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO dimension_signal_config (config_key, signal_keys) VALUES (?, ?)")) {
                        ps.setString(1, KEY_FOLLOWUP_SIGNAL_KEYS);
                        ps.setString(2, DEFAULT_FOLLOWUP_SIGNAL_KEYS);
                        ps.executeUpdate();
                    }
                    log.info("dimension_signal_config created with seed row '{}'",
                            KEY_FOLLOWUP_SIGNAL_KEYS);
                }
                tableEnsured = true;
            } catch (SQLException e) {
                log.warn("dimension_signal_config DDL failed (will retry): {}", e.getMessage());
            }
        }
    }

    private boolean tableExists(Connection c) {
        try (Statement st = c.createStatement()) {
            st.execute("SELECT 1 FROM dimension_signal_config LIMIT 1");
            return true;
        } catch (SQLException notExists) {
            return false;
        }
    }

    private void createTable(Connection c) throws SQLException {
        String ddl = "CREATE TABLE IF NOT EXISTS dimension_signal_config ("
                + "config_key VARCHAR(64) PRIMARY KEY, "
                + "signal_keys VARCHAR(255) NOT NULL, "
                + "updated_at TIMESTAMP NOT NULL DEFAULT now())";
        try (Statement st = c.createStatement()) {
            st.execute(ddl);
        }
    }
}
