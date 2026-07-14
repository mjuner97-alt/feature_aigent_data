# 二开功能迁移检查表

> 生成日期：2026/07/13
> 关联文档：`UPGRADE_PLAN.md` §3.2 九大业务模块挂载清单
> 用途：逐项标注二开功能模块的 v1 文件清单、v2 扩展形态、挂载方式、挂载阶段、验收证据、当前状态，作为阶段 2-6 渐进挂载的可执行跟踪表
> 升级分支：`upgrade/2.0.0-RC5-dual-track`

---

## 阶段 7 实施摘要（2026/07/14 更新）

> 阶段 7 已实施完成，详见 [`stage7-implementation-record.md`](stage7-implementation-record.md)。下方各模块状态已根据实际实施情况更新。

**已完成**：
- MetricClassificationService + SkillCandidate + SkillCandidateRepository + FingerprintCalculator 迁移到 `v2/skills/`
- ToolCallTrackingHook 重构为无参构造函数 + ThreadLocal 模式，接入 HarnessA2aRunnerV2
- SessionMiddleware 创建（替代 v1 SessionHook），接入 HarnessA2aRunnerV2 middlewares 链
- ToolCallCollector 持久化到情景记忆（V2ChatStreamServiceImpl 注入 EpisodicMemory，cleanup 中写入）
- EpisodicMemory 接口新增 `recordSessionWithToolContext()` 方法签名
- V2ToolGroupAdapter 创建（Toolkit + ToolGroup + MetaTool），替代 ToolRoutersIndex 的 flat router_tool 分发
- V2SessionRouter 创建（灰度路由，默认 100% v2）
- BotLoopGuard / IdempotencyStore ADR 文档（确认为 Channel-level，不适用于 A2A runner）

**与计划的偏差**：
1. 灰度切换推迟 -- V2SessionRouter 创建但默认 100% v2，v1 controller 未重新启用，灰度切换推迟到 Stage 8
2. 数据迁移脚本未执行 -- 推迟到 Stage 8（v2 全链路运行时验证通过后）
3. v2 全链路运行时验证未执行 -- 编译验证通过，运行时验证推迟到 Stage 8 启动时

**阶段 7 验收缺口（转入后续阶段）**：MetricClassificationService / FingerprintCalculator / ToolCallTrackingHook / SessionMiddleware / V2ToolGroupAdapter / V2SessionRouter 运行时验证

---

## 阶段 1 实施摘要（2026/07/13 更新）

> 阶段 1 已实施完成，详见 [`stage1-implementation-record.md`](stage1-implementation-record.md)。下方各模块状态已根据实际实施情况更新。

**已完成**：
- pom.xml 升级到 v2.0.0-RC5（含 3 个新增依赖）
- `HarnessA2aRunnerV2` + `V2ChatController` 新建，SSE 冒烟测试通过（28 typed `AgentEvent`）
- v2 builder 装配：workspace + memory + compaction + toolResultEviction + planMode + taskList + pendingToolRecovery
- v2 builder 自动注册标准工具（`read_file` / `write_file` / `edit_file` / `glob` / `grep` / `shell_execute` / `list_files` / `plan_enter` / `plan_write` / `plan_exit` / `task_add` / `task_update` / `task_list` 等）

**与计划的偏差**：
1. dual-track 假设不成立 -- v1 业务代码 10 文件 82 编译错误，Maven compiler 排除 `com/agentscopea2a/{agent,harness,service,controller}/**`
2. 5 个 shadow override 提前删除（`ReActAgent` / `SubagentsHook` / `MemoryFlushHook` / `MemoryMaintenanceHook` / `AgentSpawnTool`，合计 3100 行）
3. v2 jar 构建需兼容层绕过（v1 a2a-client/server jar 作为 2.0.0-RC5、umbrella 空 stub）

**阶段 1 验收缺口（转入后续阶段）**：AGENTS.md 变更验证、工具调用全链路验证、Plan Mode HITL 验证、自定义 middleware / 工具挂载（推迟到阶段 2+）

---

## 状态图例

| 状态 | 含义 |
|---|---|
| ⬜ pending | 未启动 |
| 🔄 in_progress | 进行中 |
| ✅ done | 已完成 |
| ⚠️ blocked | 阻塞中（标注阻塞原因） |
| ❌ skipped | 跳过（标注原因） |
| 🗑️ deleted | shadow override 已删除（标注删除阶段与原因）|

---

## 一、Harness 业务编排（§3.2.1）-- 阶段 1-2

| v1 二开文件 | 行数 | v2 扩展形态 | 挂载方式 | 阶段 | 状态 |
|---|---|---|---|---|---|
| `harness/runner/HarnessA2aRunner.java` | 242 | `HarnessA2aRunnerV2`（基于 `HarnessAgent.builder()`）| 新建 v2 入口，v1 保留 | 阶段 1 | ✅（`com.agentscopea2a.v2.runner.HarnessA2aRunnerV2` 已建；v1 入口代码因 v2 API 不兼容未编译，Maven excludes 排除）|
| `harness/hooks/ArtifactAccessHook.java` | - | `ArtifactAccessMiddleware` | `.middlewares(...)` onActing | 阶段 5 | ✅（`v2/middleware/ArtifactAccessMiddleware` 已创建，onActing 拦截 read_file/write_file/shell_execute 路径参数）|
| `harness/hooks/ArtifactHandoffHook.java` | - | `ArtifactHandoffHook`（保留 Hook API）| `.hook(...)` PostActingEvent | 阶段 5 | ✅（`v2/hooks/ArtifactHandoffHook` 已创建；v2 Middleware 无工具结果后处理钩子，必须用已弃用 Hook API，标记 `@SuppressWarnings("deprecation")`）|
| `harness/hooks/PythonExecRetryHook.java` | - | `PythonExecRetryHook`（保留 Hook API）| `.hook(...)` PostActingEvent | 阶段 6 | ✅（git mv 到 `v2/hooks/`，包声明更新，`@SuppressWarnings("deprecation")`，`V2InfraConfig` 提供 bean，挂载到 `HarnessA2aRunnerV2`）|
| `harness/hooks/ResponseCacheHook.java` | - | `ResponseCacheMiddleware` | `.middlewares(...)` onAgent | 阶段 5 | ✅（`v2/middleware/ResponseCacheMiddleware` 已创建，cache HIT 短路返回合成 AgentResultEvent）|
| `harness/hooks/SkillEvolutionHook.java` | - | `SkillEvolutionMiddleware` | `.middlewares(...)` onAgent | 阶段 3 | ⬜ |
| `harness/hooks/SkillRetrievalHook.java` | - | `SkillRetrievalMiddleware` | `.middlewares(...)` onSystemPrompt | 阶段 3 | ⬜ |
| `harness/hooks/SkillSynthesisHook.java` | - | `SkillSynthesisMiddleware` | `.middlewares(...)` onAgent | 阶段 3 | ⬜ |
| `harness/hooks/ToolCallCollector.java` | - | `ToolCallCollector`（数据持有类，非 Hook/Middleware）| ThreadLocal bind/unbind | 阶段 6 | ✅（git mv 到 `v2/tools/`，包声明更新，由 V2ChatStreamServiceImpl bind/unbind）|
| `harness/hooks/ToolCallTrackingHook.java` | - | `ToolCallTrackingHook`（保留 Hook API）| `.hook(...)` PreActing+PostActing | 阶段 6 | ✅（git mv 到 `v2/hooks/`，重构为无参构造函数 + ThreadLocal 模式，`V2InfraConfig` 提供 bean，`HarnessA2aRunnerV2` 通过 `ObjectProvider` 接线）|
| `agent/hook/SessionHook.java` | - | `SessionMiddleware` | `.middlewares(...)` onActing | 阶段 7 | ✅（`v2/middleware/SessionMiddleware` 已创建，实现 `MiddlewareBase` 接口，清理 tool call input 中的 regex 模式，`V2InfraConfig` 提供 bean，挂载到 `HarnessA2aRunnerV2.middlewares()`）|
| `harness/cache/ResponseCacheService.java` | 288 | `v2/cache/ResponseCacheService` + `v2/middleware/ResponseCacheMiddleware`（onAgent）| `.middlewares(...)` | 阶段 5 | ✅（git mv 到 `v2/cache/`，`V2InfraConfig` 提供 bean，middleware 挂载到 `HarnessA2aRunnerV2`）|
| `harness/config/PersistenceProperties.java` | - | 保留为 `@ConfigurationProperties` | v2 入口注入 | 阶段 2 | ⬜ |
| `harness/config/InfraConfig.java` | - | 重建为 `V2InfraConfig`（v1 在 excluded 包）| Spring `@Configuration` | 阶段 5 | ✅（`V2InfraConfig` 提供 ResponseCacheService/ArtifactStore/middleware/hook beans）|
| `harness/config/SandboxProperties.java` | - | 重建为 `V2SandboxConfig.SandboxPropertiesV2`（v1 在 excluded 包）| `@ConfigurationProperties(prefix = "harness.a2a.sandbox")` | 阶段 4 | ✅ |
| `harness/config/SshHealthCheck.java` | - | 保留 | v2 入口注入 | 阶段 5 | ⬜ |
| `harness/config/PythonExecProperties.java` | - | 重建为 `V2ToolConfig.PythonExecPropertiesV2`（内部类）| `V2ToolConfig` bean | 阶段 6 | ✅（`PythonExecPropertiesV2` 内部类替代，`PythonExecTool` 使用构造函数注入）|
| `harness/config/WorkspaceMaterializer.java` | - | 保留，对接 v2 `WorkspaceSpec` | v2 入口注入 | 阶段 4 | ⬜ |
| `harness/config/FilesystemConfig.java` | - | 重建为 `V2SandboxConfig`（v1 在 excluded 包，`SandboxDistributedOptions` 已删除）| Spring `@Configuration` 注入 | 阶段 4 | ✅（`V2SandboxConfig` 提供 sandbox/distributed/remote filesystem beans）|
| `harness/config/JedisBaseStore.java` | - | **评估删除**（v2 用 `JdbcDistributedStore`，不引入 Redis）| 删除 | 阶段 5 | ⬜ |
| `harness/workspace/RemoteDirSyncer.java` | - | 对接 v2 `RemoteFilesystemSpec` | `.filesystem(...)` | 阶段 5 | ⬜ |
| `harness/workspace/RemoteWorkspaceSyncService.java` | - | 对接 v2 `RemoteFilesystemSpec` | `.filesystem(...)` | 阶段 5 | ⬜ |

