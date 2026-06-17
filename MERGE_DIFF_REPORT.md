# 当前项目 vs agentscope-java-v1.1.0-RC1 差异分析报告

> 生成时间：2026/06/16
> 当前项目：`D:\AILLMS\javacode\analysis-project\analysis-project` (com.agentscopea2a.* + io.agentscope.core.*)
> 新版本项目：`D:\AILLMS\javacode\agentscope-java-v1.1.0-RC1` (io.agentscope.harness.* + org.example.harness.a2a.*)
> 文件总数：当前 68 个 .java，新版 56 个 .java

---

## 一、整体结构差异

| 维度 | 当前项目 | 新版本项目 | 说明 |
|---|---|---|---|
| 主包名 | `com.agentscopea2a.*` | `org.example.harness.a2a.*` | 包结构基本一一对应 |
| 副包名 | `io.agentscope.core.*`（自维护）| `io.agentscope.harness.*`（新增能力） | 当前项目内嵌了核心库 ReActAgent；新版仅扩展 harness 层 |
| Spring Boot | 3.2.3 | **4.0.4** | 新版 SB4，需 JDK 21 |
| JDK | 17 | **21** | 升级 |
| 数据源 | 多源（MySQL+ClickHouse+Gauss）+ MyBatis + 业务 Tools | 仅 MySQL + 沙箱 + 远端工作区 | 当前项目业务工具更丰富 |
| 入口 | `AgentscopeA2aApplication` | `HarnessA2aApplication` | 新版入口包含 `@ConfigurationPropertiesScan` 等显式扫描 |
| ChatController | **流式 SSE**（ChatStreamServiceImpl）| 阻塞式 ResponseEntity | 当前项目流式实现是独有功能 |

---

## 二、需要"更新合并"的同名类（35 个）

下表中的类两边都存在；新版本通常有功能增强或修复，需要 cherry-pick 合并到当前项目（保留当前项目包名 `com.agentscopea2a.*`）。

