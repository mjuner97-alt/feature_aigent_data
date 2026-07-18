# Harness Agent 框架 vs 传统 Agent2Agent 框架 — 技术与使用效果对比

> 对象：本项目（基于 AgentScope Java **harness 1.1.0-RC2** + Spring Boot 3.2.3）所采用的 **Harness Agent 框架** 与 **传统 Agent2Agent（A2A）协议框架** 之间的横向对比。
> 目的：理清两条技术路线在架构、能力、性能、可维护性、运维成本上的差异，为后续选型 / 演进提供依据。
> 范围：以本仓库的 `QualitySupervisor`（多智能体质量数据助手）为 Harness 侧实例；以 Google A2A Protocol / 早期 AgentScope A2A starter 等"独立服务、消息互发"的形态为传统 A2A 侧参照。

---

## 1. 概述

### 1.1 两条路线的本质差异

| 维度 | 传统 Agent2Agent 框架 | Harness Agent 框架（本项目） |
|---|---|---|
| **核心抽象** | Agent = 独立服务，通过 JSON-RPC 互发消息 | Agent = 单进程内由 `HarnessAgent` builder 装配的执行单元 |
| **协作模型** | 网络对等协商（AgentCard 发现 → 远程调用） | 进程内 Supervisor 派单（Markdown 驱动 subagent） |
| **状态归属** | 每个 Agent 自管状态、记忆、工具 | 状态外置到 MySQL（session / cache / memory），Agent 实例轻量 |
| **扩展点** | 协议层（skill / message format） | Hook 链（PreCall / PostCall / PreActing / PostActing / PreReply / PostReply） |
| **跨 Agent 数据传递** | 序列化进 message body（LLM 抄数字） | Artifact Handoff 协议（CSV 落盘 + `pd.read_csv(handle)` 引用） |
| **能力进化** | 静态（人工改 prompt / 加 skill） | 自演化（skill 合成 → 蒸馏 → 检索 → 反馈演化） |
| **典型代表** | Google A2A Protocol、跨服务多 Agent 编排 | AgentScope Harness、Claude Code Agent SDK |

### 1.2 一句话总结

> **传统 A2A** 把"多 Agent"做成"多服务"，把协作问题转化为网络协议问题；
> **Harness** 把"多 Agent"做成"单进程内的 Hook 编排"，把协作问题转化为**上下文管理 + 状态外置 + 切面扩展**问题。

前者强调 **边界与互操作**，后者强调 **内聚与可控**。两者并非互斥——本项目同时暴露 A2A AgentCard（`/.well-known/agent-card.json`），但**主调用链路走 Harness**，A2A 只作为对外互操作协议。

---

## 2. 架构模型对比

### 2.1 传统 A2A：服务化对等协商

```
┌────────────┐    JSON-RPC     ┌────────────┐    JSON-RPC     ┌────────────┐
│  Agent A   │ ◄─────────────► │  Agent B   │ ◄─────────────► │  Agent C   │
│ (独立进程)  │   AgentCard     │ (独立进程)  │   AgentCard     │ (独立进程)  │
│ 自管 state  │   发现 + 调用   │ 自管 state  │                 │ 自管 state  │
│ 自管 tools  │                 │ 自管 tools  │                 │ 自管 tools  │
│ 自管 memory │                 │ 自管 memory │                 │ 自管 memory │
└────────────┘                 └────────────┘                 └────────────┘
      │                              │                              │
      └──────── 状态/记忆/缓存各自为政，跨 Agent 共享需额外协议 ──────┘
```

特征：
- 每个 Agent 是一个**独立部署单元**（进程 / 容器 / 服务）
- 通过 `AgentCard` 描述自身 skills，调用方解析后发起 JSON-RPC
- 协作 = 多次远程调用 + 消息体序列化
- 无共享上下文，跨 Agent 数据靠 message 携带

### 2.2 Harness：进程内编排 + 状态外置

