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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 全局关键词优先级仓储（单张平铺表 {@code skill_keyword_line}，
 * docs/202610/skill关键词优先级行模型存储方案.md v3，gaussCustomerDataSource）。
 *
 * <p>一行 = 页面一行配置（L1 | L2 | L3 词集，顿号分隔、格内词彼此等价）。
 * L1 无优先级（仅分组身份 + 漏斗入围门槛，不进优先级索引，只命中 L1 的候选
 * 平局落 name）；L2/L3 优先级 = L1 分组内行序（同一 L1 串各组 row_order
 * 从 0 起，小者优先；跨组命中业务上不出现）。空表 = 功能天然关闭（排序退化
 * 为层深度 + name）。保存走整表替换（删旧插新，批量 INSERT 一次灌完）；
 * 读失败沿用上次快照（检索热路径）。启动时自动迁移：v2 两级表
 * （skill_keyword_group/row）优先，其次旧的一词一行表 skill_keyword_priority
 * （幂等，本表非空跳过）。
 */
public class SkillKeywordPriorityRepository {

    private static final Logger log = LoggerFactory.getLogger(SkillKeywordPriorityRepository.class);

    private final DataSource dataSource;
    private final long cacheTtlMillis;
    private volatile boolean tableEnsured;
    private volatile List<SkillKeywordPriorityLine> cachedLines;
    private volatile long lastLoadAt;

    public SkillKeywordPriorityRepository(DataSource dataSource, long cacheTtlMillis) {
        this.dataSource = dataSource;
        this.cacheTtlMillis = cacheTtlMillis;
    }

    /** 检索热路径消费的展开形：L2/L3 词 → (level, 词, 行序)；L1 不进索引（无优先级）。 */
    public List<SkillKeywordPriority> findAllEnabled() {
        return expand(findAllLines());
    }

    /** 全量行（行序有序），TTL 快照；读失败沿用上次快照。 */
    public List<SkillKeywordPriorityLine> findAllLines() {
        List<SkillKeywordPriorityLine> snapshot = cachedLines;
        if (snapshot != null && cacheTtlMillis > 0
                && System.currentTimeMillis() - lastLoadAt < cacheTtlMillis) {
            return snapshot;
        }
        ensureTable();
        try {
            List<SkillKeywordPriorityLine> result = loadLines();
            cachedLines = result;
            lastLoadAt = System.currentTimeMillis();
            return result;
        } catch (SQLException e) {
            log.warn("findAllLines skill_keyword_line failed (keep last snapshot): {}", e.getMessage());
            return snapshot != null ? snapshot : List.of();
        }
    }

