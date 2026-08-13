**代码级 V1.0 设计**。

先给结论：

> **你的项目不需要重做 Skills 自优化。**
>
> 现有 `SkillEvolutionRunner + SkillDistiller + Trace + Verification + GoldenEvaluationRunner + VersionRegistry` 保留；新增一个 **Evolution Orchestrator + GEPA Evolution Service + Candidate Evaluation Adapter**，把“规则触发”升级成“基于轨迹的反思式进化”。

你现在已经具备很完整的基础设施：Skill 有 `skill_manage / skill_index / skill_candidate / skill_pending_judgement` 等管理与统计结构；同时已有 Trace、Verification、Golden Evaluation、Replay、A/B 和版本管理。

---

# 一、最终架构，我建议定成这个

```text
                         ┌─────────────────────────┐
                         │     AgentScope Java     │
                         │                         │
                         │ HarnessAgent            │
                         │   ├─ SubAgent           │
                         │   └─ Tools              │
                         └────────────┬────────────┘
                                      │
                              Agent Execution
                                      │
                                      ↓
                         ┌─────────────────────────┐
                         │      Trace System       │
                         │                         │
                         │ LLM / Skill / Tool      │
                         │ Tool Result / Error     │
                         │ Verification / Answer   │
                         └────────────┬────────────┘
                                      │
                                      ↓
                         ┌─────────────────────────┐
                         │   Failure Mining        │
                         │                         │
                         │ wrong_tool              │
                         │ wrong_skill             │
                         │ workflow_skip           │
                         │ calculation_error       │
                         │ verification_failure    │
                         └────────────┬────────────┘
                                      │
                                      ↓
                  ┌──────────────────────────────────────┐
                  │       Evolution Orchestrator         │
                  │             Java                    │
                  │                                      │
                  │ Trigger → Dataset → GEPA Job         │
                  └──────────────────┬───────────────────┘
                                     │ HTTP
                                     ↓
                  ┌──────────────────────────────────────┐
                  │       GEPA Evolution Service         │
                  │             Python                   │
                  │                                      │
                  │ DSPy + GEPA                          │
                  │                                      │
                  │ Reflect → Mutate → Search             │
                  └──────────────────┬───────────────────┘
                                     │
                               Candidate Skill
                                     │
                                     ↓
                  ┌──────────────────────────────────────┐
                  │       AgentScope Evaluation          │
                  │                                      │
                  │ Golden Dataset                       │
                  │ Replay                               │
                  │ Verification                         │
                  └──────────────────┬───────────────────┘
                                     │
                                     ↓
                             Candidate Ranking
                                     │
                      ┌──────────────┴──────────────┐
                      ↓                             ↓
                   Reject                        Accept
                      │                             │
                      ↓                             ↓
                   Archive                    Approval
                                                    │
                                                    ↓
                                                Skill V+1
                                                    │
                                                    ↓
                                              Production
```

这里最重要的一刀：

**GEPA 不负责运行 Agent。**

它只负责：

> **根据执行经验，产生更好的 Skill Candidate。**

AgentScope Java 仍然是唯一的 Runtime。

---

# 二、为什么采用 Java + Python，而不是硬塞进 Java

你现在是：

```text
AgentScope Java 2.0.0-RC5
Spring Boot
JDK 21
```

而 Hermes Self-Evolution 当前公开实现本身就是 Python 项目，依赖 `dspy>=3.0.0`，核心就是 DSPy + GEPA。([GitHub][1])

所以最自然的是：

```text
Java
Agent Runtime
Evaluation Runtime
Skill Registry
Trace
Approval
        │
        │ HTTP
        ↓
Python
GEPA
DSPy
Evolution
```

不要做：

```text
Java
 └── Python Runtime
      └── DSPy
           └── GEPA
```

后者部署、依赖、升级都会比较麻烦。

---

# 三、第一步：重构 `SkillEvolutionRunner`

你目前：

```text
SkillEvolutionHook
       ↓
SkillEvolutionRunner
       ↓
recordFailure()
       ↓
failure rate
       ↓
SkillDistiller.evolve()
```

你已有的逻辑大致是：

```text
failure_rate >= 0.3
AND total >= 5
        ↓
evolve()

failure_rate >= 0.6
AND total >= 10
        ↓
blacklist
```



