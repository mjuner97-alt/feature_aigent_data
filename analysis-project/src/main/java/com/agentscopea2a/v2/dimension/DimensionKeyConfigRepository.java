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
 * <p>表首次创建时自动灌入内置 9 键种子行（builtin=true，aliases 为空数组——内置键
 * 提取走 DimensionStateManager 结构化规则，不走词表；seed 键集与
 * {@code DirectSkillExecutor.DIMENSION_KEYS} 同源，两处需同步维护）。
 * 存量表每次启动做内置键存在性补种（新版新增的内置键如 subItemNo 能补进行存量表）。
 * 读失败时沿用上次快照（param_mapping 保存校验与直接执行提取都在热路径上）。
 */
public class DimensionKeyConfigRepository {

    private static final Logger log = LoggerFactory.getLogger(DimensionKeyConfigRepository.class);

    private static final TypeReference<List<DimensionKeyConfig.AliasMapping>> ALIAS_LIST_TYPE =
            new TypeReference<>() {};

    /** 内置 11 键 seed（key → 展示名），与 DirectSkillExecutor.DIMENSION_KEYS 同源
     *  （perNo=统一认证号，人员台账维度方案 §3.4；perRole=岗位角色，岗位角色内置维度方案 §4.2）。 */
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
        keys.put("subItemNo", "需求子条目");
        keys.put("perName", "人");
        keys.put("perNo", "统一认证号");
        keys.put("perRole", "岗位角色");
        return java.util.Collections.unmodifiableMap(keys);
    }

    /**
     * timeRange 保留键 seed（docs/202610/dimension-key-dynamic-dimension-plan.md §6.1.4）。
     * 非 builtin（词表须管理员可扩），值为语义锚点，执行期解析 start/end；
     * DELETE 是软删（行保留 enabled=false），存在性补种只认"完全无行"——软删不会复活。
     */
    private static final String RESERVED_TIME_RANGE_KEY = TimeRangeResolver.RESERVED_KEY;
    private static final String TIME_RANGE_ALIASES_SEED =
            "[{\"alias\":\"本周\",\"value\":\"THIS_WEEK\"},"
                    + "{\"alias\":\"当周\",\"value\":\"THIS_WEEK\"},"
                    + "{\"alias\":\"上周\",\"value\":\"LAST_WEEK\"},"
                    + "{\"alias\":\"前一周\",\"value\":\"LAST_WEEK\"},"
                    + "{\"alias\":\"今天\",\"value\":\"TODAY\"},"
                    + "{\"alias\":\"今日\",\"value\":\"TODAY\"},"
                    + "{\"alias\":\"下周\",\"value\":\"NEXT:1w\"},"
                    + "{\"alias\":\"近7天\",\"value\":\"LAST:7d\"},"
                    + "{\"alias\":\"近30天\",\"value\":\"LAST:30d\"},"
                    + "{\"alias\":\"交付周到投产\",\"value\":\"交付周至投产周\"},"
                    + "{\"alias\":\"交付周至投产\",\"value\":\"交付周至投产周\"},"
                    + "{\"alias\":\"交付到投产\",\"value\":\"交付周至投产周\"},"
                    + "{\"alias\":\"交付至投产\",\"value\":\"交付周至投产周\"},"
                    + "{\"alias\":\"交付前一周至投产\",\"value\":\"交付周至投产周\"},"
                    + "{\"alias\":\"交付前一周到投产\",\"value\":\"交付周至投产周\"},"
                    + "{\"alias\":\"交付前到投产\",\"value\":\"交付周至投产周\"},"
                    + "{\"alias\":\"交付后\",\"value\":\"交付周至投产周\"},"
                    + "{\"alias\":\"投产周\",\"value\":\"投产周\"},"
                    + "{\"alias\":\"投产前一周\",\"value\":\"投产周\"},"
                    + "{\"alias\":\"投产前\",\"value\":\"AMBIGUOUS:PROD\"}]";

    /** 2026-10-14 首版 seed 的 alias 集合：存量表 timeRange 行若仍是这套（管理员未改过），
     *  启动时自动升级到新 seed（时间维度方案 §3.2——管理员改过的行不覆盖）。 */
    private static final java.util.Set<String> LEGACY_TIME_RANGE_ALIAS_NAMES = java.util.Set.of(
            "本周", "上周", "前一周", "近一周", "最近一周", "近7天", "近30天");

    /**
     * 口语值方案 §3.6（docs/202610/版本季度部门口语值处理方案.md）：允许页面维护词表的
     * 内置键白名单（仅 aliases 可编辑，key/label/enabled 仍锁，见 update/setEnabled）。
     * 2026/10/26 我X方案 §8.3 扩至 8：+groupName/appName/productLine/perNo/perRole
     * （regex 型正则配置条目需页面/API 可编辑；副作用=这五个键的同义词词表同步解锁）。
     */
    public static final java.util.Set<String> VOCAB_EDITABLE_BUILTIN_KEYS =
            java.util.Set.of("versionPlan", "vestQuarter", "deptName",
                    "groupName", "appName", "productLine", "perNo", "perRole");

    /** 内置键默认词表 seed（§3.2）：行 aliases 为空时灌入，管理员改过（非空）不覆盖。 */
    private static final Map<String, String> BUILTIN_VOCAB_SEEDS = buildBuiltinVocabSeeds();

    private static Map<String, String> buildBuiltinVocabSeeds() {
        Map<String, String> seeds = new LinkedHashMap<>();
        StringBuilder dept = new StringBuilder("[");
        for (int i = 1; i <= 5; i++) {
            String cn = switch (i) {
                case 1 -> "一";
                case 2 -> "二";
                case 3 -> "三";
                case 4 -> "四";
                default -> "五";
            };
            if (i > 1) {
                dept.append(",");
            }
            dept.append("{\"alias\":\"杭").append(cn).append("\",\"value\":\"杭州开发")
                    .append(cn).append("部\"}");
        }
        dept.append("]");
        seeds.put("deptName", dept.toString());
        seeds.put("versionPlan", "["
                + "{\"alias\":\"今年以来\",\"value\":\"YEAR_V\"},"
                + "{\"alias\":\"全年\",\"value\":\"YEAR_V\"},"
                + "{\"alias\":\"今年\",\"value\":\"YEAR_V\"},"
                + "{\"alias\":\"各月度版本\",\"value\":\"YEAR_V\"},"
                + "{\"alias\":\"每个月度版本\",\"value\":\"YEAR_V\"},"
                + "{\"alias\":\"各个月度版本\",\"value\":\"YEAR_V\"},"
                + "{\"alias\":\"每月\",\"value\":\"YEAR_V\"},"
                + "{\"alias\":\"各月\",\"value\":\"YEAR_V\"},"
                + "{\"alias\":\"各版本\",\"value\":\"YEAR_V\"},"
                + "{\"alias\":\"上半年\",\"value\":\"HALF_V:1\"},"
                + "{\"alias\":\"下半年\",\"value\":\"HALF_V:2\"},"
                + "{\"alias\":\"近半年\",\"value\":\"LAST:6V\"},"
                + "{\"alias\":\"目前\",\"value\":\"THIS_V\"},"
                + "{\"alias\":\"当前\",\"value\":\"THIS_V\"},"
                + "{\"alias\":\"现在\",\"value\":\"THIS_V\"},"
                + "{\"alias\":\"本月\",\"value\":\"THIS_V\"},"
                + "{\"alias\":\"当月\",\"value\":\"THIS_V\"},"
                + "{\"alias\":\"近期\",\"value\":\"THIS_V\"},"
                + "{\"alias\":\"在建版本\",\"value\":\"THIS_V\"},"
                + "{\"alias\":\"延后版\",\"value\":\"DELAYED\"}"
                + "]");
        seeds.put("vestQuarter", "["
                + "{\"alias\":\"当季\",\"value\":\"THIS_Q\"},"
                + "{\"alias\":\"在建季度\",\"value\":\"THIS_Q\"}"
                + "]");
        return java.util.Collections.unmodifiableMap(seeds);
    }

    /**
     * regex 型 seed（我X按需注入方案 §8.1，2026/10/26）：正则放它触发的目标键行，
     * type=regex、value=用途标签。首版 seed = 现硬编码正则（行为等价基线）。
     * 启动时按「该行是否已有同标签 regex 条目」append-if-missing——管理员删过条目
     * 重启会补回（regex 条目是行为基线；运行期删除后 resolver 有硬编码兜底，不会静默失效）。
     *
     * <p>block 条目（我X方案 §9，2026/10/26 E2E）：候选 token 命中 alias 正则即拒收，
     * 拦"我的组/我的产品线"等代词短语被 EXPLICIT_TEAM/EXPLICIT_PRODUCT_LINE 抢成垃圾
     * 维度值。基线 = 裸 `我` 单字（find 语义，变体全覆盖；真实组名/产品线/应用不含"我"）。
     * deptName/groupName/appName/productLine 四键（拍板③"一起做"）；update() 对基线
     * block 条目做删除保护（拍板①：管理员不可删）。
     */
    private static final Map<String, List<DimensionKeyConfig.AliasMapping>> REGEX_SEEDS = buildRegexSeeds();

    private static DimensionKeyConfig.AliasMapping blockSeed() {
        return new DimensionKeyConfig.AliasMapping("我", "block", DimensionKeyConfig.AliasMapping.TYPE_REGEX);
    }

    private static Map<String, List<DimensionKeyConfig.AliasMapping>> buildRegexSeeds() {
        Map<String, List<DimensionKeyConfig.AliasMapping>> seeds = new LinkedHashMap<>();
        seeds.put("deptName", List.of(new DimensionKeyConfig.AliasMapping(
                "我部|我们部门|我的部门|本部门|我所在部门", "myDept", DimensionKeyConfig.AliasMapping.TYPE_REGEX),
                blockSeed()));
        seeds.put("groupName", List.of(new DimensionKeyConfig.AliasMapping(
                "我的组|我们组|我的团队|我们团队|我的小组|我的统计组", "myGroup", DimensionKeyConfig.AliasMapping.TYPE_REGEX),
                blockSeed()));
        seeds.put("appName", List.of(new DimensionKeyConfig.AliasMapping(
                "我的应用(群组)?", "myApp", DimensionKeyConfig.AliasMapping.TYPE_REGEX),
                blockSeed()));
        seeds.put("productLine", List.of(new DimensionKeyConfig.AliasMapping(
                "我的(产品线|条线|业务线)", "myProductLine", DimensionKeyConfig.AliasMapping.TYPE_REGEX),
                blockSeed()));
        seeds.put("perNo", List.of(new DimensionKeyConfig.AliasMapping(
                "我担任|我现任|我所担任", "myself", DimensionKeyConfig.AliasMapping.TYPE_REGEX)));
        seeds.put("perRole", List.of(new DimensionKeyConfig.AliasMapping(
                "担任([一-龥]{2,10}?)(?=[的，。？?！!、\\s]|$)", "extract", DimensionKeyConfig.AliasMapping.TYPE_REGEX)));
        return java.util.Collections.unmodifiableMap(seeds);
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
        // 口语值方案 §3.6: 白名单内置键允许更新（仅 aliases 语义，key/label 保护在 Controller 层；
        // 白名单外内置键仍拒绝——防止内置键全量放开）。白名单动态拼占位符（§8.3 扩至 8 键）。
        String placeholders = String.join(", ", java.util.Collections.nCopies(
                VOCAB_EDITABLE_BUILTIN_KEYS.size(), "?"));
        String sql = "UPDATE dimension_key_config SET key=?, label=?, aliases=?, enabled=?, remark=?, "
                + "updated_at=now() WHERE id=? AND (builtin=FALSE OR key IN (" + placeholders + "))";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, row.key());
            ps.setString(2, row.label());
            ps.setString(3, serializeAliases(withBaselineBlockEntries(row.key(), row.aliases())));
            ps.setBoolean(4, row.enabled());
            ps.setString(5, row.remark());
            ps.setLong(6, row.id());
            int i = 7;
            for (String key : VOCAB_EDITABLE_BUILTIN_KEYS) {
                ps.setString(i++, key);
            }
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

    /**
     * 基线 block 条目删除保护（我X方案 §9 拍板①）：REGEX_SEEDS 里该键行的 block 基线条目
     * （如 groupName 行 `我`⇒block）管理员不可删——update 提交的 aliases 缺失时静默补回；
     * 用户新增的其他 block 条目可自由删改。
     */
    private List<DimensionKeyConfig.AliasMapping> withBaselineBlockEntries(
            String key, List<DimensionKeyConfig.AliasMapping> aliases) {
        List<DimensionKeyConfig.AliasMapping> baseline = REGEX_SEEDS
                .getOrDefault(key == null ? "" : key, List.of()).stream()
                .filter(DimensionKeyConfig.AliasMapping::isBlock)
                .toList();
        if (baseline.isEmpty()) {
            return aliases;
        }
        List<DimensionKeyConfig.AliasMapping> merged = new ArrayList<>(
                aliases == null ? List.<DimensionKeyConfig.AliasMapping>of() : aliases);
        for (DimensionKeyConfig.AliasMapping b : baseline) {
            if (merged.stream().noneMatch(a -> a != null && a.isBlock() && b.alias().equals(a.alias()))) {
                merged.add(b);
            }
        }
        return List.copyOf(merged);
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
                } else {
                    ensureBuiltinKeyRows(c);
                }
                ensureReservedRow(c);
                ensureBuiltinVocabSeeds(c);
                ensureRegexSeeds(c);
                tableEnsured = true;
            } catch (SQLException e) {
                log.warn("dimension_key_config DDL failed (will retry): {}", e.getMessage());
            }
        }
    }

    /**
     * 内置键默认词表补种（口语值方案 §3.6）：白名单内置键的 aliases 为空（未被管理员
     * 改过）时灌入 seed；已有词表内容（含管理员删过词的存量）不覆盖——同 timeRange
     * "删/停用=关功能"拍板语义，避免重启复活已删词条。
     */
    private void ensureBuiltinVocabSeeds(Connection c) throws SQLException {
        for (Map.Entry<String, String> entry : BUILTIN_VOCAB_SEEDS.entrySet()) {
            String key = entry.getKey();
            String current;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT aliases FROM dimension_key_config WHERE key=? LIMIT 1")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        continue; // 行不存在（seed 失败等），等下次启动重试
                    }
                    current = rs.getString(1);
                }
            }
            List<DimensionKeyConfig.AliasMapping> parsed = parseAliases(current);
            if (!parsed.isEmpty()) {
                continue; // 管理员已维护过，不覆盖
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE dimension_key_config SET aliases=?, updated_at=now() WHERE key=?")) {
                ps.setString(1, entry.getValue());
                ps.setString(2, key);
                ps.executeUpdate();
            }
            log.info("dimension_key_config: seeded builtin vocab for '{}'", key);
        }
        invalidateCache();
    }

    /** regex 型条目补种（我X方案 §8.1）：目标键行缺失同标签 regex 条目时追加（保序，
     *  同义词条目不动）。同义词 seed 的"非空不覆盖"语义不适用于 regex 条目——它们
     *  常与词表共存一行（如 deptName 的 杭一→杭州开发一部 + myDept 正则）。 */
    private void ensureRegexSeeds(Connection c) throws SQLException {
        for (Map.Entry<String, List<DimensionKeyConfig.AliasMapping>> entry : REGEX_SEEDS.entrySet()) {
            String key = entry.getKey();
            String current;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT aliases FROM dimension_key_config WHERE key=? LIMIT 1")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        continue; // 行不存在（补种失败等），等下次启动重试
                    }
                    current = rs.getString(1);
                }
            }
            List<DimensionKeyConfig.AliasMapping> parsed = parseAliases(current);
            boolean missing = entry.getValue().stream()
                    .anyMatch(seed -> parsed.stream()
                            .noneMatch(a -> a.isRegex() && seed.value().equals(a.value())));
            if (!missing) {
                continue;
            }
            List<DimensionKeyConfig.AliasMapping> merged = new ArrayList<>(parsed);
            for (DimensionKeyConfig.AliasMapping seed : entry.getValue()) {
                boolean present = merged.stream()
                        .anyMatch(a -> a.isRegex() && seed.value().equals(a.value()));
                if (!present) {
                    merged.add(seed);
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE dimension_key_config SET aliases=?, updated_at=now() WHERE key=?")) {
                ps.setString(1, serializeAliases(merged));
                ps.setString(2, key);
                ps.executeUpdate();
            }
            log.info("dimension_key_config: seeded regex patterns for '{}'", key);
        }
        invalidateCache();
    }

    /** 内置键存在性补种（存量表每次启动执行）：新版本新增的内置键（如 subItemNo）
     *  在老表里没有行，这里补齐；完全无该 key 的行才插入（软删/手工改键不复活，
     *  同 timeRange 拍板语义）。建表路径 seed 已含全量内置键，无需重跑。 */
    private void ensureBuiltinKeyRows(Connection c) throws SQLException {
        java.util.Set<String> existing = new java.util.HashSet<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT key FROM dimension_key_config");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                existing.add(rs.getString(1));
            }
        }
        String sql = "INSERT INTO dimension_key_config (key, label, builtin, aliases, enabled, remark) "
                + "VALUES (?, ?, TRUE, '[]', TRUE, '内置键（提取走结构化规则，不走词表）')";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (Map.Entry<String, String> entry : BUILTIN_KEYS.entrySet()) {
                if (existing.contains(entry.getKey())) {
                    continue;
                }
                ps.setString(1, entry.getKey());
                ps.setString(2, entry.getValue());
                ps.executeUpdate();
                log.info("dimension_key_config: backfilled builtin key '{}'", entry.getKey());
            }
        }
    }

    /** timeRange 行存在性补种（建表时与存量表都执行）：完全无该 key 的行才插入；
     *  软删行（enabled=false）视为存在，不复活（删/停用=关功能）。
     *  存量行词表若与 2026-10-14 首版 seed 完全一致（管理员未改过）→ 升级为新 seed；
     *  管理员改过的行（含软删）不动。 */
    private void ensureReservedRow(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT aliases FROM dimension_key_config WHERE key=? LIMIT 1")) {
            ps.setString(1, RESERVED_TIME_RANGE_KEY);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    upgradeLegacySeedIfUntouched(c, rs.getString(1));
                    return;
                }
            }
        }
        String sql = "INSERT INTO dimension_key_config (key, label, builtin, aliases, enabled, remark) "
                + "VALUES (?, ?, FALSE, ?, TRUE, ?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, RESERVED_TIME_RANGE_KEY);
            ps.setString(2, "时间范围");
            ps.setString(3, TIME_RANGE_ALIASES_SEED);
            ps.setString(4, "时间范围键：绝对区间正则+词表锚点，执行期解析 start/end"
                    + "（docs/202610/dimension-key-dynamic-dimension-plan.md §6.1）");
            ps.executeUpdate();
        }
        log.info("dimension_key_config: seeded reserved key '{}'", RESERVED_TIME_RANGE_KEY);
    }

    /** 存量行 alias 名集合与首版 seed 完全一致 → 覆盖为新 seed（管理员自定义过则跳过）。 */
    private void upgradeLegacySeedIfUntouched(Connection c, String aliasesJson) {
        if (aliasesJson == null || aliasesJson.isBlank()) {
            return;
        }
        try {
            java.util.Set<String> names = new java.util.HashSet<>();
            for (DimensionKeyConfig.AliasMapping a : parseAliases(aliasesJson)) {
                if (a != null) {
                    names.add(a.alias());
                }
            }
            if (!names.equals(LEGACY_TIME_RANGE_ALIAS_NAMES)) {
                return;
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE dimension_key_config SET aliases=?, updated_at=now() WHERE key=?")) {
                ps.setString(1, TIME_RANGE_ALIASES_SEED);
                ps.setString(2, RESERVED_TIME_RANGE_KEY);
                ps.executeUpdate();
            }
            log.info("dimension_key_config: upgraded legacy timeRange seed to current vocabulary");
        } catch (Exception e) {
            log.warn("dimension_key_config: timeRange seed 升级检查失败, 保留存量词表: {}", e.getMessage());
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
