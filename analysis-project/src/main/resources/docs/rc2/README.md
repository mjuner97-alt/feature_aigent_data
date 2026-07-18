# Agentscope Harness 多智能体编排引擎

> AgentScope Java **harness 1.1.0-RC2** + Spring Boot 3.2.3 (Java 17) 上的多智能体 Harness 示例。
> 一个 Markdown 驱动的"质量数据智能助手",涵盖 **两步式工具路由 / Skills 自演化 / 缓存命中短路 / 多租户 artifact 协议 / 沙箱执行 / MySQL 镜像 memory / 共享单容器 / 夜间记忆咀嚼** 等生产级设计点。

---

## 1. 这个示例做什么

模拟一个 **QualitySupervisor**:用户用自然语言提问,主管(supervisor)派单给四个领域子智能体,完成下面三类任务。

| 用户意图 | 派单链路 | 数据/工具 |
|---|---|---|
| **简单查询**(`查询2026年1季度杭州开发一部的质量数据`) | supervisor → `query_data` | `tool_index` skill 选 `toolId` → `toolMetaInfo` → `router_tool` 路由到 `AgentTools` 的 `quality_query_by_*` |
| **数据分析**(`对比 Q1 与 Q2 缺陷密度,算环比`) | supervisor → `analyze_data` → `query_data`×N →(`data_primitives` 路由 / 或 `code_interpreter`) | `AgentTools` 取数 + `DataPrimitivesTool`(5 个 pandas 原语)或沙箱 `python_exec`;**中间数据走 CSV artifact** |
| **保存技能**(`把刚才流程保存为 skill`) | supervisor → `generate_skill` → `save_skill` | `SkillSaveTool` 落盘 `workspace/skills/<name>/SKILL.md` |

底层值得关注的能力(每一项后面括号里是"为什么这条对你重要"):

- **workspace 文件驱动子智能体**(加 agent 不动 Java)——
  人格 + 工具清单都在 [`src/main/resources/workspace/agent-subagents/*.md`](../workspace/agent-subagents/) 的 YAML front matter 里声明。加一个子智能体 = 新增一个 `.md`;子代理只需要在 `tools:` 写 `tool_router`(或 `python_exec` / `skill_save` 等少数特殊工具),所有业务工具都通过元工具路由调用,**不再需要在 Java 端维护逐工具白名单**。
- **★ 两步式元工具路由**(LLM 不直接看一整个工具菜单)——
  子代理拿到的只有两个元工具:`toolMetaInfo(toolId)` 查参数定义、`router_tool(paramsJson)` 统一执行入口。业务工具(`AgentTools.quality_query_by_*`、`DataPrimitivesTool.data_aggregate/...`)被 `ToolRoutersIndex` 反射扫描 `@Tool` 方法登记到 `toolId` 映射表;子代理先查 `tool_index` / `data_primitives` 这两个 skill 拿到 `toolId`,再发起调用。**工具数量从 O(N) 个名字膨胀到 LLM 上下文 → 收敛到 2 个元工具 + 1 个索引 skill**。
- **★ Skills 自演化(越用越聪明)**(`harness/skills/` 全套,已覆盖 **8 项优化**)——
  - `SkillSynthesisHook` — `PostReply` 阶段把成功的工作流提炼成 `SkillCandidate` 入库(MySQL `skill_candidate` 表);累计 N 次同质轨迹 → `SkillDistiller` 用 LLM 蒸馏出 `SKILL.md` 草稿(带重试 + 宽松解析,蒸馏成功率 ~95%)
  - `SkillEvolutionHook` — 跑时基于反馈(用户拒绝 / 工具失败关键字)对现有 skill 打负分,触发再合成;跨 JVM 通过 MySQL `UPDATE ... WHERE evolving=FALSE` 互斥演进,避免重复 LLM 调用
  - `SkillRetrievalHook` — `PreReply` 阶段按问题 embedding 在 `SkillVectorIndex`(`OpenAiCompatEmbeddingClient`)里 top-k 检索;L2 使用 **JVM 内存缓存**(60s 定时刷新 + 写穿透),避免全表 SQL;命中时额外查询 EpisodicMemory 追加 `## 最近参考案例`
  - `SkillDistiller` — 失败时自动重试 + `parseLenient()` 宽松解析(name→hash/description→name 两级 fallback),仅 body 缺失才返回 null
  - `SkillEvolutionRunner` — 演进锁升级为 MySQL 级(`evolving` 列 + `UPDATE ... WHERE evolving=FALSE`);Pending Judgement 从 JVM `LinkedHashMap` 升级为 `skill_pending_judgement` 表(MySQL 持久化,L1+L2 双层)
  - `HarnessA2aRunner` — 缓存 HIT 路径调用 `recordCacheHitToEpisodic()`,让缓存命中的交互也在 EpisodicMemory 留下痕迹,避免长期记忆偏斜
  - `MySqlEpisodicMemory` — `search()` 支持向量检索(复用 EmbeddingClient + cosine 计算),配置开关 `vectorSearchEnabled` 控制,结果不足时 fallback FTS
  - 分层注入:元技能(`skills-builtin/`)全量加载,业务技能(`skills-auto/`)仅 L1/L2 命中时注入
- **HarnessAgent 内置 hook 全开**(免费拿生产能力)——
  `WorkspaceContextHook` / `SessionPersistenceHook` / `CompactionHook`(40 触发,12 保留) / `ToolResultEvictionHook`(80000 字符以上落盘) / `MemoryFlushHook` 等都是 builder 默认开。
- **业务 hook 共 7 个**(业务层逻辑全部聚合在 hook,工具不污染)——
  - `ResponseCacheHook`(priority 0) — 用 `DimensionStateManager` 抽问题维度,以 `tenantBucket | intent | dimensionKey` 为 key 在 MySQL 里命中复用,命中时**短路** LLM 直接把缓存 Event 流回客户端。**实测 cache hit 从 33s 降到 1s**。
  - `ArtifactHandoffHook`(priority 12)★ — **P3 协议**:`PostActingEvent` 阶段透明识别工具返回的 markdown 表格 / JSON 数组,自动落 CSV 到 `workspace/artifacts/<userId>/<taskId>/<tool>-<uuid>.csv`,把原结果替换成带 `pd.read_csv(...)` 的简短 handoff 消息。**工具方零改动**就能接入。
  - `ArtifactAccessHook`(priority 8)★ — `PreActingEvent` 阶段拦截 `read_file` / `write_file` / `shell_execute` / `python_exec`,artifact 树内非本 task 桶的路径改写成必败 sentinel。详见 §5。
  - `PythonExecRetryHook` — 沙箱内 `python_exec` 报 `ModuleNotFoundError` / `Timeout` 等可恢复异常时自动 `pip install` / 二次执行,把容器内的小坑挡掉,subagent 看到的就是干净的结果。
  - `SkillRetrievalHook` / `SkillSynthesisHook` / `SkillEvolutionHook` — 见上节。