**这个不要删。**

改成：

```text
SkillEvolutionRunner
        │
        ├── recordFailure()
        │
        ├── shouldEvolve()
        │
        └── createEvolutionJob()
                     │
                     ↓
              EvolutionOrchestrator
```

也就是：

```java
public interface SkillEvolutionOrchestrator {

    EvolutionJob trigger(EvolutionTrigger trigger);

}
```

---

# 四、定义 EvolutionTrigger

建议：

```java
public class EvolutionTrigger {

    private String skillName;

    private String skillVersion;

    private TriggerType type;

    private String userId;

    private String sessionId;

    private String traceId;

    private List<String> failureTypes;

    private int failureCount;

    private double failureRate;

}
```

TriggerType：

```java
public enum TriggerType {

    USER_REJECTION,

    TOOL_FAILURE,

    VERIFICATION_FAILURE,

    GOLDEN_EVAL_FAILURE,

    WRONG_TOOL,

    WRONG_SKILL,

    WORKFLOW_FAILURE,

    MANUAL,

    SCHEDULED
}
```

这样以后你可以清楚知道：

```text
为什么这个 Skill 被优化？
```

而不是简单：

```text
failure_count++
```

---

# 五、第二步：把 Failure 从“计数”升级成“案例”

这是整个方案最重要的改造。

你现在：

```text
skill_index
    failure_count
```

只能知道：

> 这个 Skill 经常失败。

Evolution 真正需要知道：

> **为什么失败？**

所以新增：

```text
skill_failure_case
```

我建议：

```sql
CREATE TABLE skill_failure_case (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,

    skill_name VARCHAR(128) NOT NULL,
    skill_version VARCHAR(32),

    trace_id VARCHAR(128),
    session_id VARCHAR(128),

    question TEXT,

    failure_type VARCHAR(64),
    failure_reason TEXT,

    expected_behavior TEXT,
    actual_behavior TEXT,

    trajectory JSON,
    verification JSON,

    severity DECIMAL(5,2),

    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_skill_failure (
        skill_name,
        failure_type
    )
);
```

---

# 六、Failure Taxonomy 建议先固定下来

第一版不要让 LLM 自由发挥。

先定义：

```text
WRONG_SKILL
WRONG_TOOL
TOOL_ARGUMENT_ERROR
TOOL_EXECUTION_ERROR
WORKFLOW_SKIP
CALCULATION_ERROR
DATA_INTERPRETATION_ERROR
SQL_ERROR
EMPTY_RESULT
VERIFICATION_FAILURE
USER_REJECTION
MAX_ITERATION
OUTPUT_FORMAT_ERROR
HALLUCINATION
```

例如你的真实案例：

> “杭州开发二部 7 月版各应用 Q2-1 达标率 + 同比”

如果 Agent：

```text
没有调用 data_distribution
```

那么：

```text
failure_type = WORKFLOW_SKIP
```

如果：

```text
tool_index
```

选错工具：

```text
failure_type = WRONG_TOOL
```

如果：

```text
Q2-1 达标率 + 缺陷密度
```

Agent 自己心算：

```text
failure_type = CALCULATION_ERROR
```

你目前项目里恰好已经有这些高频真实失败类型，可以直接拿来做第一批 taxonomy。

---

# 七、第三步：新增 `FailureAnalyzer`

结构：

```text
Trace
 ↓
FailureAnalyzer
 ↓
FailureCase
```

接口：

```java
public interface FailureAnalyzer {

    FailureCase analyze(ExecutionTrace trace);

}
```

第一版甚至可以：

```text
规则
+
LLM
```

结合。

例如：

```text
python_exec exit != 0
→ TOOL_EXECUTION_ERROR

verification verdict = FAIL
→ VERIFICATION_FAILURE

retrieved skill != expected skill
→ WRONG_SKILL

selected tool != expected tool
→ WRONG_TOOL
```

只有无法判断的时候才调用 LLM。

这样便宜很多。

---

# 八、第四步：把你现有 Trace 转成 GEPA 能理解的 Dataset

这是 Java → Python 的核心数据结构。

我建议不要直接把整个数据库表丢给 GEPA。

定义：

