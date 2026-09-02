package com.agentscopea2a.v2.registry.service;

import com.agentscopea2a.entity.SqlRegistryEntry;
import com.agentscopea2a.mapper.gauss.SqlRegistryMapper;
import com.agentscopea2a.v2.auth.entity.DeveloperPlPersonInfo;
import com.agentscopea2a.v2.auth.mapper.DeveloperPlPersonInfoMapper;
import com.agentscopea2a.v2.registry.dto.SqlTestResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterUtils;
import org.springframework.jdbc.core.namedparam.ParsedSql;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * SQL 注册表管理服务.
 *
 * <p>提供 CRUD + SQL 测试功能. SQL 测试复用 {@link com.agentscopea2a.v2.tools.SqlRegistryExecTool}
 * 的校验+执行逻辑, 但返回结构化 JSON (非 markdown).
 */
@Service
public class SqlRegistryManageService {

    private static final Logger log = LoggerFactory.getLogger(SqlRegistryManageService.class);

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    /** 兜底 LIMIT */
    private static final int ROW_LIMIT = 10_000;

    /**
     * 模板形态白名单: 必须 SELECT 开头 + 含 FROM.
     * 管理页面比 Agent 工具更严格, 强制 SELECT 开头.
     */
    private static final Pattern TEMPLATE_SHAPE_PATTERN = Pattern.compile(
            "^\\s*SELECT\\s+[\\s\\S]+\\s+FROM\\s+[\\s\\S]+$",
            Pattern.CASE_INSENSITIVE);

    /**
     * 模板黑名单: 分号 / 注释符 / DDL / DML / system.* 系统表 / INTO OUTFILE / LOAD_FILE.
     * 与 SqlRegistryExecTool 保持一致.
     */
    private static final Pattern TEMPLATE_FORBIDDEN_PATTERN = Pattern.compile(
            "(;|--|/\\*|\\*/|"
                    + "\\bDROP\\b|\\bDELETE\\b|\\bUPDATE\\b|\\bINSERT\\b|\\bALTER\\b|"
                    + "\\bTRUNCATE\\b|\\bCREATE\\b|\\bGRANT\\b|\\bREVOKE\\b|\\bMERGE\\b|"
                    + "\\bCALL\\b|\\bEXEC\\b|\\bEXECUTE\\b|"
                    + "\\bINTO\\s+OUTFILE\\b|\\bINTO\\s+DUMPFILE\\b|\\bLOAD_FILE\\b|"
                    + "system\\.(tables|settings|processes|users|parts))",
            Pattern.CASE_INSENSITIVE);

    /** 匹配 SQL 中的 :param 占位符 */
    private static final Pattern NAMED_PARAM_PATTERN = Pattern.compile(":(\\w+)");

    /** 已有 LIMIT 检测 */
    private static final Pattern HAS_LIMIT_PATTERN = Pattern.compile(
            "\\bLIMIT\\s+(\\d+|:\\w+)", Pattern.CASE_INSENSITIVE);

    private final SqlRegistryMapper mapper;
    private final DeveloperPlPersonInfoMapper personInfoMapper;
    private final Map<String, DataSource> dataSourceMap;

    public SqlRegistryManageService(
            SqlRegistryMapper mapper,
            DeveloperPlPersonInfoMapper personInfoMapper,
            @org.springframework.beans.factory.annotation.Qualifier("mysqlDataSource") DataSource mysqlDataSource,
            @org.springframework.beans.factory.annotation.Qualifier("gaussCustomerDataSource") DataSource gaussDataSource,
            @org.springframework.beans.factory.annotation.Qualifier("clickHouseDataSource") DataSource clickHouseDataSource) {
        this.mapper = mapper;
        this.personInfoMapper = personInfoMapper;
        Map<String, DataSource> m = new LinkedHashMap<>();
        m.put("mysql", mysqlDataSource);
        m.put("gauss", gaussDataSource);
        m.put("clickhouse", clickHouseDataSource);
        this.dataSourceMap = Collections.unmodifiableMap(m);
    }

    // ======================================================================
    // CRUD
    // ======================================================================

    /**
     * 列表 (含禁用记录), 可选按 datasource / createdBy 筛选.
     * datasource 精确匹配 (忽略大小写); createdBy 模糊匹配 (忽略大小写, 适合输入框).
     */
    public List<SqlRegistryEntry> list(String datasource, String createdBy) {
        List<SqlRegistryEntry> entries = mapper.selectAll().stream()
                .filter(e -> datasource == null || datasource.isBlank()
                        || datasource.equalsIgnoreCase(e.getDatasource()))
                .filter(e -> createdBy == null || createdBy.isBlank()
                        || (e.getCreatedBy() != null
                                && e.getCreatedBy().toLowerCase().contains(createdBy.toLowerCase())))
                .collect(Collectors.toList());
        populateCreatedByNames(entries);
        return entries;
    }

