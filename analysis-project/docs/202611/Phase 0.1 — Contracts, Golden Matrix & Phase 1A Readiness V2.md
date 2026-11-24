# Phase 0.1 — Contracts, Golden Matrix & Phase 1A Readiness V2

> **文档地位**：本文档与《Phase 0.1 — Architecture & Decision Baseline V2》共同构成 Phase 0.1 **唯一权威实施基线**。本文档定义全部可实施契约、Golden Matrix 判定矩阵与 Phase 1A（1A-0 ~ 1A-12）实施顺序。架构决策与不变式的论证见 Document 1。
>
> **版本**：V2（2026-11-24，按修订版任务书从零重建）
> **状态**：PHASE 0.1 — CANONICAL CONTRACT BASELINE
> **历史文档**：《Phase 0.1 Revised Architecture Decision & Acceptance Contract》《Phase 1A — Context-Aware Follow-up Guard》降级为历史审计材料，其冻结语义已完整并入本文档与 Document 1，读者无需翻阅旧文档。
> **架构定位**：主路径（NEW_REQUEST）现役架构 = **Rule-Based Fast Path Router + Direct Probe + Evidence-Augmented Agent Fallback**。旧 LLM-first Request Decomposition 与主路径 Direct Feasibility Guard 已废弃，仅历史保留。追问层（LEGACY_FOLLOWUP）Guard（原 §7.5 Option C）原样保留。
> **范围**：本文档为纯文档任务产物，不修改业务代码，不提前实现 Router / Probe / Agent 改造，不改变运行时行为。

---

# 1. Contract Overview

本文档的契约分为四层：

| 层 | 契约（§） | 性质 |
|---|---|---|
| 感知层 | Router Input（§2）、Request Shape（§3）、Metric（§4）、Built-in Dimension（§5）、Dynamic Dimension（§6）、Dimension Relationship（§7）、Objective（§8）、Direct Plan Coverage（§9） | 确定性事实提取与形态建模，禁止 LLM、禁止猜测 |
| 决策层 | Router Decision（§10）、Confidence（§11）、Reason Codes（§12）、Direct Fast Path（§13） | 确定性规则路由，Hard Coverage Constraints 优先于 Confidence |
| 执行层 | Probe Eligibility（§14）、Probe Boundaries（§15）、Probe Side-Effect Isolation（§16）、Probe Result（§17）、Evidence（§18）、Evidence Reuse（§19）、Agent Context（§20）、Agent Completion（§21）、Whole-Request Completion（§22） | 有界验证与补全执行 |
| 状态层 | Snapshot（§23）、Pending（§24）、PlanOrigin（§25）、Multi-Skill G1–G7（§26）、V1/V2 Parity（§27）、Shadow（§28）、Metrics（§29） | 冻结状态机与部署契约 |

**全局不变式（所有契约的共同前提）**：

- I-1 Router 全程确定性：无 LLM 调用、无伪概率、无黑盒打分。
- I-2 禁止硬编码维度/指标目录：所有识别依据来自真实配置源（§2.2）。
- I-3 Request Completion 以原始用户 Request 为基准，不以 Direct Plan、Skill 成功或 Probe 成功为基准。
- I-4 Probe Success ≠ Request Completion；Partial Direct Result ≠ Request Completion。
- I-5 Probe 非提交性执行：不污染 Snapshot / Pending / PlanOrigin / 正式执行历史。
- I-6 Multi-Skill Direct Execution（G1–G7 及其全部语义项）为 Frozen Architecture，Router 不得重构其内部规则。
- I-7 规则冲突或 Router 内部异常一律 fail-closed：绝不进入 Fast Path。
- I-8 日志/指标不得记录原始用户请求、敏感用户标识或不必要敏感内容；原因码/指标标签为低基数枚举。
- I-9 具体数据结构（DTO/字段）以现有代码模型为依据确定，架构文档中的概念模型不要求机械复制为代码。
- I-10 未知维度不得拒绝执行：标记 Unknown/Uncertain Dimension Signal 交给 Probe/Agent 处理。

---

# 2. Router Input Contract

## 2.1 输入清单

Router 决策前可读取且仅可读取以下输入：

| # | 输入 | 来源 | 用途 |
|---|---|---|---|
| IN-1 | 原始用户请求文本 | 当前轮请求 | 形态分析主依据 |
| IN-2 | 相关会话上下文 | 现有会话状态（经既有注入规则） | 指代消解、Pending 归属 |
| IN-3 | DimensionState Projection | 现有 DimensionStateManager | 仅作参考信号之一（见 §3.4 投影限制） |
| IN-4 | PlanOrigin | 现有显式标记（NEW_REQUEST / PENDING_CONTINUATION / LEGACY_FOLLOWUP） | 路由适用范围判定 |
| IN-5 | Direct Plan | planAll / planSingle / planMulti 现有输出 | 覆盖评估的核心事实 |
| IN-6 | 确定性配置源 | 见 §2.2 | 指标/维度识别依据 |
| IN-7 | 端点标识 | V1 / V2 | 模式与指标维度 |
| IN-8 | 运行模式 | OFF / SHADOW / ENFORCEMENT（端点维度配置） | 生效路径判定 |

## 2.2 确定性配置源（禁止硬编码目录的唯一豁免依据）

Router 的指标/维度识别必须复用以下既有确定性来源，**禁止在代码或配置中新建硬编码的维度/指标目录**：

| # | 来源 | 已验证代码事实 |
|---|---|---|
| S-1 | `dimension_key_config`：内置维度键、别名、标准值、启用状态 | 现有内置键体系（含 projectNo 等 12 键） |
| S-2 | 动态维度配置：extraDimensions / 动态键词表（orgName、prodDate 等） | 现有动态维度注册与 `{year:$n}` 补年令牌 |
| S-3 | `tool_route_metadata`：topicTags / metricTags | 2026/11/18 起目录标签以元数据为准，词袋仅留前端校验 |
| S-4 | Skill 能力与参数映射：params_schema 单一真相源 | 直接执行参数保形契约 |
| S-5 | 既有维度抽取（正则轨、时间轨、台账轨等） | 现有 extraction 管线，Router 只读结果不重跑 |
| S-6 | 既有执行形态：Direct Runtime 支持的调用/整合形态 | plan 输出与执行器能力 |
| S-7 | 业务词表 | 现有已配置词表 |

**规则**：

- R-IN-1：Router 对上述来源只读，不写入。
- R-IN-2：来源缺失或不可用时，对应信号降级为 Uncertain，不猜测、不默认。
- R-IN-3：新增识别能力必须通过配置源扩展实现，不得在 Router 内硬编码。

## 2.3 输入禁令

- 禁止在 Router 内发起任何 LLM 调用。
- 禁止仅依据 DimensionState 判定维度形态（§3.4）。
- 禁止依据文本推断 PlanOrigin（必须读显式标记）。
- 禁止读取或依赖 Probe 之外的未来执行结果。

---

# 3. Request Shape Contract

## 3.1 逻辑模型

```text
RequestShape
    ├── Objectives                  目标清单（§8）
    ├── Metrics                     指标信号（§4）
    ├── Dimensions                  维度信号：Built-in / Dynamic / Unknown（§5/§6）
    ├── DimensionRelationships      关系形态 A–E（§7）
    ├── InvocationRequirements      调用要求：次数 / 参数组 / 重复调用 / 依赖
    └── IntegrationRequirements     整合要求：对比 / 排名 / 下钻 / 汇总 / 结论
```

以上为逻辑契约，具体数据结构在 1A-1 依现有代码实现确定（I-9）。

## 3.2 五个分析视角

Request Shape Analysis 必须覆盖五个视角，缺一不可：

| 视角 | 判定内容 | 详细契约 |
|---|---|---|
| Metric Shape | 单指标 / 多指标 / 指标间关系 | §4 |
| Dimension Shape | Built-in / Dynamic / Unknown 维度信号 | §5 / §6 |
| Dimension Relationship Shape | A–E 关系形态 | §7 |
| Objective Shape | 目标数量、输入实体 vs 输出实体 | §8 |
| Direct Plan Coverage Shape | Plan 对请求的实际覆盖 | §9 |

## 3.3 Coverage Evaluation（四态）

| 状态 | 含义 | 路由后果 |
|---|---|---|
| COMPLETE | Direct Plan 可确定完整覆盖请求 | 允许进入 Fast Path（仍需满足 §13 全部条件） |
| PARTIAL | Plan 覆盖部分目标/指标/维度，存在确定性缺口 | 不得 Fast Path；进入 Probe → Agent |
| UNCERTAIN | 规则无法确定是否覆盖（含 Unknown 信号、来源缺失降级） | 不得 Fast Path；进入 Probe → Agent |
| UNSUPPORTED | 请求需要的执行形态当前 Direct Runtime 不支持 | 不得 Fast Path、不 Probe；直接 Agent Fallback |

## 3.4 DimensionState Projection Limit（Case 11 冻结约束）

DimensionState 每轮仅保留一个 Peer Dimension（投影不完整），因此：

- PL-1：Router **不得**仅依据 DimensionState 将请求判定为「单维度/单 Peer Dimension」。
- PL-2：判定维度形态必须同时检查：① 原始请求中的维度信号；② Explicit Extraction 结果；③ Direct Plan 携带的参数；④ Invocation Parameters。
- PL-3：当上述来源与 DimensionState 不一致时，按更复杂形态处理（保守原则），并记录原因码。

---

# 4. Metric Recognition Contract

- M-1：指标识别信号 = S-3（tool_route_metadata topicTags/metricTags）∪ S-7（业务词表）∪ S-4（skill 词条）。多源取并集，不新增硬编码词表（I-2）。
- M-2：单指标不等于单目标；多指标不等于必然 Agent（Case 6/8：多指标可被单 Skill 一次调用完整覆盖时允许 Fast Path）。
- M-3：不得仅依据「和」「最高」「对比」等连接词强制判定多指标/复杂请求（§6.4 原则）。
- M-4：Metric Shape 输出为枚举信号：`SINGLE_METRIC` / `MULTI_METRIC_COMPLETE` / `MULTI_METRIC_PARTIAL` / `MULTI_METRIC_DIFF_SKILL` / `UNCERTAIN_METRIC_SIGNAL`。
- M-5：识别失败时降级为 `UNCERTAIN_METRIC_SIGNAL`，不得拒绝执行。

---

# 5. Built-in Dimension Recognition Contract

- B-1：内置维度识别唯一依据 = S-1（dimension_key_config：内置键、别名、标准值、启用状态）。
- B-2：别名消解沿用既有 resolveEmbeddedCandidates 契约（别名是标准名前缀的消歧行为不变）。
- B-3：禁用状态的内置键不得参与识别。
- B-4：识别输出为结构化维度信号（键、标准值、来源引用），不得输出自由文本推断。
- B-5：内置维度识别失败不构成拒绝执行理由，降级为 Unknown/Uncertain Dimension Signal（I-10）。

---

# 6. Dynamic Dimension Recognition Contract

- DD-1：动态维度识别唯一依据 = S-2（extraDimensions / 动态键配置，含 orgName、prodDate、`{year:$n}` 补年令牌等既有机制）。
- DD-2：动态维度语义可确定且 Direct Plan 可表达 → 与内置维度同权参与覆盖评估。
- DD-3：动态维度语义不确定，或其执行形态当前 Direct Runtime 不支持 → 输出 `DYNAMIC_DIM_SEMANTICS_UNCERTAIN` 或 `UNSUPPORTED` 信号（视程度进入 Probe 或直接 Agent）。
- DD-4：动态维度识别失败不拒绝执行（I-10）。

---

# 7. Dimension Relationship Contract

## 7.1 关系形态 A–E

| 形态 | 定义 | 例 | 路由含义 |
|---|---|---|---|
| A Single Dimension | 单一维度 | 国库组的缺陷密度 | Fast Path 可能（需 Plan COMPLETE） |
| B Cross-Level | 跨层级双维度 | 部门 + 人；部门 + 应用 | 复杂度信号；Plan 可表达时不必然 Agent |
| C Multiple Peer Dimensions | 多个平级维度 | 应用 + 产品线 + 统计组 | 复杂度信号；须逐一验证 Plan 表达能力 |
| D Hierarchical Drill-Down | 层级下钻 | 部门下各应用 | 复杂度信号；下钻粒度/默认下钻契约不变 |
| E Multiple Drill-Down Dimensions | 多下钻维度 | 各应用再按统计组展开 | 强复杂度信号 |

## 7.2 业务层级参考

