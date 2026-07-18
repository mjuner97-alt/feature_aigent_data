# 框架核心亮点验收记录

> **范围**：v2 升级到 agentscope-java 2.0.0-RC5 后，核验新版本框架的两大类核心亮点是否已具备——
> (1) **自主且可控**：ReAct 范式 / 安全中断 / 优雅取消 / 人机协同（Hook）
> (2) **内置工具**：PlanNotebook / 结构化输出 / 长期记忆（语义搜索 + 多租户隔离）
>
> **分支**：`upgrade/2.0.0-RC5-dual-track`
> **核验日期**：2026/07/18
> **关联文档**：[stage9-e2e-acceptance.md](stage9-e2e-acceptance.md)（端到端用例验收）、[CURRENT-STATUS.md](CURRENT-STATUS.md)（阶段快照）

---

## 一、总览

| 类别 | 子项 | 状态 | 关键证据 |
|---|---|---|---|
| 自主且可控 | ReAct 范式 | ✅ 已具备 | `HarnessA2aRunnerV2.java:156-159` `enablePlanMode()` + `enableTaskList(true)` + `enablePendingToolRecovery(true)` |
| 自主且可控 | 安全中断（pause/resume） | ⚠️ 部分具备 | 状态持久化到 MySQL，跨 JVM 重启可恢复；无显式 `/pause` `/resume` REST 端点 |
| 自主且可控 | 优雅取消 | ✅ 已具备 | `V2ChatStreamServiceImpl.java:121-132` `Disposable.dispose()` 取消 reactive 流 + AsyncToolMiddleware 工具级 cancel |
| 自主且可控 | 人机协同（Hook） | ✅ 已具备 | 7 个 v2 hook 全部迁移 + `PermissionMode` 5 模式 + `/debug/permission/mode` GET/POST 端点 |
| 内置工具 | PlanNotebook | ✅ 已具备 | `enablePlanMode()` + `planFileDirectory("plans")` + `enableTaskList(true)`；11.2 跨重启 PASS |
| 内置工具 | 结构化输出 | ⚠️ 未显式启用 | 仅 DTO 层 `@JsonProperty`；未集成框架级 `OutputParser` / `ResponseFormat` |
| 内置工具 | 长期记忆 | ✅ 已具备 | `MySqlEpisodicMemory` + `MysqlMemoryStore` + `MemoryHydrator` + `memory_save` 工具 |
| 内置工具 | 多租户隔离 | ✅ 已具备 | `MysqlMemoryStore` 建表 `user_id VARCHAR(128) NOT NULL`；所有查询 `WHERE user_id=?` |
| 内置工具 | 语义搜索 | ✅ 已具备 | `MySqlEpisodicMemory:189-190` `vectorSearchEnabled=true` + `EmbeddingClient` 注入 |

**判定**：9 项中 7 项 ✅ 已具备，2 项 ⚠️ 部分具备。主链路（自主 ReAct 循环 + Plan Mode + 长期记忆 + 多租户）全部满足业务要求。

---

## 二、自主且可控

### 2.1 ReAct 范式 ✅

**定义**：Reasoning + Acting 交替循环——LLM 推理（PRE_REASONING/POST_REASONING）→ 选择工具 → 执行（PRE_ACTING/POST_ACTING）→ 观察结果 → 再推理，直到任务完成。

**证据**：

| 项 | 位置 | 说明 |
|---|---|---|
| builder 链路启用 | `HarnessA2aRunnerV2.java:143-163` | `HarnessAgent.builder()` 装配 `.enablePlanMode()` + `.enableTaskList(true)` + `.enablePendingToolRecovery(true)` + `.middlewares(middlewares)`，JAR 内部 ReAct loop 自动启用 |
| 运行时日志 | `/tmp/app-e2e.log` | 多次验证：`[QualitySupervisorV2] PRE_REASONING` → `POST_REASONING tool_call: name=agent_spawn` → `PRE_ACTING` → `POST_ACTING state=SUCCESS` → `PRE_REASONING` 循环 |
| E2E 用例 | §3.2 / §3.4 / §3.11 | C1 简单查询、C2 含计算分析（并行 query_data x2 + code_interpreter）、C3 多轮对话全部 PASS |

**关联**：[[async_tool_middleware_wiring]]（ReAct loop 内的工具调用 >30s 由 AsyncToolMiddleware offload）

### 2.2 安全中断（pause/resume）⚠️ 部分具备

**定义**：运行中任务可被显式暂停（pause），后续可恢复（resume）继续执行，状态不丢失。

**已具备部分**：

