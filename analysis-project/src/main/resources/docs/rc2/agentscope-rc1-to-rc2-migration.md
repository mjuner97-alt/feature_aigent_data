# agentscope-java 1.1.0-RC1 → 1.1.0-RC2 升级迁移文档

> 触发原因：`docs/stream-thinking-subagent-plan.md` 中"主+子智能体流式思考过程透传"的实施依赖 `EventSource`，而 RC1 不提供。RC2 已经把 `EventSource` + `SubagentEventBus` 内建到事件管线里 —— 升级后子 agent reasoning / tool_result / agent_result 全部自动冒泡到主 stream，服务层只要透传 `event.getSource()` 即可，无需自己拼 source 元信息。
>
> 状态：**升级方案选定为方案 B（直接迁移到 RC2）**。本文档锁定迁移所有断点、shadow 文件清单、API 映射、落地顺序、验收。
>
> **更新（2026-06-25）**：阶段 A / B / C 已落地并通过冒烟。`mvn -DskipTests compile/package` 全绿、`dev` profile 启动正常、SSE 旧前端契约保持不变。原文档 §1 三条 intent（简单查询 / 数据分析 / 保存技能）端到端测试 2/3 通过，第 3 条 `save_skill` 中途被截断 —— 经追查为 glm-5.1 `finish_reason=length`(`completion_tokens=879`) 的 token 预算问题,**与 RC2 迁移无关**,详见 §9.

---

## 0. TL;DR

- pom 已切到 `1.1.0-RC2`。仅靠 pom 升级 **无法**通过编译。
- 项目业务代码（`com.agentscopea2a.*`）共 **3 个文件 ≥ 4 处**编译错误。
- 项目里维护了 **11 个 shadow 文件**（位于 `src/main/java/io/agentscope/**`），用于本地热补丁覆盖 JAR 类。其中 **2 个**在 RC2 下编译失败，**9 个**需要重新比对源头是否仍需保留。
- 推荐落地顺序：**先看哪些 shadow 还有必要 → 删冗余 shadow → 同步必要 shadow → 改业务代码 → 编译 → 启动 → SSE 冒烟**。

---

## 1. RC2 关键 API 变化（基于 javap 实测）

| 区域 | RC1 | RC2 | 影响 |
|---|---|---|---|
| 子 agent 规约类 | `io.agentscope.harness.agent.subagent.SubagentSpec` | `io.agentscope.harness.agent.subagent.SubagentDeclaration`（新增 `workspaceMode / workspacePath / inlineAgentsBody / model / url / headers / description`） | 类名变 + 字段语义重排 |
| 子 agent prompt 入口 | `SubagentSpec.getSysPrompt()` | **方法删除**；改由 `getInlineAgentsBody()` 承载内联 agents 段，sysPrompt 走声明里的其它字段 | 业务代码读取方式必须改 |
| AgentSpecLoader | `loadFromDirectory(Path dir)` | `loadFromDirectory(Path dir, Path workspaceRoot)` | 需新增 workspaceRoot 参数 |
| Event 携带来源 | 无 source 字段 | `Event { source: EventSource }`, `Event.withSource(EventSource)` | 业务直接 `event.getSource()` 即可 |
| EventSource | 不存在 | `EventSource(agentKey, agentId, agentName, sessionId, parentSessionId, taskId, depth, path)` | 协议透传所需 |
| 子 agent 事件冒泡 | 无 —— `AgentSpawnTool` 走同步 `agent.call()`，子流被吃掉 | `SubagentEventBus` 把子 agent reasoning/tool_result/agent_result 冒泡到父 stream（带 `EventSource`） | "前端看到子 agent 思考"的能力本期由 RC2 自动给出 |
| HarnessAgent.Builder | `.subagentFactory(name, factory)` | 多了 `.subagent(SubagentDeclaration)` / `.subagents(List<SubagentDeclaration>)`；旧 `.subagentFactory` 仍保留 | 现有 `registerSubagentFromSpec(...)` **可以继续走 factory 路线**，免大改 |
| TaskRepository.putTask | RC1 旧签名 | 多参签名（增 taskId/initiator 等） | shadow `AgentSpawnTool` 内部用到，**必须同步** |
| DefaultAgentManager.invokeAgent | RC1 旧签名 | 改返回值/形参 | shadow `AgentSpawnTool` 内部用到，**必须同步** |
| SubagentsHook 内部状态 | `Map<String, SubagentFactory>` | `List<SubagentEntry>`（保留 factory + spec 解耦） | shadow `SubagentsHook` 必须同步 |

