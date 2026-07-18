# 12 Shadow Override 定制点提取清单

> 生成日期：2026/07/13
> 升级分支：`upgrade/2.0.0-RC5-dual-track`
> 用途：逐个 diff 12 个 shadow override 文件与 v1 jar 原版，提取定制点清单，为迁移到 v2 扩展形态（middleware / 子类 / SkillVisibilityFilter / Builder 配置 / 业务包保留）做准备
> 关联文档：`UPGRADE_PLAN.md` §3.2 / §3.3 / §3.4、`business-modules-migration-checklist.md`

---

## 阶段 1 删除摘要（2026/07/13 更新）

阶段 1 因 v2 API 不兼容，5 个 shadow override 提前删除（原计划阶段 2/6/9 删除）：

| 文件 | 行数 | 原计划删除阶段 | 实际删除阶段 | 删除原因（v2 API 不兼容）| 定制点保留 |
|---|---|---|---|---|---|
| `core/ReActAgent.java` | 1906 | 阶段 9 | 阶段 1 | 引用 v2 已删的 `StructuredOutputCapableAgent` / `PendingToolRecoveryHook` / `core.plan.*` / `core.session.*` / `core.state.{AgentMetaState,SessionKey,StatePersistence,ToolkitState}` / `PlanNotebook` | 1 处 SHADOW PATCH（L384-390 跳过 strict validation）-- 通过 v2 `enablePendingToolRecovery(boolean)` Builder 替代，阶段 9 验证 |
| `harness/agent/hook/SubagentsHook.java` | 302 | 阶段 6 | 阶段 1 | 引用 v2 已删的 `DefaultTaskRepository` | 无实质定制（原版复制）-- v2 `SubagentsMiddleware` 替代，阶段 6 验证 |
| `harness/agent/hook/MemoryFlushHook.java` | 100 | 阶段 2 | 阶段 1 | `MemoryFlushManager.offloadMessages` 签名新增 `RuntimeContext` 参数；引用 `StructuredOutputCapableAgent` | 禁用 PostCall LLM 抽取 + 同步 offloadMessages -- v2 `MemoryConfig.flushTrigger=THROTTLED` 替代，阶段 2 验证 |
| `harness/agent/hook/MemoryMaintenanceHook.java` | 202 | 阶段 2 | 阶段 1 | `MemoryConsolidator.consolidate` 签名新增 `RuntimeContext` 参数 | LAST_RUN_AT 改静态（节流 bug 修复）-- v2 `MemoryMaintenanceMiddleware` 是否已修复待验证，阶段 2 评估 |
| `harness/agent/tool/AgentSpawnTool.java` | 590 | 阶段 6 | 阶段 1 | `DefaultAgentManager.createAgent` / `TaskRepository.putTask` 签名新增 `RuntimeContext` 参数 | timeout 30s->600s（remote MySQL cold connect 修复）+ MAX_SPAWN_DEPTH=3 -- 通过 v2 Builder 注入，阶段 6 验证 |

**阶段 1 已删 5 个文件合计 3100 行**（占 12 文件 4421 行的 70%）。剩余 7 个文件（3 memory + 3 Docker + 1 AgentSpecLoader）保留，按原计划阶段 2/4/6 删除。

**定制点保留说明**：5 个已删除文件的定制点已在本文件后续章节完整记录，后续阶段按需通过 v2 middleware / builder 选项 / 业务子类重新实现。删除文件本身不丢失定制信息，因为：(1) 大部分定制点已记录在本文件；(2) git 历史可追溯；(3) 后续阶段实施时按本文件验证 v2 等价性。

---

## 总览

12 个 shadow override 文件合计约 4421 行（含 ReActAgent 1906 行）。按文件大小+功能分 4 组：

| 组 | 文件 | 行数 | 二开标记 | 状态 |
|---|---|---|---|---|
| memory | EpisodicResult.java | 31 | 无（原版复制）| ⬜ 保留（阶段 2 删除）|
| memory | EpisodicMemory.java | 91 | 无（原版复制）| ⬜ 保留（阶段 2 删除）|
| memory | MemoryFlushHook.java | 100 | **有**（明注定制）| 🗑️ 阶段 1 已删除 |
| memory | MemoryProvider.java | 139 | 无（原版复制）| ⬜ 保留（阶段 2 删除）|
| memory | MemoryMaintenanceHook.java | 202 | **有**（明注定制）| 🗑️ 阶段 1 已删除 |
| sandbox | DockerCliRunner.java | 151 | 无明显标记 | ⬜ 保留（阶段 4 删除）|
| sandbox | DockerSandboxClient.java | 228 | **有**（明注定制）| ⬜ 保留（阶段 4 删除）|
| sandbox | DockerSandbox.java | 397 | **有**（配合 shared-container）| ⬜ 保留（阶段 4 删除）|
| subagent+tool | AgentSpecLoader.java | 284 | 无（原版复制）| ⬜ 保留（阶段 6 删除）|
| subagent+tool | SubagentsHook.java | 302 | 无（原版复制）| 🗑️ 阶段 1 已删除 |
| subagent+tool | AgentSpawnTool.java | 590 | **有**（SHADOW PATCH 明注）| 🗑️ 阶段 1 已删除 |
| ReActAgent | ReActAgent.java | 1906 | **有**（1 处 SHADOW PATCH）| 🗑️ 阶段 1 已删除 |

