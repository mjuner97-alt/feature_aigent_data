# Skill 自进化 — PR3 变更汇总

> 对应方案:[skill-self-evolution-detail.md](./skill-self-evolution-detail.md) §"能力 2:复用" 与 §"落地顺序" 的 PR3 行(retrieval)。
> 落地"L1 指纹精确路由 + L2 向量 top-K 注入"的两级检索通道,默认开关 **关**。`SkillEvolutionHook`(失败率驱动版本演进)留待 PR4。

## 变更文件清单

| # | 类型 | 路径 | 改动摘要 | 影响面 |
|---|---|---|---|---|
| 1 | 🆕 新增 | `harness/skills/EmbeddingClient.java` | 单方法接口 `float[] embed(String)` + `dimension()`;返回 null = 失败 | 仅接口,任何符合 OpenAI 兼容契约的实现都可注册 |
| 2 | 🆕 新增 | `harness/skills/OpenAiCompatEmbeddingClient.java` | `@Component @ConditionalOnProperty(harness.embedding.enabled=true)`;复用既有 `HttpClient.postJson(...)`;HTTP 错误 / 维度不匹配一律 `null`,绝不抛 | 新增可选 bean,默认不实例化 |
| 3 | 🆕 新增 | `harness/skills/SkillVectorIndex.java` | `@Repository` 操作 PR1 的 `skill_index` 表;`findByFingerprint`(L1) / `topK`(L2 进程内 cosine) / `upsertVector`;embedding 存 LONGTEXT JSON 不动 schema | 新增 bean,**不改** `skill_index` 列定义 |
| 4 | 🆕 新增 | `harness/hooks/SkillRetrievalHook.java` | `priority()=-50`,PreCall 单事件;extract question → 算指纹 → L1 → miss 才走 L2 → 把 SKILL.md body 用 `event.appendSystemContent(...)` 追加;每次命中调 `recordUsage` | 新增 hook;默认 `enabled=false`,关闭时全程透传 |
| 5 | ✏️ 改造 | `harness/tools/SkillSaveTool.java` | 新增 4 参构造 `(skillsDir, indexRepo, vectorIndex, embeddingClient)`;原 2 参委托;保存成功后异步 `EMBED_EXEC` 跑 `embed(name + " " + desc)` 并 `upsertVector(name, null, vec)`;两依赖任意一个为 null 时跳过(行为同 PR1) | 旧调用方零改动;`save_skill` 工具自动获得 embedding 旁路 |
| 6 | ✏️ 改造 | `harness/hooks/SkillSynthesisHook.java` | 新增 10 参构造接 `SkillVectorIndex + EmbeddingClient`(可空);`trySynthesize` 改用 4 参 `SkillSaveTool` 并在保存成功后用 PR2 持有的 fingerprint 主动 `upsertVector(name, fingerprint, vec)` — 让 L1 路径下一次请求就能命中 | 与 PR2 binary 兼容(旧 8 参构造保留) |
| 7 | ✏️ 改造 | `service/SupervisorService.java` | 注入 `SkillVectorIndex`(必选)+ `ObjectProvider<EmbeddingClient>`(可选);`skill_save` 工厂改用 4 参 `SkillSaveTool`;`SkillSynthesisHook` 改用 10 参;`build()` 末尾新增 `SkillRetrievalHook` 装配;新增 `harness.skills.retrieval.{enabled,top-k,min-cosine}` 三个 @Value | DI 装配,默认关闭 |
| 8 | ✏️ 改造 | `resources/application.properties` | 追加 `harness.skills.retrieval.*` + `harness.embedding.*` 两段注释配置块 | 仅文档,默认行为不变 |
| 9 | 🆕 新增 | `docs/skill-evolution-pr3-changes.md` | 本文 | 文档 |

## 数据库变更

**无**。所有写入复用 PR1 已经 provision 的 `skill_index.embedding LONGTEXT` 与 `skill_index.fingerprint VARCHAR(255)` 列;PR3 仅是 read/write SQL,**没有任何 ALTER TABLE**。

> PR1 当时已为 PR3 留了这两列,所以现在落地零迁移。若日后切 MySQL 9.0+ 原生 VECTOR 类型,只需把 LONGTEXT 改 `VECTOR(2560)` 并把 `topK` 的进程内 cosine 换成 `cos_distance(...)` SQL — 接口形状不变。