- **Memory 三件套**(本地文件 + MySQL 镜像 + 容器内回灌)——
  - `MysqlMemoryStore` — `agent_memory` + `agent_memory_ledger` 两张表自动建表,以 `(user_id, kind, key_name)` 上锁,`upsert` / `appendLedgerLine` / `tailLedger`
  - `MemoryFileWatcher` — 5s 轮询 `<workspace>/memory/<userId>/`,把 `MEMORY.md` / `*.jsonl` 增量同步到 DB,容器内子智能体的写入也能进 DB
  - `MemoryHydrator` — `SupervisorService.build()` 之前把 DB 里的 MEMORY.md 回灌到本地文件,让 harness 的 `WorkspaceContextHook` 不感知 DB 链路
- **★ 夜间记忆咀嚼**(4 阶段管道,`@Scheduled cron 21:09`)——
  - Phase 1 `CleanLedger`:清理 `agent_memory_ledger` 超 90 天的行
  - Phase 2 `MineTraces`:从 `episodic_memory` 挖掘 L1 工具调用 + 读子智能体 `memory_messages.jsonl` 提取 L2 工具链,合并 fingerprint 写入 `user_trace_summary`
  - Phase 3 `EvolveSkills`:对高失败率工具链(>0.3)触发 skill 蒸馏/演化
  - Phase 4 `ConsolidateMemory`:成功流程 LLM 归并到用户 MEMORY.md
  - 跨 JVM 互斥:MySQL `GET_LOCK`,按 userId 独立 try-catch
- **持久层**(`Session` 接口 MySQL 直挂)——
  `MySQLSession` 实现框架 `Session` 接口(单表 `session_state_list`,list 状态增量 append);`EpisodicLongTermMemoryAdapter` 把 `MySqlEpisodicMemory` 适配到 `LongTermMemory`,按 `userId` 分桶。
- **沙箱(可选 profile)+ 共享单容器**——
  默认 profile 为 `dev`(无沙箱)。叠加 `sandbox-windows` / `sandbox-linux` / `sandbox-linux-remote` 之一(见 §3.3)即把 `FilesystemTool` / `ShellExecuteTool` / `PythonExecTool` / `code_interpreter` 全部丢进 Docker 容器;三个 profile 都默认 `isolationScope: GLOBAL` + `sharedContainerName: agentscope-shared-demo` —— **整个 fleet 复用一个长寿命容器**,JVM 不创建/销毁容器,只 `docker exec`。详见 §3.4。
- **distributed 路径**(可选,Redis BaseStore 已落地,profile 自行装配)——
  `JedisBaseStore` 已实现 — 业务侧若要两副本可自配 profile;**artifacts 不走 Redis**,需 LB 按 userId 粘性或共享存储。
- **artifact 兜底 GC**(自愈)——
  `ArtifactSweeper` 每小时扫 `workspace/artifacts/`,清掉 ≥6h 没碰的 bucket。给 JVM crash / 客户端断开 / 第三方端点这些 `doFinally` 漏网的场景兜底。

---

## 2. 项目架构

### 2.1 模块结构

