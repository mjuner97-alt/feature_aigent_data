# 前端流式输出主/子智能体 思考过程 + 最终结果 方案

> 目标：`POST /ai/chat` SSE 流不仅吐出 **主智能体的最终答案文本**，还要把 **主智能体的思考过程（REASONING / TOOL_RESULT / HINT）**、以及任何被 spawn 出来的 **子智能体（subagent）的思考过程与最终结果** 都按时序、按来源、按阶段，分包推到前端。前端据此可以渲染：
>
> - 一条主流程时间线（Supervisor 的思考 → 调子 agent → 子 agent 反馈 → 继续思考 → 最终回答）
> - 每个子 agent 一张折叠卡片（子 agent 的内部 reasoning / tool_result / 最终答复）

---

## 1. 现状 & 痛点

| 项 | 现状 | 问题 |
|---|---|---|
| SSE 事件名 | `sendChunk` 把 `EventType.name().toLowerCase()` 当事件名（reasoning / tool_result / hint） | 前端可以按 type 分流，但无法分辨"是主 agent 还是哪个子 agent 发的" |
| `AiChatResult` 载体 | 只有 `agentId / agentName / lineResult / resultAll` | 没有 **source path / depth / phase / agentRole（main/sub）**，前端只能拿到聚合后的纯文本 |
| `event.getSource()` | 当 subagent spawn 时框架已经填入 `EventSource{path, depth, agentId, agentName, sessionId, parentSessionId}` | `ChatStreamServiceImpl.sendChunk` **完全没读 source**，子 agent 事件被当作主 agent 事件下发，UI 上无法区分 |
| `AGENT_RESULT` 事件 | 主 agent 的 AGENT_RESULT 被 `sendChunk` 显式 `return;` 跳过 | 子 agent 的 AGENT_RESULT 也被一并丢弃；前端拿不到"子 agent 的最终回答"这个语义节点，只能靠累加 reasoning 文本拼出来 |
| `accumulated` 累加 | 单一 `StringBuilder`，所有来源混在一起 | 主 agent 文字和子 agent 文字混叠成一段，`resultAll` 不再"代表主 agent 累计回答" |
| 子 agent 最终结果 | 子 agent 的 `AGENT_RESULT` 在框架内部被消费（作为 tool_result 回填给主 agent） | 即使前端能看到 `TOOL_RESULT` 文本，也分不清是"工具返回"还是"子 agent 收尾的总结" |
| done / error 帧 | 只发主流程的 `done` | 前端无法知道"子 agent A 已经结束、子 agent B 开始了" |

> 一句话：**框架的事件流已经携带了 source 信息，但服务层把这层信息拍扁了。** 改造重点不是底层能力，而是把 `EventSource` 和 `EventType` 透传到 SSE 协议层与前端 DTO。

## 变更文件清单

| # | 类型 | 路径 | 改动摘要 | 行数 | 影响面 |
|---|---|---|---|---|---|
| 1 | 修改 | `entity/AiChatResult.java` | 追加 11 个 nullable 字段：`agentRole / sourcePath / sourceDepth / sourceAgentId / sourceAgentName / sourceSessionId / parentSessionId / phase / isLast / messageId / cumulativeBySource`。旧字段一字未改 | +27 / -1 | 序列化向后兼容，旧前端忽略新字段即可 |
| 2 | 改写 | `service/impl/ChatStreamServiceImpl.java` | 累加器从单个 `StringBuilder` 改为 `Map<sourcePath, StringBuilder>`；新增 `SourceMeta / SourceTracker`；`sendChunk` 拆为 `handleReasoning / handleToolResult / handleMainAgentResult / emitSimple`；新增 SSE 事件名 `reasoning_done / agent_result / subagent_start`；done 帧带 `cumulativeBySource`；`baseBuilder` + `sendFrame` 抽取 | ~530（原 ~400） | 单文件局部重构，回滚 1 个 commit |
| 3 | 新增 | `docs/stream-thinking-subagent-plan.md` | 本文件 | +260 | 文档 |
| — | 不动 | `service/SupervisorService.java` | 框架已经自动为子 agent 注册 `ArtifactHandoffHook` 等，不需要改 | 0 | — |
| — | 不动 | `controller/ChatController.java` / `dto/ChatRequest.java` | HTTP 契约不变 | 0 | — |
| — | 不动 | 任何 subagent markdown（`workspace/harness-a2a/subagents/*.md`） | 子 agent 端零改动 | 0 | — |

