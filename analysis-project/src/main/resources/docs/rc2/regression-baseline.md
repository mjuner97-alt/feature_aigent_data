# 回归测试基线（v1.1.0-RC2 二开）

> 记录日期：2026/07/13
> 升级分支：`upgrade/2.0.0-RC5-dual-track`
> 用途：作为阶段 7-9 全链路验证 + 性能回归的对比基线。本基线基于代码分析 + 文档记录，非实跑数据；阶段 7 前需在 v1 入口实跑一次补齐性能数据。
> 关联文档：`UPGRADE_PLAN.md` §6 验收标准、`business-modules-migration-checklist.md`

---

## 一、基线记录方式

本基线分两层：
1. **功能基线**：v1 入口核心功能清单 + 当前实现方式（基于代码分析）
2. **性能基线**：阶段 7 前需在 v1 入口实跑记录（首请求延迟 / 并发吞吐 / 上下文 token / 工具调用延迟）

---

## 二、功能基线（v1 入口核心功能）

### 2.1 SSE 流式聊天

| 项 | v1 现状 |
|---|---|
| 入口 | `controller/ChatController.java`（SseEmitter，servlet 栈）|
| Agent 入口 | `harness/runner/HarnessA2aRunner.java`（242 行，v1 二开包装）|
| 流式 API | `Flux<Event> stream(...)`（v2 已 `@Deprecated(forRemoval=true)`）|
| 事件类型 | `Event` / `EventType` / `EventSource`（v1，未类型化）|
| 中断 / 取消 | v1 无 `InterruptControl`，依赖 Reactor `Flux.cancel()` |
| HITL 人机协同 | v1 Hook 系统拦截，无 `RequireUserConfirmEvent` |
| Channel 路由 | v1 单线程编排，无 `Channel` / `Gateway` |
| BotLoopGuard | v1 无（内网多 bot 死循环风险）|

**回归点**：v2 升级后 28 typed `AgentEvent` 流式正常；中断/取消保留上下文；HITL 完整链路；`Channel` per-session 并发；`BotLoopGuard` 拦截死循环。

### 2.2 工具调用

| 项 | v1 现状 |
|---|---|
| 工具注册 | `Toolkit` + `@Tool`（v1）|
| 业务工具 | `DataPrimitivesTool` / `QualityTools` / `SkillSaveTool` / `CaptureSkillSaveTool` / `PythonExecTool` / `KnownEntities` |
| 工具路由 | `ToolRoutersIndex`（347 行，二开定制）|
| Python 执行 | `PythonExecTool` + `PythonExecRetryHook`（沙箱执行 + 重试）|
| 异步工具 | v1 无 `AsyncToolMiddleware` / `MessageBus` / `InboxMiddleware` |
| ToolGroup | v1 无（工具全部常驻 context）|
| MCP 集成 | v1 无 |
| 大工具结果卸载 | v1 无（80K+ 字符结果直接占 context）|
| 工具调用追踪 | `ToolCallCollector` / `ToolCallTrackingHook`（二开 Hook）|

**回归点**：v2 升级后工具调用全链路正常；`ToolGroup` 按需激活省 context；异步工具后台化；`ToolResultEviction` 80K 字符落盘。

### 2.3 子 agent 编排

| 项 | v1 现状 |
|---|---|
| 子 agent 声明 | `SubagentsHook`（302 行 shadow override）|
| Agent spawn | `AgentSpawnTool`（590 行 shadow override）|
| Spec 加载 | `AgentSpecLoader`（284 行 shadow override）|
| 同步 / 后台 | v1 仅同步 |
| 流式转发 | v1 无 `SubagentExposedEvent` |
| 自动反向通知 | v1 无 |
| 远程 stub | v1 无 |

**回归点**：v2 升级后子 agent 编排正常（同步/后台/流式转发）；3 个 subagent shadow override 删除。

### 2.4 记忆

