# Skill 自进化 — PR4 变更汇总(SkillEvolutionHook 失败反馈闭环)

> 对应方案:[skill-self-evolution-detail.md](./skill-self-evolution-detail.md) §"能力 3:失败反馈闭环" + §"落地顺序" 的 PR4 行。
> 设计文档:[skill-evolution-pr4-design.md](./skill-evolution-pr4-design.md)(讨论阶段,已落地。本文以实现视角覆盖)。
> 落地"PR2 沉淀 → PR3 召回 → PR4 演进 / 拉黑 → 重新召回"的闭环。默认开关 **关**。

## 变更文件清单

| # | 类型 | 路径 | 改动摘要 | 影响面 |
|---|---|---|---|---|
| 1 | ✏️ 改造 | `harness/hooks/SkillRetrievalHook.java` | 命中后写 `runtimeContext.put("skills.retrieved", List.copyOf(loaded))` —— 给 PR4 PostCall 提供输入。写入失败仅 debug,不影响注入路径 | 仅新增一个 attribute 写,PR3 行为不变 |
| 2 | 🆕 新增 | `harness/hooks/SkillEvolutionHook.java` | `priority()=+60`,**PreCall + PostCall 都监听**。PreCall 处理跨轮用户负反馈(消费上一轮 cache,关键词匹配 → recordFailure / 无匹配 → recordSuccess);PostCall 扫 memory 数 `python_exec` 失败(`[python_exec] exit=非零` token 匹配)≥2 → recordFailure;否则把本轮 skills 缓存进 runner 等下一轮判 | 新增 hook,默认 `enabled=false`,关闭时短路 |
| 3 | 🆕 新增 | `harness/skills/SkillEvolutionRunner.java` | 三件事:(a)`recordSuccess/recordFailure` 直写 `skill_index` 计数,失败路径调 `evaluateThresholds`;(b)per-skill `markEvolving/markEvolved` CAS(ConcurrentHashMap.putIfAbsent),`dispatchEvolve` 在 `boundedElastic` 跑 `SkillDistiller.evolve`,落盘 + `upsertEmbeddingOnly` + `resetCounts`;(c)`cachePendingJudgement/consumePendingJudgement` 跨轮判用户负反馈,LinkedHashMap LRU cap 1024 | 新增 bean,默认关 |
| 4 | ✏️ 改造 | `harness/skills/SkillDistiller.java` | 新增 `Mono<DistilledSkill> evolve(name, oldBody, exemplarQuestion, failedTraceSnippet)`;prompt 让 LLM 在 oldBody 基础上修订;parse 复用,**强制校验 name 与原 skill 一致**,LLM 改名 → reject。`callEvolveModel` 单独的 prompt 模板:四段(name 必须等于原名 / description / sample_questions / SKILL.md body),内嵌 ≤500 字失败 trace | 新方法;`distill()` 路径不动 |
| 5 | ✏️ 改造 | `harness/skills/SkillIndexRepository.java` | 新增 5 个原子方法:`incrementSuccess(name)` / `incrementFailure(name)` / `markBlacklist(name)` / `resetCounts(name)` / `findStats(name)` → `Optional<SkillStats>`;`SkillStats` 是 `(name, successCount, failureCount, version, status)` record,带 `totalUses()` + `failureRate()` 派生方法。所有 UPDATE 都按 PK 走单行,不加事务(失败回 false,调用方按"尽力而为"处理) | 新增方法,不改表结构 |
| 6 | ✏️ 改造 | `harness/skills/SkillVectorIndex.java` | 新增 `upsertEmbeddingOnly(name, embedding)` —— 演进时只刷 embedding 列,**不动 fingerprint**。`upsertVector(name, fp, vec)` 原方法保留供 PR2 / PR3 自动蒸馏 + 手动 save_skill 使用 | 新方法 |
| 7 | ✏️ 改造 | `service/SupervisorService.java` | 注入 `SkillEvolutionRunner`(必选);新增 2 个 `@Value`:`harness.skills.evolution.enabled` + `.rejection-keywords`(其他 5 个评估阈值由 runner 自己 `@Value` 注入,不再走 Supervisor);`build()` 末尾 `if (enabled) b.hook(new SkillEvolutionHook(runner, ctx, rejectionKeywords))` | DI 装配,默认关 |
| 8 | ✏️ 改造 | `resources/application.properties` | 追加 `harness.skills.evolution.*` 注释块(7 个键 + 默认值 + 注释解释 evolve / blacklist / 并发 CAS 机制) | 仅文档,默认行为不变 |
| 9 | 🆕 新增 | `docs/skill-evolution-pr4-design.md` | 设计阶段文档(已合入) | 文档 |
| 10 | 🆕 新增 | `docs/skill-evolution-pr4-changes.md` | 本文 | 文档 |

