# Plan: 将 Controller 切回 ChatStreamServiceV_3，并为 V_3Impl 植入新版本核心能力

## Context

当前项目中有两套流式输出服务：
- **ChatStreamServiceImpl (新版)** — 使用 `processChunk/processChunkPublic` 机制，有 `StreamContext` 状态机，支持 `agent.stream(List<Msg>, StreamOptions, ctx)` 增量模式，`SseEmitterCacheUtil` 缓存，`buildErrorMessage` 错误分类。
- **ChatStreamServiceV_3Impl (老版)** — 使用 `sendChunk/sendDone` + `AiChatResult` DTO 的前端兼容格式，SSE event name 区分消息类型，但**缺少**新版的核心能力。

Controller（ChatController）目前所有请求都路由到 `chatStreamService`（ChatStreamServiceImpl），老版本 V3 服务未被使用。

**目标**：Controller 切回 `chatStreamServiceV3`（ChatStreamServiceV_3Impl），同时将新版中以下核心能力植入 V_3Impl：
1. `agent.stream(List.of(userMsg), streamOptions, ctx)` — 增量流模式 + Flux onErrorResume
2. `SseEmitterCacheUtil` 注册 emitter
3. `emitCachedResponse` — 缓存命中回放（CacheHitException 短路，三帧补齐）
4. `buildErrorMessage` — 错误分类（重试耗尽/ModelException 中文提示）
5. `handleStreamSuccess / handleStreamError` — 统一成功/错误处理+DB落库
6. 保留 V3 的 AiChatResult 格式 + SSE event name + extractText/extractThinking 独立提取（不改前端协议）

## 修改步骤

### Step 1: ChatController — 切回 ChatStreamServiceV_3

文件：`ChatController.java`
- 将注入 `ChatStreamService chatStreamService` → 保持两个注入不变，`chat()`/`chatA2A()` 方法路由到 `chatStreamServiceV3.stream()`/`.streamPublic()`
- 保留 `normalizeConversationId()`

### Step 2: ChatStreamServiceV_3Impl — 植入新版核心能力

#### 2.1 添加 imports
- `io.agentscope.core.agent.StreamOptions`
- `io.agentscope.core.model.ModelException`
- `com.agentscopea2a.util.SseEmitterCacheUtil`
- `reactor.core.publisher.Flux`

#### 2.2 stream() 方法升级

新流程：
1. 创建 SseEmitter
2. `normalize(req)` → `Resolved`
3. 生成 ansUUID(V3原有)
4. `SseEmitterCacheUtil.put(conversationId, emitter)` — 新加
5. `buildRuntimeContext(req)` — 已有
6. supervisorService.newCacheHook / supervisorService.build — 已有
7. 配置 `StreamOptions.builder().eventTypes(EventType.ALL).incremental(true).build()`
8. `agent.stream(List.of(userMsg), streamOptions, ctx)`
   - `.onErrorResume(CacheHitException.class, e -> emitCachedResponse(...))`
   - `.subscribe(chunk -> sendChunk(...), err -> handleStreamError(...), () -> handleStreamSuccess(...))`

#### 2.3 sendChunk() — 保留原有 AiChatResult 格式

V3 原有的 `sendChunk` 逻辑基本不变，只是从 lambda 回调路径改为 `processChunk` 风格调用。思考内容（extractThinking）保持原有行为——仅累积不 SSE 下发。

```java
private void processChunk(Event event, SseEmitter emitter, Resolved resolved, 
                          String ansUUID, StringBuilder accumulated, StringBuilder thinkAccumulated) {
    // 复用 V3 原有的 sendChunk 逻辑，不做 ContentBlock 分发变更
    sendChunk(emitter, resolved, event, ansUUID, accumulated, thinkAccumulated);
}
```

#### 2.4 emitCachedResponse() — 缓存命中回放

```java
private Flux<Event> emitCachedResponse(SseEmitter emitter, Resolved resolved, 
                                        String ansUUID, StringBuilder accumulated, String cached) {
    log.info("Cache HIT for /ai/chat");
    accumulated.setLength(0);
    accumulated.append(cached);
    // 三帧补齐：reasoning(空) → text(cached) → done
    sendChunkDirect(emitter, resolved, "reasoning", "", ansUUID, accumulated);
    sendChunkDirect(emitter, resolved, "text", cached, ansUUID, accumulated);
    sendDone(emitter, resolved, ansUUID, accumulated);
    emitter.complete();
    ThreadContextUtils.clearContext();
    saveAnswerIntoDB(req, "", cached);
    return Flux.empty();
}
```

#### 2.5 handleStreamError() — 升级错误消息

```java
private void handleStreamError(SseEmitter emitter, Resolved resolved, String ansUUID,
                                StringBuilder accumulated, StringBuilder thinkAccumulated, 
                                ChatRequest req, Throwable err) {
    // 先检查 CacheHitException（兼容旧有 unwrapCacheHit 路径）
    CacheHitException cacheHit = unwrapCacheHit(err);
    if (cacheHit != null) { ... 原有缓存处理 ... return; }
    
    String msg = buildErrorMessage(err);  // 新加：分类友好提示
    sendError(emitter, resolved, msg);
    emitter.complete();
    ThreadContextUtils.clearContext();
    saveAnswerIntoDB(req, thinkAccumulated.toString(), accumulated.toString());
}
```

#### 2.6 添加 buildErrorMessage()

从 ChatStreamServiceImpl 完整复制。

#### 2.7 saveAnswerIntoDB() 保留原有签名

V3 的 `saveAnswerIntoDB(ChatRequest, String, String)` 方法不变。

## 涉及文件

| 文件 | 修改类型 |
|------|---------|
| `ChatController.java` | 路由切回 V3 |
| `ChatStreamServiceV_3Impl.java` | 升级核心能力 |
| 不需要改 DTO/Entity 文件 | AiChatResult 不变 |

## 不做的变更（保持 V3 原有行为）

- SSE 格式保持 `AiChatResult` + event name
- ThinkingBlock 内容仅累积落库，不 SSE 下发
- `extractText/extractThinking` 保持独立提取
- `normalize()/Resolved` 不动
- `buildRuntimeContext()` 不动
- `mainAgentMapper` 不动

## 验证

1. `mvn compile -pl analysis-project -am` 编译通过
2. SSE 输出格式仍是 AiChatResult（`code/ansUUID/lineResult/resultAll`）
3. 缓存命中时能回放三帧
4. 错误时返回分类中文提示