| 项 | v1 现状 |
|---|---|
| 会话状态 | `MySQLSession`（per-agent mutable）|
| 长期记忆 | `EpisodicMemory`（MySQL FTS5）+ `MEMORY.md` MySQL 镜像 |
| Episodic 适配 | `EpisodicLongTermMemoryAdapter` |
| Episodic shadow override | `EpisodicMemory` / `EpisodicResult` / `MemoryProvider`（91+31+139 行）|
| Memory Hook | `MemoryFlushHook` / `MemoryMaintenanceHook`（100+202 行 shadow override）|
| 双层记忆 | v1 无（无 flush / consolidation 双层）|
| 对话压缩 | v1 无 `CompactionConfig` |
| 三处独立 LLM | v1 无（flush/consolidation/compaction 走主模型）|
| memory_search 工具 | v1 无（agent 不能自服务检索）|
| digestion 体系 | `MemoryDigestionService` / `MemoryFlowConsolidator` / `SkillFlowEvolver` / `TraceMiner`（4 文件，后台任务）|
| MySQL 镜像 | `MysqlMemoryStore` + `MemoryHydrator` + `MemoryFileWatcher`（双写）|

**回归点**：v2 升级后 `memory/YYYY-MM-DD.md` 生成；`MEMORY.md` 出现在 system prompt；三处 LLM 走小模型 token 成本降 60%+；`CompactionConfig` 触发；`ToolResultEvictionConfig` 80K+ 字符落盘；FTS5 检索正常；5 个 memory shadow override 删除。

### 2.5 技能（skills 自由化）

| 项 | v1 现状 |
|---|---|
| 主动/触发生成 | `SkillSynthesisRunner`（636 行）|
| 演化 | `SkillEvolutionRunner`（442 行）|
| 蒸馏 | `SkillDistiller`（648 行）|
| 向量索引 | `SkillVectorIndex`（372 行）|
| 语义指纹 | `FingerprintCalculator`（246 行）|
| 指标分类 | `MetricClassificationService`（413 行）|
| 仓库 | `SkillIndexRepository`（MySQL）|
| 候选 | `SkillCandidate` / `SkillCandidateRepository` |
| Embedding | `EmbeddingClient` / `OpenAiCompatEmbeddingClient`（内网 OpenAI 兼容 API）|
| Skill 保存 | `SkillSaveTool` / `CaptureSkillSaveTool` |
| 检索 Hook | `SkillRetrievalHook`（四阶段路由：L1/L2 user + L1/L2 auto）|
| 合成 Hook | `SkillSynthesisHook` |
| 演化 Hook | `SkillEvolutionHook` |
| SkillCurator 闭环 | v1 无（propose / promote / usage / audit / security / curator）|
| SkillPromotionGate | v1 无 |
| SkillSecurityScanner | v1 无（agent 自写 skill 无安全扫描）|
| SkillVisibilityFilter | v1 无（无灰度发布）|
| MarketplaceStager | v1 无（无 SHA-256 文件级去重）|
| 4 层合成 | v1 无（仅单层 workspace skills）|

**回归点**：v2 升级后 `propose_skill` 起草草稿；`SkillPromotionGate` 审批；`SkillSecurityScanner` 拦截恶意 shell；`CanaryFilter` 10% 灰度；`SkillAuditLog` 记录 propose/promote/使用计数；v1 保留部分（蒸馏/向量索引/语义指纹/指标分类）与 v2 框架对接顺畅；"越用越聪明"效果不退化。

### 2.6 维度状态

| 项 | v1 现状 |
|---|---|
| 维度管理 | `DimensionStateManager` |
| 维度提示 | `DimensionPrompts` |
| 维度状态 | `DimensionState` |
| 维度服务 | `LlmDimensionService` / `OpenAILlmDimensionService` |
| 问题分析 | `QuestionAnalysis` |
| 异常 | `DimensionException` |
| 注入方式 | v1 Hook 注入 system prompt |
| AgentState 4 子上下文 | v1 无（PermissionContextState/ToolContextState/TaskContextState/PlanModeContextState）|
| InterruptControl | v1 无 |
| TaskContextState + TodoTools | v1 无（无 TaskList）|

**回归点**：v2 升级后 `DimensionStateMiddleware` onSystemPrompt 注入正常；v2 子上下文（Task/Permission/PlanMode）用框架标准；两者通过 `RuntimeContext` 传递不互相序列化。

### 2.7 多数据源

| 项 | v1 现状 |
|---|---|
| 业务路由 | MySQL / ClickHouse / GaussDB（`config/datasource/`）|
| Mapper | `mapper/ck/` / `mapper/db1/` / `mapper/gauss/` / `mapper/mysql/` |
| DistributedStore | v1 无 |
| JdbcStore / JdbcAgentStateStore | v1 无（v1 用 `MySQLSession` 自管）|
| BaseStore + NamespaceFactory | v1 无 |