```
                ┌─────────────────── Spring Boot 进程 ───────────────────┐
                │                                                          │
   Client ──►  HarnessA2aRunner (AgentRunner Bean)
                │   ├─ SupervisorService.build(cacheHook, ctx)             │
                │   │    ├─ MemoryHydrator.hydrate(userId)  ← DB 回灌文件   │
                │   │    ├─ 装载 Hook 链（内置 7 + 业务 7）                  │
                │   │    └─ 解析 agent-subagents/*.md → SubagentSpec       │
                │   │                                                     │
                │   └─ HarnessAgent.stream(msgs, ctx)                      │
                │        ├─ ResponseCacheHook(0)  ── 命中则短路 LLM         │
                │        ├─ SkillRetrievalHook   ── embedding top-k 注入   │
                │        ├─ ArtifactHandoffHook(12) ── 表格 → CSV 引用     │
                │        ├─ ArtifactAccessHook(8) ── 跨桶访问拦截          │
                │        ├─ SkillSynthesisHook ── PostReply 蒸馏候选       │
                │        └─ SkillEvolutionHook  ── 反馈驱动再合成          │
                │                                                          │
                │   ┌─── 外置状态层（MySQL） ───┐                          │
                │   │  session_state_list       │                          │
                │   │  response_cache           │                          │
                │   │  agent_memory + _ledger   │                          │
                │   │  episodic_memory          │                          │
                │   │  skill_index / candidate  │                          │
                │   └───────────────────────────┘                          │
                └──────────────────────────────────────────────────────────┘
                                          │
                                          ▼
                          共享 Docker 容器（GLOBAL scope）
                          agentscope-shared-demo
```

特征：
- **单进程多 Agent**：Supervisor + 4 个 subagent（query_data / analyze_data / generate_skill / code_interpreter）都在同一 JVM
- **状态全外置**：session、cache、memory、skills 全在 MySQL，Agent 实例每请求新建、轻量
- **Hook 切面**：业务逻辑全部聚合在 hook，工具层零侵入
- **共享容器沙箱**：所有用户 × session × subagent 复用同一个长寿命 Docker 容器，JVM 只 `docker exec`

---

## 3. 技术优势对比

### 3.1 工具管理与路由

| 项 | 传统 A2A | Harness |
|---|---|---|
| 工具注册 | 每 Agent 显式声明工具清单，LLM 直接看完整工具菜单 | `@Tool` 反射扫描 → `toolId` 映射表；子代理只持 2 个元工具 |
| 工具膨胀 | 工具数 O(N) → LLM 上下文膨胀，命中率下降 | 收敛到 **2 个元工具 + 1 个索引 skill**，上下文恒定 |
| 新增工具 | 改 Agent prompt + 重新发布服务 | 写 `@Tool` 方法 + 更新 `tool_index/SKILL.md`，**子代理零改动** |
| 参数发现 | LLM 靠 prompt 例子猜参数 | `toolMetaInfo(toolId)` 显式取 schema，参数错配率下降 |

**关键机制：两步式元工具路由**（`ToolRoutersIndex` + `router_tool` + `toolMetaInfo`）
- 子代理先查 `tool_index` skill 拿 `toolId`，再走元工具统一执行入口
- 业务工具数量从 O(N) 个名字膨胀到 LLM 上下文 → 收敛到 **2 个元工具 + 1 个索引 skill**

### 3.2 跨 Agent 数据传递：Artifact Handoff 协议

| 项 | 传统 A2A | Harness |
|---|---|---|
| 表格数据传递 | 序列化进 message body，LLM 抄数字到下一个 Agent | `ArtifactHandoffHook` 自动落 CSV，message 只带 `pd.read_csv(handle)` |
| Token 成本 | ~800 token/表 × N 次跨 Agent 复制 | ~80 token/handoff，**省 90%+** |
| 正确性 | LLM 抄错数字风险 | LLM 不触碰数字，DataFrame 直接读 |
| 工具侵入 | 工具需感知协议 | **工具零改动**，返回 markdown 即可 |
| 多租户安全 | 协议层无内置隔离 | 路径分桶 `<userId>/<taskId>/` + UUID + `ArtifactAccessHook` 拦截 |

