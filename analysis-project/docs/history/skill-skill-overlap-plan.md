# Skill-Skill 重叠检测 +  skill配置启动阻断 方案

日期：2026-10-10
状态：已实施（2026-10-10，全量 319 单测 BUILD SUCCESS，前端 vite build 通过）；E2E 六步验收待内网环境执行
关联：`tool-tool-overlap-and-startup-guard-plan.md`（Tool↔Tool 检测，四层一致性规则已上线）、`skill-tool-overlap-and-description-similarity-plan.md`（Skill↔Tool 检测，已上线）

> 定版记录（2026-10-10 用户确认）：① **做启动阻断/启用互斥**（见 §3.3，行为对齐 Tool↔Tool 的"自愈优先、阻断兜底"终态）；② topicTags 确认不纳入差异层；③ HIGH 的 suggestion 用"合并"口径，启用互斥的拦截语用"至多启用一个"口径。

## 1. 背景与目标

现状：重叠检测页面（`/script-registry/overlap`，组件 `RoutingOverlapPage.vue`）已有两个视图：

- **Skill↔Tool**（`SkillToolOverlapService`）：S1 topic 交集 / S2 描述 cosine / S3 名称-keywords-toolId 匹配；
- **Tool↔Tool**（`ToolToolOverlapService`）：v2 四层一致性规则（topic/metric/dimension 集合相等 + 描述 cosine ≥ 0.88，全同才 HIGH）。

目标：新增 **Skill↔Skill** 重叠检测，展示在同一页面第三个视图。判定逻辑仿照 Tool↔Tool v2 的"分层一致性"哲学，但分层依据换为 Skill 路由实际使用的三要素：**领域标签（domainTags）、关键词（keywords）、描述**。此外：

1. 存在 HIGH 级 Skill↔Skill 重叠（双方 active）时，**启动期自动停用低优先级一侧（自愈）**，自愈失败/超阈值时阻断启动兜底，日志明确提示。
2. **启用互斥**：管理页启用与已 active Skill 构成 HIGH 重叠的 Skill 时被拦截，提示"同一候选集内至多启用一个"。

## 2. 现状盘点（关键代码位置）

| 组件 | 文件 | 说明 |
|---|---|---|
| Skill 运行时元数据 | `v2/skills/SkillRoutingMetadata.java` | skillName / shortSummary / keywords / **domainTags** / topicTags / creator / active |
| Skill 选择器（筛选链路核心） | `v2/skills/SkillCandidateSelector.java` | keyword 命中（或点名 skillName）才入候选；domainTags 互斥过滤 + 排序加成；topicTags 不参与匹配 |
| Skill↔Tool 检测（范围/存活判定样板） | `v2/governance/SkillToolOverlapService.java:136-191` | 联合存活判定（skill_index 黑名单 + skill_manage 权威）、declaration 三级回退、owner 三级回退 |
| Tool↔Tool 检测（分层判定样板） | `v2/governance/ToolToolOverlapService.java` | 四层一致性 + differingLayers + TTL 缓存 + invalidate |
| HTTP 视图 | `v2/governance/RoutingOverlapController.java` | `GET /api/routing-overlap`、`/tool-tool`、`/summary`、`/tool-tool/summary` |
| 保存失效联动 | `v2/skills/SkillRoutingMetadataAdminService.java:85` | 保存后调 `overlapService.invalidate()`（在此追加 skill-skill invalidate） |
| embedding 缓存 | `v2/governance/GovernanceEmbeddingCache.java` | `EntityType.SKILL`，`semanticAvailable()`/`warm()` |
| 文本相似度工具 | `v2/governance/TextSimilarityUtil.java` | cosine / normalizeName / levenshteinSimilarity / bigramJaccard，直接复用 |
| Skill 广场保存期相似检测 | `v2/governance/SkillDescriptionSimilarityService.java` | 名称 Levenshtein + bigram Jaccard + cosine（草稿 vs 全库），与本次检测职责不同，不合并 |
| 前端页面 | `frontend/src/pages/RoutingOverlapPage.vue` | 已有 Skill↔Tool / Tool↔Tool 两个 radio 视图 |
| 前端 API | `frontend/src/api/routingOverlap.ts`、`types/routingOverlap.ts` | `BASE=/api/routing-overlap` |

