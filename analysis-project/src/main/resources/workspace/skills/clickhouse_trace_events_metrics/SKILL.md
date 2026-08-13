---
name: clickhouse_trace_events_metrics
description: 通过 script_exec 调预注册 Python 脚本 "q_clickhouse_demo_trace_events" 一次完成 ClickHouse trace_event 事件流统计 (按 event_type 分组, 事件数/去重会话/去重 trace/平均最大耗时)
---

# ClickHouse trace_event 事件流统计 (走 script_exec 一步到位)

> 共享硬规则 (CSV 路径 / arith 复算 / 空结果 / 直接调用 / python_exec 重试) 已在主 agent AGENTS.md
> 和子 agent sysPrompt (SubagentRegistrar 自动注入 `skills/_common/SKILL.md`) 中, 本 skill 不重复。

> 本 skill 是 `script_exec` 工具的 ClickHouse 数据源验证实例。预注册 Python 脚本已由开发人员录入
> GaussDB `script_registry` 表, script_id = `q_clickhouse_demo_trace_events`, datasources = `["clickhouse"]`。
> 脚本内部完成: SQL 取数 (ClickHouse via sqlalchemy+clickhouse-sqlalchemy) + pandas 算
> 事件数/去重会话/去重 trace/平均最大耗时, 一次返回 markdown 表 + JSON 行。

业务表: `trace_event` (ClickHouse, 含 event_id/conversation_id/trace_id/event_type/event_name/source/timestamp/duration_ms/event_json/event_date 字段)
预注册脚本: `q_clickhouse_demo_trace_events` (在 `script_registry` 表中)
适用问题: 用户问 "X 时间段内 trace 事件总数 / 平均耗时 / 最大耗时" 或 "X 时间段内各 event_type 事件分布"

## script_id 与参数 schema

| script_id | datasources | 参数 | 说明 |
|---|---|---|---|
| `q_clickhouse_demo_trace_events` | `["clickhouse"]` | `start_date` (string, 必填) | 开始日期, 如 "2026-07-01" |
| | | `end_date` (string, 必填) | 结束日期, 如 "2026-07-31" |
| | | `source` (string, 可选) | 按 source 字段过滤, 不传则全量 |

脚本内部 SQL (开发人员录入, LLM 不能改):

```sql
SELECT
  event_type AS "事件类型",
  count() AS "事件数",
  uniqExact(conversation_id) AS "去重会话数",
  uniqExact(trace_id) AS "去重trace数",
  round(avg(duration_ms), 2) AS "平均耗时ms",
  max(duration_ms) AS "最大耗时ms"
FROM trace_event
WHERE event_date BETWEEN toDate(:start) AND toDate(:end)
  [AND source = :source]   -- source 传了才加
GROUP BY event_type
ORDER BY "事件数" DESC
```

脚本内部 pandas 计算 (开发人员录入, LLM 不能改):

```python
total_events = int(df["事件数"].sum())               # 跨组 sum = 总事件数
distinct_conv = int(df["去重会话数"].sum())           # 下限近似 (同 user 跨 event_type 会重复算)
total_duration = (df["事件数"] * df["平均耗时ms"]).sum()
avg_duration_ms = round(total_duration / total_events, 2)  # 加权平均
max_duration_ms = int(df["最大耗时ms"].max())
```

## 工作流 (严格按顺序)

### Step 1: 从用户问题提取参数

- `start_date`: 开始日期 (例: "2026-07-01")
- `end_date`: 结束日期 (例: "2026-07-31")
- `source`: 可选, 按 source 字段过滤 (例: "analyze_data" / "supervisor")

如果用户没指定时间范围, 追问 -- 不要默认查全部 (会扫全表)。常见来源:
- 用户说 "7月份" -> start_date="2026-07-01", end_date="2026-07-31" (注意用当前年份补全)
- 用户说 "最近一周" -> 用今天的日期往前推 7 天

### Step 2: 直接调 script_exec 一步到位 (SQL 取数 + pandas 算指标)

```
script_exec(
  scriptId="q_clickhouse_demo_trace_events",
  params={"start_date":"2026-07-01", "end_date":"2026-07-31"}
)
```

带 source 过滤:

```
script_exec(
  scriptId="q_clickhouse_demo_trace_events",
  params={"start_date":"2026-07-01", "end_date":"2026-07-31", "source":"analyze_data"}
)
```

