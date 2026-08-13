# Skills 自优化方案 - 项目信息汇总

> 目的：为 skills 自优化设计提供完整的项目背景。8 个维度（Agent 架构 / Skill 格式 / Skill 加载 / Tool / 执行流程 / Trace / 评价 / 真实失败案例）逐项展开，配套关键文件路径与配置项。
> 分支：`upgrade/2.0.0-RC5-dual-track`（agentscope-java 2.0.0-RC5 + Spring Boot 3.2.3 + JDK 21）
> 工程根：`D:/AILLMS/javacode/analysis-project/analysis-project`
> 调研时间：2026/08/12

---

## ① Agent 架构

### 1.1 三层结构：HarnessAgent → Subagent → Tool

| 层 | 实现类 | 路径 | 角色 |
|---|---|---|---|
| 主 Agent | `HarnessA2aRunnerV2` | `v2/runner/HarnessA2aRunnerV2.java` | per-request 构建 `HarnessAgent`（QualitySupervisorV2），实现 `AgentRunner` 接口 |
| 子 Agent 注册 | `SubagentRegistrar` | `v2/runner/SubagentRegistrar.java` | 启动时扫 `workspace/agent-subagents/*.md`，按 spec 注册 `subagentFactory` |
| 子 Agent | `analyze_data` / `generate_skill` | `workspace/agent-subagents/*.md` | 每个 spec 含 `name / description / tools / maxIters`，由 `SubagentRegistrar` 在 builder 上挂 factory |
| 框架底座 | `HarnessAgent.Builder` | `io.agentscope.harness.agent.HarnessAgent`（JAR） | 提供 model / workspace / toolkit / hook / middleware / memory / stateStore / filesystem / skillRepository 等组装点 |

### 1.2 主 Agent 装配要点（`HarnessA2aRunnerV2.buildAgent(ctx)`）

- **per-request 架构**：每次 `streamEvents` 调用都新建 agent，无共享实例，避免并发污染（`HarnessA2aRunnerV2.java:134-149`）
- **模型**：`FallbackModelDecorator`（用户模型 → 默认模型降级），Memory 用独立的 `light-classifier` 小模型（不烧主模型 token 做摘要）
- **SkillRepository**：`new DatabaseSkillRepository(skillMapper, userId, skillFileBaseDir)` —— 按 `owner_user_id` 隔离，从 `skill_manage` 表加载
- **StateStore**：`SanitizingAgentStateStore(MysqlAgentStateStore(dataSource, true))` —— 包装层把 `/` 替换成 `_`，避免 MysqlAgentStateStore 拒绝含 `/` 的 sessionId
- **Memory**：`consolidationMinGap=365d`（关闭 LLM consolidation 省时）+ `flushTrigger=never()`（关闭框架 per-call flush，per-user 隔离由 `PerUserMemoryContextMiddleware` + `MemoryLedgerMirrorMiddleware` 接管）
- **Compaction**：`triggerMessages=20 / keepMessages=8`（针对 32K 窗口的 glm-5.2 提早触发摘要）
- **禁用的框架工具**：`.disableFilesystemTools() / .disableShellTool() / .disableMemoryTools()`，build 后再 `removeTool("session_search"/"session_list"/"session_save")` —— 防止 LLM 浪费 token 探查
- **memory_get 替换**：`PerUserMemoryGetTool` 替换框架 `MemoryGetTool`（避免读共享根 `MEMORY.md` 跨租户串扰）
- **Plan Mode**：主 agent 已关闭（supervisor 是纯路由器），原计划挂在 `analyze_data` 子 agent 也于 2026/07/25 回退（LLM 卡 `plan_enter` 循环 + HITL ASK 阻塞）

### 1.3 子 Agent 装配要点（`SubagentRegistrar.registerSubagentFromSpec`）

每个子 agent 拿到独立 `Toolkit`，仅含 spec 声明的工具（`SubagentRegistrar.java:286-475`）：

```
analyze_data   tools: [tool_router, python_exec, arith, sql_list, sql_registry_exec, script_list, script_exec]   maxIters: 30
generate_skill tools: [skill_save]                                                                                  maxIters: 3
```

子 agent **必须挂的 hook/middleware 链**（与 v1 `SupervisorService:562-569` 对齐，否则 router_tool 表格结果不会落 artifact / python_exec 越权读别用户文件）：

- `ArtifactHandoffHook`（priority 12，PostActingEvent）—— 把 router_tool 表格结果改写成 CSV 引用，让 python_exec 用 `pd.read_csv(handle)` 而不是粘 markdown
- `ArtifactAccessMiddleware` —— 跨租户路径守卫，相对路径重写到 `/workspace/artifacts/<user>/<task>/`
- `PythonExecAccessMiddleware` —— 仅声明 `python_exec` 的子 agent 挂
- `PythonExecRetryHook` —— 仅声明 `python_exec` 的子 agent 挂
- `L2EventCollectorHook`（V3.0）—— 收 L2 工具事件入 `VerificationContext`
- `ToolCallTrackingHook`（priority 45）—— 工具入参入 `ToolCallCollector`，前端展示用
- `AiChatRestToolCallTrackingToDbHook`（priority 47）—— 完整 payload 入 `TraceSession`
- `ToolResultTruncationMiddleware` —— 压缩已消费的 tool result（如 SKILL.md 全文），减 LLM context bloat
- `SubagentEventForwardingMiddleware` —— 把子 agent AgentEvent 镜像到父 SSE 流（目前注释掉，见 `SubagentRegistrar.java:385`）

### 1.4 Manager / 协调机制

- **没有显式 Manager 类**：`HarnessA2aRunnerV2` 同时承担 runner + builder + 装配器三职
- **子 agent 调用**：通过框架 `agent_spawn` 元工具（JAR 自带），`SubagentRegistrar` 注册的 factory 在 spawn 时构建子 agent
- **跨 agent 状态共享**：通过 `RuntimeContext` 传递（`ParentEmitterCarrier` / `ArtifactContext` / `ToolCallCollector` / `VerificationContext` / `TraceSession` 都放 ctx，`AgentSpawnTool.execLocalSync` 用 `RuntimeContext.builder(ctx).from(ctx)` 克隆到子 agent）
- **artifact 桶**：主 agent 的 `ArtifactContext.from(ctx)` pin 在 RuntimeContext，子 agent 用同一桶（避免子 agent 拿 `sub-xxx` sessionId 写到独立桶）

### 1.5 关键 Hook / Middleware 一览

| 类型 | 类 | priority | 触发点 | 作用 |
|---|---|---|---|---|
| Hook | `SkillSynthesisHook` | 50 | PreCall | cache-MISS 路径 → `bumpAndMaybeSynthesize` 异步蒸馏 |
| Hook | `SkillEvolutionHook` | - | PreCall + PostCall | 跨轮 rejection + 本轮 python_exec 失败 → `recordFailure` |
| Hook | `VerificationHook` | 46 | Pre/PostActing + PostCall | V3.0 闭环验证，触发 `VerifyLoopOrchestrator` |
| Hook | `ArtifactHandoffHook` | 12 | PostActing | router_tool 表格 → CSV artifact + 短 handoff msg |
| Hook | `ToolCallTrackingHook` | 45 | Pre/PostActing | 工具入参入 collector |
| Hook | `AiChatRestToolCallTrackingToDbHook` | 47 | 全 Hook 事件 | 完整 payload 入 TraceSession |
| Hook | `ArithMentalMathDetectorHook` | - | PostReasoning | 检测 LLM 心算（应在 `arith_tool_design_philosophy` 语境） |
| Hook | `PythonExecRetryHook` | - | PostActing | python_exec 失败重试，加 `✦ 失败行 / ✦ 异常类别 / ✦ 常见修法` |
| Hook | `KnowledgeRetrievalHook` | - | PreReasoning | 检索 knowledge/ 动态知识 |
| Middleware | `MemoryLedgerMirrorMiddleware` | - | onAgent | 捕获响应入 `agent_memory_ledger` + 本地 daily .md |
| Middleware | `EpisodicRetrievalMiddleware` | - | onAgent | 检索 episodic_memory（带 user_id 过滤） |
| Middleware | `PerUserMemoryContextMiddleware` | - | onAgent | 注入 per-user MEMORY.md 到 system prompt |
| Middleware | `ArtifactAccessMiddleware` | - | onActing | 路径越权守卫 |
| Middleware | `DimensionStateMiddleware` | - | onAgent | 维度状态跨轮继承（有 bug，见 memory `dimension_state_persistence_gap`） |
| Middleware | `ResponseCacheMiddleware` | - | onAgent | 缓存命中短路（HIT 路径已废弃，见 `response_cache_deprecated`） |
| Middleware | `ToolResultTruncationMiddleware` | - | onAgent | 压缩已消费 tool result |
| Middleware | `ToolCallContentRepairMiddleware` | - | onAgent | 修工具调用内容 |
| Middleware | `SessionMiddleware` | - | onAgent | session 状态 |
| Middleware | `SubagentEventForwardingMiddleware` | - | onReasoning/onModelCall/onActing | 子 agent 事件镜像到父 SSE |
| Middleware | `PythonExecAccessMiddleware` | - | onActing | python_exec 路径守卫 |

