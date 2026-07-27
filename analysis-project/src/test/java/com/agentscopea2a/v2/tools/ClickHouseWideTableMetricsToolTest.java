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
 * <p>Two layers (mirrors {@link WideTableMetricsToolTest}):
 * <ul>
 *   <li>{@link #rejectMalformedTableName()} etc. -- pure validation, no DB needed.</li>
 *   <li>{@link #realQueryTraceRecent()} -- hits real ClickHouse, gated by CK_HOST env var.
 *       Run from IDE or {@code mvn test -DCK_HOST=... -DCK_USER=... -DCK_PASS=...}.</li>
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
                null);
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
                null);
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
                Map.of("userId; DROP", "x"));
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
                null);
        String text = extractText(r);
        assertTrue(text.contains("clickHouseDataSource 未注入"),
                "expected missing datasource rejection, got: " + text);
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
                null);
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
    void realQueryRejectsUnknownColumn() {
        DataSource ds = buildRealDataSource();
        ClickHouseWideTableMetricsTool tool = new ClickHouseWideTableMetricsTool(ds);
        // sessionId_real 不在 trace_recent 实际列集合内 (实际列是 sessionId camelCase)
        ToolResultBlock r = tool.clickhouseQuery(
                TABLE_NAME,
                List.of("sessionId_real", "userId"),
                null);
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
                null);
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
