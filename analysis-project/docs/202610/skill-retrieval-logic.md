# Skill 检索链路现状（截至 2026/10/14）

> 结论先行：当前 Skill 检索是**确定性的 metadata 分级关键词下钻路由**，不是向量/embedding 检索。
> embedding 路径（SkillVectorIndex、SkillRetrievalHook）已于 2026/07/25 停用、2026/07/30 物理删除；
> 2026/08/31 起 metadata 路由正式上线（`application.properties:267` `harness.a2a.skill-context.routing.enabled=true`）；
> 2026/10/13 在单一 keywords 层之上增加三级层级关键词（见 §7）；
> 2026/10/14 层级语义二次定稿：从"任一层命中即入围（只加权不过滤）"改为**命中即逐层收窄**（§7.3）；
> **2026/10/22 四次定稿：池级收窄与拍板⑥废弃，改为按 skill 自身配置层级的 AND + 双轨并存（见 §9，当前生效语义）。**

## 1. 运行时全流程

```
用户问题
  │ V2ChatStreamServiceImpl.buildRuntimeContext
  │   ctx.put("lastQuestion", question)          ← 问题原文入口 (V2ChatStreamServiceImpl.java:842)
  ▼
SkillVectorIndexVisibilityFilter.filter(all, ctx)   ← JAR SkillCurator 每次装配可见 Skill 目录时回调
  │ 1) enabled=false 或 ctx 为空 → 全量放行
  │ 2) SkillUsageResolver 按用户过滤权限 (builtin workspace skill 例外)
  │ 3) question 为空 → 全量放行
  │ 4) 读 skill_routing_metadata 全量 (TTL 30s 缓存)
  │ 5) SkillCandidateSelector.select(all, activeMetadata, question) → Top-K 候选
  │ 6) 追加"无 metadata 行"的 skill (热加载新 SKILL.md 不至于隐身)
  ▼
LLM 只看到候选目录 → 决定调 load_skill_through_path(path=…) 读 SKILL.md 全文
  ▼
SkillFixedToolGuardHook 武装固定工具链 (load_skill → router_tool → python_exec → arith)
```

## 2. 核心组件

### 2.1 SkillRoutingMetadataRepository（数据源）
`v2/skills/SkillRoutingMetadataRepository.java`

- 表：`skill_routing_metadata`（openGauss，`ensureTable()` 幂等建表 + 补列；flyway 对该库不可用，见 memory `opengauss_flyway_and_empty_string_null`）。
- 字段：`skill_name`(PK) / `short_summary` / `keywords`(JSON 数组) / `domain_tags` / `topic_tags` / `maintainer` / `metric_tags`(遗留) / `priority`(遗留) / `active` / `updated_at`。
- 读路径走 **TTL 快照缓存**（`harness.a2a.skill-context.metadata-cache-ttl-ms=30000`）；`upsert/rename/deactivate/delete` 写后立即 `invalidateCache()`，管理页保存即时生效。
- `findAll()`（含 inactive）与 `findActive()` 有区分：**"从未配置"** 和 **"显式停用"** 是两种语义，前者始终可见，后者始终隐藏。
- 管理页查询走 `findAllWithSkillManage`：`skill_manage`（页面创建，带 owner）∪ `skill_index`（builtin）LEFT JOIN routing metadata。

### 2.2 SkillCandidateSelector（打分）
`v2/skills/SkillCandidateSelector.java` — 纯确定性，无 LLM、无 embedding。

| 信号 | 分值 | 说明 |
|---|---|---|
| 问题显式包含 skill 名 | 20_000 | 直接命中，其余关键词不累加 |
| domain 命中 | 6_000 | 高于通用 skill 得分上限（叶层 cap 4_000），保证领域 skill 排在通用 skill 前 |
| 一级关键词命中 | +500 | 层级下钻（2026/10/13），逐层累加 |
| 二级关键词命中 | +1_000 | 同上 |
| 三级关键词命中 | +1_500 | 同上 |
| 每个 keyword（叶层）命中 | +2_000 | 单 skill 上限 4_000，与层级分累加 |

- **四层权重倒序 L4 > L3 > L2 > L1**（2026/10/14 二次定稿）：叶层 keywords 是管理员配置的精确词，命中说明力最强，权重必须最高；L1 是宽泛入口词，命中说明力最弱。初版（10/13）叶层 500/词低于 L3 的 1_500，语义倒挂，已修正。
- **跨层重复计分是有意行为**（2026/10/14 用户拍板"需要双倍得分"）：同一词条配置在多层且都命中时各层分数都拿（如"质量"同时命中 L1 和叶层 = 500+2_000）。分数衡量"它把问题信号配置得多重"，不按"不同词个数"去重。

