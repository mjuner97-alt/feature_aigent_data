# dimension_context 维度上下文机制解析

> 2026/11/18 整理。对应代码：`v2/middleware/DimensionStateMiddleware.java`（注入渲染）、`v2/dimension/DimensionStateManager.java`（解析管线，2240 行）、`V2DimensionConfig.java`（装配）。

## 1. 这是什么

`<dimension_context>` 是一段**动态注入主 agent 系统提示词末尾**的维度上下文块，内容是系统按**确定性规则**（纯正则 + 词表，零 LLM）从用户当前及历史口语化表述解析出的标准维度值。AGENTS.md:15-20 给它定了契约：

- 块里的产品线/组/应用/版本计划等值是**已确认的标准名**，不是候选建议；
- LLM 构造查询参数必须**直接采用**，即使用户原文是口语词/简称；
- **禁止追问**「具体指哪个部门/产品线」或要求确认口径；
- 标准名可能不在工具目录词表里（词表未覆盖新增产品线），不影响有效性。

设计目的：把"口语 → 标准名"的解析从 LLM 挪到 Java 规则层，保证跨轮口径一致、指纹稳定、不因小模型理解偏差反问用户。

## 2. 注入：DimensionStateMiddleware

### 2.1 装配

| 项 | 值 |
|---|---|
| 注册 | `V2DimensionConfig.dimensionStateMiddleware`（bean）→ `HarnessAgentPartsConfig.harnessMiddlewares:88-105`（主 agent 中间件列表，排在 responseCache 之后、episodic 之前） |
| 生效范围 | **仅主 agent**（子 agent 中间件链不注入，子 agent 收到的上下文靠主 agent 传递） |
| 预算 | `harness.a2a.dimension.max-chars` 默认 2000，超限按行边界截断并标注「(维度上下文已按预算截断)」 |
| 用户问题来源 | `ctx.get("lastQuestion")`，由 `V2ChatStreamServiceImpl.buildRuntimeContext:1197` 在请求开始写入 |
| 依赖 | `DimensionStateManager` + `PersonalDimensionResolver`（人员台账，null 跳过） |

### 2.2 onSystemPrompt 流程（`DimensionStateMiddleware.java:75-116`）

1. 取 lastQuestion；空 → 原样返回；
2. `dimensionStateManager.processQuestionInContext(ctx, userQuestion)` → 新状态 + 本轮别名映射（纯本地，零 LLM）；
3. `enrichPersonal(state, ctx, userQuestion)`：人员台账回退注入（见 §7）；
4. 状态有任何维度或人员解析行 → `formatDimensionContext` 渲染成块，追加 `systemPrompt + "\n\n" + 块`；
5. 任何 RuntimeException 降级为无注入，不打断 chat 链路。

### 2.3 块内容结构（`formatDimensionContext:223-284`）

```
<dimension_context>
当前对话维度上下文。以下维度值是系统根据用户口语化表述确定性解析出的标准名（非猜测）……禁止向用户追问……

同义词解析映射（口语词 → 维度：标准名）：        ← 本轮 alias 命中行
- 口语词「车贷」→ 组维度：金融市场车贷组

季度/版本计划：2026年10月份版本                  ← timeDimension（单类型槽位）
部门：杭州开发一部                               ← departments
组：xxx / 应用：F-XXX / 产品线：xxx              ← peerDimension（单类型槽位）
人：张三                                         ← persons

人员解析（确定性结果，非猜测）：                  ← 人员台账 enrich
- 「张三」→ 统一认证号 123456（杭州开发一部）
涉及上述人员的查询，参数 perNo 直接使用对应统一认证号……
</dimension_context>
```

## 3. 解析管线：processQuestionInContext

`DimensionStateManager.processQuestionInContext`（`DimensionStateManager.java:172-195`）是 **P1-2 多用户串扰修复后的安全入口**：状态全部走局部变量 + ctx，不碰单例 bean 的 `currentState` 实例字段（旧序列 `loadFrom → processQuestion → saveTo` 会被并发请求互相覆盖）。每轮：

```
ctx 取上一轮 dimensionState
  → ① analyzeQuestionRuleBased(question)      规则抽取（§4）
  → ② inheritDimensions(loaded, analysis)     按 level 继承/裁剪（§5）
  → ③ resolveReference                        "这个X/那个X" 替换为状态值（§6）
  → ④ assembleQuestion                        状态拼前缀的 enrichedQuestion（供旧链路/预览）
  → ⑤ clarifiedPeer 一次性回填                反问确认轮补 peer（§8）
  → ctx.put("dimensionState", newState)       写回，本轮与后续轮共用
```

返回 `ProcessResult(enrichedQuestion, newState, resolvedAliases)`——注入块只用 newState + resolvedAliases；`enrichedQuestion` 主要供旧 v1 链路和调试。

## 4. ① 规则抽取 analyzeQuestionRuleBased（:880-916）

按序四个子步骤：

### 4.1 同义词解析（AliasResolver）

- 词源：`dimension_alias` 表（gauss，TTL 5min 快照，`V2DimensionConfig:90-100`），按「长前短后」最长匹配；
- 命中产出 `ResolvedAlias(维度类型, 口语词, 标准名, span)`；同词多标准名 → `AmbiguousAlias`（进反问状态机，§8）；
- 两道修正：`resolveEmbeddedCandidates`（:1461）歧义候选中**只有一个标准名完整出现在问题文本**时静默消歧（反问回环修复）；`suppressEmbeddedAliasHits`（:1487）alias 命中嵌在组名 token 内部且互不包含时抑制（"个贷数据开发组"的"个贷"不许抢注产品线）；
- **alias 命中即压制组/产品线/需求项正则兜底**（`aliasHit`，:1291）。

### 4.2 指代探测 + 内置键词表