---

## ② Skill 格式

### 2.1 物理形式：Markdown + YAML frontmatter

```markdown
---
name: <skill_name>              # 必填，英文小写+下划线，与目录名一致
description: "<一句话中文>"      # 必填，≤80 字，LLM 路由判断依据
---

# <技能中文名>
## 适用场景
...
## 工作流
1. ...
2. ...
## 注意事项
- ...
```

- **目录结构**：`workspace/skills/<skill_name>/SKILL.md` + 可选附件（`.py` / `.sql`，由 `skill_file_reference` 表关联）
- **三类 skill 目录**：
  - `workspace/skills/` —— builtin skill（`source='auto_synthesized'`），由开发预录入
  - `workspace/skills-user/` —— 用户生成 skill（`source='user_generated'`），由 `SkillSaveTool` 写
  - `workspace/skills-auto/` —— 自动合成 skill（W2/W3/W4/W5 演化路径，`source='auto_synthesized'`）
- **共享硬规则**：`workspace/skills/_common/SKILL.md` 由 `SubagentRegistrar.loadCommonRules` 启动时读，prepend 到每个子 agent sysPrompt，避免每个 `*_metrics` skill 重复 CSV 路径 / arith / 空结果 / 直接调用规则

### 2.2 Skill 实体（Java 侧）

| 类 | 路径 | 说明 |
|---|---|---|
| `AgentSkill` | `io.agentscope.core.skill.AgentSkill`（JAR） | 框架统一接口，含 name / description / skillContent / source / resources |
| `Skill` | `v2/skillManager/entity/Skill.java` | `skill_manage` 表实体（id / name / retrievalName / description / content / ownerUserId / status） |
| `SkillEntry` | `v2/skills/SkillEntry.java` | `skill_index` 表实体（name / fingerprint / version / usage_count / success_count / failure_count / source） |
| `SkillFile` / `SkillFileReference` | `v2/skillManager/entity/` | skill 附件文件管理 |
| `SkillDraft` / `SkillApproval` / `SkillApprover` / `SkillPublish` / `SkillVersionHistory` / `SkillOperationHistory` / `SkillLike` / `SkillUserDisable` | `v2/skillManager/entity/` | skill 管理流（草稿 / 审批 / 发布 / 版本 / 操作历史 / 点赞 / 用户禁用） |

### 2.3 Skill 在 DB 中的两套表

| 表 | 用途 | 写入方 |
|---|---|---|
| `skill_manage` | 用户可见的 skill 库（管理 UI 用），按 `owner_user_id` 隔离 | `DatabaseSkillRepository.save` / `SkillManageService` |
| `skill_index` | skill 元数据 + 使用统计（version / usage_count / success_count / failure_count / source / fingerprint） | `SkillIndexRepository.upsertOnSave` / `BuiltinSkillRegistrar` / `SkillEvolutionRunner` |
| `skill_candidate` | cache-MISS 蒸馏候选（fingerprint / hit_count / metric_tag） | `SkillSynthesisRunner.bumpAndMaybeSynthesize` |
| `skill_pending_judgement` | 跨轮 rejection 缓存（session_key → skills_json + exemplar_question） | `SkillEvolutionRunner.cachePendingJudgement` |
| `skill_file` / `skill_file_reference` | skill 附件文件（按 userId 隔离） | `SkillFileService` |
| `skill_job` / `skill_job_execution` / `skill_dependency_metric` | 定时任务（指标触发批跑） | `SkillJobScheduler` |

### 2.4 SKILL.md 压缩规则（`ToolResultTruncationMiddleware`）

LLM 多轮 ReAct 中，`load_skill_through_path` 返回的 SKILL.md 第一轮看全文，后续轮次被压缩：

| 元素 | 保留 | 说明 |
|---|---|---|
| Frontmatter `---` | ✅ | name / description 元数据 |
| 代码块 ` ``` ` | ✅ | 工具调用示例、python_exec 模板，逐字保留 |
| 表格行 `\| ... \|` | ✅ | 字段映射表、枚举值 |
| 标题 `#` | ✅ | 章节结构 |
| 无序列表 `- ` | ✅ | **硬规则必须用列表** |
| 有序列表 `1.` | ✅ | 工作流步骤 |
| 段落 / 引用块 `>` | ❌ | 描述性文字、引用块一律丢弃 |

> ⚠️ 写 SKILL.md 时硬规则必须用 `-` bullet，不能写在 `>` 引用块里，否则后续轮次 LLM 看不到。详见 `docs/table-mertics/skill-format-guide.md`。

### 2.5 现有 builtin skill 清单

```
workspace/skills/
├── _common/                            # 共享硬规则（SubagentRegistrar 注入）
├── data_primitives/                    # data_aggregate / data_top_n / data_compare_ratio / data_pivot / data_distribution 工具索引
├── tool_index/                         # quality_query_by_* 工具索引（路由 B 兜底）
├── q2_1_by_dept_version_metrics/       # script_exec 验证实例（q2_1_metrics_by_dept_version 脚本）
├── q2_1_metrics_download_demo/         # 下载链接生成 demo
├── trace_recent_metrics/               # wide_table_query 老路径
├── trace_recent_stats_metrics/         # wide_table_query 统计路径
└── wide_table_q2_1_metrics/            # wide_table_query Q2-1 实例
```

---

## ③ Skill 加载

### 3.1 三条加载路径

```
┌──────────────────────────────────────────────────────────────────┐
│  Boot 期: BuiltinSkillRegistrar (CommandLineRunner, @Order(0))   │
│  扫 workspace/skills/*/SKILL.md → parseFrontmatter →             │
│  INSERT skill_index (source='auto_synthesized')                  │
│  幂等：findByName 命中则 skip                                     │
└──────────────────────────────────────────────────────────────────┘
                            ↓
┌──────────────────────────────────────────────────────────────────┐
│  Session 期: JAR WorkspaceContextHook                            │
│  读 workspace/skills/ 注入 system prompt（builtin skill 列表）   │
│  ⚠️ Hot-reload: 改/删 SKILL.md 后须重启 JVM（见 skills-热加载方案）│
└──────────────────────────────────────────────────────────────────┘
                            ↓
┌──────────────────────────────────────────────────────────────────┐
│  请求期: LLM 显式调 load_skill_through_path(name="<skill_name>") │
│  → DatabaseSkillRepository.getSkill(name)                        │
│    1. selectByRetrievalNameAndOwner(name, userId)  快速路径      │
│    2. 未命中 → selectByRetrievalNameAccessibleByUser(name, userId)│
│       (含维度发布范围)                                            │
│  → AgentSkill.builder().name(...).skillContent(...).resources(...)│
│  → 返回 SKILL.md 全文 + 附件资源 (.py / .sql 内容)               │
└──────────────────────────────────────────────────────────────────┘
```

### 3.2 关键类

| 类 | 路径 | 角色 |
|---|---|---|
| `BuiltinSkillRegistrar` | `v2/skills/BuiltinSkillRegistrar.java` | Boot 期扫盘 + INSERT skill_index |
| `DatabaseSkillRepository` | `v2/skills/DatabaseSkillRepository.java` | `AgentSkillRepository` 实现，从 `skill_manage` 表加载（per-user） |
| `SkillIndexRepository` | `v2/skills/SkillIndexRepository.java` | `skill_index` 表 DAO（GaussDB），含 `upsertOnSave` / `upsertFingerprint` / `bumpUsage` / `recordSuccess` / `recordFailure` |
| `SkillSaveTool` | `v2/tools/SkillSaveTool.java` | `@Tool save_skill(skill_name, description, content)`，写文件 + upsert skill_index + sync skill_manage |
| `SkillManageService` / `SkillFileService` | `v2/skillManager/service/` | 管理 UI 用的 CRUD + 附件管理 |
| `SkillMapper` | `v2/skillManager/mapper/SkillMapper.java` | MyBatis mapper，含 `selectByRetrievalNameAndOwner` / `selectByRetrievalNameAccessibleByUser` / `selectActiveByUser` 等 |
| `SkillCuratorConfig` / `SkillVisibilityFilter` / `LocalApprovalGate` / `CompositeFilter` | JAR + v2 config | skill 可见性过滤（visibility filter 当前 pass-through，见 memory `visibility_filter_passthrough_hot_load`） |