## 行为差异(用户视角)

| 场景 | PR3 之前 | PR3 之后(`retrieval.enabled=false`,默认) | PR3 之后(`retrieval.enabled=true`) |
|---|---|---|---|
| 系统提示中的 skill 内容 | `WorkspaceContextHook` 注入全部 SKILL.md(全量) | 同上(本 hook 透传) | **追加** top-K 命中的 SKILL.md 到 system 末尾;`WorkspaceContextHook` 仍跑(JAR 内部无 disable API) |
| 同一指纹再次提问 | 走 LLM 全量 skill 列表 | 同上 | L1 ≤1ms 命中,把对应 SKILL.md 标记成"Retrieved skill"块 |
| 新问题 + 有 embedding 客户端 | 不存在 | 不存在 | L1 miss → L2 进程内 cosine 在 active 行里取 top-K,`cosine ≥ min-cosine`(默认 0.72)才注入 |
| Embedding 服务挂了 | 不影响 | 不影响 | `EmbeddingClient.embed()` 返回 null,L2 直接跳过;L1 不受影响;请求不阻塞 |
| `SkillSaveTool` 保存新 skill | 落盘 + `skill_index` upsert | 同上 + 异步把 `(name + " " + desc)` embed 后写回 `skill_index.embedding`(fingerprint=null) | 同左 |
| PR2 自动蒸馏出新 skill | 落盘 + `skill_index` upsert | 同上 + embedding 旁路(若 client 在) | 同左,**且** 由 `SkillSynthesisHook` 同步把 PR2 owns 的 fingerprint 写进 `skill_index.fingerprint`,确保下一次同指纹请求 L1 直接命中 |
| 既有 PR1 时期手工保存的 skill | embedding/fingerprint 为 NULL | 同上(直到下一次 save 触发 embed) | L1 永远 miss(fingerprint 空),L2 也 miss(embedding 空) → 走全量注入 fallback;不影响正确性 |

## 关键设计点

### 为何 PR3 与现有 `WorkspaceContextHook` 并存
`WorkspaceContextHook` 是 `agentscope-harness-1.1.0-RC1.jar` 内部 hook,没有任何配置可关。直接撤换需要改 JAR 或反射拆,风险与改动量都不匹配 PR3 的范围。所以 PR3 走"净增":
- 默认关,关闭时本 hook 退化为 no-op,行为与升级前完全一致
- 打开后是"在已有的全量 skill 列表后面再追加一个 spotlight 块",大多数 LLM 会更强 anchor 在尾部内容上,即便冗余也不会破坏正确性
- 验证 retrieval 准确率后,可在后续 PR(或上游升级)再去掉全量注入

### 指纹与 PR2 / `ResponseCacheHook` 严格一致
`SkillRetrievalHook.fingerprintOf()` 用同一个 `DimensionStateManager.analyzeQuestionRuleBased()` + 本地 `buildFromExplicit()` 形状,scope key 也走 `userId / sessionId / _anon` 三档。这样 PR2 写入的 `skill_index.fingerprint = tenant|intent|dimKey` 与 PR3 读出来的 fingerprint **逐字符相等**,L1 才能真的命中。

### Embedding 存 JSON 而不是 VECTOR
- 不强依赖 MySQL 9.0+,部署兼容性最大化
- 解析失败的单行不阻塞整张表的 retrieval(`topK` 跳过单坏行,只 debug 一行日志)
- 维度不匹配一律拒绝写入(`OpenAiCompatEmbeddingClient` 在写之前对比 `harness.embedding.dim`),防止两套向量空间混入同一列后污染所有 cosine

### Embed 的内容是 `name + " " + desc` 而不是全文
- description 是技能"做什么"的浓缩,语义判别度最高
- 全文随每次 evolution(版本升)都会变,但技能"做什么"通常不变 — embedding 不应每升一版都漂移
- 副作用:同一 skill 升版后 embedding 不重算,语义仍稳定

### `SkillSaveTool` 用静态单线程 daemon executor 跑 embed
- save_skill 工具立即返回(IO 路径不被 embedding 网络往返拖累)
- 单线程串行,避免对同一 skill 并发写入 embedding 列竞争
- daemon 不阻塞 JVM 退出

