# ClickHouse 宽表指标加工方案

> 在已有的 GaussDB 宽表工具 `WideTableMetricsTool` (走 `wide_table_query`) 旁边,新增一套对等
> 的 ClickHouse 通用 SELECT 工具 `clickhouse_query`,并先用 `default.trace_recent` 这张 trace
> 元数据表写一个验证 skill (`trace_recent_metrics`),证明 LLM 能基于该工具走完
> "load_skill -> clickhouse_query -> python_exec -> arith -> 回复" 的 5 步工作流。
>
> 本文档面向业务人员与运维,描述整体架构、关键文件、与 GaussDB 方案的差异点、SKILL 设计与验证步骤。

## 1. 背景与目标

### 业务需求

部分宽表落在 ClickHouse 上 (而非 GaussDB)。业务方希望对话方式查这些表,沿用
GaussDB 宽表已验证的 5 步工作流 (load_skill -> 查数 -> python_exec 算指标 -> arith 复算 -> 中文回复)。

第一张验证表 (用户提供):

```sql
CREATE TABLE default.trace_recent
(
    `sessionId`        String,
    `userId`           String,
    `question`         String,
    `createdAt`        DateTime,
    `finishedAt`       Nullable(DateTime),
    `totalDurationMs`  UInt64,
    `status`           String,
    `agentName`        String,
    `eventCount`       UInt32
)
ENGINE = MergeTree
ORDER BY (createdAt, sessionId)
SETTINGS index_granularity = 8192
```

验证用指标 (会话 trace 元数据,不是业务宽表指标,仅用于验证工具链):

| 指标 | 公式 |
|---|---|
| 会话总数 | `count(*)` |
| 平均会话时长 | `avg(totalDurationMs)` (ms / 转 s) |
| 平均事件数 | `avg(eventCount)` |
| 成功率 | `status='completed' / count(*)` |
| 按 agent 分布 | groupBy `agentName` + count/avg |
| 按 user 分布 | groupBy `userId` + count/avg |

### 设计目标

1. **对齐 GaussDB 方案** -- `clickhouse_query` 与 `wide_table_query` 结构、签名、CSV artifact 链路完全一致,LLM 学一次就能两边用。
2. **复用现有 agent** -- 不新增子 agent,继续走 `analyze_data` 子 agent + `python_exec` + `arith` 工具链。
3. **真实 DB 查询** -- 连真实 ClickHouse (124.222.194.178:8123/default),不走 mock。
4. **业务自治** -- 新增表只写 SKILL.md,不改 Java 代码 (与 GaussDB 方案一致)。

## 2. 架构总览

```
用户问 "alice 的会话总数、平均时长是多少?"
    ↓
Supervisor (AGENTS.md 路由) -> agent_spawn(analyze_data)
    ↓
analyze_data load_skill_through_path(name="trace_recent_metrics")
    (skill 提供: 表名 default.trace_recent + 字段映射 + 指标公式 + python_exec 模板)
    ↓
Step 1: analyze_data 调 clickhouse_query(
            table="trace_recent",
            fields=["sessionId","userId","totalDurationMs","status","agentName","eventCount"],
            filters={"userId":"alice"})
        -> 工具返回 CSV artifact 路径 + 前 10 行预览 (不算任何指标)
    ↓
Step 2: analyze_data 调 python_exec(pandas 代码)
        - pd.read_csv(<CSV 路径>)
        - 算 count / avg / 分位数
        -> 返回数字
    ↓
Step 3: analyze_data 调 arith(op="div", numbers=[sum_duration, count]) 复算平均时长
        (BigDecimal 双保险, 禁止心算)
    ↓
Step 4: 回复用户 (中文 + 数字 + 业务解读)
```

### 三层职责划分

| 层 | 职责 | 实现位置 |
|---|---|---|
| **Java 工具层** | 通用 ClickHouse SELECT 查询,落 CSV artifact | `ClickHouseWideTableMetricsTool.java` (新增) |
| **Skill 层** | 每张 CH 表一个 skill,定义字段映射+公式+python_exec 模板 | `workspace/skills/trace_recent_metrics/SKILL.md` (新增,验证用) |
| **Agent 层** | 读 skill,组合 clickhouse_query + python_exec + arith | `analyze_data` 子 agent (仅 spec tools 列表加一项) |

**核心原则** (与 GaussDB 方案一致): Java 工具只做通用 SELECT,不写任何业务指标公式。所有业务逻辑
(字段映射、公式、计算模板) 都在 skill 里,业务人员改 skill 不需要重新编译 Java。

## 3. 关键设计决策

### 3.1 新工具 `clickhouse_query` 与 `wide_table_query` 的差异

只 3 处不同,其余完全对齐:

| 项 | `WideTableMetricsTool` (GaussDB) | `ClickHouseWideTableMetricsTool` (ClickHouse) |
|---|---|---|
| DataSource Bean | `gaussDataSource` | `clickHouseDataSource` (已由 `ClickHouseConfig` 注入) |
| schema (硬编码) | `remote_app` | `default` |
| 列名校验 SQL | `information_schema.columns WHERE table_schema=? AND table_name=?` | `system.columns WHERE database=? AND table=?` (CH 权威视图) |
| @Tool name | `wide_table_query` | `clickhouse_query` (避免重名) |
| 标识符引号 | `"ident"` (PG 双引号,处理 `app` 保留字) | `"ident"` (CH 0.6.x 接受 ANSI 双引号) |
| `filters` / `fields` / `LIMIT=10000` | 等值 AND,参数化绑定 | 沿用 |
| 列名正则 `^[a-zA-Z_][a-zA-Z0-9_]*$` | 适用 | 适用 (trace_recent 列名 camelCase 兼容) |

SQL 注入防护沿用 GaussDB 的三层:
- **表名**: 正则白名单, schema 硬编码, 不接受特殊字符。
- **字段名 / filter 列名**: 从 `system.columns` 查实际列名,只接受集合内字段。
- **filter 值**: `PreparedStatement.setXxx()` 参数化绑定。
- **LIMIT**: 工具内部固定 10000, 不暴露给 LLM。
- **Connection**: `setReadOnly(true)`。

### 3.2 列名校验为什么用 `system.columns` 而不是 `information_schema.columns`

ClickHouse 0.6.x 同时支持 `information_schema.columns` (ANSI 兼容视图) 和 `system.columns` (CH 原生
系统表)。`system.columns` 是权威数据源,包含 `database` / `table` / `column` / `type` / `position`
等字段,DDL 改了立即反映;`information_schema.columns` 在 CH 上是映射视图,部分版本对 Nullable /
LowCardinality 类型描述不全。选 `system.columns` 更稳。

```sql
SELECT name FROM system.columns WHERE database = ? AND table = ?
```

### 3.3 schema 硬编码 `default`,不暴露给 LLM

与 GaussDB 方案一致: schema 不在工具参数里出现,LLM 不能传错 schema。如果后续要查非 `default`
库的表 (如 `analytics.events`),再加第二个工具 `clickhouse_query_analytics` 或者把 schema
参数化 (优先前者,LLM 传 schema 容易出错)。

### 3.4 trace_recent 的 `createdAt` 不进 filters

`createdAt` 是 DateTime,等值 filters (`WHERE createdAt = ?`) 在业务上没意义 (用户不会问
"createdAt='2026-07-27 14:30:00' 的会话")。验证阶段只暴露 3 个字符串维度做等值筛选:
`userId` / `agentName` / `status`。后续如果要做时间范围,再扩 `clickhouse_query` 加
`rangeFilters` 参数 (类似 `WHERE createdAt >= ? AND createdAt < ?`),但这是后续需求,不在
本次方案内。

### 3.5 工具直接注册给子 agent,跳过 router_tool

与 `wide_table_query` 一致 (见 memory `wide_table_query_direct_exposure`): `clickhouse_query`
直接注册在 `analyze_data` 子 agent 的 Toolkit 上,LLM 一次调通,省 `toolMetaInfo` + `router_tool`
共 4-5 轮 LLM 拼参往返。

`ToolRoutersIndex` 也会注册一份 (让主 agent / 其他子 agent 通过 `router_tool` 也能调),但
`analyze_data` 走直注册路径。

## 4. 文件清单

### 新增文件

| 文件 | 用途 |
|---|---|
| `src/main/java/com/agentscopea2a/v2/tools/ClickHouseWideTableMetricsTool.java` | 通用 ClickHouse SELECT 工具 |
| `src/main/resources/workspace/skills/trace_recent_metrics/SKILL.md` | trace_recent 验证 skill |
| `src/test/java/com/agentscopea2a/v2/tools/ClickHouseWideTableMetricsToolTest.java` | 单测,连真 CH 跑一次 `SELECT * FROM trace_recent LIMIT 1` |
| `src/main/resources/docs/table-mertics/clickhouse-方案.md` | 本文档 |

### 改动文件

| 文件 | 改动 |
|---|---|
| `src/main/java/com/agentscopea2a/v2/config/V2ToolConfig.java` | 加 `@Bean ClickHouseWideTableMetricsTool` (注入 `clickHouseDataSource`); `V2ToolGroupAdapter` builder 加 `clickhouse_query` (ungrouped,可选 -- 主 agent 是否直接调) |
| `src/main/java/com/agentscopea2a/v2/tools/ToolRoutersIndex.java` | 构造多塞一个 `ClickHouseWideTableMetricsTool` 参数,`init()` 里 `registerTools` 注册一份 (让 `router_tool` 也能调度) |
| `src/main/java/com/agentscopea2a/v2/runner/SubagentRegistrar.java` | 构造加 `ObjectProvider<ClickHouseWideTableMetricsTool>`, `toolRegistry.put("clickhouse_query", ckWt)` |
| `src/main/resources/workspace/agent-subagents/analyze_data.md` | `tools:` 列表加 `clickhouse_query` |