```text
Department（部门）
    → Application / Product Line / Statistical Group（平级中层）
        → Person（人）
```

- 层级仅作关系形态参考，不构成硬编码维度目录（I-2）；层级语义以 S-1/S-2 配置为准。
- 形态 C/E 出现时，Router 必须验证 Direct Plan 能否完整表达该组合；不能表达 → PARTIAL/UNSUPPORTED。

---

# 8. Objective Contract

- O-1：Objective = 用户请求要达成的发现/结论，不是 Skill 名、不是 Plan 步骤、不是指标数量。
- O-2：目标分解必须区分**输入实体**（作为过滤/参数的实体，如「国库组」）与**待发现输出实体**（作为结论对象的实体，如「那个人」）。输出实体不得误判为缺失参数。
- O-3：单一请求可含多个 Objective（Case 1：缺陷密度 + 最高的人 = 两个 Objective）。
- O-4：Objective 数量以确定性信号为准；无法确定时输出 `UNCERTAIN_OBJECTIVE_SIGNAL`，不得猜测数量。
- O-5：每个 Objective 必须可映射到 Invocation Requirement 或 Integration Requirement；无法映射 → Coverage 至少为 UNCERTAIN。

---

# 9. Direct Plan Coverage Contract

## 9.1 分析清单

对当前 Direct Plan（IN-5）必须分析：Skills 清单、Invocations 清单、每个 Invocation 的参数、每个 Invocation 覆盖的 Objectives、重复调用要求、同 Skill 多参数需求、多 Skill 需求、未覆盖 Objective、参数冲突、维度组合表达力、下钻关系表达力、执行依赖、结果整合要求、Direct Runtime 是否支持该执行形态。

## 9.2 覆盖结论规则

| # | 规则 | 结论 |
|---|---|---|
| CV-1 | 所有 Objective 均有对应 Invocation，参数完整、无未表达关系、无整合要求超出单次执行 | COMPLETE |
| CV-2 | 存在确定性未覆盖 Objective / 指标 / 维度组合 | PARTIAL |
| CV-3 | 请求需要的执行形态（趋势、嵌套聚合、跨结果整合等）Direct Runtime 不支持 | UNSUPPORTED |
| CV-4 | 任何必要事实（Objective 映射、参数完整性、表达力）无法确定 | UNCERTAIN |
| CV-5 | Plan 与请求的 Invocation 要求不匹配（次数/参数组/粒度不一致） | INVOCATION_PLAN_MISMATCH → 不得 Fast Path |
| CV-6 | COMPLETE 不等于必须 Fast Path——仍须同时满足 §13 的全部准入条件 | 约束规则 |
| CV-7 | 覆盖评估全部来自 IN-5/IN-6 的确定性事实；禁止用 Confidence 替代覆盖判定（§11） | 约束规则 |

---

# 10. Router Decision Contract

## 10.1 路由输出

```text
RouteCode ∈ { DIRECT_FAST_PATH, PROBE_THEN_AGENT, AGENT_FALLBACK }
+ ReasonCodes（§12，1..n 个，可解释）
+ CoverageEvaluation（COMPLETE / PARTIAL / UNCERTAIN / UNSUPPORTED）
+ RequestShape 摘要信号
```

## 10.2 决策表（优先级自上而下）

| 优先级 | 条件 | 决策 |
|---|---|---|
| P0 | Router 内部异常或规则冲突 | fail-closed：不进 Fast Path；Probe 可用时 → PROBE_THEN_AGENT，否则 AGENT_FALLBACK（I-7） |
| P1 | Coverage = UNSUPPORTED | AGENT_FALLBACK（不 Probe） |
| P2 | Coverage = PARTIAL / UNCERTAIN | PROBE_THEN_AGENT |
| P3 | Coverage = COMPLETE 且 §13 十项准入条件全部满足且无负向证据 | DIRECT_FAST_PATH |
| P4 | Coverage = COMPLETE 但存在负向证据 / 准入条件不满足 | PROBE_THEN_AGENT |

## 10.3 决策原则

- RD-1：RouteCode 必须附带至少一个原因码；禁止无解释的决策。
- RD-2：复杂度信号（多 Objective / 多指标 / 多维度 / 下钻 / 重复调用 / 整合 / Plan 不匹配 / 动态维度不确定等）是「检查项」而非「必然 Agent 判决」——最终判定以 Direct Plan 实际覆盖能力为准；Plan 能完整表达的多维度请求允许 Fast Path。
- RD-3：禁止把「多维度」或「多指标」本身作为唯一判据。
- RD-4：同一请求在相同输入与配置下必须产生相同决策（确定性）。

---

# 11. Confidence Contract

- C-1：Confidence 是**辅助指标**，必须有具体依据（规则命中明细），禁止不可解释的黑盒分数。
- C-2：**Hard Coverage Constraints 优先于 Confidence**：即使 Confidence 很高，只要 Direct Coverage 存在确定性缺口（PARTIAL/UNSUPPORTED/UNCERTAIN），不得进入 Fast Path。
- C-3：初始阈值 ≈ 0.8 为**未经实验验证的初始设计值**；文档与代码均须标注该性质。
- C-4：Confidence 计算依据必须可枚举（命中的确定性信号集合），支持 Shadow Mode 依真实数据调整（§28）。
- C-5：参考决策式（非充分条件）：`High Confidence + Complete Direct Coverage → 可参与 Fast Path 判定；Low Confidence / Complex Shape → PROBE_THEN_AGENT`。

---

# 12. Reason Codes

低基数枚举（I-8）。主路径 Router 原因码：

| 原因码 | 含义 |
|---|---|
| `SHAPE_UNCERTAIN` | Request Shape 无法确定 |
| `MULTI_OBJECTIVE_PARTIAL_COVERAGE` | 多目标且 Plan 部分覆盖 |
| `MULTI_METRIC_PARTIAL_COVERAGE` | 多指标且 Plan 部分覆盖 |
| `MULTI_METRIC_DIFF_SKILL` | 多指标需不同 Skill |
| `REPEATED_INVOCATION_REQUIRED` | 需要同 Skill 多次调用 |
| `PEER_DIMENSIONS_UNEXPRESSED` | 多平级维度 Plan 无法表达 |
| `CROSS_LEVEL_DIMENSIONS_UNEXPRESSED` | 跨层级维度 Plan 无法表达 |
| `DRILL_DOWN_UNEXPRESSED` | 下钻关系 Plan 无法表达 |
| `INTEGRATION_REQUIRED` | 需要跨执行结果整合 |
| `DYNAMIC_DIM_SEMANTICS_UNCERTAIN` | 动态维度语义不确定 |
| `UNKNOWN_DIMENSION_SIGNAL` | 未知维度信号（不拒绝，转 Probe/Agent） |
| `INVOCATION_PLAN_MISMATCH` | 调用要求与 Plan 不匹配 |
| `EXECUTION_DEPENDENCY_UNSUPPORTED` | 执行依赖不受支持 |
| `PLAN_COVERAGE_UNSUPPORTED` | 执行形态 Direct Runtime 不支持 |
| `RULE_CONFLICT_FAIL_CLOSED` | 规则冲突 fail-closed |
| `ROUTER_EXCEPTION_FAIL_CLOSED` | Router 异常 fail-closed |
| `FAST_PATH_ADMITTED` | Fast Path 准入（附命中条件） |
| `PROBE_STATE_*` | Probe 六态（§17）：PROBE_SUCCESS / PROBE_EMPTY_RESULT / PROBE_EXECUTION_FAILED / PROBE_NO_DIRECT_PLAN / PROBE_UNSUPPORTED / PROBE_TIMEOUT |

追问层 Guard 既有原因码（FOLLOWUP_TASK_REPLACEMENT 等）原样保留，作用域限追问层（Option C），不在主路径复用。

---

# 13. Direct Fast Path Contract

- F-1：Fast Path = 既有 Direct Skill Execution 原路径零开销执行；不引入新执行层、不改变执行结果、不改变结果渲染契约。
- F-2：准入必须同时满足以下十项：
  1. 请求形态明确；
  2. Objective 结构清晰；
  3. 指标与维度提取稳定；
  4. 当前 Direct Plan 可完整覆盖（Coverage = COMPLETE）；
  5. 执行实例数量与请求一致；
  6. 参数配置完整；
  7. 没有未知的执行依赖；
  8. 没有无法表达的维度关系；
  9. 没有未覆盖 Objective；
  10. 没有需要跨执行结果整合的复杂任务。
- F-3：负向证据（任一即否决 Fast Path）：Unknown Dimension Signal、Dynamic Dimension 语义不确定、Invocation Plan Mismatch、执行依赖不确定、Integration Requirement 超出单次执行、§3.4 投影不一致按保守原则升级。
- F-4：Fast Path 目标占比 ≥ 80%（`simple_request_fast_path_coverage` 度量），该目标为设计目标，待 Shadow 实测定标（D-6）。
- F-5：Fast Path 命中必须记录 `FAST_PATH_ADMITTED` + 命中条件明细。

---

# 14. Probe Eligibility Contract

Probe 目的**不是完成整个请求**，而是获取初步结果与 Evidence。

## 14.1 资格审计（基于现有 Direct Plan 逐项确认）

| # | 审计点 | 契约 |
|---|---|---|
| E-1 | Probe 允许执行哪些现有 Skills | 仅限只读、无副作用工具、结果有界的 Skill；名单来自配置源（S-4/S-6），不硬编码 |
| E-2 | 是否允许执行现有 Multi-Skill Plan | 允许执行其子集；不得强制单 Skill（§9.1 禁令），也不得无限制运行整个 Plan |
| E-3 | Invocation 数量上限 | 有界（具体预算值 D-4，默认保守小值） |
| E-4 | 执行时间限制 | 有界 timeout；超时按 §17 PROBE_TIMEOUT |
| E-5 | 数据量限制 | 有界；超出按 TRUNCATED 处理 |
| E-6 | Tool Call 次数限制 | 有界 |
| E-7 | Result Size 限制 | 有界；落 Evidence 时截断标记 |
| E-8 | 失败处理 | Probe 失败为正常分支（PROBE_EXECUTION_FAILED），不计入异常率（H7 口径不变），不得阻断 Agent Fallback |
| E-9 | 不适合 Probe 的 Skill | 产生副作用、长耗时、大数据量导出的 Skill 标记为 Probe-ineligible（配置源声明） |
| E-10 | 数据库读取 | 仅允许既有 Direct 执行已有的只读查询路径；不得扩展新的写路径 |
| E-11 | 副作用工具 | 一律禁止（写入、删除、提交、外部消息） |

## 14.2 资格结论

- PE-1：无任何 eligible Invocation → 跳过 Probe，直接 AGENT_FALLBACK（原因码 `PROBE_NO_DIRECT_PLAN` 或对应资格码）。
- PE-2：Probe 至多一次（Frozen，继承原契约）。
- PE-3：Probe 选择哪些 Invocation 由覆盖缺口的最小验证需求决定，禁止 top-1 截断式的机械选择，也禁止无限制全量执行。

---

# 15. Probe Boundaries

- PB-1：Probe 为**非提交性执行**：Probe Success ≠ Request Success（冻结不变式）。
- PB-2：Probe 不提交 Snapshot、不消费 Pending、不改变 PlanOrigin、不触发正式 `recordResult`、不冒充 Agent Plan。
- PB-3：Probe 复用 planSingle()/planMulti() 既有规划能力与 Direct 执行器，但必须以隔离模式运行（§16）。
- PB-4：Probe 期间产生的任何内部状态一律标记 Candidate/Probe 域，与正式执行域隔离（ISO 体系）。
- PB-5：Probe 超时/失败/为空均为正常分支，按 §17 状态机记录后进入 Agent。

---

# 16. Probe Side-Effect Isolation

十点隔离（全部强制，缺一不可）：

| # | 隔离点 | 契约 |
|---|---|---|
| ISO-1 | 不提交成功执行历史 | Probe 结果不得进入任何正式执行历史 |
| ISO-2 | 不污染 Committed Snapshot | Committed Snapshot 只接受既有契约下的正式成功执行 |
| ISO-3 | 不改变原有 Snapshot | 既有 Snapshot 内容与 TTL 行为不变 |
| ISO-4 | 不污染 Pending | 不创建、不修改、不消费正式 Pending |
| ISO-5 | 不触发正式 recordResult | recordResult 仅正式执行可达 |
| ISO-6 | 不改变 PlanOrigin 正式语义 | Probe 运行期的内部标记不得回写 PlanOrigin |
| ISO-7 | 不视为已完成请求 | Probe 结束不产生任何「请求完成」信号 |
| ISO-8 | 失败不阻断 Agent Fallback | Probe 失败后 Agent 必须照常接收完整原始请求 |
| ISO-9 | Probe Yield 不污染 Snapshot | 隔离域内的让位行为与正式域隔离 |
| ISO-10 | 结果不得误入正式执行历史 | 任何持久化通道必须携带 Probe 域标记并过滤 |