**关键发现**：12 个文件中只有 5 个有明确二开定制标记（MemoryFlushHook / MemoryMaintenanceHook / DockerSandboxClient / DockerSandbox / AgentSpawnTool / ReActAgent），其中 ReActAgent 1906 行只有 1 处 SHADOW PATCH（L384-390），其余 1900+ 行上游原版。其余 6 个文件是上游原版复制（删除后 v2 jar 同名类直接生效）。

**阶段 1 删除影响**：5 个已删除文件中，3 个有定制（MemoryFlushHook / MemoryMaintenanceHook / AgentSpawnTool），1 处 SHADOW PATCH（ReActAgent），1 个原版复制（SubagentsHook）。定制点已记录在后续章节，后续阶段按需通过 v2 middleware / builder 重新实现。

---

## 阶段 2 实施摘要（2026/07/13 更新）

> 阶段 2 精简版已实施完成。

**已完成**：
- `MysqlAgentStateStore` 挂载到 `HarnessA2aRunnerV2`（Spring `@Primary` DataSource 自动注入）
- `MemoryConfig` 切小模型（从 glm-5.2 切到 light-classifier/qwen3:8b，降低 flush/consolidation token 成本）
- `KnownEntities` 从 `harness/tools/` 移到 `v2/tools/`

**推迟到阶段 3+**（原阶段 2 计划项有跨阶段依赖）：

| 推迟项 | 阻塞依赖 | 解除阶段 |
|---|---|---|
| EpisodicRetrievalMiddleware | `MySqlEpisodicMemory` 依赖 `EmbeddingClient` | 阶段 3 |
| 3 个 shadow override 删除（EpisodicMemory / EpisodicResult / MemoryProvider）| `MySqlEpisodicMemory` 实现 `EpisodicMemory` 接口 | 阶段 3 |
| ResponseCacheMiddleware | 依赖 `SkillSynthesisRunner` / `FingerprintCalculator` / `DimensionStateManager` | 阶段 3+5 |
| MemoryDigestionService 对接 | 依赖 `SkillDistiller` / `SkillVectorIndex` / `FingerprintCalculator` | 阶段 3 |

---


### 1.1 EpisodicResult.java（31 行）-- 原版复制

- **文件职责**：episodic memory 检索结果 record（sessionId / snippet / relevance / timestamp），implements `State`
- **二开标记**：无
- **定制点**：无实质定制，与 v2 jar 同名类一致
- **迁移目标**：迁移到 `com.agentscopea2a.agent.memory.EpisodicResult`（业务包，阶段 2 删除 shadow override）
- **v2 扩展形态**：业务包保留
- **迁移阶段**：阶段 2 删除
- **风险**：低 -- 纯数据 record，无依赖

### 1.2 EpisodicMemory.java（91 行）-- 原版复制

- **文件职责**：Episodic memory 接口，extends `MemoryProvider`，提供 `recordSession` / `search` / `getSession` 三个方法
- **二开标记**：无（注释提到 "Design Reference: Hermes Agent SessionDB with FTS5" 是上游注释）
- **定制点**：无实质定制
- **迁移目标**：迁移到 `com.agentscopea2a.agent.memory.EpisodicMemory`（业务包，阶段 2 删除 shadow override）
- **v2 扩展形态**：业务包保留
- **迁移阶段**：阶段 2 删除
- **风险**：低 -- 纯接口定义

### 1.3 MemoryProvider.java（139 行）-- 原版复制

- **文件职责**：Memory provider 接口，定义 7 个方法：`getName` / `systemPromptBlock` / `prefetch` / `syncTurn` / `onPreCompress` / `onSessionEnd` / `getToolObjects`
- **二开标记**：无
- **定制点**：无实质定制
- **迁移目标**：迁移到 `com.agentscopea2a.agent.memory.MemoryProvider`（业务包，阶段 2 删除 shadow override）
- **v2 扩展形态**：业务包保留
- **迁移阶段**：阶段 2 删除
- **风险**：低 -- 纯接口定义

### 1.4 MemoryFlushHook.java（100 行）-- **有二开定制** -- 🗑️ 阶段 1 已删除

> **删除日期**：2026/07/13
> **删除原因**：v2 API 不兼容 -- `MemoryFlushManager.offloadMessages` 签名新增 `RuntimeContext` 参数；引用 v2 已删的 `StructuredOutputCapableAgent`
> **定制点保留**：本节定制点清单已完整记录，阶段 2 通过 v2 `MemoryConfig.flushTrigger=THROTTLED` + 业务 `EpisodicRetrievalMiddleware` 重新实现

- **文件职责**：Hook，PostCall 时 offload inline messages 到 workspace
- **二开标记**：L35-40 明注："本地影子覆盖 JAR 中的 MemoryFlushHook（classpath 优先）"
- **定制点**：