- `detectReferenceType`（:918）：这个/那个 + 时间/部门/组/应用/产品线/需求项/需求子条目；
- `collectBuiltinVocabHits`（:1328）：扫 `dimension_key_config` 白名单内置键（**versionPlan/vestQuarter/deptName** 三个）的 synonym alias，问题 contains 即收值；`YEAR_V/HALF_V` 锚点紧邻年份前缀时升级 `@yyyy` 限定（"25年上半年"）；timeRange 日期 span 不参与（防"10月1日"被"1月"误收）。

### 4.3 层级判定 detectLevel（:930-975）

按序短路：CAUSE（原因/为什么）→ TIME_RANGE（过去/趋势/历史）→ PERSON（谁）→ PEER（指代组/应用/产品线/需求项、F-码、裸码、组名/产品线命中、alias 命中）→ DEPARTMENT（部门词/部门正则/词表）→ TIME（各时间正则/词表/时间指代）→ null（非维度问题）。

### 4.4 显式维度提取 extractExplicitDimensions（:986-1276）

| 槽位 | 来源 |
|---|---|
| timeDimension | 十余个正则族全部归一为 `{y}年{n}月份版本` / `{y}年{n}季度` 标准格式（不同写法同一指纹）：共享后缀列举（"10、11月版"）、区间展开（"1-3季度"，越界/倒序整段弃收）、EXPLICIT_VERSION/MONTH/QUARTER、延后版（"10月延后版"）、紧凑 YYYYMM、中文数字月、近 N 个版本（`LAST:nV` 锚点）；裸日期 "M月D日" 只作屏蔽 span 不产值；**VERSION 与 QUARTER 同现时 VERSION 优先**（单类型槽位） |

**时间双轨语义（两路径不同形）**：DimensionState 的 timeDimension 是单类型槽位（版本或季度二选一），而直接执行侧 versionPlan/vestQuarter 是**双键并存**，且 vestQuarter 缺席时可从版本**推算**——`valuesFor`（`DirectSkillExecutor.java:2801-2807`）在 `extraction.vestQuarter` 为空时用 `VersionPlanKnowledgeHook.deriveQuarters` 从版本值推出所属季度（"2026年10月份版本" → 2026年4季度）。推算只发生在 `resolved`（脚本实际拿到的值）层，extraction 层如实保留"本轮只提了版本、没提季度"的事实。

**锚点的两种呈现**：`LAST:nV` / `YEAR_V` / `HALF_V:{1|2}@yyyy` / `THIS_V/THIS_Q` 等锚点在 dimension_context 中**原样渲染**给 LLM；直接执行路径则在执行期展开——`expandVersionValues/expandAnchorValues` 经 `VersionPlanAnchorResolver` 查在建版本日历（复用 VersionPlanKnowledgeHook 的 TTL 缓存）落成具体版本名/季度值，**锚点原词绝不透传给脚本**；解析不了 → 参数不传 → 必填缺失回退 LLM（fail-safe）。
| departments | EXPLICIT_DEPT 正则（杭州开发X部 + 简称归一 normalizeDepartment）+ 词表值 |
| peerDimension | 优先级 **应用 F-码 > alias 解析 > 组正则 > 产品线正则**；裸应用码（省略 F- 前缀，技术词表拦截）只在 alias 未命中时收集；需求项 I20260208-0005 / 需求子条目 S20250926-000741 / 项目编号 KJ+8位 无条件进专字段，peer 槽位为空时同入槽位 |
| persons | 规则层不产（由人员台账 enrich / LLM 提取） |

**§13.4 两遍分段抽取**（:656-830）：第一遍 `buildMaskedQuestion` 把时间/部门/应用/需求项/项目编号 span 等长掩码；第二遍在掩码文本上找组名（`X组`）/产品线 token，再叠加多层守卫拒收垃圾值——粒度词首字守卫（各/每/按）、粒度尾缀回看、drillDown 词表反哺 stems、维度键字典 block 正则（"我的组"）、左侧修剪（虚词 + tool_metric_catalog 指标词表，词表已知整名不剪）。效果："杭州三部数据智能组" → 部门=杭州开发三部 + 组=数据智能组，而不是整段吞成组名。

## 5. ② 继承 inheritDimensions（:1636-1784）

槽位：timeDimension / departments / peerDimension（单类型，组·应用·产品线·需求项·子条目·项目编号共用）/ persons（+ requirementNos/subRequirementNos 专字段）。**这 4 槽是 LLM 路径（dimension_context）的投影**——直接执行路径把同一状态展开成 `DimensionExtraction` 的 13 个固定键全键输出（versionPlan/vestQuarter 双时间键、deptName、groupName、appName、productLine、itemNo、subItemNo、projectNo、perName、perNo、perRole、metrics，`DirectSkillExecutor.java:245-326`），空数组 = 问题未提及；`extraDimensions`（orgName/prodDate 等动态维度键词表命中）单列、不计入固定键。两组槽位的对应与差异见 §4.4/§7/§9。

按本轮 level 决定保留多少上一轮状态（自上而下保留）：

| 本轮 level | 继承保留 |
|---|---|
| TIME | 不继承（本轮时间显式为准） |
| DEPARTMENT | + timeDimension |
| PEER | + timeDimension、departments |
| PERSON / TIME_RANGE | + timeDimension、departments、peerDimension |
| CAUSE | 全量继承；带指代时按指代层级丢下级（handleReferenceInCause） |
| null | 状态原样保留；但本轮有显式维度时以显式值落地（防 level 漏判丢值） |

随后 `applyExplicitDimensions`（:1744）**自上而下重置**：显式时间 → 清部门/peer/人；显式部门 → 清 peer/人；显式 peer → 清人，专字段以本轮提取为准（无则清，防跨轮串值）。这是"新问题换口径，旧口径不残留"的核心规则。

