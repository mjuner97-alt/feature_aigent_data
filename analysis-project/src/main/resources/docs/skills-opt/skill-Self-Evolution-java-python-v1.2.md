对。你这轮审查已经把 V1.1 从“通用架构方案”推进到了**可以真正落代码的项目级方案**。

尤其有一个关键结论需要再明确：

> **现在不应该继续讨论 GEPA 算法本身，而应该先把“现有 Skill 生命周期 + Trace + Evaluation + 文件格式 + 部署约束”钉死。**
>
> GEPA 只是其中一个 `EvolutionStrategy`，不是整个 Skills 自优化系统。

我建议把方案正式升级成 **V1.2：Project-Fitted Skill Evolution PoC**，并且把 MVP 再砍小。

---

# 一、V1.2 最终架构

先把整个链路定死：

```text
                         AgentScope Runtime
                                │
                                │
                       ┌────────▼────────┐
                       │ SkillEvolutionHook│
                       └────────┬────────┘
                                │
               ┌────────────────┼────────────────┐
               │                │                │
        USER_REJECTION     TOOL_FAILURE    VERIFICATION_FAIL
               │                │                │
               └────────────────┼────────────────┘
                                ↓
                    SkillEvolutionRunner
                                │
                     recordFailure(...)
                                │
                                ↓
                       FailureAnalyzer
                                │
              ┌─────────────────┼─────────────────┐
              │                 │                 │
        TraceMiner规则     WrongToolClassifier  LoadSkillMismatch
              │                 │                 │
              └─────────────────┼─────────────────┘
                                ↓
                       skill_failure_case
                                │
                     threshold / manual / cron
                                ↓
                     EvolutionOrchestrator
                                │
                  ┌─────────────┴─────────────┐
                  │                           │
              strategy=llm               strategy=gepa
                  │                           │
                  ↓                           ↓
          SkillDistiller               Python GEPA Service
          现有实现                         │
                                          │
                              extract → mutate → patch
                                          │
                                          ↓
                                    Candidate Body
                                          │
                                          ↓
                                Java SkillSaveService
                                          │
                                          ↓
                              Skill Candidate Version
                                          │
                                          ↓
                              GoldenEvaluationRunner
                                          │
                                          ↓
                               candidate score
                                          │
                           ┌──────────────┴──────────────┐
                           │                             │
                         reject                       improve
                           │                             │
                           ↓                             ↓
                        ARCHIVED                  REVIEW_REQUIRED
                                                         │
                                                         ↓
                                                   人工审批
                                                         │
                                                         ↓
                                                     PUBLISHED
```

这里有三个非常重要的边界：

### Java 是 Source of Truth

负责：

* Skill
* Version
* Trace
* Failure
* Golden Evaluation
* Candidate
* Approval
* Publish
* Rollback

### Python 只是 Evolution Engine

负责：

* GEPA
* reflection
* mutation
* candidate patch

**Python 不直接写 Skill 文件。**

### AgentScope 是唯一真实 Runtime

GEPA 不自己模拟一套 Agent。

---

# 二、先把 strategy 关系彻底定清楚

这是 V1.2 第一张必须画出来的表。

| 配置                                             | 现状 | V1.2                    |
| ---------------------------------------------- | -- | ----------------------- |
| `harness.skills.evolution.fail-rate-evolve`    | 已有 | 保留                      |
| `harness.skills.evolution.fail-rate-blacklist` | 已有 | 保留                      |
| `harness.skills.evolution.strategy`            | 新增 | `llm / gepa`            |
| `harness.skills.synthesis.*`                   | 已有 | 不改                      |
| `SkillDistiller`                               | 已有 | LLM Strategy            |
| `SkillEvolutionRunner`                         | 已有 | Trigger / Orchestration |
| GEPA                                           | 新增 | Evolution Engine        |

因此：

```properties
harness.skills.evolution.fail-rate-evolve=0.3
harness.skills.evolution.fail-rate-blacklist=0.6

harness.skills.evolution.strategy=llm
```

### 两个概念必须严格分离

```text
fail-rate-evolve
       ↓
什么时候触发 Evolution

strategy
       ↓
触发以后怎么 Evolution
```

所以：

