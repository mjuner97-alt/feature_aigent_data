# AgentScope Java 框架升级方案：1.1.0-RC2 二开 -> 2.0.0-RC5

> 编写日期：2026/07/13
> 升级对象（应用项目）：`D:\AILLMS\javacode\analysis-project\analysis-project`，基于 `agentscope-java-1.1.0-RC2` 二开的 Spring Boot 应用
> 升级目标（库版本）：`agentscope-java-2.0.0-RC5`（作为 jar 依赖引入）
> 部署目标：内网环境
> 升级方式：**双轨并行，v2 builder 为新骨架**（v1 入口保留到 v2 入口跑通全链路 + 切流量 + 观察期后整体删除）

---

## 〇·I、阶段 5 实施状态（2026/07/14 更新 - backfill）

> 阶段 5 已实施完成。早期 Stage 5 完成了 ResponseCache / Artifact / SkillVectorIndex 迁移；Stages 6-8 完成后，回过头补齐分布式文件系统 + 远程 Artifact IO + backstop GC + 废弃文件清理。

### 已完成（补齐部分）

**RemoteFilesystemSpec 接入 HarnessA2aRunnerV2（主缺口）：**
- `HarnessA2aRunnerV2` 新增 `ObjectProvider<RemoteFilesystemSpec>` 参数 + else-if 分发块
- 镜像 v1 `SupervisorService` either-or 逻辑：sandbox 优先，否则用 remote
- 当 `distributed.enabled=true` 且 `sandbox.enabled=false` 时，文件操作真正路由到 MySQL-backed BaseStore
- 自动激活（RemoteFilesystemSpec 内部）：`CompositeFilesystem` + `OverlayFilesystem` + `NamespaceFactory` + `WorkspaceIndex`

**SshArtifactIo 条件化装配：**
- `V2InfraConfig.artifactStore()` 新增 `SandboxPropertiesV2` 参数
- `artifacts.remote.enabled=true` 时用 `SshArtifactIo`（CSV 写到远端 Docker 主机）
- 否则用 `LocalArtifactIo`（原行为）

**ArtifactSweeper 迁移：**
- `git mv harness/artifact/ArtifactSweeper.java -> v2/artifact/ArtifactSweeper.java`
- 移除 `@Component`/`@Value`/`@Autowired`，构造函数注入 `(Path, long, boolean, boolean)`
- V2InfraConfig 新增 `artifactSweeper()` bean
- 保留 `@Scheduled(cron = "0 17 * * * *")` backstop GC

**SshHealthCheck 迁移：**
- `git mv harness/config/SshHealthCheck.java -> v2/sandbox/SshHealthCheck.java`
- 构造函数参数 `SandboxProperties` -> `V2SandboxConfig.SandboxPropertiesV2`
- 保留 `@Component` + `@ConditionalOnProperty` + `@PostConstruct`
- 启动时验证 SSH ControlMaster socket，WARN-on-failure（不 fail-fast）

**废弃 v1 文件删除（3 文件）：**
- `harness/config/JedisBaseStore.java` - v2 用 `MysqlDistributedStore`，不引入 Redis
- `harness/workspace/RemoteDirSyncer.java` - v2 `RemoteFilesystemSpec` + `BaseStore` 替代双向 SSH sync
- `harness/workspace/RemoteWorkspaceSyncService.java` - 依赖 `RemoteDirSyncer`，同步废弃

**编译验证**：`mvn clean compile` -> BUILD SUCCESS，v2 源文件 73 -> 75（+2：ArtifactSweeper + SshHealthCheck）

### 已完成验证项（prior stages，无需代码变更）

| 项 | 完成阶段 | 证据 |
|----|----------|------|
| 多数据源 config（MySQL @Primary / ClickHouse / GaussDB） | Stage 2 | `config/datasource/{MySQL,ClickHouse,Gauss}Config.java` 均编译并提供 bean |
| `DistributedStore` 一行注入 | Stage 4 | `V2SandboxConfig.distributedStore()` -> `MysqlDistributedStore.create(dataSource)` |
| `JdbcAgentStateStore` / `MysqlAgentStateStore` | Stage 2 | `HarnessA2aRunnerV2`: `.stateStore(new MysqlAgentStateStore(dataSource))` |
| `TaskContextState` + `TodoTools` | Stage 1 | `HarnessA2aRunnerV2`: `.enableTaskList(true)` |
| `AgentState` 4 子上下文 | builder 默认 | PermissionContextState/ToolContextState/TaskContextState/PlanModeContextState 默认启用 |
| `InterruptControl` | builder 默认 | per-session 中断信号默认启用 |

### 架构决策

| 决策 | 原因 |
|------|------|
| either-or filesystem 分发镜像 v1 SupervisorService | sandbox 与 remote 互斥（config 控制），v1 已验证 |
| SshArtifactIo 条件基于 `artifacts.remote.enabled` | artifact IO 专用配置，与 Docker CLI 的 `sandbox.remote-docker-*` 分离 |
| ArtifactSweeper 保留 `@Scheduled` | `@EnableScheduling` 在 Stage 1 已激活，cron :17 避开拥堵 |
| SshHealthCheck 保留 `@Component` + `@ConditionalOnProperty` | 条件注解本身就是 gating，比 config 复制更简洁 |
| 删除 RemoteDirSyncer / RemoteWorkspaceSyncService | v2 BaseStore 架构"无同步"，v1 SSH sync 是 Redis 未引入时的权宜之计 |
| 删除 JedisBaseStore | v2 不引入 Redis，保留死代码会造成误导 |

### 阶段 5 验收缺口（需运行时验证）

- `harness.a2a.distributed.enabled=true` + `sandbox.enabled=false`：日志显示 "remote filesystem wired (scope=USER)"
- `WorkspaceIndex.open(workspace)` 触发：SQLite 文件出现在 workspace
- 两副本收敛：副本 A 写 `skills/foo.md` -> 副本 B 通过 BaseStore 读到
- `artifacts.remote.enabled=true`：日志显示 "SshArtifactIo wired"，CSV 落在远端主机
- `ArtifactSweeper` cron :17 触发：过期 bucket（>6h）被清理
- `sandbox.remote-docker-enabled=true`：启动日志显示 `SshHealthCheck` SUCCESS 或 WARN
- 多数据源：`clickHouseDataSource` + `gaussDataSource` bean 在 `*.enabled=true` 时加载（当前无 v2 代码消费）

### 延期项

| 项 | 原因 | 目标阶段 |
|----|------|----------|
| 多数据源消费层（v2 tools 用真实 DB 查询替代 mock 数据） | 属于业务逻辑层，非基础设施；当前 v2 tools 用 mock 数据 | Stage 9+（按业务需求驱动） |
| 两副本收敛运行时验证 | 需部署双副本 + 跨副本读写验证 | Stage 9（集成测试） |
| `SshArtifactIo` / `ArtifactSweeper` / `SshHealthCheck` 运行时验证 | 需配置对应 flag + 真实环境 | Stage 9（集成测试） |

---

## 〇·F、阶段 6 实施状态（2026/07/14 更新）

> 阶段 6 已实施完成。

### 已完成

**工具类迁移（7 文件 git mv + 包声明更新）：**
- `DataPrimitivesTool` → `v2/tools/`，`SandboxProperties` → `SandboxPropertiesV2`，移除 `@Component`
- `QualityTools` → `v2/tools/`，无外部依赖
- `SkillSaveTool` → `v2/tools/`，依赖更新（`EmbeddingClient`/`SkillVectorIndex`/`SkillEntry`/`SkillIndexRepository` → `v2.skills`）
- `CaptureSkillSaveTool` → `v2/tools/`，无外部依赖
- `PythonExecTool` → `v2/tools/`，`SandboxPropertiesV2`/`PythonExecPropertiesV2` 替代
- `AgentTools` → `v2/tools/`，构造函数注入 `QualityTools`
- `MdTableParser` → `v2/tools/`，无外部依赖
- `ToolRoutersIndex` → `v2/tools/`，移除 `@Component`/`@Autowired`，构造函数注入

**技能支持迁移（2 文件 git mv）：**
- `SkillEntry` → `v2/skills/`，移除 `@Repository`/`@Qualifier`，由 `V2ToolConfig` 显式创建
- `SkillIndexRepository` → `v2/skills/`，移除 `@Repository`/`@Qualifier`

**Hook 迁移（3 文件 git mv）：**
- `PythonExecRetryHook` → `v2/hooks/`，`@SuppressWarnings("deprecation")`，`V2InfraConfig` bean
- `ToolCallTrackingHook` → `v2/hooks/`，`@SuppressWarnings("deprecation")`，import 更新
- `ToolCallCollector` → `v2/tools/`，Javadoc 引用更新

**新建文件：**
- `v2/config/V2ToolConfig.java` — Spring `@Configuration`，创建 7 个工具 beans + `PythonExecPropertiesV2` 内部类
- `v2/service/V2ChatStreamService.java` — 接口：`SseEmitter stream(ChatRequest req)`
- `v2/service/V2ChatStreamServiceImpl.java` — 实现：ToolCallCollector bind/unbind、RuntimeContext 构建、AgentEvent→AiChatResult SSE 映射、ArtifactStore cleanup

**更新文件：**
- `V2InfraConfig` 新增 `PythonExecRetryHook` bean
- `HarnessA2aRunnerV2` 新增 `ObjectProvider<PythonExecRetryHook>` 参数，`.hook(pythonExecRetryHook)` 接线
- `V2ChatController` 简化为委托 `V2ChatStreamService.stream()`

**编译验证**：`mvn clean compile` → BUILD SUCCESS，56 个 v2 源文件

### 架构决策

| 决策 | 原因 | 影响 |
|------|------|------|
| `ToolRoutersIndex` 保留路由模式 | `router_tool` + `toolMetaInfo` 二步式路由是前端技能系统核心模式，ToolGroup 迁移影响面大 | 后续独立阶段迁移到 `ToolGroup` |
| `PythonExecRetryHook` 使用已弃用 Hook API | 同 ADR-5.1，工具结果重写需要 `PostActingEvent` | 标记 `@SuppressWarnings("deprecation")`，待框架更新后迁移 |
| `ToolCallTrackingHook` 使用已弃用 Hook API | 需要同时拦截 `PreActingEvent`（参数）和 `PostActingEvent`（结果） | Middleware 无对称钩子替代 |
| `V2ChatStreamServiceImpl` 简化 SSE 映射 | 缓存命中已在中间件层处理，子代理工具上下文注入推迟 | 后续阶段补充 ToolCallCollector 持久化 |
| `PythonExecPropertiesV2` 作为内部类 | v1 `PythonExecProperties` 在 excluded 包，需要在 v2 配置中重新定义 | 与 `SandboxPropertiesV2` 模式一致 |

### 关键 API 差异（阶段 6 新增）

| v1 API | v2 替代 | 影响 |
|--------|---------|------|
| `TextBlockStartEvent.getText()` | 不存在（`TextBlockStartEvent` 只是标记事件） | 高：流式文本只从 `TextBlockDeltaEvent.getDelta()` 获取 |
| `TextBlockDeltaEvent.getText()` | `TextBlockDeltaEvent.getDelta()` | 高：方法名不同 |
| `Msg.getContents()` | `Msg.getContent()` 或 `Msg.getTextContent()` | 中：v2 用 `getContent()` 和便捷方法 `getTextContent()` |

---

## 〇·H、阶段 8 实施状态（2026/07/14 更新）

> 阶段 8 已实施完成。

### 已完成

**Memory Digestion Pipeline 全量迁移（4 文件 git mv）：**
- `TraceMiner` -> `v2/digestion/`，移除 `MySqlEpisodicMemory` import，直接操作 raw JDBC，保留所有失败分类模式 + L1/L2 合并
- `SkillFlowEvolver` -> `v2/digestion/`，移除 `@Component`/`@ConditionalOnProperty`/`@Value`，构造函数 11 参显式注入，保留 dual fingerprint 策略 + user-skill skip rule
- `MemoryFlowConsolidator` -> `v2/digestion/`，import 更新到 v2.memory + v2.digestion
- `MemoryDigestionService` -> `v2/digestion/`，移除 `@Component`/`@ConditionalOnProperty`/`@Qualifier`/`@Value`，`@PostConstruct announce()` 改为公开方法，保留 `@Scheduled` cron + MySQL GET_LOCK 互斥 + 4 阶段 pipeline

**Skill PR2/PR3/PR4 闭环迁移（3 文件 git mv）：**
- `SkillDistiller`（648 行）-> `v2/skills/`，构造函数改为 `(Model, ObjectProvider<EpisodicMemory>, MetricClassificationService, Path workspace)`，import 更新到 v2.tools / v2.memory
- `SkillSynthesisRunner`（636 行）-> `v2/skills/`，移除 `SimpleSessionKey` import（v2 jar 不存在）+ 两处 `RuntimeContext.builder().sessionKey(...)` 调用（v2 RuntimeContext.Builder 无 `sessionKey()` 方法）
- `SkillEvolutionRunner`（442 行）-> `v2/skills/`，保留 `@Scheduled` 在 `cleanupPendingJudgementTable()`，保留 in-process CAS + MySQL cross-JVM lock + pending-judgement cache（L1 LRU + L2 MySQL）

**基础记忆存储迁移（2 文件 git mv）：**
- `MysqlMemoryStore` -> `v2/memory/`，移除 `@Repository`/`@Qualifier`/`@ConditionalOnProperty`，`ensureSchema()` 改为公开方法
- `MemoryHydrator` -> `v2/memory/`，移除 `@Component`/`@Autowired`，构造函数改为 `(Path workspaceMemoryRoot, MysqlMemoryStore store)`

**新建/修改 config：**
- `v2/config/V2DigestionConfig.java` 新建 - `@Configuration`，两个 `@Bean` 方法（`skillFlowEvolver` / `memoryDigestionService`），均带 `@ConditionalOnProperty(harness.a2a.memory.digestion.enabled)` 门禁；light-classifier Model 在 config 内通过 `OpenAIChatModel.builder()` 构建；调用 `svc.announce()` 替代 `@PostConstruct`
- `v2/config/V2MemoryConfig.java` 扩展 - 新增 `MysqlMemoryStore` + `MemoryHydrator` beans，均带 `@ConditionalOnProperty(harness.a2a.memory.mysql-mirror.enabled)` 门禁
- `v2/config/V2SkillConfig.java` 扩展 - 新增 `SkillDistiller` + `SkillSynthesisRunner` + `SkillEvolutionRunner` beans

**V2ChatController 接入灰度路由守卫：**
- 新增 `private final V2SessionRouter sessionRouter;` 字段
- `chat()` 方法新增守卫：当 `conversationId != null && !sessionRouter.shouldUseV2(conversationId)` 时抛出 `V1RoutingNotAvailableException`（HTTP 503）
- 真正的 v1 fallback 推迟到 Stage 9（v1 controller 重新启用后替换为 redirect）

**ToolRoutersIndex 保留为 fallback：**
- `ToolRoutersIndex` bean 仅在 `V2ToolConfig` 中定义，无运行时消费者（`HarnessA2aRunnerV2` 已通过 `V2ToolGroupAdapter` 提供 Toolkit）
- 按计划允许保留：`reset_equipped_tools` meta-tool 运行时验证需启动应用并实际触发工具调用，编译验证无法覆盖
- 移除推迟到 Stage 9 运行时验证后

**编译验证**：`mvn clean compile` -> BUILD SUCCESS，73 个 v2 源文件（67 + 9 新增 - 0 删除 + 1 新 config = 73）

### 架构决策

| 决策 | 原因 | 影响 |
|------|------|------|
| 所有迁移类移除 Spring 注解 | v2 模式要求显式装配，由 `@Configuration @Bean` 方法 + `@ConditionalOnProperty` 控制生命周期 | 类本身为 POJO，可在测试中直接 `new`；条件化由 config 层统一管理 |
| `@PostConstruct` -> 公开方法 + config 显式调用 | v2 不依赖 JSR-250 注解扫描，且 `@Bean` 方法可在构造后立即调用初始化 | `ensureSchema()` / `init()` / `announce()` 由对应 config 在 `return` 前调用 |
| `@Value` -> 构造函数 primitive 参数 | config 类解析 `@Value` 并传 plain value，业务类不耦合 Spring | 类可独立测试，配置项变更不影响类签名 |
| `SimpleSessionKey` 删除 | v2 jar 已删除 `io.agentscope.core.state.SimpleSessionKey`，且 `RuntimeContext.Builder` 无 `sessionKey()` 方法 | `SkillSynthesisRunner` 两处 builder 链移除 `.sessionKey(SimpleSessionKey.of(...))` 调用，sessionKey 改由其他方式传递 |
| `MemoryDigestionService` 保留 `@Scheduled` | Spring 通过 component scan 自动拾取 `@Scheduled`，且 `@EnableScheduling` 已在 `AgentscopeA2aApplication` 启用 | cron 表达式 `${harness.a2a.memory.digestion.cron:0 9 21 * * *}` 默认 21:09 触发 |
| `MySQL GET_LOCK` 保留 | cross-JVM 互斥必须依赖数据库锁，单 JVM 内锁不够 | lock name `memory_digestion_lock`，获取失败静默跳过（其他副本正在执行） |
| `V2ChatController` 抛 503 而非路由 v1 | v1 controller 在 Stage 8 仍 Maven-excluded，无法实际路由 | 真正的 v1 fallback 推迟到 Stage 9（v1 controller 重新启用后替换为 redirect） |
| `ToolRoutersIndex` 保留为 fallback | 运行时验证需启动应用并实际触发 `quality_tools` / `data_primitives` 工具调用，编译验证无法覆盖 | bean 已定义但未消费，移除推迟到 Stage 9 运行时验证后 |
| `TraceMiner` 不做 Spring bean | 由 `MemoryDigestionService` 通过 `new TraceMiner(...)` 直接实例化，每次 digest 创建新实例 | 避免无状态 bean 的 Spring 容器开销；保留 plain class 形态 |
| `EpisodicMemory` 使用 v2 业务接口 | v2 jar 无 `io.agentscope.core.memory.EpisodicMemory`，业务接口 `v2.memory.EpisodicMemory` 是 Stage 3 已建立的 shadow override 迁移产物 | `SkillDistiller` / `SkillSynthesisRunner` 通过 `ObjectProvider<EpisodicMemory>` 注入，可选依赖 |

