# LLM 上下文优化方案 (减少每轮输入 LLM 的 token)

> 创建日期：2026/08/05
> 状态：方案待评审
> 关联：`docs/memory_truncated/tool-result-truncation-plan.md` (已落地的 ToolResult 压缩)

## 背景

内网 LLM (glm-5.2, 32K 上下文窗口) 能力弱，每轮 ReAct 累积的消息越长，首 token 延迟越高、心算/串话越严重。当前虽然已有几处压缩措施（见下文「现有压缩措施」），但**系统提示、子 agent 提示词、工具 schema、ReAct 历史**这四块还没系统瘦身。本文盘点每块当前体积，给出 P0-P3 优化项与收益估算。

## 当前 LLM 上下文构成盘点

### 字节数据 (workspace/ 全量)

| 文件 | 行数 | 字节 | 进入 LLM 方式 |
|---|---:|---:|---|
| `AGENTS.md` | 105 | 6.8K | JAR `WorkspaceContextHook` 自动注入主 agent system prompt |
| `agent-subagents/analyze_data.md` | 189 | 12.4K | `SubagentRegistrar.registerSubagentFromSpec` 设为子 agent sysPrompt (SubagentRegistrar.java:269) |
| `agent-subagents/generate_skill.md` | 109 | 3.9K | 同上 |
| `knowledge/KNOWLEDGE.md` | 13 | 2.0K | JAR `WorkspaceContextHook` always-on (主+子 agent 都注入) |
| `policies/repair_policy.yaml` | 38 | 1.3K | 同上 |
| `skills/wide_table_q2_1_metrics/SKILL.md` | 142 | 7.6K | `load_skill_through_path` 工具返回，按需加载 |
| `skills/data_primitives/SKILL.md` | 116 | 5.3K | 同上 |
| `skills/tool_index/SKILL.md` | 82 | 4.5K | 同上 |
| `skills/trace_recent_metrics/SKILL.md` | 191 | 7.6K | 同上 |
| `skills/trace_recent_stats_metrics/SKILL.md` | 148 | 6.3K | 同上 |
| `skills/q2_1_by_dept_version_metrics/SKILL.md` | 149 | 6.0K | 同上 |
| `skills/q2_1_metrics_download_demo/SKILL.md` | 155 | 7.7K | 同上 |
| **小计** | 1437 | 71.3K | |

### 单轮 LLM 输入体量估算

按 1 token ≈ 2.5 字节 (中文混合) 折算。

**主 agent (Supervisor) 第 1 轮 (无历史)**:
- AGENTS.md 6.8K + KNOWLEDGE.md 2.0K + policy 1.3K + 工具 schema (8-12 个工具) ~6K + MEMORY.md (per-user) ~1K
- ≈ 17K / 6.8K tokens

**主 agent 第 5 轮 ReAct (累积历史，单 task)**:
- 上述 17K + 4 轮 user/assistant/tool 消息 + 1 个 SKILL.md (已被 `ToolResultTruncationMiddleware` 压到 1-2K) + 1 个 CSV 预览 (5 行 handoff, ~1K) + 1 个 python_exec 输出 (~2K)
- ≈ 28K / 11K tokens

**子 agent `analyze_data` 第 1 轮** (派单后独立上下文):
- analyze_data.md 12.4K + KNOWLEDGE.md 2.0K + policy 1.3K + 工具 schema (8 个工具) ~7K
- ≈ 23K / 9K tokens

**子 agent `analyze_data` 第 8 轮 (5 步工作流 + 重试)**:
- 上述 23K + 7 轮历史 + 1 个 SKILL.md (压缩后 1-2K) + 多个 CSV 预览 + python_exec 输出
- ≈ 45-60K / 18-24K tokens

**结论**: 子 agent 后期最容易撞 32K 上限，主 agent 次之。首 token 延迟和心算错误率都会随上下文增长显著恶化。

## 现有压缩措施 (已落地，不要重复造轮子)

| 机制 | 位置 | 状态 | 节省 |
|---|---|---|---|
| `ToolResultTruncationMiddleware` | `V2InfraConfig.java:247` | enabled=true, tools=`load_skill_through_path` | 后续轮 SKILL.md 5-8K -> 1-2K |
| `ArtifactHandoffHook` | `ArtifactHandoffHook.java:73` | enabled; EXCLUDED_TOOLS 跳过 load_skill/arith/save_skill 等 | 10000 行 CSV -> 5 行预览 + 路径 (~100-500K) |
| `SkillRetrievalHook` (自动注入 SKILL) | `application.properties:112` | **enabled=false** (有意禁用) | 不再"净增"5-8K |
| 主 agent `.disableFilesystemTools().disableShellTool().disableMemoryTools()` | `HarnessA2aRunnerV2.java` | enabled | 减少工具数 |
| 子 agent 同上 + post-build `removeTool(session_*/plan_*)` | `SubagentRegistrar.java:412-423` | enabled | 同上 |
| `PerUserMemoryContextMiddleware` | per-user MEMORY.md 注入 | enabled | 不压缩，只隔离 |
| `harness.a2a.compaction.trigger=40 / keep=12` (消息滑窗) | `application.properties:77-78` | **注释了，未启用** | 0 |
| `harness.a2a.tool-eviction.max-chars=80000` (大 tool result 截断) | `application.properties:79` | **注释了，未启用** | 0 |