    private void populateCreatedByNames(List<SqlRegistryEntry> entries) {
        List<String> userIds = entries.stream()
                .map(SqlRegistryEntry::getCreatedBy)
                .filter(Objects::nonNull)
                .filter(id -> !id.isBlank())
                .distinct()
                .toList();
        if (userIds.isEmpty()) return;

        List<DeveloperPlPersonInfo> people = personInfoMapper.selectByUserIds(userIds);
        Map<String, String> namesByUserId = new HashMap<>();
        if (people != null) {
            for (DeveloperPlPersonInfo person : people) {
                if (person.getUserId() != null && !person.getUserId().isBlank()
                        && person.getName() != null && !person.getName().isBlank()) {
                    namesByUserId.putIfAbsent(person.getUserId(), person.getName());
                }
            }
        }
        for (SqlRegistryEntry entry : entries) {
            entry.setCreatedByName(namesByUserId.get(entry.getCreatedBy()));
        }
    }

    public SqlRegistryEntry getById(Long id) {
        return mapper.selectById(id);
    }

    public SqlRegistryEntry getBySqlId(String sqlId) {
        return mapper.selectBySqlId(sqlId);
    }

    @Transactional("gaussCustomerTransactionManager")
    public SqlRegistryEntry create(SqlRegistryEntry entry, String userId) {
        // 校验 sql_template (暂关闭: Connection 只读, 形态/关键字校验无必要)
//        String validationError = validateTemplate(entry.getSqlTemplate());
//        if (validationError != null) {
//            throw new IllegalArgumentException(validationError);
//        }

        // 校验 sql_id 唯一性 (含禁用记录, 防止注册乱象下重复)
        if (mapper.countBySqlId(entry.getSqlId()) > 0) {
            throw new IllegalArgumentException("sql_id '" + entry.getSqlId() + "' 已存在");
        }

        entry.setCreatedBy(userId);
        if (entry.getEnabled() == null) {
            entry.setEnabled(1);
        }
        mapper.insert(entry);
        return entry;
    }

    @Transactional("gaussCustomerTransactionManager")
    public SqlRegistryEntry update(Long id, SqlRegistryEntry patch) {
        SqlRegistryEntry existing = mapper.selectById(id);
        if (existing == null) {
            throw new IllegalArgumentException("记录不存在: id=" + id);
        }

        // 如果 sql_template 有变更, 重新校验 (暂关闭: Connection 只读, 形态/关键字校验无必要)
//        if (patch.getSqlTemplate() != null && !patch.getSqlTemplate().equals(existing.getSqlTemplate())) {
//            String validationError = validateTemplate(patch.getSqlTemplate());
//            if (validationError != null) {
//                throw new IllegalArgumentException(validationError);
//            }
//        }

        // 如果 sql_id 有变更, 检查唯一性 (含禁用记录)
        if (patch.getSqlId() != null && !patch.getSqlId().equals(existing.getSqlId())) {
            if (mapper.countBySqlId(patch.getSqlId()) > 0) {
                throw new IllegalArgumentException("sql_id '" + patch.getSqlId() + "' 已存在");
            }
        }

        // 选择性更新: 非 null 字段覆盖
        if (patch.getSqlId() != null) existing.setSqlId(patch.getSqlId());
        if (patch.getName() != null) existing.setName(patch.getName());
        if (patch.getDescription() != null) existing.setDescription(patch.getDescription());
        if (patch.getDatasource() != null) existing.setDatasource(patch.getDatasource());
        if (patch.getSqlTemplate() != null) existing.setSqlTemplate(patch.getSqlTemplate());
        if (patch.getParamsSchema() != null) existing.setParamsSchema(patch.getParamsSchema());
        if (patch.getEnabled() != null) existing.setEnabled(patch.getEnabled());
        // 创建人: 管理端统一修正用(临时放开, 后续可再关闭)。空串视为不改。
        if (patch.getCreatedBy() != null && !patch.getCreatedBy().isBlank()) existing.setCreatedBy(patch.getCreatedBy());

        mapper.update(existing);
        return existing;
    }

    @Transactional("gaussCustomerTransactionManager")
    public void delete(Long id) {
        SqlRegistryEntry existing = mapper.selectById(id);
        if (existing == null) {
            throw new IllegalArgumentException("记录不存在: id=" + id);
        }
        mapper.deleteById(id);
    }

    // ======================================================================
    // SQL 测试
    // ======================================================================