```java
public class EvolutionExample {

    private String exampleId;

    private String question;

    private String skillName;

    private String skillVersion;

    private String skillContent;

    private List<TraceStep> trajectory;

    private ExpectedBehavior expected;

    private ActualBehavior actual;

    private EvaluationResult evaluation;

    private FailureCase failure;

}
```

例如：

```json
{
  "example_id": "case-001",

  "question": "杭州开发二部7月Q2-1达标率同比",

  "skill": "q2_1_by_dept_version_metrics",

  "skill_version": "v3",

  "trajectory": [
    {
      "type": "skill_load",
      "skill": "q2_1_by_dept_version_metrics"
    },
    {
      "type": "tool_call",
      "tool": "tool_index"
    },
    {
      "type": "tool_result",
      "success": true
    }
  ],

  "expected": {
    "tool": "q2_1_by_dept_version_metrics",
    "must_use": [
      "data_distribution"
    ]
  },

  "failure": {
    "type": "WRONG_TOOL",
    "reason": "..."
  }
}
```

---

# 九、第五步：Evolution API

Java 提供：

```http
POST /api/evolution/jobs
```

请求：

```json
{
  "skill": {
    "name": "q2_1_by_dept_version_metrics",
    "version": "3",
    "content": "..."
  },

  "examples": [
    {}
  ],

  "evaluation": {
    "dataset_id": "golden-v3"
  },

  "constraints": {
    "max_size": 15000,
    "preserve_name": true,
    "preserve_purpose": true,
    "allow_tool_change": false
  }
}
```

返回：

```json
{
  "job_id": "evo-20260812-001",
  "status": "RUNNING"
}
```

---

# 十、Python 服务目录

我建议非常简单：

```text
skill-evolution/
│
├── evolution/
│   ├── api/
│   │   └── app.py
│   │
│   ├── gepa/
│   │   ├── optimizer.py
│   │   ├── adapter.py
│   │   ├── evaluator.py
│   │   └── prompts.py
│   │
│   ├── models/
│   │   ├── skill.py
│   │   ├── trace.py
│   │   └── evolution.py
│   │
│   ├── dataset/
│   │   ├── builder.py
│   │   └── splitter.py
│   │
│   └── constraints/
│       └── validator.py
│
├── tests/
│
├── requirements.txt
└── Dockerfile
```

依赖第一版：

```text
dspy
fastapi
uvicorn
pydantic
pyyaml
```

Hermes Self-Evolution 当前公开项目本身也采用 DSPy 3.x + OpenAI SDK + YAML 等轻量依赖，并明确采用 API 调用而非 GPU 训练。([GitHub][2])

---

# 十一、GEPA Adapter 才是核心

不要让 GEPA 直接知道你的数据库、AgentScope、SkillManager。

做一个：

```python
class SkillEvolutionAdapter:
    """
    GEPA <-> AgentScope Java
    """

    def evaluate(
        self,
        skill_candidate,
        dataset
    ):
        ...
```

内部：

```text
GEPA
 ↓
Candidate Skill
 ↓
POST Java /evaluation/run
 ↓
AgentScope
 ↓
GoldenDataset
 ↓
Verification
 ↓
metrics
 ↓
GEPA
```

所以 GEPA 只看到：

```text
candidate
score
feedback
```

---

# 十二、Evaluator 返回的不要只是一个分数

建议：

```json
{
  "score": 0.87,

  "metrics": {
    "task_success": 0.90,
    "tool_accuracy": 0.95,
    "verification_pass": 0.91,
    "answer_quality": 0.84,
    "latency": 8.2,
    "token_cost": 7200
  },

  "failures": [
    {
      "type": "WRONG_TOOL",
      "count": 2
    }
  ]
}
```

然后综合评分：

```text
Score =
    0.40 * task_success
  + 0.20 * tool_accuracy
  + 0.20 * verification_pass
  + 0.15 * answer_quality
  + 0.05 * cost_score
```

后面再做 Pareto，不要第一版就搞得太复杂。

---

# 十三、GEPA 的真正输入应该是“失败轨迹”

这点非常重要。

不要：

```text
Skill V1
 ↓
LLM
 ↓
“帮我优化”
```

而应该：

```text
Skill V1
   +
失败案例
   +
Execution Trace
   +
Expected Behavior
   +
Evaluation Feedback
        ↓
      GEPA
        ↓
Candidate
```

