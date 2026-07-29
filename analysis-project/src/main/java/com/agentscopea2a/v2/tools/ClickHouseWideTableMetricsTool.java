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

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 通用 ClickHouse 宽表查询工具 -- 与 {@link WideTableMetricsTool} 结构对齐, 走
 * {@code SELECT <fields> FROM <table> WHERE <filters> LIMIT 10000} 并以 markdown 表返回。
 * 不在 Java 里写任何业务指标公式, 聚合/计数/比率都走 {@code python_exec} + pandas
 * (由业务 skill 模板决定)。
 *
 * <p>与 GaussDB 版的 3 处差异:
 * <ul>
 *   <li>DataSource: {@code clickHouseDataSource} (由 {@code ClickHouseConfig} 注入)。</li>
 *   <li>schema: 硬编码 {@code default}, 不在参数中暴露给 LLM。</li>
 *   <li>列名校验 SQL: 走 ClickHouse 原生 {@code system.columns} (而非 information_schema.columns),
 *       字段名 {@code name}, 库字段 {@code database}, 表字段 {@code table}。</li>
 * </ul>
 *
 * <p><b>SQL 注入防护</b> (与 GaussDB 版一致):
 * <ul>
 *   <li>表名: 正则 {@code ^[a-zA-Z_][a-zA-Z0-9_]*$}。</li>
 *   <li>字段名 / filter 列名: 从 {@code system.columns} 查实际列名, 只接受集合内字段。</li>
 *   <li>filter 值: {@code PreparedStatement.setXxx()} 参数化绑定。</li>
 *   <li>LIMIT: 工具内部固定 10000。</li>
 *   <li>Connection 默认只读。</li>
 * </ul>
 *
 * <p><b>列名双引号:</b> ClickHouse 0.6.x 接受 ANSI 双引号包裹标识符, 与 GaussDB 版拼 SQL 方式一致。
 *
 * <p><b>Bean wiring:</b> 由 {@link com.agentscopea2a.v2.config.V2ToolConfig} 创建 bean,
 * 注入 {@code clickHouseDataSource}。
 */
public class ClickHouseWideTableMetricsTool {

    private static final Logger log = LoggerFactory.getLogger(ClickHouseWideTableMetricsTool.class);

    /** schema 固定为 default, 不在工具参数中暴露给 LLM。 */
    private static final String SCHEMA = "default";

    /** 表名格式, 仅字母数字下划线。 */
    private static final Pattern TABLE_NAME_PATTERN =
            Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    /** 字段名 / filter 列名格式。 */
    private static final Pattern COLUMN_NAME_PATTERN =
            Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    /**
     * 子查询 filter value 严格白名单: 必须形如 {@code (SELECT ...)} (圆括号包裹 + SELECT 开头)。
     * 用于支持 {@code {"in_date":"(SELECT MAX(in_date) FROM ...)"}} 这类语义,
     * value 不走参数化绑定而是字符串拼到 WHERE, 所以必须严格限制形态防 SQL 注入。
     */
    private static final Pattern SUBQUERY_PATTERN =
            Pattern.compile("^\\(\\s*SELECT\\s+[\\s\\S]+\\)$", Pattern.CASE_INSENSITIVE);

    /**
     * 子查询内禁出现的 SQL 关键字 / 元字符: 分号、注释符、DDL/DML 关键字。
     * 白名单已强制 {@code (SELECT ...)} 形式, 这些关键字本就不该出现在子查询里,
     * 出现即视为注入企图, 一律拒执行。
     */
    private static final Pattern SUBQUERY_FORBIDDEN_PATTERN = Pattern.compile(
            "(;|--|/\\*|\\*/|\\bDROP\\b|\\bDELETE\\b|\\bUPDATE\\b|\\bINSERT\\b|"
                    + "\\bALTER\\b|\\bTRUNCATE\\b|\\bCREATE\\b|\\bGRANT\\b|\\bREVOKE\\b|"
                    + "\\bMERGE\\b|\\bCALL\\b|\\bEXEC\\b|\\bEXECUTE\\b)",
            Pattern.CASE_INSENSITIVE);

