---
name: gauss_clickhouse_reconcile_metrics
description: 通过 script_exec 调预注册 Python 脚本 "q_join_gauss_clickhouse_demo" 一次完成 GaussDB 项目画像 + ClickHouse trace_event 同期对账 (跨库无外键, 时间窗口对齐)
---

# GaussDB 项目画像 + ClickHouse trace_event 同期对账 (走 script_exec 一步到位)

> 共享硬规则 (CSV 路径 / arith 复算 / 空结果 / 直接调用 / python_exec 重试) 已在主 agent AGENTS.md
> 和子 agent sysPrompt (SubagentRegistrar 自动注入 `skills/_common/SKILL.md`) 中, 本 skill 不重复。

> 本 skill 是 `script_exec` 工具的**多数据源**验证实例。预注册 Python 脚本已由开发人员录入
> GaussDB `script_registry` 表, script_id = `q_join_gauss_clickhouse_demo`, datasources = `["gauss","clickhouse"]`。
> ScriptExecTool 按数组注入两套 env: GAUSS_JDBC_URL/USER/PASS/JAR (JPype) + CLICKHOUSE_DB_URL (sqlalchemy)。
> 脚本内部完成: GaussDB 查项目画像 + ClickHouse 查同期 trace 事件 + pandas 并排汇总, 一次返回 markdown + JSON。

业务表:
- GaussDB: `dsqa_dwd_req_item_app_portrait_wide_inf` (schema `remote_app`, 项目画像宽表)
- ClickHouse: `trace_event` (agent trace 事件流)

预注册脚本: `q_join_gauss_clickhouse_demo` (在 `script_registry` 表中)
适用问题: 用户问 "X 部门 Y 版本项目打分情况 + 同期 agent trace 事件情况"

⚠️ **跨库无外键, 做同期对账, 不是 row-level join**: trace_event 表只有 conversation_id/trace_id 维度,
没有 dept/project_no 业务外键, 无法与 GaussDB 项目画像表做行级 join。本脚本用时间窗口对齐:
GaussDB 按 dept+version 查项目打分情况, ClickHouse 按 event_date (与 version 月份对齐) 查 trace 事件统计,
pandas 把两份汇总指标并排输出。

## script_id 与参数 schema

| script_id | datasources | 参数 | 说明 |
|---|---|---|---|
| `q_join_gauss_clickhouse_demo` | `["gauss","clickhouse"]` | `dept` (string, 必填) | 开发部门, 如 "杭州开发二部" |
| | | `version` (string, 必填) | 版本计划, 如 "2026年7月份版本" |
| | | `start_date` (string, 必填) | trace_event 查询开始日期, 与 version 月份对齐 |
| | | `end_date` (string, 必填) | trace_event 查询结束日期, 与 version 月份对齐 |

脚本内部 SQL (开发人员录入, LLM 不能改):

GaussDB 侧 (JPype + opengauss-jdbc, 占位符 :name):

```sql
SELECT
  projectzh_no AS "project_no",
  dev_dept AS "dept",
  version_plan AS "version",
  score_status_2_1 AS "score_status",
  standard_is_2_1 AS "is_passed"
FROM dsqa_dwd_req_item_app_portrait_wide_inf
WHERE dev_dept = :dept
  AND version_plan = :version
  AND in_date = (SELECT MAX(in_date) FROM dsqa_dwd_req_item_app_portrait_wide_inf)
```

ClickHouse 侧 (sqlalchemy + clickhouse-sqlalchemy, 占位符 :name):

```sql
SELECT
  event_type AS "event_type",
  count() AS "event_count",
  uniqExact(conversation_id) AS "distinct_conv",
  round(avg(duration_ms), 2) AS "avg_duration_ms",
  max(duration_ms) AS "max_duration_ms"
FROM trace_event
WHERE event_date BETWEEN toDate(:start) AND toDate(:end)
GROUP BY event_type
```

脚本内部 pandas 计算 (开发人员录入, LLM 不能改):

```python
# GaussDB 侧 (经 JDBC rs.getString 返回的全是 string, 直接和字面量比)
total_projects = len(df_gauss)
scored_projects = int((df_gauss["score_status"] == "已打分").sum())
passed_projects = int((df_gauss["is_passed"] == "达标").sum())
scored_pct = round(scored_projects / total_projects * 100, 2)
passed_pct = round(passed_projects / total_projects * 100, 2)

# ClickHouse 侧 (加权平均)
total_events = int(df_ck["event_count"].sum())
distinct_conv = int(df_ck["distinct_conv"].sum())  # 下限近似
total_duration = (df_ck["event_count"] * df_ck["avg_duration_ms"]).sum()
avg_duration_ms = round(total_duration / total_events, 2)
max_duration_ms = int(df_ck["max_duration_ms"].max())
```

## 工作流 (严格按顺序)

### Step 1: 从用户问题提取参数

- `dept`: 开发部门 (例: "杭州开发二部")
- `version`: 版本计划 (例: "2026年7月份版本")
- `start_date`: trace_event 查询开始日期 (例: "2026-07-01") -- **与 version 月份对齐**
- `end_date`: trace_event 查询结束日期 (例: "2026-07-31") -- **与 version 月份对齐**

如果用户没指定部门或版本, 追问 -- 不要默认查全部 (GaussDB 会扫全表)。

⚠️ **start_date / end_date 必须与 version 月份对齐** -- 这是同期对账的语义前提。
- version="2026年7月份版本" -> start_date="2026-07-01", end_date="2026-07-31"
- version="2026年8月份版本" -> start_date="2026-08-01", end_date="2026-08-31"
- 用户没明说 trace 时间范围时, 从 version 字符串解析月份填入