这正是 Hermes Self-Evolution 当前项目强调的部分：GEPA 读取 execution traces，不只看失败结果，而是根据失败原因提出 targeted improvements。([GitHub][1])

---

# 十四、Candidate 生成不要只有一个

例如：

```text
Skill V3
   ↓
GEPA
   │
   ├── Candidate A
   │   增强 Tool Selection
   │
   ├── Candidate B
   │   增强 Workflow
   │
   └── Candidate C
       增强 Calculation Rule
```

然后：

```text
A → Golden → 0.86
B → Golden → 0.91
C → Golden → 0.83
```

选择：

```text
B
```

---

# 十五、但一定要加“Mutation Scope”

这个对你的项目特别重要。

第一版：

```yaml
mutation_scope:
  - workflow
  - rules
  - tool_selection
  - examples
```

禁止：

```yaml
forbidden:
  - tool_implementation
  - sql
  - java_code
  - security_rules
  - skill_name
```

尤其是你的 Skill 里有 `.py / .sql` 附件，而且你项目有 `python_exec`、SQL、sandbox 等能力。

**第一阶段千万不要让 GEPA 改代码附件。**

Hermes 自进化项目目前也把 Skill 文件作为 Phase 1，而工具描述、System Prompt、代码属于后续阶段。([GitHub][1])

这个阶段划分非常值得你直接借鉴。

---

# 十六、Candidate 验证直接复用你现在的 GoldenEvaluationRunner

不要另写。

你现在已经：

```text
GoldenDatasetCase
        ↓
GoldenEvaluationRunner
        ↓
expected-answer
+
verification verdict
        ↓
accuracy
```

并且已经有：

```text
GATE_ACCURACY_DROP = 0.02
```

准确率下降超过 2% 阻断发布。

我建议直接把它变成：

```java
public interface SkillCandidateEvaluator {

    EvaluationResult evaluate(
        String skillName,
        String candidateContent,
        Dataset dataset
    );

}
```

然后：

```text
GoldenEvaluationRunner
        ↑
SkillCandidateEvaluator
        ↑
GEPA Adapter
```

---

# 十七、版本管理不要重新造

你已经有：

```text
SkillVersionHistory
VersionRegistry
RuleExperimentService
ReplayService
```

所以：

```text
Candidate
   ↓
Evaluation
   ↓
VersionRegistry
   ↓
v4-candidate
   ↓
Approval
   ↓
v4-production
```

而不是：

```text
直接覆盖 SKILL.md
```

这点非常重要。

Hermes 的自进化方案也采用约束检查、完整测试、语义保持以及人工 PR Review，而不是让优化器直接覆盖生产。([GitHub][1])

---

# 十八、你现有的 `SkillSynthesis` 和 `SkillEvolution` 要区分

这一点我建议你以后架构上明确：

## Skill Synthesis

解决：

> **没有 Skill，创建 Skill。**

```text
cache miss
   ↓
重复出现
   ↓
generate_skill
   ↓
新 Skill
```

你现在已经有这个路径。

---

## Skill Evolution

解决：

> **已有 Skill，但做得不好。**

```text
Skill V3
 ↓
失败
 ↓
Trace
 ↓
GEPA
 ↓
Skill V4
```

所以：

```text
              Skill Lifecycle
                    │
          ┌─────────┴─────────┐
          ↓                   ↓
     Synthesis            Evolution
     “创建能力”            “改进能力”
```

这两个千万不要混。

---

# 十九、最终你的 `v2/skills/` 我建议变成这样

```text
v2/
├── skills/
│   │
│   ├── SkillDistiller.java
│   │
│   ├── SkillSynthesisRunner.java
│   ├── SkillEvolutionRunner.java
│   │
│   ├── evolution/
│   │   ├── EvolutionOrchestrator.java
│   │   ├── EvolutionTrigger.java
│   │   ├── EvolutionJobService.java
│   │   ├── EvolutionStrategy.java
│   │   ├── LlmEvolutionStrategy.java
│   │   ├── GepaEvolutionStrategy.java
│   │   ├── EvolutionCandidate.java
│   │   └── EvolutionResult.java
│   │
│   ├── failure/
│   │   ├── FailureAnalyzer.java
│   │   ├── FailureCase.java
│   │   ├── FailureType.java
│   │   └── FailureRepository.java
│   │
│   ├── evaluation/
│   │   ├── SkillCandidateEvaluator.java
│   │   ├── GoldenSkillEvaluator.java
│   │   └── EvaluationResult.java
│   │
│   └── version/
│       └── SkillEvolutionVersionService.java
```

