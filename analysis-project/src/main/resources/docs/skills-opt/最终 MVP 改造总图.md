可以。基于你刚给出的 **Step 0 事实矩阵 + 13 点核验结果**，现在已经可以从“架构讨论”进入**方法级改造设计**。

不过先锁定一个关键事实：你第 13 点把 `q2_1_by_dept_version_metrics` 作为 MVP skill，但前面的核验又明确它是 **script_exec 3 步流程**。这意味着它并不是此前定义的典型 prompt-driven skill。**因此下面我不会擅自把它改造成“prompt-driven”**，而是按你当前事实矩阵，把它作为 **script_exec skill 的最小 GEPA patch 实验对象**。这会影响 MutationRegion 的具体实现，我会在代码框架里显式标出来。

---

# 一、最终 MVP 改造总图

最终建议把链路固定成：

```text
                    ┌──────────────────────────────┐
                    │ Runtime Failure / Digestion  │
                    └──────────────┬───────────────┘
                                   │
                     trigger / failure case
                                   ↓
                    ┌──────────────────────────────┐
                    │ EvolutionOrchestrator        │
                    │       trigger(...)           │
                    └──────────────┬───────────────┘
                                   │
                         GET_LOCK(skill)
                                   │
                         create skill_job
                                   │
                            async dispatch
                                   ↓
             ┌─────────────────────┴─────────────────────┐
             │                                           │
        strategy=llm                                strategy=gepa
             │                                           │
             ↓                                           ↓
      SkillDistiller                              Python GEPA
      existing path                                FastAPI :8765
             │                                           │
             │                                  extract → mutate
             │                                           │
             │                                      body_patch
             └─────────────────────┬─────────────────────┘
                                   ↓
                         Candidate Construction
                                   │
                                   ↓
                         SkillFormatValidator
                                   │
                     ┌─────────────┴─────────────┐
                     │                           │
                   invalid                     valid
                     │                           │
                  REJECTED                       ↓
                                           EVALUATING
                                               │
                                               ↓
                                  GoldenEvaluationRunner
                                               │
                              5 cases × candidate
                                               │
                                               ↓
                                  candidate_score
                                               │
                                  baseline_max + 1
                                               │
                              ┌────────────────┴─────────────┐
                              │                              │
                           improve                         no improve
                              │                              │
                              ↓                              ↓
                      REVIEW_REQUIRED                     REJECTED
                              │
                     human approve/reject
                       │             │
                    approve       reject
                       │             │
                       ↓             ↓
                   PUBLISHED      REJECTED
                       │
                       ↓
              SkillSaveTool.applyPatch()
                       │
                  base_version check
                       │
                       ↓
              skill_index version + 1
                       │
                  resetCounts()
```

这里最重要的边界是：

> **EvolutionOrchestrator 管“演化流程”，SkillSaveTool 管“最终写入”，GoldenEvaluationRunner 管“评价”，Python GEPA 管“生成 patch”。**

四者不要互相吞职责。

---

# 二、最终职责边界

| 组件                         | 职责                    | 不负责                     |
| -------------------------- | --------------------- | ----------------------- |
| `SkillEvolutionRunner`     | 发现 evolution trigger  | 不自己实现 evolution         |
| `SkillFlowEvolver`         | digestion 侧触发入口       | 不自己 evolve              |
| `EvolutionOrchestrator`    | 唯一 evolution workflow | 不直接写 SKILL.md           |
| `SkillDistiller`           | LLM strategy          | 不负责 candidate lifecycle |
| Python GEPA                | mutation engine       | 不写 Java DB              |
| `SkillFormatValidator`     | patch/candidate 合法性   | 不判断效果                   |
| `GoldenEvaluationRunner`   | candidate evaluation  | 不修改 skill               |
| `SkillCandidateRepository` | candidate 持久化         | 不执行 runtime             |
| `SkillJobRepository`       | job 状态                | 不保存 skill body          |
| `SkillSaveTool`            | 最终 skill 保存           | 不做 Golden evaluation    |
| `SkillIndexRepository`     | active version/index  | 不管理 candidate           |

---

# 三、SkillEvolutionRunner 改造

## 3.1 原来的职责

当前：

```text
SkillEvolutionRunner
    ↓
recordFailure()
    ↓
dispatchEvolve()
    ↓
SkillDistiller / evolution logic
```

现在应该变成：

