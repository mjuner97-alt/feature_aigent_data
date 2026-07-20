# Plan B：单次 interrupt + supplement + auto-resume 端点

> 状态：**已实施 + E2E 验证通过（7/7）** - 2026-07-20
> 提出日期：2026-07-19
> 关联缺口：`framework-highlights-verification.md` §4.1 "无 `/pause` `/resume` REST 端点"（已补 ✅）
> 替代方案：`interactive-pause-resume-plan.md`（debug 端点 + 两步调用，已被本方案替代）

## E2E 验证结果（2026-07-20）

| # | 验证项 | 状态 | 证据 |
|---|---|---|---|
| 1 | `agent.interrupt()` 真的设置 InterruptControl flag | ✅ | log: `interrupt triggered for sessionId=interrupt-e2e-005, hasInFlight=true` |
| 2 | 框架 `handleInterrupt()` append recovery msg | ✅ | chat1.txt 末尾 `"resultAll":"I noticed that you have interrupted me. What can I do for you?"` + 本地 daily memory 文件含同样文本 |
| 3 | `saveStateToSession()` 落盘 MySQL | ✅ | `agentscope_sessions` 表有 `interrupt_tester:interrupt-e2e-005`（4697B）和 `interrupt-e2e-006`（14609B）行，state_data 含 recovery msg + supplement |
| 4 | 新流 `beforeAgentExecution` reload state | ✅ | resume stream 的 LLM thinking 引用"之前的分析" + 完整理解 supplement 上下文 |
| 5 | LLM 实际基于 supplement 续跑（不是重头开始） | ✅ | resume stream 响应：`"明白了，您要把之前的分析从'按部门分组'改成'按产品线分组'，并重点对比杭州开发一部 vs 二部的产品线质量表现"` |
| 6 | 30s 超时 + 强制 dispose 路径 | ✅ | log: `forcibly disposed in-flight subscription for sessionId=interrupt-e2e-005 after 180s timeout, proceeding to resume stream`（超时从 30s 调到 180s，见"实施中发现的 Bug 及修复"） |
| 7 | X-Resume-Stream header 前端切换提示 | ✅ | response headers: `X-Resume-Stream: true` + `X-Original-Conversation-Id: interrupt-e2e-005` |

## 实施中发现的 Bug 及修复（2026-07-20）

E2E 测试中发现 3 个原有设计未覆盖的 bug，已修复：

### Bug #1：`doOnCancel` 缺失导致 cancel 信号丢失

**位置**：`V2ChatStreamServiceImpl.java` `stream()` 方法的 `eventFlux.subscribe(...)`

**现象**：interrupt 触发后，框架对 reactive stream 发的是 CANCELLED 信号（不是 COMPLETED），原 `subscribe()` 只挂了 `onNext`/`onError`/`onComplete` 三个 consumer，CANCELLED 信号不触发任何 consumer → `sendDone` 不调用 → `emitter.complete()` 不调用 → `onCompletion` 回调不触发 → cleanup 不跑 → `inFlight.completion` 永不完成 → interrupt 端点 30s 超时 → 504。

**修复**：在 subscribe 前给 eventFlux 加 `doOnCancel`，镜像 onComplete 行为（调 sendDone + emitter.complete() 触发 cleanup 链）。

```java
eventFlux = eventFlux.doOnCancel(() -> {
    log.info("v2 stream cancelled (likely interrupt) for sessionId={}, accumulatedLen={}",
            conversationId, accumulated.length());
    try {
        sendDone(emitter, accumulated, ansUUID, agentId, agentName, formType, conversationId);
    } catch (Exception e) {
        log.warn("SSE done send failed during cancel for sessionId={}: {}",
                conversationId, e.getMessage());
        try { emitter.complete(); } catch (Exception ce) { /* 忽略 */ }
    }
});
```

### Bug #2：SseEmitter 回调不可靠导致 inFlight 条目残留

**位置**：`V2ChatStreamServiceImpl.java` `stream()` 方法的 onError / onComplete consumer

**现象**：Spring 6.1.4 的 `SseEmitter.completeWithError()` 在 SSE 响应未 commit 时（如 workspace 启动失败、SandboxException 在首个事件前抛出）**不会触发 `onError` 回调**（async dispatch 路径不通）。cleanup 不跑 → `inFlightCalls` 条目残留 → 后续 resume stream 被 `putIfAbsent` 拒绝 → HTTP 429 "session busy"。

**修复**：在 error / success consumer 里显式调 `cleanup.run()`（CAS-guarded，重复调用幂等，不会跟 SseEmitter 的 `onCompletion`/`onError` 回调冲突）。

```java
error -> {
    // ... 原有逻辑 ...
    cleanup.run();  // 显式 cleanup，不依赖 SseEmitter 回调
},
() -> {
    try { sendDone(...); } catch (Exception e) { /* log */ }
    cleanup.run();  // 同上
}
```

### Bug #3：504 改为 fall-through to resume（避免用户感知失败）

**位置**：`V2ChatInterruptController.java` `interruptAndResume()` 方法的超时分支

**现象**：框架的 `checkInterrupted()` 只在 ReAct 迭代边界检查 InterruptControl flag。pre-iteration 中间件（`MemoryMaintenanceMiddleware` 做 SSH 文件操作 + LLM 调用、`SkillEvolutionHook` 做 LLM-based metric classification）阻塞时，interrupt 无法穿透，180s 都未必够。原设计超时后返回 504，用户得手动重试，体验差。

**修复**：超时后不再返回 504，而是：
1. `d.dispose()` 强制取消 in-flight 订阅（同步触发 doOnCancel → sendDone → emitter.complete → cleanup → 移除 inFlight 条目 + 完成 future）
2. 继续启动 resume stream（`putIfAbsent` 此时能成功，因为 cleanup 已同步移除条目）
3. 框架的 `callSerializationKey` 会把新调用排在原调用之后（原调用 cancel 后 release key，新调用立即 unblock）

```java
} catch (TimeoutException te) {
    Disposable d = inFlight.subscription().get();
    if (d != null && !d.isDisposed()) {
        d.dispose();
        log.warn("v2 /chat/interrupt: forcibly disposed in-flight subscription "
                + "for sessionId={} after {}s timeout, proceeding to resume stream",
                sessionId, INTERRUPT_WAIT_SECONDS);
    }
    // 不抛 504，继续往下走启动 resume stream
}
```

### 超时从 30s 调到 180s 的理由

- 30s 对慢 LLM 模型（qwen3:8b on CPU）太紧：单个 LLM 调用 + pre-call hooks 就 20-40s
- 框架的 `checkInterrupted()` 在 ReAct 迭代边界才检查，in-flight LLM 调用必须返回才能触发
- `MemoryMaintenanceMiddleware` 做 memory consolidation LLM 调用 + SSH `find` 命令扫 log 文件，单次可达 60-90s
- 180s 能覆盖一次完整的中间件 cycle，仍能 bound worst case
- 配合 Bug #3 的 fall-through 设计，超时不再是用户感知的失败