## 数据库变更

**无**。`skill_index` 表的 `success_count` / `failure_count` / `status` 三列在 PR1 已经 `CREATE TABLE` 时建好(PR1 的 DDL 里写了"reserved for PR4"注释);PR4 只是开始 read/write 这三列,**没有任何 ALTER TABLE**。

## 行为差异(用户视角)

| 场景 | PR4 之前 | PR4 之后(`evolution.enabled=false`,默认) | PR4 之后(`evolution.enabled=true`) |
|---|---|---|---|
| PR3 召回成功 | append skill 到 system,记 `recordUsage` | 同上 + 写 `runtimeContext.skills.retrieved` attribute | 同左 |
| 本轮 `python_exec` 失败 ≥ 2 次 | LLM 看到 retry hint 自己重试 | 同上 | 同左 + PostCall hook 给每个召回 skill `incrementFailure`,失败率超阈 → 异步 evolve / blacklist |
| 本轮无失败信号 | 用户答题结束 | 同上 | PostCall 把 `[retrieved skills, exemplar]` 写进 runner 的 pending cache;下一轮 PreCall 凭用户输入判 success/failure |
| 下一轮用户说"不对/错了/重算" | 没有特殊处理 | 同上 | PreCall 命中关键词 → 给上一轮 skills `incrementFailure`,trace 用"用户在下一轮否认了答案: <输入>"作为 evolve 上下文 |
| 下一轮用户继续提问 | 没有特殊处理 | 同上 | PreCall 消费 pending → `incrementSuccess` |
| 失败率 > 0.3 且 ≥ 5 次 | 不存在 | 不存在 | 异步在 `boundedElastic` 跑 evolve:LLM 拿 (oldBody + failedTrace ≤500 字) → 新 body → `saveSkill`(同名,version + 1)→ `upsertEmbeddingOnly`(不动 fingerprint)→ `resetCounts` |
| 失败率 > 0.6 且 ≥ 10 次 | 不存在 | 不存在 | `markBlacklist`:`status='blacklist'`,文件 + 累计计数保留;`SkillVectorIndex.findByFingerprint/topK` 既有的 `status='active'` filter 自动停止召回 |
| 同一 skill 并发触发 evolve | 不存在 | 不存在 | `ConcurrentHashMap.putIfAbsent` CAS,只有第一个抢到 lock 的请求实际跑 LLM,其他直接放行,`doFinally` 释放 lock |
| LLM 演进时改了 name | 不存在 | 不存在 | `SkillDistiller.evolve` 在 parse 后做 `equalsIgnoreCase` 校验,改名 → 返回 null,本次演进作废,下次失败再触发 |

## 关键设计点

### 失败信号源 = `python_exec retry ≥ 2` + 用户跨轮负反馈(方案 A)

设计文档讨论过 3 种信号:
- (A)`PythonExecRetryHook` 重试 ≥2 次 + 用户跨轮负反馈
- (B)恢复 `DataGroundingHook` 做轻量数值校验
- (C)LLM 自纠错关键词探测

PR4 选 **A**。理由:
- B 已删,恢复需要新建 hook + 数值匹配启发式,假阳性(LLM 对明细做了正确的平均 → 数值不在原始集合里)会让好 skill 被错降级,危害大于"少抓一些失败"
- C 假阳性更高(LLM 的"抱歉、感谢提问"礼貌用语会被命中)
- A 的两个信号在现有代码里都已经天然存在:`PythonExecRetryHook` 已经给 `python_exec` 失败加 `[python_exec] exit=非零` banner;用户负反馈关键词是简单字符串匹配
- 闭环优先级:**先把闭环跑起来**。失败覆盖率不完美(~40%)但样本量上来后,孤儿 skill 累计 failure 仍能触发 evolve,只是节奏慢

后续 PR5 可恢复 grounding 校验,做 B+A 联合信号源,假阳性互相消减。

### PostCall 自己扫 memory,不去改 PythonExecRetryHook

设计文档原计划是给 `PythonExecRetryHook` 加 `ctx.put("python_exec.failure_count", ++count)`。实现时换成 `SkillEvolutionHook` PostCall 直接遍历 `memory.getMessages()` 数 `ToolResultBlock` 里出现 `[python_exec] exit=非零` token 的次数。