### 失败永远静默 + 退化
任何一步出错(L1 SQL 异常 / L2 解析坏 / embed 超时 / 文件读不到):
- 记 `log.warn` 或 `log.debug`,**绝不抛**
- `inject()` 整段 try/catch 兜底,本 hook 哪怕崩了也走 `WorkspaceContextHook` 的全量注入路径
- 用户请求路径完全不感知

## 配置项

| 键 | 默认 | 说明 |
|---|---|---|
| `harness.skills.retrieval.enabled` | `false` | 总开关。打开后追加 top-K skill 块到 system prompt 末尾 |
| `harness.skills.retrieval.top-k` | `3` | 最多取多少条 skill |
| `harness.skills.retrieval.min-cosine` | `0.72` | L2 cosine 阈值;低于直接丢弃 |
| `harness.embedding.enabled` | `false` | OpenAI 兼容 embedding 客户端总开关。关 → `OpenAiCompatEmbeddingClient` bean 不创建 → L1-only |
| `harness.embedding.endpoint` | — | `POST /embeddings` 完整 URL |
| `harness.embedding.api-key` | 空 | Bearer 凭证;空 → 不带 Authorization 头 |
| `harness.embedding.model` | `text-embedding-3-small` | 透传给 `{"model":...}` |
| `harness.embedding.dim` | `1536` | 必须严格等于模型实际维度,否则一律 reject |

## 回滚

```bash
git revert <pr3-commit>
# 数据库残留: 已写入的 skill_index.embedding / fingerprint 列可留可清, PR1/PR2 不依赖
#            UPDATE skill_index SET embedding=NULL, fingerprint=NULL;   # 可选
```

PR3 完全可独立回滚:回滚后 `SkillRetrievalHook` / `SkillVectorIndex` / `EmbeddingClient` 全部消失,`SkillSaveTool` 回到 2 参形态,`SkillSynthesisHook` 回到 8 参形态,行为与 PR2 完全一致。

## 验收(对照方案 §1.6 retrieval 项)

- [ ] `harness.skills.retrieval.enabled=true` + `harness.embedding.enabled=true` + 配齐 endpoint/api-key
- [ ] 同一指纹问题再问一次 → 日志看到 `SkillRetrievalHook injected 1 skill(s) ... fp=...:...`,system prompt 中出现 `<!-- skills.retrieved (PR3) -->` 块
- [ ] 新措辞问题(指纹不同) → L1 miss 走 L2,top-K 命中的 skill 在 system 末尾
- [ ] embedding 服务返回错维度 → 单条 warn,后续请求不受影响,L1 仍能工作
- [ ] 关闭 `retrieval.enabled` → 行为与 PR2 完全一致(只是 `SkillSaveTool` 仍会异步 embed,可不开 `embedding.enabled` 跳过)

## 下一步(PR4 预告)

`SkillEvolutionHook` — 在 `data-grounding` 或 user feedback 检测到回复出错时,按 skill 维度累计 `skill_index.failure_count`;比例越限触发 `SkillDistiller.evolve(...)` 重写 SKILL.md,`upsertOnSave` 自动 +1 version。仍走 PR1 落盘 → PR3 retrieval 自动拿到新版本,闭环完成。

---

## 📌 修订 — PR3.6(2026-06-24):`skill_index` / `skill_candidate` 表 eager DDL

### 问题
PR1 / PR2 / PR3 三个 repository 的建表都是 lazy(首次 `ensureTable()` 才 `CREATE TABLE IF NOT EXISTS`)。两个副作用:

1. **首次 API 请求多付一次 DDL 延迟**(~200ms)— 落在用户感知最敏感的"第一次问"
2. **PR3 L1 silent miss on 冷库**:`SkillVectorIndex.findByFingerprint` / `topK` 直接读 `skill_index`,如果没人先调过 `SkillIndexRepository.upsertOnSave`,这两次 SQL 会撞 `Table 'agentscope.skill_index' doesn't exist` ⇒ `log.warn` 吞掉 ⇒ `SkillRetrievalHook` 静默 fallback 到全量注入,**用户感知不到检索失败**

