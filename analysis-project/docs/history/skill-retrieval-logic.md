# Skill 检索链路现状（截至 2026/10/13）

> 结论先行：当前 Skill 检索是**确定性的 metadata 分级关键词下钻路由**，不是向量/embedding 检索。
> embedding 路径（SkillVectorIndex、SkillRetrievalHook）已于 2026/07/25 停用、2026/07/30 物理删除；
> 2026/08/31 起 metadata 路由正式上线（`application.properties:267` `harness.a2a.skill-context.routing.enabled=true`）；
> 2026/10/13 在单一 keywords 层之上增加三级层级关键词，逐层下钻（见 §7）。

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
| 问题显式包含 skill 名 | 10_000 | 直接命中，其余关键词不累加 |
| domain 命中 | 3_000 | 高于关键词上限，保证领域 skill 排在通用 skill 前 |
| 一级关键词命中 | +500 | 层级下钻（2026/10/13），逐层累加 |
| 二级关键词命中 | +1_000 | 同上 |
| 三级关键词命中 | +1_500 | 同上 |
| 每个 keyword（叶层）命中 | 500 | 单 skill 上限 1_500，与层级分累加 |

- 匹配语义：`lowercase + trim` 后 `question.contains(term)`。
- **不命中任何 keyword 且未被点名 → 直接出局**（无 LOW 分兜底，无佐证不出候选，与 Skill↔Tool 重叠 S1 换轴同哲学）。
- `domainTags` 是**互斥域过滤**：问题命中某领域时，其他领域且非通用的 skill 出局（过滤在 filter 层与 selector 内各做一次）。
- `topicTags` 仅内部管理归类，**不参与匹配**；`metric_tags`/`priority` 已随评分体系简化废弃。
- 排序：explicit > score > keywordHits > name 字典序；截断两档看榜首（2026/10/13 起含层级下钻，见 §7.3）：榜首命中链完整 → `maxVisibleSkills`(=5)，榜首仅上层保底 → `fallbackVisibleSkills`(=10)。

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

### 7.3 下钻算法（SkillCandidateSelector）

> **语义定稿（2026/10/13，两次修正后用户拍板"任一层命中即入围"）**：初版"层层硬过滤+空层回退"有两个漏洞——(1) pool 里未配置层级的通用 skill 让每层收窄永远"成功"，只命中 L1 的 skill 在 L2 直接出局；(2) 层层下钻要求自上而下进链，真实用户经常只说最具体的词（"扫描告警"只含 L3），精确配置了 L3 的 skill 反而进不来。最终语义：**层级只加权不过滤**，见下。

```
入围: 显式点名 skillName ∨ 任一层 (L1/L2/L3/叶层) 至少一个词条正则命中问题
     （全部层都未命中 → 出局, 返回空集合, LLM 直接走工具）
层级分: L1 +500 / L2 +1000 / L3 +1500（单 skill 每层一次），与叶层 keywordScore 累加
       → 问得越具体, 配了越深层且命中的 skill 排越前
截断两档看榜首: 排第一的候选配置的每层都命中（或显式点名）→ 问题精确, max-visible-skills(5)
              榜首有配置了却未命中的层 → 问题宽泛（可信度低）, fallback-visible-skills(10)
```

- **深层反向入围示例**：code_quality 配 L1"质量"/L2"代码"/L3"告警"，问"扫描告警"（只含 L3 词）→ 靠 L3 命中 +1500 入围且排最前（L1/L2 未命中 → 榜首链不完整 → 放宽档 10）。
- **全链示例**：问"查询代码质量告警" → L1+L2+L3 全中 3000 分 + 叶层"质量" 500 → 榜首链完整 → 精确档 5。
- **浅层兜底示例**：问"质量情况"（仅含 L1"质量"）→ 配了该词的 skill 都可见；榜首只中 L1 → 放宽档 10。
- **淘汰示例**：问"查询达标率"与 A 的四层全不沾边 → A 出局；未配置层的通用 skill 靠叶层命中入围。
- "只配 L1 + 叶层关键词、两层都命中"完全合法：得分 L1 500 + 叶层命中分。
- 上层级全命中(1500) > 仅 L1(500)，叶层精确命中的 skill 靠 keywordScore（500/词 cap 1500）反超。
- 配置建议：L1 放最宽的入口词（问题里最常出现的词），L3 放最具体的词；任一层命中都能入围，层级配置只决定"问多深时排多前"。

### 7.4 正则策略

- 词条 `Pattern.compile(term, CASE_INSENSITIVE)` 成功 → `find()` 匹配；失败 → 回退字面 contains（静态 `PATTERN_CACHE` 缓存，`Optional.empty()` 标记非法词避免重复编译报错）。
- **分隔符坑**：管理页逗号/顿号/换行切分会切开 `{1,3}` 里的逗号——前端 `split()` 与后端 `AdminService.splitAwareOfRegexBraces` 均已做括号感知（`{...}` 内逗号不切）。

### 7.5 与 tool 下钻的对照

| | ToolIndexService | SkillCandidateSelector |
|---|---|---|
| 层级 | topic → metric → dimension | L1 → L2 → L3 → keywords 叶层 |
| 输入来源 | agent 传结构化标签 | 正则直接匹配用户问题 |
| 层级作用 | 逐层收窄过滤 | 加权打分不过滤（任一层命中即入围，2026/10/13 定稿） |
| 未命中 | 空 + unknown 标记指标 | 空集合，LLM 直接走工具（不兜底） |

### 7.6 兼容性

- 存量行三列回填 `'[]'` → 全部层"未配置"直通，叶层语义与旧版完全一致（旧单测不改一行全过）。
- `SkillRoutingStartupAudit` 自动停用重建行时透传层级字段（否则会清空配置）；`SkillManageBridge`/`BuiltinSkillRegistrar` draft 行走旧 8 参重载（空层级）。
- 重叠治理（SkillSkillOverlap 等）本期不纳入层级字段，仍看 domain/keywords/描述。

### 7.7 配置入口

管理页（Skill 配置页）编辑弹窗新增一级/二级/三级关键词三个输入框（顿号/换行分隔，支持正则），表格加"层级关键词"列；API `PUT /api/skill-routing/{skillName}` body 增加同名 camelCase 字段。