```
analysis-project/
├── pom.xml                                          # Java 17 + Spring Boot 3.2.3
│                                                    # 依赖 agentscope-harness 1.1.0-RC2
│                                                    #     + agentscope-a2a-spring-boot-starter
├── .gitignore                                       # 已忽略 .env / .agentscope/ / .idea
├── src/main/java/
│   ├── io/agentscope/                               # ★ 本地 patch — classpath 优先级覆盖 jar 内同名类
│   │   ├── core/ReActAgent.java
│   │   ├── core/memory/...                          #   EpisodicMemory / EpisodicResult / MemoryProvider
│   │   └── harness/agent/sandbox/impl/docker/
│   │       └── DockerSandboxClient.java             #   修两个反序列化 bug + 加共享容器分支(短路 docker run)
│   └── com/agentscopea2a/                            # 业务代码包名忽略即可
│       ├── AgentscopeA2aApplication.java            # Spring Boot bootstrap + @EnableScheduling
│       ├── service/
│       │   ├── SupervisorService.java               # ★ HarnessAgent 工厂 + 子智能体 Markdown 装配 + hook 链
│       │   ├── ChatStreamService.java               # 聊天流式接口
│       │   └── impl/ChatStreamServiceImpl.java
│       ├── controller/
│       │   ├── ChatController.java                  # POST /ai/chat — 直接 REST 入口(SSE 流)
│       │   └── DebugController.java                 # GET /debug/* — 看 workspace / memory / sessions / skills
│       ├── config/                                  # 数据源 / 多 DB
│       ├── agent/
│       │   ├── memory/                              # ★ MEMORY.md 文件 + MySQL 镜像三件套
│       │   │   ├── MysqlMemoryStore.java            #   agent_memory / agent_memory_ledger 自动建表 + CRUD
│       │   │   ├── MemoryFileWatcher.java           #   5s 轮询 <workspace>/memory/ 镜像到 DB
│       │   │   ├── MemoryHydrator.java              #   build() 前 DB → 文件回灌(missing/blank 才覆盖)
│       │   │   ├── MemoryFencing.java               #   多 session 写同 user 的本地文件锁
│       │   │   ├── MySqlEpisodicMemory.java         #   长期记忆 MySQL 实现(向量检索 + FTS + TOOL 提取)
│       │   │   ├── EpisodicMemoryConfig.java
│       │   │   └── EpisodicLongTermMemoryAdapter.java
│       │   ├── dimension/                           # 维度抽取(supervisor / dimension / 子代理可异)
│       │   ├── hook/SessionHook.java                # session 级 hook
│       │   ├── model/                               # ModelRegistry / ModelProperties / 多实例工厂
│       │   ├── session/MySQLSession.java            # Session 接口 MySQL 实现
│       │   └── tools/
│       │       ├── AgentTools.java                  # ★ 4 个 @Tool 业务查询方法(quality_query_by_*),
│       │       │                                    #   由 ToolRoutersIndex 反射扫到登记 toolId
│       │       └── routers/                         # HttpClient / MdTableParser(路由辅助类)
│       ├── harness/
│       │   ├── config/
│       │   │   ├── InfraConfig.java                 # HikariCP + ArtifactStore + Model + workspace.path
│       │   │   ├── PersistenceProperties.java       # harness.a2a.mysql.* (@ConfigurationProperties)
│       │   │   ├── SandboxProperties.java           # harness.a2a.sandbox/distributed/skills/memory.*
│       │   │   ├── FilesystemConfig.java            # 按 profile 选 Sandbox/Remote filesystem
│       │   │   │                                    #   + 启动期把 sharedContainerName 注入 DockerSandboxClient
│       │   │   ├── JedisBaseStore.java              # distributed profile 用的 Redis BaseStore
│       │   │   └── WorkspaceMaterializer.java       # 启动时把 classpath:workspace/** 拷到磁盘
│       │   ├── hooks/
│       │   │   ├── ResponseCacheHook.java           # priority 0 — Pre-/PostCall 缓存命中短路(MySQL)
│       │   │   ├── ArtifactAccessHook.java          # priority 8 ★ PreActing 拦截跨桶 read/write/shell/python_exec
│       │   │   ├── ArtifactHandoffHook.java         # priority 12 ★ PostActing 识别表格 → 落 CSV + 替换 result
│       │   │   ├── PythonExecRetryHook.java         # 沙箱内 python_exec 失败自动恢复(pip install / 重试)
│       │   │   ├── SkillRetrievalHook.java          # ★ PreReply embedding top-k SKILL.md 注入
│       │   │   ├── SkillSynthesisHook.java          # ★ PostReply 把成功流程蒸馏入 skill_candidate 表
│       │   │   └── SkillEvolutionHook.java          # ★ 反馈驱动 skill 再合成 / 降权
│       │   ├── skills/                              # ★ Skills 自演化整包
│       │   │   ├── SkillVectorIndex.java            #   内存向量库(60s 缓存 + 写穿透 + SQL fallback)
│       │   │   ├── EmbeddingClient.java + OpenAiCompatEmbeddingClient.java
│       │   │   ├── SkillDistiller.java              #   LLM 蒸馏 SkillCandidate → SKILL.md(重试+宽松解析)
│       │   │   ├── SkillCandidate.java + SkillCandidateRepository.java
│       │   │   ├── SkillIndexRepository.java        #   skill_index 表 CRUD + cross-JVM evolving 锁
│       │   │   ├── SkillSynthesisRunner.java        #   被 SynthesisHook + ResponseCacheHook 共享
│       │   │   └── SkillEvolutionRunner.java        #   演进调度 + Pending Judgement MySQL 持久化
│       │   ├── artifact/
│       │   │   ├── ArtifactStore.java               # ★ 委托给 ArtifactIo,(user, task) 双层分桶,cleanupTask GC
│       │   │   ├── ArtifactIo.java                  # ★ 物理 IO 接口(writeAtomic / deleteBucket)
│       │   │   ├── LocalArtifactIo.java             # 本地实现 — .tmp + ATOMIC_MOVE
│       │   │   ├── SshArtifactIo.java               # ★ SSH 远端实现 — 远端 Docker host 场景用
│       │   │   ├── ArtifactContext.java + ArtifactRef.java
│       │   │   ├── ArtifactSweeper.java             # @Scheduled 每小时兜底清过期 bucket(本地)
│       │   │   └── TabularExtractor.java            # markdown table / JSON array → TabularData
│       │   ├── tools/
│       │   │   ├── QualityTools.java                # 旧版 4 个直接查询 @Tool(保留向后兼容)
│       │   │   ├── DataPrimitivesTool.java          # ★ 5 个 pandas 原语 @Tool(aggregate/top_n/pivot/...)
│       │   │   ├── PythonExecTool.java              # ★ python_exec(code) — 一次远端往返,直接喂 python3 -
│       │   │   ├── ToolRoutersIndex.java            # ★ @PostConstruct 反射扫所有 @Tool 方法
│       │   │   │                                    #   暴露 router_tool + toolMetaInfo 元工具
│       │   │   ├── KnownEntities.java               # 已知实体白名单(部门/季度等)
│       │   │   └── SkillSaveTool.java               # @Tool save_skill — 落盘 SKILL.md
│       │   ├── cache/
│       │   │   └── ResponseCacheService.java        # MySQL response_cache 表的读写 + intent 分类
│       │   ├── runner/HarnessA2aRunner.java         # Harness 适配 + artifact GC + 缓存 HIT 记入 EpisodicMemory
│       │   └── workspace/                           # 远程 Docker daemon 工作区双向同步(skills / memory.remote=true 时启用)
│       │       ├── RemoteDirSyncer.java
│       │       └── RemoteWorkspaceSyncService.java
│       ├── mapper/                                  # MyBatis (多数据源)
│       ├── entity/ + dto/                           # 业务模型
└── src/main/resources/
    ├── application.properties                       # 端口 / AgentCard 三个 skill / 模型实例 / 默认 profile=dev
    ├── application-dev.properties                   # 开发库:MySQL/ClickHouse/GaussDB,端口 8081
    ├── application-prod.properties                  # 生产库,端口 8080
    ├── application-sandbox-windows.properties       # ★ 沙箱 profile — Windows JVM + 远程 Linux Docker
    ├── application-sandbox-linux.properties         # ★ 沙箱 profile — Linux JVM + 同机本地 Docker
    ├── application-sandbox-linux-remote.properties  # ★ 沙箱 profile — Linux JVM + 远程 Linux Docker
    ├── log4j2.xml
    ├── mybatis/                                     # MyBatis XML
    ├── docs/                                        # 文档目录
    │   ├── README.md                                # ← 本文件
    │   └── ssh.md                                   # 远端 Docker SSH 接通速查
    └── workspace/                                   # 启动时被 WorkspaceMaterializer 拷到 .agentscope/workspace/harness-a2a/
        ├── AGENTS.md                                # supervisor 人格(决策规则 + 硬规则 + 数据纪律)
        ├── knowledge/KNOWLEDGE.md                   # 领域知识 — 维度格式
        ├── skills/                                  # ★ 基线 skill(随项目装箱)+ 自学新 skill 落盘位置
        │   ├── tool_index/SKILL.md                  #   工具索引 — toolId ↔ 业务场景映射
        │   └── data_primitives/SKILL.md             #   pandas 5 原语的调用指南
        └── agent-subagents/                         # YAML front matter 声明 name / tools / maxIters
            ├── query_data.md                        # tools: tool_router
            ├── analyze_data.md                      # tools: tool_router
            ├── generate_skill.md                    # tools: skill_save
            └── code_interpreter.md                  # tools: python_exec (sandbox 模式额外注入 shell/read/write)
```

### 2.2 一次请求的执行链路

```
┌───────────────────────────────────────────────────────────────────────────┐
│  Client  ──POST /ai/chat (SSE) ──>  Spring Boot (port 8080)│
└───────────────────────────────────────────────────────────────────────────┘
                                  │
        ┌─────────────────────────┴─────────────────────────┐
        ▼                                                   ▼
  ChatController → ChatStreamService              HarnessA2aRunner (AgentRunner Bean)
  (直接 REST,SSE)                                  (Harness 调用,流式 Flux<Event>)
        │                                                   │
        └─────────────────────────┬─────────────────────────┘
                                  ▼
              SupervisorService.build(cacheHook, ctx)        ← 每次请求都新建 HarnessAgent
              ├─ MemoryHydrator.hydrate(userId)               ← DB → MEMORY.md 文件回灌(若缺/空)
              └─ 装载 hook 链 + 子智能体 spec(只暴露 tool_router / python_exec / skill_save 几把元工具)
                                  │
        ┌─────────────────────────┼──────────────────────────┐
        │                         │                          │
        ▼                         ▼                          ▼
   内置 hook                  业务 hook                    workspace
   (Workspace/Session/        ResponseCacheHook(0)         AGENTS.md → sysPrompt
    Memory/Compaction/        ArtifactAccessHook(8) ★      knowledge/  → 注入
    ToolResultEviction        ArtifactHandoffHook(12) ★    skills/tool_index + data_primitives → 注入
    /...)                     PythonExecRetryHook            agent-subagents/*.md → SubagentSpec
                              SkillRetrievalHook ★            ↓
                              SkillSynthesisHook ★           registerSubagentFromSpec
                              SkillEvolutionHook ★            (artifact ctx 钉到外层 task)
                                  │
                                  ▼
              agent.stream(msgs, ctx)  /  agent.call(msg, ctx)
                                  │
        ┌─────────────────────────┴───────────────────────────────────┐
        ▼ 缓存命中                                                    ▼ 缓存未命中
   CacheHitException 短路                                  ReAct 主循环:
   直接把 cached response 包成 Event                       reasoning → agent_spawn → 子智能体 ReAct
                                                          ├─ subagent 走两步式路由:
                                                          │  ① 查 tool_index / data_primitives skill 拿 toolId
                                                          │  ② toolMetaInfo(toolId) 取参数定义
                                                          │  ③ router_tool({"toolId":..., ...}) 执行
                                                          │  → 业务工具 (AgentTools / DataPrimitivesTool) 返回 markdown
                                                          │  → ArtifactHandoffHook 落 CSV,改写 result
                                                          │     成 "📦 ... /workspace/artifacts/...csv"
                                                          ├─ supervisor 派 code_interpreter (沙箱模式)
                                                          │  → spawn 消息里只带 csv path,不带数据
                                                          │  → python_exec(code) 一把,容器内 pandas 计算
                                                          │  → PythonExecRetryHook 兜底 ModuleNotFoundError
                                                          │  → ArtifactAccessHook 校验 read/write/shell/python_exec 路径
                                                          │       是否在当前 task 桶内
                                                          └─ PostReply:写缓存 + skill synthesis(蒸馏候选 skill)
                                  │
        ┌─────────────────────────┴─────────────────────────┐
        ▼                                                   ▼
   SessionPersistenceHook 自动保存                    HarnessA2aRunner.doFinally
   (session_state_list 表)                          ├─ active.remove(taskId)
                                  │                  └─ artifactStore.cleanupTask(ctx)
   MemoryFlushHook 写 workspace/memory/MEMORY.md         ↑ 异常路径也覆盖
   ↓                                                  ArtifactSweeper @ :17/h 兜底
   MemoryFileWatcher(5s 轮询)→ MysqlMemoryStore         (扫掉 doFinally 漏的)
                                  ▼
                                Client
```

