# domain_tags 命中优先于直接执行：路由漏斗二次重排序方案

> 日期：2026-10-28
> 状态：已实施（2026-10-28 拍板①-⑥全部通过并落地，788 单测全过，E2E 待内网）
> 修订（2026-10-29，用户实证拍板）：问「部门例会 三部遗留问题」时无领域标签的层级轨 skill（q2_1 类）混进了目录 —— 收紧为：**domain 命中时目录只保留命中领域的 skill，未配领域标签（通用）的 skill 一并出局，层级关键词命中也不例外**；filter 预收窄与 selector 双轨（层级轨入池条件 + 存量轨互斥域过滤）同步收紧，显式点名仍豁免。789 单测全过。
> 关联：`docs/202610/直接执行优先于旁路词重排序方案.md`（上一轮重排序，本方案再次修订漏斗）、`docs/202610/skill-retrieval-logic.md` §8（旁路词）、§2.2（domain 互斥域过滤）

## 1. 背景与动机

上一轮重排序（2026/10/27）把漏斗定为「直接执行 > 旁路词 > LLM 检索 > 工具」。但还有一类 skill 天然需要 LLM 编排、不适合直接执行：**配了 domain_tags 的领域 skill**（例会材料、周报等，LLM 要聚合多个脚本/组织语言）。

现网配置实例（2026/10/28 导出，按正确列对齐）：

| skill | keywords | domain_tags | 层级关键词 | script_id |
|---|---|---|--|---|
| context_demo_change_risk | 告警 | 杭研周例会、部门例会 | — | — |
| context_demo_cost_analysis | 遗留 | 杭研周例会、部门例会 | — | — |
| context_demo_automation_rate | — | — | L1=问题/缺陷/A号/D号/DCM/适应性，L2=遗留 | — |
| context_demo_application_quality | — | — | L1=问题/缺陷/A号/D号/DCM/适应性，L2=未闭环 | —（冲突场景假设其参与直接执行） |

注（2026/10/28 INSERT 语句为准）：domain_tags **已经配好**——change_risk / cost_analysis 两例会 skill 均配了「杭研周例会、部门例会」，无需新建配置；quality/automation 两个层级 skill 尚未配 script_id，冲突场景以其接入直接执行为前提。落地前置动作只剩给这两个层级 skill 配 script_id。

冲突问法：

> **部门例会上提到的未闭环问题**

- 「未闭环」+「问题」 AND 命中 context_demo_application_quality 的 L1+L2 → 直接执行抢跑；
- 但用户语义明显在「部门例会」这个领域 → 应该走 LLM 装配例会类 skill；
- 直接执行的确定性短路优先级太高，把领域问法截胡了。

根因：domain_tags 是管理员给 skill 声明的**领域归属**（与层级关键词同级的显式声明），上一轮漏斗里它没有任何优先级语义——只在 LLM 路径内部做互斥域过滤，排不到漏斗层面。

## 2. 目标漏斗（用户拍板口径，2026-10-28）

```
0. 歧义反问短路 (clarifyPrompt)                              ← 不变
1. 补参轮 pending 消费 (缺参/时间歧义/人名重名)                ← 不变, 补参轮优先完成在途直接执行
2. ★ domain_tags 命中 → 走 LLM 运行对应领域 skill              ← 本次新增最高层
     (问题 contains 任一 active skill 的 domain_tags 词条)
3. skill 直接执行 (层级轨 AND / 存量轨 top-1 + script_id)      ← 上一轮的第 2 层下移到这
4. 旁路词命中 → skill 检索置空 → LLM 直接走工具层
5. domain_tags+keywords 配置、仅命中叶层 keywords → LLM+目录    ← 现存量轨, 不变
6. 无候选 → LLM 走工具层                                      ← 不变
```

语义要点：

- **domain 命中 = 领域点名**，与「显式点名 skill 名」同级的归属声明，压过直接执行与旁路；
- domain 命中时直接执行**整体放弃**（不是降级到后面再试）——哪怕层级轨 AND 命中的 skill 自己也配了 domain_tags，同样走 LLM；
- 仅命中叶层 keywords（domain 没命中）的领域 skill 维持现状（漏斗第 5 层），不被提升；
- **domain 命中时目录 = 命中领域 skill 的专属目录**（2026/10/29 收紧）：未配领域标签（通用）的 skill 一并出局，层级轨/存量轨同规则；配了命中领域标签的层级 skill 照常入池且按拍板②排到存量轨领域 skill 之后。

## 3. 方案设计

### 3.1 `SkillBypassKeywordService.bypassed()`：domain 命中豁免旁路

