# Skill 自进化 — PR2 变更汇总

> 对应方案:[skill-self-evolution-detail.md](./skill-self-evolution-detail.md) §"能力 1:沉淀" 与 §"落地顺序" 的 PR2 行(synthesis)。
> 仅落地"同指纹问题 ≥ N 次 → 自动蒸馏 SKILL.md"的闭环,默认开关 **关**。`SkillRetrievalHook` / `SkillEvolutionHook` 留待 PR3/PR4。

## 变更文件清单

| # | 类型 | 路径 | 改动摘要 | 影响面 |
|---|---|---|---|---|
| 1 | 🆕 新增 | `harness/skills/SkillCandidate.java` | `skill_candidate` 表只读快照 record;`STATUS_PENDING/SYNTHESIZED/REJECTED/BLACKLIST` 常量 | 仅本包 |
| 2 | 🆕 新增 | `harness/skills/SkillCandidateRepository.java` | `@Repository` + 懒建表 DDL + `incrementHit / findByFingerprint / markSynthesized / markRejected`;`ON DUPLICATE KEY UPDATE` 保证并发安全,`markSynthesized` 是原子 CAS | 新增 bean,无现有代码改写 |
| 3 | 🆕 新增 | `harness/skills/SkillDistiller.java` | `@Component`,把 exemplar question + 可选 EpisodicMemory snippets 喂 LLM,解析出 `name / description / body` 三段;`Model.stream(...)` 同 `OpenAILlmDimensionService` | 新增 bean;直接依赖 `Model` 主 bean |
| 4 | 🆕 新增 | `harness/hooks/SkillSynthesisHook.java` | `implements Hook`:PreCall 复用 `DimensionStateManager.analyzeQuestionRuleBased()` 提指纹,PostCall 异步走 `trySynthesize`;失败/空 reply 不计数;`enabled=false` 时全程透传 | 新增 hook,每请求一个实例 |
| 5 | ✏️ 改造 | `service/SupervisorService.java` | 注入 `SkillCandidateRepository + SkillDistiller + DimensionStateManager`(字段);`build()` 在 `ArtifactHandoffHook / DataGroundingHook` 之后挂 `SkillSynthesisHook(...)`;新增 `${harness.skills.auto-synth.enabled}` / `${harness.skills.auto-synth.threshold}` 两个 @Value | DI 装配 + 每请求多一个 hook 实例 |
| 6 | ✏️ 改造 | `resources/application.properties` | 新增注释配置块 `harness.skills.auto-synth.enabled/threshold`,默认注释掉(= false / 3) | 仅文档,默认行为不变 |
| 7 | 🆕 新增 | `docs/skill-evolution-pr2-changes.md` | 本文 | 文档 |

## 数据库变更(自动 DDL,无需手工执行)

| 库 | 表 | 触发 | 列 |
|---|---|---|---|
| `default_db` | `skill_candidate` | 首次 `incrementHit()` 时 `CREATE TABLE IF NOT EXISTS` | `fingerprint` PK / `user_id` / `hit_count` / `last_query` / `last_trace_id` / `status` / `synth_skill` / `updated_at` |

> `skill_index` 表由 PR1 拥有;本 PR 不改其 schema,只是新蒸馏的 skill 走原有的 `SkillSaveTool.saveSkill(...)` 写入,自动 upsert 一行。

## 行为差异(用户视角)

