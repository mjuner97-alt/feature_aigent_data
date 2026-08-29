# Artifact 租户隔离修复方案

## 日期：2026-07-23

## 问题描述

用户 `1322` 在前端发起分析请求时，子 agent `analyze_data` 的工具调用出现三个问题：

### 问题 1：子 agent 写入跨桶路径被误拦截

```
🔧 调用工具：write_file
"Written to /__forbidden__/1322/sub-414e9705-..."

🔧 调用工具：python_exec
python_exec denied by PythonExecAccessMiddleware: cross-tenant artifact path in code
  (user=1322 task=sub-414e9705-...) path=/workspace/artifacts/analyze_q1
```

**根因**：子 agent 的 `sessionId` 被框架改为 `sub-<uuid>`（`AgentSpawnTool.execLocalSync` 生成），
`ArtifactContext.from(RuntimeContext)` 用这个 `sub-xxx` 作为 `taskBucket`，导致：
- `allowedPrefix` = `/workspace/artifacts/1322/sub-414e9705-.../`
- 而 LLM 生成的路径或 `ArtifactHandoffHook` 保存的 CSV 路径可能指向主 conversation 的桶
- 两边桶不一致，被跨租户保护误拦截

### 问题 2：工具返回的 MD 表格数据没有自动落到用户会话下的 artifacts

**根因**：`ArtifactHandoffHook` 在 V2InfraConfig 中用 `new ArtifactHandoffHook(artifactStore)` 构造，
`fixedContext=null`。子 agent 运行时 `ArtifactContext.from(ctx)` 用子 agent 的 `sessionId=sub-xxx`
生成 taskBucket，CSV 写到了子 agent 的隔离桶 `/workspace/artifacts/1322/sub-xxx/`。
后续工具（python_exec 等）的 `allowedPrefix` 也是 `/workspace/artifacts/1322/sub-xxx/`，
CSV 应该能读到。但日志显示 LLM 在 python_exec 中写了一个不完整的路径
`/workspace/artifacts/analyze_q1`（缺少 user/task 中间目录），被跨租户保护拦截。

**实际上 CSV 确实落盘了**，只是 LLM 后续引用路径不正确。

### 问题 3：list_file 等工具没有用户隔离

**根因**：`ArtifactAccessMiddleware` 只拦截 `read_file`、`write_file`、`shell_execute` 三个工具。
`list_files` 是框架内置工具，不在拦截列表中。如果 `list_files` 列出 `/workspace/artifacts/` 下
所有用户的文件，就有隔离泄漏风险。

## 根因分析

### 核心 bug：子 agent sessionId 导致 taskBucket 不一致

```
主 agent 请求流:
  V2ChatStreamServiceImpl.stream()
    → RuntimeContext(userId="1322", sessionId="<conversationId>")
    → ArtifactContext(userBucket="1322", taskBucket="<conversationId>")
    → allowedPrefix = /workspace/artifacts/1322/<conversationId>/

子 agent 请求流:
  AgentSpawnTool.execLocalSync()
    → RuntimeContext(userId="1322", sessionId="sub-414e9705-...")
    → ArtifactContext(userBucket="1322", taskBucket="sub-414e9705-...")
    → allowedPrefix = /workspace/artifacts/1322/sub-414e9705-.../
```

问题链：
1. `ArtifactHandoffHook`（fixedContext=null）用子 agent 的 RuntimeContext 生成 ArtifactContext，
   CSV 写到 `sub-xxx` 桶
2. `ArtifactAccessMiddleware` 和 `PythonExecAccessMiddleware`（也是 fixedContext=null）用子 agent 的
   RuntimeContext，`allowedPrefix` 也是 `sub-xxx` 桶
3. 所以 CSV 落盘 → 跨桶检查 这条链在子 agent 内部是一致的
4. **但** 主 agent 的 `allowedPrefix` 是 `<conversationId>` 桶，它无法读到子 agent 写到 `sub-xxx` 桶的 CSV
5. **更严重的是** LLM 在 python_exec 中写了不完整路径 `/workspace/artifacts/analyze_q1`，
   缺少 user/task 中间目录，被跨桶保护拦截

### v1 的解决方案

v1 的 `ArtifactHandoffHook` 和 `ArtifactAccessMiddleware` 用 **fixedContext**（钉住外层请求的桶），
确保子 agent 的 artifact 路径与主 agent 一致。测试用例
`ArtifactHandoffHookTest.java:224-232` 验证了这一点：

