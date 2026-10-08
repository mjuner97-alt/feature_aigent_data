# LLM 请求完整内容与上下文大小观测方案

## 1. 目标与边界

### 1.1 目标

在测试环境中回答两个问题：

1. 本轮每一次真正提交给 LLM 的完整请求内容是什么；
2. 请求由哪些部分组成，每部分占用多少 KB、估算多少 Token，最终总量是多少。

页面主要使用 `frontend-pm`，观测能力服务于调试上下文压缩、Skill 路由、工具多轮调用和模型请求失败问题。

### 1.2 明确不做的事情

- 不修改 LLM 的回答内容，不对 `finalAnswer` 做过滤、去重或重写。
- 不把观测信息拼回发送给 LLM 的上下文。
- 不默认保存历史案例。
- 不默认加载任何完整 `SKILL.md` 到观测页面或新的上下文；只记录当次请求实际已经进入 messages 的内容。
- 不把 provider 的真实 Token 用量伪装成调用前精确值。调用前只能估算，调用结束后如果 provider 返回 usage，则同时展示真实 usage。
- 不将 API Key、Authorization、Cookie、密码、数据库连接串写入快照。
- 不把大请求全文通过 SSE 持续推送；SSE 只发送轻量的 traceId、状态和汇总，全文通过受保护的 REST 接口按需读取。

### 1.3 适用接口

第一阶段覆盖 `/v2/ai/chat`，因为它已经有 v2 trace、上下文预算中间件和 `frontend-pm` 聊天页面。

第二阶段再评估 `/ai/chat`。v1 不能因为增加观测而改变现有回答链路；如果复用采集器，必须保证采集失败不影响 v1 请求。

## 2. 当前代码基础与缺口

当前后端已有：

- `ContextSizeEstimator`：按消息和工具 schema 计算字符数及估算 Token；
- `ContextBudgetMiddleware`：在 reasoning 边界观察和压缩输入；
- `TraceSession`：记录工具事件、模型调用结束时的 input/output Token 汇总；
- `/api/trace/conversations` 和 `/api/trace/{conversationId}`：查询会话 trace；
- ClickHouse `trace_conversation`、`trace_event`：保存会话汇总和事件 JSON；
- `frontend-pm/src/components/ActivityFeed.tsx`：显示本轮智能体活动；
- `frontend-pm/src/pages/ChatPage.tsx`、`ChatPanel.tsx`：管理当前会话和 SSE 流。

缺口是：

- 没有保存最终 middleware 处理完成后、即将进入模型调用的 `messages` 与 `tools` 快照；
- 没有把 system prompt 拆分成可解释的来源区块；
- 没有每次模型调用级别的大小统计；
- 当前 trace API 只能看到事件和会话总 Token，不能查看“这一轮的最终请求全文”。

## 3. 总体架构

```text
用户请求
  -> v2 ChatController
  -> Agent hooks / middlewares
  -> LlmRequestObserver（模型调用前）
       |- 生成 requestId / callId
       |- 对最终 messages、tools 做分块快照
       |- 计算 UTF-8 bytes、KB、字符数、估算 Token
       |- 脱敏并按开关决定是否保存全文
       `- 只向 SSE 发送轻量 request_observed 事件
  -> LLM provider
  -> ModelCallEndEvent
       |- 补写真实 input/output usage
       `- 更新快照状态
  -> Trace / LLM observation store
  -> GET /api/llm-observations/{conversationId}/{requestId}
  -> frontend-pm 请求观测面板
```

### 3.1 采集边界

采集点必须位于所有会改变最终输入的逻辑之后，包括：

- 当前轮消息和已恢复的会话消息；
- system prompt；
- Skill 路由摘要或已选 Skill 内容；
- 工具 schema；
- 工具结果、工具结果裁剪和 Artifact handoff；
- 上下文压缩后的保留消息。

因此不能只在 Controller 入口采集，也不能只使用 `ContextBudgetMiddleware` 第一次看到的输入。推荐让 `LlmRequestObserver` 作为模型调用前的最后一个 middleware/hook，收到的对象就是将交给模型适配器的最终 `ReasoningInput`。

### 3.2 多次模型调用

一轮用户请求可能包含主 Agent、子 Agent、工具决策和最终回答等多次 LLM 调用。每次调用独立生成 `callId`，归属于同一个 `requestId`：

