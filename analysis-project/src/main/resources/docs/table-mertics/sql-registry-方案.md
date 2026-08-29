# SQL 注册表执行工具方案

> 业务方/DBA 把复杂 SQL (GROUP BY / CASE WHEN / JOIN / 窗口函数等) 预审后存到 MySQL 业务库的
> `sql_registry` 配置表, 项目构建 `sql_registry_exec` 工具通过 `sql_id` 执行预定义 SQL,
> LLM 只能传 sql_id + 参数, 不能改 SQL 结构。从根本上消除 freeformSql 的注入风险。

## 1. 背景与目标

### 业务需求

现有 `wide_table_query` / `clickhouse_query` 工具只支持 `SELECT ... WHERE 等值 ... LIMIT`，
无法表达如下复杂 SQL（用户示例）：

```sql
SELECT 
  req_item_no AS 需求项编号,
  MIN(sign_start_date) AS 首次会签发起时间,
  CASE 
    WHEN MAX(sign_status) IS NULL OR MAX(sign_status) = '待会签' 
    THEN '首次会签未发起'
    ELSE MAX(sign_status) 
  END AS 会签状态
FROM req_sign_detail
GROUP BY req_item_no
```

如果直接在工具加 `freeformSql` 参数让 LLM 传任意 SQL，安全风险大：
- 白名单难严：CASE WHEN / 子查询里能藏 `system.tables` 探测
- LLM 可能被 prompt injection 诱导生成恶意 SQL
- 黑名单永远写不全

### 设计目标

1. **SQL 模板预审** -- 业务方/DBA 把 SQL 写好存到 `sql_registry` 表，LLM 不能改模板结构
2. **参数化绑定** -- 模板用 `:param` 占位符，LLM 传参走 `PreparedStatement.setXxx()`，防值注入
3. **多数据源路由** -- 一张配置表管 mysql/gauss/clickhouse 三种数据源的 SQL
4. **复用现有链路** -- 仍走 ArtifactHandoffHook 落 CSV + python_exec 后处理
5. **运维可禁用** -- `enabled` 字段一键禁用某条 SQL 而不删记录

## 2. 架构总览

```
业务方/DBA 预审 SQL
       ↓ INSERT sql_registry (sql_id, datasource, sql_template, params_schema)
       ↓
LLM 调 sql_list() 看可用 sql_id (可选)
       ↓
LLM 调 sql_registry_exec(sqlId="xxx", params={...})
       ↓
工具流程:
  1. SELECT sql_template, datasource, params_schema FROM sql_registry WHERE sql_id=? AND enabled=1
  2. 校验 sql_template 形态 (SELECT 开头, 无禁用关键字 -- 双保险防 DBA 失误)
  3. 按 datasource 路由到对应 DataSource (mysql/gauss/clickhouse)
  4. NamedParameterStatement 替换 :param -> ? + 参数化绑定
  5. 强制 LIMIT 兜底 (没显式 LIMIT 自动加 LIMIT 10000)
  6. Connection.setReadOnly(true)
  7. executeQuery -> markdown 表 (ArtifactHandoffHook 自动落 CSV)
       ↓
LLM 调 python_exec + pandas 后处理 CSV (如需)
       ↓
LLM 回复用户
```

### 三层职责划分

| 层 | 职责 | 实现位置 |
|---|---|---|
| **配置层** | SQL 模板 + 参数 schema, DBA 预审后 INSERT | MySQL `sql_registry` 表 |
| **工具层** | 按 sql_id 查模板 + 参数化执行 + 落 CSV artifact | `SqlRegistryExecTool.java` (新增) |
| **Skill 层** | 每条 SQL 一个 skill, 描述 sql_id + 参数 + python_exec 后处理模板 | `workspace/skills/<name>_metrics/SKILL.md` |
| **Agent 层** | sql_list -> sql_registry_exec -> python_exec -> arith | `analyze_data` 子 agent (tools 列表加新工具) |

## 3. SQL 配置表设计