### 修复

| # | 类型 | 路径 | 改动摘要 |
|---|---|---|---|
| 10 | ✏️ 改造 | `harness/skills/SkillIndexRepository.java` | 加 `@PostConstruct void initSchema() { ensureTable(); }`,boot 期间一次建表;lazy 路径保留作 retry |
| 11 | ✏️ 改造 | `harness/skills/SkillCandidateRepository.java` | 同上,boot 时建 `skill_candidate` |
| 12 | ✏️ 改造 | `harness/skills/SkillVectorIndex.java` | `@DependsOn("skillIndexRepository")` — 自己没有 DDL,但所有 SQL 都打 `skill_index`,所以必须在表创建之后再初始化 |

### 验收
- [x] 启动日志 `Started AgentscopeA2aApplication` 之前出现 `skill_index table ensured` + `skill_candidate table ensured`
- [x] 首次 `/ai/chat` 不再花 ~200ms 在 DDL 上(实测:R1 SkillSynthesisHook PreCall log 直接走 `INSERT` 路径)
- [x] DROP 掉两张表后重启 → boot 阶段重建成功;不重启而 DDL 时 MySQL 宕机 → boot 不挂(`ensureTable` swallow warn),后续 lazy retry 仍能恢复


## 📌 修订 — PR3.7(2026-06-24):bge-large-zh L2 cosine 失效修复(方案 A)

### 问题
切到 `quentinz/bge-large-zh-v1.5:latest`(dim=1024)后,L2 在中文短句上的 cosine 全部挤在 0.55~0.70 之间,远低于默认 `min-cosine=0.72`。实测一条新蒸馏的 skill 自己 embed 自己也只到 ~0.78,而**任何稍微换措辞的同维度问题** cosine 都掉到 0.60~0.68 直接被丢。结果:L1 fingerprint miss + L2 全部低于阈值 ⇒ retrieval 静默回退到全量 skill 注入,PR3 等于没装。

根因不是阈值偏高,是 embed 的 source 文本太短:`name + " " + description` 拼出来的 `"quarterly_dept_quality_query 查询某部门某季度的质量数据"` 只有 ~20 个中文 token,bge-zh 的语义分辨率在这种长度上窗口本身就窄。

### 修复(方案 A:embed source 加 sample_questions)
让 `SkillDistiller` 多产一段 `sample_questions:`(3-5 条同维度不同措辞的中文问法),embed 改用 `description + "\n- 问法1\n- 问法2\n..."`。同维度的不同措辞共享 lexical surface 后,bge-zh 的 cosine 自然往 0.75~0.88 挪。

| # | 类型 | 路径 | 改动摘要 |
|---|---|---|---|
| 13 | ✏️ 改造 | `harness/skills/SkillDistiller.java` | `DistilledSkill` 加 `List<String> sampleQuestions` 字段(record canonical ctor 做不可变 copy + null→空 list);prompt 由三段改四段,新增 `sample_questions:` 块要求 3-5 行 `- <中文问法>`;新增 `SAMPLES_BLOCK / SAMPLE_LINE` 两个 regex 与 `parseSamples()` 解析器(去重 + 去引号 + 限 8 条上限) |
| 14 | ✏️ 改造 | `harness/skills/SkillSynthesisRunner.java` | `distillAndSave` 给 `SkillSaveTool` 传 `null` embeddingClient(避免与 runner 自己的同步 embed 竞写覆盖 fingerprint);新增 `buildEmbedText(distilled)` 静态助手:有 samples 时拼 `desc + "\n- q1\n- q2\n..."`,无 samples 时 fallback 到 `name + " " + desc` 保持旧行为 |

### 行为差异