- ⚠️ **参数名必须在 params_schema 内** -- 多余参数会被工具拒执行 (防注入)。本例只能传 `start_date` / `end_date` / `source`。
- 工具返回 markdown 汇总表 + 明细前 10 行 + 末行 `json: {"total_events":N,"event_types":N,"distinct_conv_lower_bound":N,...}` (程序解析用)。
- **指标已由脚本算好** (含加权平均耗时), LLM 直接读 JSON 字段即可, **不需要再调 arith 复算**。
- 不需要再调 `python_exec` 写 pandas 代码 -- 脚本内部已经算好了。

### Step 3: 回复用户

中文, 包含时间范围 + 指标数字 (事件总数 / 事件类型数 / 去重会话数 / 平均耗时 / 最大耗时) + 业务解读 + 数据来源标注。

> ## 示例 1: 时间范围查询 (不带 source)
>
> 用户问: "2026年7月份 trace 事件总数和平均耗时多少?"
>
> params: `{"start_date":"2026-07-01", "end_date":"2026-07-31"}`
>
> ### Step 2 调用
>
> ```
> script_exec(
>   scriptId="q_clickhouse_demo_trace_events",
>   params={"start_date":"2026-07-01", "end_date":"2026-07-31"}
> )
> ```
>
> 返回 (示意):
> ```
> | 事件总数 | 事件类型数 | 独立用户数(下限) |
> |---:|---:|---:|
> | 12580 | 8 | 342 |
>
> 明细 (按事件类型, 前 10 行):
>   - tool_call: 事件数=5230, 去重会话=180, 去重trace=412, 平均耗时=342.5ms, 最大耗时=8200
>   - llm_response: 事件数=4102, 去重会话=175, 去重trace=389, 平均耗时=1820.3ms, 最大耗时=45000
>   ...
>
> json: {"total_events":12580,"event_types":8,"distinct_conv_lower_bound":342,...}
> ```
>
> ### Step 3 回复
>
> "2026年7月 trace_event 统计: 事件总数 12580, 涵盖 8 种事件类型, 涉及去重会话 342 个 (下限近似)。
> 平均耗时 X ms, 最大耗时 Y ms。明细按事件数降序, tool_call 占比最高..."

> ## 示例 2: 带 source 过滤
>
> 用户问: "analyze_data agent 这个月 trace 事件情况?"
>
> params: `{"start_date":"2026-08-01", "end_date":"2026-08-31", "source":"analyze_data"}`
>
> ### Step 2 调用
>
> ```
> script_exec(
>   scriptId="q_clickhouse_demo_trace_events",
>   params={"start_date":"2026-08-01", "end_date":"2026-08-31", "source":"analyze_data"}
> )
> ```

## 注意事项

- **必填参数 start_date + end_date**, 用户没指定就追问, 不要默认查全部 (trace_event 表大, 全表扫慢)。
- **多余参数会被拒执行** -- 只能传 start_date / end_date / source, 传其他参数名 (如 limit / tableName / event_type) 会被工具拒。
- **source 是可选的** -- 用户没提 source 就不传, 不要传空字符串。
- **distinct_conv 是下限近似** -- 同一 conversation 跨 event_type 会被分组去重后重复累加, 精确全局去重需另查 `SELECT uniqExact(conversation_id) FROM trace_event WHERE ...`。回复用户时标注 "(下限近似)"。
- **avg_duration_ms 是加权平均** -- 脚本用 `sum(事件数 * 组平均) / 总事件数` 计算, 不是组平均的简单算术平均。LLM 直接读 JSON 字段, 不要再用 arith 复算。
- **脚本不可改** -- 业务方要改 SQL 或 pandas 计算逻辑需找开发人员在 `script_registry` 表里改 script_path 指向的 .py 文件, LLM 只能传 script_id + params。
- **替代 clickhouse_query + python_exec 两步走** -- 一次 script_exec 调用拿到全部数字, 不再需要 LLM 写 pandas 代码, 避免 qwen3:8b 写代码卡死。
- **event_date 走分区裁剪** -- SQL 用 `event_date BETWEEN toDate(:start) AND toDate(:end)` 而非 `timestamp BETWEEN`, 走 Date 分区裁剪比 DateTime 范围扫描快。