### DDL (放 MySQL 业务库)

```sql
CREATE TABLE sql_registry (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sql_id VARCHAR(64) NOT NULL UNIQUE COMMENT '业务可读 ID, snake_case, 如 req_sign_status_by_item',
    name VARCHAR(128) NOT NULL COMMENT '中文名称',
    description TEXT COMMENT '用途说明',
    datasource VARCHAR(16) NOT NULL COMMENT '目标数据源: mysql / gauss / clickhouse',
    sql_template TEXT NOT NULL COMMENT 'SQL 模板, :param 命名占位符',
    params_schema JSON COMMENT '参数定义: [{"name":"userId","type":"string","required":true,"description":"用户 ID"}]',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '0=禁用 1=启用',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by VARCHAR(64) COMMENT '创建人',
    INDEX idx_datasource_enabled (datasource, enabled)
) ENGINE=InnoDB CHARSET=utf8mb4 COMMENT='SQL 配置注册表';
```

### 示例数据

```sql
INSERT INTO sql_registry (sql_id, name, description, datasource, sql_template, params_schema, created_by) VALUES
(
  'req_sign_status_by_item',
  '需求项会签状态聚合',
  '按需求项编号分组, 取首次会签发起时间 + 会签状态 (CASE WHEN)',
  'clickhouse',
  'SELECT 
     req_item_no AS 需求项编号,
     MIN(sign_start_date) AS 首次会签发起时间,
     CASE
       WHEN MAX(sign_status) IS NULL OR MAX(sign_status) = ''待会签''
       THEN ''首次会签未发起''
       ELSE MAX(sign_status)
     END AS 会签状态
   FROM req_sign_detail
   GROUP BY req_item_no
   LIMIT :limit',
  '[{"name":"limit","type":"int","required":false,"description":"最大返回行数, 默认 10000"}]',
  'dba'
),
(
  'trace_recent_stats_by_user',
  '用户会话统计',
  '按 userId 分组, 取会话数 / 平均时长 / 平均事件数',
  'clickhouse',
  'SELECT
     userId,
     count() AS 会话数,
     avg(totalDurationMs) AS 平均时长ms,
     avg(eventCount) AS 平均事件数
   FROM default.trace_recent
   WHERE userId = :userId
   GROUP BY userId
   LIMIT 10000',
  '[{"name":"userId","type":"string","required":true,"description":"用户 ID"}]',
  'dba'
);
```

## 4. 工具设计

### 4.1 sql_list (辅助工具)

```java
@Tool(name = "sql_list",
      description = "列出所有预注册 SQL 的 sql_id + 名称 + 描述 + 参数 schema. "
                  + "用此工具查可用 sql_id 后再调 sql_registry_exec.")
public ToolResultBlock sqlList() {
    // SELECT sql_id, name, description, params_schema, datasource
    // FROM sql_registry WHERE enabled=1 ORDER BY sql_id
    // 渲染成 markdown 表给 LLM 看
}
```

返回示例：

```
| sql_id                       | name            | datasource  | params                |
|---|---|---|---|
| req_sign_status_by_item      | 需求项会签状态聚合 | clickhouse  | limit (int, 可选)     |
| trace_recent_stats_by_user   | 用户会话统计      | clickhouse  | userId (string, 必填) |
```

### 4.2 sql_registry_exec (主工具)