**回归点**：v2 升级后多数据源业务路由正常；`DistributedStore` 一行注入生效（stateStore + baseStore + executionGuard + snapshotSpec 自动接好）。

### 2.8 Artifact 体系

| 项 | v1 现状 |
|---|---|
| Artifact 存储 | `ArtifactStore`（MySQL）|
| 表格提取 | `TabularExtractor` |
| Artifact 上下文 | `ArtifactContext` / `ArtifactRef` |
| 本地 IO | `LocalArtifactIo` |
| SSH 远端 IO | `SshArtifactIo` |
| 清理 | `ArtifactSweeper` |
| 访问控制 Hook | `ArtifactAccessHook`（onActing）|
| 交接 Hook | `ArtifactHandoffHook`（onAgent）|
| CompositeFilesystem | v1 无 |
| RemoteFilesystem + JdbcStore | v1 无（无跨副本共享）|
| NamespaceFactory | v1 无 |

**回归点**：v2 升级后 Artifact 跨副本共享（节点 A 写入 -> 节点 B 读取）；`CompositeFilesystem` 多后端组合正常。

### 2.9 沙箱

| 项 | v1 现状 |
|---|---|
| Docker CLI | `DockerCliRunner`（151 行 shadow override）|
| Docker 沙箱 | `DockerSandbox`（397 行 shadow override）|
| 沙箱客户端 | `DockerSandboxClient`（228 行 shadow override）|
| 沙箱配置 | `SandboxProperties` / `application-sandbox-{linux,windows,linux-remote}.properties` |
| 内网定制 | registry 镜像管理 / CLI 命令审计 / 命令白名单 / 连接池 / 鉴权 / 健康检查 / 镜像预拉取 / 容器启动 hook |
| IsolationScope.USER | v1 无（无多租户隔离）|
| SandboxExecutionGuard | v1 无（无并发锁，多副本 last-write-wins 风险）|
| JdbcSnapshotSpec | v1 无（本地文件快照，无跨副本恢复）|
| SessionSandboxStateStore | v1 无（沙箱状态不走 AgentStateStore）|
| WorkspaceSpec + WorkspaceEntry | v1 无（无结构化布局）|
| 跨副本 resume | v1 无 |
| E2B 云沙箱 | 不引入（保留本地 / Docker / SSH 远端）|

**回归点**：v2 升级后 `DockerSandbox.start()/stop()/exec()` 行为与 v1 等价；跨副本 resume 成功；`JdbcSandboxExecutionGuard` 并发锁有效；`IsolationScope.USER` 多租户隔离；3 个 Docker shadow override 删除；v1 内网定制保留在子类。

### 2.10 Plan Mode

| 项 | v1 现状 |
|---|---|
| PlanNotebook | 8 工具，结构化 Plan+SubTask 状态机 |
| Plan Mode（v2）| 3 工具：`plan_enter`/`plan_write`/`plan_exit`，markdown 文件 + HITL 退出 |
| HITL 退出 | v1 无 |
| plan 状态跨重启恢复 | v1 无 |

**回归点**：v2 升级后 `plan_enter` -> `plan_write` -> `plan_exit` + HITL 完整链路；plan 状态跨重启恢复。

### 2.11 Permission 系统

| 项 | v1 现状 |
|---|---|
| 独立权限层 | v1 无 |
| PermissionEngine | v1 无 |
| 5 mode | v1 无 |
| HITL 审批 | v1 无 `UserConfirmResultEvent` |

**回归点**：v2 升级后 5 mode 切换正常；HITL 审批流程跑通。

---

## 三、性能基线（阶段 7 前实跑补齐）

| 指标 | v1 基线 | v2 目标 | 测试方法 |
|---|---|---|---|
| 首请求延迟 | 待实跑 | ≤ v1 基线 | 单次 SSE 聊天 cold start |
| 并发吞吐 | 待实跑 | ≥ v1 基线 | N 并发 SSE 聊天 |
| 上下文 token 数（长对话）| 待实跑 | v2 触发 `CompactionConfig` 后下降 | 50 轮对话后测 system prompt token |
| 大工具结果占用 context | 待实跑 | v2 `ToolResultEviction` 80K+ 字符落盘 | 调用返回 100K 字符的工具，测 context 占用 |
| 三处 LLM token 成本 | v1 走主模型 | v2 走 `openai:gpt-4.1-mini`，降 60%+ | flush/consolidation/compaction LLM 调用累计 token |
| 沙箱启动延迟 | 待实跑 | ≤ v1 基线 | `DockerSandbox.start()` 单次延迟 |
| 沙箱跨副本 resume | v1 无 | 演示成功 | 节点 A 创建 -> 节点 B resume |
| FTS5 检索延迟 | 待实跑 | ≤ v1 基线 | `MySqlEpisodicMemory.search()` 单次延迟 |