- 匹配语义：`lowercase + trim` 后 `question.contains(term)`。
- **不命中任何 keyword 且未被点名 → 直接出局**（无 LOW 分兜底，无佐证不出候选，与 Skill↔Tool 重叠 S1 换轴同哲学）。
- `domainTags` 是**互斥域过滤**：问题命中某领域时，其他领域且非通用的 skill 出局（过滤在 filter 层与 selector 内各做一次）。
- `topicTags` 仅内部管理归类，**不参与匹配**；`metric_tags`/`priority` 已随评分体系简化废弃。
- 排序：explicit > score > keywordHits > name 字典序；截断两档看榜首（2026/10/13 起含层级下钻，见 §7.3）：榜首命中链完整 → `maxVisibleSkills`(=5)，榜首仅上层保底 → `fallbackVisibleSkills`(=10)。
- **层级命中同时承担收窄过滤**（2026/10/14 二次定稿）：某层有命中时，配置了该层却未命中的 skill 出局（即使叶层命中），该层未配置的 skill 直通；详见 §7.3。

### 2.3 SkillVectorIndexVisibilityFilter（唯一入口）
`v2/skills/SkillVectorIndexVisibilityFilter.java` — 实现 JAR 的 `SkillVisibilityFilter` SPI，注册于 `V2SkillConfig:109`。

关键规则（路由永远不能把 skill 变得不可达）：
1. **无 metadata 行的 skill 恒可见** —— 热加载新 SKILL.md 后、管理员还没配置路由时不会隐身（2026/07/29 热加载回归的修复）。
2. `active=false` 的行 = 管理员显式停用，恒隐藏。
3. 全部 metadata 为空 → 全量放行并 warn。
4. 纯关键词语义下，配置了路由行但零命中的 skill **不进候选**，最终可见集可以为空 —— 此时 LLM 直接走工具，不再有 domain-gated 兜底。
5. `skillUsageResolver`（per-user `skill_manage` 权限）在前置过滤：非 builtin skill 必须在用户的可用集合内；解析失败 fail-closed 隐藏全部。

### 2.4 配置项（application.properties:267-270）

```properties
harness.a2a.skill-context.routing.enabled=true      # 路由开关
harness.a2a.skill-context.max-visible-skills=5      # 精确档: 榜首候选命中链完整 (问题精确) 时的目录上限
harness.a2a.skill-context.fallback-visible-skills=10 # 放宽档: 榜首仅靠上层保底存活 (问题宽泛) 时放宽到 10
harness.a2a.skill-context.metadata-cache-ttl-ms=30000
```

## 3. 写入侧（谁生成 routing metadata）

| 入口 | 行为 |
|---|---|
| `BuiltinSkillRegistrar`（启动时） | 扫 `workspace/skills/` 把新 SKILL.md 注册进 `skill_index`，并生成 **name-derived draft 路由行**（active=true），新 builtin skill 即刻可路由；对已从磁盘消失的 builtin skill 做 tombstone 清理（`delete`）。 |
| `SkillManageBridge`（页面双写） | Skill 配置页创建/更新时同步 routing metadata，页面 skill 才能被对话加载。 |
| `SkillRoutingMetadataController` / `SkillRoutingTagController` | 管理页直接编辑 keywords/domain/topic/active；domain/topic 标签须在 `SkillRoutingTagDictionary` 登记且启用（词典空则自由填写）。 |
| `SkillSaveTool` / SkillSynthesis 链路 | agent 产出新 skill 时落 `skill_index` + 路由行。 |

## 4. 与相邻链路的边界

- **`load_skill_through_path`**：目录可见 ≠ 内容加载。LLM 从候选目录选中后，用绝对路径调该工具读 SKILL.md 全文（`DatabaseSkillRepository` 按 path 精确匹配）；结果经 `ToolResultTruncationMiddleware` 压缩、`ArtifactHandoffHook` 提取字段映射表。
- **`SkillFixedToolGuardHook`**：`load_skill_through_path` 成功后武装固定工具链，PostActing 机械覆写 `tool_index`/`toolMetaInfo`，与检索是两道独立防线。
- **工具路由（tool_routing）**：`tool_index` 的 metadata 路由是独立体系（`harness.a2a.tool-routing.*`），skill 检索不经过它；目录渲染主题→指标词表判定终局化后（2026/09/10），skill 目录之外不再下探 `tool_index`。
- **重叠治理**：`SkillSkillOverlapService` / `SkillToolOverlapService` / `SkillDescriptionSimilarityService` 消费同一份 `skill_routing_metadata`（keywords/domain/描述），启动审计与启用互斥是检索语义的约束来源（入口词归一化与 `SkillCandidateSelector` 保持一致）。

## 5. 历史沿革（为什么是现在这样）