```java
@Tool(name = "sql_registry_exec",
      description = "通过 sql_id 执行预注册 SQL. 先用 sql_list 查可用 sql_id, 再传 sql_id + params. "
                  + "返回 markdown 表 + CSV artifact. 算复杂聚合/JOIN/CASE WHEN 走此工具, "
                  + "简单等值查询仍走 wide_table_query / clickhouse_query.")
public ToolResultBlock sqlRegistryExec(
    @ToolParam(name = "sqlId",
               description = "预注册 SQL 的 ID, 如 req_sign_status_by_item")
    String sqlId,
    @ToolParam(name = "params", required = false,
               description = "SQL 模板参数, JSON 对象, 如 {\"limit\":100, \"userId\":\"alice\"}. "
                          + "参数名见 sql_list 返回的 params_schema")
    Map<String, Object> params) {

    // 1. 查 sql_registry 表
    SqlRegistryEntry entry = registryMapper.findBySqlId(sqlId);
    if (entry == null || !entry.isEnabled()) {
        return ToolResultBlock.text("sql_registry_exec 拒绝执行: sql_id='" + sqlId
                + "' 不存在或已禁用. 先调 sql_list 查可用 sql_id");
    }

    // 2. 二次校验 sql_template (防 DBA 失误)
    String err = validateTemplate(entry.getSqlTemplate());
    if (err != null) return ToolResultBlock.text(err);

    // 3. 按 datasource 路由
    DataSource ds = dataSourceMap.get(entry.getDatasource());
    if (ds == null) {
        return ToolResultBlock.text("sql_registry_exec 拒绝执行: 未知 datasource="
                + entry.getDatasource() + " (只支持 mysql/gauss/clickhouse)");
    }

    // 4. 替换 :param + 强制 LIMIT
    String sql = ensureLimit(entry.getSqlTemplate());
    Map<String, Object> paramMap = params == null ? Collections.emptyMap() : params;

    // 5. 校验参数都在 params_schema 内 (防 LLM 传多余参数触发表名注入)
    Set<String> declaredParams = parseParamNames(entry.getParamsSchema());
    for (String k : paramMap.keySet()) {
        if (!declaredParams.contains(k)) {
            return ToolResultBlock.text("sql_registry_exec 拒绝执行: 参数 '" + k
                    + "' 不在 sql_id=" + sqlId + " 的 params_schema 内. 已声明参数: " + declaredParams);
        }
    }

    // 6. 执行 (NamedParameterStatement 处理 :param -> ?)
    long start = System.currentTimeMillis();
    try (Connection conn = ds.getConnection()) {
        conn.setReadOnly(true);
        try (NamedParameterStatement nps = new NamedParameterStatement(conn, sql)) {
            for (Map.Entry<String, Object> e : paramMap.entrySet()) {
                nps.setObject(e.getKey(), e.getValue());
            }
            try (ResultSet rs = nps.executeQuery()) {
                return renderResult(sqlId, rs, System.currentTimeMillis() - start);
            }
        }
    } catch (SQLException e) {
        log.error("sql_registry_exec 失败: sqlId={} params={}", sqlId, paramMap, e);
        return ToolResultBlock.text("sql_registry_exec 失败: " + e.getMessage());
    }
}
```

### 4.3 校验逻辑 (双保险)

```java
// 形态: 必须 SELECT 开头且有 FROM
private static final Pattern TEMPLATE_SHAPE_PATTERN = Pattern.compile(
    "^\\s*SELECT\\s+[\\s\\S]+\\s+FROM\\s+[\\s\\S]+$",
    Pattern.CASE_INSENSITIVE
);

// 黑名单 (与 subqueryFilters 一致, 防 DBA 录入时失误写了 DDL/DML)
private static final Pattern TEMPLATE_FORBIDDEN_PATTERN = Pattern.compile(
    "(;|--|/\\*|\\*/|"
    + "\\bDROP\\b|\\bDELETE\\b|\\bUPDATE\\b|\\bINSERT\\b|\\bALTER\\b|"
    + "\\bTRUNCATE\\b|\\bCREATE\\b|\\bGRANT\\b|\\bREVOKE\\b|\\bMERGE\\b|"
    + "\\bCALL\\b|\\bEXEC\\b|\\bEXECUTE\\b|"
    + "\\bINTO\\b|\\bOUTFILE\\b|\\bDUMPFILE\\b|\\bLOAD_FILE\\b|"
    + "system\\.(tables|settings|processes|users|parts))",
    Pattern.CASE_INSENSITIVE
);

private static String validateTemplate(String sql) {
    if (!TEMPLATE_SHAPE_PATTERN.matcher(sql).matches()) {
        return "sql_registry_exec 拒绝执行: sql_id 对应模板必须形如 'SELECT ... FROM ...'";
    }
    if (TEMPLATE_FORBIDDEN_PATTERN.matcher(sql).find()) {
        return "sql_registry_exec 拒绝执行: sql_id 对应模板含禁用关键字/元字符 "
                + "(DBA 录入时失误? 禁 ; / 注释符 / DDL / DML / system.* 系统表)";
    }
    return null;
}
```