- `requestId`：一次 `/v2/ai/chat` 请求；
- `callId`：本次请求中的一次具体模型调用；
- `conversationId`：跨轮会话 ID；
- `traceId`：已有 trace 关联 ID。

前端默认显示本轮调用列表，点击某次调用才加载其完整内容，避免页面初始加载几十 MB。

## 4. 数据模型

### 4.1 请求汇总表

建议新增 ClickHouse 表 `llm_request_observation`，一行对应一个 `callId`：

| 字段 | 类型 | 说明 |
|---|---|---|
| `request_id` | String | `/v2/ai/chat` 请求 ID |
| `call_id` | String | 单次 LLM 调用 ID |
| `conversation_id` | String | 会话 ID |
| `trace_id` | String | 现有 trace ID |
| `user_id` | String | 脱敏或内部用户 ID |
| `source` | LowCardinality(String) | `v2_chat` / `subagent` |
| `agent_name` | String | 主 Agent 或子 Agent 名称 |
| `model` | LowCardinality(String) | 模型名，不保存 API Key |
| `phase` | LowCardinality(String) | `before_model` / `after_model` |
| `status` | LowCardinality(String) | `captured` / `completed` / `failed` |
| `message_count` | UInt32 | 最终消息数 |
| `tool_count` | UInt32 | 工具 schema 数 |
| `total_chars` | UInt64 | 逻辑字符数 |
| `total_bytes` | UInt64 | UTF-8 序列化字节数 |
| `total_kb` | Float64 | `total_bytes / 1024` |
| `estimated_input_tokens` | UInt64 | 调用前估算值 |
| `provider_input_tokens` | UInt64 | provider 返回的真实值，无则为 0 |
| `provider_output_tokens` | UInt64 | provider 返回的真实值，无则为 0 |
| `redaction_count` | UInt32 | 脱敏次数 |
| `content_mode` | LowCardinality(String) | `metadata_only` / `full` |
| `created_at` | DateTime64(3) | 创建时间 |
| `completed_at` | DateTime64(3) | 完成时间 |
| `event_date` | Date | TTL 分区字段 |

默认只保存汇总和区块元数据；测试环境显式打开全文后，`content_mode=full`。

### 4.2 区块表

新增 `llm_request_observation_block`，一行对应请求中的一个逻辑区块：

| `call_id` | `block_id` | `block_type` | `source` | `item_index` | `chars` | `bytes` | `kb` | `estimated_tokens` | `content` |
|---|---|---|---|---:|---:|---:|---:|---:|---|
| 调用 ID | 区块 ID | `system`/`history`/`user`/`assistant`/`tool_result`/`tool_schema`/`options` | 来源标签 | 原始顺序 | 字符数 | UTF-8 字节数 | bytes/1024 | chars/4 向上取整 | 脱敏后的可选全文 |

区块必须保留原始顺序。对 messages 中的每个 message 至少保存：`role`、`index`、`source`、`contentBlocks`；工具结果单独标记为 `tool_result`，不能只汇总到 history。

### 4.3 内容快照格式

REST 返回的完整内容使用稳定 JSON，不直接返回框架对象：

```json
{
  "requestId": "req_xxx",
  "callId": "call_xxx",
  "conversationId": "conv_xxx",
  "summary": {
    "totalBytes": 512340,
    "totalKb": 500.33,
    "estimatedInputTokens": 128085,
    "providerInputTokens": 0,
    "messageCount": 18,
    "toolCount": 12
  },
  "blocks": [
    {
      "type": "system",
      "source": "base_system_prompt",
      "index": 0,
      "chars": 12000,
      "bytes": 18000,
      "kb": 17.58,
      "estimatedTokens": 3000,
      "content": "..."
    }
  ]
}
```

## 5. KB 与 Token 计算规则

### 5.1 KB 作为主指标

所有区块统一使用 UTF-8 编码：

```text
bytes = UTF_8.getBytes(content).length
kb = bytes / 1024.0
```

总字节数应基于最终请求 JSON 序列化结果计算；区块字节数用于解释组成。两者可能存在 JSON 引号、转义、字段名和数组分隔符开销，页面必须明确展示：

- `内容区块合计`；
- `协议序列化开销`；
- `最终请求总大小`。

不能简单把区块 `chars` 当作 KB，也不能以 Java `String.length()` 作为网络大小。