### 与原计划的偏差

| 偏差 | 原计划 | 实际 | 影响 |
|---|---|---|---|
| 1. `ToolRoutersIndex` 未移除 | 阶段 8 完成运行时验证后移除 | 运行时验证需启动应用 + 实际触发 `reset_equipped_tools` meta-tool，编译验证无法覆盖，按计划允许保留为 fallback | 移除推迟到 Stage 9 运行时验证后 |
| 2. 真正的 v1 fallback 未实现 | 阶段 8 接入 v1/v2 灰度路由（v1 controller redirect） | v1 controller 在 Stage 8 仍 Maven-excluded，`V2ChatController` 路由到 v1 桶时返回 503 而非 redirect | 真正 v1 fallback 推迟到 Stage 9（v1 controller 重新启用后替换） |
| 3. 数据迁移脚本未执行 | 阶段 8 执行 episodic memory / skill_index / user_trace_summary schema 迁移 | 推迟到 Stage 9（v2 digestion pipeline 运行时验证通过后） | 数据迁移与灰度切换绑定，避免过早迁移 |
| 4. 回滚演练未执行 | 阶段 8 完成回滚演练，RTO < 5 分钟 | 推迟到 Stage 9（v1 controller 重新启用后） | 回滚依赖 v1 controller 可用 |

### 阶段 8 验收缺口（需运行时验证）

- `MemoryDigestionService` nightly cron 在 21:09（可配置）触发
- MySQL `GET_LOCK('memory_digestion_lock')` 首副本获取，其他副本静默跳过
- Phase 1 (CleanLedger)：`agent_memory_ledger` 90 天以上记录被删除
- Phase 2 (MineTraces)：`user_trace_summary` 从 `QualitySupervisor_episodic_memory` + L2 subagent 文件正确填充
- Phase 3 (EvolveSkills)：skill 失败率 > 0.3 触发 `SkillDistiller.evolve()`，cross-JVM CAS lock 工作
- Phase 3 (EvolveSkills)：未匹配 trace 触发 `SkillSynthesisRunner.distillForDigestion()` via subagent
- Phase 4 (ConsolidateMemory)：per-user MEMORY.md 通过 LLM 与当日成功 trace 合并
- `digestion_log` 表记录 per-user phase counts + 完成时间戳
- `SkillEvolutionRunner.recordFailure()` bump `skill_index.failure_count` + 异步触发 evolve
- `SkillEvolutionRunner.cachePendingJudgement()` 写入 L1 (LRU) + L2 (`skill_pending_judgement`)
- `V2SessionRouter.shouldUseV2()` 对 100% v2 配置始终返回 true
- `V2ToolGroupAdapter` `reset_equipped_tools` meta-tool 在 LLM 工具列表中（如 ToolRoutersIndex 移除条件成立）
- `MysqlMemoryStore` + `MemoryHydrator` 在 mysql-mirror.enabled=true 时正确装配
- `V2ChatController` 当 `harness.routing.v2-percentage < 100` 且 conversationId 命中 v1 桶时返回 HTTP 503

---

## 〇·G、阶段 7 实施状态（2026/07/14 更新）

> 阶段 7 已实施完成。

### 已完成

**技能基础设施迁移（4 文件 git mv）：**
- `MetricClassificationService` -> `v2/skills/`，移除 `@Service`/`@Value`/`@PostConstruct`，构造函数注入 `(SkillCandidateRepository, Model, boolean, Path)`，`init()` 由 V2SkillConfig 调用
- `SkillCandidate` -> `v2/skills/`，包声明更新
- `SkillCandidateRepository` -> `v2/skills/`，移除 `@Repository`/`@Qualifier`，`initSchema()` 由 V2SkillConfig 调用
- `FingerprintCalculator` -> `v2/skills/`，移除 `@Component`，import 更新到 v2 包

**Hook 重构 + Middleware 创建：**
- `ToolCallTrackingHook` 重构为无参构造函数 + ThreadLocal 模式（`ToolCallCollector.getCurrent()`），singleton bean
- `SessionMiddleware` 新建（v2 middleware，清理 tool call input 中的 regex 模式，替代 v1 `SessionHook`）

**ToolCallCollector 持久化（路径 B/C 打通）：**
- `V2ChatStreamServiceImpl` 注入 `EpisodicMemory`，cleanup 中将 tool call context 写入情景记忆
- `collector.bind()` 从 HTTP 线程移到 executor 线程（修复 ThreadLocal 跨线程问题）
- `EpisodicMemory` 接口新增 `recordSessionWithToolContext()` 方法签名

**V2ToolGroupAdapter 创建（Toolkit + ToolGroup + MetaTool）：**
- 新建 `v2/tools/V2ToolGroupAdapter.java`，Builder 模式，创建 `quality_tools` + `data_primitives` 两个 META scope 工具组
- 注册 `reset_equipped_tools` meta-tool（LLM 可动态切换工具组）
- `V2ToolConfig` 新增 `V2ToolGroupAdapter` bean
- `HarnessA2aRunnerV2` 通过 `ObjectProvider<V2ToolGroupAdapter>` 接线 `.toolkit(adapter.getToolkit())`
- `ToolRoutersIndex` bean 保留作为 fallback（待运行时验证后移除）

**V2SessionRouter 创建（灰度路由预备）：**
- 新建 `v2/routing/V2SessionRouter.java`，基于 `harness.routing.v2-percentage` 配置（默认 100% v2）
- Session stickiness：同一 conversationId 始终路由到相同入口
- 当前默认 100% v2，v1 controller 重新启用后可用于灰度切换

**BotLoopGuard / IdempotencyStore ADR：**
- ADR-7.2 确认两者为 Channel-level 组件，不适用于 `streamEvents()` runner
- `HarnessAgent.Builder` 不暴露 `.botLoopGuard()` / `.idempotencyStore()` 方法
- 推迟到 Stage 8+（Channel/Gateway 集成时再考虑）

**其他变更：**
- `V2SkillConfig` 新增 `SkillCandidateRepository` / `MetricClassificationService` / `FingerprintCalculator` 三个 bean
- `V2InfraConfig` 新增 `ToolCallTrackingHook` / `SessionMiddleware` 两个 bean
- `HarnessA2aRunnerV2` 新增 `ToolCallTrackingHook` hook + `SessionMiddleware` middleware + `V2ToolGroupAdapter` toolkit
- `ChatRequest` 移除对 v1 service 包的 import 依赖（`ChatStreamServiceV_3`）

**编译验证**：`mvn clean compile` -> BUILD SUCCESS，67 个 v2 源文件

### 架构决策

| 决策 | 原因 | 影响 |
|------|------|------|
| `ToolCallTrackingHook` 改为无参 + ThreadLocal | V2ChatStreamServiceImpl 已通过 bind/unbind 管理 ToolCallCollector 生命周期，Hook 通过 ThreadLocal 获取，避免 per-request 创建 Hook 实例 | Hook 作为 singleton bean 在 HarnessA2aRunnerV2 中接线 |
| `SessionMiddleware` 实现 MiddlewareBase 接口 | v2 MiddlewareBase 是 interface（非 abstract class），必须用 `implements` 而非 `extends` | onActing 签名为 `(Agent, RuntimeContext, ActingInput, Function) -> Flux` |
| `ToolCallCollector.bind()` 移到 executor 线程 | 原来 bind() 在 HTTP 线程执行，但 streamEvents() 在 executor 线程执行，ThreadLocal 不跨线程 | bind() 移到 `Executors.newSingleThreadExecutor().submit()` 内部，确保 hook 可访问 |
| `V2ToolGroupAdapter` 使用 Builder 模式 | Toolkit 注册 API 较复杂，Builder 提供流式 API | V2ToolConfig 通过 `V2ToolGroupAdapter.builder()` 配置工具组 |
| `ToolRoutersIndex` 保留作为 fallback | Toolkit 路径未运行时验证，保留旧路由作为回退 | 待 reset_equipped_tools meta-tool 验证通过后移除 |
| `V2SessionRouter` 默认 100% v2 | 当前只有 v2 入口启用，v1 路由在 Stage 8+ 灰度切换时启用 | 不影响当前行为，预留灰度能力 |
| `BotLoopGuard` / `IdempotencyStore` 不迁移 | Channel-level 组件，不适用于 streamEvents() runner | 未来如需 webhook 集成，创建 Channel adapter |
| `EpisodicMemory` 接口新增 `recordSessionWithToolContext()` | V2ChatStreamServiceImpl 通过接口引用而非具体类，保持依赖倒置 | MySqlEpisodicMemory 已有实现，接口补全签名 |

### 与原计划的偏差

| 偏差 | 原计划 | 实际 | 影响 |
|---|---|---|---|
| 1. 灰度切换推迟 | 阶段 7 完成 v1/v2 灰度路由（10% -> 30% -> 50% -> 100%） | V2SessionRouter 创建但默认 100% v2，v1 controller 未重新启用 | 灰度切换推迟到 Stage 8（v1 controller 重新启用后） |
| 2. 数据迁移脚本未执行 | 阶段 7 执行会话状态 / 长期记忆 / 沙箱快照 / 技能库 schema 迁移 | 推迟到 Stage 8（v2 全链路运行时验证通过后） | 数据迁移与灰度切换绑定，避免过早迁移 |
| 3. v2 全链路运行时验证未执行 | 阶段 7 完成 11 项功能运行时验证 | 编译验证通过，运行时验证推迟到 Stage 8 启动时 | 验收缺口见 §〇·G |
| 4. 回滚演练未执行 | 阶段 7 完成回滚演练，RTO < 5 分钟 | 推迟到 Stage 8（v1 controller 重新启用后） | 回滚依赖 v1 controller 可用 |

### 阶段 7 验收缺口（需运行时验证）

- MetricClassificationService 加载 metric-categories.yaml 配置正确
- MetricClassificationService ruleBasedTag() 关键词匹配正确
- FingerprintCalculator computeMetric() 生成正确的语义指纹
- FingerprintCalculator compute() 生成正确的维度指纹（用于 ResponseCache）
- ToolCallTrackingHook 通过 ThreadLocal 正确追踪 L1 工具调用（bind 在 executor 线程）
- SessionMiddleware 清理 tool call input 中的 regex 模式
- SkillCandidateRepository 的 DDL 自动创建和 incrementHit/findByFingerprint 操作
- V2SkillConfig 正确创建所有 bean 并调用 init() 方法
- ToolCallCollector 持久化到情景记忆正确工作（路径 B/C）
- V2ToolGroupAdapter 正确创建工具组并注册 meta-tool
- `reset_equipped_tools` meta-tool 允许 LLM 动态切换工具组
- V2SessionRouter 默认路由 100% 到 v2

---

## 〇·D、阶段 4 实施状态（2026/07/13 更新）

> 阶段 4 已实施完成。

### 已完成

- `SharedContainerDockerSandboxClient` 创建（实现 `SandboxClient<DockerSandboxClientOptions>`，共享容器模式 + deserialize 修复）
- `DockerCliRunner` git mv 从 `io.agentscope.harness.agent.sandbox.impl.docker/` 到 `v2/sandbox/`，方法可见性改为 public
- `V2SandboxConfig` 创建（Spring beans for `DockerFilesystemSpec` + `DistributedStore` + `RemoteFilesystemSpec` + `SandboxPropertiesV2`）
- `V2SandboxConfig` 内嵌 `SandboxPropertiesV2` / `DistributedPropertiesV2`（因 v1 版本在 excluded 包）
- `HarnessA2aRunnerV2` 新增 `ObjectProvider<SandboxFilesystemSpec>` + `ObjectProvider<DistributedStore>` 构造参数，条件化接线
- `MysqlDistributedStore.create(dataSource)` 替代已删除的 `SandboxDistributedOptions`
- `DockerFilesystemSpec` 使用正确 v2 import path（`io.agentscope.harness.agent.sandbox.impl.docker`）
- 3 个 Docker shadow override 文件删除（`DockerSandboxClient.java` / `DockerSandbox.java` / `DockerCliRunner.java` moved）
- `AgentSpecLoader` shadow override 删除（v2 有完整版 `io.agentscope.harness.agent.subagent.AgentSpecLoader`）
- 编译验证通过（`mvn clean compile` → BUILD SUCCESS，47 source files）

### 与原计划的偏差

| 偏差 | 原计划 | 实际 | 影响 |
|---|---|---|---|
| 1. RemoteDockerSandbox 未创建 | 创建 `RemoteDockerSandbox` 子类 | 推迟到阶段 5+（v2 `DockerSandbox` 私有方法无法覆写，需改用 `DOCKER_HOST` 环境变量或 wrapper script）| 低影响：SSH remote 模式需额外配置 |
| 2. Redis 分布式模式未启用 | 可选用 `JedisBaseStore` + `RedisSandboxExecutionGuard` | 当前仅用 `MysqlDistributedStore`（MySQL-backed，不需要 Redis 依赖）| 低影响：MySQL 模式满足内网需求 |
| 3. V2FilesystemConfig 合并到 V2SandboxConfig | 分开创建 | 合并（减少文件数，sandbox 和 distributed 配置逻辑紧密相关）| 无影响 |

### 阶段 4 验收缺口（需运行时验证）

- Shared container 模式生效（`SharedContainerDockerSandboxClient.create()` resolve via docker inspect）
- DockerCliRunner SSH remote 模式配置生效
- SharedContainerDockerSandboxClient 反序列化修复生效（`deserializeState()` → `DockerSandboxState.class`）
- `DistributedStore` 接线生效（`MysqlDistributedStore` 创建，`.distributedStore()` 接入 builder）
- `RemoteFilesystemSpec` 条件创建（`distributed.enabled=true` 时）
- Subagent sandbox 独立 isolation scope 创建
- `JdbcSnapshotSpec` / `JdbcSandboxExecutionGuard` 在 distributed 模式下生效
- `HarnessA2aRunnerV2` 条件化接线（sandbox 和 distributed store 均为 Optional）

---

## 〇·C、阶段 3 实施状态（2026/07/13 更新）

> 阶段 3 已实施完成。

### 已完成

- SkillCurator pipeline 启用（`V2SkillConfig` 提供 `SkillManageConfig` + `SkillCuratorConfig` + `LocalApprovalGate`）
- Dimension 集群 7 文件 `git mv` 从 `agent/dimension/` 到 `v2/dimension/`，`DimensionStateManager` 适配 v2 `RuntimeContext` API
- `DimensionStateMiddleware` 创建（`MiddlewareBase.onSystemPrompt`），挂载到 `HarnessA2aRunnerV2.middlewares()`
- `V2DimensionConfig` 创建（Spring beans for `LlmDimensionService` + `DimensionStateManager`）
- EmbeddingClient / OpenAiCompatEmbeddingClient `git mv` 从 `harness/skills/` 到 `v2/skills/`
- HttpClient `git mv` 从 `agent/tools/routers/` 到 `v2/util/`
- 3 个 shadow override（`EpisodicMemory` / `EpisodicResult` / `MemoryProvider`）`git mv` 从 `io.agentscope.core.memory/` 到 `v2/memory/`（业务接口，v2 jar 无此类）
- MySqlEpisodicMemory / EpisodicMemoryConfig `git mv` 从 `agent/memory/` 到 `v2/memory/`
- `EpisodicRetrievalMiddleware` 创建（`MiddlewareBase.onSystemPrompt`），挂载到 `HarnessA2aRunnerV2.middlewares()`
- `V2MemoryConfig` 创建（Spring beans for `EpisodicMemoryConfig` + `EpisodicMemory` + `EpisodicRetrievalMiddleware`）
- 编译验证通过（`mvn clean compile` → BUILD SUCCESS，44 source files）

### 与原计划的偏差

原阶段 3 计划主要覆盖 Skills 模块挂载。实际实施中：