```java
// 模拟框架给子 agent 绑定不同的 sessionId
hook.setRuntimeContext(RuntimeContext.builder()
    .sessionId("sub-9999-abcd").build());
// pinned bucket 必须胜过子 agent ctx
assertTrue(text.contains("/alice/task_unit/"));
assertFalse(text.contains("sub-9999-abcd"));
```

但 v2 的 `V2InfraConfig` 没有传 fixedContext：

```java
// V2InfraConfig.java:189
return new ArtifactHandoffHook(artifactStore);  // fixedContext = null !!

// V2InfraConfig.java:162
return new ArtifactAccessMiddleware(artifactStore);  // fixedContext = null !!

// V2InfraConfig.java:175
return new PythonExecAccessMiddleware(artifactStore);  // fixedContext = null !!
```

### v2 的解决思路

v2 不能用固定的 fixedContext（因为多用户并发，每个请求的 user/task 桶不同）。
但可以借鉴 v1 的思路：**在请求开始时（V2ChatStreamServiceImpl）捕获主 agent 的 ArtifactContext，
通过 RuntimeContext 传递给子 agent，让所有中间件用主 agent 的桶**。

方案：在 `RuntimeContext` 中存储一个 `PARENT_ARTIFACT_CONTEXT` key，
指向主 agent 的 `ArtifactContext`。所有中间件和 Hook 优先从该 key 读取，
回退到 `ArtifactContext.from(ctx)`。

## 修复方案

### Fix 1：在 RuntimeContext 中传递主 agent 的 ArtifactContext

**文件**: `V2ChatStreamServiceImpl.java`

在 `stream()` 方法中，创建请求级 `RuntimeContext` 时，把主 agent 的 `ArtifactContext` 存入：

```java
// 在 V2ChatStreamServiceImpl.stream() 中，已有：
// ctx.put(ParentEmitterCarrier.class, parentEmitterCarrier);

// 新增：存储主 agent 的 ArtifactContext
ArtifactContext mainCtx = new ArtifactContext(
    sanitize(userId),   // userBucket
    sanitize(sessionId) // taskBucket = conversationId
);
ctx.put(ArtifactContext.class, mainCtx);
```

**文件**: `SubagentRegistrar.java` 或框架的 `AgentSpawnTool`

框架的 `AgentSpawnTool.execLocalSync` 通过 `RuntimeContext.builder(ctx).from(ctx)` 克隆
主 agent 的 RuntimeContext 给子 agent。由于我们用 `ctx.put(ArtifactContext.class, mainCtx)`
存储，`from(ctx)` 会自动复制这个 key 到子 agent 的 RuntimeContext。

所以子 agent 的 `RuntimeContext` 中会有 `ArtifactContext` key 指向主 agent 的桶，
即使 `sessionId` 被改成了 `sub-xxx`。

### Fix 2：所有中间件和 Hook 优先从 RuntimeContext 读取 ArtifactContext

**文件**: `ArtifactAccessMiddleware.java`

当前 `resolveContext(ctx)` 方法：
```java
private ArtifactContext resolveContext(RuntimeContext ctx) {
    if (fixedContext != null) {
        return fixedContext;
    }
    return ArtifactContext.from(ctx);
}
```

修改为：
```java
private ArtifactContext resolveContext(RuntimeContext ctx) {
    if (fixedContext != null) {
        return fixedContext;
    }
    // 优先从 RuntimeContext 读取主 agent 钉住的 ArtifactContext
    // （解决子 agent sessionId=sub-xxx 导致的跨桶问题）
    if (ctx != null) {
        ArtifactContext pinned = ctx.get(ArtifactContext.class);
        if (pinned != null) {
            return pinned;
        }
    }
    return ArtifactContext.from(ctx);
}
```

**文件**: `PythonExecAccessMiddleware.java` — 同样修改 `resolveContext(ctx)`

**文件**: `ArtifactHandoffHook.java`

当前 `onEvent()` 方法用 `ArtifactContext.from(ctx)` 构建 context。
修改为优先从 RuntimeContext 读取：

```java
return HookRuntimeContext.resolve()
    .map(ctx -> {
        // 优先从 RuntimeContext 读取主 agent 钉住的 ArtifactContext
        ArtifactContext pinned = ctx.get(ArtifactContext.class);
        ArtifactContext artifactCtx = (pinned != null) ? pinned : ArtifactContext.from(ctx);
        applyHandoff(post, toolName, table, artifactCtx);
        return event;
    })
    .switchIfEmpty(Mono.fromSupplier(() -> {
        if (runtimeContext != null) {
            ArtifactContext pinned = runtimeContext.get(ArtifactContext.class);
            ArtifactContext artifactCtx = (pinned != null) ? pinned : ArtifactContext.from(runtimeContext);
            applyHandoff(post, toolName, table, artifactCtx);
        } else {
            log.warn("...");
        }
        return event;
    }));
```

