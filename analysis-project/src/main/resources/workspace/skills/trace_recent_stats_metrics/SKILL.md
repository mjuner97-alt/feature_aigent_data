---
name: trace_recent_stats_metrics
description: 通过 sql_registry_exec 调预注册 SQL "trace_recent_stats_by_user" 算 ClickHouse 用户会话聚合 (GROUP BY + sumIf)
---

# trace_recent 用户会话统计 (按 userId 分组, 走 sql_registry_exec)

> 本 skill 是 `sql_registry_exec` 工具的 ClickHouse 验证实例, 演示 GROUP BY + 聚合函数 +
> sumIf 的复杂 SQL 走预注册路径。预注册 SQL 已由 DBA 录入 MySQL `sql_registry` 表,
> sql_id = `trace_recent_stats_by_user`, datasource = `clickhouse`。
> 后续业务方新增其他 ClickHouse 复杂 SQL (含 GROUP BY / CASE WHEN / 窗口函数) 时, 复制本目录改:
> frontmatter description / sql_id / 参数说明 / python_exec 后处理模板, 即可生成新 skill。

业务表: `default.trace_recent` (ClickHouse)
预注册 SQL: `trace_recent_stats_by_user` (在 `sql_registry` 表中)
适用问题: 用户问 "X 用户 + 会话数 / 平均时长 / 平均事件数 / 完成数"

## sql_id 与参数 schema

| sql_id | datasource | 参数 | 说明 |
|---|---|---|---|
| `trace_recent_stats_by_user` | clickhouse | `userId` (string, 必填) | 用户 ID, 如 "alice" |
| | | `startTime` (date, 必填) | 开始日期 ISO 格式 YYYY-MM-DD, 如 "2026-07-01" |

> LIMIT 由工具内部固定 10000, 不在参数中暴露给 LLM (避免 LLM 传错触发重复 LIMIT 语法错)。

预注册 SQL (DBA 录入, LLM 不能改):

```sql
SELECT
  userId,
  count() AS `会话数`,
  avg(totalDurationMs) AS `平均时长ms`,
  avg(eventCount) AS `平均事件数`,
  sumIf(1, status = 'COMPLETED') AS `完成数`
FROM default.trace_recent
WHERE userId = :userId
  AND createdAt >= :startTime
GROUP BY userId
```

> 中文别名必须用反引号包裹 (ClickHouse 要求, 否则报 `Unrecognized token: Syntax error`). LIMIT 由工具内部固定 10000, 不要写 `LIMIT :limit` 占位符.

> 这条 SQL 含 GROUP BY + 聚合函数 (count / avg / sumIf), 是 wide_table_query 表达不了的语义,
> 故走 sql_registry_exec 路径而非 clickhouse_query 路径。

## 工作流 (analyze_data 必读, 严格按顺序)

### Step 1: 从用户问题提取参数

- `userId`: 用户 ID (例: "alice")
- `startTime`: 开始日期 ISO 格式 YYYY-MM-DD (例: "2026-07-01")

如果用户没指定 userId 或时间范围, 追问 -- 不要默认查全部。

### Step 2: 直接调 sql_registry_exec 取数

```
sql_registry_exec(
  sqlId="trace_recent_stats_by_user",
  params={"userId":"alice", "startTime":"2026-07-01"}
)
```

> ⚠️ **直接调用, 不要走 router_tool**: `sql_registry_exec` 已直接注册在 analyze_data 子 agent 的 Toolkit 上, 跳过 `router_tool({toolId:...})` 元工具路由能省 5 轮 LLM 往返。
>
> ⚠️ **参数名必须在 params_schema 内** -- 多余参数会被工具拒执行 (防注入)。本例只能传 `userId` / `startTime` (不要传 `limit` / `tableName` / `schema` 等额外参数)。
>
> ⚠️ **日期格式必须是 ISO YYYY-MM-DD** -- ClickHouse Date 类型, 传 "2026/07/01" 或 "07-01-2026" 会被驱动拒。

