# MEMORY.md 跨租户串扰修复

> 路径：`src/main/resources/docs/Plan-Machie/memory-tenant-isolation-fix.md`
> 关联：`memory-middleware-middle-ground.md`（flush 关闭的延迟收益）；`docs/rc2-to-rc5/interactive-pause-resume-plan.md`（同套 shared-container 多租户场景）
> 日期：2026-07-21

## 背景与动机

### 用户报错现象

用户切换 `?userId=alice` 与 `?userId=bob` 测试多租户隔离时,LLM 回答里反复出现:

```
记忆里已有 Q1 和 Q2 的数据
已有分析报告（记忆）：Q1 均值 14.30、中位数 13.1、标准差 10.13...
根据存量记忆,2026 年 Q1 各部门的缺陷密度数据如下:一部 23.1、二部 13.1...
```

这些数据是**别人历史会话**的产物,被注入到当前用户的 system prompt 或被 `memory_search` 工具捞出来。切换 userId 不解决——LLM 仍然看到跨用户记忆。

### 紧接前一次修复

[[episodic_memory_user_id_isolation]] 已经修了 `EpisodicRetrievalMiddleware` 全表 FTS 无 `user_id` 过滤的问题 (加列 + backfill + WHERE)。但用户测下来发现 "新 userId 还是不行,他是直接用记忆工具搜的" —— episodic 修了,`MEMORY.md` 这层还有两处独立的 shared-root 串扰源。

## 根因(两个独立串扰源)

### 串扰源 1:根 `MEMORY.md`(system prompt 注入路径)

框架 `WorkspaceContextMiddleware.onSystemPrompt` 调 `workspaceManager.readMemoryMd(rc)`:

```java
// WorkspaceManager.java
public String readMemoryMd(RuntimeContext rc) {
    return readWithOverride(rc, MEMORY_MD);  // MEMORY_MD = "MEMORY.md"
}

private String readWithOverride(RuntimeContext rc, String relativePath) {
    String fsContent = readTextThroughFilesystem(rc, relativePath);  // filesystem 层(namespaced)
    if (!fsContent.isEmpty()) return fsContent;
    return readFileQuietly(workspace.resolve(relativePath));  // ★ fallback 到 shared 根
}
```

- filesystem 层(RemoteFilesystem)按 `IsolationScope` namespaced,新 session 通常为空
- **fallback 到本地 `workspace.resolve("MEMORY.md")` —— 这是 SHARED 根文件**
- 项目 `.agentscope/workspace/harness-a2a/MEMORY.md` 是聚合文件 (3436 字节,含 "Q1 均值 14.30" 等所有用户的合并内容)
- 该内容注入到每个用户的 `<memory_context>` block
- per-user `memory/<userId>/MEMORY.md` 文件 `MemoryHydrator` 在写,但 `readMemoryMd(rc)` 读的是根 `MEMORY_MD` 常量,**从不读 per-user 路径**

### 串扰源 2:根 `memory/<date>.md`(memory_search 工具路径)

框架 `MemoryFlushMiddleware`(默认 `FlushMode.ALWAYS`)每次 call 结束把 LLM 总结的 memory 写到:

- 容器 `/workspace/MEMORY.md`(根)
- 容器 `/workspace/memory/<date>.md`(根 daily ledger)

shared-container 模式下,容器 `/workspace/memory/` bind-mount 到远端 docker host `/opt/agentscope-workspace/harness-a2a/memory/` —— **所有用户共享同一个目录**。

`memory_search` 工具调 `workspaceManager.listMemoryFilePaths(rc)` 列文件,返回 shared 根目录所有 daily `.md`。bob 调 `memory_search` 看到 alice 的 `2026-07-21.md` 里 "一部 23.1、二部 13.1..." 条目。

实测 bob 的 `memory_search` 返回:

```
Found 10 matches:
Source: memory/2026-07-14.md#166: # 📋 2026年Q1 vs Q2 各部门质量分对比分析方案优化版
Source: memory/2026-07-18.md#21: - 2026年Q1杭州开发一部与二部缺陷密度差值:一部23.1,二部13.1,差值10.0
Source: memory/2026-07-21.md#16: - 直接以存量记忆中的Q1缺陷密度数据(一部23.1、二部13.1、三部3.1、四部6.1、五部26.1)回复用户
...
```

