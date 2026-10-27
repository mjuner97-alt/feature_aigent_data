/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.agentscopea2a.v2.dimension;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 人员台账数据访问层（表 {@code developer_pl_person_info_t}，人员台账维度方案 §2）。
 *
 * <p>JdbcTemplate(gaussCommonDataSource) 模式，同 {@link VersionCalendarRepository}：
 * TTL 缓存 + 失败保陈旧/降级为空，不抛错不阻塞 chat。快照口径 = max(统计月份)
 * （业务拍板，与旧表/旧 auth Mapper 的 版本月份 口径无关，旧 Mapper 不动）。
 *
 * <p>缓存：per-user 归属（Q1，按条目惰性加载，TTL 30min）+ 姓名字典（Q3 全量 Set，
 * TTL 30min，加载失败 → 人名扫描整体关闭）。Q2（按姓名）直查不缓存（重名反问
 * 消费轮要求确定性，2800 行级别全扫成本可忽略）。
 *
 * <p>降级契约（方案 §3.5/拍板⑥）：查询失败返回 null/空集，调用方不注入维度、
 * 问题原样走 LLM。
 */
public class PersonLedgerRepository {

    private static final Logger log = LoggerFactory.getLogger(PersonLedgerRepository.class);

    static final String TABLE = "developer_pl_person_info_t";

    /** 快照过滤（§2.1）：max 统计月份，中文列名一律双引号，参数全 PreparedStatement 绑定。 */
    private static final String SNAPSHOT_FILTER =
            "\"统计月份\" = (SELECT MAX(\"统计月份\") FROM " + TABLE + ")";

    private static final String Q1_BY_USER_ID = "SELECT DISTINCT \"部门\", \"统计组\", \"应用\", "
            + "\"产品线\", \"姓名\", \"统一认证号\", \"岗位角色\" FROM " + TABLE
            + " WHERE \"统一认证号\" = ? AND " + SNAPSHOT_FILTER;

    private static final String Q2_BY_NAME = "SELECT DISTINCT \"部门\", \"统计组\", \"姓名\", "
            + "\"统一认证号\", \"岗位角色\" FROM " + TABLE + " WHERE \"姓名\" = ? AND " + SNAPSHOT_FILTER;

    private static final String Q3_NAME_DICTIONARY = "SELECT DISTINCT \"姓名\" FROM " + TABLE
            + " WHERE " + SNAPSHOT_FILTER + " AND \"姓名\" IS NOT NULL";

    /** Q4 角色字典（岗位角色内置维度方案 §4.1）：值域 = 运行期 DISTINCT，字典即词表永不腐化。 */
    private static final String Q4_ROLE_DICTIONARY = "SELECT DISTINCT \"岗位角色\" FROM " + TABLE
            + " WHERE " + SNAPSHOT_FILTER + " AND \"岗位角色\" IS NOT NULL";

    /** Q8 维度值→部门（2026/10/27 拍板）：问句没提部门但命中统计组/产品线/应用时，
     *  按维度值反填 deptName。列名拼接进 SQL 前过白名单闸（同 Q5-Q7 TRUTH_COLUMNS）。 */
    private static final Set<String> DEPT_LOOKUP_COLUMNS = Set.of("统计组", "产品线", "应用");

    /** Q5-Q7 真值列白名单（维度值台账真值校验方案 §4.1）：列名拼接进 SQL 前必须过闸。 */
    private static final Set<String> TRUTH_COLUMNS = Set.of("统计组", "产品线", "应用");

    /** Q1 结果（多人多行/兼岗 → 各列 distinct 收集为多值，LinkedHashSet 保序去重）。 */
    public record PersonLedgerRow(List<String> depts, List<String> groups, List<String> apps,
                                  List<String> productLines, List<String> roles,
                                  String name, String userId) {

        /** Legacy 6-arg shape (no roles) for existing call sites/tests. */
        public PersonLedgerRow(List<String> depts, List<String> groups, List<String> apps,
                               List<String> productLines, String name, String userId) {
            this(depts, groups, apps, productLines, List.of(), name, userId);
        }

        public static PersonLedgerRow empty(String userId) {
            return new PersonLedgerRow(List.of(), List.of(), List.of(), List.of(),
                    List.of(), null, userId);
        }

        public boolean isEmpty() {
            return (depts == null || depts.isEmpty())
                    && (groups == null || groups.isEmpty())
                    && (apps == null || apps.isEmpty())
                    && (productLines == null || productLines.isEmpty())
                    && (roles == null || roles.isEmpty())
                    && (name == null || name.isBlank());
        }
    }

