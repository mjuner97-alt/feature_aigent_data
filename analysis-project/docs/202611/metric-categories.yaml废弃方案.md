# metric-categories.yaml 废弃方案（metric-classification 链路）

> 2026/11/19 立项。背景：内网 auto-synth 被旧配置 `enabled=true` 意外打开（已调整关闭），
> 借机把 `metric-categories.yaml` 及其所属的 metric-classification（指标语义分类）链路
> 正式废弃，与 2026/09/08 "Skill 自进化 (PR2/PR3/PR4) — 已废弃" 同风格处理。

## 1. 结论速览

- `metric-categories.yaml` 的**唯一 Java 读者**是 `MetricClassificationService.init()`
  （`MetricClassificationService.java:117`），且**启动时无条件读取**——`enabled=false` 也读。
- 它的全部运行时消费链路（auto-synth 计数 / 蒸馏 prompt 增强 / metric_tag 回填）均已关闭，
  **当前唯一实际作用是：启动时读一次文件 + 打两行 INFO 日志**，纯死重。
- 可以废弃，但**不能只删文件就完事**——存在 bean 级联依赖（§5），且 light-classifier 4 行
  模型实例配置**仍被活跃的 V2DimensionConfig 使用，永远不能删**（§6）。

## 2. 文件本身

| 位置 | 性质 |
|---|---|
| `src/main/resources/workspace/knowledge/metric-categories.yaml` | 仓库模板（git 唯一跟踪副本） |
| 各环境运行时 workspace（`harness.a2a.workspace.path` + `/knowledge/`，如内网 `/java/analysis-project/analysis-project/workspace/knowledge/`） | 实际被读取的副本 |

内容结构：`categories[]`（tag / chineseHint / keywords / description）+ fallbackTag +
fallbackDescription。词条以 `\` 开头按正则，其余 contains 匹配（与 KnowledgeRetrievalHook 的
knowledge-index.yaml 只是**命名约定互相借鉴**，二者无代码依赖、互不影响）。

> 注意与 `knowledge-index.yaml`（KnowledgeRetrievalHook 用，活跃）区分——本次废弃不动它。

## 3. 消费链路全景与当前活性

```
metric-categories.yaml
  └─ 唯一读者: MetricClassificationService.init()          ← 启动时无条件读
       │
       ├─ ruleBasedTag(question)   — 纯关键词匹配，不调 LLM
       │    ├─ FingerprintCalculator.computeMetric()  → 指纹第三段 tag
       │    │    ├─ SkillSynthesisHook      auto-synth.enabled=false 早退  ✗ 不活跃
       │    │    ├─ SkillEvolutionHook      evolution.enabled=false 早退   ✗ 不活跃
       │    │    └─ digestion TraceMiner/SkillFlowEvolver  bean 不装配      ✗ 不活跃
       │    └─ MetricClassificationService.doClassifyAndUpdate() → updateMetricTag
       │         └─ SkillSynthesisHook         ✗ 不活跃
       │
       ├─ classifyAndUpdateAsync() — 轻量 LLM 兜底分类
       │    └─ SkillSynthesisHook.handlePreCall()             ✗ 不活跃
       │
       └─ getMetricHint(tag)       — 蒸馏 prompt 中文提示
            └─ SkillDistiller → SkillSynthesisRunner          ✗ 不活跃
```

**活性判定**：所有入口都有 enabled 早退 / @ConditionalOnProperty（matchIfMissing=false），
当前运行时 `ruleBasedTag` / `classifyAndUpdateAsync` / `getMetricHint` 均零调用。
测试目录（src/test）**无任何** MetricClassificationService / metric-categories 引用。

### 残余副作用（本次要废掉的）

1. 启动时读 yaml：`init()` 无条件执行，打
   `[METRIC_CLASSIFY] Initialized with N keywords across M categories` +
   `Loaded N metric categories from ...`。
2. `V2SkillConfig.metricClassificationService`（`V2SkillConfig.java:386`）：
   enabled=false 时 lightModel=null 不建模型，但 bean 仍创建。
3. `V2SkillConfig.skillDistiller`（`V2SkillConfig.java:431`）：**无条件 new
   OpenAIChatModel(light-classifier)**——即使蒸馏链路全关，启动仍建一个模型客户端。
4. `SkillSynthesisHook` / `SkillEvolutionHook` bean 仍创建并挂到 harnessHooks
   （`HarnessAgentPartsConfig.java:189-198`），运行时靠 enabled 早退。
5. 每轮提问若 auto-synth 被误开（本次内网事故），整条链复活并暴露 SQL 方言 bug。

## 4. 方案 A：最小废弃（只动文件与配置，零代码）

1. 删仓库模板 `src/main/resources/workspace/knowledge/metric-categories.yaml`。
2. 删各环境运行时 workspace 下的 `knowledge/metric-categories.yaml`。
3. 效果：`init()` 走 "not found → using built-in defaults" 分支，回退内置 9 类默认。
4. 缺点：bean、启动日志、SkillDistiller 的无条件模型构建全部保留——**文件没了但链路还在**，
   不算真废弃。仅适合作为方案 B 的第 1 步先行验证（删文件 → 重启 → 确认走内置默认不报错）。

## 5. 方案 B（推荐）：bean 级条件化 + 删文件，对齐 9/08 风格

### 5.1 bean 级联依赖陷阱（先看这个再动手）

直接删 `metricClassificationService` bean 会**启动炸**，因为下游 bean 用的是直接参数注入：

```
metricClassificationService (V2SkillConfig:386)
   ↑ 直接注入                    ↑ 直接注入