**关键机制：P3 协议**（`ArtifactHandoffHook` + `ArtifactStore` + `ArtifactAccessHook`）
- `PostActingEvent` 透明识别 markdown table / JSON array → 落 CSV → 替换结果为 handoff 消息
- `PreActingEvent` 拦截 `read_file` / `write_file` / `shell_execute` / `python_exec` 跨桶引用
- task 结束 `doFinally` 清整桶，`ArtifactSweeper` 每小时兜底

### 3.3 状态与记忆管理

| 项 | 传统 A2A | Harness |
|---|---|---|
| 对话状态 | 每 Agent 自管，跨 session 不可恢复 | `MySQLSession` 实现 `Session` 接口，按 (user_id, session_id) 持久化 |
| 长期记忆 | 每 Agent 自管，无统一规范 | `MySqlEpisodicMemory` 支持向量检索 + FTS fallback，按 userId 分桶 |
| 记忆镜像 | 无 | 文件 + DB 双链路：`MemoryFileWatcher` 5s 轮询镜像；`MemoryHydrator` build 前 DB → 文件回灌 |
| 上下文压缩 | 每 Agent 自实现 | 内置 `CompactionHook`（40 触发 / 12 保留）+ `ToolResultEvictionHook`（>80000 字符落盘） |
| 跨 Agent 记忆共享 | 需额外协议 | 同进程内天然共享（按 userId 分桶） |

**关键机制：Memory 三件套**
- `MysqlMemoryStore`：`agent_memory` + `agent_memory_ledger` 两表，`(user_id, kind, key_name)` 上锁
- `MemoryFileWatcher`：5s 轮询 `<workspace>/memory/<userId>/`，容器内子智能体写入也进 DB
- `MemoryHydrator`：`build()` 前把 DB 里的 MEMORY.md 回灌到本地文件，让 harness 内置 hook 不感知 DB

### 3.4 性能优化：缓存与短路

| 项 | 传统 A2A | Harness |
|---|---|---|
| 缓存粒度 | 协议层无定义 | 维度级缓存：`tenantBucket \| intent \| dimensionKey` |
| 缓存命中行为 | 仍需走完 Agent 调用链 | `CacheHitException` **短路 LLM**，直接把 cached response 流回客户端 |
| 命中延迟 | ~33s（全链路）→ 仍要远程调 Agent | **~1s**（实测） |
| 缓存可观测 | 无 | Micrometer `harness.a2a.cache{outcome=hit|miss|write|error}` |
| 缓存与记忆联动 | 无 | 缓存 HIT 也记入 EpisodicMemory，避免长期记忆偏斜 |

**关键机制：ResponseCacheHook（priority 0）**
- `PreCallEvent`：`DimensionStateManager` 抽问题维度 → 生成 tenant-scoped key → 查 MySQL `response_cache`
- 命中 → 抛 `CacheHitException` 短路，跳过所有 LLM 调用与工具执行
- `PostCallEvent`：未命中则写缓存
- 缓存 HIT 路径仍调 `recordCacheHitToEpisodic()`，让缓存命中的交互也在 EpisodicMemory 留痕

### 3.5 能力自演化：Skills 闭环

| 项 | 传统 A2A | Harness |
|---|---|---|
| Skill 来源 | 人工编写、静态发布 | 成功流程自动蒸馏 + 人工基线 |
| 检索方式 | 全量塞 prompt 或人工选择 | embedding top-k（L1 JVM 内存 60s 刷新 + L2 SQL fallback） |
| 演化机制 | 无 | 反馈驱动（用户拒绝 / 工具失败关键字）打负分 → 触发再合成 |
| 跨实例协调 | 无 | MySQL `evolving` 锁 + `UPDATE ... WHERE evolving=FALSE` 跨 JVM 互斥 |
| 持久化 | 无 | `skill_index` / `skill_candidate` / `skill_pending_judgement` 三表 |
| 失败恢复 | 无 | `SkillDistiller` 重试 + `parseLenient()` 宽松解析，蒸馏成功率 ~95% |