**关键缺口**: 消息滑窗和大 tool result 截断两块配置还注释着，**没有任何运行时代码在消费这两个属性** (v2 没有对应 bean)。需要新实现一个 `SlidingWindowMiddleware` 或确认 JAR 内置 hook 在读。

## 优化方案 (按优先级)

### P0 - 零代码改，纯瘦身 (1-2 天)

**P0.1 AGENTS.md 瘦身** (105 行 -> ~50 行)

当前问题：路由决策树、算术硬规则、CSV 路径纪律、注意事项段都有大量"为什么"和示例段，可移到配套文档。

动作：
- 删掉第 30-50 行「路由决策树」里的 `例: ...` 注释行 (LLM 看了决策树规则就能判，例子对它没用)
- 把「数据传递纪律」段 (91-96) 缩成 3 条 bullet (现在 6 行)
- 把「注意事项」段 (99-106) 缩成 4 条 bullet
- 「算术硬规则」段 (80-89) 删掉 LLM 心算风险解释 (88-89)，只留 3 条规则
- 工具表 (5-17) 改成单行格式：`| load_skill_through_path | 加载宽表指标/工具索引 skill 全文 |`

预计：6.8K -> 3.5K (-48%)

**P0.2 analyze_data.md 瘦身** (189 行 -> ~90 行)

当前问题：「路径 A/B/C」三段每段都有 6 步详解 + 完整调用示例，重复严重；「数据处理决策树」表与下面的代码块内容重叠。

动作：
- 路径 A/B/C 三段保留"适用场景 + 对应 skill + 工具调用顺序"四要素，**删掉每段重复的"复算百分比"步骤** (在「数据处理决策树」里统一说一次)
- 「数据处理决策树」表格 (90-101) 保留，删除下面 (105-121) 的代码块 (与表格重复)
- 「调 router_tool 的标准流程」段 (131-139) 删掉 (路径 B 已说过)
- 「CSV 路径纪律」段 (141-148) 缩成 3 条 (与 AGENTS.md 重叠)
- 「python_exec 失败重试纪律」段 (164-177) 删掉「harness 的 PythonExecRetryHook...」段 (这是给开发者看的)

预计：12.4K -> 6.5K (-48%)

**P0.3 generate_skill.md 瘦身** (109 行 -> ~55 行)

当前问题：「典型失败模式」表 (28-42) 4 个例子太啰嗦，LLM 看完参数硬规则就能判。

动作：
- 「典型失败模式」4 例缩成 2 行 bullet
- 「SKILL.md 正文结构」段 (60-103) 保留章节标题列表，删掉每章下面的"入参 JSON 示例/返回结果格式"详细说明 (移到 `docs/prompt/skill-template.md`)
- 「重要规则」段 (105-110) 删掉「参考系统提示中提供的工具调用链路详情」段 (自指)

预计：3.9K -> 2.0K (-49%)

**P0.4 KNOWLEDGE.md / repair_policy.yaml** 暂不动 (本来就不大)。

**P0 收益**: 主 agent 系统提示 17K -> 13K (-24%)；子 agent analyze_data 系统提示 23K -> 17K (-26%)。每轮都省。

---

### P1 - 启用已注释的中间件 + 扩展 truncation 工具列表 (2-3 天)

**P1.1 扩展 `ToolResultTruncationMiddleware` 的 tools 列表**

当前只有 `load_skill_through_path`。可加：
- `toolMetaInfo` - 返回参数 schema JSON，第二轮起已"用过"
- `sql_list` - 返回所有 sql_id 列表，第二轮起已"用过"
- `generate_csv_download_url` - 返回短链 URL，第二轮起已"用过"

动作：`application.properties:436` 改为
```
harness.a2a.tool-truncation.tools=load_skill_through_path,toolMetaInfo,sql_list,generate_csv_download_url
```

风险：低。这些工具的旧结果只是参考，LLM 第一轮看完决定调哪个工具后就不再需要完整 schema。

预计：多轮 ReAct 每轮省 1-3K。

**P1.2 启用消息滑窗 (`harness.a2a.compaction.trigger/keep`)**

先确认 v2 代码里有没有 bean 读这两个属性 (grep `compaction.trigger` / `compaction.keep` 全代码无命中，JAR 内可能有但不确定)。

如果 JAR 不读：实现 `SlidingWindowMiddleware` (v2 middleware) 在 `onReasoning` 时若 `messages.size() > trigger`，把最早的 `trigger - keep` 条 user/assistant/tool 消息折叠成 1 条 summary (用 light-classifier 模型生成，或简单截断到前 200 字 + "...")。

如果 JAR 读：直接取消注释，调参 `trigger=20, keep=8` (40/12 太大，对 32K 窗口来说前 12 条历史已经 20K+)。

风险：中。要确认不会丢 LLM 还在用的早期上下文 (比如 user 在第 1 轮说的"分析 Q2-1"这个意图，第 8 轮算达标率时还要回头看)。**保守做法是只折叠 tool result，不折叠 user/assistant 文本消息**。