**阶段 1 实际验收**：`HarnessA2aRunnerV2` 装配 7 能力（workspace / memory / compaction / toolResultEviction / planMode / taskList / pendingToolRecovery），SSE 冒烟测试通过；stateStore / distributedStore / filesystem / channel / skill 待阶段 2+ 挂载。AGENTS.md 变更验证待做。

---

## 二、记忆处理（§3.2.2）-- 阶段 2（精简版已完成）

> 阶段 2 精简版已实施完成，详见 [`stage2-implementation-record.md`](stage2-implementation-record.md)。原计划 5 项因跨阶段依赖，实际完成 3 项（stateStore 挂载 / MemoryConfig 小模型 / KnownEntities 移位），4 项推迟到阶段 3+。

| v1 二开文件 | v2 扩展形态 | 挂载方式 | 阶段 | 状态 |
|---|---|---|---|---|
| `agent/session/MySQLSession.java` | `MysqlAgentStateStore`（v2 内置）+ Spring `@Primary` DataSource 自动注入 | `.stateStore(new MysqlAgentStateStore(dataSource))` | 阶段 2 | ✅（阶段 2 已挂载）|
| `agent/memory/EpisodicLongTermMemoryAdapter.java` | 重写为 `EpisodicRetrievalMiddleware`（onSystemPrompt）| `.middlewares(...)` | 阶段 3 | ✅（`EpisodicRetrievalMiddleware` 已创建，挂载到 `HarnessA2aRunnerV2.middlewares()`）|
| `agent/memory/MySqlEpisodicMemory.java`（FTS5）| 已移到 `v2/memory/`，保留 FTS5 + 向量检索 | `V2MemoryConfig` Spring bean | 阶段 3 | ✅（git mv 到 `v2/memory/`，package 更新，`V2MemoryConfig` 提供 bean）|
| `agent/memory/MysqlMemoryStore.java` | 保留，作为 v2 `MEMORY.md` 的 MySQL 镜像层（双写）| v2 `MemoryConfig` 配置 | 阶段 2 | ⬜（运行时配置，无代码改动）|
| `agent/memory/MemoryHydrator.java` | 保留，双写同步 | v2 `MemoryConfig` 配置 | 阶段 2 | ⬜（运行时配置，无代码改动）|
| `agent/memory/MemoryFileWatcher.java` | 保留，双写同步 | v2 `MemoryConfig` 配置 | 阶段 2 | ⬜（运行时配置，无代码改动）|
| `agent/memory/EpisodicMemoryConfig.java` | 已移到 `v2/memory/`，保留为业务配置 | `@ConfigurationProperties` via `V2MemoryConfig` | 阶段 3 | ✅（git mv 到 `v2/memory/`，package 更新）|
| `agent/memory/digestion/MemoryDigestionService.java` | 保留，对接 v2 `MemoryMaintenanceMiddleware` 节流闸门 | 后台任务 | 阶段 3（原阶段 2，推迟：依赖 SkillDistiller）| ⬜ 🔄 |
| `agent/memory/digestion/MemoryFlowConsolidator.java` | 保留 | 后台任务 | 阶段 3（原阶段 2，推迟：依赖 SkillDistiller）| ⬜ 🔄 |
| `agent/memory/digestion/SkillFlowEvolver.java` | 保留 | 后台任务 | 阶段 3（原阶段 2，推迟：依赖 SkillDistiller）| ⬜ 🔄 |
| `agent/memory/digestion/TraceMiner.java` | 保留 | 后台任务 | 阶段 3（原阶段 2，推迟：依赖 SkillDistiller）| ⬜ 🔄 |
| **shadow override** `io/agentscope/core/memory/EpisodicMemory.java` | 迁移到 `com.agentscopea2a.v2.memory.EpisodicMemory`（业务接口，v2 jar 无此类）| 业务包保留 | 阶段 3 | ✅（git mv 到 `v2/memory/`，脱离 shadow override 位置）|
| **shadow override** `io/agentscope/core/memory/EpisodicResult.java` | 迁移到业务包 `com.agentscopea2a.v2.memory.EpisodicResult`（record，实现 v2 `State` 接口）| 业务包保留 | 阶段 3 | ✅（git mv 到 `v2/memory/`）|
| **shadow override** `io/agentscope/core/memory/MemoryProvider.java` | 迁移到业务包 `com.agentscopea2a.v2.memory.MemoryProvider`（接口，v2 jar 无此类）| 业务包保留 | 阶段 3 | ✅（git mv 到 `v2/memory/`）|
| **shadow override** `io/agentscope/harness/agent/hook/MemoryFlushHook.java` | `MemoryFlushMiddleware` 或 v2 `MemoryConfig` 替代 | `.middlewares(...)` 或 builder | 阶段 1 已删除 | 🗑️ |
| **shadow override** `io/agentscope/harness/agent/hook/MemoryMaintenanceHook.java` | `MemoryMaintenanceMiddleware` 替代 | `.middlewares(...)` | 阶段 1 已删除 | 🗑️ |
| **v2 builder 默认**：`MemoryConfig` 双层记忆 | flushPrompt/consolidationPrompt/model("light-classifier") | `.memory(...)` | 阶段 2 | ✅（阶段 2 已切小模型 light-classifier/qwen3:8b）|
| **v2 builder 默认**：`CompactionConfig` | triggerMessages=40 / keepMessages=12 / truncateArgs | `.compaction(...)` | 阶段 2 | ✅（阶段 1 已挂载，trigger=40 / keep=12）|
| **v2 builder 默认**：`ToolResultEvictionConfig` | 80K 字符落盘 + 占位符 | `.toolResultEviction(...)` | 阶段 2 | ✅（阶段 1 已挂载，`defaults()`）|
| **v2 builder 默认**：`MysqlAgentStateStore` | MySQL 状态持久化，Spring `@Primary` DataSource 自动注入 | `.stateStore(new MysqlAgentStateStore(dataSource))` | 阶段 2 | ✅（阶段 2 已挂载）|
| **v2 工具**：`memory_search` / `memory_get` / `session_search` | agent 自服务工具（v2 自动注册）| builder 自动注册 | 阶段 2 | ⬜（需运行时验证）|