| 类名 | 当前位置 | 新版位置 | 大小变化 | 关键变更摘要 | 合并优先级 |
|---|---|---|---|---|---|
| **InfraConfig** | harness/config | a2a/config | 4.4KB → 8.9KB | **新增 HikariCP DataSource Bean**（连接池替代 DriverManager 每请求新建连接，P2-1 改进）；artifactStore 兼容 sandbox/host 双模式 | ⭐⭐⭐ 高 |
| **SupervisorService** | service | a2a/service | 12.7KB → 23.9KB | 新增：①episodic memory 启动预热（消除首请求 13s）；②MemoryHydrator DB→file 同步；③SandboxFilesystemSpec / RemoteFilesystemSpec / SandboxDistributedOptions 注入；④subagent 工具工厂化；⑤共享 sharedEpisodicMemory 实例 | ⭐⭐⭐ 高 |
| **ResponseCacheService** | harness/cache | a2a/cache | 9.9KB → 12.2KB | 新增 `analyze` 类意图基于 SHA-256 question hash 的缓存键，避免分析问句撞键；analyze 关键词扩展 | ⭐⭐⭐ 高 |
| **ArtifactStore** | harness/artifact | a2a/artifact | 6.5KB → 9.5KB | **抽象出 `ArtifactIo` 后端**（本地/SSH 双实现），支持 sandbox 容器内 mountPrefix；新增 `agentPathRoot()` 暴露给 hooks | ⭐⭐⭐ 高 |
| **ArtifactAccessHook** | harness/hooks | a2a/hooks | 6.4KB → 13.3KB | **从仅拦 read_file 扩展到 read_file + write_file + shell_execute**，对子 agent shell 命令做跨租户路径子串过滤 | ⭐⭐⭐ 高 |
| **DimensionStateManager** | agent/dimension | a2a/dimension | 32.0KB → 32.5KB | 微调，主要是错误处理细节 | ⭐ 低 |
| **OpenAILlmDimensionService** | agent/dimension | a2a/dimension | 3.5KB → 4.4KB | 提示词/容错增强 | ⭐⭐ 中 |
| **PersistenceProperties** | harness/config | a2a/config | 3.7KB → 3.5KB | 默认值变化（host/db/pwd 改为本地默认）；JDBC URL 简化。**当前项目特化的"useSSL+timezone+allowPublicKeyRetrieval"参数应保留** | ⭐⭐ 中 |
| **MySqlEpisodicMemory** | agent/memory | a2a/memory | 16.7KB → 15.5KB | 简化（移除部分 fallback 路径），更依赖外部 DataSource 注入 | ⭐⭐ 中 |
| **EpisodicMemoryConfig** | agent/memory | a2a/config | 3.7KB → 3.5KB | 包路径迁移 + 字段微调 | ⭐ 低 |
| **EpisodicLongTermMemoryAdapter** | agent/memory | a2a/memory | 5.4KB → 5.3KB | 微调 | ⭐ 低 |
| **EpisodicMemory** (接口) | io/agentscope/core/memory | a2a/memory | 3.3KB → 1.9KB | **当前项目自维护、扩展过；新版回退到精简版**。建议**保留当前项目版本** | ⛔ 不合并 |
| **EpisodicResult** | io/agentscope/core/memory | a2a/memory | 1.2KB → 1.1KB | 同上 | ⛔ 不合并 |
| **MySQLSession** | agent/session | a2a/session | 15.0KB → 15.3KB | 微调 | ⭐ 低 |
| **ChatController** | controller | a2a/controller | 2.0KB → 6.3KB | **新版是阻塞 ResponseEntity 实现，当前项目是 SSE 流式**。**建议保留当前项目流式实现**，仅将其中字段命名/Cache hook 兼容改动同步进来 | ⚠️ 谨慎 |
| **DebugController** | controller | a2a/controller | 7.8KB → 7.9KB | 调试端点小调整 | ⭐ 低 |
| **HarnessA2aRunner** | harness/runner | a2a/runner | 6.6KB → 6.6KB | 仅包名差异 | ⭐ 低 |
| **ResponseCacheHook** | harness/hooks | a2a/hooks | 11.5KB → 11.6KB | 微调，与 cache key 改动配合 | ⭐⭐ 中 |
| **ArtifactHandoffHook** | harness/hooks | a2a/hooks | ≈ | 微调 | ⭐ 低 |
| **DataGroundingHook** | harness/hooks | a2a/hooks | ≈ | 微调 | ⭐ 低 |
| **WorkspaceMaterializer** | harness/config | a2a/config | ≈ | 微调 | ⭐ 低 |
| **TabularExtractor** | harness/artifact | a2a/artifact | ≈ | 微调 | ⭐ 低 |
| **KnownEntities** | harness/tools | a2a/tools | ≈ | 微调 | ⭐ 低 |
| **QualityTools** | harness/tools | a2a/tools | ≈ | 微调 | ⭐ 低 |
| **SkillSaveTool** | harness/tools | a2a/tools | ≈ | 微调 | ⭐ 低 |
| **ArtifactSweeper** | harness/artifact | a2a/artifact | 7.7KB → 8.5KB | 配合多后端 IO 增强 | ⭐⭐ 中 |
| **ModelFactory** | agent/model | a2a/config | 1.1KB → 4.3KB | 大幅扩展（含 Models.java 配套）。但新版用法与当前 ModelRegistry 体系**冲突**，需评估 | ⚠️ 评估 |
| **ArtifactContext / ArtifactRef** | harness/artifact | a2a/artifact | 几乎相同 | 仅包名 | ⭐ 低 |
| 其余 dimension 类（DimensionException/Prompts/State/QuestionAnalysis/LlmDimensionService）| agent/dimension | a2a/dimension | 几乎相同 | 仅包名 | ⭐ 低 |

---

## 三、新版本独有的"新增功能"（22 个文件）

需要决定是否引入到当前项目。按主题分组：

### 🔥 P1：Sandbox / 远程文件系统（沙箱化执行）— 新版本核心新功能
| 文件 | 大小 | 说明 |
|---|---|---|
| `io/agentscope/harness/agent/sandbox/WorkspaceMountSupport.java` | 4.7KB | 工作区挂载支持 |
| `io/agentscope/harness/agent/sandbox/impl/docker/DockerSandboxClient.java` | 11.8KB | Docker 沙箱客户端 |
| `io/agentscope/harness/agent/filesystem/sandbox/SandboxBackedFilesystem.java` | 13.1KB | 沙箱文件系统适配 |
| `org/example/harness/a2a/config/SandboxProperties.java` | 18.5KB | 沙箱配置（工作区根、镜像、网络等）|
| `org/example/harness/a2a/config/FilesystemConfig.java` | 15.3KB | 文件系统模式自动装配 |
| `org/example/harness/a2a/workspace/RemoteDirSyncer.java` | 17.6KB | 远程目录同步 |
| `org/example/harness/a2a/workspace/RemoteWorkspaceSyncService.java` | 7.3KB | 远程工作区同步服务 |
| `org/example/harness/a2a/artifact/ArtifactIo.java` (接口) | 3.0KB | Artifact IO 抽象 |
| `org/example/harness/a2a/artifact/LocalArtifactIo.java` | 3.9KB | 本地实现 |
| `org/example/harness/a2a/artifact/SshArtifactIo.java` | 9.3KB | SSH 远程实现 |

**用途**：把子 agent 的 `shell_execute / code_interpreter` 改为运行在 Docker 沙箱内或远程主机上（通过 SSH 同步）。当前项目是直接在 host 上跑。

