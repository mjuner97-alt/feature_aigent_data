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
 * Tests for {@link WideTableMetricsTool}.
 *
 * <p>Three layers:
 * <ul>
 *   <li>{@link #rejectMalformedTableName()} etc. -- pure validation, no DB needed.</li>
 *   <li>{@link #rejectSubqueryMissingParens()} etc. -- subquery filter value whitelist, no DB needed.</li>
 *   <li>{@link #realQueryQ2_1ByDepartment()} -- hits real GaussDB, gated by OG_HOST env var.</li>
 * </ul>
 */
class WideTableMetricsToolTest {

    private static final String TABLE_NAME =
            "dsqa_dwd_req_item_app_portrait_wide_inf";

    /**
     * Note: actual DB column names use {@code 0} (zero) instead of {@code _} (underscore)
     * due to CSV-import corruption when the table was created. The first column
     * {@code projectzh0no} additionally has a UTF-8 BOM prefix ({@code ﻿projectzh0no}),
     * so we omit it from the test field list. These are data-quality issues for the DBA to fix
     * (re-import with proper CSV encoding + BOM stripping); the tool itself works correctly.
     */
    private static final List<String> Q2_1_FIELDS = List.of(
            "projectzh0name", "dev0dept", "version0plan",
            "app", "product0line", "stat0group",
            "score0status0201", "standard0is0201");

    // ----------------------------------------------------------------------
    // Validation - no DB
    // ----------------------------------------------------------------------

    @Test
    void rejectMalformedTableName() {
        WideTableMetricsTool tool = new WideTableMetricsTool(null);
        ToolResultBlock r = tool.wideTableQuery(
                "productsgaussdb; DROP TABLE foo",
                List.of("projectzh_no"),
                null, null);
        String text = extractText(r);
        assertTrue(text.contains("不是合法表名"),
                "expected table name format rejection, got: " + text);
    }

    @Test
    void rejectInjectionInFieldName() {
        WideTableMetricsTool tool = new WideTableMetricsTool(null);
        ToolResultBlock r = tool.wideTableQuery(
                TABLE_NAME,
                List.of("projectzh_no; DROP TABLE foo"),
                null, null);
        String text = extractText(r);
        assertTrue(text.contains("字段名") && text.contains("不合法"),
                "expected field name rejection, got: " + text);
    }

    @Test
    void rejectInjectionInFilterColumn() {
        WideTableMetricsTool tool = new WideTableMetricsTool(null);
        ToolResultBlock r = tool.wideTableQuery(
                TABLE_NAME,
                List.of("projectzh_no"),
                Map.of("dev_dept; DROP", "x"),
                null);
        String text = extractText(r);
        assertTrue(text.contains("filter 列名") && text.contains("不合法"),
                "expected filter column name rejection, got: " + text);
    }

    @Test
    void rejectWhenDataSourceMissing() {
        WideTableMetricsTool tool = new WideTableMetricsTool(null);
        ToolResultBlock r = tool.wideTableQuery(
                TABLE_NAME,
                List.of("projectzh_no"),
                null, null);
        String text = extractText(r);
        assertTrue(text.contains("gaussDataSource 未注入"),
                "expected missing datasource rejection, got: " + text);
    }

    // ----------------------------------------------------------------------
    // subqueryFilters whitelist - no DB
    // ----------------------------------------------------------------------

    @Test
    void rejectSubqueryMissingParens() {
        WideTableMetricsTool tool = new WideTableMetricsTool(null);
        ToolResultBlock r = tool.wideTableQuery(
                TABLE_NAME,
                List.of("projectzh_no"),
                null,
                Map.of("in_date", "SELECT MAX(in_date) FROM " + TABLE_NAME));
        String text = extractText(r);
        assertTrue(text.contains("subqueryFilters") && text.contains("必须形如 (SELECT ...)"),
                "expected subquery format rejection, got: " + text);
    }

    @Test
    void rejectSubqueryWithDrop() {
        WideTableMetricsTool tool = new WideTableMetricsTool(null);
        // value 形如 (SELECT ...; DROP TABLE x) -- 通过 SUBQUERY_PATTERN 但被 FORBIDDEN 拦
        ToolResultBlock r = tool.wideTableQuery(
                TABLE_NAME,
                List.of("projectzh_no"),
                null,
                Map.of("in_date", "(SELECT MAX(in_date) FROM " + TABLE_NAME + "; DROP TABLE x)"));
        String text = extractText(r);
        assertTrue(text.contains("禁用关键字") && text.contains("DROP"),
                "expected DROP keyword rejection, got: " + text);
    }

    @Test
    void rejectSubqueryNonStringValue() {
        WideTableMetricsTool tool = new WideTableMetricsTool(null);
        ToolResultBlock r = tool.wideTableQuery(
                TABLE_NAME,
                List.of("projectzh_no"),
                null,
                Map.of("in_date", 123));
        String text = extractText(r);
        assertTrue(text.contains("必须是字符串") && text.contains("(SELECT ..."),
                "expected non-string value rejection, got: " + text);
    }

    @Test
    void rejectSubqueryColumnInjection() {
        WideTableMetricsTool tool = new WideTableMetricsTool(null);
        ToolResultBlock r = tool.wideTableQuery(
                TABLE_NAME,
                List.of("projectzh_no"),
                null,
                Map.of("in_date; DROP", "(SELECT '2026-07-01')"));
        String text = extractText(r);
        assertTrue(text.contains("subqueryFilters 列名") && text.contains("不合法"),
                "expected subquery column name rejection, got: " + text);
    }

    @Test
    void acceptWellFormedSubqueryInValidation() {
        // 形如 (SELECT ...) 的合法 value 应通过 value 白名单校验阶段 (走到 DB 后才报 dataSource 未注入)
        WideTableMetricsTool tool = new WideTableMetricsTool(null);
        ToolResultBlock r = tool.wideTableQuery(
                TABLE_NAME,
                List.of("projectzh_no"),
                null,
                Map.of("in_date", "(SELECT MAX(in_date) FROM " + TABLE_NAME + ")"));
        String text = extractText(r);
        assertTrue(text.contains("gaussDataSource 未注入"),
                "expected well-formed subquery to pass value validation and fail at DB-wiring stage, got: " + text);
    }

    // ----------------------------------------------------------------------
    // Integration - real GaussDB (gated)
    // ----------------------------------------------------------------------

    @Test
    @EnabledIfEnvironmentVariable(named = "OG_HOST", matches = ".+")
    void realQueryQ2_1ByDepartment() {
        DataSource ds = buildRealDataSource();
        WideTableMetricsTool tool = new WideTableMetricsTool(ds);
        ToolResultBlock r = tool.wideTableQuery(
                TABLE_NAME,
                Q2_1_FIELDS,
                Map.of("dev0dept", "杭州开发二部", "version0plan", "2026年7月份版本"),
                null);
        String text = extractText(r);
        System.out.println("=== realQueryQ2_1ByDepartment output ===\n" + text);
        assertNotNull(text);
        assertTrue(text.contains("[wide_table_query]"),
                "expected wide_table_query marker, got: " + text);
        assertTrue(text.contains("共 45 行"),
                "expected 45 rows for 杭州开发二部/2026年7月份版本, got: " + text);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "OG_HOST", matches = ".+")
    void realQueryQ2_1ByProductLine() {
        DataSource ds = buildRealDataSource();
        WideTableMetricsTool tool = new WideTableMetricsTool(ds);
        ToolResultBlock r = tool.wideTableQuery(
                TABLE_NAME,
                Q2_1_FIELDS,
                Map.of("product0line", "资产管理部"),
                null);
        String text = extractText(r);
        System.out.println("=== realQueryQ2_1ByProductLine output ===\n" + text);
        assertTrue(text.contains("[wide_table_query]"));
        assertTrue(text.contains("共 99 行"),
                "expected 99 rows for product0line=资产管理部 (only value in dev DB), got: " + text);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "OG_HOST", matches = ".+")
    void realQueryQ2_1ByStatGroup() {
        DataSource ds = buildRealDataSource();
        WideTableMetricsTool tool = new WideTableMetricsTool(ds);
        ToolResultBlock r = tool.wideTableQuery(
                TABLE_NAME,
                Q2_1_FIELDS,
                Map.of("stat0group", "杭州二部金融市场组"),
                null);
        String text = extractText(r);
        System.out.println("=== realQueryQ2_1ByStatGroup output ===\n" + text);
        assertTrue(text.contains("[wide_table_query]"));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "OG_HOST", matches = ".+")
    void realQueryRejectsUnknownColumn() {
        DataSource ds = buildRealDataSource();
        WideTableMetricsTool tool = new WideTableMetricsTool(ds);
        ToolResultBlock r = tool.wideTableQuery(
                TABLE_NAME,
                List.of("dev_dept", "score_status_2_1"),
                null, null);
        String text = extractText(r);
        System.out.println("=== realQueryRejectsUnknownColumn output ===\n" + text);
        assertTrue(text.contains("不在表") && text.contains("实际列集合内"),
                "expected column whitelist rejection, got: " + text);
    }

    // ----------------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------------

    private static DataSource buildRealDataSource() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:postgresql://" + env("OG_HOST", "116.148.121.37")
                + ":" + env("OG_PORT", "5432") + "/" + env("OG_DB", "postgres")
                + "?sslmode=disable");
        cfg.setUsername(env("OG_USER", "remote_app"));
        cfg.setPassword(env("OG_PASS", "MyPass@123"));
        cfg.setDriverClassName("org.postgresql.Driver");
        cfg.setMaximumPoolSize(2);
        cfg.setMinimumIdle(1);
        cfg.setPoolName("WideTableMetricsToolTest");
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