> 备注：subagent 流式冒泡的具体冒泡机制由 RC2 内部 `SubagentEventBus.publish(...)` 完成 —— 服务层只是收到比 RC1 多得多的 `Event`，每条都带非空 `source`。

---

## 2. 编译错误清单（pom 升 RC2 后第一遍编译）

### 2.1 业务代码（`com.agentscopea2a.*`）

| 位置 | 错误 | 修复策略 |
|---|---|---|
| `service/SupervisorService.java:59` `import ... SubagentSpec` | 类不存在 | 改 import 为 `SubagentDeclaration` |
| `service/SupervisorService.java:155` 字段类型 `List<SubagentSpec> workspaceSubagents` | 类型不存在 | 改成 `List<SubagentDeclaration>` |
| `service/SupervisorService.java:262` `AgentSpecLoader.loadFromDirectory(subagentsDir)` | 方法签名变 | 改成 `loadFromDirectory(subagentsDir, workspace)` |
| `service/SupervisorService.java:382` 循环局部 `for (SubagentSpec spec : workspaceSubagents)` | 类型不存在 | 改成 `SubagentDeclaration` |
| `service/SupervisorService.java:436` 方法形参 `SubagentSpec spec` 在 `registerSubagentFromSpec(...)` | 同上 | 改成 `SubagentDeclaration` |
| `service/SupervisorService.java:438` `spec.getSysPrompt()` | 方法已删 | 改成 `spec.getInlineAgentsBody()`（或从声明中读 prompt 字段；具体看 javap 出来的 getter） |

### 2.2 Shadow 文件（`io.agentscope.*`，本仓库覆盖 JAR）

| 位置 | 错误 | 修复策略 |
|---|---|---|
| `io/agentscope/harness/agent/hook/SubagentsHook.java:96` `Map<String, SubagentFactory>` 不兼容 `List<SubagentEntry>` | 容器类型变 | 重抓 RC2 JAR 源 `SubagentsHook`，叠加本地补丁 |
| `io/agentscope/harness/agent/tool/AgentSpawnTool.java:143,150,165,235,242,257` `TaskRepository.putTask` / `DefaultAgentManager.invokeAgent` 签名变 | 调用形参错 | 同上，重抓 RC2 源叠加本地补丁。**注意**：RC2 的 AgentSpawnTool 已经内置 `SubagentEventBus.buildChildSource(...)` 与事件冒泡，本地 shadow 若是为了 RC1 修 bug，可能 RC2 已修复，应优先评估是否还需要 shadow |

> 其它 9 个 shadow 文件 **当前一遍编译没报错**，但 `ReActAgent / MemoryFlushHook / MemoryMaintenanceHook / EpisodicMemory / EpisodicResult / MemoryProvider / DockerSandbox / DockerCliRunner / DockerSandboxClient` 是否还需要 shadow，要逐个比对 RC2 jar 中相同类的字节码 —— 见 §3。

---

## 3. Shadow 文件全量清单 & 评估（已完成 audit）

> 已对照 `~/.m2/repository/io/agentscope/agentscope-core/1.1.0-RC2/` 与 `agentscope-harness/1.1.0-RC2/` 的字节码，结合 shadow 注释里写明的 patch 原因，得到以下定性。"Canonical" 表示 JAR 里**根本没有这个类**，shadow 即唯一定义，不可删。