```text
SkillEvolutionRunner
    ↓
recordFailure()
    ↓
dispatchEvolve()
    ↓
EvolutionOrchestrator.trigger(...)
```

---

## 3.2 方法签名

保留原方法：

```java
private void dispatchEvolve(
        String skillName,
        EvolutionTrigger trigger
)
```

内部不再直接调用 `SkillDistiller`。

改成：

```java
private void dispatchEvolve(
        String skillName,
        EvolutionTrigger trigger
) {
    evolutionOrchestrator.trigger(
        EvolutionTrigger.builder()
            .skillName(skillName)
            .triggerType(trigger.getTriggerType())
            .userId(trigger.getUserId())
            .traceId(trigger.getTraceId())
            .failureType(trigger.getFailureType())
            .exemplarQuestion(trigger.getExemplarQuestion())
            .build()
    );
}
```

依赖新增：

```java
private final EvolutionOrchestrator evolutionOrchestrator;
```

---

# 四、SkillFlowEvolver 改造

这是本次改造中比较重要的一刀。

当前：

```text
MemoryDigestionService
        ↓
SkillFlowEvolver.evolve(...)
        ↓
findSkillForTrace(...)
        ↓
dispatchEvolve(...)
        ↓
SkillDistiller
```

改成：

```text
MemoryDigestionService
        ↓
SkillFlowEvolver.evolve(...)
        ↓
findSkillForTrace(...)
        ↓
EvolutionOrchestrator.trigger(...)
```

---

## 4.1 `dispatchEvolve`

原：

```java
private void dispatchEvolve(
        String skillName,
        List<TraceEvent> traces
)
```

建议保留入口，但是内部转换 trigger：

```java
private void dispatchEvolve(
        String skillName,
        List<TraceEvent> traces
) {
    for (TraceEvent trace : traces) {

        EvolutionTrigger trigger =
            EvolutionTrigger.fromDigestion(
                skillName,
                trace
            );

        evolutionOrchestrator.trigger(trigger);
    }
}
```

不过这里有一个**必须在最后一轮代码核对时确认的问题**：

### 不建议直接“一条 trace 一个 job”

如果 Phase 3 一次拿到：

```text
skill A
  trace1
  trace2
  trace3
  trace4
```

不应该产生：

```text
job1
job2
job3
job4
```

更合理：

```text
skill A
    ↓
aggregate failure cases
    ↓
one EvolutionTrigger
    ↓
one skill_job
```

因此最终实现更可能是：

```java
EvolutionTrigger trigger =
    EvolutionTrigger.builder()
        .skillName(skillName)
        .triggerType(SCHEDULED)
        .failureCases(failureCases)
        .build();

evolutionOrchestrator.trigger(trigger);
```

**这个点需要以你实际 `SkillFlowEvolver.evolve()` 的循环结构为准。**

---

# 五、EvolutionOrchestrator 接口

建议正式落到：

```text
v2.skills.evolution
```

---

## 5.1 Interface

```java
public interface EvolutionOrchestrator {

    EvolutionJobId trigger(
        EvolutionTrigger trigger
    );

    EvolutionJobStatus getStatus(
        EvolutionJobId jobId
    );

    void approve(
        CandidateId candidateId,
        String operator
    );

    void reject(
        CandidateId candidateId,
        String operator,
        String reason
    );

    void rollback(
        String skillName,
        int toVersion,
        String operator
    );
}
```

MVP 实际需要的只有：

```java
trigger()
approve()
reject()
getStatus()
```

`rollback()` 可以先留接口，内部后实现。

---

# 六、EvolutionTrigger

不要让 orchestrator 依赖：

```java
String skillName
```

这种碎片参数。

建议：

```java
public record EvolutionTrigger(

    String skillName,

    TriggerType triggerType,

    String userId,

    String traceId,

    FailureType failureType,

    String exemplarQuestion,

    String failedTraceSnippet,

    List<FailureCase> failureCases

) {}
```

其中：

```java
enum TriggerType {

    USER_REJECTION,

    RUNTIME_FAILURE,

    SCHEDULED

}
```

---

# 七、EvolutionOrchestratorImpl.trigger

这是整个 MVP 的核心。

---

## 7.1 完整框架