工具返回 markdown 预览 + `📦 CSV 路径: <path>` 行 (由 ArtifactHandoffHook 自动落 CSV)。

> 🚨 **硬规则**: CSV 路径只能从 sql_registry_exec 返回的 `📦 CSV 路径:` 行复制, 不要手工编造, 里面带 `<userId>/<taskId>` 前缀, 改写会被 ArtifactAccessMiddleware 越权拦截。

### Step 3: 用 python_exec + pandas 后处理 (如需)

本预注册 SQL 已含 GROUP BY + 聚合, 返回的 markdown 表已经是聚合结果 (一行一用户), 不是底层行。
python_exec 仅做格式化展示:

```python
import pandas as pd
df = pd.read_csv("/workspace/artifacts/<user>/<task>/sql-xxx.csv")  # 路径从工具返回复制
print(df.to_string(index=False))
# SQL 已完成聚合 + sumIf, 无需再算, 直接展示
```

如果只需要展示聚合结果, 可以跳过 python_exec, 直接用工具返回的 markdown 表。

### Step 4: 用 arith 复算百分比 (BigDecimal, 双重保险, 如需算成功率)

```
arith(op="pct", numbers=[<完成数>, <会话数>])    # 成功率
```

arith 返回 BigDecimal 精度结果, 以此为准回复用户。

### Step 5: 回复用户

中文, 包含:
- 用户 ID + 时间范围
- 指标数字 (会话数 / 平均时长 / 平均事件数 / 完成数 / 成功率)
- 业务解读 (例: "alice 7月共 100 个会话, 成功率 95%, 平均时长 30s")
- 数据来源标注 (基于聚合后 1 行数据)

## 示例 1: 单用户单时间段

用户问: "alice 7月份的会话统计?"

params: `{"userId":"alice", "startTime":"2026-07-01"}`

### Step 2 调用

```
sql_registry_exec(
  sqlId="trace_recent_stats_by_user",
  params={"userId":"alice", "startTime":"2026-07-01"}
)
```

### Step 3 模板 (如需展示)

```python
import pandas as pd
df = pd.read_csv("/workspace/artifacts/<user>/<task>/sql-xxx.csv")
print(df.to_string(index=False))
# 输出: userId | 会话数 | 平均时长ms | 平均事件数 | 完成数
#        alice  | 100    | 30000      | 5.5        | 95
```

### Step 4 复算成功率

```
arith(op="pct", numbers=[95, 100])    # 95.00%
```

## 注意事项

- **必填参数 userId + startTime**, 用户没指定就追问, 不要默认查全部。
- **不要走 router_tool** -- sql_registry_exec 已直接注册, 一次调通。
- **多余参数会被拒执行** -- 只能传 userId / startTime, 传其他参数名 (如 limit / tableName / schema) 会被工具拒。
- **LIMIT 由工具内部固定 10000** -- 不要在 params 里传 limit, 也不要在 SQL 模板里写 `LIMIT :limit` (会触发重复 LIMIT 语法错)。
- **SQL 模板不可改** -- 业务方要改 SQL (改聚合维度 / 加 CASE WHEN 等) 需找 DBA 在 sql_registry 表里改 sql_template, LLM 只能传 sql_id + params。
- **SQL 已含聚合** -- 返回的是聚合后 1 行 (按 userId 分组), 不要在 python_exec 里再 groupBy。
- **空结果时返回 "无数据"**, 不要算 0/0。
- **禁止 LLM 心算百分比** -- 走 arith 工具 (BigDecimal 精度)。
- **与 `trace_recent_metrics` skill 的区别**:
  - `trace_recent_metrics` -- 走 clickhouse_query 取底层宽表行, 在 python_exec 里 pandas 算指标 (适合灵活探索)
  - `trace_recent_stats_metrics` (本 skill) -- 走 sql_registry_exec 取预聚合 1 行 (适合固定报表, 算法由 DBA 钉死)