**阶段 7 前补齐方法**：
1. v1 入口启动（`mvn spring-boot:run -Dspring-boot.run.profiles=dev`）
2. 跑 5 个核心场景：SSE 聊天 / 工具调用 / 子 agent / 记忆检索 / 技能查询
3. 记录延迟 / 吞吐 / token / 资源消耗
4. 填入本表"v1 基线"列

---

## 四、阶段 7-9 验收对照

阶段 7-9 全链路验证时，逐项对照本基线：

| 验收项 | v1 基线 | v2 实测 | 是否通过 |
|---|---|---|---|
| SSE 流式聊天 | §2.1 | 待测 | ⬜ |
| 工具调用全链路 | §2.2 | 待测 | ⬜ |
| 子 agent 编排 | §2.3 | 待测 | ⬜ |
| 跨会话记忆一致 | §2.4 | 待测 | ⬜ |
| 技能闭环 | §2.5 | 待测 | ⬜ |
| 维度状态注入 | §2.6 | 待测 | ⬜ |
| 多数据源 | §2.7 | 待测 | ⬜ |
| Artifact 体系 | §2.8 | 待测 | ⬜ |
| Docker 沙箱 | §2.9 | 待测 | ⬜ |
| Plan Mode | §2.10 | 待测 | ⬜ |
| Permission 系统 | §2.11 | 待测 | ⬜ |
| HITL 人机协同 | v1 无 | 待测 | ⬜ |
| 中断 / 取消 | v1 无 | 待测 | ⬜ |
| 首请求延迟 | 待跑 | ≤ v1 | ⬜ |
| 并发吞吐 | 待跑 | ≥ v1 | ⬜ |
| 上下文压缩 | v1 无 | 触发 | ⬜ |
| 大工具结果卸载 | v1 无 | 80K 落盘 | ⬜ |
| 三处 LLM token | v1 主模型 | 降 60%+ | ⬜ |

---

## 五、已知风险点（阶段 7-9 重点验证）

1. **Memory 体系双轨冲突**：v2 双层记忆与二开 digestion 可能重复 flush -- 通过 `MemoryConfig.flushTrigger=THROTTLED` 节流
2. **v1 Episodic 与 v2 双层记忆语义重叠**：明确分工，前缀标注来源（`[Episodic]` vs `[MEMORY.md]`）
3. **PlanNotebook -> Plan Mode 业务逻辑不兼容**：8 工具状态机 -> 3 工具 markdown，评估二开 PlanNotebook 业务依赖
4. **`streamEvents()` 子 agent 事件转发**：v2 子 agent 事件转发依赖 `source` 字段
5. **Spring Boot 3.2 vs v2 兼容性**：保留 SB 3.2.3，逐文件验证 v2 import 兼容性
6. **MySQL Connector 8.2 vs v2 默认 9.6**：保留 8.2，`PersistenceProperties` URL 参数保留
7. **`JdbcSandboxExecutionGuard` MySQL `GET_LOCK` 限制**：会话级锁，连接池借还可能跨会话导致锁泄漏 -- 阶段 4 验证 `SandboxLease.close()` 在同连接释放
8. **`JdbcSnapshotSpec` BLOB 大小限制**：MySQL 默认 `max_allowed_packet=64MB` -- 阶段 0 评估工作区典型大小
9. **v1 算法层 vs v2 工程层数据归一**：字段定义、统计口径可能不一致 -- 阶段 3 前先做字段映射表
10. **`AsyncToolMiddleware` 与 v1 同步工具协议不兼容**：仅长时工具（>30s）启用异步，短工具保持同步
11. **多 Channel 并发与 v1 单线程编排冲突**：审计共享资源线程安全性（`ResponseCacheService` / `MysqlMemoryStore` / `SkillIndexRepository`）
12. **ReActAgent 1906 行定制丢失**：阶段 0 完整提取定制点清单（见 `shadow-override-customization-points.md`），阶段 9 删除前确认所有定制点已迁移