## 3. 方案设计

### 3.1 判定哲学：从 Agent 选 Skill 链路定义"重复"

Agent 实际选 Skill 的链路（`SkillCandidateSelector.select()`）：

```
用户问题
  → keywords 命中 (或问题显式点名 skillName) 才进入候选集; 全未命中返回空 (LLM 直接走工具)
  → domainTags 互斥域过滤 (问题命中领域时排除其他领域的 skill) + 排序加成
  → 叶子选型: Agent 对着候选集里的 name + description 二选一 (load_skill_through_path)
```

因此 Skill↔Skill "重复"的判定标准：**存在一类现实问题能让两个 Skill 同时进入候选集，且 Agent 无法从领域排序、关键词命中面、描述上对二者做出区分** —— 此时 Agent 必然瞎选。

与 Tool↔Tool 的两点关键差异：

- **入口是 keywords 而非 topic_tags**。工具靠 topic_tags 圈候选集；Skill 靠 keyword 命中用户问题（topicTags 仅内部管理归类，**不参与匹配**，故不作为差异层，最多 LOW 巡检展示）。
- **共现前提是"关键词命中面可能重叠"**。keyword 匹配语义是"问题文本包含该词"（子串包含），所以两个 Skill 可能共现的判定是：归一化后存在 kA∈A、kB∈B 使 **kA 与 kB 相等或存在包含关系**（问题含长词必含短词，二者同时命中）。双方 keywords 均为空 → 永远不会同时入候选 → 不构成重复（连 LOW 也不出）。

### 3.2 新增 `SkillSkillOverlapService`（v2/governance/）

输入：`skillRoutingMetadataRepository.findActive()`，联合存活判定与 declaration/owner 三级回退**照抄 `SkillToolOverlapService.compute()`**（skill_index 黑名单排除、skill_manage 权威、shortSummary→manageRow.description→entry.description）。Skill 数量为几十级，O(n²) 两两配对可接受；结果 TTL 缓存 + `invalidate()`，模式与既有两个 Service 相同。

#### 判定信号（三层一致性规则，仿 Tool↔Tool v2）

- **T0 共现前提**：归一化后存在相等或包含关系的 keyword 对（§3.1）；未命中 → 跳过。
- **L1 领域标签无差异**：两侧 `domainTags` **集合相等**（归一化后；相交但不同也算差异，同 Tool↔Tool L3 口径）。注意：双方均空视为相等（都是通用 skill，域过滤对二者行为一致）。
- **L2 关键词无差异**：两侧 `keywords`（并 skillName，即 aliases 集合）**集合相等**。keywords 决定路由入口，集合不同 = 服务的问题面不同 → 差异。
- **L3 描述无差异**：两侧 declaration 的 embedding cosine ≥ `cosineHighThreshold`（0.88）；cosine 0.80~0.88 视为"相近但有差异"。向量经 `GovernanceEmbeddingCache.embeddingFor(EntityType.SKILL, skillName, declaration)`。一方描述为空 → 该层无证据（不单独构成差异，见 degraded 口径）。
- **T3 名称相似**：`normalizeName(skillNameA)` vs `normalizeName(skillNameB)` levenshtein ≥ `nameSimilarityThreshold`（仅 LOW 命名卫生用）。

#### 分级