## 6. ③ 指代消解 resolveReference（:1792-1885）

"这个/那个 + X" 替换为结构化状态对应槽位值（时间按季度/版本分别替换；组/应用/产品线/需求项/子条目要求 peer 槽位类型匹配才替换）。状态无对应值时保持原文。

## 7. 人员台账 enrich（DimensionStateMiddleware.enrichPersonal:125-175）

LLM 回退兜底路径（直接执行主场景在 `DirectSkillExecutor.plan` 参数契约）：

- **"我X"**（我部/我组/我的应用/我的产品线/裸我）：`PersonalDimensionResolver.resolve` 查人员台账（gauss-common，TTL 30min）→ peer 槽位（三选一先命中者胜，已有值不覆盖并 warn）+ 部门一项**全量连带**注入；
- **人名扫描**（2~4 字窗口，先经 `maskForPersonScan` 屏蔽时间/部门/应用/组/产品线 span 防抢）：唯一命中 → persons + 统一认证号注入行；重名 → 注入"存在重名，请先确认"行。

**perNo/perRole 的键位语义（直接执行侧独立键，不回写状态）**：`DimensionExtraction` 里 `perNo`（统一认证号）执行期解析——"我"→`ChatRequest.userId`、人名→台账 Q2 解析——**不进 DimensionState、不进指纹**（注释 `DirectSkillExecutor.java:243`），纯执行期参数；`perRole`（岗位角色）由配置正则加载/角色双路径提取（我X方案 §8 / 岗位角色方案 §3.1b），同样是独立键位。dimension_context 侧 perNo 只以"人员解析"文本行呈现，perRole 不出现（三属性渲染扩展方案见 §13，待实施）。

**直接执行侧独有的台账后处理链**（LLM 路径没有这一层）：台账真值扫描 `applyLedgerTruthScan`（抽出的维度值须在台账/alias 标准名真值域内）、`backfillDeptFromLedger`（缺部门必填时从台账反填，实测样例的 `deptName=杭州服务支持部` 即可能来自此）、重锚抑制 `applyReanchorSuppression`（我X 命中后抑制显式重锚冲突）。

## 8. 反问状态机（P3，:2092-2239）

`dimension_alias` 同词对多标准名时的确定性反问（`V2ChatStreamServiceImpl:340-346` 在 agent 启动前调用）：

1. `startClarificationIfAmbiguous`：本轮有 AmbiguousAlias → 登记 `pendingClarification` 到状态，**零 token 短路**，反问文本（候选列表+序号）直接经 done 事件下发，不启 agent；
2. 下一轮 `consumeClarification`：回复是序号（"1"/"第2个"/全半角/中文数字）或包含候选名 → peer 写入选定标准名 + alias 原位替换标准名后重跑原问题；未命中 → 丢弃 pending 按普通问题放行（宁少问不死循环）；
3. 确认结果经 `clarifiedPeerQuestion/clarifiedPeer` 一次性回填（:183-190）：确认出的标准名往往不被正则词表识别，按问题文本精确匹配补回 peer，用后即清。

## 9. 持久化与消费侧

- **本轮内**：状态写 `RuntimeContext` key=`dimensionState`；同请求内 DirectSkillExecutor 等通过 ctx 读取。
- **跨轮**：下一轮 `processQuestionInContext` 从 ctx 读回。⚠️ 已知 gap（2026/07/17 E2E 发现）：`agent_state` JSON 无 dimensionState 字段，单例 bean 的 `currentState` 是内存态，**JVM 重启后跨轮继承丢失**（`clarifiedPeer` 等易变字段同样不落盘）。
- **直接执行参数契约**：`DirectSkillExecutor` 用 `previewState`（:208，只读不写 ctx）对问题做同一套规则解析（同引擎、同状态、同口径——dimension_context 是状态给 LLM 的提示词投影，extraction 是给脚本的参数投影），随后走 extraction 级后处理链（§7）→ `buildParams` 按 skill 自己的 `param_mapping`/`params_schema` 落参（维度键映射为脚本参数名，如 versionPlan→version_plan、appName→app、deptName→dev_dept；array 恒传 List、number 解除转数值保形）→ `stripGranularityConflicts`（粒度词与维度值冲突剔除）→ `applyDistributionDrillDown`（分布类问题默认下钻一级）。动态键（orgName/prodDate 等词表命中）落在 `extraDimensions`，同样在此层转参；保留键 `timeRange`（本周/近N天/近N月等 → 语义锚点 → 执行期解析为日期区间，歧义锚点短路反问）目前也只走这条路——LLM 路径的 dimension_context 暂不渲染时间范围，注入方案见 §12。是否正式写状态由短路决定后才调 `processQuestionInContext`（`V2ChatStreamServiceImpl:458-465`），避免回退 LLM 时解析两次。
- **resolved = 脚本真正拿到的全键最终值**：`resolvedValues → valuesFor`（`DirectSkillExecutor.java:2782-2822`）按启用键全键展开——versionPlan/vestQuarter 锚点执行期展开（§4.4）、vestQuarter 空时从版本推算季度、`.start/.end` 后缀键取第一个可解析值的聚合时间窗口边缘、timeRange 后缀键解析锚点取第一个范围（多范围对比不支持）；不可解析的锚点返回空 → 参数不传 → 必填缺失回退 LLM（fail-safe）。
- **metrics 键位语义（检索证据，非维度值）**：extraction.metrics 不是维度抽取产物，而是"用户问题里命中了该 skill 路由配置的哪些词条"——词源是 `skill_index` routing metadata 的 level1/2/3Keywords + keywords（`SkillCandidateSelector.matchedTermsOf:302-309` 收集，span 屏蔽语义：命中全落在维度实体 span 内 = 假主题信号丢弃），经 `SkillCandidateSelection.matchedKeywords`（top-1 轨）/ `LevelTrackHit.matchedTerms`（层级轨）传入 `extract(...)`；多 skill 轮 per-skill 被 `withMatch`（:639-643）替换为本 skill 命中词条。它不参与 param_mapping 落参、不进指纹，仅供维度解析面板展示与日志审计。增强方案（并入 tool 目录标签去重检索）见 §11。
- **遗留 LLM 路径**：`updateFromAnswer`/`extractDimensions`（llmService，light-classifier qwen3:8b）从回答提取维度——v2 chat 主链路已不依赖，属 v1 遗留。

