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
package com.agentscopea2a.v2.tools;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.agentscope.core.message.ToolResultBlock;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Tests for {@link ClickHouseWideTableMetricsTool}.
 *
 * <p>Three layers:
 * <ul>
 *   <li>{@link #rejectMalformedTableName()} etc. -- pure validation, no DB needed.</li>
 *   <li>{@link #rejectSubqueryMissingParens()} etc. -- subquery filter value whitelist, no DB needed.</li>
 *   <li>{@link #realQueryTraceRecent()} -- hits real ClickHouse, gated by CK_HOST env var.</li>
 * </ul>
 */
class ClickHouseWideTableMetricsToolTest {

    private static final String TABLE_NAME = "trace_recent";

    private static final List<String> TRACE_FIELDS = List.of(
            "sessionId", "userId", "totalDurationMs", "status", "agentName", "eventCount");

    // ----------------------------------------------------------------------
    // Validation - no DB
    // ----------------------------------------------------------------------

    @Test
    void rejectMalformedTableName() {
        ClickHouseWideTableMetricsTool tool = new ClickHouseWideTableMetricsTool(null);
        ToolResultBlock r = tool.clickhouseQuery(
                "trace_recent; DROP TABLE foo",
                List.of("sessionId"),
                null, null);
        String text = extractText(r);
        assertTrue(text.contains("不是合法表名"),
                "expected table name format rejection, got: " + text);
    }

    @Test
    void rejectInjectionInFieldName() {
        ClickHouseWideTableMetricsTool tool = new ClickHouseWideTableMetricsTool(null);
        ToolResultBlock r = tool.clickhouseQuery(
                TABLE_NAME,
                List.of("sessionId; DROP TABLE foo"),
                null, null);
        String text = extractText(r);
        assertTrue(text.contains("字段名") && text.contains("不合法"),
                "expected field name rejection, got: " + text);
    }

    @Test
    void rejectInjectionInFilterColumn() {
        ClickHouseWideTableMetricsTool tool = new ClickHouseWideTableMetricsTool(null);
        ToolResultBlock r = tool.clickhouseQuery(
                TABLE_NAME,
                List.of("sessionId"),
                Map.of("userId; DROP", "x"),
                null);
        String text = extractText(r);
        assertTrue(text.contains("filter 列名") && text.contains("不合法"),
                "expected filter column name rejection, got: " + text);
    }

    @Test
    void rejectWhenDataSourceMissing() {
        ClickHouseWideTableMetricsTool tool = new ClickHouseWideTableMetricsTool(null);
        ToolResultBlock r = tool.clickhouseQuery(
                TABLE_NAME,
                List.of("sessionId"),
                null, null);
        String text = extractText(r);
        assertTrue(text.contains("clickHouseDataSource 未注入"),
                "expected missing datasource rejection, got: " + text);
    }

    // ----------------------------------------------------------------------
    // subqueryFilters whitelist - no DB
    // ----------------------------------------------------------------------

    @Test
    void rejectSubqueryMissingParens() {
        ClickHouseWideTableMetricsTool tool = new ClickHouseWideTableMetricsTool(null);
        // value 缺圆括号包裹 -> 不匹配 ^\(SELECT ... \)$
        ToolResultBlock r = tool.clickhouseQuery(
                TABLE_NAME,
                List.of("sessionId"),
                null,
                Map.of("createdAt", "SELECT MAX(createdAt) FROM trace_recent"));
        String text = extractText(r);
        assertTrue(text.contains("subqueryFilters") && text.contains("必须形如 (SELECT ...)"),
                "expected subquery format rejection, got: " + text);
    }

    @Test
    void rejectSubqueryWithDrop() {
        ClickHouseWideTableMetricsTool tool = new ClickHouseWideTableMetricsTool(null);
        // value 形如 (SELECT ...; DROP TABLE x) -- 通过 SUBQUERY_PATTERN 但被 FORBIDDEN 拦
        ToolResultBlock r = tool.clickhouseQuery(
                TABLE_NAME,
                List.of("sessionId"),
                null,
                Map.of("createdAt", "(SELECT MAX(createdAt) FROM trace_recent; DROP TABLE x)"));
        String text = extractText(r);
        assertTrue(text.contains("禁用关键字") && text.contains("DROP"),
                "expected DROP keyword rejection, got: " + text);
    }

    @Test
    void rejectSubqueryNonStringValue() {
        ClickHouseWideTableMetricsTool tool = new ClickHouseWideTableMetricsTool(null);
        ToolResultBlock r = tool.clickhouseQuery(
                TABLE_NAME,
                List.of("sessionId"),
                null,
                Map.of("createdAt", 123));
        String text = extractText(r);
        assertTrue(text.contains("必须是字符串") && text.contains("(SELECT ..."),
                "expected non-string value rejection, got: " + text);
    }

    @Test
    void rejectSubqueryColumnInjection() {
        ClickHouseWideTableMetricsTool tool = new ClickHouseWideTableMetricsTool(null);
        ToolResultBlock r = tool.clickhouseQuery(
                TABLE_NAME,
                List.of("sessionId"),
                null,
                Map.of("userId; DROP", "(SELECT 'alice')"));
        String text = extractText(r);
        assertTrue(text.contains("subqueryFilters 列名") && text.contains("不合法"),
                "expected subquery column name rejection, got: " + text);
    }

    @Test
    void acceptWellFormedSubqueryInValidation() {
        // 形如 (SELECT ...) 的合法 value 应通过 value 白名单校验阶段 (走到 DB 后才报 dataSource 未注入)
        ClickHouseWideTableMetricsTool tool = new ClickHouseWideTableMetricsTool(null);
        ToolResultBlock r = tool.clickhouseQuery(
                TABLE_NAME,
                List.of("sessionId"),
                null,
                Map.of("createdAt", "(SELECT MAX(createdAt) FROM trace_recent)"));
        String text = extractText(r);
        assertTrue(text.contains("clickHouseDataSource 未注入"),
                "expected well-formed subquery to pass value validation and fail at DB-wiring stage, got: " + text);
    }

    // ----------------------------------------------------------------------
    // Integration - real ClickHouse (gated)
    // ----------------------------------------------------------------------

    @Test
    @EnabledIfEnvironmentVariable(named = "CK_HOST", matches = ".+")
    void realQueryTraceRecent() {
        DataSource ds = buildRealDataSource();
        ClickHouseWideTableMetricsTool tool = new ClickHouseWideTableMetricsTool(ds);
        ToolResultBlock r = tool.clickhouseQuery(
                TABLE_NAME,
                TRACE_FIELDS,
                null, null);
        String text = extractText(r);
        System.out.println("=== realQueryTraceRecent output ===\n" + text);
        assertNotNull(text);
        assertTrue(text.contains("[clickhouse_query]"),
                "expected clickhouse_query marker, got: " + text);
        assertTrue(text.contains("共 ") && text.contains(" 行"),
                "expected row count summary, got: " + text);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "CK_HOST", matches = ".+")
    void realQueryWithSubqueryFilter() {
        DataSource ds = buildRealDataSource();
        ClickHouseWideTableMetricsTool tool = new ClickHouseWideTableMetricsTool(ds);
        ToolResultBlock r = tool.clickhouseQuery(
                TABLE_NAME,
                TRACE_FIELDS,
                null,
                Map.of("createdAt", "(SELECT MAX(createdAt) FROM trace_recent)"));
        String text = extractText(r);
        System.out.println("=== realQueryWithSubqueryFilter output ===\n" + text);
        assertTrue(text.contains("[clickhouse_query]"),
                "expected clickhouse_query marker, got: " + text);
        assertTrue(text.contains("subqueryFilters="),
                "expected subqueryFilters echoed in result, got: " + text);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "CK_HOST", matches = ".+")
    void realQueryRejectsUnknownColumn() {
        DataSource ds = buildRealDataSource();
        ClickHouseWideTableMetricsTool tool = new ClickHouseWideTableMetricsTool(ds);
        ToolResultBlock r = tool.clickhouseQuery(
                TABLE_NAME,
                List.of("sessionId_real", "userId"),
                null, null);
        String text = extractText(r);
        System.out.println("=== realQueryRejectsUnknownColumn output ===\n" + text);
        assertTrue(text.contains("不在表") && text.contains("实际列集合内"),
                "expected column whitelist rejection, got: " + text);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "CK_HOST", matches = ".+")
    void realQueryRejectsUnknownTable() {
        DataSource ds = buildRealDataSource();
        ClickHouseWideTableMetricsTool tool = new ClickHouseWideTableMetricsTool(ds);
        ToolResultBlock r = tool.clickhouseQuery(
                "trace_nonexistent_table",
                List.of("sessionId"),
                null, null);
        String text = extractText(r);
        System.out.println("=== realQueryRejectsUnknownTable output ===\n" + text);
        assertTrue(text.contains("system.columns 查不到列"),
                "expected missing-table rejection, got: " + text);
    }

    // ----------------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------------

    private static DataSource buildRealDataSource() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:clickhouse://" + env("CK_HOST", "124.222.194.178")
                + ":" + env("CK_PORT", "8123") + "/" + env("CK_DB", "default")
                + "?compress=0");
        cfg.setUsername(env("CK_USER", "test_user"));
        cfg.setPassword(env("CK_PASS", "Ym5BTURlLmEgFct0"));
        cfg.setDriverClassName("com.clickhouse.jdbc.ClickHouseDriver");
        cfg.setMaximumPoolSize(2);
        cfg.setMinimumIdle(1);
        cfg.setPoolName("ClickHouseWideTableMetricsToolTest");
        return new HikariDataSource(cfg);
    }

    private static String env(String k, String def) {
        String v = System.getenv(k);
        return (v == null || v.isEmpty()) ? def : v;
    }

    private static String extractText(ToolResultBlock block) {
        if (block == null) return "(null ToolResultBlock)";
        if (block.getOutput() == null) return "(null output)";
        StringBuilder sb = new StringBuilder();
        for (var b : block.getOutput()) {
            if (b instanceof io.agentscope.core.message.TextBlock tb) {
                sb.append(tb.getText());
            }
        }
        return sb.toString();
    }
}
