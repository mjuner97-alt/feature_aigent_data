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
 * 检索旁路词仓储（表 {@code skill_bypass_keyword}，gaussCustomerDataSource），
 * 照搬 {@link com.agentscopea2a.v2.dimension.DimensionKeyConfigRepository} 的
 * ensureTable + TTL 快照模式（docs/history/skill-retrieval-logic.md §8.2）。
 *
 * <p>表首次创建时灌入三组 seed（分析类 / 表格类 / 质量板块）；空表补种（管理端删除
 * 是软删 enabled=false，行仍在表中，NOT EXISTS 防重使软删 seed 词重启不复活）。
 * 匹配在每轮请求热路径上，读失败沿用上次快照，不阻塞不抛错。
 */
public class SkillBypassKeywordRepository {

    private static final Logger log = LoggerFactory.getLogger(SkillBypassKeywordRepository.class);

    /** 三组 seed 词（§8.1 拍板①；表格类只配「表格」，contains 天然覆盖 制作/生成/展示表格 等变体）。 */
    private static final List<SkillBypassKeyword> SEED = buildSeed();

    private static List<SkillBypassKeyword> buildSeed() {
        List<SkillBypassKeyword> seeds = new ArrayList<>();
        for (String w : new String[]{"分析", "趋势", "对比", "分布", "归因", "标准差",
                "分位数", "相关系数", "同比", "环比", "改进建议", "报告", "探索式分析"}) {
            seeds.add(new SkillBypassKeyword(null, "分析类", w, true, "偏分析问题不走 skill 检索"));
        }
        seeds.add(new SkillBypassKeyword(null, "表格类", "表格", true,
                "contains 覆盖 制作表格/生成表格/表格展示/表格输出 等变体"));
        for (String w : new String[]{"质量", "缺陷密度", "检出率", "研发问题", "D号", "适应性问题", "A号"}) {
            seeds.add(new SkillBypassKeyword(null, "质量板块", w, true, "质量板块已有原子服务"));
        }
        return List.copyOf(seeds);
    }

    private final DataSource dataSource;
    private final long cacheTtlMillis;
    private volatile boolean tableEnsured;
    private volatile List<SkillBypassKeyword> cachedEnabled;
    private volatile long lastLoadAt;

    public SkillBypassKeywordRepository(DataSource dataSource, long cacheTtlMillis) {
        this.dataSource = dataSource;
        this.cacheTtlMillis = cacheTtlMillis;
    }