| 场景 | PR2 之前 | PR2 之后(开关默认关) | PR2 之后(`auto-synth.enabled=true`) |
|---|---|---|---|
| 同一类问题问 3 次 | 无任何沉淀,需要用户喊"保存为 skill" | 同上 | 第 3 次回复正常返回后,后台异步蒸馏出一个新 SKILL.md 到 `workspace/.../skills/<name>/`,**用户全程没说"保存"** |
| Cache 命中(命中 `ResponseCacheHook`) | 直接返回缓存 | 同上(`CacheHitException` 短路,本 hook 的 PostCall 不执行) | 同左 — 命中不计数,避免死循环 |
| 蒸馏 LLM 自身产生新 query | 不存在 | 不存在 | 蒸馏走异步 `Schedulers.boundedElastic()`,不复用同一个 `RuntimeContext`;失败仅打 warn |
| 两个 JVM 同时蒸馏同一指纹 | 不存在 | 不存在 | `UPDATE ... WHERE status='pending'` affected_rows 抢占,只有一个写入 |
| MySQL 不可达 | 不影响 | 不影响 | 仅打 warn,`incrementHit` 返回 empty,本次 PostCall 跳过;阅读路径不阻塞 |
| 蒸馏失败 / LLM 解析不出三段 | 不存在 | 不存在 | `SkillDistiller.parse()` 返回 null → `markRejected("distiller_returned_null")`,同一指纹下次不再触发 |

## 关键设计点

### 指纹与 `ResponseCacheHook` 共用同一 dimension key
两者都跑 `DimensionStateManager.analyzeQuestionRuleBased()` + `buildFromExplicit()`,且都按 `userId / sessionId / _anon` 做 tenant scope。这样保证:
- 同一类问题在 cache 维度被认作同一条,在 candidate 维度也被认作同一条 — 不会出现"cache hit 不重复但 candidate 计数翻倍"或反之
- Cache 命中后短路抛 `CacheHitException`,框架不再走 PostCall,本 hook 的计数器**自然**不会被命中流量推高(无需额外标志位)

### 不在 SKILL.md 里写 frontmatter
`SkillDistiller` prompt 明确要求"不要输出 YAML frontmatter",所有版本/usage/last_used 由 PR1 的 `SkillSaveTool` 落盘时强制生成。PR2 与 PR1 共用同一条 save 路径,YAML 风格保持一致。

### 异步、不阻塞回复
`handlePostCall` 触发后,所有 DB / LLM 操作都跑在 `Schedulers.boundedElastic()` 上,`PostCallEvent` 立即返回。reply 已经在线,蒸馏的 8-30s 延迟对用户不可见。

### EpisodicMemory 可选
`SkillDistiller` 通过 `ObjectProvider<EpisodicMemory>` 拿引用;bean 不在时(或 `search` 抛错时),自动降级到"question-only"模式。这样本 PR 不强依赖 `MySqlEpisodicMemory` 的就绪时序。

## 配置项

| 键 | 默认 | 说明 |
|---|---|---|
| `harness.skills.auto-synth.enabled` | `false` | 总开关。Dev 默认关,demo 验证通过后改 `true` |
| `harness.skills.auto-synth.threshold` | `3` | 同指纹累计多少次触发蒸馏。生产建议 5 |

## 回滚

```bash
git revert <pr2-commit>
# 数据库残留: DROP TABLE skill_candidate;   # 可选,留着也无副作用
# 已蒸馏的 SKILL.md 不会自动清理 — 仍由 PR1 路径管理,可手工 rm
```

PR2 完全可独立回滚:回滚后只是没有自动蒸馏,manual `save_skill` 工具与 `skill_index` 表(PR1)全部保持原状。

## 验收(对照方案 §1.6)

- [ ] 跑 demo:开启 `auto-synth.enabled=true threshold=3`,连问 3 次"杭州开发一部 Q1 缺陷密度"(每次换措辞)→ 第 3 次返回后,`workspace/.../skills/` 出现新 skill,**用户全程没说"保存"**
- [ ] `skill_candidate` 表 `status='synthesized'`、`synth_skill` 不为空
- [ ] 同义重复:同一 fingerprint 被命中 5 次,只产 1 个 skill(`markSynthesized` 后续不再触发)
- [ ] 关闭开关后,行为与 PR1 完全一致

## 下一步(PR3 预告)

新增 `EmbeddingClient` + `SkillVectorIndex` + `SkillRetrievalHook`,把现有 `WorkspaceContextHook` 的全量 skill 注入替换为"L1 指纹精确路由 + L2 向量 top-K"。先与原行为并存,通过 attribute 隔离,验证后再撤换默认行为。

---