**保留约束**：Candidate / Committed Snapshot Isolation 原契约完整保留（12 不变式 + 13 行状态转移表，见 §23）。规划期 `recordLastExec`（:1311）写入 Candidate 域的既有事实不变，升级为 1A 强制约束（1A-7 验证）。

---

# 17. Probe Result Contract

## 17.1 Probe 六态（路由层）

| 状态 | 含义 | 后续 |
|---|---|---|
| PROBE_SUCCESS | Probe 执行成功且结果完整未截断 | Evidence 交付 Agent |
| PROBE_EMPTY_RESULT | 执行成功但结果为空 | Evidence（EMPTY）交付 Agent |
| PROBE_EXECUTION_FAILED | 执行失败 | Evidence（FAILED）交付 Agent；不阻断 |
| PROBE_NO_DIRECT_PLAN | 无 eligible Invocation | 跳过 Probe，直接 Agent |
| PROBE_UNSUPPORTED | 目标执行形态不支持 Probe | 跳过 Probe，直接 Agent |
| PROBE_TIMEOUT | 超时 | Evidence（TIMEOUT）交付 Agent |

- 六态全部为**正常分支**，不计入运行时异常率（H7 口径不变）。
- 若 Probe 只执行了部分 Objective，必须明确记录 Partial Coverage（Evidence `objectiveCoverage`）。

## 17.2 Evidence resultStatus 七态（证据层）

`SUCCESS / PARTIAL / EMPTY / FAILED / TIMEOUT / SKIPPED / TRUNCATED`（§18）。

---

# 18. Evidence Contract

## 18.1 ProbeEvidence 概念模型（16 字段）

```text
ProbeEvidence
    requestId            请求标识
    probeId              Probe 实例标识
    originalRequest      原始请求（脱敏后引用，遵守 I-8）
    planOrigin           本次运行的 PlanOrigin 上下文（Probe 域标记，非正式回写）
    skillId              Skill 标识
    scriptId             Script 标识
    invocationId         Invocation 标识
    invocationParameters 实际参数（保形）
    objectiveCoverage    覆盖了哪些 Objective（含 Partial 声明）
    resultStatus         七态之一
    resultSummary        摘要
    structuredResult     结构化结果（有界）
    executionError       错误信息（FAILED 时）
    timeoutStatus        超时状态
    truncatedStatus      截断状态
    evidenceTimestamp    证据时间戳
```

- EC-1：具体字段以现有代码模型为依据确定，不要求机械复制此概念模型（I-9）。
- EC-2：Evidence 必须区分七态：`SUCCESS / PARTIAL / EMPTY / FAILED / TIMEOUT / SKIPPED / TRUNCATED`。
- EC-3：Evidence 只读、只传递；Agent 与 Router 均不得修改既有 Evidence。
- EC-4：Evidence 携带 Probe 域标记，任何持久化/展示通道必须遵守 ISO-10。

---

# 19. Evidence Reuse Contract

## 19.1 复用决策

`REUSE / ADDITIONAL_EXECUTION / MIXED / REJECTED` 四态，由 Agent 依 §19.2 判定并给出依据。

## 19.2 禁令（六不得）

- 不得盲目信任 Evidence。
- 不得将 Partial Result 当作 Full Result。
- 不得将参数不一致的结果当作可复用结果。
- 不得将过期结果当作当前结果。
- 不得将失败结果伪装为成功。
- 不得将 Probe Invocation 与正式 Agent Invocation 混淆。

## 19.3 复用判定维度（九项）

`Skill / Script / Parameters / Metric / Dimensions / Time Range / Scope / Invocation Identity / Result Completeness`。

- ER-1：Same Skill ≠ Same Invocation ≠ Same Script ≠ 自动可复用——九项维度须逐一核对。
- ER-2：无法确认 Evidence 与剩余任务匹配时，**允许重新执行**（ADDITIONAL_EXECUTION），不构成异常。

---

# 20. Agent Context Contract

Agent Fallback 输入必须完整包含以下八项（缺一即契约违规）：

```text
1. Original User Request            完整原始请求（禁止只传剩余部分——冻结不变式）
2. Relevant Conversation Context    相关会话上下文
3. Deterministic Dimension Context  确定性维度上下文
4. Current Request Shape            当前请求形态
5. Direct Plan                      当前 Direct Plan
6. Direct Probe Evidence            Probe 证据（§18）
7. Probe Execution Status           Probe 六态
8. Coverage Gaps                    覆盖缺口清单
```

- ACX-1：Evidence 注入为**可复用参考**，不强制复用、不强制重跑（§19 判定权在 Agent）。
- ACX-2：Agent Goal = 完整原始请求（冻结不变式）。

---

# 21. Agent Completion Contract

## 21.1 十二项职责

1. 理解完整原始请求。
2. 识别所有 Objective。
3. 分析 Probe 已完成的部分。
4. 识别 Probe 未完成的部分。
5. 判断已有 Evidence 是否可复用。
6. 避免不必要的重复调用。
7. 必要时调用同一个 Skill 多次。
8. 必要时调用不同 Skill。
9. 根据依赖关系规划剩余执行。
10. 完成数据整合、比较、排名、下钻和分析。
11. 验证结果是否覆盖整个请求。
12. 不能将 Probe Result 直接当成整个 Request 的最终答案。

## 21.2 完成声明

- AG-1：Agent 完成前必须执行职责 11 的覆盖验证；验证失败时**如实声明无法完成的部分**，不得伪装完整（§22）。
- AG-2：Agent 工具失败时按既有失败语义处理，不得将失败渲染为成功。
- AG-3：Agent 不冒充 Direct：Agent 执行结果不得写入 Direct 执行历史，不得修改 PlanOrigin 为正式 Direct 语义。

---

# 22. Whole-Request Completion

```text
Request Completion =
      Original Request Objective Coverage
    + Required Invocation Coverage
    + Required Dimension Coverage
    + Required Analysis Coverage
    + Required Result Integration
```

- WC-1：`Partial Direct Result ≠ Request Completion`；`Probe Success ≠ Request Completion`。
- WC-2：完成度以**原始用户 Request** 为基准，不以 Direct Plan、Skill 成功或 Probe 成功为基准。
- WC-3：除非明确说明无法完成，否则不得将部分结果伪装成完整结果。
- WC-4：整轮让位原子性（冻结）：禁止「部分 Direct + 剩余交 ReAct」的混合让位；让位 = 整轮交给 Agent，携带完整上下文。Probe ≠ 部分让位（Probe 是有界验证，非提交性）。
- WC-5：Yield 语义作用域仍限追问层（Option C）；主路径的让位即 Agent Fallback（§10）。

---

# 23. Snapshot Contract

| 项 | 契约 |
|---|---|
| Candidate Snapshot | 规划期产生的快照；不提交成功执行历史；`recordLastExec(:1311)` 规划期写入属 Candidate 域既有事实，1A-7 强制验证隔离 |
| Committed Snapshot | 仅既有契约下的正式成功执行可提交；TTL 30min（v2 :453） |
| Probe | 不污染 Committed Snapshot；不改变原有 Snapshot（ISO-2/3） |
| Yield | 不污染 Snapshot（ISO-9） |
| 提交门槛 | 只有符合既有契约的正式成功执行才允许提交（原有语义逐字保留） |
| 连续多轮污染 | S1→S2→Turn3 测试场景保留为回归项 |

---

# 24. Pending Contract

- PD-1：正式 Ask/Pending 行为原样保留：`pending.put ⟺ ask plan 存在`（实现不变式，写入契约+测试）；TTL 5min（v2 :442）；key = userId:conversationId。
- PD-2：Probe 不创建、不修改、不消费正式 Pending（ISO-4）。
- PD-3：Agent Fallback 与 Pending：Agent 让位轮不产生正式 Pending；若请求本身含 Ask 需求，按既有混合轮语义处理（CR-02 Option A：混合轮 Ask 豁免但 Executable 仍受判定；Ask 形态请求按既有反问链路走，不进 Probe）。
- PD-4：Pending Consume 短路规则原样保留（consumePending 位于 planAll 最前 :573-578）。
- PD-5：Clarification 后的计划重新评估：追问轮回到既有链路（LEGACY_FOLLOWUP / PENDING_CONTINUATION 归属），不经过主路径 Router。

---

# 25. PlanOrigin Contract

- PO-1：PlanOrigin 三值枚举 `NEW_REQUEST / PENDING_CONTINUATION / LEGACY_FOLLOWUP`，显式标记产生（唯一合法产生条件契约保留），禁止文本推断。
- PO-2：主路径 Router 仅作用于 `NEW_REQUEST`；`PENDING_CONTINUATION` / `LEGACY_FOLLOWUP` 走既有追问层链路（Option C Guard 原样保留）。
- PO-3：执行语义区分（不得混淆）：

```text
Direct Fast Path        正式 Direct 执行（可提交）
Direct Probe            非提交性验证执行（Candidate/Probe 域）
Agent Fallback          正式 Agent 让位执行
Agent Tool Invocation   Agent 内部工具调用
Final Execution         最终对用户交付的执行
```

- PO-4：Probe 复用 Direct Skill Executor **不构成**将 Probe 误认为正式 Direct Execution 的理由；任何持久化通道以显式域标记区分。

---

# 26. Multi-Skill G1–G7 Preservation

Frozen Architecture，Router 不得重构、简化或改变其资格判定（I-6）。

| Gate | 内容 |
|---|---|
| G1 | Explicit Selection / Exclusion（点名选择/排除） |
| G2 | Domain Hit Conditions / Exclusions（domain 命中只留命中领域 skill，通用出局，层级轨也不例外） |
| G3 | Bypass Conditions（旁路条件；旁路检查在 plan() 内既有顺序不变） |
| G4 | Priority Tie-Break（优先级仅深度并列时整轮降级） |
| G5 | Complete Top-Hit Chain（完整最高命中链；无 fallbackExpanded） |
| G6 | Legacy-track Exclusion（存量轨剔除不阻断 + 点名恒单执行） |
| G7 | Eligible Level-track Candidate Requirements（≥2 个合格层级轨候选） |

**同时保留的语义项**（逐字自原始规范，不得依本概述替代原文）：Mention-order（提及序）；Injection-only Hits（注入命中排最后）；Comparator Fallback；Shared Extraction；Skill Parameter Mapping；Schema / Backfill（params_schema 单一真相源 + 回填）；Anchor Suppression（锚点屏蔽）；Drill-down（默认下钻一级 + 粒度冲突消解）；Mixed Ask / Exec（混排反问契约）；Time / Person Ambiguity（时间歧义短路 + 人名消歧/会话钉定）；Script Failure Continuation（脚本失败继续）；Serial Execution（串行执行）；boundedElastic；Dimension Extract Event；Per-Script toolCallId；Result Titles / short_summary；Pending Consume Short-circuit；Snapshot；recordResult semantics；Config defaults and effective runtime configuration（multi.enabled 默认 false，混合轮仅该开关可达）。

---

# 27. V1 / V2 Parity

## 27.1 集成原则

- V1 `/ai/chat`（Vue 生产前端流量，frontend/src/api/chat.ts:97）保留原有 Pipeline。
- V2 `/v2/ai/chat`（React /pm，frontend-pm/src/api/chat.ts:96）保留原有 Pipeline。
- V1 与 V2 SSE 协议**不合并**；反问轮协议差异（v1 纯一次性 text 事件硬约束）不变。
- 两侧 Service 层分别集成 Router；Shared Router Core 可复用（同一判定逻辑）。
- 不在共享 DirectSkillExecutor（V2SkillConfig :209 单例，v2 planAll :461 / v1 planAll :499 两调用点）内强制实施 Router；其职责与契约保持稳定。
- Fast Path 不改变原有 Direct Execution 结果；Probe 不破坏原有执行状态；Agent Fallback 必须能接收 Evidence。

## 27.2 集成形态

```text
V1 Pipeline
    └── Rule-Based Router
            ├── Direct Fast Path
            └── Probe → Agent Fallback

V2 Pipeline
    └── Rule-Based Router
            ├── Direct Fast Path
            └── Probe → Agent Fallback
```

