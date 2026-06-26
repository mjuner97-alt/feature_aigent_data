# Skill 自进化 — PR4 设计文档(SkillEvolutionHook)

> 对应方案:[skill-self-evolution-detail.md](./skill-self-evolution-detail.md) §"能力 3:失败反馈闭环" + §"落地顺序" 的 PR4 行。
> **状态**:设计阶段,未开始实现。需要先就 §三 的 4 项决策达成一致再动代码。
> **目标**:把 PR2(自动沉淀)+ PR3(智能召回)的开环路径闭成"沉淀 → 召回 → 演进 / 拉黑 → 重新召回"的闭环。

---

## 一、整体形状

```
PreCall    SkillRetrievalHook (PR3, priority=-50)
             ├─ L1/L2 命中后,把 SKILL.md body 追加到 system
             └─ 命中列表写入 ctx.attribute("skills.retrieved", List<String>)    ← PR4 新增
                ↓
LLM 调用 + 工具执行
                ↓
PostCall   SkillEvolutionHook (PR4, priority=+60)
             ├─ 读 ctx.attribute("skills.retrieved")
             ├─ 判 success / failure(详见 §四)
             ├─ 同步 UPDATE skill_index SET success_count / failure_count += 1
             └─ 越限触发:
                   • failure_rate > evolveThreshold + uses ≥ minEvolve
                     → 异步 SkillDistiller.evolve(oldBody, failedTrace) → 覆写同文件,version++
                   • failure_rate > blacklistThreshold + uses ≥ minBlacklist
                     → status='blacklist'(文件保留,SkillRetrievalHook 召回时 WHERE status != 'blacklist' 过滤)
```

**核心数据**:`skill_index` 表里 `failure_count INT / success_count INT / status VARCHAR(20)` 三列在 PR1 已建好 — **零 DDL**。

---

## 二、需要先决策的 4 件事

### (a)失败信号源

PR4 原始设计有三个失败信号源,但 `git status` 显示 `DataGroundingHook.java` 已删(`D` 标记)。所以必须先决定信号源:

| 选项 | 失败覆盖率 | 实现成本 | 假阳性风险 |
|---|---|---|---|
| **A. 只用 PythonExecRetryHook + 用户负反馈** | ~40% | 极低,**复用现有 hook 信号** | 低 |
| **B. 恢复轻量 grounding 校验**(只查 LLM 回复里的数值是否出现在 query_quality_data 返回过的集合) | ~75% | 中,需新增 1 个 PostCall hook | 中(四舍五入会误伤) |
| **C. 加 LLM 自纠错关键词探测**("抱歉"/"我之前算错") | ~25% | 低 | 高(LLM 礼貌用语) |

**建议:A**。PR4 先把闭环跑通,grounding 校验留 PR5。Grounding 误判 → 好 skill 被错降级 → 危害大于"少抓一些失败"。

### (b)PR3 attribute plumbing 改 1 行

`SkillRetrievalHook` 命中后,需要把召回的 skill 名字写进 `RuntimeContext.attribute("skills.retrieved", List<String>)`,这样 PostCall 才知道给谁记账。**这一行改放在 PR4 里**。

### (c)演进 prompt 是否带失败 trace

带上 → LLM 知道"上次为什么错"、修复方向更准,但 prompt 涨 2~3 倍。
**建议:带上,只截最近 1 条失败 trace 的 500 字头部** + 老 SKILL.md body 全文。

### (d)并发

per-skill `markEvolving(name)` CAS,沿用 `SkillSynthesisRunner.markSynthesized` 的套路,避免同一个 skill 被两个并发请求同时 evolve。**建议确认这个方向**。

---

## 三、变更文件清单(实现阶段填)