    /** Q2 行按统一认证号去重合并后的身份（兼岗多行 → 同一 perNo 合并部门/组/角色多值）。 */
    public record PersonIdentity(String name, String perNo, List<String> depts, List<String> groups,
                                 List<String> roles) {

        public PersonIdentity(String name, String perNo, List<String> depts, List<String> groups) {
            this(name, perNo, depts, groups, List.of());
        }

        /** 反问展示后缀：徐三金（杭州开发三部 / 应用开发）——角色列带出辅助消歧（岗位角色方案 §7-2）。 */
        public String displaySuffix() {
            List<String> parts = new ArrayList<>();
            if (depts != null) {
                depts.stream().filter(d -> d != null && !d.isBlank()).forEach(parts::add);
            }
            if (groups != null) {
                groups.stream().filter(g -> g != null && !g.isBlank()).forEach(parts::add);
            }
            if (roles != null) {
                roles.stream().filter(r -> r != null && !r.isBlank()).forEach(parts::add);
            }
            return parts.isEmpty() ? name : name + "（" + String.join(" / ", parts) + "）";
        }
    }

    private final JdbcTemplate jdbcTemplate;
    private final long ttlMillis;

    private record CachedRow(PersonLedgerRow row, long at) {}

    private final Map<String, CachedRow> userCache = new ConcurrentHashMap<>();

    private record CachedDepts(List<String> depts, long at) {}

    // Q8 维度值→部门 per-key TTL 缓存（失败负缓存防反复打库）
    private final Map<String, CachedDepts> groupDeptCache = new ConcurrentHashMap<>();

    private final Object lock = new Object();
    // writes guarded by lock, reads volatile
    private volatile Set<String> nameDictionary;
    private volatile long dictExpireAt;
    private volatile long dictNextRetryAt;

    private final Object roleLock = new Object();
    // Q4 角色字典（岗位角色内置维度方案 §4.1），同 nameDictionary 的 TTL+退避模式
    private volatile Set<String> roleDictionary;
    private volatile long roleDictExpireAt;
    private volatile long roleDictNextRetryAt;

    private record CachedTruth(Set<String> values, long expireAt) {}

    // Q5-Q7 真值字典（维度值台账真值校验方案 §4.1），per-column TTL；values 可为空集
    private final Map<String, CachedTruth> truthDictCache = new ConcurrentHashMap<>();
    private final Map<String, Long> truthDictNextRetryAt = new ConcurrentHashMap<>();
    private final Object truthLock = new Object();

    public PersonLedgerRepository(JdbcTemplate jdbcTemplate, long ttlSeconds) {
        this.jdbcTemplate = jdbcTemplate;
        this.ttlMillis = ttlSeconds * 1000L;
    }

