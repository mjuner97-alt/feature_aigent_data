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

import com.agentscopea2a.entity.SqlRegistryEntry;
import com.agentscopea2a.mapper.mysql.SqlRegistryMapper;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.ParsedSql;
import org.springframework.jdbc.core.namedparam.NamedParameterUtils;

/**
 * 通过 sql_id 执行预注册 SQL.
 *
 * <p>业务方/DBA 把复杂 SQL (GROUP BY / CASE WHEN / JOIN / 窗口函数等) 预审后存到 MySQL 业务库的
 * {@code sql_registry} 配置表. 本工具按 {@code sqlId} 取出 sql_template + params_schema,
 * 校验模板 + 参数后参数化绑定执行, 把结果以 markdown 表返回.
 *
 * <p>与 {@code wide_table_query} / {@code clickhouse_query} 的分工:
 * <ul>
 *   <li>简单等值查询 (WHERE col=val) -> 走 wide_table_query / clickhouse_query</li>
 *   <li>等值子查询 (WHERE col=(SELECT MAX...)) -> 走 wide_table_query + subqueryFilters</li>
 *   <li>复杂聚合/JOIN/CASE WHEN/窗口函数 -> 走 sql_list -> sql_registry_exec</li>
 * </ul>
 *
 * <p><b>SQL 注入防护 (三层):</b>
 * <ul>
 *   <li><b>DBA 预审</b>: sql_template 由业务方/DBA 录入, LLM 不能改 SQL 结构.</li>
 *   <li><b>二次校验</b>:
 *     <ul>
 *       <li>{@link #TEMPLATE_SHAPE_PATTERN}: 必须 SELECT 开头 + 含 FROM.</li>
 *       <li>{@link #TEMPLATE_FORBIDDEN_PATTERN}: 禁 ; / 注释符 / DDL / DML / system.* 系统表
 *           (防 DBA 录入失误写了 DROP/DELETE 等).</li>
 *     </ul>
 *   </li>
 *   <li><b>参数白名单</b>: LLM 传的参数名必须在 params_schema 内, 多余参数一律拒执行
 *       (防 LLM 传未声明参数触发表名注入).</li>
 *   <li><b>参数化绑定</b>: 用 Spring {@link NamedParameterUtils} 把 {@code :param} 替换成 {@code ?},
 *       值走 {@link PreparedStatement#setObject(int, Object)}, 不字符串拼接.</li>
 *   <li><b>强制 LIMIT 兜底</b>: 模板没显式 LIMIT 自动加 LIMIT 10000.</li>
 *   <li><b>Connection 只读</b>: {@link Connection#setReadOnly(boolean)}.</li>
 * </ul>
 *
 * <p><b>多数据源路由</b>: 按 sql_registry 的 {@code datasource} 字段路由到对应 DataSource
 * (mysql / gauss / clickhouse).
 *
 * <p><b>Bean wiring:</b> 由 {@link com.agentscopea2a.v2.config.V2ToolConfig} 创建 bean,
 * 注入 mysqlDataSource + gaussDataSource + clickHouseDataSource + SqlRegistryMapper.
 */
public class SqlRegistryExecTool {

    private static final Logger log = LoggerFactory.getLogger(SqlRegistryExecTool.class);

    /** 兜底 LIMIT, 模板没显式 LIMIT 时自动追加. */
    private static final int ROW_LIMIT = 10_000;

    /**
     * 模板形态白名单: 必须 SELECT 开头 + 含 FROM.
     * 不强制 LIMIT (由 {@link #ensureLimit} 兜底), 不强制 WHERE (允许全表扫, 由 DBA 录入时审).
     */
    private static final Pattern TEMPLATE_SHAPE_PATTERN = Pattern.compile(
            "^\\s*SELECT\\s+[\\s\\S]+\\s+FROM\\s+[\\s\\S]+$",
            Pattern.CASE_INSENSITIVE);

    /**
     * 模板黑名单 (与 subqueryFilters 一致, 防 DBA 录入时失误写了 DDL/DML):
     * 分号 / 注释符 / DDL / DML / system.* 系统表 / INTO OUTFILE / LOAD_FILE.
     */
    private static final Pattern TEMPLATE_FORBIDDEN_PATTERN = Pattern.compile(
            "(;|--|/\\*|\\*/|"
                    + "\\bDROP\\b|\\bDELETE\\b|\\bUPDATE\\b|\\bINSERT\\b|\\bALTER\\b|"
                    + "\\bTRUNCATE\\b|\\bCREATE\\b|\\bGRANT\\b|\\bREVOKE\\b|\\bMERGE\\b|"
                    + "\\bCALL\\b|\\bEXEC\\b|\\bEXECUTE\\b|"
                    + "\\bINTO\\s+OUTFILE\\b|\\bINTO\\s+DUMPFILE\\b|\\bLOAD_FILE\\b|"
                    + "system\\.(tables|settings|processes|users|parts))",
            Pattern.CASE_INSENSITIVE);