## 10. 关键设计点小结

| 点 | 说明 |
|---|---|
| 零 LLM 解析 | 主链路纯正则 + 词表，确定性可回归（936+ 单测大头在此）；LLM 只在遗留提取路径 |
| 单类型槽位 + 自上而下重置 | time/peer 各一个槽位；显式指定上层即清下层，防止换口径后旧值串轮 |
| 指纹稳定 | 所有口语写法归一为标准格式（"4月版"="2026年4月份版本"），同口径跨轮同指纹 |
| span 屏蔽体系 | 时间/部门/应用/需求项先掩码再抽组名；粒度词/代词短语/block 正则/词表反哺多守卫拒垃圾值 |
| alias > 正则 | alias 命中压制组/产品线兜底；alias 嵌组名互不包含时反向抑制 |
| 判定即终局 | dimension_context 明确"禁止追问口径"，与 tool_metric_catalog 的"目录外终局"同风格——把反问面收窄到真正的同义词歧义 |
| 主 agent only | 子 agent 不注入；复杂分析派单时主 agent 在任务描述里携带标准名 |
| 双路径同源投影 | 同一 DimensionState 两种投影：LLM 路径 4 槽提示词（锚点原样给 LLM）；直接执行 13 固定键参数（双时间键并存、vestQuarter 由版本推算、锚点执行期展开 fail-safe、台账/粒度治理链、metrics 检索证据回填） |
| 已知 gap | agent_state 不落 dimensionState，JVM 重启丢跨轮继承 |

## 11. metrics 来源增强方案：并入 tool 目录标签去重检索（待实施）

> 2026/11/18 拍板（已对齐两点口径：词条源取 topicTags+metricTags；仅回填 metrics 不影响 skill 检索打分）。暂不改代码。

### 11.1 现状与动机

metrics 目前唯一词源是**命中 skill 自己的分级词条**（§9 metrics 键位语义）。两个局限：

- 词覆盖受限于单个 skill 配置的词条，用户问题里涉及其它业务指标词（如工具目录里登记的"质量分""风险"）不会出现在 metrics 里，维度解析面板的"命中词条"刻画不全；
- 同一问题涉及多个业务概念时，metrics 只反映 top-1/层级轨胜者的词条，跨 skill 的公共业务概念缺失。

tool 目录侧（`tool_index` routing metadata）天然维护着业务主题/指标词表（topicTags/metricTags），且 2026/11/18 起**标签真源已是 `tool_route_metadata` 直读**（词袋不再过滤，见 `tool_metric_catalog目录机制解析.md` §7），后端直插的新标签即生效——正好可作 metrics 的第二词源。

### 11.2 目标方案

`extraction.metrics` 扩为**两词源并集去重**（保序）：

```
metrics = skill 命中词条（现状不变） ∪ tool 目录标签真命中词条（新增）
```

| 决策点 | 拍板 |
|---|---|
| tool 词条源 | `ToolRoutingCatalogService.snapshot()`（enabled + isAvailable 已过滤）的 **topicTags ∪ metricTags**；不含 dimensionTags（维度词命中面广，会污染 metrics 语义），不含 description（噪音大） |
| 匹配语义 | 与 skill 词条收集同口径：`SkillTermMatcher` 匹配 + `DimensionSpanScanner.inSpans` span 屏蔽（命中全落在维度实体 span 内 = 假信号丢弃）；工具标签无 fallback 保留字问题，无需 isFallbackMarker 分支 |
| 影响范围 | **仅 metrics 回填**——skill 检索打分/候选入选逻辑不动（skill 与 tool 无关联字段，跨库对齐打分语义成本高、风险大） |
| 落点 | 单 skill 轨（`plan/planSingle`）：`extract(preview, selection.matchedKeywords() ∪ toolTagHits, ...)`；多 skill 轮（`planMulti`）：公共段算一次 toolTagHits，`withMatch` 的 per-skill metrics = `hit.matchedTerms() ∪ toolTagHits` 去重 |
| 依赖注入 | `DirectSkillExecutor` 增加 `ObjectProvider<ToolRoutingCatalogService>`；bean 缺失或 snapshot 失败 → toolTagHits 为空集，**退化为现状**（fail-open） |
| 去重 | 并集按 skill 词条在前、tool 标签在后的顺序 `LinkedHashSet` 去重保序 |

### 11.3 改动点清单（实施时）

1. `SkillCandidateSelector`：把私有 `collectMatched` 暴露一个公共静态入口（如 `collectRealHits(question, terms, entitySpans)`），供 DirectSkillExecutor 复用 span 屏蔽收集（避免复制逻辑）；
2. `DirectSkillExecutor`：构造器加 `ObjectProvider<ToolRoutingCatalogService>`；新增私有方法 `toolTagHits(question, entitySpans)`（snapshot 取 topicTags∪metricTags → collectRealHits → 去重）；`extract` 调用点（:704/:898/:1132 等处）metrics 入参改为并集；`withMatch` 调用点同步合并；
3. entitySpans 来源沿用 skill 检索现有口径（`DimensionSpanScanner` 对问题扫出的维度实体 span），公共段一次计算两处复用。

### 11.4 影响面与风险