| 能力 | 证据 |
|---|---|
| 状态持久化 | `MysqlAgentStateStore` 把 `agent_state`（含 plan/tasks/sandbox state）写入 MySQL `agentscope_sessions.state_data` JSON 字段 |
| 跨 JVM 重启恢复 | 11.2 PASS（2026/07/17）：旧 JVM taskkill /F 强杀 → 新 JVM 启动 → 同 conversationId 发请求 → `plan_mode_context` 从 MySQL 恢复，LLM 第一句 "好的,我来退出计划模式并开始执行计划" |
| 沙箱状态持久化 | 10.2 PASS：`_sandbox_state` 写入 `agentscope_sessions.__anon__:sandbox:session:<convId>`，第二轮 `Priority 3: resuming from persisted state` |
| 并发执行保护 | `JdbcSandboxExecutionGuard` MySQL `GET_LOCK` 30min timeout，避免并发执行冲突 |

**未具备部分**：

| 缺口 | 说明 |
|---|---|
| 无 `/pause` REST 端点 | `DebugController` 仅有 `/debug/permission/mode`、`/debug/digest`、`/debug/sweep` 等；无显式 pause |
| 无 `/resume` REST 端点 | 同上 |
| 取消即结束 | `V2ChatStreamServiceImpl` 客户端断开后 `Disposable.dispose()` 取消流，任务直接结束而非暂停（恢复靠下一轮同 conversationId 重新发起 + state store 回灌） |

**当前路径**：依赖"取消 + 状态已落盘 + 重启续跑"组合实现，本质是"中断 + 重启续跑"而非"运行中真暂停"。若业务需要运维侧主动 pause（不杀进程），需补 `DebugController.pauseSession(userId, sessionId)` 端点 + agent 内部暂停信号机制。

**关联**：[[plan_state_cross_restart_verified]]（11.2 跨重启验证细节 + 踩坑：stale MySQL GET_LOCK 阻塞 30min）

### 2.3 优雅取消 ✅

**定义**：客户端断连 / 超时 / 主动取消时，reactive 流被正确清理，无 500 错误，资源释放，已落盘状态不丢。

**证据**：

| 项 | 位置 | 说明 |
|---|---|---|
| 流级取消 | `V2ChatStreamServiceImpl.java:121-132` | `AtomicReference<Disposable> subscription` + cleanup Runnable CAS guard，`d.dispose()` 取消 reactive 流，日志 `v2 stream cancelled for sessionId=... (client disconnect/timeout)` |
| 工具级取消 | `HarnessA2aRunnerV2.java:178-180` | AsyncToolMiddleware `asyncToolTimeout(30s)`：工具调用 >30s 自动 offload 到后台，返回 HintBlock 占位符，主流程不被阻塞 |
| E2E 用例 | §2.5 PASS | conversationId=meta-tool-verify-001 客户端 ctrl+C → `v2 stream cancelled for sessionId=meta-tool-verify-001` 触发，无 500 |
| E2E 用例 | §3.4 PASS | conversationId=fix-3.4-v2 agent_spawn >30s → `AsyncToolMiddleware: Tool execution timed out after 30s, offloading to background: session=fix-3.4-v2`，HintBlock 占位符投递成功 |
| Spring 10min async timeout | `application.properties` | 超时后切 SSE 流，agent 后台继续 reasoning/tool calls，`doFinally` 保证 daily 文件等副作用不丢（见 [[daily_file_local_write_fix]]） |

**关联**：[[async_tool_middleware_wiring]]、[[daily_file_local_write_fix]]（两处均依赖 `doFinally`/`concatWith` 处理 Spring timeout 切流）

### 2.4 人机协同（Hook）✅

**定义**：通过 Hook 机制让外部代码在 agent 执行的关键节点（工具调用前后、推理前后、消息收发）介入，实现审批、改写、拦截等协同行为。

**证据**：