- VP-1：具体挂接点以真实代码审计（1A-0）确定，不得未经审计认定两 Pipeline 完全相同。
- VP-2：模式配置（OFF/SHADOW/ENFORCEMENT）按端点独立（§28 / Document 1 §25）。
- VP-3：v1 三大实施前置风险继续有效：① v1 planAll(:499) 空列表分支行为未验证（无 fallback 则 Yield 退化为无输出）——最高优先级；② v1 SSE 反问轮纯一次性 text 事件硬约束；③ v1 侧 dimensionState/pending/Snapshot 写点相对 Guard 位置未排查。

---

# 28. Shadow Mode Contract

## 28.1 行为契约

- Shadow Mode 不改变实际用户请求的执行路径。
- **Shadow Mode 不执行 Probe**（冻结修订：Shadow 仅记录；Probe 虽为非提交性执行但仍消耗真实资源，Shadow 阶段一律不跑）。
- Shadow Mode 不污染 Snapshot、Pending、recordResult。

```text
Current Production Decision
        │
        ├── Execute Existing Path
        │
        └── Shadow Router（旁路）
                ├── Request Shape
                ├── Confidence
                ├── Decision
                ├── Reason Codes
                ├── Metric Signals
                ├── Dimension Signals
                ├── Hierarchy Signals
                ├── Objective Signals
                ├── Direct Coverage
                └── Expected Route
```

## 28.2 Shadow 评估指标（15 项）

| # | 指标 | 说明 |
|---|---|---|
| 1 | Fast Path Precision | Fast Path 判定正确率 |
| 2 | Fast Path Recall | 应走 Fast Path 而被检出的比例 |
| 3 | Complex Request Detection | 复杂请求检出率 |
| 4 | Objective Coverage Gap Detection | 覆盖缺口检出率 |
| 5 | Repeated Invocation Detection | 重复调用需求检出率 |
| 6 | Dimension Combination Detection | 维度组合检出率 |
| 7 | Hierarchical Drill-down Detection | 下钻检出率 |
| 8 | Multiple Metric Detection | 多指标检出率 |
| 9 | False Fast Path | 误入 Fast Path 率（阈值待 Shadow 定标，D-5） |
| 10 | False Probe | 误触发 Probe 率（阈值待定标） |
| 11 | Probe Cost | 预估 Probe 成本（基于 Shape 推演，不实际执行） |
| 12 | Agent Cost | Agent Fallback 频次与预估成本 |
| 13 | Fast Path Latency | Router 判定耗时 |
| 14 | Probe Latency | 预估 Probe 耗时 |
| 15 | Request Completion Rate | 请求完整达成率（ENFORCEMENT 后实测） |

## 28.3 Shadow → Enforcement 阶段

```text
OFF → SHADOW（只记录）→ 达标评审 → ENFORCEMENT（端点维度灰度）→ 全量
任一阶段异常 → Kill Switch 回退，三类故障分法（§32）
```

---

# 29. Metrics / Observability

- OB-1：指标标签为低基数枚举（I-8）：`endpoint`（v1/v2）、`route_code`、`probe_state`、`reason_code`、`coverage`。
- OB-2：禁止记录原始用户请求、敏感用户标识、不必要敏感内容；Signal 记录使用类别计数而非原文。
- OB-3：核心指标：`simple_request_fast_path_coverage`、`false_fast_path_rate`、`false_probe_rate`、`router_decision_latency`、`probe_state_distribution`、`agent_fallback_rate`、`request_completion_rate`。
- OB-4：指标按端点维度拆分（双端点保护）。
- OB-5：H7 异常率口径不变：严格 <0.5%（等于也 BLOCKED）；系统性异常（同类输入稳定触发）单独条款阻断；Probe 六态不计入异常率。

---

# 30. Golden Matrix

判定矩阵共 **52 例**（Fast Path 8 + Probe → Agent 28 + Critical Regression 16），每例 12 字段。Coverage 取值 COMPLETE / PARTIAL / UNCERTAIN / UNSUPPORTED。

## 30.1 Fast Path（FP-01 ~ FP-08）

### FP-01 Single Objective + Single Metric + Single Dimension

| Field | 内容 |
|---|---|
| Case ID | FP-01 |
| User Input | `11月版本国库组的缺陷密度` |
| Request Shape | 单 Objective；单指标；维度=版本(11月版本)+部门(国库组)，形态 A |
| Direct Plan | 单 Skill 单 Invocation，参数完整 |
| Coverage | COMPLETE |
| Expected Route | Fast |
| Probe Expected | 不执行 |
| Agent Expected | 不需要 |
| Snapshot Expected | 正式成功后按既有契约提交 |
| Pending Expected | 无 |
| PlanOrigin | NEW_REQUEST |
| Acceptance | 结果与现状 Direct 执行完全一致；记录 FAST_PATH_ADMITTED |

### FP-02 Single Skill + Complete Coverage

| Field | 内容 |
|---|---|
| Case ID | FP-02 |
| User Input | `11月版本各部门的缺陷密度` |
| Request Shape | 单 Objective；单指标；维度=版本+部门（全部门值域），形态 A |
| Direct Plan | 单 Skill 单 Invocation 完整覆盖（Case 8 形态：单 Skill 多目标一次调用完整亦归此） |
| Coverage | COMPLETE |
| Expected Route | Fast |
| Probe Expected | 不执行 |
| Agent Expected | 不需要 |
| Snapshot Expected | 正式提交 |
| Pending Expected | 无 |
| PlanOrigin | NEW_REQUEST |
| Acceptance | 与现状一致；命中链 G5 完整 |

### FP-03 Single Metric + Ranking + Complete Coverage

| Field | 内容 |
|---|---|
| Case ID | FP-03 |
| User Input | `11月版本各部门缺陷密度最高的部门` |
| Request Shape | 单 Objective（含排名结论）；单指标；形态 A；Integration=排名 |
| Direct Plan | 单 Skill 一次调用可完成排名（不得仅凭「最高」判复杂——M-3） |
| Coverage | COMPLETE |
| Expected Route | Fast |
| Probe Expected | 不执行 |
| Agent Expected | 不需要 |
| Snapshot Expected | 正式提交 |
| Pending Expected | 无 |
| PlanOrigin | NEW_REQUEST |
| Acceptance | 排名结果与现状一致 |

### FP-04 Multiple Metrics + Complete Multi-Skill Coverage

| Field | 内容 |
|---|---|
| Case ID | FP-04 |
| User Input | `11月版本国库组的缺陷密度和测试用例执行率` |
| Request Shape | 单 Objective 双指标（Case 6）；两指标分别完整映射两 Skill |
| Direct Plan | planMulti 两 Invocation，参数完整 |
| Coverage | COMPLETE |
| Expected Route | Fast |
| Probe Expected | 不执行 |
| Agent Expected | 不需要 |
| Snapshot Expected | 各 Invocation 正式提交 |
| Pending Expected | 无 |
| PlanOrigin | NEW_REQUEST |
| Acceptance | 与现有多执行行为一致（G7 链路复验） |

### FP-05 One Objective + Multiple Parameter Values, Direct Plan Complete

| Field | 内容 |
|---|---|
| Case ID | FP-05 |
| User Input | `11月版本和10月版本国库组的缺陷密度` |
| Request Shape | 单 Objective；单指标；同参数键多值（版本×2，版本/季度互不换算契约不变） |
| Direct Plan | 同 Skill 两次调用，参数组完整（Plan 可表达） |
| Coverage | COMPLETE |
| Expected Route | Fast |
| Probe Expected | 不执行 |
| Agent Expected | 不需要 |
| Snapshot Expected | 两次正式提交 |
| Pending Expected | 无 |
| PlanOrigin | NEW_REQUEST |
| Acceptance | 与现有多值参数行为一致 |

### FP-06 Built-in Dimension

| Field | 内容 |
|---|---|
| Case ID | FP-06 |
| User Input | `11月版本杭州开发一部的缺陷密度` |
| Request Shape | 单 Objective；单指标；内置维度部门（S-1 标准值命中），形态 A |
| Direct Plan | 单 Skill 单 Invocation |
| Coverage | COMPLETE |
| Expected Route | Fast |
| Probe Expected | 不执行 |
| Agent Expected | 不需要 |
| Snapshot Expected | 正式提交 |
| Pending Expected | 无 |
| PlanOrigin | NEW_REQUEST |
| Acceptance | 内置键识别来自 dimension_key_config，无硬编码 |

### FP-07 Configured Dynamic Dimension

| Field | 内容 |
|---|---|
| Case ID | FP-07 |
| User Input | `投产日期在11月10日之后的11月版本应用的缺陷密度` |
| Request Shape | 单 Objective；单指标；动态维度 prodDate（S-2 配置+补年令牌），语义可确定 |
| Direct Plan | 单 Skill 单 Invocation 支持该参数 |
| Coverage | COMPLETE |
| Expected Route | Fast |
| Probe Expected | 不执行 |
| Agent Expected | 不需要 |
| Snapshot Expected | 正式提交 |
| Pending Expected | 无 |
| PlanOrigin | NEW_REQUEST |
| Acceptance | 动态维度语义确定时不被歧视；行为与现状一致 |

### FP-08 Explicit Dimension + Alias

| Field | 内容 |
|---|---|
| Case ID | FP-08 |
| User Input | `11月版本杭研的缺陷密度` |
| Request Shape | 单 Objective；单指标；动态/别名维度（杭研=上级组织词表命中），形态 A |
| Direct Plan | 单 Skill 单 Invocation；别名消解走既有 resolveEmbeddedCandidates |
| Coverage | COMPLETE |
| Expected Route | Fast |
| Probe Expected | 不执行 |
| Agent Expected | 不需要 |
| Snapshot Expected | 正式提交 |
| Pending Expected | 无 |
| PlanOrigin | NEW_REQUEST |
| Acceptance | 别名消解行为不回归（clarify-loop 修复不劣化） |

## 30.2 Probe → Agent（PA-01 ~ PA-28）

### PA-01 Multiple Objectives + Partial Direct Coverage

| Field | 内容 |
|---|---|
| Case ID | PA-01 |
| User Input | `11月版本国库组的缺陷密度和那个人的缺陷密度最高` |
| Request Shape | 双 Objective（O1=国库组缺陷密度；O2=发现最高的人，输出实体不得当缺失参数——O-2） |
| Direct Plan | 现有 Direct 仅覆盖 O1（O2 无对应 Invocation） |
| Coverage | PARTIAL |
| Expected Route | Probe → Agent |
| Probe Expected | 执行 O1 对应 Invocation；Evidence resultStatus=SUCCESS，objectiveCoverage 记录仅 O1 |
| Agent Expected | 复用 O1 Evidence（REUSE），补 O2 排名发现 |
| Snapshot Expected | Probe 不提交；Agent 补全结果不入 Direct 历史 |
| Pending Expected | 无 |
| PlanOrigin | NEW_REQUEST |
| Acceptance | 最终回答同时覆盖 O1+O2；Probe 结果不被误当最终答案（职责 12） |

### PA-02 Multiple Metrics + Partial Coverage

| Field | 内容 |
|---|---|
| Case ID | PA-02 |
| User Input | `11月版本国库组的缺陷密度和人均代码行数排名` |
| Request Shape | 双指标，其一（人均代码行数）Plan 无法计算 |
| Direct Plan | 仅缺陷密度 Invocation |
| Coverage | PARTIAL |
| Expected Route | Probe → Agent |
| Probe Expected | 执行缺陷密度 Invocation；Evidence=SUCCESS(Partial Coverage) |
| Agent Expected | 复用缺陷密度，自行计算人均并排名 |
| Snapshot Expected | Probe 不提交 |
| Pending Expected | 无 |
| PlanOrigin | NEW_REQUEST |
| Acceptance | 两指标均有结果；无重复调用缺陷密度 |

### PA-03 Multiple Metrics + Different Skills

| Field | 内容 |
|---|---|
| Case ID | PA-03 |
| User Input | `11月版本国库组的缺陷密度和测试用例执行情况的对比分析` |
| Request Shape | 双指标双 Skill + 整合要求（对比） |
| Direct Plan | 两 Skill 可执行但整合（对比结论）超出单次执行 |
| Coverage | PARTIAL |
| Expected Route | Probe → Agent |
| Probe Expected | 有界 Probe 验证其一或两者可用性（Invocation 预算内） |
| Agent Expected | 按依赖规划执行并整合对比结论 |
| Snapshot Expected | Probe 不提交；正式执行走 Agent 语义 |
| Pending Expected | 无 |
| PlanOrigin | NEW_REQUEST |
| Acceptance | 对比结论基于真实两份结果；Evidence 复用判定合规 |

