# 长任务节点执行日志设计

## 目标

为长任务中的 Python 节点建立独立的 ClickHouse 执行日志，记录每个节点每次执行尝试的结果，支持在现有“会话历史”页面中查看、筛选和分页。日志用于排查和审计，不改变长任务在 GaussDB 中的业务状态流转。

## 范围

本设计只覆盖长任务直接执行注册 Python 脚本的链路：`FlowCoordinator -> ScriptExecTool`。

不覆盖智能体调用 `python_exec` 的事件，也不记录 Python 脚本内部阶段或进度。

## 核心模型

一条日志记录对应一个节点的一次执行尝试。节点重试会产生多条记录，通过 `attempt_no` 区分。

关联链路为：

```text
flow_execution_id -> node_execution_id -> attempt_id / attempt_no
```

`conversation_id` 不作为日志关联字段。长任务日志以流程执行、节点执行和尝试记录为主键线索。

## ClickHouse 表

新增表 `long_task_node_execution_log`。

### 标识与归属字段

- `log_id String`：日志唯一 ID。
- `flow_execution_id Int64`：长任务流程执行 ID。
- `node_execution_id Int64`：节点执行 ID。
- `attempt_id Int64`：节点尝试记录 ID。
- `attempt_no UInt32`：节点内的尝试序号。
- `user_id String`：触发长任务的用户，用于“我的 / 全部”过滤。
- `flow_name String`：流程名称快照。
- `node_key String`：节点键。
- `node_name String`：节点显示名称快照。
- `script_id String`：Python 脚本注册 ID 快照。

### 执行字段

- `status LowCardinality(String)`：`RUNNING`、`SUCCESS`、`FAILED`、`CANCELLED` 等状态。
- `retryable Bool`：失败是否可重试。
- `error_code String`：错误分类。
- `started_at DateTime64(3)`：本次尝试开始时间。
- `completed_at DateTime64(3)`：本次尝试结束时间。
- `duration_ms UInt64`：执行耗时。

### 内容字段

- `params_json String`：解析后的脚本参数 JSON。
- `output_text String`：脚本有效输出。
- `error_message String`：错误信息。
- `params_truncated Bool`、`output_truncated Bool`、`error_truncated Bool`：是否截断。
- `params_bytes UInt64`、`output_bytes UInt64`、`error_bytes UInt64`：截断前字节数。

### 分区、排序与保留

- `event_date Date DEFAULT toDate(started_at)`。
- `PARTITION BY toYYYYMMDD(event_date)`，按天分区。
- `ORDER BY (event_date, user_id, started_at, flow_execution_id, node_execution_id, attempt_no)`。
- `TTL event_date + INTERVAL 90 DAY`。

参数、输出和错误单字段设置统一大小上限；超过上限时保存前缀、原始字节数和截断标记。建议首版上限为 256 KB，具体值在实现时作为配置确定。

## 写入时机与一致性

节点尝试完成时写入一条最终日志，覆盖成功、失败、可重试失败、取消和超时。首版不在开始时写入 `RUNNING` 记录，避免 ClickHouse 更新成本并保证日志天然追加写入；执行中的节点状态继续从 GaussDB 查询。

日志写入失败不阻塞长任务主流程。GaussDB 中现有节点尝试表仍是业务状态的权威来源，ClickHouse 日志是查询副本。

## 查询接口

新增节点日志列表接口，支持：

- `scope=mine|all`，默认 `mine`。
- `page`，从 1 开始。
- `pageSize`，默认 20，最大 100。
- `from`、`to`：开始时间范围。
- `status`：执行状态。
- `flowName`：流程名称模糊匹配。
- `nodeName`：节点名称模糊匹配。

返回统一分页结构：

```json
{
  "items": [],
  "page": 1,
  "pageSize": 20,
  "total": 125
}
```

列表接口只返回摘要字段。详情接口按 `log_id` 返回完整参数、输出、错误及截断信息。

## 页面设计

在现有“会话历史”页面增加 Tab：

- 会话历史
- 节点执行日志

节点执行日志页面提供“我的 / 全部”切换，以及时间范围、执行状态、流程名称、节点名称四个筛选条件。列表采用服务端分页，进入页面、切换范围、筛选、翻页和点击手动刷新时加载数据，不做自动轮询。

列表展示流程、节点、脚本、状态、尝试次数、开始时间、耗时和错误摘要。点击行打开详情，不在长任务页面增加按钮。

## 权限

所有用户均可查看节点日志。“我的”按当前登录用户的 `user_id` 过滤；“全部”展示所有用户记录。详情接口沿用列表接口的可见范围规则，避免通过 `log_id` 绕过筛选范围。

## 错误与边界

- 节点在建立尝试记录前失败时，没有稳定的 `attempt_id`；这类异常继续由现有服务日志记录，首版不写入节点执行日志。
- ClickHouse 查询失败时页面显示可重试错误，不影响长任务执行。
- 输出为空时保存空字符串和字节数 0，不把空输出误判为查询失败。
- 同一次尝试重复写入时，调用方使用稳定 `log_id`；实现层需保证重试不会产生重复可见记录。

## 验证标准

1. Python 节点成功、失败、重试、取消均能产生对应日志。
2. 同一节点多次尝试可按 `attempt_no` 区分。
3. “我的 / 全部”、四类筛选和服务端分页结果正确。
4. 详情能够展示参数、输出、错误和截断信息。
5. 日志写入失败不会改变长任务成功或失败结果。
6. 天分区和 90 天 TTL 生效。

## 设计自检结论

- 关联关系完整：流程、节点、尝试三级 ID 足以定位日志，不依赖 `conversation_id`。
- 职责清晰：GaussDB 保存可变业务状态，ClickHouse 保存面向查询的追加日志。
- 查询字段覆盖页面筛选和“我的 / 全部”范围。
- 分区、排序和分页策略与时间倒序查询一致。
- 当前仍需在实现阶段固定“256 KB 上限”和最终写入时机的技术细节，但不影响表和页面设计。

