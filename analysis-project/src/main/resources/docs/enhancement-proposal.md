# harness-example-a2a 增强方案

> 目的:盘点示例**已经用上**和**还没用上**的 `agentscope-harness` 能力,挑出能让 demo 更有看点、更接近真实生产、更能体现 harness 范式的增强点,排好优先级。
>
> 现状速览参见 [architecture.md](architecture.md) / [README.md](README.md)。本文不重复架构图,只聚焦"能加什么"。

---

## 1. 现状盘点:harness 用了多少

把 `HarnessAgent.Builder` 的能力表对一遍 [SupervisorService.java](../src/main/java/io/agentscope/examples/harness/a2a/service/SupervisorService.java):

| harness 能力 | 现状 | 备注 |
|---|---|---|
| `WorkspaceContextHook`(AGENTS.md / KNOWLEDGE / MEMORY 注入) | ✅ 默认开 | 主要卖点之一 |
| `SessionPersistenceHook` | ✅ 默认开 + 自定义 `MySQLSession` | per-task `sessionKey` |
| `MemoryFlushHook` / `MemoryMaintenanceHook` / `MemoryConsolidator` | ✅ 默认开 | 写 `workspace/MEMORY.md` |
| `CompactionHook` | ✅ 显式开,`triggerMessages=40` | |
| `ToolResultEvictionHook` | ✅ 显式开,`maxChars=80_000` | |
| `LongTermMemory`(`MySqlEpisodicMemory`) | ✅ `LongTermMemoryMode.BOTH` | **作用域有问题**,见下 |
| `subagentFactory(...)` 编程式注册 | ✅ 3 个子 agent | 但**不是** Markdown 自动发现 |
| `AgentTraceHook` | ✅ 默认开 | |
| 自定义业务 hook | ✅ `ResponseCacheHook` / `DataGroundingHook` | |
| `SkillLearningHook` / `SkillLearningManager` | ✅ 默认开(`enableSkillLearning` 默认 `true`) | 自动抽取已生效,但**没有显式配置 `skillLearningMinGap`**,且效果**没有被可视化/演示** |
| **`AgentSpecLoader` + `workspace/subagents/*.md` 自动发现** | ❌ **未启用** | Markdown 只当 sysPrompt 模板用 |
| **`MemorySearchTool` / `MemoryGetTool` / `SessionSearchTool`** | ✅ 默认会注册,但子 agent 拿不到 | 子 agent toolkit 只塞了 `QualityTools` |
| **`FilesystemTool` / `ShellExecuteTool`** | ⚠️ 默认会注册到 supervisor | 但没用 sandbox,直接打到宿主 |
| **`SandboxManager` / `SandboxFilesystemSpec`** | ❌ **未启用** | 文档里也明确说"无 sandbox 隔离" |
| **`RemoteFilesystemSpec` + `BaseStore`** | ❌ 未启用 | 分布式工作区路径 |
| **`IsolationScope`(SESSION/USER/AGENT/GLOBAL)** | ❌ 未启用 | 多租户的关键开关 |
| **`SandboxDistributedOptions` + `RedisSandboxExecutionGuard`** | ❌ 未启用 | 真正能跑多副本的关键 |

可以看到示例**主要展示了 prompt / 子 agent / 记忆 / 自动技能学习**类能力,但 harness 的**"隔离 / 沙箱 / 分布式工作区"**这条线几乎是缺位的。同时还有几个**硬 bug** 让 demo 跑不顺(`generate_skill` 子 agent 实际拿不到 `save_skill` 工具,LTM 作用域写死成 `"QualitySupervisor"` 而不是 per-user)。

> **关于 `SkillLearningHook`**:它是 harness 默认开启的(`enableSkillLearning` 默认 `true`),supervisor 满足所有注册条件(非 leaf / 有 model / 没禁用 memory hook),所以**自动技能学习实际已经在跑**。问题不在于"没启用",而是 demo 完全没有把它**讲出来**:
> - `application.yml` / `SupervisorService` 没显式设置 `skillLearningMinGap`(默认 10 分钟),短演示里可能根本不触发;
> - 没有 REST 端点能直接看 `workspace/skills/` 下自动产出的 skill,观众看不见;
> - 文档 [README.md](README.md) / [architecture.md](architecture.md) 里也没把"自动学习"列出来。
>

---

## 2. 增强方案

按"能让 demo 更好看 / 更能体现 harness"打分,分 P0/P1/P2。**P0 都是低成本高收益**,P1 是体现 harness 差异化能力,P2 是面向生产的工程化。