> 注：3.5 节提到的"v2 切换抽出 `toSseFrame` 独立方法"在落地时改为 **`baseBuilder + sendFrame` 这一对辅助方法** —— 等价的抽象点，升级 2.0.0 时只换 `SourceMeta` 的推断来源。

### 1.1.0-RC1 现实约束（实施中发现）

读字节码确认：当前 pom 锁定的 `agentscope-core 1.1.0-RC1` **还没有 `EventSource`**（2.0.0 才引入），且子 agent 通过 `AgentSpawnTool` 走同步 `agent.call()`，子 agent 内部 reasoning 流 **不会冒泡** 到主 stream。所以本期实施落地的范围如下：

| 维度 | 1.1.0-RC1 现状 | 2.0.0+ 升级后 |
|---|---|---|
| 主 agent reasoning 流式 | ✅ 已支持，每个 chunk 带 `sourcePath=main` | ✅ 同 |
| `subagent_start` 事件 | ✅ 从 REASONING 帧里的 `ToolUseBlock(agent_spawn\|agent_send)` 反推 | ✅ 改为 `event.getSource()` 首见即发 |
| 子 agent 内部 reasoning（逐 token） | ❌ 框架不冒泡，主 stream 看不到 | ✅ 升级后自动到位，本服务无需改协议 |
| 子 agent 最终回答 | ✅ 从 `TOOL_RESULT(agent_spawn\|agent_send)` 提取，发 `agent_result`（agentRole=sub） | ✅ 改为 `EventType.AGENT_RESULT && source != null` |
| 子 agent 内部工具结果（query/python_exec 等） | ❌ 不冒泡 | ✅ 升级后到位 |

升级 2.0.0 时只需改 `ChatStreamServiceImpl` 中两个推断点（`handleReasoning` 里的 ToolUseBlock 扫描、`handleToolResult` 里的工具名匹配），把它们换成 `event.getSource()` 解析；DTO schema 与 SSE 事件名不变。

---

## 2. 总体设计

### 2.1 概念

```
主 Agent (Supervisor)  ── 一个时间线（main）
   ├── REASONING        ── 思考文本（流式）
   ├── TOOL_RESULT      ── 工具返回（非子 agent）
   ├── HINT             ── RAG/memory/skill 注入
   └── AGENT_RESULT     ── 最终回答（最后一次完整文本）

   并行/嵌套：
   └── 子 Agent (e.g. analyze-data)
         ├── REASONING        ── 子 agent 思考（流式）
         ├── TOOL_RESULT      ── 子 agent 内部工具返回
         └── AGENT_RESULT     ── 子 agent 给主 agent 的总结回答
```

事件归属由 `event.getSource()` 唯一决定：
- `source == null` → 主 agent
- `source != null` → 子 agent；用 `source.getPath()`（如 `main/analyze-data`）作为稳定标识

### 2.2 SSE 事件名规范（不动事件名，靠 payload 字段分流）

| event name | 含义 | 何时发 |
|---|---|---|
| `reasoning` | 思考流式 | `EventType.REASONING && !isLast` |
| `reasoning_done` | 一段思考收尾 | `EventType.REASONING && isLast`（**新增**，原来直接 return 丢失） |
| `tool_result` | 工具结果 | `EventType.TOOL_RESULT`（保留） |
| `hint` | RAG/memory 注入 | `EventType.HINT`（保留） |
| `agent_result` | 子 agent / 主 agent 的最终回答 | `EventType.AGENT_RESULT`（**新增**，且 source 非空时代表子 agent 完成） |
| `subagent_start` | 第一次见到新的 source path | 服务层维护"已见 path 集合"，首见即合成此事件（**新增**） |
| `subagent_end` | 收到 `EventType.AGENT_RESULT && source != null` 时合成 | 顺便复用 `agent_result` 同帧（**新增**） |
| `done` | 主流程整体结束 | onComplete 时发（保留） |
| `error` | 异常 | onError 时发（保留） |