### 2.3 关键设计选择

- **每请求一个 `HarnessAgent` 实例** — 重状态(对话历史、长期记忆、缓存)外置到 `MySQLSession` + `EpisodicMemory` + `ResponseCacheService`,builder 本身轻量,避免并发请求互踩。`HarnessA2aRunner.active` 用 `ConcurrentHashMap<taskId, HarnessAgent>` 临时托管,完成时 `doFinally` 移除。
- **子智能体 Markdown 驱动** — `SupervisorService.@PostConstruct` 把 `workspace/agent-subagents/*.md` 解析为 `SubagentDeclaration`,启动期 fail-fast 校验每个 `tools:` 声明的工具名;`WorkspaceMaterializer.ALWAYS_OVERWRITE_PREFIXES` 列出的目录(`agent-subagents/` / `AGENTS.md` / `knowledge/` / 部分基线 `skills/`)每次启动都从 jar 覆盖磁盘,保证 spec 与代码同版本。
- **★ 两步式元工具路由** — `ToolRoutersIndex.@PostConstruct` 反射扫所有标了 `@Tool` 的方法(`AgentTools` / `DataPrimitivesTool` / ...),建立 `toolId → (Bean, Method)` 表。子代理只持有 `router_tool` + `toolMetaInfo` 两把元工具,先查 `tool_index` / `data_primitives` 这两个 SKILL.md 找到 `toolId`,再走元工具调用。**新增业务工具完全不动子代理 prompt 也不改子代理 `tools:` 声明**。
- **共享单容器(GLOBAL scope)** — sandbox profile 默认 `isolationScope: GLOBAL` + `sharedContainerName: agentscope-shared-demo`。所有 user × session × subagent 复用同一个长寿命容器,**JVM 不 docker run 也不 docker stop**,只 `docker exec`。冷启动从 ~30s 降到 ~0,资源占用从 N×(M+1) 个容器降到 1。运维需要预先 `docker run -d --name agentscope-shared-demo ...` 一次。详见 §3.4。
- **多租户在共享容器里靠两层防护** — (a) `agent_memory` / `agent_memory_ledger` 表按 user_id 分行,文件只是中转;(b) `ArtifactAccessHook` 拦截 `read_file` / `write_file` / `shell_execute` / `python_exec` 跨桶引用。第三层 uid 隔离已评估但**暂未实施**。
- **LTM 按 user 分桶** — `SupervisorService.ltmBucketFor(ctx)` 优先用 `userId`,退回 `sessionId`,最后 `"anonymous"`。
- **缓存键加 tenant 前缀** — `ResponseCacheHook.scopedKey` = `tenantBucket | intent | dimensionKey`,两个用户问同一维度组合不会拿到彼此结果。
- **★ Memory + 技能双闭环自演化**(多位一体,越用越好用)——
  成功响应在 `SkillSynthesisHook` 阶段落 `skill_candidate`;累计阈值后 `SkillDistiller` 用 LLM 蒸馏(带重试 + 宽松解析);`SkillRetrievalHook` 用 L2 内存缓存(60s 定时刷新 + 写穿透)替代全表 SQL;命中时追加 `## 最近参考案例`;`SkillEvolutionRunner` 用 MySQL `evolving` 锁跨 JVM 互斥演进,`PendingJudgement` 持久化到 MySQL 表;EpisodicMemory 支持向量检索 + FTS fallback;缓存 HIT 路径记入 EpisodicMemory;元技能(`skills-builtin/`)与业务技能(`skills-auto/`)分层注入。**夜间记忆咀嚼** (`MemoryDigestionService`) 定时对每用户挖掘 L1+L2 完整工具调用链,高失败率触发技能进化,成功流程归并到 MEMORY.md。详见 [`docs/memory-skill-optimization-plan.md`](memory-skill-optimization-plan.md) 和 [`docs/nightly-memory-digestion-plan.md`](nightly-memory-digestion-plan.md)。
- **★ Artifact 路径分桶** — `workspace/artifacts/<userId>/<taskId>/<tool>-<uuid>.csv`。`ArtifactHandoffHook` 钉到 build 时的 outer ctx,subagent 即使被 framework 给新 `sessionId="sub-uuid"`,artifact 还是写到 outer task 桶里(否则 `code_interpreter` 读不到 `query_data` 写的 CSV)。
- **★ ArtifactIo 可插拔** — `ArtifactStore` 委托给 `ArtifactIo` 接口;默认 `LocalArtifactIo`,开 `harness.a2a.artifacts.remote.enabled=true` 切到 `SshArtifactIo`,写到**远端 Docker daemon 的宿主机**上(JVM 在笔记本、Docker 在 GPU 机的场景)。
- **★ MEMORY.md 双链路** — 容器内子智能体写文件 → `MemoryFileWatcher` 5s 轮询 mirror 进 `agent_memory`(`source='container'`);宿主 JVM 调 `MemoryHydrator.hydrate(userId)` 从 DB 反向回灌文件,让 harness 内置的 `WorkspaceContextHook` 不感知 DB。

---

## 3. 启动 & 使用

### 3.1 前置依赖

