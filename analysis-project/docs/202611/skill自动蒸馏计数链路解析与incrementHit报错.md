# Skill 自动蒸馏计数链路解析（MetricClassification / FingerprintCalculator / SkillCandidate）

> 2026/11/19 整理。起因：日志中出现 `KnowledgeRetrievalHook` 注入 + `[METRIC_CLASSIFY]` 关键词
> 匹配 + `incrementHit(...) failed: ERROR: syntax error at or near "("` 三段连发，需要弄清这条
> 链路是干嘛的、涉及哪些组件、为什么报错、以及它在当前代码里到底还有没有用。

## 1. 一句话结论

这是 **skill 自动蒸馏（auto-synth，PR2 时代）的"在线命中计数"链路**：每次用户提问时，把问题按
"指标语义"归到一个全局指纹上，对 `skill_candidate` 表计数；同一指标被问到 N 次（阈值默认 3）就
异步蒸馏生成一个 SKILL.md 存进 `skills-auto/` + `skill_index`。

**但该链路在 2026/09/08 已被整体废弃**（`application.properties` 明确标注）：
`harness.skills.auto-synth.enabled` Java 默认 false，`metric-classification.enabled=false` 显式
双保险。**代码还活着，配置已关闸**。正常关闭状态下，每轮对话走到 `SkillSynthesisHook.onEvent()`
第一行 `if (!runner.enabled()) return` 就退出，不会产生本日志里的任何一行。

> ⚠️ 疑点：产生本日志的环境里 incrementHit 实际执行了，说明该环境把
> `harness.skills.auto-synth.enabled=true` 打开了（外部配置/旧配置残留/旧包部署），
> 与 application.properties 的废弃声明不符，需要排查该环境的实际生效配置。

## 2. 链路全景图

```
用户提问 (PreCallEvent, priority 50)
  │
  ▼
SkillSynthesisHook.onEvent()                    hooks/SkillSynthesisHook.java
  │ runner.enabled()==false → 直接退出（当前默认路径，什么也不发生）
  │
  ▼ handlePreCall()
  ├── ① FingerprintCalculator.computeMetric(intent, question)
  │       └── MetricClassificationService.ruleBasedTag(question)   ← 关键词分类
  │             命中 '问题' → tag='problem'
  │             拼指纹: "_global" + "|" + intent + "|" + tag
  │             = _global|query|problem
  │
  ├── ② SkillSynthesisRunner.bumpAndMaybeSynthesize(fingerprint, ...)
  │       └── SkillCandidateRepository.incrementHit()              ← 本次报错点
  │             skill_candidate 表 upsert + hit_count+1（仅 status=pending 时）
  │             命中阈值(hit_count >= threshold 默认3) →
  │             异步蒸馏: generate_skill 子 agent 或直接 LLM
  │               → SKILL.md 写 skills-auto/ 目录
  │               → skill_index 插行 (fingerprint 指纹做 L1 查找键)
  │               → markSynthesized() CAS 防止双蒸馏
  │
  └── ③ MetricClassificationService.classifyAndUpdateAsync()       (enabled 时)
          轻量 LLM 兜底分类 → 写 skill_candidate.metric_tag
          （日志里那行 [METRIC_CLASSIFY] keyword match 其实来自 ①的
            ruleBasedTag，纯规则匹配，不调 LLM，不受 enabled=false 影响）
```

## 3. 各组件职责与关联

### 3.1 MetricClassificationService（指标语义分类）
`v2/skills/MetricClassificationService.java`

- **做什么**：把中文问题归类到一个英文 metric tag（problem / defect_density / response_time /
  error_rate / stat_summary ...）。