> 兼容策略：旧前端只认 `reasoning / done / error` 也能继续工作——`reasoning_done / agent_result / subagent_*` 是**新增**事件名，旧端会自动忽略。

### 2.3 payload 扩展 —— `AiChatResult` 加字段

在不破坏已有字段的前提下，**追加**：

| 字段 | 类型 | 含义 |
|---|---|---|
| `agentRole` | `"main"` / `"sub"` | 来自 `source == null` 判定 |
| `sourcePath` | String | 主 agent 时为 `"main"`；子 agent 时为 `source.getPath()`（如 `main/analyze-data`） |
| `sourceDepth` | int | `source == null ? 0 : source.getDepth()` |
| `sourceAgentId` | String | 子 agent 的 markdown 文件名（`analyze-data`），主 agent 为空 |
| `sourceAgentName` | String | 子 agent 的显示名；主 agent 为空 |
| `sourceSessionId` | String | 子 agent 的 sessionId（前端用作"子 agent 卡片 key"） |
| `parentSessionId` | String | 子 agent 上溯的父 sessionId |
| `phase` | String | 等同 SSE 事件名（reasoning / agent_result / …）；冗余出来方便前端只解析 data 字段 |
| `isLast` | boolean | 当前是不是这段消息的收尾 chunk |
| `messageId` | String | `event.getMessage().getId()`，前端用作"合并流式片段"的 key |

> 这些字段都标 nullable，旧字段一字未改，旧字段 `agentId/agentName/conversationId` 继续承载主请求的标识（前端选项面板那个 `agentId`，不要和 `sourceAgentId` 混淆）。

### 2.4 累加器从单个改为按 sourcePath 分桶

把 `StringBuilder accumulated` 换成 `Map<String, StringBuilder> accBySource`：
- 主 agent 桶 key = `"main"`
- 子 agent 桶 key = `source.getPath()`
- `resultAll` 在 chunk 里填**当前桶**的累计，前端能分别拿到每个 agent 的累计文本
- `done` 帧里把所有桶的快照也带上，方便前端兜底渲染

---

## 3. 改动清单

### 3.1 `dto/AiChatResult.java` —— 扩字段

在原 `@Builder` 类上新增上文第 2.3 节列出的字段。**不要**改任何已有字段名或类型，保证旧序列化兼容。

### 3.2 `service/impl/ChatStreamServiceImpl.java` —— 核心改动

要点：

1. **读 `event.getSource()`**：抽一个 `static SourceMeta extract(Event)`，返回归一化的 `{role, path, depth, agentId, agentName, sessionId, parentSessionId}`。
2. **替换累加器**：
   ```java
   final Map<String, StringBuilder> accBySource = new ConcurrentHashMap<>();
   final Set<String> seenSources = ConcurrentHashMap.newKeySet();
   ```
3. **`sendChunk` 改写**：
   - `EventType.AGENT_RESULT`：不再无脑 return。若 `source != null`，发 `agent_result` 事件（子 agent 完成）；若 `source == null`，发主 agent 的 `agent_result`（也作为 done 前的最终快照）。
   - `EventType.REASONING && isLast`：发 `reasoning_done`（带 messageId，前端可关闭"打字光标"动画）。
   - `EventType.REASONING && !isLast`：维持现在的 `reasoning` 流式 chunk，但 payload 里写入 source 字段。
   - `TOOL_RESULT / HINT`：保留，加 source 字段。
   - 首次见到 `sourcePath`：合成 `subagent_start` 帧（不携带 text，只携带 source 元信息）。