| 依赖 | 用途 | 何时可省 |
|---|---|---|
| **Java 17+** + Maven 3.8+ | 编译运行 | 不可省;Spring Boot 3.2.3 强制 ≥ 17 |
| **MySQL 5.7+ / 8.x / 9.x** | session 持久化 + response cache + 长期记忆 + memory 镜像 | 不可省;不可达时 fail-soft 但失去全部记忆/缓存 |
| **Anthropic / GLM 兼容 LLM API**(ARK / Volces / GLM 等) | 模型推理 | 不可省 |
| **Docker 24+** + `deepanalyze-vllm:latest` 镜像 | 仅 sandbox-* profile 需要 | 默认 `dev` profile 无沙箱,可省 |
| **预创建的共享容器** `agentscope-shared-demo` | sandbox-* profile 默认 GLOBAL 共享容器 | 把 `harness.a2a.sandbox.shared-container-name=` 留空回退到 GLOBAL scope JVM 自管容器 |
| **SSH 免密** 到 Docker 宿主 | `sandbox-windows` / `sandbox-linux-remote` profile | `sandbox-linux`(同机本地 Docker)可省 |
| **Redis 6+** | 仅 `distributed` profile | 不启分布式时可省 |

### 3.2 配置项

LLM / 模型实例 全部走 `application.properties` 里的 `harness.a2a.model.instances.*`(见该文件)。沙箱相关配置在 4 份 profile 里,环境变量主要还剩两个:

| 变量 | 默认 | 说明 |
|---|---|---|
| `DOCKER_HOST` | (空) | 远端 Docker 时 OS env 设 `ssh://user@host`(docker CLI 直接读) |
| `JASYPT_ENCRYPTOR_PASSWORD` | (空) | `application-prod.properties` 里 `ENC(...)` 加密项的解密 key |

> **MySQL 表会在首次访问时自动 `CREATE TABLE IF NOT EXISTS`**(`session_state_list` / `response_cache` / `QualitySupervisor_episodic_memory` / `agent_memory` / `agent_memory_ledger`)无需提前 DDL。

### 3.3 启动 — sandbox profile 选哪个

默认 `application.properties` 里 `spring.profiles.active=dev`,**不启沙箱**。要启沙箱叠加下面三个 profile 之一:

| profile | 拓扑 | 何时用 |
|---|---|---|
| `sandbox-windows` | Windows JVM + 远端 Linux Docker (ssh://) | 笔记本是 Windows、Docker 在 GPU 机器上 |
| `sandbox-linux` | Linux JVM + 同机本地 Docker (unix socket) | 一台 Linux 机器全包,简单可靠 |
| `sandbox-linux-remote` | Linux JVM + 远端 Linux Docker (ssh://) | Linux 笔记本、Docker 在另一台机器,memory 远程同步可开 |

激活方式(任选其一):
```bash
java -jar app.jar --spring.profiles.active=dev,sandbox-linux
# 或在 application.properties 里改 spring.profiles.active=dev,sandbox-linux
```

**敏感占位**:三份 profile 里的 `ssh-target` / `remote-root` / `shared-container-name` 都是 `XXXXX` 占位,使用前替换成真实地址。详见各 `.properties` 文件头部注释。

### 3.4 启动 — sandbox profile + 共享容器

```bash
# 一次性:在 Docker daemon 上预创建共享容器(JVM 不会自己创建)
# 关键两处:
#   --entrypoint /bin/sh    绕开 deepanalyze-vllm 镜像自带的 nvidia_entrypoint.sh —— 它在无 GPU
#                           的机器上会非零退出,导致即使 CMD 是 sleep 也活不过来
#   --restart unless-stopped 容器万一挂了(OOM / daemon 重启)dockerd 自动拉起,只有人工
#                           `docker stop` 才真正停;否则 Exit 后不会自愈
docker run -d --name agentscope-shared-demo \
  --restart unless-stopped \
  --entrypoint /bin/sh \
  -e TZ=Asia/Shanghai \
  -v /opt/agentscope-workspace/harness-a2a/skills:/workspace/skills \
  -v /opt/agentscope-workspace/harness-a2a/memory:/workspace/memory \
  -v /opt/agentscope-workspace/harness-a2a/artifacts:/workspace/artifacts \
  deepanalyze-vllm:latest -c 'while :; do sleep 3600; done'

# 构建 + 启动
mvn -B -DskipTests clean package
java -jar target/analysis-project-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev,sandbox-linux
```

启动健康的关键日志:

```
HikariCP DataSource initialised: url=jdbc:mysql://...:3306/<db>, pool max=20
agent_memory + agent_memory_ledger tables ensured
response_cache table ensured
ArtifactStore ready (REMOTE): ssh=docker-host
  remoteRoot=/opt/agentscope-workspace/harness-a2a/artifacts
[sandbox-docker] Attaching to shared container: name=agentscope-shared-demo id=...
MemoryFileWatcher polling .../memory every 5s
Loaded 4 Markdown subagent spec(s): [query_quality_data, generate_skill, analyze_data, code_interpreter]
Started AgentscopeA2aApplication in 5.7 seconds
```

### 3.5 远端 Docker(典型场景:笔记本无 Docker / GPU 在远程)

`SshArtifactIo` + `DOCKER_HOST=ssh://...` + 共享容器一起解决"Docker 在远端机"的部署。详细 SSH 接通步骤见同目录 [`ssh.md`](ssh.md);要点:

```bash
# 一次性:免密 SSH
ssh-copy-id root@<docker-host>
ssh -o BatchMode=yes root@<docker-host> 'echo ok'      # 必须返回 ok

# 一次性:远端建工作区
ssh root@<docker-host> 'mkdir -p /opt/agentscope-workspace/harness-a2a/{skills,memory,artifacts}'

# 一次性:远端 daemon 上预建共享容器(参数说明见 §3.4)
ssh root@<docker-host> \
  'docker run -d --name agentscope-shared-demo \
    --restart unless-stopped \
    --entrypoint /bin/sh \
    -e TZ=Asia/Shanghai \
    -v /opt/agentscope-workspace/harness-a2a/skills:/workspace/skills \
    -v /opt/agentscope-workspace/harness-a2a/memory:/workspace/memory \
    -v /opt/agentscope-workspace/harness-a2a/artifacts:/workspace/artifacts \
    deepanalyze-vllm:latest -c "while :; do sleep 3600; done"'

# 强烈建议:本机 ~/.ssh/config 加 ControlMaster 复用,把每次 ssh 握手从 ~300ms 降到 ~30ms
# Windows 注意:Git Bash 自带的 /usr/bin/ssh 才支持 ControlMaster,Windows 自带 OpenSSH 不行 ——
# `which ssh` 应返回 /usr/bin/ssh,否则把 /usr/bin 提到 PATH 最前
mkdir -p ~/.ssh && chmod 700 ~/.ssh
cat >> ~/.ssh/config <<'EOF'

Host docker-host
  HostName <ip>
  User root
  ControlMaster auto
  ControlPath /tmp/.ssh-mux-%C
  ControlPersist 10m
EOF
chmod 600 ~/.ssh/config

# 验证 master 起来了:第一次 ~300ms,第二次 ~30ms,socket 文件出现在 /tmp/.ssh-mux-*
ssh docker-host 'echo ok' && ssh -O check docker-host

# 启动:用 ssh://docker-host 别名而不是裸 IP —— 这样 docker client / SshArtifactIo / 手敲 ssh
# 三条路径全部命中同一个 ControlMaster,握手只发生一次,后续都是复用
export DOCKER_HOST=ssh://docker-host
java -jar target/analysis-project-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev,sandbox-linux-remote
```

### 3.6 关掉沙箱 / 切到独立容器(回退选项)

| 你想 | 操作 |
|---|---|
| 不要沙箱(直接打到宿主 shell,**只用于本地演示**) | 不叠加任何 sandbox profile,只保留 `--spring.profiles.active=dev` |
| 不要共享容器,每用户一容器(GLOBAL → USER) | 在 sandbox profile 里把 `harness.a2a.sandbox.isolation-scope=USER` + `harness.a2a.sandbox.subagent-isolation-scope=USER` + `harness.a2a.sandbox.shared-container-name=`(留空) |
| 关 memory 镜像(纯文件链路,单副本兼容) | `harness.a2a.memory.mysql-mirror.enabled=false` |

### 3.7 调用方式 — REST

`dev` profile 默认端口 **8080**(`prod` 8080,见 `application-*.properties`)。

```bash
# 简单查询（SSE 流式返回；userId 可选，传了之后同一 user 跨 session 也能命中缓存）
curl -sS --max-time 300 http://localhost:8080/ai/chat -H 'Content-Type: application/json' \
  --data-binary '{
    "agentName": "数字QA助手",
    "userId": "t4",
    "messages": [{"role": "user", "content": "查询2026年1季度杭州开发一部的质量数据"}]
  }'

# 数据分析（自动派 code_interpreter）
curl -sS --max-time 600 http://localhost:8080/ai/chat -H 'Content-Type: application/json' \
  --data-binary '{
    "agentName": "数字QA助手",
    "userId": "t4",
    "messages": [{"role": "user", "content": "对比2026年1季度和2026年2季度各部门的环比变化率"}]
  }'

# 保存 skill
curl -sS --max-time 300 http://localhost:8080/ai/chat -H 'Content-Type: application/json' \
  --data-binary '{
    "agentName": "数字QA助手",
    "userId": "t4",
    "messages": [{"role": "user", "content": "把刚才的查询流程保存为 quarter_compare 这个 skill"}]
  }'
```

> **Windows 注意**：必须用 `--data-binary` 而非 `-d`，否则 Git Bash 会把 UTF-8 转为 GBK 导致中文乱码。

### 3.8 A2A 协议(附属能力)

该服务也暴露了 A2A AgentCard(由 agentscope-a2a-spring-boot-starter 提供),位于 **`/.well-known/agent-card.json`**。AgentCard 内容由 `application.properties` 里 `agentscope.a2a.server.card.*` 定义(3 个 skill)。此协议主要用于与其他 A2A 兼容系统互操作,主调用方式仍为 §3.7 的 REST SSE。

### 3.9 观察 agent 产物 — `/debug/*`

```bash
curl http://localhost:8080/debug/workspace         # 概览:memoryCount / skillCount / agentsDir
curl http://localhost:8080/debug/memory            # MEMORY.md 预览 + 日历日志列表
curl http://localhost:8080/debug/skills            # 自动学到 + 用户主动保存的 skill
curl http://localhost:8080/debug/sessions          # 每个 agent 的 session 文件
```

### 3.10 监控 — Micrometer

`management.endpoints.web.exposure.include=health,info,metrics` 已开。

```bash
curl http://localhost:8080/actuator/metrics/harness.a2a.cache
curl 'http://localhost:8080/actuator/metrics/harness.a2a.cache?tag=outcome:hit'

curl http://localhost:8080/actuator/metrics/hikaricp.connections.active
```

---

## 4. 核心协议 — Artifact Handoff

### 4.1 问题

`analyze_data` 子智能体派 `query_quality_data` 拿到 24 行 × N 列的质量数据表,再派 `code_interpreter` 用 pandas 算均值/方差。**inline 走的话:整张表 ×2 次复制到 prompt → 烧 token + LLM 抄错数字**。

### 4.2 解决 — P3 协议

**工具完全不感知 artifact**(`QualityTools` 还是返回 markdown)。`ArtifactHandoffHook` 在 `PostActingEvent` 自动:

1. 调 `TabularExtractor.tryParse(text)` 识别表格(markdown table 或 JSON array of objects)
2. ≥4 数据行的表 → 写 CSV 到 `workspace/artifacts/<userId>/<taskId>/<tool>-<uuid>.csv`
3. 替换工具结果为带 `pd.read_csv(...)` 提示 + 5 行预览的简短消息

LLM 看见 handoff 消息就把 path 抄给 `code_interpreter`,后者 `pd.read_csv(path)` 拿到完整 DataFrame。

### 4.3 为什么这条对你重要

- **工具规模化** — 加任何返回 markdown 表或 JSON 数组的工具 = **0 改动接入 artifact**
- **多租户安全** — 路径分桶 + UUID + 原子写 + `ArtifactAccessHook` 三工具拦截 + task 结束即清
- **token 省** — 原 ~800 token/表 ×N 次跨 agent 复制 → ~80 token/handoff,**省 90%+**
- **正确性** — LLM 不再触碰数字,`DataGroundingHook` 验证小数全部通过

### 4.4 排除的工具

`read_file` / `write_file` / `shell_execute` / `python_exec` / `agent_spawn` / `agent_send` / `task_*` / `memory_*` / `session_search` / `save_skill` / `toolMetaInfo` 永远不被 artifact 化(避免循环 / 破坏语义)。完整列表见 `ArtifactHandoffHook.EXCLUDED_TOOLS`。

### 4.5 远端写后端(`SshArtifactIo`)

`ArtifactStore` 把写/删委托给 `ArtifactIo` 接口:

| 实现 | 用途 | 启用条件 |
|---|---|---|
| `LocalArtifactIo` | `.tmp + ATOMIC_MOVE` 写本地 | 默认 |
| `SshArtifactIo` | `ssh user@host 'mkdir -p && cat > tmp && mv -f tmp final'` | `harness.a2a.artifacts.remote.enabled=true`(sandbox profile 默认 ON) |

**关键路径关系**(必须匹配):

| 视角 | 路径 |
|---|---|
| 本机 JVM(`QualityTools` → `ArtifactStore`) | 不动本机磁盘,直接 SSH 上传 |
| 远端宿主 | `<artifacts.remote.remote-root>/<user>/<task>/qd-*.csv` |
| 远端 docker bind mount 左侧 | 同上 |
| 容器内 `pd.read_csv(...)` | `/workspace/artifacts/<user>/<task>/qd-*.csv` |

---

## 5. 多租户 / 多用户隔离

共享单容器(GLOBAL scope)下,所有用户共用一个容器进程空间。隔离靠**两层防护 + 一层数据层**:

### 5.1 数据层 — 路径 + DB 行隔离

| 资源 | 隔离方式 | 文件 |
|---|---|---|
| **artifacts** | `<root>/<userBucket>/<taskBucket>/<file>` 路径分桶,文件名带 UUID,task 结束 `cleanupTask` 删整桶 | `harness/artifact/ArtifactStore.java` |
| **MEMORY.md / 日 ledger** | MySQL `agent_memory` / `agent_memory_ledger` 表按 user_id 分行,文件只是中转通道 | `agent/memory/MysqlMemoryStore.java` |
| **session** | `session_state_list` 表按 (user_id, session_id) 主键 | `agent/session/MySQLSession.java` |
| **response cache** | key = `tenantBucket \| intent \| dimensionKey`,两个用户的同维度问题不互通 | `harness/cache/ResponseCacheService.java` |
| **长期记忆 LTM** | `EpisodicMemory` 按 userId 分桶 | `agent/memory/EpisodicLongTermMemoryAdapter.java` |

### 5.2 工具层 — `ArtifactAccessHook` 拦截四工具

`PreActingEvent` 阶段拦截下面四个工具,在 artifact 树内但跨桶 → 改写参数让工具失败:

| 工具 | 检查 | 违规处理 |
|---|---|---|
| `read_file` | `path` 在 artifacts 树内但不在自己桶 | 改写 `path` 为 `/__forbidden__/<u>/<t>` |
| `write_file` | 同上(防写穿) | 同上 |
| `shell_execute` | `command` 字符串里出现 `<artifactsRoot>...` 但不是自己桶;或 `working_directory` 跨桶 | 改写 `command` 为 `echo '... denied ...' >&2; exit 77` |
| `python_exec` | `code` 字符串里出现跨桶 artifact 路径(`pd.read_csv("...")` / `open("...")` 等) | 替换为抛出 `PermissionError` 的简短 Python |

详见 `harness/hooks/ArtifactAccessHook.java`。

### 5.3 已知缝隙 + 未来路径

工具层 hook 是 **substring 检查不解析 shell**,以下场景**不挡**:

- shell 字符串拼接:`cat $(echo /workspace/art''ifacts/bob/x)`
- Python `os.chdir("/workspace/artifacts"); listdir()`
- `/proc/<pid>/...`、`/tmp/*` 跨用户互探

真正的硬层隔离是**容器层 uid 隔离**(每用户固定 uid + 目录 chown 700)。已评估、设计就绪、**暂未实施**。启动信号:出现实际越权 / 合规要求 / 转外部 SaaS。

---

## 6. 怎么改 / 加东西

| 想做什么 | 改哪里 | 注意 |
|---|---|---|
| **加一个子智能体** | `src/main/resources/workspace/agent-subagents/<name>.md`,YAML 头声明 `name` / `tools` / `maxIters` | `tools:` 推荐就写 `tool_router`(再加 `python_exec` / `skill_save` 等少数特殊工具),业务工具走元工具路由,不要在 Java 端逐个登记 |
| **加一个新业务查询/计算工具** | 写一个 `@Tool` 方法,挂在已被扫描的 Bean 上(`AgentTools` / `DataPrimitivesTool` / 任何被 `ToolRoutersIndex` 收录的 Bean) | `ToolRoutersIndex.@PostConstruct` 反射扫到就自动暴露给 `router_tool`,**子代理无需任何改动**;再把 `toolId` 加到 `workspace/skills/tool_index/SKILL.md`(或 `data_primitives`)让 LLM 找得到 |
| **加一个非路由工具**(如 `save_skill` 这种特殊语义) | 在 `SupervisorService.buildToolRegistry()` 里 `r.put("tool_name", () -> new Xxx())`,然后在子代理 `tools:` 显式声明 | 启动期 fail-fast 校验工具名 |
| **改 supervisor 人格** | 编辑 `src/main/resources/workspace/AGENTS.md` | `WorkspaceMaterializer` 把它列在 `ALWAYS_OVERWRITE_PREFIXES`,每次启动都覆盖磁盘版本 |
| **加/改基线 SKILL.md** | 写到 `src/main/resources/workspace/skills/<name>/SKILL.md`,加到 `ALWAYS_OVERWRITE_PREFIXES` 让启动覆盖 | LLM 通过 `SkillRetrievalHook` embedding 检索命中后注入;自学的 skill 不放 `ALWAYS_OVERWRITE_PREFIXES`,免得被覆盖 |
| **关 / 调 Skills 自演化** | `harness.a2a.skills.synthesis.enabled=false` / `evolution.enabled=false` / `retrieval.enabled=false`(任选其一关闭一条链路) | 三条链路独立。关 retrieval 后业务技能不回退为全量加载(元技能 `skills-builtin/` 仍全量,业务 `skills-auto/` 不注入) |
| **改维度抽取规则** | 编辑 `dimension/DimensionStateManager.java` 等 | 同名类也在 jar 里,classpath 上本地源码优先 |
| **换 LLM provider** | 改 `InfraConfig.model()` Bean | 下游全部走 `Model` 接口 |
| **换 embedding provider** | 实现 `EmbeddingClient` 接口(`harness/skills/`),在配置里指向新实现 | 默认 `OpenAiCompatEmbeddingClient` 走 `/v1/embeddings` 协议,GLM / ARK / OpenAI 都兼容 |
| **换 artifact 后端** | 实现 `ArtifactIo`(`harness/artifact/ArtifactIo.java`),在 `InfraConfig.artifactStore()` 注入 | 接口只 3 个方法,新写 S3 / OSS / NFS 都简单 |
| **关 / 调 artifact** | `harness.a2a.artifacts.keep=true` 关闭 GC + sweeper(debug 用);`harness.a2a.artifacts.sweeper.max-age-hours` 改兜底清理阈值 | keep=true 不要在生产开,会无限积累 |
| **关共享容器** | `harness.a2a.sandbox.shared-container-name=`(空)+ `harness.a2a.sandbox.isolation-scope=USER` | 回退到每用户一容器,失去冷启动收益 |
| **关 memory 镜像** | `harness.a2a.memory.mysql-mirror.enabled=false` | 单副本兼容,但 GLOBAL scope 下 MEMORY.md 会跨用户串台 — **不要同时关镜像 + 开 GLOBAL** |

---

## 7. 已知限制 / 注意点

- **关 sandbox profile 后 `python_exec` / `code_interpreter` 落宿主 shell**(无沙箱)— 仅本地演示用。
- **MySQL fail-soft 静默副作用** — DB 不可达时服务不挂,但 `ResponseCacheService.put()` 报 WARN 却仍让 hook 看起来 "Response cached"(实际没写)。
- **`DataGroundingHook` 已下线** — 旧版的小数比对方案被替换成"LLM 不算数 → 由 `data_primitives` / `code_interpreter` 算"的硬规则(见 `AGENTS.md`「数据处理硬规则」)。如果业务仍想要末尾告警,可以挂回去,该类已删除请新写。
- **`SkillRetrievalHook` 与全量加载不再并存** — 已实现**分层注入**:元技能(`skills-builtin/`,2 个)由 HarnessAgent 内部 `FileSystemSkillRepository` 全量加载;业务技能(`skills-auto/`)只由 `SkillRetrievalHook` 按 L1/L2 命中注入。`WorkspaceContextHook` 不再扫到大量自动合成技能,详见 [`docs/memory-skill-optimization-plan.md`](memory-skill-optimization-plan.md) §5。
- **`SkillSynthesisRunner` 在 cache hit 路径也跑** — `ResponseCacheHook` 命中时不调 LLM 但仍传成功流程给 synthesis,以便缓存到的轨迹也能被沉淀。
- **distributed profile + artifact 不自动跨副本** — 需 LB 按 userId 粘性 / 共享存储 / 同 SSH 远端。
- **artifact 不加密** — 同主机不同租户 bind-mount 共享路径,假设是"主机内 trust"。
- **`SshArtifactIo` 必须密钥免密** — `BatchMode=yes` 写死,密码登录立即报错。
- **`ArtifactSweeper` 在 remote 模式下不扫盘** — 只靠 per-request `doFinally → cleanupTask` 经 SSH 清。客户端断线 / JVM crash 时残留要靠 cron 兜底。
- **`MemoryFileWatcher` 用 5s 轮询**,而不是 `WatchService`。原因是 docker bind mount 上 inotify 不可靠跨平台行为差 — 每文件 30ms 内能 mirror,够用。
- **共享单容器的 OOM 集中度**:所有用户共享内存上限,某用户 LLM 写 `[0]*10**9` 仍能 OOMkill 整个容器影响其他用户。容器层 cgroup 是单一 budget,要内存隔离需 per-user 容器或 cgroup-per-uid。
- **uid 隔离暂未实施** — `ArtifactAccessHook` 是软层防御,容器内进程通过 shell 拼接 / `os.chdir` 等手段仍能绕过。
- **A2A AgentCard skill id 仍叫 `query_quality_data`**(`application.properties` 里),与子代理改名 `query_data` 不同步;这是对外协议字段,改名要兼容老客户端,**当前保持 legacy id**。新子代理统一叫 `query_data`,详见 `agent-subagents/query_data.md`。

---

## 8. 端到端验证 checklist

```bash
# 0. 启动
mvn -B -DskipTests clean package
nohup java -jar target/analysis-project-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=dev,sandbox-linux > /tmp/app.log 2>&1 &

# 1. 简单查询(实测 ~42s cold)
curl -sS http://localhost:8080/ai/chat -H 'Content-Type: application/json' \
  --data-binary '{
    "agentName": "数字QA助手",
    "messages": [{"role": "user", "content": "查询2026年1季度所有部门的质量数据"}]
  }'

# 2. 同问题再发(实测 < 1s,cache hit)
curl -sS http://localhost:8080/ai/chat -H 'Content-Type: application/json' \
  --data-binary '{
    "agentName": "数字QA助手",
    "messages": [{"role": "user", "content": "查询2026年1季度所有部门的质量数据"}]
  }'

# 2b. 跨 session 复用(传 userId,session_id 换一个 — 仍然 cache hit)
curl -sS http://localhost:8080/ai/chat -H 'Content-Type: application/json' \
  --data-binary '{
    "agentName": "数字QA助手",
    "userId": "u-1024",
    "messages": [{"role": "user", "content": "查询2026年1季度所有部门的质量数据"}]
  }'

# 3. cache 指标
curl -sS http://localhost:8080/actuator/metrics/harness.a2a.cache
curl -sS 'http://localhost:8080/actuator/metrics/harness.a2a.cache?tag=outcome:hit'

# 4. 分析链路(实测 ~216s,触发 code_interpreter)
curl -sS --max-time 600 http://localhost:8080/ai/chat -H 'Content-Type: application/json' \
  --data-binary '{
    "agentName": "数字QA助手",
    "messages": [{"role": "user", "content": "对比2026年1季度和2026年2季度各部门的环比变化率"}]
  }'

# 5. memory 镜像
curl -sS http://localhost:8080/debug/memory | jq
mysql -e "SELECT user_id, key_name, version, updated_at FROM <db>.agent_memory ORDER BY updated_at DESC LIMIT 5"

# 6. 多租户隔离 — 两个 userId 互不串
curl -sS http://localhost:8080/ai/chat -H 'Content-Type: application/json' \
  --data-binary '{
    "agentName": "数字QA助手",
    "userId": "alice",
    "messages": [{"role": "user", "content": "查询2026年1季度所有部门的质量数据"}]
  }'
curl -sS http://localhost:8080/ai/chat -H 'Content-Type: application/json' \
  --data-binary '{
    "agentName": "数字QA助手",
    "userId": "bob",
    "messages": [{"role": "user", "content": "查询2026年1季度所有部门的质量数据"}]
  }'

# 7. artifact 应被 GC(任务结束清空) — 远端 Docker 场景下要 ssh 到 docker host
ssh root@<docker-host> 'find /opt/agentscope-workspace/harness-a2a/artifacts -type f' \
  | head    # 任务进行中可见,完成后秒删
```

期望:

- 所有请求 200 OK,中文回复
- 第 2 个请求秒回(cache hit 短路)
- 第 4 个请求里的所有变化率**都来自 `data_primitives` 路由(`data_compare_ratio` 等)或 `code_interpreter` 的 Python stdout**,日志里能看到 `router_tool called: toolId=...` 或 `python_exec` 输出
- alice / bob 两请求 `agent_memory` 表里两行 user_id 各自分行,不串
- artifact 目录任务完成后立即清空

---

## 9. 测试

```bash
mvn -B test
```

覆盖(单元测试,无外部依赖,~3 秒跑完):

| 测试类 | 关注点 |
|---|---|
| `ArtifactStoreTest` | UUID 防碰撞、并发多用户写、`cleanupTask` 只清目标桶、原子写无 `.tmp` 残留、路径 sanitize、IO 失败 fail-soft |
| `TabularExtractorTest` | markdown table 识别 + 阈值 / 不规则放过、JSON array 识别 + 同构性校验 / 异构放过、CSV escape、preview 截断 |
| `ArtifactHandoffHookTest` | 真 `QualityTools` 输出 → artifact 落盘 + handoff 消息正确、排除工具放行、small table 不 artifact 化、pinned ctx 覆盖 subagent ctx |
| `SkillSaveToolTest` | `save_skill` 落盘命名 / SKILL.md 内容拼装 |

`SshArtifactIo`、`SkillVectorIndex`、`SkillDistiller`、`ToolRoutersIndex` 反射扫描等链路不在单测覆盖(需要可达 SSH 端点 / Embedding 服务 / Spring 上下文,放在集成 / smoke 层验证)。

---

## 10. 文档导航

| 文件 | 内容 |
|---|---|
| `README.md` | 本文件 — 总览 + 架构 + 启动 + 验证 |
| [`ssh.md`](ssh.md) | 远端 Docker 场景的 SSH 接通速查(免密 / ControlMaster / DOCKER_HOST) |
| [`memory-skill-optimization-plan.md`](memory-skill-optimization-plan.md) | Memory + Skill 自进化管道 8 项优化设计 + 实施状态 |
| [`agentscope-rc1-to-rc2-migration.md`](agentscope-rc1-to-rc2-migration.md) | RC1 → RC2 升级要点 |
| [`code-interpreter-optimization.md`](code-interpreter-optimization.md) | `python_exec` 直跑、Schema 注入、`PythonExecRetryHook` 设计 |
| [`data-grounding-removal-changes.md`](data-grounding-removal-changes.md) | `DataGroundingHook` 下线背景 + 替代方案 |
| `skill-evolution-pr1..pr4-changes.md` / `skill-self-evolution-detail.md` | Skills 自演化 4 期 PR 演化路径 + 总体设计 |
| [`skill-evolution-pr4-design.md`](skill-evolution-pr4-design.md) | PR4(Evolution)详细设计文档 |
| [`subagent-load-fix-changes.md`](subagent-load-fix-changes.md) | subagent Markdown 解析 / fail-fast 修正 |
| [`stream-thinking-subagent-plan.md`](stream-thinking-subagent-plan.md) | 子代理 reasoning 流式透出方案 |
| [`user-id-cache-scope-plan.md`](user-id-cache-scope-plan.md) | 缓存 tenant 分桶方案 |