### Step 2: 直接调 script_exec 一步到位 (GaussDB 取数 + ClickHouse 取数 + pandas 并排汇总)

```
script_exec(
  scriptId="q_join_gauss_clickhouse_demo",
  params={
    "dept":"杭州开发二部",
    "version":"2026年7月份版本",
    "start_date":"2026-07-01",
    "end_date":"2026-07-31"
  }
)
```

- ⚠️ **参数名必须在 params_schema 内** -- 多余参数会被工具拒执行 (防注入)。本例只能传 `dept` / `version` / `start_date` / `end_date`。
- 工具返回 markdown 同期对账表 (GaussDB 项目画像 + ClickHouse trace 统计 + 前 5 event_type 明细) + 末行 JSON。
- **所有指标已由脚本算好** (含打分率/达标率/加权平均耗时), LLM 直接读 JSON 字段即可, **不需要再调 arith 复算**。
- 不需要再调 `python_exec` 写 pandas 代码 -- 脚本内部已经算好了。

### Step 3: 回复用户

中文, 包含部门 + 版本 + 时间范围 + 两套指标数字 (GaussDB 项目总数/打分率/达标率 + ClickHouse 事件总数/去重会话/平均耗时) + 业务解读 (项目质量与 agent 调用情况对照) + 数据来源标注。

> ## 示例 1: 单部门单版本同期对账
>
> 用户问: "杭州开发二部 7月版项目打分情况 + 同期 agent trace 事件情况?"
>
> params: `{"dept":"杭州开发二部", "version":"2026年7月份版本", "start_date":"2026-07-01", "end_date":"2026-07-31"}`
>
> ### Step 2 调用
>
> ```
> script_exec(
>   scriptId="q_join_gauss_clickhouse_demo",
>   params={
>     "dept":"杭州开发二部",
>     "version":"2026年7月份版本",
>     "start_date":"2026-07-01",
>     "end_date":"2026-07-31"
>   }
> )
> ```
>
> 返回 (示意):
> ```
> ## 同期对账: 杭州开发二部 / 2026年7月份版本 (event_date in [2026-07-01, 2026-07-31])
>
> ### GaussDB 项目画像
> | 项目总数 | 已打分 | 达标数 | 打分率 | 达标率 |
> |---:|---:|---:|---:|---:|
> | 45 | 42 | 38 | 93.33% | 84.44% |
>
> ### ClickHouse trace_event 同期统计
> | 事件总数 | 去重会话数(下限) | 平均耗时ms | 最大耗时ms |
> |---:|---:|---:|---:|
> | 3210 | 95 | 425.8 | 38000 |
>
> trace_event 按 event_type 明细 (前 5, 按 event_count desc):
>   - tool_call: 事件数=1450, 去重会话=92, 平均=312.5ms, 最大=8200
>   - llm_response: 事件数=980, 去重会话=88, 平均=1820.3ms, 最大=38000
>   ...
>
> json: {"total_projects":45,"scored_projects":42,"passed_projects":38,
>        "scored_pct":93.33,"passed_pct":84.44,
>        "total_events":3210,"distinct_conv_lower_bound":95,
>        "avg_duration_ms":425.8,"max_duration_ms":38000}
> ```
>
> ### Step 3 回复
>
> "杭州开发二部 2026年7月份版本同期对账:
>  - 项目画像: 总数 45, 打分率 93.33%, 达标率 84.44% (GaussDB, 数据日期取最新 in_date)。
>  - trace_event: 7月同期事件总数 3210, 涉及去重会话 95 个 (下限近似), 平均耗时 425.8ms, 最大耗时 38000ms。
>  - 对照解读: 45 个项目对应 95 个会话, 平均每个项目触发约 2.1 次 agent 调用, tool_call 占事件主体..."

## 注意事项

- **必填参数 dept + version + start_date + end_date**, 用户没指定就追问, 不要默认查全部。
- **多余参数会被拒执行** -- 只能传 dept / version / start_date / end_date, 传其他参数名会被工具拒。
- **start_date / end_date 必须与 version 月份对齐** -- 这是同期对账的语义前提, version 是 7月份版本就传 7月份的 start/end_date, 不要传 8月份。
- **跨库无外键, 不是 row-level join** -- trace_event 表无 dept/project_no 字段, 脚本做时间窗口对账, 不做 pd.merge。回复用户时不要说 "join", 说 "同期对账"。
- **distinct_conv 是下限近似** -- 同一 conversation 跨 event_type 会被分组去重后重复累加。回复用户时标注 "(下限近似)"。
- **avg_duration_ms 是加权平均** -- 脚本用 `sum(事件数 * 组平均) / 总事件数` 计算, 不是组平均的简单算术平均。LLM 直接读 JSON 字段, 不要再用 arith 复算。
- **GaussDB 经 JDBC 返回值是 string** -- 脚本内 `score_status == "已打分"` 是字符串比较, 不是布尔/数字。LLM 不需要管这个细节, 脚本已处理。
- **脚本不可改** -- 业务方要改 SQL 或 pandas 计算逻辑需找开发人员在 `script_registry` 表里改 script_path 指向的 .py 文件, LLM 只能传 script_id + params。
- **替代 wide_table_query + clickhouse_query + python_exec 三步走** -- 一次 script_exec 调用拿到两套数字, 不再需要 LLM 写 pandas 代码做跨库合并。
- **datasources 声明在 script_registry 表** -- LLM 调 script_exec 时不传 datasources, 工具按注册表声明注入对应 env。本脚本声明 `["gauss","clickhouse"]`, 工具会同时注入 GAUSS_JDBC_URL/USER/PASS/JAR + CLICKHOUSE_DB_URL。