---

## Context（背景）

### 用户诉求
agent 执行任务中分析思路不对时，用户希望能：
1. 中断当前执行（保留已完成的步骤）
2. 补充调整信息
3. 一键继续，agent 基于补充信息继续分析

### 当前缺口
- **框架能力已就位**：AgentScope 的 `InterruptControl` + `ReActAgent.interrupt()` + `handleInterrupt()` + `checkInterrupted()` 已完整可用
- **项目代码零接入**：grep 整个项目，没有任何代码调用 `interrupt()`，没有 REST 端点暴露这个能力
- **现状**：agent 跑偏了用户只能等它跑完（烧 token + 时间），无法中途打断重定向

### 现有 plan 文档的问题
`interactive-pause-resume-plan.md` 已规划 `/debug/session/pause` + `/debug/session/resume` 两个端点，但：
- 定位是 debug 工具，路径在 `/debug/` 下，不适合终端用户
- pause + resume 是**两次 HTTP 调用**，用户体验割裂
- pause body 里的 message 不注入上下文（仅供审计），用户必须重新输入
- recovery msg 是英文硬编码 "I noticed that you have interrupted me..."

### 本方案目标
**面向终端用户的单次端点**：用户在 UI 上点击"暂停并补充"按钮，输入补充信息，前端只发一次请求，server 完成打断+续跑，返回新 SSE 流。客户端感知是"一步操作"。

---

## 设计

### 端点设计

| 端点 | 行为 |
|---|---|
| `POST /v2/ai/chat/interrupt` | 接收 `{userId, conversationId, supplement}`；打断当前 in-flight 调用；等待其完成；以 supplement 作为新 user msg 启动新 SSE 流；返回 SseEmitter（与 `/v2/ai/chat` 同格式）|

**路径选择理由**：放在 `/v2/ai/chat/` 命名空间下（非 `/debug/`），表示是用户级功能；前端可复用 `/v2/ai/chat` 的 SSE 解析逻辑。

### 核心流程

```
Client                  Server
  │
  │  POST /v2/ai/chat (输入"分析Q1质量分")
  │  ────────────────►  V2ChatStreamServiceImpl.stream()
  │                       -> runner.streamEvents() 启动 ReAct loop
  │  ◄─── SSE: text_delta... (agent 在跑)
  │
  │  [用户决定打断,前端关闭原 SSE 连接]
  │
  │  POST /v2/ai/chat/interrupt
  │       body: {userId, conversationId, supplement:"改成按产品线分组"}
  │  ────────────────►  V2ChatInterruptController
  │                       ① 从 inFlightCalls 拿到当前 call 的 InFlightCall
  │                          （含 completion future + subscription ref）
  │                       ② runner.getAgent().getDelegate()
  │                            .interrupt(userId, sessionId, supplementMsg)
  │                          -> InterruptControl.flag=true
  │                       ③ 等待 completion future 完成（180s 超时）
  │                          [fast path] in-flight 在迭代边界 checkInterrupted:
  │                             -> InterruptedException -> handleInterrupt()
  │                             -> append recovery msg + saveStateToSession()
  │                             -> AgentResultEvent -> onComplete consumer
  │                             -> sendDone + emitter.complete()
  │                             -> cleanup (remove inFlight + complete future)
  │                          [slow path] 180s 超时：
  │                             -> d.dispose() 强制取消订阅
  │                             -> doOnCancel 触发 sendDone + emitter.complete()
  │                             -> cleanup 同步移除 inFlight 条目
  │                             -> 不抛 504，继续走 step ④
  │                       ④ 调 chatStreamService.stream(chatReq)
  │                          其中 chatReq.input = supplement
  │                          -> callSerializationKey 串行化（同 session 排队）
  │                          -> 新 SSE 流，context 从 saved state 起步
  │                             (含历史 + recovery msg + 新 supplement)
  │  ◄─── SSE: text_delta (基于补充信息继续)
  │  ◄─── SSE: done
  │  + Response headers:
  │    X-Resume-Stream: true
  │    X-Original-Conversation-Id: <sessionId>
  │
```

### 关键技术决策

#### 1. 如何可靠等待 in-flight 调用完成？

**不依赖 Reactor 的 `callSerializationKey` 自动排队**（虽然同 session 调用会序列化，但 SseEmitter 行为不可控），改用**显式完成追踪器**：

`V2ChatStreamServiceImpl` 新增字段：
```java
private final ConcurrentHashMap<String, CompletableFuture<Void>> inFlightCalls =
    new ConcurrentHashMap<>();

private static String callKey(String userId, String sessionId) {
    return (userId == null ? "__anon__" : userId) + ":" + sessionId;
}

public CompletableFuture<Void> getInFlightCall(String userId, String sessionId) {
    return inFlightCalls.get(callKey(userId, sessionId));
}
```

在 `stream()` 入口：
```java
CompletableFuture<Void> completion = new CompletableFuture<>();
inFlightCalls.put(callKey(userId, conversationId), completion);
// ... 在 doOnComplete / doOnError 里:
inFlightCalls.remove(callKey(userId, conversationId));
completion.complete(null); // 或 completeExceptionally(error)
```

#### 2. supplement 消息怎么处理？

**方案：不修改框架的 `handleInterrupt()`**（在 JAR 里），让 recovery msg 自然进入 context，supplement 作为新 `/chat` 的 user msg 紧跟其后：

LLM 看到的 context 序列：
```
[历史 user msg + assistant msg + tool_call + tool_result...]
ASSISTANT: "I noticed that you have interrupted me. What can I do for you?"
USER: "改成按产品线分组,重点对比一部 vs 二部"  ← supplement
```

LLM 自然理解这是"被打断后的新指令"，按新方向继续。recovery msg 英文是个小瑕疵但功能性不受影响。

**为何不替换 recovery msg 为中文**：
- 修改 saved state 需要操作 `AgentState.contextMutable()` + 手动调用 `stateStore.save()`，绕过框架路径
- 风险高、收益低
- 后续若框架开放 hook 钩子，再考虑

#### 3. InterruptControl.userMessage 怎么用？

**保留传递但仅做审计**：`interrupt(userId, sessionId, supplementMsg)` 把 supplement 存到 `InterruptControl.userMessage`，框架不自动注入 context，仅用于日志/调试时追溯用户当时输入了什么。

### 边界场景