| 行号 | 定制点描述 | v2 扩展形态 | 迁移阶段 |
|---|---|---|---|
| L37-38 | **禁用 PostCall LLM 抽取**：原版每次响应都一次完整 LLM 往返做 flushMemories 抽取；二开改为长期回忆由 `EpisodicLongTermMemoryAdapter`（MySQL FTS）承担 | v2 `MemoryConfig.flushTrigger=THROTTLED` 节流 + 业务 `EpisodicRetrievalMiddleware`（onSystemPrompt）| 阶段 2 |
| L39-40 | **offloadMessages 同步执行**：必须在 sandbox 调用上下文存活期间完成，否则 `WorkspaceManager.getFilesystem()` 在请求结束后抛 "No active sandbox" | 验证 v2 是否已修复 sandbox 生命周期；若未修复则业务 middleware 保留同步语义 | 阶段 2 |
| L60-65 | PostCallEvent 触发 `offloadInline` | `onAgent` middleware 替代 | 阶段 2 |
| L72-99 | `offloadInline` 实现：从 `reActAgent.getMemory()` 取 live messages，调用 `MemoryFlushManager.offloadMessages(live, agentId, sessionId)` | 业务 middleware 自管 | 阶段 2 |
| L68-70 | priority=5 | middleware 顺序配置 | 阶段 2 |

- **迁移目标**：v2 `MemoryConfig` + 业务 `EpisodicRetrievalMiddleware` 替代
- **迁移阶段**：阶段 2 删除
- **风险**：中 -- sandbox 调用上下文存活期间必须完成 offload，需验证 v2 是否已修复

### 1.5 MemoryMaintenanceHook.java（202 行）-- **有二开定制** -- 🗑️ 阶段 1 已删除

> **删除日期**：2026/07/13
> **删除原因**：v2 API 不兼容 -- `MemoryConsolidator.consolidate` 签名新增 `RuntimeContext` 参数
> **定制点保留**：本节定制点清单已完整记录（LAST_RUN_AT 静态节流修复是关键），阶段 2 评估 v2 `MemoryMaintenanceMiddleware` 是否已修复 per-instance 节流 bug，若未修复则业务 middleware 保留静态 LAST_RUN_AT

- **文件职责**：Hook，PostCall 时按 30 分钟节流跑 memory 维护（expire daily files / consolidate / prune old sessions）
- **二开标记**：L37-41 明注："本地影子覆盖 JAR 中的 MemoryMaintenanceHook（classpath 优先）"
- **定制点**：

| 行号 | 定制点描述 | v2 扩展形态 | 迁移阶段 |
|---|---|---|---|
| L37-41 | **LAST_RUN_AT 改为 JVM 静态变量**：原版 per-instance，每次构建 HarnessAgent 创建新 hook 实例，lastRunAt 永远重置为 EPOCH，30 分钟节流形同虚设；二开改静态后节流为 JVM 全局，父 supervisor 与 subagent 共享同一时钟 | 验证 v2 `MemoryMaintenanceMiddleware` 是否已修复 per-instance 节流 bug；若未修复则业务 middleware 保留静态 LAST_RUN_AT | 阶段 2 |
| L50-51 | `static AtomicReference<Instant> LAST_RUN_AT` | 同上 | 阶段 2 |
| L82-106 | PostCallEvent + CAS 节流 + fire-and-forget 异步执行 `runMaintenance` | `onAgent` middleware 替代 | 阶段 2 |
| L108-114 | `runMaintenance`：expireDailyFiles + consolidateMemory + pruneOldSessions | 业务 middleware 自管 或 v2 `MemoryMaintenanceMiddleware` | 阶段 2 |
| L116-151 | `expireDailyFiles`：glob `*.md` in MEMORY_DIR，解析文件名日期，过期移到 archive/ | 业务 middleware 保留 | 阶段 2 |
| L153-162 | `consolidateMemory`：调用 `MemoryConsolidator.consolidate()` | v2 `MemoryConfig.consolidationPrompt` 替代或业务 middleware 保留 | 阶段 2 |
| L164-193 | `pruneOldSessions`：glob `*.log.jsonl` in AGENTS_DIR，按 modifiedAt 过期删除 | 业务 middleware 保留 | 阶段 2 |

- **迁移目标**：v2 `MemoryMaintenanceMiddleware`（如已修复节流）或业务 middleware 保留静态节流
- **迁移阶段**：阶段 2 删除
- **风险**：中 -- 需验证 v2 是否已修复 per-instance 节流 bug；若未修复需保留定制

### 1.6 memory 组总结

- **5 个文件中 2 个已在阶段 1 删除**（MemoryFlushHook / MemoryMaintenanceHook，因 v2 API 不兼容提前删除），3 个原版复制保留（EpisodicMemory / EpisodicResult / MemoryProvider，阶段 2 删除）
- **阶段 2 删除前需完成**（针对剩余 3 个原版复制文件）：
  1. 业务 EpisodicMemory / EpisodicResult / MemoryProvider 接口迁移到 `com.agentscopea2a.agent.memory.*`
  2. 删除 3 个 shadow override，让 v2 jar 同名类直接生效（这 3 个文件无实质定制，删除无风险）
- **阶段 2 已删除文件（阶段 1 提前删除）的定制点恢复**：
  1. 验证 v2 `MemoryConfig.flushTrigger=THROTTLED` 是否覆盖 MemoryFlushHook 的禁用 LLM 抽取需求
  2. 验证 v2 sandbox 生命周期是否已修复 "No active sandbox" 问题
  3. 验证 v2 `MemoryMaintenanceMiddleware` 是否已修复 per-instance 节流 bug（关键 -- 二开 LAST_RUN_AT 静态修复若 v2 未等价则需业务 middleware 保留）