### 🔥 P2：Memory 持久化与刷写
| 文件 | 大小 | 说明 |
|---|---|---|
| `io/agentscope/harness/agent/memory/MemoryFlushManager.java` | 14.3KB | 内存刷写管理器 |
| `io/agentscope/harness/agent/hook/MemoryFlushHook.java` | 4.5KB | 刷写钩子 |
| `io/agentscope/harness/agent/hook/MemoryMaintenanceHook.java` | 8.5KB | 维护钩子 |
| `org/example/harness/a2a/memory/MysqlMemoryStore.java` | 9.7KB | MySQL 内存镜像存储 |
| `org/example/harness/a2a/memory/MemoryFileWatcher.java` | 9.4KB | 文件监听写回 DB |
| `org/example/harness/a2a/memory/MemoryHydrator.java` | 3.6KB | DB → 文件同步（每请求开始时） |
| `org/example/harness/a2a/memory/NightlyMemoryFlushService.java` | 15.1KB | 定时刷写服务 |
| `org/example/harness/a2a/memory/MemoryFencing.java` | 2.3KB | 写锁/隔离 |

**用途**：把 `MEMORY.md` 工作区文件 ⇄ MySQL 双向同步，跨请求保留 agent 记忆。当前项目无此能力。

### P3：辅助工具
| 文件 | 大小 | 说明 |
|---|---|---|
| `io/agentscope/harness/agent/tool/AgentSpawnTool.java` | 14.0KB | 子 agent 派生工具（独立实现）|
| `org/example/harness/a2a/config/JedisBaseStore.java` | 6.0KB | Jedis 缓存基础（依赖 jedis）|
| `org/example/harness/a2a/config/Models.java` | 1.0KB | 模型常量 |
| `org/example/harness/a2a/HarnessA2aApplication.java` | 3.9KB | 新版入口 |

---

## 四、当前项目独有（新版本没有，**不要删除**）

按主题分组，这些是当前项目的"业务定制层"：

### 业务工具（13 个，agent/tools）
- `AgentTools` / `CalculationTool` / `CodeAgentTool` / `ComprehensiveTool` / `DetectRateTool` / `DownloadExcelTool` / `EchartsGenerateTool` / `GenerateMdTool` / `ManagerAgentDocTool` / `ManagerAgentRuleTool` / `ManagerAgentTool` / `ProblemTool`
- `routers/HttpClient` / `routers/MdTableParser`

### 多数据源（ClickHouse / GaussDB / MySQL）
- `config/datasource/ClickHouseConfig` / `GaussConfig` / `MySQLConfig`
- `mapper/ck/ClickHousePingMapper` / `mapper/gauss/GaussPingMapper` / `mapper/mysql/MysqlPingMapper`
- `entity/AiChatResult` / `ChatReqDto` / `PingResult`

### 模型注册体系（更完整）
- `agent/model/ModelBuilders` / `ModelProperties` / `ModelRegistry` / `ModelUtil`
- 新版本只有简单的 `ModelFactory + Models`，**当前项目的 ModelRegistry 更强**

### 流式聊天
- `dto/ChatRequest`
- `service/ChatStreamService` (接口)
- `service/impl/ChatStreamServiceImpl` (16.6KB SSE 实现)
- `agent/hook/SessionHook`

### 核心库内嵌
- `io/agentscope/core/ReActAgent` (78.5KB) — 当前项目自维护并定制了核心 ReAct 循环
- `io/agentscope/core/memory/MemoryProvider` (4.9KB)

---

## 五、配置文件差异

| 当前项目 (`src/main/resources`) | 新版本 |
|---|---|
| application.properties / -dev / -prod | application.yml / -distributed.yml / -sandbox.yml |
| log4j2.xml | log4j2.xml + log4j2.component.properties |
| docs/ | (无 — 无内置 docs)|
| mybatis/ | (无 MyBatis) |
| workspace/ | workspace/ (相同概念) |

新版本的 `application-sandbox.yml` 与 `application-distributed.yml` 是配合 SandboxProperties / 远端工作区使用的，需要**新增**而不是替换当前 properties。

---

## 六、依赖（pom.xml）差异

新版本独有依赖：
- `spring-boot-starter-actuator`（暴露健康 / metrics 端点）
- `mysql-connector-j 9.6.0`（当前是 8.2.0）

新版本**移除**的依赖（当前项目仍需保留）：
- `webflux`、`micrometer-core`、`metrics-core`、`mybatis-spring-boot-starter`、`opengauss-jdbc`、`clickhouse-jdbc`、`hutool-all`、`fastjson`、`jasypt-spring-boot-starter`、`commons-io`、`agentscope-core`（当前自维护）

新版本可能需要新增的依赖（沙箱/SSH 用）：
- HikariCP（Spring Boot 4 已默认带，SB3.2 也带）
- jedis（如果引入 JedisBaseStore）
- jsch / sshd（如果引入 SshArtifactIo / RemoteDirSyncer——需要确认其 import）