    /**
     * 测试 SQL 执行. 前端直接传入 sql_template / datasource / params_schema / params,
     * 不查 sql_registry 表, 直接路由到对应数据源执行.
     *
     * @param sqlTemplate  SQL 模板
     * @param datasource   目标数据源 (mysql/gauss/clickhouse)
     * @param paramsSchema 参数定义 JSON
     * @param params       用户填入的参数值
     * @return 结构化测试结果
     */
    public SqlTestResult testSql(String sqlTemplate, String datasource, String paramsSchema, Map<String, Object> params) {
        if (sqlTemplate == null || sqlTemplate.isBlank()) {
            return SqlTestResult.builder().success(false).error("sql_template 为空").build();
        }

        // 1. 校验 sql_template (暂关闭: Connection 只读, 形态/关键字校验无必要)
//        String shapeErr = validateTemplateShape(sqlTemplate);
//        if (shapeErr != null) {
//            return SqlTestResult.builder().success(false).error(shapeErr).datasource(datasource).build();
//        }
//
//        String forbiddenErr = validateTemplateForbidden(sqlTemplate);
//        if (forbiddenErr != null) {
//            return SqlTestResult.builder().success(false).error(forbiddenErr).datasource(datasource).build();
//        }

        // 2. 解析 params_schema, 校验参数
        Set<String> declaredParams = parseParamNames(paramsSchema);
        Map<String, Object> paramMap = params == null ? Collections.emptyMap() : new LinkedHashMap<>(params);

        for (Map.Entry<String, Object> e : paramMap.entrySet()) {
            if (!declaredParams.contains(e.getKey())) {
                return SqlTestResult.builder()
                        .success(false)
                        .error("参数 '" + e.getKey() + "' 不在 params_schema 内. 已声明参数: " + declaredParams)
                        .datasource(datasource)
                        .build();
            }
        }

        String missingRequired = checkRequiredParams(paramsSchema, paramMap);
        if (missingRequired != null) {
            return SqlTestResult.builder()
                    .success(false)
                    .error("缺少必填参数: " + missingRequired)
                    .datasource(datasource)
                    .build();
        }

        List<String> templateParams = extractTemplateParams(sqlTemplate);
        for (String p : templateParams) {
            if (!declaredParams.contains(p)) {
                return SqlTestResult.builder()
                        .success(false)
                        .error("sql_template 含占位符 :" + p + " 但 params_schema 未声明")
                        .datasource(datasource)
                        .build();
            }
        }

        // 3. 按 datasource 路由
        String dsKey = datasource == null ? null : datasource.toLowerCase();
        DataSource ds = dsKey == null ? null : dataSourceMap.get(dsKey);
        if (ds == null) {
            return SqlTestResult.builder()
                    .success(false)
                    .error("datasource '" + datasource + "' 不在支持列表 (mysql/gauss/clickhouse) 或 DataSource 未注入")
                    .datasource(datasource)
                    .build();
        }

        // 4. 强制 LIMIT 兜底
        String sql = ensureLimit(sqlTemplate);

        // 5. 执行
        long start = System.currentTimeMillis();
        log.info("SQL 测试: datasource={} dsKey={}", datasource, dsKey);
        try (Connection conn = ds.getConnection()) {
            conn.setReadOnly(true);


            MapSqlParameterSource paramSource = new MapSqlParameterSource();
            for (Map.Entry<String, Object> e : paramMap.entrySet()) {
                paramSource.addValue(e.getKey(), e.getValue());
            }

            ParsedSql parsed = NamedParameterUtils.parseSqlStatement(sql);
            String preparedSql = NamedParameterUtils.substituteNamedParameters(parsed, paramSource);
            Object[] paramArray = NamedParameterUtils.buildValueArray(parsed, paramSource, null);
            Object[] finalArgs = expandCollections(paramArray);

            try (PreparedStatement ps = conn.prepareStatement(preparedSql)) {
                for (int i = 0; i < finalArgs.length; i++) {
                    ps.setObject(i + 1, finalArgs[i]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    return renderTestResult(datasource, rs, System.currentTimeMillis() - start);
                }
            }
        } catch (SQLException e) {
            log.error("SQL 测试失败: datasource={} params={}", datasource, paramMap, e);
            return SqlTestResult.builder()
                    .success(false)
                    .error(e.getClass().getSimpleName() + ": " + e.getMessage())
                    .datasource(datasource)
                    .build();
        } catch (Exception e) {
            log.error("SQL 测试失败: datasource={} params={}", datasource, paramMap, e);
            return SqlTestResult.builder()
                    .success(false)
                    .error(e.getClass().getSimpleName() + ": " + e.getMessage())
                    .datasource(datasource)
                    .build();
        }
    }

    // ======================================================================
    // 校验逻辑 (复用 SqlRegistryExecTool 的模式)
    // ======================================================================

//    /** 管理页面强制 SELECT 开头校验 (比 Agent 工具更严格) */
//    private static String validateTemplateShape(String sql) {
//        if (!TEMPLATE_SHAPE_PATTERN.matcher(sql).matches()) {
//            return "sql_template 必须形如 'SELECT ... FROM ...' (仅允许 SELECT 查询)";
//        }
//        return null;
//    }
//
//    /** 禁用关键字校验 */
//    private static String validateTemplateForbidden(String sql) {
//        if (TEMPLATE_FORBIDDEN_PATTERN.matcher(sql).find()) {
//            return "sql_template 含禁用关键字/元字符 (禁 ; / 注释符 / DDL / DML / system.* 系统表 / INTO OUTFILE / LOAD_FILE)";
//        }
//        return null;
//    }
//
//    /** 综合校验 (用于 create/update) */
//    private static String validateTemplate(String sql) {
//        if (sql == null || sql.isBlank()) {
//            return "sql_template 不能为空";
//        }
//        String shapeErr = validateTemplateShape(sql);
//        if (shapeErr != null) return shapeErr;
//        String forbiddenErr = validateTemplateForbidden(sql);
//        if (forbiddenErr != null) return forbiddenErr;
//        return null;
//    }

    private static String ensureLimit(String sql) {
        if (HAS_LIMIT_PATTERN.matcher(sql).find()) {
            return sql;
        }
        return sql + " LIMIT " + ROW_LIMIT;
    }

    // ======================================================================
    // params_schema 解析 (与 SqlRegistryExecTool 一致)
    // ======================================================================

    private static Set<String> parseParamNames(String paramsSchemaJson) {
        if (paramsSchemaJson == null || paramsSchemaJson.isBlank()) {
            return new LinkedHashSet<>();
        }
        try {
            List<?> list = JSON_MAPPER.readValue(paramsSchemaJson, List.class);
            Set<String> names = new LinkedHashSet<>();
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) continue;
                Object name = m.get("name");
                if (name != null) names.add(String.valueOf(name));
            }
            return names;
        } catch (Exception e) {
            log.warn("parseParamNames 解析失败: {}", paramsSchemaJson, e);
            return new LinkedHashSet<>();
        }
    }

    private static String checkRequiredParams(String paramsSchemaJson, Map<String, Object> paramMap) {
        if (paramsSchemaJson == null || paramsSchemaJson.isBlank()) return null;
        try {
            List<?> list = JSON_MAPPER.readValue(paramsSchemaJson, List.class);
            List<String> missing = new ArrayList<>();
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) continue;
                if (Boolean.TRUE.equals(m.get("required"))) {
                    Object name = m.get("name");
                    if (name == null) continue;
                    if (!paramMap.containsKey(String.valueOf(name))) {
                        missing.add(String.valueOf(name));
                    }
                }
            }
            return missing.isEmpty() ? null : missing.toString();
        } catch (Exception e) {
            log.warn("checkRequiredParams 解析失败: {}", paramsSchemaJson, e);
            return null;
        }
    }

    private static List<String> extractTemplateParams(String sql) {
        List<String> names = new ArrayList<>();
        java.util.regex.Matcher m = NAMED_PARAM_PATTERN.matcher(sql);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    // ======================================================================
    // 结果渲染 (结构化 JSON, 非 markdown)
    // ======================================================================

    private static SqlTestResult renderTestResult(String datasource,
                                                   ResultSet rs, long elapsedMs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int colCount = meta.getColumnCount();

        List<String> columns = new ArrayList<>(colCount);
        for (int i = 1; i <= colCount; i++) {
            columns.add(meta.getColumnLabel(i));
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        int totalRows = 0;
        int maxPreviewRows = 10;
        while (rs.next()) {
            totalRows++;
            if (rows.size() < maxPreviewRows) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= colCount; i++) {
                    row.put(columns.get(i - 1), rs.getObject(i));
                }
                rows.add(row);
            }
        }

        return SqlTestResult.builder()
                .success(true)
                .columns(columns)
                .rows(rows)
                .totalRows(totalRows)
                .elapsedMs(elapsedMs)
                .datasource(datasource)
                .build();
    }

    // ======================================================================
    // Collection 展开 (与 SqlRegistryExecTool 一致)
    // ======================================================================

    private static Object[] expandCollections(Object[] args) {
        List<Object> out = new ArrayList<>(args.length);
        for (Object val : args) {
            if (val == null) {
                out.add(null);
            } else if (val instanceof Collection) {
                for (Object e : (Collection<?>) val) {
                    out.add(e);
                }
            } else if (val.getClass().isArray()) {
                if (val instanceof java.sql.Array) {
                    out.add(val);
                } else {
                    for (int i = 0; i < java.lang.reflect.Array.getLength(val); i++) {
                        out.add(java.lang.reflect.Array.get(val, i));
                    }
                }
            } else {
                out.add(val);
            }
        }
        return out.toArray();
    }
}
