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

import com.agentscopea2a.entity.SqlRegistryEntry;
import com.agentscopea2a.mapper.mysql.SqlRegistryMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.agentscope.core.message.ToolResultBlock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Tests for {@link SqlRegistryExecTool}.
 *
 * <p>Three layers:
 * <ul>
 *   <li>{@link #rejectUnknownSqlId()} etc. -- pure validation + sql_registry lookup, no real DB needed.</li>
 *   <li>{@link #rejectMalformedTemplateShape()} etc. -- template shape + forbidden keyword validation, no DB.</li>
 *   <li>{@link #rejectUndeclaredParam()} etc. -- params_schema validation, no DB.</li>
 *   <li>{@link #realExecTraceRecentStatsByUser()} -- hits real ClickHouse, gated by CK_HOST.</li>
 * </ul>
 */
class SqlRegistryExecToolTest {

    private static final String CK_SQL_ID = "trace_recent_stats_by_user";
    private static final String GAUSS_SQL_ID = "q2_1_metrics_by_dept_version";

    // ----------------------------------------------------------------------
    // Stub SqlRegistryMapper
    // ----------------------------------------------------------------------

    /**
     * In-memory stub for {@link SqlRegistryMapper} -- returns preset entries by sql_id,
     * no DB needed. Lets validation tests run without MySQL sql_registry wired.
     */
    private static final class StubMapper implements SqlRegistryMapper {
        private final Map<String, SqlRegistryEntry> entries = new HashMap<>();

        StubMapper put(SqlRegistryEntry e) {
            entries.put(e.getSqlId(), e);
            return this;
        }

        @Override
        public SqlRegistryEntry selectBySqlId(String sqlId) {
            return entries.get(sqlId);
        }

        @Override
        public List<SqlRegistryEntry> listAllEnabled() {
            return List.copyOf(entries.values());
        }
    }

    private static SqlRegistryEntry ckEntry() {
        return SqlRegistryEntry.builder()
                .sqlId(CK_SQL_ID)
                .name("用户会话统计")
                .description("按 userId 分组, 取会话数/平均时长/平均事件数/完成数. 支持时间范围筛选.")
                .datasource("clickhouse")
                .sqlTemplate(
                        "SELECT userId, count() AS 会话数, avg(totalDurationMs) AS 平均时长ms, "
                                + "avg(eventCount) AS 平均事件数, "
                                + "sumIf(1, status = 'COMPLETED') AS 完成数 "
                                + "FROM default.trace_recent "
                                + "WHERE userId = :userId AND createdAt >= :startTime "
                                + "GROUP BY userId LIMIT :limit")
                .paramsSchema(
                        "[{\"name\":\"userId\",\"type\":\"string\",\"required\":true,\"description\":\"用户 ID\"},"
                                + "{\"name\":\"startTime\",\"type\":\"date\",\"required\":true,\"description\":\"开始日期 YYYY-MM-DD\"},"
                                + "{\"name\":\"limit\",\"type\":\"int\",\"required\":false,\"description\":\"最大返回行数\"}]")
                .enabled(1)
                .build();
    }

    private static SqlRegistryEntry gaussEntry() {
        return SqlRegistryEntry.builder()
                .sqlId(GAUSS_SQL_ID)
                .name("部门+版本 Q2-1 指标查询")
                .description("按开发部门 + 版本计划筛选宽表, 自动取最新 in_date.")
                .datasource("gauss")
                .sqlTemplate(
                        "SELECT projectzh_no AS 项目编号, dev_dept AS 开发部门, "
                                + "score_status_2_1 AS Q2_1打分状态, standard_is_2_1 AS Q2_1是否达标 "
                                + "FROM dsqa_dwd_req_item_app_portrait_wide_inf "
                                + "WHERE dev_dept = :dept AND version_plan = :version "
                                + "AND in_date = (SELECT MAX(in_date) FROM dsqa_dwd_req_item_app_portrait_wide_inf) "
                                + "LIMIT :limit")
                .paramsSchema(
                        "[{\"name\":\"dept\",\"type\":\"string\",\"required\":true,\"description\":\"开发部门\"},"
                                + "{\"name\":\"version\",\"type\":\"string\",\"required\":true,\"description\":\"版本计划\"},"
                                + "{\"name\":\"limit\",\"type\":\"int\",\"required\":false,\"description\":\"最大返回行数\"}]")
                .enabled(1)
                .build();
    }

    // ----------------------------------------------------------------------
    // Validation - no DB
    // ----------------------------------------------------------------------

    @Test
    void rejectEmptySqlId() {
        SqlRegistryExecTool tool = newTool(new StubMapper());
        ToolResultBlock r = tool.sqlRegistryExec("", null);
        String text = extractText(r);
        assertTrue(text.contains("sqlId 为空"), "expected empty sqlId rejection, got: " + text);
    }

    @Test
    void rejectUnknownSqlId() {
        SqlRegistryExecTool tool = newTool(new StubMapper());
        ToolResultBlock r = tool.sqlRegistryExec("nonexistent_id", null);
        String text = extractText(r);
        assertTrue(text.contains("不存在或已禁用"),
                "expected unknown sql_id rejection, got: " + text);
    }

    @Test
    void rejectWhenMapperMissing() {
        // SqlRegistryMapper null - simulates bean wiring failure
        SqlRegistryExecTool tool = new SqlRegistryExecTool(null, null, null, null);
        ToolResultBlock r = tool.sqlRegistryExec(CK_SQL_ID, null);
        String text = extractText(r);
        assertTrue(text.contains("registryMapper 未注入"),
                "expected mapper missing rejection, got: " + text);
    }

    @Test
    void rejectMalformedTemplateShape() {
        // SQL missing FROM clause -- should fail TEMPLATE_SHAPE_PATTERN
        SqlRegistryEntry bad = SqlRegistryEntry.builder()
                .sqlId("bad_shape")
                .name("bad")
                .description("bad")
                .datasource("clickhouse")
                .sqlTemplate("SELECT 1")  // no FROM
                .paramsSchema("[]")
                .enabled(1)
                .build();
        SqlRegistryExecTool tool = newTool(new StubMapper().put(bad));
        ToolResultBlock r = tool.sqlRegistryExec("bad_shape", null);
        String text = extractText(r);
        assertTrue(text.contains("必须形如 'SELECT ... FROM ...'"),
                "expected template shape rejection, got: " + text);
    }

    @Test
    void rejectTemplateWithDropKeyword() {
        // DBA accidentally wrote DROP in the template
        SqlRegistryEntry bad = SqlRegistryEntry.builder()
                .sqlId("bad_drop")
                .name("bad")
                .description("bad")
                .datasource("clickhouse")
                .sqlTemplate("SELECT * FROM foo; DROP TABLE bar")
                .paramsSchema("[]")
                .enabled(1)
                .build();
        SqlRegistryExecTool tool = newTool(new StubMapper().put(bad));
        ToolResultBlock r = tool.sqlRegistryExec("bad_drop", null);
        String text = extractText(r);
        assertTrue(text.contains("禁用关键字") && text.contains("DBA"),
                "expected forbidden keyword rejection, got: " + text);
    }

    @Test
    void rejectTemplateWithSystemTable() {
        // DBA accidentally referenced system.tables (info leak)
        SqlRegistryEntry bad = SqlRegistryEntry.builder()
                .sqlId("bad_sys")
                .name("bad")
                .description("bad")
                .datasource("clickhouse")
                .sqlTemplate("SELECT * FROM system.tables WHERE database = 'default'")
                .paramsSchema("[]")
                .enabled(1)
                .build();
        SqlRegistryExecTool tool = newTool(new StubMapper().put(bad));
        ToolResultBlock r = tool.sqlRegistryExec("bad_sys", null);
        String text = extractText(r);
        assertTrue(text.contains("禁用关键字") || text.contains("system."),
                "expected system table rejection, got: " + text);
    }

    @Test
    void rejectUnknownDatasource() {
        // entry.datasource = 'oracle' - not in mysql/gauss/clickhouse
        SqlRegistryEntry bad = SqlRegistryEntry.builder()
                .sqlId("bad_ds")
                .name("bad")
                .description("bad")
                .datasource("oracle")
                .sqlTemplate("SELECT * FROM foo")
                .paramsSchema("[]")
                .enabled(1)
                .build();
        SqlRegistryExecTool tool = newTool(new StubMapper().put(bad));
        ToolResultBlock r = tool.sqlRegistryExec("bad_ds", null);
        String text = extractText(r);
        assertTrue(text.contains("datasource") && text.contains("不在支持列表"),
                "expected unknown datasource rejection, got: " + text);
    }

    @Test
    void rejectUndeclaredParam() {
        // LLM tries to pass a param that's not in params_schema (injection attempt)
        SqlRegistryExecTool tool = newTool(new StubMapper().put(ckEntry()));
        ToolResultBlock r = tool.sqlRegistryExec(CK_SQL_ID,
                Map.of("userId", "alice", "startTime", "2026-07-01",
                        "tableName", "system.users"));  // tableName not declared
        String text = extractText(r);
        assertTrue(text.contains("参数 'tableName'") && text.contains("不在 sql_id")
                        && text.contains("params_schema 内"),
                "expected undeclared param rejection, got: " + text);
    }

    @Test
    void rejectMissingRequiredParam() {
        // LLM forgot userId (required) -- only passed startTime + limit
        SqlRegistryExecTool tool = newTool(new StubMapper().put(ckEntry()));
        ToolResultBlock r = tool.sqlRegistryExec(CK_SQL_ID,
                Map.of("startTime", "2026-07-01", "limit", 100));
        String text = extractText(r);
        assertTrue(text.contains("缺少必填参数") && text.contains("userId"),
                "expected missing required param rejection, got: " + text);
    }

    @Test
    void rejectTemplateParamMismatch() {
        // DBA wrote :nonExistent in template but didn't declare it in params_schema
        SqlRegistryEntry bad = SqlRegistryEntry.builder()
                .sqlId("bad_param_mismatch")
                .name("bad")
                .description("bad")
                .datasource("clickhouse")
                .sqlTemplate("SELECT * FROM foo WHERE id = :nonExistent LIMIT 100")
                .paramsSchema("[]")  // empty - no params declared
                .enabled(1)
                .build();
        SqlRegistryExecTool tool = newTool(new StubMapper().put(bad));
        ToolResultBlock r = tool.sqlRegistryExec("bad_param_mismatch", null);
        String text = extractText(r);
        assertTrue(text.contains(":nonExistent") && text.contains("params_schema 未声明"),
                "expected template param mismatch rejection, got: " + text);
    }

    @Test
    void rejectWhenDatasourceMissing() {
        // All DataSources null but datasource name ('clickhouse') is in the dataSourceMap.
        // Tool should distinguish "unknown datasource" from "datasource bean not wired" --
        // in the latter case, returns "不可用: DataSource bean 未注入".
        SqlRegistryExecTool tool = new SqlRegistryExecTool(null, null, null, new StubMapper().put(ckEntry()));
        ToolResultBlock r = tool.sqlRegistryExec(CK_SQL_ID, Map.of("userId", "alice", "startTime", "2026-07-01"));
        String text = extractText(r);
        // Validation passed (no "拒绝执行" for shape/params), but DataSource bean missing
        assertTrue(text.contains("不可用") && text.contains("DataSource bean 未注入"),
                "expected 'bean not wired' rejection after validation passes, got: " + text);
    }

    @Test
    void acceptWellFormedTemplateInValidation() {
        // Well-formed entry should pass all validation stages (template shape + params +
        // template param match). With all-null DataSources, it'll fail at the datasource
        // routing stage with "DataSource bean 未注入" -- proving validation didn't reject it.
        SqlRegistryExecTool tool = new SqlRegistryExecTool(null, null, null, new StubMapper().put(ckEntry()));
        ToolResultBlock r = tool.sqlRegistryExec(CK_SQL_ID, Map.of("userId", "alice", "startTime", "2026-07-01"));
        String text = extractText(r);
        // Should NOT contain template shape / forbidden / param validation rejections
        assertTrue(!text.contains("必须形如 'SELECT ... FROM ...'")
                        && !text.contains("禁用关键字")
                        && !text.contains("不在 sql_id")
                        && !text.contains("缺少必填参数")
                        && !text.contains("params_schema 未声明"),
                "expected well-formed template to pass all validation, got: " + text);
    }

    // ----------------------------------------------------------------------
    // Integration - real ClickHouse (gated)
    // ----------------------------------------------------------------------

    @Test
    @EnabledIfEnvironmentVariable(named = "CK_HOST", matches = ".+")
    void realExecTraceRecentStatsByUser() {
        DataSource ckDs = buildRealClickHouseDataSource();
        SqlRegistryExecTool tool = new SqlRegistryExecTool(null, null, ckDs,
                new StubMapper().put(ckEntry()));
        ToolResultBlock r = tool.sqlRegistryExec(CK_SQL_ID,
                Map.of("userId", "alice", "startTime", "2026-01-01"));
        String text = extractText(r);
        System.out.println("=== realExecTraceRecentStatsByUser output ===\n" + text);
        assertNotNull(text);
        assertTrue(text.contains("[sql_registry_exec]"),
                "expected sql_registry_exec marker, got: " + text);
        // trace_recent may or may not have alice's data -- just verify it executes + returns markdown table
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "CK_HOST", matches = ".+")
    void realExecRejectsUndeclaredParamOnRealDs() {
        DataSource ckDs = buildRealClickHouseDataSource();
        SqlRegistryExecTool tool = new SqlRegistryExecTool(null, null, ckDs,
                new StubMapper().put(ckEntry()));
        ToolResultBlock r = tool.sqlRegistryExec(CK_SQL_ID,
                Map.of("userId", "alice", "tableName", "system.users"));
        String text = extractText(r);
        System.out.println("=== realExecRejectsUndeclaredParamOnRealDs output ===\n" + text);
        // Undeclared param rejection should fire BEFORE reaching the DB
        assertTrue(text.contains("参数 'tableName'") && text.contains("不在 sql_id"),
                "expected undeclared param rejection before DB hit, got: " + text);
    }

    // ----------------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------------

    private static SqlRegistryExecTool newTool(SqlRegistryMapper mapper) {
        // null DataSources are fine for validation tests (validation fails before DB hit)
        return new SqlRegistryExecTool(null, null, null, mapper);
    }

    private static DataSource buildRealClickHouseDataSource() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:clickhouse://" + env("CK_HOST", "127.0.0.1")
                + ":" + env("CK_PORT", "8123") + "/" + env("CK_DB", "default"));
        cfg.setUsername(env("CK_USER", "default"));
        cfg.setPassword(env("CK_PASS", ""));
        cfg.setDriverClassName("com.clickhouse.jdbc.ClickHouseDriver");
        cfg.setMaximumPoolSize(2);
        cfg.setMinimumIdle(1);
        cfg.setPoolName("SqlRegistryExecToolTest-CK");
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