### 3.3 Skill 选择逻辑（LLM 端，见 `workspace/AGENTS.md`）

主 agent（Supervisor）的路由决策：

```
1. 含分析意图关键词 (分析/趋势/对比/分布/归因/标准差/分位数/相关系数/同比/环比/改进建议/报告/探索式分析)
   -> agent_spawn(analyze_data)
2. 简单指标查数 -> Supervisor 直跑:
   Step 1 - 优先找用户自定义 skill (load_skill_through_path 试语义匹配名)
   Step 2 - 找不到匹配 -> 走接口封装 skill (xxx_tool_index)
3. 生成下载链接 -> 下载链接生成专章
4. 接口查询 / 通用查数 -> 路径 B (router_tool)
5. "保存为skill" / "生成技能" -> agent_spawn(generate_skill)
```

子 agent（`analyze_data`）的路由决策：

```
查询需求复杂度?
├─ 简单指标 (Q2-1 达标率等) 有 script_exec 脚本 -> ★ script_exec (一步到位, 含百分比)
├─ 已封装接口 (quality_query_by_*) -> ★ 路径 B: router_tool
└─ 复杂聚合 / JOIN / CASE WHEN / 窗口函数
     -> ★ 路径 C: sql_list -> sql_registry_exec(sqlId, params)
```

### 3.4 检索禁用状态（重要）

```properties
# application.properties:112
harness.skills.retrieval.enabled=false    # vector retrieval 已禁用
harness.skills.retrieval.top-k=3
harness.skills.retrieval.min-cosine=0.55  # bge-large-zh-v1.5 中文短句 cosine 偏低 (0.55~0.78), 不可靠
```

- `SkillVectorIndex` + `SkillVectorIndexVisibilityFilter` + `SkillRetrievalHook` 已删除（见 memory `skill_vector_index_dead_code_removed`）
- skill 选择完全靠 LLM 显式调 `load_skill_through_path`，不依赖 embedding
- `EmbeddingClient` 保留给 episodic memory 用

### 3.5 热加载缺口

- `BuiltinSkillRegistrar` 只在 boot 跑一次，新贴 `SKILL.md` 文件没 DB 行会被 `SkillVectorIndexVisibilityFilter` 挡掉（已 pass-through，所以现在不挡了）
- 当前实际热加载由 JAR `WorkspaceContextHook` 提供：每次 session 启动时从磁盘读 `skills/`
- 删源码 skill 后 `WorkspaceMaterializer` 残留幽灵文件（单向 ADD/UPDATE 不 DELETE，见 memory `workspace_materializer_no_delete`），需手动 `rm workspace/` + 重启
- 详见 `docs/table-mertics/skills-热加载方案.md`（计划砍掉 skill_index + embedding 整层）

---

## ④ Tool 定义 / 注册 / 调用

### 4.1 Tool 定义：`@Tool` + `@ToolParam` 注解

```java
@Tool(
    name = "router_tool",                    // 工具名（LLM 看到的）
    description = "统一工具路由入口..."       // LLM 决策依据
)
@Timed(value = "router_tool.duration", ...)  // ⚠️ 会触发 CGLIB 代理，注册前须 unwrap
public Object router_tool(
    @ToolParam(
        name = "paramsJson",
        description = "JSON 格式参数,必须包含 toolId 字段..."
    ) String paramsJson
) { ... }
```

- 注解类型：`io.agentscope.core.tool.Tool` / `io.agentscope.core.tool.ToolParam`（JAR 提供）
- 参数类型由 `Method.getGenericParameterTypes()` 反射提取，`ObjectMapper.convertValue` 做 Jackson 强转
- 自动注入参数（如 `RuntimeContext`）：通过 `isAutoInjectedType` 判断（当前空实现，预留扩展）

### 4.2 Tool 注册：两条路径

#### 路径 A：ungrouped 直注册（主 agent，见 `V2ToolConfig.v2ToolGroupAdapter`）

```java
V2ToolGroupAdapter.Builder b = V2ToolGroupAdapter.builder();
b.tool(unwrapCglib(py));        // PythonExecTool
b.tool(unwrapCglib(at));        // ArithTool
b.tool(unwrapCglib(wt));        // WideTableMetricsTool (legacy-skill-only)
b.tool(unwrapCglib(ck));        // ClickHouseWideTableMetricsTool
b.tool(unwrapCglib(slt));       // SqlListTool
b.tool(unwrapCglib(sre));       // SqlRegistryExecTool
b.tool(unwrapCglib(scL));       // ScriptListTool
b.tool(unwrapCglib(scE));       // ScriptExecTool
b.tool(unwrapCglib(tri));       // ToolRoutersIndex (exposes router_tool + toolMetaInfo)
V2ToolGroupAdapter adapter = b.build();   // 不分组, 不挂 reset_equipped_tools 元工具
```

> ⚠️ **CGLIB 代理 unwrap 必须做**：`ToolRoutersIndex.router_tool` 上的 `@Timed` 触发 `TimedAspect` CGLIB 代理，`getDeclaredMethods()` 返回 synthetic bridge methods 不带 `@Tool`，工具静默不注册。`unwrapCglib` 用 `AopProxyUtils.getSingletonTarget` 解包。见 memory `router_tool_cglib_proxy_fix`。

#### 路径 B：toolRegistry 注册（子 agent，见 `SubagentRegistrar` 构造函数）

```java
toolRegistry.put("tool_router", toolRoutersIndex);
toolRegistry.put("python_exec", py);
toolRegistry.put("skill_save", ss);
toolRegistry.put("arith", at);
toolRegistry.put("sql_list", slt);
toolRegistry.put("sql_registry_exec", sre);
toolRegistry.put("script_list", sl);
toolRegistry.put("script_exec", se);
```

子 agent spec 声明 `tools: [tool_router, python_exec, arith, ...]`，`SubagentRegistrar` 按 spec.tools 从 toolRegistry 取出，调 `tk.registerTool(unwrapCglib(tool))` 注册到独立 `Toolkit`。

### 4.3 Tool 调用：两种模式

#### 模式 1：元工具路由（router_tool）

```python
router_tool(paramsJson='{"toolId":"quality_query_by_department_quarter","quarter":"2026年1季度","department":"杭州开发五部"}')
```

- `ToolRoutersIndex.router_tool` 解析 JSON → 取 `toolId` → 反射调用对应 `@Tool` 方法
- 适合：`AgentTools`（quality_query_*）+ `DataPrimitivesTool`（data_aggregate / data_top_n / data_compare_ratio / data_pivot / data_distribution）+ `CsvDownloadTool`
- `toolMetaInfo(toolId)` 可查参数元信息

#### 模式 2：直接调用（不走 router_tool）

```python
sql_registry_exec(sqlId="...", params={...})
script_exec(scriptId="q2_1_metrics_by_dept_version", params={"dept":"杭州开发二部", "version":"2026年7月份版本"})
python_exec(code="import pandas as pd\ndf = pd.read_csv('...')")
arith(op="pct", a=42, b=45)
```

- `sql_registry_exec` / `script_exec` / `python_exec` / `arith` 已直接注册在 Toolkit，跳过 `router_tool` 元工具路由
- 节省 4-5 轮 LLM 往返（见 `_common/SKILL.md` 的"直接调用"硬规则）

### 4.4 Tool 全清单

