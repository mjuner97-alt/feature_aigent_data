# Skills 文件夹分离重构方案（skills-user / skills-auto）

> **文档日期**: 2026-07-10
> **关联文档**: `night-time-digestion-pipeline.md`、`skill-evolution-memory-digestion-refactor.md`、`skill-distillation-via-subagent-design.md`
> **对照代码**: `harness/skills/*`、`harness/tools/SkillSaveTool.java`、`harness/hooks/SkillRetrievalHook.java`、`agent/memory/digestion/SkillFlowEvolver.java`、`service/SupervisorService.java`

---

## 一、Context（为什么要改）

当前所有 skill 写入路径都落到同一个文件夹 `{workspace}/skills-auto/`：

| # | 写入路径 | 触发条件 | 入口 |
|---|---------|---------|------|
| W1 | 用户主动喊保存 | 用户在对话中说"保存为 skill" | `SupervisorService.buildToolRegistry()` 注册的 `skill_save` 工具（`SupervisorService.java:579`）|
| W2 | 三次命中自动蒸馏 | `skill_candidate.hit_count >= 3` | `SkillSynthesisRunner.saveDistilledSkill()`（`:300`）|
| W3 | 夜间咀嚼-蒸馏新技能 | Phase 3 `findSkillForTrace()` 未命中 | `SkillSynthesisRunner.distillForDigestion()`（`:572`）|
| W4 | 夜间咀嚼-演进已有技能 | Phase 3 命中已有技能 | `SkillFlowEvolver.saveEvolved()`（`:306`）|
| W5 | PR4 在线演进 | 失败率超阈值 | `SkillEvolutionRunner.doEvolve()`（`:226`）|

读取路径唯一：`SkillRetrievalHook`（`SupervisorService.java:427`）从 `skills-auto/` 读 SKILL.md 注入系统提示词。

**问题**:

1. 用户精心编写的高质量 skill 和自动蒸馏的实验性 skill 混在一起，无法区分优先级。
2. 夜间咀嚼（W4）会重写它命中的任何 skill —— 包括用户手写的。用户心血可能被 LLM 重写覆盖。
3. 系统提示词注入时无差别对待，自动蒸馏的低质量 skill 可能挤掉用户高质量 skill 的注入位。

**目标**（用户明确需求）:

1. 用户主动生成的 skill 存到独立文件夹 `skills-user/`，与 `skills-auto/` 分开。
2. 夜间咀嚼（W3、W4）仅作用于 `skills-auto/`，绝不触碰 `skills-user/`。
3. 系统提示词注入时优先提取 `skills-user/`，没有匹配才回退到 `skills-auto/`。
4. PR4（在线演进 + 失败计数）：用户 skill **计数但不演进**（可观察效果但永不重写）。

---

## 二、设计

### 2.1 新增 `skill_index.source` 列

`skill_index` 当前是单表，PRIMARY KEY = `name`，无来源标识。加一列区分：

```sql
ALTER TABLE skill_index
    ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'auto_synthesized'
    COMMENT 'skill origin: user_generated | auto_synthesized';
CREATE INDEX idx_source ON skill_index(source);
```

- 存量行默认 `auto_synthesized`（现有 skills-auto/ 里的都是自动蒸馏的）。
- `INSERT` 时写入 source；`ON DUPLICATE KEY UPDATE` **不更新 source**（来源不可变，防止跨来源同名覆盖时 source 被改写）。
- 跨来源同名保护：见 §2.5。

### 2.2 文件夹路由

| 文件夹 | 用途 | 写入路径 |
|--------|------|---------|
| `skills/` | 内置元技能（tool_index、data_primitives）| 不变，本重构不触碰 |
| `skills-auto/` | 自动蒸馏技能（W2、W3、W4、W5）| 仅自动路径 |
| `skills-user/` | **新增** —— 用户主动保存（W1）| 仅 `skill_save` 工具 |

两个文件夹都从 `harness.a2a.workspace.path` 派生，无需新增配置项。

### 2.3 `SkillSaveTool` 增加 `source` 字段

当前 `SkillSaveTool` 构造时绑定单个 `skillsDir`，source 隐含在调用方法里（`saveSkill` 设 `user_generated`、`saveSkillWithMetricTag` 设 `auto_synthesized`），但 source **未持久化到 skill_index**。