10 条全是别人的会话记录。

## 修复方案

**核心思路:** 框架的 `WorkspaceManager` / `MemoryFlushMiddleware` / `memory_search` 都假设 workspace 是单租户的 —— 根文件即用户文件。shared-container 多租户场景下这个假设破裂。

修法三步,缺一不可:

1. **删根 `MEMORY.md`** —— 阻断 `readMemoryMd` 的 shared-root fallback
2. **项目层 middleware 从 DB 注入 per-user 内容** —— 接管 system prompt 的 memory 注入
3. **`FlushTrigger.never()`** —— 禁框架的 shared-root 写入,否则下次 call 又写回去

### 改动文件

#### 1. `v2/middleware/PerUserMemoryContextMiddleware.java`(新增)

```java
public class PerUserMemoryContextMiddleware implements MiddlewareBase {
    private static final String SECTION_HEADER = "## 用户记忆 (Per-User Memory)";
    private final MysqlMemoryStore mysqlMemoryStore;

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String systemPrompt) {
        return Mono.fromCallable(() -> {
            String userId = ctx != null ? ctx.getUserId() : null;
            if (userId == null || userId.isBlank()) return systemPrompt;  // 宁可无召回也不要跨租户泄漏

            String body;
            try {
                body = mysqlMemoryStore
                        .read(userId, MysqlMemoryStore.KIND_MEMORY_MD, "MEMORY.md")
                        .orElse("");
            } catch (Exception e) {
                return systemPrompt;  // DB 读失败静默降级
            }
            if (body == null || body.isBlank()) return systemPrompt;

            String base = systemPrompt != null ? systemPrompt : "";
            String separator = base.isEmpty() || base.endsWith("\n") ? "" : "\n";
            return base + separator + SECTION_HEADER + "\n" + body.strip();
        });
    }
}
```

运行在 `WorkspaceContextMiddleware` 之前(user middlewares 先注册先执行),不冲突 —— 框架的 `<memory_context>` block 因根文件删除变空,per-user 内容由这个 middleware 注入。

#### 2. `v2/config/V2MemoryConfig.java`

新增 `@Bean`:

```java
@Bean
@ConditionalOnProperty(prefix = "harness.a2a.memory.mysql-mirror",
        name = "enabled", havingValue = "true", matchIfMissing = false)
public PerUserMemoryContextMiddleware perUserMemoryContextMiddleware(MysqlMemoryStore mysqlMemoryStore) {
    return new PerUserMemoryContextMiddleware(mysqlMemoryStore);
}
```

`@ConditionalOnProperty` 是关键 —— 没 mysql-mirror 就没 per-user 数据源,不注册。

#### 3. `v2/config/HarnessAgentPartsConfig.java`

`harnessMiddlewares` 加 `ObjectProvider<PerUserMemoryContextMiddleware>` 参数,放在 `sessionMiddleware` 之后、`memoryLedgerMirror` 之前。

#### 4. `v2/runner/HarnessA2aRunnerV2.java`

```java
.memory(MemoryConfig.builder()
        .model(smallModel)
        .consolidationMinGap(Duration.ofDays(365))  // 已有:跳过 19s consolidation
        .flushTrigger(MemoryConfig.FlushTrigger.never())  // ★ 新增:禁 per-call flush
        .build())
```

关掉框架 per-call flush,阻止 `MemoryFlushMiddleware` 写 shared 根 `MEMORY.md` + `memory/<date>.md`。`MemoryMaintenanceMiddleware` 仍注册(只是 `consolidationMinGap=365d` 让它跳过 consolidation),daily 文件保留/清理逻辑不受影响。

per-user memory 持久化由 `MemoryLedgerMirrorMiddleware`(local file + DB mirror)+ `MemoryHydrator`(DB → local file)接管。

### 一次性运维操作(清理污染源)