| 偏差 | 原计划 | 实际 | 影响 |
|---|---|---|---|
| 1. Dimension 集群提前 | 阶段 5 | 阶段 3（无跨阶段依赖，提前迁移）| 正面：减少阶段 5 工作量 |
| 2. Memory 集群提前 | 阶段 2（原计划）/ 阶段 3（推迟后）| 阶段 3（EmbeddingClient 迁移后解除阻塞）| 正面：提前跑通 Episodic 检索 |
| 3. shadow override 迁移方式 | 删除 | 迁移到 `v2/memory/`（v2 jar 无此类接口，必须保留为业务接口）| 正面：保持接口可用 |

### 阶段 3 验收缺口（需运行时验证）

- `propose_skill` 工具可起草草稿到 `skills/_drafts/`
- `SkillPromotionGate` 审批流程跑通（`LocalApprovalGate` stdin 确认）
- 维度状态注入 system prompt 正常（`DimensionStateMiddleware` onSystemPrompt 生效）
- 情景记忆检索注入 system prompt 正常（`EpisodicRetrievalMiddleware` onSystemPrompt 生效）
- FTS5 检索正常（`MySqlEpisodicMemory.search()`）
- Embedding 向量检索正常（`harness.embedding.enabled=true` 时）

---

## 〇·A、阶段 1 实施状态（2026/07/13 更新）

> 阶段 1 已实施完成。本节为快速摘要，正文章节保留原计划文本作为后续阶段参考。

### 已完成

- pom.xml 升级到 v2.0.0-RC5（含 3 个新增依赖：`agentscope-extensions-mysql`、`agentscope-extensions-model-openai`、`guava 33.0.0-jre`）
- 新建 `HarnessA2aRunnerV2`（`com.agentscopea2a.v2.runner`），基于 `HarnessAgent.builder()` 装配 workspace + memory + compaction + toolResultEviction + planMode + taskList + pendingToolRecovery
- 新建 `V2ChatController`（`com.agentscopea2a.v2.controller`），POST `/v2/ai/chat` SSE 流式入口
- v2 入口冒烟测试通过：SSE 流式返回 28 typed `AgentEvent`，GLM 模型 via 火山方舟 OpenAI 兼容协议

### 与原计划的 3 大偏差

| 偏差 | 原计划 | 实际 | 影响 |
|---|---|---|---|
| 1. dual-track 假设不成立 | v1 入口 + 12 shadow override 保留到阶段 9 | v1 业务代码 10 个文件 82 个编译错误（v2 已删 API），Maven compiler 排除 `com/agentscopea2a/{agent,harness,service,controller}/**` | 失去 v1 流量兜底；回滚 = revert pom + 恢复 shadow override |
| 2. 5 个 shadow override 提前删除 | 阶段 2/4/6/9 分批删除 | 5 个在阶段 1 提前删除（`ReActAgent` / `SubagentsHook` / `MemoryFlushHook` / `MemoryMaintenanceHook` / `AgentSpawnTool`，合计 3100 行）| 定制点已记录在 `shadow-override-customization-points.md`，后续阶段按需通过 v2 middleware / builder 重新实现 |
| 3. v2 jar 构建遇源码树不完整 | `mvn install` 一把过 | v2 源码树 6 个模块引用不存在的类；绕过：v1 a2a-client/server jar 作为 2.0.0-RC5 兼容层、umbrella 空 stub | 运行时 `NoSuchMethodError` 风险（v1 jar .class 绑定 v1 core API），阶段 2+ 重点验证 a2a 链路 |

### 7 个保留 shadow override

仍留在源码树（编译通过或暂未触发编译）：`EpisodicMemory` / `EpisodicResult` / `MemoryProvider` / `DockerCliRunner` / `DockerSandbox` / `DockerSandboxClient` / `AgentSpecLoader`。删除节点不变（阶段 2 删 3 memory，阶段 4 删 3 Docker，阶段 6 删 AgentSpecLoader）。

### 阶段 1 验收缺口（转入后续阶段）

- AGENTS.md 变更后下一轮 system prompt 立即变化验证（待做）
- 工具调用全链路验证（v2 builder 自动注册的标准工具未实测）
- Plan Mode HITL 验证（`plan_enter` / `plan_write` / `plan_exit`）
- 自定义 middleware（ResponseCacheMiddleware）和工具（KnownEntities）挂载（推迟到阶段 2+）

---

## 〇·B、阶段 2 实施状态（2026/07/13 更新）

> 阶段 2 已实施完成（精简版）。原阶段 2 计划 5 项中 4 项有跨阶段依赖，实际完成 3 项无依赖项。

### 已完成

- `MysqlAgentStateStore` 挂载到 `HarnessA2aRunnerV2`（`new MysqlAgentStateStore(dataSource)`，Spring `@Primary` DataSource 自动注入）
- `MemoryConfig` 切小模型（复用 `light-classifier` 实例 qwen3:8b，主模型 glm-5.2 不再承担 flush/consolidation）
- `KnownEntities` 从 `harness/tools/` 移到 `v2/tools/`（`git mv`，package 声明改为 `com.agentscopea2a.v2.tools`）
- 编译验证通过（BUILD SUCCESS，31 source files）

### 与原计划的偏差

原阶段 2 计划包含 5 项，代码探索后发现 4 项有跨阶段依赖，推迟到阶段 3+：

| 推迟项 | 阻塞依赖 | 解除阶段 |
|---|---|---|
| EpisodicRetrievalMiddleware | `MySqlEpisodicMemory` 依赖 `EmbeddingClient`（skills 模块） | 阶段 3 |
| 3 个 shadow override 删除 | `MySqlEpisodicMemory` 实现 `EpisodicMemory` 接口，删除后编译失败 | 阶段 3 |
| ResponseCacheMiddleware | 依赖 `SkillSynthesisRunner` / `FingerprintCalculator` / `DimensionStateManager` | 阶段 3+5 |
| MemoryDigestionService 对接 | 依赖 `SkillDistiller` / `SkillVectorIndex` / `FingerprintCalculator` | 阶段 3 |

### 阶段 2 验收缺口（需运行时验证）

- stateStore 持久化（`agentscope_sessions` 表记录）
- AGENTS.md 变更立即生效（v2 workspace 驱动核心证据）
- memory tools 自动注册（`memory_save` / `memory_search` / `session_search`）
- 同 conversationId 多轮上下文连续

---

## 〇、升级路径决策

### 0.1 为什么不选 in-place jar 升级

in-place jar 升级（改 1 行 pom 版本号 + 处理 12 个补丁文件 + 业务代码 API 适配）看起来工作量小，但本质是"最小改动思维"，与"吸收新框架优点"天然矛盾。三个具体问题：

1. **shadow override 屏蔽 v2 jar 同名类**：12 个补丁文件在 `src/main/java/io/agentscope/*` 下，与 v2 jar 同名同包。Java classpath 优先级：项目 `src/main/java` > jar。v1 的 shadow override 会**盖住** v2 jar 里同名的类。例如 `io.agentscope.core.ReActAgent` 的 1906 行定制补丁会屏蔽 v2 jar 里 `ReActAgent` 的所有改进（per-(userId,sessionId) 隔离、`InterruptControl`、`AgentState` 4 子上下文）。**jar 升级了，但核心类没变**。

2. **入口决定吸收程度**：业务代码入口是 `HarnessA2aRunner`（v1 二开包装）。即使 v2 `HarnessAgent.builder()` 在 classpath 上，`HarnessA2aRunner` 内部不会自动用它。v2 harness 的核心价值是 `HarnessAgent.builder()` **一行装好所有能力**（workspace/memory/compaction/subagent/sandbox/planMode/skill/channel -- architecture.md 表格那 11 个），但这个打包价值只有走 v2 入口才能享受。`HarnessA2aRunner` 还是 v1 那套装配方式，v2 builder 的 11 能力一个都不会自动生效。

3. **编译错误驱动 = 被动适配**：v2 jar 引入后，业务代码会有编译错误（v1 API 被删的）。开发者改到能编译就停。v2 的新 API（`Channel`/`Gateway`、`DistributedStore` 一行注入、`PermissionEngine` 5 mode、28 typed `AgentEvent`、`CompactionConfig`、`ToolResultEvictionConfig`、`SkillCurator` 闭环）**不在编译错误路径上** -- 不用它们也能编译通过，所以不会被主动采纳。in-place 升级需要几十条"采纳清单"逐个论证，每条都可能漏。

**结论**：jar 引入是 v2 能力吸收的**必要条件**（classpath 上得有），不是**充分条件**。要真正吸收，必须：(1) 删除 shadow override（解除屏蔽）；(2) 入口走 v2 builder（享受打包价值）。这正是双轨并行的核心动作。

### 0.2 选定方向：双轨并行，v2 builder 为新骨架

核心思路：

- 在原项目里**新建** `HarnessA2aRunnerV2`（基于 `HarnessAgent.builder()`），**不动 v1 入口**
- v2 入口先跑通最小路径（workspace + memory + 一个 middleware + 一个工具 + 一个沙箱），再**按模块**把业务能力**作为 v2 扩展**重新挂载：
  - 维度状态 -> `onSystemPrompt` middleware
  - 多数据源 -> `DistributedStore` + `JdbcStore` + 业务路由保留
  - Artifact -> `CompositeFilesystem` 后端 + `ArtifactStore` middleware
  - 元工具 -> `@Tool` 注解 + `ToolGroup` + `AsyncToolMiddleware`
  - skills 自由化算法 -> `SkillVisibilityFilter` / `SkillUsageStore` 扩展
  - 沙箱内网定制 -> `DockerSandbox` 子类 + `DockerSandboxClientOptions` 扩展
- 每挂载完一个模块，v2 入口就多一个能力；v2 跑通全链路后**切流量**，v1 `HarnessA2aRunner` + 12 个 shadow override + ReActAgent 1906 行定制**整体删除**（不再逐个决策）

**关键转变**：
- v2 能力从"清单采纳"变成"builder 默认" -- harness 11 个能力天然吸收，不需要"保证吸收"
- 业务定制从"补丁保留"变成"v2 扩展重写" -- 与 v2 协同，不是屏蔽 v2
- 12 个 shadow override 从"逐个决策"变成"整体删除" -- 一次清零，永久解除屏蔽

### 0.3 双轨期的形态

| 阶段 | v1 入口 | v2 入口 | 流量 |
|---|---|---|---|
| 阶段 1 前 | 生产 | 不存在 | 100% v1 |
| 阶段 1-6（v2 入口重建 + 模块挂载）| 生产，只读维护（不接新需求）| 开发，渐进挂载业务能力 | 100% v1 |
| 阶段 7（session 灰度切换）| 生产，承接老 session | 生产，承接新 session | 灰度（按 session 路由）|
| 阶段 8（观察期 2-4 周）| 待删除，承接剩余老 session | 生产，承接新 session | 主要 v2，v1 兜底 |
| 阶段 9（v1 整体删除）| **删除**：`HarnessA2aRunner` + 12 shadow override + ReActAgent 1906 行 | 生产，唯一入口 | 100% v2 |

**回滚能力**：阶段 7-8 任何时点，流量可即时切回 v1（v1 入口完整保留）。这是双轨并行相比 in-place 升级的最大风险兜底 -- in-place 升级回滚要回退业务代码（难），双轨并行回滚 = 流量切回 v1（即时）。

### 0.4 升级工作的三个着力点

| 着力点 | 工作量 | 说明 |
|---|---|---|
| **新建 v2 入口 + 业务模块作为 v2 扩展重写** | 主要工作量 | `HarnessA2aRunnerV2` 基于 `HarnessAgent.builder()`；业务能力（维度状态 / 多数据源 / Artifact / 元工具 / skills 算法 / 沙箱内网定制）按模块重写为 v2 扩展形态（middleware / filesystem / tool / SkillVisibilityFilter 等）|
| **数据迁移脚本** | 一次性脚本 | 记忆 / 状态 / Artifact 的 v1 -> v2 格式迁移，切换前一次性跑 |
| **v1 入口整体删除** | 删除操作 | `HarnessA2aRunner` + 12 个 shadow override + ReActAgent 1906 行定制，观察期结束后一次性清掉 |

---

## 一、升级目标与原则

### 1.1 升级目标

1. **对齐 v2 核心亮点**：让二开项目获得 ReAct 自主可控（安全中断 / 优雅取消 / 人机协同 Hook）、Plan Mode（v1 PlanNotebook 的 v2 演进）、结构化输出（模型层原生）、双层长期记忆等新版本能力 -- 这些能力在 v2 builder 入口下**默认生效**，不需要"采纳清单"。
2. **二开功能保留策略（按目标分级）**：保留的是**业务目标与能力**，不是 v1 具体实现；v2 有更优技术时优先采纳 v2。

   | 保留项 | 保留目标（为什么留） | 技术策略 |
   |---|---|---|
   | **Harness 模块应用** | 主子智能体编排 + 元工具调用体系 + skills 注入方式（Harness 工程优秀，满足业务需求）| 架构保留；v2 builder 默认装配 11 能力（Permission / PlanMode / Middleware 5-stage / OTel 等）天然吸收 |
   | **skills 自由化** | 越用越聪明（主动生成 / 触发生成 / 演化 / 蒸馏 / 向量索引 / 指纹 / 指标分类）| **目标保留，技术可替换**：v2 SkillCurator 闭环（propose / promote / usage / audit / security / curator）作为主路径；v1 算法仅保留 v2 无等价的部分，作为 `SkillVisibilityFilter` / `SkillUsageStore` 扩展 |
   | **沙箱体系** | per-user / session / 本地 / 远程 代码执行环境 | 能力保留；v2 `DockerFilesystemSpec` + `IsolationScope` + `JdbcSandboxExecutionGuard` + `JdbcSnapshotSpec` + workspace 投影采纳；v1 内网定制（registry / CLI 审计 / 命令白名单 / 连接池 / 鉴权）作为 `DockerSandbox` 子类 |
   | **维度状态 / 多数据源 / SSE 流式 / Artifact 体系** | 业务定制 | 保留 v1 业务逻辑；作为 v2 扩展重写（middleware / filesystem / Channel）；v2 使能依赖（`AgentStateStore` / `DistributedStore` / `streamEvents()` / `CompositeFilesystem`）强制采纳 |

3. **与 v2 上游对齐**：项目结构从"v1 定制 + v2 jar"的混合体，迁移到"v2 上游结构 + 业务扩展"的范式。后续 v2 升级（v2.0 -> v2.1 -> v2.2...）只需改 pom 版本号 + 跑回归 + 处理 breaking change，不再需要"采纳清单"。

### 1.2 升级原则

| 原则 | 含义 |
|---|---|
| **双轨并行** | v1 入口保留到 v2 入口跑通全链路 + 切流量 + 观察期后整体删除；v2 入口基于 `HarnessAgent.builder()` 重建 |
| **v2 builder 为骨架** | v2 入口走 `HarnessAgent.builder()` 一行装好所有能力，业务定制作为 middleware / filesystem / tool / SkillVisibilityFilter 扩展挂载，不写 shadow override |
| **业务能力作为 v2 扩展重写** | 维度状态 -> `onSystemPrompt` middleware；多数据源 -> `DistributedStore` + 业务路由；Artifact -> `CompositeFilesystem` + `ArtifactStore` middleware；元工具 -> `@Tool` + `ToolGroup`；skills 算法 -> `SkillVisibilityFilter` / `SkillUsageStore` 扩展 |
| **v1 入口只读维护** | 双轨期 v1 入口不接新需求，只修严重 bug；新需求全部在 v2 入口开发 |
| **session 灰度切换** | 新 session 走 v2，老 session 继续走 v1；观察 2-4 周后整体切 v2 |
| **v1 整体删除** | 观察期结束，`HarnessA2aRunner` + 12 个 shadow override + ReActAgent 1906 行定制一次性清掉 |
| **内网友好** | 不引入 Git/Nacos 等外网依赖；skills 走本地 / MySQL；Docker 沙箱镜像内网化；`DistributedStore` 用 `JdbcDistributedStore`（MySQL）|
| **格式保持** | 包结构（`com.agentscopea2a.*` 业务包）、命名、配置文件格式（`application-*.properties` + `log4j2.xml` + MyBatis）沿用原项目 |

### 1.3 不升级项（明确排除）

- 不升级到 Spring Boot 4.x（保留 SB 3.2.x，JDK 17；v2 核心 JDK 17+ 兼容）
- 不引入 GraalVM 原生镜像（内网部署暂不需要）
- 不引入 E2B 云沙箱（保留本地 / Docker / SSH 远端三种）
- 不引入外部 Git/Nacos skills 仓库（保留 MySQL + 本地文件）
- 不引入 Redis（内网 MySQL 足够；`DistributedStore` 用 `JdbcDistributedStore`）

---

## 二、版本差异总览

### 2.1 整体结构对比