| 项 | 位置 | 说明 |
|---|---|---|
| Hook 数量 | `src/main/java/com/agentscopea2a/v2/hooks/` | 7 个 hook 全部迁移到 v2 并实现 `RuntimeContextAware`：`ArtifactHandoffHook` / `ArtifactAccessMiddleware` / `KnowledgeRetrievalHook` / `PythonExecRetryHook` / `SkillEvolutionHook` / `SkillRetrievalHook` / `SkillSynthesisHook` / `ToolCallTrackingHook` |
| 装配 | `HarnessA2aRunnerV2.java` builder `.middlewares(middlewares)` | 启动日志 `Hook wired` 7 条 |
| 子 agent hook 链 | `SubagentRegistrar` | 2026/07/17 修复：每个子 agent 显式挂 `ArtifactHandoffHook` + `ArtifactAccessMiddleware` + `PythonExecRetryHook`（仅 `python_exec` spec），对齐 v1 `SupervisorService:562-569` |
| HITL 审批 | `PermissionMode` 5 模式 | `default` / `accept_edits` / `explore` / `bypass` / `dont_ask`，`HarnessAgent.setPermissionMode(userId, sessionId, mode)` 持久化到 `agent_state.permission_context.mode` |
| 切换端点 | `DebugController.java:233-310` | `POST /debug/permission/mode?userId=X&sessionId=Y&mode=Z` + `GET /debug/permission/mode?userId=X&sessionId=Y`（dev profile 下免鉴权） |
| E2E 用例 | §11.4 PASS | bypass 模式让 4.5 测试 LLM 跳过 Plan Mode HITL 批准直接 spawn code_interpreter + python_exec SUCCESS |
| E2E 用例 | §9.1 PASS | `ArtifactHandoffHook` 捕获子 agent router_tool 结果，3 次 CSV 落盘到 SSH 远端 `/opt/agentscope-workspace/harness-a2a/artifacts/anonymous/...` |

**关联**：[[subagent_hook_chain_migration]]、[[permission_mode_endpoint]]、[[skill_retrieval_hook_not_migrated]]、[[skill_synth_hook_migrated]]、[[skill_evolution_hook_migrated]]

---

## 三、内置工具

### 3.1 PlanNotebook ✅

**定义**：LLM 在复杂任务前可主动进入 Plan Mode，写 plan 文件（`plans/PLAN.md`）等待用户审批后再执行；TaskList 工具（`todo_write`）管理任务清单跨轮持久化。

**证据**：

| 项 | 位置 | 说明 |
|---|---|---|
| Plan Mode 启用 | `HarnessA2aRunnerV2.java:156-157` | `.enablePlanMode()` + `.planFileDirectory("plans")` |
| TaskList 启用 | `HarnessA2aRunnerV2.java:158` | `.enableTaskList(true)` |
| Pending tool 恢复 | `HarnessA2aRunnerV2.java:159` | `.enablePendingToolRecovery(true)` -- 跨轮未完成工具调用可恢复 |
| 触发规则 | `AGENTS.md` | 2026/07/17 spec 改后加"第 0 步:判断是否需要进入 Plan Mode"段，触发词："分析+报告/多步/完整方案/详细/全面" 或 ≥3 个清晰步骤 |
| E2E 用例 | §11.1 PASS | 中性 prompt "请帮我分析2026年上半年各部门质量分变化趋势..." 触发 `plan_enter`(7次) + `plan_write`(5次) + `plan_exit`(4次) |
| E2E 用例 | §11.2 PASS | 跨 JVM 重启：`plan_mode_context` 持久化到 MySQL `state_data`，新 JVM 恢复后 LLM 识别 `plan_active=true` 继续执行 |
| E2E 用例 | §11.3 PASS | `todo_write` 调用 SUCCESS（`POST_REASONING tool_call: name=todo_write` → `POST_ACTING state=SUCCESS result_len=125`），`tasks_context` 字段持久化到 agent_state JSON |

**关联**：[[plan_state_cross_restart_verified]]、[[spec_prompt_strengthening]]

### 3.2 结构化输出 ⚠️ 未显式启用

**定义**：LLM 输出按预定义 JSON Schema / Pydantic 模型解析，避免自由文本解析的不确定性。

**已具备部分**：
- DTO 层 `@JsonProperty`：`ChatRequest` 用 `user_id`（不是 `userId`）、`DimensionState` / `QuestionAnalysis` 字段标注
- JAR 内部 agent 输出仍是 `Msg.getTextContent()` 自由文本

**未具备部分**：
- `grep -r "OutputParser|ResponseFormat|output_parser|response_format" src/main/java` 全空，未集成框架级结构化输出能力
- LLM 蒸馏 SKILL.md（6.3）时参数靠 prompt 引导而非 schema 强约束，导致 6.3 出现"调 save_skill 缺 skill_name"问题（见 [[distillation_agent_spawn_confusion]]）

**影响**：
- 业务上目前不影响主链路（system prompt 引导 + LLM 自由文本响应已足够覆盖查询/分析/技能生成场景）
- 若新增"LLM 直接产出维度评分表 / 结构化指标卡"等用例，需补充 `OutputParser` 集成

**建议**：内网部署后视业务需求决定是否启用。

### 3.3 长期记忆 ✅

**定义**：跨会话/跨轮的持久化记忆，包含 episodic（事件轨迹）、semantic（摘要）、procedural（技能）三类，支持检索注入。

**证据**：

