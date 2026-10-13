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
package com.agentscopea2a.v2.hooks;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreCallEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Injects the "current in-build version plan" (当前在建版本), computed in real time from the
 * {@code rdc_train_calendar} train calendar table, into the system prompt on every call.
 *
 * <p>KNOWLEDGE.md ships a hardcoded default ("2026年6月份版本") that goes stale as release
 * trains roll over. This hook supersedes it with a live value: the version plans of the
 * train window nearest today (typically the previous, current and next monthly versions,
 * e.g. "2026年9月份版本、2026年10月份版本、2026年11月份版本"), ordered by 投产日 and
 * de-duplicated, plus the 当前在建季度 derived from those versions, wrapped in
 * {@code <domain_knowledge_current_version>} so the LLM treats it as authoritative over
 * the static default.
 *
 * <p>The query runs against the gauss-common datasource with a TTL cache (version rollover
 * granularity is days, so a 30-minute TTL is ample). Refresh failures keep serving the last
 * successful value with a 60s retry backoff; with no value ever fetched, nothing is injected
 * and KNOWLEDGE.md's static default applies. Injection never blocks a request.
 *
 * <p>See docs/202610/当前在建版本动态注入方案.md.
 */
public class VersionPlanKnowledgeHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(VersionPlanKnowledgeHook.class);

    private static final String OPEN_TAG = "\n\n<domain_knowledge_current_version>\n";
    private static final String CLOSE_TAG = "</domain_knowledge_current_version>\n";

    /** Retry backoff after a failed refresh, so a DB outage doesn't cost a query per request. */
    private static final long RETRY_BACKOFF_MS = 60_000L;

    /**
     * Window selection: rank distinct 常规投产日 by distance to today, then take the nearest
     * rank plus one neighbour on the already-started side and one on the upcoming side
     * (今天投产 counts as the nearest). Final filter 火车开始时间 <= 今天 keeps only trains
     * that have actually kicked off.
     */
    static final String VERSION_PLAN_SQL = """
            WITH RankedCalendar AS (
                SELECT
                    火车开始时间,
                    版本计划,
                    CAST(常规投产日 AS DATE) AS 投产日_Date,
                    DENSE_RANK() OVER (ORDER BY CAST(常规投产日 AS DATE)) AS date_rank,
                    ABS(CAST(常规投产日 AS DATE) - CURRENT_DATE) AS diff_days,
                    CAST(常规投产日 AS DATE) - CURRENT_DATE AS diff_sign
                FROM rdc_train_calendar
                WHERE
                    火车类型 IN ('直接发布火车（四周）', '非直接发布火车（四周）（4+4）')
                    AND CAST(常规投产日 AS DATE) BETWEEN CURRENT_DATE - INTERVAL '3' MONTH
                                                      AND CURRENT_DATE + INTERVAL '3' MONTH
            ),
            MinDiff AS (
                SELECT
                    date_rank AS min_rank,
                    diff_sign AS min_sign
                FROM RankedCalendar
                ORDER BY diff_days ASC
                LIMIT 1
            )
            SELECT
                rc.版本计划 AS 当前在建版本
            FROM RankedCalendar rc
            CROSS JOIN MinDiff md
            WHERE
                CASE
                    WHEN md.min_sign < 0 THEN
                        rc.date_rank BETWEEN md.min_rank AND md.min_rank + 2
                    WHEN md.min_sign > 0 THEN
                        rc.date_rank BETWEEN md.min_rank - 1 AND md.min_rank + 1
                    ELSE
                        rc.date_rank BETWEEN md.min_rank AND md.min_rank + 2
                END
                AND CAST(rc.火车开始时间 AS DATE) <= CURRENT_DATE
            ORDER BY rc.投产日_Date
            """;

    /** Parses 'xxxx年x月份版本' so the quarter (当前在建季度) can be derived from it. */
    private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d{4})年(\\d{1,2})月份版本");

    private final JdbcTemplate jdbcTemplate;
    private final boolean enabled;
    private final long ttlMillis;
    private final Object lock = new Object();

    // writes guarded by lock, reads volatile
    private volatile CachedVersions cache;
    private volatile long nextRetryAt;

    private record CachedVersions(List<String> versions, long expireAt) {}

    public VersionPlanKnowledgeHook(JdbcTemplate jdbcTemplate, boolean enabled, long ttlSeconds) {
        this.jdbcTemplate = jdbcTemplate;
        this.enabled = enabled;
        this.ttlMillis = ttlSeconds * 1000L;
    }

    @Override
    public int priority() {
        // Before KnowledgeRetrievalHook (-40), after the framework's own pre-call hooks
        return -45;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (!enabled) return Mono.just(event);
        if (event instanceof PreCallEvent e) {
            try {
                inject(e);
            } catch (Exception ex) {
                // Injection must never block a request; KNOWLEDGE.md static default applies
                log.warn("VersionPlanKnowledgeHook injection skipped: {}", ex.getMessage());
            }
            return Mono.just(event);
        }
        return Mono.just(event);
    }

    private void inject(PreCallEvent event) {
        List<String> versions = currentVersions();
        if (versions.isEmpty()) return;
        StringBuilder block = new StringBuilder(OPEN_TAG);
        block.append("当前在建版本（当前版本计划，实时从火车日历计算，按投产时间先后排列）：")
                .append(String.join("、", versions)).append('\n');
        List<String> quarters = deriveQuarters(versions);
        if (!quarters.isEmpty()) {
            block.append("当前在建季度（由在建版本推算，可能为多个，按投产时间先后排列）：")
                    .append(String.join("、", quarters)).append('\n');
        }
        block.append(CLOSE_TAG);
        event.appendSystemContent(block.toString());
        log.info("VersionPlanKnowledgeHook injected versions: {}, quarters: {}", versions, quarters);
    }

    /** '2026年10月份版本' -> '2026年4季度'; deduped, order-preserving; unparsable values skipped. */
    static List<String> deriveQuarters(List<String> versions) {
        return versions.stream()
                .map(VersionPlanKnowledgeHook::toQuarter)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private static String toQuarter(String version) {
        if (version == null) return null;
        Matcher m = VERSION_PATTERN.matcher(version.trim());
        if (!m.matches()) return null;
        int month = Integer.parseInt(m.group(2));
        return m.group(1) + "年" + ((month - 1) / 3 + 1) + "季度";
    }

    /** TTL-cached lookup; single-flight refresh; stale-serves on failure. */
    List<String> currentVersions() {
        CachedVersions c = cache;
        long now = System.currentTimeMillis();
        if (c != null && now < c.expireAt()) return c.versions();
        if (now < nextRetryAt) return c == null ? List.of() : c.versions();
        synchronized (lock) {
            c = cache;
            now = System.currentTimeMillis();
            if (c != null && now < c.expireAt()) return c.versions();
            if (now < nextRetryAt) return c == null ? List.of() : c.versions();
            try {
                List<String> fresh = jdbcTemplate.queryForList(VERSION_PLAN_SQL, String.class)
                        .stream()
                        .filter(v -> v != null && !v.isBlank())
                        .distinct()
                        .toList();
                cache = new CachedVersions(List.copyOf(fresh), System.currentTimeMillis() + ttlMillis);
                log.info("VersionPlanKnowledgeHook refreshed versions: {}", fresh);
                return cache.versions();
            } catch (Exception ex) {
                nextRetryAt = System.currentTimeMillis() + RETRY_BACKOFF_MS;
                log.warn("VersionPlanKnowledgeHook refresh failed, serving {}: {}",
                        c == null ? "empty" : "stale", ex.getMessage());
                return c == null ? List.of() : c.versions();
            }
        }
    }
}