| Tool 类 | toolId / 方法名 | 注册位置 | 用途 |
|---|---|---|---|
| `AgentTools` | `quality_query_by_version_department` / `quality_query_by_department_quarter` / `quality_query_by_version_person` / `quality_query_by_quarter_person` / `agent_tools_ping` | 主+子（via ToolRoutersIndex.router_tool） | 质量查询接口 |
| `DataPrimitivesTool` | `data_aggregate` / `data_top_n` / `data_compare_ratio` / `data_pivot` / `data_distribution` | 主+子（via router_tool） | 数据处理原语（Java 端按模板拼代码） |
| `CsvDownloadTool` | `generate_csv_download_url` / `buildXxxDownLoadUrl` | 主+子（via router_tool） | CSV 下载短链 |
| `SqlListTool` | `sql_list` | 主+子（直接注册） | 列出 `sql_registry` 表中预注册 SQL |
| `SqlRegistryExecTool` | `sql_registry_exec` | 主+子（直接注册） | 按 sqlId 执行预注册 SQL（mysql/gauss/clickhouse 路由） |
| `ScriptListTool` | `script_list` | 主+子（直接注册） | 列出 `script_registry` 表中预注册 Python 脚本 |
| `ScriptExecTool` | `script_exec` | 主+子（直接注册） | 按 scriptId 执行预注册脚本（SQL 取数 + pandas 算指标一次完成） |
| `PythonExecTool` | `python_exec` | 主+子（直接注册） | 沙箱内执行任意 Python（pandas / numpy / openpyxl / matplotlib） |
| `ArithTool` | `arith` | 主+子（直接注册） | BigDecimal 加减乘除 / 百分比（禁心算） |
| `WideTableMetricsTool` | `wide_table_query` | 主+子（直接注册，legacy-skill-only） | 宽表查询（GaussDB） |
| `ClickHouseWideTableMetricsTool` | `clickhouse_query` | 主+子（直接注册，legacy-skill-only） | 宽表查询（ClickHouse） |
| `ToolRoutersIndex` | `router_tool` / `toolMetaInfo` | 主+子（直接注册） | 元工具路由 |
| `SkillSaveTool` | `save_skill` | 子 generate_skill（直接注册） | LLM 主动合成 skill |
| `PerUserMemoryGetTool` | `memory_get` | 主+子（替换框架 MemoryGetTool） | 读 per-user MEMORY.md（MysqlMemoryStore） |
| `WriteMarkdownTool` | `write_markdown` | （视配置） | 写 markdown 文件 |

### 4.5 ToolResultEviction 已禁用

```java
// HarnessA2aRunnerV2.java:279
// 2026/07/28: 禁用 JAR 内置 ToolResultEvictionMiddleware。
// wide_table_query 返回 >80K 字符 CSV 时, middleware 调
// SandboxBackedFilesystem.uploadFiles 把结果写到容器内
// /large_tool_results/..., 内部走 ssh.exe + base64 payload,
// 命令行超过 Windows CreateProcess 8KB 上限 -> error=206。
// 业务侧 ArtifactHandoffHook (priority 12, PostActingEvent)
// 已经把大表格 CSV 落到 ArtifactStore 并把 tool result 替换成
// 短 handoff 消息 (pd.read_csv(...)), eviction 在这套架构下冗余。
// Linux 部署无此问题, 可恢复。
```

---

## ⑤ 执行流程（一次用户请求从入口到最终答案）

### 5.1 端到端时序

```
┌──────────────────────────────────────────────────────────────────────────────┐
│ 1. 前端 POST /v2/ai/chat (ChatRequest: conversationId, userId, question)     │
│    V2ChatController.chat()                                                    │
│       └─ V2SessionRouter.shouldUseV2(conversationId) 灰度路由守卫             │
└──────────────────────────────────────────────────────────────────────────────┘
                                  ↓
┌──────────────────────────────────────────────────────────────────────────────┐
│ 2. V2ChatStreamServiceImpl.stream(req)                                       │
│    a. callKey = "<userId>:<conversationId>"                                  │
│    b. inFlightCalls.putIfAbsent(callKey, new InFlightCall())                 │
│       (同会话并发请求直接 429 TooManyRequests)                                │
│    c. new ToolCallCollector(text)                                            │
│    d. buildRuntimeContext(conversationId, userId, text)                      │
│    e. new TraceSession(...) → ctx.put(TraceSession.KEY, traceCtx)            │
│    f. ctx.put(ToolCallTrackingHook.COLLECTOR_CTX_KEY, collector)             │
│    g. new VerificationContext(...) → ctx.put(VERIFY_CTX_KEY, verificationCtx)│
│    h. new ParentEmitterCarrier() → ctx.put(ParentEmitterCarrier.class, ...)  │
│    i. ctx.put(ToolCallTrackingHook.EMITTER_CTX_KEY, emitter)                 │
│    j. mainArtifactCtx = ArtifactContext.from(ctx) → ctx.put(...)             │
│    k. episodicSessionId = "user:<userId>:<conversationId>"                   │
│    l. SseEmitter emitter = new SseEmitter(600_000L)  // 10 min               │
│    m. cleanup = buildCleanup(streamCtx, callKey, inFlight)                   │
│    n. emitter.onCompletion/onTimeout/onError → cleanup (幂等)                │
│    o. Mono.fromRunnable(...).subscribeOn(boundedElastic()).subscribe()       │
└──────────────────────────────────────────────────────────────────────────────┘
                                  ↓
┌──────────────────────────────────────────────────────────────────────────────┐
│ 3. runner.streamEvents(List.of(userMsg), ctx)                                │
│    HarnessA2aRunnerV2.buildAgent(ctx):                                       │
│       - modelProvider.getModelForUser(userId)  // FallbackModelDecorator     │
│       - new DatabaseSkillRepository(skillMapper, userId, skillFileBaseDir)   │
│       - SanitizingAgentStateStore(MysqlAgentStateStore(dataSource, true))    │
│       - MemoryConfig: consolidationMinGap=365d, flushTrigger=never()         │
│       - CompactionConfig: trigger=20, keep=8                                 │
│       - .disableFilesystemTools().disableShellTool().disableMemoryTools()    │
│       - .middlewares(middlewares)                                            │
│       - .filesystem(sandboxFilesystem or remoteFilesystem)                   │
│       - .hook(hook) for each hook                                            │
│       - .toolkit(toolGroupAdapter.getToolkit())                              │
│       - subagentRegistrar.registerAll(builder, ...)                          │
│       - agent = builder.build()                                              │
│       - post-build: removeTool session_search/list/save                      │
│       - replaceTool memory_get → PerUserMemoryGetTool                        │
│    agent.streamEvents(messages, ctx) → Flux<AgentEvent>                      │
└──────────────────────────────────────────────────────────────────────────────┘
                                  ↓
┌──────────────────────────────────────────────────────────────────────────────┐
│ 4. 事件流订阅 (boundedElastic 调度)                                          │
│    eventFlux.subscribe(                                                      │
│       event -> processChunk(event, streamCtx, strategy),                     │
│       error -> handleStreamError(streamCtx, error, strategy),                │
│       () -> handleStreamSuccess(streamCtx, strategy)                         │
│    )                                                                         │
│                                                                              │
│    processChunk 分发:                                                         │
│    - AgentResultEvent    → answerContent (不重复发送, 避免双倍)              │
│    - TextBlockStartEvent → thinkContent 加 "\n" 分隔 (修多 block 粘一行 bug) │
│    - TextBlockDeltaEvent → thinkContent + sendThink (action="执行中")        │
│    - AgentStartEvent     → SSE "agent_start" (🤖 启动智能体)                 │
│    - ToolCallStartEvent  → SSE "tool_call_start" (🔧 调用工具, 带 toolInput) │
│    - ToolResultEndEvent  → SSE "tool_result_end" (✅ 完成, 带 toolOutput)    │
│    - SubagentExposedEvent → SSE "subagent_exposed"                          │
│    - ModelCallEndEvent   → traceCtx.recordUsage(mce) (token 统计)           │
│                                                                              │
│    Hook 链 (priority 升序):                                                   │
│    - SkillRetrievalHook(-50) → SkillSynthesisHook(50) → ...                 │
│    - VerificationHook(46) → ToolCallTrackingHook(45) →                       │
│      AiChatRestToolCallTrackingToDbHook(47) → ArtifactHandoffHook(12)        │
│                                                                              │
│    Middleware 链 (onAgent):                                                   │
│    - PerUserMemoryContextMiddleware → EpisodicRetrievalMiddleware →          │
│      MemoryLedgerMirrorMiddleware → ResponseCacheMiddleware →                │
│      ToolResultTruncationMiddleware → ...                                    │
└──────────────────────────────────────────────────────────────────────────────┘
                                  ↓
┌──────────────────────────────────────────────────────────────────────────────┐
│ 5. 子 agent 派单 (LLM 调 agent_spawn)                                        │
│    AgentSpawnTool.execLocalSync:                                             │
│    - 找 subagentFactory(agentId) 构建 sub-HarnessAgent                       │
│    - RuntimeContext.builder(ctx).from(ctx) 克隆父 ctx                        │
│    - sub-agent.streamEvents(...) → Flux<AgentEvent>                          │
│    - 子 agent 内部 ReAct 循环 (maxIters 控制):                                │
│      load_skill_through_path → router_tool / sql_registry_exec /             │
│      script_exec / python_exec / arith → 回复                                 │
│    - SubagentEventForwardingMiddleware 镜像事件到父 SSE                       │
│    - 子 agent 返回结果作为 agent_spawn 工具返回值, 进入父 agent 下一轮 ReAct   │
└──────────────────────────────────────────────────────────────────────────────┘
                                  ↓
┌──────────────────────────────────────────────────────────────────────────────┐
│ 6. 终止 (handleStreamSuccess / handleStreamError)                            │
│    - traceCtx.markSuccess() / markError(msg)                                 │
│    - VerificationHook.PostCall 触发 supervisor-exit verify                   │
│    - SSE "done" 事件 (AiChatResult, code=200/500)                            │
│                                                                              │
│ 7. cleanup (幂等, onCompletion/onTimeout/onError 三条终止路径都触发)         │
│    - traceAssembler.assemble(traceCtx) → traceQueue.offer                    │
│    - TraceBatchWriter.write → ClickHouse trace_event + trace_conversation    │
│    - episodicMemory.persistSession(episodicSessionId, ...)                   │
│    - verificationRecorder.persist(verificationCtx)                           │
│    - artifactStore.cleanupTask(taskBucket)  (keep-artifacts=true 时跳过)     │
│    - inFlightCalls.remove(callKey, inFlight)                                 │
│    - emitter.complete()                                                      │
└──────────────────────────────────────────────────────────────────────────────┘
```