预计：长 ReAct (>10 轮) 子 agent 上下文从 45-60K 封顶到 ~30K (-40%)。

**P1.3 启用 `tool-eviction.max-chars` (大 tool result 截断)**

当前 JAR 内 `ToolResultEvictionMiddleware` 已禁用 (见 MEMORY `tool_result_eviction_disabled.md`，原因是 Windows CreateProcess 8K 限制，但业务侧 ArtifactHandoffHook 已兜底)。

**不要重新启用** - ArtifactHandoffHook 已经把大 CSV 落 artifact，剩下的"大 tool result"只有 python_exec 的 print 输出 (DataFrame 大表 print)。这块可以单独写个 `PythonExecOutputTruncationMiddleware`，限制 stdout 超过 4K 时只保留头尾各 2K + 中间省略号。

预计：python_exec 大输出场景省 3-10K。

---

### P2 - 中等改造 (1-2 周)

**P2.1 SKILL.md 拆段：硬规则 + 例子**

当前每个 SKILL.md 5-8K，结构是「字段映射 + 公式 + 工作流 + 示例 1/2/3 + 注意事项」。示例段占比 30-40%。

改造：每个 skill 目录拆成两个文件：
- `SKILL.md` - 只保留硬规则 (字段映射 / 公式 / 工作流必走步) - 预计 3-4K
- `EXAMPLES.md` - 示例段

`load_skill_through_path` 工具加 `includeExamples` 参数 (默认 false)。LLM 第一次调用只拿硬规则；当用户问题对应不上硬规则里的字段时，第二轮主动调 `load_skill_through_path(name=..., includeExamples=true)` 拿例子。

预计：第一次 load 省 2-3K；后续轮 ToolResultTruncationMiddleware 压缩后省 0.5-1K。

**P2.2 KNOWLEDGE.md / repair_policy.yaml 改成按需注入**

当前 always-on (主+子 agent 都注入)。改成 `KnowledgeRetrievalHook` 关键词驱动：
- KNOWLEDGE.md 在用户问"部门/小组/应用/产品线/需求项"时注入
- repair_policy.yaml 在用户问"修复/返工/政策"时注入

但 `KnowledgeRetrievalHook` 现在只读 `knowledge-dynamic/`，需要扩展支持 `knowledge/` 也走关键词匹配。或者更简单：把 `KNOWLEDGE.md` 移到 `knowledge-dynamic/KNOWLEDGE.md` + 配 `knowledge-index.yaml` 关键词。

预计：主 agent 第 1 轮省 3.3K (KNOWLEDGE + policy)。

**P2.3 抽取共享段 (CSV 路径纪律 / 算术硬规则)**

AGENTS.md 和 analyze_data.md 都有「CSV 路径纪律」「算术硬规则」段，子 agent 还会再读一遍。提取成 `workspace/_shared/csv-discipline.md`，由 JAR WorkspaceContextHook 在子 agent 启动时也注入 (需要确认 JAR 是否支持 `_shared/` 目录约定)。

如果 JAR 不支持：直接把这两段从 analyze_data.md 删掉 (子 agent 沿用主 agent 已有的就行)，但要看子 agent 上下文是不是真的会继承主 agent 的 system prompt - **当前不会** (子 agent 用 spec.getInlineAgentsBody() 单独设)。所以这条得改 JAR 或在 SubagentRegistrar 里手动 append 共享段。

预计：子 agent 省 0.8K (与 P0.2 重叠，不能叠加算)。

**P2.4 子 agent 完成后只回传结论**

当前 `SubagentEventForwardingMiddleware` 把子 agent 的所有 `text_block_delta / tool_call_start` 镜像到父 SSE (前端展示用)。但**主 agent 的 LLM 上下文**是否会被注入子 agent 的完整 ReAct 轨迹？需确认 `AgentSpawnTool.execLocalSync` 给主 agent 回灌的是子 agent 的最终 Msg 还是全部中间步骤。

若是全部中间步骤：改 `agent_spawn` 工具的 `PostActingEvent` 只把子 agent 的最后一条 assistant Msg 设为 tool result，丢掉中间步骤。

预计：主 agent 派单后下一轮省 5-15K (取决于子 agent 跑了多少轮)。

---

### P3 - 较大改造 (2-4 周)

**P3.1 工具 description 共享模板**

`data_aggregate / data_top_n / data_compare_ratio / data_pivot / data_distribution` 5 个工具 description 高度相似 (都讲"csvPath 来源 / 维度无硬限制 / 调用方式")。可抽一个 `data_primitive_base` description，5 个工具只写差异部分。

预计：工具 schema 总量省 1-2K。

**P3.2 工具分组：业务工具按需暴露**

当前主 agent 注册了 8-12 个工具。可按用户意图分组：
- 查数意图：只暴露 `wide_table_query / clickhouse_query / sql_list / sql_registry_exec / load_skill_through_path`
- 分析意图：只暴露 `agent_spawn` (派单给 analyze_data)
- 保存意图：只暴露 `agent_spawn` (派单给 generate_skill)