    /** LIMIT 固定 10000, 不在工具参数中暴露给 LLM。 */
    private static final int ROW_LIMIT = 10_000;

    private final DataSource clickHouseDataSource;

    public ClickHouseWideTableMetricsTool(DataSource clickHouseDataSource) {
        this.clickHouseDataSource = clickHouseDataSource;
    }

    @Tool(
            name = "clickhouse_query",
            description =
                    "通用 ClickHouse 宽表查询. SELECT 指定字段 + WHERE 等值筛选 -> markdown 表 (>=4 行时自动落 CSV artifact + 预览). "
                            + "不做聚合/计数, 聚合用 python_exec + pandas. "
                            + "filters 是 JSON 列->值映射, 等值 AND 连接 (不支持 OR/IN/LIKE), 值走参数化绑定防注入. "
                            + "subqueryFilters 是 JSON 列->子查询字符串映射, 用于等值比较子查询场景 (如 {\"in_date\":\"(SELECT MAX(in_date) FROM trace_recent)\"}), "
                            + "value 必须形如 (SELECT ...) 且禁 DDL/DML 关键字. "
                            + "列名会在 system.columns 中校验, schema 固定为 default, 只需传表名.")
    public ToolResultBlock clickhouseQuery(
            @ToolParam(
                            name = "table",
                            description =
                                    "宽表名 (不含 schema, schema 固定为 default), 如 trace_recent")
                    String table,
            @ToolParam(
                            name = "fields",
                            description =
                                    "SELECT 字段列表, 如 [\"sessionId\",\"userId\",\"totalDurationMs\",\"status\"]")
                    List<String> fields,
            @ToolParam(
                            name = "filters",
                            description =
                                    "WHERE 等值条件, JSON 对象, 如 {\"userId\":\"alice\",\"status\":\"completed\"}. "
                                            + "列名必须在表内存在, 值走参数化绑定防注入.",
                            required = false)
                    Map<String, Object> filters,
            @ToolParam(
                            name = "subqueryFilters",
                            description =
                                    "WHERE 等值子查询条件, JSON 对象, 如 {\"in_date\":\"(SELECT MAX(in_date) FROM trace_recent)\"}. "
                                    + "value 必须形如 (SELECT ...), 禁分号/注释符/DDL/DML 关键字 (DROP/INSERT/UPDATE 等). "
                                    + "用于 '最新日期'/'最大版本' 这类语义, value 直接字符串拼到 WHERE 不走参数化绑定 (故白名单严格).",
                            required = false)
                    Map<String, Object> subqueryFilters) {

        if (table == null || table.isBlank()) {
            return ToolResultBlock.text("clickhouse_query 拒绝执行: table 为空,必须是宽表名 (schema 固定为 default)");
        }
        if (fields == null || fields.isEmpty()) {
            return ToolResultBlock.text("clickhouse_query 拒绝执行: fields 为空,至少需要一个字段");
        }

        if (!TABLE_NAME_PATTERN.matcher(table).matches()) {
            return ToolResultBlock.text(
                    "clickhouse_query 拒绝执行: table '" + table
                            + "' 不是合法表名 (仅字母数字下划线, schema 固定为 default 由工具自动拼接)");
        }
        String tableName = table;

        for (String f : fields) {
            if (f == null || !COLUMN_NAME_PATTERN.matcher(f).matches()) {
                return ToolResultBlock.text(
                        "clickhouse_query 拒绝执行: 字段名 '" + f + "' 不合法 (仅字母数字下划线)");
            }
        }

        Map<String, Object> filterMap = filters == null ? Collections.emptyMap() : new LinkedHashMap<>(filters);
        for (String col : filterMap.keySet()) {
            if (!COLUMN_NAME_PATTERN.matcher(col).matches()) {
                return ToolResultBlock.text(
                        "clickhouse_query 拒绝执行: filter 列名 '" + col + "' 不合法 (仅字母数字下划线)");
            }
        }

        Map<String, Object> subqueryMap = subqueryFilters == null
                ? Collections.emptyMap()
                : new LinkedHashMap<>(subqueryFilters);
        for (Map.Entry<String, Object> e : subqueryMap.entrySet()) {
            String col = e.getKey();
            if (!COLUMN_NAME_PATTERN.matcher(col).matches()) {
                return ToolResultBlock.text(
                        "clickhouse_query 拒绝执行: subqueryFilters 列名 '" + col + "' 不合法 (仅字母数字下划线)");
            }
            Object v = e.getValue();
            if (!(v instanceof String sv)) {
                return ToolResultBlock.text(
                        "clickhouse_query 拒绝执行: subqueryFilters['" + col + "'] 的 value 必须是字符串, 形如 (SELECT ...), 实际类型: "
                                + (v == null ? "null" : v.getClass().getSimpleName()));
            }
            if (!SUBQUERY_PATTERN.matcher(sv).matches()) {
                return ToolResultBlock.text(
                        "clickhouse_query 拒绝执行: subqueryFilters['" + col + "'] 的 value 必须形如 (SELECT ...), 实际: " + sv);
            }
            if (SUBQUERY_FORBIDDEN_PATTERN.matcher(sv).find()) {
                return ToolResultBlock.text(
                        "clickhouse_query 拒绝执行: subqueryFilters['" + col + "'] 含禁用关键字/元字符 (;/--/*/DROP/DELETE/UPDATE/INSERT/ALTER/TRUNCATE/CREATE/GRANT/REVOKE/MERGE/CALL/EXEC/EXECUTE), 实际: " + sv);
            }
        }

        if (clickHouseDataSource == null) {
            return ToolResultBlock.text(
                    "clickhouse_query 不可用: clickHouseDataSource 未注入 (spring.datasource.hikari.clickhouse.enabled=false?)");
        }

        long start = System.currentTimeMillis();
        try (Connection conn = clickHouseDataSource.getConnection()) {
            conn.setReadOnly(true);
            Set<String> actualColumns = fetchColumnNames(conn, tableName);
            if (actualColumns.isEmpty()) {
                return ToolResultBlock.text(
                        "clickhouse_query 拒绝执行: 表 " + table
                                + " 在 system.columns 查不到列 (schema/table 名错误或无权限)");
            }
            List<String> invalidFields = new ArrayList<>();
            for (String f : fields) {
                if (!actualColumns.contains(f)) {
                    invalidFields.add(f);
                }
            }
            if (!invalidFields.isEmpty()) {
                return ToolResultBlock.text(
                        "clickhouse_query 拒绝执行: 字段 " + invalidFields + " 不在表 " + table
                                + " 的实际列集合内. 可用列见 system.columns (共 "
                                + actualColumns.size() + " 列)");
            }
            List<String> invalidFilterCols = new ArrayList<>();
            for (String col : filterMap.keySet()) {
                if (!actualColumns.contains(col)) {
                    invalidFilterCols.add(col);
                }
            }
            if (!invalidFilterCols.isEmpty()) {
                return ToolResultBlock.text(
                        "clickhouse_query 拒绝执行: filter 列 " + invalidFilterCols + " 不在表 " + table
                                + " 的实际列集合内.");
            }
            List<String> invalidSubCols = new ArrayList<>();
            for (String col : subqueryMap.keySet()) {
                if (!actualColumns.contains(col)) {
                    invalidSubCols.add(col);
                }
            }
            if (!invalidSubCols.isEmpty()) {
                return ToolResultBlock.text(
                        "clickhouse_query 拒绝执行: subqueryFilters 列 " + invalidSubCols + " 不在表 " + table
                                + " 的实际列集合内.");
            }

            String sql = buildSql(tableName, fields, filterMap, subqueryMap);
            log.info("clickhouse_query SQL: {} | filter values: {} | subqueryFilters: {}", sql, filterMap.values(), subqueryMap);

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                int idx = 1;
                for (Object v : filterMap.values()) {
                    ps.setObject(idx++, v);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    return renderResult(table, fields, filterMap, subqueryMap, rs, System.currentTimeMillis() - start);
                }
            }
        } catch (SQLException e) {
            log.error("clickhouse_query SQL 失败: table={} fields={} filters={} subqueryFilters={}", table, fields, filterMap, subqueryMap, e);
            return ToolResultBlock.text(
                    "clickhouse_query SQL 失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // ======================================================================
    // SQL 拼接 -- 列名一律双引号; filters 走参数化绑定 (?), subqueryFilters 直接拼 (已白名单)
    // ======================================================================

    private static String buildSql(String tableName,
                                   List<String> fields,
                                   Map<String, Object> filters,
                                   Map<String, Object> subqueryFilters) {
        StringBuilder sb = new StringBuilder("SELECT ");
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(quoteIdent(fields.get(i)));
        }
        sb.append(" FROM ").append(quoteIdent(SCHEMA)).append(".").append(quoteIdent(tableName));
        boolean hasWhere = false;
        if (!filters.isEmpty()) {
            sb.append(" WHERE ");
            hasWhere = true;
            int i = 0;
            for (String col : filters.keySet()) {
                if (i > 0) sb.append(" AND ");
                sb.append(quoteIdent(col)).append(" = ?");
                i++;
            }
        }
        if (!subqueryFilters.isEmpty()) {
            sb.append(hasWhere ? " AND " : " WHERE ");
            int i = 0;
            for (Map.Entry<String, Object> e : subqueryFilters.entrySet()) {
                if (i > 0) sb.append(" AND ");
                // value 已通过 SUBQUERY_PATTERN 白名单校验 (形如 (SELECT ...)), 直接字符串拼接
                sb.append(quoteIdent(e.getKey())).append(" = ").append(e.getValue());
                i++;
            }
        }
        sb.append(" LIMIT ").append(ROW_LIMIT);
        return sb.toString();
    }

    private static String quoteIdent(String ident) {
        return "\"" + ident + "\"";
    }

    // ======================================================================
    // 结果渲染 -- markdown 表, 与 ArtifactHandoffHook 期望格式一致
    // ======================================================================

    private static ToolResultBlock renderResult(String table, List<String> fields,
                                                Map<String, Object> filters,
                                                Map<String, Object> subqueryFilters,
                                                ResultSet rs, long elapsedMs) throws SQLException {
        StringBuilder md = new StringBuilder();
        md.append("[clickhouse_query] table=").append(table);
        if (!filters.isEmpty()) {
            md.append(" filters=").append(filters);
        }
        if (!subqueryFilters.isEmpty()) {
            md.append(" subqueryFilters=").append(subqueryFilters);
        }
        md.append(" limit=").append(ROW_LIMIT).append("\n\n");

        ResultSetMetaData meta = rs.getMetaData();
        int colCount = meta.getColumnCount();

        md.append("| ");
        for (int i = 1; i <= colCount; i++) {
            if (i > 1) md.append(" | ");
            md.append(escapeCell(meta.getColumnLabel(i)));
        }
        md.append(" |\n");

        md.append("|");
        for (int i = 1; i <= colCount; i++) {
            md.append("---|");
        }
        md.append("\n");

        int totalRows = 0;
        while (rs.next()) {
            totalRows++;
            md.append("| ");
            for (int i = 1; i <= colCount; i++) {
                if (i > 1) md.append(" | ");
                md.append(escapeCell(rs.getString(i)));
            }
            md.append(" |\n");
        }
        md.append("\n[clickhouse_query] 共 ").append(totalRows).append(" 行");
        md.append(", 耗时 ").append(elapsedMs).append(" ms");
        return ToolResultBlock.text(md.toString());
    }

    private static String escapeCell(String s) {
        if (s == null) return "";
        return s.replace("|", "\\|").replace("\n", " ").replace("\r", "");
    }

    // ======================================================================
    // system.columns 列名白名单 (ClickHouse 原生系统表, 权威性高于 information_schema)
    // ======================================================================

    private static Set<String> fetchColumnNames(Connection conn, String table) throws SQLException {
        String sql =
                "SELECT name FROM system.columns WHERE database = ? AND table = ? ORDER BY position";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, SCHEMA);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                Set<String> cols = new java.util.LinkedHashSet<>();
                while (rs.next()) {
                    cols.add(rs.getString(1));
                }
                return cols;
            }
        }
    }
}