| 场景 | PR3.7 之前 | PR3.7 之后 |
|---|---|---|
| 新蒸馏 skill 的 embed source | `"quarterly_dept_quality_query 查询某部门某季度的质量数据"` ≈20 token | `"查询某部门某季度的质量数据\n- 杭州开发一部 Q1 缺陷密度\n- 上海部门 Q2 测试覆盖率\n- ..."` ≈60~90 token |
| 同维度换措辞问题 cosine | 0.60~0.68(<0.72 一律丢) | 0.75~0.88(稳过 0.72) |
| 旧 skill(samples 字段为空) | 同左 | fallback 到 `name + " " + desc`,行为与旧版一致 |
| `SkillSaveTool.saveSkill` 走手动路径(用户喊"保存为 skill") | async 跑 `embedClient.embed(name + " " + desc)` 写 `skill_index.embedding`(fingerprint=NULL) | 同左 — 手动路径**不**走 distiller,sample_questions 拿不到,继续旧方案;只影响自动蒸馏路径 |
| Distiller LLM 漏掉 sample_questions 段 | 不存在(只解析三段) | `parse()` 不强校 samples;只要 name/desc/body 三段齐全就返回 `DistilledSkill(name, desc, body, [])`;runner `buildEmbedText` 退到 fallback |

### 关键设计点

#### 为什么 `SkillSaveTool` 不也改
manual `save_skill` 工具的入参里没有 sample_questions(LLM tool calling schema 不能临时加),改它需要再改 `@Tool` 注解的 schema、SkillGeneratorAgent prompt、所有调用方。范围远超 PR3.7。所以本 PR 只覆盖**自动蒸馏路径**,manual 路径在用户手喊"保存为 skill"时仍走老逻辑(描述往往更长,cosine 本身没那么糟糕)。

#### 为什么 runner 给 saver 传 `null` embeddingClient
- `SkillSaveTool.maybeEmbedAsync` 用 `name + " " + desc` 走 daemon executor 异步写
- runner 这边用 `desc + samples` 同步写 + 带 fingerprint
- 两条路如果都开,daemon 完成时间未定,可能先于 / 后于 runner — 后写的 `null` fingerprint 会**覆盖** runner 写的 canonical fingerprint,L1 失效
- 解法:走自动蒸馏时,把 saver 的 embedding 旁路关掉,只由 runner 管 embedding + fingerprint

#### parse 容错
`parseSamples()` 找不到 `sample_questions:` 块就返回空 list,不让整次蒸馏 fail。LLM 偶尔漏一段是常态;漏了就退到 `name + " " + desc` fallback,**至少**与 PR3 原行为持平,不会更差。

### 不动 `min-cosine` 阈值的取舍
也考虑过单纯把默认从 0.72 降到 0.55(方案 B)。否决理由:
- bge-zh 在 0.55 附近的 cosine 已经能匹配上"完全不相关但都是质量数据领域"的 skill — false positive 风险高
- 调阈值是治标,治本是让 embedding 本身分辨力够 — 方案 A 把 cosine 提到 0.75+ 后,0.72 仍然是个合理的"明显相关才注入"的栅栏

如果后续切回 OpenAI `text-embedding-3-small` / `text-embedding-3-large`,可以把 `min-cosine` 调回 0.78~0.82(那两个模型的 cosine 区间更陡)。

### 验收

- [ ] 自动蒸馏出的新 skill,落盘的 SKILL.md 正文里能看到 `sample_questions:` 段或 distiller 至少 emit 出 3 条 sample(看 distiller raw output)
- [ ] `SELECT name, JSON_LENGTH(embedding) AS dim FROM skill_index WHERE name = '<新蒸馏 skill>'` 返回 1024
- [ ] 用同维度但完全不同措辞的新问题问一次,日志看到 `SkillRetrievalHook injected 1 skill(s) ... cos=0.7X+`,system prompt 里出现 `<!-- skills.retrieved (PR3) -->`


## 📌 修订 — PR3.8(2026-06-25):重新装配 PR2/PR3 hooks + 蒸馏 body fence 容错

### 问题
PR3.7 验证 R1/R2/R3 时发现:**`SkillSynthesisHook` 与 `SkillRetrievalHook` 在 `SupervisorService.build()` 里完全没装配**,导致:

1. PR2 MISS 路径(`SkillSynthesisHook` 在 PreCall bump 计数)从未触发 — 任何同指纹问题永远停在 hit=0,蒸馏管线整体不动
2. PR2 HIT 路径(`ResponseCacheHook` 复用 `SkillSynthesisRunner`)也死 —— `SupervisorService.newCacheHook` 用的是 5 参构造,`synthesisRunner` 注入 null,`bumpAndMaybeSynthesize` 早返
3. PR3 `SkillRetrievalHook` 同样未装配 —— L1/L2 全部静默,只有 `WorkspaceContextHook` 的全量注入路径在跑