### 5.2 关键配置项

```properties
# application.properties
harness.a2a.workspace.path=.agentscope/workspace/harness-a2a
harness.a2a.tool-execution.timeout-seconds=300
# SSE_TIMEOUT = 600_000L (10 min, 硬编码在 V2ChatStreamServiceImpl)
# agent_spawn async timeout = 30s (AsyncToolMiddleware, 见 memory async_tool_middleware_wiring)
```

### 5.3 中断 + 恢复

- **单端点设计**（见 memory `interrupt_resume_two_step_restored`）：中断按钮 POST JSON `/v2/ai/chat/interrupt`，resume 走正常 `/v2/ai/chat` 输入框发送
- `/v2/ai/chat/interrupt` 实现：`V2ChatInterruptController` → 查 `inFlightCalls.get(callKey)` → `subscription.dispose()` 强制取消 + 等待 `completion` future（30s 超时）→ 保存中断状态到 MySQL `agentscope_sessions.state_data`
- resume：`/v2/ai/chat` 启动新流，`MysqlAgentStateStore` 读取 `plan_mode_context` / `permission_context` 等恢复状态

### 5.4 跨 JVM 重启恢复

- `plan_mode_context` 持久化到 `agentscope_sessions.state_data`，跨 JVM 重启能恢复（见 memory `plan_state_cross_restart_verified`）
- 旧 JVM `taskkill /F` 后须 KILL 旧 MySQL `GET_LOCK` 连接避免 30min 阻塞（`MemoryDigestionService` 用 `GET_LOCK` 跨 JVM 互斥）

---

## ⑥ Trace 保存

### 6.1 三套并行的记录系统

| 系统 | 存储 | 用途 | 触发点 |
|---|---|---|---|
| **TraceSession** (请求级) | 内存 → ClickHouse `trace_event` / `trace_conversation` | LLM 输入/思考/输出、工具入参/返回的完整 payload | `AiChatRestToolCallTrackingToDbHook` 全 Hook 事件 |
| **EpisodicMemory** (会话级) | MySQL `QualitySupervisor_episodic_memory` | 跨会话回忆（user_id 隔离） | `V2ChatStreamServiceImpl.cleanup` 持久化 |
| **agent_memory_ledger** (用户级) | MySQL `agent_memory_ledger` | per-user 每日活动 timeline（用于 digestion 找活跃用户） | `MemoryLedgerMirrorMiddleware` concatWith |

### 6.2 TraceSession 详细采集（`AiChatRestToolCallTrackingToDbHook`）

```
priority = 47 (紧跟 VerificationHook(46), 在 ToolCallTrackingHook(45) 之后)

Hook 事件采集集 (与 JAR JsonlTraceExporter 默认集一致):
├── PRE_CALL        - LLM 输入 (system_message + input_messages)
├── POST_CALL       - LLM 最终输出
├── PRE_REASONING   - 推理前
├── POST_REASONING  - 推理后 (含 thinking)
├── PRE_ACTING      - 工具调用前 (含 toolUse)
├── POST_ACTING     - 工具调用后 (含 toolResult, 优先级确保是最终值)
└── ERROR           - 异常

每条记录序列化为 JSON, 含:
- id (UUID)
- type (Hook 事件类型)
- createdAt (ISO-8601)
- source (主 agent=null / 子 agent 名)
- payload (完整内容, 单字段截断 65536 字符保护 ClickHouse 行大小)
```

- **不发送 SSE**：本 hook 只做离线 trace 落库
- **不采集 AgentEvent delta 流**：delta 一次推理几百上千条，Hook 事件每个操作一条更紧凑
- **token 统计**：单独走 `TraceSession.recordUsage(ModelCallEndEvent)`（唯一携带 usage 的事件）

### 6.3 落库链路

```
TraceSession (CopyOnWriteArrayList<TraceEventRecord>)
        ↓ serializeEventsJson() (sealed=true, 按 createdAt 升序)
        ↓ 头部插一条 USER_INPUT 虚拟事件 (用户原始输入)
List<String> eventJsons
        ↓
TraceAssembler.assemble(traceSession)
        ↓ 组装 AssembledTrace (conversation + eventJsons)
        ↓
TraceQueue.offer (有界阻塞队列, 满时按 discardOnFull 策略)
        ↓
TraceBatchWriter.write (cleanup 时同步调用, 不再依赖定时调度)
        ↓ 失败重试 1 次, 仍失败丢弃
        ↓ 先写 trace_event (数据量大, 更易失败), 再写 trace_conversation
        ↓ ClickHouse 无事务, 先 event 后 conversation 避免"有 conversation 无 event"
ClickHouse trace_event + trace_conversation
```

### 6.4 状态机

```
RUNNING ──markSuccess()──> SUCCESS
        ├──markError(msg)──> ERROR
        └──markTimeout()──> TIMEOUT
```

`markTimeout()` 在 `emitter.onTimeout` 回调里调，先于 cleanup，确保 assemble 看到的是 TIMEOUT 终态。

### 6.5 EpisodicMemory 隔离修复（重要）

- 表加 `user_id` 列 + backfill 265 行 + WHERE 过滤 + ctx 取 userId
- 跨用户记忆串扰根因：`EpisodicRetrievalMiddleware` 全表 FTS 搜索无 user_id 过滤
- 修复后 alice/bob E2E 验证隔离生效（见 memory `episodic_memory_user_id_isolation`）

### 6.6 跨用户 MEMORY.md 串扰修复

- 根因：`WorkspaceContextMiddleware` 读 shared 根 `MEMORY.md` + `MemoryFlushMiddleware` 写 shared 根 daily `.md`
- 修复：删根文件 + `PerUserMemoryContextMiddleware` 注入 per-user + `MemoryFlushMiddleware.FlushTrigger.never()` 禁框架 flush
- `PerUserMemoryGetTool` 替换框架 `MemoryGetTool`（避免 `readWithOverride` fallback 读 shared 根）（见 memory `memory_root_file_tenant_isolation` + `memory_get_tool_per_user_isolation`）

### 6.7 L2 Trace（子 agent）

- `L2TraceReader`（`v2/digestion/L2TraceReader.java`）—— 读子 agent trace 文件
- `TraceMiner` 用 `L2_SEPARATOR=">"` 合并 L1 + L2 工具 ID 序列
- 子 agent 共享请求级 RuntimeContext，能拿到主 agent 创建的 TraceSession，`source` 字段为子 agent 名

---

## ⑦ 评价

### 7.1 评价基础设施全览