**关键机制：Skills 自演化三链路**
- `SkillSynthesisHook`：`PostReply` 把成功工作流提炼成 `SkillCandidate` 入库；累计 N 次同质轨迹 → `SkillDistiller` 蒸馏 `SKILL.md`
- `SkillEvolutionHook`：跑时基于反馈对现有 skill 打负分，触发再合成；跨 JVM 通过 MySQL 互斥演进
- `SkillRetrievalHook`：`PreReply` 按问题 embedding top-k 检索；命中时追加 `## 最近参考案例`

### 3.6 沙箱与执行环境

| 项 | 传统 A2A | Harness |
|---|---|---|
| 容器生命周期 | 每 Agent 自管或无沙箱 | **共享单容器**（GLOBAL scope），整个 fleet 复用一个长寿命容器 |
| 冷启动 | ~30s（容器创建 + 镜像拉起） | **~0**（JVM 只 `docker exec`，不 `docker run`） |
| 资源占用 | N × (M+1) 个容器 | **1 个容器** |
| 隔离粒度 | 容器级（强） | 路径分桶 + DB 行隔离 + `ArtifactAccessHook`（软层）+ uid 隔离（规划中） |
| 远端执行 | 需自建 SSH / RPC 通道 | `SshArtifactIo` + `DOCKER_HOST=ssh://` 原生支持远端 Docker daemon |
| Python 重试 | 无 | `PythonExecRetryHook`：`ModuleNotFoundError` / `Timeout` 自动 `pip install` + 二次执行 |

**关键机制：共享单容器**
- `agentscope-shared-demo` 预创建一次，`--restart unless-stopped` 自愈
- 所有 user × session × subagent 复用，JVM 不创建/销毁容器
- 三 profile 覆盖三种拓扑：`sandbox-windows`（Win JVM + 远端 Linux Docker）/ `sandbox-linux`（同机）/ `sandbox-linux-remote`（Linux JVM + 远端 Docker）

### 3.7 夜间记忆咀嚼（长期优化）

| 项 | 传统 A2A | Harness |
|---|---|---|
| 长期记忆维护 | 无 | `@Scheduled cron 21:09` 四阶段管道 |
| 工具链挖掘 | 无 | Phase 2 `MineTraces`：L1 工具调用 + L2 子代理 `memory_messages.jsonl`，合并 fingerprint |
| 失败链路进化 | 无 | Phase 3 `EvolveSkills`：高失败率工具链（>0.3）触发 skill 蒸馏/演化 |
| 记忆归并 | 无 | Phase 4 `ConsolidateMemory`：成功流程 LLM 归并到用户 MEMORY.md |
| 跨 JVM 互斥 | 无 | MySQL `GET_LOCK`，按 userId 独立 try-catch |

### 3.8 可扩展性：Hook 切面 vs 协议层

| 项 | 传统 A2A | Harness |
|---|---|---|
| 扩展点位置 | 协议层（message / skill 格式） | Hook 链（6 类事件，priority 排序） |
| 业务逻辑侵入 | 工具需感知验证 / 缓存 / 审计 | **工具零侵入**，业务逻辑全在 hook |
| 优先级控制 | 无显式机制 | `@Priority` 数值排序，cache(0) → access(8) → handoff(12) |
| 事件粒度 | 消息级 | `PreCall` / `PostCall` / `PreActing` / `PostActing` / `PreReply` / `PostReply` |
| 内置能力 | 无 | `WorkspaceContextHook` / `SessionPersistenceHook` / `CompactionHook` / `ToolResultEvictionHook` / `MemoryFlushHook` 默认全开 |