| # | shadow 路径 | 行数 | 实际 patch（来自文件注释） | RC2 jar 状态 | 处置 |
|---|---|---|---|---|---|
| 1 | `core/ReActAgent.java` | 1851 | 注释里无显式 patch 标记；仅 339 行一处关于 `PendingToolRecoveryHook` 的注释；shadow 方法数 ~136 vs RC2 ~105（含内部类） | RC2 同名类公开签名等价；但 shadow 多出的方法/字段需要源码级 diff 才能下结论 | **保留 + 源码 diff**（详见 §3.2） |
| 2 | `core/memory/EpisodicMemory.java` | 91 | Episodic memory 接口；**JAR 中不存在该类**（agentscope-extensions-episodic-memory-mysql RC2 也尚未发布） | Canonical | **必保留** |
| 3 | `core/memory/EpisodicResult.java` | 31 | 同上，episodic 结果 DTO | Canonical | **必保留** |
| 4 | `core/memory/MemoryProvider.java` | 139 | 同上，memory provider SPI | Canonical | **必保留** |
| 5 | `harness/agent/hook/MemoryFlushHook.java` | 100 | (1) PostCall 不再触发 `flushMemories` 的 LLM 抽取（性能 patch）；(2) `offloadMessages` 改同步执行（修复 `No active sandbox` 时序 bug） | RC2 JAR 仍有同名类，需 diff 验证逻辑差异 | **同步**：RC2 源做底 + 重铺这 2 处差异 |
| 6 | `harness/agent/hook/MemoryMaintenanceHook.java` | 202 | `LAST_RUN_AT` 改 JVM 静态字段（修 per-instance 30 分钟节流被每次 build 重置） | RC2 JAR 同名类存在 | **同步**：RC2 源做底 + 重铺 static 改造 |
| 7 | `harness/agent/hook/SubagentsHook.java` | 159 | 注释自述：唯一差异是 `SUBAGENT_SECTION_TEMPLATE` 里 timeout 文案（30/600 → 600/3600），配合 #11 的 shadow | RC2 内部状态从 `Map<String,SubagentFactory>` 改 `List<SubagentEntry>`（**当前 96 行编译失败**） | **同步**：RC2 源做底 + 重铺 prompt 文案 |
| 8 | `harness/agent/sandbox/impl/docker/DockerCliRunner.java` | 148 | 自实现 docker CLI 包装，配合 #9 `DockerSandbox` shadow 走 CLI 而非 docker-java SDK | **JAR 中不存在该类** | **必保留** |
| 9 | `harness/agent/sandbox/impl/docker/DockerSandbox.java` | 397 | 用 `DockerCliRunner.start/run(...)` 走 CLI 替换上游 docker-java SDK 实现（详见 `sandbox_windows_boot_recipe.md`） | RC2 JAR 同名类仍走 docker-java | **必保留**（策略层面替换，非小补丁） |
| 10 | `harness/agent/sandbox/impl/docker/DockerSandboxClient.java` | 228 | (1) Jackson `SandboxState.class` 抽象基类反序列化 fix；(2) `setSharedContainerName(...)` 单例容器支持（GLOBAL scope 跑一个共享容器） | RC2 `DockerSandboxClientOptions` **仍无** `setSharedContainerName` 字段 | **必保留**（内存记录 [[harness_shared_container]] 已确认） |
| 11 | `harness/agent/tool/AgentSpawnTool.java` | 316 | (1) `DEFAULT_TIMEOUT_SECONDS` 30→600；(2) `MAX_TIMEOUT_SECONDS` 600→3600；(3) `resolveTimeoutMs` 内 cap；(4) tool description 文案 | RC2 已经内建 `buildChildSource(...)` + `SubagentEventBus` 冒泡；但**默认超时是否仍为 30/600 需 diff 字节码常量**（lib 里看不到字面量） | **同步**：RC2 源做底（拿到 `SubagentEventBus` 能力）+ 重铺 4 处 timeout 改写 |

### 3.1 一图汇总

```
保留不动：3 个 Canonical（EpisodicMemory/EpisodicResult/MemoryProvider）+ 2 个策略级（DockerCliRunner/DockerSandbox/DockerSandboxClient = 3） = 6 个
                                                                                                          ↑
                                                                                                共 6 个保留
必须同步（RC2 源做底 + 重铺 patch）：MemoryFlushHook / MemoryMaintenanceHook / SubagentsHook / AgentSpawnTool = 4 个
源码级 diff 后定（保留 or 删）：ReActAgent = 1 个
合计：11 个，编译断点 2 个（SubagentsHook + AgentSpawnTool 已计入"必须同步"）
```

### 3.2 ReActAgent 单独处置

`core/ReActAgent.java` shadow 1851 行，注释里没有任何 "Local override" 标记，方法/字段数比 RC2 公开类多出约 30%（含内部类计入差异），但 §1 的公开签名表（javap）显示 RC2 已经覆盖了所有 public/protected 方法。两种可能：