| 时间 | 事件 |
|---|---|
| 2026/07 中旬 | v1 时代：`SkillRetrievalHook` + `SkillVectorIndex`，embedding Top-K 检索注入 prompt。 |
| 2026/07/25 | 检索 hook 关停（`harness.skills.retrieval.enabled=false`），改为 ReAct 显式 `load_skill_through_path`。 |
| 2026/07/29 | visibility filter 改纯 pass-through，解锁 `skills/` 热加载。 |
| 2026/07/30 | 删除 `SkillVectorIndex` + `SkillRetrievalHook` + 5 处 embedding 空转写入；EmbeddingClient 仅保留给 episodic memory。 |
| 2026/08/31 | **确定性 metadata 路由上线**：Top-K 候选 + 空候选不兜底 + TTL 缓存；draft skill 默认 active=true。 |
| 2026/09/07 | 统一工具路由切换（tool_index cutover），skill 与 tool 治理对齐同一套四层一致性规则。 |
| 2026/09~10 | Skill↔Tool S1 换轴（keywords 命中、无 LOW 佐证不出记录）、Skill↔Skill 三层一致性 + 启动互斥——检索侧的"无佐证不出候选"哲学随之固化。 |
| 2026/10/14 | 层级语义二次定稿："任一层命中即入围（只加权不过滤）"改为**命中即逐层收窄**（L1 配置了却未命中的 skill 出局，即使叶层命中；未配置层直通；该层无命中跳过收窄），见 §7.3。 |
| 2026/10/14（晚） | 权重三次定稿：四层权重倒序 **L4(叶层 2000/词 cap 4000) > L3(1500) > L2(1000) > L1(500)**，跨层重复计分确认保留（"需要双倍得分"）；domain 3000→6000、explicit 10000→20000 同步上调保不变量。 |

## 6. 已知坑

- `skill_routing_metadata` 所在 openGauss 把 `''` 视为 NULL，`maintainer` 必须保持可空列（DDL 注释有说明）。
- flyway 在该库不可用，新列必须走 `ensureColumn` 运行时幂等补列，不能写迁移脚本。
- `metric_tags`/`priority` 列仍在表中但代码已不读；清理时注意 `findAll()` 的 SELECT 列表与 `map()` 对齐（曾出过 `map()` 读死列的 updated_at 死代码 bug）。
- 改 `SkillCandidateSelector` 的归一化逻辑时，`SkillSkillOverlapService` 的入口词归一化刻意保持一致，两边要同步改。
- 关键词条目支持正则后，**能编译成合法正则的词按正则解释**（如 `C++`、`v1.2` 含元字符，语义与字面不同）；配置时纯字面词避免 `\.^$|?*+()[]{}|` 等元字符，或写成转义形式。编译失败的（如 `[`）自动回退字面包含。

## 7. 设计方案：分级关键词层层下钻（2026/10/13）

### 7.1 动机

单一 keywords 层平铺打分不够用：宽泛词（"问题"）召回太多，精确词（"未闭环问题"）要逐个枚举配不全。参照 tool 侧 `ToolIndexService.index` 的 topic→metric→dimension 逐层收窄下钻，给 skill 侧增加层级；差别在于 tool 下钻由 LLM/agent 传结构化标签，skill 检索没有 agent 中转，**改为用正则直接匹配用户问题文本**。domain 层不动。

### 7.2 四层结构与存储

```
skill_routing_metadata 新增 3 列 (ensureColumn 幂等补列, JSON 数组同 keywords):

level1_keywords  一级关键词（最宽泛入口词，如 "问题"）
level2_keywords  二级关键词（在一级下钻，如 "未闭环"）
level3_keywords  三级关键词（最具体，如 "严重"）
keywords         叶层（现状不动，精确词）
```

Record/Input/View 各加 `level1Keywords/level2Keywords/level3Keywords` 字段（追加在尾部，旧 8/5/10 参构造器保留为重载，存量调用点零改动）。

### 7.3 下钻算法（SkillCandidateSelector）— 已实施（2026/10/14，395 单测全过）

> ⚠️ **本节"命中即逐层收窄"（池级收窄）已于 2026/10/22 被四次定稿取代（§9）**：收窄不再跨 skill 联动，改为每个 skill 自身配置层级的 AND；叶层-only 存量 skill 不再被拍板⑥挤出。以下保留作历史沿革。