    /** 匹配 SQL 中的 :param 占位符, 用于参数缺失检查. */
    private static final Pattern NAMED_PARAM_PATTERN = Pattern.compile(":(\\w+)");

    /** 已有 LIMIT 检测 (任意位置, 大小写不敏感). 匹配 `LIMIT 100` 或 `LIMIT :param`. */
    private static final Pattern HAS_LIMIT_PATTERN = Pattern.compile(
            "\\bLIMIT\\s+(\\d+|:\\w+)", Pattern.CASE_INSENSITIVE);

    private final Map<String, DataSource> dataSourceMap;
    private final SqlRegistryMapper registryMapper;

    public SqlRegistryExecTool(DataSource mysqlDs,
                               DataSource gaussDs,
                               DataSource clickHouseDs,
                               SqlRegistryMapper registryMapper) {
        Map<String, DataSource> m = new LinkedHashMap<>();
        m.put("mysql", mysqlDs);
        m.put("gauss", gaussDs);
        m.put("clickhouse", clickHouseDs);
        this.dataSourceMap = Collections.unmodifiableMap(m);
        this.registryMapper = registryMapper;
    }

    @Tool(
            name = "sql_registry_exec",
            description = "通过 sql_id 执行预注册 SQL (业务方/DBA 预审的复杂 SQL: GROUP BY/CASE WHEN/JOIN/窗口函数). "
                    + "先用 sql_list 查可用 sql_id 再传参. "
                    + "返回 markdown 表 (>=4 行时自动落 CSV artifact + 预览). "
                    + "简单等值查询走 wide_table_query / clickhouse_query, 复杂聚合走本工具.")
    public ToolResultBlock sqlRegistryExec(
            @ToolParam(
                    name = "sqlId",
                    description = "预注册 SQL 的 ID, 如 req_sign_status_by_item / trace_recent_stats_by_user. "
                            + "可用 sql_id 见 sql_list 返回")
                    String sqlId,
            @ToolParam(
                    name = "params",
                    description = "SQL 模板参数, JSON 对象, 如 {\"limit\":100, \"userId\":\"alice\"}. "
                            + "参数名必须在 params_schema 内 (多余参数会被拒执行防注入). "
                            + "参数名 + 类型见 sql_list 返回",
                    required = false)
                    Map<String, Object> params) {

        if (sqlId == null || sqlId.isBlank()) {
            return ToolResultBlock.text("sql_registry_exec 拒绝执行: sqlId 为空. 先调 sql_list 查可用 sql_id");
        }
        if (registryMapper == null) {
            return ToolResultBlock.text("sql_registry_exec 不可用: registryMapper 未注入 (检查 SqlRegistryMapper bean)");
        }

        // 1. 查 sql_registry 表
        SqlRegistryEntry entry;
        try {
            entry = registryMapper.selectBySqlId(sqlId);
        } catch (Exception e) {
            log.error("sql_registry_exec 查询 sql_registry 失败: sqlId={}", sqlId, e);
            return ToolResultBlock.text("sql_registry_exec 查询 sql_registry 失败: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        if (entry == null) {
            return ToolResultBlock.text("sql_registry_exec 拒绝执行: sql_id='" + sqlId
                    + "' 不存在或已禁用 (enabled=0). 先调 sql_list 查可用 sql_id");
        }

        String template = entry.getSqlTemplate();
        if (template == null || template.isBlank()) {
            return ToolResultBlock.text("sql_registry_exec 拒绝执行: sql_id='" + sqlId
                    + "' 的 sql_template 为空 (DBA 录入失误?)");
        }

        // 2. 二次校验 sql_template (防 DBA 失误)
        String err = validateTemplate(template);
        if (err != null) {
            log.warn("sql_registry_exec 模板校验失败: sqlId={} reason={}", sqlId, err);
            return ToolResultBlock.text(err + " (sqlId=" + sqlId + ")");
        }

        // 3. 解析 params_schema, 校验参数名 + 缺失必填参数 + 模板占位符与 schema 对齐
        // (放在 datasource 路由之前, 让参数校验失败时无需真实 DB 连接也能返回明确错误)
        Set<String> declaredParams = parseParamNames(entry.getParamsSchema());
        Map<String, Object> paramMap = params == null ? Collections.emptyMap() : new LinkedHashMap<>(params);

        for (Map.Entry<String, Object> e : paramMap.entrySet()) {
            if (!declaredParams.contains(e.getKey())) {
                return ToolResultBlock.text("sql_registry_exec 拒绝执行: 参数 '" + e.getKey()
                        + "' 不在 sql_id=" + sqlId + " 的 params_schema 内. 已声明参数: " + declaredParams
                        + " (多余参数一律拒执行, 防注入)");
            }
        }
        String missingRequired = checkRequiredParams(entry.getParamsSchema(), paramMap);
        if (missingRequired != null) {
            return ToolResultBlock.text("sql_registry_exec 拒绝执行: 缺少必填参数: " + missingRequired
                    + " (sqlId=" + sqlId + ")");
        }

        // 4. 模板里出现的 :param 若不在 params_schema 内 (DBA 录入时占位符写错), 拒执行防 NPE
        List<String> templateParams = extractTemplateParams(template);
        for (String p : templateParams) {
            if (!declaredParams.contains(p)) {
                return ToolResultBlock.text("sql_registry_exec 拒绝执行: sql_id='" + sqlId
                        + "' 的 sql_template 含占位符 :" + p + " 但 params_schema 未声明 (DBA 录入失误, 让 DBA 修正)");
            }
        }

        // 5. 按 datasource 路由
        String datasource = entry.getDatasource();
        String dsKey = datasource == null ? null : datasource.toLowerCase();
        DataSource ds = dsKey == null ? null : dataSourceMap.get(dsKey);
        if (ds == null) {
            if (dsKey != null && dataSourceMap.containsKey(dsKey)) {
                // datasource 名对 (mysql/gauss/clickhouse) 但 DataSource bean 未注入 -- 配置/wiring 问题
                return ToolResultBlock.text("sql_registry_exec 不可用: sql_id='" + sqlId
                        + "' 的 datasource='" + datasource + "' 对应 DataSource bean 未注入"
                        + " (检查 application-*.properties 数据源开关 + V2ToolConfig @Bean)");
            }
            // datasource 名不在 mysql/gauss/clickhouse 列表 -- DBA 录入失误
            return ToolResultBlock.text("sql_registry_exec 拒绝执行: sql_id='" + sqlId
                    + "' 的 datasource='" + datasource + "' 不在支持列表 (mysql/gauss/clickhouse)");
        }

        // 6. 强制 LIMIT 兜底
        String sql = ensureLimit(template);

        // 7. 执行 (NamedParameterUtils 处理 :param -> ?)
        long start = System.currentTimeMillis();
        try (Connection conn = ds.getConnection()) {
            conn.setReadOnly(true);

            MapSqlParameterSource paramSource = new MapSqlParameterSource();
            for (Map.Entry<String, Object> e : paramMap.entrySet()) {
                paramSource.addValue(e.getKey(), e.getValue());
            }

            ParsedSql parsed = NamedParameterUtils.parseSqlStatement(sql);
            String preparedSql = NamedParameterUtils.substituteNamedParameters(parsed, paramSource);
            Object[] paramArray = NamedParameterUtils.buildValueArray(parsed, paramSource, null);

            try (PreparedStatement ps = conn.prepareStatement(preparedSql)) {
                for (int i = 0; i < paramArray.length; i++) {
                    ps.setObject(i + 1, paramArray[i]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    return renderResult(sqlId, paramMap, rs, System.currentTimeMillis() - start);
                }
            }
        } catch (SQLException e) {
            log.error("sql_registry_exec SQL 失败: sqlId={} params={}", sqlId, paramMap, e);
            return ToolResultBlock.text("sql_registry_exec SQL 失败: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage()
                    + " (sqlId=" + sqlId + ")");
        } catch (Exception e) {
            log.error("sql_registry_exec 失败: sqlId={} params={}", sqlId, paramMap, e);
            return ToolResultBlock.text("sql_registry_exec 失败: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage()
                    + " (sqlId=" + sqlId + ")");
        }
    }

    // ======================================================================
    // 校验逻辑
    // ======================================================================

    private static String validateTemplate(String sql) {
        if (!TEMPLATE_SHAPE_PATTERN.matcher(sql).matches()) {
            return "sql_registry_exec 拒绝执行: sql_template 必须形如 'SELECT ... FROM ...'";
        }
        if (TEMPLATE_FORBIDDEN_PATTERN.matcher(sql).find()) {
            return "sql_registry_exec 拒绝执行: sql_template 含禁用关键字/元字符 "
                    + "(禁 ; / 注释符 / DDL / DML / system.* 系统表 / INTO OUTFILE / LOAD_FILE. "
                    + "DBA 录入时失误?)";
        }
        return null;
    }

    /**
     * 模板没显式 LIMIT 自动追加 LIMIT 10000.
     * 已有 LIMIT (任意位置, 包括子查询里的) -> 不动.
     */
    private static String ensureLimit(String sql) {
        if (HAS_LIMIT_PATTERN.matcher(sql).find()) {
            return sql;
        }
        return sql + " LIMIT " + ROW_LIMIT;
    }

    // ======================================================================
    // params_schema 解析
    // ======================================================================

    /**
     * 解析 params_schema JSON, 取所有声明的参数名.
     * [{"name":"userId","type":"string","required":true,"description":"..."},
     *  {"name":"limit","type":"int","required":false,"description":"..."}]
     * -> {userId, limit}
     */
    private static Set<String> parseParamNames(String paramsSchemaJson) {
        if (paramsSchemaJson == null || paramsSchemaJson.isBlank()) {
            return new LinkedHashSet<>();
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            List<?> list = mapper.readValue(paramsSchemaJson, List.class);
            Set<String> names = new LinkedHashSet<>();
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) continue;
                Object name = m.get("name");
                if (name != null) {
                    names.add(String.valueOf(name));
                }
            }
            return names;
        } catch (Exception e) {
            log.warn("parseParamNames 解析失败: {}", paramsSchemaJson, e);
            return new LinkedHashSet<>();
        }
    }