漏斗第 2 层 > 第 4 层：domain 命中的问题**不能被旁路词置空目录**，否则「分析一下例会情况」（分析=旁路词，例会=domain）会掉到工具层，第 1 层名存实亡。

在 `bypassedInternal()` 的旁路词命中之后、返回 true 之前，加 domain 豁免（与现有显式点名豁免同构，同一份 active 快照，零额外查询）：

```java
// domain 让位 (2026/10/28): 问题命中任一 active skill 的 domain_tags → 走该领域 skill,
// 不旁路 (漏斗: domain 命中 > 旁路词)。归一化与 filter 的 matchedDomains 同语义。
for (SkillRoutingMetadata entry : active) {
    if (entry.domainTags() == null) continue;
    for (String tag : entry.domainTags()) {
        if (tag != null && !tag.isBlank()
                && SkillTermMatcher.containsName(normalized, tag)) {
            log.info("SkillBypassKeywordService: bypass word hit but question hits domain tag '{}', not bypassing", tag);
            return false;
        }
    }
}
```

归一化用 `SkillTermMatcher.containsName`（lowercase+trim 字面 contains），与 `SkillVectorIndexVisibilityFilter.matchedDomains` / selector 的 `matchedTags` 同语义，遵守 §6「归一化两边同步改」纪律。

### 3.2 `DirectSkillExecutor.plan()`：domain 命中放弃直接执行

在 selector 之后、旁路检查**之前**插一段（两个都返回 null，但按漏斗顺序排列利于阅读）：

```java
// domain 让位 (2026/10/28, docs/202610/domain_tags命中优先于直接执行重排序方案.md):
// 问题命中任一 active skill 的 domain_tags → LLM 运行领域 skill (漏斗第 2 层),
// 直接执行放弃 (第 3 层); 显式点名 skill 名豁免 (与 selector explicit 置顶一致, 拍板①)。
if (!selection.explicitNameMatched() && hitsAnyDomainTag(active, question)) {
    return null;
}
```

```java
/** 问题 contains 任一 active skill 的 domain_tags 词条 (与 filter.matchedDomains 同语义)。 */
private static boolean hitsAnyDomainTag(List<SkillRoutingMetadata> active, String question) {
    String normalized = SkillTermMatcher.normalize(question);
    if (normalized.isEmpty()) return false;
    for (SkillRoutingMetadata entry : active) {
        if (entry.domainTags() == null) continue;
        for (String tag : entry.domainTags()) {
            if (tag != null && !tag.isBlank()
                    && normalized.contains(SkillTermMatcher.normalize(tag))) {
                return true;
            }
        }
    }
    return false;
}
```

行为约束：

- domain 让位发生在**参数组装/反问之前**——domain 命中 + 层级命中 + 缺必填 → 直接回 LLM，不反问（漏斗第 2 层整体接管，与 10/27 拍板②「旁路词场景缺参反问」不冲突，那条只约束旁路层）；
- 补参轮 pending 消费仍在最前——反问后的补参轮不受 domain 让位影响（完成在途执行）；
- 判定只依赖已取出的 `active` 列表，零额外查询。

### 3.3 目录排序：domain 命中时域内 skill 提到层级轨前（拍板②，建议做）

domain 命中走 LLM 后，目录里**层级轨直接执行 skill（如 application_quality）目前排在存量轨领域 skill 前面**（depth 1-3 > 0）。内网小模型按目录顺序选 skill，可能仍去 load 直接执行 skill，第 2 层名存实亡。

改法（`SkillCandidateSelector.selectInternal`）：`ScoredSkill` 加 `domainHit` 字段，存量轨候选当 `requestedDomains` 非空且自身 domain 命中时置 true；比较器改为

```
显式点名 > domainHit > depth > globalRank > name
```

- `requestedDomains` 为空（无 domain 命中）时所有候选 domainHit=false，**排序零变化**——该改动只在漏斗第 2 层激活时生效，不影响 10/27 漏斗的任何既有排序断言；
- `topFromLevelTrack` 语义不受影响（domainHit 提前的榜首必来自存量轨，topFromLevelTrack=false，plan() 的旁路让位判定依然正确）。

### 3.4 domain 命中即入围：免叶层 keyword 要求（拍板⑥，建议做）

现网配置暴露的一个缺口：例会 skill 的叶层 keywords 是「告警/遗留」这类窄词，**「部门例会」本身不在 keywords 里**。现有 selector 入围规则是「任一叶层 keyword 命中才进存量轨」，所以问「部门例会情况」（domain 命中、叶层未命中）时——domain 豁免旁路了、plan() 也让位了，但例会 skill **根本进不了候选池**，目录为空，LLM 走工具，第 2 层照样落空。