```java
@Override
@Transactional
public EvolutionJobId trigger(
        EvolutionTrigger trigger
) {

    String skillName = trigger.skillName();

    // 1. 获取 skill 当前状态
    SkillIndex skill =
        skillIndexRepository.findByName(skillName)
            .orElseThrow(() ->
                new IllegalArgumentException(
                    "Skill not found: " + skillName
                )
            );

    // 2. 获取 evolution lock
    boolean locked =
        skillEvolutionLock.tryLock(skillName);

    if (!locked) {
        return EvolutionJobId.existing(
            skillName
        );
    }

    // 3. 创建 job
    SkillEvolutionJob job =
        skillJobRepository.create(
            SkillJobCreateRequest.builder()
                .skillName(skillName)
                .baseVersion(skill.getVersion())
                .strategy(strategy)
                .triggerType(trigger.triggerType())
                .status(EvolutionJobStatus.PREPARING)
                .build()
        );

    // 4. 持久化 failure case
    persistFailureCases(
        job,
        trigger
    );

    // 5. 异步执行
    evolutionExecutor.execute(
        () -> runEvolution(
            job.getId(),
            trigger
        )
    );

    return new EvolutionJobId(
        job.getId()
    );
}
```

---

# 八、为什么这里不能直接 `@Async`

可以使用：

```java
@Async
```

但 MVP 我更建议：

```java
TaskExecutor evolutionExecutor
```

原因是后续需要：

```text
并发数
队列长度
超时
拒绝策略
job tracking
```

统一控制。

例如：

```java
@Bean
public Executor evolutionExecutor() {

    ThreadPoolTaskExecutor executor =
        new ThreadPoolTaskExecutor();

    executor.setCorePoolSize(2);
    executor.setMaxPoolSize(2);
    executor.setQueueCapacity(10);
    executor.setThreadNamePrefix(
        "skill-evolution-"
    );

    return executor;
}
```

GEPA 本身是慢任务，不应该把 Web request thread 卡住。

---

# 九、runEvolution 主流程

```java
private void runEvolution(
        Long jobId,
        EvolutionTrigger trigger
) {

    SkillEvolutionJob job =
        skillJobRepository.findById(jobId);

    try {

        updateStatus(
            job,
            PREPARING
        );

        SkillSnapshot base =
            skillSnapshotService.load(
                trigger.skillName()
            );

        EvolutionResult result;

        if (strategy == EvolutionStrategy.LLM) {

            result =
                evolveByLlm(
                    job,
                    base,
                    trigger
                );

        } else {

            result =
                evolveByGepa(
                    job,
                    base,
                    trigger
                );
        }

        List<SkillCandidate> candidates =
            candidateService.createCandidates(
                job,
                base,
                result
            );

        for (SkillCandidate candidate :
                candidates) {

            evaluateCandidate(
                candidate,
                trigger
            );
        }

        updateStatus(
            job,
            COMPLETED
        );

    } catch (Exception e) {

        updateFailed(
            job,
            e
        );

    } finally {

        skillEvolutionLock.unlock(
            trigger.skillName()
        );
    }
}
```

---

# 十、LLM Strategy

这里必须遵守你已经确认的事实：

> `strategy=llm` 不创造新 synthesis 机制。

因此：

```java
private EvolutionResult evolveByLlm(
        SkillEvolutionJob job,
        SkillSnapshot base,
        EvolutionTrigger trigger
) {

    SkillDistillResult result =
        skillDistiller.evolve(
            base.body(),
            trigger.failedTraceSnippet(),
            trigger.exemplarQuestion()
        );

    return EvolutionResult.from(
        result
    );
}
```

注意：

**这不是把 `SkillSynthesisRunner` 强行塞进 Orchestrator。**

你已经核验：

```text
retrieval disabled
    ↓
runtime cache-MISS 不再 synthesis
    ↓
SkillSynthesisRunner
    ↓
目前主要剩 digestion Phase 3
```

所以：

```text
EvolutionOrchestrator
       │
       ├── strategy=llm → SkillDistiller
       │
       └── strategy=gepa → Python GEPA
       
SkillSynthesisRunner
       │
       └── digestion synthesis
```

两条路径保持平行。

---

# 十一、GEPA Strategy

```java
private EvolutionResult evolveByGepa(
        SkillEvolutionJob job,
        SkillSnapshot base,
        EvolutionTrigger trigger
) {

    updateStatus(
        job,
        OPTIMIZING
    );

    GepaRequest request =
        GepaRequest.builder()
            .skillName(base.name())
            .baseVersion(base.version())
            .oldBody(base.body())
            .exemplar(
                trigger.exemplarQuestion()
            )
            .failedTrace(
                trigger.failedTraceSnippet()
            )
            .strategy("gepa")
            .build();

    GepaResponse response =
        gepaClient.evolve(request);

    return validateAndConvert(
        response,
        base
    );
}
```