> **语义沿革**：
> - 初版（2026/10/13）"层层硬过滤+空层回退"有两个漏洞——(1) pool 里未配置层级的通用 skill 让每层收窄永远"成功"，只命中 L1 的 skill 在 L2 直接出局；(2) 层层下钻要求自上而下进链，真实用户经常只说最具体的词（"扫描告警"只含 L3），精确配置了 L3 的 skill 反而进不来。当日修正为"任一层命中即入围"（层级只加权不过滤）。
> - **二次定稿（2026/10/14）**："只加权不过滤"在实际配置上出反例——用户问题"三部10月未闭环问题"命中了 skill1 的 L1"问题"，但 L1 配了"研发问题"/"测试问题"的 skill2/skill3 仅靠叶层"未闭环"照样入围，L1 的收窄语义失效。定稿为**命中即逐层收窄**：某层一旦有命中，该层就从"加分项"升级为"过滤器"，配置了该层却未命中的 skill 出局；该层未配置（为空）的 skill 视为通用，直通。深层反向入围场景由"该层无命中则跳过收窄"保住。
> - **三次定稿（2026/10/14，权重倒序）**：E2E"质量告警"暴露叶层权重倒挂——叶层 keywords 是管理员配的精确词，分值（500/词）却低于 L3（1500）。定稿：**四层权重 L4(叶层) > L3 > L2 > L1**（叶层 2000/词 cap 4000），并确认**跨层重复计分保留**（同一词条命中多层各层都拿分，"需要双倍得分"用户拍板；不按不同词去重）。domain/explicit 常数同步上调以保住"explicit 最高、domain 压通用"两条不变量。

```
第 0 步 预选（与旧一致）:
    入池 = 显式点名 skillName ∨ 任一层 (L1/L2/L3/叶层) 至少一个词条正则命中问题
    （全不命中 → 出局, 返回空集合, LLM 直接走工具）

第 1~3 步 逐层收窄 (L1 → L2 → L3):
    若当前池中存在 skill 在该层命中:
        池 ← { 该层命中的 skill } ∪ { 该层未配置的 skill }
        （配置了该层却未命中的 skill 出局 —— 即使它叶层命中）
    若该层无任何命中: 跳过, 不收窄（保住"只说最具体词"的深层反向入围）
    叶层 keywords 不收窄, 收窄只发生在 L1/L2/L3

打分排序（2026/10/14 二次定稿: 四层权重倒序 L4 > L3 > L2 > L1, 跨层累加保留）:
    L1 +500 / L2 +1000 / L3 +1500 / 叶层 L4 +2000/词 (cap 4000)
    同一词条配置在多层且都命中时各层分数都拿 (双倍得分是有意行为)
    排序 explicit(20000) > score > keywordHits > name 字典序
    domain(6000) 保证: 通用 skill (三层全空) 只能拿叶层分 ≤ 4000 < domain skill 最低 6000+500

截断两档看榜首（不变）:
    榜首配置的每层都命中（或显式点名/层未配置）→ 问题精确, max-visible-skills(5)
    榜首有配置了却未命中的层 → 问题宽泛, fallback-visible-skills(10)
```

**定稿案例**（4 个 skill 均配叶层"未闭环"）：

| Skill | L1 | 叶层 |
|---|---|---|
| skill1 | 问题 | 未闭环 |
| skill2 | 研发问题 | 未闭环 |
| skill3 | 测试问题 | 未闭环 |
| skill4 | （空） | 未闭环 |

问"**三部10月未闭环问题**"：

1. 预选：skill1（L1"问题"命中）、skill2/skill3/skill4（叶层"未闭环"命中）全部入池。
2. L1 收窄：skill1 的 L1 命中 → 池收窄为 {命中 L1 的 skill1} ∪ {L1 未配置的 skill4}；skill2/skill3 的 L1 配置了却未命中 → **出局**。
3. L2/L3：无任何命中 → 跳过。
4. 结果：**skill1 + skill4**；skill1 榜首命中链完整 → 精确档 `max-visible-skills`。

- **收窄与直通的边界**："该层未配置"= 数组为空。skill4 三层全空 → 任何收窄都直通，是纯叶层兜底的通用 skill；只要某层配了词，就等于承诺"问题触及该层时我必须在该层命中，否则让位"。
- **深层反向入围仍成立**：code_quality 配 L1"质量"/L2"代码"/L3"告警"，问"扫描告警"（只含 L3 词）→ L1/L2 无命中跳过收窄，L3 收窄时命中的 skill 与 L3 未配置的 skill 留下；code_quality 靠 L3 命中 +1500 排最前。
- **浅层收窄示例**：问"质量情况"（仅含 L1"质量"）→ L1 收窄后，L1 配置了别的词（如"缺陷"）但叶层"质量"命中的 skill 出局——这是与 2026/10/13 版最大的行为差异：**层配置是承诺，不是可选项**。
- **淘汰示例**：问"查询达标率"与 A 的四层全不沾边 → 预选出局；未配置层的通用 skill 靠叶层命中入围（若同批有更深命中触发收窄，仍须按收窄规则二次筛选）。
- "只配 L1 + 叶层关键词、两层都命中"完全合法：得分 L1 500 + 叶层 2000/词（跨层累加）。
- 配置建议：L1 放最宽的入口词（问题里最常出现的词），叶层 keywords 放最精确的词（权重最高，2000/词）；**不确定该不该配某层时留空**（留空=通用直通，配置=收窄承诺）。