---

## 4. 使用效果优势对比

### 4.1 开发效率

| 场景 | 传统 A2A | Harness |
|---|---|---|
| 加一个子智能体 | 新建服务 / 改 AgentCard / 部署 | 新增一个 `agent-subagents/<name>.md`（YAML front matter 声明 name / tools / maxIters） |
| 加一个业务工具 | 改 Agent prompt + 注册工具 + 发布 | 写一个 `@Tool` 方法 + 更新 `tool_index/SKILL.md` |
| 改 supervisor 人格 | 改 prompt + 重启服务 | 编辑 `workspace/AGENTS.md`，启动期自动覆盖 |
| 加一个切面（如审计） | 改协议 / 改所有 Agent | 写一个 `Hook` 实现，注入 builder |
| 换 LLM provider | 每 Agent 各换一遍 | 改 `InfraConfig.model()` 一个 Bean |
| 换 embedding | 每 Agent 各换一遍 | 实现 `EmbeddingClient` 接口 |
| 换 artifact 后端 | 每 Agent 各换一遍 | 实现 `ArtifactIo` 接口（3 个方法） |

### 4.2 运行性能（本项目实测）

| 指标 | 传统 A2A（估算） | Harness（实测） |
|---|---|---|
| 简单查询冷启动 | ~30s（容器创建）+ LLM 链路 | ~42s cold（无沙箱 / 沙箱已温） |
| 同问题二次请求 | ~33s（仍走全链路） | **<1s**（cache hit 短路） |
| 跨 session 复用 | 不支持（缓存按 session） | 支持（缓存按 userId 维度） |
| 数据分析链路 | LLM 抄数字 + 多次往返 | ~216s，`code_interpreter` 容器内 pandas 一把算完 |
| 中间数据传递 | ~800 token/表 × N 次 | ~80 token/handoff，**省 90%+** |
| 沙箱冷启动 | ~30s/请求 | ~0（共享容器） |

### 4.3 多租户隔离

| 层 | 传统 A2A | Harness |
|---|---|---|
| 数据层 | 靠每 Agent 自实现 | `agent_memory` / `agent_memory_ledger` 按 user_id 分行；`response_cache` key 加 tenant 前缀；`session_state_list` 按 (user_id, session_id) 主键；LTM 按 userId 分桶 |
| 文件层 | 靠每 Agent 自实现 | `workspace/artifacts/<userId>/<taskId>/<tool>-<uuid>.csv` 路径分桶 + 原子写 |
| 工具层 | 无 | `ArtifactAccessHook` 拦截 `read_file` / `write_file` / `shell_execute` / `python_exec` 跨桶引用 |
| 容器层 | 容器级强隔离（每 Agent 一容器） | 共享容器 + 软层 hook（uid 隔离规划中） |

### 4.4 可观测性

| 项 | 传统 A2A | Harness |
|---|---|---|
| 指标 | 每 Agent 自埋点 | Micrometer 统一：`harness.a2a.cache{outcome=hit|miss|write|error}` / HikariCP / JVM |
| 调试接口 | 无统一规范 | `/debug/workspace` / `/debug/memory` / `/debug/skills` / `/debug/sessions` |
| 工具调用追踪 | 每 Agent 自实现 | `ToolCallCollector`（ThreadLocal）+ `ToolCallTrackingHook`，doFinally 持久化到 EpisodicMemory |
| 缓存可观测 | 无 | `/actuator/metrics/harness.a2a.cache?tag=outcome:hit` |
| 健康检查 | 每 Agent 自实现 | `/actuator/health` + `SshHealthCheck`（远端 Docker 可达性） |

### 4.5 容错与自愈