**阶段 2 已完成项**：
1. ✅ `MysqlAgentStateStore` 挂载 — `new MysqlAgentStateStore(dataSource)` 注入到 builder
2. ✅ `MemoryConfig` 切小模型 — 从 `glm-5.2` 改为 `light-classifier (qwen3:8b)`，降低 flush/consolidation token 成本
3. ✅ `KnownEntities` 移到 `v2/tools/` 包 — 编译通过

**阶段 2 推迟到阶段 3+ 的项**：

| 推迟项 | 阻塞依赖 | 解除阶段 |
|---|---|---|
| EpisodicRetrievalMiddleware | `MySqlEpisodicMemory` 依赖 `EmbeddingClient`（skills 模块）| 阶段 3 |
| 3 个 shadow override 删除 | `MySqlEpisodicMemory` 实现 `EpisodicMemory` 接口 | 阶段 3 |
| ResponseCacheMiddleware | 依赖 `SkillSynthesisRunner` / `FingerprintCalculator` / `DimensionStateManager` | 阶段 3+5 |
| MemoryDigestionService 对接 | 依赖 `SkillDistiller` / `SkillVectorIndex` / `FingerprintCalculator` | 阶段 3 |

| v1 二开文件 | v2 扩展形态 | 挂载方式 | 阶段 | 状态 |
|---|---|---|---|---|
| `agent/session/MySQLSession.java` | `JdbcAgentStateStore`（v2 内置）+ 业务字段扩展 | `.stateStore(...)` | 阶段 2 | ⬜ |
| `agent/memory/EpisodicLongTermMemoryAdapter.java` | 重写为 `EpisodicRetrievalMiddleware`（onSystemPrompt）| `.middlewares(...)` | 阶段 2 | ⬜ |
| `agent/memory/MySqlEpisodicMemory.java`（FTS5）| 保留，对接 v2 `session_search` 工具 | `Toolkit.registerTool(...)` | 阶段 2 | ⬜ |
| `agent/memory/MysqlMemoryStore.java` | 保留，作为 v2 `MEMORY.md` 的 MySQL 镜像层（双写）| v2 `MemoryConfig` 配置 | 阶段 2 | ⬜ |
| `agent/memory/MemoryHydrator.java` | 保留，双写同步 | v2 `MemoryConfig` 配置 | 阶段 2 | ⬜ |
| `agent/memory/MemoryFileWatcher.java` | 保留，双写同步 | v2 `MemoryConfig` 配置 | 阶段 2 | ⬜ |
| `agent/memory/EpisodicMemoryConfig.java` | 保留为业务配置 | `@ConfigurationProperties` | 阶段 2 | ⬜ |
| `agent/memory/digestion/MemoryDigestionService.java` | 保留，对接 v2 `MemoryMaintenanceMiddleware` 节流闸门 | 后台任务 | 阶段 2 | ⬜ |
| `agent/memory/digestion/MemoryFlowConsolidator.java` | 保留 | 后台任务 | 阶段 2 | ⬜ |
| `agent/memory/digestion/SkillFlowEvolver.java` | 保留 | 后台任务 | 阶段 2 | ⬜ |
| `agent/memory/digestion/TraceMiner.java` | 保留 | 后台任务 | 阶段 2 | ⬜ |
| **shadow override** `io/agentscope/core/memory/EpisodicMemory.java` | 迁移到 `com.agentscopea2a.agent.memory.EpisodicMemory` | 业务包保留 | 阶段 2 删除 | ⬜ |
| **shadow override** `io/agentscope/core/memory/EpisodicResult.java` | 迁移到业务包 | 业务包保留 | 阶段 2 删除 | ⬜ |
| **shadow override** `io/agentscope/core/memory/MemoryProvider.java` | 迁移到业务包 | 业务包保留 | 阶段 2 删除 | ⬜ |
| **shadow override** `io/agentscope/harness/agent/hook/MemoryFlushHook.java` | `MemoryFlushMiddleware` 或 v2 `MemoryConfig` 替代 | `.middlewares(...)` 或 builder | 阶段 1 已删除 | 🗑️（v2 API 不兼容：`MemoryFlushManager.offloadMessages` 签名新增 `RuntimeContext` 参数；引用 `StructuredOutputCapableAgent` 已删）|
| **shadow override** `io/agentscope/harness/agent/hook/MemoryMaintenanceHook.java` | `MemoryMaintenanceMiddleware` 替代 | `.middlewares(...)` | 阶段 1 已删除 | 🗑️（v2 API 不兼容：`MemoryConsolidator.consolidate` 签名新增 `RuntimeContext` 参数）|
| **v2 builder 默认**：`MemoryConfig` 双层记忆 | flushPrompt/consolidationPrompt/model("openai:gpt-4.1-mini") | `.memory(...)` | 阶段 2 | 🔄（阶段 1 已挂载，model 暂用主模型；阶段 2 改小模型降 token）|
| **v2 builder 默认**：`CompactionConfig` | triggerMessages=40 / keepMessages=12 / truncateArgs | `.compaction(...)` | 阶段 2 | ✅（阶段 1 已挂载，trigger=40 / keep=12）|
| **v2 builder 默认**：`ToolResultEvictionConfig` | 80K 字符落盘 + 占位符 | `.toolResultEviction(...)` | 阶段 2 | ✅（阶段 1 已挂载，`defaults()`）|
| **v2 工具**：`memory_search` / `memory_get` / `session_search` | agent 自服务工具 | `Toolkit.registerTool(...)` | 阶段 2 | ⬜ |

**验收证据**：(1) `memory/2026-07-13.md` 已生成；(2) `MEMORY.md` 出现在下一轮 system prompt；(3) flush/consolidation/compaction 三处 LLM 走 `openai:gpt-4.1-mini`，token 成本降 60%+；(4) `CompactionConfig.triggerMessages=40` 触发后上下文 token 下降；(5) `ToolResultEvictionConfig` 对 80K+ 字符结果落盘；(6) 3 个 memory shadow override 已删除；(7) FTS5 检索正常。

---

## 三、Skills 自由化（§3.2.3）-- 阶段 3

> 阶段 3 已实施完成（2026/07/13），详见 [`stage3-implementation-record.md`](stage3-implementation-record.md)。