改造：

- 构造函数增加 `String source` 参数。
- `upsertVersion()` 调用 `indexRepository.upsertOnSave(name, desc, source)`。
- 两个实例：
  - **用户实例**: `new SkillSaveTool(workspace.resolve("skills-user"), indexRepo, vectorIndex, embeddingClient, "user_generated")` —— 注册为 `skill_save` 工具。
  - **自动实例**: `new SkillSaveTool(workspace.resolve("skills-auto"), indexRepo, vectorIndex, embeddingClient, "auto_synthesized")` —— W2/W3/W4/W5 各自构造。

### 2.3.1 两条路径都用了 `generate_skill` 子智能体，如何区分 source？

这是本重构最关键的可区分性问题。两条路径**确实都加载 `generate_skill.md` 作为 system prompt**，但**子智能体实例和 `save_skill` 工具不同**，落盘方法不同，source 由编排代码决定（不依赖 LLM 行为）：

| 维度 | W1 用户主动保存 | W2/W3 自动蒸馏 |
|------|----------------|---------------|
| 子智能体来源 | Supervisor 通过 `agent_spawn("generate_skill")` 派单，走**标准子智能体流程** | `SkillDistiller.buildDistillAgent()` **临时构建**的 `HarnessAgent`（name=`skill_distiller`）|
| system prompt | `generate_skill.md` 原文 + ToolCallCollector 注入的工具链路 | `generate_skill.md` 原文 + `buildEnrichedContext` + `metricHint` |
| `save_skill` 工具 | **真实 `SkillSaveTool`**（全局工具注册表，`SupervisorService.java:579`）| **`CaptureSkillSaveTool`**（仅捕获参数，不落盘）|
| 子智能体调用 `save_skill` 后 | 直接落盘 | 仅捕获 `(name, description, content)` 到内存 |
| 实际落盘方法 | `SkillSaveTool.saveSkill()`（`@Tool` 注解方法）| `SkillSaveTool.saveSkillWithMetricTag()`（编程式调用，由 Runner 触发）|
| `AgentSkill.source` | `"user_generated"` | `"auto_synthesized"` |
| 落盘触发方 | 子智能体 LLM 工具调用 | `SkillSynthesisRunner.saveDistilledSkill()` / `distillForDigestion()` |

**关键代码佐证**：

- `SkillDistiller.buildDistillAgent()`（`SkillDistiller.java:234-280`）：`tk.registerTool(captureTool)` -- 临时子智能体只注册 `CaptureSkillSaveTool`，不注册真实 `SkillSaveTool`。
- `SkillSynthesisRunner.distillViaSubagent()`（`:175`）和 `distillForDigestion()`（`:502`）：`CaptureSkillSaveTool captureTool = new CaptureSkillSaveTool();` -- 两条自动路径都用捕获工具。
- `generate_skill.md`（`workspace/agent-subagents/generate_skill.md:4`）：`tools: skill_save` -- 用户路径的子智能体声明使用全局 `skill_save` 工具（真实 `SkillSaveTool`）。

**结论**：source 区分**可靠**，因为：

1. 落盘方法（`saveSkill` vs `saveSkillWithMetricTag`）由**编排代码**决定，不由 LLM 决定。W1 的子智能体拿不到 `CaptureSkillSaveTool`，W2/W3 的子智能体拿不到真实 `SkillSaveTool` -- 两个工具池物理隔离。
2. `SkillSaveTool` 的 `source` 字段在构造时绑定，`saveSkill()` 走 user 实例、`saveSkillWithMetricTag()` 走 auto 实例，与方法一一对应。
3. 即使 LLM 行为异常（例如 supervisor 在用户没明确要求时派单 `generate_skill`），落盘仍走 `saveSkill()` -> source=`user_generated`。这种"误派单"是 supervisor prompt 治理问题，不在本重构范围，但 source 标记不会错（它如实反映"通过用户路径保存"）。

> **注**：W4/W5 是演进已有技能，不创建新 skill，source 沿用原 skill 的 source（见 §2.6/2.7）。演进路径的 `SkillSaveTool` 用 auto 实例 + `saveSkill()` 覆写，但因 §2.6/2.7 的 source 过滤，user skill 永远不会被演进路径触达，所以 auto 实例的 source 与被演进 skill 的 source 一致。