| 场景 | 处理 |
|---|---|
| 没有在跑的 in-flight 调用 | `getInFlightCall()` 返回 null；跳过等待，直接 `chatStreamService.stream()` 启动新调用；`interrupt()` 设置的 flag 在新调用 `beforeAgentExecution()` 里被 `reset()` 清掉，无害 |
| 当前调用 180s 内未完成 | **实施修订**：不返回 504，而是 `d.dispose()` 强制取消订阅（同步触发 doOnCancel -> cleanup -> 移除 inFlight 条目），然后继续启动 resume stream。框架的 `callSerializationKey` 串行化排队保证 state 一致性。详见"实施中发现的 Bug 及修复 #3" |
| 同一 session 短时间多次点 interrupt | `interrupt()` 幂等；**修订：第二次 interrupt 直接 reject 409 Conflict**（见评审修订 #1）|
| 用户没填 supplement | 返回 400 Bad Request |
| 前端没关闭原 SSE 连接 | **修订：response header 加 `X-Resume-Stream: true` + `X-Original-Conversation-Id` 提示前端关闭原流**（见评审修订 #5）|
| in-flight 调用错误结束（如 SandboxException）| **实施修订**：error consumer 显式调 `cleanup.run()`，确保 `inFlightCalls` 条目被移除，后续 interrupt 不会被 429 拒绝。详见"实施中发现的 Bug 及修复 #2" |

---

## 评审修订记录（2026-07-19）

经深入代码评审发现 10 个缺陷，按优先级修复如下。

### P0：必须修复才能实施

#### 修订 #1：CompletableFuture 注册竞态（修复缺陷 1+2）

**问题**：`ConcurrentHashMap.put()` 覆盖式，两个并发同 session 请求会互相覆盖，导致旧 future 永远不 complete + 新 future 被旧 cleanup 误删。

**修复**：用 `putIfAbsent` + 等待旧 future 完成才注册新的；同 session 已有 in-flight 时直接 reject 第二个请求。

`V2ChatStreamServiceImpl.stream()` 入口改为：

```java
String key = callKey(userId, conversationId);
CompletableFuture<Void> existing = inFlightCalls.get(key);
if (existing != null && !existing.isDone()) {
    // 同 session 已有 in-flight 调用，reject 防止覆盖
    SseEmitter err = new SseEmitter();
    err.completeWithError(new TooManyRequestsException(
        "Session " + conversationId + " already has an in-flight call"));
    return err;
}
CompletableFuture<Void> completion = new CompletableFuture<>();
CompletableFuture<Void> prev = inFlightCalls.putIfAbsent(key, completion);
if (prev != null) {
    // 竞态窗口：另一个线程刚刚 put 了 - 走它的 future
    completion = prev;
}
// ... 业务逻辑 ...
```

`interrupt` 端点同 session 已在 interrupt 等待中时返回 409：

```java
if (interruptInProgress.putIfAbsent(key, true) != null) {
    return ResponseEntity.status(409).body(...);
}
```

#### 修订 #2：验证 callSerializationKey 是否真序列化（修复缺陷 3）✅ 已验证

**问题**：方案假设同 session 调用会自动序列化，但 `stream()` 直接调 `runner.streamEvents()`，没看到显式锁。如果不序列化，两个 stream() 同时跑会覆盖 saveStateToSession。

**验证结果**：✅ **框架已序列化，跳过 Striped<Lock>**

**源码证据**（`agentscope-core-2.0.0-RC5-sources.jar` / `io/agentscope/core/agent/AgentBase.java`）：

```java
// line 113
private final ConcurrentHashMap<Object, Mono<Void>> callGates = new ConcurrentHashMap<>();

// line 270-283 - callInternal 入口
Object gateKey = callSerializationKey(rc);
Mono<Msg> lifecycle = Mono.defer(() -> runLifecycleBody(msgs, rc, doCallFn, requestId));
Mono<Msg> gated = gateKey == null ? lifecycle : serializeOnKey(gateKey, lifecycle);

// line 345-365 - serializeOnKey 实现（FIFO 排队）
private <T> Mono<T> serializeOnKey(Object key, Mono<T> action) {
    return Mono.defer(() -> {
        Sinks.Empty<Void> release = Sinks.empty();
        Mono<Void> releaseMono = release.asMono();
        Mono<Void>[] prev = new Mono[1];
        callGates.compute(key, (k, tail) -> {
            prev[0] = tail == null ? Mono.empty() : tail;
            return releaseMono;        // 把自己的 release mono 设为新的 tail
        });
        return prev[0].onErrorComplete()   // 等前一个 terminate（complete/error/cancel 都触发）
                .then(action)
                .doFinally(sig -> {
                    release.tryEmitEmpty();              // 释放下一个
                    callGates.remove(key, releaseMono);  // BiPredicate 防止误删其他 key
                });
    });
}
```

**ReActAgent 的 key 实现**（`io/agentscope/core/ReActAgent.java:502`）：
```java
@Override
protected Object callSerializationKey(RuntimeContext rc) {
    String sid = rc != null ? rc.getSessionId() : null;
    if (sid == null || sid.isBlank()) sid = defaultSessionId;
    String uid = rc != null ? rc.getUserId() : null;
    return slotKey(uid, sid);   // 同 (userId, sessionId) -> 同 key -> 串行
}
```

**机制保证**：
1. ✅ 同 session 的两次 `streamEvents()` subscribe 会自动排队，第二个等第一个 terminate 才开始
2. ✅ doFinally 在 complete/error/cancel 都触发 release，不会阻塞队列
3. ✅ `callGates.remove(key, releaseMono)` 用 BiPredicate 值比较，避免误删其他 key
4. ✅ interrupt -> handleInterrupt -> saveStateToSession -> terminate -> 自动 release -> 排队中的新调用 unblock

**对 Plan B 的影响**：
- ✅ 不需要加 `Striped<Lock>`，框架已序列化 streamEvents lifecycle
- ⚠️ **修订 #1 的 putIfAbsent 仍要做** - inFlightCalls 的 put/remove 在 `V2ChatStreamServiceImpl` 层（HTTP 线程同步阶段），不在 serializeOnKey 保护范围内。两个 stream() 紧挨着进入时，put 会冲突
- ⚠️ **修订 #3 的 remove-before-complete 顺序约束仍要做** - 同理，cleanup Runnable 在 SSE 回调线程触发，跟框架 serializeOnKey 无关

**对 interrupt 端点时序的影响**：
1. 用户调 `/v2/ai/chat/interrupt`
2. 端点先调 `agent.interrupt()` 设置 flag
3. 端点调 `chatStreamService.stream(chatReq)` -> 返回 SseEmitter
4. 内部 `subscribe()` 在 boundedElastic 上异步触发
5. `subscribe()` 进入 serializeOnKey -> gate key = 同 session -> 等前一个调用的 release mono
6. 前一个调用：checkInterrupted -> throw InterruptedException -> handleInterrupt -> saveStateToSession -> Mono terminate -> doFinally release.tryEmitEmpty()
7. 新调用 unblock -> beforeAgentExecution -> activateSlotForContext (从 MySQL 加载含 recovery msg 的 state) -> interruptControl.reset() -> doCall