## 📌 修订 — PR2.1(2026-06-24):修复 Cache HIT 路径不计数 + 不蒸馏 Bug

### 问题
PR2 初版假设"Cache 命中后 `CacheHitException` 短路,本 hook PostCall 不执行,所以命中**不会**推高计数器"。
但实测发现:计数器原本在 PostCall 才 `incrementHit`,Cache HIT 短路同样跳过它 ⇒ **同维度问题被 cache 反复命中,候选表永远不增长,蒸馏永远触发不了**。

打开 `harness.a2a.response-cache.enabled=true` 后,R1 MISS 把候选行写到 hit=1,R2/R3 全部 cache HIT 短路 ⇒ R3 后 `skill_candidate.hit_count` 仍为 1,达不到 threshold。

### 修复(方案 A:抽出共享 Runner)
新增 `SkillSynthesisRunner` 单例,把 "bump 计数 → 检阈值 → markSynthesized CAS → 异步蒸馏"四步集中。两条路径都委托给它:

- **Cache MISS 路径**(`SkillSynthesisHook`):PreCall 调 `runner.bumpAndMaybeSynthesize(...)`。Hook 自身瘦身为"算指纹 + 调 Runner"两步,不再持有 distiller / saver / vectorIndex / embeddingClient
- **Cache HIT 路径**(`ResponseCacheHook`):HIT 分支在 `Mono.error(CacheHitException)` 之前调 `runner.bumpAndMaybeSynthesize(...)`,使用 `tenantBucket|intent|stateKey`(无 `q=hash`)指纹形态

`SkillCandidateRepository.markSynthesized` 是行级 CAS,两条路径并发命中同一指纹时只会蒸馏一次。

### 变更文件清单(本次新增 / 改造)

| # | 类型 | 路径 | 改动摘要 | 影响面 |
|---|---|---|---|---|
| 8 | 🆕 新增 | `harness/skills/SkillSynthesisRunner.java` | `@Component`;捕获 `skillsDir + enabled + threshold + vectorIndex(可空) + embeddingClient(可空) + distiller + 两个 repo`;`bumpAndMaybeSynthesize(fingerprint, userId, question, traceId)` 一站式;`maybeDispatch` 守 `STATUS_PENDING` + `hitCount >= threshold`;`distillAndSave` 用 CAS 保证 at-most-once | 集中蒸馏逻辑,两条路径共享 |
| 9 | ✏️ 重写 | `harness/hooks/SkillSynthesisHook.java` | 构造缩减为 3 参 `(runner, dimManager, ctx)`;`handlePreCall` 算指纹后委托 `runner.bumpAndMaybeSynthesize`;移除 distiller / saver / vectorIndex / embeddingClient 等成员 | 旧 8/10 参构造删除 |
| 10 | ✏️ 改造 | `harness/hooks/ResponseCacheHook.java` | 新增 6 元构造接 `SkillSynthesisRunner`(可空);HIT 分支前调 `runner.bumpAndMaybeSynthesize(...)`,异常吞掉;旧 2/4/5 元构造保持 `null` | 旧调用方零改动 |
| 11 | ✏️ 改造 | `service/SupervisorService.java` | 注入 `SkillSynthesisRunner` 字段;`build()` 改用 3 参 `SkillSynthesisHook`;`newCacheHook(...)` 透传 `skillSynthesisRunner` 给 6 元 `ResponseCacheHook`;删 `autoSynthEnabled/Threshold` 两个 `@Value`(已被 Runner 拿走) | DI 装配,行为不变 |

### 行为差异(PR2 → PR2.1)