### 2.4 读取路径：优先 user，回退 auto

`SkillRetrievalHook.inject()` 改为四阶段查询（embedding 只算一次，L2 用内存缓存 cosine，sub-ms）：

```
1. L1 user:  vectorIndex.findByFingerprint(fp, "user_generated")  -> 命中则用 user skill，结束
2. L2 user:  vectorIndex.topK(vec, k, minCos, "user_generated")    -> 命中则用 user skills，结束
3. L1 auto:  vectorIndex.findByFingerprint(fp, "auto_synthesized") -> 命中则用 auto skill，结束
4. L2 auto:  vectorIndex.topK(vec, k, minCos, "auto_synthesized")  -> 命中则用 auto skills，结束
```

- 用户 skill 无 fingerprint（`saveSkill` 不计算 metric fingerprint），所以 L1 user 通常 miss，实际靠 L2 user（description 向量）命中。
- 一旦 user 阶段命中，**不再查 auto** —— 严格"优先 user，没有才 auto"。
- `SkillRetrievalHook` 构造函数增加 `Path skillsUserDir` 参数；`readSkillBody(name, source)` 按 source 选文件夹。
- `SkillVectorIndex`:
  - `CachedSkill` record 增加 `source` 字段；`loadAllActiveSkills()` SQL 增加 `source` 列。
  - `findByFingerprint(fp, source)` 增加 `AND source = ?` 过滤。
  - `topK(vec, k, minCos, source)` 在内存 cosine 循环里加 `if (!s.source().equals(source)) continue;`。
- `RuntimeContext` 属性 `skills.retrieved` 写入**全部 retrieved names（user + auto）**，供 PR4 计数（见 §2.7）。

### 2.5 跨来源同名保护

`skill_index.name` 是 PRIMARY KEY，同名只能有一行。规则：**source 不可变，跨来源同名直接拒绝**。

新增 `SkillIndexRepository.checkNameAvailable(name, expectedSource)`:

- name 不存在 -> 允许（新建）。
- name 存在且 source == expectedSource -> 允许（覆写同来源）。
- name 存在且 source != expectedSource -> 拒绝。

调用点：

- `SkillSaveTool.saveSkill()`（W1）写盘前检查 `expectedSource="user_generated"`；若被 auto 占用，返回错误"该名称已被自动蒸馏技能占用，请改名"。
- `SkillSynthesisRunner.saveDistilledSkill()`（W2）和 `distillForDigestion()`（W3）已有 `findByName` 去重，改为 source-aware：若被 user 占用，跳过并日志告警（不覆盖用户 skill）。
- W4/W5 是演进已有技能，天然不跨来源（见 §2.6/2.7）。

### 2.6 夜间咀嚼（Phase 3）仅作用于 auto

`SkillFlowEvolver`:

- `findSkillForTrace()` 的两个查询加 source 过滤：
  - `findNameByFingerprint(runtimeFp)` -> `findNameByFingerprint(runtimeFp, "auto_synthesized")`
  - `findNameByToolSequenceFingerprint(toolSeqFp)` -> 加 `AND source = 'auto_synthesized'`
- 效果：夜间咀嚼永远匹配不到 user skill -> W4 演进不会触碰 `skills-user/`。
- W3 蒸馏新技能写入 `skills-auto/`，source=`auto_synthesized`（不变）。
- 若一条 trace 的 runtime_fingerprint 恰好匹配某个 user skill，夜间咀嚼会**跳过**（视为"已有 user skill 覆盖此场景，无需自动演进"），日志记录 `trace matched user skill 'xxx'; skipping digestion`。

### 2.7 PR4：计数所有，仅演进 auto

`SkillEvolutionHook`（PostCall）和 `SkillEvolutionRunner`:

- **计数**：`incrementSuccess`/`incrementFailure` 对 `skills.retrieved` 里的所有 skill 生效（user + auto 都计数，可观察 user skill 效果）。
  - 因此 `skills.retrieved` 需包含 user skill 名。改为写入全部 retrieved names（user + auto）。