### 4.4 强制 LIMIT 兜底

```java
private static String ensureLimit(String sql) {
    String upper = sql.toUpperCase().replaceAll("\\s+", " ");
    // 已有 LIMIT (任意位置) -> 不动
    if (upper.matches("(?s).*\\bLIMIT\\s+\\d+.*")) {
        return sql;
    }
    return sql + " LIMIT " + ROW_LIMIT;  // ROW_LIMIT=10000
}
```

## 5. 多数据源路由

`SqlRegistryExecTool` 注入三个 DataSource + 一个配置库 DataSource：

```java
public class SqlRegistryExecTool {
    private final Map<String, DataSource> dataSourceMap;
    private final SqlRegistryMapper registryMapper;  // 操作 MySQL sql_registry 表

    public SqlRegistryExecTool(
            @Qualifier("mysqlDataSource") DataSource mysqlDs,
            @Qualifier("gaussDataSource") DataSource gaussDs,
            @Qualifier("clickHouseDataSource") DataSource ckDs,
            SqlRegistryMapper registryMapper) {
        this.dataSourceMap = Map.of(
            "mysql", mysqlDs,
            "gauss", gaussDs,
            "clickhouse", ckDs
        );
        this.registryMapper = registryMapper;
    }
}
```

`sql_registry` 表本身存在 MySQL 业务库（用 `mysqlDataSource`），通过 MyBatis Mapper 访问：

```java
public interface SqlRegistryMapper {
    @Select("SELECT * FROM sql_registry WHERE sql_id = #{sqlId} AND enabled = 1")
    SqlRegistryEntry findBySqlId(@Param("sqlId") String sqlId);

    @Select("SELECT sql_id, name, description, datasource, params_schema "
          + "FROM sql_registry WHERE enabled = 1 ORDER BY sql_id")
    List<SqlRegistryEntry> listAll();
}
```

## 6. NamedParameterStatement 实现

Spring JDBC 的 `NamedParameterJdbcTemplate` 不适合直接拿 `ResultSet`（它的 query 方法要 RowMapper）。
有两种选择：

### 方案 a: 用 Spring 的 NamedParameterUtils 自己拼

```java
String sql = "SELECT ... WHERE user_id = :userId LIMIT :limit";
MapSqlParameterSource params = new MapSqlParameterSource()
    .addValue("userId", "alice")
    .addValue("limit", 100);

ParsedSql parsed = NamedParameterUtils.parseSqlStatement(sql);
String preparedSql = NamedParameterUtils.substituteNamedParameters(parsed, params);
List<Object> paramList = NamedParameterUtils.buildValueArray(parsed, params, null);

try (PreparedStatement ps = conn.prepareStatement(preparedSql)) {
    for (int i = 0; i < paramList.size(); i++) {
        ps.setObject(i + 1, paramList.get(i));
    }
    try (ResultSet rs = ps.executeQuery()) { ... }
}
```

### 方案 b: 自行实现 :param -> ? 替换

```java
private static String replaceNamedParams(String sql, Map<String, Object> params,
                                          List<Object> orderedValues) {
    Pattern p = Pattern.compile(":(\\w+)");
    Matcher m = p.matcher(sql);
    StringBuffer sb = new StringBuffer();
    while (m.find()) {
        String name = m.group(1);
        if (!params.containsKey(name)) {
            throw new IllegalArgumentException("缺少参数: " + name);
        }
        orderedValues.add(params.get(name));
        m.appendReplacement(sb, "?");
    }
    m.appendTail(sb);
    return sb.toString();
}
```