| 级别 | 条件 | 含义 |
|---|---|---|
| **HIGH** | T0 且 **L1∧L2∧L3 全部无差异**；degraded（embedding 不可用）或一方描述为空时：L1∧L2 相等 **且** T3 名称高度相近（levenshtein ≥ 0.9，名称是 Agent 直接看到的选型依据，作硬证据替代 cosine） | 三层全部不可区分，同候选集内 Agent 必然瞎选 |
| **MEDIUM** | T0 且（L3 cosine ≥ 0.80 或 T3 名称相近），但存在至少一个差异层 | 同候选集内相近，但 Agent 仍可按差异层选型，人工确认；**suggestion 点名差异层** |
| **LOW** | 仅 T3 命中（T0 未满足，命名卫生）；或 T0 满足但 L3 cosine < 0.80 且无其他信号（巡检） | 供巡检参考 |

要点：

- 差异层的方向是"**豁免 HIGH**"而非"制造记录"：三层全同才 HIGH，任一层不同封顶 MEDIUM 并在 suggestion 点名（与 Tool↔Tool v2 完全同构）。
- **topicTags 不参与**：运行时匹配语义（`SkillCandidateSelector` javadoc）明确 topicTags 仅内部管理归类，若纳入差异层会把"管理归类不同但路由行为完全相同"的一对误豁免。
- L2 比较用 aliases 集合（keywords + skillName），与 Skill↔Tool 的 S3 名称可比对集合保持一致口径。
- degraded 判定只看 `semanticAvailable()`（沿用 Tool↔Tool 实施期教训：启动/后台预热时序不可依赖 `warm()`），cosine 逐对按向量是否可得判定。
- **双方均未 active 的对过滤不出**（纯噪音，与 Tool↔Tool 既有规则一致）；单侧 active 照报——启用互斥需要在报告里看到"本侧未启用、对侧已启用"的对才能正确拦截。

#### 视图 record `SkillSkillOverlapView`

```java
record SkillSkillOverlapView(
        String skillNameA, String ownerA,
        String skillNameB, String ownerB,
        String level,
        List<String> domainTagsA,       // 展示 L1 两侧取值
        List<String> domainTagsB,
        List<String> keywordsA,         // 展示 L2 两侧取值
        List<String> keywordsB,
        List<String> keywordOverlap,    // T0 命中的共现词对
        double cosine, boolean cosineKnown,
        List<String> differingLayers,   // 差异层名列表, 如 ["领域标签","描述"], HIGH 时为空
        String suggestion) {}
```

`suggestion` 固定句式：

- HIGH：`Skill xxx 与 Skill yyy 领域标签、关键词、描述完全重叠，Agent 在同一候选集内无法区分，请合并为一个 Skill 或拆分关键词/改写描述`
- MEDIUM：`Skill xxx 与 Skill yyy 描述无法区分（cosine=0.93）但关键词不同（A: 缺陷,统计; B: 缺陷,趋势），命中问题面不同，请人工确认是否重复`

### 3.3 启动阻断与启用互斥（对齐 Tool↔Tool"自愈优先、阻断兜底"终态）

#### 3.3.1 启动自愈/阻断：新增 `SkillRoutingStartupAudit`（v2/skills/）

仿照 `ToolRoutingStartupAudit`（ApplicationRunner），但**只服务重叠检测**（Skill 侧无其他启动审计类别）：

- `block-skill-overlap=false`（默认）→ `run()` 直接 return，**不做 O(n²) 配对也不预热向量**（避免每次启动白付 ~百秒 embedding 成本；这是与 Tool 审计"始终跑"的有意差异）。
- `block-skill-overlap=true`：
  1. 先调 `SkillSkillOverlapService.ensureSkillVectorsWarmed()` **同步预热** skill 描述向量（启动审计先于后台预热完成，不预热会因向量缺失漏判 cosine 证据；skill 数几十级，启动延迟可接受——沿用 Tool 侧实施期教训 §5）。
  2. 取报告，过滤 **HIGH 且双方 active** 的对（单侧未 active 不在路由候选，无选型风险，走页面预警）。
  3. 规模 ≤ `block-skill-overlap-max-skills`（默认 4）时**自愈**：逐对自动停用低优先级一侧——SkillRoutingMetadata 无 priority 字段，确定性规则定为**保留 skillName 字典序较小者**（重启幂等）——upsert `active=false` + WARN 日志（含保留侧与换启方法）；自愈处置过的对不再产生阻断 issue；upsert 失败的对收集起来走阻断兜底。处置后 `invalidate()` 让缓存反映现状。
  4. 超过 max-skills 阈值 → 视为批量数据问题，不自动变更元数据，降级 WARN（静默改几百条太危险）。
  5. 存在自愈失败的对 → 抛 `IllegalStateException`，消息含全部未处置对明细（`SkillRoutingStartupAudit: 检测到 N 组 Skill 功能重复…与 Skill xxx 有重复，请处理`），进程退出码 1。
