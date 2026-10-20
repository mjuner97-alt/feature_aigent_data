/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.agentscopea2a.v2.dimension;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 火车日历快照查询（表 {@code rdc_train_calendar}，口语值方案 §3.3）。
 *
 * <p>一次全量快照（每版本一行：MIN(火车开始时间) ~ MAX(常规投产日)），TTL 缓存 +
 * 失败保陈旧值，快照之上派生两类口径：
 * <ul>
 *   <li>版本集合的聚合时间窗口（{@code versionPlan.start/.end} 传参）；</li>
 *   <li>{@code LAST:{n}V}：在建版本的最后一个版本按月序往前推 n 个版本（缺位月照常生成）。</li>
 * </ul>
 *
 * <p>降级契约：gauss 不可用 → 快照为空 → 锚点解析为空 → 必填缺失回退 LLM（不抛错不阻塞）。
 */
public class VersionCalendarRepository {

    private static final Logger log = LoggerFactory.getLogger(VersionCalendarRepository.class);

    /** 与 VersionPlanKnowledgeHook.VERSION_PLAN_SQL 同一火车类型口径（排除紧急发布临时火车）。 */
    static final String SNAPSHOT_SQL = """
            SELECT 版本计划,
                   CAST(MIN(火车开始时间) AS DATE) AS v_start,
                   MAX(常规投产日) AS v_end
            FROM rdc_train_calendar
            WHERE 火车类型 IN ('直接发布火车（四周）', '非直接发布火车（四周）（4+4）')
            GROUP BY 版本计划
            """;

    /** LAST:{n}V 在建锚点的标准月版名形态（按月序回推的起点）。 */
    private static final java.util.regex.Pattern MONTH_VERSION_ANCHOR =
            java.util.regex.Pattern.compile("(\\d{4})年(\\d{1,2})月份版本");

    private final JdbcTemplate jdbcTemplate;
    private final long ttlMillis;
    private final Object lock = new Object();

    // writes guarded by lock, reads volatile
    private volatile Map<String, VersionWindow> snapshot;
    private volatile long expireAt;
    private volatile long nextRetryAt;

    /** 单版本时间窗口 [火车开始时间, 常规投产日]（ISO yyyy-MM-dd，含端点）。 */
    public record VersionWindow(String start, String end) {}

    public VersionCalendarRepository(JdbcTemplate jdbcTemplate, long ttlSeconds) {
        this.jdbcTemplate = jdbcTemplate;
        this.ttlMillis = ttlSeconds * 1000L;
    }

    /** 版本 → 时间窗口快照（TTL 缓存；失败保陈旧；从未成功过返回空 map）。 */
    public Map<String, VersionWindow> snapshot() {
        Map<String, VersionWindow> cached = snapshot;
        long now = System.currentTimeMillis();
        if (cached != null && now < expireAt) {
            return cached;
        }
        if (now < nextRetryAt) {
            return cached == null ? Map.of() : cached;
        }
        synchronized (lock) {
            cached = snapshot;
            now = System.currentTimeMillis();
            if (cached != null && now < expireAt) {
                return cached;
            }
            if (now < nextRetryAt) {
                return cached == null ? Map.of() : cached;
            }
            try {
                Map<String, VersionWindow> fresh = new LinkedHashMap<>();
                jdbcTemplate.query(SNAPSHOT_SQL, rs -> {
                    String version = rs.getString(1);
                    Date start = rs.getDate(2);
                    Date end = rs.getDate(3);
                    if (version != null && !version.isBlank() && start != null && end != null) {
                        fresh.putIfAbsent(version.trim(),
                                new VersionWindow(start.toLocalDate().toString(), end.toLocalDate().toString()));
                    }
                });
                snapshot = java.util.Collections.unmodifiableMap(fresh);
                expireAt = System.currentTimeMillis() + ttlMillis;
                log.info("VersionCalendarRepository refreshed: {} versions", fresh.size());
                return snapshot;
            } catch (Exception ex) {
                nextRetryAt = System.currentTimeMillis() + 60_000L;
                log.warn("VersionCalendarRepository refresh failed, serving {}: {}",
                        cached == null ? "empty" : "stale", ex.getMessage());
                return cached == null ? Map.of() : cached;
            }
        }
    }

    /**
     * 版本集合的聚合时间窗口 [min(start), max(end)]（口语值方案 §3.3）；集合里没有任何
     * 版本在日历中 → 返回 null（.start/.end 参数不传）。日历缺部分版本时按查到的聚合。
     */
    public VersionWindow aggregateWindow(List<String> versions) {
        if (versions == null || versions.isEmpty()) {
            return null;
        }
        Map<String, VersionWindow> snap = snapshot();
        LocalDate min = null;
        LocalDate max = null;
        for (String v : versions) {
            VersionWindow w = v == null ? null : snap.get(v.trim());
            if (w == null) {
                continue;
            }
            LocalDate s = LocalDate.parse(w.start());
            LocalDate e = LocalDate.parse(w.end());
            if (min == null || s.isBefore(min)) {
                min = s;
            }
            if (max == null || e.isAfter(max)) {
                max = e;
            }
        }
        return min == null ? null : new VersionWindow(min.toString(), max.toString());
    }

    /**
     * LAST:{n}V（拍板①终版口径：在建版本的最后一个版本往前推 x 个）：以 anchorVersion
     * 为终点按**月序**回推 n 个标准月版本名（"{y}年{m}月份版本"），日历缺位的月份照样
     * 生成（"近半年"从 11月版前推到 6月版），跨年自动进位，升序返回。
     * anchorVersion 为 null / 非标准月版名 → 退化为日历最新版本往前推 n 个（按投产日序）。
     * 月序路径不依赖日历快照（gauss 不可用也能展开），时间窗口聚合才查日历。
     */
    public List<String> lastVersions(int n, String anchorVersion) {
        if (n < 1) {
            return List.of();
        }
        if (anchorVersion != null) {
            java.util.regex.Matcher m = MONTH_VERSION_ANCHOR.matcher(anchorVersion.trim());
            if (m.matches()) {
                int y = Integer.parseInt(m.group(1));
                int mo = Integer.parseInt(m.group(2));
                if (mo >= 1 && mo <= 12) {
                    List<String> out = new ArrayList<>(n);
                    for (int i = n - 1; i >= 0; i--) {
                        LocalDate d = LocalDate.of(y, mo, 1).minusMonths(i);
                        out.add(d.getYear() + "年" + d.getMonthValue() + "月份版本");
                    }
                    return List.copyOf(out);
                }
            }
        }
        Map<String, VersionWindow> snap = snapshot();
        return snap.entrySet().stream()
                .sorted(Comparator.comparing(
                                (Map.Entry<String, VersionWindow> e) -> LocalDate.parse(e.getValue().end()))
                        .reversed())
                .limit(n)
                .sorted(Comparator.comparing(
                        e -> LocalDate.parse(e.getValue().end())))
                .map(Map.Entry::getKey)
                .toList();
    }

    /** 版本是否在日历快照中存在（延后版定位用；日历不可用返回 false）。 */
    public boolean containsVersion(String version) {
        return version != null && snapshot().containsKey(version.trim());
    }

    /** 测试与运维口：清除缓存强制下次重读。 */
    public void invalidate() {
        synchronized (lock) {
            snapshot = null;
            expireAt = 0;
            nextRetryAt = 0;
        }
    }

    /** 供日志/诊断。 */
    public int snapshotSize() {
        return Objects.requireNonNullElse(snapshot, Map.<String, VersionWindow>of()).size();
    }
}