### 不动

- `application-dev.properties` -- ClickHouse 数据源已配好 (`spring.datasource.hikari.clickhouse.*`)
- `WideTableMetricsTool.java` -- GaussDB 工具保持不动
- `ClickHouseConfig.java` / `ClickHousePingMapper.java` -- 已有基础设施不动
- 其他 skill (`wide_table_q2_1_metrics` / `data_primitives` / `tool_index`)

## 5. `clickhouse_query` 工具签名

```
clickhouse_query(
    table:   String           -- 表名 (不含 schema, schema 固定为 default), 如 "trace_recent"
    fields:  List<String>     -- SELECT 字段列表, 如 ["sessionId","userId","totalDurationMs"]
    filters: Map<String,Object>?  -- WHERE 等值条件, 如 {"userId":"alice","status":"completed"}
                                  -- 列名必须在表内存在, 值走参数化绑定防注入
)
-> ToolResultBlock.text(markdown 表 + 行数 + 耗时 ms)
```

返回格式与 `wide_table_query` 完全一致,`ArtifactHandoffHook` 看到 markdown 表自动落 CSV
artifact,后续 `python_exec` 直接 `pd.read_csv(<CSV 路径>)`。

示例返回:

```
[clickhouse_query] table=trace_recent filters={userId=alice} limit=10000

| sessionId | userId | totalDurationMs | status    | agentName   | eventCount |
|---|---|---|---|---|---|
| abc-123   | alice  | 45230           | completed | analyze_data | 12 |
| def-456   | alice  | 12100           | completed | analyze_data | 8 |
...

[clickhouse_query] 共 2 行, 耗时 87 ms
```

## 6. SKILL 设计 -- `trace_recent_metrics`

frontmatter:

```yaml
---
name: trace_recent_metrics
description: ClickHouse default.trace_recent 表的会话统计指标加工 - 用户/agent/状态维度
---
```

body 结构对齐 `wide_table_q2_1_metrics/SKILL.md`:

### 字段映射 (9 个字段)

| 表字段 | 中文 | 用途 |
|---|---|---|
| sessionId | 会话 ID | 唯一标识 |
| userId | 用户 ID | 维度-用户 |
| question | 用户问题 | (可选, 不参与指标) |
| createdAt | 创建时间 | (时间维度, 验证阶段不进 filters) |
| finishedAt | 完成时间 | (时间维度, 不进 filters) |
| totalDurationMs | 总时长(ms) | 指标-平均时长 |
| status | 状态 | 维度-状态 / 成功率计算 (值: completed/error/...) |
| agentName | Agent 名 | 维度-agent |
| eventCount | 事件数 | 指标-平均事件数 |

### 6 个验证指标公式

1. **会话总数** = `count(*)`
2. **平均时长** = `sum(totalDurationMs) / count` (ms, arith 复算转 s)
3. **平均事件数** = `sum(eventCount) / count`
4. **成功率** = `status='completed'` 行数 / 总数
5. **按 agent 分布** = `groupBy agentName + count + avg(totalDurationMs)` (走 `python_exec` pandas)
6. **按 user 分布** = `groupBy userId + count + avg(totalDurationMs)` (同上)

### 5 步工作流 (analyze_data 必读)

#### Step 1: 从用户问题提取参数

维度三选一 (用户没指定就追问, 不要默认查全部):
- `userId`: 用户维度
- `agentName`: Agent 维度
- `status`: 状态维度

#### Step 2: 直接调 clickhouse_query 取数

```
clickhouse_query(
  table="trace_recent",
  fields=["sessionId","userId","totalDurationMs","status","agentName","eventCount"],
  filters={"userId":"alice"}
)
```

工具返回 markdown 预览 + `📦 CSV 路径: <path>` (由 ArtifactHandoffHook 自动落 CSV)。

#### Step 3: 用 python_exec + pandas 算指标

```python
import pandas as pd
df = pd.read_csv("/workspace/artifacts/<user>/<task>/ckq-xxx.csv")
total = len(df)
avg_duration = df['totalDurationMs'].mean()
avg_events = df['eventCount'].mean()
print(f"总数={total}, 平均时长={avg_duration}ms, 平均事件数={avg_events}")
```

如果 total=0, 直接回复 "无数据"。

#### Step 4: 用 arith 复算 (BigDecimal, 双重保险)