| v1 二开文件 | 行数 | v2 扩展形态 | 挂载方式 | 阶段 | 状态 |
|---|---|---|---|---|---|
| `harness/skills/SkillSynthesisRunner.java` | 636 | **评估替换**：v2 `ProposeSkillTool` + `SkillManageTool` 覆盖则删 v1，仅保留 v1 生成 prompt 模板 | `.enableSkillManageTool(...)` | 阶段 3 | ⬜ |
| `harness/skills/SkillEvolutionRunner.java` | 442 | **评估替换**：v2 `SkillCurator` 后台整理 + LLM "伞合并"扫描覆盖则删 v1 | `.enableSkillCurator(...)` | 阶段 3 | ⬜ |
| `harness/skills/SkillDistiller.java` | 648 | **保留 v1**（v2 无等价），输出通过 v2 `SkillManageTool` 写回 | 后台任务 | 阶段 3 | ⬜ |
| `harness/skills/SkillVectorIndex.java` | 372 | git mv 到 `v2/skills/SkillVectorIndex`，移除 `@Repository` 等注解，由 `V2SkillConfig` 管理 bean | `.enableSkillPromotionGate(..., filter)` | 阶段 5 | ✅（git mv 到 `v2/skills/`，`V2SkillConfig` 提供 bean，`SkillVectorIndexVisibilityFilter` 实现桥接）|
| `harness/skills/FingerprintCalculator.java` | 246 | **保留 v1 语义指纹**（v2 只有 SHA-256 文件级），文件级去重用 v2 `MarketplaceStager` | 扩展 `SkillUsageStore` | 阶段 7 | ✅（git mv 到 `v2/skills/`，移除 `@Component`，import 更新到 v2 包，`V2SkillConfig` 提供 bean）|
| `harness/skills/MetricClassificationService.java` | 413 | **保留 v1**（v2 只有使用计数），对接 v2 `SkillUsageRecord` | 扩展 `SkillUsageStore` | 阶段 7 | ✅（git mv 到 `v2/skills/`，移除 `@Service`/`@Value`/`@PostConstruct`，构造函数注入 `(SkillCandidateRepository, Model, boolean, Path)`，`init()` 由 V2SkillConfig 调用）|
| `harness/skills/SkillIndexRepository.java` | - | git mv 到 `v2/skills/`，移除 `@Repository`/`@Qualifier`，由 `V2ToolConfig` 管理 bean | `V2ToolConfig` bean | 阶段 6 | ✅（git mv 到 `v2/skills/`，构造函数改为普通 `DataSource` 参数，`V2ToolConfig` 显式创建 bean）|
| `harness/skills/SkillEntry.java` | - | git mv 到 `v2/skills/`，包声明更新 | `SkillSaveTool` 引用 | 阶段 6 | ✅（git mv 到 `v2/skills/`，包声明更新）|
| `harness/skills/SkillCandidate.java` | - | 保留，对接 v2 `ProposeSkillTool` 草稿 | 业务包保留 | 阶段 7 | ✅（git mv 到 `v2/skills/`，包声明更新）|
| `harness/skills/SkillCandidateRepository.java` | - | 保留，对接 v2 `SkillPromotionGate` | 业务包保留 | 阶段 7 | ✅（git mv 到 `v2/skills/`，移除 `@Repository`/`@Qualifier`，`initSchema()` 由 V2SkillConfig 调用）|
| `harness/skills/EmbeddingClient.java` → `v2/skills/EmbeddingClient.java` | - | 已移到 v2 包（`com.agentscopea2a.v2.skills`），脱离 v1 compile-exclude | `SkillVisibilityFilter` 向量计算 / `MySqlEpisodicMemory` 向量检索 | 阶段 3 | ✅（git mv 到 `v2/skills/`，package 声明更新）|
| `harness/skills/OpenAiCompatEmbeddingClient.java` → `v2/skills/OpenAiCompatEmbeddingClient.java` | - | 同上 | 同上 | 阶段 3 | ✅（git mv 到 `v2/skills/`，package 声明更新，引用改为 `v2.util.HttpClient`）|
| `agent/tools/routers/HttpClient.java` → `v2/util/HttpClient.java` | - | 同上（被 `OpenAiCompatEmbeddingClient` 引用）| 业务工具 | 阶段 3 | ✅（git mv 到 `v2/util/`）|
| **v2 builder 默认**：`SkillCurator` 闭环 | propose -> promote -> usage -> audit -> security -> curator | `.enableSkillManageTool(...).enableSkillCurator(...)` | 阶段 3 | ✅（`V2SkillConfig` 提供 `SkillManageConfig` + `SkillCuratorConfig` + `LocalApprovalGate` bean；builder 已挂载）|
| **v2 builder 默认**：`SkillPromotionGate` | `LocalApprovalGate` 对接内网审批 | `.enableSkillPromotionGate(...)` | 阶段 3 | ✅（`enableSkillPromotionGate(localApprovalGate, new CompositeFilter())` 已挂载）|
| **v2 builder 默认**：`SkillSecurityScanner` | agent 自写 skill 防恶意 shell | `.enableSkillPromotionGate(..., scanner)` | 阶段 3 | ⬜（默认 Scanner 未显式配置，由 builder 内置）|
| **v2 builder 默认**：`SkillVisibilityFilter` | `CanaryFilter` + `EnvironmentFilter` 灰度 | `.enableSkillPromotionGate(..., filter)` | 阶段 3 | ✅（当前用 `CompositeFilter()` 空通过，后续替换为含 `SkillVectorIndex` 的实现）|
| **v2 builder 默认**：`MarketplaceStager` | SHA-256 文件级去重 + 孤儿清理 + 执行位恢复 | builder 默认 | 阶段 3 | ⬜（builder 内置，需运行时验证）|
| **v2 builder 默认**：4 层合成 | project global / marketplace / workspace shared / user-isolated | builder 默认 | 阶段 3 | ⬜（builder 内置，需运行时验证）|

**验收证据**：(1) `propose_skill` 工具可起草草稿到 `skills/_drafts/`；(2) `SkillPromotionGate` 审批流程跑通；(3) `SkillSecurityScanner` 拦截含恶意 shell 的 skill；(4) `CanaryFilter` 按 10% 灰度发布；(5) `SkillAuditLog` 记录 propose/promote/使用计数；(6) v1 保留部分（蒸馏/向量索引/语义指纹/指标分类）与 v2 框架对接顺畅；(7) "越用越聪明"效果不退化。

---

## 四、沙箱体系（§3.2.4）-- 阶段 4（已完成）