```text
failure rate
      │
      ↓
Evolution Trigger
      │
      ├── strategy=llm
      │       ↓
      │   SkillDistiller
      │
      └── strategy=gepa
              ↓
          GEPA Service
```

而不是：

```text
strategy=gepa
→ fail-rate 配置失效
```

**不会失效。**

---

# 三、但 V2 LLM arm 有一个必须先验证的问题

你提到的这个问题非常关键：

> `strategy=llm` 是否真的就是现有 SkillDistiller 路径？

不能先假设。

需要实际追：

```text
SkillEvolutionHook
      ↓
SkillEvolutionRunner
      ↓
recordFailure()
      ↓
shouldEvolve()
      ↓
?
      ↓
SkillDistiller.evolve()
```

以及：

```text
SkillSynthesisRunner
      ↓
bumpAndMaybeSynthesize()
```

因为：

> `Skill Synthesis` 和 `Skill Evolution` 在你的代码里虽然都涉及 SkillDistiller，但语义和触发路径不是一回事。

所以 V1.2 Step 0 必须先确认：

```text
现有 SkillEvolutionRunner
    ↓
到底有没有调用 SkillDistiller.evolve()
```

如果有：

```text
V2 = 现有 LLM Evolution
```

如果没有：

```text
V2 ≠ LLM Evolution
```

那么就不能为了做三臂实验，硬把 Synthesis 当 Evolution。

---

# 四、FailureAnalyzer 不应该“替换 TraceMiner”

这个关系现在可以彻底定下来：

```text
                    FailureAnalyzer
                          │
             ┌────────────┴────────────┐
             │                         │
        TraceMiner                New Classifiers
        existing                  new
             │                         │
       regex failures           WRONG_TOOL
       EXIT_CODE                WRONG_SKILL
       EMPTY_TABLE              WORKFLOW_SKIP
       MAX_ITERS
```

也就是说：

```java
public interface FailureClassifier {

    Optional<FailureCase> classify(ExecutionTrace trace);

}
```

初始实现：

```text
PythonExecFailureClassifier
    ↑
复用 TraceMiner 现有 regex

EmptyResultClassifier
    ↑
复用现有逻辑

MaxIterationClassifier
    ↑
复用现有逻辑

WrongToolClassifier
    ↑
新增

LoadSkillMismatchClassifier
    ↑
新增
```

---

# 五、实时和离线两个路径要分开

这是 V1.2 非常重要的一点。

不要：

```text
Hook
  ↓
FailureAnalyzer

MemoryDigestion
  ↓
FailureAnalyzer

两个都直接 trigger GEPA
```

否则非常容易：

```text
同一个 failure
   ↓
实时触发一次
   ↓
凌晨 digestion 又触发一次
   ↓
两个 GEPA Job
```

应该设计成：

```text
                    FailureAnalyzer
                          │
             ┌────────────┴────────────┐
             ↓                         ↓
       Runtime Path               Digestion Path
             │                         │
       recordFailure()          aggregate / enrich
             │                         │
             └────────────┬────────────┘
                          ↓
                   failure_case
                          │
                          ↓
                 EvolutionOrchestrator
```

### Runtime

负责：

```text
发现 failure
→ 持久化
→ 更新统计
```

### Digestion

负责：

```text
聚合 failure
→ 形成 evolution signal
→ SCHEDULED trigger
```

### EvolutionOrchestrator

统一入口：

```java
trigger(EvolutionTrigger trigger)
```

所以 `SkillFlowEvolver` 最终可以被：

```text
EvolutionOrchestrator
```

统一。

---

# 六、SkillFlowEvolver 怎么处理

你的判断是对的。

最终：

```text
MemoryDigestionService
        │
        │ Phase 3
        ↓
EvolutionOrchestrator
        │
        ├── trigger(SCHEDULED)
        │
        └── strategy
```

而不是：

```text
MemoryDigestionService
       ↓
SkillFlowEvolver

SkillEvolutionHook
       ↓
EvolutionOrchestrator
```

两套 Evolution Engine 并存。

所以：

> **EvolutionOrchestrator 是唯一 Evolution 入口。**

`SkillFlowEvolver` 后续：

```text
deprecated
```

或者内部改成：