实现：在 PreCall hook 里根据用户问题分类，动态调整 toolkit 暴露的工具集。框架是否支持运行时改 toolkit 待验证。

预计：工具 schema 省 3-5K。

**P3.3 跨 turn 记忆折叠**

长对话 (>5 turn) 时，把前面 N 条 user/assistant 折叠成"对话摘要: <LLM 生成>"。这是 P1.2 的升级版，但用 LLM 而不是简单截断。

风险：高。摘要可能丢业务关键参数 (部门名/版本号)。

---

## 收益估算汇总

| 方案 | 主 agent 第 1 轮 | 主 agent 第 5 轮 | 子 agent 第 1 轮 | 子 agent 第 8 轮 |
|---|---:|---:|---:|---:|
| 当前 | 17K / 6.8K tok | 28K / 11K tok | 23K / 9K tok | 45-60K / 18-24K tok |
| P0 全做 | 13K / 5.2K | 24K / 9.6K | 17K / 6.8K | 38-50K / 15-20K |
| P0+P1 全做 | 13K / 5.2K | 22K / 8.8K | 17K / 6.8K | 30K / 12K (滑窗封顶) |
| P0+P1+P2 全做 | 10K / 4K | 18K / 7.2K | 13K / 5.2K | 22K / 8.8K |

P0+P1 是性价比最高的阶段，**总体上下文减 30-40%**，对 32K 窗口的 glm-5.2 来说：
- 首 token 延迟从 3-5s 降到 2-3s
- 长 ReAct 不再撞 32K 上限
- 心算错误率显著下降 (上下文越短 LLM 越专注)

## 落地建议

1. **本周**: P0.1-P0.3 三份提示词瘦身，单独提交 PR，配合 E2E 测试 (现有 `docs/superpowers/specs/` 下的 case) 验证准确率不降。
2. **下周**: P1.1 (扩展 truncation 列表) + P1.2 (滑窗实现或确认 JAR 已读)。P1.2 风险较高，先用 `trigger=30, keep=15` 保守值。
3. **两周后**: 评估 P0+P1 效果 (trace 里看 LLM input 字节数)，决定是否做 P2。
4. P3 暂缓，等业务稳定后再做。

## 验证手段

- 启动后看 trace (L2TraceReader 抓的 LLM input 字节数) - 对比优化前后同一 case 的每轮字节数
- `docs/superpowers/specs/` 下 11.x / 6.x 等 E2E case 通过率不能降
- 子 agent analyze_data 8 轮后 LLM input 字节数 < 30K 是硬指标

## 不做的事

- 不重新启用 `response-cache.enabled` - 见 MEMORY `response_cache_deprecated.md`
- 不重新启用 JAR `ToolResultEvictionMiddleware` - 见 MEMORY `tool_result_eviction_disabled.md`
- 不重新启用 `SkillRetrievalHook` - 见 MEMORY `tool_chain_simplification.md` (PR3 已禁用)
- 不重新启用 analyze_data 子 agent plan mode - 见 MEMORY `plan_mode_disabled_analyze_data.md`

---

## 自评审 (2026/08/05)

写完上面的方案后回头审视，发现多处前提未验证、收益估算想当然、风险被低估。下面逐条挑出问题，并修正优先级。

### A. 前提未验证就写方案

**A.1 v2 路径没有 trace，"用 trace 量化基线"行不通**

`AiChatRestToolCallTrackingToDbHook` 类注释明确："仅 v1 /ai/chat 使用；v2 /v2/ai/chat 不创建 TraceSession，本 hook 在 `ctx.get(TraceSession.KEY)` 为 null 时自动 no-op" (`AiChatRestToolCallTrackingToDbHook.java:57-58`)。

我方案里说"启动后看 trace 对比优化前后字节数" - 但 v2 路径现在根本没开 TraceSession。**落地前必须先在 V2ChatStreamServiceImpl 里也建 TraceSession**，否则所有收益估算都没法验证。这条应该提到 P0 之前。

**A.2 没确认 prompt cache 状态**

全代码 grep `cache_control` / `promptCaching` / `cacheControl` **零命中**。但 glm-5.2 走 Ark `/api/coding` 是 Anthropic 协议 (见 MEMORY `ark-coding-channel-protocol.md`)，Anthropic 协议原生支持 `cache_control` prompt caching。

如果 Ark 通道支持且项目未启用：system prompt + 工具 schema 是稳定前缀，cache 命中后这部分**几乎免费**。此时瘦身的实际收益是"cache miss 时降低首 token 延迟"，而不是"每轮省 token"。**整个 P0 的收益估算逻辑要重写**。

落地前必须先确认：Ark `/api/coding` 是否支持 prompt caching？项目当前 `Model` bean 配置有没有传 `cache_control`？

**A.3 子 agent 完成后回灌主 agent 的内容没确认**

`SubagentEventForwardingMiddleware` 注释只说"把子 agent 事件镜像到父 SSE"，**没说主 agent 的 LLM 上下文是否被注入子 agent 中间步骤**。我 P2.4 方案里自己也写了"需确认 `AgentSpawnTool.execLocalSync` 给主 agent 回灌的是子 agent 的最终 Msg 还是全部中间步骤"，但没去验证就写了改造方案。