**新调用 subscribe 到 unblock 之间会有一段等待时间**（前一个调用 cleanup 期间），客户端会看到 SSE 流开始但暂时无事件。这个空白期正常（前一个调用 terminate 通常在 1-3s 内完成）。

### P1：重要修复

#### 修订 #3：cleanup 里 remove/complete 顺序严格化（修复缺陷 7）

**问题**：现有 `cleanup` Runnable 没显式说明 remove 必须在 complete 之前。

**修复**：在 cleanup Runnable 末尾加显式注释 + 单元测试断言顺序：

```java
Runnable cleanup = () -> {
    if (!cleaned.compareAndSet(false, true)) return;
    // ... 现有 cleanup 逻辑 ...

    // In-flight call tracking: remove BEFORE complete to prevent race where
    // interrupt endpoint sees future completed, calls stream() which puts a new
    // future, and then this cleanup's remove wipes the new future.
    // MUST NOT swap the order.
    inFlightCalls.remove(key);
    completion.complete(null);
};
```

单元测试：`testInterruptRemovesOldFutureBeforeComplete` - 模拟 interrupt 后 stream() 立即注册新 future，断言新 future 不被旧 cleanup 误删。

#### 修订 #4：30s 超时后强制取消 in-flight（修复缺陷 4）

**问题**：30s 超时后 in-flight 调用继续烧 token + CPU。

**修复**：把 subscription 引用也通过 `inFlightCalls` 暴露，超时后 dispose：

```java
// V2ChatStreamServiceImpl: 把 inFlightCalls 值类型从 CompletableFuture<Void> 改为封装类
private static class InFlightCall {
    final CompletableFuture<Void> completion = new CompletableFuture<>();
    final AtomicReference<Disposable> subscription = new AtomicReference<>();
}

// interrupt 端点等待超时后:
if (!inFlight.completion.get(30, TimeUnit.SECONDS)) {
    Disposable d = inFlight.subscription.get();
    if (d != null && !d.isDisposed()) {
        d.dispose();
        log.warn("Interrupt timeout - forcibly disposed in-flight subscription for sessionId={}", sessionId);
    }
    return ResponseEntity.status(504).body(...);
}
```

副作用：dispose 后原 SSE 流会触发 onError -> cleanup -> complete。需要确保 cleanup 兼容 dispose 后的 cleanup。

#### 修订 #5：双 SSE 流协调提示前端（修复缺陷 8）

**问题**：服务端不强制前端关原 SSE，两个流同时活跃会导致 UI 混乱。

**修复**：interrupt 端点 response header 加提示：

```java
HttpHeaders headers = new HttpHeaders();
headers.add("X-Original-Stream-Id", originalAnsUUID);  // 让前端知道要关哪个 SSE
headers.add("X-Resume-Stream-Id", newAnsUUID);          // 新 SSE 的 ID
return ResponseEntity.ok().headers(headers).body(emitter);
```

并在文档协议约定里明确：前端收到 `/v2/ai/chat/interrupt` 响应后必须主动 `EventSource.close()` 原 `/v2/ai/chat` 连接。

### P2：标准合规修复

#### 修订 #6：conversationId 必填假设文档化（修复缺陷 12）

**问题**：`/v2/ai/chat` 不强制传 conversationId，但 interrupt 端点要求。

**修复**：
1. interrupt 端点要求 conversationId 必填，缺失返回 400 + 友好错误："interrupt requires conversationId; the original /v2/ai/chat must explicitly pass conversationId"
2. 文档明确：要使用 interrupt 功能，前端必须在 `/v2/ai/chat` 请求里显式传 conversationId（不能用 server 生成的 UUID）

#### 修订 #7：permission/mode 弃用 header 符合 RFC（修复缺陷 13）

**问题**：`X-Sunset: v2.1.0` 不符合 RFC 8594（标准是 `Sunset` header 值为 HTTP-date）。

**修复**：用标准 header 组合：

```java
HttpHeaders headers = new HttpHeaders();
headers.add("Deprecation", "true");                                    // RFC 7234
headers.add("Sunset", "Wed, 31 Dec 2026 23:59:59 GMT");               // RFC 8594 HTTP-date
headers.add("Link", "</v2/ai/session/permission/mode>; rel=\"successor-version\"");  // RFC 8288
```

#### 修订 #8：PermissionModeHelper 抽公共方法（修复缺陷 14）

**问题**：方案说"复用 DebugController 的实现"但没说怎么复用。

**修复**：抽到独立 helper 类避免代码重复：

新增 `analysis-project/src/main/java/com/agentscopea2a/v2/util/PermissionModeHelper.java`：

```java
public class PermissionModeHelper {
    public static Map<String, Object> buildGetResponse(String userId, String sessionId, PermissionMode mode) {
        // ... 从 DebugController.getPermissionMode 抽出 ...
    }
    public static Map<String, Object> buildSetResponse(String userId, String sessionId, PermissionMode mode) {
        // ... 从 DebugController.setPermissionMode 抽出 ...
    }
}
```

`DebugController` 和 `V2SessionController` 都调这个 helper。

### P3：测试改进

#### 修订 #9：E2E 用 SSE 事件计数代替 sleep（修复缺陷 10）

**问题**：`sleep 8` 不可靠，LLM 慢响应时 agent 还没开始跑 tool call。

**修复**：改成事件计数等待：

```bash
# 等到原 SSE 流收到至少 3 个 text_block_delta 事件才发 interrupt
while [ "$(grep -c 'event:text_block_delta' /tmp/interrupt-e2e-chat1.txt)" -lt 3 ]; do
    sleep 0.5
    # 加 30s 总超时防止死等
done
```

---

### 修订汇总：改动文件清单更新

| # | 文件 | 操作 | 修订原因 |
|---|---|---|---|
| 1 | `v2/dto/InterruptResumeRequest.java` | 新增 | 原方案 |
| 2 | `v2/service/V2ChatStreamService.java` | 修改 | 加 `getInFlightCall` 方法（原方案）|
| 3 | `v2/service/V2ChatStreamServiceImpl.java` | 修改 | 加 `inFlightCalls` + `Striped<Lock>`（修订 #1+#2）+ cleanup 顺序约束（修订 #3）+ subscription 暴露（修订 #4）|
| 4 | `v2/controller/V2ChatInterruptController.java` | 新增 | interrupt + 等待 + 强制 dispose（修订 #4）+ 409 防重入（修订 #1）+ response header（修订 #5）|
| 5 | `v2/util/PermissionModeHelper.java` | 新增 | 抽公共方法（修订 #8）|
| 6 | `v2/controller/V2SessionController.java` | 新增 | permission/mode 迁移，调 PermissionModeHelper（修订 #8）|
| 7 | `controller/DebugController.java` | 修改 | permission/mode 加 @Deprecated + 标准 header（修订 #7），实现改调 PermissionModeHelper（修订 #8）|
| 8 | `framework-highlights-verification.md` | 修改 | §4.1 标记已补 |