### 5.2 Token 规则

- 调用前：沿用 `ContextSizeEstimator` 的离线估算，并在观测中标记 `estimated`；
- 调用后：读取 `ModelCallEndEvent` 的 provider usage，标记 `provider`；
- provider 没有返回 usage 时，不补写伪精确值，保留估算值和 `providerInputTokens=0`；
- 前端同时展示 `估算 Token` 和 `模型实际 Token`，不混为一个数字。

### 5.3 50K 压缩阈值关联

观测记录必须带上采集前后的上下文状态：

- `before_compaction_total_kb`、`before_compaction_estimated_tokens`；
- `after_compaction_total_kb`、`after_compaction_estimated_tokens`；
- `compaction_triggered`；
- `compaction_reason`，如 `warn_ratio`、`hard_ratio`、`manual`。

当前治理目标是估算上下文达到 50K Token 时启动保护/压缩。观测功能只记录这个事实，不重新实现压缩；最终提交给 LLM 的快照必须是压缩后的 `after` 数据。

## 6. 脱敏、权限与留存

### 6.1 脱敏规则

保存全文前执行统一 `LlmObservationRedactor`：

- `Authorization: Bearer ...`、`api_key`、`token`、`password`、`secret` 替换为 `[REDACTED]`；
- URL 中的凭据、JDBC 密码、Cookie 替换；
- 可配置用户 ID 脱敏；
- SQL 默认保留结构和参数占位符，不保存真实账号密码；
- 对包含 `"token"`、`"apiKey"` 等字段的 JSON 递归处理，而不是正则只处理纯文本。

页面上显示“已脱敏”，不能提供绕过脱敏的普通用户开关。

### 6.2 权限

沿用现有用户身份和维度可见性：

- 普通用户只查看自己的 `conversationId`；
- 管理员/测试管理员可按用户和会话查询；
- 不能因为知道 `conversationId` 就读取其他用户内容；
- 后端对 `conversationId`、`requestId`、`callId` 做归属校验；
- 所有全文读取写审计日志。

### 6.3 开关与留存

建议配置：

```properties
harness.a2a.llm-observation.enabled=false
harness.a2a.llm-observation.capture-content=false
harness.a2a.llm-observation.max-content-bytes=10485760
harness.a2a.llm-observation.ttl-days=7
harness.a2a.llm-observation.allow-admin-full-content=true
```

规则：

- 生产默认关闭全文，只保留汇总和区块大小；
- 测试环境按需打开全文；
- 单次调用超过 `max-content-bytes` 时，保存前 N 字节并标记 `truncated=true`，同时保留完整大小统计；
- 汇总和全文分开 TTL，全文 TTL 更短；
- 前端关闭页面不等于删除服务端快照，删除由 TTL 或管理员清理接口负责。

## 7. 后端接口设计

### 7.1 SSE 轻量事件

在已有 v2 SSE 中增加事件名 `llm_request_observed`，只发送：

```json
{
  "requestId": "req_xxx",
  "callId": "call_xxx",
  "phase": "before_model",
  "agentName": "analyze_data",
  "messageCount": 18,
  "toolCount": 12,
  "totalKb": 500.33,
  "estimatedInputTokens": 128085,
  "compactionTriggered": true,
  "contentAvailable": true
}
```

SSE 不携带 `content`，也不携带完整 system prompt。

### 7.2 会话列表

```http
GET /api/llm-observations/conversations/{conversationId}?page=0&size=20
```

返回本会话的请求列表和每次请求的调用汇总，按 `createdAt` 倒序。字段至少包括：`requestId`、调用次数、总 KB、总估算 Token、真实 input/output Token、是否压缩、状态。

### 7.3 单次请求调用列表

```http
GET /api/llm-observations/{conversationId}/{requestId}
```

返回该请求的所有 `callId` 汇总，不返回全文。

### 7.4 单次模型调用详情

```http
GET /api/llm-observations/{conversationId}/{requestId}/{callId}
```

返回汇总、区块列表和在权限/开关允许时的脱敏全文。支持：

- `?includeContent=false`：只返回区块大小；
- `?includeContent=true`：按权限返回全文；
- `?blockType=system`：只取指定区块；
- `?download=true`：下载 JSON 快照，仍执行权限检查和脱敏。

### 7.5 删除接口