应该先读 `AgentSpawnTool.execLocalSync` (JAR 内) 或做实验 (派单后看主 agent 下一轮 LLM input 字节数有没有暴涨) 来确认。如果已经只回灌 finalMsg，P2.4 整条废掉。

### B. 收益估算有错

**B.1 P0 收益按行数估，实际 token 不一定线性**

我说"AGENTS.md 105 行 -> 50 行，省 48%"。但删的主要是 ASCII 短行 (例子、bullet)，留下的是中文密集的字段映射表 - 中文每字符 ≈ 1 token，ASCII 每字符 ≈ 0.25 token。删 50% 行数实际可能只省 25-30% token。

应该改成按**字节**估 (我已经有 wc -c 数据：AGENTS.md 6.8K -> 估 3.5K)，token 换算再打折 (中文混合按 2.5 字节/token 算，3.3K 差 -> 1.3K token)。原方案的 "-48%" 改成 "-30% 到 -40%"。

**B.2 P1.1 把 `toolMetaInfo` 加进 truncation 列表是错的**

`toolMetaInfo` 返回的参数 schema JSON，LLM 第二轮拼参调 `router_tool` 时**仍需要看完整 schema**知道每个参数叫什么、什么类型。压缩后 LLM 拼参错误率会上升，反而增加重试轮数。

`toolMetaInfo` 应排除。`generate_csv_download_url` 返回就是个短链 URL，本来就没几字节，加进去几乎无收益。**P1.1 实际只有 `sql_list` 有点收益** (整个 sql_id 列表，第二轮起确实不再需要)。

修正后 P1.1 收益从"每轮省 1-3K"降到"每轮省 0.3-0.5K"。

**B.3 P1.2 滑窗对 ReAct 流程危险，收益估算偏高**

ReAct 里 LLM 经常需要回看前几轮工具结果对账 (第 5 轮算达标率时要看第 3 轮 wide_table_query 返回的字段名)。折叠早期消息会让 LLM "失忆"，重做查询，**反而增加轮数和总 token 消耗**。

我提的"保守做法只折叠 tool result"已经被 `ToolResultTruncationMiddleware` 做了，再做滑窗只能折叠 user/assistant 文本消息 - 这才是真正的风险点。

子 agent 第 8 轮 45-60K 的估算也偏高 - 实际 ReAct 流程多轮历史大部分是 tool result (已被压缩)，纯 user/assistant 文本消息占比小。**滑窗最多省 5-10K，不是我说的 30K**。

修正后 P1.2 收益从"-40%"改成"-10% 到 -15%"，风险从"中"改成"高"。

### C. 风险被低估

**C.1 P0.2 删 analyze_data.md 路径 A/B/C 重复步骤风险高**

三种路径的 6 步详解虽然结构相似，但每段都包含该路径特有的细节：
- 路径 A 强调 `filters vs subqueryFilters` 的区别
- 路径 B 强调 `toolMetaInfo` 可选 + 拼 JSON 调 `router_tool`
- 路径 C 强调 `sql_registry_exec` 的 `params` 必须在 `sql_list` 返回的 schema 内

删掉重复步骤后，LLM 在路径 B 里看不到 `toolMetaInfo` 的使用提示，可能拼参失败。子 agent 是小参数 LLM，缺一步指引就卡住。

**应该改成**: 保留三段的"特有细节"，只删真正重复的"复算百分比 / 回复用户中文 / 数据来源标注"等公共步骤。预计省 30% 而非 48%。

**C.2 P0.3 删 generate_skill.md 失败模式表风险高**

`generate_skill` 子 agent `maxIters=3`，没机会重试。失败模式表 (4 例) 是防 LLM 调 `save_skill` 时常见错误的护栏 - 删掉后 LLM 写中文 skill_name 或含连字符的名字，调 `save_skill` 失败，3 轮用完没保存成功。

应该**保留失败模式表**，删其他段 (如"生成步骤"的详解 + "重要规则"的自指段)。预计省 30% 而非 49%。

**C.3 P2.1 SKILL.md 拆段，加 includeExamples 参数负担 LLM 决策**

`load_skill_through_path` 是 JAR 内置工具 (v2 代码里没有它的实现)，加参数需要改 JAR 或写包装层。更重要的是：让 LLM 做"先看硬规则判断够不够、再决定要不要拿例子"的二次决策，对小参数 LLM 是负担。

应该改成: **直接在 SKILL.md 里把"示例 1/2/3"段标成 `> 以下示例可选` 并放在文档末尾**，让 `ToolResultTruncationMiddleware` 的 `compactMarkdown` 把 `>` quote block 自动丢掉 (现在的实现就是丢 quote block)。**零代码改**，只是写 SKILL.md 时把示例改成 `>` 引用块。

**C.4 P2.4 子 agent 回传结论依赖未确认的前提**

见 A.3。整条暂缓，先做实验确认。

### D. 遗漏的事

**D.1 没考虑 frontend 输出的 SSE 流量**