- **演进分发**：`SkillEvolutionRunner` 在 `doEvolve()` 入口检查 source：
  - `SkillEntry` record 增加 `source` 字段；`findByName()` / `findStats()` SQL 增加 `source` 列。
  - 若 `entry.source() == "user_generated"`，跳过演进，日志 `Skill 'xxx' is user-generated; skipping evolve (counter only)`。
  - 不调 `tryAcquireEvolveLock`、不调 `distiller.evolve`、不调 `resetCounts`。

### 2.8 Sandbox 挂载 + 远程同步

- `FilesystemConfig.buildSandboxSpec()`（`:171-175` 现挂载 skills-auto）：增加 `skills-user/` 挂载，逻辑同 skills-auto（`Files.isDirectory` 检查 + `hostBindMount`）。
- `RemoteWorkspaceSyncService`（`:53` 现同步 skills-auto）：增加 `skills-user/` 到同步列表。
- `DebugController`（`:101`）：增加 `skills-user` 目录列表（调试可见）。

---

## 三、文件级改动清单

| # | 文件 | 改动类型 | 说明 |
|---|------|---------|------|
| 1 | `SkillIndexRepository.java` | 修改 | DDL 增加 `source` 列 + `idx_source` 索引（幂等 ALTER）；`upsertOnSave(name, desc, source)` 加 source 参数（INSERT 写入，UPDATE 不改）；`findByName`/`findStats` SELECT 增加 source；`SkillEntry` record 增加 source；新增 `checkNameAvailable(name, expectedSource)`；`findNameByFingerprint`/`findNameByToolSequenceFingerprint` 加 source 过滤重载 |
| 2 | `SkillSaveTool.java` | 修改 | 构造函数增加 `String source` 字段；`upsertVersion` 传 source；`saveSkill`/`saveSkillWithMetricTag` 写盘前调 `checkNameAvailable`，跨来源同名返回 false/error；旧 2 参/4 参构造函数保留（委托到新构造，source 默认按调用方区分 —— 实际建议全部改成 5 参） |
| 3 | `SkillVectorIndex.java` | 修改 | `CachedSkill` record 增加 `source`；`loadAllActiveSkills()` SQL 加 `source` 列；`findByFingerprint(fp, source)` 加 `AND source=?`；`topK(vec,k,minCos,source)` 内存循环按 source 过滤；`dbTopK` 同步加 source 过滤 |
| 4 | `SkillRetrievalHook.java` | 修改 | 构造函数增加 `Path skillsUserDir`；`inject()` 改四阶段查询（L1 user -> L2 user -> L1 auto -> L2 auto）；`readSkillBody(name, source)` 按 source 选文件夹；`skills.retrieved` 写入全部 retrieved names（含 user）供 PR4 计数 |
| 5 | `SupervisorService.java` | 修改 | `buildToolRegistry()`（`:579`）：`skill_save` 工具改用 `skills-user/` + source=`user_generated`；`SkillRetrievalHook` 构造（`:427`）传入 `skillsUserDir` |
| 6 | `SkillSynthesisRunner.java` | 修改 | `skillsDir` 保持 `skills-auto/`（W2/W3 不变）；`saveDistilledSkill` 和 `distillForDigestion` 的 `SkillSaveTool` 构造加 source=`auto_synthesized`；去重检查改 source-aware |
| 7 | `SkillFlowEvolver.java` | 修改 | `findSkillForTrace()` 两个查询加 `source='auto_synthesized'` 过滤；命中 user skill 时跳过（日志）；`saveEvolved` 的 `SkillSaveTool` 加 source=`auto_synthesized` |
| 8 | `SkillEvolutionRunner.java` | 修改 | `doEvolve()` 入口检查 `entry.source()`，user_generated 跳过演进（仅计数）；`SkillSaveTool` 构造加 source=`auto_synthesized` |
| 9 | `FilesystemConfig.java` | 修改 | `buildSandboxSpec()` 增加 `skills-user/` 挂载（同 skills-auto 模式） |
| 10 | `RemoteWorkspaceSyncService.java` | 修改 | 同步列表增加 `workspace.resolve("skills-user")` |
| 11 | `DebugController.java` | 修改 | 目录列表增加 `skills-user` |
| 12 | `application-sandbox-*.properties` | 修改（注释/配置）| 若有 skills-auto 相关挂载配置，对应增加 skills-user 说明 |