### P0-1. 修掉 `generate_skill` 工具丢失的 bug ✅ 已实施

**问题**:[SupervisorService.java](../src/main/java/io/agentscope/examples/harness/a2a/service/SupervisorService.java) 里 `SkillSaveTool` 只注册给 supervisor,但 `toolkitForSub("generate_skill")` 只塞了 `QualityTools`。子 agent 的 prompt 让它调 `save_skill`,实际工具不存在 → demo 第三类场景"把刚才的查询流程保存为 skill"是失败的。

**改动**:`toolkitForSub` 改成按名字派发——

```java
private Toolkit toolkitForSub(String name) {
    Toolkit tk = new Toolkit();
    switch (name) {
        case "generate_skill" -> tk.registerTool(new SkillSaveTool(workspace.resolve("skills")));
        case "query_quality_data", "analyze_data" -> tk.registerTool(new QualityTools());
        default -> {}
    }
    return tk;
}
```

成本 5 行,demo 立刻多一条能跑通的演示路径。

---


### P0-3. 让子 agent 也能用 memory / session 检索工具 — ⚠️ 经核实**无需改动**

复核 [HarnessAgent.java:1416-1420](../../../../agentscope-harness/src/main/java/io/agentscope/harness/agent/HarnessAgent.java) 后发现 `MemorySearchTool` / `MemoryGetTool` / `SessionSearchTool` 是用 `agentToolkit.registerTool(...)` **追加**到用户传入的 toolkit,不是替换。子 agent `disableMemoryTools` 默认 false → 这些工具一直都在。原结论判错,改动免做。

---

### P1-1. 切到 `AgentSpecLoader` —— 子 agent 真正"Markdown 驱动" ✅ 已实施

**现状**:Markdown 文件只是 sysPrompt 的字符串来源,**注册逻辑还在 Java 里**(`registerSubagentFactories`)。新增一个子 agent 要改两处。

