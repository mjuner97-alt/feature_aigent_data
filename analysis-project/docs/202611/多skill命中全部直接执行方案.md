# 多 skill 命中全部直接执行方案

> 2026/11/13 立项。场景：用户问题同时命中多个 skill 的层级标签，当前只执行排序 top-1 的脚本，
> 需求为把命中的多个 skill 对应脚本都直接执行出来。
> 约束①（拍板）：**优先级（skill_keyword_priority）真正参与决胜的场景不在本方案范围内**——
> 优先级仅存在于同一 L1 关键词内的二三级词之间，只在候选深度并列时决定胜负；此类场景维持原
> top-1 行为（详见 §4.1 G4）。

---

## 1. 问题场景

用户问题：

```
杭二11月的整体缺陷密度情况，及未闭环问题明细
```

同时命中两个层级轨 skill：

| skill | 命中层 | 命中词 | depth |
|---|---|---|---|
| wenshu_zhengtiquexianmidu | L1 | 缺陷密度 | 1 |
| wenshu_weibihuan_free_query | L1+L2 | 问题/缺陷 + 未闭环 | 2 |

实际 trace（09:11:56）只出了 `wenshu_weibihuan_free_query` 一条维度解析 + 一次 script_exec，
缺陷密度的诉求被吞掉。根因：`DirectSkillExecutor.plan()` 拍板口径"候选非空即执行 top-1"
（DirectSkillExecutor.java:558-559），排序比较器 depth 大者前 → weibihuan (depth 2) 胜出。

## 2. 现状链路（关键代码）

1. **候选选择**：`SkillCandidateSelector.selectInternal()`（SkillCandidateSelector.java:106-197）
   - 显式点名 / 层级轨（L1/L2/L3 配置层 AND）/ 存量轨三分流；
   - 合并排序比较器：`explicit > domainHit > depth(L3>L2>L1) > globalRank(skill_keyword_priority 位次) > name`（:191-195）；
   - 两档截断：榜首命中链完整截 `maxVisibleSkills`，榜首 partial 放宽到 `fallbackVisibleSkills`（:199-214）。
2. **直接执行判定**：`DirectSkillExecutor.plan()`（DirectSkillExecutor.java:507-719）
   - pending 消费 → selector → domain 让位（:543）→ 旁路词让位（:552-557）→ **取 top-1**（:559）
   → canUse 权限 → param_mapping 非空 → `extract()` 维度提取（共享）→ 真值校验/人员/反填/重锚抑制
   → 时间锚点歧义反问（:620-641）→ 人名重名反问（:646-661）→ buildParams → 快照兜底（:668-684）
   → 分布下钻 → 缺必填反问/回退（:692-714）→ 返回单个 `DirectExecutionPlan`。
3. **执行**：`V2ChatStreamServiceImpl`（v2）/ `ChatStreamServiceImpl`（v1）短路轮：
   `plan()` 非空 → `sendDimensionExtractEvent`（单条 dimension_extract 事件）→
   `runDirectExecution`（V2ChatStreamServiceImpl.java:556-611）单次
   `scriptExecTool.executeStructured(scriptId, params)`（ScriptExecTool.java:731，**一次一个脚本**），
   tool_call_start/tool_result_end + stdout 作为文本 chunk 渲染；失败直接报错不回退 LLM（拍板）。
4. **优先级机制**：`skill_keyword_priority` 表（行模型，docs/202610/skill关键词优先级行模型存储方案.md），
   selector 内 `buildPriorityIndex()`/`bestGlobalRank()`（SkillCandidateSelector.java:219-246）仅参与**排序位次**；
   `SkillRoutingMetadata` 自身的 priority 列已随评分体系简化移除（SkillRoutingMetadata.java:13）。

## 3. 需求与约束

- 多个 skill 标签命中时，命中的脚本**全部直接执行**，结果按序展示。
- 约束①：命中词配置了优先级（skill_keyword_priority 有对应行）的场景**不在范围**——
  这类标签业务上是刻意分主次的，维持"按优先级取 top-1"的存量行为。
- 既有拍板一律不动：domain 让位、旁路词重排序、层级 AND 收窄、缺参反问、追问轮、
  时间歧义/人名重名反问、真值校验、失败不回退 LLM。

## 4. 方案设计

