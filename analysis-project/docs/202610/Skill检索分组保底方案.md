# Skill 检索分组保底方案（保留词「保底」自动命中）

> 日期：2026-10-16
> 状态：已实施 + 本机 E2E 验证通过（2026-10-16，selector 日志证据）
> 关联：`docs/history/skill-retrieval-logic.md` §7.3/§8（检索两阶段 + 全局关键词优先级）、
> `docs/202610/skill关键词优先级行模型存储方案.md`（v3 平铺表，语义定稿）、
> `docs/history/skill-direct-exec-plan.md`（直接执行，top-1 即执行 → 保底场景误路由风险集中地）

## 1. 问题

配置里每个 L1 分组最后一行是「保底」行（L2=保底，组内 row_order 最大），意图：
**问题命中了组的一级关键词、但该组二级（及以下）关键词全部未命中时，走这个保底 skill**。

典型问题：「问题的整体情况」「Qi 的整体情况」——表述不可穷举（"整体情况/总体情况/大概情况/汇总…"），不可能靠配置词覆盖。

### 1.1 现状走查（为什么「保底」行现在是死配置）

以「问题的整体情况」为例，锚点 `SkillCandidateSelector`（行号为当前代码）：

| 步骤 | 现状 |
|---|---|
| 预选（L100-109） | 组内各 skill 的 L1（问题/缺陷/…）命中 → 全部入层级池；L2/L3 零命中 |
| 收窄（L218-236） | L1 层命中存在 → 全保留；L2 层无任何命中 → 整层跳过 |
| 深度（L125） | 所有候选 depth=1 |
| 位次（L199-209） | L1 词不进优先级索引（v3 语义：L1 无优先级）→ 位次全 MAX_VALUE |
| 排序（L130-133） | depth 平 → 位次平 → **落 name 兜底，top-1 不可控** |

「保底」行在 v3 展开里是 `level=2, keyword=保底, sortOrder=6` 的普通索引条目——但
`bestGlobalRank` 只拿**问题中真命中的词**去查位次，「保底」二字永远不出现在问题里 →
该条目永不命中。配置等于没配。

### 1.2 直接执行放大了这个坑

`skill-direct-exec-plan.md` 拍板口径「候选非空即执行 top-1」：L1-only 平局场景下
top-1 按 name 排序随机落在某个组内 skill 上 → 直接执行错脚本。该文档风险 #1
（top-1 误路由）在本场景集中暴露，需要一个确定性的兜底出口。

## 2. 语义目标（待拍板）

1. **组内兜底**：问题命中组 L1、该组 L2/L3 零命中 → 该组的保底 skill 出战，位次 =
   保底行的组内 row_order（排在任何具体词命中之下、其他 L1-only 候选之上）。
2. **具体词优先**：问题命中组内任一具体 L2/L3 词 → 保底 skill 退后（row_order 6 > 0..5）。
3. **逐层类推**：命中 L2、L3 零命中 → L3 保底行兜底（保底行配 L3=保底）。
4. **不可穷举** → 兜底不能靠词匹配，必须是「无条件触发」语义，问题文本里不需要出现任何保底表述。

## 3. 方案对比

| 方案 | 思路 | 改动面 | 结论 |
|---|---|---|---|
| **A 保留词自动命中（推荐）** | skill 的分级关键词格内的精确词「保底」= 保留字：父层有真命中时自动视作本层命中，位次取全局表保底行 | 仅 `SkillCandidateSelector`（~20 行）+ 测试；**零表结构、零 API、零前端逻辑** | 语义挂在 skill 自己身上（"我是这个组的保底"），配置即声明，收窄/位次/截断全部复用现有机制 |
| B 优先级表加列点名 skill | 保底行加 `skill_name` 列，selector 在"组内 L2/L3 零命中"时把指定 skill 按保底行位次注入池 | 表结构 + 迁移 + UI + 校验 + 收窄规则加例外，改动面大；"组"的判定（谁的 L1 命中算同组）要在 selector 里重建 | 显式但重，且组归属判定与 skill 自身配置冗余，两处配置可互相矛盾 |
| C 纯配置近似（不改代码） | 保底 skill 只配 L1 不配 L2，组内无 L2 命中时与其他 L1-only 候选平局落 name | 零 | 多个 L1-only skill 时失效，靠 name 前缀作弊，否决 |

## 4. 方案 A 设计：保留词「保底」自动命中

### 4.1 保留字定义

`SkillCandidateSelector` 新增常量 `FALLBACK_MARKER = "保底"`。