---

# 十二、Java → Python HTTP API

MVP 固定：

```http
POST /evolve
Content-Type: application/json
```

Payload：

```json
{
  "skill_name": "q2_1_by_dept_version_metrics",
  "base_version": 3,
  "old_body": "...",
  "exemplar": "查询某部门某版本指标",
  "failed_trace": "...",
  "strategy": "gepa"
}
```

这里有一个重要原则：

> Python 不应该拿到整个 `SKILL.md` frontmatter 后再重新生成整个文件。

只给：

```text
body
```

---

# 十三、Python 返回 JSON

建议严格固定：

```json
{
  "skill_name": "q2_1_by_dept_version_metrics",
  "base_version": 3,
  "body_patch": {
    "region": "execution_steps",
    "operation": "replace",
    "old": "...",
    "new": "..."
  },
  "gepa_trace": {
    "iterations": 1,
    "reason": "clarify execution ordering"
  }
}
```

注意：

### Python 不返回：

```yaml
---
name: xxx
description: xxx
version: 4
---
```

因为：

> `SkillSaveTool` 是 frontmatter Source of Truth。

---

# 十四、Python GEPA `/evolve`

FastAPI：

```python
@app.post("/evolve")
def evolve(req: GepaRequest) -> GepaResponse:

    candidate_body = gepa_engine.optimize(
        old_body=req.old_body,
        exemplar=req.exemplar,
        failed_trace=req.failed_trace
    )

    patch = build_body_patch(
        old_body=req.old_body,
        new_body=candidate_body
    )

    return GepaResponse(
        skill_name=req.skill_name,
        base_version=req.base_version,
        body_patch=patch,
        gepa_trace=...
    )
```

---

# 十五、Mutation Region 不再叫 `tool_selection`

这一点按照 Step 0 事实重新定义。

建议：

```java
public enum MutationRegion {

    EXECUTION_STEPS,

    TOOL_CALLING,

    FAILURE_HANDLING,

    PROMPT_RULES,

    ROUTER_ARGUMENTS

}
```

对于你当前 MVP 的 `q2_1`：

```text
SKILL.md
 ├── 工具调用
 ├── 执行步骤
 └── 失败模式
```

优先：

```text
EXECUTION_STEPS
```

而不是：

```text
TOOL_SELECTION
```

---

# 十六、SkillSaveTool.applyPatch

这是最终写入边界。

建议新增：

```java
public SaveSkillResult applyPatch(
        String skillName,
        SkillPatch patch,
        int baseVersion
)
```

或者更完整：

```java
public SaveSkillResult applyPatch(
        SkillPatchRequest request
)
```

其中：

```java
public record SkillPatchRequest(

    String skillName,

    int baseVersion,

    MutationRegion region,

    PatchOperation operation,

    String oldText,

    String newText,

    String candidateId

) {}
```

---

# 十七、applyPatch 的完整逻辑

```java
@Transactional
public SaveSkillResult applyPatch(
        SkillPatchRequest request
) {

    SkillIndex current =
        indexRepository.findByName(
            request.skillName()
        ).orElseThrow();

    // 1. 乐观锁
    if (current.getVersion()
            != request.baseVersion()) {

        throw new ConcurrentSkillUpdateException(
            "Skill version changed: expected="
            + request.baseVersion()
            + ", actual="
            + current.getVersion()
        );
    }

    // 2. 读取当前 body
    String currentBody =
        skillFileSystemHelper.readSkillBody(
            request.skillName()
        );

    // 3. patch old 必须匹配
    if (!currentBody.contains(
            request.oldText())) {

        throw new PatchConflictException(
            "Patch old text not found"
        );
    }

    // 4. 应用 patch
    String newBody =
        patchEngine.apply(
            currentBody,
            request
        );

    // 5. 格式校验
    skillFormatValidator.validate(
        request.skillName(),
        newBody
    );

    // 6. 最终保存
    SaveSkillResult result =
        saveSkill(
            request.skillName(),
            newBody
        );

    // 7. candidate 状态
    candidateRepository.markPublished(
        request.candidateId(),
        result.version()
    );

    return result;
}
```

---

# 十八、注意一个关键竞态

不能：