| 场景 | 传统 A2A | Harness |
|---|---|---|
| LLM 调用失败 | 抛错给上游 | `SkillDistiller` 重试 + 宽松解析 |
| 沙箱 `ModuleNotFoundError` | 报错给 LLM | `PythonExecRetryHook` 自动 `pip install` + 二次执行 |
| task 异常退出 | 残留状态 | `doFinally` 在 complete/error/cancel 都清理 artifact 桶 |
| artifact 残留（JVM crash） | 无 | `ArtifactSweeper` 每小时扫 ≥6h 没碰的 bucket |
| 共享容器 OOM | 单 Agent 影响 | `--restart unless-stopped` dockerd 自愈 |
| 缓存写失败 | 报错 | MySQL fail-soft，WARN 但不阻断 |
| 记忆偏斜 | 无 | 缓存 HIT 路径也记入 EpisodicMemory，避免 MISS-only 偏斜 |

### 4.6 长期使用价值

| 项 | 传统 A2A | Harness |
|---|---|---|
| 越用越聪明 | 不支持 | Skills 自演化：成功流程蒸馏 → 检索注入 → 反馈演化 |
| 长期记忆归并 | 不支持 | 夜间咀嚼：成功流程 LLM 归并到 MEMORY.md |
| 失败链路自修复 | 不支持 | 高失败率工具链触发 skill 进化 |
| 跨用户知识复用 | 不支持 | skill 全局共享（按 userId 分桶的是 memory） |
| 知识沉淀 | 靠人工 | `skill_candidate` → `SKILL.md` 自动沉淀，可由人工 review |

---

## 5. 传统 A2A 的优势（公平起见）

Harness 不是银弹，传统 A2A 在以下场景仍有优势：

| 场景 | 传统 A2A 优势 | Harness 局限 |
|---|---|---|
| **跨组织互操作** | 协议标准化，不同厂商 Agent 可互调 | Harness 是进程内编排，跨组织需走 A2A 协议层 |
| **异构 LLM / 异构技术栈** | 每 Agent 可用不同语言 / 不同 LLM | 同进程内通常同语言同 LLM provider |
| **独立扩缩容** | 每 Agent 可按负载独立扩容 | 单进程扩容需多副本 + Redis BaseStore（`distributed` profile） |
| **故障隔离** | 单 Agent 挂不影响其他 | 单进程内任一 hook 抛错可能影响整请求（靠 try-catch 兜底） |
| **安全强隔离** | 容器 / 进程级天然强隔离 | 共享容器是软层 hook 隔离，uid 隔离暂未实施 |
| **团队边界** | 不同团队各管一个 Agent 服务 | 单仓库单进程，团队协作需靠模块边界 |
| **协议可演进** | A2A 协议层标准化演进 | Harness API 跟随 AgentScope 版本（当前 1.1.0-RC2） |

**本项目的取舍**：主调用链路走 Harness（性能、自演化、多租户、开发效率优先），同时暴露 A2A AgentCard 作为对外互操作入口（兼容 A2A 生态）。这是**"内 Harness + 外 A2A"**的混合形态。

---

## 6. 适用场景建议

### 6.1 选 Harness 当满足

- ✅ 单组织内部多 Agent 协作（不需要跨厂商互操作）
- ✅ 对延迟敏感（缓存短路、共享容器冷启动 ~0）
- ✅ 需要长期记忆 + 技能自演化（越用越聪明）
- ✅ 多租户场景（按 userId 分桶 + 缓存隔离）
- ✅ 工具数量多且持续增长（两步式元工具路由避免上下文膨胀）
- ✅ 团队规模适中，偏好单仓库高效迭代
- ✅ 需要强可观测性（Micrometer + debug 接口统一）

### 6.2 选传统 A2A 当满足

- ✅ 跨组织 / 跨厂商 Agent 互操作
- ✅ 不同 Agent 用不同技术栈 / 不同 LLM provider
- ✅ 需要独立扩缩容 / 独立故障隔离
- ✅ 安全合规要求容器级强隔离
- ✅ 大团队多仓库边界清晰

### 6.3 混合形态（本项目路径）