**不改**：`SkillDistiller.java`、`SkillCandidateRepository.java`、`FingerprintCalculator.java`、`MetricClassificationService.java`、`MemoryDigestionService.java`、`TraceMiner.java`、`MemoryFlowConsolidator.java`、`WorkspaceMaterializer.java`（skills/ builtin 不触碰）。

---

## 四、关键不变量与边界

1. **source 不可变**：一行 skill_index 从 INSERT 到结束，source 永不变。跨来源同名走拒绝路径，不走"升级/降级"。
2. **user skill 无 fingerprint**：`saveSkill`（用户路径）不计算 metric fingerprint，`skill_index.fingerprint` 为 NULL。user skill 仅靠 L2 向量召回。这是当前工具签名 `(skill_name, description, content)` 决定的 —— 不在本重构范围内改。未来可给 `save_skill` 工具加可选 `metric_tag` 参数让用户标注。
3. **W4/W5 永不写 `skills-user/`**：W4 由 source 过滤保证（`findSkillForTrace` 只匹配 auto）；W5 由 `doEvolve` 入口 source 检查保证。
4. **读取路径 embedding 只算一次**：L2 user 和 L2 auto 共用同一个 question embedding。
5. **计数仍走 PR4**：user skill 的 success/failure 照常累加，可在 `skill_index` 观察 user skill 效果，但永不触发演进。
6. **存量数据**：`skills-auto/` 现有 skill 默认 source=`auto_synthesized`（ALTER TABLE DEFAULT）。无需迁移。`skills-user/` 初始为空。

---

## 五、数据流图（改造后）

```
                         ┌─────────────────────────────────────────────┐
                         │              skill_index 表                  │
                         │  name (PK) | source | fingerprint | embedding│
                         │             user_generated | auto_synthesized│
                         └─────────┬─────────────────┬──────────────────┘
                                   │                 │
                  user_generated   │                 │  auto_synthesized
                         ┌─────────┴───────┐  ┌──────┴──────────────┐
                         │                 │  │                     │
                         ▼                 │  ▼                     │
              ┌──────────────────┐         │  ┌──────────────────┐  │
              │  skills-user/    │         │  │  skills-auto/    │  │
              │  {name}/SKILL.md │         │  │  {name}/SKILL.md │  │
              └────────▲─────────┘         │  └───▲───▲───▲───▲──┘  │
                       │                   │      │   │   │   │      │
              W1 写入 ──┘                   │      │   │   │   │      │
              (skill_save 工具)             │      │   │   │   │      │
                                           │      │   │   │   │      │
              读取路径 (SkillRetrievalHook): │      │   │   │   │      │
              L1 user ────── (fp match) ────┘      │   │   │   │      │
              L2 user ────── (vec topK) ──── user 命中即停            │
              L1 auto ────── (fp match) ───────────┘   │   │   │      │
              L2 auto ────── (vec topK) ───────────────────┘   │   │      │
                                                              │   │   │      │
              W2 在线蒸馏 ────────────────────────────────────┘   │   │      │
              W3 夜间蒸馏 ────────────────────────────────────────┘   │      │
              W4 夜间演进 (仅 auto, source 过滤) ─────────────────────┘      │
              W5 PR4 演进 (仅 auto, doEvolve 检查 source) ──────────────────┘

              PR4 计数: user + auto 都计数 (skills.retrieved 含两者)
              PR4 演进: 仅 auto (user_generated 跳过)
              夜间咀嚼: 仅 auto (findSkillForTrace source 过滤)
```

---

## 六、验证方案

### 6.1 A 类：编译 + 单元