```text
check version
    ↓
patch
    ↓
save
```

三个步骤之间允许其他线程更新。

所以必须保证：

```text
version check
+
DB version update
```

具备原子性。

推荐最终落成：

```sql
UPDATE skill_index
SET version = version + 1
WHERE skill_name = ?
  AND version = ?
```

然后：

```java
if (affectedRows != 1) {
    throw new ConcurrentSkillUpdateException();
}
```

这才是真正的 optimistic locking。

---

# 十九、SkillFormatValidator

新增：

```java
public final class SkillFormatValidator {

    private final String commonRules;

    public SkillFormatValidator(
        SubagentRegistrar subagentRegistrar
    ) {
        this.commonRules =
            subagentRegistrar.loadCommonRules();
    }

    public ValidationResult validate(
        String skillName,
        String body
    );

    public ValidationResult validatePatch(
        String skillName,
        String oldBody,
        String newBody,
        MutationRegion region
    );
}
```

---

# 二十、Validator 四层校验

### Layer 1：Markdown

```text
标题结构
代码块
section
空内容
```

### Layer 2：Skill 结构

例如：

```text
工具调用
执行步骤
失败模式
```

### Layer 3：工具约束

Candidate 不能凭空出现：

```text
foo_tool
bar_tool
```

只能使用当前 skill / analyze_data 已存在的工具。

### Layer 4：`_common/SKILL.md`

这是必须补进去的：

```java
if (contradictsCommonRules(
        newBody,
        commonRules
)) {
    return invalid(
        "Candidate conflicts with _common/SKILL.md"
    );
}
```

例如 `_common`：

```text
所有计算必须走 arith
```

candidate：

```text
直接心算结果
```

→ reject。

---

# 二十一、Candidate 生命周期

建议最终固定：

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

失败：

```text
                    ┌──→ REJECTED
                    │
EVALUATING ─────────┤
                    │
REVIEW_REQUIRED ────┘
```

---

# 二十二、skill_candidate

你目前真实表已经有：

```text
pending
synthesized
rejected
```

因此这里有一个**必须区分“现状”和“目标”**的问题。

建议不要把原状态语义硬改掉。

新增：

```text
lifecycle_status
```

或者统一升级：

```text
status
```

为：

```text
PENDING
PREPARING
OPTIMIZING
EVALUATING
REVIEW_REQUIRED
APPROVED
PUBLISHED
REJECTED
```

如果其他现有代码大量依赖：

```text
pending/synthesized/rejected
```

则 MVP 更安全：

```text
status           -- 保留旧语义
lifecycle_status -- 新生命周期
```

避免影响已有逻辑。

---

# 二十三、GoldenEvaluationRunner

candidate 生成后：

```java
private void evaluateCandidate(
        SkillCandidate candidate,
        EvolutionTrigger trigger
) {

    candidateRepository.updateStatus(
        candidate.id(),
        EVALUATING
    );

    GoldenEvaluationResult result =
        goldenEvaluationRunner.evaluate(
            GoldenEvaluationRequest.builder()
                .skillName(
                    candidate.skillName()
                )
                .candidateBody(
                    candidate.body()
                )
                .goldenVersion(
                    candidate.goldenVersion()
                )
                .userId("gepa-eval")
                .build()
        );

    candidateRepository.saveScore(
        candidate.id(),
        result.score()
    );

    if (result.score()
            >= baselineMaxScore + 1) {

        candidateRepository.updateStatus(
            candidate.id(),
            REVIEW_REQUIRED
        );

    } else {

        candidateRepository.updateStatus(
            candidate.id(),
            REJECTED
        );
    }
}
```

这里严格采用：

```text
candidate_score >= baseline_max + 1 case
```

而不是百分比。

---

# 二十四、5 Golden 的评价方式

例如：

```text
Golden 1
Golden 2
Golden 3
Golden 4
Golden 5
```

Baseline：

```text
run1 = 3/5
run2 = 4/5
run3 = 3/5

baseline_max = 4
```

Candidate：

```text
candidate >= 5/5
```

才进入：

```text
REVIEW_REQUIRED
```

如果：

```text
4/5
```

即使平均数很好，也不能通过 MVP gate。

---

# 二十五、人工审批