**目标**:让 [workspace/subagents/*.md](../src/main/resources/workspace/subagents/) 文件加 YAML front matter 声明工具,启动时 [AgentSpecLoader.loadFromDirectory()](../../../../agentscope-harness/src/main/java/io/agentscope/harness/agent/subagent/AgentSpecLoader.java) 自动发现并注册。

`workspace/subagents/query-quality-data.md` 增加头:

```markdown
---
name: query_quality_data
description: 质量数据查询专家
tools:
  - quality_tools
  - memory_search
maxIters: 6
---

# 你是质量数据查询专家
...(原内容)
```

Java 端需要做的:
- 把"工具名 → Tool 实例"的映射注册一次(类似 Spring `@Bean` 注入命名工具表),`AgentSpecLoader` 解析到 `tools:` 后从注册表取。
- `SupervisorService` 里删掉 3 个 `subagentFactory(...)` 调用,改成 `loadSubagentsFromWorkspace()`。

**收益**:对外展示"加子 agent 不写 Java"这个核心卖点——直接编辑 `.md` + 重启就行。同时 [comparison-with-legacy-a2a.md](comparison-with-legacy-a2a.md) 里说的"harness 范式"才真正成立。

---

### P1-2. 用 `SandboxFilesystemSpec` 给 `SkillSaveTool` / 文件读写加隔离 ✅ 已实施(端到端)

`application-sandbox.yml` profile 默认 image `deepanalyze-vllm:latest`(预制,启动时不 build);`FilesystemConfig.sandboxFilesystem(...)` 用 `BindMountEntry` 把宿主 `workspace/skills/` 和 `workspace/memory/` 挂进容器 → sandbox 内 `SkillSaveTool` / `MemoryFlushHook` 写的文件持久化到宿主。配置 + 集成细节见 [sandbox-integration-plan.md](sandbox-integration-plan.md)。

**现状**:`SkillSaveTool` 直接 `Files.writeString(workspace/skills/...)` 写宿主磁盘;harness 默认注册的 `FilesystemTool` / `ShellExecuteTool` 也是直接打到宿主。多用户并发 → 文件互踩 + 安全风险。

**改动**:加一种**可选** profile(`application-sandbox.yml`),用 [SandboxFilesystemSpec](../../../../agentscope-harness/src/main/java/io/agentscope/harness/agent/sandbox/) 把工作区接到 Docker(项目里已有 `harness-sandbox-docker` 模块,可以复用)——

```java
.filesystem(new DockerSandboxFilesystemSpec(...)
    .isolationScope(IsolationScope.SESSION))
.sandboxDistributed(SandboxDistributedOptions.builder()
    .requireDistributed(false).build())
```

**收益**:
- 演示"同一份代码、改一行配置切换 local/docker filesystem"——这是 harness 抽象层的核心价值;
- 让`SkillSaveTool` / `FilesystemTool` / `ShellExecuteTool` 自动落到沙箱里,**安全演示**;
- 配合 `IsolationScope.SESSION` 演示多用户并发隔离。

---

### P1-3. 修正 `LongTermMemory` 的作用域 ✅ 已实施

**问题**:[SupervisorService.java](../src/main/java/io/agentscope/examples/harness/a2a/service/SupervisorService.java) 里 `EpisodicLongTermMemoryAdapter` 在构造函数里 **new 一次,固定 `sessionId="QualitySupervisor"`**。代码注释暗示"per-request",实际所有用户的长期记忆混到一个 bucket 里。

**改动**:adapter 接受 `RuntimeContext`,从里面取 `userId` 或 `sessionKey` 做 sessionId;或者每次 `build()` 重新构造。同时把 **MySQL 连接参数**从 `initLongTermMemory()` 硬编码挪到 `application.yml` + `@ConfigurationProperties`(`192.168.101.16/root/lwj052607` 不该在源码里)。

**收益**:LTM 多租户能用了;同时 [feedback_session_approach](../../../../../root/.claude/projects/-java-agentscope-java-release-1-0-12/memory/feedback_session_approach.md) 提到的"必须用 Session 接口"这条思路也用到了 LTM 上。

---

### P1-4. `RemoteFilesystemSpec` + `IsolationScope` 做多副本演示 ✅ 已实施

**故事**:Spring Boot 起 2 个实例(`8889` / `8890`),共享同一个 [InMemoryStore](../../../../agentscope-harness/src/main/java/io/agentscope/harness/agent/store/InMemoryStore.java) 替换成 Redis-backed `BaseStore` 实现 → workspace 文件(memory / skills / sessions)走分布式存储。两边任一实例写,另一边都能读。

```java
.filesystem(new RemoteFilesystemSpec(redisStore)
    .isolationScope(IsolationScope.USER))   // 同 userId 跨副本共享
.sandboxDistributed(SandboxDistributedOptions.builder()
    .executionGuard(RedisSandboxExecutionGuard.builder(jedis).build())
    .build())
```

**收益**:真正演示 harness "分布式工作区"能力;`RedisSandboxExecutionGuard` 防止两副本同时启动同一个 sandbox 实例。

(P1-4 跟 P1-2 是一对——本地 / 沙箱 / 分布式三档,逐档解锁。)

---

### P2-1. 把 cache / session / LTM 的 `DriverManager` 全换成 HikariCP ✅ 已实施

- session 和 cache 已切到 HikariCP(`InfraConfig.dataSource()`),3 个服务共享一个连接池;
- LTM 走 `MySqlEpisodicMemory`,其内部仍是 `DriverManager`(harness 上游限制),但凭据已统一通过 `PersistenceProperties` 注入,源码无明文。

- [MySQLSession.java](../src/main/java/io/agentscope/examples/harness/a2a/session/MySQLSession.java) 每次操作都 `DriverManager.getConnection()` → 高并发下卡死。
- [ResponseCacheService.java](../src/main/java/io/agentscope/examples/harness/a2a/cache/ResponseCacheService.java) 同样问题。
- `EpisodicLongTermMemoryAdapter` 走的是 `MySqlEpisodicMemory`,但 LTM 的连接参数也是硬编码 → 同上。

Spring Boot 里加一个 `HikariDataSource` Bean,3 个服务都注入它。同时把表名等改成 `@ConfigurationProperties(prefix = "harness-a2a.persistence")`。

### P2-2. `ResponseCacheHook` 用正式短路机制替代 `CacheHitException` ⚠️ 部分实施

harness 还没暴露 `PreCallEvent.shortCircuit(result)`,exception 短路保留(已在 hook docstring 标注 TODO)。已完成的部分:cache key 前缀加 `userId`/`sessionId`(多租户隔离),增加 Micrometer 指标 `harness.a2a.cache{outcome=hit|miss|write|error}` 经 actuator 暴露。

[ResponseCacheHook.java](../src/main/java/io/agentscope/examples/harness/a2a/hooks/ResponseCacheHook.java) 现在用抛异常做命中短路——能跑,但语义不好。如果 harness 后续给 hook 加了 `PreCallEvent.shortCircuit(result)` 之类 API,迁过去;同时 cache key 里加上 `userId` / 数据版本号,加 hit/miss/write 指标(Micrometer)。

### P2-3. `DataGroundingHook` 改成基于 tool 结果的结构化校验 — ✅ 单源已抽出,JSON 输出**不做**

**已做**:把硬编码的实体表抽到 `KnownEntities`(单一来源,`QualityTools` 和 hook 都读它)→ 添加新部门/人员只改一处。

**不做 — `QualityTools` 输出 JSON envelope**:原方案让每个 tool 在文本末尾追加 `\`\`\`json grounding {...}\`\`\`` 块,hook 解析。在 demo 里跑得通,**但放到真实场景里成本/收益不划算**:
- **侵入式**:每个 tool 都得改 return + 维护 envelope schema。生产里一个 supervisor 挂 80+ tool 是常态,80 处改造 + 80 处测试。
- **耦合错位**:让"业务 tool"知道"校验 hook 存在",违反单一职责。Tool 关心"给 LLM 什么数据",hook 关心"答案是否真实",二者不该互相感知。
- **替代方案更好**:当前的"正则抓 `\d+\.\d+` + 实体名包含匹配"对多数 tool 够用;真要做强校验该走 LLM-as-judge,而不是结构化比对。

**不做 — hook 挂到子 agent**:supervisor 的 `PostActingEvent` 在每次 `agent_spawn` 后会带回子 agent 的 `ToolResultBlock`,hook 已经能拿到完整数据流,**无需重复挂**(原方案这条是误判)。

[DataGroundingHook.java](../src/main/java/io/agentscope/examples/harness/a2a/hooks/DataGroundingHook.java) 现在硬编码了中文实体表 + 正则抓小数。改成:
- 实体清单从 `QualityTools` 静态数据派生(避免重复维护);
- 给 `QualityTools` 返回结构化结果(JSON 而不是字符串)→ hook 比对"最终回答里出现的数字"和"工具返回过的数字"集合;
- hook 同时挂到子 agent 上(现在只挂在 supervisor)。

### P2-4. 给 `MemorySearchTool` / `SessionSearchTool` 加可视化端点 ✅ 已实施

加 `/debug/memory` `/debug/sessions` `/debug/skills` 三个 REST 端点,直接列 workspace 下的内容,方便演示时让观众"看见"自动产出的记忆/技能/会话日志。

---

## 3. 建议的实施顺序

| 步骤 | 工作量 | 收益 | 依赖 |
|---|---|---|---|
| **P0-1** 修 `generate_skill` 工具丢失 | 10 分钟 | 修 bug | 无 |
| **P0-3** 子 agent 拿到 memory 工具 | 30 分钟 | 修 bug | 无 |
| **P1-1** `AgentSpecLoader` 自动发现 | 半天 | 兑现核心卖点 | P0-3 |
| **P1-3** LTM 作用域 + 配置外移 | 半天 | 修 bug + 安全 | 无 |
| **P2-1** HikariCP | 半天 | 工程化 | P1-3 |
| **P1-2** Sandbox filesystem profile | 1 天 | 体现 harness 抽象 | P1-1 |
| **P1-4** Remote filesystem 多副本 | 1-2 天 | 大演示 | P1-2 |
| P2-2 / P2-3 / P2-4 | 各 0.5-1 天 | 打磨 | 上面这些做完 |

最划算的口袋:**P0-1 + P0-3 + P1-1 + P1-3**(约 2 个工作日),能把示例从"展示 prompt / 记忆"升级到"展示 prompt / 记忆 / **真正 Markdown 驱动子 agent** / 安全多租户 LTM",同时把现存几个 demo 跑不通的 bug 修掉。

## 4. 不建议做的事

- **不要**把 `MySQLSession` 改成基于 harness 现成的 `MysqlSession`(如果存在的话)单纯为了少写代码——目前自定义的 `session_state_list` 增量 append 设计是适配 cache 场景的,换掉得不偿失。
- **不要**为了用 `SubagentSpec` 把所有子 agent 改成 leaf agent——`analyze_data` 需要嵌套调 `query_quality_data` 的话(`AgentSpecLoader.parse` 默认 leaf),还是得用 `subagentFactory(...)` 显式构造。两种方式混用即可。
- **不要**把 `DataGroundingHook` 换成"LLM-as-judge"做事实校验——慢、贵、且这个 demo 的数据是 mock 的,结构化比对就足够了。
