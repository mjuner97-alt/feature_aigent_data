# 交互式 pause/resume 方案：让用户能中途打断 agent 并补充信息

> **状态：已被 Plan B（`interrupt-resume-single-endpoint-plan.md`）替代 - 2026-07-19**
>
> 本文档作为设计演进记录保留。Plan B 重新设计为单端点 `POST /v2/ai/chat/interrupt`
> （interrupt + supplement + auto-resume 合一），用户体验从"两步操作"优化为"一步操作"。
> 详见 `interrupt-resume-single-endpoint-plan.md`。
>
> 原始方案状态：**方案待评审**（未实现）
> 提出日期：2026-07-18
> 关联缺口：`framework-highlights-verification.md` §4.1 "无 `/pause` `/resume` REST 端点"

---

## 一、用户诉求与缺口定位

### 用户原始描述

> "agent 执行任务中，分析思路不对或者怎么样时，用户暂停分析，并补充信息，然后 agent 基于用户补充的信息继续分析，之前执行不丢，可交互的。"

### 是否是状态机？

**不是传统意义上的状态机。** framework-highlights-verification.md §4.1 写的"无 `/pause` `/resume` REST 端点"这个缺口，**底层机制框架已经内置**，缺的只是 REST 入口。换言之：框架已经支持"暂停 + 保状态 + 续跑"，只是没有 HTTP 端点把它暴露出来。

### 框架内置机制的证据

| 组件 | 位置 | 作用 |
|---|---|---|
| `InterruptControl` | `io.agentscope.core.interruption.InterruptControl` | per-`(userId, sessionId)` 的中断信号，持有一个可选的 `Msg userMessage`。API：`trigger(src, msg)` / `isInterrupted()` / `reset()` / `getUserMessage()` |
| `ReActAgent.interrupt(userId, sessionId, msg)` | `ReActAgent.java:749-751` | 公共 API，外部调用即触发指定 session 的中断 |
| `CallExecution.checkInterrupted()` | `ReActAgent.java:1480-1492` | 每次迭代前检查 `state.interruptControl().isInterrupted()`，命中即抛 `InterruptedException("Agent execution interrupted")`，中止 in-flight 调用 |
| `ReActAgent.handleInterrupt()` | `ReActAgent.java:3440-3466` | 捕获 `InterruptedException` 后的恢复逻辑：把 `"I noticed that you have interrupted me. What can I do for you?"` append 到 `scope.state.contextMutable()`，调 `saveStateToSession(scope)` 持久化，返回 recovery msg 作为 `AgentResultEvent` |
| `ReActAgent.scopeFrom()` | `ReActAgent.java:537` | **下一次** `/chat` 调用开始时 `scope.state.interruptControl().reset()` 清除中断标志，新调用从 saved state 继续执行 |

### 用户场景与框架设计的对应关系

| 用户的设想 | 框架对应 |
|---|---|
| 用户暂停分析 | `agent.interrupt(userId, sessionId)` -> in-flight SSE 流在下次 checkpoint 抛 `InterruptedException` |
| 之前执行不丢 | `handleInterrupt()` 把 recovery msg append 到 `contextMutable()` 并 `saveStateToSession`，所有 messages / tool results / plan / activatedGroups 持久化 |
| 用户补充信息 | 用户在 SSE `done` 后发新的 `POST /v2/ai/chat`，`input` 字段即补充信息 |
| agent 基于补充信息继续分析 | 新 `/chat` 调用 `scope.state.interruptControl().reset()` 后从 saved state 起步，新 user msg + 历史 context 一起送入 LLM |

---

## 二、推荐方案

### 端点设计

| 端点 | 行为 | 实现 |
|---|---|---|
| `POST /debug/session/pause?userId=X&sessionId=Y` | 中断指定 session 的 in-flight 调用；可选 body `{message: "..."}` 携带补充信息存入 `InterruptControl.userMessage`（仅做记录，不自动注入 context） | `runner.getAgent().getDelegate().interrupt(userId, sessionId, msg)` |
| `GET /debug/session/resume?userId=X&sessionId=Y` | 查询 saved state 状态：返回 last assistant msg / context size / interrupt flag。**不主动触发新调用** - 真正"继续"由用户发下一个 `/v2/ai/chat` 完成 | 读 `agent.getDelegate().getAgentState(userId, sessionId)` 的 `contextMutable()` 末尾 + `interruptControl().isInterrupted()` |

### 为什么不做"resume 触发"端点？

