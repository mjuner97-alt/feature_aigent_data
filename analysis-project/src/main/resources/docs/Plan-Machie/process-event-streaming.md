# 过程事件流式透传：让前端"看见"智能体在做什么

> 路径：`src/main/resources/docs/Plan-Machie/process-event-streaming.md`
> 关联：`memory-middleware-middle-ground.md`（延迟优化）；`plan-notebook-frontend-design.md`（前端架构）
> 日期：2026-07-21

## 问题

用户反馈："前端很多关键信息步骤前端都没有流式展示，让用户感觉卡住了一样，实际后端是一直有输出的"。

具体现象（用户实测"分析2026年Q1各部门质量分趋势"）：
- 用户提交 → 屏幕黑盒 30s（无任何输出）
- 突然一次性涌出 LLM 写的整段文字（"好的，我已经加载了参考技能..."）
- 最终 markdown 报告流式打印

中间 30s 发生了什么（参考 `quarterly_department_quality_query/SKILL.md` §调用顺序图）：
```
用户 -> Supervisor 意图识别 + 参数提取
       → 加载 skill (skill_router 工具调用)
       → agent_spawn 派单 query_data 子智能体
              → 加载 tool_index 技能
              → 直接调用 quality_query_by_department_quarter 工具
              → 返回数据
       → Supervisor 汇总生成报告
```

每一步框架都发了事件，但前端一个都没收到。

## 根因

`V2ChatStreamServiceImpl.handleEvent` (line 338-378) 只处理两种 `AgentEvent`：

```java
if (event instanceof AgentResultEvent) { ... return; }   // 只取 final text
if (event instanceof TextBlockDeltaEvent delta) {        // 只取 LLM token
    chunk = delta.getDelta();
}
if (chunk == null || chunk.isEmpty()) return;            // 其他 26 种事件全丢
```

框架 `AgentEventType` 枚举共 30 个类型（extracted from agentscope-core-2.0.0-RC5-sources.jar）：
```
AGENT_START, AGENT_END, AGENT_RESULT,
MODEL_CALL_START, MODEL_CALL_END,
TEXT_BLOCK_START/DELTA/END,
THINKING_BLOCK_START/DELTA/END,
DATA_BLOCK_START/DELTA/END,
TOOL_CALL_START/DELTA/END,
TOOL_RESULT_START/END,
TOOL_RESULT_TEXT_DELTA, TOOL_RESULT_DATA_DELTA,
EXCEED_MAX_ITERS, REQUIRE_USER_CONFIRM, ...,
SUBAGENT_EXPOSED, HINT_BLOCK, CUSTOM
```

**只有 `TEXT_BLOCK_DELTA` 被转发，其余 29 种全部丢弃**。所以用户只看到 LLM 生成的文字流，看不到工具调用、子智能体派单、工具结果返回。

## 设计

### 事件筛选：转发 7 类过程事件

按 SKILL.md 调用顺序图对应：

| 事件 | 字段 | 展示文案 | 频率 |
|---|---|---|---|
| `AGENT_START` | name, role, sessionId | "🤖 启动智能体：{name} ({role})" | 1 次/agent |
| `TOOL_CALL_START` | toolCallId, toolCallName | "🔧 调用工具：{toolCallName}" | 每次 tool call |
| `TOOL_CALL_DELTA` | delta (JSON 参数片段) | (折叠为参数预览) | 高频,聚合 |
| `TOOL_RESULT_START` | toolCallName | "📋 工具返回：{toolCallName}" | 每次 tool result |
| `TOOL_RESULT_END` | toolCallName, state | "✅ 完成：{toolCallName} ({state})" | 每次 tool result |
| `SUBAGENT_EXPOSED` | subagentId, label | "👥 派单子智能体：{label}" | 每次 agent_spawn |
| `AGENT_END` | replyId | "✅ 智能体完成" | 1 次/agent |

**不转发的事件**（理由）：
- `TEXT_BLOCK_START/END`：无 payload,纯 marker,`TEXT_BLOCK_DELTA` 已覆盖
- `THINKING_BLOCK_*`：暴露 LLM 内部思考链,中文场景下信息密度低且容易吓到用户
- `DATA_BLOCK_*`：结构化数据块,前端展示成本高,先跳过
- `MODEL_CALL_START/END`：每次 LLM 调用都发,太频繁,与 `TEXT_BLOCK_DELTA` 重复
- `TOOL_RESULT_TEXT_DELTA/DATA_DELTA`：tool 结果流式增量,数据量大,先展示 Start/End 即可
- `HINT_BLOCK`：AsyncToolMiddleware 占位符,已有专门 UI(后续)
- `EXCEED_MAX_ITERS`：错误场景,`error` SSE 已覆盖

### DTO 扩展：`AiChatResult` 新增字段