```bash
# 本地 host 根 MEMORY.md
rm .agentscope/workspace/harness-a2a/MEMORY.md

# 远端 docker host(容器 mount 源)
ssh docker-host "rm -f /opt/agentscope-workspace/harness-a2a/MEMORY.md \
                       /opt/agentscope-workspace/harness-a2a/memory/2026-*.md"

# 容器内(防 projectionRoots 再投)
ssh docker-host "docker exec agentscope-shared-demo rm -f /workspace/MEMORY.md \
                                                       /workspace/memory/2026-*.md"

# 清理容器 /workspace/ 根下陈旧 csv/py/png
ssh docker-host 'docker exec agentscope-shared-demo bash -c "cd /workspace && rm -f *.csv *.py *.png nul \"D:\\\\*\""' 

# 清空被污染用户的 episodic 记忆(之前看到 shared daily 文件后产生的幻觉会话)
ssh docker-host "mysql -h 127.0.0.1 -u root -p<pwd> agentscope -e \
  \"DELETE FROM QualitySupervisor_episodic_memory WHERE user_id IN ('alice_tester','bob_tester')\""
```

第 5 步容易漏 —— bob 的某次会话看到根 daily 文件后幻觉 "根据存量记忆 一部 23.1..." 这条幻觉被记到 bob 的 episodic 里,下次会话 `EpisodicRetrievalMiddleware` 会注入为 "## 历史参考案例",LLM 继续幻觉。必须清掉。

## 框架源码核实(agentscope-harness-2.0.0-RC5-sources.jar)

### `WorkspaceManager.readMemoryMd` 的 fallback 路径

```java
// WorkspaceManager.java:257
public String readMemoryMd(RuntimeContext rc) {
    return readWithOverride(rc, MEMORY_MD);  // MEMORY_MD = "MEMORY.md"
}

// WorkspaceManager.java:824
private String readWithOverride(RuntimeContext rc, String relativePath) {
    String fsContent = readTextThroughFilesystem(rc, relativePath);
    if (!fsContent.isEmpty()) return fsContent;
    return readFileQuietly(workspace.resolve(relativePath));  // ★ shared root fallback
}
```

### `MemoryFlushMiddleware` 的写入路径

```java
// HarnessAgent.java:2141
inner.middleware(new MemoryFlushMiddleware(
        wsManager,
        memoryModel,
        effectiveFlushPrompt,
        memoryConfig.flushTrigger(),  // ← 这里传 FlushTrigger
        effectiveIsolationScope));
```

`MemoryConfig.FlushTrigger` 有三档:

```java
public enum FlushMode {
    ALWAYS,   // 每次 call 后 flush(默认)
    NEVER,    // 完全跳过 per-call flush(offload 仍跑)
    THROTTLED // 每 minGap 至多一次
}
```

`NEVER` 模式下 middleware 仍注册但不执行 flush 逻辑 —— `MemoryMaintenanceMiddleware` 的 daily 文件保留/清理不受影响。

### `IsolationScope` 的 namespace 映射

```java
// IsolationScope.java:102
case USER -> rc -> {
    String uid = rc.getUserId();
    if (uid != null && !uid.isBlank()) return List.of(uid);
    String sid = rc.getSessionId();
    return (sid != null && !sid.isBlank()) ? List.of(sid) : List.of();
};
case SESSION -> rc -> {
    String sid = rc.getSessionId();
    return (sid != null && !sid.isBlank()) ? List.of(sid) : List.of();
};
```

`V2SandboxConfig` 默认 `IsolationScope.SESSION` —— filesystem 层按 session namespace。但 `MemoryFlushMiddleware` 写的是**本地文件系统**(非 namespaced filesystem),所以 isolation scope 对它无效,根文件总是 shared。

## E2E 验证(2026/07/21 PASS)

### alice_tester 发 "分析 2026 年 Q1 各部门质量分趋势"

```
✓ 系统提示无 "记忆里已有" / "Q1 均值 14.30" / "历史参考案例"
✓ LLM 直接派单 query_data 子智能体查数据库
✓ 返回真实数据 (一部 23.1、二部 13.1、三部 3.1、四部 6.1、五部 26.1)
```

### bob_tester(清空 episodic 后)发同样问题

```
✓ 系统提示无 alice 的数据
✓ 无 "根据存量记忆" 幻觉
✓ LLM 请求重发(curl 中文编码问题),无跨用户数据泄漏
```

### 远端 docker host 验证

