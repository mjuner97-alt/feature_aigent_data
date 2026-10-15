package com.agentscopea2a.v2.skills;

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
 * 全局关键词优先级仓储（表 {@code skill_keyword_priority}，gaussCustomerDataSource），
 * 照 dimension_key_config 的 ensureTable + TTL 快照模式（拍板⑧）。
 *
 * <p>无 seed（词序由管理员在优先级页首次配置）；空表 = 功能天然关闭（排序退化为
 * 层深度 + name）。保存走整表替换（拖曳页编辑的是全量树，单行 CRUD 反而割裂），
 * sort_order 由保存的列表位次生成。读失败沿用上次快照（检索排序在热路径上）。
 */
public class SkillKeywordPriorityRepository {

    private static final Logger log = LoggerFactory.getLogger(SkillKeywordPriorityRepository.class);

    private final DataSource dataSource;
    private final long cacheTtlMillis;
    private volatile boolean tableEnsured;
    private volatile List<SkillKeywordPriority> cachedEnabled;
    private volatile long lastLoadAt;

    public SkillKeywordPriorityRepository(DataSource dataSource, long cacheTtlMillis) {
        this.dataSource = dataSource;
        this.cacheTtlMillis = cacheTtlMillis;
    }

    /** 启用行（TTL 快照；失败沿用上次快照），按 level, sort_order 有序，供 selector 排序。 */
    public List<SkillKeywordPriority> findAllEnabled() {
        List<SkillKeywordPriority> snapshot = cachedEnabled;
        if (snapshot != null && cacheTtlMillis > 0
                && System.currentTimeMillis() - lastLoadAt < cacheTtlMillis) {
            return snapshot;
        }
        ensureTable();
        List<SkillKeywordPriority> result = new ArrayList<>();
        String sql = "SELECT id, level, parent_keyword, keyword, sort_order "
                + "FROM skill_keyword_priority ORDER BY level, sort_order";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                SkillKeywordPriority row = map(rs);
                if (row != null && row.valid()) {
                    result.add(row);
                }
            }
            cachedEnabled = List.copyOf(result);
            lastLoadAt = System.currentTimeMillis();
            return cachedEnabled;
        } catch (SQLException e) {
            log.warn("findAllEnabled skill_keyword_priority failed (keep last snapshot): {}", e.getMessage());
            return snapshot != null ? snapshot : List.copyOf(result);
        }
    }

    /** 管理 API 全量行（同 findAllEnabled，当前无启停列，预留分离）。 */
    public List<SkillKeywordPriority> findAllForAdmin() {
        return findAllEnabled();
    }

    /** 整表替换保存（拖曳页全量树提交），一个事务内删旧插新，sort_order 按列表位次。 */
    public void replaceAll(List<SkillKeywordPriority> rows) {
        ensureTable();
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("DELETE FROM skill_keyword_priority");
            }
            String sql = "INSERT INTO skill_keyword_priority (level, parent_keyword, keyword, sort_order) "
                    + "VALUES (?, ?, ?, ?)";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                int sortOrder = 0;
                int lastLevel = 0;
                for (SkillKeywordPriority row : rows) {
                    if (row == null || !row.valid()) {
                        throw new IllegalArgumentException("非法行: level=" + row.level() + ", keyword=" + row.keyword());
                    }
                    if (row.level() < lastLevel) {
                        throw new IllegalArgumentException("行必须按 level 升序提交");
                    }
                    lastLevel = row.level();
                    ps.setInt(1, row.level());
                    // 该 openGauss 实例把 '' 视为 NULL，level 1 无父词必须绑 null 而非 ''
                    ps.setString(2, row.level() == 1 ? null : row.parentKeyword());
                    ps.setString(3, row.keyword());
                    ps.setInt(4, sortOrder++);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            c.commit();
        } catch (SQLException | IllegalArgumentException e) {
            invalidateCache();
            throw new IllegalStateException("skill_keyword_priority 保存失败: " + e.getMessage(), e);
        }
        invalidateCache();
    }

    /** Drops the TTL snapshot so the next read hits the database. */
    public void invalidateCache() {
        cachedEnabled = null;
        lastLoadAt = 0;
    }

    private SkillKeywordPriority map(ResultSet rs) throws SQLException {
        return new SkillKeywordPriority(rs.getLong("id"), rs.getInt("level"),
                rs.getString("parent_keyword"), rs.getString("keyword"), rs.getInt("sort_order"));
    }

    private void ensureTable() {
        if (tableEnsured) return;
        synchronized (this) {
            if (tableEnsured) return;
            try (Connection c = dataSource.getConnection()) {
                if (!tableExists(c)) {
                    createTable(c);
                    log.info("skill_keyword_priority created");
                } else {
                    relaxParentNotNull(c);
                }
                tableEnsured = true;
            } catch (SQLException e) {
                log.warn("skill_keyword_priority DDL failed (will retry): {}", e.getMessage());
            }
        }
    }

    /** 通过探测查询判存，避免依赖 openGauss 的 information_schema 方言差异。 */
    private boolean tableExists(Connection c) {
        try (Statement st = c.createStatement()) {
            st.execute("SELECT 1 FROM skill_keyword_priority LIMIT 1");
            return true;
        } catch (SQLException notExists) {
            return false;
        }
    }

    private void createTable(Connection c) throws SQLException {
        String ddl = "CREATE TABLE IF NOT EXISTS skill_keyword_priority ("
                + "id BIGSERIAL PRIMARY KEY, "
                + "level INT NOT NULL, "
                // 可空：该库 '' 视为 NULL，NOT NULL 会让 level 1 行（无父词）无法插入
                + "parent_keyword VARCHAR(128), "
                + "keyword VARCHAR(128) NOT NULL, "
                + "sort_order INT NOT NULL, "
                + "created_at TIMESTAMP NOT NULL DEFAULT now())";
        try (Statement st = c.createStatement()) {
            st.execute(ddl);
        }
    }

    /** 存量表建表时 parent_keyword 是 NOT NULL DEFAULT ''，本库 '' 即 NULL，幂等放开约束。 */
    private void relaxParentNotNull(Connection c) {
        try (Statement st = c.createStatement()) {
            st.execute("ALTER TABLE skill_keyword_priority ALTER COLUMN parent_keyword DROP NOT NULL");
        } catch (SQLException e) {
            log.warn("skill_keyword_priority relax parent_keyword NOT NULL failed: {}", e.getMessage());
        }
    }
}