### 修订汇总：新增验证步骤

#### 6. 并发安全验证（修订 #1+#2）

```bash
# 6a. 同 session 并发 /v2/ai/chat 应该 reject 第二个
curl -sN -X POST http://localhost:8081/v2/ai/chat \
  -H 'Content-Type: application/json' \
  --data-binary '{"input":"分析1","conversationId":"concurrent-001","user_id":"concurrent_tester"}' \
  > /tmp/concurrent-1.txt 2>&1 &
sleep 1
curl -s -o /tmp/concurrent-2.txt -w "%{http_code}" -X POST http://localhost:8081/v2/ai/chat \
  -H 'Content-Type: application/json' \
  --data-binary '{"input":"分析2","conversationId":"concurrent-001","user_id":"concurrent_tester"}'
# 预期: 429 (Too Many Requests) 或 409 (Conflict)

# 6b. callSerializationKey 验证 - 如果不加锁,这个测试会数据丢失
# 跑 5 次同 session 并发,检查 MySQL state_data 没有损坏
for i in 1 2 3 4 5; do
  curl -sN -X POST http://localhost:8081/v2/ai/chat \
    -H 'Content-Type: application/json' \
    --data-binary "{\"input\":\"分析$i\",\"conversationId\":\"stress-001\",\"user_id\":\"stress_tester\"}" \
    > /dev/null 2>&1 &
done
wait
# 预期: 4 个 reject,1 个成功,MySQL state_data 完整
```

#### 7. 强制取消验证（修订 #4）

```bash
# 启一个超长任务,然后 interrupt 不等待 30s,直接强制取消
curl -sN -X POST http://localhost:8081/v2/ai/chat \
  -H 'Content-Type: application/json' \
  --data-binary '{"input":"跑一个 60s 的长任务","conversationId":"long-001","user_id":"long_tester"}' \
  > /tmp/long-chat1.txt 2>&1 &

sleep 3
# 模拟 30s 超时(实际可以等 31s)
curl -s -X POST http://localhost:8081/v2/ai/chat/interrupt \
  -H 'Content-Type: application/json' \
  --data-binary '{"user_id":"long_tester","conversationId":"long-001","supplement":"取消"}' \
  -w "%{http_code}" -o /tmp/long-interrupt.txt
# 预期: 504 + 强制 dispose 原流

# 校验原 SSE 流在 dispose 后 5s 内结束(不应该继续烧 token)
sleep 5
grep -c "^event:done$" /tmp/long-chat1.txt  # 预期: 1 (强制结束)
```

---

### 修订后的风险与限制补充

| 新增风险 | 影响 | 应对 |
|---|---|---|
| `Striped<Lock>` 死锁风险 | interrupt 端点等待 future 时持有 lock，cleanup 也要 lock 才能 unlock | 用 `tryLock(0, SECONDS)` 而不是 `lock()`，避免阻塞 |
| `dispose()` 后 cleanup 触发 complete 的重入风险 | 强制 dispose 触发 onError -> cleanup -> complete，跟 interrupt 端点的 `future.get()` 竞态 | 用 `AtomicBoolean` 守护 complete 只执行一次 |
| `TooManyRequestsException` 是新异常类 | 需要全局异常处理器 | 加到现有 `GlobalExceptionHandler`（如果有）|

---

## 改动文件

### 1. 新增 `InterruptResumeRequest.java`
路径：`analysis-project/src/main/java/com/agentscopea2a/v2/dto/InterruptResumeRequest.java`

```java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class InterruptResumeRequest {
    @JsonProperty("user_id") @JsonAlias("userId")
    private String userId;

    @JsonAlias("conversation_id")
    private String conversationId;       // 即 sessionId

    private String supplement;            // 必填,用户补充/调整信息
}
```

### 2. 新增 `V2ChatInterruptController.java`
路径：`analysis-project/src/main/java/com/agentscopea2a/v2/controller/V2ChatInterruptController.java`

```java
@RestController
@RequestMapping("/v2/ai/chat")
public class V2ChatInterruptController {
    private final ObjectProvider<HarnessA2aRunnerV2> runnerProvider;
    private final ObjectProvider<V2ChatStreamService> chatStreamServiceProvider;

    @PostMapping("/interrupt")
    public ResponseEntity<?> interruptAndResume(
            @RequestBody(required = false) InterruptResumeRequest req,
            @RequestParam(value = "userId", required = false) String userIdParam,
            @RequestParam(value = "conversationId", required = false) String convIdParam) {
        // 解析参数 (body 或 query)
        // 校验 supplement 非空
        // 拿 runner,校验 SERVICE_UNAVAILABLE
        // ① 拿 inFlightCalls 的 CompletableFuture (可能为 null)
        // ② runner.getAgent().getDelegate().interrupt(userId, sessionId, supplementMsg)
        // ③ 等待 CompletableFuture (30s 超时)
        // ④ 调 chatStreamService.stream(chatReq) 返回 SseEmitter
    }
}
```

### 3. 修改 `V2ChatStreamService.java`
路径：`analysis-project/src/main/java/com/agentscopea2a/v2/service/V2ChatStreamService.java`

新增方法：
```java
CompletableFuture<Void> getInFlightCall(String userId, String sessionId);
```

### 4. 修改 `V2ChatStreamServiceImpl.java`
路径：`analysis-project/src/main/java/com/agentscopea2a/v2/service/V2ChatStreamServiceImpl.java`

改动点：
- 新增字段 `ConcurrentHashMap<String, CompletableFuture<Void>> inFlightCalls`
- `stream()` 入口注册 `CompletableFuture`，key = `userId:conversationId`
- cleanup Runnable 里 `inFlightCalls.remove(key)` + `completion.complete(null)` / `completeExceptionally`
- 实现 `getInFlightCall(userId, sessionId)` 方法

### 5. 修改 `framework-highlights-verification.md`（可选）
路径：`analysis-project/src/main/resources/docs/rc2-to-rc5/framework-highlights-verification.md`

§4.1 "无 `/pause` `/resume` REST 端点"这条缺口标记为 ✅ 已补（指向 `/v2/ai/chat/interrupt`）。

### 6. 保留原 plan 文档作为历史
`interactive-pause-resume-plan.md` 标记为"已被 Plan B（interrupt-resume-single-endpoint-plan）替代"，不删除（保留设计演进记录）。