```java
// AiChatResult.java
private String eventType;      // 事件类型 (event name lowercased): "tool_call_start" 等
private String toolCallId;     // 工具调用 ID (tool_call_* / tool_result_* 共用)
private String toolCallName;   // 工具名 (python_exec / agent_spawn / skill_router ...)
private String toolCallState;  // ToolResultState.name() (仅 tool_result_end)
private String subagentId;     // 子智能体 ID (仅 subagent_exposed)
private String subagentLabel;  // 子智能体标签 (仅 subagent_exposed)
private String agentNameRaw;   // AgentStartEvent.name (仅 agent_start)
private String agentRole;      // AgentStartEvent.role (仅 agent_start)
```

### 后端改动：`V2ChatStreamServiceImpl.handleEvent`

```java
private void handleEvent(AgentEvent event, SseEmitter emitter, ...) throws Exception {
    String eventName = event.getType() != null ? event.getType().name().toLowerCase() : "custom";
    String source = event.getSource();   // null=主 agent; "analyze_data"=子智能体
    
    // TextBlockDelta: 走原路径,累积 text
    if (event instanceof TextBlockDeltaEvent delta) {
        String chunk = delta.getDelta();
        if (chunk == null || chunk.isEmpty()) return;
        accumulated.append(chunk);
        sendEvent(eventName, emitter, builder().lineResult(chunk).resultAll(accumulated.toString()).source(source).build());
        return;
    }
    
    // AgentResultEvent: 终止事件,doOnComplete 已发 done,这里不转发
    if (event instanceof AgentResultEvent) {
        String text = extractText(((AgentResultEvent) event).getResult());
        if (text != null && !text.isEmpty()) accumulated.append(text);
        return;
    }
    
    // 过程事件:按类型构造 payload,不累积到 accumulated
    AiChatResult payload = switch (eventName) {
        case "agent_start" -> {
            AgentStartEvent e = (AgentStartEvent) event;
            yield builder().eventType(eventName).source(source)
                    .agentNameRaw(e.getName()).agentRole(e.getRole())
                    .lineResult("🤖 启动智能体：" + e.getName() + " (" + e.getRole() + ")")
                    .build();
        }
        case "tool_call_start" -> {
            ToolCallStartEvent e = (ToolCallStartEvent) event;
            yield builder().eventType(eventName).source(source)
                    .toolCallId(e.getToolCallId()).toolCallName(e.getToolCallName())
                    .lineResult("🔧 调用工具：" + e.getToolCallName())
                    .build();
        }
        case "tool_call_end" -> {  // 可选:与 start 重复,跳过
            yield null;
        }
        case "tool_result_start" -> {
            ToolResultStartEvent e = (ToolResultStartEvent) event;
            yield builder().eventType(eventName).source(source)
                    .toolCallId(e.getToolCallId()).toolCallName(e.getToolCallName())
                    .lineResult("📋 工具返回：" + e.getToolCallName())
                    .build();
        }
        case "tool_result_end" -> {
            ToolResultEndEvent e = (ToolResultEndEvent) event;
            yield builder().eventType(eventName).source(source)
                    .toolCallId(e.getToolCallId()).toolCallName(e.getToolCallName())
                    .toolCallState(e.getState() != null ? e.getState().name() : null)
                    .lineResult("✅ 完成：" + e.getToolCallName() + " (" + (e.getState() != null ? e.getState().name() : "?") + ")")
                    .build();
        }
        case "subagent_exposed" -> {
            SubagentExposedEvent e = (SubagentExposedEvent) event;
            yield builder().eventType(eventName).source(source)
                    .subagentId(e.getSubagentId())
                    .subagentLabel(e.getLabel())
                    .lineResult("👥 派单子智能体：" + (e.getLabel() != null ? e.getLabel() : e.getSubagentId()))
                    .build();
        }
        case "agent_end" -> {
            yield builder().eventType(eventName).source(source)
                    .lineResult("✅ 智能体完成")
                    .build();
        }
        default -> null;  // 其他事件 (thinking/model_call/text_block_start等) 不转发
    };
    
    if (payload == null) return;
    String json = objectMapper.writeValueAsString(payload);
    emitter.send(SseEmitter.event().name(eventName).data(json));
}
```

### 前端改动

#### 1. `api/chat.ts`：扩展 ChatEvent 类型

```typescript
export type ChatEvent =
  | { type: 'token'; chunk: string; fullText: string }       // text_block_delta (LLM 文字)
  | { type: 'process'; eventType: string; message: string; source: string | null;
      toolCallName?: string; subagentLabel?: string }        // 过程事件
  | { type: 'done'; fullText: string; conversationId: string }
  | { type: 'error'; error: string };
```

`streamChat` 增加事件分发：
```typescript
if (eventName === 'text_block_delta') {
  yield { type: 'token', chunk, fullText };
} else if (eventName === 'done') {
  yield { type: 'done', ... };
} else if (PROCESS_EVENTS.has(eventName)) {
  yield {
    type: 'process',
    eventType: eventName,
    message: json.lineResult ?? '',
    source: json.source ?? null,
    toolCallName: json.toolCallName,
    subagentLabel: json.subagentLabel,
  };
}
// 其他事件仍忽略

const PROCESS_EVENTS = new Set([
  'agent_start', 'tool_call_start', 'tool_result_start',
  'tool_result_end', 'subagent_exposed', 'agent_end',
]);
```