`SubagentEventForwardingMiddleware` 把子 agent 的所有 `text_block_delta / tool_call_start` 镜像到父 SSE - 这是给前端展示用的，**不进 LLM 上下文**。但前端 ChatPanel 90s watchdog (见 MEMORY `frontend_stuck_stream_watchdog.md`) 的卡顿问题可能是这条 SSE 流太大、前端渲染慢，跟 LLM 上下文长度无关。

应该先用 trace 区分"LLM 推理慢" vs "SSE 流量大前端渲染慢" vs "上下文长 LLM 心算错" - 三个问题混在一起，光压缩上下文不解决另外两个。

**D.2 没考虑换基础设施的对比**

32K 上下文撞瓶颈时，应该对比两条路：
- **路 X**: 按本方案压缩 prompt (P0+P1 减 30-40%)
- **路 Y**: 换更大上下文窗口的模型 (glm-5.2 -> 128K 版本，如果 Ark 提供)

路 Y 零代码、立竿见影，但增加每 token 成本。本方案没做这个对比，可能花两周做 P0+P1 还不如直接换模型。

**D.3 没考虑主 agent 的工具 schema 总量**

我盘点时估工具 schema 5-8K，但没实际打印 toolkit 注册后 LLM 看到的 tools JSON。应该先 dump 一份主 agent 的 tools schema 实际字节数 (在 PreReasoning hook 里 `e.getTools()` 序列化)，再决定要不要做 P3.1 (description 共享模板) 和 P3.2 (按意图分组)。

**D.4 没考虑 SKILL.md 之间的内容重复**

`wide_table_q2_1_metrics` / `trace_recent_metrics` / `trace_recent_stats_metrics` 三个 skill 都有「filters vs subqueryFilters 区别」「CSV 路径硬规则」「arith 复算」段，每段都重复 0.5-1K。应该抽到一个 `skills/_common.md` 共享文件，每个 SKILL.md 顶部 `> 本 skill 假定你已加载 _common.md`，主 agent 第一次 load 任何 skill 时先 load `_common.md`。

收益: 每个 SKILL.md 省 1-2K。比 P2.1 拆段更直接。

### E. 修正后的优先级

| 优先级 | 动作 | 估收益 | 风险 |
|---|---|---:|---|
| **P0** | 在 V2ChatStreamServiceImpl 启用 TraceSession (改 1 行) | 0 (前提条件) | 低 |
| **P0** | 确认 Ark `/api/coding` 是否支持 prompt caching，若支持配置 cache_control | 可能省 50%+ 每轮成本 | 低 |
| **P0** | dump 一份主 agent tools schema 实际字节，确认工具 schema 是不是真瓶颈 | 0 (量化前提) | 低 |
| **P0** | 实验确认 AgentSpawnTool 给主 agent 回灌的是 finalMsg 还是中间步骤 | 0 (前提条件) | 低 |
| **P1** | P0.1-P0.3 提示词瘦身 (保留失败模式表、保留三路径特有细节) | -25% 到 -35% | 中 |
| **P1** | P1.1 只加 `sql_list` 到 truncation 列表 (排除 toolMetaInfo / generate_csv_download_url) | -0.3K 到 -0.5K/轮 | 低 |
| **P2** | D.4 抽 `skills/_common.md` 共享段 | -1K 到 -2K/skill | 中 |
| **P2** | C.3 改写 SKILL.md 把示例段标成 `>` quote block 让 compactMarkdown 自动丢 | -1K 到 -2K/首次 load | 低 |
| **P3** | P1.2 滑窗 (折叠纯 user/assistant 文本，不动 tool result) | -5K 到 -10K (长 ReAct) | 高 |
| **P3** | P2.4 子 agent 只回传结论 (前提: A.3 确认主 agent 现在看到中间步骤) | -5K 到 -15K | 中 |
| **暂缓** | P2.2 KNOWLEDGE 改按需注入 (always-on 才 2K，不值得动) | -2K | 高 |
| **暂缓** | P2.3 抽共享段 (需改 SubagentRegistrar 代码) | -0.8K | 中 |
| **暂缓** | P3.1/P3.2 工具 description 共享 / 按意图分组 (需改 JAR 或动态 toolkit) | -1K 到 -3K | 高 |

### F. 修正后的结论

原方案的"P0 零代码瘦身 30-40%"过于乐观。实际：

1. **必须先做 4 个前提验证** (启用 v2 trace / 确认 prompt cache / dump tools schema / 确认子 agent 回灌内容)，否则后续所有动作都是盲打。
2. **prompt cache 是最大变量** - 如果 Ark 支持，开了之后 system prompt + 工具 schema cache 命中几乎免费，瘦身的边际收益大幅下降，方案重心应转向"减少每轮变动的 tool result / history"。
3. **P0 提示词瘦身仍是高 ROI**，但收益估到 -25% 到 -35% 更现实，且必须保留失败模式表、保留三路径特有细节。
4. **滑窗 (P1.2) 风险被低估**，应降级到 P3。
5. **新增 D.4 抽 skills/_common.md** - 比拆 SKILL.md 段更直接。

修正后建议执行顺序：先花 1-2 天做 4 个前提验证 (P0)，根据结果再决定 P1 走瘦身还是走 prompt cache。

---