```java
@Override
@Transactional
public void approve(
        CandidateId candidateId,
        String operator
) {

    SkillCandidate candidate =
        candidateRepository.get(candidateId);

    assertStatus(
        candidate,
        REVIEW_REQUIRED
    );

    candidateRepository.updateStatus(
        candidateId,
        APPROVED
    );

    skillSaveTool.applyPatch(
        SkillPatchRequest.from(
            candidate
        )
    );
}
```

最终：

```text
APPROVED
    ↓
SkillSaveTool.applyPatch()
    ↓
PUBLISHED
```

而不是：

```text
candidateRepository
    ↓
直接改 skill 文件
```

---

# 二十六、Reject

```java
public void reject(
        CandidateId candidateId,
        String operator,
        String reason
) {

    candidateRepository.reject(
        candidateId,
        operator,
        reason
    );
}
```

状态：

```text
REVIEW_REQUIRED
       ↓
   REJECTED
```

不修改：

```text
skill_index
```

---

# 二十七、PUBLISHED 后发生什么

最终：

```text
SkillSaveTool
      ↓
skill file
      ↓
SkillIndexRepository.upsertOnSave
      ↓
version + 1
      ↓
SkillIndexRepository.resetCounts
```

所以：

```text
old version
    ↓
candidate
    ↓
approved
    ↓
new version
```

例如：

```text
v3
 ↓
GEPA candidate
 ↓
approve
 ↓
v4
```

然后：

```text
failure_count = 0
success_count = 0
```

你已经确认的规则：

> **发布新 version 后计数归零，而不是继承旧 version。**

---

# 二十八、skill_job DDL

由于你已经确认：

> `skill_job` 不存在。

所以这里明确为 **🆕 NEW**。

MVP 草案：

```sql
CREATE TABLE skill_job (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,

    skill_name VARCHAR(128) NOT NULL,

    base_version INT NOT NULL,

    strategy VARCHAR(32) NOT NULL
        COMMENT 'llm / gepa',

    trigger_type VARCHAR(32) NOT NULL
        COMMENT 'USER_REJECTION / RUNTIME_FAILURE / SCHEDULED',

    status VARCHAR(32) NOT NULL,

    failure_count INT NOT NULL DEFAULT 0,

    candidate_count INT NOT NULL DEFAULT 0,

    error_message TEXT,

    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    started_at DATETIME NULL,

    finished_at DATETIME NULL,

    created_by VARCHAR(64),

    KEY idx_skill_status (
        skill_name,
        status
    ),

    KEY idx_created_at (
        created_at
    )

) COMMENT='Skill Evolution Job';
```

建议再增加：

```sql
locked_at
```

如果你的 lock 最终通过 DB 实现。

---

# 二十九、skill_failure_case DDL

这是明确的：

> **🆕 NEW**

```sql
CREATE TABLE skill_failure_case (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,

    skill_name VARCHAR(128) NOT NULL,

    failed_trace_id VARCHAR(128),

    user_id VARCHAR(128),

    trigger_type VARCHAR(32) NOT NULL,

    failure_type VARCHAR(64) NOT NULL,

    exemplar_question TEXT,

    failed_trace_snippet TEXT,

    payload JSON,

    aggregated TINYINT NOT NULL DEFAULT 0,

    created_at DATETIME NOT NULL
        DEFAULT CURRENT_TIMESTAMP,

    KEY idx_skill_created (
        skill_name,
        created_at
    ),

    KEY idx_failure_type (
        failure_type
    ),

    KEY idx_trace (
        failed_trace_id
    )

) COMMENT='Skill Evolution Failure Cases';
```

MVP 可以先不做复杂 aggregation。

---

# 三十、skill_candidate DDL 改造

当前已经存在：

```text
pending
synthesized
rejected
```

建议增加：

```sql
ALTER TABLE skill_candidate
    ADD COLUMN lifecycle_status VARCHAR(32),
    ADD COLUMN base_version INT,
    ADD COLUMN candidate_version INT NULL,
    ADD COLUMN strategy VARCHAR(32),
    ADD COLUMN mutation_region VARCHAR(64),
    ADD COLUMN body_patch JSON,
    ADD COLUMN score INT NULL,
    ADD COLUMN golden_total INT NULL,
    ADD COLUMN reviewer VARCHAR(64) NULL,
    ADD COLUMN review_reason TEXT NULL,
    ADD COLUMN published_version INT NULL;
```

MVP 关键字段：

```text
skill_name
base_version
strategy
mutation_region
body_patch
score
golden_total
lifecycle_status
reviewer
published_version
```

---