| 维度 | v1.1.0-RC2 二开 | v2.0.0-RC5 |
|---|---|---|
| 主包 | `com.agentscopea2a.*` 业务包 + 12 个 `io.agentscope.*` 补丁文件 | `io.agentscope.*`（核心 + harness + extensions，全量 jar）|
| ReActAgent | 补丁文件覆盖 jar（1906 行定制）| jar 内 1 个，完全无状态 |
| HarnessAgent | 业务 `HarnessA2aRunner` 包装 | jar 提供完整 `HarnessAgent` builder（11 能力默认装配）|
| Agent 状态 | per-agent 有状态 mutable | per-(userId, sessionId) 无状态隔离 |
| Hook 系统 | `io.agentscope.core.hook.Hook` | `@Deprecated`，桥接到 `MiddlewareBase` |
| Memory | `Memory` 接口 + `LongTermMemory` + 自定义 `EpisodicMemory` | `AgentStateStore` + `AgentState` + Harness 双层记忆（flush / consolidation）|
| Plan | `PlanNotebook`（8 工具，结构化 Plan+SubTask 状态机）| Plan Mode（3 工具：`plan_enter`/`plan_write`/`plan_exit`，markdown 文件 + HITL 退出）|
| 工具系统 | `Toolkit` + `@Tool` | `ToolBase`/`AgentTool` + `ToolGroup`/`MetaToolFactory` + 注解驱动 + 权限挂载 |
| 权限 | 无独立权限层 | `PermissionEngine` + `PermissionMode` + HITL `UserConfirmResultEvent` |
| 事件流 | `Flux<Event> stream(...)` | `Flux<AgentEvent> streamEvents(...)`（28 个类型化事件，含 HITL）|
| Pipeline | `Pipeline`/`Pipelines`/`SequentialPipeline`/`FanoutPipeline`/`MsgHub` | 删除，改用 middleware + 子 agent + event stream |
| Session | `SessionManager` | `AgentStateStore`（InMemory / JsonFile / Jdbc / 自定义）|
| TTS | `io.agentscope.core.model.tts.*`（14 文件）| 删除，对接上游 SDK |
| RAG | `Knowledge`/`KnowledgeRetrievalTools`/`GenericRAGHook` | `@Deprecated`，v2 重写中 |
| Skills | 自由化体系（合成/演化/蒸馏/向量/指纹/指标分类）| SkillCurator + SkillPromoter + SkillSecurityScanner + SkillUsageStore + SkillAuditLog + 自学习闭环 |
| Subagent | workspace spec + `AgentSpawnTool` | 同步/后台 + 自动反向通知 + 流式转发 + 远程 stub + workspace spec |
| 沙箱 | DockerCliRunner + DockerSandbox + DockerSandboxClient | AbstractBaseSandbox + SandboxManager + SessionSandboxStateStore + WorkspaceMountSupport + Docker/E2B |
| 文件系统 | 本地 + SSH 远端 | LocalFilesystem / LocalFilesystemWithShell / SandboxBackedFilesystem / OverlayFilesystem / CompositeFilesystem / RemoteFilesystem |
| Channel/Gateway | 无 | HarnessGateway + Channel + per-session 并发 + 多 agent 路由 + 流式 SSE |
| DistributedStore | 无 | 跨副本恢复（一行注入 stateStore + baseStore + executionGuard + snapshotSpec）|
| OpenTelemetry | 无原生 | `OtelTracingMiddleware` 原生 |
| Observability | 自定义 metrics | OTel + AgentScope Studio |

### 2.2 核心 API 变更（v1 -> v2，业务代码迁移对照）

| v1 API | v2 替代 | 备注 |
|---|---|---|
| `ReActAgent.Builder.memory(Memory)` | `.stateStore(AgentStateStore)` | 会话历史放 `AgentState.context` |
| `ReActAgent.Builder.statePersistence(StatePersistence)` | `.stateStore(AgentStateStore)` | StatePersistence 已删 |
| `ReActAgent.Builder.structuredOutputReminder(...)` | 删除，模型层原生 | StructuredOutputCapableAgent 已删 |
| `ReActAgent.Builder.planNotebook(PlanNotebook)` | `HarnessAgent.Builder.enablePlanMode()` | 8 工具 -> 3 工具 |
| `ReActAgent.Builder.hook(Hook)` / `.hooks(List)` | `.middleware(MiddlewareBase)` / `.middlewares(List)` | Hook 标 `@Deprecated`，LegacyHookDispatcher 桥接 |
| `ReActAgent.Builder.skillBox(SkillBox)` | `.skillRepository(AgentSkillRepository)` / `.skillRepositories(List)` | SkillBox 标 `@Deprecated` |
| `ReActAgent.Builder.longTermMemory(...)` / `.longTermMemoryMode(...)` | `MemoryConfig.builder().flushPrompt(...).consolidationPrompt(...)` | LongTermMemory 标 `@Deprecated` |
| `ReActAgent.getCurrentSessionId()` / `getCurrentUserId()` | `RuntimeContext.getSessionId()` / `getUserId()` | Agent 无状态 |
| `ReActAgent.getState()` | `getAgentState()` / `getAgentState(userId, sessionId)` | |
| `AgentBase(name, desc, checkRunning, hooks)` | `AgentBase(name, desc, hooks)` | checkRunning 已忽略 |
| `io.agentscope.core.session.SessionManager` | `AgentStateStore` | 包删除 |
| `io.agentscope.core.pipeline.*` | middleware + 子 agent + event stream | 包删除 |
| `io.agentscope.core.model.tts.*` | 直接对接上游 TTS SDK | 14 文件删除 |
| `io.agentscope.core.hook.PendingToolRecoveryHook` | `Builder.enablePendingToolRecovery(boolean)` | |
| `AgentMetaState` | `AgentState` | |
| `StateModule` / `StatePersistence` / `ToolkitState` | `AgentState` / `AgentStateStore` / `state.legacy.ToolkitState` | |
| `Flux<Event> stream(...)` | `Flux<AgentEvent> streamEvents(...)` | 11 + 3 + 4 + 9 个 stream 重载标 `@Deprecated`（forRemoval）|
| `io.agentscope.core.agent.Event/EventType/EventSource` | `io.agentscope.core.event.AgentEvent` + 28 子类 | 软弃用，harness 内部仍用 |

### 2.3 v2 Harness 能力图谱（`HarnessAgent.builder()` 默认装配）

下表是 v2 harness architecture.md 列的 11 个能力。**在双轨并行方案下，这些能力在 v2 入口（`HarnessA2aRunnerV2`）中默认装配，不需要"采纳清单"**。v1 入口（`HarnessA2aRunner`）一个都不会自动生效 -- 这是双轨并行相比 in-place 升级的核心收益。

| 能力 | 解决什么问题 | Builder 入口 | v1 入口是否有 |
|---|---|---|---|
| 工作区驱动的人格 | 人格 / 知识 / 子 agent / 技能 / MCP 白名单都以文件形式存在 | `.workspace(path)` | ✘（v1 用代码装配）|
| 状态持久化 | 同 `(userId, sessionId)` 跨请求、跨进程、跨副本恢复 | 默认开启；`.stateStore(...)` 替换实现 | ✘（v1 per-agent mutable）|
| 双层长期记忆 | 长会话里有价值的事实自动沉淀到 `MEMORY.md` | 默认开启；`.memory(...)` 定制 prompt / 触发策略 | ✘（v1 EpisodicMemory 自管）|
| 对话压缩 | 上下文有界；模型真的溢出时强制重试 | `.compaction(...)` | ✘（v1 无）|
| 大工具结果卸载 | 超 80K 字符的结果落盘 + 占位符 | `.toolResultEviction(...)` | ✘（v1 无）|
| 子 agent 编排 | 委派给子 agent，支持同步或后台，自动反向通知 | `.subagent(...)` 或 `workspace/subagents/` | ✘（v1 SubagentsHook 补丁）|
| 可插拔文件系统 | 本机 + shell / 共享存储 / 沙箱，不改代码切换 | `.filesystem(...)` | ✘（v1 本地 + SSH 硬编码）|
| 沙箱隔离 | 文件与命令隔离，跨调用恢复，多副本部署 | `.filesystem(new DockerFilesystemSpec()...)` | ✘（v1 DockerSandbox 补丁）|
| 计划模式 | 只读思考阶段 + HITL 退出 | `.enablePlanMode()` | ✘（v1 PlanNotebook 8 工具）|
| 技能装配 | 来自 Git / Nacos / MySQL / classpath / 工作区 | `.skillRepository(...)` | ✘（v1 SkillBox）|
| MCP 集成与工具白名单 | 声明式 MCP server + 工具粒度允许 / 拒绝 | `workspace/tools.json` | ✘（v1 无）|
| Channel 路由 | 会话管理、per-session 并发控制、多 agent 路由、流式事件 | `agent.channel(...)` / `GatewayBootstrap` | ✘（v1 单线程编排）|

**关键洞察**：v2 builder 的 11 能力是协同的（Channel + Gateway + DistributedStore + BotLoopGuard 是一套；CompositeFilesystem + RemoteFilesystem + JdbcStore 是一套）。in-place 升级逐个评估会错过协同价值；双轨并行在 v2 入口下天然享受协同。

---

## 三、业务能力作为 v2 扩展重写

### 3.1 v2 入口骨架（`HarnessA2aRunnerV2`）

新建 `com.agentscopea2a.harness.runner.HarnessA2aRunnerV2`，基于 `HarnessAgent.builder()` 装配。v1 `HarnessA2aRunner` 不动，双轨期保留。

**最小路径装配**（阶段 1 跑通）：

```java
public class HarnessA2aRunnerV2 {
    private final HarnessAgent agent;

    public HarnessA2aRunnerV2(DataSource dataSource, Model model, Path workspace) {
        this.agent = HarnessAgent.builder()
            .name("QualitySupervisorV2")
            .model(model)
            .workspace(workspace)
            .stateStore(new JdbcAgentStateStore(dataSource))           // v2 分布式状态
            .memory(MemoryConfig.builder()                             // v2 双层记忆
                .flushPrompt(...)
                .consolidationPrompt(...)
                .model("openai:gpt-4.1-mini")
                .build())
            .compaction(CompactionConfig.builder()                     // v2 上下文压缩
                .triggerMessages(40).keepMessages(12).build())
            .toolResultEviction(ToolResultEvictionConfig.defaults())   // v2 大工具结果卸载
            .filesystem(new DockerFilesystemSpec()                     // v2 沙箱
                .image("internal/agentscope-sandbox:2.0")
                .isolationScope(IsolationScope.USER)
                .snapshotSpec(new JdbcSnapshotSpec(dataSource, "sandbox_snapshots"))
                .executionGuard(JdbcSandboxExecutionGuard.builder(dataSource).build()))
            .distributedStore(JdbcDistributedStore.from(dataSource))   // v2 一行注入
            .enablePlanMode().planFileDirectory("plans")               // v2 Plan Mode
            .enableTaskList(true)                                      // v2 TaskList
            .build();
    }

    public Flux<AgentEvent> streamEvents(List<Msg> messages, AgentRequestOptions options) {
        RuntimeContext ctx = RuntimeContext.builder()
            .sessionId(options.getTaskId())
            .userId(options.getUserId())
            .build();
        return agent.streamEvents(messages, ctx);
    }
}
```

**关键点**：
- v2 builder 默认装好 11 能力 -- 不需要"采纳清单"
- 业务 middleware / tool / skill / filesystem 扩展通过 `.middlewares(...)` / `Toolkit.registerTool(...)` / `.skillRepository(...)` / `.filesystem(...)` 挂载
- v1 `HarnessA2aRunner` 保持不变，双轨期 v1 入口继续生产

### 3.2 业务能力挂载清单

每个业务模块作为 v2 扩展重写，按模块渐进挂载到 v2 入口。挂载顺序按"与 v1 弱耦合优先"。

#### 3.2.1 Harness 业务编排 -> v2 builder + middleware

| v1 二开 | v2 扩展形态 | 挂载方式 |
|---|---|---|
| `HarnessA2aRunner`（242 行）| `HarnessA2aRunnerV2`（基于 `HarnessAgent.builder()`）| 新建 v2 入口，v1 保留 |
| `harness.hooks.*`（9 文件 2514 行）| `MiddlewareBase` 实现 | `.middlewares(List.of(...))` 挂载（见 §3.3）|
| `harness.cache.ResponseCacheService`（288 行）| `ResponseCacheMiddleware`（onAgent）| `.middlewares(...)` |
| `harness.config.*`（8 文件 1459 行）| 保留为 `@ConfigurationProperties` | v2 入口注入 |
| `harness.workspace.*`（2 文件 499 行）| 对接 v2 `RemoteFilesystemSpec` | `.filesystem(...)` |

#### 3.2.2 记忆处理 -> `AgentStateStore` + `MemoryConfig` + Episodic 扩展

| v1 二开 | v2 扩展形态 | 挂载方式 |
|---|---|---|
| `agent.session.MySQLSession` | `JdbcAgentStateStore`（v2 内置）+ 业务字段扩展 | `.stateStore(...)` |
| `EpisodicMemory` / `EpisodicResult` / `MemoryProvider`（补丁文件）| 迁移到 `com.agentscopea2a.agent.memory.*`，作为业务接口 | `EpisodicRetrievalMiddleware`（onSystemPrompt）|
| `MySqlEpisodicMemory`（FTS5）| 保留，对接 v2 `session_search` 工具 | `Toolkit.registerTool(...)` |
| `MysqlMemoryStore` + `MemoryHydrator` + `MemoryFileWatcher` | 保留，作为 v2 `MEMORY.md` 的 MySQL 镜像层（双写）| v2 `MemoryConfig` 配置 |
| `EpisodicLongTermMemoryAdapter` | 重写为 `EpisodicRetrievalMiddleware`（onSystemPrompt）| `.middlewares(...)` |
| `digestion/*`（4 文件）| 保留，对接 v2 `MemoryMaintenanceMiddleware` 节流闸门 | 后台任务 |
| v2 双层记忆 | `MemoryConfig.builder().flushPrompt(...).consolidationPrompt(...).model("openai:gpt-4.1-mini")` | `.memory(...)` -- **builder 默认** |
| v2 三处独立 LLM | flush/consolidation/compaction 走小模型 | `.memory(...).model(...)` -- **builder 默认** |
| v2 `CompactionConfig` | triggerMessages/keepMessages/truncateArgs | `.compaction(...)` -- **builder 默认** |
| v2 `ToolResultEvictionConfig` | 80K 字符落盘 + 占位符 | `.toolResultEviction(...)` -- **builder 默认** |
| v2 `memory_search` / `memory_get` / `session_search` | agent 自服务工具 | `Toolkit.registerTool(...)` |

**分工**：v1 Episodic 负责跨 session 业务事实（用户偏好 / 项目约定）；v2 `MEMORY.md` 负责 per-session 上下文压缩。两者前缀标注来源，不重复。

#### 3.2.3 Skills 自由化 -> `SkillCurator` 闭环 + v1 算法扩展

> **用户意图**：目标是"越用越聪明"，**技术可替换** -- v2 SkillCurator 闭环作为主路径，v1 算法逐个评估替换，仅保留 v2 无等价的部分。

| v1 二开 | v2 扩展形态 | 挂载方式 |
|---|---|---|
| `SkillSynthesisRunner`（636 行，主动/触发生成）| **评估替换**：v2 `ProposeSkillTool` + `SkillManageTool` 覆盖则删 v1，仅保留 v1 生成 prompt 模板 | `.enableSkillManageTool(...)` |
| `SkillEvolutionRunner`（442 行，演化）| **评估替换**：v2 `SkillCurator` 后台整理 + LLM "伞合并"扫描覆盖则删 v1 | `.enableSkillCurator(...)` |
| `SkillDistiller`（648 行，蒸馏）| **保留 v1**（v2 无等价），输出通过 v2 `SkillManageTool` 写回 | 后台任务 |
| `SkillVectorIndex`（372 行，向量索引）| **保留 v1**（v2 无等价），作为 `SkillVisibilityFilter` 实现 | `.enableSkillPromotionGate(..., filter)` |
| `FingerprintCalculator`（246 行，语义指纹）| **保留 v1 语义指纹**（v2 只有 SHA-256 文件级），文件级去重用 v2 `MarketplaceStager` | 扩展 `SkillUsageStore` |
| `MetricClassificationService`（413 行，指标分类）| **保留 v1**（v2 只有使用计数），对接 v2 `SkillUsageRecord` | 扩展 `SkillUsageStore` |
| `SkillIndexRepository` | 改造为 v2 `AgentSkillRepository`（参考 `MysqlSkillRepository`）| `.skillRepository(...)` |
| `EmbeddingClient` / `OpenAiCompatEmbeddingClient` | 保留，内网 OpenAI 兼容 API | `SkillVisibilityFilter` 向量计算 |
| v2 `SkillCurator` 闭环 | propose -> promote -> usage -> audit -> security -> curator | `.enableSkillManageTool(...).enableSkillCurator(...)` -- **builder 默认** |
| v2 `SkillPromotionGate` | `LocalApprovalGate` 对接内网审批 | `.enableSkillPromotionGate(...)` |
| v2 `SkillSecurityScanner` | agent 自写 skill 防恶意 shell | `.enableSkillPromotionGate(..., scanner)` |
| v2 `SkillVisibilityFilter` | `CanaryFilter` + `EnvironmentFilter` 灰度 | `.enableSkillPromotionGate(..., filter)` |
| v2 `MarketplaceStager` | SHA-256 文件级去重 + 孤儿清理 + 执行位恢复 | **builder 默认** |
| v2 4 层合成 | project global / marketplace / workspace shared / user-isolated | **builder 默认** |