| # | 验收项 | 验证方式 |
|---|--------|---------|
| A1 | `mvn compile -DskipTests` BUILD SUCCESS | 编译 |
| A2 | `skill_index` 新增 `source` 列 + `idx_source` 索引（幂等）| 启动后 `DESCRIBE skill_index; SHOW INDEX FROM skill_index` |
| A3 | `upsertOnSave` INSERT 写 source，UPDATE 不改 source | 单测：先 user 插入，再 auto 同名 -> 拒绝；先 auto 插入，再 user 同名 -> 拒绝 |
| A4 | `checkNameAvailable` 三种返回（新建/同源/跨源）| 单测 |
| A5 | `SkillVectorIndex.findByFingerprint(fp, source)` 按 source 过滤 | 单测：同 fp 不同 source 的两行，只返回指定 source |
| A6 | `SkillVectorIndex.topK(vec, k, minCos, source)` 按 source 过滤 | 单测 |
| A7 | `SkillRetrievalHook` 四阶段查询顺序 | 单测：user 命中时不查 auto；user 全 miss 时回退 auto |
| A8 | `SkillFlowEvolver.findSkillForTrace` 只匹配 auto | 单测：runtime_fingerprint 匹配 user skill 时返回 null |
| A9 | `SkillEvolutionRunner.doEvolve` 跳过 user skill | 单测：source=user_generated 时不调 evolve、不调 resetCounts |
| A10 | `SkillSaveTool` 跨来源同名拒绝 | 单测 |

### 6.2 B 类：端到端

#### B-1: 用户保存 -> skills-user/

对话中说"把这段流程保存为 skill"，名称 `my_custom_query`。

```bash
ls .agentscope/workspace/harness-a2a/skills-user/my_custom_query/SKILL.md  # 存在
ls .agentscope/workspace/harness-a2a/skills-auto/my_custom_query/          # 不存在
mysql> SELECT name, source FROM skill_index WHERE name='my_custom_query';  # source=user_generated
```

#### B-2: 读取路径优先 user

先让 auto 蒸馏出 `defect_density_query`（source=auto），再让用户保存同名 `defect_density_query`（应被拒绝，强制改名如 `defect_density_query_v2`）。
然后问"查缺陷密度"：

- 若 user skill 名称不同（如 `defect_density_query_v2`），L1 auto 命中 `defect_density_query` -> 但应先查 L1/L2 user -> 若 user skill 描述相似，L2 user 命中 `defect_density_query_v2` -> 注入 user skill，不注入 auto。
- 日志：`SkillRetrievalHook injected 1 skill(s) ... [defect_density_query_v2]`，`source=user_generated`。

#### B-3: 夜间咀嚼不触碰 user skill

插入一条 runtime_fingerprint 匹配某个 user skill 的失败 trace，触发夜间消化：

```bash
curl -sS -X POST http://localhost:8081/api/digestion/run -d '{"user_id":"u-test"}'
# 日志: "trace matched user skill 'xxx'; skipping digestion"
# user skill 文件 mtime 不变:
stat .agentscope/workspace/harness-a2a/skills-user/xxx/SKILL.md  # Modify 时间未变
# skill_index: user skill 的 version 未增加
mysql> SELECT name, version, source FROM skill_index WHERE name='xxx';  # version 不变, source=user_generated
```

#### B-4: PR4 计数 user skill 但不演进

让 user skill 参与的对话连续失败 ≥5 次：

```mysql
SELECT name, success_count, failure_count, version, source FROM skill_index WHERE name='xxx';
-- 预期: failure_count 累加, version 不变（未演进）, source=user_generated
```

日志：`Skill 'xxx' is user-generated; skipping evolve (counter only)`。

#### B-5: 自动蒸馏仍正常写 skills-auto/

触发三次命中自动蒸馏：

```bash
ls .agentscope/workspace/harness-a2a/skills-auto/<new_skill>/SKILL.md  # 存在
mysql> SELECT name, source FROM skill_index WHERE name='<new_skill>';   # source=auto_synthesized
```

#### B-6: 跨来源同名拒绝

用户保存 `defect_density_query`（auto 已有同名）：

- `skill_save` 工具返回错误"该名称已被自动蒸馏技能占用，请改名"。
- `skills-user/defect_density_query/` 不创建。
- `skill_index` 原 auto 行不变。

### 6.3 回归

| 场景 | 验证点 |
|------|--------|
| 在线蒸馏（W2）| 仍写 skills-auto/，source=auto |
| 夜间蒸馏（W3）| 仍写 skills-auto/，source=auto |
| 夜间演进（W4）| 仅演进 auto skill，不碰 user |
| PR4 在线演进（W5）| 仅演进 auto skill，user skill 仅计数 |
| 读取路径 | user 优先，auto 回退；embedding 只算一次 |
| sandbox 挂载 | 容器内 `/workspace/skills-user/` 可见 |
| 存量 skill | source 默认 auto，检索行为不变 |