- metrics 仅用于维度解析面板展示 + 日志审计，**不进 param_mapping 落参、不进指纹、不进 DimensionState** → 行为面无回归风险；
- tool 标签是业务高频词，命中率高于 skill 词条，面板 metrics 可能变长——如需收敛可后续加 topN 截断（本期不做）；
- snapshot 每次 plan 调用实时构建（`ToolRoutingCatalogService` 无缓存），plan 高频路径多一次全目录扫描；量级为启用工具新（几十内）可接受，必要时复用 metadata 仓储 TTL 缓存（`harness.a2a.tool-routing.metadata-cache-ttl-ms`）。

### 11.5 E2E 验证点（实施后）

1. 单 skill 轨：问题含 skill 词条 + 工具目录指标词（如"风险"），metrics 同时含两者且无重复；
2. 多 skill 混排轮：各 plan 的 metrics = 各自命中词条 ∪ 公共 tool 标签命中；
3. 工具标签词嵌在维度实体 span 内（如某标签恰为组名子串）→ 被屏蔽不进 metrics；
4. ToolRoutingCatalogService bean 缺失/快照异常 → metrics 与现状一致（退化路径）。

## 12. 时间范围（timeRange）注入 dimension_context 方案（2026/11/18 已实施，E2E 待验）

> 2026/11/18 立项并当日实施：本周/近X个月/今天/近X天/当周/上周/下周等口语 → 具体日期区间的换算，实施前只有直接执行路径享受 Java 侧确定性解析，LLM 路径要模型自己心算日期——复杂繁琐且易错（与"算术禁止心算走 ArithTool"同一设计哲学），故把解析结果注入 dimension_context 让模型直接用。直接执行侧一行未动（拍板④）。

### 12.1 现状

**提取层**（`TimeRangeExtractor`）已支持口语 → 语义锚点，两词源：

| 词源 | 形态 → 锚点 |
|---|---|
| `dimension_key_config(timeRange)` 词表 | 业务自配 alias → THIS_WEEK / LAST_WEEK / TODAY / THIS_MONTH / THIS_YEAR / LAST:n(d/w/M) / NEXT:n(w/M) / T-n / R-n / 交付周至投产周 / 投产周（"当周→THIS_WEEK"这类由业务配词表即可） |
| 内置正则 | 绝对区间/裸日期单日/混合省略 → `DATE:a~b`；截至X日 → `UNTIL:date`；未来/下N周 → `NEXT:nw`；近N天 → `LAST:nd`；近N周 → `AMBIGUOUS:WEEKS:n`；近N个月 → `AMBIGUOUS:MONTHS:n`（方向歧义反问）；临近交付N周内 → `R-n`、T-X → `T-n`（里程碑透传） |

**解析层**（`TimeRangeResolver.resolve`）执行期把日期计算型锚点解析为 `[start, end]` 闭区间（ISO，Asia/Shanghai；口径：本周=本周一~today、上周=日历周、近N天/周/月=today-N~today、未来N=today~today+N，均含今天）。

**流转现状**：锚点只落在直接执行路径——`DirectSkillExecutor.extractExtraDimensions` 收进 `extraction.extraDimensions["timeRange"]`（保留键），`valuesFor` 的 `timeRange`/`timeRange.start/.end` 后缀键执行期解析落参；歧义锚点 Java 侧短路反问（`firstAmbiguousAnchor` → `ambiguityAskPrompt`）。

**缺口**：`<dimension_context>` 完全不渲染时间范围，DimensionState 无 timeRange 槽——LLM 路径下用户说"本周/近30天"，模型既看不到解析结果，也没有日历工具，只能心算。

### 12.2 目标

dimension_context 新增**时间范围段**，渲染执行期解析出的具体日期区间，模型直接采用、无需换算：

```
时间范围（执行期确定性解析，直接使用无需自行换算日期；脚本仅支持单范围，落参只取第一个范围）：
- 本周 → 2026-11-16 ~ 2026-11-18
- 近30天 → 2026-10-19 ~ 2026-11-18
```

### 12.3 设计要点

| 点 | 拍板 |
|---|---|
| 解析时机 | **渲染期实时 resolve**（middleware `onSystemPrompt` 每轮执行时算）——遵守 `TimeRangeResolver` 铁律"解析结果绝不进指纹/缓存，日期随今天漂移必须执行时算"：DimensionState 只存**锚点**，不存日期值；同轮多次渲染/跨轮重渲染自动拿最新日期 |
| 状态槽位 | DimensionState 新增 `timeRangeAnchors`（List\<String\> 锚点，不进指纹）；来源 = 词表命中 + TimeRangeExtractor 正则产出（与直接执行 `extractExtraDimensions` 同源同口径，避免两套提取） |
| 跨轮继承 | 并入现有 level 继承矩阵（归 TIME 层级：显式新时间/新维度时按 `applyExplicitDimensions` 自上而下重置）；锚点继承、日期每轮重算，"本周"下一轮自动滚动。拍板②：与 timeDimension **同轮并存**（「本周缺陷数 + 10月版本完成率」两参数槽各自传、互不抑制），**跨轮显式新时间时一并清掉**（「本周…」→ 次轮「11月版本的呢」只显式提版本即触发时间重置，`timeRangeAnchors` 清空，旧区间不串值） |
| 渲染分型 | ① 日期计算型（THIS_WEEK/LAST:n d/w/M…）→ `口语词/锚点 → [start, end]`；② 绝对型（DATE:a~b / UNTIL:date）→ 锚点本身即日期，原样渲染；③ 里程碑透传型（T-n/R-n/交付周至投产周/投产周）→ 渲染锚点字面量 + "执行期由脚本语义解释，勿自行换算" |
| 多范围渲染 | 拍板③：**全渲染+标注**（B）——「近3个月和近1个月对比」的 LAST:3M、LAST:1M 区间都渲染，段内统一标注"脚本仅支持单范围，落参只取第一个范围"；模型可见完整上下文但明确知道第二个范围不会落参，避免自传参/自拼对比 |
| 本期范围 | 拍板④：**只加 LLM 路径渲染**——只改 DimensionStateMiddleware，直接执行侧（extractExtraDimensions/valuesFor）一行不动；两条路径暂时各提各的（口径相同、重复提取），内网验证通过后再同源化（12.4 第 4 条延后） |
| 歧义型 | `AMBIGUOUS:WEEKS/MONTHS/PROD` **不注日期**——复用直接执行的短路反问模式（Java 侧零 token 下发 `ambiguityAskPrompt`，方向确认后回填确定性锚点再渲染）；LLM 回退路径无法短路时渲染"「近N周」方向待确认（历史/未来），请先向用户反问"提示行 |
| 与 timeDimension 边界 | 双轨既有口径不动：连写"近X个月版本/近X个月个版本"归版本键（LAST:nV），裸"近X个月"归 timeRange（2026-11-17 换轨拍板⑷）；TIME_MASK_SPAN 屏蔽的时间 span 不重复产 timeRange |
| 缓存安全 | 注入的日期区间不进 ResponseCache 指纹（同 resolver 口径）；相对时间正则的缓存兜底判定（`TimeRangeExtractor.isRelativeTimeDrift`）已存在，不新增冲突 |
| 预算 | 新增段计入 `harness.a2a.dimension.max-chars`（2000）现有截断机制，无需单独预算 |