```http
DELETE /api/llm-observations/{conversationId}/{requestId}
```

仅管理员可用，用于测试数据清理；普通用户不能删除其他人的观测。

## 8. `frontend-pm` 页面方案

### 8.1 入口

在 `frontend-pm/src/pages/ChatPage.tsx` 增加“本轮请求观测”入口，默认不占据聊天主区域：

- 右侧 `ActivityFeed` 顶部增加“上下文”按钮和本轮总 KB/Token 摘要；
- 点击后打开右侧抽屉或底部面板；
- 不改变聊天气泡、不把完整请求插入对话内容；
- 页面刷新后通过 `conversationId` 调用 REST 恢复列表。

### 8.2 请求列表视图

每个 `requestId` 一行显示：

| 列 | 内容 |
|---|---|
| 时间 | 请求开始时间 |
| Agent | 主 Agent/子 Agent |
| 调用数 | 本轮 LLM 调用数量 |
| 最终大小 | KB，保留两位 |
| 估算 Token | 调用前估算 |
| 实际 Token | provider usage，缺失显示 `-` |
| 压缩 | `未触发` / `已触发` |
| 状态 | `captured` / `completed` / `failed` |

### 8.3 调用详情视图

点击请求后显示 call 列表，再点击调用显示：

- 顶部汇总卡：总 KB、估算 Token、真实 Token、message 数、tool 数；
- 区块表格：类型、来源、顺序、KB、估算 Token；
- 条形图：各区块占总大小的比例，颜色固定区分 `system`、`history`、`tool_result`、`tool_schema`、`user`；
- 内容查看器：按区块折叠，显示脱敏后的全文；
- “复制区块”与“下载快照”按钮；
- 超过前端单区块显示上限时，先展示大小和摘要，点击后分页读取全文。

### 8.4 观测页面组件

建议新增：

- `frontend-pm/src/api/llmObservation.ts`：SSE 事件类型和 REST 请求；
- `frontend-pm/src/types/llmObservation.ts`：请求、调用、区块、大小类型；
- `frontend-pm/src/components/ContextObservationBadge.tsx`：右侧活动栏摘要；
- `frontend-pm/src/components/LlmObservationDrawer.tsx`：请求/调用列表和详情；
- `frontend-pm/src/components/ContextBlockTable.tsx`：区块大小表；
- `frontend-pm/src/components/ContextContentViewer.tsx`：脱敏全文查看器。

沿用现有 React、TypeScript、CSS-in-JS 和 `ActivityFeed` 风格，不新增图表依赖；区块占比可以先使用 CSS 横条，避免为观测功能引入新的 npm 依赖。

## 9. 与现有上下文压缩的关系

采集和压缩的顺序必须固定：

```text
原始输入
  -> 动态 system prompt 裁剪
  -> 工具结果裁剪 / Artifact handoff
  -> 50K 保护与对话压缩
  -> LlmRequestObserver 记录最终 payload
  -> LLM
```

观测页面同时提供“压缩前摘要”和“提交前摘要”，但默认不保存压缩前全文。这样既能解释为什么大小下降，又不会因为调试快照把被裁剪的历史案例、完整 Skill 再长期保存。

系统 prompt 的区块来源应由组装方显式标注，例如：

- `base_system_prompt`；
- `tool_instructions`；
- `selected_skill_summary`；
- `dimension_context`；
- `memory_context`；
- `knowledge_context`；
- `conversation_history`。

如果现有 AgentScope API 无法直接提供来源标签，第一版允许标记为 `system_unknown`，但不能凭字符串猜测来源；第二版再在各注入 middleware 处增加区块元数据。

## 10. 实施阶段

### 阶段 1：后端采集与测试

1. 新增 `LlmObservationProperties`，默认关闭全文、限制最大保存字节和 TTL。
2. 新增 `LlmRequestObservation`、`LlmObservationBlock`、`LlmObservationSummary` DTO。
3. 新增 `LlmRequestObserver`，在最终模型输入边界生成 request/call ID、区块统计和脱敏快照。
4. 扩展 `TraceSession` 或建立独立关联服务，接收 `ModelCallEndEvent` 的真实 usage。
5. 新增 ClickHouse DDL 和写入队列，写入失败只记录 WARN，不影响 LLM 请求。
6. 新增单元测试：UTF-8 KB、区块合计、协议开销、脱敏、超大截断、无 provider usage、压缩前后标记。