# 三十一、三张表的最终关系

```text
skill_index
    │
    │ base_version
    ↓
skill_job
    │
    │ 1:N
    ↓
skill_candidate
    │
    │ score
    ↓
GoldenEvaluationRunner
    │
    │ pass
    ↓
REVIEW_REQUIRED
    │
    │ approve
    ↓
SkillSaveTool
    │
    ↓
skill_index.version + 1
```

failure：

```text
Trace
 ↓
FailureClassifier
 ↓
skill_failure_case
 ↓
EvolutionOrchestrator
 ↓
skill_job
```

而：

```text
skill_pending_judgement
```

继续保留原来的：

```text
实时用户反馈短期 pending
```

**不与 `skill_failure_case` 合并。**

---

# 三十二、FailureClassifier SPI

虽然你这轮主要问方法级改造，但这里是必须进入代码图的。

```java
public interface FailureClassifier {

    Optional<FailureClassification> classify(
        TraceContext trace
    );

}
```

初始实现：

```text
PythonExecFailureClassifier
    ↓
复用 TraceMiner 四类 regex

LoadSkillMismatchClassifier
    ↓
NEW

WrongToolClassifier
    ↓
NEW
```

---

## TraceMiner 的位置

不要让：

```text
TraceMiner
```

直接变成实时 Hook 的实现。

更合理：

```text
Runtime
  ↓
SkillEvolutionHook
  ↓
FailureClassifier
  ↓
failure_case

Digestion
  ↓
TraceMiner
  ↓
FailureClassifier
  ↓
failure_case
```

最终两条路径：

```text
                FailureClassifier
                /               \
Runtime Hook                  Digestion
     ↓                           ↓
failure_case                failure_case
                \             /
                 EvolutionOrchestrator
```

这样才能解决你之前指出的：

> 两条 failure path 重复产生 candidate。

`skill_failure_case` 可以增加：

```text
trace_id
aggregated
```

EvolutionOrchestrator 再根据：

```text
skill_name + failure fingerprint + active job
```

做去重。

---

# 三十三、GEPA Candidate 的真实隔离

GoldenEvaluationRunner：

```java
.userId("gepa-eval")
```

产生：

```text
agent_memory_ledger
```

时：

```text
user_id = gepa-eval
```

ClickHouse：

```text
trace_conversation.user_id = gepa-eval
```

而：

```text
trace_event
```

需要：

```text
conversation_id
    ↓
trace_conversation
    ↓
user_id
```

不用新增：

```text
trace_type
```

MVP 不做 ClickHouse DDL。

---

# 三十四、GEPA Python 服务最终形态

你已经确认：

```text
analysis-project-test
    │
    ├── Java
    │
    └── Python FastAPI
             │
             └── :8765
```

因此：

```text
Java
  ↓
http://127.0.0.1:8765/evolve
  ↓
FastAPI
  ↓
GEPA
  ↓
DeepSeek-v4-flash
  ↓
OpenAI protocol
```

这样不需要：

```text
Anthropic client
JPype
ProcessBuilder
```

---

# 三十五、最终 Java → Python → Java

整个最核心的数据流可以压缩成：

```text
EvolutionOrchestrator
        │
        │ old_body
        │ base_version
        │ exemplar
        │ failed_trace
        ↓
POST /evolve
        │
        ↓
Python GEPA
        │
        │ extract
        │ mutate
        │ patch
        ↓
{
  "base_version": 3,
  "body_patch": {
      "region": "execution_steps",
      "operation": "replace",
      "old": "...",
      "new": "..."
  }
}
        │
        ↓
Java
        │
        ↓
SkillFormatValidator
        │
       OK
        ↓
skill_candidate
        │
        ↓
GoldenEvaluationRunner
        │
        ↓
5 Golden
        │
        ↓
score
        │
   >= baseline_max + 1
        ↓
REVIEW_REQUIRED
        │
     human
        │
      approve
        ↓
SkillSaveTool.applyPatch()
        │
        ↓
optimistic lock
        │
        ↓
saveSkill()
        │
        ↓
skill_index.version + 1
        │
        ↓
resetCounts()
        │
        ↓
PUBLISHED
```

---

# 三十六、最终需要改 / 新增 / 不动的类

## 修改

```text
SkillEvolutionRunner
    └── dispatchEvolve()

SkillFlowEvolver
    └── dispatchEvolve()

SkillSaveTool
    └── + applyPatch()

SkillIndexRepository
    └── optimistic version update

SkillCandidateRepository
    └── lifecycle / score / publish

MemoryDigestionService
    └── 保持调用 SkillFlowEvolver
```