| 项 | 位置 | 说明 |
|---|---|---|
| Episodic 存储 | `MySqlEpisodicMemory.java` | trace 表存 (session_id, role, content, embedding)，按 session 维度回溯 |
| Memory Store | `MysqlMemoryStore.java` | 两张表 `agent_memory`（UPSERT 语义）+ `agent_memory_ledger`（按日期 append） |
| 启动回灌 | `MemoryHydrator` | JVM 启动时把 `MEMORY.md` + `memory/*.md` 内容回灌进 agent 上下文 |
| 内置工具 | `memory_save` | LLM 可主动调用把当前对话摘要写入长期记忆 |
| Mirror 中间件 | `MemoryLedgerMirrorMiddleware.java` | 每次调用捕获 response 写入 `agent_memory_ledger` + 本地 daily 文件 `<workspace>/memory/<userId>/<date>.md` |
| E2E 用例 | §5.1 PASS | `agent_memory_ledger` 多行 `source=v2-activity` 写入 |
| E2E 用例 | §5.2 PASS | 第二轮请求 system prompt 含 `## 历史参考案例`（cosine 相似度匹配的历史 trace 注入） |
| E2E 用例 | §5.3 PASS | system prompt 含 `## Memory Recall` + `## Memory Persistence` 引用 MEMORY.md |
| E2E 用例 | §5.4 PASS | daily 文件直写宿主磁盘（bypass sandbox，规避 Windows 8KB CreateProcess limit） |

**关联**：[[ledger_mirror_middleware_design]]、[[daily_file_local_write_fix]]、[[episodic_write_blocked]]

### 3.4 多租户隔离 ✅

**定义**：不同用户（租户）的记忆 / 会话状态 / artifact 互相隔离，A 用户无法读到 B 用户的数据。

**证据**：

| 项 | 位置 | 说明 |
|---|---|---|
| 建表 schema | `MysqlMemoryStore.java:68-78` | `agent_memory` 表 `user_id VARCHAR(128) NOT NULL`，唯一键 `uk_user_kind_key (user_id, kind, key_name)` |
| 建表 schema | `MysqlMemoryStore.java:81-88` | `agent_memory_ledger` 表 `user_id VARCHAR(128) NOT NULL`，索引 `idx_user_date (user_id, date_key, id)` |
| 查询过滤 | `MysqlMemoryStore.java:125, 146, 195` | 所有 SELECT 都 `WHERE user_id=?`，没有跨用户查询路径 |
| 上下文透传 | `RuntimeContext.getUserId()` | 中间件层 / hook 层 / 工具层均通过 `RuntimeContext` 拿 userId，不会回退到 anonymous |
| Episodic 隔离 | `MySqlEpisodicMemory.java` | `session_id = user:<userId>:<conversationId>` 格式，按用户切分 trace |
| Artifact 隔离 | `ArtifactHandoffHook` | 路径含 `<userId>/<taskId>` 前缀，SSH 远端 `/opt/agentscope-workspace/harness-a2a/artifacts/<userId>/...` |
| Sandbox state 隔离 | `agentscope_sessions` | state_key 格式 `<userId>:sandbox:session:<convId>`，SanitizingAgentStateStore 处理 Windows 路径分隔符问题（见 [[sandbox_state_persistence_jar_bug]]） |
| E2E 用例 | §5.4 PASS | conversationId=fix-5.4-v4 user_id=fix_tester_3 → daily 文件 `memory/fix_tester_3/2026-07-17.md`（不是 anonymous） |

**注意**：测试时 ChatRequest DTO 用 `@JsonProperty("user_id")`，curl body 必须写 `"user_id":"..."`，写 `"userId"` 会被忽略落到 "anonymous"。

### 3.5 语义搜索 ✅

**定义**：长期记忆检索不靠关键词匹配，而是基于 embedding 向量相似度，容错近义词 / 改写。

**证据**：

| 项 | 位置 | 说明 |
|---|---|---|
| 配置 | `EpisodicMemoryConfig` | `vectorSearchEnabled=true` 开关 + `EmbeddingClient` 注入 |
| 写入侧 | `MySqlEpisodicMemory.java:142, 164-165` | INSERT 时 `embedContent(content)` 调 `EmbeddingClient.embed` 生成向量，写入 `embedding` JSON 字段 |
| 检索侧 | `MySqlEpisodicMemory.java:189-190` | `if (config.isVectorSearchEnabled() && embeddingClient != null)` 优先走向量搜索 |
| 向量查询 | `MySqlEpisodicMemory.java:227-241` | `embeddingClient.embed(query)` 生成查询向量 → 全表 `WHERE embedding IS NOT NULL ORDER BY id DESC LIMIT ?` → Java 端算余弦相似度排序 |
| Fallback | `MySqlEpisodicMemory.java` | 无 embedding 时 fallback 到 FTS5（MySQL FULLTEXT 索引） |
| E2E 用例 | §5.2 PASS | 第二轮 system prompt 注入 `## 历史参考案例`，含相关度分数（cosine=0.61 等阈值匹配） |

