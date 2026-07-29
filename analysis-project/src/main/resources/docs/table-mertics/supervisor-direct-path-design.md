# Supervisor 直跑宽表指标 / 简单查数方案

> 在保留 `agent_spawn` 子智能体派单体系的前提下,把两类高频低复杂度场景 --
> **宽表指标加工** 与 **简单查数** -- 上收到 Supervisor 直接执行,跳过 subagent 启动开销。
> 复杂分析(缺陷密度 / 跨表 / 探索式)继续走 `agent_spawn(analyze_data)`。

## 1. 背景与问题

### 1.1 现状链路

一次 "杭州开发二部 7月版 Q2-1 完成率" 查询实测经过的环节(取自前端活动日志):

```
09:43:05  启动 Supervisor
09:43:18  load_skill_through_path(wide_table_q2_1_metrics)   ~13s (含 LLM 路由判断)
09:43:23  wide_table_query                                     ~5s
09:43:28  python_exec  (CSV 路径错,重试 1)                      ~6s
09:43:34  python_exec  (重试 2)                                 ~8s
09:43:42  python_exec  (重试 3)                                 ~7s
09:43:49  python_exec  (成功)                                   ~5s
09:43:54  arith × 2                                             ~5s
09:44:00  agent_spawn 返回                                      ~6s
09:44:04  完成
总耗时 ~60s
```

整个链路里:
- **subagent 启动 + Toolkit 构建 + hook 链装配**: 固定 ~10-15s,与查询复杂度无关
- **CSV 路径 bug 导致的 4 次 python_exec 重试**: ~20s (独立 bug,与本方案正交,见 §6)
- **真实业务计算**(wide_table_query + arith + 最后一次 python_exec): ~15s

### 1.2 痛点

1. **链路过长**: 简单指标提取本应是「查数 → 算数 → 回复」三步,实际却要 6-8 跳,其中 subagent bootstrap 是固定开销
2. **agent_spawn 体系被滥用**: 简单查数 / 单指标提取本不需要 subagent 的上下文隔离与 5 步工作流,但当前路由树把所有「Q2-1/完成率」都强派给 `analyze_data`
3. **CSV 路径 bug 独立存在**: 4 次重试用掉 ~20s,与架构无关,Supervisor 直跑也会复现 -- 需单独修(见 §6)

### 1.3 不改的边界

- **agent_spawn 体系保留**: 复杂分析仍需要 subagent 隔离上下文
- **per-user 隔离机制不动**: `ArtifactAccessMiddleware` / `PerUserMemoryGetTool` / per-user MEMORY.md 全部保留
- **`WideTableMetricsTool` 不动**: 工具签名与 SQL 注入防护保持现状
- **`analyze_data` 子 agent 保留**: 复杂分析(含「对比/趋势/分布/归因」等分析意图的指标加工)继续派单给它
- **`query_data` 子 agent 删除**: spec 文件直接删,Supervisor 直跑路径覆盖其全部职责(已确认,见 §4.5)
- **CSV 路径 bug 已独立修复**: 不再是本方案阻塞项(见 §6)

## 2. 设计目标

Supervisor 直接处理两类高频低复杂度场景,**跳过 agent_spawn**:

| 场景 | 触发词 | 直跑路径 |
|---|---|---|
| 宽表指标加工 | Q2-1/Q2-2/Q3/Q4/完成率/达标率/合格率/通过率/打分状态 | `load_skill_through_path` → `wide_table_query` → `python_exec` → `arith` |
| 简单查数 | 最值/排序/近期/明细/"X指标大于Y"/"X的数据" | `wide_table_query` → `arith`(若需百分比) |

`agent_spawn` 仅在以下情况触发:
- "分析/趋势/对比/统计/分布/均值/标准差/分位数/相关系数/同比/环比/改进建议/报告"
- 缺陷密度分析(需要 GROUP BY + 多表 join)
- 跨表对比 / 探索式分析
- `agent_spawn(generate_skill)` 保存技能

## 3. 目标架构

### 3.1 直跑链路

```
用户提问 "杭州开发二部 7月版 Q2-1 完成率"
  ↓
Supervisor (AGENTS.md 路由决策树命中"宽表指标加工")
  ↓ 直跑,无 agent_spawn
Supervisor:
  1. load_skill_through_path("wide_table_q2_1_metrics")  ← 读字段映射 + 公式模板
  2. wide_table_query(table, fields, filters)             ← CSV artifact 自动落盘
  3. python_exec(pd.read_csv + pandas 算指标)              ← 业务计算
  4. arith(op="pct", numbers=[分子, 分母])                ← BigDecimal 复算
  5. 中文回复(4 个数字 + 业务解读)
预期总耗时: 15-20s (省 subagent 启动 ~10-15s)
```

### 3.2 主 agent 工具注册表