| v1 二开文件 | 行数 | v2 扩展形态 | 挂载方式 | 阶段 | 状态 |
|---|---|---|---|---|---|
| **shadow override** `DockerCliRunner.java` | 151 | git mv 到 `v2/sandbox/`，方法可见性改为 public，保留 SSH-remote Docker 支持 | `V2SandboxConfig` 调用 `DockerCliRunner.configure()` | 阶段 4 已迁移 | ✅（`com.agentscopea2a.v2.sandbox.DockerCliRunner`）|
| **shadow override** `DockerSandbox.java` | 397 | v2 `DockerSandbox.shutdown()` 已内置 `containerOwned` 检查，无需覆写 | N/A | 阶段 4 已删除 | 🗑️（v2 已含 containerOwned 检查；RemoteDockerSandbox 推迟到阶段 5+）|
| **shadow override** `DockerSandboxClient.java` | 228 | 重建为 `SharedContainerDockerSandboxClient`（共享容器模式 + deserialize 修复）| `.client(new SharedContainerDockerSandboxClient())` on `DockerFilesystemSpec` | 阶段 4 已替换 | ✅（`com.agentscopea2a.v2.sandbox.SharedContainerDockerSandboxClient` implements `SandboxClient<DockerSandboxClientOptions>`）|
| `v2/sandbox/`（新建）| - | `SharedContainerDockerSandboxClient` + `DockerCliRunner`（从 shadow override 迁移）| `.client(new SharedContainerDockerSandboxClient())` on `DockerFilesystemSpec` | 阶段 4 | ✅ |
| `v2/config/V2SandboxConfig.java`（新建）| - | Spring beans for `DockerFilesystemSpec` + `DistributedStore` + `RemoteFilesystemSpec` | `ObjectProvider` 注入到 `HarnessA2aRunnerV2` | 阶段 4 | ✅ |
| workspace 投影根 | - | v2 `WorkspaceProjectionApplier` | `.workspaceProjectionRoots(...)` | 阶段 4 | ✅（`spec.workspaceProjectionRoots(List.of("AGENTS.md", "knowledge", "agent-subagents"))`）|
| 快照策略 | - | v2 `JdbcSnapshotSpec`（MySQL BLOB，跨副本）| `.snapshotSpec(new JdbcSnapshotSpec(...))` | 阶段 4 | ✅（`V2SandboxConfig` 条件化挂载）|
| 资源限额 | - | v2 `memorySizeBytes` / `cpuCount` | `.memorySizeBytes(...).cpuCount(...)` | 阶段 4 | ✅（`DockerFilesystemSpec` builder 支持，运行时配置）|
| 网络模式 | - | v2 `network(String)` | `.network(...)` | 阶段 4 | ✅（`DockerFilesystemSpec` builder 支持，运行时配置）|
| 额外 docker run 参数 | - | v2 `additionalRunArgs(...)` | `.additionalRunArgs(...)` | 阶段 4 | ✅（`DockerFilesystemSpec` builder 支持，运行时配置）|
| 超时 / 重试 | - | v2 内置 retry | builder 默认 | 阶段 4 | ⬜ |
| **v2 新能力**：`IsolationScope.USER` | - | 4 scope，USER 缺 userId 自动降级 SESSION | `.isolationScope(IsolationScope.USER)` | 阶段 4 | ✅（`V2SandboxConfig` 支持 SESSION/USER/AGENT/GLOBAL）|
| **v2 新能力**：`SandboxExecutionGuard` | - | 可插拔并发锁，防多副本 last-write-wins | `.executionGuard(JdbcSandboxExecutionGuard.builder(...).build())` | 阶段 4 | ✅（distributed.enabled=true 时挂载 `JdbcSandboxExecutionGuard`）|
| **v2 新能力**：`SessionSandboxStateStore` | - | 沙箱状态走 `AgentStateStore`，跨副本 resume | `.stateStore(...)` 自动生效 | 阶段 4 | ✅（阶段 2 已挂载 `MysqlAgentStateStore`）|
| **v2 新能力**：`WorkspaceSpec` + `WorkspaceEntry` | - | 结构化布局（FileEntry/DirEntry/BindMountEntry/...）| `.workspaceSpec(...)` | 阶段 4 | ✅（`V2SandboxConfig` 构建 `WorkspaceSpec` 含 skills/memory/artifacts bind mounts）|

**验收证据**：(1) `SharedContainerDockerSandboxClient.create()/resume()/deserializeState()` 行为与 v1 shadow override 等价；(2) 共享容器模式（`setSharedContainerName`）通过 `SharedContainerDockerSandboxClient` 实现；(3) `DockerCliRunner.configure()` SSH remote 模式配置生效；(4) `JdbcSandboxExecutionGuard` 并发锁条件化挂载（distributed.enabled=true）；(5) `IsolationScope.USER` 多租户隔离支持；(6) `JdbcSnapshotSpec` 快照跨副本恢复条件化挂载；(7) workspace 投影根同步正确；(8) 3 个 Docker shadow override + 1 个 AgentSpecLoader shadow 已从 `io.agentscope.*` 删除；(9) `MysqlDistributedStore.create(dataSource)` 替代已删除的 `SandboxDistributedOptions`；(10) `DockerFilesystemSpec` 使用正确 v2 import path（`io.agentscope.harness.agent.sandbox.impl.docker`）；(11) `HarnessA2aRunnerV2` 条件化接线 sandbox/distributed（`ObjectProvider`）。

---

## 五、维度状态（§3.2.5）-- 阶段 3（提前从阶段 5）

| v1 二开文件 | v2 扩展形态 | 挂载方式 | 阶段 | 状态 |
|---|---|---|---|---|
| `agent/dimension/DimensionStateManager.java` → `v2/dimension/DimensionStateManager.java` | `DimensionStateMiddleware`（onSystemPrompt）| `.middlewares(...)` | 阶段 3（提前）| ✅（git mv 到 `v2/dimension/`，`Session`/`SessionKey` 改为 `RuntimeContext.put/get`，middleware 已挂载）|
| `agent/dimension/DimensionPrompts.java` → `v2/dimension/DimensionPrompts.java` | 保留为业务配置 | middleware 注入 via `V2DimensionConfig` | 阶段 3（提前）| ✅ |
| `agent/dimension/DimensionState.java` → `v2/dimension/DimensionState.java` | 保留为业务状态对象 | middleware 自管 | 阶段 3（提前）| ✅ |
| `agent/dimension/LlmDimensionService.java` → `v2/dimension/LlmDimensionService.java` | 保留 | middleware 调用 | 阶段 3（提前）| ✅ |
| `agent/dimension/OpenAILlmDimensionService.java` → `v2/dimension/OpenAILlmDimensionService.java` | 保留 | `V2DimensionConfig` Spring bean | 阶段 3（提前）| ✅ |
| `agent/dimension/QuestionAnalysis.java` → `v2/dimension/QuestionAnalysis.java` | 保留 | middleware 调用 | 阶段 3（提前）| ✅ |
| `agent/dimension/DimensionException.java` → `v2/dimension/DimensionException.java` | 保留 | 业务异常 | 阶段 3（提前）| ✅ |
| **v2 builder 默认**：`AgentState` 4 子上下文 | PermissionContextState/ToolContextState/TaskContextState/PlanModeContextState | builder 默认 | 阶段 5 | ⬜ |
| **v2 builder 默认**：`InterruptControl` | per-session 中断信号，runtime-only 不序列化 | builder 默认（Harness 使能）| 阶段 5 | ⬜ |
| **v2 builder 默认**：`TaskContextState` + `TodoTools` | TaskList + `TaskReminderMiddleware` | `.enableTaskList(true)` | 阶段 5 | ⬜ |

**分工**：v1 维度状态保留为业务 middleware 自管（不写入 `AgentState.context`），通过 `onSystemPrompt` 注入；v2 子上下文（Task/Permission/PlanMode）用框架标准；两者通过 `RuntimeContext` 传递，不互相序列化。

---

## 六、多数据源（§3.2.6）-- 阶段 5

| v1 二开 | v2 扩展形态 | 挂载方式 | 阶段 | 状态 |
|---|---|---|---|---|
| `config/datasource/`（多数据源业务路由 MySQL/ClickHouse/GaussDB）| 全部保留，业务逻辑不变 | `@Configuration` 注入 | 阶段 5 | ⬜ |
| `mapper/ck/` / `mapper/db1/` / `mapper/gauss/` / `mapper/mysql/` | 保留 | MyBatis 配置 | 阶段 5 | ⬜ |
| **v2 新能力**：`DistributedStore` 一行注入 | 自动接 stateStore + baseStore + executionGuard + snapshotSpec | `.distributedStore(JdbcDistributedStore.from(dataSource))` | 阶段 1 配置 / 阶段 5 验证 | ⬜ |
| **v2 新能力**：`JdbcStore` / `JdbcAgentStateStore` | MySQL 分布式状态 | `.stateStore(...)` | 阶段 1 配置 / 阶段 5 验证 | ⬜ |
| **v2 新能力**：`BaseStore` + `NamespaceFactory` | 多租户隔离 | `.distributedStore(...)` 自动生效 | 阶段 5 | ⬜ |

