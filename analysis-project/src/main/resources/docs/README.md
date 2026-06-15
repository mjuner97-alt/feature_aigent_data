# harness-example-a2a

> **Harness-native 重写**的 `agentscope-examples/a2a` 质量数据多智能体示例。把传统的"ReActAgent + 手写 hook"模式换成 `HarnessAgent` 的"workspace 文件驱动 + 子智能体工厂"模式;通过 A2A JSON-RPC + 普通 REST 双协议对外暴露;在 4 个 demo 工具之上演示了一整套生产级能力:**响应缓存、数据校验、CSV artifact 跨智能体握手(本地或远端 SSH)、多租户隔离、沙箱传播、分布式部署**。
>
> 当前工程坐标:`org.example:agent_init:1.0-SNAPSHOT`,依赖 `io.agentscope:*:1.1.0-RC1`,Java 21 + Spring Boot 4.0.4。源码已搬到 `com.agentscopea2a.harness.*` 包下,框架级的 `io.agentscope.core.dimension.*`(自写)同时复制到了 `com.agentscopea2a.harness.dimension` 以便就地迭代。
>
> 配套文档:
> - **架构/能力** ← 你在看这个
> - [enhancement-proposal.md](enhancement-proposal.md) — P0/P1/P2 增强清单(每条已附实现状态)
> - [sandbox-integration-plan.md](sandbox-integration-plan.md) — sandbox profile 的落地细节
> - [artifact-handoff-proposal.md](artifact-handoff-proposal.md) — **CSV artifact 协议设计** + P0/P1/P2/P3 演进
> - [artifact-multi-tenancy.md](artifact-multi-tenancy.md) — **多用户并发下 artifact 隔离的 5 类坑 + 修法**

---

## 1. 这个示例做什么

模拟一个"质量数据智能助手 — QualitySupervisor"。用户用自然语言提问,主管(supervisor)派单给四个领域子智能体,完成下面三类任务:

| 用户意图 | 派单链路 | 数据/工具 |
|---|---|---|
| **简单查询**(`查询2026年1季度杭州开发一部的质量数据`) | supervisor → `query_quality_data` | `QualityTools`(4 个维度组合查询函数) |
| **数据分析**(`对比 Q1 与 Q2 缺陷密度,算环比`) | supervisor → `analyze_data` → `query_quality_data`×N → `code_interpreter` | `QualityTools` + 沙箱 Python(pandas/numpy);**中间数据走 CSV artifact** |
| **保存技能**(`把刚才流程保存为 skill`) | supervisor → `generate_skill` → `save_skill` | `SkillSaveTool` 落盘 `workspace/skills/<name>/SKILL.md` |

底层值得关注的能力(每一项后面括号里是"为什么这条对你重要"):