4. **`sendDone` 改写**：除了原 `done` 帧，额外把 `accBySource` 的所有 (path → cumulative) 作为 map 字段塞进 payload；前端可直接用 `done.cumulativeBySource["main/analyze-data"]` 渲染历史。
5. **`handleStreamError`**：cache HIT 路径保持现状（视为主 agent reasoning + done）；其它错误把 `sourcePath` 设为 `main`。
6. **日志**：当前 `log.info("stream event: ...")` 增加 `source=` 字段，定位子 agent 流问题。

### 3.3 子 agent 端 —— 不需要改

`ArtifactHandoffHook` / `ArtifactAccessHook` 已经按 subagent 注册到 `parent.subagentFactory(...)`，框架在 spawn 时会自动给每条事件打 `EventSource`。**只要服务层透传 source，子 agent 流就免费拿到了。**

但有一个注意点：`SupervisorService.registerSubagentFromSpec` 里给子 agent 加了 `.disableMemoryHooks()`。这只影响 memory hook，不影响 event stream —— 子 agent 的 `stream()` 依然会把 reasoning/tool_result/agent_result 冒泡上来，所以这块**不动**。

### 3.4 文档 & 联调脚本

- 在 `docs/README.md` 里加一行索引指向本文件。
- 在 `docs/` 下再补一段 **前端契约示例**（JSON 样例 + 事件序列），便于前端同学对接（见 §5）。

### 3.5 (可选 / 二期) 切到 v2 事件流

源码注释里 `Event/EventType` 都标了 `@Deprecated(since = "2.0.0")`，新 API 是 `ReActAgent#streamEvents(...)` 返回的 `AgentEvent`（28 种细粒度事件，含 HITL 流程）。本期**不切**，原因：

- 现有 `HarnessAgent.stream()` 仍走 v1 Event 流，是 harness/A2A/AGUI 全家共用的事实标准；
- v1 已经能携带 source，足够满足"主+子 思考过程流式"的需求；
- v2 切换是跨模块的兼容性工作，应单独立项。

留一个 hook：在 §3.2 的 `sendChunk` 改写时，把"事件 → 协议帧"的映射抽成一个独立 method（如 `toSseFrame(Event)`），将来 v2 切换时只需要替换这个方法。

---

## 4. 实施步骤（建议 2 个 PR）

### PR-A：协议透传（必做）

1. 扩 `AiChatResult` 字段（§3.1）。
2. 改 `ChatStreamServiceImpl`（§3.2），保证旧前端不报错（只增不改 SSE 事件名 + DTO 字段）。
3. 联调：写一个 `curl -N` 脚本 + 一个最小 HTML demo，能看到主 agent + 子 agent 两条线分别推进。
4. 单测：mock 一个发出"主 reasoning → 子 agent reasoning → 子 agent agent_result → 主 reasoning → 主 agent_result"序列的 `Flux<Event>`，断言 SSE 输出帧顺序 + payload 字段。

### PR-B：前端渲染（前端侧）

1. 按 `sourcePath` 维护一棵树（main 根 + 每个 subagent 一颗子节点）。
2. 主时间线上把 `subagent_start` 渲染为可折叠卡片占位，卡片内部用 `sourcePath` 匹配的 chunk 填充。
3. `agent_result` 是收尾节点，关闭对应卡片的"loading"。
4. 异常态：`error` 帧覆盖整个会话状态，`done` 仍然清理 loading。

---

## 5. 前端契约示例

请求：
```
POST /ai/chat
Content-Type: application/json
{ "agentId": "7", "input": "分析 Q2 缺陷率 TOP5 部门" }
```

响应（SSE，节选）：

