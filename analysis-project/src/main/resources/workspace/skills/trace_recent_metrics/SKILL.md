---
name: trace_recent_metrics
description: 通过 script_exec 调预注册脚本 "q_clickhouse_demo_trace_events" 一次完成 ClickHouse platform_orders 订单指标计算 + 明细 CSV/xlsx 下载
---

# platform_orders 订单指标加工 + 明细下载 (script_exec 一步完成)

> 共享硬规则 (CSV 路径 / arith 复算 / 空结果 / 直接调用 / python_exec 重试) 已在主 agent AGENTS.md
> 和子 agent sysPrompt (SubagentRegistrar 自动注入 `skills/_common/SKILL.md`) 中, 本 skill 不重复。

> 本 skill 是 script_exec 明细下载能力 (print_download_csv / print_download_xlsx) 的
> ClickHouse 验证实例, 与 q2_1_script_then_download_demo (GaussDB 侧) 对应。
>
> ⚠️ 历史说明: 本 skill 原走 clickhouse_query 查 `default.trace_recent` 会话宽表, 但该表
> 在 ClickHouse 服务器上不存在 (2026/10/09 确认), 已改为 script_exec 范式查
> `default.platform_orders` 订单表; skill 名沿用旧名未改。表为空时返回 0 行 + 无下载链接
> 属正常, 如实告知用户即可。

业务表: `default.platform_orders` (ClickHouse; 字段: order_id / user_id / product / amount / order_date)
预注册脚本: `q_clickhouse_demo_trace_events` (script_id 沿用旧名, 在 `script_registry` 表中)
适用问题: 用户问 "某时间段 / 某用户 + 订单数 / 消费总额 / 客单价 (+ 要明细下载)"

## 脚本与参数 schema

| scriptId | datasources | 参数 | 说明 |
|---|---|---|---|
| `q_clickhouse_demo_trace_events` | `["clickhouse"]` | `start_date` (string, 必填) | 开始日期 ISO 格式 YYYY-MM-DD, 如 "2026-01-01" |
| | | `end_date` (string, 必填) | 结束日期 ISO 格式 YYYY-MM-DD |
| | | `user_id` (string, 可选) | 用户 ID 过滤, 如 "1001"; 用户没指定就不传 |

## 工作流 (analyze_data 必读, 严格按顺序)

### Step 1: 从用户问题提取参数

- `start_date` / `end_date`: 时间范围, ISO 格式 YYYY-MM-DD
- `user_id`: 可选, 用户明确指定某个用户时才传

如果用户没指定时间范围, 追问 -- 不要默认查全部。

### Step 2: 执行脚本 (已内置明细下载)

必须直接调用一次 `script_exec`:

```text
script_exec(
  scriptId="q_clickhouse_demo_trace_events",
  params={"start_date":"2026-01-01","end_date":"2026-12-31","user_id":"1001"}
)
```

脚本内部已固化下载文件名 (platform_orders_明细.csv / .xlsx), **不要**传 downloadFilename
之类的参数, 也**不要**再调用 sql_registry_exec / clickhouse_query 取同样的数据。

### Step 3: 回复用户

- 工具结果会被系统接管 (你看到的是占位符): 汇总表、图表与 CSV/xlsx 两条下载链接由系统
  自动附在回答末尾;
- 只需根据问题写简短的业务总结 (引用 json: 行里的指标数字), **不要**复述图表内容、
  **不要**编造下载链接或 shortCode;
- 表为空时如实告知 "查询时间段内无订单数据", 不要编造数字。

> ## 示例 1: 时间段全量
>
> 用户问: "2026 年 7 月的订单统计, 要明细。"
>
> ```text
> script_exec(
>   scriptId="q_clickhouse_demo_trace_events",
>   params={"start_date":"2026-07-01","end_date":"2026-07-31"}
> )
> ```
>
> ## 示例 2: 指定用户
>
> 用户问: "用户 1001 今年以来买了什么, 花了多少钱?"
>
> ```text
> script_exec(
>   scriptId="q_clickhouse_demo_trace_events",
>   params={"start_date":"2026-01-01","end_date":"2026-12-31","user_id":"1001"}
> )
> ```

## 注意事项

- **必填参数 start_date + end_date**, 用户没指定就追问。
- **日期格式必须是 ISO YYYY-MM-DD** -- ClickHouse Date 类型, 传 "2026/07/01" 会被驱动拒。
- **多余参数会被拒执行** -- 只能传 start_date / end_date / user_id。
- **明细不占上下文** -- 订单明细行只进 CSV/xlsx 下载块, LLM 看到的是占位符; 需要基于明细行
  做进一步计算时, 让用户直接下载文件, 不要试图从工具结果里读明细。
- **与 `trace_recent_stats_metrics` skill 的区别**:
  - `trace_recent_stats_metrics` -- 走 sql_registry_exec 取预聚合 1 行 (固定报表, SQL 由 DBA 钉死, 无下载)
  - `trace_recent_metrics` (本 skill) -- 走 script_exec 取明细行, pandas 算指标 + CSV/xlsx 明细下载 (灵活探索)