---

# 二十、数据库增加 3 张表就够了

你现在已经很多表了，我不建议继续疯狂增加。

第一版只增加：

```text
skill_failure_case
skill_evolution_job
skill_evolution_candidate
```

### `skill_evolution_job`

```text
job_id
skill_name
base_version
trigger_type
status
dataset_id
strategy
created_at
completed_at
```

### `skill_evolution_candidate`

```text
candidate_id
job_id
skill_name
base_version
candidate_version
content
score
metrics
status
created_at
```

### `skill_failure_case`

前面已经定义。

这样就够支撑第一版。

---

# 二十一、Evolution Job 状态机

一定要有。

```text
PENDING
   ↓
PREPARING
   ↓
OPTIMIZING
   ↓
EVALUATING
   ↓
REVIEW_REQUIRED
   ↓
APPROVED
   ↓
PUBLISHED
```

异常：

```text
             ┌── FAILED
             │
任何状态 ─────┤
             │
             └── REJECTED
```

---

# 二十二、第一版不要做“全自动发布”

我建议：

```text
GEPA
 ↓
Candidate
 ↓
Golden Evaluation
 ↓
Score提升
 ↓
Human Approval
 ↓
Production
```

而不是：

```text
GEPA
 ↓
Score提升
 ↓
自动上线
```

因为你目前已经有：

```text
SkillApproval
SkillApprover
SkillPublish
```

直接利用现有审批体系即可。

---

# 二十三、第一阶段真正要做的 MVP

我帮你把范围砍到最小：

## 只做一个 Skill

```text
q2_1_by_dept_version_metrics
```

## 只优化一个维度

```text
Tool Selection
```

## 只优化 SKILL.md

不改：

```text
Tool
Java
SQL
System Prompt
```

## 只使用：

```text
20~50 个失败案例
+
50 个 Golden Cases
```

然后：

```text
V1
 ↓
Baseline
 ↓
GEPA
 ↓
V2 Candidate
 ↓
Golden Evaluation
 ↓
Compare
```

---

# 二十四、第一版你最终要拿到这个结果

例如：

```text
Skill: q2_1_by_dept_version_metrics

                V1        GEPA-V2
------------------------------------
Task Success    72%       86%
Tool Accuracy   68%       91%
Verification    75%       89%
Avg Tokens      8.2K      8.8K
Avg Latency     7.3s      7.8s
```

然后得出：

> GEPA 根据真实失败轨迹对 Skill 工作流和工具选择规则进行进化，在固定 Golden Dataset 上 Task Success 提升 14 个百分点，Tool Accuracy 提升 23 个百分点，同时 Token/Latency 增幅可控。

这才是一个非常漂亮的技术验证。

---

# 二十五、开源项目怎么复用

我建议你**不要直接 fork Hermes Self-Evolution**。

采用：

### 直接复用

**Hermes Self-Evolution**

重点参考：

* Evolution pipeline
* GEPA 调用方式
* trace → reflection
* candidate generation
* constraint gate
* evaluation
* report

它目前明确采用 DSPy + GEPA 做 Skill 优化，并设计了测试、大小限制、语义保持、人工 Review 等 guardrails。([GitHub][1])