```java
return evolutionOrchestrator.trigger(...)
```

---

# 七、Candidate 生命周期最终怎么落表

这里我建议不要污染 `skill_index`。

## `skill_index`

继续表示：

> 当前可运行 Skill 索引。

所以：

```text
active
blacklisted
```

保持。

---

## `skill_pending_judgement`

继续表示：

> 跨轮 rejection 临时缓存。

不要拿它做 Candidate 状态。

---

## `skill_candidate`

专门表示：

> Evolution Candidate 生命周期。

因此：

```text
skill_candidate.status
```

定义：

```text
PENDING
PREPARING
OPTIMIZING
EVALUATING
REVIEW_REQUIRED
APPROVED
PUBLISHED
REJECTED
FAILED
```

---

# 八、PUBLISHED 不等于 skill_index 新增一条乱七八糟的状态

发布过程：

```text
candidate
   │
   ↓
APPROVED
   │
   ↓
SkillSaveService
   │
   ├── Skill file
   ├── skill_manage
   └── skill_index
```

最终：

```text
skill_index
    name = xxx
    version = V4
    fingerprint = FP4
    status = active
```

旧版本：

```text
V3
```

仍然保留在 VersionRegistry / version history。

---

# 九、新版本的 failure_count / success_count

你提出的结论我建议直接定：

> **归零。**

即：

```text
V3
fingerprint=A
success=83
failure=17

       ↓ evolution

V4
fingerprint=B
success=0
failure=0
```

因为：

```text
A ≠ B
```

否则会把旧版本行为污染到新版本。

但：

```text
V3 history
```

永久保留。

这样才能回答：

> V4 到底比 V3 好不好？

---

# 十、Mutation region 不应该叫 `tool_selection`

这一点建议在代码设计里直接改名字。

定义：

```java
MutationRegion
```

而不是：

```java
ToolSelectionRegion
```

因为 Skill Archetype 不一样。

---

# 十一、Skill Archetype

先定义：

```text
SCRIPT_EXEC_ONESHOT

PROMPT_DRIVEN

ROUTER_TOOL

MULTI_STEP_WORKFLOW
```

对应：

| Archetype            | Evolution |
| -------------------- | --------- |
| script_exec one-shot | ❌ 第一阶段不做  |
| prompt-driven        | ✅ MVP     |
| router_tool          | Phase 2   |
| multi-step workflow  | Phase 3   |

---

# 十二、MVP 就选 prompt-driven

这一点现在可以锁死。

例如：

```text
quality_query_by_xxx
```

它应该具有：

```markdown
## 工具调用

...

## 执行步骤

...

## 失败模式

...
```

GEPA 第一阶段允许：

```text
## 工具调用
+
## 执行步骤
```

范围内修改。

而：

```text
frontmatter
script_id
toolId
code block
_common
```

全部禁止。

---

# 十三、Mutation 实际上不是“让 GEPA 重写 SKILL.md”

这个设计需要再收紧。

Python 输入：

```text
skill_body
mutation_region
failure_examples
```

GEPA 输出：

```text
body_patch
```

例如：

```json
{
  "region": "tool_calling",
  "operation": "replace",
  "old": "优先调用 router_tool",
  "new": "当用户请求质量指标查询时，优先调用 quality_query_by_xxx..."
}
```

然后 Java：

```text
old SKILL.md
      ↓
extract region
      ↓
apply patch
      ↓
validate
      ↓
reconstruct body
      ↓
SkillSaveTool
      ↓
重新生成 frontmatter
```

这样 GEPA 根本没有机会修改：

```text
name
version
script_id
toolId
frontmatter
```

---

# 十四、SkillSaveTool 是最终边界

最终应该形成：

```text
GEPA
 ↓
body patch
 ↓
Java
 ↓
SkillSaveTool
 ↓
stripFrontmatter()
 ↓
renderFrontmatter()
```

所以 Python **永远不要输出完整 SKILL.md**。

它只返回：

```json
{
  "body": "..."
}
```

或者更好：

```json
{
  "patch": [
    {
      "region": "tool_calling",
      "operation": "replace",
      "old": "...",
      "new": "..."
    }
  ]
}
```

---