## P0 阶段实施结论 (2026/08/05)

四个前提验证全部落地，结论如下：

### P0-1 V2 启用 TraceSession ✅

`V2ChatStreamServiceImpl.stream()` 里复刻 v1 `ChatStreamServiceImpl` 的 trace 流程 (13 处改动)：
- 新增 `TraceSession traceCtx`，在 `RuntimeContext` 中以 `TraceSession.KEY` 注入
- `AiChatRestToolCallTrackingToDbHook` (priority=47) 不再 no-op：v2 路径的 PRE/POST_CALL、PRE/POST_REASONING、PRE/POST_ACTING、ERROR 事件会落 `TraceEventRecord`
- `ModelCallEndEvent` 走 `recordUsage` 累加 token
- `emitter.onTimeout / handleStreamError / handleStreamSuccess` 分别调 `markTimeout / markError / markSuccess`
- `buildCleanup` 末尾 `traceAssembler.assemble(ctx.traceCtx)` + `traceQueue.offer(trace)` 异步入写 ClickHouse

`mvn clean compile -DskipTests` BUILD SUCCESS。重启后端 + 发一次真实 chat，就能在 `trace_event` 表查 PRE_REASONING.input_messages 字节数。

### P0-2 确认 prompt cache ✅

`application.properties` 当前 `glm-main` 配置是 **DeepSeek + OpenAI 协议** (不是 MEMORY 里记的 Ark /api/coding + glm-5.2，那条被注释了)。DeepSeek API 自动 prompt caching，**无需 `cache_control` 字段**，稳定前缀 (system prompt + 工具 schema) 命中后几乎免费。

含义：
- 系统提示词瘦身 (P0.1-P0.3) 的边际收益从「每轮省 token」变成「降低 cache miss 时首 token 延迟」
- 但 P0.1-P0.3 仍要做：DeepSeek cache 有 TTL (默认 1h)，长会话或冷启动时 miss 的成本仍在；瘦身后 cache miss 时 LLM 输入更短，首 token 更快，心算错误率也更低
- **真正的瓶颈会从「system prompt 体积」转向「每轮变动的 tool result + ReAct 历史」** — 这块继续靠 `ToolResultTruncationMiddleware` + `ArtifactHandoffHook` 兜底，不需要新代码

### P0-3 量化 tools schema ⏸ 跳过

需要重启后端 + 发真实 chat 请求才能从 ClickHouse 拉到 trace。用户决定跳过 P0-3 直接进 P1，等 P1 落地后一起跑 E2E 对比优化前后字节数。

### P0-4 确认子 agent 回灌内容 ✅

读 harness sources jar 里的 `AgentSpawnTool.java` (行 613-686 `execLocalSync` + 行 706-766 `execWithTimeoutPromotion`)：

- `execLocalSync` 三条路径 (streamEvents / SubagentEventBus / 非流式) 最终都返回 `Mono<Msg>`
- `execWithTimeoutPromotion` 把 Msg 拼成 `header + "\nstatus: ok\nreply:\n" + msg.getTextContent()` 作为 ToolResult 回主 agent
- 子 agent 中间事件 (text_block_delta / tool_call_start) 通过 `taggedEmitter` 转发到 parent emitter，**仅用于 SSE/前端展示，不进主 agent LLM context**

**结论**：P2.4 「子 agent 只回传结论」框架已经天然做到，无需再改。从方案里划掉。

### 修正后的方案表

| 项 | 状态 | 备注 |
|---|---|---|
| P0-1 V2 启用 TraceSession | ✅ 完成 | 13 处改动，BUILD SUCCESS |
| P0-2 确认 prompt cache | ✅ 完成 | DeepSeek + OpenAI 协议，自动 cache |
| P0-3 量化 tools schema | ⏸ 跳过 | 等 P1 一起 E2E |
| P0-4 确认子 agent 回灌 | ✅ 完成 | 框架天然只回灌 final Msg，P2.4 删除 |
| P1 提示词瘦身 | 🚧 进行中 | P0.1-P0.3 + P1.1 sql_list 加入截断 |

---

## P2 阶段实施结论 (2026/08/05)

继续落地 4 项 (P1.2 调参 + P2.1 示例段标 `>` + D.4 抽 _common + P2.3 SubagentRegistrar 注入):

### P1.2 调小 compaction 参数 ✅

**重要发现**: JAR 内置 `CompactionMiddleware` (harness sources jar `io/agentscope/harness/agent/middleware/CompactionMiddleware.java`)，v2 `HarnessA2aRunnerV2.java:257-260` 已经 hard-code `triggerMessages=40 / keepMessages=12` 在跑。原方案文档"properties 注释了=未启用"是误判 - 代码不读 properties，直接 hard-code。

改动: `triggerMessages(40) -> 20`, `keepMessages(12) -> 8` (匹配 32K 窗口，前 12 条历史已 20K+ 偏大)。

### P2.1 SKILL.md 示例段标 `>` quote block ✅

7 个 SKILL.md 中 5 个有「示例 1/2/3」段，逐行加 `> ` 前缀让 `ToolResultTruncationMiddleware.compactMarkdown` 在第二轮起自动丢。`data_primitives` 的「调用示例」段同样处理。`tool_index` 无示例段，不动。