[Hermes Agent Self-Evolution GitHub](https://github.com/NousResearch/hermes-agent-self-evolution?utm_source=chatgpt.com)

---

### 核心依赖

**DSPy / GEPA**

你不用重新实现 GEPA 算法。

你的代码：

```text
GepaEvolutionStrategy
```

只负责把你们的数据转换成 GEPA 需要的格式。

---

### 不建议第一阶段采用

```text
Darwinian Evolver
```

因为你当前目标是：

```text
Skill.md
```

不是：

```text
Java/Python Tool Code
```

等第二阶段真的开始：

> Tool Implementation 自进化

再研究 Darwinian Evolver。

---

# 二十六、我建议你的技术路线最终分成 3 个阶段

## Phase 1：Skill Text Evolution

```text
Trace
 ↓
Failure
 ↓
GEPA
 ↓
SKILL.md
```

**这是现在做。**

---

## Phase 2：Skill + Tool Description Evolution

```text
Skill
+
Tool Description
+
Tool Selection
```

例如：

```text
Tool A:
“查询 Q2-1 数据”

↓

优化为：

“用于查询 Q2-1 按部门、版本、月份维度的数据；
涉及同比时优先使用此工具。”
```

---

## Phase 3：Workflow / Agent Policy Evolution

最后才是：

```text
Skill
+
Tool
+
SubAgent
+
Workflow
+
Prompt
```

甚至：

```text
AgentScope
   ↓
Planner
   ↓
Skill
   ↓
Tool
```

整体策略进化。

---

# 二十七、还有一个更长期的方向：Skill Graph

你这个项目未来其实非常适合做这个。

现在：

```text
Skill A
Skill B
Skill C
```

以后：

```text
                 ┌─────────────┐
                 │ Q2-1 Skill  │
                 └──────┬──────┘
                        │
               ┌────────┼────────┐
               ↓        ↓        ↓
           Metric     Tool     Analysis
           Skill      Skill     Skill
               │        │        │
               └────────┼────────┘
                        ↓
                 Verification
```

然后统计：

```text
Skill A
 ↓
经常和 B 一起成功
```

系统自动发现：

```text
A → B
```

成为 Skill Dependency。

你项目已经有 `skill_dependency_metric`，所以未来继续往 Skill Graph 演进也比较自然。

---

# 二十八、最终我建议你把这个项目定位成

不要叫：

> **Skill 自动修改**

太弱。

建议叫：

# Agent Skill Evolution Platform

下面分四层：

```text
┌─────────────────────────────────────┐
│         Skill Management            │
│ Draft / Version / Approval / Publish│
├─────────────────────────────────────┤
│         Skill Evolution             │
│ Failure / Reflection / GEPA / Mutate│
├─────────────────────────────────────┤
│         Skill Evaluation            │
│ Golden / Replay / Verification / A/B │
├─────────────────────────────────────┤
│         Agent Runtime               │
│ AgentScope / Tool / SubAgent / Trace │
└─────────────────────────────────────┘
```

你现在其实已经有下面三层：

```text
Agent Runtime        ✅
Skill Management     ✅
Evaluation           ✅
```

真正需要补强的只有：

```text
        ↓

Skill Evolution
```

---

## 我建议下一步直接进入“代码设计”

如果你要真正开始开发，我建议下一轮我直接给你以下 **4 个可以落地的东西**，而不是再讲概念：

### A. Java 端详细改造

具体到：

```text
SkillEvolutionRunner
SkillDistiller
SkillEvolutionHook
GoldenEvaluationRunner
VersionRegistry
```

分别：

> **保留什么 / 删除什么 / 新增什么 / 调用关系是什么**

### B. 3 张 MySQL 表完整 DDL

```text
skill_failure_case
skill_evolution_job
skill_evolution_candidate
```

包括索引、状态、JSON 字段。

### C. Python GEPA Service 完整骨架

```text
FastAPI
  ↓
GepaEvolutionService
  ↓
DSPy / GEPA
  ↓
Java Evaluation API
```

给到**类、接口、请求 JSON、返回 JSON、目录结构**。

### D. 第一个 `q2_1_by_dept_version_metrics` Evolution 实验

直接按照你现在真实业务里的：

> **“多维度下钻 + Q2-1 + 同比 + Tool Selection”**

设计：

```text
50 个 Golden Case
+
失败 Trace
↓
V1
↓
GEPA
↓
V2
↓
Evaluation
↓
V1 vs V2
```

我认为这会是你这个项目最有价值的 **PoC**，也最容易最终证明“Skills 自优化到底有没有用”。

[1]: https://github.com/NousResearch/hermes-agent-self-evolution?ref=blog.lai.so&utm_source=chatgpt.com "GitHub - NousResearch/hermes-agent-self-evolution at blog.lai.so · GitHub"
[2]: https://github.com/NousResearch/hermes-agent-self-evolution/blob/main/pyproject.toml?utm_source=chatgpt.com "hermes-agent-self-evolution/pyproject.toml at main · NousResearch/hermes-agent-self-evolution · GitHub"