框架的设计就是"interrupt 后由用户下一句话触发继续" - `/v2/ai/chat` 已经是 resume 入口。多做一个 resume 触发端点会引入"无 user msg 的空调用"，对 ReActAgent 来说不自然（`doCallInner` 至少需要一个 user msg 驱动）。

### 调用时序

```
Client                    Server /v2/ai/chat (in-flight)         agent.interrupt()
  │  POST /v2/ai/chat ─────► │                                       │
  │  SSE: text_delta... ◄─── │  LLM call #1 ─► tool_call ──► ...    │
  │  POST /debug/session/pause ─────────────────────────────────►  │ trigger()
  │  ◄── 200 {status:ok} ──────────────────────────────────────── │
  │  SSE: text_delta "I noticed..." ◄── handleInterrupt recovery   │
  │  SSE: done ◄──────────  saveStateToSession() + reset on next  │
  │                                                                │
  │  (用户读 recovery msg，编辑补充信息)                            │
  │                                                                │
  │  POST /v2/ai/chat (input="补充信息...") ─────►  scope.state.interruptControl().reset()
  │  SSE: text_delta (基于历史 + 补充信息继续) ◄──  LLM call N     │
  │  SSE: done ◄──────────                                         │
```

---

## 三、需要改动的文件

| 文件 | 操作 | 改动 |
|---|---|---|
| `src/main/java/com/agentscopea2a/controller/DebugController.java` | 修改 | 加 `POST /debug/session/pause` + `GET /debug/session/resume` 两个端点；复用已有的 `runnerProvider` |
| `src/main/resources/docs/rc2-to-rc5/framework-highlights-verification.md` | 修改（可选） | §4.1 把"无 /pause /resume 端点"这条缺口标记为 ✅ 已补 |

### 不需要改的文件

- `HarnessA2aRunnerV2.java`：`getAgent()` 已经返回 `HarnessAgent`，`HarnessAgent.getDelegate()` 返回 `ReActAgent`，链路天然可达
- `V2ChatStreamServiceImpl.java`：现有 `/chat` 流程天然就是 resume 入口
- `application.properties`：无需新配置

---

## 四、参考代码片段

> 以下代码未写入仓库，仅供评审时参考具体形态。确认方案后再实施。

```java
// ==================== Session pause/resume (interactive HITL) ====================

/**
 * Interrupts the in-flight agent call for the given (userId, sessionId) session.
 *
 * <p>Framework flow: agent.interrupt() sets InterruptControl.flag -> next iteration's
 * checkInterrupted() throws InterruptedException -> handleInterrupt() appends recovery
 * message "I noticed that you have interrupted me..." to contextMutable, saves state,
 * returns recovery msg as AgentResultEvent -> SSE stream emits done.
 *
 * <p>State (messages, tool results, plan, activatedGroups) is preserved across the
 * interrupt. The next POST /v2/ai/chat with the user's supplement info resumes from
 * the saved state - no separate /resume trigger needed.
 *
 * <p>Optional body {message: "..."} stores a user msg on InterruptControl.userMessage
 * for audit/debugging. It is NOT auto-injected into the conversation - the user's
 * actual supplement must be sent via the next /v2/ai/chat request.
 */
@PostMapping("/session/pause")
public ResponseEntity<Map<String, Object>> pauseSession(
        @RequestParam("userId") String userId,
        @RequestParam("sessionId") String sessionId,
        @RequestBody(required = false) Map<String, String> body) {
    HarnessA2aRunnerV2 runner = runnerProvider.getIfAvailable();
    if (runner == null) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }
    Msg supplementMsg = null;
    if (body != null && body.get("message") != null && !body.get("message").isBlank()) {
        supplementMsg = Msg.builder().role(MsgRole.USER)
                .content(TextBlock.builder().text(body.get("message")).build())
                .build();
    }
    try {
        runner.getAgent().getDelegate().interrupt(userId, sessionId, supplementMsg);
        log.info("Session paused: userId={}, sessionId={}, hasSupplement={}",
                userId, sessionId, supplementMsg != null);
        Map<String, Object> out = new HashMap<>();
        out.put("status", "ok");
        out.put("userId", userId);
        out.put("sessionId", sessionId);
        out.put("message", "In-flight call will abort at next checkpoint. "
                + "Send next /v2/ai/chat with supplement info to resume.");
        return ResponseEntity.ok(out);
    } catch (Exception e) {
        log.warn("pauseSession failed: userId={}, sessionId={}, err={}",
                userId, sessionId, e.getMessage());
        Map<String, Object> err = new HashMap<>();
        err.put("status", "error");
        err.put("message", e.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(err);
    }
}

/**
 * Returns the saved session state for resume preview. Does NOT trigger a new agent
 * call - resume is achieved by sending the next POST /v2/ai/chat.
 */
@GetMapping("/session/resume")
public ResponseEntity<Map<String, Object>> resumePreview(
        @RequestParam("userId") String userId,
        @RequestParam("sessionId") String sessionId) {
    HarnessA2aRunnerV2 runner = runnerProvider.getIfAvailable();
    if (runner == null) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }
    try {
        AgentState state =
                runner.getAgent().getDelegate().getAgentState(userId, sessionId);
        List<Msg> context = state.contextMutable();
        Msg lastAssistant = null;
        for (int i = context.size() - 1; i >= 0; i--) {
            if (context.get(i).getRole() == MsgRole.ASSISTANT) {
                lastAssistant = context.get(i);
                break;
            }
        }
        Map<String, Object> out = new HashMap<>();
        out.put("userId", userId);
        out.put("sessionId", sessionId);
        out.put("interrupted", state.interruptControl().isInterrupted());
        out.put("contextSize", context.size());
        out.put("lastAssistantText", lastAssistant != null ? lastAssistant.getTextContent() : null);
        out.put("resumeHint", "Send POST /v2/ai/chat with supplement info to continue.");
        return ResponseEntity.ok(out);
    } catch (Exception e) {
        log.warn("resumePreview failed: userId={}, sessionId={}, err={}",
                userId, sessionId, e.getMessage());
        Map<String, Object> err = new HashMap<>();
        err.put("status", "error");
        err.put("message", e.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(err);
    }
}
```