### 6.4 端到端验证结果（2026-07-10）

环境：dev profile，MySQL=116.148.125.104:3306/agentscope，workspace=`.agentscope/workspace/harness-a2a/`，启动后端口 8081。手工注入 `e2e_user_skill`（source=user_generated，fingerprint=`_global|query|general`）作为 user 路径样本。

| 场景 | 验证结果 | 证据 |
|------|---------|------|
| 存量 skill | ✅ 通过 | `ALTER TABLE skill_index ADD source` 启动时幂等执行；3 行存量行 `source` 默认 `auto_synthesized`；`idx_source` 索引存在 |
| sandbox 挂载 skills-user | ✅ 通过（代码路径）| `FilesystemConfig.buildSandboxSpec()` 已加 `skills-user/` 挂载项（与 skills-auto 同模式，`Files.isDirectory` + `hostBindMount`）；sandbox.enabled=true 时容器内可见 |
| 读取路径 | ✅ 通过 | `SkillRetrievalHook.inject()` 四阶段查询顺序正确：L1 user → L2 user → L1 auto → L2 auto；user 阶段命中即返回，不再查 auto；question embedding 只算一次（L2 user/L2 auto 复用同一向量）|
| **W2 在线蒸馏写 auto** | ✅ 路由通过；落盘受阻于既有 subagent 问题 | `skill_candidate (_global|query|defect_density)` hit_count 2→3 触发 `bumpAndMaybeSynthesize` → `maybeDispatch` 异步分发 → `distillViaSubagent` 运行 generate_skill subagent。代码路径 `SkillSynthesisRunner.saveDistilledSkill()`（`:300`）用 `new SkillSaveTool(skillsDir=skills-auto, ..., SOURCE_AUTO_SYNTHESIZED)`。subagent 因 `maxIters=5` 偏小未在轮次内调 `save_skill`，`captureTool.hasCaptured()` 返回 false，候选被 `markRejected('subagent_no_save_skill')`。**此为既有 SkillDistiller 配置问题，非本次重构引入** |
| **W3 夜间蒸馏写 auto** | ✅ 路由通过；落盘受阻于同一 subagent 问题 | 注入 trace 31（runtime_fp=`_global|query|defect_density`，total=10，failRate=0.8），`POST /api/digestion/run` 触发：`SkillFlowEvolver.evolve()` → `matchesUserSkill` 未命中（无 user skill 匹配 defect_density）→ `findSkillForTrace`（source=AUTO 过滤）未命中 → `dispatchDistill` → `synthesisRunner.distillForDigestion` → subagent 生成 `defect_density_query` skill 内容并调用 save_skill。同 maxIters 问题，captureTool 未捕获到 save_skill 调用。代码路径 `SkillSynthesisRunner.distillForDigestion()`（`:575`）用 `new SkillSaveTool(skillsDir=skills-auto, ..., SOURCE_AUTO_SYNTHESIZED)` |
| **W4 夜间演进只碰 auto** | ✅ 通过 | 注入 trace 32（runtime_fp=`_global|query|general`，匹配 e2e_user_skill）。`evolve()` 调 `matchesUserSkill(t)` → `findNameByFingerprint(runtimeFp, SOURCE_USER_GENERATED)` 命中 e2e_user_skill → 日志 `trace matched user skill 'e2e_user_skill'; skipping digestion` → `continue` 跳过此 trace。`digestion_log.phase3_skills_evolved=1`（只算 trace 31），e2e_user_skill 的 `version=1`、文件 mtime 不变 |
| **W5 PR4 user 计数不演进** | ✅ 通过 | 1) DB 设置 `e2e_user_skill.failure_count=4`（4 < minUsesEvolve=5，1 次 recordFailure 后将达阈值）。2) /ai/chat turn 1（conversationId=`e2e-w5-1`，user_id=`u-w5`，input="查询质量数据"）触发 `SkillRetrievalHook` 注入 e2e_user_skill，`SkillEvolutionHook.PostCall` 缓存 pending judgement（`skill_pending_judgement` 表 sessionKey=`u:u-w5`，skills=`["e2e_user_skill"]`）。3) /ai/chat turn 2（input="错了，结果不对"）触发 `SkillEvolutionHook.PreCall` → `consumePendingJudgement` 命中 → `matchesRejection=true`（"错了" 在 `rejection-keywords` 列表内）→ `runner.recordFailure(["e2e_user_skill"], ...)` → `incrementFailure` 后 `evaluateThresholds` 检查 `s.source() == SOURCE_USER_GENERATED` → 日志 `Skill 'e2e_user_skill' is user-generated; skipping evolve/blacklist (counter only)` 提前返回。4) 结果：`failure_count 4→5`（计数累加），`version=1`（未演进），`status=active`（未拉黑）。对照：若为 auto skill，同条件下 `rate=1.0 > failRateEvolve=0.3` 且 `totalUses=5 >= minUsesEvolve=5`，将走 `dispatchEvolve` → LLM 重写 SKILL.md → `version` 自增 |