```
✓ /opt/agentscope-workspace/harness-a2a/MEMORY.md 不存在
✓ /opt/agentscope-workspace/harness-a2a/memory/ 目录空(无 daily .md 重新生成)
✓ 容器 /workspace/MEMORY.md 不存在
✓ 容器 /workspace/ 根下只有 AGENTS.md + 结构目录 (memory/ skills/ knowledge/ 等)
✓ 无陈旧 csv/py/png
```

## 与 `memory-middleware-middle-ground.md` 的关系

`memory-middleware-middle-ground.md` 提出的 "中方案" 是 `.flushTrigger(FlushTrigger.never())` —— 当时动机是**性能**(省 8s flush LLM 调用)。

本修复**复用了同一个旋钮**,但动机变成**租户隔离**(阻止 shared-root 写入)。两份文档指向同一行代码改动,收益叠加:

| 维度 | 中方案(性能) | 本修复(隔离) |
|---|---|---|
| 改动 | `.flushTrigger(FlushTrigger.never())` | 同一行 |
| 收益 1 | 单轮响应 ~5s(省 8s flush) | — |
| 收益 2 | — | 跨用户 daily .md 不再串扰 |
| 配套 | 无 | 删根 MEMORY.md + PerUserMemoryContextMiddleware |

## 限制与已知边界

### 1. `memory_save` 工具的写入路径未变

LLM 主动调 `memory_save` 工具时,框架仍会尝试写到容器 `/workspace/MEMORY.md`。实测 `FlushTrigger.never()` 只禁 per-call flush,`memory_save` 工具内部仍写。

当前测试场景下 LLM 没调 `memory_save`(用户问的是查询,不是偏好/决策),所以没触发。若后续 LLM 调 `memory_save`,根 `MEMORY.md` 会被重新创建。

**应对方案**(若未来需要):
- shadow-override `MemorySaveTool` 重定向到 per-user 路径
- 或在 `MemoryLedgerMirrorMiddleware` 里加 hook,检测到根 `MEMORY.md` 写入后立即删掉

### 2. `memory_search` 工具仍读 shared 根

LLM 调 `memory_search` 时,框架 `listMemoryFilePaths(rc)` 仍会列 shared 根 `memory/` 目录。只是因为 daily `.md` 不再被 `MemoryFlushMiddleware` 创建,目录为空,工具返回 "No matching memories found"。

per-user 记忆访问通过:
- system prompt 的 `## 用户记忆` 段(本 middleware 注入)
- `episodic_search` 工具(已 per-user 化,见 [[episodic_memory_user_id_isolation]])

`memory_search` 工具本身没改 —— 它仍是 shared-root 语义。当前测试场景下够用,因为 per-user 数据通过另两条路径可达。

### 3. 多 JVM 副本共享同一 MySQL + docker host

本修复假设单 JVM。多 JVM 副本同时写同一 userId 的 `agent_memory` 行时,`MysqlMemoryStore.upsert` 是 `INSERT ... ON DUPLICATE KEY UPDATE`,语义上最后写赢。读侧(`PerUserMemoryContextMiddleware`)每次 call 都现读 DB,所以跨副本一致性 OK。

## 关联记忆

- [[episodic_memory_user_id_isolation]] —— 同期修复的 episodic 跨用户串扰(FTS WHERE user_id 过滤)
- [[async_tool_middleware_wiring]] —— AsyncToolMiddleware + WorkspaceMessageBus 模式,interrupt 与之共存
- [[plan_state_cross_restart_verified]] —— plan_mode_context 跨重启恢复,同样依赖 `agentscope_sessions.state_data` 持久化
- [[frontend_duplication_markdown_fixes]] —— 前端 6 个问题修复(叠字/重复/markdown/工具 IO 折叠)
- [[interrupt_resume_endpoint_verified]] —— Plan B `/v2/ai/chat/interrupt` 单端点
- [[ledger_mirror_middleware_design]] —— `MemoryLedgerMirrorMiddleware` 从事件流捕获响应写入 `agent_memory_ledger`
- [[daily_file_local_write_fix]] —— `MemoryLedgerMirrorMiddleware` 加 `writeLocalDailyFile` bypass sandbox,Windows 8KB CreateProcess 规避