改法（`SkillCandidateSelector.selectInternal` 存量轨段）：问题命中某 skill 的 domain_tags 时，该 skill **免叶层 keyword 要求直接入围**（keywordHits==0 也进池，domain 分照加）：

```java
for (Candidate c : restPool) {
    boolean domainHit = !requestedDomains.isEmpty() && hasAnyDomain(c.entry().domainTags(), requestedDomains);
    if (c.keywordHits() == 0 && !domainHit) {
        continue;   // 原规则不变; domain 命中即入围 (漏斗第 2 层: 领域点名 = 归属声明)
    }
    ...
}
```

- 无 domain 命中（requestedDomains 空）时 `domainHit` 恒 false，**行为零变化**；
- 与 §3.3 排序提升叠加：domain 命中的 skill入围 + 排到层级轨前，第 2 层闭环；
- 替代做法（管理员把「例会」也配进叶层 keywords）不依赖代码，但每加一个领域词要人工同步两处，漏配即复发——代码侧兜底更稳，两者不冲突可并用。

### 3.5 不改动项

| 组件 | 理由 |
|---|---|
| `SkillVectorIndexVisibilityFilter` 主流程 | bypassed() 的 domain 豁免自动生效；domain 预收窄 **2026/10/29 已改**（原「保留该领域+通用」改为「只留命中领域」，与 selector 同语义双保险） |
| 上一轮的层级轨旁路让位（topFromLevelTrack） | 漏斗第 3 层内部逻辑不变，只是整体下移 |
| 补参轮 / 歧义反问 / 显式点名豁免旁路 | 不变 |
| 存量轨（仅叶层命中）行为 | 漏斗第 5 层 = 现状 |
| selector 匹配/入围语义（AND、保底注入、domain 互斥过滤） | 不变；3.3 只动比较器 |

## 4. 行为变化对照

配置：例会类 skill 配 `domain_tags=例会` + `keywords=部门例会/杭研周例会`；application_quality 配 L1=问题/L2=未闭环 + script_id；旁路词含「分析」。

| # | 问法 | 现状（10/27 漏斗） | 调整后 |
|---|---|---|---|
| 1 | 部门例会上提到的未闭环问题 | 层级 AND 命中 → **直接执行**（截胡） | 「例会」domain 命中 → **LLM 运行例会 skill** |
| 2 | 分析一下例会情况 | 旁路（分析）→ 目录空 → 走工具 | domain 命中豁免旁路 → LLM+例会 skill |
| 3 | 未闭环的A号数据（无 domain 词） | 直接执行 | **不变**（无 domain 命中，漏斗第 3 层照常） |
| 4 | 例会材料里没提关键词，仅问例会 | domain 互斥过滤后叶层未命中 → 目录空 | domain 命中 → LLM；目录收窄到例会域，例会 skill 靠 **domain 命中即入围**（§3.4 拍板⑥），不再要求叶层 keyword 必命中 |
| 5 | 用 xxx_skill 查例会数据（显式点名） | 直接执行该 skill | **不变**（拍板①：显式点名豁免 domain 让位） |
| 6 | 部门例会上提到的未闭环问题 + 缺部门 | 层级命中 → 缺参反问 | domain 让位在前 → **回 LLM，不反问** |
| 7 | 分析一下未闭环的A号数据（无 domain 词） | 层级让位旁路 → 直接执行 | **不变**（10/27 漏斗在第 3/4 层的原样保留） |

## 5. 拍板点（2026-10-28 全部拍板通过，已实施）

| # | 决策点 | 建议 |
|---|---|---|
| ① | 显式点名 skill 名 vs domain 命中谁赢 | **显式点名赢**（豁免 domain 让位）——用户点名了具体 skill 就别拦；与 selector explicit 置顶、旁路豁免口径一致 |
| ② | domain 命中时目录是否把域内 skill 提到层级轨候选前（§3.3） | **做**——不做的话小模型仍可能按目录顺序 load 直接执行 skill，第 2 层形同虚设；改动带 `requestedDomains` 空守卫，无 domain 命中时排序零变化 |
| ③ | domain 命中 + 层级命中 + 缺必填 → 回 LLM 不反问 | **确认**（§3.2 行为约束）——domain 命中即整体让位，反问是第 3 层内部环节 |
| ④ | 补参轮 pending 消费是否被 domain 让位拦截 | **不拦**（pending 在最前，完成在途执行）——用户上一轮已经在直接执行流里补参，中途换轨体验割裂 |
| ⑤ | domain 词条撞车审计（domain 词同时是旁路词/层级词的提示） | 本期**不做**，同上一轮拍板④口径 |
| ⑥ | domain 命中但叶层 keywords 未命中时，skill 是否免叶层要求入围候选池（§3.4） | **做**——现网叶层词（告警/遗留）不含领域词（例会），「部门例会情况」domain 命中但叶层零命中，按原规则例会 skill 进不了池、第 2 层落空；带 `requestedDomains` 空守卫，无 domain 命中时行为零变化 |

