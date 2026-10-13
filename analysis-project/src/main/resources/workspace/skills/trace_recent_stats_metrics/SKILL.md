---
name: trace_recent_stats_metrics
description: 通过 sql_registry_exec 调预注册 SQL "trace_recent_stats_by_user" 算 ClickHouse 用户订单聚合 (GROUP BY + 聚合函数)
---

# 用户订单统计 (按 userId 分组, 走 sql_registry_exec)

> 共享硬规则 (CSV 路径 / arith 复算 / 空结果 / 直接调用 / python_exec 重试) 已在主 agent AGENTS.md
> 和子 agent sysPrompt (SubagentRegistrar 自动注入 `skills/_common/SKILL.md`) 中, 本 skill 不重复。

> 本 skill 是 `sql_registry_exec` 工具的 ClickHouse 验证实例, 演示 GROUP BY + 聚合函数的
> 复杂 SQL 走预注册路径。预注册 SQL 已由 DBA 录入 GaussDB `sql_registry` 表,
> sql_id = `trace_recent_stats_by_user`, datasource = `clickhouse`。
>
> ⚠️ 历史说明: 本 skill 原查 `default.trace_recent` 会话数据, 但该表在 ClickHouse
> 服务器上不存在 (2026/10/09 确认), 已改指 `default.platform_orders` 订单表;
> sql_id 沿用旧名未改。表为空时返回 0 行属正常, 如实告知用户即可。

业务表: `default.platform_orders` (ClickHouse; 字段: user_id / product / amount / order_date)
预注册 SQL: `trace_recent_stats_by_user` (在 `sql_registry` 表中)
适用问题: 用户问 "X 用户 + 订单数 / 消费总额 / 客单价 / 首末单日期"

## sql_id 与参数 schema

| sql_id | datasource | 参数 | 说明 |
|---|---|---|---|
| `trace_recent_stats_by_user` | clickhouse | `userId` (string, 必填) | 用户 ID, 如 "1001" |
| | | `startTime` (date, 必填) | 开始日期 ISO 格式 YYYY-MM-DD, 如 "2026-07-01" |

预注册 SQL (DBA 录入, LLM 不能改):

```sql
SELECT
  user_id AS `用户ID`,
  count() AS `订单数`,
  round(sum(amount), 2) AS `消费总额`,
  round(avg(amount), 2) AS `平均客单价`,
  min(order_date) AS `首单日期`,
  max(order_date) AS `最近订单日期`
FROM default.platform_orders
WHERE user_id = :userId
  AND order_date >= :startTime
GROUP BY user_id
```

> 中文别名必须用反引号包裹 (ClickHouse 要求, 否则报 `Unrecognized token: Syntax error`)。
> LIMIT 由工具内部固定 10000, 不要写 `LIMIT :limit` 占位符。

> 这条 SQL 含 GROUP BY + 聚合函数 (count / sum / avg / min / max), 是 wide_table_query
> 表达不了的语义, 故走 sql_registry_exec 路径而非 clickhouse_query 路径。

## 工作流 (analyze_data 必读, 严格按顺序)

### Step 1: 从用户问题提取参数

- `userId`: 用户 ID (例: "1001")
- `startTime`: 开始日期 ISO 格式 YYYY-MM-DD (例: "2026-07-01")

如果用户没指定 userId 或时间范围, 追问 -- 不要默认查全部。

### Step 2: 直接调 sql_registry_exec 取数

```
sql_registry_exec(
  sqlId="trace_recent_stats_by_user",
  params={"userId":"1001", "startTime":"2026-07-01"}
)
```

- ⚠️ **参数名必须在 params_schema 内** -- 多余参数会被工具拒执行 (防注入)。本例只能传 `userId` / `startTime` (不要传 `limit` / `tableName` / `schema` 等额外参数)。
- ⚠️ **日期格式必须是 ISO YYYY-MM-DD** -- ClickHouse Date 类型, 传 "2026/07/01" 或 "07-01-2026" 会被驱动拒。

### Step 3: 用 python_exec + pandas 后处理 (如需)

本预注册 SQL 已含 GROUP BY + 聚合, 返回的 markdown 表已经是聚合结果 (一行一用户), 不是底层行。
python_exec 仅做格式化展示:

```python
import pandas as pd
df = pd.read_csv("/workspace/artifacts/<user>/<task>/sql-xxx.csv")  # 路径从工具返回复制
print(df.to_string(index=False))
# SQL 已完成聚合, 无需再算, 直接展示
```

如果只需要展示聚合结果, 可以跳过 python_exec, 直接用工具返回的 markdown 表。

### Step 4: 用 arith 复算平均客单价 (核对口径)

```
arith(op="div", numbers=[<消费总额>, <订单数>])    # 应与 SQL 返回的平均客单价一致
```

### Step 5: 回复用户

中文, 包含用户 ID + 时间范围 + 指标数字 (订单数 / 消费总额 / 平均客单价 / 首末单日期) + 业务解读 + 数据来源标注。

> ## 示例 1: 单用户单时间段
>
> 用户问: "用户 1001 从 2026 年 7 月以来的订单统计?"
>
> params: `{"userId":"1001", "startTime":"2026-07-01"}`
>
> ### Step 2 调用
>
> ```
> sql_registry_exec(
>   sqlId="trace_recent_stats_by_user",
>   params={"userId":"1001", "startTime":"2026-07-01"}
> )
> ```
>
> ### Step 3 模板 (如需展示)
>
> ```python
> import pandas as pd
> df = pd.read_csv("/workspace/artifacts/<user>/<task>/sql-xxx.csv")
> print(df.to_string(index=False))
> # 输出: 用户ID | 订单数 | 消费总额 | 平均客单价 | 首单日期   | 最近订单日期
> #       1001   | 3      | 599.97   | 199.99     | 2026-07-02 | 2026-07-15
> ```
>
> ### Step 4 复算平均客单价
>
> ```
> arith(op="div", numbers=[599.97, 3])    # 199.99, 与 SQL 返回一致
> ```

## 注意事项

- **必填参数 userId + startTime**, 用户没指定就追问, 不要默认查全部。
- **多余参数会被拒执行** -- 只能传 userId / startTime, 传其他参数名 (如 limit / tableName / schema) 会被工具拒。
- **LIMIT 由工具内部固定 10000** -- 不要在 params 里传 limit, 也不要在 SQL 模板里写 `LIMIT :limit` (Java 路径缺参直接报错)。
- **SQL 模板不可改** -- 业务方要改 SQL (改聚合维度 / 加 CASE WHEN 等) 需找 DBA 在 sql_registry 表里改 sql_template, LLM 只能传 sql_id + params。
- **SQL 已含聚合** -- 返回的是聚合后 1 行 (按 user_id 分组), 不要在 python_exec 里再 groupBy。
- **表为空时返回 0 行** -- platform_orders 当前是空表演示环境, 查询成功但无数据属正常, 如实告知用户, 不要编造数字。
- **与 `trace_recent_metrics` skill 的区别**:
  - `trace_recent_metrics` -- 走 clickhouse_query 取底层宽表行, 在 python_exec 里 pandas 算指标 (适合灵活探索)
  - `trace_recent_stats_metrics` (本 skill) -- 走 sql_registry_exec 取预聚合 1 行 (适合固定报表, 算法由 DBA 钉死)