### PA-04 Same Skill + Multiple Invocation Parameters

| Field | 内容 |
|---|---|
| Case ID | PA-04 |
| User Input | `11月版本国库组和产品线B的缺陷密度对比` |
| Request Shape | 单 Objective；同 Skill 需两组参数（Case 2）+ 对比整合 |
| Direct Plan | Plan 仅含单组参数 |
| Coverage | PARTIAL（INVOCATION_PLAN_MISMATCH） |
| Expected Route | Probe → Agent |
| Probe Expected | 单组 Probe 验证可用性；Evidence=SUCCESS |
| Agent Expected | ADDITIONAL_EXECUTION 补第二组后对比 |
| Snapshot Expected | Probe 不提交 |
| Pending Expected | 无 |
| PlanOrigin | NEW_REQUEST |
| Acceptance | 两组数据均来自真实执行；对比成立 |

### PA-05 Same Skill + Repeated Invocation Required

| Field | 内容 |
|---|---|
| Case ID | PA-05 |
| User Input | `11月版本国库组每个成员的缺陷密度` |
| Request Shape | 单 Objective；同 Skill 需按成员重复调用 N 次 |
| Direct Plan | Plan 无法表达逐成员展开 |
| Coverage | PARTIAL |
| Expected Route | Probe → Agent |
| Probe Expected | 单成员 Probe 验证；Evidence=SUCCESS |
| Agent Expected | 循环调用补全全部成员 |
| Snapshot Expected | Probe 不提交 |
| Pending Expected | 无 |
| PlanOrigin | NEW_REQUEST |
| Acceptance | 成员全覆盖；Probe 结果不冒充全部成员结果 |

### PA-06 Multiple Peer Dimensions

| Field | 内容 |
|---|---|
| Case ID | PA-06 |
| User Input | `11月版本应用A和产品线B的缺陷密度` |
| Request Shape | 多平级维度（形态 C：应用+产品线） |
| Direct Plan | Plan 无法同时表达双平级轴 |
| Coverage | PARTIAL |
| Expected Route | Probe → Agent |
| Probe Expected | 单轴 Probe；Evidence=SUCCESS |
| Agent Expected | 补第二轴并整合 |
| Snapshot Expected | Probe 不提交 |
| Pending Expected | 无 |
| PlanOrigin | NEW_REQUEST |
| Acceptance | 两轴均有数据；形态 C 判定不依赖 DimensionState 单投影（PL-1/PL-2） |

### PA-07 Department + Application

| Field | 内容 |
|---|---|
| Case ID | PA-07 |
| User Input | `11月版本杭州开发一部各应用的缺陷密度` |
| Request Shape | 跨层级（形态 B：部门+应用） |
| Direct Plan | 本例 Plan 无应用展开 |
| Coverage | PARTIAL |
| Expected Route | Probe → Agent |
| Probe Expected | 部门级 Probe；Evidence=SUCCESS |
| Agent Expected | 逐应用补全 |
| Snapshot Expected | Probe 不提交 |
| Pending Expected | 无 |
| PlanOrigin | NEW_REQUEST |
| Acceptance | 应用粒度完整；部门级数据复用不重跑 |

### PA-08 Department + Product Line

| Field | 内容 |
|---|---|
| Case ID | PA-08 |
| User Input | `11月版本杭州开发一部各产品线的缺陷密度` |
| Request Shape | 形态 B：部门+产品线 |
| Direct Plan | 无产品线展开 |
| Coverage | PARTIAL |
| Expected Route | Probe → Agent |
| Probe Expected | 部门级 Probe |
| Agent Expected | 产品线轴补全 |
| Snapshot Expected | Probe 不提交 |
| Pending Expected | 无 |
| PlanOrigin | NEW_REQUEST |
| Acceptance | 产品线粒度完整 |

### PA-09 Application + Product Line

| Field | 内容 |
|---|---|
| Case ID | PA-09 |
| User Input | `11月版本应用A及其所属产品线的缺陷密度对比` |
| Request Shape | 形态 C：应用+产品线平级 |
| Direct Plan | 单轴 |
| Coverage | PARTIAL |
| Expected Route | Probe → Agent |
| Probe Expected | 应用轴 Probe |
| Agent Expected | 产品线轴补全+对比 |
| Snapshot Expected | Probe 不提交 |
| Pending Expected | 无 |
| PlanOrigin | NEW_REQUEST |
| Acceptance | 对比基于两轴真实数据 |

### PA-10 Application + Statistical Group

| Field | 内容 |
|---|---|
| Case ID | PA-10 |
| User Input | `11月版本应用A各统计组的缺陷密度` |
| Request Shape | 形态 B/C：应用+统计组 |
| Direct Plan | 无统计组展开 |
| Coverage | PARTIAL |
| Expected Route | Probe → Agent |
| Probe Expected | 应用级 Probe |
| Agent Expected | 统计组轴补全 |
| Snapshot Expected | Probe 不提交 |
| Pending Expected | 无 |
| PlanOrigin | NEW_REQUEST |
| Acceptance | 统计组粒度完整 |

### PA-11 Department + Person

| Field | 内容 |
|---|---|
| Case ID | PA-11 |
| User Input | `11月版本杭州开发一部每个人的缺陷密度` |
| Request Shape | 形态 B：部门+人（人=待发现集合，O-2） |
| Direct Plan | 无法按人展开 |
| Coverage | PARTIAL |
| Expected Route | Probe → Agent |
| Probe Expected | 部门级 Probe |
| Agent Expected | 逐人补全 |
| Snapshot Expected | Probe 不提交 |
| Pending Expected | 无 |
| PlanOrigin | NEW_REQUEST |
| Acceptance | 全员覆盖；人名钉定/消歧契约不回归 |

### PA-12 Department + Application + Person

| Field | 内容 |
|---|---|
| Case ID | PA-12 |
| User Input | `11月版本杭州开发一部各应用中张三的缺陷密度` |
| Request Shape | 三维：部门+应用+人（B/C 复合） |
| Direct Plan | 无法表达 |
| Coverage | PARTIAL |
| Expected Route | Probe → Agent |
| Probe Expected | 部门或应用级单轴 Probe |
| Agent Expected | 组合轴补全 |
| Snapshot Expected | Probe 不提交 |
| Pending Expected | 无 |
| PlanOrigin | NEW_REQUEST |
| Acceptance | 组合查询结果正确 |

### PA-13 Department + Product Line + Statistical Group

| Field | 内容 |
|---|---|
| Case ID | PA-13 |
| User Input | `11月版本杭州开发一部产品线B各统计组的缺陷密度` |
| Request Shape | 形态 E：部门→产品线→统计组链式 |
| Direct Plan | 无法表达 |
| Coverage | PARTIAL |
| Expected Route | Probe → Agent |
| Probe Expected | 可行的最上游单轴 Probe |
| Agent Expected | 链式补全 |
| Snapshot Expected | Probe 不提交 |
| Pending Expected | 无 |
| PlanOrigin | NEW_REQUEST |
| Acceptance | 链式结构完整 |

### PA-14 Multi-Level Drill-down

| Field | 内容 |
|---|---|
| Case ID | PA-14 |
| User Input | `11月版本杭州开发一部下各应用的缺陷密度，再下钻到统计组` |
| Request Shape | 形态 E：两级下钻（Case 5） |
| Direct Plan | 仅一级或无下钻 |
| Coverage | PARTIAL |
| Expected Route | Probe → Agent |
| Probe Expected | 一级下钻 Probe 验证 |
| Agent Expected | 二级下钻补全 |
| Snapshot Expected | Probe 不提交 |
| Pending Expected | 无 |
| PlanOrigin | NEW_REQUEST |
| Acceptance | 下钻契约（默认下钻一级/粒度冲突消解）不回归 |

### PA-15 Nested Aggregation

| Field | 内容 |
|---|---|
| Case ID | PA-15 |
| User Input | `11月版本各部门中每个应用里缺陷密度最高的人` |
| Request Shape | 嵌套聚合（部门→应用→人内取最值） |
| Direct Plan | 不支持嵌套聚合执行形态 |
| Coverage | UNSUPPORTED 或 PARTIAL（依 1A-0 审计结果定） |
| Expected Route | UNSUPPORTED→Agent；PARTIAL→Probe → Agent |
| Probe Expected | 仅当 Coverage=PARTIAL 时执行可行子集 |
| Agent Expected | 完成嵌套聚合 |
| Snapshot Expected | Probe 不提交 |
| Pending Expected | 无 |
| PlanOrigin | NEW_REQUEST |
| Acceptance | 嵌套结果正确；UNSUPPORTED 分支不产生 Probe 调用 |

### PA-16 Grouped Ranking

| Field | 内容 |
|---|---|
| Case ID | PA-16 |
| User Input | `11月版本各统计组按缺陷密度排名并列出各组最高的人` |
| Request Shape | 分组排名（组内聚合+组间排名） |
| Direct Plan | 仅组间或仅组内 |
| Coverage | PARTIAL |
| Expected Route | Probe → Agent |
| Probe Expected | 可行子集 Probe |
| Agent Expected | 补全并整合排名 |
| Snapshot Expected | Probe 不提交 |
| Pending Expected | 无 |
| PlanOrigin | NEW_REQUEST |
| Acceptance | 排名正确、组内最值正确 |

### PA-17 Objective Integration

| Field | 内容 |
|---|---|
| Case ID | PA-17 |
| User Input | `对比11月版本国库组和产品线B的缺陷密度并给出结论` |
| Request Shape | 双轴 + 结论整合（Integration=分析结论） |
| Direct Plan | 双轴可执行但结论整合超单次执行 |
| Coverage | PARTIAL |
| Expected Route | Probe → Agent |
| Probe Expected | 双 Invocation 有界 Probe（预算内）或单轴 Probe |
| Agent Expected | 整合出结论 |
| Snapshot Expected | Probe 不提交 |
| Pending Expected | 无 |
| PlanOrigin | NEW_REQUEST |
| Acceptance | 结论可追溯至真实执行结果 |

### PA-18 Unknown Dimension

| Field | 内容 |
|---|---|
| Case ID | PA-18 |
| User Input | `11月版本XX小组的缺陷密度`（XX小组未配置） |
| Request Shape | Unknown Dimension Signal（I-10：不得拒绝） |
| Direct Plan | 维度无法解析 |
| Coverage | UNCERTAIN |
| Expected Route | Probe → Agent |
| Probe Expected | 不执行或执行无维度约束 Probe（依资格）；Evidence 记录 Unknown |
| Agent Expected | 自行解析/说明 |
| Snapshot Expected | Probe 不提交 |
| Pending Expected | 无 |
| PlanOrigin | NEW_REQUEST |
| Acceptance | 不出现「维度不存在拒绝执行」；原因码 UNKNOWN_DIMENSION_SIGNAL |

### PA-19 Dynamic Dimension + Unsupported Execution Shape

| Field | 内容 |
|---|---|
| Case ID | PA-19 |
| User Input | `11月版本按自定义标签统计缺陷`（动态维度执行形态不支持） |
| Request Shape | 动态维度语义不确定/执行形态不支持（Case 10） |
| Direct Plan | 不支持该形态 |
| Coverage | UNSUPPORTED |
| Expected Route | Agent（不 Probe） |
| Probe Expected | 不执行（PROBE_UNSUPPORTED 语义，跳过） |
| Agent Expected | ReAct 自行处理 |
| Snapshot Expected | 无 Probe 副作用 |
| Pending Expected | 无 |
| PlanOrigin | NEW_REQUEST |
| Acceptance | 无 Probe 资源消耗；原因码 DYNAMIC_DIM_SEMANTICS_UNCERTAIN / PLAN_COVERAGE_UNSUPPORTED |

### PA-20 Invocation Plan Mismatch

| Field | 内容 |
|---|---|
| Case ID | PA-20 |
| User Input | `11月版本国库组按周的缺陷趋势` |
| Request Shape | 趋势=多时点重复调用；Plan 为单点 |
| Direct Plan | 单点 Invocation |
| Coverage | PARTIAL（INVOCATION_PLAN_MISMATCH，CV-5） |
| Expected Route | Probe → Agent |
| Probe Expected | 单点 Probe |
| Agent Expected | 多时点补全+趋势整合 |
| Snapshot Expected | Probe 不提交 |
| Pending Expected | 无 |
| PlanOrigin | NEW_REQUEST |
| Acceptance | 趋势完整；原因码 INVOCATION_PLAN_MISMATCH |