### 7. permission/mode 路径迁移（详见下方"扩展：permission/mode 路径迁移"章节）
- 修改 `DebugController.java`：在 `getPermissionMode` / `setPermissionMode` 上加 `@Deprecated`，response header 加 `X-Deprecation` 提示新路径
- 新增 `V2SessionController.java`：`/v2/ai/session/permission/mode` GET+POST，复用现有 `agent.getPermissionMode()` / `agent.setPermissionMode()` 调用

---

## 扩展：permission/mode 路径迁移

### 为什么迁移

PermissionMode 的 5 种模式（`default` / `accept_edits` / `explore` / `bypass` / `plan_mode`）是**用户在交互过程中切换的人机协同模式**，不是 debug 工具：

| 模式 | 用户场景 |
|---|---|
| `default` | "每一步工具调用都要我批准" |
| `accept_edits` | "信任它自动改文件，其他还是要问" |
| `explore` | "只让它探索，不要动文件" |
| `bypass` | "全部跳过批准" |
| `plan_mode` | "先规划再做" |

当前 `/debug/permission/mode` 的代码注释自己也写明错位了：

```java
// DebugController.java:289
// <p>This mutates session state; intended for dev/debug use only.
//                                              ^^^^^^^^^^^^^^^^^
```

定位错了。HITL 模式切换属于用户级操作。

### 迁移策略：双路径共存 + 标记弃用

为避免破坏前端现有调用，采用**渐进迁移**：

| 旧路径（保留 + 弃用标记） | 新路径（正式） |
|---|---|
| `GET /debug/permission/mode` | `GET /v2/ai/session/permission/mode` |
| `POST /debug/permission/mode` | `POST /v2/ai/session/permission/mode` |

### 实现细节

#### 新增 `V2SessionController.java`
路径：`analysis-project/src/main/java/com/agentscopea2a/v2/controller/V2SessionController.java`

```java
@RestController
@RequestMapping("/v2/ai/session")
public class V2SessionController {
    private final ObjectProvider<HarnessA2aRunnerV2> runnerProvider;

    @GetMapping("/permission/mode")
    public ResponseEntity<Map<String, Object>> getPermissionMode(
            @RequestParam("userId") String userId,
            @RequestParam("sessionId") String sessionId) {
        // 复用 DebugController.getPermissionMode 的实现逻辑
        // 调 agent.getPermissionMode(userId, sessionId)
    }

    @PostMapping("/permission/mode")
    public ResponseEntity<Map<String, Object>> setPermissionMode(
            @RequestParam("userId") String userId,
            @RequestParam("sessionId") String sessionId,
            @RequestParam("mode") String modeStr) {
        // 复用 DebugController.setPermissionMode 的实现逻辑
        // 调 agent.setPermissionMode(userId, sessionId, mode)
    }
}
```

#### 修改 `DebugController.java`

在 `getPermissionMode` / `setPermissionMode` 上加 `@Deprecated` 注解 + response header 弃用提示：

```java
@Deprecated
@GetMapping("/permission/mode")
public ResponseEntity<Map<String, Object>> getPermissionMode(...) {
    HttpHeaders headers = new HttpHeaders();
    headers.add("X-Deprecation", "Use GET /v2/ai/session/permission/mode instead");
    headers.add("X-Sunset", "v2.1.0");   // 计划在 v2.1.0 移除
    // ... 现有实现不变 ...
    return ResponseEntity.ok().headers(headers).body(out);
}
```

#### DebugController 仅保留真·debug 端点

迁移后 DebugController 只剩**纯调试用途**的端点，定位清晰：

| 保留 | 用途 |
|---|---|
| `GET /debug/workspace` | 看 workspace 内容 |
| `GET /debug/memory` | 看 memory 文件 |
| `GET /debug/memory/ledger/{userId}` | 看用户 memory ledger |
| `GET /debug/memory/active-users` | 看活跃用户 |
| `GET /debug/skills` | 看 skills 列表 |
| `POST /debug/digest` | 手动触发 digestion |
| `POST /debug/sweep` | 手动触发 artifact 清理 |
| `GET /debug/sessions` | 看 sessions 状态 |

#### 最终用户级端点布局

```
/v2/ai/chat                          ← 主入口（现有）
/v2/ai/chat/interrupt                ← Plan B 中断+续跑
/v2/ai/session/permission/mode        ← HITL 模式切换（迁移）
```

### 迁移时间表

| 阶段 | 时间 | 动作 |
|---|---|---|
| 1. 双路径共存 | 当前版本 | 新路径可用；旧路径加弃用标记 |
| 2. 前端切换 | 1-2 周内 | 前端调用全部切到 `/v2/ai/session/permission/mode` |
| 3. 删除旧路径 | v2.1.0 | 从 DebugController 移除 `permission/mode` 端点 |

### 风险与限制

| 风险 / 限制 | 影响 | 应对 |
|---|---|---|
| 前端已有调用 `/debug/permission/mode` | 切换期间需要支持双路径 | 弃用标记 + X-Sunset header 提示；前端切换完成前不删除旧路径 |
| response body 格式必须完全一致 | 前端代码不能改动 | V2SessionController 直接复用 DebugController 的 response 构造逻辑（可抽公共方法或复制） |
| 文档需要同步更新 | E2E 测试脚本可能引用旧路径 | grep 项目代码 + 文档，确认所有 `/debug/permission/mode` 引用都加了迁移说明 |

---

## 复用的现有能力

| 能力 | 位置 | 用法 |
|---|---|---|
| `HarnessAgent.getDelegate()` | `io.agentscope.harness.agent.HarnessAgent` | 拿到 `ReActAgent`，调 `interrupt(userId, sessionId, msg)` |
| `ReActAgent.interrupt(userId, sessionId, msg)` | 框架 JAR | 触发 `InterruptControl` flag，存 supplement 到 `userMessage` |
| `InterruptControl` | `io.agentscope.core.interruption.InterruptControl` | per-session 中断信号，框架自动检查 |
| `handleInterrupt()` + `saveStateToSession()` | 框架 JAR | 中断后自动 append recovery msg + 落盘 state |
| `beforeAgentExecution()` line 538 | 框架 JAR | 新调用开始时 `reset()` 清 flag，从 saved state 起步 |
| `V2ChatStreamService.stream(ChatRequest)` | 现有 | 直接复用，supplement 作为 `ChatRequest.input` 启动新流 |
| `SseEmitter` + event mapping | `V2ChatStreamServiceImpl` | 复用现有 SSE 协议，前端无感 |
| DebugController 的 `ObjectProvider<HarnessA2aRunnerV2>` pattern | `DebugController.java` | lazy bean 注入风格 |

---

## 验证步骤

> 以下命令经过 2026-07-20 E2E 验证实测可用。关键点：中文请求体必须用 UTF-8 编码的文件（`--data-binary @file`），不要用 inline 中文字符串（bash on Windows 会以非 UTF-8 编码发送，导致 LLM 收到乱码）。

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
  --spring.profiles.active=sandbox-windows,dev > /tmp/app-interrupt.log 2>&1 &