skillDistiller (V2SkillConfig:431)   skillSynthesisHook (V2SkillConfig:328)
   ↑ 直接注入
skillSynthesisRunner (V2SkillConfig:454)
   ↑ 直接注入
skillSynthesisHook
```

`HarnessAgentPartsConfig.harnessHooks` 对 hook 用 ObjectProvider getIfAvailable 是 null 容忍的，
但前提是 bean 定义不注册（@ConditionalOnProperty 不满足 → 方法不执行）；
**bean 定义存在而依赖缺失时 context refresh 直接失败**。

### 5.2 改动清单

全部 bean 统一挂同一个条件——`harness.skills.auto-synth.enabled`（默认 false，缺失即不装配）。
语义即"重开自进化时这组 bean 才存在"，无需为已废弃链路保留两套独立开关：

| # | 位置 | 改动 |
|---|---|---|
| 1 | `V2SkillConfig.java` metricClassificationService | 加 `@ConditionalOnProperty(name="harness.skills.auto-synth.enabled", matchIfMissing=false)` |
| 2 | `V2SkillConfig.java` skillDistiller | 同上条件（它同时被 digestion 的 SkillFlowEvolver 引用；V2DigestionConfig 本身 matchIfMissing=false 不装配，重开 digestion 时按其文档同步开 auto-synth 即可，风险记录在案） |
| 3 | `V2SkillConfig.java` skillSynthesisRunner | 同上条件 |
| 4 | `V2SkillConfig.java` skillSynthesisHook | 同上条件 |
| 4b | `V2SkillConfig.java` **skillEvolutionRunner + skillEvolutionHook（实施时补充）** | 同上条件。⚠️ 首轮实施漏掉导致启动报错：skillEvolutionRunner 直接注入 SkillDistiller（已条件化）→ context refresh 失败。PR4 链同属自进化废弃范围，hook 注入 runner，两者必须同开关。DebugController 已是 ObjectProvider 不受影响。 |
| 5 | `FingerprintCalculator.java` | metricClassifier 改可空（构造参数保留但 null 容忍），`computeMetric` 中 `metricClassifier == null → tag="general"` |
| 6 | `V2SkillConfig.java` fingerprintCalculator bean | 参数改 `ObjectProvider<MetricClassificationService>`，`getIfAvailable()` 传入 |
| 7 | `SkillSynthesisHook.java:126-135` | `metricClassifier != null` 判断已存在，无需改（bean 不装配时 hook 也不存在） |
| 8 | `SkillDistiller.java` getMetricHint 调用点 | 构造参数改 ObjectProvider 或可空容忍（bean 不装配时 distiller 也不存在，此项随 #2 自然消失；仅在保留 distiller bean 时才需要） |
| 9 | `application.properties` | 删除 `harness.skills.metric-classification.enabled=false` 行与注释块，更新 §"Skill 自进化 — 已废弃" 注释补充 metric-classification 一并条件化 |
| 10 | 仓库模板 yaml + 各环境 workspace/knowledge/metric-categories.yaml | 删除 |

### 5.3 不改动项（明确排除）

- **light-classifier 4 行实例配置（api-key / base-url / name 等）不能删**：
  `V2DimensionConfig.java:48-51` 维度分析仍活跃使用该实例。
- `knowledge-index.yaml` / KnowledgeRetrievalHook：活跃功能，不动。
- `SkillCandidateRepository` / `SkillIndexRepository` bean：SkillIndexRepository 被
  **活跃的** BuiltinSkillRegistrar、DirectSkillExecutor 使用，必须保留；
  SkillCandidateRepository 仅被 skillSynthesisRunner/SkillSynthesisHook 引用——若希望彻底，
  可随 #3/#4 条件化（挂同一开关），保守起见本期可先保留（空表 ensureTable 一次，无害）。
- `SkillEvolutionHook` / `SkillEvolutionRunner`（PR4）：已是 enabled=false 早退 + 默认 false，
  与本次无关，若要做"自进化全链彻底删除"另立方案（扩展项见 §7）。

## 6. 影响面

| 项 | 影响 |
|---|---|
| 指纹语义 | `computeMetric` 第三段恒为 `general`（`_global|query|general`）。仅影响已废弃链路（skill_candidate 计数、skill_index.fingerprint L1、digestion runtime_fingerprint）。**若将来重开自进化，旧指纹行（如 `_global|query|problem`）不会续接累计**，需清表或接受重新累计。 |
| 启动 | 少 4 个 bean + 少一个无条件 OpenAIChatModel 客户端；日志少 [METRIC_CLASSIFY] 两行 + "SkillSynthesisHook: auto-synth enabled=..." 一行（**注意：该行消失后，之前"grep 启动日志确认开关"的排查手段失效**，需 grep "SkillSynthesisHook wired" 判断是否挂载）。 |
| hook 链 | SkillSynthesisHook 不再挂载（等价于 enabled=false 早退，行为无差异）。 |
| 单测 | src/test 无引用，预期零改动；全量跑一遍确认。 |
| 文档 | `docs/superpowers/plans/2026-09-03-tool-routing-admin-ui.md` 的 "Do not modify metric-categories.yaml" 随之过时；`docs/errors/windows-sandbox.md` 的 tar 报错记录仅历史参考。 |

## 7. 扩展项（本期不做，留档）

若将来决定"自进化全链彻底删除"（而非条件化），完整清单为：
SkillSynthesisHook / SkillSynthesisRunner / SkillDistiller / MetricClassificationService /
SkillCandidateRepository / SkillEvolutionHook / SkillEvolutionRunner / FingerprintCalculator
（需先确认 ResponseCache 侧对维度式指纹的引用）+ skill_candidate 表。
前提核查：SkillIndexRepository 必须保留（BuiltinSkillRegistrar / DirectSkillExecutor 活跃使用）。

## 8. 验证清单

1. 方案 A 第 1 步先行：删 yaml → 本地重启 → 日志出现
   `metric-categories.yaml not found at ... using built-in defaults`，启动不报错。
2. 方案 B 实施后本地重启：
   - 无 `[METRIC_CLASSIFY] Initialized` / `Loaded N metric categories` 日志；
   - 无 `MetricClassificationService: light model` / `SkillSynthesisHook: auto-synth enabled` 日志；
   - 有 `SkillSynthesisRunner` / `SkillSynthesisHook` 相关 bean 缺失不报错（context 正常起）。
3. `mvn test` 全量过（当前基线 966+）。
4. 一次完整 E2E 对话：日志无 `[METRIC_CLASSIFY]` / `[FINGERPRINT]` / `incrementHit`；
   维度分析（V2DimensionConfig，复用 light-classifier）不受影响。
5. 内网部署同步：删服务器 workspace/knowledge/metric-categories.yaml + 确认
   auto-synth 开关保持关闭（本次事故开关）。

## 9. 回滚 / 重开指引

- 回滚：git revert + 恢复 yaml 模板（git 历史有）+ 各环境 workspace 文件。
- 重开 metric-classification（配合重开自进化）：
  1. `harness.skills.auto-synth.enabled=true`（bean 全部回归）；
  2. 恢复 metric-categories.yaml 到运行时 workspace/knowledge/（缺失时回退内置默认也可用）；
  3. **先修 SkillCandidateRepository.incrementHit 的 SQL 方言**（ON DUPLICATE KEY UPDATE /
     VALUES(col) 在非 MySQL 兼容模式的 gauss 上必报 syntax error，见
     `docs/202611/skill自动蒸馏计数链路解析与incrementHit报错.md` §4）。

## 10. 涉及文件清单

| 文件 | 角色 |
|---|---|
| `src/main/resources/workspace/knowledge/metric-categories.yaml` | 待删模板 |
| `v2/skills/MetricClassificationService.java` | 唯一读者；bean 条件化 |
| `v2/skills/FingerprintCalculator.java:58,114-127` | ruleBasedTag 调用点，null 容忍 |
| `v2/skills/SkillDistiller.java:304` | getMetricHint 消费点 |
| `v2/hooks/SkillSynthesisHook.java:126` | classifyAndUpdateAsync 调用点 |
| `v2/config/V2SkillConfig.java:328,386,414,431,454` | bean 级联，全部条件化 |
| `v2/config/V2DimensionConfig.java:48-51` | light-classifier 活跃消费方（**不动**） |
| `v2/config/HarnessAgentPartsConfig.java:189-193` | hook 挂载，null 容忍（不动） |
| `resources/application.properties:131-136` | 双保险行删除 + 注释更新 |
| `v2/config/V2DigestionConfig.java:125-127` | digestion 引用（不装配，不动） |
| `docs/202611/skill自动蒸馏计数链路解析与incrementHit报错.md` | 姊妹文档（链路解析 + SQL 方言 bug） |