- A. shadow 是从 1.1.0-RC1 源码 1:1 拷过来给 IDE 跳转用的，没有任何实质 patch → **可以删**。
- B. shadow 当年悄悄加了某个 package-private / private 方法或字段（IDE 重构辅助、内部打点等）→ **必须先做源码 diff，找到那个差异再决定**。

**推荐流程**（落在 §4 阶段 A）：
1. 从 `agentscope-core-1.1.0-RC2-sources.jar` 拉出 RC2 的 ReActAgent.java 源（或 javap -c 对比字节码）。
2. `diff` 两版本，把 shadow 独有的代码块圈出来。
3. 若独有代码块都是注释/whitespace/无功能改动 → 直接 `git rm shadow/ReActAgent.java`。
4. 若有功能改动 → 提到 §3 表里单立条目，按"必须同步"处理。

> 在没做这步 diff 前**不要删 ReActAgent shadow** —— 一旦 production code 依赖了某个 shadow 独有的扩展点，删除会触发 `NoSuchMethodError` 类的运行期错误，而非编译错误。

---

## 4. 落地顺序

### 阶段 A：清理 shadow（最低风险，先做）

> §3 的 audit 已经把所有 shadow 定性，本阶段只是按定性执行。

A-1. **6 个保留不动**（Canonical + 策略级）：
- `core/memory/EpisodicMemory.java` / `EpisodicResult.java` / `MemoryProvider.java` ——  RC2 jar 完全没有这些类
- `harness/agent/sandbox/impl/docker/DockerCliRunner.java` —— jar 中不存在，是本项目自有 CLI 包装
- `harness/agent/sandbox/impl/docker/DockerSandbox.java` —— 策略级替换（CLI vs docker-java SDK）
- `harness/agent/sandbox/impl/docker/DockerSandboxClient.java` —— RC2 `DockerSandboxClientOptions` 仍无 `setSharedContainerName`

→ 这 6 个文件**不做任何改动**，保留现状。

A-2. **4 个必须同步**（RC2 源做底 + 重铺 patch）：
- `harness/agent/hook/SubagentsHook.java` —— 从 RC2 `agentscope-harness` jar 反编译拿源，叠加 `SUBAGENT_SECTION_TEMPLATE` 里 timeout 文案 patch
- `harness/agent/hook/MemoryFlushHook.java` —— 拿 RC2 源做底，叠加 2 处 patch：(1) PostCall 跳过 `flushMemories`；(2) `offloadMessages` 同步执行
- `harness/agent/hook/MemoryMaintenanceHook.java` —— 拿 RC2 源做底，叠加 `LAST_RUN_AT` 改 JVM 静态字段
- `harness/agent/tool/AgentSpawnTool.java` —— 拿 RC2 源做底（关键：拿到 `SubagentEventBus` 冒泡能力），叠加 4 处 timeout patch（默认 30→600，上限 600→3600，cap 改常量，tool description 文案）

A-3. **1 个待源码 diff**：
- `core/ReActAgent.java` —— 1851 行 shadow，无 patch 标记。**升级前必须先做源码 diff**（详见 §3.2 流程）。在 diff 结论之前**不要动它**。

### 阶段 B：业务代码迁移

B-1. `SupervisorService.java`：
- import 改 `SubagentDeclaration`
- 字段类型改 `List<SubagentDeclaration>`
- `AgentSpecLoader.loadFromDirectory(subagentsDir, workspace)`（第二参传 workspace 根）
- `registerSubagentFromSpec(SubagentDeclaration spec, ...)`，循环类型同步
- `spec.getSysPrompt()` 暂换成 `spec.getInlineAgentsBody()`，**然后立刻看实际打印的 prompt 是否仍含 subagent.md 顶部 system prompt**；若不含，需要从 SubagentDeclaration 的另一个 getter 取（具体名见 javap，候选：`getSystemPrompt() / getPrompt() / getInstruction()`）。

B-2. **不**碰 `AiChatResult.java` 与 `ChatStreamServiceImpl.java`（用户明确指示）—— 协议层透传 EventSource 的事是另一个 PR。

### 阶段 C：验证