```
┌─────────────────────────────────────────────────────────────────────┐
│ 在线评价 (请求期)                                                   │
│ ──────────────────────────────────────────────────────────────── │
│ VerificationHook (priority 46)                                      │
│   ├─ subagent-exit checkpoint (agent_spawn PostActing)              │
│   ├─ per-critical-tool checkpoint (PostActing on configured tools)  │
│   └─ supervisor-exit checkpoint (PostCall)                          │
│        ↓                                                            │
│ VerifyLoopOrchestrator                                              │
│   ├─ VerifyAgentInvoker  - 调 verify 子 agent                       │
│   ├─ CriticAgentInvoker  - 调 critic 子 agent                       │
│   ├─ ContractComplianceChecker - 语义契约检查                       │
│   ├─ DeterministicChecker - 规则检查                                │
│   ├─ DecisionTraceExtractor - 决策追溯                              │
│   └─ TrustScoreCalculator - 信任分计算                              │
│        ↓                                                            │
│ VerificationContext (per-request)                                   │
│   ├─ eventStream (CopyOnWriteArrayList<AgentExecutionEvent>)        │
│   ├─ decisionTrace                                                  │
│   ├─ candidateConclusion                                            │
│   ├─ artifactRefs                                                   │
│   ├─ verdicts (List<VerificationVerdict>)                           │
│   ├─ repairHistory (List<RepairPlan>)                               │
│   └─ contractSnapshot                                               │
│        ↓                                                            │
│ VerificationRecorder - 持久化到 MySQL                               │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│ 离线评价 (定时 / 触发式)                                            │
│ ──────────────────────────────────────────────────────────────── │
│ GoldenEvaluationRunner (V3.0 §13/§25, P0)                           │
│   ├─ golden 数据集 (GoldenDatasetCase)                              │
│   ├─ 每个用例跑 5 min 超时                                          │
│   ├─ 评分: expected-answer 准确率 + verification verdict            │
│   └─ 准入门槛: GATE_ACCURACY_DROP = 0.02 (准确率下降 >2% 阻断发布)  │
│        ↓                                                            │
│ VersionRegistry - 版本管理 (agent/prompt/skill/semantic version)    │
│ QualityOptimizationLoop - 优化循环                                  │
│ TrustCalibrationService - 信任分校准                                │
│ SloMonitor - SLO 监控                                               │
│ ReplayService - 回放                                               │
│ RuleExperimentService - 规则实验 (A/B)                              │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│ 夜间 digestion (cron 0 9 21 * * *)                                  │
│ ──────────────────────────────────────────────────────────────── │
│ MemoryDigestionService                                              │
│   Phase 1: CleanLedger - 清 agent_memory_ledger >90 天              │
│   Phase 2: MineTraces - 扫 episodic_memory 当日, 提取工具序列,      │
│             分类失败 (TraceMiner.classifyTrace)                     │
│             ├─ FAILURE (1.0): python_exec non-zero exit / "error"   │
│             ├─ FAILURE (1.0): tool_router 返回空表 (只 header)      │
│             └─ POSSIBLE_FAILURE (0.5): 无 finish marker / maxIters  │
│             → user_trace_summary 表                                 │
│   Phase 3: EvolveSkills - 评估 fingerprint 失败率, 触发演化         │
│   Phase 4: ConsolidateMemory - 合并成功 trace 到 per-user MEMORY.md│
│                                                                      │
│ Cross-JVM 互斥: MySQL GET_LOCK("memory_digestion_lock")             │
└─────────────────────────────────────────────────────────────────────┘
```

### 7.2 Skill 演化 / 合成闭环

#### PR2 - cache-MISS 路径合成（`SkillSynthesisHook` + `SkillSynthesisRunner`）

```
PreCall (priority 50)
   ↓
SkillSynthesisRunner.bumpAndMaybeSynthesize(fingerprint, userId, question, traceId)
   ↓
1. candidateRepo.incrementHit(fingerprint, userId, question, traceId)
   → skill_candidate.hit_count++
2. 若 hit_count >= threshold (默认配置) 且 markSynthesized CAS 成功
   → 异步 dispatch (boundedElastic):
      ├─ via-subagent=true (默认): 调 generate_skill 子 agent 蒸馏
      └─ via-subagent=false: 直接调 SkillDistiller.distill
   → SkillSaveTool.save_skill 写 skills-auto/<name>/SKILL.md
   → SkillIndexRepository.upsertOnSave 写 skill_index
```

#### PR4 - failure-feedback 演化（`SkillEvolutionHook` + `SkillEvolutionRunner`）

```
PreCall (跨轮 rejection):
  1. consumePendingJudgement(sessionKey)
  2. 若上一轮缓存了 retrieved_skills, 且本轮用户消息匹配 rejection 关键词
     ("不对" / "错了" / "重算" / "重新" / "不是这样" / "不正确")
     → recordFailure(skills)  (skill_index.failure_count++)
  3. 否则丢弃 pending judgement

PostCall (本轮信号):
  1. 读 ctx.get("skills.retrieved")
  2. 扫 agent memory 找 python_exec 失败 (≥2 retries)
  3. 若有失败信号:
     → recordFailure(skills)
     → 跳过 pending judgement cache (避免 double-count)
  4. 否则 cachePendingJudgement(sessionKey, skills, exemplarQuestion)
     → 等下一轮 rejection 投票

阈值评估 (在 recordFailure 后):
  - failure_count / total >= failRateEvolve (0.3) 且 total >= minUsesEvolve (5)
    → markEvolving CAS → 异步 SkillDistiller.evolve (改写 SKILL.md)
  - failure_count / total >= failRateBlacklist (0.6) 且 total >= minUsesBlacklist (10)
    → 加入黑名单 (skill_index.status='blacklisted')
```

#### 配置

```properties
# application.properties:96-139
harness.skills.auto-synth.enabled=true
harness.skills.auto-synth.via-subagent=true
harness.skills.retrieval.enabled=false        # vector retrieval 已禁用
harness.skills.evolution.enabled=true
harness.skills.evolution.fail-rate-evolve=0.3
harness.skills.evolution.fail-rate-blacklist=0.6
harness.skills.evolution.min-uses-evolve=5
harness.skills.evolution.min-uses-blacklist=10
harness.skills.evolution.rejection-keywords=不对,错了,重算,重新,不是这样,不正确
harness.skills.isolation.enabled=true
harness.skills.metric-classification.enabled=true
harness.skills.metric-classification.model-instance=light-classifier

# digestion
harness.a2a.memory.digestion.enabled=true
harness.a2a.memory.digestion.cron=0 9 21 * * *
harness.a2a.memory.digestion.batch-size=50
harness.a2a.memory.digestion.episodic-retention-days=30
harness.a2a.memory.digestion.ledger-retention-days=90
harness.a2a.memory.digestion.summary-max-length=200
harness.a2a.memory.digestion.episodic-table-name=QualitySupervisor_episodic_memory
harness.a2a.memory.digestion.min-traces=5
harness.a2a.memory.digestion.via-subagent=true
```

### 7.3 测试集

- **Golden 数据集**：`GoldenDatasetCase`（`v2/verify/GoldenDatasetCase.java`），由 `GoldenEvaluationRunner` 跑
- **Golden 评估调度**：`GoldenEvaluationScheduled`（cron 触发或 controller 手动触发）
- **报告**：`GoldenEvaluationReport` / `CalibrationReport` / `SloReport` / `ExperimentMetrics`
- **修复策略**：`RepairPolicyEngine` + `RepairPlan` + `RepairType`（基于 `policies/repair_policy.yaml`）
- **回放**：`ReplayService` + `ReplayResult`
- **A/B 实验**：`RuleExperiment` + `RuleExperimentService` + `OptimizationProposal` + `OptimizationApplyResult`

### 7.4 当前测试覆盖度

- **E2E 报告**：`docs/rc2-to-rc5/stage1-8-e2e-test-report.md`（2026/07/14，仅 smoke test 通过）
- **未完成项**（`docs/rc2-to-rc5/CURRENT-STATUS.md`）：
  - Stage 4 distributed 模式完全未测
  - cron 触发型（Stage 5/7/8）bean wired 但 cron 时刻未到
  - 异常路径 SSE 错误事件加 `@Valid` / `@NotBlank` 校验未做
- **回滚基线**：`docs/rc2/regression-baseline.md`

### 7.5 没有自动评分的环节

- **人工评分**：当前无人工评分入口，只有 `GoldenEvaluationRunner` 的 expected-answer 准确率
- **业务方反馈**：仅靠 rejection keywords（"不对" / "错了"）作为隐式负反馈
- **点赞数据**：`skill_like` 表存在，但未参与 skill 排序 / 演化决策

---

## ⑧ 真实失败案例（Agent 经常做错的任务）

### 8.1 案例 A：LLM 心算 23.1 - 13.1 出错 → ArithTool 强制

**场景**：用户问"杭州开发一部 Q2-1 达标率 84.44%，二部 93.33%，一部比二部差多少？"

**LLM 行为**：直接心算 `93.33 - 84.44 = 8.99`，但小参数模型（qwen3:8b / glm-5.2）连 `23.1 - 13.1` 都会算错（可能输出 `10.2` 或 `9.99`）。