### Fix 3：ArtifactAccessMiddleware 增加 list_files 拦截

**文件**: `ArtifactAccessMiddleware.java`

增加 `list_files` 工具的拦截，对返回结果中的路径进行过滤，只保留当前用户桶内的文件。
但 `list_files` 是框架内置工具，中间件无法直接修改其返回结果（MiddlewareBase 只能修改 ActingInput，不能修改 ToolResultBlock）。

**替代方案**：在 `ArtifactAccessMiddleware.onActing()` 中，拦截 `list_files` 的调用，
将其 `path` 参数修正为用户桶路径（如果 LLM 传了 artifacts 根路径，替换为用户的桶路径）。

```java
private static final String LIST_FILES_TOOL = "list_files";
private static final String GLOB_TOOL = "glob";

// 在 onActing 中增加：
} else if (LIST_FILES_TOOL.equals(toolName) || GLOB_TOOL.equals(toolName)) {
    replacement = enforceListPath(toolUse, artifactCtx, artifactsRoot, allowedPrefix);
}
```

`enforceListPath` 方法：如果 `path` 参数以 `artifactsRoot` 开头但不以 `allowedPrefix` 开头，
替换为 `allowedPrefix`；如果 `path` 就是 `artifactsRoot`，也替换为 `allowedPrefix`。

### Fix 4：LLM 生成不完整路径的问题

日志显示 LLM 在 python_exec 中写了 `/workspace/artifacts/analyze_q1` 这样不完整的路径。
这不是代码 bug，而是 LLM 的幻觉问题。两个改进方向：

**方向 A**：在 `analyze_data.md` 的 system prompt 中明确告诉 LLM：
> 你必须使用 ArtifactHandoffHook 返回的完整 CSV 路径（如 `/workspace/artifacts/1322/xxx/query_quality-uuid.csv`），
> 不要自己拼接路径。如果路径不完整，python_exec 会被安全中间件拦截。

**方向 B**：在 `PythonExecAccessMiddleware` 中增加路径修正逻辑——当 python_exec 代码中的
路径以 `artifactsRoot` 开头但不包含 user/task 桶前缀时，自动插入正确的桶前缀。
这类似于 `ArtifactAccessMiddleware.enforceShellExecute` 中对 `working_directory` 的路径修正。

方向 B 更健壮（不依赖 LLM 行为），推荐实施。

## 修改文件清单

| # | 文件 | 修改内容 |
|---|------|----------|
| 1 | `V2ChatStreamServiceImpl.java` | 在 `stream()` 中 `ctx.put(ArtifactContext.class, mainArtifactCtx)` |
| 2 | `ArtifactAccessMiddleware.java` | `resolveContext()` 优先从 `ctx.get(ArtifactContext.class)` 读取；增加 `list_files`/`glob` 拦截；python_exec 路径修正 |
| 3 | `PythonExecAccessMiddleware.java` | `resolveContext()` 优先从 `ctx.get(ArtifactContext.class)` 读取；增加路径修正（不完整路径自动插入桶前缀） |
| 4 | `ArtifactHandoffHook.java` | `onEvent()` 优先从 RuntimeContext 读取 pinned ArtifactContext |
| 5 | `ToolCallContentRepairMiddleware.java` | 已修复（content null 问题），无需额外改动 |
| 6 | `analyze_data.md` (可选) | prompt 中明确告诉 LLM 使用完整 CSV 路径 |

## 优先级

- **P0（必须修复）**: Fix 1 + Fix 2 — 子 agent 的 artifact 桶不一致是根因
- **P1（建议修复）**: Fix 4 方向 B — python_exec 不完整路径自动修正
- **P2（后续）**: Fix 3 — list_files 拦截（需评估框架限制）
- **P3（可选）**: Fix 4 方向 A — prompt 强化

## 验证方法

1. 发起分析请求，检查 `write_file` 路径是否为 `/workspace/artifacts/1322/<conversationId>/xxx`
2. 检查 `python_exec` 不再被跨租户保护拦截
3. 检查 `ArtifactHandoffHook` 的 CSV 保存路径与 `ArtifactAccessMiddleware` 的 `allowedPrefix` 一致
4. 检查主 agent 能在最终报告中看到子 agent 产出的数据
5. 切换不同用户 ID 验证隔离性：用户 A 的 artifact 不能被用户 B 访问