    /** 启用行（TTL 快照；失败沿用上次快照），供旁路判定热路径。 */
    public List<SkillBypassKeyword> findAllEnabled() {
        List<SkillBypassKeyword> snapshot = cachedEnabled;
        if (snapshot != null && cacheTtlMillis > 0
                && System.currentTimeMillis() - lastLoadAt < cacheTtlMillis) {
            return snapshot;
        }
        ensureTable();
        List<SkillBypassKeyword> result = new ArrayList<>();
        String sql = "SELECT id, group_name, keyword, enabled, remark "
                + "FROM skill_bypass_keyword WHERE enabled ORDER BY id";
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
            log.warn("findAllEnabled skill_bypass_keyword failed (keep last snapshot): {}", e.getMessage());
            return snapshot != null ? snapshot : List.copyOf(result);
        }
    }

    /** 管理 API 全量行（含 enabled=false），绕过缓存直接读库。 */
    public List<SkillBypassKeyword> findAllForAdmin() {
        ensureTable();
        List<SkillBypassKeyword> result = new ArrayList<>();
        String sql = "SELECT id, group_name, keyword, enabled, remark "
                + "FROM skill_bypass_keyword ORDER BY group_name, id";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(map(rs));
            }
        } catch (SQLException e) {
            log.warn("findAllForAdmin skill_bypass_keyword failed: {}", e.getMessage());
        }
        return result;
    }

    public boolean insert(SkillBypassKeyword row) {
        ensureTable();
        String sql = "INSERT INTO skill_bypass_keyword (group_name, keyword, enabled, remark) VALUES (?, ?, ?, ?)";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, row.groupName());
            ps.setString(2, row.keyword());
            ps.setBoolean(3, row.enabled());
            ps.setString(4, row.remark());
            boolean ok = ps.executeUpdate() > 0;
            if (ok) invalidateCache();
            return ok;
        } catch (SQLException e) {
            log.warn("insert skill_bypass_keyword [{}] failed: {}", row.keyword(), e.getMessage());
            return false;
        }
    }

    public boolean update(SkillBypassKeyword row) {
        ensureTable();
        String sql = "UPDATE skill_bypass_keyword SET group_name=?, keyword=?, enabled=?, remark=? WHERE id=?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, row.groupName());
            ps.setString(2, row.keyword());
            ps.setBoolean(3, row.enabled());
            ps.setString(4, row.remark());
            ps.setLong(5, row.id());
            boolean ok = ps.executeUpdate() > 0;
            if (ok) invalidateCache();
            return ok;
        } catch (SQLException e) {
            log.warn("update skill_bypass_keyword id={} failed: {}", row.id(), e.getMessage());
            return false;
        }
    }

    /** 软删：enabled=false（与 seed 不复活口径配套——软删行仍在表中，重启不补种）。 */
    public boolean setEnabled(long id, boolean enabled) {
        ensureTable();
        String sql = "UPDATE skill_bypass_keyword SET enabled=? WHERE id=?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setBoolean(1, enabled);
            ps.setLong(2, id);
            boolean ok = ps.executeUpdate() > 0;
            if (ok) invalidateCache();
            return ok;
        } catch (SQLException e) {
            log.warn("setEnabled skill_bypass_keyword id={} failed: {}", id, e.getMessage());
            return false;
        }
    }

    /** Drops the TTL snapshot so the next read hits the database. */
    public void invalidateCache() {
        cachedEnabled = null;
        lastLoadAt = 0;
    }

    private SkillBypassKeyword map(ResultSet rs) throws SQLException {
        return new SkillBypassKeyword(rs.getLong("id"), rs.getString("group_name"),
                rs.getString("keyword"), rs.getBoolean("enabled"), rs.getString("remark"));
    }

    private void ensureTable() {
        if (tableEnsured) return;
        synchronized (this) {
            if (tableEnsured) return;
            try (Connection c = dataSource.getConnection()) {
                boolean fresh = !tableExists(c);
                if (fresh) {
                    createTable(c);
                }
                // 空表只可能来自「建表成功但 seed 失败」的残留（管理端删除是软删
                // enabled=false，行仍在表中，永远清不空表），补种即自愈；
                // seed 带 NOT EXISTS 防重，并发/部分残留下也只补缺失词。
                if (fresh || isEmpty(c)) {
                    seed(c);
                }
                tableEnsured = true;
            } catch (SQLException e) {
                log.warn("skill_bypass_keyword DDL failed (will retry): {}", e.getMessage());
            }
        }
    }

    /** 通过探测查询判存，避免依赖 openGauss 的 information_schema 方言差异。 */
    private boolean tableExists(Connection c) {
        try (Statement st = c.createStatement()) {
            st.execute("SELECT 1 FROM skill_bypass_keyword LIMIT 1");
            return true;
        } catch (SQLException notExists) {
            return false;
        }
    }

    private boolean isEmpty(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT 1 FROM skill_bypass_keyword LIMIT 1")) {
            return !rs.next();
        }
    }

    private void createTable(Connection c) throws SQLException {
        String ddl = "CREATE TABLE IF NOT EXISTS skill_bypass_keyword ("
                + "id BIGSERIAL PRIMARY KEY, "
                + "group_name VARCHAR(64) NOT NULL, "
                + "keyword VARCHAR(128) NOT NULL, "
                + "enabled BOOLEAN NOT NULL DEFAULT TRUE, "
                + "remark VARCHAR(255), "
                + "created_at TIMESTAMP NOT NULL DEFAULT now(), "
                + "updated_at TIMESTAMP NOT NULL DEFAULT now())";
        try (Statement st = c.createStatement()) {
            st.execute(ddl);
        }
    }

    private void seed(Connection c) throws SQLException {
        String sql = "INSERT INTO skill_bypass_keyword (group_name, keyword, enabled, remark) "
                + "SELECT ?, ?, TRUE, ? WHERE NOT EXISTS "
                + "(SELECT 1 FROM skill_bypass_keyword WHERE group_name = ? AND keyword = ?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (SkillBypassKeyword row : SEED) {
                ps.setString(1, row.groupName());
                ps.setString(2, row.keyword());
                ps.setString(3, row.remark());
                ps.setString(4, row.groupName());
                ps.setString(5, row.keyword());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        log.info("skill_bypass_keyword created and seeded with {} bypass words", SEED.size());
    }
}