| # | 类型 | 路径 | 改动摘要 | 影响面 |
|---|---|---|---|---|
| 1 | 🆕 新增 | `harness/hooks/SkillEvolutionHook.java` | `priority()=+60`,PostCall 单事件;读 `ctx.attribute("skills.retrieved")` → 判 success/failure → UPDATE counts → 越限异步 evolve 或 blacklist | 新增 hook,默认 `enabled=false` |
| 2 | 🆕 新增 | `harness/skills/SkillEvolutionRunner.java` | per-skill ConcurrentHashMap CAS `markEvolving / markEvolved`;`boundedElastic` 上跑 `SkillDistiller.evolve` + 落盘 + embed | 新增 bean,与 `SkillSynthesisRunner` 并列 |
| 3 | ✏️ 改造 | `harness/skills/SkillDistiller.java` | 新增 `evolve(oldBody, failedTrace, exemplarQuestion)` 方法:prompt 让 LLM 在 oldBody 基础上修复 + 输出新 body;parse 复用既有的 NAME/DESC/BODY 三段提取(name 必须与原 skill 一致,否则 reject) | 新方法,旧 `distill()` 不动 |
| 4 | ✏️ 改造 | `harness/skills/SkillIndexRepository.java` | 新增 `incrementSuccess(name)` / `incrementFailure(name)` / `markBlacklist(name)` / `findStats(name)` 4 个方法;SQL 走 `UPDATE skill_index SET ... WHERE name=?` 原子自增 | 新增方法,不改表结构 |
| 5 | ✏️ 改造 | `harness/skills/SkillVectorIndex.java` 或 `SkillIndexRepository`(取决于召回逻辑当前住哪) | `topK` / `findByFingerprint` SQL 追加 `AND (status IS NULL OR status != 'blacklist')`;现有 `status='active'` 行为不变 | 召回逻辑收紧;不影响落盘 |
| 6 | ✏️ 改造 | `harness/hooks/SkillRetrievalHook.java` | 命中后追加一行 `ctx.setAttribute("skills.retrieved", List.copyOf(namesHit))`;原 inject 逻辑不动 | PR4 输入打通 |
| 7 | ✏️ 改造 | `service/SupervisorService.java` | 注入 `SkillEvolutionRunner`(必选);`build()` 末尾装配 `SkillEvolutionHook`;新增 `harness.skills.evolution.*` 共 5 个 `@Value` | DI 装配,默认关 |
| 8 | ✏️ 改造 | `resources/application.properties` | 追加 `harness.skills.evolution.*` 配置块(注释 + 默认值) | 仅文档,默认行为不变 |
| 9 | 🆕 新增 | `docs/skill-evolution-pr4-changes.md` | 实现完成后写,本设计文档归档为 `pr4-design.md` | 文档 |

## 四、判 success / failure 的具体规则(选项 A 方案)

PostCall 拿到 `skills.retrieved=[name1, name2]` 后,**对每个 skill 单独打分**:

```
failure 信号(任一即可):
  1. 本次 turn 内 python_exec retry count ≥ 2
     ← 从 ctx.attribute("python_exec.retry_count") 或现有 PythonExecRetryHook 暴露的状态读
  2. PostCall 抛 exception 且 cause 不是 CacheHitException(工具全失败)
  3. 用户下一轮 message 含拒绝关键词 ["不对","错了","重算","重新","不是这样","不正确"]
     ← 跨 turn 信号,在 turn[N+1] PreCall 回溯给 turn[N] 的 skill 记账

success 信号(默认值):
  上述 failure 均不命中 → success_count += 1
```

### 跨 turn 用户负反馈的实现

最简方案:每次 PostCall 把 `[turn_n_skills, timestamp]` 写进 `RuntimeContext.attribute("skills.last_turn")`,下一轮 PreCall **第一件事**判用户输入:

```java
String userInput = extractUserQuestion(...);
List<String> lastTurnSkills = (List<String>) ctx.getAttribute("skills.last_turn");
if (lastTurnSkills != null && matchesRejectionPattern(userInput)) {
    for (String name : lastTurnSkills) repo.incrementFailure(name);
} else if (lastTurnSkills != null) {
    for (String name : lastTurnSkills) repo.incrementSuccess(name);
}
ctx.setAttribute("skills.last_turn", null);  // 消费完清空
```

放 PreCall 而不是 PostCall 是因为:本轮的 success/failure 信号在本轮 PostCall 已经知道一部分(retry/exception),但用户接受度只能等下一轮。两个信号源在不同生命周期点收集 — failure 立刻记,success 等下一轮无负反馈才记。

---

## 五、演进 / 黑名单触发

```
failure_rate = failure_count / (success_count + failure_count)
total_uses   = success_count + failure_count

if failure_rate > blacklistThreshold && total_uses ≥ minBlacklist:
    status = 'blacklist'
    # 文件保留,只是不再被召回。运维 review 后可手动 UPDATE status='active' 复活
    # 不删 embedding 行,避免反复拉黑→平反时丢失累计样本

else if failure_rate > evolveThreshold && total_uses ≥ minEvolve:
    if SkillEvolutionRunner.markEvolving(name):  # CAS,失败说明已有别人在演进
        async on boundedElastic:
            SkillDistiller.evolve(oldBody, failedTrace, exemplarQuestion)
              ├─ 成功:落盘覆写 SKILL.md(同文件名,frontmatter version++)
              │       重新 embed 写回 skill_index.embedding
              │       success_count = 0, failure_count = 0(给新版本干净窗口)
              │       markEvolved(name)
              └─ 失败:markEvolved(name) 释放 lock,本次放过,等下次再触发
```

两个阈值 + 两个最小样本数让冷启动期不会立刻误降级。

---

## 六、关键设计点

### 为什么 evolve 是"同名覆写 + version++"而不是"新名字 + 旧名字 deprecate"

- L1 fingerprint 路径依赖 `skill_index.fingerprint`,改名要同步迁移 fingerprint,迁移失败就 L1 silent miss
- 用户调用 `save_skill` 时不可能知道"上次已经叫这个名字了",换名等于把每个 skill 的版本树搞成森林
- SKILL.md 文件名 = skill 身份,version 在 frontmatter 内 — 这是 PR1 定下来的约定,PR4 应继承

### 为什么 blacklist 不删 row / 不删文件