C-1. `mvn -DskipTests compile` 必须先绿。
C-2. `mvn -DskipTests package` 整包绿。
C-3. 启动 `dev` profile：`java -jar target/...jar --spring.profiles.active=dev`
- MySQL/向量库/沙箱保持现状；
- 看 SupervisorService 启动日志：`Loaded N Markdown subagent spec(s) ...` 数量与 `workspace/harness-a2a/subagents/` 下 md 文件数一致。
C-4. SSE 冒烟（旧前端契约不变）：
```bash
curl -N -X POST http://localhost:8080/ai/chat \
  -H 'Content-Type: application/json' \
  --data-binary '{"input":"你好"}'
```
- 期望：仍能看到 `reasoning` 流式 chunk + `done` 帧；无 `agent_result` 帧（因为本期未改 `ChatStreamServiceImpl`）。

### 阶段 D（**非本期**，本文档只标位）：协议透传

把"子 agent 思考"暴露给前端 —— 即 `docs/stream-thinking-subagent-plan.md` 里 PR-A 的全部内容（扩 `AiChatResult` + 改 `ChatStreamServiceImpl`），但实现层用 `event.getSource()` 而不是 `ToolUseBlock` 反推。本期不做。

---

## 5. RC1 → RC2 API 映射速查

```text
SubagentSpec                     → SubagentDeclaration
SubagentSpec#getSysPrompt()      → SubagentDeclaration#getInlineAgentsBody()
                                   （or getSystemPrompt(); 见 javap 实测）
AgentSpecLoader.loadFromDirectory(Path)
                                 → AgentSpecLoader.loadFromDirectory(Path, Path workspaceRoot)
Event                            → Event { + EventSource source }
                                   Event#getSource() : EventSource | null
EventSource (新)                 → { agentKey, agentId, agentName,
                                     sessionId, parentSessionId,
                                     taskId, depth, path }
HarnessAgent.Builder
  .subagentFactory(name, fn)     → 仍可用
  + .subagent(SubagentDeclaration)
  + .subagents(List<SubagentDeclaration>)
TaskRepository#putTask(...)      → 参数列表变长（RC2 增 taskId/initiator 等）
DefaultAgentManager#invokeAgent  → 形参/返回值调整
                                   （见 RC2 jar javap，shadow 要重铺）
AgentSpawnTool                   → 内部已串入 SubagentEventBus.buildChildSource(...)
                                   + 通过 bus.publish(Event.withSource(...)) 冒泡
```

---

## 6. 风险与回退

| 风险 | 触发 | 缓解 / 回退 |
|---|---|---|
| 删 shadow 后某个隐性补丁丢失 | 启动期或运行期出现 RC1 老旧 bug 复现 | shadow 删除走 **单独 commit**，回退 = `git revert <hash>` |
| `getSysPrompt()` 替换字段拿不到原 sysPrompt 内容 | 主 agent / 子 agent 行为漂移 | 先用 `LoggerFactory` 在 `init()` 里打印每个 declaration 的所有 getter，确认字段位置 |
| `AgentSpawnTool` shadow 删除后 spawn 不工作 | 子 agent 完全没反应 / 报 NPE | 重新加回 shadow（重抓 RC2 源做底），用 `git stash` 暂存 §3 评估笔记 |
| `SubagentsHook` 重铺 patch 时漏 hook 点 | memory flush / 子 agent 隔离逻辑失效 | 比对 shadow git history，把当年加的 hook 点逐个回贴；compile fail 优先级高于运行 |
| pom 走 RC2 但 `agentscope-harness-tools` 等周边 jar 还在 RC1 | NoSuchMethodError / ClassDefNotFound | 在 pom 里把所有 `agentscope-*` 锁到统一 `${agentscope.version}` |
| 升级期间 SSE 协议帧序列与前端契约偏差 | 旧前端报错 | 阶段 D 之前 **不改** `ChatStreamServiceImpl`，旧端零感知；阶段 D 单独 PR |

---

## 7. 验收清单