- **阻断发生在 ApplicationRunner 阶段（端口已短暂监听后进程退出）**，运维探活以进程退出码/日志为准，不能只看端口（Tool 侧同款 gotcha）。
- degraded 口径：预热后仍取不到向量的对，HIGH 依赖名称硬证据（§3.2 分级），与页面判定一致。

配置（`V2ToolConfig` 中 skill routing 相关 bean 所在处装配，application.properties 新增）：

```properties
harness.a2a.skill-routing.block-skill-overlap=false
harness.a2a.skill-routing.block-skill-overlap-max-skills=4
```

#### 3.3.2 启用互斥：`SkillRoutingMetadataAdminService` 保存期拦截

完全镜像 `ToolRoutingMetadataAdminService.assertNotHighOverlap`（:61-84）：

- 构造器追加 `SkillSkillOverlapService`（可 null，保持既有测试兼容）。
- `save()` 在 upsert 前调 `assertNotHighSkillOverlap(metadata)`：仅当 `input.active()==true` 时检查报告中的 HIGH 对——
  - 本 skill 为 A 且对侧 B active → 抛 `IllegalStateException("SkillOverlapBlocked: 与 Skill " + B + " 有重复，同一候选集内至多启用一个；请先停用对方，或在重叠检测页区分关键词、领域标签或描述后再启用")`；本 skill 为 B 且对侧 A active 同理。
  - 对侧未 active 不拦（先启用的一方保留，即"仅可启用其中一个"；换保留侧 = 先停当前启用方再启本方）。
  - 停用（active=false）与元数据修改不拦——那是整改动作。
- `setActive()` 走 `save()`，自动被覆盖。
- **已知边界**（与 Tool 侧一致，有意接受）：报告缓存基于保存前快照，"编辑已启用 Skill 使其与另一已启用 Skill 变 HIGH"的场景本次保存拦不住，缓存过期/重启后页面可见，由启动自愈兜底；`SkillManageBridge.ensureRoutingMetadata`（广场发布首次创建 active 草稿）不拦——发布路径拦截不成比例，且草稿 keywords 为名称派生、T0 共现概率低，同样由启动自愈兜底。

### 3.4 HTTP 端点扩展

`RoutingOverlapController` 新增（与现有端点并列）：

- `GET /api/routing-overlap/skill-skill` — 参数 `level` / `skillName` / `limit` / `offset`，返回 `{degraded, total, items[]}`（skillName 过滤命中 A 或 B 任一侧）
- `GET /api/routing-overlap/skill-skill/summary` — counts + highBySkill

失效联动：`SkillRoutingMetadataAdminService.save()`（:85 处）在调 `overlapService.invalidate()` 的同一位置追加 `skillSkillOverlapService.invalidate()`。Skill 删除/下线路径若未经过该 save，则依赖 TTL 缓存自然过期（可接受，派生数据不落库）。

### 3.5 前端（RoutingOverlapPage.vue）

页面顶部 `el-radio-group` 加第三个视图 `Skill↔Skill`：

- 列 = 级别 / Skill A / 负责人 A / Skill B / 负责人 B / 共现关键词 / cosine / 差异层 / 信号 / 建议
- summary 统计条复用（HIGH 计数取当前视图）
- `api/routingOverlap.ts` 加 `listSkillSkillOverlap()` / `skillSkillOverlapSummary()`；`types/routingOverlap.ts` 加 `SkillSkillOverlapItem`