- skill 分级关键词（`level2Keywords`/`level3Keywords`）按顿号拆词后**精确等于**「保底」的那个词是保留字；
- 保留字**不参与问题文本匹配**（不进 `SkillTermMatcher` 扫描，防真出现"保底"字样的无关问题误命中）；
- 其余词一切照旧（正则/字面语义不变）。

### 4.2 注入规则（链式，锚点 `selectInternal` L100-109 之后）

```
l1/l2/l3 = 各层真命中词（照旧扫描，保留字跳过）
if (l1 非空 && skill.level2Keywords 拆词含「保底」)  → l2Terms 注入「保底」
if (l1 非空 && l2Terms 非空 && skill.level3Keywords 拆词含「保底」) → l3Terms 注入「保底」
```

- L2 保底的锚 = **该 skill 自己的 L1 真命中**（组身份由 skill 自身 L1 定义，无需重建组归属）；
- L3 保底的锚 = **L1 有命中词且 L2 有命中词**（L2 命中含 L2 保底注入，即链式下钻；
  仅 L2 命中而 L1 未命中时不触发——skill 靠深层词入池不代表它承载该组的宽泛问题）；
- L1 无保底（L1 没有父层可锚，连 L1 都未命中的全局兜底与旁路词/阶段 2 叶层回退职责重叠，本期不做，见 §7 拍板点 3）。

### 4.3 注入后全部复用现有机制（selector 其余零改）

| 机制 | 行为 |
|---|---|
| 收窄 `narrowByLevels`（L218-236） | 注入的「保底」算 L2/L3 真命中：L1-only 问题下，配置了具体 L2 却没命中的 skill 在 L2 层被淘汰，保底 skill 存活 → **自然胜出**；具体词命中时保底 skill 与具体 skill 同层共存，排序分胜负 |
| 深度（L125） | 保底注入 L2 → depth=2，压过 depth=1 的 L1-only 候选 |
| 位次 `bestGlobalRank`（L199-209） | 查 `2|保底` → 全局表保底行的组内 row_order（v3 `expand()` 已把「保底」当普通 L2 词展开，**buildPriorityIndex 零改**）；表里没配保底行 → MAX_VALUE，仍靠 depth 压过 L1-only |
| 排序链（L130-133） | 显式点名 > depth > 保底行位次 > name，不改 |
| 两档截断（L165-177） | 保底 skill 的命中链视为**完整**（partiallyMatched L272-278 对注入层不判缺）→ 榜首截 maxVisibleSkills。⚠️ 拍板点 1 |

### 4.4 管理页配置方式（纯使用约定，零代码）

- 保底 skill：一级关键词照配组词（如 `问题、缺陷、A号、D号、DCM、适应性`），**二级关键词格填 `保底`**，三级留空；配 script_id 即成为该组直接执行的兜底出口；
- 优先级表：组内加「保底」行（L2=保底，组内最后一位）——已配好（12+2 行配置的 row_order=6），它唯一的作用是给保底 skill 一个**组内位次**；
- 一组建议只配一个保底 skill；配多个 → 位次相同落 name（拍板点 2）。

### 4.5 典型场景走查（基于现网 12+2 行配置）

| 问题 | 池 | 收窄后 | 排序结果 |
|---|---|---|---|
| 问题的整体情况 | 问题组全部 L1 命中 | 具体词 skill（配了 L2 未命中）在 L2 层淘汰；保底 skill（marker 注入）存活 | **保底 skill top-1**（depth 2 > 1）；配了 script_id → 直接执行 |
| 未闭环遗留问题 | 遗留/未闭环 skill + 保底 skill | 全存活（L2 层均有命中） | 遗留（rank 0）> 未闭环（rank 1）> 保底（rank 6）✓ 具体优先 |
| 卡口达标率 | Qi 组：达标率 skill + 保底 skill | 全存活 | 达标率（rank 0）> 保底（rank 6）✓ |
| 帮我写个周报 | 零 L1 命中 | 层级池空 → 阶段 2 叶层回退，保底 marker 不触发（无 L1 锚）✓ 不泄漏 |

### 4.6 直接执行侧配套（零改）

- `DimensionExtraction.metrics`（指标佐证 = top-1 命中词）不含「保底」——保留字在 selector 源头
  （`collectMatched`）就不参与匹配、不进命中词表，`matchedKeywords` 天然干净，executor 无需过滤；
- `plan()` 判定链零改（top-1 语义自动获得确定性兜底出口）。

## 5. 改动清单（已实施 2026/10/16）