### PA-21 Partial Probe

| Field | 内容 |
|---|---|
| Case ID | PA-21 |
| User Input | PA-01 类请求，Probe 预算内仅执行了第一个 Objective |
| Request Shape | 同 PA-01 |
| Direct Plan | 多 Invocation，Probe 预算截断 |
| Coverage | PARTIAL |
| Expected Route | Probe → Agent |
| Probe Expected | Evidence resultStatus=PARTIAL，objectiveCoverage 明确列出未覆盖 Objective |
| Agent Expected | 识别缺口，补执行 |
| Snapshot Expected | Probe 不提交 |
| Pending Expected | 无 |
| PlanOrigin | NEW_REQUEST |
| Acceptance | Partial 声明强制存在；Agent 不把 Partial 当 Full（§19.2） |

### PA-22 Probe Failure

| Field | 内容 |
|---|---|
| Case ID | PA-22 |
| User Input | PA-01 类请求，Probe 脚本报错 |
| Request Shape | 同 PA-01 |
| Direct Plan | 同 PA-01 |
| Coverage | PARTIAL |
| Expected Route | Probe → Agent |
| Probe Expected | PROBE_EXECUTION_FAILED；Evidence resultStatus=FAILED+executionError |
| Agent Expected | 无 Evidence 复用，全量自行完成；不被阻断（ISO-8） |
| Snapshot Expected | 无污染 |
| Pending Expected | 无 |
| PlanOrigin | NEW_REQUEST |
| Acceptance | Agent 正常完成；不计入异常率 |

### PA-23 Probe Empty

| Field | 内容 |
|---|---|
| Case ID | PA-23 |
| User Input | PA-01 类请求，Probe 返回空结果 |
| Direct Plan | 同 PA-01 |
| Coverage | PARTIAL |
| Expected Route | Probe → Agent |
| Probe Expected | PROBE_EMPTY_RESULT；Evidence resultStatus=EMPTY |
| Agent Expected | 自行执行并解释空结果 |
| Snapshot Expected | 无污染 |
| Pending Expected | 无 |
| PlanOrigin | NEW_REQUEST |
| Acceptance | 空结果不被伪装为成功 |

### PA-24 Probe Timeout

| Field | 内容 |
|---|---|
| Case ID | PA-24 |
| User Input | PA-01 类请求，Probe 超过时间预算 |
| Direct Plan | 同 PA-01 |
| Coverage | PARTIAL |
| Expected Route | Probe → Agent |
| Probe Expected | PROBE_TIMEOUT；Evidence resultStatus=TIMEOUT+timeoutStatus |
| Agent Expected | 全量自行完成 |
| Snapshot Expected | 无污染 |
| Pending Expected | 无 |
| PlanOrigin | NEW_REQUEST |
| Acceptance | 超时预算被强制；不计入异常率 |

### PA-25 Probe Truncated

| Field | 内容 |
|---|---|
| Case ID | PA-25 |
| User Input | PA-01 类请求，Probe 结果超出大小预算被截断 |
| Direct Plan | 同 PA-01 |
| Coverage | PARTIAL |
| Expected Route | Probe → Agent |
| Probe Expected | Evidence resultStatus=TRUNCATED+truncatedStatus |
| Agent Expected | 知晓截断，按需重新执行完整版（ADDITIONAL_EXECUTION） |
| Snapshot Expected | 无污染 |
| Pending Expected | 无 |
| PlanOrigin | NEW_REQUEST |
| Acceptance | 截断结果不被当作完整结果复用 |

### PA-26 Agent Tool Failure

| Field | 内容 |
|---|---|
| Case ID | PA-26 |
| User Input | PA-01 类请求，Agent 补全阶段工具失败 |
| Direct Plan | 同 PA-01 |
| Coverage | PARTIAL |
| Expected Route | Probe → Agent |
| Probe Expected | 同 PA-01（SUCCESS） |
| Agent Expected | 按既有失败语义处理（AG-2）；如实声明未完成部分，不伪装成功 |
| Snapshot Expected | Probe 不提交 |
| Pending Expected | 无 |
| PlanOrigin | NEW_REQUEST |
| Acceptance | 用户得到诚实的部分结果+失败说明 |

### PA-27 Agent Partial Completion

| Field | 内容 |
|---|---|
| Case ID | PA-27 |
| User Input | PA-01 类请求，Agent 覆盖验证（职责 11）失败 |
| Direct Plan | 同 PA-01 |
| Coverage | PARTIAL |
| Expected Route | Probe → Agent |
| Probe Expected | 同 PA-01 |
| Agent Expected | 明确声明无法完成的部分及原因（AG-1/WC-3） |
| Snapshot Expected | 无污染 |
| Pending Expected | 无 |
| PlanOrigin | NEW_REQUEST |
| Acceptance | 无完整结果伪装；完成度报告与缺口清单一致 |

### PA-28 DimensionState Projection Limit

| Field | 内容 |
|---|---|
| Case ID | PA-28 |
| User Input | `上个月各部门的缺陷密度和缺陷密度最高的人`（DimensionState 仅存单一 Peer Dimension 的历史投影） |
| Request Shape | 双 Objective + 部门聚合 + 人发现（Case 11） |
| Direct Plan | 仅覆盖密度部分 |
| Coverage | PARTIAL |
| Expected Route | Probe → Agent |
| Probe Expected | 密度 Probe；Evidence=SUCCESS(Partial) |
| Agent Expected | 补人的发现 |
| Snapshot Expected | 无污染 |
| Pending Expected | 无 |
| PlanOrigin | NEW_REQUEST |
| Acceptance | Router 判定不受 DimensionState 单投影误导（PL-1~PL-3 必须有专项断言） |

## 30.3 Critical Regression（CR-01 ~ CR-16）

### CR-01 Existing Multi-Skill Direct Complete

| Field | 内容 |
|---|---|
| Case ID | CR-01 |
| User Input | `11月版本国库组的缺陷密度和测试用例执行率`（Case 9 现状多执行场景） |
| Request Shape | 多 Skill 完整覆盖 |
| Direct Plan | planMulti 双 Invocation（G1–G7 全通过） |
| Coverage | COMPLETE |
| Expected Route | Fast |
| Probe Expected | 不执行 |
| Agent Expected | 不需要 |
| Snapshot Expected | 各 Invocation 正式提交 |
| Pending Expected | 无 |
| PlanOrigin | NEW_REQUEST |
| Acceptance | 现有多执行行为零回归（917 测试基线不劣化） |

### CR-02 G1–G7

| Field | 内容 |
|---|---|
| Case ID | CR-02 |
| User Input | `用缺陷密度脚本查11月版本国库组`（G1 点名）等 7 组代表性输入，逐 Gate 验证 |
| Request Shape | 各 Gate 对应形态 |
| Direct Plan | 各 Gate 预期 Plan |
| Coverage | COMPLETE（各 Gate 场景） |
| Expected Route | Fast（资格判定路径先于 Router 完成，Router 不改变其结果） |
| Probe Expected | 不执行 |
| Agent Expected | 不需要 |
| Snapshot Expected | 正式提交 |
| Pending Expected | 按 Gate 场景既有语义 |
| PlanOrigin | NEW_REQUEST |
| Acceptance | G1–G7 逐 Gate 判定结果与重建前完全一致；Router 不得改变资格判定（I-6） |

### CR-03 Mixed Ask / Exec

| Field | 内容 |
|---|---|
| Case ID | CR-03 |
| User Input | `11月版本国库组缺陷密度是多少？另外张三的认证编号是多少`（混排） |
| Request Shape | Ask + Exec 混合轮 |
| Direct Plan | Ask 子请求产生 missingAsk |
| Coverage | 按混合轮既有契约 |
| Expected Route | 混排反问契约生效；Executable 部分按 CR-02 Option A：Ask 豁免、Executable 仍受判定 |
| Probe Expected | 不执行（Ask 形态不进 Probe） |
| Agent Expected | 不需要 |
| Snapshot Expected | 既有语义 |
| Pending Expected | pending.put ⟺ ask plan 存在（PD-1） |
| PlanOrigin | NEW_REQUEST |
| Acceptance | 混排反问契约零回归；仅 multi.enabled（默认 false）可达的 M-C 行为不变 |

### CR-04 Ambiguous Time

| Field | 内容 |
|---|---|
| Case ID | CR-04 |
| User Input | `最近国库组的缺陷情况`（时间歧义） |
| Request Shape | 时间歧义（既有歧义短路） |
| Direct Plan | Ask（缺时间） |
| Coverage | 既有语义 |
| Expected Route | 既有反问链路（不进 Probe/Agent；歧义短路优先于 Router） |
| Probe Expected | 不执行 |
| Agent Expected | 不需要 |
| Snapshot Expected | 既有语义 |
| Pending Expected | pending.put ⟺ ask plan 存在 |
| PlanOrigin | NEW_REQUEST |
| Acceptance | 时间歧义行为零回归 |

### CR-05 Ambiguous Person

| Field | 内容 |
|---|---|
| Case ID | CR-05 |
| User Input | `查一下张伟的缺陷`（多人重名） |
| Request Shape | 人名歧义（既有消歧） |
| Direct Plan | Ask（消歧反问） |
| Coverage | 既有语义 |
| Expected Route | 既有反问链路 |
| Probe Expected | 不执行 |
| Agent Expected | 不需要 |
| Snapshot Expected | 既有语义 |
| Pending Expected | 消歧候选 pending 不错位（11/23 修复不回归） |
| PlanOrigin | NEW_REQUEST |
| Acceptance | 人名消歧/会话钉定零回归 |

### CR-06 Pending Consume Short-circuit

| Field | 内容 |
|---|---|
| Case ID | CR-06 |
| User Input | 反问后追问 `是国库组` |
| Request Shape | Pending 续轮 |
| Direct Plan | consumePending 短路（planAll 最前 :573-578） |
| Coverage | 既有语义 |
| Expected Route | 不经主路径 Router（PlanOrigin=PENDING_CONTINUATION 走既有链路，PO-2） |
| Probe Expected | 不执行 |
| Agent Expected | 不需要 |
| Snapshot Expected | 既有语义 |
| Pending Expected | 短路消费；TTL 5min 过期按真实分支归属 origin |
| PlanOrigin | PENDING_CONTINUATION |
| Acceptance | 短路行为零回归 |

### CR-07 Script Failure Continuation

| Field | 内容 |
|---|---|
| Case ID | CR-07 |
| User Input | FP-04 类多执行，其中一脚本失败 |
| Request Shape | 多 Skill |
| Direct Plan | planMulti |
| Coverage | COMPLETE |
| Expected Route | Fast |
| Probe Expected | 不执行 |
| Agent Expected | 不需要 |
| Snapshot Expected | 失败 Invocation 不提交成功历史 |
| Pending Expected | 无 |
| PlanOrigin | NEW_REQUEST |
| Acceptance | 失败继续（Script Failure Continuation）零回归 |

### CR-08 Snapshot Isolation

| Field | 内容 |
|---|---|
| Case ID | CR-08 |
| User Input | PA-01 类请求 + 已有 Committed Snapshot 场景 |
| Request Shape | 同 PA-01 |
| Direct Plan | 同 PA-01 |
| Coverage | PARTIAL |
| Expected Route | Probe → Agent |
| Probe Expected | 执行；全程不触碰 Committed Snapshot（ISO-2/3） |
| Agent Expected | 正常 |
| Snapshot Expected | Committed Snapshot 前后一致（专项断言） |
| Pending Expected | 无 |
| PlanOrigin | NEW_REQUEST |
| Acceptance | 12 不变式 + 13 行状态转移表全部通过 |

### CR-09 Candidate Snapshot

| Field | 内容 |
|---|---|
| Case ID | CR-09 |
| User Input | PA-01 类请求，Probe 运行期触发规划期 recordLastExec(:1311) |
| Request Shape | 同 PA-01 |
| Direct Plan | 同 PA-01 |
| Coverage | PARTIAL |
| Expected Route | Probe → Agent |
| Probe Expected | Candidate 域写入被隔离，不晋升 Committed |
| Agent Expected | 正常 |
| Snapshot Expected | Candidate/Committed 生命周期隔离 |
| Pending Expected | 无 |
| PlanOrigin | NEW_REQUEST |
| Acceptance | 规划期写入不进入 Committed；S1→S2→Turn3 连续多轮污染测试通过 |

### CR-10 Committed Snapshot