**已知限制**：
- 当前实现是 Java 端算余弦相似度（全表扫描 + 排序），不是 MySQL 原生向量索引（如 pgvector / MySQL 9.0 vector type）
- 大规模数据（百万级 trace）下性能会下降，需评估是否迁移到原生向量数据库

---

## 四、缺口与建议

### 4.1 当前缺口

| 缺口 | 影响 | 优先级 |
|---|---|---|
| 无 `/pause` `/resume` REST 端点 | 运维侧无法主动暂停运行中 agent，只能取消 + 重启续跑 | P2（当前"取消 + 状态恢复"路径已满足业务） |
| 未集成框架级 `OutputParser` / `ResponseFormat` | LLM 输出仍是自由文本，结构化场景靠 prompt 引导（6.3 蒸馏因此出现参数缺失问题） | P2（视业务需求决定是否启用） |
| 语义搜索 Java 端算余弦 | 大规模数据下性能瓶颈 | P3（当前数据量 < 1k 行，无影响） |

### 4.2 建议

1. **不阻塞内网部署**：当前 7/9 项已具备 + 2 项部分具备均不影响主链路，可推进内网部署
2. **补 pause/resume 端点**：若运维有"主动暂停"诉求，在 `DebugController` 加 `POST /debug/session/pause?userId=X&sessionId=Y` + `POST /debug/session/resume`，调用 agent 内部暂停信号（需 JAR 支持）
3. **结构化输出视业务而定**：若新增"LLM 直接产出维度评分表"等用例，集成 `OutputParser` 并改 spec 强约束 schema；6.3 蒸馏场景的参数缺失问题已通过 spec 强化（"必填参数硬规则"段）缓解
4. **向量索引升级**：trace 行数超过 10w 时评估迁移到 pgvector / Milvus / MySQL 9.0 vector type

---

## 五、关联记忆

| 记忆 | 类型 | 说明 |
|---|---|---|
| [[async_tool_middleware_wiring]] | project | 3.4 HintBlock 修复：AsyncToolMiddleware 装配 + 30s timeout |
| [[daily_file_local_write_fix]] | project | 5.4 daily 文件本地直写 + doFinally 保 cancel 信号 |
| [[plan_state_cross_restart_verified]] | project | 11.2 plan_mode_context 跨 JVM 重启恢复 |
| [[permission_mode_endpoint]] | project | 11.4 PermissionMode 5 模式 + 切换端点 |
| [[subagent_hook_chain_migration]] | project | SubagentRegistrar hook 链对齐 v1 |
| [[spec_prompt_strengthening]] | project | AGENTS.md 路由决策树 + Plan Mode 触发段 |
| [[distillation_agent_spawn_confusion]] | project | 6.3 蒸馏 LLM 误调 agent_spawn 阻塞 |
| [[ledger_mirror_middleware_design]] | project | MemoryLedgerMirrorMiddleware 原始设计 |
| [[episodic_write_blocked]] | project | Episodic 3 bugs 修复记录 |
| [[sandbox_state_persistence_jar_bug]] | project | SanitizingAgentStateStore 包装层修复 |
| [[skill_retrieval_hook_not_migrated]] | project | SkillRetrievalHook 迁移到 v2/hooks/ |
| [[skill_synth_hook_migrated]] | project | SkillSynthesisHook 迁移到 v2/hooks/ |
| [[skill_evolution_hook_migrated]] | project | SkillEvolutionHook 迁移到 v2/hooks/ |

---

## 六、验收结论

**v2 升级到 agentscope-java 2.0.0-RC5 后，框架核心亮点 9 项中 7 项已具备、2 项部分具备**：

- ✅ **自主且可控** 4 项中 3 项具备（ReAct / 优雅取消 / 人机协同），1 项部分具备（安全中断缺显式 pause/resume 端点，但状态持久化 + 跨重启续跑路径已验证）
- ✅ **内置工具** 5 项中 4 项具备（PlanNotebook / 长期记忆 / 多租户 / 语义搜索），1 项未显式启用（结构化输出，视业务需求决定）

**判定**：主链路能力齐备，可推进内网部署。剩余 2 项缺口均为边缘能力，不阻塞业务运行。