- **与 v2 双层记忆的潜在冲突**：v1 EpisodicMemory（跨 session 业务事实）与 v2 `MEMORY.md`（per-session 上下文压缩）-- 明确分工，前缀标注来源（`[Episodic]` vs `[MEMORY.md]`）

---

## 二、sandbox 组定制点（3 文件，~776 行）

### 2.1 DockerCliRunner.java（151 行）-- 无明注定制标记

- **文件职责**：Docker CLI 命令执行器，支持 local 模式（直接 `docker` 命令）和 remote 模式（`ssh` + remote docker）
- **二开标记**：无"// 本地影子覆盖"注释，但提供了 `configure(remoteEnabled, sshTarget, sshOptions, timeoutSeconds)` 方法支持 SSH 远端 Docker -- 这是二开为内网多副本部署加的
- **定制点**：

| 行号 | 定制点描述 | v2 扩展形态 | 迁移阶段 |
|---|---|---|---|
| L23 | 静态 `Config` 槽，支持运行时 `configure(...)` | 验证 v2 是否支持 SSH remote；若支持则删除，若不支持保留为业务包 | 阶段 4 |
| L27-38 | `configure(remoteEnabled, sshTarget, sshOptions, timeoutSeconds)` -- SSH 远端模式配置 | 同上 | 阶段 4 |
| L107-126 | `command(dockerArgs)` -- local 模式直接 `docker`，remote 模式 `ssh -o BatchMode=yes + sshOptions + sshTarget + remote docker cmd` | 同上 | 阶段 4 |
| L135-141 | `remoteDockerCommand` -- 把 docker args 拼成 ssh 远端命令字符串 | 同上 | 阶段 4 |
| L143-145 | `sh(s)` -- shell 转义 | 同上 | 阶段 4 |

- **迁移目标**：评估 v2 是否已支持 SSH remote Docker；若支持则删除 shadow override，若不支持则保留为业务包
- **v2 扩展形态**：v2 `DockerSandboxClientOptions` 扩展或业务包独立 `DockerCliRunner`
- **迁移阶段**：阶段 4
- **风险**：中 -- SSH remote 模式是内网多副本部署的关键，v2 是否等价支持需验证

### 2.2 DockerSandboxClient.java（228 行）-- **有二开定制**

- **文件职责**：`SandboxClient` 实现，`create` / `resume` / `delete` / `serializeState` / `deserializeState`
- **二开标记**：L21-53 明注："Local override of upstream DockerSandboxClient that fixes USER-scope container reuse AND adds shared-single-container support"
- **定制点**：

| 行号 | 定制点描述 | v2 扩展形态 | 迁移阶段 |
|---|---|---|---|
| L29-33 | **修复上游 bug 1**：上游 `objectMapper.readValue(json, SandboxState.class)` 但 `SandboxState` 是 abstract，Jackson 无法实例化；二开改为反序列化到 `DockerSandboxState` 再返回 base type | 验证 v2.0.0-RC5 是否已修复；若已修复则删除，若未修复保留为子类 | 阶段 4 |
| L34-39 | **修复上游 bug 2**：序列化 JSON 含 `NoopSandboxSnapshot.id` 等实现字段，反序列化时 abstract-base deserializer 报 unknown properties；二开配置 `FAIL_ON_UNKNOWN_PROPERTIES=false` | 同上 | 阶段 4 |
| L41-46 | **新增 shared-container 模式**（2026-06-13）：`setSharedContainerName` 设置后，所有 `create` 通过 `docker inspect` 解析 containerId，设 `containerOwned=false`（shutdown 跳过 stop/rm），配合 `IsolationScope.GLOBAL` 把整个 fleet 折叠到一个长生命周期容器 | 验证 v2 `IsolationScope.GLOBAL` 是否等价；若不等价保留为业务子类 | 阶段 4 |
| L58-74 | `sharedContainerName` 静态配置槽，boot 时从 `FilesystemConfig` 设置一次（`DockerSandboxClient` 由 harness 反射构造，无构造函数注入 Spring properties）| 业务子类保留静态配置 | 阶段 4 |
| L78-84 | 构造函数配置 `FAIL_ON_UNKNOWN_PROPERTIES=false` + 注册 `HarnessSandboxJacksonModule` | 业务子类保留 | 阶段 4 |
| L91-149 | `create` 方法：shared-container 短路逻辑（L120-144）+ 正常 create 逻辑 | 业务子类保留 | 阶段 4 |
| L151-168 | `resolveContainerId(name)` -- `docker inspect -f {{.Id}} name` 解析容器 ID | 业务子类保留 | 阶段 4 |
| L170-202 | `resume` 方法：shared-container 模式下重新解析 containerId（防止 operator 重建容器后 stale id）| 业务子类保留 | 阶段 4 |
| L210-227 | `serializeState` / `deserializeState`：用配置好的 ObjectMapper | 业务子类保留 | 阶段 4 |

- **迁移目标**：阶段 4 删除 shadow override，定制点迁到 `InternalDockerSandboxClient` 子类
- **v2 扩展形态**：`DockerSandboxClient` 子类 + 静态配置槽
- **迁移阶段**：阶段 4
- **风险**：高 -- shared-container 是关键性能优化（冷启动节省），v2 `IsolationScope.GLOBAL` 是否等价需验证

### 2.3 DockerSandbox.java（397 行）-- **有二开定制**