**替换原则**：阶段 3 前每个 v1 算法文件做"v2 能力对照评估"，v2 已有等价则删 v1（不保留死代码），v2 无等价则保留并作为 v2 扩展对接。

#### 3.2.4 沙箱体系 -> `DockerFilesystemSpec` + v1 内网定制子类

| v1 二开 | v2 扩展形态 | 挂载方式 |
|---|---|---|
| `DockerCliRunner`（151 行补丁）| 内网 registry 镜像管理 / CLI 命令审计 / 命令白名单保留为 `DockerSandbox` 子类 | `.filesystem(new InternalDockerFilesystemSpec()...)` |
| `DockerSandbox`（397 行补丁）| 内网镜像预拉取 / 容器启动 hook 保留为子类 | 同上 |
| `DockerSandboxClient`（228 行补丁）| 连接池 / 鉴权 / 健康检查保留为 `DockerSandboxClient` 子类或 `SandboxClientOptions` 扩展 | 同上 |
| workspace 投影根 | v2 `WorkspaceProjectionApplier` | `.workspaceProjectionRoots(...)` -- **改用 v2** |
| 快照策略 | v2 `JdbcSnapshotSpec`（MySQL BLOB，跨副本）| `.snapshotSpec(new JdbcSnapshotSpec(...))` -- **改用 v2** |
| 资源限额 | v2 `memorySizeBytes` / `cpuCount` | `.memorySizeBytes(...).cpuCount(...)` -- **改用 v2** |
| 网络模式 | v2 `network(String)` | `.network(...)` -- **改用 v2** |
| 额外 docker run 参数 | v2 `additionalRunArgs(...)` | `.additionalRunArgs(...)` -- **改用 v2** |
| 超时 / 重试 | v2 内置 retry | **改用 v2** |
| v2 `IsolationScope.USER` | 4 scope（SESSION/USER/AGENT/GLOBAL），USER 缺 userId 自动降级 SESSION | `.isolationScope(IsolationScope.USER)` -- **v2 新能力** |
| v2 `SandboxExecutionGuard` | 可插拔并发锁，防多副本 last-write-wins | `.executionGuard(JdbcSandboxExecutionGuard.builder(...).build())` -- **v2 新能力** |
| v2 `SessionSandboxStateStore` | 沙箱状态走 `AgentStateStore`，跨副本 resume | `.stateStore(...)` 自动生效 -- **v2 新能力** |
| v2 `WorkspaceSpec` + `WorkspaceEntry` | 结构化布局（FileEntry/DirEntry/BindMountEntry/...）| `.workspaceSpec(...)` -- **v2 新能力** |

**净效果**：v1 的 3 个 Docker 文件约 60% 定制可被 v2 原生替代（改用 v2 Builder），30% 保留为子类（内网 registry / CLI 审计 / 连接池 / 鉴权），外加 4 项 v2 新能力（`IsolationScope.USER` / `JdbcSandboxExecutionGuard` / `JdbcSnapshotSpec` / `SessionSandboxStateStore`）采纳。

#### 3.2.5 维度状态 -> `onSystemPrompt` middleware

| v1 二开 | v2 扩展形态 | 挂载方式 |
|---|---|---|
| `DimensionStateManager` / `DimensionPrompts` / `DimensionState` / `LlmDimensionService` / `OpenAILlmDimensionService` / `QuestionAnalysis` / `DimensionException` | 全部保留，改为 `DimensionStateMiddleware`（onSystemPrompt）注入 system prompt | `.middlewares(...)` |
| v2 `AgentState` 4 子上下文 | `PermissionContextState`/`ToolContextState`/`TaskContextState`/`PlanModeContextState` | **builder 默认** |
| v2 `InterruptControl` | per-session 中断信号，runtime-only 不序列化 | **builder 默认**（Harness 使能）|
| v2 `TaskContextState` + `TodoTools` | TaskList + `TaskReminderMiddleware` | `.enableTaskList(true)` -- **builder 默认** |

**分工**：v1 维度状态保留为业务 middleware 自管（不写入 `AgentState.context`），通过 `onSystemPrompt` 注入；v2 子上下文（Task / Permission / PlanMode）用框架标准。两者通过 `RuntimeContext` 传递，不互相序列化。

#### 3.2.6 多数据源 -> `DistributedStore` + `JdbcStore` + 业务路由保留

| v1 二开 | v2 扩展形态 | 挂载方式 |
|---|---|---|
| 多数据源业务路由（MySQL / ClickHouse / GaussDB）| 全部保留，业务逻辑不变 | `@Configuration` 注入 |
| v2 `DistributedStore` 一行注入 | 自动接 stateStore + baseStore + executionGuard + snapshotSpec | `.distributedStore(JdbcDistributedStore.from(dataSource))` -- **v2 新能力** |
| v2 `JdbcStore` / `JdbcAgentStateStore` | MySQL 分布式状态 | `.stateStore(...)` -- **v2 新能力** |
| v2 `BaseStore` + `NamespaceFactory` | 多租户隔离 | `.distributedStore(...)` 自动生效 |

#### 3.2.7 SSE 流式 -> `streamEvents()` + `Channel` + `Gateway`

| v1 二开 | v2 扩展形态 | 挂载方式 |
|---|---|---|
| SSE 业务事件定制 | 保留业务事件，适配 v2 28 typed `AgentEvent` | `streamEvents()` 适配层 |
| v2 `streamEvents()` | `Flux<AgentEvent>` + 28 typed 子类 | **强制采纳**（v1 `stream()` 已 `@Deprecated(forRemoval=true)`）|
| v2 `Channel` + `Gateway` | per-session 并发控制 + 多 agent 路由 | `agent.channel(ChatUiChannel.create())` -- **v2 新能力** |
| v2 `BotLoopGuard` | 防 bot 死循环（内网多 bot 必备）| `.botLoopGuard(...)` |
| v2 `IdempotencyStore` | 幂等 | `.idempotencyStore(...)` |
| v2 `SubagentExposedEvent` | 分支对话（可选）| `agent_spawn expose_to_user=true` |

#### 3.2.8 Artifact 体系 -> `CompositeFilesystem` + `ArtifactStore` middleware

| v1 二开 | v2 扩展形态 | 挂载方式 |
|---|---|---|
| `ArtifactStore` / `TabularExtractor` / `ArtifactContext` / `ArtifactRef` | 保留业务逻辑 | `ArtifactStoreMiddleware`（onAgent / onActing）|
| `LocalArtifactIo` / `SshArtifactIo` | 对接 v2 `CompositeFilesystem` 后端 | `.filesystem(new CompositeFilesystem(...)...)` |
| v2 `CompositeFilesystem` | 组合 Local + Remote 多后端 | **v2 新能力**（沙箱使能）|
| v2 `RemoteFilesystem` + `JdbcStore` | 远端 KV，跨副本共享 | **v2 新能力**（沙箱使能）|
| v2 `NamespaceFactory` | 多租户隔离 | **v2 新能力**（沙箱使能）|
| v2 `OverlayFilesystem` | 读写分离 | 可选 |
| v2 `WorkspaceIndex` | SQLite 加速远端 ls/glob/grep | 可选 |

#### 3.2.9 元工具体系 -> `@Tool` 注解 + `ToolGroup` + `AsyncToolMiddleware`

| v1 二开 | v2 扩展形态 | 挂载方式 |
|---|---|---|
| `ToolRoutersIndex`（347 行）| 对接 v2 `ToolGroupManager`，作为 `ToolGroup` 激活策略 | `.toolGroups(...)` |
| `DataPrimitivesTool` / `QualityTools` / `SkillSaveTool` / `CaptureSkillSaveTool` / `PythonExecTool` / `KnownEntities` | 全部保留，改用 v2 `@Tool` / `@ToolParam` 注解 | `Toolkit.registerTool(Object)` 反射注册 |
| `PythonExecTool` | 通过 `SandboxContext` 注入 v2 `ShellExecuteTool`，复用 v2 沙箱生命周期 | `Toolkit.registerTool(...)` |
| `SkillSaveTool` | 对接 v2 `ProposeSkillTool`，二开保存逻辑保留，v2 负责审批 / 提升 | `Toolkit.registerTool(...)` |
| v2 `ToolGroup` + `ToolGroupScope` + `ToolGroupManager` | 按需激活工具组，省 context | **v2 新能力** |
| v2 `MetaToolFactory` | `reset_equipped_tools` 让 LLM 自己切换工具集 | **v2 新能力** |
| v2 `AsyncToolMiddleware` + `MessageBus` + `InboxMiddleware` | 长时工具后台化 + inbox 推回 `HintBlock` | **v2 新能力**（数据类 agent 革命性）|
| v2 `WaitAsyncResultsTool` | LLM 主动等待异步结果 | `Toolkit.registerTool(...)` |
| v2 `ToolSuspendException` | 工具 HITL 挂起 | middleware 内抛出 |
| v2 `ToolResultEviction` | 大工具结果落盘 | `.toolResultEviction(...)` -- **builder 默认** |
| v2 MCP 集成 | `McpClientManager` + `McpTool` 远程工具协议 | `workspace/tools.json` |

### 3.3 Hook -> Middleware 迁移映射

9 个业务 Hook 全部重写为 v2 `MiddlewareBase`，挂载到 v2 入口（v1 入口的 Hook 不动，双轨期保留）。

| 二开 Hook | v1 位置 | v2 Middleware 实现 | 迁移 stage |
|---|---|---|---|
| `ArtifactAccessHook` | `harness.hooks` | `ArtifactAccessMiddleware` | `onActing`（拦截 read_file / write_file / shell_execute 跨租户路径）|
| `ArtifactHandoffHook` | `harness.hooks` | `ArtifactHandoffMiddleware` | `onAgent`（artifact 交接）|
| `PythonExecRetryHook` | `harness.hooks` | `PythonExecRetryMiddleware` | `onActing`（Python 执行重试）|
| `ResponseCacheHook` | `harness.hooks` | `ResponseCacheMiddleware` | `onAgent`（响应缓存，question-hash key）|
| `SkillEvolutionHook` | `harness.hooks` | `SkillEvolutionMiddleware` | `onAgent`（技能演化触发）|
| `SkillRetrievalHook` | `harness.hooks` | `SkillRetrievalMiddleware` | `onSystemPrompt`（四阶段路由：L1/L2 user + L1/L2 auto）|
| `SkillSynthesisHook` | `harness.hooks` | `SkillSynthesisMiddleware` | `onAgent`（技能合成触发）|
| `ToolCallCollector` | `harness.hooks` | `ToolCallCollectorMiddleware` | `onActing`（工具调用收集）|
| `ToolCallTrackingHook` | `harness.hooks` | `ToolCallTrackingMiddleware` | `onActing`（工具调用追踪 + metrics）|
| `agent.hook.SessionHook` | `agent.hook` | `SessionMiddleware` | `onAgent`（session 生命周期）|

迁移要点：
- v1 `HookEvent.appendSystemContent(String)` -> v2 `onSystemPrompt` stage 的 `Transformer` 模式
- v1 `PreReasoningEvent` / `PostReasoningEvent` -> v2 `onReasoning` stage 的洋葱模型
- v1 `PreActingEvent` / `PostActingEvent` -> v2 `onActing` stage 的洋葱模型
- v1 `PreCallEvent` / `PostCallEvent` -> v2 `onAgent` stage 的洋葱模型

### 3.4 v1 入口与 shadow override 整体删除计划

12 个 shadow override + ReActAgent 1906 行定制 + `HarnessA2aRunner` v1 入口，**观察期结束后一次性整体删除**，不逐个决策。

| 删除对象 | 行数 | 删除前提 | 删除节点 |
|---|---|---|---|
| `io.agentscope.core.ReActAgent`（补丁）| 1906 | 1906 行定制点全部迁移到 v2 middleware | 阶段 9（观察期结束）|
| `io.agentscope.core.memory.EpisodicMemory` / `EpisodicResult` / `MemoryProvider`（补丁）| 91 + 31 + 139 | 已迁移到 `com.agentscopea2a.agent.memory.*` | 阶段 2（记忆模块挂载完成）|
| `io.agentscope.harness.agent.hook.MemoryFlushHook` / `MemoryMaintenanceHook` / `SubagentsHook`（补丁）| 100 + 202 + 302 | v2 middleware 替代 | 阶段 2 / 阶段 6 |
| `io.agentscope.harness.agent.sandbox.impl.docker.DockerCliRunner` / `DockerSandbox` / `DockerSandboxClient`（补丁）| 151 + 397 + 228 | 内网定制迁到子类，v2 原生能力采纳 | 阶段 4 |
| `io.agentscope.harness.agent.tool.AgentSpawnTool`（补丁）| 590 | v2 `AgentSpawnTool` 替代，定制点通过 Builder 注入 | 阶段 6 |
| `io.agentscope.subagent.AgentSpecLoader`（补丁）| 284 | v2 `harness.agent.subagent.AgentSpecLoader` 替代 | 阶段 6 |
| `HarnessA2aRunner`（v1 入口）| 242 | v2 入口 `HarnessA2aRunnerV2` 跑通全链路 + 切流量 + 观察期结束 | 阶段 9 |

**关键点**：阶段 1-8 期间，shadow override 仍在 classpath 上，会屏蔽 v2 jar 同名类。但 v2 入口 `HarnessA2aRunnerV2` 不依赖这些被屏蔽的类（它直接用 v2 `HarnessAgent.builder()`），所以 v2 入口能正常享受 v2 能力。v1 入口 `HarnessA2aRunner` 继续用被屏蔽的 v1 定制类 -- 这正是双轨并行的关键：v1 v2 互不干扰。

### 3.5 数据迁移（v1 -> v2 格式）

session 灰度切换前，一次性跑数据迁移脚本：

| 数据类型 | v1 格式 | v2 格式 | 迁移策略 |
|---|---|---|---|
| 会话状态 | per-agent mutable，`MySQLSession` | per-(userId, sessionId)，`JdbcAgentStateStore` | 脚本扫描 v1 `MySQLSession` 表，按 `(userId, sessionId)` 重组写入 v2 `agent_state` 表 |
| 长期记忆 | `EpisodicMemory`（MySQL FTS5）+ `MEMORY.md` MySQL 镜像 | v2 `memory/YYYY-MM-DD.md` + `MEMORY.md` + `MEMORY.md` MySQL 镜像 | 脚本把 v1 `MEMORY.md` 迁到 v2 格式；v1 Episodic 保留为业务扩展，不迁移 |
| 沙箱快照 | v1 本地文件 | v2 `JdbcSnapshotSpec`（MySQL BLOB）| 脚本扫描 v1 本地快照，写入 v2 `sandbox_snapshots` 表 |
| Artifact 元数据 | v1 `ArtifactStore`（MySQL）| v1 `ArtifactStore` 保留（作为 middleware）| 不迁移，v2 入口直接读 v1 表 |
| 技能库 | v1 `SkillIndexRepository`（MySQL）| v2 `AgentSkillRepository`（MySQL，参考 `MysqlSkillRepository`）| 脚本把 v1 `skills` 表字段映射到 v2 schema（保留 v1 业务字段）|

**关键点**：迁移脚本在 session 灰度切换前一次性跑；老 session 切到 v2 时，v2 入口从迁移后的数据恢复状态。切流量后新写入全部走 v2 格式。

### 3.6 配置文件迁移

| 二开配置 | 迁移策略 |
|---|---|
| `application.properties` / `-dev` / `-prod` | **保留** properties 格式（不迁移到 yml）；新增 v2 入口配置项 |
| `application-sandbox-linux.properties` / `-windows` / `-linux-remote` | **保留**，对齐 v2 `SandboxClientOptions` 字段 |
| `log4j2.xml` | **保留** |
| `mybatis/*` | **保留** |
| `workspace/` | **保留**，新增 `workspace/subagents/` / `workspace/skills/` / `workspace/plans/` 对齐 v2 约定 |
| `docs/` | **保留** |

新增配置项（v2 入口）：
- `harness.a2a.v2.enabled`（新增，v2 入口开关，双轨期默认 false，灰度切换时 true）
- `harness.a2a.v2.compaction.trigger` / `.keep`（对齐 v2 `CompactionConfig`）
- `harness.a2a.v2.tool-eviction.max-chars`（对齐 v2 `ToolResultEvictionConfig`）
- `harness.a2a.v2.memory.flush.enabled` / `.consolidation.enabled` / `.model`（v2 双层记忆）
- `harness.a2a.v2.plan-mode.enabled` / `.plan-file-directory`（v2 Plan Mode）
- `harness.a2a.v2.permission.mode`（v2 Permission 系统）
- `harness.a2a.v2.distributed-store.type=jdbc`（v2 `DistributedStore`）

### 3.7 依赖（pom.xml）迁移