- **内部编排**走 Harness：Supervisor + 4 subagent 同进程，享缓存 / 自演化 / 共享容器 / artifact handoff
- **对外互操作**走 A2A：暴露 AgentCard，兼容 A2A 生态，第三方可按协议调用
- **跨副本分布式**：`distributed` profile + Redis BaseStore（已落地 `JedisBaseStore`），artifacts 需 LB 粘性或共享存储

---

## 7. 总结

### 7.1 技术优势核心一句话

> Harness 把"多 Agent 协作"从**网络协议问题**变成**上下文管理 + 状态外置 + 切面扩展**问题，从而拿到了缓存短路、artifact 透明传递、skill 自演化、共享容器冷启动 ~0 等"传统 A2A 协议层无法表达"的能力。

### 7.2 使用效果优势核心一句话

> 同等业务目标下，Harness 比 A2A 在**延迟（33s → 1s）、token 成本（省 90%+）、开发效率（加子代理 = 写一个 .md）、运维成本（1 容器 vs N 容器）、长期价值（越用越聪明）**五个维度上有数量级或显著优势。

### 7.3 取舍

- **Harness 代价**：失去跨组织互操作 / 异构技术栈 / 独立扩缩容 / 容器级强隔离
- **A2A 代价**：失去缓存短路 / artifact 透明传递 / skill 自演化 / 共享容器 / 统一可观测
- **本项目选择**：内 Harness + 外 A2A，鱼与熊掌兼得，但分布式场景需额外处理 artifact 跨副本

### 7.4 演进方向

- **uid 隔离**：补齐容器层硬隔离（已设计未实施）
- **distributed artifact**：跨副本共享存储 / LB 粘性
- **A2A 协议层 skill 同步**：让外部 A2A Agent 也能消费本项目自演化的 skill
- **Hook 链可视化**：把 14 个 hook 的执行轨迹可视化，便于调试

---

## 附录 A：关键文件索引

| 能力 | 关键文件 |
|---|---|
| Harness 适配 + 缓存 HIT 记入 EpisodicMemory | `harness/runner/HarnessA2aRunner.java` |
| HarnessAgent 工厂 + hook 链装配 | `service/SupervisorService.java` |
| 两步式元工具路由 | `harness/tools/ToolRoutersIndex.java` |
| Artifact Handoff 协议 | `harness/hooks/ArtifactHandoffHook.java` / `ArtifactAccessHook.java` / `artifact/ArtifactStore.java` |
| 缓存短路 | `harness/hooks/ResponseCacheHook.java` / `harness/cache/ResponseCacheService.java` |
| Skills 自演化 | `harness/hooks/Skill{Retrieval,Synthesis,Evolution}Hook.java` / `harness/skills/*` |
| Memory 三件套 | `agent/memory/{MysqlMemoryStore,MemoryFileWatcher,MemoryHydrator}.java` |
| 夜间咀嚼 | `agent/memory/digestion/MemoryDigestionService.java` |
| 沙箱共享容器 | `harness/config/FilesystemConfig.java` + 本地 patch `DockerSandboxClient.java` |
| 多租户隔离 | `agent/session/MySQLSession.java` / `harness/hooks/ArtifactAccessHook.java` |

## 附录 B：参考文档

- 项目总览：[`README.md`](README.md)
- RC1 → RC2 升级：[`agentscope-rc1-to-rc2-migration.md`](agentscope-rc1-to-rc2-migration.md)
- Memory + Skill 优化：[`skill-evolution-memory-digestion-combined.md`](skill-evolution-memory-digestion-combined.md)
- 夜间咀嚼管道：[`night-time-digestion-pipeline.md`](night-time-digestion-pipeline.md)
- code_interpreter 优化：[`code-interpreter-optimization.md`](code-interpreter-optimization.md)
- 缓存 tenant 分桶：[`user-id-cache-scope-fix.md`](user-id-cache-scope-fix.md)