### D.4 抽 `skills/_common/SKILL.md` ✅

新建 `workspace/skills/_common/SKILL.md` (1.5K, 35 行)，含 5 段共享硬规则: CSV 路径 / arith 复算 / 空结果 / 直接调用 / python_exec 重试。

5 个 `*_metrics` SKILL.md 删除重复段 (inline CSV 路径硬规则 / 注意事项里的"禁止心算"+"空结果")，顶部加引用提示 `> 共享硬规则...已在主 agent AGENTS.md 和子 agent sysPrompt (SubagentRegistrar 自动注入 skills/_common/SKILL.md) 中, 本 skill 不重复`。

各 SKILL.md 字节变化:

| SKILL.md | 原 | 现 | 减少 |
|---|---:|---:|---:|
| wide_table_q2_1_metrics | 7614 | 6190 | -1424 (-19%) |
| trace_recent_metrics | 7570 | 6804 | -766 (-10%) |
| trace_recent_stats_metrics | 6301 | 5322 | -979 (-16%) |
| q2_1_by_dept_version_metrics | 5999 | 4986 | -1013 (-17%) |
| q2_1_metrics_download_demo | 7676 | 7662 | -14 (示例段标 `>`, 文件本身没瘦) |
| data_primitives | 5305 | 5319 | +14 (仅标 `>`, 没删段) |
| tool_index | 4464 | 4464 | 0 (无示例段, 不动) |

### P2.3 SubagentRegistrar 注入共享段 ✅

`SubagentRegistrar.java` 改动:
- 新增 `commonRules` 字段 (final String)
- 构造时 `loadCommonRules(workspace)` 读 `skills/_common/SKILL.md`，剥掉 YAML frontmatter
- `registerSubagentFromSpec` 中 sysPrompt 拼接: `(commonRules + "\n\n---\n\n" + basePrompt)` (commonRules 为空时降级为原 basePrompt)
- 文件缺失时 graceful degradation (warn + 空字符串)

`analyze_data.md` 删除「CSV 路径纪律」段内容 + 「python_exec 失败重试纪律」整段 (现在由 _common 自动注入)，保留本 skill 特有规则 (pip install 限制等)。

### 净收益估算 (单 subagent 5 轮 ReAct, 加载 1 skill)

| 阶段 | sysPrompt 每轮 | skill 第 1 轮 | skill 第 2-5 轮 (compact) | 5 轮总计 |
|---|---:|---:|---:|---:|
| 优化前 | 7.9K | 7.6K | 4K | 39.5 + 7.6 + 16 = 63.1K |
| 优化后 | 8.5K (含 _common 1.5K + analyze_data 7.0K) | 6.2K | 2.5K (`>` 示例段被丢) | 42.5 + 6.2 + 10 = 58.7K |
| 净省 | +0.6K (sysPrompt 多) | -1.4K (skill 小) | -1.5K/轮 (示例段丢) | **-4.4K (-7%)** |

加 2 个 skill 时收益放大到 -8K (~13%)。`triggerMessages=20` 让长 ReAct 8 轮以上触发摘要压缩，再省 5-10K。

### 修正后的方案表

| 项 | 状态 | 备注 |
|---|---|---|
| P0-1 V2 启用 TraceSession | ✅ 完成 | 13 处改动，BUILD SUCCESS |
| P0-2 确认 prompt cache | ✅ 完成 | DeepSeek + OpenAI 协议，自动 cache |
| P0-3 量化 tools schema | ⏸ 跳过 | 等 E2E 一起 |
| P0-4 确认子 agent 回灌 | ✅ 完成 | 框架天然只回灌 final Msg，P2.4 删除 |
| P1 提示词瘦身 | ✅ 完成 | AGENTS.md -39%, analyze_data.md -36%, generate_skill.md -13% |
| P1.1 sql_list 加入截断 | ✅ 完成 | 排除 toolMetaInfo / generate_csv_download_url |
| P1.2 调小 compaction 参数 | ✅ 完成 | 40/12 -> 20/8 (JAR CompactionMiddleware 已在跑) |
| P2.1 SKILL.md 示例段标 `>` | ✅ 完成 | 5 个 metrics SKILL.md + data_primitives |
| D.4 抽 skills/_common | ✅ 完成 | 1.5K 共享段, 5 个 SKILL.md 删重复 |
| P2.3 SubagentRegistrar 注入 | ✅ 完成 | commonRules 字段 + loadCommonRules + sysPrompt prepend |
| P3 P1.2 滑窗 (折叠 user/assistant) | ❌ 不做 | 风险高, ToolResultTruncationMiddleware 已兜底 tool result |
| P3 P2.4 子 agent 只回传结论 | ❌ 不做 | 框架天然做到 (P0-4 验证) |
| 暂缓 P2.2 KNOWLEDGE 按需注入 | ⏸ 暂缓 | always-on 才 2K, 不值得动 |
| 暂缓 P3.1/P3.2 工具 description 共享 / 按意图分组 | ⏸ 暂缓 | 需改 JAR 或动态 toolkit, 风险高 |