### 7.4 正则策略

- 词条 `Pattern.compile(term, CASE_INSENSITIVE)` 成功 → `find()` 匹配；失败 → 回退字面 contains（静态 `PATTERN_CACHE` 缓存，`Optional.empty()` 标记非法词避免重复编译报错）。
- **分隔符坑**：管理页逗号/顿号/换行切分会切开 `{1,3}` 里的逗号——前端 `split()` 与后端 `AdminService.splitAwareOfRegexBraces` 均已做括号感知（`{...}` 内逗号不切）。

### 7.5 与 tool 下钻的对照

| | ToolIndexService | SkillCandidateSelector |
|---|---|---|
| 层级 | topic → metric → dimension | L1 → L2 → L3 → keywords 叶层 |
| 输入来源 | agent 传结构化标签 | 正则直接匹配用户问题 |
| 层级作用 | 逐层收窄过滤 | 命中即逐层收窄（该层无命中则跳过；未配置层直通；2026/10/14 定稿） |
| 未命中 | 空 + unknown 标记指标 | 空集合，LLM 直接走工具（不兜底） |

### 7.6 兼容性

- 存量行三列回填 `'[]'` → 全部层"未配置"直通，叶层语义与旧版完全一致（旧单测不改一行全过）。
- `SkillRoutingStartupAudit` 自动停用重建行时透传层级字段（否则会清空配置）；`SkillManageBridge`/`BuiltinSkillRegistrar` draft 行走旧 8 参重载（空层级）。
- 重叠治理（SkillSkillOverlap 等）本期不纳入层级字段，仍看 domain/keywords/描述。

### 7.7 配置入口

管理页（Skill 配置页）编辑弹窗新增一级/二级/三级关键词三个输入框（顿号/换行分隔，支持正则），表格加"层级关键词"列；API `PUT /api/skill-routing/{skillName}` body 增加同名 camelCase 字段。

## 8. 设计方案：检索旁路词 + 两阶段匹配（2026-10-15 拍板，待实施）

### 8.1 拍板记录

| # | 决策点 | 结论 |
|---|---|---|
| ① | 旁路生效范围 | **两处同拦**：`SkillVectorIndexVisibilityFilter`（目录置空）+ `DirectSkillExecutor.plan()`（短路放弃回退 LLM）——直接执行短路在 LLM 之前，只拦目录不拦短路则旁路形同虚设。⚠️ **2026/10/27 修订**（`docs/202610/直接执行优先于旁路词重排序方案.md`）：plan() 内旁路检查后移到 selector 之后，层级轨 top-1（L1/L2/L3 AND 命中）让位旁路——直接执行优先于旁路词；目录侧维持无条件置空 |
| ② | 质量类 skill | **实质退役**：质量板块（质量/缺陷密度/检出率/研发问题/D号/适应性问题/A号）切到原子工具体系，含旁路词的问题不走任何 quality skill；配套运维动作=管理员停用相关 skill（代码不删） |
| ③ | 旁路匹配语义 | **平铺 OR + contains**，分组（分析类/表格类/质量板块）仅作归类备注不参与逻辑；表格词用「表格」一词即可 contains 覆盖 制作表格/表格展示/表格列出/表格输出 等全部形态 |
| ④ | 显式点名 | **旁路让位**：问题包含任一 active skill 名（与 selector explicit 同语义 contains）→ 本轮不旁路，照常检索 |
| ⑤ | 匹配粒度 | **复用 selector 现有词条机制**（§7.4）：`lowercase+trim` 后可编译成正则的词按正则 `find()` 解释、编译失败回退字面 contains；seed 词均为字面词（行为=contains），管理员未来可用 lookaround 词形排除误伤（如 `(?<!数据)分析`），不用改代码 |
| ⑥ | 两阶段细节 | 阶段一保留「命中即逐层收窄」（§7.3 不变）；阶段一命中时只配叶层的 skill 出局（即使叶层命中）；domain 互斥过滤只在阶段二；阶段一**无四层打分**，纯按层级命中数/顺序排序（§7.3 三次定稿的 L4>L3>L2>L1 权重体系就此推翻） |
| ⑦ | 否定语境 | **方案 A：不建否定词表、不加抑制代码**。词条机制（§7.4）已支持正则，个别特殊词条由管理员配 lookaround 词形排除否定误命中，如配 `(?<!不包含)(?<!不)(?<!未)遗漏` 则「不包含遗漏」不命中、「包含遗漏的缺陷」命中；仅有少数特殊词需要这样配，普通词保持字面 |
| ⑧ | 漏斗优先级 | **层深度漏斗 + 全局关键词优先级页**（2026-10-15 定稿，推翻前稿的 skill 级 priority 分数方案——不打分、不复活 `priority` 列、不逐 skill 配置）。优先级配在**词**上、全局生效：新增表 `skill_keyword_priority(level, parent_keyword, keyword, sort_order)`（gauss 库，照 dimension_key_config 模式）；`level=1` 行 = 一级词全局顺序；`level=2` 行 = 二级词、`parent_keyword` = 所属一级词（**「哪个二级属于哪个一级」的分组关系由该页维护**，skill 词表只管成员资格）；前端独立页：一级一个有序列表可拖曳，二级按一级分组多行 table、**行可拖（组间优先级）、行内词也可拖（组内漏斗）**，三级同理。排序键 = 显式点名 > 层深度 (L3>L2>L1) > 命中词全局位次（skill 取其该层最优命中的 sort_order）> name；未入全局表的词排已配置词之后。匹配/入围逻辑不变（仍看各 skill 自己的词表），该表只参与排序 |