**验收证据**：(1) 多数据源业务路由正常（MySQL/ClickHouse/GaussDB）；(2) `DistributedStore` 一行注入生效（stateStore + baseStore + executionGuard + snapshotSpec 自动接好）。

---

## 七、SSE 流式（§3.2.7）-- 阶段 6

| v1 二开文件 | v2 扩展形态 | 挂载方式 | 阶段 | 状态 |
|---|---|---|---|---|
| `controller/ChatController.java` | `V2ChatStreamServiceImpl` 适配 v2 `streamEvents()` | `V2ChatStreamService.stream()` | 阶段 6 | ✅（`V2ChatStreamService` 接口 + `V2ChatStreamServiceImpl` 实现，`V2ChatController` 委托调用）|
| `controller/DigestionController.java` | 保留 | 保留 | 阶段 6 | ⬜ |
| `controller/DebugController.java` | 保留 | 保留 | 阶段 6 | ⬜ |
| SSE 业务事件定制 | `V2ChatStreamServiceImpl` 处理 `TextBlockDeltaEvent` → AiChatResult 增量 | `streamEvents()` 适配层 | 阶段 6 | ✅（`TextBlockDeltaEvent.getDelta()` 获取增量文本，`AgentResultEvent` 终止事件）|
| **v2 强制采纳**：`streamEvents()` | `Flux<AgentEvent>` + typed 子类 | 强制（v1 `stream()` 已 `@Deprecated(forRemoval=true)`）| 阶段 6 | ✅（`V2ChatStreamServiceImpl` 使用 `streamEvents()`）|
| **v2 新能力**：`Channel` + `Gateway` | per-session 并发控制 + 多 agent 路由 | `agent.channel(ChatUiChannel.create())` | 阶段 6 | ⬜ |
| **v2 新能力**：`BotLoopGuard` | 防 bot 死循环（内网多 bot 必备）| `.botLoopGuard(...)` | 阶段 7 | ❌（ADR-7.2 确认为 Channel-level 组件，不适用于 `streamEvents()` runner，推迟到 Stage 8+ Channel 集成）|
| **v2 新能力**：`IdempotencyStore` | 幂等 | `.idempotencyStore(...)` | 阶段 7 | ❌（ADR-7.2 确认为 Channel-level 组件，不适用于 `streamEvents()` runner，推迟到 Stage 8+ Channel 集成）|
| **v2 新能力**：`SubagentExposedEvent` | 分支对话（可选）| `agent_spawn expose_to_user=true` | 阶段 6 | ⬜ |

**验收证据**：(1) `streamEvents()` 28 typed 事件流式正常；(2) `Channel` per-session 并发控制有效；(3) `BotLoopGuard` 拦截死循环；(4) 中断/取消正常（`InterruptControl` 保留上下文）；(5) HITL 人机协同正常。

---

## 八、Artifact 体系（§3.2.8）-- 阶段 5

| v1 二开文件 | v2 扩展形态 | 挂载方式 | 阶段 | 状态 |
|---|---|---|---|---|
| `harness/artifact/ArtifactStore.java` | git mv 到 `v2/artifact/ArtifactStore`，包更新 | `V2InfraConfig` bean | 阶段 5 | ✅ |
| `harness/artifact/TabularExtractor.java` | git mv 到 `v2/artifact/TabularExtractor`，包更新 | middleware/hook 调用 | 阶段 5 | ✅ |
| `harness/artifact/ArtifactContext.java` | git mv 到 `v2/artifact/ArtifactContext`（record，包更新）| middleware/hook 自管 | 阶段 5 | ✅ |
| `harness/artifact/ArtifactRef.java` | git mv 到 `v2/artifact/ArtifactRef`（record，包更新）| middleware/hook 自管 | 阶段 5 | ✅ |
| `harness/artifact/ArtifactIo.java` | git mv 到 `v2/artifact/ArtifactIo`（接口，包更新）| - | 阶段 5 | ✅ |
| `harness/artifact/LocalArtifactIo.java` | git mv 到 `v2/artifact/LocalArtifactIo`，包更新 | `V2InfraConfig` ArtifactStore bean | 阶段 5 | ✅ |
| `harness/artifact/SshArtifactIo.java` | git mv 到 `v2/artifact/SshArtifactIo`，包更新 | 远程 Docker 模式 | 阶段 5 | ✅ |
| `harness/artifact/ArtifactSweeper.java` | 保留 | 后台任务 | 阶段 5 | ⬜ |
| **v2 新能力**：`CompositeFilesystem` | 组合 Local + Remote 多后端 | `.filesystem(...)` | 阶段 5 | ⬜ |
| **v2 新能力**：`RemoteFilesystem` + `JdbcStore` | 远端 KV，跨副本共享 | `.filesystem(...)` | 阶段 5 | ⬜ |
| **v2 新能力**：`NamespaceFactory` | 多租户隔离 | 自动生效 | 阶段 5 | ⬜ |
| **v2 可选**：`OverlayFilesystem` | 读写分离 | 可选 | 阶段 5 | ⬜ |
| **v2 可选**：`WorkspaceIndex` | SQLite 加速远端 ls/glob/grep | 可选 | 阶段 5 | ⬜ |

**验收证据**：Artifact 跨副本共享（节点 A 写入 -> 节点 B 读取）。

---

## 九、元工具体系（§3.2.9）-- 阶段 6