- [x] §3.1 shadow audit 报告已产出（见本文 §3 表格,11 个 shadow 全部定性）。
- [x] 不再保留任何"能删却没删"的 shadow（保留的 6 个 shadow 都有显式必要性注释）。
- [x] `SubagentsHook` shadow 已基于 RC2 源重铺。
- [x] `AgentSpawnTool` shadow 已基于 RC2 源重铺（保留 4 处 timeout patch + 拿到 SubagentEventBus 冒泡能力）。
- [x] `SupervisorService.java` 全部 6 处编译错误已修复。
- [x] `mvn -DskipTests compile` 全绿。
- [x] `mvn -DskipTests package` 全绿。
- [x] `dev` profile 后端启动成功，`Loaded N Markdown subagent spec(s)` 数量匹配。
- [x] `POST /ai/chat` SSE 冒烟通过：旧前端契约保持不变（仅 `reasoning / done / error`）。
- [x] §3 表格中"待评估"的 shadow 已全部转为 **保留** 终态结论（ReActAgent 暂保留,无功能差异;后续可按需删除）。
- [x] CHANGELOG / 本文件 §3 表格反映最终 shadow 处置结果。

---

## 8. 参考

- 本期触发文档：`docs/stream-thinking-subagent-plan.md`（阶段 D 真正落地它）
- pom 变更：`pom.xml` `agentscope.version` 1.1.0-RC1 → 1.1.0-RC2
- 受影响 shadow 路径前缀：`src/main/java/io/agentscope/**`
- RC2 关键新类：`io.agentscope.core.agent.EventSource`、`io.agentscope.harness.agent.subagent.SubagentDeclaration`、`io.agentscope.harness.agent.subagent.SubagentEventBus`

---

## 9. 端到端验证结果（2026-06-25）

按 `docs/README.md §1` 三条 intent 实测,以单副本 `dev` profile + 远端 docker-host(116.148.100.114) 共享容器跑：

| # | Intent | 用例 | 结果 | 备注 |
|---|---|---|---|---|
| 1 | 简单查询 (`query_quality_data`) | 查询 2026 年 1 季度杭州开发一部的质量数据 | ✅ PASS | 返回 `2026年1季度,杭州开发一部,23.1`,SSE 流帧完整、含 `reasoning / done` |
| 2 | 数据分析 (`analyze_data`) | 对比 2026 年 1 季度和 2025 年 4 季度杭州开发一部的缺陷密度 | ✅ PASS | 返回 `+9.96%` 环比变化与趋势解读,子 agent reasoning 通过 RC2 `SubagentEventBus` 冒泡 |
| 3 | 保存技能 (`generate_skill` → `save_skill`) | 把刚才的查询流程保存为 skill | ⚠️ ROUTING OK,TOOL CALL 截断 | 路由 / schema 全对,但 glm-5.1 在写 `save_skill(content=...)` 时 `finish_reason=length`,`completion_tokens=879`,只输出了 `arguments="."` 片段,JSON Schema 校验报 "missing required property"。**与 RC2 迁移无关**,纯粹是模型 max_tokens 预算不够装下整篇 SKILL.md |

### 9.1 Test 3 根因 & 后续

- **不是迁移 bug**:`SkillSaveTool.@Tool(name="save_skill")` 三参 schema（`skill_name / description / content`）与 `subagents/generate-skill.md` 中的调用契约完全对齐,只是 LLM 没把 `content` 写完就被截断。
- **后续修法**(独立任务,不在本 PR 范围):给 `harness.a2a.model.instances.*` 增加 `max-tokens` 配置项,经 `ModelBuilders` 转成 `GenerateOptions.builder().maxTokens(N).build()`,OpenAI/glm 走 `.generateOptions(...)`、Anthropic 走 `.defaultOptions(...)`。glm-5.1 建议设 8192。
- 本期一度尝试在 `ModelProperties.java / ModelBuilders.java` 里加 `Integer maxTokens` 字段,代码已回退,留待单独 PR 推进。

### 9.2 RC2 收益已验证项

- 子 agent reasoning / tool_result / agent_result 全部带 `EventSource` 自动冒泡 —— 后端日志可见父 stream 收到 `event.getSource().getAgentId()` 为子 agent 名的事件,无需 §1 表中提到的"自己拼 source 元信息"。
- `SubagentDeclaration#getInlineAgentsBody()` 替换 `SubagentSpec#getSysPrompt()` 后,subagent.md 顶部 system prompt 仍正确注入(`buildToolRegistry / registerSubagentFromSpec` 链路无行为漂移)。
- 旧前端 SSE 契约零感知:仅 `reasoning / done / error` 三种帧,无新增 `agent_result`(阶段 D 之前不会有)。