### 阶段 2：REST 与 SSE

1. 增加 `llm_request_observed` 轻量 SSE 事件。
2. 新增会话、请求、调用详情接口。
3. 加入用户归属和管理员权限校验。
4. 用测试数据验证全文关闭时 REST 永远不返回 content；开启时只返回脱敏内容。

### 阶段 3：`frontend-pm` 展示

1. 扩展 `ProcessEvent`/SSE 解析，接收观测摘要。
2. 在 `ActivityFeed` 增加本轮上下文摘要入口。
3. 实现抽屉、调用列表、区块表和全文折叠查看器。
4. 增加 loading、无数据、权限拒绝、全文未开启、内容被截断、调用失败状态。
5. 前端构建验证：`npm run build`，不新增依赖。

### 阶段 4：v1 兼容与上线

1. 确认 `/ai/chat` 是否能取得同样的最终模型输入边界。
2. 能复用则接入同一个 observer，并设置 `source=v1_chat`；不能复用则只保留 v2，不能修改 v1 输出行为。
3. 测试环境打开 `capture-content`，生产仅打开 metadata。
4. 观察 7 天存储量、写入延迟和队列丢弃数后再决定是否延长 TTL。

## 11. 测试验收标准

### 后端

- 同一用户请求包含 3 次模型调用时返回 3 个独立 `callId`；
- `totalBytes` 使用 UTF-8 字节数，中文内容计算正确；
- 区块顺序与最终 messages 顺序一致；
- `totalBytes = blockBytes + protocolOverhead`，误差仅来自明确记录的序列化字段；
- 50K 压缩触发时，快照内容是压缩后的最终输入；
- provider 返回 usage 时展示真实值，未返回时展示 `-` 而不是 0 Token 误导用户；
- API Key、密码、Cookie、token 字段均被替换；
- 未开启全文时，即使请求 `includeContent=true` 也不返回全文；
- 非所属用户读取返回 403/404，不能通过更换参数越权；
- 观测写库失败不会导致聊天接口失败。

### 前端

- 右侧活动栏能看到本轮总 KB 和估算 Token；
- 能展开请求、调用和区块三级结构；
- ECharts/HTML/Markdown 等工具结果只作为实际 message/tool_result 区块展示，不改变聊天回答；
- 大区块不会撑爆页面，支持折叠和截断提示；
- SSE 断开后能通过 REST 查询已完成的观测；
- 无观测权限、无全文、无数据和加载失败均有明确状态；
- `frontend-pm` 构建无新增依赖错误。

## 12. 风险与取舍

| 风险 | 处理方式 |
|---|---|
| 完整 prompt 含敏感数据 | 默认 metadata-only，统一递归脱敏，短 TTL |
| ClickHouse 写入放大 | 汇总与全文分表，异步队列，全文按需开启 |
| 框架无法暴露最终 payload | 优先在 model adapter 边界采集；无法实现时明确标记为估算，不冒充最终值 |
| 区块来源无法自动识别 | 由 middleware 显式传来源；未标注时使用 `unknown`，不做字符串猜测 |
| 观测影响聊天延迟 | 统计同步、写库异步；快照序列化超过上限立即截断 |
| v1/v2 行为不一致 | 先只实现 v2；v1 复用必须经过独立回归，不改变 v1 输出 |
| 前端依赖增加 | 第一版只用已有 React/CSS，不新增 npm 包 |

## 13. 推荐默认配置

测试环境：

```properties
harness.a2a.llm-observation.enabled=true
harness.a2a.llm-observation.capture-content=true
harness.a2a.llm-observation.max-content-bytes=10485760
harness.a2a.llm-observation.ttl-days=7
harness.a2a.llm-observation.allow-admin-full-content=true
```

生产环境：

```properties
harness.a2a.llm-observation.enabled=true
harness.a2a.llm-observation.capture-content=false
harness.a2a.llm-observation.max-content-bytes=0
harness.a2a.llm-observation.ttl-days=7
harness.a2a.llm-observation.allow-admin-full-content=false
```

上线顺序建议：先只开汇总和 KB，再在受控测试用户范围开启全文，确认脱敏和权限后才允许管理员查看完整请求。这样能满足上下文压缩测试，又不会把调试能力变成新的敏感数据出口。