| 工具 | 来源 | 分组 | 状态 |
|---|---|---|---|
| `agent_spawn` | JAR 自动 | - | 保留 |
| `arith` | V2ToolConfig | ungrouped | 保留(始终可见) |
| `python_exec` | V2ToolConfig | `python_exec` group | 保留(grouped,LLM 按需 activate) |
| `wide_table_query` | V2ToolConfig | ungrouped | **新增** |
| `skill_manage` (含 `load_skill_through_path`) | `enableSkillManageTool` | - | 保留 |
| `memory_get` (PerUserMemoryGetTool) | post-build replace | - | 保留 |
| `reset_equipped_tools` | meta-tool | - | 保留 |

工具总数: 6 → 7(仍在 qwen3:8b 可接受范围,>8 才明显退化)。

### 3.3 路由决策树(AGENTS.md 修改后)

```
用户意图是什么?
├─ ① 含「分析/趋势/对比/统计/分布/均值/标准差/分位数/相关系数/同比/环比/改进建议/报告」任一关键词
│     (无论是否同时含 Q2-x/完成率等指标词, 只要有分析意图就派 analyze_data)
│  -> agent_spawn(analyze_data)
│     例: "Q2-1 完成率 + 与 8月版对比" / "Q2-1 趋势分析" / "Q2-1 各部门分布"
│
├─ ② 仅含「Q2-1/Q2-2/Q3/Q4/完成率/达标率/合格率/通过率/打分状态/打分指标」
│     + 维度(部门/产品线/统计组) + 版本, 无任何分析/对比/趋势意图
│  -> ★ Supervisor 直跑
│     (load_skill_through_path + wide_table_query + python_exec + arith)
│     例: "杭州开发二部 7月版 Q2-1 完成率"
│
├─ ③ 简单查数(单维度筛选,无分析/计算意图):
│     - "X部门的数据" / "X指标的明细" / "X最多的" / "X大于Y的" / "近期X"
│  -> ★ Supervisor 直跑
│     (wide_table_query + arith 若需百分比)
│
├─ ④ 缺陷密度 / 跨表 join / 探索式分析
│  -> agent_spawn(analyze_data)
│
└─ ⑤ 用户说「保存为skill」「保存这个流程」「生成技能」
   -> agent_spawn(generate_skill)
```

**关键决策点**: 「对比/趋势/分布/归因」等分析意图优先级最高(分支 ①),即使提问里同时含 `Q2-1`/`完成率` 等指标词,只要带分析意图就派 `analyze_data` -- 因为这类需求需要 5 步工作流 + 上下文隔离,Supervisor 单轮 `wide_table_query` 兜不住。

## 4. 关键改动点

### 4.1 V2ToolConfig.v2ToolGroupAdapter -- 主 agent 工具组

`analysis-project/src/main/java/com/agentscopea2a/v2/config/V2ToolConfig.java:165-196`

```java
// 新增: WideTableMetricsTool 注册为 ungrouped (始终可见)
// 原因: 与 arith 同理 -- 简单查数/指标提取是 Supervisor 直跑路径的核心工具,
// 若放进 group 则 LLM 看不到, 只能继续派 agent_spawn(analyze_data), 改动失效.
WideTableMetricsTool wt = wideTableMetricsToolProvider.getIfAvailable();
if (wt != null) {
    b.tool(wt);  // ungrouped
    log.info("V2ToolGroupAdapter: registered WideTableMetricsTool (ungrouped, always available)");
}
```

构造函数需新增 `ObjectProvider<WideTableMetricsTool>` 参数。

### 4.2 AGENTS.md -- 路由决策树

`analysis-project/src/main/resources/workspace/AGENTS.md:5-50`

- 删除当前路由树第 2 条「包含完成率/达标率/... → agent_spawn(analyze_data)」
- 删除当前路由树第 3 条「仅查询X季度X部门的数据 → agent_spawn(query_data)」
- 新增第 1 条「宽表指标加工 → Supervisor 直跑」
- 新增第 2 条「简单查数 → Supervisor 直跑」
- 修改「注意事项」段:删除「你自己没有任何数据查询工具」那句,改为「宽表指标加工与简单查数直接调 wide_table_query;复杂分析派 agent_spawn(analyze_data)」
- 「数值计算硬规则」表里「完成率/达标率... → 派 analyze_data」改为「→ Supervisor 直跑 arith + python_exec」

### 4.3 主 agent Hook / Middleware 链(已验证 2026/07/26)

Supervisor 直跑 `wide_table_query` + `python_exec` 后,以下 hook/middleware 忡须在主 agent 上生效。**已确认全部满足**:

| Hook / Middleware | 作用 | 验证结果 |
|---|---|---|
| `ArtifactHandoffHook` | 把 wide_table_query 的 markdown 表落 CSV artifact,改写为「预览 + 📦 CSV 路径」给 LLM | ✅ `HarnessAgentPartsConfig.harnessHooks` line 153-157 注入,priority=12 |
| `PythonExecRetryHook` | python_exec 失败时追加「✦ 失败行 / ✦ 异常类别 / ✦ 常见修法」提示 | ✅ `HarnessAgentPartsConfig.harnessHooks` line 158-162 注入,priority=13 |
| `ArtifactAccessMiddleware` | cross-tenant path guard,防止 python_exec 读他人 artifact | ✅ `HarnessAgentPartsConfig.harnessMiddlewares` line 114 无条件注入 |
| `PythonExecAccessMiddleware` | python_exec 调用前路径扫描 | ✅ `HarnessAgentPartsConfig.harnessMiddlewares` line 127-131 `ObjectProvider.getIfAvailable()`;`V2InfraConfig.pythonExecAccessMiddleware` 是无条件 @Bean,所以总是可用 |

注入链路: `V2InfraConfig` 定义 4 个 @Bean → `HarnessAgentPartsConfig.harnessHooks` / `harnessMiddlewares` 用 `ObjectProvider.getIfAvailable()` 收集到 `List<Hook>` / `List<MiddlewareBase>` @Bean → `HarnessA2aRunnerV2` 构造函数注入这两个 List → `builder.hook(...)` / `builder.middlewares(...)` 转发给 HarnessAgent.Builder。

### 4.4 Skill 加载确认(已验证 2026/07/26)

Supervisor 需要 `load_skill_through_path` 来加载 `wide_table_q2_1_metrics` skill 全文。**已确认通过 `HarnessSkillMiddleware` 自动注册,无需额外配置**:

注册链路(全在 JAR source override 文件里):
1. `HarnessAgent.Builder.build()` 在 line 2396 检查 `!orderedSkillRepos.isEmpty() && !disableDynamicSkills` -- 主 agent 满足(默认有 workspace/skills/ 仓库,且 `disableDynamicSkills` 未被显式调用)
2. 装配 `HarnessSkillMiddleware`(line 2434-2441),传入 `orderedSkillRepos + agentToolkit + skillFilter + visibilityFilter + stager + shellPolicy`
3. `HarnessSkillMiddleware.onSystemPrompt`(每次 LLM call 前)调用 `SkillRuntime.install()`,后者 idempotently 注册 `load_skill_through_path` 到 `agent.getToolkit()`(注意:不是构造函数传的 toolkit,而是 agent runtime 的 live toolkit -- 因为 HarnessAgent build 时会深拷贝 toolkit)
4. `load_skill_through_path` 由 `SkillLoadTool` 实现(`io/agentscope/harness/agent/skill/runtime/SkillLoadTool.java:54`),`TOOL_NAME = "load_skill_through_path"`(line 56)

关键证据: `HarnessSkillMiddleware.java` 类 javadoc line 58-70 明确说明:
> Install the catalog into the SkillRuntime, which (idempotently) registers the `load_skill_through_path` tool on the agent's runtime toolkit.
>
> Toolkit note: the toolkit constructor parameter is accepted for API compatibility but is not used for runtime tool registration. Instead, onSystemPrompt always installs into agent.getToolkit()...

**结论**: 主 agent 与所有子 agent 走同一个 build 路径,`load_skill_through_path` 在主 agent 上自动可用。`enableSkillManageTool(skillManageConfig)` 注册的是另一个工具 `skill_manage` + `propose_skill`(用于 propose/promote/curator 流程),与 `load_skill_through_path` 是两条独立路径。

### 4.5 query_data 子 agent 处理(已决定: 删除 spec)

用户确认: 「去除 query_data 能力,Supervisor 直接取代」。

**执行动作**: 直接删除 `analysis-project/src/main/resources/workspace/agent-subagents/query_data.md`。

`SubagentRegistrar` 已有 graceful degradation 逻辑(spec 文件缺失则跳过注册),不需要改 Java 代码。AGENTS.md 路由树里所有派单 `query_data` 的分支同步改为「Supervisor 直跑」或派 `analyze_data`(见 §3.3)。

回滚预案: 若 Supervisor 直跑路径在 Phase 3 E2E 验证失败,git revert 删除 spec 的 commit 即可恢复 `query_data.md` -- 这是单文件 git 历史,回滚成本低。

## 5. 工具污染风险评估