| 依赖 | 变更 |
|---|---|
| `agentscope.version`（第 25 行） | `1.1.0-RC2` -> `2.0.0-RC5`（**核心改动，1 行**）|
| `agentscope-core` | jar 依赖升级到 v2 |
| `agentscope-harness` | jar 依赖升级到 v2 |
| `agentscope-a2a-spring-boot-starter` | jar 依赖升级到 v2 |
| 新增 `agentscope-extensions-mysql` | v2 `JdbcDistributedStore` / `JdbcSandboxExecutionGuard` / `JdbcSnapshotSpec` / `JdbcAgentStateStore` |
| 新增 `agentscope-extensions-skill-mysql-repository` | v2 MySQL skill 仓库（可选，或保留二开 `SkillIndexRepository`）|
| `spring-boot` | 保留 3.2.3（v2 兼容 SB 3.x）|
| `java.version` | 保留 17 |
| 其余业务依赖（MyBatis / ClickHouse / GaussDB / Hutool / fastjson / jasypt）| 全部保留 |

---

## 四、升级步骤（分阶段）

### 阶段 0：准备（1 周）

1. **建立升级分支**：基于原项目创建 `upgrade/2.0.0-RC5-dual-track` 分支
2. **内网依赖预下载**：v2 全量 jar、extensions、依赖项打包入内网 Maven 仓库
3. **Docker 沙箱镜像内网化**：v2 默认沙箱镜像拉取到内网 registry
4. **回归测试基线**：记录原项目核心功能基线（SSE 聊天 / 工具调用 / 子 agent / 记忆 / 技能）
5. **二开功能清单**：对照 §3.2 业务能力挂载清单，逐项标注"v2 扩展形态 / 挂载方式 / 挂载阶段"
6. **12 个补丁文件定制点提取**：逐个文件 diff v1 jar 原版，提取定制点清单（为后续迁移到 v2 扩展做准备）

**验收**：升级分支创建；内网依赖就绪；回归基线记录；二开功能清单完成；定制点清单完成

### 阶段 1：v2 入口最小路径（1-2 周）

> **实施状态**：✅ 已完成（2026/07/13）。详见本文 §〇·A。原计划下方文本保留作为后续阶段参考；实际实施有 3 大偏差（dual-track 假设不成立、5 个 shadow override 提前删除、v2 jar 构建需兼容层绕过）。

**目标**：新建 `HarnessA2aRunnerV2`，跑通 v2 builder 最小路径（workspace + memory + 一个 middleware + 一个工具 + 一个沙箱）。v1 入口不动。

1. **pom.xml 升级**（核心改动）：
   - 第 25 行 `agentscope.version` 从 `1.1.0-RC2` 改为 `2.0.0-RC5`
   - 新增 `agentscope-extensions-mysql` 依赖
2. **新建 `HarnessA2aRunnerV2`**（§3.1 装配示例）：
   - `HarnessAgent.builder()` 装配 v2 11 能力（workspace / stateStore / memory / compaction / toolResultEviction / filesystem / planMode / taskList）
   - `.distributedStore(JdbcDistributedStore.from(dataSource))` 一行注入
   - `.filesystem(new DockerFilesystemSpec().image("internal/agentscope-sandbox:2.0").isolationScope(IsolationScope.USER).snapshotSpec(new JdbcSnapshotSpec(...)).executionGuard(JdbcSandboxExecutionGuard.builder(...).build()))`
3. **最小业务 middleware 挂载**：选一个最简单的 Hook（如 `ResponseCacheHook` -> `ResponseCacheMiddleware`）重写为 v2 middleware，挂到 v2 入口
4. **最小业务工具挂载**：选一个最简单的工具（如 `KnownEntities`）改用 v2 `@Tool` 注解，`Toolkit.registerTool(...)` 注册到 v2 入口
5. **v2 入口冒烟测试**：
   - SSE 流式聊天（`streamEvents()` 28 typed 事件）
   - 一个工具调用
   - 一个沙箱执行
   - Plan Mode（`plan_enter` -> `plan_write` -> `plan_exit` + HITL）
6. **v1 入口保持不变**：v1 `HarnessA2aRunner` + 12 shadow override + ReActAgent 1906 行定制全部保留，继续生产

**验收**：v2 入口 `HarnessA2aRunnerV2` 编译通过；冒烟测试 5 项全过；v2 builder 11 能力生效（改 `AGENTS.md` 后下一轮 system prompt 立即变化；`memory/YYYY-MM-DD.md` 生成；`CompactionConfig` 触发；`JdbcSandboxExecutionGuard` 并发锁有效；Plan Mode HITL 完整）；v1 入口生产不受影响

### 阶段 2：记忆模块挂载（2 周）

**目标**：v1 记忆体系作为 v2 扩展挂载到 v2 入口；3 个 memory shadow override 删除。

1. **`MySQLSession` -> `JdbcAgentStateStore`**（§3.2.2）：
   - v2 `JdbcAgentStateStore` 内置实现，按 `(userId, sessionId)` 寻址
   - 业务字段扩展通过 `AgentState.context` 自定义 key
2. **`EpisodicMemory` / `EpisodicResult` / `MemoryProvider` 迁移到业务包**：
   - 从 `io.agentscope.core.memory.*` 迁到 `com.agentscopea2a.agent.memory.*`
   - **删除 3 个 shadow override**（阶段 2 删除点）
3. **`EpisodicLongTermMemoryAdapter` 重写为 `EpisodicRetrievalMiddleware`**（onSystemPrompt）：
   - 注入历史相关对话到 system prompt
   - 前缀标注来源（`[Episodic]` vs v2 `[MEMORY.md]`）
4. **v2 双层记忆接入**（builder 默认）：
   - `MemoryConfig.builder().flushPrompt(...).consolidationPrompt(...).model("openai:gpt-4.1-mini")` -- 三处独立 LLM，小模型省成本
   - `MemoryConfig.FlushTrigger.throttled(Duration.ofMinutes(10))` -- per-call flush 节流
   - `CompactionConfig.builder().triggerMessages(40).keepMessages(12).truncateArgs(...)` -- 对话压缩 + 参数截断
   - `ToolResultEvictionConfig` 大工具结果卸载（80K 字符）+ 占位符
   - 上下文溢出自动压缩重试（`context_length_exceeded` 兜底）
   - `memory_search` / `memory_get` / `session_search` 工具注册
5. **二开 digestion 体系对接**：
   - `MemoryDigestionService` / `MemoryFlowConsolidator` / `SkillFlowEvolver` / `TraceMiner` 作为后台维护
   - 对接 v2 `MemoryMaintenanceMiddleware` 节流闸门
6. **MySQL 镜像保留**：
   - `MysqlMemoryStore` + `MemoryHydrator` + `MemoryFileWatcher` 双写保留
   - `MEMORY.md` 文件 ⇄ MySQL 双向同步
7. **`MemoryFlushHook` / `MemoryMaintenanceHook` shadow override 删除**（阶段 2 删除点）

**验收证据**：(1) `memory/2026-07-13.md` 已生成且含本次会话事实；(2) `MEMORY.md` 出现在下一轮 system prompt；(3) flush/consolidation/compaction 三处 LLM 调用的 model 字段为 `openai:gpt-4.1-mini`，token 成本相比主模型降 60%+；(4) 触发 `CompactionConfig.triggerMessages=40` 后上下文 token 数下降；(5) `ToolResultEvictionConfig` 对 80K+ 字符工具结果落盘 + 占位符；(6) 3 个 memory shadow override 已删除；(7) FTS5 检索正常

### 阶段 3：Skills 模块挂载（2 周）

**目标**：v2 SkillCurator 闭环作为主路径；v1 算法逐个评估替换，仅保留 v2 无等价的部分。

1. **`SkillIndexRepository` 改造为 `AgentSkillRepository`**（§3.2.3）：
   - 参考 v2 `MysqlSkillRepository` 扩展
   - 保留二开字段（fingerprint / source / usage_count / last_used）
2. **v1 算法逐个评估替换**（§3.2.3 清单）：
   - `SkillSynthesisRunner`（636 行）-> **评估替换**：v2 `ProposeSkillTool` + `SkillManageTool` 覆盖则删 v1
   - `SkillEvolutionRunner`（442 行）-> **评估替换**：v2 `SkillCurator` 后台整理覆盖则删 v1
   - `SkillDistiller`（648 行）-> **保留 v1**（v2 无等价），对接 v2 `SkillManageTool` 写回
   - `SkillVectorIndex`（372 行）-> **保留 v1**（v2 无等价），作为 `SkillVisibilityFilter` 实现
   - `FingerprintCalculator`（246 行）-> **保留 v1 语义指纹**（v2 只有 SHA-256 文件级）
   - `MetricClassificationService`（413 行）-> **保留 v1**（v2 只有使用计数），对接 v2 `SkillUsageRecord`
3. **v2 SkillCurator 闭环启用**（builder 默认）：
   - `.enableSkillManageTool(SkillManageConfig.defaults())` / `.enableSkillCurator(SkillCuratorConfig.builder()...)`
   - `SkillPromotionGate`（`LocalApprovalGate` 对接内网审批流程）
   - `SkillVisibilityFilter` 灰度发布（`CanaryFilter` + `EnvironmentFilter`，对接 v1 `SkillVectorIndex`）
   - `SkillSecurityScanner` 安全扫描（agent 自写 skill 防恶意 shell）
   - `SkillUsageStore` 使用计数 + `SkillAuditLog` 审计（对接 v1 `MetricClassificationService`）
   - `MarketplaceStager` SHA-256 文件级去重 + 孤儿清理 + 执行位恢复
   - 4 层合成（project global / marketplace / workspace shared / user-isolated）
4. **v1 算法对接 v2 框架**（保留的部分）：
   - `SkillVectorIndex` / `FingerprintCalculator` / `MetricClassificationService` 作为 `SkillVisibilityFilter` / `SkillUsageStore` 扩展实现
   - `SkillDistiller` 输出通过 `SkillManageTool` 写回，纳入 v2 审批 / 灰度 / 审计流程
5. **二开 EmbeddingClient 保留**：内网 OpenAI 兼容 API

**验收证据**：(1) `propose_skill` 工具可起草草稿到 `skills/_drafts/`；(2) `SkillPromotionGate` 审批流程跑通（`LocalApprovalGate` stdin 确认）；(3) `SkillSecurityScanner` 拦截含恶意 shell 的 skill；(4) `CanaryFilter` 按 10% 灰度发布；(5) `SkillAuditLog` 记录 propose / promote / 使用计数；(6) v1 保留部分（蒸馏 / 向量索引 / 语义指纹 / 指标分类）与 v2 框架对接顺畅；(7) "越用越聪明"效果不退化

### 阶段 4：沙箱模块挂载（1-2 周）

**目标**：v2 `DockerFilesystemSpec` + v1 内网定制子类；3 个 Docker shadow override 删除。

1. **`InternalDockerFilesystemSpec` 新建**（继承 `DockerFilesystemSpec`）：
   - `.image("internal/agentscope-sandbox:2.0")` -- 内网 registry 镜像
   - `.isolationScope(IsolationScope.USER)` -- 多租户隔离，userId 缺失自动降级 SESSION
   - `.snapshotSpec(new JdbcSnapshotSpec(dataSource, "sandbox_snapshots"))` -- MySQL BLOB 快照，跨副本恢复
   - `.executionGuard(JdbcSandboxExecutionGuard.builder(dataSource).build())` -- MySQL `GET_LOCK` 并发锁
   - `.workspaceProjectionRoots(List.of("skills", "subagents", "knowledge", ".skills-cache"))` -- workspace 投影根
   - `.workspaceSpec(new WorkspaceSpec()...)` -- 结构化布局
   - `DockerSandboxClientOptions` 子类配置 `memorySizeBytes` / `cpuCount` / `network` / `additionalRunArgs`
2. **v1 内网定制保留为子类**：
   - `DockerCliRunner` 内网 registry 镜像管理 / CLI 命令审计 / 命令白名单 -> 包装为 `DockerSandbox` 子类或独立 `DockerCliRunner`
   - `DockerSandbox` 内网镜像预拉取 / 容器启动 hook -> 保留为 `DockerSandbox` 子类
   - `DockerSandboxClient` 连接池 / 鉴权 / 健康检查 -> 保留为 `DockerSandboxClient` 子类或 `SandboxClientOptions` 扩展
3. **3 个 Docker shadow override 删除**（阶段 4 删除点）：
   - `DockerCliRunner.java` / `DockerSandbox.java` / `DockerSandboxClient.java` 的 shadow override 删除，定制点迁到子类
4. **v2 沙箱能力验证**：
   - 跨副本 resume 演示（节点 A 创建沙箱，节点 B resume）
   - `JdbcSandboxExecutionGuard` 并发锁有效（两副本同 scope 同时 call，串行执行）
   - `IsolationScope.USER` 多租户隔离（不同 userId 互不污染）

**验收证据**：(1) `DockerSandbox.start()/stop()/exec()` 行为与 v1 二开等价；(2) 跨副本 resume 成功（节点 A 创建 -> 节点 B resume）；(3) `JdbcSandboxExecutionGuard` 并发锁有效（两副本同 scope 串行执行）；(4) `IsolationScope.USER` 多租户隔离（不同 userId 互不污染）；(5) `JdbcSnapshotSpec` 快照跨副本恢复；(6) workspace 投影根同步正确；(7) 3 个 Docker shadow override 已删除；(8) v1 内网定制（registry / CLI 审计 / 命令白名单 / 连接池 / 鉴权 / 健康检查）保留在子类

### 阶段 5：维度状态 + Artifact + 多数据源挂载（2 周）

**目标**：v1 业务定制作为 v2 扩展挂载；v2 使能依赖（`DistributedStore` / `CompositeFilesystem` / `JdbcStore`）采纳。

1. **维度状态 -> `DimensionStateMiddleware`**（§3.2.5）：
   - `DimensionStateManager` / `DimensionPrompts` / `DimensionState` 等保留，改为 `onSystemPrompt` middleware
   - `.middlewares(List.of(new DimensionStateMiddleware(...)))`
2. **Artifact -> `CompositeFilesystem` + `ArtifactStoreMiddleware`**（§3.2.8）：
   - `ArtifactStore` / `TabularExtractor` / `ArtifactContext` / `ArtifactRef` 保留，改为 middleware
   - `LocalArtifactIo` / `SshArtifactIo` 对接 v2 `CompositeFilesystem` 后端
   - `.filesystem(new CompositeFilesystem(LocalFilesystem.withShell(), new RemoteFilesystem(JdbcStore.from(dataSource))))` -- Artifact 跨副本共享
3. **多数据源 -> `DistributedStore` + 业务路由保留**（§3.2.6）：
   - MySQL / ClickHouse / GaussDB 业务路由全部保留
   - `.distributedStore(JdbcDistributedStore.from(dataSource))` 一行注入（阶段 1 已配，此处验证生效）
4. **维度状态 / Artifact / 多数据源 v1 入口对应模块进入"只读"**：
   - v1 `HarnessA2aRunner` 的维度状态 / Artifact / 多数据源模块不再接新需求
   - 新需求全部在 v2 入口开发

**验收证据**：(1) 维度状态注入 system prompt 正常（`DimensionStateMiddleware` onSystemPrompt 生效）；(2) Artifact 跨副本共享（节点 A 写入 -> 节点 B 读取）；(3) 多数据源业务路由正常（MySQL / ClickHouse / GaussDB）；(4) `DistributedStore` 一行注入生效（stateStore + baseStore + executionGuard + snapshotSpec 自动接好）

### 阶段 6：元工具 + SSE 流式挂载（1-2 周）

**目标**：v1 元工具改用 v2 注解；v2 `Channel` / `Gateway` / `AsyncToolMiddleware` 采纳；`SubagentsHook` / `AgentSpawnTool` / `AgentSpecLoader` shadow override 删除。

1. **`@Tool` / `@ToolParam` 注解改造**（§3.2.9）：
   - `DataPrimitivesTool` / `QualityTools` / `SkillSaveTool` / `CaptureSkillSaveTool` / `PythonExecTool` / `KnownEntities` 全部改用 v2 注解
   - `Toolkit.registerTool(Object)` 反射注册到 v2 入口
2. **`ToolRoutersIndex` 对接 `ToolGroupManager`**：
   - 二开工具路由索引作为 `ToolGroup` 激活策略
   - `MetaToolFactory` 按需激活
3. **`PythonExecTool` 对接 v2 沙箱**：
   - 通过 `SandboxContext` 注入 v2 `ShellExecuteTool`
   - 复用 v2 沙箱生命周期 + 预热池
4. **v2 异步工具 + 消息总线采纳**：
   - `AsyncToolMiddleware` + `MessageBus` + `InboxMiddleware` -- 长时工具后台化
   - `WaitAsyncResultsTool` / `ToolSuspendException`
   - `ToolResultEviction`（builder 默认）
   - MCP 集成（`McpClientManager` + `McpTool`）评估对接内网工具服务
5. **SSE 流式 -> `streamEvents()` + `Channel`**（§3.2.7）：
   - v2 入口 `streamEvents()` 返回 `Flux<AgentEvent>` + 28 typed 子类
   - `agent.channel(ChatUiChannel.create())` 创建内部 gateway
   - per-session 并发控制 + 多 agent 路由
   - `BotLoopGuard` 防 bot 死循环（内网多 bot 必备）
   - `IdempotencyStore` 幂等