### 12.4 改动点清单（2026/11/18 已实施）

1. ✅ `DimensionState`：加 `timeRangeAnchors` 字段（deepCopy/hasDimensions 同步；equals/hashCode/toCacheKey 刻意不含——锚点日期随今天漂移不进指纹）；
2. ✅ `DimensionStateManager`：`analyzeQuestionRuleBased` 第 4 步 `extractTimeRangeAnchors`（timeRange 词表行 + `TimeRangeExtractor.extract`，字典读取失败退化纯正则 fail-open）；`inheritDimensions` DEPARTMENT/PEER/PERSON/TIME_RANGE 层继承锚点；`applyExplicitDimensions` 显式新时间清锚点 + 本轮锚点末尾回填（拍板②）；歧义短路 `startClarificationIfAmbiguous` 扩展 timeRange pending（拍板①）+ `consumeClarification` 历史/未来答复解析 + `clarifiedTimeRangeQuestion` 一次性回填（防反问回环）；
3. ✅ `DimensionStateMiddleware.formatDimensionContext`：新增"时间范围"段（`appendTimeRangeSection`），锚点逐个 `TimeRangeResolver.resolve(anchor, LocalDate.now(ZONE))` 分型渲染；多范围全渲染并在段内标注"脚本仅支持单范围，落参只取第一个"（拍板③）；歧义锚点渲染待确认提示行（正常已被短路反问拦截）；
4. ⏸ 直接执行路径对齐（拍板④**本期延后**）：内网验证通过后，`extractExtraDimensions` 与状态收集同源，`extraDimensions[timeRange]` 改从共享提取取值（保持 valuesFor 落参不变）；
5. ✅ `AGENTS.md` 维度上下文段补时间范围契约句：确定性解析结果、直接用区间端点、禁止自行换算日期。

### 12.5 E2E 验证点（单测已覆盖对应断言，内网 E2E 待验）

1. "本周的缺陷数" → dimension_context 出现 `本周 → [本周一, today]`，模型参数直接传端点、无日期幻觉；
2. 跨轮继承："近30天"次日用同 conversationId 追问 → 区间端点自动滚动到新 today；
3. "近3个月" → Java 侧短路反问历史/未来（非 LLM 心算）；回复"历史" → 渲染 `LAST:3M → [today-3月, today]`；
4. "近3个月版本" → 仍归版本键 LAST:3V，不产 timeRange（边界不回归）；
5. T-2/投产周透传锚点 → 渲染字面量，脚本侧语义不变；
6. "本周 F-A 缺陷数 + 10月版本完成率" 同轮 → timeRange 区间与 versionPlan 并存各自传参（拍板②并存）；
7. 同轮"本周"后追问"11月版本的呢" → timeRange 段消失（拍板②跨轮清掉）；
8. "近3个月和近1个月对比" → 两区间都渲染，段内含"仅支持单范围"标注，脚本只收 LAST:3M（拍板③）。

### 12.6 拍板清单（2026/11/18 盘点，同日 5 项全部拍板）