```
event: reasoning
id: {ansUUID}
data: {"phase":"reasoning","agentRole":"main","sourcePath":"main","sourceDepth":0,
       "lineResult":"我需要先查...","resultAll":"我需要先查...",
       "agentId":"7","ansUUID":"...","messageId":"m-1","isLast":false}

event: subagent_start
data: {"phase":"subagent_start","agentRole":"sub","sourcePath":"main/analyze-data",
       "sourceAgentId":"analyze-data","sourceAgentName":"数据分析子助手",
       "sourceSessionId":"sub-uuid-1","sourceDepth":1}

event: reasoning
data: {"phase":"reasoning","agentRole":"sub","sourcePath":"main/analyze-data",
       "lineResult":"先 query_quality_data...","resultAll":"先 query_quality_data...",
       "messageId":"m-2","isLast":false}

event: tool_result
data: {"phase":"tool_result","agentRole":"sub","sourcePath":"main/analyze-data",
       "lineResult":"{\"rows\":[...]}","messageId":"m-3","isLast":true}

event: agent_result
data: {"phase":"agent_result","agentRole":"sub","sourcePath":"main/analyze-data",
       "lineResult":"Q2 TOP5: ...","resultAll":"Q2 TOP5: ...","isLast":true}

event: reasoning
data: {"phase":"reasoning","agentRole":"main","sourcePath":"main",
       "lineResult":"结合子 agent 数据,结论是...","isLast":false}

event: agent_result
data: {"phase":"agent_result","agentRole":"main","sourcePath":"main",
       "lineResult":"...","resultAll":"...","isLast":true}

event: done
data: {"phase":"done","cumulativeBySource":{
         "main":"...",
         "main/analyze-data":"Q2 TOP5: ..."
       }}
```

前端伪代码：

```js
const tree = { main: { text: "", subagents: {} } };

es.addEventListener("reasoning", e => {
  const d = JSON.parse(e.data);
  const node = d.agentRole === "main"
      ? tree.main
      : (tree.main.subagents[d.sourcePath] ??= { text: "", done: false });
  node.text = d.resultAll;
  render(tree);
});

es.addEventListener("agent_result", e => {
  const d = JSON.parse(e.data);
  if (d.agentRole === "sub") tree.main.subagents[d.sourcePath].done = true;
  render(tree);
});

es.addEventListener("done", () => closeStream());
```

---

## 6. 风险 & 回退

| 风险 | 影响 | 缓解 |
|---|---|---|
| `EventSource.path` 在框架内为 null 的边界 case | 子 agent 帧被误归到 main | extract() 兜底：source 非空但 path 空时退化为 `"main/" + agentId`；再不行 `"main/unknown"` |
| 子 agent 大量 chunk 把网络打爆 | SSE 阻塞 | 短期不做节流；如需再加，可在 sendChunk 里对 same messageId 做 N ms 合并（不在本期范围） |
| 前端旧客户端没升级 | 看到未识别事件名 | 旧端只 `addEventListener("message" / "reasoning" / "done")`，新事件被 EventSource 默认丢弃，**无破坏** |
| `AGENT_RESULT` 主 agent 帧打开后跟 `done` 帧文本重复 | resultAll 渲染两次 | sendDone 不再重复 lineResult（只发空 lineResult + cumulativeBySource），由 agent_result 帧承担"最终回答"语义 |
| v2 `AgentEvent` 迁移 | 未来需重构 | §3.5 的 `toSseFrame` 抽象点已经留好 |

---

## 7. 验收清单

- [ ] `curl -N -X POST /ai/chat -d '{"input":"..."}'` 能看到 main + 至少一个 subagent path 的 reasoning chunk 交替流出
- [ ] 子 agent 任意工具调用都能看到 `tool_result` 帧带正确 `sourcePath`
- [ ] 子 agent 收尾时收到一帧 `agent_result` (agentRole=sub)
- [ ] 主 agent 结束时先 `agent_result`(agentRole=main) 再 `done`
- [ ] `done.cumulativeBySource` 包含 main + 全部出现过的子 agent path
- [ ] 旧前端仅订阅 `reasoning/done/error` 仍工作正常
- [ ] 缓存 HIT 路径只发 main 的 reasoning + done（无子 agent 帧）
- [ ] 单测：覆盖"事件 → SSE 帧"映射的 5 个主分支（REASONING 流式 / REASONING isLast / TOOL_RESULT / HINT / AGENT_RESULT main vs sub）