推荐 **方案 a**（Spring 自带，无需自己写正则）。

## 7. 与现有工具的关系

| 工具 | 适用 | 安全等级 |
|---|---|---|
| `wide_table_query` / `clickhouse_query` (fields+filters) | 简单等值查询 | 高 (参数化绑定) |
| `wide_table_query` / `clickhouse_query` (+subqueryFilters) | 等值子查询 | 高 (白名单 + 子查询校验) |
| `sql_registry_exec` | 复杂聚合/JOIN/CASE WHEN/窗口函数 | 高 (DBA 预审 + 二次校验) |

三层递进。LLM 工具选择决策树：

```
查询需求复杂度?
├─ 简单等值查询 (WHERE col=val)
│  -> ★ wide_table_query / clickhouse_query (fields + filters)
├─ 等值子查询 (WHERE col=(SELECT MAX...))
│  -> ★ wide_table_query / clickhouse_query (filters + subqueryFilters)
└─ 复杂聚合/JOIN/CASE WHEN
     -> ★ sql_list -> sql_registry_exec(sqlId, params)
        (如果 sql_list 没找到匹配的 sql_id, 回复用户 "暂无对应预注册 SQL, 请业务方在 sql_registry 表新增")
```

## 8. SKILL 集成

业务方新增复杂 SQL 的流程：

1. **DBA 录入** -- `INSERT INTO sql_registry (...) VALUES (...)`
2. **写 SKILL** -- 在 `workspace/skills/<name>_metrics/SKILL.md` 描述:
   - frontmatter: `name: <name>_metrics`
   - body: sql_id + 参数说明 + python_exec 后处理模板
3. **LLM 工作流**:
   - `load_skill_through_path(name="<name>_metrics")` 读 SKILL
   - (可选) `sql_list()` 看可用 sql_id
   - `sql_registry_exec(sqlId="xxx", params={...})` 取数
   - `python_exec` + pandas 后处理 (如需)
   - `arith` 复算百分比
   - 回复用户

### SKILL 模板示例 (`req_sign_status_metrics/SKILL.md`)

```markdown
---
name: req_sign_status_metrics
description: 需求项会签状态聚合 - 按需求项编号分组取会签状态
---

# 需求项会签状态聚合

业务表: `default.req_sign_detail` (ClickHouse)
预注册 SQL: `req_sign_status_by_item` (在 sql_registry 表中)

## 参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| limit | int | 否 | 最大返回行数, 默认 10000 |

## 工作流

### Step 1: 调 sql_registry_exec 取数

sql_registry_exec(
  sqlId="req_sign_status_by_item",
  params={"limit":1000}
)

### Step 2: python_exec 后处理 (如需)

import pandas as pd
df = pd.read_csv("<CSV 路径>")
print(df.head())
# SQL 已完成聚合 + CASE WHEN, python_exec 仅做格式化展示

### Step 3: 回复用户

中文, 包含: 需求项数量 + 状态分布
```

## 9. 落地清单

### 新增文件

| 文件 | 用途 |
|---|---|
| `docs/table-mertics/sql-registry.sql` | DDL + 示例数据 (DBA 执行) |
| `src/main/java/com/agentscopea2a/v2/tools/SqlRegistryExecTool.java` | 主工具 |
| `src/main/java/com/agentscopea2a/v2/tools/SqlListTool.java` | 辅助列表工具 |
| `src/main/java/com/agentscopea2a/mapper/mysql/SqlRegistryMapper.java` | MyBatis Mapper |
| `src/main/resources/mybatis/mapper/mysql/SqlRegistryMapper.xml` | Mapper XML |
| `src/main/java/com/agentscopea2a/entity/SqlRegistryEntry.java` | entity |
| `src/test/java/com/agentscopea2a/v2/tools/SqlRegistryExecToolTest.java` | 单测 |

### 改动文件