6. **`SubagentsHook` / `AgentSpawnTool` / `AgentSpecLoader` shadow override 删除**（阶段 6 删除点）：
   - v2 `SubagentsMiddleware` / `AgentSpawnTool` / `AgentSpecLoader` 替代
   - 定制点通过 v2 Builder 或子类注入
7. **安全中断 / 优雅取消 / HITL**：
   - v2 `InterruptContext` / `InterruptControl` / `InterruptSource` 接入
   - SSE 流式支持中断 / 取消
   - middleware 在任意 reasoning step 注入修正 / 补充上下文
   - 对接 v2 `RequireUserConfirmEvent` / `UserConfirmResultEvent`

**验收证据**：(1) 工具调用全链路正常（含元工具 / `ToolGroup` 激活）；(2) 长时工具异步化后主推理不阻塞（`AsyncToolMiddleware` + `HintBlock` 推回）；(3) `streamEvents()` 28 typed 事件流式正常；(4) `Channel` per-session 并发控制有效；(5) `BotLoopGuard` 拦截死循环；(6) 中断 / 取消正常（`InterruptControl` 保留上下文）；(7) HITL 人机协同正常（任意 reasoning step 注入）；(8) 3 个 shadow override（`SubagentsHook` / `AgentSpawnTool` / `AgentSpecLoader`）已删除

### 阶段 7：v2 全链路验证 + session 灰度切换（2 周）

**目标**：v2 入口跑通全链路；session 灰度切换（新 session 走 v2，老 session 继续走 v1）。

1. **v2 全链路验证**：
   - SSE 流式聊天 + 中断 / 取消 + HITL
   - 工具调用（含元工具 / `ToolGroup` / 异步工具 / MCP）
   - 子 agent 编排（同步 / 后台 / 流式转发）
   - 记忆（Episodic + 双层 + MySQL 镜像 + `memory_search` / `session_search`）
   - 技能（合成 / 演化 / 蒸馏 / 检索 / 提升 / 审批 / 安全扫描 / 灰度）
   - 维度状态注入
   - 多数据源（MySQL / ClickHouse / GaussDB）
   - Artifact 体系（本地 / SSH 远端 / 跨副本共享）
   - Docker 沙箱（含跨副本 resume + 并发锁）
   - Plan Mode（`plan_enter` -> `plan_write` -> `plan_exit` + HITL）
   - Permission 系统（5 mode + HITL 审批）
2. **数据迁移脚本执行**（§3.5）：
   - 会话状态 v1 -> v2 格式迁移
   - 长期记忆 `MEMORY.md` 迁移
   - 沙箱快照本地 -> `JdbcSnapshotSpec` 迁移
   - 技能库 schema 迁移
3. **session 灰度切换**：
   - 路由层配置：新 session 走 v2 入口，老 session 继续走 v1 入口
   - 灰度比例：10% -> 30% -> 50% -> 100%（新 session）
   - 监控：错误率 / 延迟 / 资源消耗对比 v1 基线
4. **回滚演练**：
   - 验证流量可即时切回 v1（v1 入口完整保留）
   - 演练切换流程，记录 RTO

**验收证据**：(1) v2 入口全链路 11 项功能验证通过；(2) 数据迁移脚本执行成功，老 session 切到 v2 后状态恢复正确；(3) session 灰度切换生效（新 session 100% 走 v2）；(4) 错误率 / 延迟 / 资源消耗对比 v1 基线无退化；(5) 回滚演练 RTO < 5 分钟

### 阶段 8：观察期 + Memory Digestion Pipeline 迁移（2-4 周）

> **实施说明**：阶段 8 实际实施不仅包含原计划的观察期，还完成了 Memory Digestion Pipeline + Skill PR2/PR3/PR4 闭环的全量迁移。详见 §〇·H 阶段 8 实施状态。

**原计划目标**：v2 入口承接主要流量；v1 入口待删除，承接剩余老 session。

**实际实施**：
1. **Memory Digestion Pipeline 全量迁移**（9 文件 git mv）：
   - `MysqlMemoryStore` / `MemoryHydrator` -> `v2/memory/`
   - `SkillDistiller` / `SkillSynthesisRunner` / `SkillEvolutionRunner` -> `v2/skills/`
   - `TraceMiner` / `SkillFlowEvolver` / `MemoryFlowConsolidator` / `MemoryDigestionService` -> `v2/digestion/`
   - `V2DigestionConfig` 新建（显式装配 digestion beans，带 `digestion.enabled` 门禁）
   - `V2MemoryConfig` / `V2SkillConfig` 扩展（新增 5 个 bean）
2. **V2ChatController 接入 V2SessionRouter 灰度守卫**：
   - v1 桶命中时返回 HTTP 503（真正 v1 fallback 推迟到 Stage 9）
3. **ToolRoutersIndex 保留为 fallback**：
   - 运行时验证未执行，按计划允许保留
4. **观察期任务**（推迟到 Stage 9 运行时验证后启动）：
   - v2 入口监控（OTel / metrics / 错误率 / 延迟）
   - v1 入口只读维护
   - 剩余老 session 切换
   - v1 入口流量归零确认

**验收证据**（已满足）：(1) `mvn clean compile` -> BUILD SUCCESS，73 个 v2 源文件；(2) 9 文件迁移 + 1 新 config + 3 config 修改 + 1 controller 修改完成

**验收证据**（推迟到 Stage 9 运行时验证）：(1) v2 入口稳定运行 2-4 周，无严重故障；(2) v1 入口流量归零持续 1 周；(3) OTel tracing 正常；(4) 关键能力 metrics 正常；(5) Memory Digestion nightly cron 触发；(6) `reset_equipped_tools` meta-tool 运行时验证（决定 ToolRoutersIndex 移除）

### 阶段 9：v1 入口整体删除 + 回归测试 + 内网部署（1 周）

**目标**：v1 入口 + 12 shadow override + ReActAgent 1906 行定制一次性删除；全功能回归；内网部署。

1. **v1 入口整体删除**（§3.4 删除计划）：
   - `HarnessA2aRunner`（v1 入口）删除
   - `io.agentscope.core.ReActAgent` shadow override 删除（1906 行定制点已全部迁到 v2 middleware）
   - 验证 v2 jar `ReActAgent` 生效（不再被屏蔽）
   - 路由层移除 v1 入口分支
2. **12 shadow override 全部删除**：
   - 阶段 2 已删 3 个 memory 文件
   - 阶段 4 已删 3 个 Docker 文件
   - 阶段 6 已删 3 个 subagent 文件
   - 阶段 9 删除剩余 3 个（如有）
   - 验证 v2 jar 同名类生效（不再被屏蔽）
3. **功能回归**（全功能）：
   - SSE 流式聊天 + 中断 / 取消 + HITL
   - 工具调用（含元工具 / `ToolGroup` / 异步工具 / MCP）
   - 子 agent 编排（同步 / 后台 / 流式转发）
   - 记忆（Episodic + 双层 + MySQL 镜像）
   - 技能（合成 / 演化 / 蒸馏 / 检索 / 提升 / 审批）
   - 维度状态
   - 多数据源（MySQL / ClickHouse / GaussDB）
   - Artifact 体系（本地 / SSH 远端 / 跨副本共享）
   - Docker 沙箱（含跨副本 resume + 并发锁）
   - Plan Mode + Permission + TaskList
4. **性能回归**：
   - 首请求延迟（v2 双层记忆预热）
   - 并发吞吐（v2 per-(userId, sessionId) 隔离 + `Channel` per-session 并发）
   - 上下文压缩触发
   - 大工具结果卸载
5. **内网部署**：
   - 内网 Maven 仓库依赖验证
   - Docker 沙箱镜像内网化
   - 配置文件内网化（API key / DataSource）
   - `DistributedStore` 用 `JdbcDistributedStore`（MySQL）
6. **文档更新**：
   - 升级说明文档
   - 内网部署手册
   - 运维监控手册

**验收证据**：(1) v1 入口 + 12 shadow override + ReActAgent 1906 行定制全部删除；(2) v2 jar 同名类生效（`ReActAgent` 等 v2 改进可见）；(3) 全功能回归通过；(4) 性能对比 v1 基线无退化；(5) 内网部署完成

**执行进度（2026/07/16）**：阶段 9 删除部分完成（步骤 1-3 + 功能回归）：

1. ✅ **v1 入口 + shadow override 删除**：
   - `HarnessA2aRunner`（v1 入口）删除
   - `io/agentscope/core/ReActAgent.java`（1906 行定制）删除
   - 12 个 shadow override 全部删除（`io/agentscope/{core,harness,subagent}/**`）
   - v1 业务包整体删除：`com/agentscopea2a/{agent,harness,service}/**` + `ChatController.java` + `DigestionController.java`
   - `pom.xml` 的 `<excludes>` + `<testExcludes>` 移除（v1 源码已无）
   - 唯一保留：`WorkspaceMaterializer` 工具类迁移到 `com.agentscopea2a.v2.config.WorkspaceMaterializer`（`SubagentRegistrar` 依赖）

2. ✅ **构建验证**：`mvn clean compile -Dmaven.test.skip=true` -> BUILD SUCCESS（5.2s，仅 deprecation warning）；`mvn package` -> jar 生成成功

3. ✅ **功能回归**（启动 + smoke test）：
   - `HarnessA2aRunnerV2 initialized` + `V2SessionRouter: v2Percentage=100`
   - 7 个 v2 hook 全部 wired（ArtifactHandoffHook/PythonExecRetryHook/ToolCallTrackingHook/SkillRetrievalHook/SkillSynthesisHook/SkillEvolutionHook/KnowledgeRetrievalHook）
   - SSE 请求成功：`stage9-smoke-001` 返回正确质量分 3.1，`event:done` 正常收尾，无 500
   - 技能合成 MISS path 触发：`[MISS path] candidate _global|query|general hit=3 status=synthesized thr=3`
   - 无 ERROR/Exception

4. ⏳ **剩余**：性能回归 + 内网部署（Maven 仓库 / Docker 镜像 / 配置内网化）推迟到内网环境验证

---

## 五、风险与应对

| 风险 | 影响 | 应对 |
|---|---|---|
| **双轨期数据一致性** | v1 v2 并行期，记忆 / 状态 / Artifact 写入两套格式，可能导致切换后状态丢失 | 阶段 7 数据迁移脚本一次性跑；老 session 切到 v2 时从迁移后数据恢复；切流量后新写入全部走 v2 格式 |
| **双轨期资源消耗** | v1 v2 两套入口并行，内存 / 连接池 / 沙箱实例翻倍 | 双轨期短（阶段 7-8 共 3-5 周）；v1 入口只读维护，资源消耗低；阶段 9 v1 整体删除后资源恢复 |
| **session 灰度切换路由错误** | 路由层配置错误，新 session 走 v1 或老 session 走 v2 | 阶段 7 路由层充分测试；灰度比例渐进（10% -> 30% -> 50% -> 100%）；回滚演练 RTO < 5 分钟 |
| **回滚不彻底** | 切回 v1 后，v2 写入的数据 v1 读不懂 | v1 v2 共享存储 schema（`AgentStateStore` 表 v1 v2 兼容）；v2 写入字段 v1 忽略；v1 写入字段 v2 兼容 |
| **ReActAgent 1906 行定制丢失** | 阶段 9 删除 shadow override 后，定制逻辑可能遗漏 | 阶段 0 完整提取定制点清单，逐项映射到 v2 middleware；阶段 9 删除前确认所有定制点已迁移 |
| **Hook -> Middleware 行为偏差** | 9 个业务 Hook 重写可能引入 bug | 阶段 6 每完成一个 Hook 立即回归测试；v1 v2 双轨期对比行为差异 |
| **Memory 体系双轨冲突** | v2 双层记忆与二开 digestion 可能重复 flush | 明确分工：v2 负责 per-session 上下文压缩，二开负责跨 session 业务事实沉淀；通过 `MemoryConfig.flushTrigger=THROTTLED` 节流 |
| **v1 Episodic 与 v2 双层记忆语义重叠** | v1 `EpisodicMemory`（跨 session 业务事实）与 v2 `MEMORY.md`（per-session 长期记忆）内容可能重复 | 明确分工：v1 Episodic 负责跨 session 业务事实（用户偏好 / 项目约定），v2 `MEMORY.md` 负责 per-session 上下文压缩；`EpisodicRetrievalMiddleware` 在 `onSystemPrompt` 注入 Episodic，v2 自动注入 `MEMORY.md`，两者前缀标注来源 |
| **PlanNotebook -> Plan Mode 业务逻辑不兼容** | 8 工具状态机 -> 3 工具 markdown，设计思路不同 | 评估二开 PlanNotebook 业务依赖；不兼容部分保留为自定义 middleware |
| **`streamEvents()` 子 agent 事件转发** | v2 `HarnessAgent.streamEvents()` 子 agent 事件转发依赖 `source` 字段 | 阶段 6 验证子 agent 事件流式转发；按 `source` 字段区分父子事件 |
| **Spring Boot 3.2 vs v2 兼容性** | v2 可能默认假设 SB 4.x | 保留 SB 3.2.3，逐文件验证 v2 import 兼容性 |
| **MySQL Connector 8.2 vs v2 默认 9.6** | 时区 / SSL 行为差异 | 保留 8.2，`PersistenceProperties` URL 参数保留 `useSSL+timezone+allowPublicKeyRetrieval` |
| **内网 Docker 沙箱镜像** | v2 默认镜像无法拉取 | 阶段 0 预下载，内网 registry 维护 |
| **`JdbcSandboxExecutionGuard` MySQL `GET_LOCK` 限制** | MySQL `GET_LOCK` 是会话级锁，连接池借还可能跨会话导致锁泄漏 | 阶段 4 验证连接池借还路径，确保 `SandboxLease.close()` 在同连接释放 |
| **`JdbcSnapshotSpec` BLOB 大小限制** | MySQL 默认 `max_allowed_packet=64MB`，大工作区快照可能超限 | 阶段 0 评估工作区典型大小；超限时调大 `max_allowed_packet` 或拆分 BLOB |
| **v1 算法层 vs v2 工程层数据归一** | v1 二开的"指标分类 / 指纹 / 使用计数"与 v2 的 `SkillUsageStore` / `SkillAuditLog` / `FingerprintCalculator` 字段定义、统计口径可能不一致 | 阶段 3 前先做字段映射表：v1 `SkillEntry.usage_count` <-> v2 `SkillUsageRecord.callCount`，v1 `fingerprint` <-> v2 `SkillFingerprint`；归一后保留 v1 字段为业务扩展，v2 字段为框架标准；双写过渡期 1 个月 |
| **v1 维度状态与 v2 AgentState 子上下文冲突** | v1 `DimensionStateManager` 自管状态，v2 `AgentState` 有 `PermissionContextState` / `TaskContextState` 等子上下文，两套状态体系并存可能重复或冲突 | v1 维度状态保留为业务 middleware 自管（不写入 `AgentState.context`），通过 `onSystemPrompt` 注入；v2 子上下文（Task / Permission / PlanMode）用框架标准；两者通过 `RuntimeContext` 传递，不互相序列化 |
| **`AsyncToolMiddleware` 与 v1 同步工具协议不兼容** | v1 工具全部同步返回，业务调用方预期 `ToolResultBlock` 立即返回；v2 异步化后返回占位符 + 后续 `HintBlock`，调用方需改造 | 阶段 6 评估每个工具是否需要异步化：仅长时工具（数据拉取 / 模型推理 > 30s）启用异步，短工具保持同步；SSE 流式前端适配 `HintBlockEvent` 渲染 |
| **多 Channel 并发与 v1 单线程编排冲突** | v1 `HarnessA2aRunner` 单线程顺序处理，v2 `Channel` per-session 并发，业务侧共享资源（如 `ResponseCacheService`）可能竞态 | 阶段 6 前审计共享资源线程安全性：`ResponseCacheService` 改用 `ConcurrentHashMap`，`MysqlMemoryStore` 双写加行锁，`SkillIndexRepository` 读写分离 |
| **v2 后续升级 breaking change** | v2 还在 RC，后续 v2.0 -> v2.1 可能有 breaking change | 双轨并行方案下，项目结构与 v2 上游对齐，breaking change 影响范围小（只动 v2 入口配置）；建立 v2 release notes 跟踪机制 |

---

## 六、验收标准

### 6.1 v2 能力生效证据（核心）

每个 v2 能力必须有"生效证据"，不是"功能没坏"就够：