### 8.2 旁路词存储与配置

新表 `skill_bypass_keyword`（gaussCustomerDataSource，照抄 dimension_key_config 模式）：

```
id BIGSERIAL PK
group_name  VARCHAR(64)   -- 归类备注：分析类 / 表格类 / 质量板块（不参与匹配）
keyword     VARCHAR(128)  -- 旁路词，selector 词条语义（可编译即正则, 否则字面 contains）
enabled     BOOLEAN DEFAULT TRUE
remark      VARCHAR(255)
created_at / updated_at
```

- `ensureTable()` 幂等建表 + seed 三组词：
  - 分析类：分析、趋势、对比、分布、归因、标准差、分位数、相关系数、同比、环比、改进建议、报告、探索式分析
  - 表格类：表格（contains 已覆盖「制作表格/表格展示/表格列出/表格输出」等变体）
  - 质量板块：质量、缺陷密度、检出率、研发问题、D号、适应性问题、A号
- 存量表存在性补 seed（完全无该 keyword 的行才插入；软删行不复活，同 timeRange 补种语义）
- TTL 快照（同 dimension_key_config 模式）；管理接口 `SkillBypassKeywordController`：
  GET/POST/PUT/DELETE `/v2/skill/bypass-keyword` + POST `.../reload`，写仅管理员
- 前端：Skill 配置页新增「检索旁路词」区块（独立于 skill 行编辑）：分组列表 CRUD +
  启用开关 + 刷新快照按钮

### 8.3 旁路判定（新组件 SkillBypassKeywordService）

单一判定入口，两处消费：

```java
boolean bypassed(String question):
    1. 任一 enabled 旁路词按 selector 词条语义命中 → 继续; 否则 false
       （可编译为正则 → Pattern.find(); 编译失败 → 字面 contains;
        lowercase+trim 归一化与 selector 一致, 词条匹配逻辑复用同一实现）
    2. 问题 contains 任一 active skill 名（explicit 同语义）→ false（拍板④）
    3. true
```

词条匹配复用：`SkillCandidateSelector` 的正则词条匹配（`PATTERN_CACHE` + 非法词回退
字面）抽为公共静态工具或直接暴露静态方法，旁路判定与 selector 消费同一实现——
两处行为不得漂移（同 §6 已知坑「归一化逻辑两边同步改」的纪律）。

否定语境按拍板⑦（方案 A）走配置不走代码：特殊词条配 lookaround 词形
（`(?<!不)遗漏`），机制零改动；前端 Skill 配置页词条输入框的提示文案补一句示例
（「支持正则：`(?<!不)遗漏` 可排除"不遗漏/不包含遗漏"等否定形态」），降低配置门槛。

消费点：
1. `SkillVectorIndexVisibilityFilter.filter()`：selector 之前调 `bypassed()` → 命中返回
   空候选集（LLM 直接走工具，现有空候选语义不变）；
2. `DirectSkillExecutor.plan()`：入口处（metadata 查询之前）调 `bypassed()` → 命中
   return null（短路放弃，回退 LLM→tool 链路）。

### 8.4 两阶段匹配（SkillCandidateSelector.select 重构）

> ⚠️ **拍板⑥（阶段一命中时只配叶层的 skill 出局）与阶段一池级收窄已于 2026/10/22 被四次定稿取代（§9）**；拍板⑧漏斗排序（层深度 > 命中词全局位次 > name）在层级轨内保留。以下保留作历史沿革。