- **文件职责**：`AbstractBaseSandbox` 实现，`start` / `shutdown` / `doExec` / `doPersistWorkspace` / `doHydrateWorkspace` / `doSetupWorkspace` / `doDestroyWorkspace` / `doEnsureContainerRunning` / `createAndStartContainer` / `buildDockerRunCommand`
- **二开标记**：无"// 本地影子覆盖"注释，但与 `DockerSandboxClient` 配合实现 shared-container 模式
- **定制点**：

| 行号 | 定制点描述 | v2 扩展形态 | 迁移阶段 |
|---|---|---|---|
| L59-62 | `shutdown` 检查 `dockerState.isContainerOwned()`，若 false 跳过 stop/rm -- 配合 shared-container 模式 | 业务子类保留 | 阶段 4 |
| L218-235 | `doEnsureContainerRunning`：containerId 已存在则 inspect 状态，RUNNING 直接返回，STOPPED 重启，UNKNOWN 创建新的 | 验证 v2 是否等价；若不等价保留为子类 | 阶段 4 |
| L277-332 | `buildDockerRunCommand`：支持 memory/cpu/ports/network/additionalRunArgs/bind mounts | v2 原生 `DockerFilesystemSpec` Builder 已支持 | 阶段 4 |
| L334-350 | `inspectContainerState`：`docker inspect -f {{.State.Running}}` | 同上 | 阶段 4 |

- **迁移目标**：阶段 4 删除 shadow override，定制点迁到 `InternalDockerSandbox` 子类
- **v2 扩展形态**：v2 `DockerSandbox` 子类
- **迁移阶段**：阶段 4
- **风险**：中 -- shared-container 短路逻辑需保留

### 2.4 sandbox 组总结

- **3 个文件都有定制**（DockerCliRunner SSH remote / DockerSandboxClient 2 bug 修复 + shared-container / DockerSandbox 配合 shared-container）
- **阶段 4 删除前需完成**：
  1. 验证 v2.0.0-RC5 是否已修复 `SandboxState` abstract 反序列化 bug
  2. 验证 v2.0.0-RC5 是否已修复 `NoopSandboxSnapshot.id` unknown properties bug
  3. 验证 v2 `IsolationScope.GLOBAL` 是否等价于二开 shared-container 模式
  4. 验证 v2 是否支持 SSH remote Docker
  5. 若 v2 不等价，保留为 `InternalDockerSandbox` + `InternalDockerSandboxClient` + 独立 `DockerCliRunner` 子类
- **v1 内网定制（registry / CLI 审计 / 命令白名单 / 连接池 / 鉴权 / 健康检查）**：实际代码中没看到这些定制（UPGRADE_PLAN.md §3.2.4 提到），可能在 `SandboxProperties` / `application-sandbox-*.properties` 配置中，阶段 4 进一步确认

---

## 三、subagent+tool 组定制点（3 文件，~1176 行）

### 3.1 AgentSpecLoader.java（284 行）-- 原版复制

- **文件职责**：从 workspace `subagents/` 目录加载 Markdown + YAML front matter 格式的 `SubagentDeclaration`
- **二开标记**：无
- **定制点**：无实质定制
- **迁移目标**：v2 `harness.agent.subagent.AgentSpecLoader` 替代
- **v2 扩展形态**：v2 原生
- **迁移阶段**：阶段 6 删除
- **风险**：低 -- 上游原版复制，删除后 v2 jar 同名类生效

### 3.2 SubagentsHook.java（302 行）-- 原版复制 -- 🗑️ 阶段 1 已删除

> **删除日期**：2026/07/13
> **删除原因**：v2 API 不兼容 -- 引用 v2 已删除的 `DefaultTaskRepository`
> **定制点保留**：无实质定制（原版复制），v2 `SubagentsMiddleware` 替代，阶段 6 验证

- **文件职责**：Hook，PreReasoning 时注入 subagent 使用指南到 system prompt + 异步任务摘要
- **二开标记**：无（注释提到 "In default mode (standalone HarnessAgent), this hook creates an AgentSpawnTool..." 是上游注释）
- **定制点**：无实质定制
- **迁移目标**：v2 `SubagentsMiddleware` 替代
- **v2 扩展形态**：v2 原生
- **迁移阶段**：阶段 6 删除
- **风险**：低 -- 上游原版复制

### 3.3 AgentSpawnTool.java（590 行）-- **有二开定制** -- 🗑️ 阶段 1 已删除

> **删除日期**：2026/07/13
> **删除原因**：v2 API 不兼容 -- `DefaultAgentManager.createAgent` / `TaskRepository.putTask` 签名新增 `RuntimeContext` 参数
> **定制点保留**：本节定制点清单已完整记录，关键定制为 timeout 30s->600s（remote MySQL cold connect 修复）+ MAX_SPAWN_DEPTH=3；阶段 6 通过 v2 Builder 注入 `timeoutSeconds` / `maxSpawnDepth` 重新实现

- **文件职责**：子 agent 编排工具（`agent_spawn` / `agent_send` / `agent_list`），sync/async + remote/local 多分支
- **二开标记**：L73-74 明注："SHADOW PATCH: upstream 30s too short - remote MySQL cold connect + slow SQL trips `Timeout on blocking read for 30000000000 NANOSECONDS`. Raise default to 600, max to 3600."
- **定制点**：