# 十五、ToolResultTruncationMiddleware 约束应该成为 Validator

不能只靠 Prompt 告诉 GEPA：

> “请输出 bullet。”

应该：

```text
GEPA
 ↓
Candidate
 ↓
SkillFormatValidator
 ↓
PASS / FAIL
```

检查：

```text
hard rule 是否 bullet
是否出现 blockquote
frontmatter 是否存在
code block 是否变化
skill size <= 8000
script_id 是否变化
toolId 是否变化
_common 是否变化
```

这样：

```text
Prompt = soft constraint
Validator = hard constraint
```

---

# 十六、Windows pipe deadlock 不能让 GEPA 自己碰

这个约束应该写到 `GoldenEvaluationRunner` / Tool Execution 层。

也就是说：

```text
GEPA
 ↓
GoldenEvaluationRunner
 ↓
AgentScope
 ↓
python_exec
```

GEPA 不允许：

```java
ProcessBuilder
```

自己执行：

```text
python
script
```

统一复用现有：

```text
redirectOutput
redirectError
```

机制。

这样 GEPA 以后换 Linux / Windows 都不会重新踩坑。

---

# 十七、Golden Evaluation 必须是“真实运行环境”

这一点是整个 PoC 能不能成立的核心。

错误：

```text
candidate
+
question
 ↓
LLM
```

正确：

```text
_common/SKILL.md
       +
candidate SKILL.md
       +
真实 analyze_data subagent
       +
真实 tools
       +
真实 AgentScope
       ↓
GoldenEvaluationRunner
       ↓
Trace
       ↓
task_success
```

这样得到的结果才回答：

> **Candidate 是否真的改善了生产 Agent。**

---

# 十八、GEPA Evaluation 的 userId 必须隔离

固定：

```text
userId = gepa-eval
```

例如：

```text
trace_type = GEPA_EVAL
```

这样：

```text
agent_memory_ledger
daily md
trace
```

不会污染真实用户。

同时可以很容易：

```sql
DELETE FROM ...
WHERE trace_type = 'GEPA_EVAL';
```

---

# 十九、Plan Mode 约束直接进入 Validator

Candidate 不能出现：

```text
进入 plan mode
使用 plan_enter
```

因为当前：

```text
analyze_data
```

是：

```text
load_skill
 ↓
router_tool
 ↓
python_exec
 ↓
arith
 ↓
answer
```

所以可以增加：

```text
forbidden_patterns:
  - plan mode
  - plan_enter
  - plan_exit
```

---

# 二十、CriticAgent：MVP 直接删掉

这一点我赞成你现在的判断。

**V1 PoC 不引入 CriticAgent。**

原因非常简单：

```text
GEPA
 ↓
candidate
 ↓
GoldenEvaluationRunner
```

已经够验证核心假设。

如果第一版再增加：

```text
CriticAgent
Subagent
Prompt
ToolRegistry
LLM judge
```

变量一下增加太多。

第一版我们要回答的只有：

> **GEPA 生成的 Skill Candidate，在真实 AgentScope Golden Evaluation 中，是否比现有 LLM Evolution 更好？**

所以：

```text
CriticAgent = Phase 2
```

---

# 二十一、MVP 的 GEPA 不要做真正的“大规模搜索”

第一版：

```text
1 skill
5 golden
10 failure
2 candidate
```

流程：

```text
V1
 ↓
baseline × 3
 ↓
GEPA
 ├── candidate A
 └── candidate B
 ↓
GoldenEvaluation
 ↓
A vs B vs V1
```

最多：

```text
2 × 5 = 10
```

次真实 Agent Evaluation / iteration。

这完全可以接受。

---

# 二十二、5 个 Golden 足够吗？

**不是为了证明统计显著性。**

这是 PoC。

5 个 Case 的目的：

> 验证整条 Evolution Pipeline 是否跑通。

不是：

> 证明 GEPA 在统计学意义上显著优于 LLM。

因此分两阶段：

### Stage 1

```text
5 Golden
```

验证：

```text
Pipeline correctness
Candidate correctness
```

### Stage 2

如果结果有希望：

```text
15
30
50+
```

再做真正效果评估。

所以不能在 5 个 Case 上宣称：

> “提升了 20%”。