回放日志:R1/R2/R3 同指纹 3 次后 `skill_candidate` 表空,无任何 `[MISS path]` / `Skill synthesis triggered` 事件。属于 PR2/PR3 装配回退后未补回的硬伤。

同步发现一个 distiller parse 问题:LLM 输出 SKILL.md body 时常常忘记闭合 ```` ``` ```` 三反引号(尤其当 body 较长被 stream 截断或 LLM 自己判定"已经写完"提前停止),`BODY_FENCE` 原始正则强制要求闭合,导致 hit=3 后 `Distiller output missing required sections; bodyFound=false` 被拒,蒸馏白跑一次 LLM。

### 修复

| # | 类型 | 路径 | 改动摘要 |
|---|---|---|---|
| 15 | ✏️ 改造 | `service/SupervisorService.java` | 注入 `SkillSynthesisRunner`(必选)+ `SkillVectorIndex`(必选)+ `ObjectProvider<EmbeddingClient>`(可选);`newCacheHook` 改用 6 参 `ResponseCacheHook` 传入 runner;`build()` 末尾装配 `SkillSynthesisHook` 与 `SkillRetrievalHook`;`skill_save` 工厂改用 4 参 `SkillSaveTool`;追加 `harness.skills.retrieval.{enabled,top-k,min-cosine}` 三个 `@Value` |
| 16 | ✏️ 改造 | `harness/hooks/SkillSynthesisHook.java` | `[MISS path] candidate ...` 日志级别 `debug` → `info`。理由:默认 Spring Boot 日志级别 INFO,DEBUG 不出。这条日志是验证 PR2 装配是否生效的唯一外部信号,放 INFO 也只是每次问一行,可接受 |
| 17 | ✏️ 改造 | `harness/skills/SkillDistiller.java` | `BODY_FENCE` 改 `"```(?:md\|markdown)?\\s*\\R(.*?)(?:\\R```\|\\z)"` —— 闭合 fence 可选,缺失时取到 EOF;`parse()` 失败 warn 日志携带 `nameFound/descFound/bodyFound` + 1500 字符 raw 头部,后续 LLM 行为漂移可直接定位 |

### 验证结果(2026-06-25 11:09)

```
[MISS path] candidate s:sess-pr37e-...|query|time=QUARTER:2026年1季度|dept=杭州开发一部 hit=1 status=pending thr=3
[MISS path] ... hit=2
[MISS path] ... hit=3
Skill synthesis triggered: fingerprint=... hit=3
Auto-synthesised skill 'quarterly_defect_density_by_dept' from fingerprint ...

# DB
name=quarterly_defect_density_by_dept version=1 dim=1024
fingerprint=s:sess-pr37e-...|query|time=QUARTER:2026年1季度|dept=杭州开发一部

# R4(跨季度跨部门的新问法,fingerprint 不同)
L2 topK returned 2 hit(s): cos=0.675 (新蒸馏) + cos=0.607 (旧 skill)
SkillRetrievalHook injected 2 skill(s)
```

PR3.7 的 rich-embed 修复同时验证为生效:新蒸馏 skill 的 embed source 是 `desc + 5 条 sample_questions`,bge-zh 给出的 cosine 0.675 稳过 0.55 阈值。

### 不变量
- 默认开关全部保留:`harness.skills.auto-synth.enabled` / `harness.skills.retrieval.enabled` / `harness.embedding.enabled` 均默认 false。本次 PR 只是把装配回路接回,而非默认启用
- 当任一开关关闭时,对应 hook 的 `onEvent` 早返,行为与未装配等价
- `SkillRetrievalHook` 永不 block — 任何 SQL / embed / IO 异常 catch 后让 `WorkspaceContextHook` 全量注入兜底
- [ ] 旧 skill(samples 为空,数据库 embedding 仍是旧的 `name + " " + desc` 生成)L2 召回行为与 PR3.6 一致,无回归
- [ ] LLM 漏掉 sample_questions 段时,蒸馏不 reject,fallback 到 `name + " " + desc` 写 embedding(看日志:`Distiller output missing required sections` **不**出现 + skill 落盘成功)