| 行号 | 定制点描述 | v2 扩展形态 | 迁移阶段 |
|---|---|---|---|
| L73-76 | **SHADOW PATCH**：upstream 30s too short，remote MySQL cold connect + slow SQL 触发 `Timeout on blocking read for 30000000000 NANOSECONDS`；二开 `DEFAULT_TIMEOUT_SECONDS=600` / `MAX_TIMEOUT_SECONDS=3600` | 验证 v2 是否已调整 timeout；若未调整则 Builder 注入或子类保留 | 阶段 6 |
| L77 | `MAX_SPAWN_DEPTH = 3` -- 子 agent 最大 spawn 深度（防递归爆栈）| Builder 注入 | 阶段 6 |
| L153-159 / L162 / L166-170 / L468-477 | `System.err.println` 调试日志多处（agentSpawn / execLocalSync 流式/非流式分支）| 删除调试日志或改用 `log.debug` | 阶段 6 |
| L93-97 | `SpawnedAgent` record + `agentsByKey` / `labelToKey` ConcurrentHashMap（per-tool 实例状态）| 验证 v2 `DefaultAgentManager` 是否已管理；若未管理业务子类保留 | 阶段 6 |
| L107-116 | 构造函数：`agentManager` / `taskRepository` / `parentSpawnDepth` / `userIdSupplier` | v2 Builder 注入 | 阶段 6 |
| L118-270 | `agent_spawn` 工具：sync/async 分支（timeoutMs==0 走 TaskRepository async）+ remote/local 分支 | v2 `AgentSpawnTool` 替代 | 阶段 6 |
| L196 | `resolveTimeoutMs(timeoutSeconds, DEFAULT_TIMEOUT_SECONDS)` -- 默认 600s | 同 L73-76 | 阶段 6 |
| L272-415 | `agent_send` 工具：follow-up message，sync/async + remote/local 分支 | v2 `AgentSpawnTool` 替代 | 阶段 6 |
| L417-434 | `agent_list` 工具：列出 active subagents | 同上 | 阶段 6 |
| L440-510 | `execLocalSync`：`SubagentEventBus` 流式转发（Reactor Context 传播）+ 非 stream 回退到 `invokeAgent` | v2 流式转发原生支持（`SubagentExposedEvent`）| 阶段 6 |
| L512-530 | `buildChildSource`：构造子 agent `EventSource`（path = `parentSessionId/agentId`，depth, agentKey, sessionId）| v2 原生或业务子类 | 阶段 6 |
| L532-571 | `runRemoteSync`：remote task 通过 `TaskRepository` 提交 + `bgTask.waitForCompletion(timeoutMs)` 阻塞等待 | v2 原生 | 阶段 6 |
| L573-581 | `resolveTimeoutMs`：timeout 上限 `MAX_TIMEOUT_SECONDS=3600` | 同 L73-76 | 阶段 6 |

- **迁移目标**：v2 `AgentSpawnTool` 替代，定制点通过 Builder 注入
- **v2 扩展形态**：v2 原生 + Builder 注入 `timeoutSeconds` / `maxSpawnDepth`
- **迁移阶段**：阶段 6 删除
- **风险**：中 -- timeout 调整（30s -> 600s）是关键修复（remote MySQL cold connect），v2 是否已调整需验证；`SubagentEventBus` 流式转发依赖 Reactor Context 传播，v2 是否等价支持需验证

### 3.4 subagent+tool 组总结

- **3 个文件中 2 个已在阶段 1 删除**（SubagentsHook / AgentSpawnTool，因 v2 API 不兼容提前删除），1 个原版复制保留（AgentSpecLoader，阶段 6 删除）
- **阶段 6 删除前需完成**（针对剩余 AgentSpecLoader）：
  1. 验证 v2 `harness.agent.subagent.AgentSpecLoader` 是否等价（原版复制，预期等价）
  2. 删除 shadow override，让 v2 jar 同名类直接生效
- **阶段 6 已删除文件（阶段 1 提前删除）的定制点恢复**（针对 AgentSpawnTool）：
  1. 验证 v2 `AgentSpawnTool` 是否已调整 default timeout（30s -> 600s）-- remote MySQL cold connect 关键修复
  2. 验证 v2 `SubagentEventBus` 流式转发是否等价（Reactor Context 传播）
  3. 验证 v2 `DefaultAgentManager` 是否管理 `agentsByKey` / `labelToKey` 状态
  4. 删除调试 `System.err.println` 日志，改用 `log.debug`
  5. `MAX_SPAWN_DEPTH=3` 通过 Builder 注入

---

## 四、ReActAgent 定制点（1 文件，1906 行）-- **仅 1 处 SHADOW PATCH** -- 🗑️ 阶段 1 已删除

### 4.1 ReActAgent.java（1906 行）-- 1900+ 行上游原版 + 1 处定制 -- 🗑️ 阶段 1 已删除

> **删除日期**：2026/07/13
> **删除原因**：v2 API 不兼容 -- 引用 v2 已删的 `StructuredOutputCapableAgent` / `PendingToolRecoveryHook` / `core.plan.*` / `core.session.*` / `core.state.{AgentMetaState,SessionKey,StatePersistence,ToolkitState}` / `PlanNotebook`
> **定制点保留**：仅 1 处 SHADOW PATCH（L384-390 跳过 strict validation），通过 v2 `enablePendingToolRecovery(boolean)` Builder 替代，阶段 9 验证等价性