**修复**：所有加减乘除 / 百分比强制走 `ArithTool`（BigDecimal），禁止心算。
- `arith_tool_design_philosophy` memory：用工具消除 prompt engineering 问题
- `arith_tool_e2e_verified` memory：2026/07/18 5/5 PASS
- 关键：`V2ToolGroupAdapter` grouped tools 不暴露给 LLM，`arith` 必须注册为 ungrouped
- 例外：`script_exec` 返回的 JSON 已含百分比字段（`scored_pct` / `passed_pct`），LLM 直接读数字

### 8.2 案例 B：LLM 卡 plan_enter 循环 → 关闭 plan mode

**场景**：`analyze_data` 子 agent 启用 plan mode 后，LLM 反复调 `plan_enter` 不退出，HITL ASK 阻塞（无前端 UI 批准），导致 `agent_spawn` 600s 超时。

**修复**（2026/07/25，见 memory `plan_mode_disabled_analyze_data`）：
- 关闭 `analyze_data` 子 agent 的 plan mode
- `removeTool plan_enter / plan_exit / plan_write`
- 改为纯 ReAct 5 步 workflow：`load_skill → router_tool → python_exec → arith → 回复`
- 用 `todo_write`（JAR TodoTools，不在禁用清单）做轻量跟踪

### 8.3 案例 C：LLM 调 agent_spawn 替代 save_skill → 蒸馏阻塞

**场景**：`generate_skill` 子 agent 本应调 `save_skill` 工具保存 SKILL.md，但 LLM 误调 `agent_spawn`（也是元工具），导致：
- spawn 一个不存在的子 agent，subagent_failed
- 蒸馏流程无法完成
- E2E 6.3 卡死

**修复**（见 memory `distillation_agent_spawn_confusion`）：
- spec prompt 强化已注入但 qwen3:8b CPU 模式过慢
- 当前状态：PARTIAL（spec 强化已注入，但 LLM 仍偶尔误调）
- 候选方案：`generate_skill` 子 agent 移除 `agent_spawn` 工具（maxIters=3 内只能调 `skill_save`）

### 8.4 案例 D：LLM 走 router_tool 路由 sql_registry_exec → 浪费 4-5 轮

**场景**：用户问"杭州开发二部 7 月版 Q2-1 达标率"。LLM 流程：
1. `load_skill_through_path(name="q2_1_by_dept_version_metrics")` 读 SKILL.md
2. `router_tool(paramsJson='{"toolId":"sql_registry_exec","sqlId":"...","params":{...}}')` ← 错！
3. `router_tool` 调用 `ToolRoutersIndex` 反射找不到 `sql_registry_exec`（不在 `ToolRoutersIndex` 注册的 bean 里）
4. 错误回退，重试...
5. 终于发现 `sql_registry_exec` 直接可用

**修复**（见 `_common/SKILL.md` 的"直接调用"硬规则）：
- `sql_registry_exec` / `script_exec` 已直接注册在 Toolkit
- **不要走 `router_tool({toolId:"sql_registry_exec",...})` 元工具路由** —— 浪费 4-5 轮往返
- 该规则由 `SubagentRegistrar` 自动注入到每个子 agent sysPrompt

### 8.5 案例 E：LLM 写 pandas 代码卡死（qwen3:8b）→ script_exec 替代

**场景**：`analyze_data` 子 agent 拿到 CSV 路径后，需要算 Q2-1 打分率 / 达标率。qwen3:8b CPU 模式写 pandas 代码经常出错：
- `df["Q2_1打分状态"] == "已打分"` 列名写错（实际是 `Q2_1打分状态` 但 LLM 写成 `q2_1_status`）
- `int((df[...] == "...").sum())` 语法错误
- 反复重试 2 次失败，按硬规则停止

**修复**：
- 业务方在 `script_registry` 表预注册 Python 脚本（如 `q2_1_metrics_by_dept_version`）
- 脚本内部完成 SQL 取数 + pandas 算指标，一次返回 markdown 表 + JSON
- LLM 只调 `script_exec(scriptId="...", params={...})`，不写 pandas 代码
- 见 `workspace/skills/q2_1_by_dept_version_metrics/SKILL.md`

### 8.6 案例 F：TextBlockStart 不加 \n 分隔 → markdown 解析失败

**场景**：子 agent 多 block 的 delta 全部粘在一行，markdown 解析看不到 `^###` / `^|` / `^-`，前端渲染成字面文本。

**根因**（见 memory `text_block_start_newline_separator`）：`V2ChatStreamServiceImpl.processChunk` 未处理 `TextBlockStartEvent`，子 agent 每个 ReAct 步骤发一个新 TextBlock，delta 直接拼到上一 block 尾巴。

**修复**（2026/07/24）：`processChunk` 处理 `TextBlockStartEvent`，在 `thinkContent` 非空时插 `\n` 分隔。

### 8.7 案例 G：LLM 重复输出 → AgentResultEvent 不重复 append

**场景**：流式 `text_block_delta` 已累积完整文本，`AgentResultEvent` 终止事件再 append 一遍，回复双倍。

**修复**（见 memory `frontend_duplication_markdown_fixes`）：`processChunk` 在 `event instanceof AgentResultEvent` 时仅在 `answerContent.length() == 0` 时 append（非流式模型 fallback）。

### 8.8 案例 H：CSV 下载短链 404 → cleanup 提前删 taskBucket

**场景**：用户点下载链接 404。根因：`V2ChatStreamServiceImpl.buildCleanup` 在 chat 请求结束 `rm -rf taskBucket`，用户点链接时 CSV 已删。

**修复**（见 memory `csv_download_cleanup_conflict`）：
- dev 设 `keep-artifacts=true` 规避
- 生产应改 CSV 内容落 `url_shortener` 表（与 taskBucket 解耦）

### 8.9 案例 I：write_file 相对路径绕过 → 落到共享 workspace

**场景**：LLM 调 `write_file(reports/foo.md)`（相对路径），`ArtifactAccessMiddleware` 相对路径检查不严，落到共享 `/workspace/artifacts/`，跨用户可读。

**修复**（见 memory `write_file_relative_path_tenant_isolation`，2026/07/24）：
- `enforcePath` + `enforceListPath` 改成重写相对路径到 `/workspace/artifacts/<user>/<task>/`

### 8.10 案例 J：python_exec Windows 管道 4KB 死锁

**场景**：`PythonExecTool.runProcess` 在 Windows 下管道 4KB 死锁（`waitFor` 不排空 stdout/stderr）。

**修复**（见 memory `python_exec_pipe_deadlock_fix`，2026/08/07）：
- 用 `redirectOutput` / `redirectError` 到临时文件
- 同 `SshArtifactIo` 模式

### 8.11 案例 K：Markdown 渲染卡顿 20s

**场景**：前端 markdown 渲染 20s 卡顿。根因不是 regex（1.65ms/9KB），是浏览器 layout 50KB HTML（几百 DOM 节点）。

**修复**（见 memory `markdown_freeze_browser_layout`，2026/07/24）：
- `useDeferredValue` + `content-visibility:auto` + `contain`

### 8.12 案例 L：宽表数据 schema 不一致

**场景**：dev DB 实际 schema=`remote_app`（DDL 写 `productsgaussdb`），列名 `_` 被替换成 `0`（`dev0dept` 而非 `dev_dept`），第一列 `projectzh0no` 还带 UTF-8 BOM 前缀。

**修复**（见 memory `wide_table_metrics_data_quality`）：
- 工具硬编码 schema = `remote_app`
- LLM 不需要传 `remote_app.dsqa_dwd_...`

### 8.13 当前最容易出错的 5 类任务（按业务方反馈频率排序）

1. **多维度下钻分析**（"杭州开发二部 7 月版各应用 Q2-1 达标率 + 同比"）—— LLM 容易跳过 `data_distribution` 直接回复
2. **跨表 JOIN + 百分比**（"Q2-1 达标率 + 缺陷密度对比"）—— LLM 容易心算百分比
3. **生成下载链接后立即下载**（cleanup 提前删 taskBucket 导致 404）
4. **新指标 / 新维度的查询**（skill 库没匹配，LLM 走 `tool_index` 兜底但选错 toolId）
5. **保存 skill 时参数不全**（`skill_name` 含中文 / 连字符，`content` < 60 行，没调 `save_skill` 就回复"已保存"）

---

## 附录 A：项目目录结构速查