为什么:
- 单点改动,无需协调两个 hook 之间的字段约定
- `PythonExecRetryHook` 不必感知 PR4 的存在,模块解耦
- memory 是 PostCall 时一定可达的(`event.getMemory()`),性能开销 = O(messages × content blocks),量级几十,可忽略
- 缺点:与 `PythonExecRetryHook` 的 `exit` parser 同源,任一方调整了 banner 格式都得同步改 —— 但这俩文件距离很近,review 时能看见

### 跨轮负反馈用 per-session in-memory cache,不入库

`RuntimeContext` 是 per-call 的,turn N PostCall 写的 attribute 在 turn N+1 PreCall 看不到。所以跨轮负反馈必须有跨 call 的状态。

候选:
- (i)入 MySQL 一张新表 `skill_pending_judgement`
- (ii)`SkillEvolutionRunner` 持有 `Map<sessionKey, PendingJudgement>` + LRU
- (iii)塞进 Session(框架持久层)

选 **(ii)**。理由:
- 跨轮判断的窗口很短(几秒~几分钟),没必要持久化
- LinkedHashMap access-order + cap 1024 自动淘汰冷 session,内存上限可控
- 单副本 OK,多副本时一个 session 在哪个副本上判就由那个副本扣账(用户不会同时在两个副本上跟自己对话),不会丢失也不会双扣
- 多副本完美一致需要 Redis,但 PR4 的目标是闭环跑通,不是完美一致 —— 留 PR6+ 升级

### 演进时只刷 embedding 不动 fingerprint

`SkillVectorIndex.upsertVector(name, fingerprint, vec)` 三参版会把 fingerprint 覆盖。但 evolve 路径要保留 PR2 当初 stamp 的那个 canonical fingerprint(同维度问题继续走 L1)。

所以新增 `upsertEmbeddingOnly(name, vec)`:`UPDATE skill_index SET embedding = ? WHERE name = ?`,只动 embedding 列。

老版本的 PR2 synthesis 路径继续用三参 `upsertVector` 写入 (name, fingerprint, vec) 初始三元组;PR4 evolve 路径只用 `upsertEmbeddingOnly`。

### evolve 后 counts 归零的必要性

老 body 的失败样本不能算新 body 的账。否则新 body 一上线就背着 0.5 的失败率,下一次失败立刻又触发 evolve → evolve loop。

`resetCounts(name)` 让新版本拿 `(0, 0)` 起点,要重新攒到 `min-uses-evolve`(默认 5)次才会再次评估。

副作用:新版本如果立刻失败,会比旧版本晚 5 个样本才被标记 —— 这是用"避免 evolve loop"换"对新版本宽容"的取舍,可接受。

### blacklist 不删 row、不删文件

- 删 row 丢累计样本 —— 平反后等于"这个 skill 从来没失败过",信息丢失
- 删文件丢可读历史 —— 后人看 git 找不到曾经存在过的 skill
- `status='blacklist'` 是软删,只读;`SkillVectorIndex` 现有 SQL 已经 `WHERE status='active'` 过滤,**不需要改一行 SQL** blacklist 就自动停止召回

运维人工 review 后:`UPDATE skill_index SET status='active' WHERE name='xxx'` 即可恢复。

### LLM 改名 reject 而不是 rename

`distiller.evolve` 的 prompt 已经强约束 `name: <原名>`,但 LLM 偶尔会自作主张换更"准确"的名字。

如果接受 rename:
- 文件名要重命名(`SkillFileSystemHelper.saveSkills` 会按 name 落 `skills/<name>/SKILL.md`)
- `skill_index.name` 是 PK,要 INSERT 新行 + DELETE 旧行,或 UPDATE PK(不支持)
- PR2 stamp 的 fingerprint 留在旧 name 行上,新 name 行 fingerprint = NULL → L1 失效

不值得。直接 `equalsIgnoreCase` 校验,改名就拒绝,本次演进作废,下次失败再触发 —— 多花一次 LLM 调用,但保证 skill 身份稳定。

### priority +60 的位置

- PR2 `SkillSynthesisHook` = +50
- 框架内部 hook(workspace context 等)= +10 / +20
- PR3 `SkillRetrievalHook` = -50(早于注入路径)
- PR4 `SkillEvolutionHook` = **+60**

PostCall 要在所有 PreCall / 工具执行链路结束后跑,所以 priority 比 PR2 更晚。PreCall 路径上 PR4 与 PR3 顺序无所谓(PR4 PreCall 只读 runner 内部 cache,不读 attribute),但保持 PR4 在 PR3 之后符合数字直觉。