- **文件职责**：ReAct agent 主循环（reasoning + acting + 工具调用 + Hook 集成 + PlanNotebook + Memory + Session）
- **二开标记**：L386-388 明注："SHADOW PATCH: skip strict validation; allow caller-provided tool results to pass through without raising on duplicate/missing IDs (PendingToolRecoveryHook will patch up gaps during PreCallEvent)."
- **Grep 全文扫描结果**：1906 行中只有 1 处 SHADOW PATCH（L384-390），其余 1900+ 行与上游一致

### 4.2 定制点清单

| 行号 | 定制点描述 | v2 扩展形态 | 迁移阶段 |
|---|---|---|---|
| L364-401 | `doCall` 方法：处理 pending tool calls 的 3 个分支（无 pending / 有 pending 无 input / 有 pending + input）| v2 `ReActAgent` 原生 | 阶段 9 |
| L384-390 | **SHADOW PATCH**：当 user provided tool results 时，跳过 `validateAndAddToolResults(msgs, pendingIds)` 严格验证（避免 duplicate/missing IDs 报错），直接走 `acting(0)` 或 `executeIteration(0)`；PendingToolRecoveryHook 会在 PreCallEvent 时补缺 | v2 `enablePendingToolRecovery(boolean)` Builder 替代（v2 已将 PendingToolRecoveryHook 标 `@Deprecated`，改为 Builder 配置）| 阶段 9 |
| 其他 1900+ 行 | 上游原版方法：`call` / `stream` / `saveTo` / `loadFrom` / `reasoning` / `acting` / `summarizing` / `notifyHooks` / `handleInterrupt` / Builder 等 | v2 `ReActAgent` 原生 | 阶段 9 |

### 4.3 Builder 方法清单（上游原版，阶段 9 删除后改用 v2 Builder）

| v1 Builder 方法 | v2 替代 | 备注 |
|---|---|---|
| `memory(Memory)` | `stateStore(AgentStateStore)` | 会话历史放 `AgentState.context` |
| `statePersistence(StatePersistence)` | `stateStore(AgentStateStore)` | StatePersistence 已删 |
| `structuredOutputReminder(...)` | 删除，模型层原生 | StructuredOutputCapableAgent 已删 |
| `planNotebook(PlanNotebook)` | `HarnessAgent.Builder.enablePlanMode()` | 8 工具 -> 3 工具 |
| `hook(Hook)` / `hooks(List)` | `middleware(MiddlewareBase)` / `middlewares(List)` | Hook 标 `@Deprecated` |
| `skillBox(SkillBox)` | `skillRepository(AgentSkillRepository)` | SkillBox 标 `@Deprecated` |
| `longTermMemory(...)` / `longTermMemoryMode(...)` | `MemoryConfig.builder().flushPrompt(...).consolidationPrompt(...)` | LongTermMemory 标 `@Deprecated` |
| `checkRunning(boolean)` | 删除（已忽略）| |
| `enableMetaTool(boolean)` | v2 `MetaToolFactory` | |
| `enablePendingToolRecovery(boolean)` | 同名 Builder（v2 保留）| **对应 L384-390 SHADOW PATCH** |
| `knowledge(Knowledge)` / `knowledges(List)` / `ragMode(RAGMode)` / `retrieveConfig(RetrieveConfig)` | `@Deprecated`，v2 重写中 | |

### 4.4 ReActAgent 组总结

- **1906 行中只有 1 处 SHADOW PATCH**（L384-390），定制点远比想象中少
- **阶段 1 已删除**：因 v2 API 大面积不兼容（`StructuredOutputCapableAgent` / `PendingToolRecoveryHook` / `core.plan.*` / `core.session.*` / `core.state.*` / `PlanNotebook` 全部删除），无法编译，提前删除
- **阶段 9 删除前需完成**（针对 1 处 SHADOW PATCH 的 v2 等价验证）：
  1. 验证 v2 `enablePendingToolRecovery(boolean)` Builder 是否等价于 L384-390 的 SHADOW PATCH（跳过 strict validation + PendingToolRecoveryHook 补缺）
  2. 验证 v2 `ReActAgent` 的 per-(userId, sessionId) 隔离是否覆盖二开当前依赖
  3. 验证 v2 `InterruptControl` 是否覆盖二开当前的中断处理（`handleInterrupt`）
  4. 验证 v2 `AgentState` 4 子上下文（PermissionContextState / ToolContextState / TaskContextState / PlanModeContextState）是否覆盖二开当前的状态管理
  5. 业务定制（维度状态注入 / PlanNotebook 集成 / Hook 调用点）迁到 v2 middleware
- **风险**：低 -- 实际定制点只有 1 处，远比 UPGRADE_PLAN.md §3.4 描述的"1906 行定制"简单。但需逐方法验证 v2 等价性

---

## 五、阶段汇总