| 场景 | PR2 初版 | PR2.1 |
|---|---|---|
| R1 MISS + R2/R3 Cache HIT(threshold=3) | hit_count 卡在 1,**永远不蒸馏** | R1 PreCall 在 MISS 路径计 1;R2 HIT 路径计 2;R3 HIT 路径计 3 ⇒ Runner 触发异步蒸馏 |
| 全部 MISS(R1~R3) | hit_count 在 R1/R2/R3 PostCall 各 +1,R3 PostCall 蒸馏 | R1/R2/R3 PreCall 各 +1(Runner 内同步 +1),R3 PreCall 直接异步蒸馏 |
| analyze 类(带 `q=hash` cacheKey)同维度不同措辞 | cacheKey 不同 ⇒ 全 MISS,但候选指纹无 hash 仍递增,正常 | 同上;Runner 用的指纹也无 hash,语义保持 "同维度沉淀一个 skill" |
| 两条路径并发碰同一指纹 | 不可能(初版只有 PostCall 计) | `markSynthesized` 行级 CAS,affected_rows=1 的那条赢,另一条 skip |
| LLM 蒸馏出错 | `markRejected("distiller_returned_null")`,同一指纹下次 status≠pending 被挡 | 同左,Runner 内逻辑没变 |

### 关键设计点(增补)

#### 为什么抽 Runner,而不是直接在 ResponseCacheHook 里再写一份 distill 逻辑
两条路径都需要相同的"bump + 检阈值 + 异步蒸馏"行为。如果各写一份,两边的 `markSynthesized` CAS / saver / vectorIndex 三件事都要复制粘贴,迟早漂移。Runner 是一个 `@Component` 单例,捕获工作区路径与开关阈值,任何 hook 拿到它都能完成完整的合成动作。

#### Cache key vs Synthesis fingerprint 是两套形状,**不可复用**
- `ResponseCacheHook.cacheKey = tenantBucket | intent | stateKey [| q=sha8(question)]` — analyze/compare/trend 等带 `q=hash`,防止"对比 Q1 与 Q4" 和"查 Q1" 撞同一行
- Runner / `SkillSynthesisHook` 用的 fingerprint = `tenantBucket | intent | stateKey` — 无 `q=hash`,语义是"同一维度的所有措辞都该沉淀**一个** skill"

所以 `ResponseCacheHook` 在 HIT 分支不能直接传 cacheKey 给 Runner,得用同一份 `state + intent` 独立重算 fingerprint(代码 ~10 行)。

#### 计数原子性
`SkillCandidateRepository.incrementHit` 用 `ON DUPLICATE KEY UPDATE hit_count = CASE WHEN status='pending' THEN hit_count+1 ELSE hit_count END`,两条路径并发也安全:
- 已 `synthesized` 的行,即便后续 HIT 仍调 incrementHit,行级 CASE 抑制 +1,不会"蒸馏完了还在涨"
- MISS 写 N,HIT 写 N+1,DB 看到的总是单调递增

#### 阈值检测的同步窗口
旧逻辑是"R3 MISS 的 PostCall 才能看到 hit_count=3",新逻辑是"任何路径的 PreCall 算完 incrementHit 就能看到"。也就是说:
- R3 是 MISS:PreCall 算到 3 → Runner 异步派发蒸馏 → R3 仍正常返回 LLM 回复
- R3 是 HIT:HIT 短路前算到 3 → Runner 异步派发蒸馏 → R3 仍返回 cache 内容

两种情况下用户感知一致:第 3 次问完后台开始蒸馏,大约 8~30s 后 `workspace/.../skills/` 出现新 SKILL.md。

### 验收(增补)

- [ ] 开 cache + 开 auto-synth + threshold=3:R1 MISS(日志 `[MISS path] candidate ... hit=1 status=pending`),R2/R3 HIT(日志 `Cache HIT for key=...` + `Skill synthesis triggered: fingerprint=... hit=3`),`workspace/.../skills/` 出现新 SKILL.md
- [ ] 关 cache:行为退化为全 MISS 路径,SkillSynthesisHook PreCall 计数 + Runner 蒸馏正常
- [ ] `markSynthesized` 后再问同问题:无论 MISS/HIT,`skill_candidate.hit_count` 不再增长(CASE 抑制),Runner `maybeDispatch` 见 status=synthesized 直接 skip
- [ ] 两条路径并发碰同一指纹(模拟):只有一条胜出写入,另一条 log "already claimed by another writer"