### 3.6 测试

`SkillSkillOverlapServiceTest`（仿 `ToolToolOverlapServiceTest`）：

- T0 未命中（keyword 无相等/包含关系）→ 即使描述完全相同也不出记录（前提校验）；
- 双方 keywords 均空 → 不出记录；
- 三层全同（domain/keywords 集合相等 + cosine 0.93）→ HIGH，`differingLayers` 为空；
- keywords 相等 + 描述全同 + domain 相交但不同（`质检` vs `质检,交付`）→ MEDIUM，`differingLayers=["领域标签"]`；
- domain 相等 + 描述全同 + keywords 不等（子串关系仍满足 T0）→ MEDIUM，`differingLayers=["关键词"]`；
- cosine 0.85（0.80~0.88 区间）+ 三层标签全同 → MEDIUM（描述"相近但有差异"）；
- 一方描述为空 + L1∧L2 相等 + 名称相近 → degraded 同口径 HIGH；
- 仅名称相似（T0 未满足）→ LOW；
- 黑名单/双删除 skill 不出记录（联合存活判定回归）。

`SkillRoutingStartupAuditTest`（仿 `ToolRoutingStartupAuditTest`）：

- `block-skill-overlap=false` → 不预热向量、不出 HIGH issue、不抛异常；
- 双方 active 的 HIGH 对 + block 开启 + 规模 ≤ max-skills → 自愈停用字典序较大者，不抛异常，日志含保留侧；
- 规模 > max-skills → 只 WARN 不变更元数据；
- 自愈 upsert 失败 → 抛 `IllegalStateException` 且消息含"与 Skill xxx 有重复"；
- 单侧未 active 的 HIGH 对 → 不自愈不阻断；
- `assertNotHighSkillOverlap`：启用与已 active 的 HIGH 对 → 抛 `SkillOverlapBlocked`；对侧未 active → 放行；active=false 保存 → 放行。

### 3.7 E2E 验收

1. 管理页把 Skill B 的 keywords/领域标签改成与 Skill A 完全一致、描述改写为同义 → 页面 Skill↔Skill 视图出现 HIGH。
2. 恢复 B 的关键词为不同集合 → 该对降为 MEDIUM，差异层=关键词。
3. **启用互斥**：A/B 均 HIGH 且 A active 时，管理页启用 B → 被 `SkillOverlapBlocked` 拦截；先停 A 再启 B → 成功（换保留侧）。
4. **启动自愈/阻断**：构造双方 active 的 HIGH 对 → `block-skill-overlap=true` 重启 → 自愈成功（低侧被停用，日志含保留侧），不阻断；把 `block-skill-overlap-max-skills` 调成 1 且构造两对 → 降级 WARN 不变更；模拟 upsert 失败路径（或临时关闭自愈）→ 抛异常退出码 1，日志含"与 Skill xxx 有重复，请处理"。
5. 处理重复（合并/拆分关键词/改描述）→ 该对消失。
6. 停掉 embedding 服务 → 页面显示 degraded 标记，仅名称硬证据对出 HIGH。

## 4. 实施顺序

1. `SkillSkillOverlapService`（含 `ensureSkillVectorsWarmed()`）+ `SkillSkillOverlapView` + 单测（纯新增）
2. `SkillRoutingStartupAudit` + `assertNotHighSkillOverlap`（AdminService 构造器加参）+ 开关装配 + 单测
3. Controller 端点 + `SkillRoutingMetadataAdminService` invalidate 联动
4. 前端第三视图 + API/类型
5. E2E 六步验收

预计改动：新增 4 个 Java 文件（Service/View/StartupAudit/单测×2）+ 修改 3 个（Controller/AdminService/V2ToolConfig）+ application.properties 2 行 + 前端 3 个文件，无表结构变更（重叠是派生数据不落库；自愈只改 active 字段）。