    /**
     * 检查必填参数是否都传了. 缺失返回参数名列表, 全传返回 null.
     */
    private static String checkRequiredParams(String paramsSchemaJson, Map<String, Object> paramMap) {
        if (paramsSchemaJson == null || paramsSchemaJson.isBlank()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            List<?> list = mapper.readValue(paramsSchemaJson, List.class);
            List<String> missing = new ArrayList<>();
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) continue;
                Object required = m.get("required");
                if (Boolean.TRUE.equals(required)) {
                    Object name = m.get("name");
                    if (name == null) continue;
                    String n = String.valueOf(name);
                    if (!paramMap.containsKey(n)) {
                        missing.add(n);
                    }
                }
            }
            return missing.isEmpty() ? null : missing.toString();
        } catch (Exception e) {
            log.warn("checkRequiredParams 解析失败: {}", paramsSchemaJson, e);
            return null;
        }
    }

    /**
     * 提取 sql_template 里所有 :param 占位符的参数名, 用于校验 DBA 录入时占位符与 params_schema 是否对齐.
     */
    private static List<String> extractTemplateParams(String sql) {
        List<String> names = new ArrayList<>();
        Matcher m = NAMED_PARAM_PATTERN.matcher(sql);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    // ======================================================================
    // 结果渲染 -- markdown 表, 与 ArtifactHandoffHook 期望格式一致
    // ======================================================================

    private static ToolResultBlock renderResult(String sqlId,
                                                Map<String, Object> params,
                                                ResultSet rs,
                                                long elapsedMs) throws SQLException {
        StringBuilder md = new StringBuilder();
        md.append("[sql_registry_exec] sqlId=").append(sqlId);
        if (!params.isEmpty()) {
            md.append(" params=").append(params);
        }
        md.append("\n\n");

        ResultSetMetaData meta = rs.getMetaData();
        int colCount = meta.getColumnCount();

        // header
        md.append("| ");
        for (int i = 1; i <= colCount; i++) {
            if (i > 1) md.append(" | ");
            md.append(escapeCell(meta.getColumnLabel(i)));
        }
        md.append(" |\n");

        // separator
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
        md.append("\n[sql_registry_exec] 共 ").append(totalRows).append(" 行");
        md.append(", 耗时 ").append(elapsedMs).append(" ms");
        return ToolResultBlock.text(md.toString());
    }

    private static String escapeCell(String s) {
        if (s == null) return "";
        return s.replace("|", "\\|").replace("\n", " ").replace("\r", "");
    }
}