    /** 整表替换保存：一个事务删旧插新，row_order 按 L1 分组内位次生成（各组从 0 起）。 */
    public void replaceAll(List<SkillKeywordPriorityLine> lines) {
        ensureTable();
        List<SkillKeywordPriorityLine> normalized = normalize(lines);
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("DELETE FROM skill_keyword_line");
            }
            insertLines(c, normalized);
            c.commit();
        } catch (SQLException e) {
            invalidateCache();
            throw new IllegalStateException("skill_keyword_line 保存失败: " + e.getMessage(), e);
        }
        invalidateCache();
    }

    /** Drops the TTL snapshot so the next read hits the database. */
    public void invalidateCache() {
        cachedLines = null;
        lastLoadAt = 0;
    }

    // ── 纯函数（单测直测） ────────────────────────────────────────────────

    /** 按 、，, 拆词、去空白片段；全空返回空列表。 */
    public static List<String> splitWords(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> words = new ArrayList<>();
        for (String piece : value.split("[、，,]")) {
            String trimmed = piece.trim();
            if (!trimmed.isEmpty()) {
                words.add(trimmed);
            }
        }
        return words;
    }

    /** 拆词 → 去重（保序）→ 顿号拼回；存储规范化用。 */
    public static String normalizeCell(String value) {
        return String.join("、", new LinkedHashSet<>(splitWords(value)));
    }

    /** 行模型 → 检索展开形：L2/L3 词位次 = L1 分组内行序（同格内所有词同位次）；L1 不展开。 */
    public static List<SkillKeywordPriority> expand(List<SkillKeywordPriorityLine> lines) {
        List<SkillKeywordPriority> expanded = new ArrayList<>();
        if (lines == null) {
            return expanded;
        }
        Map<String, Integer> nextOrderByL1 = new LinkedHashMap<>();
        for (SkillKeywordPriorityLine line : lines) {
            if (line == null) {
                continue;
            }
            String groupKey = line.level1Keywords();
            int r = nextOrderByL1.getOrDefault(groupKey, 0);
            nextOrderByL1.put(groupKey, r + 1);
            for (String word : splitWords(line.level2Keywords())) {
                expanded.add(new SkillKeywordPriority(null, 2, null, word, r));
            }
            for (String word : splitWords(line.level3Keywords())) {
                expanded.add(new SkillKeywordPriority(null, 3, null, word, r));
            }
        }
        return expanded;
    }

    /** v2 两级表 → 平铺行：组按 group_order、组内行按 row_order，L1 串随行复制。 */
    static List<SkillKeywordPriorityLine> v2ToLines(List<V2GroupRow> groups, List<V2LineRow> rows) {
        List<V2GroupRow> ordered = new ArrayList<>(groups == null ? List.<V2GroupRow>of() : groups);
        ordered.sort(java.util.Comparator.comparingInt(V2GroupRow::groupOrder));
        Map<Long, List<V2LineRow>> rowsByGroup = new LinkedHashMap<>();
        for (V2LineRow row : rows == null ? List.<V2LineRow>of() : rows) {
            rowsByGroup.computeIfAbsent(row.groupId(), k -> new ArrayList<>()).add(row);
        }
        List<SkillKeywordPriorityLine> lines = new ArrayList<>();
        for (V2GroupRow group : ordered) {
            List<V2LineRow> groupRows = rowsByGroup.getOrDefault(group.id(), List.of());
            groupRows.sort(java.util.Comparator.comparingInt(V2LineRow::rowOrder));
            for (V2LineRow row : groupRows) {
                lines.add(new SkillKeywordPriorityLine(null, group.level1Keywords(),
                        orEmpty(row.level2Keywords()), orEmpty(row.level3Keywords()), null));
            }
        }
        return lines;
    }

    /** v2 组表一行。 */
    record V2GroupRow(long id, String level1Keywords, int groupOrder) {
    }

    /** v2 行表一行。 */
    record V2LineRow(long groupId, String level2Keywords, String level3Keywords, int rowOrder) {
    }

    /** 旧表一词一行（2026/10/15 版）→ 平铺行：按 parent(R#) 还原行、按 L1 串聚合、按首次出现排序。 */
    static List<SkillKeywordPriorityLine> legacyToLines(List<LegacyRow> rows) {
        Map<String, LegacyLine> byParent = new LinkedHashMap<>();
        for (LegacyRow row : rows) {
            if (row == null || row.level() < 1 || row.level() > 3
                    || row.keyword() == null || row.keyword().isBlank()) {
                continue;
            }
            LegacyLine line = byParent.computeIfAbsent(row.parent(), k -> new LegacyLine());
            line.cell(row.level()).add(row.keyword().trim());
        }
        Map<String, List<LegacyLine>> byL1 = new LinkedHashMap<>();
        for (LegacyLine line : byParent.values()) {
            String l1 = normalizeCell(String.join("、", line.l1));
            if (l1.isEmpty()) {
                continue; // 脏数据（bug 期 L1 无行号残留），跳过该行
            }
            String l2 = normalizeCell(String.join("、", line.l2));
            String l3 = normalizeCell(String.join("、", line.l3));
            if (l2.isEmpty() && l3.isEmpty()) {
                continue;
            }
            byL1.computeIfAbsent(l1, k -> new ArrayList<>()).add(line);
        }
        List<SkillKeywordPriorityLine> lines = new ArrayList<>();
        for (Map.Entry<String, List<LegacyLine>> e : byL1.entrySet()) {
            for (LegacyLine line : e.getValue()) {
                lines.add(new SkillKeywordPriorityLine(null, e.getKey(),
                        normalizeCell(String.join("、", line.l2)),
                        normalizeCell(String.join("、", line.l3)), null));
            }
        }
        return lines;
    }

    /** 旧表一行（level + parent 行号 + 单词）。 */
    record LegacyRow(int level, String parent, String keyword) {
    }

    private static final class LegacyLine {
        final List<String> l1 = new ArrayList<>();
        final List<String> l2 = new ArrayList<>();
        final List<String> l3 = new ArrayList<>();

        List<String> cell(int level) {
            return level == 1 ? l1 : level == 2 ? l2 : l3;
        }
    }

    /** 保存前规范化：格内去重、空 L1 / 空白行拒绝（Controller 已先校验，此处防御）。 */
    private static List<SkillKeywordPriorityLine> normalize(List<SkillKeywordPriorityLine> lines) {
        List<SkillKeywordPriorityLine> normalized = new ArrayList<>();
        for (SkillKeywordPriorityLine line : lines == null ? List.<SkillKeywordPriorityLine>of() : lines) {
            if (line == null) {
                continue;
            }
            String l1 = normalizeCell(line.level1Keywords());
            if (l1.isEmpty()) {
                throw new IllegalArgumentException("一级关键词不能为空");
            }
            String l2 = normalizeCell(line.level2Keywords());
            String l3 = normalizeCell(line.level3Keywords());
            if (l2.isEmpty() && l3.isEmpty()) {
                throw new IllegalArgumentException("存在空白行（二级/三级至少填一格）");
            }
            normalized.add(new SkillKeywordPriorityLine(null, l1, l2, l3, null));
        }
        return normalized;
    }

    // ── JDBC ─────────────────────────────────────────────────────────────

    private List<SkillKeywordPriorityLine> loadLines() throws SQLException {
        List<SkillKeywordPriorityLine> lines = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, level1_keywords, level2_keywords, level3_keywords, row_order "
                             + "FROM skill_keyword_line ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                lines.add(new SkillKeywordPriorityLine(rs.getLong("id"),
                        orEmpty(rs.getString("level1_keywords")),
                        orEmpty(rs.getString("level2_keywords")),
                        orEmpty(rs.getString("level3_keywords")),
                        rs.getInt("row_order")));
            }
        }
        return lines;
    }

    private void ensureTable() {
        if (tableEnsured) return;
        synchronized (this) {
            if (tableEnsured) return;
            try (Connection c = dataSource.getConnection()) {
                try (Statement st = c.createStatement()) {
                    // level2/3 可空：该 openGauss 实例 '' 即 NULL，NOT NULL 会让未配置格无法写入
                    st.execute("CREATE TABLE IF NOT EXISTS skill_keyword_line ("
                            + "id BIGSERIAL PRIMARY KEY, "
                            + "level1_keywords TEXT NOT NULL, "
                            + "level2_keywords TEXT, "
                            + "level3_keywords TEXT, "
                            + "row_order INT NOT NULL, "
                            + "created_at TIMESTAMP NOT NULL DEFAULT now())");
                }
                migrate(c);
                tableEnsured = true;
            } catch (SQLException e) {
                log.warn("skill_keyword_line DDL failed (will retry): {}", e.getMessage());
            }
        }
    }

    /** 迁移链（本表为空时，一个来源成功即止）：v2 两级表 → 平铺；旧一词一行表 → 平铺。幂等。 */
    private void migrate(Connection c) throws SQLException {
        if (lineCount(c) > 0) {
            return;
        }
        List<SkillKeywordPriorityLine> lines = readV2Tables(c);
        String source = "skill_keyword_group/row";
        if (lines.isEmpty()) {
            lines = readLegacyTable(c);
            source = "skill_keyword_priority";
        }
        if (lines.isEmpty()) {
            return;
        }
        List<SkillKeywordPriorityLine> normalized;
        try {
            normalized = normalize(lines);
        } catch (IllegalArgumentException e) {
            log.warn("skill_keyword_line migration source invalid (skip): {}", e.getMessage());
            return;
        }
        c.setAutoCommit(false);
        try {
            insertLines(c, normalized);
            c.commit();
            log.info("skill keyword priority migrated from {} to skill_keyword_line: {} lines", source, normalized.size());
        } catch (SQLException | RuntimeException e) {
            c.rollback();
            log.warn("skill_keyword_line migration failed (will retry): {}", e.getMessage());
        } finally {
            c.setAutoCommit(true);
        }
    }

    /** 批量插入（事务内调用）：row_order = L1 分组内位次（同一 L1 串各组从 0 起）。 */
    private static void insertLines(Connection c, List<SkillKeywordPriorityLine> lines) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO skill_keyword_line (level1_keywords, level2_keywords, level3_keywords, row_order) "
                        + "VALUES (?, ?, ?, ?)")) {
            Map<String, Integer> nextOrderByL1 = new LinkedHashMap<>();
            for (SkillKeywordPriorityLine line : lines) {
                String groupKey = line.level1Keywords();
                int rowOrder = nextOrderByL1.getOrDefault(groupKey, 0);
                nextOrderByL1.put(groupKey, rowOrder + 1);
                ps.setString(1, groupKey);
                // 该 openGauss 实例把 '' 视为 NULL，空格必须绑 null（列可空）
                ps.setString(2, emptyAsNull(line.level2Keywords()));
                ps.setString(3, emptyAsNull(line.level3Keywords()));
                ps.setInt(4, rowOrder);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** v2 两级表存在且有数据 → 平铺行；否则空列表。 */
    private List<SkillKeywordPriorityLine> readV2Tables(Connection c) {
        if (!tableExists(c, "skill_keyword_group") || !tableExists(c, "skill_keyword_row")) {
            return List.of();
        }
        try {
            List<V2GroupRow> groups = new ArrayList<>();
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT id, level1_keywords, group_order FROM skill_keyword_group "
                                 + "ORDER BY group_order, id")) {
                while (rs.next()) {
                    groups.add(new V2GroupRow(rs.getLong("id"),
                            orEmpty(rs.getString("level1_keywords")), rs.getInt("group_order")));
                }
            }
            List<V2LineRow> rows = new ArrayList<>();
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT group_id, level2_keywords, level3_keywords, row_order "
                                 + "FROM skill_keyword_row ORDER BY group_id, row_order, id")) {
                while (rs.next()) {
                    rows.add(new V2LineRow(rs.getLong("group_id"),
                            orEmpty(rs.getString("level2_keywords")),
                            orEmpty(rs.getString("level3_keywords")), rs.getInt("row_order")));
                }
            }
            return groups.isEmpty() ? List.of() : v2ToLines(groups, rows);
        } catch (SQLException e) {
            log.warn("read skill_keyword_group/row failed (try legacy): {}", e.getMessage());
            return List.of();
        }
    }

    /** 旧一词一行表存在且有数据 → 平铺行；否则空列表。 */
    private List<SkillKeywordPriorityLine> readLegacyTable(Connection c) {
        if (!tableExists(c, "skill_keyword_priority")) {
            return List.of();
        }
        try {
            List<LegacyRow> rows = new ArrayList<>();
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT level, parent_keyword, keyword FROM skill_keyword_priority "
                                 + "ORDER BY level, sort_order")) {
                while (rs.next()) {
                    rows.add(new LegacyRow(rs.getInt("level"),
                            orEmpty(rs.getString("parent_keyword")), rs.getString("keyword")));
                }
            }
            return legacyToLines(rows);
        } catch (SQLException e) {
            log.warn("read skill_keyword_priority failed: {}", e.getMessage());
            return List.of();
        }
    }

    private static String emptyAsNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static boolean tableExists(Connection c, String table) {
        try (Statement st = c.createStatement()) {
            st.execute("SELECT 1 FROM " + table + " LIMIT 1");
            return true;
        } catch (SQLException notExists) {
            return false;
        }
    }

    private static int lineCount(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM skill_keyword_line")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
