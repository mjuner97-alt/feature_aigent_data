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
            + "\"产品线\", \"姓名\", \"统一认证号\" FROM " + TABLE
            + " WHERE \"统一认证号\" = ? AND " + SNAPSHOT_FILTER;

    private static final String Q2_BY_NAME = "SELECT DISTINCT \"部门\", \"统计组\", \"姓名\", "
            + "\"统一认证号\" FROM " + TABLE + " WHERE \"姓名\" = ? AND " + SNAPSHOT_FILTER;

    private static final String Q3_NAME_DICTIONARY = "SELECT DISTINCT \"姓名\" FROM " + TABLE
            + " WHERE " + SNAPSHOT_FILTER + " AND \"姓名\" IS NOT NULL";

    /** Q1 结果（多人多行/兼岗 → 各列 distinct 收集为多值，LinkedHashSet 保序去重）。 */
    public record PersonLedgerRow(List<String> depts, List<String> groups, List<String> apps,
                                  List<String> productLines, String name, String userId) {

        public static PersonLedgerRow empty(String userId) {
            return new PersonLedgerRow(List.of(), List.of(), List.of(), List.of(), null, userId);
        }

        public boolean isEmpty() {
            return (depts == null || depts.isEmpty())
                    && (groups == null || groups.isEmpty())
                    && (apps == null || apps.isEmpty())
                    && (productLines == null || productLines.isEmpty())
                    && (name == null || name.isBlank());
        }
    }

    /** Q2 行按统一认证号去重合并后的身份（兼岗多行 → 同一 perNo 合并部门/组多值）。 */
    public record PersonIdentity(String name, String perNo, List<String> depts, List<String> groups) {

        /** 反问展示后缀：徐三金（杭州开发三部 / 杭州开发二部）。 */
        public String displaySuffix() {
            List<String> parts = new ArrayList<>();
            if (depts != null) {
                depts.stream().filter(d -> d != null && !d.isBlank()).forEach(parts::add);
            }
            if (groups != null) {
                groups.stream().filter(g -> g != null && !g.isBlank()).forEach(parts::add);
            }
            return parts.isEmpty() ? name : name + "（" + String.join(" / ", parts) + "）";
        }
    }

    private final JdbcTemplate jdbcTemplate;
    private final long ttlMillis;

    private record CachedRow(PersonLedgerRow row, long at) {}

    private final Map<String, CachedRow> userCache = new ConcurrentHashMap<>();

    private final Object lock = new Object();
    // writes guarded by lock, reads volatile
    private volatile Set<String> nameDictionary;
    private volatile long dictExpireAt;
    private volatile long dictNextRetryAt;

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
        String name = null;
        for (PersonLedgerRow row : rows) {
            if (row == null) {
                continue;
            }
            depts.addAll(row.depts());
            groups.addAll(row.groups());
            apps.addAll(row.apps());
            productLines.addAll(row.productLines());
            if (name == null && row.name() != null && !row.name().isBlank()) {
                name = row.name();
            }
        }
        return new PersonLedgerRow(List.copyOf(depts), List.copyOf(groups), List.copyOf(apps),
                List.copyOf(productLines), name, userId);
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
                PersonIdentity existing = uniq.get(perNo);
                if (existing == null) {
                    uniq.put(perNo, new PersonIdentity(name, perNo,
                            dept == null ? List.of() : List.of(dept),
                            group == null ? List.of() : List.of(group)));
                } else {
                    uniq.put(perNo, mergeIdentity(existing, dept, group));
                }
            }, name);
        } catch (Exception ex) {
            log.warn("PersonLedgerRepository byName({}) failed: {}", name, ex.getMessage());
            return List.of();
        }
        out.addAll(uniq.values());
        return List.copyOf(out);
    }

    private static PersonIdentity mergeIdentity(PersonIdentity existing, String dept, String group) {
        Set<String> depts = new LinkedHashSet<>(existing.depts());
        Set<String> groups = new LinkedHashSet<>(existing.groups());
        if (dept != null) {
            depts.add(dept);
        }
        if (group != null) {
            groups.add(group);
        }
        return new PersonIdentity(existing.name(), existing.perNo(),
                List.copyOf(depts), List.copyOf(groups));
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

    /** 测试与运维口：清除缓存强制下次重读。 */
    public void invalidate() {
        userCache.clear();
        synchronized (lock) {
            nameDictionary = null;
            dictExpireAt = 0;
            dictNextRetryAt = 0;
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