需要追加的 import：
```java
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.state.AgentState;
```

---

## 五、验证步骤（实施后执行）

### 1. 编译

```bash
cd D:/AILLMS/javacode/analysis-project/analysis-project
mvn clean package -Dmaven.test.skip=true 2>&1 | tail -10
```
预期：BUILD SUCCESS。

### 2. 启动 + 端点冒烟

```bash
cmd.exe //c "taskkill /F /IM java.exe" 2>/dev/null

DOCKER_API_VERSION=1.46 DOCKER_HOST=ssh://root@116.148.120.160 \
  nohup G:/jdk21/bin/java.exe -jar target/analysis-project-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=sandbox-windows,dev > /tmp/app-pause.log 2>&1 &
sleep 25

curl -s -o /dev/null -w "%{http_code}\n" -X POST \
  "http://localhost:8081/debug/session/pause?userId=tester&sessionId=smoke-001"
# 预期：200（即使没有 in-flight 调用，interrupt flag 也会被设置，无害）
```

### 3. 交互式 pause/resume E2E（核心场景）

```bash
# 3a. 启动一个长任务（analyze_data 会调 LLM 多轮 + python_exec，>5s）
curl -sN -X POST http://localhost:8081/v2/ai/chat \
  -H 'Content-Type: application/json' \
  --data-binary '{"input":"分析2026年1季度各部门质量分趋势,生成详细报告","conversationId":"pause-e2e-001","user_id":"pause_tester"}' \
  > /tmp/pause-e2e-chat1.txt 2>&1 &
CHAT1_PID=$!
sleep 8  # 等待 agent 进入分析中期

# 3b. 中途打断
curl -s -X POST "http://localhost:8081/debug/session/pause?userId=pause_tester&sessionId=pause-e2e-001" \
  -H 'Content-Type: application/json' \
  -d '{"message":"不要按部门,改成按产品线分组"}'
# 预期：{"status":"ok", ...}

# 3c. 等第一个 chat 流结束（应该收到 recovery msg + done）
wait $CHAT1_PID
grep -c "I noticed that you have interrupted me" /tmp/pause-e2e-chat1.txt
# 预期：≥1
grep -c "^event:done$" /tmp/pause-e2e-chat1.txt
# 预期：1

# 3d. 查 resume preview
curl -s "http://localhost:8081/debug/session/resume?userId=pause_tester&sessionId=pause-e2e-001"
# 预期：{"interrupted":false, "contextSize":N≥3, "lastAssistantText":"I noticed...", ...}

# 3e. 发补充信息继续 - 应该带着历史 + 新指令继续分析
curl -sN -X POST http://localhost:8081/v2/ai/chat \
  -H 'Content-Type: application/json' \
  --data-binary '{"input":"按产品线分组重做,重点对比一部 vs 二部","conversationId":"pause-e2e-001","user_id":"pause_tester"}' \
  > /tmp/pause-e2e-chat2.txt 2>&1

# 3f. 校验：第二轮回复应该体现"按产品线分组" + "一部 vs 二部"
grep -E "产品线|一部|二部" /tmp/pause-e2e-chat2.txt | head -5
# 预期：能匹配到，说明 agent 接受了补充信息并继续

# 3g. contextSize 在第二轮调用前后单调增长（state 被保留 + 累加）
# （可通过 /debug/session/resume 在 3d 和 3g 两次调用的 contextSize 对比验证）
```