## 新增

```text
EvolutionOrchestrator
EvolutionOrchestratorImpl

EvolutionTrigger

SkillEvolutionJob
SkillJobRepository

SkillFailureCase
SkillFailureCaseRepository

SkillFormatValidator

FailureClassifier
LoadSkillMismatchClassifier
WrongToolClassifier

GepaClient
GepaRequest
GepaResponse

SkillPatch
PatchOperation

EvolutionLock
```

## 暂不改

```text
SkillSynthesisRunner
SkillDistiller
GoldenEvaluationRunner
ReplayService
RuleExperimentService
VersionRegistry
SubagentRegistrar
analyze_data.md
_generate_skill.md
_common/SKILL.md
```

---

# 三十七、MVP 明确“不做”

这一轮最好彻底冻结：

```text
❌ CriticAgent
❌ GEPA inner-loop evaluator
❌ 多 skill
❌ 5/10/15-30/50+ Golden 分层
❌ A/B Experiment
❌ Replay integration
❌ trace_type DDL
❌ Anthropic provider
❌ plan mode
❌ retrieval evolution
❌ SkillSynthesisRunner migration
❌ 自动审批
❌ 复杂 rollback
❌ 统计显著性
```

只做：

```text
1 skill
5 Golden
2 candidates
LLM vs GEPA
人工审批
version bump
rollback 基础能力预留
```

---

# 三十八、这里有一个需要你最后代码核对的“红色问题”

你给出的事实矩阵第 13 点写的是：

> `q2_1_by_dept_version_metrics` = script_exec 3 步流程，作为 MVP。

那么我们现在必须把实验问题定义成：

```text
V2 LLM
   ↓
SkillDistiller
   ↓
生成 candidate body

V3 GEPA
   ↓
GEPA mutation
   ↓
生成 candidate body
```

但如果实际 `q2_1` 的 SKILL.md 是：

```text
工具调用：
  script_exec
  script_id = xxx

执行步骤：
  1. ...
  2. ...
  3. ...
```

并且真正决定行为的是：

```text
script_id
```

那么 GEPA 修改：

```text
SKILL.md body
```

可能**根本无法改变执行结果**。

这会导致：

```text
LLM candidate
       ↓
Golden = 3/5

GEPA candidate
       ↓
Golden = 3/5
```

不是 GEPA 不行，而是：

> **实验对象没有足够 evolution surface。**

所以你最后一轮代码核对时，**我建议优先只核一个东西**：

```text
q2_1_by_dept_version_metrics/SKILL.md
        ↓
analyze_data
        ↓
load_skill
        ↓
router_tool
        ↓
script_exec
        ↓
script_id
```

确认：

> **修改 SKILL.md body 后，AgentScope Runtime 的实际行为是否真的会发生变化。**

如果答案是 **Yes**，当前 MVP 可以继续。

如果答案是 **No**，不要改 GEPA 架构，只需要把 MVP skill 换成真正有 prompt evolution surface 的 `quality_query_by_*`。

这是目前进入实施前**唯一我认为还值得做一次代码级确认的实验有效性问题**。

---

## 最终实施顺序

建议严格按这个顺序落：

```text
Step 1
  EvolutionOrchestrator
       ↓
Step 2
  skill_job / skill_failure_case
       ↓
Step 3
  SkillEvolutionRunner
  SkillFlowEvolver
       ↓
Step 4
  SkillFormatValidator
       ↓
Step 5
  SkillSaveTool.applyPatch
       ↓
Step 6
  GoldenEvaluationRunner 接 candidate
       ↓
Step 7
  Python FastAPI /evolve
       ↓
Step 8
  strategy=llm
       ↓
Step 9
  strategy=gepa
       ↓
Step 10
  5 Golden baseline × 3
       ↓
Step 11
  2 candidate
       ↓
Step 12
  人工 approve
       ↓
Step 13
  skill_index version bump + resetCounts
```

**到这里，已经可以进入真正的代码实施阶段了。** 你下一轮做的“最后一次代码层核对”，重点就不是再审架构，而是逐项确认上面这些方法的**真实包路径、现有参数、返回类型、事务边界、Repository API 和 `q2_1` 的 evolution surface**。确认完即可按这张图直接拆任务。