## 6. 改动清单

- `v2/skills/SkillBypassKeywordService.java`：bypassedInternal 加 domain 命中豁免（§3.1）；
- `v2/skills/DirectSkillExecutor.java`：plan() 加 domain 让位 + `hitsAnyDomainTag` 静态助手（§3.2）；
- `v2/skills/SkillCandidateSelector.java`：`ScoredSkill` 加 `domainHit` + 比较器插 domainHit 位（§3.3，拍板②）；restPool 入围规则加 domain 命中豁免叶层要求（§3.4，拍板⑥）；
- 注释/文档：`SkillVectorIndexVisibilityFilter` 旁路注释补 domain 豁免一句；`skill-retrieval-logic.md` §8.1 再标注；上一轮方案文档头部加「漏斗已被本方案再次修订」交叉引用；
- 测试：§7；
- 配置：无新开关。**domain_tags 空 = 功能天然关闭**（豁免/让位/排序三处都以「存在命中的 domain 词条」为前提），不配 domain_tags 的现网行为与 10/27 漏斗逐分支一致。

## 7. 测试要点

**单测**：

1. `SkillBypassKeywordServiceTest` 新增：问题命中 domain 词 + 旁路词 → 不旁路；domain 词未命中 + 旁路词 → 照旧旁路；inactive skill 的 domain 词不豁免；无 domain_tags 行为不变（存量用例全过）。
2. `DirectSkillExecutorTest` 新增：
   - domain 命中 + 层级轨 AND 命中 + script_id → plan null（对照表 1）；
   - domain 命中 + 仅存量轨命中 → null（原本也 null，回归确认）；
   - domain 命中 + 缺必填（4 参带会话键）→ null 而非 needsAsk（拍板③）；
   - domain 命中 + 显式点名 → 正常 plan（拍板①）；
   - 无 domain 词 → 判定链与 10/27 漏斗逐分支一致（存量用例全过）。
3. `SkillCandidateSelectorTest` 新增（拍板②+⑥）：domain 命中时域内 skill 免叶层 keyword 入围且排在层级轨候选前；叶层未命中 + domain 未命中 → 仍不入池（原规则回归）；无 domain 命中时排序断言全部不变。
4. 补参轮：domain 命中的问题带 pending → consumePending 正常返回（拍板④）。

**E2E（内网）**：

1. 给例会类 skill 配 domain_tags=例会，问「部门例会上提到的未闭环问题」→ 走 LLM，ActivityFeed/trace 显示 load_skill 加载例会 skill（非 script_exec 短路）；
2. 问「分析一下例会情况」→ 目录非空且例会 skill 在前（不被旁路置空）；
3. 回归：10/27 漏斗三案例（未闭环A号直接执行 / A号数据走工具 / 分析未闭环A号直接执行）。

## 8. 风险与取舍

| # | 风险/取舍 | 处置 |
|---|---|---|
| 1 | domain 词配得太宽（如「质量」）会让大量问法绕过直接执行回到 LLM（内网 LLM 慢/不可靠） | 配置责任：domain 词应配窄领域词（例会/周报），宽泛词留给层级/叶层；§6 撞车审计可后续补提示 |
| 2 | domain 命中但该域 skill 叶层 keywords 未命中 → 目录里该 skill 缺席 | §3.4 拍板⑥已代码侧兜底：domain 命中即入围，不依赖叶层命中；管理员把领域词再配进 keywords（如「例会」）可作双保险，两者并用不冲突 |
| 3 | bypassed() 语义再次变化（10/27 才承诺零改动） | 本方案是漏斗层面新增第 1 优先级，豁免列表 = 显式点名 ∪ domain 命中，与 filter/plan 两消费点依然单一判定源，注释同步 |
| 4 | plan() 的 domain 判定与 filter 的 matchedDomains 两处实现 | 语义一致（normalize+contains），改动清单里注明同步纪律；如后续扩散可抽公共助手 |
| 5 | 与 10/27 方案的漏斗冲突 | 本方案整体取代其漏斗顺序（其第 2~4 层下移一层），10/27 的机制改动（topFromLevelTrack、旁路后移）全部保留复用 |