最多说：

> “5-case PoC 上 task success 从 3/5 提升至 4/5。”

---

# 二十三、LLM 配置：第一版不要同时支持两个 Provider

这里我建议把代码设计成：

```java
EvolutionModelProvider
```

但 MVP 只实现一个。

Python：

```text
GEPA
 ↓
LLM Provider
```

配置：

```yaml
llm:
  provider: deepseek
  model: deepseek-v4-flash
  base_url: ...
  api_key: ...
```

如果最终确认主 Agent 使用的是：

```text
glm-5.2
+
Anthropic Coding Channel
```

再实现：

```text
AnthropicProvider
```

不要第一版同时把：

```text
OpenAI-compatible
Anthropic
Ark
DeepSeek
```

全部塞进去。

---

# 二十四、这里还有一个更重要的问题：Evolution LLM 和 Runtime LLM 不一定必须相同

应该区分：

```text
Runtime LLM
```

和：

```text
Evolution LLM
```

但 PoC 最好：

```text
Runtime LLM = Evolution LLM
```

原因：

> GEPA 是在优化给 Runtime LLM 看的 Skill。

如果：

```text
Runtime = GLM
Evolution = DeepSeek
```

可能出现：

```text
DeepSeek 认为很好
GLM 实际效果不好
```

因此 MVP：

```text
same model
```

是最干净的实验。

---

# 二十五、Python 部署最终定：独立容器

不建议：

```text
JPype
```

也不建议：

```text
Java Container
 └── python process
```

建议：

```text
┌───────────────────────────┐
│ Java AgentScope Container │
│                           │
│ :18080                    │
└─────────────┬─────────────┘
              │ HTTP
              ↓
┌───────────────────────────┐
│ GEPA Container             │
│                           │
│ FastAPI :18090            │
│ DSPy                      │
│ GEPA                      │
└───────────────────────────┘
```

如果现有部署使用：

```text
--network host
```

那么：

```text
Java → http://127.0.0.1:18090
```

即可。

这样两个服务：

```text
独立发布
独立依赖
独立日志
独立扩缩
```

---

# 二十六、跨 JVM Lock

直接：

```sql
SELECT GET_LOCK('skill_evolution_lock', 0);
```

Job 开始：

```text
获取 lock
```

执行：

```text
Evolution
```

结束：

```sql
SELECT RELEASE_LOCK('skill_evolution_lock');
```

和：

```text
memory_digestion_lock
```

是两个不同名字。

因此不会冲突。

不过有一个额外建议：

> Lock 只保护“Evolution Job 执行”，不要保护整个 Candidate Evaluation 的数据库查询。

否则锁粒度过大。

---

# 二十七、最终数据库关系

V1.2 不需要胡乱增加表。

关系应该是：

```text
skill_index
     │
     │ skill
     ↓
skill_failure_case
     │
     │ selected cases
     ↓
skill_evolution_job
     │
     ├──────────────┐
     ↓              ↓
candidate A     candidate B
     │              │
     └──────┬───────┘
            ↓
      GoldenEvaluation
            │
            ↓
      skill_candidate
            │
       REVIEW_REQUIRED
            │
            ↓
         APPROVED
            │
            ↓
      SkillSaveTool
            │
            ↓
       skill_index V2
```

---

# 二十八、现有 7 张表不要重建

你指出这个非常重要。

Step 0 必须检查：

```text
skill_index
skill_candidate
skill_pending_judgement
skill_job
skill_file
skill_file_reference
skill_manage
```

尤其：

```text
SkillIndexRepository
SkillSaveTool
```

是核心。

因为 Evolution 最终不是：

```text
Python → file system
```

而应该：

```text
Python
 ↓
Java Candidate API
 ↓
SkillSaveTool
 ↓
skill_index
skill_manage
skill_file
skill_file_reference
```

---

# 二十九、Step 0 文件清单，我建议最终锁成这份

## Java 核心

```text
SkillEvolutionHook.java
SkillEvolutionRunner.java
SkillDistiller.java
SkillSynthesisRunner.java

SkillFlowEvolver.java
MemoryDigestionService.java

SkillIndexRepository.java
SkillSaveTool.java

GoldenEvaluationRunner.java
GoldenDatasetCase.java

VerificationHook.java
TraceMiner.java

VersionRegistry.java
RuleExperimentService.java
ReplayService.java

V2ToolConfig.java
SubagentRegistrar.java
```