    /**
     * Q1 按统一认证号查人员归属（"我部/我组/我的应用/我的产品线/我"）。
     * 查无此人 / userId 空 / 查询失败 → null（调用方不注入，方案 §3.5）。
     */
    public PersonLedgerRow byUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        long now = System.currentTimeMillis();
        CachedRow cached = userCache.get(userId);
        if (cached != null && now - cached.at() < ttlMillis) {
            return cached.row().isEmpty() ? null : cached.row();
        }
        List<PersonLedgerRow> rows = new ArrayList<>();
        try {
            jdbcTemplate.query(Q1_BY_USER_ID, rs -> {
                rows.add(new PersonLedgerRow(
                        distinct(rs.getString("部门")),
                        distinct(rs.getString("统计组")),
                        distinct(rs.getString("应用")),
                        distinct(rs.getString("产品线")),
                        distinct(rs.getString("岗位角色")),
                        blankToNull(rs.getString("姓名")),
                        userId));
            }, userId);
        } catch (Exception ex) {
            // 降级：不注入维度，问题原样走 LLM（拍板⑥）；负缓存避免每个问题都打库
            log.warn("PersonLedgerRepository byUserId failed (no injection): {}", ex.getMessage());
            userCache.put(userId, new CachedRow(PersonLedgerRow.empty(userId), now));
            return null;
        }
        PersonLedgerRow merged = mergeByUser(rows, userId);
        userCache.put(userId, new CachedRow(merged, now));
        return merged.isEmpty() ? null : merged;
    }

    private static PersonLedgerRow mergeByUser(List<PersonLedgerRow> rows, String userId) {
        if (rows.isEmpty()) {
            return PersonLedgerRow.empty(userId);
        }
        Set<String> depts = new LinkedHashSet<>();
        Set<String> groups = new LinkedHashSet<>();
        Set<String> apps = new LinkedHashSet<>();
        Set<String> productLines = new LinkedHashSet<>();
        Set<String> roles = new LinkedHashSet<>();
        String name = null;
        for (PersonLedgerRow row : rows) {
            if (row == null) {
                continue;
            }
            depts.addAll(row.depts());
            groups.addAll(row.groups());
            apps.addAll(row.apps());
            productLines.addAll(row.productLines());
            roles.addAll(row.roles());
            if (name == null && row.name() != null && !row.name().isBlank()) {
                name = row.name();
            }
        }
        return new PersonLedgerRow(List.copyOf(depts), List.copyOf(groups), List.copyOf(apps),
                List.copyOf(productLines), List.copyOf(roles), name, userId);
    }

    /**
     * Q2 按姓名查（人名识别）：按统一认证号去重合并（同名多 perNo = 重名 → 反问候选；
     * 同 perNo 多行 = 兼岗 → 合并部门/组）。失败/查无 → 空列表。
     */
    public List<PersonIdentity> byName(String name) {
        if (name == null || name.isBlank()) {
            return List.of();
        }
        List<PersonIdentity> out = new ArrayList<>();
        Map<String, PersonIdentity> uniq = new LinkedHashMap<>();
        try {
            jdbcTemplate.query(Q2_BY_NAME, rs -> {
                String perNo = blankToNull(rs.getString("统一认证号"));
                if (perNo == null) {
                    return;
                }
                String dept = blankToNull(rs.getString("部门"));
                String group = blankToNull(rs.getString("统计组"));
                String role = blankToNull(rs.getString("岗位角色"));
                PersonIdentity existing = uniq.get(perNo);
                if (existing == null) {
                    uniq.put(perNo, new PersonIdentity(name, perNo,
                            dept == null ? List.of() : List.of(dept),
                            group == null ? List.of() : List.of(group),
                            role == null ? List.of() : List.of(role)));
                } else {
                    uniq.put(perNo, mergeIdentity(existing, dept, group, role));
                }
            }, name);
        } catch (Exception ex) {
            log.warn("PersonLedgerRepository byName({}) failed: {}", name, ex.getMessage());
            return List.of();
        }
        out.addAll(uniq.values());
        return List.copyOf(out);
    }

    private static PersonIdentity mergeIdentity(PersonIdentity existing, String dept, String group,
                                                String role) {
        Set<String> depts = new LinkedHashSet<>(existing.depts());
        Set<String> groups = new LinkedHashSet<>(existing.groups());
        Set<String> roles = new LinkedHashSet<>(existing.roles());
        if (dept != null) {
            depts.add(dept);
        }
        if (group != null) {
            groups.add(group);
        }
        if (role != null) {
            roles.add(role);
        }
        return new PersonIdentity(existing.name(), existing.perNo(),
                List.copyOf(depts), List.copyOf(groups), List.copyOf(roles));
    }

    /**
     * Q8 维度值→部门（2026/10/27 拍板）：问句没提部门但命中统计组/产品线/应用
     * （车贷/房贷型纯组维度问句）时，DirectSkillExecutor 按维度值反填 deptName。
     * TTL 同 per-user 归属；失败/查无 → 空列表（fail-open 不反填，负缓存防反复打库）。
     * column 白名单外抛 IllegalArgumentException（拼接进 SQL 前过闸）。
     */
    public List<String> deptsByDimension(String column, String value) {
        if (column == null || !DEPT_LOOKUP_COLUMNS.contains(column)) {
            throw new IllegalArgumentException("unsupported dept-lookup column: " + column);
        }
        if (value == null || value.isBlank()) {
            return List.of();
        }
        String cacheKey = column + ":" + value;
        long now = System.currentTimeMillis();
        CachedDepts cached = groupDeptCache.get(cacheKey);
        if (cached != null && now - cached.at() < ttlMillis) {
            return cached.depts();
        }
        List<String> out = new ArrayList<>();
        try {
            jdbcTemplate.query("SELECT DISTINCT \"部门\" FROM " + TABLE
                            + " WHERE \"" + column + "\" = ? AND " + SNAPSHOT_FILTER,
                    rs -> {
                        String dept = blankToNull(rs.getString("部门"));
                        if (dept != null && !out.contains(dept)) {
                            out.add(dept);
                        }
                    }, value);
        } catch (Exception ex) {
            log.warn("PersonLedgerRepository deptsByDimension({}, {}) failed (no backfill): {}",
                    column, value, ex.getMessage());
            groupDeptCache.put(cacheKey, new CachedDepts(List.of(), now));
            return List.of();
        }
        List<String> result = List.copyOf(out);
        groupDeptCache.put(cacheKey, new CachedDepts(result, now));
        return result;
    }

    /**
     * Q3 姓名字典（人名扫描用，全量常驻内存 Set，TTL 30min）。
     * 加载失败 → 空 Set（人名扫描整体关闭，只影响 G4）+ 60s 退避重试。
     */
    public Set<String> nameDictionary() {
        Set<String> cached = nameDictionary;
        long now = System.currentTimeMillis();
        if (cached != null && now < dictExpireAt) {
            return cached;
        }
        if (now < dictNextRetryAt) {
            return cached == null ? Set.of() : cached;
        }
        synchronized (lock) {
            cached = nameDictionary;
            now = System.currentTimeMillis();
            if (cached != null && now < dictExpireAt) {
                return cached;
            }
            if (now < dictNextRetryAt) {
                return cached == null ? Set.of() : cached;
            }
            Set<String> fresh = new HashSet<>();
            try {
                jdbcTemplate.query(Q3_NAME_DICTIONARY, rs -> {
                    String name = blankToNull(rs.getString("姓名"));
                    if (name != null) {
                        fresh.add(name);
                    }
                });
                nameDictionary = Set.copyOf(fresh);
                dictExpireAt = System.currentTimeMillis() + ttlMillis;
                log.info("PersonLedgerRepository name dictionary refreshed: {} names", fresh.size());
                return nameDictionary;
            } catch (Exception ex) {
                dictNextRetryAt = System.currentTimeMillis() + 60_000L;
                log.warn("PersonLedgerRepository name dictionary load failed (scan disabled): {}",
                        ex.getMessage());
                return cached == null ? Set.of() : cached;
            }
        }
    }

    /**
     * Q4 角色字典（岗位角色内置维度方案 §4.1，2026/10/26）：全量 DISTINCT 岗位角色，
     * TTL 同姓名字典 30min + 60s 失败退避。加载失败/为空 → 空 Set
     * （角色提取路径A 归一 fail-open / 路径B 字典扫描自然禁用，fail-safe）。
     */
    public Set<String> roleDictionary() {
        Set<String> cached = roleDictionary;
        long now = System.currentTimeMillis();
        if (cached != null && now < roleDictExpireAt) {
            return cached;
        }
        if (now < roleDictNextRetryAt) {
            return cached == null ? Set.of() : cached;
        }
        synchronized (roleLock) {
            cached = roleDictionary;
            now = System.currentTimeMillis();
            if (cached != null && now < roleDictExpireAt) {
                return cached;
            }
            if (now < roleDictNextRetryAt) {
                return cached == null ? Set.of() : cached;
            }
            Set<String> fresh = new HashSet<>();
            try {
                jdbcTemplate.query(Q4_ROLE_DICTIONARY, rs -> {
                    String role = blankToNull(rs.getString("岗位角色"));
                    if (role != null) {
                        fresh.add(role);
                    }
                });
                roleDictionary = Set.copyOf(fresh);
                roleDictExpireAt = System.currentTimeMillis() + ttlMillis;
                log.info("PersonLedgerRepository role dictionary refreshed: {} roles", fresh.size());
                return roleDictionary;
            } catch (Exception ex) {
                roleDictNextRetryAt = System.currentTimeMillis() + 60_000L;
                log.warn("PersonLedgerRepository role dictionary load failed (path-B disabled): {}",
                        ex.getMessage());
                return cached == null ? Set.of() : cached;
            }
        }
    }

    /**
     * Q5-Q7 维度真值字典（维度值台账真值校验方案 §4.1，2026/10/27）：全表 DISTINCT
     * 指定列（统计组/产品线/应用），TTL 30min + 失败 60s 退避，B① 正向扫描与 B③
     * 终局校验共用的真值域。
     *
     * <p>与 Q4 的语义差异（校验用途必须区分「失败」与「真没数据」）：
     * 返回 <b>null</b> = 查询失败且无历史缓存（调用方<b>跳过</b>该键扫描与校验，fail-open
     * 防误杀）；非 null Set = 真值域快照（空集 = 成功查出行数为 0，调用方同样跳过——
     * 空域校验会误杀一切）。
     */
    public Set<String> dimensionTruthDictionary(String column) {
        if (column == null || !TRUTH_COLUMNS.contains(column)) {
            throw new IllegalArgumentException("unsupported truth column: " + column);
        }
        CachedTruth cached = truthDictCache.get(column);
        long now = System.currentTimeMillis();
        if (cached != null && now < cached.expireAt()) {
            return cached.values();
        }
        Long nextRetry = truthDictNextRetryAt.get(column);
        if (nextRetry != null && now < nextRetry) {
            return cached == null ? null : cached.values();
        }
        synchronized (truthLock) {
            cached = truthDictCache.get(column);
            now = System.currentTimeMillis();
            if (cached != null && now < cached.expireAt()) {
                return cached.values();
            }
            nextRetry = truthDictNextRetryAt.get(column);
            if (nextRetry != null && now < nextRetry) {
                return cached == null ? null : cached.values();
            }
            Set<String> fresh = new HashSet<>();
            try {
                jdbcTemplate.query("SELECT DISTINCT \"" + column + "\" FROM " + TABLE
                                + " WHERE " + SNAPSHOT_FILTER + " AND \"" + column + "\" IS NOT NULL",
                        rs -> {
                            String value = blankToNull(rs.getString(column));
                            if (value != null) {
                                fresh.add(value);
                            }
                        });
                CachedTruth snapshot = new CachedTruth(Set.copyOf(fresh),
                        System.currentTimeMillis() + ttlMillis);
                truthDictCache.put(column, snapshot);
                log.info("PersonLedgerRepository truth dictionary [{}] refreshed: {} values",
                        column, fresh.size());
                return snapshot.values();
            } catch (Exception ex) {
                truthDictNextRetryAt.put(column, System.currentTimeMillis() + 60_000L);
                log.warn("PersonLedgerRepository truth dictionary [{}] load failed (check skipped): {}",
                        column, ex.getMessage());
                return cached == null ? null : cached.values();
            }
        }
    }

    /** 测试与运维口：清除缓存强制下次重读。 */
    public void invalidate() {
        userCache.clear();
        groupDeptCache.clear();
        synchronized (lock) {
            nameDictionary = null;
            dictExpireAt = 0;
            dictNextRetryAt = 0;
        }
        synchronized (roleLock) {
            roleDictionary = null;
            roleDictExpireAt = 0;
            roleDictNextRetryAt = 0;
        }
        synchronized (truthLock) {
            truthDictCache.clear();
            truthDictNextRetryAt.clear();
        }
    }

    private static String blankToNull(String value) {
        // openGauss A 兼容下 nvarchar2 空串即 NULL，防御性再滤一遍空白
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static List<String> distinct(String value) {
        return value == null || value.isBlank() ? List.of() : List.of(value.trim());
    }
}