| # | 待拍板点 | 选项 | 结论 |
|---|---|---|---|
| 1 | **LLM 路径歧义反问方式**：AMBIGUOUS:* 反问现挂直接执行 pending 状态机，回退 LLM 链路无此机制 | A. LLM 路径也 Java 零 token 短路反问（体验统一，需新建 LLM 路径 pending）；B. 只渲染"方向待确认"提示行由 LLM 反问（零状态机改动，但话术不受控、有反问回环风险） | ✅ 已拍板（2026/11/18）：**A 短路**——与"判定终局化"哲学一致，确定性环节不交给 LLM |
| 2 | **timeRange 与 timeDimension 同轮同现优先级**：如「本周 F-A 的缺陷数，和 10月版本的完成率对比」——timeRange 锚点（THIS_WEEK）与版本键（2026年10月份版本）同句同现。需拍：①同轮并存两参数（直接执行现状）还是互斥/败者抑制（参照版本↔季度互不换算拍板），抑制时谁是败者；②跨轮继承重置链——下一轮「11月版本的呢」只显式提版本，"本周"锚点清不清（不清则下轮仍带本周区间，可能串值） | A. 并存（两参数槽各自传，继承各自独立）；B. 败者抑制（定胜负方） | ✅ 已拍板（2026/11/18）：**同轮并存**（timeRange 与 timeDimension 各自传参、互不抑制）；**跨轮清掉**——显式新时间（如「11月版本的呢」）重置 timeDimension 时，`timeRangeAnchors` 一并清空，防止旧区间串值 |
| 3 | **多范围渲染口径**：如「近3个月和近1个月的缺陷密度对比」产出 LAST:3M+LAST:1M 两锚点；落参已拍只收第一个（多范围对比不支持），渲染若两个都列，模型可能把第二个也传参或自拼对比，与脚本实际行为不一致 | A. 只渲染第一个（渲染=落参口径一致）；B. 全渲染+标注"脚本仅支持单范围" | ✅ 已拍板（2026/11/18）：**B 全渲染+标注**——所有锚点区间都渲染，段内统一标注"脚本仅支持单范围，落参只取第一个范围"；模型可见完整上下文且知道第二个不会落参，不做自拼对比 |
| 4 | **本期范围**："路径渲染"=LLM 路径（dimension_context 注入）与直接执行路径（extraction→脚本参数）两条消费链。12.4 第 4 条想把两边 timeRange 提取同源化，但直接执行链路 E2E 已过、内网待验，动它有回归风险 | A. 本期只加 LLM 路径渲染（dimension_context 多出时间范围段），直接执行侧一行不动，两路径暂时各提各的（口径相同、重复提取），内网验证后再同源化；B. 一步到位共享提取 | ✅ 已拍板（2026/11/18）：**A 本期只加 LLM 路径渲染**——只改 DimensionStateMiddleware，直接执行侧一行不动；两条路径暂时各提各的（口径相同、重复提取），内网验证通过后再做同源化（12.4 第 4 条延后） |
| 5 | **词表 seed 维护**："当周"等常用口语是否预置进 dimension_key_config(timeRange) 种子 | A. 补种子；B. 纯业务自配 | ✅ 已拍板（2026/11/18）：业务会配词表，**不预置种子、不用管** |

## 13. 人员三属性（姓名/统一认证号/岗位角色）渲染方案（2026/11/19 已实施，E2E 待验）

### 13.1 现状与动机

人有三个属性：**姓名 / 统一认证号 / 岗位角色**（台账 Q1 三列都有）。dimension_context 现状只展示姓名：

- `- 人：张三` —— persons 槽位只写名字（myself 分支 / 人名扫描唯一命中两处写入）；
- **人员解析段覆盖不全**：
  - 人名扫描唯一命中行 `「张三」→ 统一认证号 xxx（杭州开发三部）` —— 带了认证号+部门，但 `PersonIdentity.roles` 字段**已有却不渲染**；
  - **裸我/我X 分支完全不出人员解析行** —— 问题「我的 本周 11月的问题情况」走 `enrichPersonal` myself 命中台账，只写 `state.persons += ledger.name()`，统一认证号（即 `ledger.userId()`）与岗位角色（`ledger.roles()`）都不出现，LLM 拿不到人身份信息；
- 直接执行侧无此问题：`perNo`/`perRole` 是独立键（内置键，结构化规则提取），脚本参数照常落——本方案只补 LLM 路径渲染面（同 §12 拍板④口径：直接执行侧一行不动）。

### 13.2 目标（拍板样式：三行属性式并入维度值列表）

「我的 本周 11月的问题情况」渲染为（2026/11/19 用户拍板样式）：

```
<dimension_context>
当前对话维度上下文。...
- 版本计划：2026年11月份版本
- 部门：杭州产品部
- 姓名：张三
- 统一认证号：xxx
- 岗位角色：xxx

时间范围（执行期确定性解析，直接使用无需自行换算日期；脚本仅支持单范围，落参只取第一个范围）：
- 本周 → 2026-11-16 ~ 2026-11-18
</dimension_context>
```

要点：persons 不再渲染单行 `- 人：张三`，改为**姓名/统一认证号/岗位角色三行属性式**并入维度值 bullet 列表（与其他维度标签同构）。统一认证号/岗位角色照旧**只进提示词渲染、不进 DimensionState、不进指纹**（§7 键位语义不变）；不另起一套解析通道——身份解析仍复用 `enrichPersonal` 现有台账查询（myself 命中 → `PersonLedgerRow`）+ 人名扫描（唯一命中 → `PersonIdentity`）。

### 13.3 设计要点

1. **渲染改造点**：`formatDimensionContext` persons 段——有身份解析结果（认证号可用）时渲染三行 `- 姓名：x` / `- 统一认证号：x` / `- 岗位角色：x`（角色空 → 省略岗位角色行，姓名/认证号行必有）；**无身份解析结果**（personalResolver 未配置 / 台账查不到 / 兜底降级）时退回现状单行 `- 人：x`，保证降级路径不丢人维度。
2. **数据传递**：`enrichPersonal` 现返回 `List<String> perNoLines`（纯字符串），改携带结构化身份（唯一命中/myself 解析出的 name+perNo+roles）供渲染层拼三行；重名提示行保留字符串形态。
3. **"人员解析"段收窄**：三行属性式落地后，该段仅承载**重名提示行**（"存在重名（n人），请先确认"）——无重名时整段不出现（13.2 样例即无此段）；"参数 perNo 直接使用对应统一认证号"契约句整句删除（拍板④ B）。
4. **perRole 口径边界**：直接执行侧 perRole 有"配置正则（担任xxx⇒extract）+ 角色字典扫描"双路径（内置键 perRole 规则）。LLM 路径本期**只渲染台账 roles**，不做问题文本角色再提取——避免在 middleware 里另起一套正则解析（与 §12 拍板④"两路径暂时各提各的"同精神）。
5. **重名行为不变**：人名扫描多命中仍只出重名提示行，无认证号无角色（不渲染三行）。
6. **多值角色**：兼岗多行 roles 多值时 `、` 连接单行渲染（`- 岗位角色：项目经理、架构师`）。
7. **预算**：新增两行计入 `harness.a2a.dimension.max-chars`（2000）现有截断，无单独预算。