### 4. 边界场景

```bash
# 4a. 对没有 in-flight 调用的 session 调 /pause - 应该 200 但无实际效果
curl -s -X POST "http://localhost:8081/debug/session/pause?userId=tester&sessionId=idle-001"
# 预期：200，下一次 /chat 时 reset() 清掉 flag

# 4b. /resume 查询不存在的 session - 应该返回空 context 或友好错误
curl -s "http://localhost:8081/debug/session/resume?userId=ghost&sessionId=nonexistent"
# 预期：200 + contextSize=0 或 404，取决于框架对未初始化 state 的处理
```

### 5. 回归

```bash
# 不调 /pause 的正常 /chat 流程不受影响
curl -sN -X POST http://localhost:8081/v2/ai/chat \
  -H 'Content-Type: application/json' \
  --data-binary '{"input":"查询2026年1季度杭州开发一部的质量分","conversationId":"regress-001","user_id":"regress_tester"}' \
  > /tmp/regress.txt 2>&1
grep -c "^event:done$" /tmp/regress.txt
# 预期：1
```

---

## 六、风险与限制

| 风险 / 限制 | 影响 | 应对 |
|---|---|---|
| `handleInterrupt` 的 recovery msg 是英文硬编码 | 中文 UX 略突兀 | 接受现状（功能性不影响）；若要中文化，需在 `V2ChatStreamServiceImpl.handleEvent` 拦截 `AgentResultEvent` 重写文本，或继承 `ReActAgent` 重写 `handleInterrupt`（复杂，不推荐） |
| `InterruptControl.userMessage` 不自动注入 context | 用户在 `/pause` body 里传的 `message` 仅供审计，agent 看不到 | 文档明确说明：补充信息必须通过下一个 `/v2/ai/chat` 发送；`/pause` body 的 message 字段是可选的 debug 辅助 |
| `/pause` 只对 in-flight SSE 流有效；空闲 session 调用无害但无意义 | 用户可能误以为 `/pause` 后 agent 立即停下 | 端点 response 里说明："In-flight call will abort at next checkpoint" - 框架的 checkpoint 粒度是"下一次 LLM 迭代前"，所以已在 LLM 流式输出的中间打断会有 ~1-2s 延迟 |
| `/pause` 后如果不发 `/chat` 续跑，saved state 会一直留在 MySQL | 占用 `agentscope_sessions.state_data` 行 | 现有 session 清理机制（如果有）兜底；短期内不是问题 |
| `interrupt(userId, sessionId)` 找不到对应 `AgentState` 时 | 框架会 lazy-create 一个空 state 再 set flag - 无害 | 不需要特殊处理 |
| 主 agent 在 `agent_spawn` 等待子 agent 期间被打断 | 子 agent 可能仍在运行 | 框架的 `checkInterrupted()` 在主 agent 的下一次迭代检查；子 agent 的取消由 `agent_spawn` 的 `wait_async_results` 在主 agent abort 后自然处理。E2E 验证里观察是否有"子 agent 仍在跑"的日志，若有再考虑额外的子 agent 取消逻辑 |

---

## 七、不做的事（明确跳过）

- ❌ 不重写 `handleInterrupt` 中文化 recovery msg - 复杂度高、收益低
- ❌ 不做"无 user msg 的空 resume 触发"端点 - 框架设计就是"用户下一句话即 resume"
- ❌ 不做 `/pause` 后自动注入 `InterruptControl.userMessage` 到 context - 会绕过 `addToContext` 的正常校验路径，且 `handleInterrupt` 的 recovery msg 已经引导用户继续
- ❌ 不改 `HarnessA2aRunnerV2` - `getAgent().getDelegate()` 链路已通

---

## 八、关联记忆

- [[async_tool_middleware_wiring]] - AsyncToolMiddleware + WorkspaceMessageBus 的 30s 异步 offload 模式，pause/resume 可与之共存（pause 不影响已 offload 的 tool，下次 checkpoint 才生效）
- [[plan_state_cross_restart_verified]] - plan_mode_context 跨 JVM 重启恢复，pause/resume 同样依赖 `agentscope_sessions.state_data` 持久化
- [[permission_mode_endpoint]] - PermissionMode 切换端点（DebugController 同款 pattern），pause/resume 端点风格对齐