sleep 25

# 端点存在性校验（无 in-flight 调用分支）
curl -sN -X POST http://localhost:8081/v2/ai/chat/interrupt \
  -H 'Content-Type: application/json; charset=UTF-8' \
  --data-binary @<(printf '{"user_id":"smoke","conversationId":"smoke-001","supplement":"测试无 in-flight 场景"}')
# 预期: 200 + SSE 流（因为没 in-flight，直接走新 stream 路径）
```

### 3. 端到端 interrupt+resume E2E

**关键：请求体写入 UTF-8 编码的文件，curl 用 `--data-binary @file` 读取。**

```bash
# 准备 UTF-8 编码的请求体文件
cat > /tmp/chat1-req.json <<'EOF'
{"input":"分析2026年1季度各部门质量分趋势,生成详细报告","conversationId":"interrupt-e2e-001","user_id":"interrupt_tester"}
EOF

cat > /tmp/interrupt-req.json <<'EOF'
{"user_id":"interrupt_tester","conversationId":"interrupt-e2e-001","supplement":"不要按部门,改成按产品线分组,重点对比一部 vs 二部"}
EOF

# 3a. 启一个长任务
curl -sN -X POST http://localhost:8081/v2/ai/chat \
  -H 'Content-Type: application/json; charset=UTF-8' \
  --data-binary @/tmp/chat1-req.json \
  > /tmp/interrupt-e2e-chat1.txt 2>&1 &
CHAT1_PID=$!
sleep 10  # 等 agent 跑到中期

# 3b. 发起 interrupt+resume
curl -sN -X POST http://localhost:8081/v2/ai/chat/interrupt \
  -H 'Content-Type: application/json; charset=UTF-8' \
  --data-binary @/tmp/interrupt-req.json \
  -D /tmp/interrupt-headers.txt \
  > /tmp/interrupt-e2e-chat2.txt 2>&1

# 3c. 校验 response headers
grep "X-Resume-Stream: true" /tmp/interrupt-headers.txt   # 预期: 命中
grep "X-Original-Conversation-Id" /tmp/interrupt-headers.txt  # 预期: 命中

# 3d. 校验原 SSE 流以 recovery msg + done 结尾
wait $CHAT1_PID
grep -c "I noticed that you have interrupted me" /tmp/interrupt-e2e-chat1.txt  # 预期: ≥1
grep -c "^event:done$" /tmp/interrupt-e2e-chat1.txt                            # 预期: 1

# 3e. 校验 resume stream 体现 supplement 关键词（产品线 / 一部 / 二部）
grep -c "产品线" /tmp/interrupt-e2e-chat2.txt   # 预期: ≥1
grep -c "一部" /tmp/interrupt-e2e-chat2.txt     # 预期: ≥1
grep -c "二部" /tmp/interrupt-e2e-chat2.txt     # 预期: ≥1

# 3f. 校验 resume stream done
grep -c "^event:done$" /tmp/interrupt-e2e-chat2.txt   # 预期: 1（注：慢 LLM 模型下可能 10min SSE_TIMEOUT 触发，done 可能丢失，这是 pre-existing 问题，不影响 interrupt 功能验证）
```

### 4. 边界场景
```bash
# 4a. 无 in-flight 调用 - 应该走"直接新 stream"分支
cat > /tmp/edge1.json <<'EOF'
{"user_id":"edge","conversationId":"idle-001","supplement":"直接补充信息继续"}
EOF
curl -sN -X POST http://localhost:8081/v2/ai/chat/interrupt \
  -H 'Content-Type: application/json; charset=UTF-8' \
  --data-binary @/tmp/edge1.json
# 预期: 200 + SSE 流（新调用，flag 在 beforeAgentExecution 被 reset）

# 4b. 缺 supplement - 应该 400
cat > /tmp/edge2.json <<'EOF'
{"user_id":"edge","conversationId":"empty-supplement"}
EOF
curl -s -X POST http://localhost:8081/v2/ai/chat/interrupt \
  -H 'Content-Type: application/json; charset=UTF-8' \
  --data-binary @/tmp/edge2.json
# 预期: 400 + supplement is required
```

### 5. 回归
```bash
cat > /tmp/regress-req.json <<'EOF'
{"input":"你好","conversationId":"regress-001","user_id":"regress_tester"}
EOF
curl -sN -X POST http://localhost:8081/v2/ai/chat \
  -H 'Content-Type: application/json; charset=UTF-8' \
  --data-binary @/tmp/regress-req.json \
  > /tmp/regress.txt 2>&1
# 预期: 200 + SSE 流，agent 正常响应（done 事件在慢 LLM 模型下可能丢失，pre-existing 问题）
```

### 6. MySQL state 落盘验证

```bash
# 查询 agentscope_sessions 表，确认 interrupt 后 state_data 被更新
ssh root@116.148.120.160 "docker exec mymysql mysql -uroot -plwj052607 -e \
  'SELECT session_id, CHAR_LENGTH(state_data) as state_len, updated_at \
   FROM agentscope.agentscope_sessions \
   WHERE session_id LIKE \"%interrupt-e2e%\" \
   ORDER BY updated_at DESC;'"
# 预期: 能看到 interrupt-e2e-XXX 行，state_len > 0

# 查看 state_data 内容（含 recovery msg + supplement）
ssh root@116.148.120.160 "docker exec mymysql mysql -uroot -plwj052607 -e \
  'SELECT LEFT(state_data, 2000) as state_preview \
   FROM agentscope.agentscope_sessions \
   WHERE session_id = \"interrupt_tester:interrupt-e2e-001\";'"