## 5. 定版结论（2026-10-10 用户确认）

1. **启动阻断/启用互斥：做**。行为对齐 Tool↔Tool 终态——启动期"自愈优先、阻断兜底"（§3.3.1），保存期"至多启用一个"（§3.3.2）。
2. **topicTags 不纳入差异层**（运行时不参与匹配，见 §3.2 要点）。
3. **HIGH 的 suggestion 用"合并为一个 Skill"口径**；启用互斥/启动兜底的拦截语用"同一候选集内至多启用一个"口径——两者不矛盾：suggestion 是治理方向（合并），互斥是执行期底线（防瞎选）。

## 6. 实施记录（2026-10-10，已完成）

- 新增：`SkillSkillOverlapService`（三层一致性 + T0 共现前提 + TTL 缓存 + `ensureSkillVectorsWarmed()`）、`SkillSkillOverlapView`（15 字段，**比方案 §3.2 的 record 多了 `activeA`/`activeB`**——启动自愈过滤与启用互斥按"本侧未 active、对侧已 active"取对需要这两个字段，页面也如实展示启用状态）、`SkillRoutingStartupAudit`（ApplicationRunner，自愈优先/阻断兜底）、`SkillSkillOverlapServiceTest`（16 用例）、`SkillRoutingStartupAuditTest`（7 用例）。
- 修改：`RoutingOverlapController`（/skill-skill、/skill-skill/summary，构造器 2→3 参）、`SkillRoutingMetadataAdminService`（构造器 3→4 参 + `assertNotHighSkillOverlap` + 双 invalidate；**未保留 3 参旧构造器**——Spring 多构造器无法选择，测试调用方同步改 4 参）、`V2ToolConfig`（skillSkillOverlapService + skillRoutingStartupAudit 两个 bean）、`application.properties`（两个开关）。
- 前端：`RoutingOverlapPage.vue`（第三个 radio 视图 + `skillSignals()` 差异层/共现词/语义相似展示 + active 标签）、`routingOverlap.ts`（listSkillSkillOverlap/skillSkillOverlapSummary）、`types/routingOverlap.ts`（SkillSkillOverlapItem 等 3 个类型）。vite build 通过。
- 测试：全量 **319 用例 BUILD SUCCESS**（新增 16+7+3）。
- **实施期对方案的两处修正**：
  1. **L2 关键词比较用 keywords 集合，不含 skillName**（§3.2 原文写"keywords 并 skillName 的 aliases 集合"）——skillName 是主键必然不同，aliases 集合相等永假，会把每一对都打上"关键词差异层"；skillName/entry.name/manageRow.name 只参与 T0 共现词与入口词匹配。
  2. **LOW 巡检分支限定共现词 ≥ 2**（§3.2 分级表原文"T0 满足但 cosine < 0.80"无数量限定）——T0 是子串包含语义较松（如"缺陷"⊃"缺陷密度"），不限定会为每个共现单词的近邻对出 LOW 噪音；对齐 Tool↔Tool 的 `topicOverlap.size() >= 2` 口径。
- **语义注意（实施期发现）**：skillName 也是入口词（问题显式点名 skillName 入候选），所以名称存在**包含关系**（如 `query_data` ⊂ `query_data1`）的对天然满足 T0——"仅名称相近"判 LOW 要求相近但无包含关系（如 query_list/query_lisp）；名称包含且描述可区分的对会正确判 MEDIUM（差异层=关键词/领域标签），这是预期行为。
- 开关现状：`block-skill-overlap` 在 dev `application.properties` 中置 **true**（对齐 tool 侧 `block-tool-overlap=true` 的发布姿态；bean 注解默认仍为 false）。首次内网启用时启动会同步预热 skill 向量并可能自愈存量双方 active 的 HIGH 对——上线前建议先在重叠页人工核对 HIGH 列表。
- E2E 六步（§3.7）待内网环境执行。