| 文件 | 改动 |
|---|---|
| `v2/skills/SkillCandidateSelector.java` | `FALLBACK_MARKER` 常量 + `injectFallbackHits` 注入规则（§4.2）+ `collectMatched` 跳过保留字（含 metrics 源头过滤）+ javadoc |
| `SkillCandidateSelectorFallbackTest.java`（新增） | §6 用例 9 条 |
| `v2/skills/DirectSkillExecutor.java` / 表结构 / API / 前端逻辑 | **零改动**（管理页仅使用约定，见 §4.4） |

### 5.1 本机 E2E 结果（2026/10/16，真实 chat + selector INFO 日志）

临时将 3 个 context_demo skill 改造为问题组形态（L1 全=[问题]，L2 分别=遗留/未闭环/保底，
对应全局表行 rank 0/1/6），验证后已恢复原配置：

| 问题 | 日志证据 | 结论 |
|---|---|---|
| 问题的整体情况 | `pool=3, narrowedOut=2, selected=[保底skill]` | 具体词 skill 在 L2 收窄层淘汰，保底 skill 确定性胜出 ✓ |
| 未闭环遗留问题 | `pool=3, narrowedOut=0, selected=[遗留skill, 未闭环skill, 保底skill]` | rank 0 > 1 > 6，具体词优先、保底不抢跑 ✓ |

观察项：保底 skill 配了叶层 keywords（告警）且未命中 → `partiallyMatched` 仍判真 →
`fallback=true, limit=10`（截断放宽一档）。仅影响截断宽度、不影响排序与胜负；生产配置
保底 skill 不应配叶层词（保底是宽泛接住语义，格内只放组词+保底）。

## 6. 测试要点（`SkillCandidateSelectorFallbackTest`，9 条已落地）

1. L1-only 问题 → 保底 skill 胜出（depth 压制 + 收窄淘汰具体词 skill），且 matchedKeywords 不含「保底」；
2. 具体 L2 命中 → 保底退后（rank 6 > 0..5）；具体 L3 命中同理；
3. L2 保底注入需要 L1 真命中：L1 未命中 → 不注入、不泄漏（L2 收窄层淘汰）；
4. L3 保底锚 = L1+L2 双命中：L2 命中但 L1 未命中（deep 不配 L1 走到 L3 收窄层）→ 不注入、淘汰；
5. L3 保底位次：真 L3 命中（rank 0）> L3 保底（rank 6）；
6. 全局表无保底行（supplier=null）→ 保底 skill 仍靠 depth 胜出（功能不依赖表配置）；
7. 优先级表空（功能关闭）→ 同上；
8. 显式点名 skill 名 → explicit 池恒置顶，marker 无干扰；
9. 问题文本含「保底」字样 → 不触发保留字普通匹配（L1 未命中时零泄漏）。

## 7. 拍板点

| # | 决策点 | 建议 |
|---|---|---|
| 1 | 保底命中是否视为"命中链完整"（榜首截 maxVisibleSkills=5，而不是放宽到 fallbackVisibleSkills=10） | **是**——管理员配保底即声明"该 skill 覆盖组内所有宽泛问题"，无需放宽 |
| 2 | 同组多个保底 skill | 允许，位次相同落 name；文档约定一组一个 |
| 3 | L1 级保底（连 L1 都未命中时的全局兜底 skill） | **本期不做**——与旁路词（整问题不走 skill）、阶段 2 叶层回退职责重叠；将来若要做，用独立开关+专用 skill，不进分组语义 |
| 4 | 「保底」成为保留字 | 接受——业务词撞车的概率趋近于零；若真出现，换保留字面值即可（常量一处） |

## 8. 风险与取舍

| # | 风险 | 处置 |
|---|---|---|
| 1 | 普通 skill 误把「保底」配进 L2/L3 → 无声变成组保底 | 启动审计（`SkillRoutingStartupAudit` 同款）warn 提示每个组的保底 skill 清单，配置显性化 |
| 2 | 保底 skill 抢跑：问题其实偏向某具体词但该词没配进优先级表/技能 | 保底只在该 skill 自身 L1 命中时触发，组内；具体词命中时 rank 必然更优（row_order 更小）；误抢发可调保底行位次或摘 script_id |
| 3 | 与 LLM 检索链路的一致性 | 同一个 selector 实例（VisibilityFilter 与 DirectSkillExecutor 共享打分语义），模型可见候选与直接执行同序，天然一致 |
| 4 | 保留字语义对存量配置的影响 | 存量无任何 skill 配「保底」词 → 行为零变化；selector 全量单测回归保障 |
