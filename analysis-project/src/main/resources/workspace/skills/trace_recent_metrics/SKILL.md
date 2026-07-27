---
name: trace_recent_metrics
description: ClickHouse default.trace_recent 表的会话统计指标加工 - 用户/agent/状态维度
---

# trace_recent 会话指标加工

> 本 skill 是 `clickhouse_query` 工具的验证实例。后续业务方新增其他 ClickHouse 宽表指标时,
> 复制本目录改: frontmatter description / 字段映射 / 公式 / python_exec 模板里的列名,
> 即可生成新 skill (如 `event_log_metrics`), 不要改 Java 代码。

业务表: `default.trace_recent` (schema 由 `clickhouse_query` 工具固定为 `default`, 调用时只传表名)
适用问题: 用户问 "X用户/X agent/X状态 + 会话总数/平均时长/平均事件数/成功率"

## 字段映射 (9 个字段)

| 表字段 | 中文 | 类型 | 用途 |
|---|---|---|---|
| sessionId | 会话 ID | String | 唯一标识 |
| userId | 用户 ID | String | 维度-用户 |
| question | 用户问题 | String | (不参与指标, 不查) |
| createdAt | 创建时间 | DateTime | (时间维度, 验证阶段不进 filters) |
| finishedAt | 完成时间 | Nullable(DateTime) | (不查) |
| totalDurationMs | 总时长(ms) | UInt64 | 指标-平均时长 |
| status | 状态 | String | 维度-状态 / 成功率分子 (典型值: completed/error/...) |
| agentName | Agent 名 | String | 维度-agent |
| eventCount | 事件数 | UInt32 | 指标-平均事件数 |

## 6 个验证指标公式

1. **会话总数** = `count(*)`
2. **平均时长 (ms)** = `sum(totalDurationMs) / count`
3. **平均事件数** = `sum(eventCount) / count`
4. **成功率** = `status='completed'` 行数 / 总数
5. **按 agent 分布** = groupBy `agentName` + count + avg(totalDurationMs) (走 python_exec pandas)
6. **按 user 分布** = groupBy `userId` + count + avg(totalDurationMs) (同上)

## 工作流 (analyze_data 必读, 严格按顺序)

### Step 1: 从用户问题提取参数

维度三选一 (用户没指定就追问, 不要默认查全部):
- `userId`: 用户维度 (例: "alice 的会话总数")
- `agentName`: Agent 维度 (例: "analyze_data 这个 agent 平均跑多久")
- `status`: 状态维度 (例: "completed 状态的会话平均时长")

如果用户问的是"成功率"且没指定维度, filters 留空 (走全表)。

### Step 2: 直接调 clickhouse_query 取底层宽表数据

```
clickhouse_query(
  table="trace_recent",
  fields=["sessionId","userId","totalDurationMs","status","agentName","eventCount"],
  filters={"userId":"alice"}
)
```

> ⚠️ **直接调用, 不要走 router_tool**: `clickhouse_query` 已直接注册在 analyze_data 子 agent 的 Toolkit 上, 跳过 `router_tool({toolId:...})` 元工具路由能省 5 轮 LLM 往返。

> schema 由工具硬编码为 `default`, 不需要传 `default.trace_recent`, 只传表名即可。
> LIMIT 由工具硬编码为 10000, 不需要传 limit 参数。

工具返回 markdown 预览 + `📦 CSV 路径: <path>` 行 (由 ArtifactHandoffHook 自动落 CSV)。

> 🚨 **硬规则**: CSV 路径只能从 clickhouse_query 返回的 `📦 CSV 路径:` 行复制, 不要手工编造, 里面带 `<userId>/<taskId>` 前缀, 改写会被 ArtifactAccessMiddleware 越权拦截。

### Step 3: 用 python_exec + pandas 算指标

```python
import pandas as pd
df = pd.read_csv("/workspace/artifacts/<user>/<task>/ckq-xxx.csv")  # 路径从工具返回复制
total = len(df)
sum_duration = df['totalDurationMs'].sum()
avg_duration = df['totalDurationMs'].mean()
avg_events = df['eventCount'].mean()
completed = (df['status'] == 'completed').sum()
print(f"总数={total}, 平均时长={avg_duration}ms, 平均事件数={avg_events}, 完成数={completed}")
```

如果 total=0, 直接回复 "无数据", 不要调 arith。
如果 completed=0, 不要算成功率, 直接回复 "完成数为 0"。

### Step 4: 用 arith 复算 (BigDecimal, 双重保险)

```
arith(op="div", numbers=[<sum_duration>, <total>])          # 平均时长 ms
arith(op="pct", numbers=[<completed>, <total>])             # 成功率
arith(op="div", numbers=[<sum_events>, <total>])            # 平均事件数
```

arith 返回 BigDecimal 精度结果, 以此为准回复用户。

### Step 5: 回复用户

中文, 包含:
- 指标数字 (总数 / 平均时长 / 平均事件数 / 成功率)
- 业务解读 (例: "成功率 95%, 平均时长 30s - 整体运行良好")
- 数据来源标注 (基于 N 行数据)

## 示例 1: 用户维度

用户问: "alice 的会话总数、平均时长是多少?"

filters: `{"userId":"alice"}`

fields: `["sessionId","userId","totalDurationMs","status","agentName","eventCount"]`

### Step 3 模板

```python
import pandas as pd
df = pd.read_csv("/workspace/artifacts/<user>/<task>/ckq-xxx.csv")
total = len(df)
sum_duration = df['totalDurationMs'].sum()
print(f"总数={total}, 总时长={sum_duration}ms")
```

### Step 4 复算

```
arith(op="div", numbers=[<sum_duration>, <total>])
```

## 示例 2: Agent 维度

用户问: "analyze_data 这个 agent 平均跑多久, 总共多少次?"

filters: `{"agentName":"analyze_data"}`

## 示例 3: 全表成功率

用户问: "整体成功率是多少?" (没指定维度)

filters: `{}` (空, 全表扫)

### Step 3 模板

```python
import pandas as pd
df = pd.read_csv("/workspace/artifacts/<user>/<task>/ckq-xxx.csv")
total = len(df)
completed = (df['status'] == 'completed').sum()
print(f"总数={total}, 完成数={completed}")
```

### Step 4 复算

```
arith(op="pct", numbers=[<completed>, <total>])
```

## 注意事项

- **维度三选一** (用户/agent/状态), 用户没指定就追问, 不要默认查全部。
- **禁止用 SQL GROUP BY/COUNT 算指标** -- SQL 只取数, 计算走 python_exec + pandas。
- **禁止 LLM 心算百分比** -- 走 arith 工具 (BigDecimal 精度)。
- **空结果 (total=0) 时返回 "无数据"**, 不要算 0/0。
- **多维度组合也支持** (如 user + status), filters 里加多个键即可。
- createdAt / finishedAt 是 DateTime, 等值 filters 在业务上没意义, 不要传。