```
arith(op="div", numbers=[sum_duration, count])   # 平均时长 ms
arith(op="div", numbers=[completed_count, total])  # 成功率
```

#### Step 5: 回复用户

中文, 包含数字 + 业务解读 + 数据来源行数。

### 示例 1: 用户维度

用户问: "alice 的会话总数、平均时长是多少?"

filters: `{"userId":"alice"}`

### 示例 2: Agent 维度

用户问: "analyze_data 这个 agent 平均跑多久?"

filters: `{"agentName":"analyze_data"}`

### 示例 3: 状态分布

用户问: "成功率是多少?" (不指定维度, 走全表)

filters: `{}` (空, 全表扫)

## 7. 影响清单与风险

### 影响清单

- 新增 4 个文件, 改动 4 个文件
- 不改 properties / 不改 DDL / 不改其他 skill
- 不改 `WideTableMetricsTool` (GaussDB 工具完全独立)

### 风险点

1. **ClickHouse 列名校验 SQL** -- 用 `system.columns` 而非 `information_schema.columns`。
   如果 CH 版本对 `system.columns` 有访问权限限制,会拿到空集,工具会拒执行并报"列名校验失败"。
   `test_user` 账号当前已知能读 `system.columns` (ClickHousePingMapper 测试时验证过)。

2. **CGLIB 代理** -- `ClickHouseWideTableMetricsTool` 不挂 `@Timed` / `@Async` 等切面,不会被代理。
   `SubagentRegistrar` 已有通用 unwrap 逻辑,即使后续加切面也无害。

3. **零数据** -- trace_recent 表如果空,skill 要明确"无数据"分支,避免 0/0。SKILL.md Step 3 已写。

4. **camelCase 列名** -- CH 列名 `sessionId`/`userId`/`totalDurationMs` 等,与工具列名正则
   `^[a-zA-Z_][a-zA-Z0-9_]*$` 兼容,无需调整正则。

5. **Nullable(DateTime) 字段** -- `finishedAt` 是 Nullable, `rs.getString(i)` 会返回 null,
   `escapeCell(null)` 已处理 (返回空串)。

6. **DateTime 渲染** -- CH 把 DateTime 返回为 `2026-07-27 14:30:00` 字符串,直接进 markdown 表,
   pandas `read_csv` 会按字符串读,SKILL 里如果要算时间差再 `pd.to_datetime` 即可 (验证指标不涉及)。

## 8. 验证步骤

### 阶段 1: 工具单测

```bash
mvn test -Dtest=ClickHouseWideTableMetricsToolTest
```

测试用例:
- `testBasicSelect` -- `SELECT sessionId,userId,status FROM trace_recent LIMIT 1`,断言 markdown 表头正确。
- `testFilterByUserId` -- `filters={"userId":"alice"}`,断言返回行都是 alice。
- `testInvalidColumnName` -- `fields=["nonexistent"]`,断言工具拒执行返回错误信息。
- `testInvalidTableName` -- `table="trace_nonexistent"`,断言工具拒执行。

### 阶段 2: E2E (启动后端 + 前端)

1. `mvn spring-boot:run` 启动后端 (确认日志里有 `ClickHouseWideTableMetricsTool: wired` 和
   `SubagentRegistrar: toolRegistry built with ... clickhouse_query`)。
2. 前端发问: "alice 的会话总数、平均时长是多少?"
3. 观察 trace:
   - Supervisor 路由到 `analyze_data` 子 agent
   - 子 agent 调 `load_skill_through_path(name="trace_recent_metrics")`
   - 子 agent 调 `clickhouse_query(table="trace_recent", filters={"userId":"alice"})`
   - 子 agent 调 `python_exec` 算 count/avg
   - 子 agent 调 `arith` 复算
   - 回复中文 + 数字 + 业务解读

### 阶段 3: 跨用户验证

确认 trace_recent 里如果 alice / bob 都有数据,两个用户问各自指标时 filters 不串扰
(走等值 WHERE,参数化绑定,天然隔离,无需额外处理)。

## 9. 后续扩展 (本次不做)

- **多 schema 支持**: 如果后续要查非 `default` 库的表,再加 `clickhouse_query_analytics`
  或参数化 schema (优先前者)。
- **时间范围 filters**: 如果业务需要 `createdAt BETWEEN ? AND ?`,扩 `clickhouse_query` 加
  `rangeFilters: Map<String, Object[]>` 参数 (`{"createdAt":["2026-07-01","2026-08-01"]}`),
  不影响现有等值 filters 路径。
- **GROUP BY 下推**: 当前方案 SQL 只 SELECT,LIMIT 10000 后全量拉回内存走 pandas 算。
  如果 trace_recent 数据量上到百万级,再加 `clickhouse_groupby` 工具下推聚合 (CH 强项)。
  验证阶段 99 行数据,不需要。