| v1 二开文件 | 行数 | v2 扩展形态 | 挂载方式 | 阶段 | 状态 |
|---|---|---|---|---|---|
| `harness/tools/ToolRoutersIndex.java` | 347 | git mv 到 `v2/tools/`，保留路由模式（暂不迁移到 ToolGroup）| `V2ToolConfig` bean | 阶段 6 | ✅（git mv 到 `v2/tools/`，移除 `@Component`/`@Autowired`，构造函数注入）|
| `harness/tools/DataPrimitivesTool.java` | - | git mv 到 `v2/tools/`，包声明更新，`SandboxPropertiesV2` 替代 | `V2ToolConfig` bean | 阶段 6 | ✅（git mv 到 `v2/tools/`，移除 `@Component`，构造函数注入 `SandboxPropertiesV2`）|
| `harness/tools/QualityTools.java` | - | git mv 到 `v2/tools/`，包声明更新 | `V2ToolConfig` bean | 阶段 6 | ✅（git mv 到 `v2/tools/`，无外部依赖）|
| `harness/tools/SkillSaveTool.java` | - | git mv 到 `v2/tools/`，包声明更新，依赖更新 | `V2ToolConfig` bean | 阶段 6 | ✅（git mv 到 `v2/tools/`，`EmbeddingClient`/`SkillVectorIndex` → `v2.skills`，`SkillEntry`/`SkillIndexRepository` → `v2.skills`）|
| `harness/tools/CaptureSkillSaveTool.java` | - | git mv 到 `v2/tools/`，包声明更新 | `V2ToolConfig` bean | 阶段 6 | ✅（git mv 到 `v2/tools/`，无外部依赖）|
| `harness/tools/PythonExecTool.java` | - | git mv 到 `v2/tools/`，`SandboxPropertiesV2`/`PythonExecPropertiesV2` 替代 | `V2ToolConfig` bean（条件化）| 阶段 6 | ✅（git mv 到 `v2/tools/`，`@ConditionalOnProperty(prefix = "harness.a2a.sandbox", name = "enabled", havingValue = "true")`）|
| `harness/tools/KnownEntities.java` -> `v2/tools/KnownEntities.java` | - | 阶段 2 移位完成 | `QualityTools` 同包引用 | 阶段 2 ✅ | ✅ |
| `agent/tools/AgentTools.java` | - | git mv 到 `v2/tools/`，构造函数注入 `QualityTools` | `V2ToolConfig` bean | 阶段 6 | ✅（git mv 到 `v2/tools/`，移除 `@Component`，构造函数注入 `QualityTools`）|
| `agent/tools/routers/HttpClient.java` | - | 已在 `v2/util/`（阶段 3 迁移）| 业务工具 | 阶段 3 | ✅ |
| `agent/tools/routers/MdTableParser.java` | - | git mv 到 `v2/tools/`，包声明更新 | 业务工具 | 阶段 6 | ✅（git mv 到 `v2/tools/`，无外部依赖）|
| **shadow override** `AgentSpawnTool.java` | 590 | v2 `AgentSpawnTool` 替代，定制点通过 Builder 注入 | Builder | 阶段 1 已删除 | 🗑️（v2 API 不兼容：`DefaultAgentManager.createAgent` / `TaskRepository.putTask` 签名新增 `RuntimeContext` 参数；定制点 timeout 30s->600s 已记录在 shadow-override-customization-points.md，阶段 6 通过 Builder 注入）|
| **shadow override** `SubagentsHook.java` | 302 | v2 `SubagentsMiddleware` 替代 | `.middlewares(...)` | 阶段 1 已删除 | 🗑️（v2 API 不兼容：引用 v2 已删除的 `DefaultTaskRepository`）|
| **shadow override** `AgentSpecLoader.java` | 284 | v2 `harness.agent.subagent.AgentSpecLoader` 替代 | builder 默认 | 阶段 6 删除 | ⬜（阶段 1 保留，未触发编译）|
| **v2 新能力**：`ToolGroup` + `ToolGroupScope` + `ToolGroupManager` | 按需激活工具组，省 context | `.toolGroups(...)` / `.toolkit(...)` | 阶段 7 | ✅（`V2ToolGroupAdapter` 创建，Builder 模式，创建 `quality_tools` + `data_primitives` 两个 META scope 工具组，`V2ToolConfig` 提供 bean，`HarnessA2aRunnerV2` 通过 `ObjectProvider<V2ToolGroupAdapter>` 接线 `.toolkit()`）|
| **v2 新能力**：`MetaToolFactory` | `reset_equipped_tools` 让 LLM 自己切换工具集 | builder 默认 / `toolkit.registerMetaTool()` | 阶段 7 | ✅（`V2ToolGroupAdapter.Builder.enableMetaTool()` 注册 `reset_equipped_tools` meta-tool）|
| **v2 新能力**：`AsyncToolMiddleware` + `MessageBus` + `InboxMiddleware` | 长时工具后台化 + inbox 推回 `HintBlock` | `.middlewares(...)` | 阶段 6 | ⬜ |
| **v2 新能力**：`WaitAsyncResultsTool` | LLM 主动等待异步结果 | `Toolkit.registerTool(...)` | 阶段 6 | ⬜ |
| **v2 新能力**：`ToolSuspendException` | 工具 HITL 挂起 | middleware 内抛出 | 阶段 6 | ⬜ |
| **v2 新能力**：MCP 集成 | `McpClientManager` + `McpTool` 远程工具协议 | `workspace/tools.json` | 阶段 6 评估 | ⬜ |

**验收证据**：(1) 工具调用全链路正常（含元工具/`ToolGroup` 激活）；(2) 长时工具异步化后主推理不阻塞；(3) 3 个 subagent shadow override 已删除。

---

## 十、shadow override 整体删除计划（§3.4）

| 删除对象 | 行数 | 计划删除节点 | 实际删除节点 | 状态 |
|---|---|---|---|---|
| `io.agentscope.core.ReActAgent`（补丁）| 1906 | 阶段 9（观察期结束）| 阶段 1（v2 API 不兼容：引用 `StructuredOutputCapableAgent` / `PendingToolRecoveryHook` / `core.plan.*` / `core.session.*` / `core.state.*` / `PlanNotebook` 已删）| 🗑️ |
| `io.agentscope.core.memory.EpisodicMemory`（补丁）| 91 | 阶段 2 | 阶段 3（迁到 `v2/memory/` 作为业务接口）| ✅ |
| `io.agentscope.core.memory.EpisodicResult`（补丁）| 31 | 阶段 2 | 阶段 3（迁到 `v2/memory/` 作为业务 record）| ✅ |
| `io.agentscope.core.memory.MemoryProvider`（补丁）| 139 | 阶段 2 | 阶段 3（迁到 `v2/memory/` 作为业务接口）| ✅ |
| `io.agentscope.harness.agent.hook.MemoryFlushHook`（补丁）| 100 | 阶段 2 | 阶段 1（v2 API 不兼容：`offloadMessages` 签名新增 `RuntimeContext`）| 🗑️ |
| `io.agentscope.harness.agent.hook.MemoryMaintenanceHook`（补丁）| 202 | 阶段 2 | 阶段 1（v2 API 不兼容：`consolidate` 签名新增 `RuntimeContext`）| 🗑️ |
| `io.agentscope.harness.agent.hook.SubagentsHook`（补丁）| 302 | 阶段 6 | 阶段 1（v2 API 不兼容：引用 `DefaultTaskRepository` 已删）| 🗑️ |
| `io.agentscope.harness.agent.sandbox.impl.docker.DockerCliRunner`（补丁）| 151 | 阶段 4 | 阶段 4（git mv 到 `v2/sandbox/`，方法可见性改为 public）| ✅ |
| `io.agentscope.harness.agent.sandbox.impl.docker.DockerSandbox`（补丁）| 397 | 阶段 4 | 阶段 4（v2 已含 containerOwned 检查，无需覆写）| 🗑️ |
| `io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClient`（补丁）| 228 | 阶段 4 | 阶段 4（由 `SharedContainerDockerSandboxClient` 替代）| 🗑️ |
| `io.agentscope.harness.agent.tool.AgentSpawnTool`（补丁）| 590 | 阶段 6 | 阶段 1（v2 API 不兼容：`createAgent` / `putTask` 签名新增 `RuntimeContext`）| 🗑️ |
| `io.agentscope.subagent.AgentSpecLoader`（补丁）| 284 | 阶段 6 | 阶段 4（v2 有完整版 `io.agentscope.harness.agent.subagent.AgentSpecLoader`，本地 shadow 是子集）| 🗑️ |
| `HarnessA2aRunner`（v1 入口）| 242 | 阶段 9（观察期结束）| - | ⬜（v1 入口代码留在源码树但 Maven excludes 排除编译，阶段 9 视实际移植情况删除）|

**阶段 1 实际删除**：5 个 shadow override（`ReActAgent` + `MemoryFlushHook` + `MemoryMaintenanceHook` + `SubagentsHook` + `AgentSpawnTool`），合计 3100 行。定制点已记录在 `shadow-override-customization-points.md`，后续阶段按需通过 v2 middleware / builder 选项重新实现。

**12 shadow override 合计**：约 4421 行（含 ReActAgent 1906 行）；阶段 1 已删 3100 行（70%），阶段 3 已删/迁 261 行（6%），阶段 4 已删/迁 776 行（18%），剩余 284 行待阶段 6 删除。

---

## 十一、配置文件迁移（§3.6）