### 13.4 改动点清单（2026/11/19 已实施）

1. ✅ `DimensionStateMiddleware.enrichPersonal`：返回类型从 `List<String> perNoLines` 改为结构化 `PersonalEnrich(identities, ambiguityHints)`——裸我产出 `PersonIdentity(name, userId, depts, groups, roles)`（userId 即认证号，拍板④口径）、人名扫描唯一命中产出 `PersonIdentity`（含 roles），按 name 去重；重名仍为字符串提示行；
2. ✅ `formatDimensionContext`：persons 渲染改三行属性式（`appendPersonSection`：`- 姓名：x` / `- 统一认证号：x` / `- 岗位角色：x`，角色空省略），无身份退回 `- 人：x` 单行；重名提示段仅在有重名提示行时输出；契约句整句删除（拍板④ B）；
3. ✅ 单测：`myselfWritesLedgerDeptAndName` 7 参 `PersonLedgerRow` 带 roles + 三行断言（多角色 `、` 连接）；`personScanUniqueIdentityInjectsPerNoLine` 改三行断言；`personScanAmbiguousInjectsHintNotPerNo` 加无属性行/无契约句断言；新增 `personsWithoutIdentityFallBackToSingleLine` 降级退回断言；`myDeptWritesLedgerDepartmentsIntoState` 加我部命中不出属性行断言（拍板① A）。

### 13.5 E2E 验证点（实施后）

1. 「我的 本周 11月的问题情况」→ 维度值列表出 `- 姓名：张三` + `- 统一认证号：xxx` + `- 岗位角色：xxx`（13.2 样例），无 `- 人：` 行、无"人员解析"段；
2. 台账角色为空 → 只有姓名+统一认证号两行，无"岗位角色"行；
3. 人名扫描唯一命中（「徐三金的缺陷数」）→ 同样三行属性式；
4. 重名（「张三的缺陷数」）→ 仍出"存在重名"提示行，无认证号无角色（不回归）；
5. resolver 缺失/降级 → 退回 `- 人：x` 单行，人维度不丢；
6. 直接执行侧 perNo/perRole 落参回归不动（拍板④）。

### 13.6 拍板清单

| # | 待拍板点 | 选项 | 结论 |
|---|---|---|---|
| 1 | **出行范围**：三行属性行只在身份解析出结果时出（myself 命中 / 人名扫描唯一命中），还是 `myDept/myGroup/myApp/myProductLine` 任一命中也出？ | A. 仅身份解析出结果时出（persons 写入者对齐；我部/我组等场景 persons 未写、不出人属性行）；B. anyHit 即出（提问人身份任何我X场景都带出） | ✅ 已拍板（2026/11/19）：**A 仅身份解析出结果时出**——我部/我组/我的应用/我的产品线命中不出人属性行 |
| 2 | **岗位角色来源**：LLM 路径角色从哪来？ | A. 仅台账 roles（零额外解析，数据现成）；B. 叠加问题文本正则提取（与直接执行 perRole 的"担任xxx⇒extract+角色字典"同口径，但 middleware 要再跑一遍提取） | ✅ 已拍板（2026/11/19）：**A 仅台账 roles**，middleware 不做问题文本角色再提取 |
| 3 | **行格式与落点**： | A. 现有"人员解析"段单行扩展 `「name」→ 统一认证号 xxx（depts）；岗位角色：r`；B. 三行属性式（姓名/统一认证号/岗位角色 各一行）并入维度值 bullet 列表，替换 `- 人：` 行 | ✅ 已拍板（2026/11/19）：**B 三行属性式并入维度值列表**（用户给样例定稿，见 13.2） |
| 4 | **重名提示契约句**：三行属性式落地后，"涉及上述人员的查询，参数 perNo 直接使用对应统一认证号"契约句还留不留？ | A. 保留（属性行场景随段附带，重申落参用法）；B. 整句删除（认证号已在属性行明示，重名场景无 perNo 可言） | ✅ 已拍板（2026/11/19）：**B 整句删除**。口径依据：裸我场景登录 userId 即统一认证号（必有、无重名）；人名解析唯一命中必有认证号；重名仅人名扫描会出现且必然反问确认（本轮不执行查询，契约句悬空无所指）。属性行的认证号被块头总契约覆盖。实测小模型漏用 perNo 再回补 |

### 13.7 场景口径补充（拍板④ 依据）

- **裸我**（「我的 …」）：`ChatRequest.userId` 即统一认证号，台账按其查询——认证号必有、无重名可能；
- **人名扫描唯一命中**（「李四 本周 …」）：认证号由台账 Q2 解析，必有；重名（多行）时不出属性行，注入"存在重名（n人），请先确认"提示行走反问轮；
- **重名轮不执行查询**：LLM 路径由提示行驱动 LLM 反问确认，直接执行侧 fail-safe 不出参数——契约句在重名轮无落参对象。

## 14. 相关方案文档

- `docs/202611/tool_metric_catalog目录机制解析.md`（tool 目录标签真源与词袋收窄，§11.2 方案的前置）
- `docs/history/dimension-alias-config-plan.md`（§13.4 两遍分段抽取）
- `docs/202610/人员台账维度方案.md`（粒度词守卫、人员台账）
- `docs/202610/检索关键词与维度值冲突治理方案.md`（span 屏蔽 S4）
- `docs/202611/同级维度消解-维度提及无值快照删除与词表冲突方案.md`（家族消解，直接执行层）
- `docs/history/skill-direct-exec-plan.md`（previewState 参数契约）