| 维度 | 评估 |
|---|---|
| 工具数变化 | 6 → 7(qwen3:8b 在 ≤8 工具时选择准确率 >90%,>8 才明显退化) |
| LLM 选错工具概率 | `wide_table_query` 与 `quality_query_by_*` 名字相近,可能误选后者(mock 数据) |
| 缓解:工具描述 | `wide_table_query` 的 `@Tool` description 已强调"通用 GaussDB 宽表查询",可再加一句"简单查数也走这里" |
| 缓解:AGENTS.md | 路由树明确「Supervisor 直跑」分支,prompt 里硬编码优先级 |
| 缓解:retry hook | `PythonExecRetryHook` 已有,失败自动给修法提示 |
| 缓解:SkillRetrievalHook 已禁用 | 不会自动加载无关 skill 干扰 LLM 决策 |

## 6. CSV 路径 bug(已独立修复)

用户确认: 「已修复,不用管」。本方案不再作为阻塞项,Phase 1 验证时如再复现,单独排查。

## 7. 迁移步骤

### Phase 1: 工具注册(无行为变更)

- [ ] `V2ToolConfig.v2ToolGroupAdapter` 新增 `WideTableMetricsTool` ungrouped 注册
- [ ] 启动后 log 验证 `Tool registered: wide_table_query` 出现在主 agent toolkit 列表
- [x] 确认主 agent 的 `hooks` / `middlewares` 列表包含 §4.3 表中的 4 个组件(2026/07/26 验证通过)
- [x] 确认 `load_skill_through_path` 在主 agent 可用(2026/07/26 验证通过 -- `HarnessSkillMiddleware` 自动注册)
- [ ] E2E 回归: 现有 `agent_spawn(analyze_data)` 路径不受影响

### Phase 2: 路由切换 + query_data 删除

- [ ] `AGENTS.md` 路由树改: 宽表指标 + 简单查数走 Supervisor 直跑;含「对比/趋势/分布」等分析意图的派 `analyze_data`(见 §3.3)
- [ ] `analyze_data.md`: 加一段说明「现在主要承接复杂分析,简单指标提取已上收到 Supervisor」
- [ ] 删除 `agent-subagents/query_data.md` spec 文件
- [ ] 启动后 log 验证 `SubagentRegistrar: loaded N subagent specs` 里 N 减少 1,无 `query_data` 注册日志

### Phase 3: E2E 验证

- [ ] 用例 A: "杭州开发二部 7月版 Q2-1 完成率" -- 应走 Supervisor 直跑,预期 <30s
- [ ] 用例 B: "杭州开发二部 7月版 数据明细,按完成时间排序" -- 应走 Supervisor 直跑
- [ ] 用例 C: "分析各部门质量分分布" -- 应走 `agent_spawn(analyze_data)`,保留原行为
- [ ] 用例 D: "对比 Q1 Q2 杭州一部缺陷密度" -- 应走 `agent_spawn(analyze_data)`
- [ ] 用例 E: "保存为 skill" -- 应走 `agent_spawn(generate_skill)`
- [ ] 用例 F: "杭州开发二部 7月版 Q2-1 完成率 + 与 8月版对比" -- 含「对比」关键词,应走 `agent_spawn(analyze_data)`,不走 Supervisor 直跑

## 8. 风险与回滚

| 风险 | 缓解 |
|---|---|
| Supervisor 工具数增加,LLM 选错工具 | 工具描述 + AGENTS.md 路由树双保险 |
| 主 agent 上下文被原始数据污染(30 行 CSV 预览) | 30 行 × 10 列 ~5KB,远低于 context 上限;>200 行才需 subagent |
| 删除 query_data 后旧用户输入仍走 query_data 路径(幻觉派单) | `SubagentRegistrar` 启动 log 会显示 spec 列表,无 `query_data` 时 agent_spawn("query_data") 会直接报「未知子 agent」错误给 LLM,LLM 会自然降级到 Supervisor 直跑 |

回滚路径(单文件级):
- 回滚 `V2ToolConfig.java`: 去掉 `WideTableMetricsTool` 注册(1 处改动)
- 回滚 `AGENTS.md`: 路由树退回原版
- 不需要数据库迁移 / 不需要重启框架 / 不影响其他子 agent

## 9. 待确认

1. **python_exec 在主 agent 上的暴露方式**: 当前是 grouped(LLM 按需 activate),还是改为 ungrouped(始终可见)?
   - 建议保持 grouped,避免工具数膨胀;LLM 在 `wide_table_query` 返回 CSV 后会自然 activate `python_exec` group。

2. **wide_table_query 是否要做成 grouped**?
   - 不建议。与 `arith` 同理,简单查数/指标提取是 Supervisor 直跑核心,不能藏起来。

---

**已确认决定(2026/07/26)**:

- ✅ `query_data` 子 agent: 直接删除 spec 文件(不保留 fallback),见 §4.5
- ✅ CSV 路径 bug: 已独立修复,本方案不再作为阻塞项,见 §6
- ✅ 边界用例 F(含「对比/趋势/分布」等分析意图的指标加工): 派 `agent_spawn(analyze_data)`,不走 Supervisor 直跑,见 §3.3 分支 ①