# 预期: context 数组含 [user_msg, recovery_msg "I noticed that you have interrupted me...", supplement_msg, ...]
# 注：MySQL CLI 终端可能不显示中文（显示为 ??），但 state_len 字节数正常说明 UTF-8 字节已落盘
```

---

## 风险与限制

| 风险 / 限制 | 影响 | 应对 |
|---|---|---|
| `handleInterrupt` 的 recovery msg 是英文硬编码 | 中文 UX 略突兀 | 接受现状（功能性不影响）；后续若框架开放 hook 钩子，再考虑中文化或定制 |
| 框架 `checkInterrupted()` 只在 ReAct 迭代边界检查 | 慢 LLM 模型 + 重中间件场景下，interrupt 可能需要 60-180s 才生效（中间件如 `MemoryMaintenanceMiddleware` 做 SSH 文件操作 + LLM consolidation，单次可达 60-90s） | 超时从 30s 调到 180s；超时后不再 504 而是 fall-through 启动 resume stream（`callSerializationKey` 串行化排队），用户感知是"立即响应" |
| 多次连续 interrupt | `inFlightCalls` 已被取走后第二次 interrupt 直接走"新 stream"分支 | 无害，但前端应禁用按钮直到当前 interrupt 流结束 |
| `InterruptControl.userMessage` 不注入 context | interrupt body 里的 supplement 仅做审计，实际驱动 LLM 的是后续 `chatStreamService.stream(chatReq)` 的 input | 文档明确说明：supplement 同时作为 `InterruptControl.userMessage`（审计）+ `ChatRequest.input`（驱动 LLM） |
| 主 agent 在 `agent_spawn` 等待子 agent 期间被打断 | 见下方"子 agent 续跑限制"章节 | 当前不支持子 agent 内部续跑，见专门章节 |
| 慢 LLM 模型下 resume stream 的 `done` 事件可能丢失 | SSE_TIMEOUT 600s 触发后 doOnCancel 才发 done，但客户端可能已断开 | pre-existing 问题，非 interrupt 引入；客户端应基于"流关闭"而非"done 事件"判断完成 |
| 中文请求体必须用 UTF-8 编码文件发送 | bash on Windows 默认 locale 非 UTF-8，inline 中文字符串会被以非 UTF-8 编码发送，LLM 收到乱码 | 所有 curl 请求用 `--data-binary @file`，文件用 heredoc 写入（保证 UTF-8）；Content-Type 加 `charset=UTF-8` |

---

## 限制：子 agent 内部续跑不支持（重要）

### 用户期望场景
```
Supervisor (意图识别 + 参数提取)
  │ agent_spawn(query_data)
  ▼
query_data 子智能体 [跑到一半:已选 skill,已构造参数,已收到部分结果]
  ↑ 用户想在这里打断,补充"改成按产品线"
  ↓ 期望:接着 query_data 内部状态继续
Supervisor <- query_data 返回
```

### 当前架构做不到的原因

从 `SubagentRegistrar.java:189-243` 看子 agent 构建：
```java
HarnessAgent.Builder sub = HarnessAgent.builder()
    .name(id).model(model).workspace(workspace).toolkit(tk)...
    .subagentFactory(... -> sub.build())   // 每次 agent_spawn 都 new 一个临时实例
```

**关键限制**：
1. **子 agent 是临时实例**：每次 `agent_spawn` 创建一个新 `HarnessAgent`，跑完销毁
2. **InterruptControl 是 per-(userId, sessionId) 的**：主 agent 的 sessionId = conversationId；子 agent 有自己的 sessionId（TraceMiner 看到 `sub-uuid` 格式）；调 `agent.interrupt(userId, sessionId)` 只能打断**主 agent session**，不能直接打断子 agent
3. **子 agent AgentState 不持久化**：`saveStateToSession()` 只保存**主 agent** 的 state（context、plan、activatedGroups）；子 agent 的内部进度（已选 skill、已构造参数、收到的 tool 结果）**随实例销毁**
4. **`agent_spawn` 是阻塞调用**：主 agent 在 `wait_async_results` 期间被 interrupt 后，子 agent 仍在后台跑（不在主 agent 的 ReAct loop 检查范围内）

### 当前能做的（基于 artifact 的"半续跑"）

如果 `query_data` 子 agent 主动把中间结果写盘（CSV/JSON 经 `ArtifactHandoffHook`），中断恢复后：

```
Supervisor 中断 -> saveStateToSession 保存 supervisor context
                  (含 agent_spawn tool_call + artifact 引用)
用户补充信息 -> supervisor 续跑
              -> LLM 看到:之前 spawn 过 query_data,产出 xxx.csv
              -> supervisor 决定:基于 xxx.csv 重新 spawn query_data,
                                 传新指令"读 xxx.csv 继续,改成产品线分组"
              -> 新 query_data 从 xxx.csv 读中间结果 (不是从 SQL 重查)
```

但当前 `query_data` 的 SKILL.md 看起来是"原样透传原始数据"，没有 artifact 持久化中间状态这一步。要做需要：
1. 修改 `query_data` skill,加入"中间结果写 artifact"步骤
2. supervisor prompt 加入"识别已有 artifact 可复用"的指引

### 真正实现子 agent 内部续跑需要的改动（未来方向）

| 改动 | 复杂度 | 说明 |
|---|---|---|
| 子 agent state 持久化到 MySQL | 高 | 给子 agent 也配 stateStore,sessionId 用 `sub-<parentConvId>-<subUuid>` |
| 子 agent 中断 API | 中 | `agent.interrupt(userId, subSessionId, msg)` - 框架 API 已有,需要 REST 端点暴露 |
| 子 agent 恢复 API | 高 | 中断后重新激活子 agent 实例,从 MySQL 加载 state,继续 ReAct 循环 - **框架当前不支持**,需要重写 `subagentFactory` 让它能复用持久化状态 |
| `agent_spawn` 阻塞语义改造 | 中 | 当前 `wait_async_results` 阻塞等子 agent 完成,需要支持"子 agent 中断后立即返回主 agent" |

**当前 Plan B 不包含这部分改动**，作为后续架构升级单独立项。Plan B 落地后能解决"主流程被打断 + 用户补充信息续跑"的体验，子 agent 内部续跑作为已知限制记录在案。

---

## 不做的事（明确跳过）

- ❌ 不修改框架 JAR 的 `handleInterrupt()` 中文化 recovery msg
- ❌ 不做"无 user msg 的空 resume 触发"端点（`/v2/ai/chat` 本身就是 resume 入口）
- ❌ 不做 `/pause` 后自动注入 `InterruptControl.userMessage` 到 context（绕过框架路径,风险高）
- ❌ 不做子 agent 内部状态持久化（架构升级,见"限制"章节）
- ❌ 不改 `HarnessA2aRunnerV2`（`getAgent().getDelegate()` 链路已通）
- ❌ 不改 `V2ChatStreamServiceImpl` 的 SSE 事件映射逻辑（新 endpoint 复用现有 stream()）

---

## 关联文档与记忆

- `interactive-pause-resume-plan.md` - 前序方案（debug 双端点路径，已被本方案替代）
- `framework-highlights-verification.md` §4.1 - 缺口来源
- memory/async_tool_middleware_wiring - AsyncToolMiddleware + WorkspaceMessageBus 的 30s 异步 offload 模式，interrupt 与之共存
- memory/plan_state_cross_restart_verified - plan_mode_context 跨 JVM 重启恢复，interrupt/resume 同样依赖 `agentscope_sessions.state_data` 持久化
- memory/permission_mode_endpoint - PermissionMode 切换端点（DebugController 同款 pattern），interrupt 端点风格对齐
- memory/subagent_hook_chain_migration - 子 agent hooks 不继承主 agent，interrupt 不影响子 agent 已挂的 hook 链