---

## 七、合并建议（分阶段）

### 阶段 A：必合（核心改进，几乎纯收益，风险低）
1. **InfraConfig** — 引入 HikariCP DataSource Bean（消除 DriverManager 性能瓶颈）
2. **ResponseCacheService** — 引入 question-hash cache key（修复 analyze 类问句撞键 bug）
3. **ArtifactAccessHook** — 扩展拦截 write_file / shell_execute（安全加固）
4. **SupervisorService** — 至少把 *episodic memory 预热* 这段合进来（去掉首次请求 13s 卡顿）

### 阶段 B：选合（新功能，按需引入）
5. **Memory 持久化体系**（MemoryHydrator / MemoryFileWatcher / MysqlMemoryStore / NightlyMemoryFlushService 等）— 如果业务上需要跨会话 agent 记忆
6. **Sandbox / 远程文件系统**（SandboxProperties + FilesystemConfig + DockerSandboxClient + 远程工作区同步）— 如果需要把 shell_execute 隔离到 docker 或远端主机

### 阶段 C：不合或谨慎合
7. **ChatController** — **保留当前的 SSE 流式实现**，仅将新版的 cache hook + artifactStore.cleanupTask 时序改进 cherry-pick 到 ChatStreamServiceImpl
8. **EpisodicMemory / EpisodicResult** 接口 — 当前项目是自定义增强版，**不要回退**
9. **ModelFactory** — 当前 ModelRegistry/ModelProperties/ModelBuilders 体系更完整，**不要替换**
10. **ReActAgent** — 当前项目大量定制（78KB），新版本无对应文件，**保持原样**

### 阶段 D：跳过
11. **HarnessA2aApplication** — 当前 `AgentscopeA2aApplication` 已胜任，仅参考其 `@ConfigurationPropertiesScan` 用法
12. 多数据源 / MyBatis / 业务 Tools / SessionHook — 当前项目独有，**全部保留**

---

## 八、合并时的"包名映射对照表"（保留当前包名）

```text
org.example.harness.a2a.artifact      → com.agentscopea2a.harness.artifact
org.example.harness.a2a.cache         → com.agentscopea2a.harness.cache
org.example.harness.a2a.config        → com.agentscopea2a.harness.config（部分）/ agent.memory（EpisodicMemoryConfig）/ agent.model（ModelFactory）
org.example.harness.a2a.controller    → com.agentscopea2a.controller
org.example.harness.a2a.dimension     → com.agentscopea2a.agent.dimension
org.example.harness.a2a.hooks         → com.agentscopea2a.harness.hooks
org.example.harness.a2a.memory        → com.agentscopea2a.agent.memory
org.example.harness.a2a.runner        → com.agentscopea2a.harness.runner
org.example.harness.a2a.service       → com.agentscopea2a.service
org.example.harness.a2a.session       → com.agentscopea2a.agent.session
org.example.harness.a2a.tools         → com.agentscopea2a.harness.tools
org.example.harness.a2a.workspace     → com.agentscopea2a.harness.workspace（新建）

io.agentscope.harness.agent.*         → 保留 io.agentscope.harness.agent.*（io.agentscope 命名空间是核心库扩展，不属于业务包）
```

---

## 九、风险点提示

1. **JDK 版本**：新版要求 JDK 21，当前 JDK 17。如果合并 SandboxBackedFilesystem 等使用了 JDK 21 新语法（如 `String.formatted` 之外的特性），需逐文件检查。
2. **Spring Boot 版本**：新版 SB 4.0.4，当前 SB 3.2.3。`ObjectProvider`、`HikariCP` 等在两者都存在；但新版可能用了 SB4 特有 API，逐 import 验证。
3. **MySQL Connector**：新版 9.6 vs 当前 8.2。9.x 默认时区/SSL 行为有差异（已在 PersistenceProperties URL 中体现）。
4. **缓存键策略变化**：`generateCacheKey` 加了 `question` 参数后，旧缓存条目对 analyze 类意图会全部 miss 一次（自然冷启动），无数据迁移需求。
5. **EpisodicMemory 接口分歧**：新版回退到精简版，如果合并新版的 `MySqlEpisodicMemory` 需确认它是否调用了当前接口扩展的方法。

---

## 十、下一步

**待您确认要进入哪个阶段后再动代码**。建议从阶段 A 开始，逐项 cherry-pick：
- 选项 1：先合 InfraConfig 的 HikariCP 改进（一改全局受益）
- 选项 2：先合 ResponseCacheService 的 question-hash（修 bug）
- 选项 3：先合 ArtifactAccessHook 的扩展拦截（安全加固）
- 选项 4：完整合阶段 A（4 个文件）
- 选项 5：连同阶段 B 也合