| 阶段 | 计划删除文件 | 实际删除情况 | 删除前提 |
|---|---|---|---|
| 阶段 1（实际）| - | 5 个文件提前删除（ReActAgent / SubagentsHook / MemoryFlushHook / MemoryMaintenanceHook / AgentSpawnTool）| v2 API 不兼容，无法编译 |
| 阶段 2 | 3 个 memory 文件（EpisodicMemory / EpisodicResult / MemoryProvider）| 待删 | 验证 v2 `MemoryConfig` + sandbox 生命周期 + `MemoryMaintenanceMiddleware` 节流；业务 EpisodicMemory 迁移到业务包 |
| 阶段 4 | 3 个 Docker 文件（DockerCliRunner / DockerSandbox / DockerSandboxClient）| 待删 | 验证 v2 `IsolationScope.GLOBAL` + SSH remote 支持 + 2 个 bug 修复；内网定制迁到子类 |
| 阶段 6 | 1 个 subagent 文件（AgentSpecLoader）| 待删 | v2 `AgentSpecLoader` 替代（原版复制，预期等价）|
| 阶段 9 | v1 入口 `HarnessA2aRunner` | 待删 | 1906 行定制点全部迁到 v2 middleware（实际只有 1 处 SHADOW PATCH）|

**阶段 1 已删 5 个文件合计 3100 行**（占 12 文件 4421 行的 70%）。剩余 7 个文件（1321 行）按原计划阶段 2/4/6 删除。

---

## 六、关键风险

1. **MemoryFlushHook 同步 offload**：v2 sandbox 生命周期是否已修复 "No active sandbox" 问题 -- 阶段 2 验证
2. **MemoryMaintenanceHook 节流 bug**：v2 `MemoryMaintenanceMiddleware` 是否已修复 per-instance 节流 -- 阶段 2 验证
3. **DockerSandboxClient shared-container**：v2 `IsolationScope.GLOBAL` 是否等价于二开 shared-container 模式 -- 阶段 4 验证（关键性能优化，不等价则保留子类）
4. **DockerCliRunner SSH remote**：v2 是否支持 SSH remote Docker -- 阶段 4 验证
5. **AgentSpawnTool timeout**：v2 是否已调整 default timeout（30s -> 600s）-- 阶段 6 验证（remote MySQL cold connect 关键修复）
6. **AgentSpawnTool SubagentEventBus**：v2 流式转发是否等价 -- 阶段 6 验证
7. **ReActAgent SHADOW PATCH**：v2 `enablePendingToolRecovery(boolean)` 是否等价于 L384-390 跳过 strict validation -- 阶段 9 验证

## 七、12 文件定制点统计

| 文件 | 行数 | 二开定制行数 | 定制占比 |
|---|---|---|---|
| EpisodicResult.java | 31 | 0 | 0%（原版复制）|
| EpisodicMemory.java | 91 | 0 | 0%（原版复制）|
| MemoryProvider.java | 139 | 0 | 0%（原版复制）|
| MemoryFlushHook.java | 100 | ~65（注释 + offloadInline 同步逻辑）| 65% |
| MemoryMaintenanceHook.java | 202 | ~10（LAST_RUN_AT 改静态）| 5% |
| DockerCliRunner.java | 151 | ~50（SSH remote 模式）| 33% |
| DockerSandboxClient.java | 228 | ~100（2 bug 修复 + shared-container）| 44% |
| DockerSandbox.java | 397 | ~10（isContainerOwned 检查）| 3% |
| AgentSpecLoader.java | 284 | 0 | 0%（原版复制）|
| SubagentsHook.java | 302 | 0 | 0%（原版复制）|
| AgentSpawnTool.java | 590 | ~10（timeout 30s->600s）+ 调试日志 | 2% |
| ReActAgent.java | 1906 | ~7（L384-390 SHADOW PATCH）| 0.4% |
| **合计** | **4421** | **~252** | **5.7%** |

**关键洞察**：12 个 shadow override 文件 4421 行中，实际二开定制只有约 252 行（5.7%）。大部分文件是上游原版复制（删除后 v2 jar 同名类直接生效）。真正需要迁移到 v2 扩展形态的定制点集中在：
- memory 组：MemoryFlushHook 同步 offload + MemoryMaintenanceHook 静态节流
- sandbox 组：DockerSandboxClient shared-container + DockerCliRunner SSH remote
- subagent+tool 组：AgentSpawnTool timeout 调整
- ReActAgent：1 处 SHADOW PATCH（PendingToolRecovery 等价）

---

## 八、待办

- [x] 读 AgentSpawnTool.java（590 行）补完 subagent+tool 组
- [x] Grep + 针对性读 ReActAgent.java（1906 行）补完 ReActAgent 组
- [x] 阶段 1：5 个 shadow override 提前删除（ReActAgent / SubagentsHook / MemoryFlushHook / MemoryMaintenanceHook / AgentSpawnTool）
- [ ] 阶段 2 前：验证 v2 `MemoryConfig.flushTrigger=THROTTLED` / sandbox 生命周期 / `MemoryMaintenanceMiddleware` 节流（针对 MemoryFlushHook / MemoryMaintenanceHook 已删除文件的定制点恢复）
- [ ] 阶段 4 前：验证 v2 `IsolationScope.GLOBAL` / SSH remote / 2 个 bug 修复（针对 Docker 组 3 个保留文件）
- [ ] 阶段 6 前：验证 v2 `AgentSpawnTool` timeout / `SubagentEventBus` 流式转发（针对 AgentSpawnTool 已删除文件的定制点恢复）+ 验证 v2 `AgentSpecLoader` 等价（针对 AgentSpecLoader 保留文件）
- [ ] 阶段 9 前：验证 v2 `enablePendingToolRecovery(boolean)` 等价于 L384-390 SHADOW PATCH（针对 ReActAgent 已删除文件的定制点恢复）