| 二开配置 | 迁移策略 | 阶段 | 状态 |
|---|---|---|---|
| `application.properties` / `-dev` / `-prod` | 保留 properties 格式（不迁移到 yml）；新增 v2 入口配置项 | 阶段 1 | 🔄（v2 入口已新增 `harness.a2a.model.instances.glm-main.*` / `harness.a2a.workspace.path`；其他 v2 配置项待阶段 2+ 新增）|
| `application-sandbox-linux.properties` / `-windows` / `-linux-remote` | 保留，对齐 v2 `SandboxClientOptions` 字段 | 阶段 4 | ⬜ |
| `log4j2.xml` | 保留 | - | ✅ |
| `mybatis/*` | 保留 | - | ✅ |
| `workspace/` | 保留，新增 `workspace/subagents/` / `workspace/skills/` / `workspace/plans/` 对齐 v2 约定 | 阶段 1 | 🔄（v2 入口 workspace 指向 `.agentscope/workspace/harness-a2a`，由 builder 自动建子目录；阶段 2+ 评估是否切回业务 workspace）|
| `docs/` | 保留 | - | ✅ |

新增配置项（v2 入口）：
- `harness.a2a.v2.enabled`（新增，v2 入口开关，双轨期默认 false，灰度切换时 true）-- 阶段 7 新增
- `harness.routing.v2-percentage`（新增，v2 流量百分比，100=全 v2，0=全 v1，默认 100）-- 阶段 7 新增
- `harness.a2a.v2.compaction.trigger` / `.keep` -- 阶段 2 新增
- `harness.a2a.v2.tool-eviction.max-chars` -- 阶段 2 新增
- `harness.a2a.v2.memory.flush.enabled` / `.consolidation.enabled` / `.model` -- 阶段 2 新增
- `harness.a2a.v2.plan-mode.enabled` / `.plan-file-directory` -- 阶段 2 新增
- `harness.a2a.v2.permission.mode` -- 阶段 6 新增
- `harness.a2a.v2.distributed-store.type=jdbc` -- 阶段 5 新增

---

## 十二、依赖（pom.xml）迁移（§3.7）

| 依赖 | 变更 | 状态 |
|---|---|---|
| `agentscope.version`（第 25 行）| `1.1.0-RC2` -> `2.0.0-RC5` | ✅ |
| `agentscope-core` | jar 依赖升级到 v2 | ✅ |
| `agentscope-harness` | jar 依赖升级到 v2 | ✅ |
| `agentscope-a2a-spring-boot-starter` | jar 依赖升级到 v2 | ✅ |
| **新增** `agentscope-extensions-mysql` | v2 `MysqlAgentStateStore` / `MysqlDistributedStore` / `JdbcSandboxExecutionGuard` / `JdbcSnapshotSpec` | ✅ |
| **新增** `agentscope-extensions-model-openai` | v2 OpenAI 兼容模型扩展（GLM via 火山方舟；v2 模型类移到 extensions） | ✅ |
| **新增** `guava 33.0.0-jre` | `SseEmitterCacheUtil` 使用 `CacheBuilder` | ✅ |
| `spring-boot` | 保留 3.2.3 | ✅ |
| `java.version` | 保留 17 | ✅ |
| 其余业务依赖（MyBatis / ClickHouse / GaussDB / Hutool / fastjson / jasypt）| 全部保留 | ✅ |

**阶段 1 实际新增依赖说明**：
- 原计划 §3.7 提到的 `agentscope-extensions-skill-mysql-repository` 未引入（阶段 3 评估是否需要）
- 原计划未提到的 `agentscope-extensions-model-openai` 必须引入（v2 把 `OpenAIChatModel` 从 core 移到 extensions）
- 原计划未提到的 `guava` 必须引入（v1 业务 `SseEmitterCacheUtil` 依赖 `com.google.common.cache`）

**m2 artifact 状态**（v2 jar 构建产物）：
- ✅ `agentscope-core` / `agentscope-harness` / `agentscope-extensions-mysql` / `agentscope-extensions-model-openai`：v2 源码构建
- ⚠️ `agentscope-extensions-a2a-client` / `agentscope-extensions-a2a-server`：v1 1.1.0-RC2 jar 作为 2.0.0-RC5 兼容层（v2 源码树不完整）
- ⚠️ `agentscope`（umbrella）：空 stub jar 作为 2.0.0-RC5（umbrella 依赖 broken sandbox 模块）
- ✅ `agentscope-spring-boot-starter` / `agentscope-a2a-spring-boot-starter`：v2 源码构建（依赖以上兼容层）

**风险**：v1 a2a-client / a2a-server jar 的 .class 文件编译时绑定 v1 core API，运行时与 v2 core 可能存在 `NoSuchMethodError`。阶段 2+ 需重点验证 a2a 链路运行时行为。

---

## 十三、阶段汇总

| 阶段 | 模块 | 工作量 | 状态 |
|---|---|---|---|
| 阶段 0 | 准备 | 1 周 | ✅（升级分支 ✅、v2 jar 构建 ✅ 含兼容层、定制点提取 ✅、回归基线 ✅、本清单 ✅）|
| 阶段 1 | v2 入口最小路径 | 1-2 周 | ✅（HarnessA2aRunnerV2 + V2ChatController ✅、SSE 冒烟 ✅；5 shadow override 提前删除；自定义 middleware / 工具挂载推迟到阶段 2）|
| 阶段 2 | 记忆模块挂载（精简版）| ~1.5 小时 | ✅（stateStore ✅ / MemoryConfig 小模型 ✅ / KnownEntities 移位 ✅；4 项推迟到阶段 3+）|
| 阶段 3 | Skills 模块挂载 + 维度集群 + 记忆集群迁移 | ~3 小时 | ✅（SkillCurator pipeline ✅ / Dimension 集群 git mv ✅ / EmbeddingClient 迁移 ✅ / 3 shadow override 迁移 ✅ / MySqlEpisodicMemory 迁移 ✅ / EpisodicRetrievalMiddleware ✅ / V2DimensionConfig ✅ / V2MemoryConfig ✅ / V2SkillConfig ✅）|
| 阶段 4 | 沙箱模块挂载 | ~3 小时 | ✅（SharedContainerDockerSandboxClient ✅ / DockerCliRunner 迁移 ✅ / V2SandboxConfig ✅ / Docker shadow override 删除 ✅ / AgentSpecLoader shadow 删除 ✅ / HarnessA2aRunnerV2 sandbox/distributed 接线 ✅ / MysqlDistributedStore 替代 SandboxDistributedOptions ✅）|
| 阶段 5 | 维度状态 + Artifact + 多数据源挂载 | 2 周 | ⬜ |
| 阶段 6 | 元工具 + SSE 流式挂载 | 1-2 周 | ✅（8 工具类迁移 + 3 Hook 迁移 + V2ToolConfig + V2ChatStreamService + V2ChatController 更新 + PythonExecRetryHook 接线）|
| 阶段 7 | v2 全链路验证 + session 灰度切换 | 2 周 | ✅（技能基础设施迁移 + ToolCallTrackingHook 重构 + SessionMiddleware 创建 + ToolCallCollector 持久化 + V2ToolGroupAdapter + V2SessionRouter + ADR-7.2；灰度切换推迟到 Stage 8）|
| 阶段 8 | 观察期 | 2-4 周 | ⬜ |
| 阶段 9 | v1 整体删除 + 回归 + 内网部署 | 1 周 | ⬜ |

**合计**：13-16 周（不含观察期）/ 15-20 周（含观察期）

**阶段 1 实际耗时**：约 6 小时（vs 计划 1-2 周）-- 因 dual-track 假设不成立、5 shadow override 提前删除、自定义 middleware / 工具挂载推迟到阶段 2，工作量大幅缩减。剩余工作量转入阶段 2+。