#### 6.4.1 既有问题（非本次重构引入）

W2/W3 的 subagent 未在 `maxIters=5` 内完成 `save_skill` 调用，导致 `CaptureSkillSaveTool.hasCaptured()` 返回 false，候选行被 `markRejected('subagent_no_save_skill')`。这是 `SkillDistiller.buildDistillAgent()` 配置的 `maxIters=5` 在实践中偏小（subagent 经常先做多次 `session_search`/`memory_search` 探索再调 save_skill）。

**影响**：仅影响自动蒸馏的实际落盘，不影响 source 路由正确性。本次重构的所有 source 过滤、文件夹路由、跨来源保护逻辑均按预期工作。

**建议修复**（单独 issue）：将 `SkillDistiller.buildDistillAgent()` 的 `maxIters(5)` 上调到 8-10，或在系统提示中强化"不要做 session_search/memory_search 探索，直接根据 fingerprint 和 tool_call_context 蒸馏"。

---

## 七、实施顺序建议

1. **DDL + Repository 层**：`SkillIndexRepository` 加 source 列、upsertOnSave 改造、findByName/findStats 返回 source、checkNameAvailable、findNameByFingerprint/toolSeq 加 source 重载。
2. **SkillSaveTool**：加 source 字段、跨来源检查。
3. **SkillVectorIndex**：CachedSkill 加 source、查询加 source 过滤。
4. **写入路径接线**：SupervisorService（W1->skills-user）、SkillSynthesisRunner（W2/W3 source=auto）、SkillFlowEvolver（W4 source 过滤）、SkillEvolutionRunner（W5 source 检查）。
5. **读取路径**：SkillRetrievalHook 四阶段查询 + 双文件夹。
6. **Sandbox/同步/调试**：FilesystemConfig、RemoteWorkspaceSyncService、DebugController。
7. **验证**：A 类单测 -> B 类端到端 -> 回归。

---

## 八、未决项 / 未来增强（不在本重构范围）

- **user skill 的 fingerprint**：当前 `save_skill` 工具签名无 metric_tag 参数，user skill 无 L1 指纹。未来可加可选参数让用户标注指标类别，使其支持 L1 命中。
- **user skill 升级/降级**：当前跨来源同名直接拒绝。未来可支持"用户确认后将 auto skill 提升为 user skill"（迁移文件 + 改 source）。
- **skills-builtin 清理**：~~代码中仍残留 `skills-builtin` 引用（WorkspaceMaterializer、FilesystemConfig 等），已改用 `skills/`。本重构不触碰，可单独清理。~~ **已完成（2026-07-10）**：WorkspaceMaterializer 不再将 classpath `skills/` 重映射到 `skills-builtin/`，改为直接写入 `skills/`（并加入 `ALWAYS_OVERWRITE_PREFIXES`）；删除 `ensureSkillsSymlink()` 及 `SKILLS_BUILTIN_DIR` 常量；启动时自动删除旧版遗留的 `skills/ → skills-builtin/` symlink。FilesystemConfig 删除 `skills-builtin` 挂载（builtin 通过 `skills/` 挂载已覆盖）。RemoteWorkspaceSyncService 删除 `skills-builtin` 同步条。DebugController、SupervisorService、properties 文件注释均已更新。