- **两级分类**：先 `ruleBasedTag()` 纯关键词匹配（词条来自
  `workspace/knowledge/metric-categories.yaml`，文件缺失用内置 9 类默认；`\` 开头的词条按正则，
  其余 contains 匹配）；规则不命中且 enabled 时再用 light-classifier 轻量 LLM 兜底。
- **产出用途**：
  1. 指纹的第三段（`_global|query|problem`）；
  2. 回填 `skill_candidate.metric_tag`；
  3. 蒸馏时经 `SkillSynthesisRunner.withMetricTag()` 注入 SKILL.md frontmatter
     `metric_tag:` 字段；
  4. `getMetricHint()` 给蒸馏 prompt 提供中文提示。

### 3.2 FingerprintCalculator（指纹计算）
`v2/skills/FingerprintCalculator.java`

- **主方案 `computeMetric(intent, question)`**：tenant 固定 `_global`（skill 是共享知识，所有
  用户问同一指标累计在同一行，避免重复蒸馏），格式 `_global|intent|metricTag`。
  这是 skill 合成 / L1 查找 / 演化的统一键。
- **遗留方案 `compute()`（@Deprecated）**：`tenant|intent|dimKey` 维度式指纹，仅 ResponseCache
  缓存键还在用（而 ResponseCache HIT 本身也已关）。
- **消费方**：
  | 消费方 | 用途 | 当前状态 |
  |---|---|---|
  | SkillSynthesisHook | 命中计数键 | 废弃（默认关） |
  | SkillEvolutionHook (PR4) | skill 成功/失败记账定位 | 废弃（默认关） |
  | digestion/TraceMiner + SkillFlowEvolver | `runtime_fingerprint` → `skill_index.fingerprint` L1 查找（已有 skill 就不重复蒸馏） | 废弃（夜间咀嚼 V2DigestionConfig @ConditionalOnProperty 默认不装配） |

### 3.3 SkillCandidateRepository（候选计数表）
`v2/skills/SkillCandidateRepository.java`，挂在 `gaussCustomerDataSource`。

- **表 `skill_candidate`**：fingerprint 主键 / user_id / hit_count / last_query / last_trace_id /
  metric_tag / status / synth_skill。
- **status 生命周期**：`pending → synthesized / rejected / blacklisted`。
  `incrementHit` 的 CASE 子句保证只有 pending 行才 +1，已蒸馏/已拒绝的行不再计数。
- **并发安全**：`markSynthesized()` 是 CAS（`UPDATE ... WHERE status='pending'`），保证两条路径
  （cache HIT / MISS——HIT 已废弃）或两个 JVM 同时过阈值时只蒸馏一次。
- **其他写点**：`updateMetricTag`（分类回填）、`markRejected`（蒸馏失败的 4 种原因码）。

### 3.4 下游：SkillSynthesisRunner + SkillDistiller + skill_index
- 蒸馏产物落两处：`workspace/skills-auto/<name>/SKILL.md`（文件）+ `skill_index` 表
  （name/fingerprint/metric_tag?/usage_count/success_count/failure_count/status/source）。
- `skill_index.fingerprint` 是"L1 查找键"：追问同指纹问题时可按指纹直接命中已有 skill，
  跳过向量检索（这是 §8 检索改造之前的老机制，现行检索已改为 v3 平铺行表 + 路由元数据）。

## 4. 本次日志报错解析

```
WARN SkillCandidateRepository.incrementHit(_global|query|problem) failed:
[122.89.42.88:43832/122.218.248.202:8000] ERROR: syntax error at or near "(" 位置：361
```

- **SQL**（`SkillCandidateRepository.java:88-94`）：MySQL 方言 upsert
  `INSERT ... ON DUPLICATE KEY UPDATE ... VALUES(last_query) ...`。
  注释声称"openGauss 不支持 ON CONFLICT，所以用 MySQL 兼容的 ON DUPLICATE KEY UPDATE"。
- **报错位置 361**：正好落在 `VALUES(last_query)` 的左括号上。`VALUES(col)` 引用插入值的函数
  语法只在建库时指定 MySQL 兼容模式（`DBCOMPATIBILITY='B'`/mysql）的 openGauss 实例上有效；
  日志中这台 gauss（122.218.248.202:8000）不是该兼容模式，因此把 `VALUES(` 当 PG 的 VALUES
  列表语法解析，在 UPDATE 子句里遇到 `(` 直接报语法错误。
- **影响**：该库上 `skill_candidate.hit_count` 永远累计不上 → 阈值永远不触发 → 在线自动蒸馏
  静默失效。仅 WARN，不阻塞当次问答（异常被 catch 返回 Optional.empty）。
- **注意**：即使重开 auto-synth，这条 SQL 在该 gauss 实例上也是坏的。若要修复，可按方言切换
  （PG 兼容模式的 openGauss 实际支持 `ON CONFLICT (fingerprint) DO UPDATE`），或建库时指定
  `DBCOMPATIBILITY`；但在当前"整链路已废弃"的前提下，**更合理的选择是不修**，只把环境的
  auto-synth 开关关掉消掉 WARN。

## 5. 现状结论：这两个功能还有用吗？

- **设计用途**：让系统在被反复问到同类指标时自动沉淀 skill（在线计数 + 蒸馏），属于
  "自进化"闭环（PR2 auto-synth / PR3 retrieval / PR4 evolution）的计数环节。
- **实际现状**：2026/09/08 自进化精简后整链路废弃：
  - auto-synth（计数+蒸馏）：Java 默认 false，配置行已删；
  - metric-classification：显式 false 双保险（唯一运行时调用点在 bump 路径内）；
  - evolution（PR4）：默认 false；
  - 夜间咀嚼 digestion：@ConditionalOnProperty 默认不装配；
  - PR3 检索（SkillRetrievalHook / SkillVectorIndex）：2026/07/30 已物理删除。
- **代码保留原因**：bean 还在 V2SkillConfig 中装配（light-classifier 实例配置 4 行不能删，否则
  启动报 placeholder 错）；`ruleBasedTag` 的 INFO 日志每次提问仍会出现（在 computeMetric 内部，
  但 computeMetric 本身只被废弃链路调用——正常关闭状态下 SkillSynthesisHook 第一行就退出了）。

### 排查清单（针对出现本日志的环境）
1. 确认该环境实际生效配置里是否有 `harness.skills.auto-synth.enabled=true`（外部
   application.properties / 启动参数 / 旧包内的 resources）→ 有则删。
2. 删除后日志里应不再出现 `[METRIC_CLASSIFY]`、`[FINGERPRINT]`、`incrementHit` 三段。
3. 若确实需要重开自进化，先解决 §4 的 SQL 方言问题，并按 `application.properties:87-95`
   注释里的指引恢复配置。

## 6. 涉及文件清单

| 文件 | 角色 |
|---|---|
| `v2/hooks/SkillSynthesisHook.java` | PreCall 入口（priority 50），算指纹+计数+触发蒸馏 |
| `v2/skills/FingerprintCalculator.java` | 指纹计算（metric 主方案 + 维度遗留方案） |
| `v2/skills/MetricClassificationService.java` | 指标关键词/LLM 分类 |
| `v2/skills/SkillCandidateRepository.java` | skill_candidate 表计数/CAS/回填（本次报错点） |
| `v2/skills/SkillSynthesisRunner.java` | 计数→阈值→异步蒸馏→保存 共享管线 |
| `v2/skills/SkillDistiller.java` | 蒸馏 LLM prompt 构造 |
| `v2/skills/SkillIndexRepository.java` | skill_index 表（蒸馏产物登记 + L1 查找） |
| `v2/hooks/SkillEvolutionHook.java` | PR4 失败反馈（默认关，同样消费 metric 指纹） |
| `v2/digestion/TraceMiner.java` / `SkillFlowEvolver.java` | 夜间链路的指纹消费方（已废弃不装配） |
| `v2/config/V2SkillConfig.java` | 全部 bean 装配 + @Value 默认值 |
| `resources/application.properties:87-95,131-136` | 废弃声明与双保险开关 |
| `workspace/knowledge/metric-categories.yaml` | 业务自定义指标词条（缺省用内置 9 类） |