| Field | 内容 |
|---|---|
| Case ID | CR-10 |
| User Input | FP-01 类请求，正式成功执行 |
| Request Shape | 同 FP-01 |
| Direct Plan | 同 FP-01 |
| Coverage | COMPLETE |
| Expected Route | Fast |
| Probe Expected | 不执行 |
| Agent Expected | 不需要 |
| Snapshot Expected | 正式提交 Committed（TTL 30min） |
| Pending Expected | 无 |
| PlanOrigin | NEW_REQUEST |
| Acceptance | 提交门槛（既有契约正式成功执行才提交）零回归 |

### CR-11 PlanOrigin

| Field | 内容 |
|---|---|
| Case ID | CR-11 |
| User Input | 三组输入分别覆盖 NEW_REQUEST / PENDING_CONTINUATION / LEGACY_FOLLOWUP |
| Request Shape | 各自形态 |
| Direct Plan | 各自 Plan |
| Coverage | 各自 |
| Expected Route | 仅 NEW_REQUEST 经 Router；其余走既有链路（PO-2） |
| Probe Expected | 仅 NEW_REQUEST 可能 |
| Agent Expected | 仅 NEW_REQUEST 可能 |
| Snapshot Expected | 域标记正确 |
| Pending Expected | 按各自语义 |
| PlanOrigin | 三值显式标记，零文本推断 |
| Acceptance | PlanOrigin 产生条件唯一合法；Router 越界为零 |

### CR-12 V1

| Field | 内容 |
|---|---|
| Case ID | CR-12 |
| User Input | FP-01 与 PA-01 各跑一遍 `/ai/chat`（Vue 流量） |
| Request Shape | 同 FP-01 / PA-01 |
| Direct Plan | 同 |
| Coverage | 同 |
| Expected Route | Fast / Probe → Agent |
| Probe Expected | 仅 PA-01 |
| Agent Expected | 仅 PA-01 |
| Snapshot Expected | 无污染 |
| Pending Expected | v1 写点位置经 1A-0 排查（VP-3③） |
| PlanOrigin | NEW_REQUEST |
| Acceptance | v1 端点功能完整；v1 planAll(:499) 空列表分支已验证（VP-3①） |

### CR-13 V2

| Field | 内容 |
|---|---|
| Case ID | CR-13 |
| User Input | FP-01 与 PA-01 各跑一遍 `/v2/ai/chat`（React /pm 流量） |
| Request Shape | 同 FP-01 / PA-01 |
| Direct Plan | 同 |
| Coverage | 同 |
| Expected Route | Fast / Probe → Agent |
| Probe Expected | 仅 PA-01 |
| Agent Expected | 仅 PA-01 |
| Snapshot Expected | 无污染 |
| Pending Expected | v2 既有语义（:442/:453） |
| PlanOrigin | NEW_REQUEST |
| Acceptance | v2 端点功能完整；ReAct 空列表兜底（:504-525）不回归 |

### CR-14 V1 / V2 Parity

| Field | 内容 |
|---|---|
| Case ID | CR-14 |
| User Input | FP-01/PA-01/PA-18 三例双端点同输入对比 |
| Request Shape | 同 |
| Direct Plan | 同 |
| Coverage | 同 |
| Expected Route | 双端点 RouteCode/ReasonCodes 一致 |
| Probe Expected | 一致 |
| Agent Expected | 一致 |
| Snapshot Expected | 各端点按各自既有语义 |
| Pending Expected | 各端点按各自既有语义 |
| PlanOrigin | 一致 |
| Acceptance | 决策一致（协议呈现允许端点差异：v1 反问纯 text 事件）；指标按 endpoint 拆分可对账 |

### CR-15 Shadow Mode

| Field | 内容 |
|---|---|
| Case ID | CR-15 |
| User Input | FP-01 与 PA-01 各跑一遍（ENFORCEMENT=OFF, SHADOW=ON） |
| Request Shape | 同 |
| Direct Plan | 同 |
| Coverage | 同 |
| Expected Route | 实际执行=既有路径；Shadow 记录 Expected Route |
| Probe Expected | **不执行**（Shadow 冻结） |
| Agent Expected | 实际不触发 |
| Snapshot Expected | 零污染 |
| Pending Expected | 零污染 |
| PlanOrigin | 正常（走既有路径） |
| Acceptance | Shadow 记录完整含 10 字段（§28.1）；执行路径与 OFF 模式一致 |

### CR-16 Kill Switch

| Field | 内容 |
|---|---|
| Case ID | CR-16 |
| User Input | ENFORCEMENT 运行中切换 Kill Switch OFF |
| Request Shape | 同 FP-01 |
| Direct Plan | 同 |
| Coverage | 同 |
| Expected Route | 立即回退既有路径（无 Router 参与） |
| Probe Expected | 不执行 |
| Agent Expected | 不触发 |
| Snapshot Expected | 无污染 |
| Pending Expected | 无 |
| PlanOrigin | NEW_REQUEST |
| Acceptance | 回退及时（有界 TTL 缓存失效窗口内）；三类故障分法正确归类 |

---

# 31. Negative Tests

以下场景必须失败/拒绝/降级，不允许通过：

| # | 场景 | 期望 |
|---|---|---|
| NG-1 | High Confidence + Coverage=PARTIAL | 不得进入 Fast Path（C-2） |
| NG-2 | Probe Success | 不得被当作 Request Completion（I-4） |
| NG-3 | Partial Evidence | Agent 不得复用为 Full Result（§19.2） |
| NG-4 | Probe 运行期触发 recordLastExec | 不得进入 Committed Snapshot（CR-09） |
| NG-5 | Unknown Dimension + 硬编码拒绝逻辑 | 禁止拒绝执行，必须 UNKNOWN_DIMENSION_SIGNAL（I-10） |
| NG-6 | Router 内 LLM 调用 | 禁止（I-1） |
| NG-7 | Router 硬编码维度/指标目录 | 禁止（I-2） |
| NG-8 | 仅依据 DimensionState 判定单维度 | 禁止（PL-1） |
| NG-9 | 仅依据「和/最高/对比」判复杂 | 禁止（M-3） |
| NG-10 | Probe 执行副作用工具 | 禁止（E-11） |
| NG-11 | 无 eligible Invocation 时仍发起 Probe | 禁止（PE-1） |
| NG-12 | Agent 只接收剩余部分请求 | 禁止（ACX-2：Goal=完整原始请求） |
| NG-13 | 部分结果伪装完整结果 | 禁止（WC-3/AG-1） |
| NG-14 | Probe 创建/消费正式 Pending | 禁止（ISO-4） |
| NG-15 | 文本推断 PlanOrigin | 禁止（PO-1） |
| NG-16 | Router 改写 Multi-Skill G1–G7 资格判定 | 禁止（I-6） |
| NG-17 | Confidence 无命中依据的黑盒分数 | 禁止（C-1） |
| NG-18 | 日志记录原始用户请求/敏感标识 | 禁止（I-8） |
| NG-19 | Shadow Mode 执行 Probe | 禁止（§28.1 冻结） |
| NG-20 | 失败的 Probe 计入 H7 异常率 | 禁止（§17.1 六态为正常分支） |
| NG-21 | 伪概率 Confidence（无依据小数分数） | 禁止（I-1/C-1） |
| NG-22 | 输出实体（「那个人」）被反问为缺失参数 | 禁止（O-2） |

---

# 32. Failure Handling

## 32.1 三类故障分法（冻结）

| 类别 | 定义 | 处理 |
|---|---|---|
| 配置不可用 | 模式配置/DB 配置读取失败 | fail-closed：按 OFF 行为回退（保底既有路径）；有界 TTL 缓存续用旧值并在窗口后过期 |
| 算法异常 | Router/Shape 分析内部异常 | fail-closed：绝不 Fast Path；Probe 可用→PROBE_THEN_AGENT，否则 AGENT_FALLBACK；原因码 ROUTER_EXCEPTION_FAIL_CLOSED |
| 主动回滚 | Kill Switch / 运维切换 | 立即回退既有路径；记录切换事件 |

## 32.2 分类规则

- FH-1：Probe 六态（成功/空/失败/无 Plan/不支持/超时）是**正常分支**，不属于上述任何故障类别，不计入异常率。
- FH-2：H7 口径不变：异常率严格 <0.5%（等于也 BLOCKED）；系统性异常（<0.5% 但同类输入稳定触发）单独条款阻断。
- FH-3：配置读取失败≠算法异常≠主动回滚，三者必须可区分（原因码+指标）。
- FH-4：任何故障路径不得产生用户可见的空白输出（对齐 v1 planAll 空列表风险——1A-0 必须先验证）。

---

# 33. Phase 1A Implementation Sequence

阶段依赖链：1A-0 → 1A-1 → 1A-2 → 1A-3 → (1A-4 → 1A-5 → 1A-6) → 1A-7 → 1A-8 → 1A-9 → 1A-10 → 1A-11 → 1A-12。不允许跳过基础契约直接进入 Agent 编码。

### 1A-0 — Baseline & Compatibility Audit

| 字段 | 内容 |
|---|---|
| Scope | 确认 V1/V2 Pipeline、Direct Executor、Multi-Skill（G1–G7）、Snapshot、Pending、PlanOrigin 现状；v1 planAll(:499) 空列表分支验证（最高优先级）；v1 SSE 反问轮协议确认；v1 写点位置排查；DimensionState 单 Peer 投影的代码级确认（D-7）；Probe 资格所需的 Skill 副作用/耗时/结果规模元数据调研 |
| Dependencies | 无（起点） |
| Deliverables | 审计报告（含与本文档契约的差异清单）；实施挂接点清单（双端点） |
| Tests | 只读验证；关键分支最小复现 |
| Exit Criteria | 差异清单闭环；VP-3 三项前置风险全部有结论 |
| Risks | v1 空列表无 fallback 导致 Yield 退化；multi.enabled 生效配置误判 |

### 1A-1 — Request Shape Model

| 字段 | 内容 |
|---|---|
| Scope | 建立 Objective / Metric / Dimension / Hierarchy / Invocation Shape 的结构化表示（§3.1 逻辑模型的代码化，依现有代码模型 I-9） |
| Dependencies | 1A-0 |
| Deliverables | Shape 模型 + 信号枚举 + 提取器（只读消费 IN-1~IN-6） |
| Tests | 单测：五视角提取；Case 1/4/10/11 形态识别 |
| Exit Criteria | 五视角全覆盖；UNCERTAIN 降级路径可用；零 LLM 依赖 |
| Risks | 信号枚举基数失控（需低基数约束） |

### 1A-2 — Rule-Based Fast Path Router

| 字段 | 内容 |
|---|---|
| Scope | 实现确定性 Router（§10 决策表 + §12 原因码）；禁 LLM 分类 |
| Dependencies | 1A-1 |
| Deliverables | Router 核心（可双端点共享）+ 决策日志（I-8 合规） |
| Tests | 决策表全分支单测；确定性（同输入同输出）；fail-closed 注入测试 |
| Exit Criteria | §10 决策表全覆盖；NG-6/7/8/9 全部有断言 |
| Risks | 规则间冲突（须 fail-closed 兜底） |

### 1A-3 — Direct Coverage Guard

| 字段 | 内容 |
|---|---|
| Scope | 验证 Direct Plan 是否完整覆盖原始 Request（§9）；Hard Coverage Constraints 优先于 Confidence |
| Dependencies | 1A-2 |
| Deliverables | 覆盖评估器（四态输出）+ CV-1~CV-7 规则实现 |
| Tests | CV 规则逐条单测；NG-1 断言 |
| Exit Criteria | 四态判定可解释（附依据）；与 Router 决策表集成通过 |
| Risks | 覆盖误判（COMPLETE 假阳性）——Shadow 验证重点 |

### 1A-4 — Direct Probe

| 字段 | 内容 |
|---|---|
| Scope | 实现 bounded Probe：资格审计（§14）、边界（§15）、至多一次、复用现有执行 |
| Dependencies | 1A-3 |
| Deliverables | Probe 执行器 + 资格配置（配置源声明，不硬编码） |
| Tests | 六态全分支；边界预算强制；NG-10/11 断言 |
| Exit Criteria | 六态可复现；预算强制生效；副作用工具零调用 |
| Risks | Probe 成本失控（预算默认保守值 D-4） |

### 1A-5 — Evidence Model