```
analysis-project/
├── src/main/java/com/agentscopea2a/
│   ├── v2/
│   │   ├── runner/              HarnessA2aRunnerV2 + SubagentRegistrar
│   │   ├── controller/          V2ChatController + V2ChatInterruptController
│   │   ├── service/             V2ChatStreamService(Impl) + UrlShortenerService
│   │   ├── tools/               20+ Tool 类（AgentTools / ArithTool / PythonExecTool / ...）
│   │   ├── hooks/               7 个 Hook（Skill* / ArtifactHandoff / ToolCallTracking / ...）
│   │   ├── middleware/          13 个 Middleware
│   │   ├── skills/              Skill 蒸馏 / 演化 / 注册（SkillDistiller / SkillEvolutionRunner / ...）
│   │   ├── skillManager/        Skill 管理 UI 后端（entity / dto / mapper / service / controller / scheduler）
│   │   ├── memory/              MysqlMemoryStore + EpisodicMemory
│   │   ├── digestion/           夜间消化（MemoryDigestionService / TraceMiner / SkillFlowEvolver）
│   │   ├── trace/               Trace 采集 + 写入（collector / assembler / writer / model / service / controller）
│   │   ├── verify/              50+ 类验证闭环（VerificationHook / VerifyLoopOrchestrator / GoldenEvaluationRunner / ...）
│   │   ├── config/              Spring @Configuration（V2ToolConfig / V2SkillConfig / V2MemoryConfig / ...）
│   │   ├── artifact/            ArtifactStore + ArtifactContext
│   │   ├── dimension/           维度状态管理
│   │   ├── alarm/               CronFailureAlerter
│   │   ├── auth/                鉴权
│   │   ├── cache/               ResponseCacheService
│   │   ├── exception/           SkillExceptionHandler / SkillDistillationException
│   │   ├── model/               ModelProvider + FallbackModelDecorator
│   │   ├── modelConfig/         用户模型配置管理
│   │   ├── routing/             V2SessionRouter（灰度）
│   │   ├── sandbox/             沙箱配置
│   │   ├── sqlRegistry/         预注册 SQL 管理
│   │   ├── state/               SanitizingAgentStateStore
│   │   ├── tool/                （空目录）
│   │   └── util/                HookRuntimeContext 等工具
│   └── ...
├── src/main/resources/
│   ├── workspace/
│   │   ├── AGENTS.md            主 agent (Supervisor) system prompt
│   │   ├── agent-subagents/     analyze_data.md + generate_skill.md
│   │   ├── skills/              8 个 builtin skill
│   │   ├── knowledge/           静态知识（KNOWLEDGE.md + metric-categories.yaml）
│   │   ├── knowledge-dynamic/   动态知识
│   │   ├── policies/            repair_policy.yaml
│   │   ├── scripts/             _gauss_jdbc.py 等脚本
│   │   └── verify-agents/       verify / critic 子 agent spec
│   ├── docs/                    设计文档 + E2E 报告（rc2 / rc2-to-rc5 / table-mertics / skills-opt / Plan-Machie / prompt）
│   ├── application*.properties  多 profile 配置（dev / prod / docker / sandbox-linux / sandbox-windows / sandbox-linux-remote）
│   ├── db/                      SQL 脚本
│   └── mybatis/                 MyBatis mapper XML
└── pom.xml
```

## 附录 B：关键配置文件

| 文件 | 用途 |
|---|---|
| `application.properties` | 主配置（模型 / workspace / skill / digestion / retrieval） |
| `application-dev.properties` | dev profile（MySQL / LLM / sandbox 远端） |
| `application-prod.properties` | 生产 profile |
| `application-docker.properties` | docker profile |
| `application-sandbox-windows.properties` | Windows 沙箱（isolation-scope=GLOBAL + shared-container） |
| `application-sandbox-linux.properties` / `application-sandbox-linux-remote.properties` | Linux 沙箱 |
| `workspace/AGENTS.md` | 主 agent system prompt |
| `workspace/agent-subagents/*.md` | 子 agent spec |
| `workspace/skills/_common/SKILL.md` | 共享硬规则 |
| `workspace/policies/repair_policy.yaml` | 修复策略（RepairPolicyEngine 读） |
| `workspace/knowledge/metric-categories.yaml` | 指标分类（MetricClassificationService 读） |
| `docs/table-mertics/sql-registry.sql` | 预注册 SQL 表 DDL + 种子数据 |
| `docs/table-mertics/wide_inf_ddl.sql` | 宽表 DDL |

## 附录 C：数据源

| DataSource | 用途 | 表 |
|---|---|---|
| `mysqlDataSource` | 主库（agent 状态 / skill / memory / verification） | `agentscope_sessions` / `skill_manage` / `skill_index` / `skill_candidate` / `skill_pending_judgement` / `agent_memory` / `agent_memory_ledger` / `QualitySupervisor_episodic_memory` / `url_shortener` / `verification_*` / `rule_experiment*` / `version_record` |
| `gaussDataSource` | GaussDB（业务数据 + skill_index 旧位置） | `remote_app.dsqa_dwd_req_item_app_portrait_wide_inf` / `skill_index`（旧）/ `script_registry` / `sql_registry` |
| `clickHouseDataSource` | ClickHouse（trace + 宽表加速） | `trace_event` / `trace_conversation` / 宽表物化视图 |

## 附录 D：相关文档索引

| 文档 | 用途 |
|---|---|
| `docs/skills-opt/skills-opt.md` | 本文档 |
| `docs/table-mertics/skill-format-guide.md` | SKILL.md 格式与压缩规则 |
| `docs/table-mertics/skills-热加载方案.md` | 砍 skill_index + embedding 整层方案 |
| `docs/table-mertics/supervisor-direct-path-design.md` | Supervisor 直跑设计（不派 query_data） |
| `docs/table-mertics/sql-registry-方案.md` | 预注册 SQL 方案 |
| `docs/table-mertics/clickhouse-方案.md` | ClickHouse 宽表方案 |
| `docs/table-mertics/csv-短链下载方案.md` | CSV 下载短链方案 |
| `docs/table-mertics/提示词优化方案-双路径.md` | 双路径提示词优化 |
| `docs/rc2/skill-distillation-via-subagent-design.md` | 子 agent 蒸馏设计 |
| `docs/rc2/skill-evolution-memory-digestion-combined.md` | skill 演化 + memory digestion 合并设计 |
| `docs/rc2/night-time-digestion-pipeline.md` | 夜间消化流水线 |
| `docs/rc2/fingerprint-metric-redesign.md` | fingerprint 指标重设计 |
| `docs/rc2/knowledge-dynamic-retrieval-design.md` | 动态知识检索设计 |
| `docs/rc2-to-rc5/CURRENT-STATUS.md` | 升级完成度快照 |
| `docs/rc2-to-rc5/UPGRADE_PLAN.md` | 升级主计划 |
| `docs/rc2-to-rc5/stage1-8-e2e-test-report.md` | E2E 测试报告 |
| `docs/rc2-to-rc5/optimization-analysis.md` | 优化分析（P1/P2 系列） |
| `docs/rc2-to-rc5/adr-arith-tool.md` | ArithTool ADR |
| `docs/rc2-to-rc5/adr-code-interpreter-removal.md` | code_interpreter 移除 ADR |
| `docs/Plan-Machie/plan-mode-subagent-migration.md` | plan mode 子 agent 迁移（已回退） |
| `docs/Plan-Machie/memory-middleware-middle-ground.md` | memory middleware 中间立场 |
| `docs/Plan-Machie/artifact-tenant-isolation-fix.md` | artifact 租户隔离修复 |
| `docs/Plan-Machie/memory-tenant-isolation-fix.md` | memory 租户隔离修复 |
| `docs/prompt/llm-context-optimization-plan.md` | LLM context 优化 |
| `docs/memory_truncated/tool-result-truncation-plan.md` | tool result 截断方案 |

---

> 下一步：基于本文档 8 个维度的项目信息，设计 skills 自优化方案。建议优先关注：
> 1. **§8.13 的 5 类高失败任务** —— 自优化的首要目标
> 2. **§7.2 的 PR2/PR4 闭环** —— 已有的演化基础设施可复用
> 3. **§2.4 的 SKILL.md 压缩规则** —— 自优化生成的 skill 必须符合该格式
> 4. **§3.4 的 retrieval 禁用状态** —— skill 选择靠 LLM 显式调用，不靠 embedding
> 5. **§6.1 的三套记录系统** —— 评价数据来源（TraceSession / EpisodicMemory / agent_memory_ledger）