```
阶段 0 显式点名（最高优先, 不受旁路影响）:
    问题 contains skill 名 → 该 skill 直接入池置顶（不变）

阶段 1 层级匹配（新增, 优先于一切叶层/domain 信号）:
    预选 = L1 ∨ L2 ∨ L3 任一词条命中问题 的 skill（不看 domain、不看叶层）
    命中即逐层收窄: L1→L2→L3, 语义与 §7.3 完全一致
    （该层有命中→未命中的配置层 skill 出局; 该层未配置直通; 无命中跳过收窄）
    非空 → 排序: 显式点名 > 漏斗位置（层深度 L3>L2>L1, 每个 skill
          取其最深命中层作排序键） > 命中词全局位次（skill 取其该层**最优命中词**
          的全局 sort_order, 小者前; 词不在全局表 → 排已配置词之后） > name 字典序
          （无四层打分、无 priority 分数; 只配叶层的 skill 即使叶层命中也不在池中）
    截断: 榜首命中链完整 → max-visible-skills; 否则 fallback-visible-skills

漏斗语义（拍板⑧）: 收窄不动, 漏斗只影响排序。优先级配在**词**上、全局生效
    （skill 不单独配分数）: 全局页把二级词按所属一级分行, 拖行 = 组间优先级,
    行内拖词 = 组内漏斗。「未闭环」(缺陷组) vs「长时效」(A号组) 同层命中时,
    把 A号 行拖到 缺陷 行上面 → 长时效所在 skill 即赢。

阶段 2 叶层回退（阶段一为空时才走, = 2026/10/13 之前的旧行为）:
    domain 互斥域过滤 + 叶层 keywords 匹配 + 显式点名
    （层级词全未命中 → 层级分自然为 0, 原打分只剩 explicit/domain/叶层;
     §7.3 的层级收窄在此阶段天然不触发）
```

要点：
- 叶层 keywords 从「最高权重信号（2000/词）」降级为「层级全空时的兜底」——权重倒序
  三次定稿（2026/10/14 晚）被本方案推翻，§7.3 的打分描述保留作历史沿革；
- 质量类 skill 若叶层配「缺陷密度」：含质量词的问题在阶段 0/1 之前已被旁路置空，
  skill 永远不可见（= 拍板②退役）；不含旁路词的长尾问法仍可走阶段 2 兜底。

### 8.5 改动清单

- 新增 `v2/skills/SkillBypassKeywordService`（判定 + TTL 快照）+
  `SkillBypassKeywordRepository`（ensureTable + seed + 存在性补种）+
  `SkillBypassKeywordController`（管理接口）+ `V2SkillConfig` bean 注册；
- `SkillVectorIndexVisibilityFilter`：selector 前挂旁路判定；
- `DirectSkillExecutor.plan()`：入口挂旁路判定；
- `SkillCandidateSelector`：select 重构为两阶段（阶段 0/1/2），删除四层打分权重
  （L1 500 / L2 1000 / L3 1500 / 叶层 2000）与「叶层权重最高」相关单测；
  阶段一排序键 = 漏斗层深度 > 命中词全局位次 > name（无 priority 分数，拍板⑧）；
- 新增 `v2/skills/SkillKeywordPriorityRepository`（ensureTable + TTL 快照，照
  dimension_key_config 模式）+ Controller（`/v2/skill/keyword-priority` CRUD +
  reload，仅管理员写）；表结构 `skill_keyword_priority(level, parent_keyword,
  keyword, sort_order)`，level∈{1,2,3}，level≥2 时 parent_keyword 必填；
- 词条正则匹配共享化：selector 的 PATTERN_CACHE 词条匹配逻辑抽公共静态工具，
  旁路词与四层词条共用（旁路词复用 selector 词条语义=拍板⑤，两处行为必须一致）；
- 前端 `SkillRoutingConfigPage.vue`：「检索旁路词」CRUD 区块；词条输入框旁提示
  否定 lookaround 写法（拍板⑦）；
- 前端新增**全局关键词优先级独立页**（Skill 配置页入口跳转，路由挂 /script-registry/
  下）：一级 = 有序列表拖曳；二级 = 按所属一级分组的多行 table（分组关系在本页
  维护），**行可拖（组间优先级）、行内词可拖（组内漏斗）**；三级同理。保存写
  skill_keyword_priority 的 sort_order；不碰 skill_routing_metadata（priority 列
  继续遗留不读、各 skill 词表成员资格不变）；
- `application.properties`：无新开关（旁路词空表 = 功能天然关闭）；
- 文档：本节实施后补记 + §7.3 权重语义标注"已被 §8 两阶段取代"。

### 8.6 测试要点

1. 旁路：三组词各命中（seed 字面词=contains 行为）→ 目录空 + plan null；显式点名
   skill → 不旁路；旁路词全停用 → 行为与现状一致；TTL 快照与 reload；
   正则词形：配 `(?<!数据)分析` →「数据分析」不旁路、「分析」旁路；非法正则（如 `[`）
   回退字面 contains 不炸；lookaround 否定排除（拍板⑦）：四层词条配 `(?<!不包含)遗漏`
   →「不包含遗漏」不命中、「包含遗漏的缺陷」命中；
2. 两阶段：阶段一命中（含收窄语义回归 §7.3 全部定稿案例）；阶段一命中时叶层-only
   skill 出局；阶段一空 → 阶段二旧行为（domain 过滤/叶层打分/explicit）；