---

## Runtime / Prompt

```text
workspace/AGENTS.md

workspace/agent-subagents/analyze_data.md

workspace/skills/_common/SKILL.md

workspace/skills/quality_query_by_*/SKILL.md

workspace/skills/q2_1_by_dept_version_metrics/SKILL.md
```

---

## DDL

```text
skill_index
skill_candidate
skill_pending_judgement
skill_job
skill_file
skill_file_reference
skill_manage
```

---

# 三十、MVP 最终锁定

现在可以把整个 PoC 压缩成：

```text
                    MVP
                     │
          ┌──────────┴──────────┐
          │                     │
       Skill                 Dataset
          │                     │
quality_query_by_xxx       5 Golden
          │                 10 Failure
          │
    prompt-driven
          │
          ↓
      baseline
       × 3 runs
          │
          ↓
      strategy=llm
          │
          ↓
       V2 result
          │
          ↓
      strategy=gepa
          │
          ↓
    Python GEPA
          │
    ┌─────┴─────┐
    ↓           ↓
Candidate A   Candidate B
    │           │
    └─────┬─────┘
          ↓
GoldenEvaluationRunner
          │
          ↓
      Task Success
          │
          ↓
       Compare
          │
          ↓
   +5% threshold
          │
          ↓
 REVIEW_REQUIRED
          │
          ↓
      人工审批
          │
          ↓
      PUBLISHED
```

---

# 三十一、V1.2 的“明确不做”

这个反而非常重要。

第一阶段全部明确：

```text
❌ 不优化 script_exec skill

❌ 不修改 script_id

❌ 不修改 toolId

❌ 不修改 _common/SKILL.md

❌ 不修改 Agent System Prompt

❌ 不修改 Tool 实现

❌ 不修改 SQL

❌ 不修改 Python script

❌ 不引入 CriticAgent

❌ 不做 Skill Graph

❌ 不做自动发布

❌ 不做多 Skill 联合进化

❌ 不做大规模 GEPA search

❌ 不做统计显著性结论
```

只做：

```text
一个 prompt-driven Skill
+
tool/workflow 描述
+
失败案例
+
GEPA mutation
+
真实 Golden Evaluation
```

---

# 三十二、所以现在已经不是“继续讨论方案”的阶段了

下一步最合理的动作其实是：

## **Step 0：代码考古 + 依赖关系确认**

不要马上写 Java。

先把下面这些文件/DDL 的**真实代码**拿出来：

```text
1. SkillEvolutionHook.java
2. SkillEvolutionRunner.java
3. SkillDistiller.java
4. SkillSynthesisRunner.java
5. SkillFlowEvolver.java
6. MemoryDigestionService.java
7. TraceMiner.java
8. VerificationHook.java
9. SkillIndexRepository.java
10. SkillSaveTool.java
11. GoldenEvaluationRunner.java
12. VersionRegistry.java
13. V2ToolConfig.java
14. SubagentRegistrar.java
15. analyze_data.md
16. _common/SKILL.md
17. 一个 quality_query_by_*/SKILL.md
18. SkillIndex DDL
19. SkillCandidate DDL（如果已有）
20. skill_pending_judgement DDL
21. skill_job DDL
22. skill_file DDL
23. skill_file_reference DDL
24. skill_manage DDL
```

尤其不是让我“猜”这些类的实现。

**有了这 24 项，我下一步可以直接给你画出精确到方法级别的 V1.2 改造图：**

```text
现有方法
   ↓
保留 / 修改 / 新增 / 删除
   ↓
调用链
   ↓
Java DTO
   ↓
DB DDL
   ↓
GEPA HTTP API
   ↓
Python 类
   ↓
Candidate Patch
   ↓
Golden Evaluation
   ↓
Publish / Rollback
```

并且我建议**先不写 GEPA Python 代码**。先把 Java 侧的生命周期和 `SkillSaveTool → skill_index/version` 这条链彻底确认，否则 Python 服务写得越快，后面返工越大。