#### 2. 新组件 `ActivityFeed.tsx`

展示过程事件流,与 markdown 报告并排:
```
┌─ 智能体活动 ─────────────────────┐
│ 09:32:01 🤖 启动智能体：QualitySupervisorV2 │
│ 09:32:03 🔧 调用工具：skill_router            │
│ 09:32:05 📋 工具返回：skill_router              │
│ 09:32:05 ✅ 完成：skill_router (OK)            │
│ 09:32:06 👥 派单子智能体：analyze_data           │
│ 09:32:08 🔧 调用工具：quality_query_by_dept_q.. │
│ 09:32:12 📋 工具返回：quality_query_by_dept_q.. │
│ 09:32:12 ✅ 完成：quality_query_by_dept_q (OK)│
│ 09:32:18 ✅ 智能体完成                          │
└─────────────────────────────────┘
```

样式:
- 每条带时间戳 + emoji + 文案
- 子智能体事件 (source != null) 缩进 + 左边框高亮
- 失败 tool_result (state != OK) 红色
- 自动滚动到底部,新事件高亮 0.5s

#### 3. `pages/ChatPage.tsx` 布局调整

当前: 左侧 ChatPanel 占满。改为:
```
┌─ 左: ChatPanel (markdown 报告) ──┬─ 右: ActivityFeed (过程) ─┐
│  [流式 markdown 报告]              │  [实时事件列表]             │
│                                   │                            │
└───────────────────────────────────┴────────────────────────────┘
```

ChatPanel 维持现状(显示最终 LLM 报告),右侧新增 ActivityFeed。过程事件走 process 类型,不进 chat 文本,只进 activity。

### 不做的事件类型

| 事件 | 不做理由 |
|---|---|
| `THINKING_BLOCK_*` | 中文场景下 thinking 内容重复且有"吓人"风险(英文硬编码 + 内部推理) |
| `DATA_BLOCK_*` | 结构化数据,展示成本高,后续单独立项 |
| `MODEL_CALL_START/END` | 与 `TEXT_BLOCK_DELTA` 重复,且每次 LLM 调用都发,噪音大 |
| `TOOL_CALL_DELTA` | 参数 JSON 流式增量,信息密度低,且参数中可能含敏感数据(api-key 等);先只展示 start |
| `TOOL_RESULT_TEXT_DELTA` | 结果流式增量,数据量大;先只展示 start/end |
| `TOOL_CALL_END` | 与 start 重复,且无 state 信息(state 在 result_end);跳过 |
| `HINT_BLOCK` | AsyncToolMiddleware 占位符,已有专门的 HintBlock UI 路径(后续) |

## 验证

### 1. 编译 + 启动
```bash
mvn clean package -Dmaven.test.skip=true 2>&1 | tail -5
# 预期: BUILD SUCCESS

DOCKER_API_VERSION=1.46 DOCKER_HOST=ssh://root@116.148.120.160 \
  nohup G:/jdk21/bin/java.exe -jar target/analysis-project-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=sandbox-windows,dev > /tmp/app-process.log 2>&1 &
sleep 25
```

### 2. E2E 复现 + 校验

```bash
curl -sN -X POST http://localhost:8081/v2/ai/chat \
  -H 'Content-Type: application/json' \
  --data-binary '{"input":"分析2026年Q1各部门质量分趋势,生成详细报告","conversationId":"process-001","user_id":"process_tester"}' \
  > /tmp/process-chat.txt 2>&1

# 校验 SSE 流里出现 7 种过程事件
for evt in agent_start tool_call_start tool_result_start tool_result_end subagent_exposed agent_end; do
  cnt=$(grep -c "^event:$evt$" /tmp/process-chat.txt)
  echo "$evt: $cnt"
done
# 预期: agent_start≥1, tool_call_start≥1, subagent_exposed≥1, agent_end≥1

# 校验 text_block_delta 仍正常
grep -c "^event:text_block_delta$" /tmp/process-chat.txt
# 预期: ≥1

# 校验 done 仍到达
grep -c "^event:done$" /tmp/process-chat.txt
# 预期: 1
```

### 3. 前端实测

浏览器打开 http://localhost:5173/chat,提交同一问题:
- 左侧:markdown 报告流式输出(与现状一致)
- 右侧 ActivityFeed:实时滚动展示 7 种事件
- 用户感知:从黑盒 30s → 每 2-5s 一次活动更新

## 关联记忆

- [[interrupt_resume_endpoint_verified]] - interrupt 端点已验证;过程事件流不影响 interrupt 流程(interrupt 走独立 endpoint)
- [[async_tool_middleware_wiring]] - HintBlock 是 AsyncToolMiddleware 占位符;本方案不处理 HintBlock,后续单独 UI
- [[subagent_hook_chain_migration]] - 子 agent 的事件 source 字段标 "analyze_data"/"query_data",前端用 source 区分主/子
- [[plan_state_cross_restart_verified]] - PlanMode/Task 状态走 /v2/ai/session/state 轮询,与 SSE 事件流并行