| 字段 | 内容 |
|---|---|
| Scope | 实现 Probe Result → Evidence 转换、存储和传递契约（§18） |
| Dependencies | 1A-4 |
| Deliverables | ProbeEvidence 模型（16 概念字段按代码现实裁剪）+ 七态映射 + 域标记 |
| Tests | 七态映射单测；ISO-10 持久化过滤断言 |
| Exit Criteria | Evidence 端到端可传递；域标记不可丢失 |
| Risks | 字段裁剪导致复用判定信息不足 |

### 1A-6 — Evidence-Augmented Agent Fallback

| 字段 | 内容 |
|---|---|
| Scope | Agent 接收原始 Request + Probe Evidence 并完成剩余目标（§20/§21） |
| Dependencies | 1A-5 |
| Deliverables | Agent 上下文装配（8 项）+ 十二职责的 prompt/工具约束 |
| Tests | PA-01/21/26/27 场景测试；NG-12/13 断言 |
| Exit Criteria | 八项输入齐备可断言；覆盖验证（职责 11）可执行 |
| Risks | 小模型 Evidence 误信（§19.2 禁令需 prompt+校验双保险） |

### 1A-7 — Snapshot / Pending / PlanOrigin Isolation

| 字段 | 内容 |
|---|---|
| Scope | 保证 Probe 与正式执行状态隔离（§16 十点 + §23/§24/§25）；recordLastExec(:1311) Candidate 域隔离强制验证 |
| Dependencies | 1A-4（可与 1A-5/6 并行） |
| Deliverables | 隔离断言套件 + 域标记贯通验证 |
| Tests | CR-08/09/10/11 全量；S1→S2→Turn3 污染测试 |
| Exit Criteria | 12 不变式 + 13 行状态表全部通过 |
| Risks | 隐蔽共享写点遗漏（1A-0 排查清单核对） |

### 1A-8 — V1 / V2 Integration

| 字段 | 内容 |
|---|---|
| Scope | 分别接入两个 Pipeline，保持协议独立（§27） |
| Dependencies | 1A-2（Fast Path 部分）~ 1A-6（完整链路） |
| Deliverables | 双端点挂接 + 端点维度模式配置 |
| Tests | CR-12/13/14；v1 反问纯 text 事件协议断言 |
| Exit Criteria | 双端点决策一致；SSE 协议零合并 |
| Risks | v1 空列表分支（1A-0 未闭环则阻塞本阶段） |

### 1A-9 — Shadow Mode

| 字段 | 内容 |
|---|---|
| Scope | 验证 Router Decision，建立真实数据指标（§28）；不执行 Probe |
| Dependencies | 1A-8 |
| Deliverables | Shadow 旁路记录（10 字段）+ 15 指标报表 |
| Tests | CR-15；路径一致性（与 OFF 一致）断言 |
| Exit Criteria | 15 指标可产出；零副作用断言通过 |
| Risks | Shadow 记录基数过大（采样/聚合策略） |

### 1A-10 — Golden E2E

| 字段 | 内容 |
|---|---|
| Scope | 覆盖 Fast Path、Probe、Agent、完整性和回归案例（§30 全部 52 例） |
| Dependencies | 1A-9 |
| Deliverables | 52 例 E2E 套件 + 结果矩阵 |
| Tests | §30 逐例 Acceptance 字段断言；§31 Negative Tests 全量 |
| Exit Criteria | 52 例全 PASS；负向测试全 FAIL-as-expected |
| Risks | 环境依赖（gauss/MySQL）导致的 flaky |

### 1A-11 — Independent Review

| 字段 | 内容 |
|---|---|
| Scope | 独立架构、代码、契约和回归审查 |
| Dependencies | 1A-10 |
| Deliverables | 审查报告（Critical/Major/Minor 分级；Minor 须记录不修复理由） |
| Tests | §35 Checklist 逐项 |
| Exit Criteria | Critical/Major 清零 |
| Risks | 独立性不可得时按 SELF-REVIEW — NOT INDEPENDENT 标注（§35 前置说明） |

### 1A-12 — Enforcement Readiness

| 字段 | 内容 |
|---|---|
| Scope | 满足 Gate（§36）后才允许切换 Enforcement |
| Dependencies | 1A-11 |
| Deliverables | Gate 评审材料 + 切换预案（含 Kill Switch 演练） |
| Tests | CR-16 Kill Switch 演练；三类故障注入 |
| Exit Criteria | §36 Gate 全绿 |
| Risks | 指标阈值未定标（D-5/D-4）阻塞切换 |

---

# 34. Acceptance Criteria

| # | 验收项 | 判定 |
|---|---|---|
| AC-01 | 两份 V2 文档自包含，读者无需旧文档即可理解当前架构 | 阅读测试 |
| AC-02 | 30 节（Doc 1）+ 37 节（Doc 2）结构完整 | 章节核对 |
| AC-03 | 五视角 Request Shape + 四态 Coverage 完整定义 | §3 |
| AC-04 | Fast Path 十项准入 + 负向证据 | §13 |
| AC-05 | Probe 六态 + Evidence 七态 + 16 概念字段 | §17/§18 |
| AC-06 | Agent 八项输入 + 十二职责 + 复用九维 | §20/§21/§19 |
| AC-07 | Hard Coverage Constraints 优先于 Confidence，0.8 标注为未验证设计值 | §11 |
| AC-08 | Golden Matrix ≥ 51 例 × 12 字段，覆盖任务书 §17 全清单 | §30（52 例） |
| AC-09 | Negative Tests ≥ 22 项 | §31 |
| AC-10 | 三类故障分法 + H7 口径 + Probe 正常分支 | §32 |
| AC-11 | 1A-0~1A-12 每阶段 6 字段齐备 | §33 |
| AC-12 | 冻结决策可溯源：Option C/R1-R8/PlanOrigin/Snapshot/Pending/Whole-Request Yield/G1–G7/V1-V2/Shadow 全部保留 | §23–§27 + Doc 1 §5 |
| AC-13 | Shadow 不执行 Probe | §28.1 |
| AC-14 | Case 1–11 全部进入 Golden Matrix 或有明确映射 | §30 映射：Case 1→PA-01、Case 2→PA-04、Case 3→FP-03、Case 4→PA-06、Case 5→PA-14、Case 6→FP-04、Case 7→PA-02、Case 8→FP-02、Case 9→CR-01、Case 10→PA-19、Case 11→PA-28 |
| AC-15 | 禁止项全部落实：硬编码目录/LLM 路由/伪概率/DimensionState 唯一依赖/Probe 副作用 | §31 |
| AC-16 | 日志/指标合规（无原始请求、低基数） | §29 |

---

# 35. Independent Review Checklist

> 前置说明：独立复核若因子代理模型不可用而无法执行，必须显式标注 **SELF-REVIEW — NOT INDEPENDENT**（继承冻结契约 §18 规则），并逐项完成自查。

**Architecture（16 项）**

1. Fast Path 是否保持简单零开销（不引入新执行层）？
2. Request Shape 五视角是否完整（缺一即 Major）？
3. 是否存在以维度数量为唯一判据的规则？
4. 是否存在「多指标 ≡ Agent」的错误等价？
5. Router 是否完全确定性（无 LLM/无随机/无黑盒）？
6. UNSUPPORTED 是否直达 Agent（不浪费 Probe）？
7. Probe 是否有界且至多一次？
8. Evidence 是否防误信（六不得 + 九维）？
9. Agent Goal 是否为完整原始请求？
10. Completion 公式五项是否齐全？
11. DimensionState 投影限制是否可断言（PL-1~3）？
12. 未知维度是否绝不拒绝（I-10）？
13. fail-closed 路径是否明确且可达？
14. 双端点是否对称覆盖？
15. v1 前置风险（VP-3）是否显式保留？
16. Mode C / Shadow Probe 延期决策是否清晰（不做半实现）？

**Contract（8 项）**

17. 每个契约是否有可测试的判定（非描述性散文）？
18. 原因码是否低基数且可解释？
19. Probe 资格是否配置源驱动（非硬编码）？
20. Evidence 字段是否标注「依代码裁剪」？
21. 三类故障分法是否可区分（原因码+指标）？
22. Pending/Snapshot/PlanOrigin 是否与冻结原文一致？
23. Negative Tests 是否覆盖全部禁止项？
24. 阶段依赖是否禁止跳步（1A 编码前置契约）？

**Source Traceability（8 项）**

25. R1–R8（Option C 追问层）语义是否原样保留？
26. PlanOrigin 三值 + 显式标记是否保留？
27. Snapshot Candidate/Committed 隔离（12 不变式）是否保留？
28. Pending P1–P3 / A1–A5 是否保留？
29. Whole-Request Yield 作用域是否正确（限追问层；主路径=Agent Fallback）？
30. G1–G7 是否逐字保留？
31. V1/V2 代码事实（:499/:461/:209/:573-578/:442/:453/:504-525/:1311）是否准确引用？
32. 原始评审修正（CR-02 Option A、CR-03、CR-05、CR-09、H7 增强）是否继承？

**Document Consistency（4 项）**

33. Mermaid 图与正文是否一致？
34. Golden Matrix 判定与 Router Decision Policy 是否一致（逐例抽查）？
35. 两份文档之间是否有矛盾表述？
36. 是否残留旧架构表述（LLM-first / 主路径 Guard / Shadow 执行 Probe）？

**处置规则**：Critical / Major 必须修复后才能进入 1A；Minor 记录并说明不修复理由。

---

# 36. Phase 1A Readiness Gate

进入 Phase 1A 实施前，本基线必须满足：

| Gate 条件 | 状态 |
|---|---|
| 两份 V2 文档完成并通过一致性复核 | 本文提交即达成 |
| 1A-0 审计报告产出且 VP-3 三项前置风险有结论 | 1A 第一步 |
| 独立复核（或 SELF-REVIEW — NOT INDEPENDENT 标注）完成，Critical/Major 清零 | 本文 §35 |
| Golden Matrix 判定与契约无矛盾 | §30/§10 交叉核对 |
| 用户对基线冻结的确认 | 用户拍板 |

**Readiness 结论口径**：

- **READY**：全部 Gate 绿。
- **READY WITH CONDITIONS**：存在已记录的 Deferred（D-1~D-8）但不阻塞 1A-0/1A-1 启动。
- **NOT READY**：存在未闭环的 Critical/Major，或 VP-3① 未验证。

> 当前结论：**READY WITH CONDITIONS** —— 条件为：① 0.8 阈值与 Probe 预算为设计值，须 Shadow 定标（D-5/D-4）；② DimensionState 投影限制与 v1 空列表分支须 1A-0 代码级确认（D-7 / D-8 / VP-3①）；③ 独立复核独立性待定（§35 前置说明）。

---

# 37. Deferred Decisions

| # | 决策 | 延期原因 | 决策点 |
|---|---|---|---|
| D-1 | Mode C（Shadow 后的第三模式扩展）延期，不纳入 MVP | 主路径双模式（Fast/Probe→Agent）已覆盖目标；Mode C 无需求实证 | Shadow 数据产出后 |
| D-2 | Probe「一次」的确切含义（单 Invocation vs 单 Plan 集合） | 以现有代码实际执行模型为准 | 1A-0 审查后记录 |
| D-3 | Evidence 具体 DTO/字段裁剪 | 须依 1A-5 现有代码模型确定（I-9） | 1A-5 |
| D-4 | Probe 成本预算具体数值（Invocation/时间/数据量/Tool Call/Result Size） | 须依 Skill 元数据与实测成本定 | 1A-4（默认保守值起步） |
| D-5 | false_fast_path / false_probe 阈值与 0.8 Confidence 定标 | 无真实数据；0.8 为未验证初始设计值 | Shadow 定标（1A-9） |
| D-6 | Fast Path ≥80% 目标的实测校准 | 设计目标非承诺 | Shadow 定标 |
| D-7 | DimensionState「仅保留一个 Peer Dimension」的代码级确认 | 架构层已按 Case 11 保守处理；精确行为须读码 | 1A-0 |
| D-8 | v1 planAll(:499) 空列表分支行为 | 1A-0 最高优先级前置验证 | 1A-0 |
| D-9 | 多 Plan 轮次整体成功标准 | 部分成功 ≠ 整体成功，标准须先明确 | 1A 前置条件 |
| D-10 | M-C 双 Ask 单 pending（:854-861）/ 多实例部署 / 独立 Coverage Runtime | 历史拍板明确不在本期范围 | 后续立项 |

---

*文档结束 — Phase 0.1 V2 Contract / Golden Matrix / 1A Readiness 基线。与《Phase 0.1 — Architecture & Decision Baseline V2》互为引用，共同构成唯一权威实施基线。*