### 4.1 触发门控（全部满足才走多执行，否则维持旧 top-1）

| # | 门控 | 理由 |
|---|---|---|
| G1 | 非 explicit 显式点名 | 点名意图单一，点名即 top-1（与 selector 置顶一致） |
| G2 | 非 domain 命中 | 既有拍板：domain 命中整体让位 LLM，多执行在其判定之后，零变化 |
| G3 | 旁路词不让位条件照旧（topFromLevelTrack） | 存量轨 + 旁路词问法维持旁路 |
| G4 | **优先级决胜门控（约束①，2026/11/13 拍板修正口径）**：仅当排序比较**进行到 globalRank 位次决胜**时整轮降级——即候选 explicit/domainHit/**depth 全并列**且该深度的命中词在 `skill_keyword_priority` 配了 sort_order（优先级真正在分胜负，典型：同一 L1 词下多个 L2 词配置了位次，如「未闭环遗留问题」）。depth 已分出先后的（如缺陷密度 L1 vs 未闭环 L1+L2）**不降级**，即使个别命中词碰巧配了 sort_order | 拍板口径"有优先级的不在考虑范围内"的精确化：优先级仅存在于同一 L1 关键词内的二三级词之间，且只在深度并列时起决定作用；位次比较未发生就不介入 |
| G5 | 榜首命中链完整（非 partial / 非 fallbackExpanded） | 榜首都只命中了深层词 = 问法宽泛，多执行会放大误命中 |
| G6 | 参与候选全部来自层级轨（levelTrack），存量轨候选不参与 | 存量轨按叶层打分排序，语义模糊，多执行风险高 |
| G7 | 候选数 ≥2，**不设数量上限**（2026/11/13 拍板）：有几个执行几个 | 取 selector **截断前**的完整层级轨命中集合（绕开 maxVisibleSkills/fallbackVisibleSkills 截断，见 §5①）；脚本串行执行耗时可接受 |

### 4.2 候选与顺序

- **问题提及序（2026/11/13 拍板）**：按各候选 skill 命中词在问题文本中的**最早出现位置**排序
  （每个 skill 取其全部真命中词的最小位置）。分组保底注入词（"保底"保留字）无文本位置，
  该 skill 取其其余真命中词位置；全部靠注入的排最后，按排序比较器兜底。
- 示例：「杭二11月的整体缺陷密度情况，及未闭环问题明细」→ "缺陷密度"（位置靠前）先执行，
  "未闭环"后执行，与用户问的顺序一致。
- 显式点名 / domain 让位 / 旁路词判定不受影响（多执行根本不触发的轮次维持原样）。

### 4.3 维度提取复用与 per-skill 参数组装

维度提取是**问题级共享**的，per-skill 的只有参数映射：

```
公共段（一次）：previewState → extract(...) → 台账真值校验 → 人员扫描 → 重锚抑制
per-skill 循环（i = 排序后每个候选）：
    extraction_i = 公共 extraction（matchedSkill/metrics 替换为该 skill 名/该 skill 命中词）
    backfillDeptFromLedger(extraction_i, mapping_i, schema_i, required_i)   ← 按 skill schema 门控，必须循环内
    时间锚点歧义/人名重名检查（extraction 级共享，见 4.4）
    buildParams(mapping_i, schema_i, extraction_i)
    stripGranularityConflicts → applyDistributionDrillDown（per-skill，mapping/schema 不同）
    缺必填判定（per-skill，见 4.4）
```

`matchedKeywords`（metrics 词表）目前 selection 只暴露 top-1 的（SkillCandidateSelection.java:11，
`topMatched = scored.get(0).matchedTerms()`），需要扩展为 per-skill（见 §5 改动①）。

### 4.4 缺参 / 歧义 / 失败

- **时间锚点歧义 / 人名重名**：extraction 级（问题级）信号，任一存在 → 整轮反问（pending 挂 top-1），维持现状单 plan 语义。
- **缺必填参数（2026/11/13 拍板：方案B 齐参执行+反问混排）**：参数齐备的候选正常执行出结果，
  缺参候选**不执行**，在**同轮末尾**（全部结果渲染完之后）下发反问文本"「未闭环问题自由问数」
  还需要产品线，请补充…"；下一轮补参后只执行该缺参 skill（pending 单槽挂缺参 skill，复用
  既有补参机制，不重跑多执行）。⚠️ 风险：内网前端反问轮契约是"纯一次性 text 事件（零
  think/done）"（docs/202611 记忆），方案B 是"工具结果 + 反问文本"同轮出现——**实施前需
  前端确认混排展示**；确认不通过则回退方案A（整轮降级旧 top-1）。
- **脚本失败（2026/11/13 拍板）**：单脚本失败不中断其余脚本，失败位置照旧发 ERROR
  tool_result_end + 报错文案，继续下一个；与"失败不回退 LLM"拍板不冲突（仍不回退）。

### 4.5 执行与渲染

- boundedElastic 上**串行**顺序执行（runDirectExecution 循环化），保证输出顺序 = 问题提及序（§4.2）。
- 每个 skill 独立：dimension_extract 事件一条、toolCallId 一个、tool_call_start/tool_result_end 一对
  —— 前端 ActivityFeed/ToolCallCollector 按 toolCallId 折叠展示，多工具调用天然支持（LLM 路径已是多工具）。
- **stdout 段之间插入分隔（2026/11/13 拍板）：空行 + skill 标题行**，标题取该 skill 的
  `short_summary`（如 `【未闭环问题自由问数】`，比 skill 英文名可读性更好）；首个结果前同样加标题行，
  保持各段格式一致。每段独立 append 进 answerContent，Markdown.vue 顺序渲染。
- **统一样式无冲突**：ScriptHtmlSanitizer 作用域是 `.unified-table` **class**（ScriptHtmlSanitizer.java:15-22, 66-99），
  每段 stdout 经 ScriptExecTool 出口独立包壳（ScriptExecTool.java:918），多表格不会产生重复 id；
  `#report` 已被改名/重写，多段共存安全。

### 4.6 快照与追问轮

- `recordLastExec` 记**最后一个执行的 skill**（与"上轮最后执行的脚本"语义一致，2026/11/13 拍板）；
  `recordResult`（追问 hint 的 stdout 摘要）同样只记最后一个。混排反问轮（§4.4 方案B）pending
  挂缺参 skill，补参轮消费后按旧单 plan 语义执行与记录。
- `consumePending` / `planFollowupDirect`（追问轮、补参轮）位于 planAll 最前，命中即原样返回**单** plan，
  不参与多执行。
- 快照兜底继承（:668-684）与 `last.skillName().equals(topName)` 比较逻辑不变。

## 5. 代码改动清单

| # | 文件 | 改动 |
|---|---|---|
| ① | `SkillCandidateSelector.finish()`（:199-214） | 暴露三组信息到 `SkillCandidateSelection`（加字段，保留旧构造器兼容）：a) **截断前完整层级轨命中集**（name、levelTrack、depth、partial，供 G7 不设上限取全集）；b) per-skill 命中词表；c) **priorityDecisive 标志**（排序进入 globalRank 决胜且位次非 MAX_VALUE → true，G4 门控源）；另收集 per-skill 命中词最早文本位置（供提及序排序） |
| ② | `DirectSkillExecutor` | 新增 `public List<DirectExecutionPlan> planAll(question, userId, previousState, conversationId)`：门控（§4.1）不满足返回单元素（旧语义）；满足则按 §4.3 组装多 plan，**按提及序排序**；缺参候选出 ask plan（不执行），齐参候选出 exec plan。旧 `plan()` 委托 `planAll().get(0)`，零行为变化 |
| ③ | `DirectExecutionPlan` | 增加展示标题字段（取 skill short_summary）+ ask/exec 类型标志（混排轮区分） |
| ④ | `V2ChatStreamServiceImpl`（:458-486, :556-611） | `plan()` → `planAll()`；循环执行 exec plan（每段前发标题行+空行分隔，每段独立 toolCallId/tool 事件）；执行完后对 ask plan 逐一下发反问文本（混排轮末尾，复用 askPrompt）；`recordResult`/`recordLastExec` 用最后一个执行的 skill |
| ⑤ | `ChatStreamServiceImpl`（v1, :498-629） | 同步改造（内网 /ai/chat 走该路径，双路径必须一致） |
| ⑥ | 配置 | `direct-exec.multi.enabled`（默认 false），setter 注入模式（仿 `setDistributionDrillEnabled` :1287）；数量上限不设（拍板⑦） |

## 6. 影响面核对（对既有方案逐条）

| 既有方案 | 影响 |
|---|---|
| domain 命中优先（2026/10/28） | 无：G2 在多执行之前整体让位 |
| 直接执行优先于旁路词（2026/10/27） | 无：G3 判定源不变 |
| 层级 AND 收窄四次定稿（2026/10/22） | 无：入池逻辑不动，多执行只发生在入池排序之后 |
| skill 关键词优先级（拍板⑧） | 约束①落点：G4 仅在 globalRank 决胜时整轮降级（深度并列场景）；排序位次机制原样保留 |
| 缺参反问（2026/10/15） | pending 单槽不变；混排轮缺参 skill 挂 pending，补参轮回到旧单 plan 语义 |
| 追问轮维度继承（2026/11/06） | 无：追问层在 planAll 最前短路返回单 plan；快照记最后执行的 skill |
| 时间维度歧义反问 / 人名重名反问 | 无：extraction 级信号整轮反问 |
| 台账真值校验 B①/B③（2026/11/11） | 共享一次，消解结果对全部候选一致生效（语义正确：垃圾值对所有脚本都该消解） |
| 我X重锚抑制 / 部门反填（2026/10/30） | 抑制 extraction 级一次；反填按各 skill schema 门控（循环内） |
| 分布触发默认下钻（2026/11/09） | per-skill 循环内调用，mapping/schema 各自独立 |
| 统一表格样式（2026/11/12） | 无冲突：class 作用域 + 每段独立包壳（§4.5） |
| 失败不回退 LLM（拍板） | 保持 per-script：单脚本失败报错误块不回退，其余继续（待拍板确认） |

## 7. 拍板结论（2026/11/13）

1. **缺参策略：方案B 齐参执行+反问混排** —— 齐参候选先执行出结果，缺参候选同轮末尾反问，
   下一轮补参只执行缺参 skill。⚠️ 实施前需前端确认"结果+反问"混排展示；不通过则回退方案A。
2. **失败策略：继续执行其余脚本**，失败的给出报错信息（ERROR tool_result_end + 错误文案）。
3. **输出分隔：空行 + skill 标题行**，标题取 skill `short_summary`（如 `【未闭环问题自由问数】`）。
4. **执行顺序：问题提及序**（各 skill 命中词在问题中的最早出现位置；保底注入词取其余命中词位置，
   全靠注入的排最后按排序器兜底）。
5. **快照/追问 hint 记最后一个执行的 skill**。
6. **优先级门控：整轮降级，但触发条件精确化** —— 仅当排序进行到 globalRank 位次决胜
   （explicit/domainHit/depth 全并列且命中词配了 sort_order）才降级；优先级只存在于同一 L1
   关键词内的二三级词之间，depth 已分出先后的不降级。
7. **多执行数量上限：不设上限**，有几个执行几个（取 selector 截断前的完整层级轨命中集）。

## 8. 测试计划

- `DirectSkillExecutorTest` 新增：双命中出双 plan（提及序）/ G4 同深度并列+优先级配置→单 plan /
  G4 depth 分出先后但词配了 sort_order→仍多执行 / explicit→单 / domain→null / fallbackExpanded→单 /
  存量轨候选不参与 / 缺参混排（齐参执行+末尾反问+pending 挂缺参 skill）/ 失败继续 /
  追问轮与 pending 消费不受影响 / 快照记最后执行 / 4+ 命中不截断。
- `SkillCandidateSelectorTest`：截断前层级轨全集暴露、priorityDecisive 标志、最早命中位置断言。
- E2E（内网）：本节场景问句验证两个表格均渲染、dimension_extract 两条、tool 事件两对、
  标题分隔行展示；**混排轮前端展示确认**（拍板①的前置条件）。

## 9. 实施顺序

1. （前置）前端确认混排展示契约（拍板①前置；不通过则缺参策略回退方案A 整轮降级）
2. ① selection 扩展 + ② planAll（门控关闭 = 全部旧行为，回归既有单测）
3. ③④⑤ 执行链循环化（开关默认 false）
4. 单测 + 本机 E2E → 内网 E2E → 开启开关