### 失败永远静默 + 退化(同 PR3)

任何一步出错(SQL UPDATE 失败 / LLM 调用 timeout / 文件 IO / cache concurrent modification):
- 记 `log.warn` 或 `log.debug`,**绝不抛**
- `onEvent` 整段 try/catch,本 hook 哪怕崩了也走原本的 PR2/PR3 路径
- 用户请求路径完全不感知

## 配置项

| 键 | 默认 | 说明 |
|---|---|---|
| `harness.skills.evolution.enabled` | `false` | 总开关。关闭时 hook 在 SupervisorService 阶段就不装配 |
| `harness.skills.evolution.fail-rate-evolve` | `0.3` | 失败率超此值且样本数足够 → 触发异步 evolve |
| `harness.skills.evolution.fail-rate-blacklist` | `0.6` | 失败率超此值且样本数足够 → 拉黑(优先级高于 evolve) |
| `harness.skills.evolution.min-uses-evolve` | `5` | evolve 所需最小累计 use(success_count + failure_count) |
| `harness.skills.evolution.min-uses-blacklist` | `10` | blacklist 所需最小累计 use |
| `harness.skills.evolution.rejection-keywords` | `不对,错了,重算,重新,不是这样,不正确` | 用户负反馈关键词,逗号分隔,大小写不敏感 |

## 回滚

```bash
git revert <pr4-commit>
# 数据库残留:已写入的 skill_index.success_count / failure_count / status 可留可清
#            UPDATE skill_index SET failure_count=0, success_count=0, status='active';   # 可选
```

PR4 完全可独立回滚:回滚后 `SkillEvolutionHook` / `SkillEvolutionRunner` 全部消失,`SkillRetrievalHook` 多写的 `runtimeContext.skills.retrieved` 没人读但不影响透传,行为与 PR3.8 完全一致。已被标记为 `blacklist` 的 skill 在回滚后仍然被 `WHERE status='active'` 过滤掉(PR3 已有的 filter),如需复活手动 `UPDATE skill_index SET status='active'`。

## 验收(对照方案 §1.6 evolution 项)

- [ ] `harness.skills.evolution.enabled=true` + 一个已经累计 ≥5 次召回的 skill(可用 PR3.8 留下的 `quarterly_defect_density_by_dept`)
- [ ] 连续构造 3 次 `python_exec retry ≥ 2` 的失败请求 → `SELECT success_count, failure_count FROM skill_index WHERE name = '...'` 显示 `failure_count ≥ 3`,`success_count` 不变
- [ ] 触发 evolve 阈值(默认 fail-rate > 0.3 且 total ≥ 5)→ 日志看到 `Skill evolution triggered for '<name>'`,`SKILL.md` 同文件名 frontmatter `version: 2`,`success_count=0 failure_count=0`
- [ ] 构造 10 次失败请求(fail-rate > 0.6)→ `status='blacklist'`,下一次同 fingerprint 请求 L1 / L2 都 miss(被 `status='active'` filter 过滤),`WorkspaceContextHook` 全量注入路径仍在,行为不破
- [ ] 用户在 turn N+1 输入"不对" → turn N 召回的 skill `failure_count + 1`(查 SQL);turn N+1 改成普通提问 → `success_count + 1`
- [ ] 同一 skill 并发触发 evolve(同 fingerprint 在不同 session 同时累到阈值)→ 只有第一个 CAS 成功的请求实际跑 distiller(看日志中 `already evolving in another thread; skipping`)
- [ ] LLM 在 evolve 时把 name 改了 → 日志 `LLM tried to rename ... rejecting`,本次演进作废,下次失败再触发,**SKILL.md 文件不变**
- [ ] 关闭 `evolution.enabled` → 行为与 PR3.8 完全一致(无任何 `UPDATE skill_index SET success_count/failure_count`)

## 下一步(PR5 预告)

候选方向(按优先级):
1 **Skill 合并** (`SkillDistiller.merge`):周期性扫 `skill_index` 找 embedding cosine > 0.95 且 fingerprint 相似的 skill 对,LLM 合并 + 新 fingerprint 写入 + 旧 skill blacklist。
2 **`WorkspaceContextHook` 退场**:当 PR3 retrieval 准确率经 PR4 数据验证稳定后,反射禁用 JAR 内部的全量注入 hook,让 system prompt 只剩 PR3 的 spotlight 块。