| 文件 | 改动 |
|---|---|
| `V2ToolConfig.java` | 加 2 个 @Bean (sqlRegistryExecTool, sqlListTool) |
| `ToolRoutersIndex.java` | 构造 +2 参数, init() registerTools |
| `SubagentRegistrar.java` | toolRegistry 加 sql_registry_exec, sql_list |
| `analyze_data.md` | tools 列表 +2 项 |
| `AGENTS.md` | 工具表 +2 行 |

### 不动

- `WideTableMetricsTool.java` / `ClickHouseWideTableMetricsTool.java` (现有简单查询工具保持不变)
- `application-dev.properties` (数据源已配好)
- 沙箱 / artifact / CSV 链路

## 10. 风险点

| 风险 | 缓解 |
|---|---|
| **DBA 误录恶意 SQL** | `validateTemplate` 二次校验 (形态 + 黑名单), 即使 DBA 失误写了 DROP 也会被拦 |
| **LLM 传未声明参数** | params_schema 校验, 多余参数一律拒执行 |
| **LLM 拼错 sql_id** | 工具返回明确错误 + 建议调 `sql_list` |
| **参数类型不匹配** | params_schema 声明类型, 工具运行时按声明类型 setObject (string/int/double) |
| **大表无 WHERE 全量扫** | 强制 LIMIT 10000 兜底; DBA 录入时应加 WHERE 限定 |
| **多数据源混淆** | datasource 字段枚举校验 (mysql/gauss/clickhouse) |
| **sql_registry 表本身被注入** | Mapper 用 MyBatis `#{sqlId}` 参数化, 不拼字符串 |
| **SQL 模板含敏感字段 (密码等)** | DBA 录入时审查; 工具日志只打 sqlId + params values, 不打完整 SQL (避免敏感值进日志) |

## 11. 与 freeformSql 方案的对比

| 项 | freeformSql (方案 A) | sql_registry_exec (本方案) |
|---|---|---|
| 谁写 SQL | LLM | DBA 预审 |
| 安全模型 | 黑名单正则 (难严) | DBA 预审 + 黑名单二次校验 (双保险) |
| 灵活性 | 高 (LLM 任意写) | 低 (只能用预定义 sql_id) |
| 新增 SQL | LLM 现写 | DBA INSERT + 写 SKILL |
| 适合场景 | 探索性分析 | 固定报表/指标 (业务可枚举) |
| 维护成本 | 低 | 中 (运维要维护 sql_registry 表) |

**结论**: 业务指标大多是固定报表 (Q2-1 完成率 / 会签状态 / 缺陷密度分布), 适合预审模式。
探索性分析仍走 `python_exec + pandas` (从已落 CSV 的全量数据上做 ad-hoc 计算)。

## 12. 验证步骤

### 阶段 1: DDL + 单测

1. DBA 在 MySQL 业务库执行 `sql_registry.sql` 建表 + 插入示例数据
2. `mvn test -Dtest=SqlRegistryExecToolTest`
   - `rejectUnknownSqlId` -- 未知 sql_id 被拒
   - `rejectDisabledSql` -- enabled=0 被拒
   - `rejectUndeclaredParam` -- 多余参数被拒
   - `realExecReqSignStatusByItem` -- 真执行示例 SQL (gated by CK_HOST)
   - `realExecTraceRecentStatsByUser` -- 真执行 + 参数化绑定 (gated by CK_HOST)

### 阶段 2: E2E

1. 启动后端, 确认日志 `SqlRegistryExecTool: wired` + `SqlListTool: wired`
2. 前端发问: "需求项会签状态分布"
3. 观察 trace:
   - Supervisor 路由 analyze_data
   - `load_skill_through_path(name="req_sign_status_metrics")`
   - `sql_registry_exec(sqlId="req_sign_status_by_item", params={})`
   - `python_exec` 格式化展示 (如需)
   - 回复用户

### 阶段 3: 跨数据源验证

1. sql_registry 里再加一条 gauss datasource 的 SQL
2. 前端发问触发 gauss 查询
3. 确认 datasource 路由正确, GaussDB 数据返回