3. 排序（拍板⑧）：漏斗层深度 > 命中词全局位次 > name；L3 命中压过 L2 命中；
   同层两 skill 各取其最优命中词的 sort_order 比较位次；「未闭环(缺陷组)/长时效
   (A号组)」案例：A号行拖到缺陷行上面 → 长时效所在 skill 赢；行内拖词同理生效
   （组内漏斗）；词未入全局表 → 排已配置词之后、再按 name；显式点名恒置顶；
   全局表空 = 功能天然关闭（行为与仅剩层深度+name 一致）；
4. 存量： selector 相关 395 单测按新语义修订（打分类断言改排序断言），
   DirectSkillExecutor / Repository 基线不回归；
5. 联动：旁路词命中 + DirectSkillExecutor 存量用例（直接执行被旁路回退 LLM）。

## 9. 四次定稿：按 skill 自身配置层级的 AND + 双轨并存（2026/10/22，已实施）

### 9.1 动机

二次定稿（§7.3）的"命中即逐层收窄"是**池级**语义：某层是否成为过滤器取决于**池里其他 skill** 是否在该层命中，跨 skill 联动、语义反直觉；且 §8 拍板⑥使阶段一一旦有层级命中，只配领域标签+keywords 的存量 skill 全部出局——层级关键词上线后存量检索被殃及。定稿：收窄只看 skill **自身**配置。

### 9.2 语义（SkillCandidateSelector.selectInternal）

- **显式点名**：问题 contains skill 名 → 恒置顶，豁免 AND（不变）。
- **层级轨**（L1/L2/L3 任一数组非空）：**每个配置了的层都必须有词条命中问题**（AND，未配置层不设要求）：
  1. 仅配一级 → 一级需命中（其一词）；
  2. 配一级+二级 → 一级与二级各命中一词（同时满足）；
  3. 配一级+二级+三级 → 三层各命中一词（同时满足）。
  任一配置层未命中 → **该 skill 本轮完全出局**（不落叶层回退；显式点名除外）。深层词单独命中（如只说 L3 词）不再入围——§7.3 的"深层反向入围"随之废止。
- **存量轨**（三层全空，仅领域标签+keywords）：行为与 2026/10/13 之前完全一致——domain 互斥域过滤 + 叶层 keywords 命中打分（2000/词 cap 4000）+ domain 排序加成；**不再被层级 skill 的命中挤出**（拍板⑥废止）。
- **双轨合并排序**（单一比较器）：显式点名 > 层级轨（层深度 L3>L2>L1 > 命中词全局位次，拍板⑧漏斗保留）> 存量轨（domain+叶层分）> name。depth 天然分层（层级轨 1-3、存量轨 0）。
- **截断两档**：榜首 partial（配置了叶层 keywords 却零命中；层级命中已由 AND 强制不再构成 partial）→ fallback-visible-skills，否则 max-visible-skills。
- **分组保底兼容**（docs/202610/Skill检索分组保底方案.md）：保留字「保底」注入机制零改动——L2/L3 含「保底」且上层命中 → 注入命中 → AND 视为满足；保底 9 个单测原样通过。纯保底层（该层只有保留字）无法靠文本命中，AND 下与旧逻辑同样无法自行入池。

### 9.3 现网配置形态对照

| Skill 配置 | 问法 | 结果 |
|---|---|---|
| wenshu_qi_wanchenglv_dabiaolv（L1=Qi/卡口/Q1-Q4/准入，L2=达标率/通过率/完成率，叶层=拼音） | "Qi 各阶段达标率" | L1+L2 各命中一词 → AND 满足，层级轨排前 |
| 同上 | "Qi 卡口整体情况"（L2 未命中） | AND 不满足 → 出局 |
| dept_Q1 例会 skill（三层全空，叶层=Q1/需求卡口，domain=部门例会） | "Q1 部门例会需求卡口不达标情况" | 存量轨叶层命中照常入围，**不被前者 L1"Q1"命中挤出** |
| 两者同池 | "Q1 达标率情况" | 双轨并存：层级轨排前，存量轨跟随 |

### 9.4 改动与测试

- `SkillCandidateSelector`：删 `narrowByLevels`/`narrowAtLevel`；selectInternal 改为显式/层级（AND 判定后）/存量三池 + 单池合并排序；`partiallyMatched` 只留叶层子句。消费方（`SkillVectorIndexVisibilityFilter.filter` / `DirectSkillExecutor.plan`）零改动。
- `SkillCandidateSelectorTest` 33 例（6 个旧语义用例翻转 + 4 个新用例）、`SkillCandidateSelectorFallbackTest` 9 例不动、全量 654 单测通过。
- 存量行三层全空 → 走存量轨，行为与层级功能上线前完全一致。