| v2 能力 | 生效证据 |
|---|---|
| 工作区驱动的人格 | 改 `AGENTS.md` 后下一轮 system prompt 立即变化（不需重启）|
| 双层长期记忆 | `memory/YYYY-MM-DD.md` 生成；`MEMORY.md` 出现在 system prompt；三处 LLM 走小模型（token 成本对比）|
| 对话压缩 | 长对话触发 `CompactionConfig`；上下文 token 数下降；`context_length_exceeded` 自动重试 |
| 大工具结果卸载 | `ToolResultEvictionConfig` 对 80K+ 字符工具结果落盘 + 占位符 |
| 沙箱隔离 | 跨副本 resume 演示成功；`JdbcSandboxExecutionGuard` 并发锁有效 |
| `IsolationScope.USER` | 多租户隔离（不同 userId 互不污染）；userId 缺失自动降级 SESSION |
| Plan Mode | `plan_enter` -> `plan_write` -> `plan_exit` + HITL 完整链路；plan 状态跨重启恢复 |
| Permission 系统 | 5 mode 切换正常；HITL 审批流程跑通 |
| TaskList | `TodoTools` + `TaskReminderMiddleware` 正常；任务状态跨会话恢复 |
| 安全中断 / 优雅取消 | `InterruptControl` 保留上下文；SSE 流式支持中断 / 取消 |
| Channel 路由 | per-session 并发控制生效；`BotLoopGuard` 拦截死循环；28 typed `AgentEvent` 流式正常 |
| 技能装配 | 4 层合成优先级正确；`SkillPromotionGate` 审批流程跑通；`SkillSecurityScanner` 拦截恶意 shell |
| `DistributedStore` | 一行注入生效（stateStore + baseStore + executionGuard + snapshotSpec 自动接好）|
| `CompositeFilesystem` | Artifact 跨副本共享（节点 A 写入 -> 节点 B 读取）|

### 6.2 功能验收

- [ ] SSE 流式聊天正常（含中断 / 取消 + HITL）
- [ ] 工具调用全链路正常（含元工具 / `ToolGroup` 激活 / 异步工具 / MCP）
- [ ] 子 agent 编排正常（同步 / 后台 / 流式转发）
- [ ] 跨会话记忆一致（Episodic FTS5 + 双层记忆 + MySQL 镜像 + `memory_search` / `session_search`）
- [ ] 技能闭环正常（合成 / 演化 / 蒸馏 / 检索 / 提升 / 审批 / 安全扫描 / 灰度）
- [ ] 维度状态注入正常（`DimensionStateMiddleware` onSystemPrompt）
- [ ] 多数据源正常（MySQL / ClickHouse / GaussDB）
- [ ] Artifact 体系正常（本地 / SSH 远端 / 跨副本共享）
- [ ] Docker 沙箱正常（含跨副本 resume + `JdbcSandboxExecutionGuard` 并发锁）
- [ ] Plan Mode 正常（HITL 退出）
- [ ] Permission 系统正常（5 mode + HITL 审批）
- [ ] HITL 人机协同正常（任意 reasoning step 注入）

### 6.3 性能验收

- [ ] 首请求延迟 ≤ 原项目基线
- [ ] 并发吞吐 ≥ 原项目基线（v2 per-(userId, sessionId) 隔离 + `Channel` per-session 并发）
- [ ] 上下文压缩触发正常
- [ ] 大工具结果卸载正常（80K 字符落盘）
- [ ] 三处独立 LLM token 成本相比主模型降 60%+

### 6.4 内网部署验收

- [ ] 内网 Maven 仓库依赖完整
- [ ] Docker 沙箱镜像内网化
- [ ] 配置文件内网化（无外网依赖）
- [ ] `DistributedStore` 用 `JdbcDistributedStore`（MySQL）
- [ ] 全功能在内网环境正常

### 6.5 双轨并行验收

- [ ] v2 入口 `HarnessA2aRunnerV2` 跑通全链路
- [ ] 数据迁移脚本执行成功
- [ ] session 灰度切换生效（新 session 100% 走 v2）
- [ ] 回滚演练 RTO < 5 分钟
- [ ] v1 入口流量归零持续 1 周
- [ ] v1 入口 + 12 shadow override + ReActAgent 1906 行定制全部删除
- [ ] v2 jar 同名类生效（`ReActAgent` 等 v2 改进可见，不再被屏蔽）

---

## 七、附录

### 7.1 包结构（双轨期 + 删除计划）

```
com.agentscopea2a.*                        # 业务包（保留）
├── AgentscopeA2aApplication.java          # 入口
├── agent/
│   ├── dimension/                         # 维度状态（保留 -> DimensionStateMiddleware）
│   ├── hook/                              # 阶段 9 删除（Hook 重写为 middleware）
│   ├── memory/                            # 记忆处理（保留并适配）
│   │   ├── EpisodicMemory.java            # 阶段 2 从 io.agentscope.core.memory 迁入
│   │   ├── EpisodicResult.java            # 阶段 2 从 io.agentscope.core.memory 迁入
│   │   ├── MemoryProvider.java            # 阶段 2 从 io.agentscope.core.memory 迁入
│   │   ├── MySqlEpisodicMemory.java
│   │   ├── MysqlMemoryStore.java
│   │   ├── MemoryHydrator.java
│   │   ├── MemoryFileWatcher.java
│   │   ├── EpisodicLongTermMemoryAdapter.java  # 阶段 2 重写为 EpisodicRetrievalMiddleware
│   │   ├── EpisodicMemoryConfig.java
│   │   └── digestion/                     # 消化体系（保留）
│   ├── model/                             # 模型注册（保留并对接 v2）
│   ├── session/                           # MySQLSession -> JdbcAgentStateStore（阶段 2）
│   └── tools/                             # 业务工具（保留，阶段 6 改用 v2 注解）
│       └── routers/
├── config/
│   └── datasource/                        # 多数据源（保留）
├── controller/                            # SSE 流式（保留，阶段 6 适配 streamEvents）
├── dto/                                   # 数据传输对象（保留）
├── entity/                                # 实体（保留）
├── harness/
│   ├── artifact/                          # Artifact 体系（保留 -> ArtifactStoreMiddleware）
│   ├── cache/                             # 响应缓存（保留 -> ResponseCacheMiddleware）
│   ├── config/                            # 基础设施配置（保留并适配）
│   ├── hooks/                             # 阶段 9 删除（Hook 重写为 middleware）
│   ├── runner/
│   │   ├── HarnessA2aRunner.java          # v1 入口（阶段 9 删除）
│   │   └── HarnessA2aRunnerV2.java        # v2 入口（阶段 1 新建）★
│   ├── sandbox/                           # 阶段 4 新建（内网定制子类）
│   │   ├── InternalDockerFilesystemSpec.java
│   │   ├── InternalDockerSandbox.java
│   │   └── InternalDockerSandboxClient.java
│   ├── skills/                            # Skills 自由化（保留并对接 v2 SkillCurator）
│   ├── tools/                             # 元工具（保留，阶段 6 改用 v2 注解）
│   └── workspace/                         # 远程工作区（保留 -> CompositeFilesystem）
├── mapper/                                # MyBatis Mapper（保留）
│   ├── ck/                                # ClickHouse
│   ├── db1/                               # 主库
│   ├── gauss/                             # GaussDB
│   └── mysql/                             # MySQL
├── middleware/                            # 阶段 2-6 新建（v2 middleware）★
│   ├── DimensionStateMiddleware.java
│   ├── EpisodicRetrievalMiddleware.java
│   ├── ResponseCacheMiddleware.java
│   ├── ArtifactAccessMiddleware.java
│   ├── ArtifactHandoffMiddleware.java
│   ├── ArtifactStoreMiddleware.java
│   ├── PythonExecRetryMiddleware.java
│   ├── SkillEvolutionMiddleware.java
│   ├── SkillRetrievalMiddleware.java
│   ├── SkillSynthesisMiddleware.java
│   ├── ToolCallCollectorMiddleware.java
│   ├── ToolCallTrackingMiddleware.java
│   └── SessionMiddleware.java
├── service/                               # 业务服务（保留）
│   └── impl/
└── util/                                  # 工具类（保留）

io.agentscope.*                            # jar 依赖（v2.0.0-RC5）
├── core/                                  # v2 核心 jar
└── harness/                               # v2 Harness jar

src/main/java/io/agentscape/               # 12 个补丁文件（阶段 2/4/6/9 分批删除）
├── core/
│   ├── ReActAgent.java                    # 阶段 9 删除（1906 行定制迁到 middleware）
│   └── memory/                            # 阶段 2 删除（迁到 com.agentscopea2a.agent.memory）
├── harness/agent/
│   ├── hook/                              # 阶段 2/6 删除（v2 middleware 替代）
│   ├── sandbox/impl/docker/               # 阶段 4 删除（内网定制迁到子类）
│   └── tool/AgentSpawnTool.java           # 阶段 6 删除（v2 替代）
└── subagent/AgentSpecLoader.java          # 阶段 6 删除（v2 替代）
```

### 7.2 v2 入口完整装配示例

```java
public class HarnessA2aRunnerV2 {
    private final HarnessAgent agent;
    private final ChatUiChannel channel;

    public HarnessA2aRunnerV2(DataSource dataSource, Model model, Path workspace) {
        this.agent = HarnessAgent.builder()
            .name("QualitySupervisorV2")
            .model(model)
            .workspace(workspace)
            // ---- v2 builder 默认 11 能力 ----
            .stateStore(new JdbcAgentStateStore(dataSource))              // 状态持久化
            .memory(MemoryConfig.builder()                                // 双层长期记忆
                .flushPrompt(...)
                .consolidationPrompt(...)
                .model("openai:gpt-4.1-mini")
                .flushTrigger(MemoryConfig.FlushTrigger.throttled(Duration.ofMinutes(10)))
                .build())
            .compaction(CompactionConfig.builder()                        // 对话压缩
                .triggerMessages(40)
                .keepMessages(12)
                .truncateArgs(CompactionConfig.TruncateArgsConfig.defaults())
                .build())
            .toolResultEviction(ToolResultEvictionConfig.defaults())      // 大工具结果卸载
            .enablePlanMode().planFileDirectory("plans")                  // Plan Mode
            .enableTaskList(true)                                         // TaskList
            .permissionContext(PermissionContextState.defaults())         // Permission 系统
            // ---- 沙箱（v2 + v1 内网定制子类）----
            .filesystem(new InternalDockerFilesystemSpec()
                .image("internal/agentscope-sandbox:2.0")
                .isolationScope(IsolationScope.USER)
                .snapshotSpec(new JdbcSnapshotSpec(dataSource, "sandbox_snapshots"))
                .executionGuard(JdbcSandboxExecutionGuard.builder(dataSource).build()))
            // ---- DistributedStore 一行注入 ----
            .distributedStore(JdbcDistributedStore.from(dataSource))
            // ---- Skills（v2 SkillCurator 闭环 + v1 算法扩展）----
            .skillRepository(new InternalSkillRepository(dataSource))
            .enableSkillManageTool(SkillManageConfig.defaults())
            .enableSkillCurator(SkillCuratorConfig.builder()
                .staleAfterDays(30).archiveAfterDays(90).build())
            .enableSkillPromotionGate(
                new LocalApprovalGate(LocalApprovalGate.defaultPrompter()),
                new CompositeFilter(List.of(
                    new EnvironmentFilter("prod", skillUsageStore),
                    new CanaryFilter(0.10, skillUsageStore),
                    new SkillVectorIndexFilter(skillVectorIndex)         // v1 算法作为 v2 扩展
                )))
            // ---- 业务 middleware（v1 Hook 重写）----
            .middlewares(List.of(
                new SessionMiddleware(...),
                new ResponseCacheMiddleware(...),
                new DimensionStateMiddleware(...),                        // 维度状态注入
                new EpisodicRetrievalMiddleware(...),                     // Episodic 注入
                new SkillRetrievalMiddleware(...),                        // onSystemPrompt
                new ArtifactHandoffMiddleware(...),                       // onAgent
                new SkillEvolutionMiddleware(...),                        // onAgent
                new SkillSynthesisMiddleware(...),                        // onAgent
                new ArtifactAccessMiddleware(...),                        // onActing
                new PythonExecRetryMiddleware(...),                       // onActing
                new ToolCallCollectorMiddleware(...),                     // onActing
                new ToolCallTrackingMiddleware(...)                       // onActing
            ))
            // ---- 子 agent ----
            .subagent(SubagentDeclaration.builder()
                .name("reviewer").description("代码审查专家").build())
            .build();

        // ---- Channel / Gateway ----
        this.channel = agent.channel(ChatUiChannel.create());
    }

    public Flux<AgentEvent> streamEvents(List<Msg> messages, AgentRequestOptions options) {
        RuntimeContext ctx = RuntimeContext.builder()
            .sessionId(options.getTaskId())
            .userId(options.getUserId())
            .build();
        return agent.streamEvents(messages, ctx);
    }
}
```

### 7.3 SSE 流式改造示例

```java
// v1：Flux<Event> stream(...) -- @Deprecated(forRemoval=true)
@Override
public Flux<Event> stream(List<Msg> requestMessages, AgentRequestOptions options) {
    // ...
    return agent.stream(msgs, options, ctx);
}

// v2：Flux<AgentEvent> streamEvents(...) -- 28 typed 事件
@Override
public Flux<AgentEvent> streamEvents(List<Msg> requestMessages, AgentRequestOptions options) {
    RuntimeContext ctx = RuntimeContext.builder()
        .sessionId(options.getTaskId())
        .userId(options.getUserId())
        .build();
    return agent.streamEvents(requestMessages, ctx)
        .doOnNext(event -> {
            // 适配 SSE 协议：TEXT_BLOCK_DELTA / TOOL_CALL_* / RequireUserConfirmEvent 等
            // 28 个类型化事件覆盖全生命周期 + HITL
            // 子 agent 事件按 source 字段区分
        });
}
```

### 7.4 中断 / 取消接入

```java
// v2 安全中断：保留上下文与工具状态
agent.interrupt(InterruptContext.builder()
    .source(InterruptSource.USER)
    .sessionId(taskId)
    .build());

// v2 优雅取消：终止长时间运行的工具调用
agent.cancel(InterruptContext.builder()
    .source(InterruptSource.SYSTEM)
    .sessionId(taskId)
    .build());

// v2 HITL：middleware 在任意 reasoning step 注入
public class HumanInLoopMiddleware implements MiddlewareBase {
    @Override
    public Mono<ReasoningOutput> onReasoning(ReasoningInput input, Next<ReasoningOutput> next) {
        return next.apply(input)
            .flatMap(output -> {
                if (requiresHumanReview(output)) {
                    return requestHumanConfirm(output)  // 触发 RequireUserConfirmEvent
                        .then(Mono.just(output));
                }
                return Mono.just(output);
            });
    }
}
```

### 7.5 升级时间估算

| 阶段 | 工作量 | 累计 |
|---|---|---|
| 阶段 0：准备 | 1 周 | 1 周 |
| 阶段 1：v2 入口最小路径 | 1-2 周 | 2-3 周 |
| 阶段 2：记忆模块挂载 | 2 周 | 4-5 周 |
| 阶段 3：Skills 模块挂载 | 2 周 | 6-7 周 |
| 阶段 4：沙箱模块挂载 | 1-2 周 | 7-9 周 |
| 阶段 5：维度状态 + Artifact + 多数据源挂载 | 2 周 | 9-11 周 |
| 阶段 6：元工具 + SSE 流式挂载 | 1-2 周 | 10-13 周 |
| 阶段 7：v2 全链路验证 + session 灰度切换 | 2 周 | 12-15 周 |
| 阶段 8：观察期 + Memory Digestion Pipeline 迁移 | 2-4 周 | 14-19 周 |
| 阶段 9：v1 整体删除 + 回归 + 内网部署 | 1 周 | 15-20 周 |
| **合计（不含观察期）** | **13-16 周** | |
| **合计（含观察期）** | **15-20 周** | |

注：双轨并行相比 in-place 升级首次工作量略大（多 v2 入口重建 + 数据迁移脚本），但后续 v2 升级（v2.0 -> v2.1 -> v2.2...）边际成本极低（改 pom + 回归 + breaking change 处理），不再需要"采纳清单"。

---

## 八、下一步

待确认后进入阶段 0：
1. 内网 Maven 仓库 v2 全量 jar 预下载
2. 升级分支创建（`upgrade/2.0.0-RC5-dual-track`）
3. 二开功能清单逐项标注（v2 扩展形态 / 挂载方式 / 挂载阶段）
4. 回归测试基线记录
5. 12 个补丁文件定制点提取（diff v1 jar 原版，为迁移到 v2 扩展做准备）

建议从阶段 1 v2 入口最小路径开始：
- 新建 `HarnessA2aRunnerV2`（基于 `HarnessAgent.builder()`）
- 跑通 workspace + memory + 一个 middleware + 一个工具 + 一个沙箱
- 验证 v2 builder 11 能力生效（改 `AGENTS.md` 后下一轮 system prompt 立即变化等）
- v1 入口保持不变，继续生产

**关键提醒**：双轨并行方案的核心是"v2 builder 为新骨架，业务能力作为 v2 扩展重写"。阶段 1 跑通 v2 入口最小路径是风险验证 -- 跑不通就及时止损，不影响 v1 生产入口。跑通后按模块渐进挂载，每挂一个模块 v2 入口就多一个能力，v1 入口对应模块进入"只读"。v2 跑通全链路后 session 灰度切换，观察期结束 v1 整体删除。