- **workspace 文件驱动子智能体**(加 agent 不动 Java)——
  人格 + 工具清单都在 [src/main/resources/workspace/subagents/*.md](../src/main/resources/workspace/subagents/) 的 YAML front matter 里声明。加一个子智能体 = 新增一个 `.md`,前提是 `tools:` 里出现的名字已经在 [SupervisorService.buildToolRegistry()](../src/main/java/io/agentscope/examples/harness/a2a/service/SupervisorService.java#L327) 里注册。
- **HarnessAgent 内置 hook 全开**(免费拿一堆生产能力)——
  `WorkspaceContextHook`(注入 AGENTS.md / KNOWLEDGE / MEMORY)、`SessionPersistenceHook`(自动 load/save state)、`CompactionHook`(40 条压缩 / 12 条保留)、`ToolResultEvictionHook`(80000 字符以上工具结果自动落盘)、`MemoryFlushHook`、`SkillLearningHook` 等都是 builder 默认开。详见 [SupervisorService.build()](../src/main/java/io/agentscope/examples/harness/a2a/service/SupervisorService.java#L161)。
- **业务 hook 共 5 个**(业务层逻辑全部聚合在 hook,工具不污染)——
  - [ResponseCacheHook](../src/main/java/io/agentscope/examples/harness/a2a/hooks/ResponseCacheHook.java) — 用 `DimensionStateManager` 抽问题维度,以 `tenantBucket | intent | dimensionKey` 为 cache key 在 MySQL 里命中复用,命中时**短路** LLM 调用直接把缓存当 Event 流回客户端。**实测 cache hit 从 33s 降到 1s**。
  - [DataGroundingHook](../src/main/java/io/agentscope/examples/harness/a2a/hooks/DataGroundingHook.java) — 捕获每次工具结果里的实体名 + 小数,在 `PostCallEvent` 阶段对比 LLM 最终回复;缺失实体 / 编造数字 → 回复末尾打 `⚠️ 数据校验告警`。
  - [**ArtifactHandoffHook**](../src/main/java/io/agentscope/examples/harness/a2a/hooks/ArtifactHandoffHook.java) ★ — **P3 协议**:`PostActingEvent` 阶段透明识别工具返回的 markdown 表格 / JSON 数组,自动落 CSV 到 `workspace/artifacts/<userId>/<taskId>/<tool>-<uuid>.csv`,把原结果替换为带 `pd.read_csv(...)` 提示的简短 handoff 消息。**工具方零改动**就能接入。
  - [**ArtifactAccessHook**](../src/main/java/io/agentscope/examples/harness/a2a/hooks/ArtifactAccessHook.java) ★ — `PreActingEvent` 阶段拦截 `read_file`,artifact 树内非本 task 桶的路径改写成 `/__forbidden__/...` 阻止跨租户读。
- **持久层**(`Session` 接口的 MySQL 直挂)——
  [MySQLSession](../src/main/java/io/agentscope/examples/harness/a2a/session/MySQLSession.java) 实现框架 `Session` 接口(单表 `session_state_list`,list 状态增量 append);[EpisodicLongTermMemoryAdapter](../src/main/java/io/agentscope/examples/harness/a2a/memory/EpisodicLongTermMemoryAdapter.java) 把 `MySqlEpisodicMemory` 适配到 `LongTermMemory`,按 `userId` / `sessionId` 分桶。**别走自定义 component wrapper,Session 是框架第一公民。**
- **可选 sandbox / distributed**(两个 profile)——
  - `sandbox`:`FilesystemTool` / `ShellExecuteTool` / `code_interpreter` 全部进 Docker 容器跑;`workspace/skills/` / `workspace/memory/` / `workspace/artifacts/` 通过 bind mount 持久;可单独配 `subagentIsolationScope=USER` 让子智能体复用容器
  - `distributed`:两副本通过 Redis BaseStore 共享 workspace 字节(MEMORY/sessions/skills);**artifacts 不走 Redis,要 LB 按 userId 粘性**(见 §4.3)
- **artifact 兜底 GC**(自愈)——
  [ArtifactSweeper](../src/main/java/io/agentscope/examples/harness/a2a/artifact/ArtifactSweeper.java) 每小时扫描 `workspace/artifacts/`,清掉 ≥6h 没碰的 bucket。给 JVM crash / 客户端断开 / 第三方端点这些 `doFinally` 漏网的场景兜底。

---

## 2. 项目架构

### 2.1 模块结构

```
harness-example-a2a/
├── pom.xml                                          # Java 21 + Spring Boot 4.0.4
│                                                    # 依赖 agentscope-harness 1.1.0-RC1
│                                                    #     + agentscope-a2a-spring-boot-starter
│                                                    #     + agentscope-extensions-episodic-memory-mysql
├── .env                                             # ANTHROPIC_AUTH_TOKEN / HARNESS_A2A_DB_* (gitignore)
├── src/main/java/
│   ├── io/agentscope/core/dimension/                # ★ 自写、已合入官方 jar;同文件副本放在
│   │   ├── DimensionStateManager.java                #   com.agentscopea2a.harness.dimension 下,本地
│   │   ├── DimensionState.java                       #   优先级高于 jar — 改它就是改 demo 的行为
│   │   └── ...
│   └── org/example/
│       ├── docs/                                    # 文档目录
│       │   ├── README.md                            # ← 本文件
│       │   ├── enhancement-proposal.md
│       │   ├── sandbox-integration-plan.md
│       │   ├── artifact-handoff-proposal.md
│       │   └── artifact-multi-tenancy.md
│       └── harness/a2a/
│           ├── org.example.harness.HarnessA2aApplication.java           # Spring Boot bootstrap + @EnableScheduling
│           ├── service/
│           │   └── SupervisorService.java           # ★ HarnessAgent 工厂 + 子智能体 Markdown 装配
│           ├── runner/
│           │   └── HarnessA2aRunner.java            # AgentRunner Bean —— A2A 协议适配 + artifact GC
│           ├── controller/
│           │   ├── ChatController.java              # POST /chatA2A —— 直接 REST 入口 + artifact GC
│           │   └── DebugController.java             # GET /debug/* —— 看 workspace 产物
│           ├── config/
│           │   ├── InfraConfig.java                 # HikariCP + MySQLSession + ArtifactStore + Model
│           │   │                                    #   按 remote.enabled 选 Local/Ssh ArtifactIo
│           │   ├── ModelFactory.java                # 从 env / .env 构建 Anthropic 兼容 Model
│           │   ├── PersistenceProperties.java       # harness.a2a.mysql.* (@ConfigurationProperties)
│           │   ├── SandboxProperties.java           # harness.a2a.sandbox.* / .distributed.*
│           │   │                                    #   + Artifacts.Remote (sshTarget / remoteRoot)
│           │   ├── FilesystemConfig.java            # 按 profile 选 Sandbox/Remote filesystem
│           │   ├── JedisBaseStore.java              # distributed profile 用的 Redis BaseStore
│           │   └── WorkspaceMaterializer.java       # 启动时把 classpath:workspace/** 拷到磁盘
│           ├── dimension/                           # ★ 自写、跟 io.agentscope.core.dimension 同源
│           │   ├── DimensionStateManager.java       #   覆盖 jar 里的同名类(本工程内迭代用)
│           │   ├── QuestionAnalysis.java
│           │   └── ...
│           ├── hooks/
│           │   ├── ResponseCacheHook.java           # Pre-/PostCall 缓存命中短路(MySQL)
│           │   ├── DataGroundingHook.java           # 验证回复数字 vs 工具结果
│           │   ├── ArtifactHandoffHook.java         # ★ PostActing 识别表格 → 落 CSV + 替换 result
│           │   └── ArtifactAccessHook.java          # ★ PreActing 拦截跨 task 读
│           ├── artifact/
│           │   ├── ArtifactStore.java               # ★ 委托给 ArtifactIo,路径分桶,cleanupTask GC
│           │   ├── ArtifactIo.java                  # ★ 物理 IO 接口(writeAtomic / deleteBucket)
│           │   ├── LocalArtifactIo.java             # 本地实现 — .tmp + ATOMIC_MOVE
│           │   ├── SshArtifactIo.java               # ★ SSH 远端实现 — 远端 Docker host 场景用
│           │   ├── ArtifactContext.java             # (userBucket, taskBucket) 值对象 + sanitize
│           │   ├── ArtifactRef.java                 # save() 返回 handle(id / agentPath / preview)
│           │   ├── ArtifactSweeper.java             # @Scheduled 每小时兜底清过期 bucket(本地)
│           │   └── TabularExtractor.java            # markdown table / JSON array → TabularData
│           ├── tools/
│           │   ├── QualityTools.java                # 4 个 @Tool —— 模拟质量数据查询(零 artifact 感知)
│           │   ├── KnownEntities.java               # 部门/应用/人员实体表(供 grounding 用)
│           │   └── SkillSaveTool.java               # @Tool save_skill —— 落盘 SKILL.md
│           ├── cache/
│           │   └── ResponseCacheService.java        # MySQL response_cache 表的读写 + intent 分类
│           ├── session/
│           │   └── MySQLSession.java                # Session 接口 MySQL 实现
│           └── memory/
│               └── EpisodicLongTermMemoryAdapter.java   # EpisodicMemory → LongTermMemory
└── src/main/resources/
    ├── application.yml                              # 默认 profile —— 端口 8889 + AgentCard skills 声明
    ├── application-sandbox.yml                      # sandbox profile + subagentIsolationScope
    │                                                #   + harness.a2a.artifacts.remote.* (SSH 远端写)
    ├── application-distributed.yml                  # distributed profile + LB stickiness 警告
    ├── log4j2.xml
    └── workspace/                                   # 启动时被 WorkspaceMaterializer 拷到 .agentscope/workspace/harness-a2a/
        ├── AGENTS.md                                # supervisor 人格(决策规则 + 硬规则 + 数据纪律)
        ├── knowledge/KNOWLEDGE.md                   # 领域知识 —— 维度格式
        └── subagents/                               # YAML front matter 声明 name / tools / maxIters
            ├── query-quality-data.md                # tools: quality_tools
            ├── analyze-data.md                      # tools: quality_tools (派给 code_interpreter 算数)
            ├── generate-skill.md                    # tools: skill_save
            └── code-interpreter.md                  # 不声明 tools — harness 在 sandbox 模式自动注入
                                                    #   shell_execute / write_file / read_file
└── src/test/java/org/example/harness/a2a/           # 33 个单元测试,无外部依赖,跑过即代表协议守住
    ├── artifact/ArtifactStoreTest.java              # 11 测试 — UUID / 原子写 / 并发 / GC / 路径校验
    ├── artifact/TabularExtractorTest.java           # 14 测试 — markdown / JSON / CSV escape / 边界
    └── hooks/ArtifactHandoffHookTest.java           # 8 测试 — 用真 QualityTools 输出端到端验证
```

### 2.2 一次请求的执行链路

```
┌───────────────────────────────────────────────────────────────────────────┐
│  Client  ──POST /chatA2A or A2A JSON-RPC──>  Spring Boot (port 8889)      │
└───────────────────────────────────────────────────────────────────────────┘
                                  │
        ┌─────────────────────────┴─────────────────────────┐
        ▼                                                   ▼
  ChatController                                  HarnessA2aRunner (AgentRunner Bean)
  (直接 REST,Mono.block)                          (a2a starter 调用,流式 Flux<Event>)
        │                                                   │
        └─────────────────────────┬─────────────────────────┘
                                  ▼
              SupervisorService.build(cacheHook, ctx)        ← 每次请求都新建 HarnessAgent
                                  │
        ┌─────────────────────────┼──────────────────────────┐
        │                         │                          │
        ▼                         ▼                          ▼
   内置 hook                  业务 hook                    workspace
   (Workspace/Session/        ResponseCacheHook(0)         AGENTS.md → sysPrompt
    Memory/Compaction/        ArtifactHandoffHook(12) ★    knowledge/  → 注入
    SkillLearning/...)        ArtifactAccessHook(8) ★      subagents/*.md → SubagentSpec
                              DataGroundingHook(15)         ↓
                                                            registerSubagentFromSpec
                                                            (每个子 agent:工具 +
                                                             同样 4 个业务 hook,
                                                             artifact ctx 钉到外层 task)
                                  │
                                  ▼
              agent.stream(msgs, ctx)  /  agent.call(msg, ctx)
                                  │
        ┌─────────────────────────┴───────────────────────────────────┐
        ▼ 缓存命中                                                    ▼ 缓存未命中
   CacheHitException 短路                                  ReAct 主循环:
   直接把 cached response 包成 Event                       reasoning → agent_spawn → 子智能体 ReAct
                                                          ├─ subagent 调 query_quality_data
                                                          │  → tool 返回 markdown
                                                          │  → ArtifactHandoffHook 落 CSV,改写 result
                                                          │     成 "📦 ... /workspace/artifacts/...csv"
                                                          ├─ supervisor 派 code_interpreter
                                                          │  → spawn 消息里只带 csv path,不带数据
                                                          │  → code_interpreter pd.read_csv → 算
                                                          │     → ArtifactAccessHook 校验 read 路径
                                                          │       是否在当前 task 桶内
                                                          └─ PostCall:写缓存 + grounding check
                                  │
        ┌─────────────────────────┴─────────────────────────┐
        ▼                                                   ▼
   SessionPersistenceHook 自动保存                    HarnessA2aRunner.doFinally
   (session_state_list 表)                          ├─ active.remove(taskId)
                                  │                  └─ artifactStore.cleanupTask(ctx)
   MemoryFlushHook 写 workspace/MEMORY.md                ↑ 异常路径也覆盖
                                  ▼                  ArtifactSweeper @ :17/h 兜底
                                Client                  (扫掉 doFinally 漏的)
```

### 2.3 关键设计选择

- **每请求一个 `HarnessAgent` 实例** — 重状态(对话历史、长期记忆、缓存)外置到 `MySQLSession` + `EpisodicMemory` + `ResponseCacheService`,builder 本身轻量,避免并发请求互踩。`HarnessA2aRunner.active` 用 `ConcurrentHashMap<taskId, HarnessAgent>` 临时托管,完成时 `doFinally` 移除。
- **子智能体 Markdown 驱动** — `SupervisorService.@PostConstruct` 调用 `AgentSpecLoader.loadFromDirectory()` 把 `workspace/subagents/*.md` 解析为 `SubagentSpec`,启动期就 fail-fast 校验每个 `tools:` 声明的工具名在 `toolRegistry` 里存在;`disableWorkspaceSubagentAutoDiscovery()` 是因为我们自己用 `subagentFactory` 注册带工具实例的工厂,关闭框架的"只用 .md"二次注册避免重复。
- **沙箱传播 + 子智能体复用容器** — supervisor 启了 sandbox,子智能体默认共享同一个 `SandboxFilesystemSpec`(`SupervisorService.registerSubagentFromSpec` 里 `specForSubagent`),`code_interpreter` 的 `shell_execute` 真正落到容器里而不是"逃出来"打到宿主。这是 `code_interpreter` 子智能体**不在 `.md` 里声明 `tools:`** 的原因 — 框架检测到 `AbstractSandboxFilesystem` 时自动注入 `ShellExecuteTool` / `FilesystemTool`。子智能体可选 `subagentIsolationScope=USER` 让所有子智能体调用按用户复用一个容器(从 N×(M+1) 个容器降到 ~N 个,详见 [artifact-multi-tenancy.md §6.1](artifact-multi-tenancy.md))。
- **LTM 按 user 分桶** — `SupervisorService.ltmBucketFor(ctx)` 优先用 `userId`,退回 `sessionId`,最后 `"anonymous"`。修掉了旧版"所有请求落同一个桶"的污染。
- **缓存键加 tenant 前缀** — `ResponseCacheHook.scopedKey` = `tenantBucket | intent | dimensionKey`,两个用户问同一个维度组合不会拿到彼此的结果。
- **★ Artifact 路径分桶** — `workspace/artifacts/<userId>/<taskId>/<tool>-<uuid>.csv`,UUID 防同毫秒碰撞;`.tmp` + `ATOMIC_MOVE` 防半截读;`ArtifactAccessHook` 拦截跨 task 读。每个 ArtifactHandoffHook 都钉到 build 时的 outer ctx,subagent 即使被 framework 给新 `sessionId="sub-uuid"`,artifact 还是写到 outer task 的桶里(否则 `code_interpreter` 读不到 `query_quality_data` 写的 CSV)。
- **★ ArtifactIo 可插拔后端** — `ArtifactStore` 委托给 `ArtifactIo` 接口;默认 `LocalArtifactIo`(本地 `.tmp + ATOMIC_MOVE`),开 `harness.a2a.artifacts.remote.enabled=true` 切到 `SshArtifactIo`(`ssh user@host 'mkdir -p && cat > tmp && mv -f tmp final'`),写到 **远端 Docker daemon 的宿主机** 上。这是给"JVM 在笔记本,Docker 在远端 GPU 机"场景设计的 — 见 §3.4 第二段。

---

## 3. 启动 & 使用

### 3.1 前置依赖

| 依赖 | 用途 | 何时可省 |
|---|---|---|
| **Java 21+** + Maven 3.8+ | 编译运行 | 不可省;Spring Boot 4.0.4 强制 ≥ 21 |
| **MySQL 5.7+ / 8.x / 9.x** | session 持久化 + response cache + 长期记忆 | 不可省;不可达时 fail-soft 但**失去全部记忆/缓存**(WARN 不挂进程) |
| **Anthropic 兼容 LLM API**(ARK / Volces / GLM 等) | 模型推理 | 不可省;`ModelFactory` 有兜底 key 但不保证可用 |
| **Docker 24+** + `deepanalyze-vllm:latest` 镜像 | 仅 `sandbox` profile | 不启 sandbox 时可省 |
| **SSH 免密 到 Docker 宿主** | 仅 `sandbox` profile + Docker 在远端时 | Docker 跟 JVM 同机可省 |
| **Redis 6+** | 仅 `distributed` profile | 不启分布式时可省 |

### 3.2 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `ANTHROPIC_API_KEY` (或 `ANTHROPIC_AUTH_TOKEN`) | (内置兜底) | LLM API key,优先级:env > `.env` > 硬编码 |
| `ANTHROPIC_BASE_URL` | `https://ark.cn-beijing.volces.com/api/coding` | Anthropic 兼容端点 |
| `ANTHROPIC_MODEL` | `glm-5.1` | 模型 id |
| `HARNESS_A2A_DB_HOST` / `_PORT` / `_NAME` / `_USER` / `_PASS` | `192.168.101.16` / `3306` / `agentscope` / `root` / *(空)* | MySQL 连接参数,**生产必须设密码** |
| `HARNESS_A2A_SANDBOX_IMAGE` | `deepanalyze-vllm:latest` | 仅 sandbox profile |
| `HARNESS_A2A_SUBAGENT_SCOPE` | *(空)* | 仅 sandbox profile;`USER` = 子智能体按用户复用容器 |
| `HARNESS_A2A_ARTIFACTS_REMOTE_ENABLED` | `false` | 仅 sandbox profile;`true` = CSV artifact 走 SSH 写远端 |
| `HARNESS_A2A_ARTIFACTS_SSH_TARGET` | `root@116.148.121.118` | 仅 remote artifact 模式;`user@host`,免密密钥已配 |
| `HARNESS_A2A_ARTIFACTS_REMOTE_ROOT` | `/opt/agentscope-workspace/harness-a2a/artifacts` | 仅 remote artifact 模式;远端绝对路径,跟 Docker bind-mount 左侧一致 |
| `DOCKER_HOST` | *(空)* | 远端 Docker 时设 `ssh://user@host`,让 harness 把容器创到远端 |
| `HARNESS_A2A_REDIS_HOST` / `_PORT` / `_PASS` | `127.0.0.1` / `6379` / *(空)* | 仅 distributed profile |

> 优先放工作目录 `.env`(`ModelFactory` 会读)或 `export`。**MySQL 表会在首次访问时自动 `CREATE TABLE IF NOT EXISTS`**(`session_state_list` / `response_cache` / `QualitySupervisor_episodic_memory`),无需提前 DDL。

### 3.3 启动 —— 默认模式

`.env` 放在工程根目录(`ModelFactory` 会自动读),内容示例:

```dotenv
ANTHROPIC_AUTH_TOKEN=ark-xxxxxxxx...
ANTHROPIC_BASE_URL=https://ark.cn-beijing.volces.com/api/coding
ANTHROPIC_MODEL=glm-5.1
HARNESS_A2A_DB_HOST=192.168.101.16
HARNESS_A2A_DB_PASS=your-mysql-password
```

构建 + 启动:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH=$JAVA_HOME/bin:$PATH

mvn -B -DskipTests clean package
java -jar target/agent_init-1.0-SNAPSHOT.jar
```

启动健康的关键日志(本工程实测 ~7s 完成):

```
HikariCP DataSource initialised: url=jdbc:mysql://192.168.101.16:3306/agentscope, pool max=10
response_cache table ensured
ArtifactStore ready (LOCAL): hostRoot=.../artifacts, agentMountPrefix=..., keepArtifacts=false
ArtifactSweeper enabled — scanning .../artifacts every hour, evicting >6h-old buckets
Loaded 4 Markdown subagent spec(s): [query_quality_data, generate_skill, analyze_data, code_interpreter]
Started org.example.harness.HarnessA2aApplication in 5.7 seconds
```

### 3.4 启动 —— sandbox profile(Docker 容器内执行)

**本机 Docker**:

```bash
docker images | grep deepanalyze-vllm     # 镜像必须本机已存在,启动不做 build
java -jar target/agent_init-1.0-SNAPSHOT.jar --spring.profiles.active=sandbox

# 子智能体复用一个容器(可选,N×(M+1) 容器降到 ~N):
HARNESS_A2A_SUBAGENT_SCOPE=USER \
java -jar target/agent_init-1.0-SNAPSHOT.jar --spring.profiles.active=sandbox
```

**Docker 在远端机(典型场景:笔记本无 Docker / GPU 在 116.148.121.118)** —— 这是工程里 `SshArtifactIo` + `DOCKER_HOST=ssh://...` 一起解决的问题:

```bash
# 一次性:免密 SSH(在远端追加本机公钥)
ssh-copy-id root@116.148.121.118
ssh -o BatchMode=yes root@116.148.121.118 'echo ok'      # 必须返回 ok 才能继续

# 一次性:远端建 artifact 目录,Docker bind mount 的左侧会指向这里
ssh root@116.148.121.118 'mkdir -p /opt/agentscope-workspace/harness-a2a/artifacts'

# 强烈建议:本机 ~/.ssh/config 加 ControlMaster 复用,把每次 ssh 握手从 ~300ms 降到 ~30ms
cat >> ~/.ssh/config <<'EOF'
Host docker-host
  HostName 116.148.121.118
  User root
  ControlMaster auto
  ControlPath /tmp/.ssh-mux-%C
  ControlPersist 10m
EOF

# 启动:DOCKER_HOST 让 harness 在远端起容器;REMOTE_* 让 CSV artifact 走 SSH 写远端
export DOCKER_HOST=ssh://root@116.148.121.118
export HARNESS_A2A_ARTIFACTS_REMOTE_ENABLED=true
export HARNESS_A2A_ARTIFACTS_SSH_TARGET=docker-host
export HARNESS_A2A_ARTIFACTS_REMOTE_ROOT=/opt/agentscope-workspace/harness-a2a/artifacts
java -jar target/agent_init-1.0-SNAPSHOT.jar --spring.profiles.active=sandbox
```

启动日志会变成:

```
ArtifactStore ready (REMOTE): ssh=docker-host
  remoteRoot=/opt/agentscope-workspace/harness-a2a/artifacts
  agentMountPrefix=/workspace/artifacts keepArtifacts=false
ArtifactSweeper passive — remote backend in use; per-request cleanupTask handles GC over SSH.
```

激活后:`code_interpreter.shell_execute` / `SkillSaveTool` / 文件读写都进**远端**容器跑;`QualityTools` 产出的 CSV 直接 SSH 上传到远端宿主 `<remoteRoot>/<user>/<task>/`,容器内 `pd.read_csv("/workspace/artifacts/...")` 直接读到。详见 [sandbox-integration-plan.md](sandbox-integration-plan.md)。

> ⚠️ **SSH 必须密钥免密**。`SshArtifactIo` 用 `-o BatchMode=yes` 跑 ssh,密码登录会直接失败而不是卡住 reactor 线程。这是有意设计 — 生产环境不允许 ssh 进程挂在密码 prompt 上。

### 3.5 启动 —— distributed profile(两副本)

```bash
redis-cli ping                                          # 必须有 Redis

# 副本 1
java -jar target/agent_init-1.0-SNAPSHOT.jar --spring.profiles.active=distributed
# 副本 2(另一终端)
java -jar target/agent_init-1.0-SNAPSHOT.jar --spring.profiles.active=distributed \
     --server.port=8890
```

激活后 `MEMORY.md` / `memory/` / `sessions/` / `skills/` 通过 `JedisBaseStore` 路由 Redis,两副本看同一字节。可叠加 `sandbox,distributed`;此时 `SandboxDistributedOptions.requireDistributed=true`,`RedisSandboxExecutionGuard` 防两副本抢同沙箱 slot。

**⚠️ artifact 不走 Redis**(本地磁盘 bind mount 或 SSH 远端),两副本互相看不见。LB 必须按 userId 粘性(`nginx hash $http_x_user_id consistent;`),或挂共享存储(NFS/EFS/S3-FUSE),或把所有副本的 `HARNESS_A2A_ARTIFACTS_SSH_TARGET` 指向同一远端宿主。详见 [artifact-multi-tenancy.md §6.3](artifact-multi-tenancy.md)。

### 3.6 调用方式 — REST

```bash
# 简单查询
curl -X POST http://localhost:8889/chatA2A -H 'Content-Type: application/json' \
  -d '{"message":"查询2026年1季度杭州开发一部的质量数据","session_id":"demo-001"}'

# 数据分析(会自动派 code_interpreter)
curl -X POST http://localhost:8889/chatA2A -H 'Content-Type: application/json' \
  -d '{"message":"对比2026年1季度和2026年2季度各部门的环比变化率","session_id":"demo-002"}'

# 保存为 skill
curl -X POST http://localhost:8889/chatA2A -H 'Content-Type: application/json' \
  -d '{"message":"把刚才的查询流程保存为 quarter_compare 这个 skill","session_id":"demo-002"}'
```

返回 `{"reply": "..."}`。

### 3.7 调用方式 — A2A 协议

a2a starter 自动暴露 **`AgentCard`(`/.well-known/agent-card.json`)+ A2A JSON-RPC controller**(具体路径由 starter 决定,1.1.0-RC1 实测在 `/.well-known/agent-card.json`,**不是**旧 spec 的 `/.well-known/agent.json`)。`AgentCard` 内容由 `application.yml` 里 `agentscope.a2a.server.card.*` 定义(3 个 skill)。客户端按 A2A spec 调用。

### 3.8 观察 agent 产物 — `/debug/*`

```bash
curl http://localhost:8889/debug/workspace         # 概览:memoryCount / skillCount / agentsDir
curl http://localhost:8889/debug/memory            # MEMORY.md 预览 + 日历日志列表
curl http://localhost:8889/debug/skills            # 自动学到 + 用户主动保存的 skill
curl http://localhost:8889/debug/sessions          # 每个 agent 的 session 文件
```

[DebugController](../../java/org/example/harness/a2a/controller/DebugController.java) 只读、默认不鉴权 — **生产环境请用 profile 守护**。

### 3.9 监控 — Micrometer

`management.endpoints.web.exposure.include=health,info,metrics` 已开。

```bash
# Cache 命中 / 未命中 / 写入 / 错误
curl http://localhost:8889/actuator/metrics/harness.a2a.cache
curl 'http://localhost:8889/actuator/metrics/harness.a2a.cache?tag=outcome:hit'

# HikariCP 连接池
curl http://localhost:8889/actuator/metrics/hikaricp.connections.active
curl http://localhost:8889/actuator/metrics/hikaricp.connections
```

---

## 4. 核心协议 — Artifact Handoff

**这是本示例最值得学习的一块**(详见 [artifact-handoff-proposal.md](artifact-handoff-proposal.md))。

### 4.1 问题

`analyze_data` 子智能体派 `query_quality_data` 拿到 24 行 × N 列的质量数据表,再派 `code_interpreter` 用 pandas 算均值/方差。**当前如果走 inline:整张表 ×2 次复制到 prompt 里 → 烧 token + LLM 抄错数字**。

### 4.2 解决 — P3 协议

**工具完全不感知 artifact**(`QualityTools` 还是返回 markdown)。`ArtifactHandoffHook` 在 `PostActingEvent` 自动:

1. 调 `TabularExtractor.tryParse(text)` 识别表格(markdown table 或 JSON array of objects)
2. ≥4 数据行的表 → 写 CSV 到 `workspace/artifacts/<userId>/<taskId>/<tool>-<uuid>.csv`
3. 替换工具结果为:

```
query_quality_by_department_quarter 查询完成 — 共 5 行,列: 季度、部门、质量分(缺陷密度)

前 5 行预览:
| 季度 | 部门 | 质量分(缺陷密度) |
|--|--|--|
| 2026年1季度 | 杭州开发一部 | 23.1 |
| ... |

📦 完整数据已保存为 CSV artifact:
  路径: /workspace/artifacts/alice/demo-002/qd-7a2f...csv

▶ 后续派给 code_interpreter 时,在 Python 中读取:
  import pandas as pd
  df = pd.read_csv("/workspace/artifacts/alice/demo-002/qd-7a2f...csv")

🚨 严禁: 派单 code_interpreter 时不要把上面的预览表格复制进 task 字符串,
   只传 csv 路径 + 计算需求即可 — 完整数据在 csv 里。
```

LLM 看见这段就只把 path 抄给 `code_interpreter`,后者 `pd.read_csv(path)` 拿到完整 DataFrame。

### 4.3 为什么这条对你重要

- **工具规模化** — 加任何返回 markdown 表或 JSON 数组的工具 = **0 改动接入 artifact**。80+ 生产工具不需要懂 `ArtifactStore` / `RuntimeContext`。
- **多租户安全** — 路径分桶 + UUID + 原子写 + `ArtifactAccessHook` 拦截跨 task 读 + task 结束即清。**实测 8 用户 × 50 并发 0 碰撞**。
- **token 省** — 原 ~800 token/表 ×N 次跨 agent 复制 → ~80 token/handoff,**省 90%+**。
- **正确性** — LLM 不再触碰数字,`DataGroundingHook` 验证 36 个小数全部通过。

### 4.4 排除的工具

`read_file` / `write_file` / `shell_execute` / `agent_spawn` / `agent_send` / `task_*` / `memory_*` / `session_search` / `save_skill` 永远不被 artifact 化(会导致循环 / 破坏语义)。完整列表见 [ArtifactHandoffHook.EXCLUDED_TOOLS](../../java/org/example/harness/a2a/hooks/ArtifactHandoffHook.java)。

### 4.5 远端写后端(`SshArtifactIo`)

`ArtifactStore` 不直接动磁盘,而是把写 / 删委托给 `ArtifactIo` 接口:

| 实现 | 用途 | 何时启用 |
|---|---|---|
| `LocalArtifactIo` | `.tmp + ATOMIC_MOVE` 写本地 | 默认 |
| `SshArtifactIo` | `ssh user@host 'mkdir -p && cat > tmp && mv -f tmp final'` 写远端 | `harness.a2a.artifacts.remote.enabled=true` |

**为什么需要 SSH 后端**:开 sandbox profile 后,harness 用 `docker -v <host_path>:/workspace/artifacts ...` 跑容器。如果 `DOCKER_HOST=ssh://remote`,bind mount 左侧的 `<host_path>` 在**远端**解析。本机 JVM 把 CSV 落到本机磁盘 → 容器里看见空目录 → `pd.read_csv` 失败。`SshArtifactIo` 把 CSV 字节直接推到远端,bind mount 立刻可见。

**关键路径关系**(必须匹配):

| 视角 | 路径 |
|---|---|
| 本机 JVM(`QualityTools` → `ArtifactStore`) | 不动本机磁盘,直接 SSH 上传 |
| 远端宿主 | `<HARNESS_A2A_ARTIFACTS_REMOTE_ROOT>/<user>/<task>/qd-*.csv` |
| 远端 docker bind mount 左侧 | 同上 |
| 容器内 `pd.read_csv(...)` | `/workspace/artifacts/<user>/<task>/qd-*.csv` |

详见 [sandbox-integration-plan.md](sandbox-integration-plan.md)。

---

## 5. 怎么改 / 加东西

| 想做什么 | 改哪里 | 注意 |
|---|---|---|
| **加一个子智能体** | 新增 `src/main/resources/workspace/subagents/<name>.md`,YAML 头声明 `name` / `tools` / `maxIters` | `tools:` 里出现的名字必须在 [SupervisorService.buildToolRegistry()](../../java/org/example/harness/a2a/service/SupervisorService.java) 里能查到,否则启动直接抛 `IllegalStateException` |
| **加一个新工具** | 写一个带 `@Tool` 的类,在 `buildToolRegistry()` 里 `r.put("tool_name", () -> new Xxx())` | 如果工具返回 markdown table 或 JSON 数组,**ArtifactHandoffHook 自动接管**,工具方零额外代码 |
| **改 supervisor 人格** | 编辑 `src/main/resources/workspace/AGENTS.md` | `WorkspaceMaterializer` 把它列在 `ALWAYS_OVERWRITE_PREFIXES`,每次启动都覆盖磁盘版本 — **改源码而不是磁盘** |
| **改维度抽取规则** | 编辑 [dimension/DimensionStateManager.java](../../java/org/example/harness/a2a/dimension/DimensionStateManager.java)(本工程内副本) | 同名类也在 `agentscope-core-1.1.0-RC1.jar` 里,classpath 上本地源码优先;`mvn clean compile` 后即生效 |
| **换 LLM provider** | 改 [InfraConfig.model()](../../java/org/example/harness/a2a/config/InfraConfig.java) Bean 返回别的 `Model`(如 `DashScopeChatModel.builder()...`) | 下游全部走 `Model` 接口,无需其它改动 |
| **换 session 后端** | 在 [InfraConfig](../../java/org/example/harness/a2a/config/InfraConfig.java) 里把 `mysqlSession` Bean 换成自己的 `Session` 实现 | 注意 framework 用的是 `Session` 接口,不要做自定义包装 |
| **换 artifact 后端** | 实现 [ArtifactIo](../harness/a2a/artifact/ArtifactIo.java) 接口(参考 `LocalArtifactIo` / `SshArtifactIo`),在 `InfraConfig.artifactStore()` 注入 | 接口只 3 个方法,新写 S3 / OSS / NFS 后端都简单 |
| **调缓存策略 / 关掉缓存** | 删/不传 `ResponseCacheHook`,或改 `application.yml` 维度匹配规则 | `CacheHitException` 是有意控制流,不要当 error 处理 |
| **调上下文压缩阈值** | `application.yml` 里 `harness.a2a.compaction.trigger`(默认 40 条)/`.keep`(默认 12 条)/`harness.a2a.tool-eviction.max-chars`(默认 80000) | 大幅调小用于本地验证;生产保持默认 |
| **关 / 调 artifact** | `harness.a2a.artifacts.keep=true` 关闭 GC + sweeper(debug 时用);`harness.a2a.artifacts.sweeper.max-age-hours` 改兜底清理阈值 | keep=true 时**不要在生产开**,会无限积累 |
| **子智能体复用容器** | `harness.a2a.sandbox.subagentIsolationScope=USER` | 要求 userId 非空;子智能体之间互写有冲突文件时不能开(本 demo 用 `/workspace/run.py`,跨 task 会互覆) |
| **CSV 写到远端 Docker host** | `harness.a2a.artifacts.remote.enabled=true` + 配 `sshTarget` / `remoteRoot`(详见 §3.4) | SSH 必须密钥免密,推荐配 `~/.ssh/config` ControlMaster 复用 |

---

## 6. 已知限制 / 注意点

- **默认 profile 下 `code_interpreter` 的 `shell_execute` 落宿主 shell**(无沙箱)— 仅用于本地演示,生产环境必须叠加 `sandbox` profile。
- **MySQL fail-soft 静默副作用** — DB 不可达时服务不挂,但 `ResponseCacheService.put()` 报 WARN 却仍让 `ResponseCacheHook` log "Response cached"(实际没写)。**真 DB 环境无影响**;DB 故障期间监控指标和日志会有轻微误导。
- **`DataGroundingHook` 只校验小数**(`\d+\.\d+`),不校验整数(年份/计数/百分比误报多)。
- **distributed profile + artifact 不自动跨副本** — 需 LB 按 userId 粘性,或共享存储,或所有副本指同一 SSH 远端(详见 §3.5 / §4.5)。
- **artifact 不加密** — 同主机不同租户 bind-mount 共享路径,假设是"主机内 trust";严格 multi-tenant SaaS 需要再加一层 per-user 对称密钥(留给后续)。
- **`SshArtifactIo` 必须密钥免密** — `BatchMode=yes` 写死,密码登录会立即报错;按 §3.4 配 `ssh-copy-id`。
- **ArtifactSweeper 在 remote 模式下不扫盘** — 只靠 per-request `doFinally → cleanupTask` 经 SSH 清。客户端断线 / JVM crash 时,远端残留要靠 cron `find` 或后续给 sweeper 加远端模式(目前是 P2 缺口)。

---

## 7. 端到端验证 checklist

工程已实测两轮(默认 profile + 真 MySQL + 真 LLM),全部通过。复现命令:

```bash
# 0. 启动(默认 profile)
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH=$JAVA_HOME/bin:$PATH
mvn -B -DskipTests clean package
nohup java -jar target/agent_init-1.0-SNAPSHOT.jar > /tmp/app.log 2>&1 &
# 等 ~7s 看到 "Started org.example.harness.HarnessA2aApplication in 5.7 seconds"

# 1. 简单查询(实测 ~42s cold)
curl -sS http://localhost:8889/chatA2A -H 'Content-Type: application/json' \
  -d '{"message":"查询2026年1季度所有部门的质量数据","session_id":"v-1"}' | jq .reply

# 2. 同问题再发一次(实测 < 1s,cache hit)
curl -sS http://localhost:8889/chatA2A -H 'Content-Type: application/json' \
  -d '{"message":"查询2026年1季度所有部门的质量数据","session_id":"v-1"}' | jq .reply

# 3. 看 cache 指标(应 miss=1, write=1, hit=1)
curl -sS http://localhost:8889/actuator/metrics/harness.a2a.cache
curl -sS 'http://localhost:8889/actuator/metrics/harness.a2a.cache?tag=outcome:hit'

# 4. 分析链路(实测 ~216s,触发 code_interpreter)
curl -sS --max-time 600 http://localhost:8889/chatA2A -H 'Content-Type: application/json' \
  -d '{"message":"对比2026年1季度和2026年2季度各部门的环比变化率","session_id":"v-2"}' | jq .reply

# 5. 看 workspace 产物
curl -sS http://localhost:8889/debug/workspace | jq
curl -sS http://localhost:8889/debug/skills | jq

# 6. artifact 应被 GC(磁盘干净,只剩空 _anon/ 父目录)
find .agentscope/workspace/harness-a2a/artifacts -type f      # 期望 0 文件

# 7. 5 并发同问题压测(实测总耗时 1s,5 个回复字节一致)
for i in 1 2 3 4 5; do
  (curl -sS http://localhost:8889/chatA2A -H 'Content-Type: application/json' \
    -d '{"message":"查询2026年1季度所有部门的质量数据","session_id":"v-1"}' \
    -o /tmp/c$i.json) &
done; wait
md5sum /tmp/c[1-5].json | awk '{print $1}' | sort -u | wc -l   # 期望 1

# 8. 多租户隔离(alice 是新 tenant bucket,应 MISS;再问应 HIT)
curl -sS http://localhost:8889/chatA2A -H 'X-User-Id: alice' \
  -H 'Content-Type: application/json' \
  -d '{"message":"查询2026年1季度所有部门的质量数据","session_id":"alice-1"}' | jq .reply
```

期望:

- 所有请求 200 OK,中文回复
- 第 2 个请求秒回(cache hit 短路)
- 第 4 个请求回复里的所有变化率 **都来自 code_interpreter 的 Python stdout**,日志里有 `Data grounding check passed: N entities, M numbers all verified`,**reply 中无 hook 注入的 ⚠️ 数据校验告警**
- 磁盘上 artifact 目录在 task 完成后立即清空(`doFinally` 触发)
- 5 并发请求 `unique replies: 1`(缓存读路径线程安全)

---

## 8. 测试

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
mvn -B test
```

应输出 `Tests run: 33, Failures: 0, Errors: 0`,覆盖:

| 测试类 | 关注点 |
|---|---|
| `ArtifactStoreTest`(11) | UUID 防碰撞、并发多用户写、`cleanupTask` 只清目标桶、原子写无 `.tmp` 残留、路径 sanitize、IO 失败 fail-soft、5000 次连续写无重复 id |
| `TabularExtractorTest`(14) | markdown table 识别 + 阈值 / 不规则放过、JSON array 识别 + 同构性校验 / 异构放过、CSV escape、preview 截断 |
| `ArtifactHandoffHookTest`(8) | 真 `QualityTools` 输出 → artifact 落盘 + handoff 消息正确、`read_file` / `shell_execute` / `agent_spawn` 放行、small table 不 artifact 化、pinned ctx 覆盖 subagent ctx |

所有测试无外部依赖(无 DB / Docker / LLM / SSH),~3 秒跑完。`SshArtifactIo` 当前没纳入单元测试 — 它需要可达的 SSH 端点,正确的覆盖位置是集成测试 / smoke 脚本(`SshArtifactIo` 已通过 loopback `root@127.0.0.1` 验证过 write / atomic / describe / delete / 空参数 guard 全部 OK)。