- 删 row 丢累计样本 — 平反时归零,等于"这个 skill 从来没失败过"
- 删文件丢可读历史 — 后人看 git log 找不到曾经存在过的 skill
- `status='blacklist'` 是只读软删,所有写路径不变;唯一改的是召回 SQL 加 `WHERE status != 'blacklist'`

### 为什么 evolve 后 counts 归零

- 老 body 的失败样本不能算新 body 的账 — 否则新 body 一上线就背着 0.5 的失败率,下一次失败立刻又触发 evolve,陷入 evolve loop
- 给新 body `(0, 0)` 起点,要重新攒到 `minEvolve` 次才会再次评估

### 为什么不在 PR4 做"skill 合并"

`SkillDistiller.merge(skillA, skillB)` 是个诱人的方向 — 但合并意味着 fingerprint 也得合并,L1 路径会失效。PR4 不碰,留 PR5 或更后面。

### 失败信号选 A 而不是 B(grounding 校验)的取舍

- B 的 false positive 假设场景:LLM 输出 `平均缺陷密度 0.42`,query_quality_data 返回的明细里没出现 0.42 — 但实际上是 LLM 对明细做了正确的平均。这种"派生数据"无法被 grounding 校验区分
- A 的 false negative 场景:LLM 编了一个数字,python_exec 没出错,用户也没否认(用户也没核对) — 这条失败被漏掉了。但漏掉 ≠ 标错,后续重复出错累计上来仍会触发
- 闭环优先级:**先让闭环跑起来**,样本量上来后,grounding 误差也能从对比中识别(同一 skill 在 grounding 信号下被频繁标错但 retry/负反馈干净 → 反推 grounding 失准)

---

## 七、配置项(规划)

| 键 | 默认 | 说明 |
|---|---|---|
| `harness.skills.evolution.enabled` | `false` | 总开关。关闭时 hook 透传,success/failure 都不记账 |
| `harness.skills.evolution.fail-rate-evolve` | `0.3` | 失败率超过此值且样本数足够 → 触发 evolve |
| `harness.skills.evolution.fail-rate-blacklist` | `0.6` | 失败率超过此值且样本数足够 → 拉黑 |
| `harness.skills.evolution.min-uses-evolve` | `5` | evolve 所需的最小累计 use(success+failure) |
| `harness.skills.evolution.min-uses-blacklist` | `10` | blacklist 所需的最小累计 use |
| `harness.skills.evolution.rejection-keywords` | `不对,错了,重算,重新,不是这样,不正确` | 用户负反馈关键词,逗号分隔 |
| `harness.skills.evolution.max-evolutions-per-skill` | `3` | 同一 skill 最多 evolve 次数;超过仍失败 → 强制 blacklist。防止 evolve loop |

---

## 八、回滚

```bash
git revert <pr4-commit>
# 数据库残留:已写入的 skill_index.failure_count / success_count / status 可留可清
#            UPDATE skill_index SET failure_count=0, success_count=0, status='active';   # 可选
```

PR4 完全可独立回滚:回滚后 `SkillEvolutionHook` / `SkillEvolutionRunner` 全部消失,`SkillRetrievalHook` 多写的 `ctx.attribute("skills.retrieved")` 没人读但不影响透传,行为与 PR3.8 完全一致。

---

## 九、验收(实现完成后填)

- [ ] `harness.skills.evolution.enabled=true` + 一个已经累计 ≥5 次召回的 skill
- [ ] 构造 3 次 python_exec retry≥2 的失败请求 → `skill_index.failure_count` ≥ 3,success_count 不变;触发 evolve → SKILL.md 同名覆写,frontmatter `version: 2`,counts 归零
- [ ] 构造 10 次失败请求(fail-rate > 0.6) → `status='blacklist'`,下一次同 fingerprint 召回 L1 miss(被 `status != 'blacklist'` 过滤),L2 也 miss
- [ ] 用户在 turn[N+1] 输入"不对" → turn[N] 召回的 skill failure_count +1;无关键词时 success_count +1
- [ ] 同一 skill 并发触发 evolve → 只有第一个 CAS 成功的请求实际跑 distiller,其他直接放行
- [ ] 关闭 `evolution.enabled` → 行为与 PR3.8 完全一致(无任何 UPDATE skill_index 写入)

---

## 十、下一步(PR5 预告)

候选方向(按优先级):

1. **Grounding 校验器**:轻量 PostCall hook,把 LLM 回复里的数值与 `query_quality_data` 返回的数值集合做模糊匹配(±1% 容差),不匹配的数值 → grounding 失败信号补进 PR4 的 failure 计数器。
2. **Skill 合并** (`SkillDistiller.merge`):周期性扫 `skill_index` 找 embedding cosine > 0.95 且 fingerprint 相似的 skill 对,LLM 合并 + 新 fingerprint 写入 + 旧 skill blacklist。
3. **WorkspaceContextHook 退场**:当 PR3 retrieval 准确率经 PR4 数据验证稳定后,反射禁用 JAR 内部的全量注入 hook,让 system prompt 只剩 PR3 的 spotlight 块。
