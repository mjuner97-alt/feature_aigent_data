# Skill 自进化与夜间记忆咀嚼 —— 完整设计文档

> **文档性质**:合并文档,完整保留所有原始内容
> **合并来源**:15 个原始文档(见附录 A)
> **最后更新**:2026-07-03
> **目标**:让系统"越用越好用"——自动沉淀技能、智能召回、失败反馈闭环、夜间咀嚼优化

---

## 目录

1. [总览](#1-总览)
2. [Skill 自进化核心设计](#2-skill-自进化核心设计)
3. [PR 落地详情](#3-pr-落地详情)
4. [优化方案](#4-优化方案)
5. [夜间记忆咀嚼](#5-夜间记忆咀嚼)
6. [关键修复与增强](#6-关键修复与增强)
7. [测试场景](#7-测试场景)
8. [变更文件总表](#8-变更文件总表)
9. [配置项总表](#9-配置项总表)
10. [统一验收 Checklist](#10-统一验收-checklist)
11. [附录](#11-附录)

---

## 1. 总览

### 1.1 目标一句话总结

把当前"手动喊保存 skill"的半自动沉淀路径,演进为**自动沉淀 + 智能召回 + 失败回流**的闭环,让同一类问题问得越多,系统响应越快、答案越稳。

### 1.2 三大能力矩阵

| 能力 | 现状 | 评级 | 目标 |
|---|---|---|---|
| **沉淀** 类似问题 → 凝结 skill | `generate_skill` 子智能体 + `save_skill` 工具,**需用户显式发话** | ⚠️ 半自动 | 同指纹问题 ≥ N 次 → 自动蒸馏 SKILL.md |
| **召回** 新问题 → 加载对应 skill | `WorkspaceContextHook` 启动时**全量注入** `skills/*/SKILL.md` 到 system prompt | ✅ 但不可扩展 | L1 指纹精确路由 + L2 向量 top-K |
| **短路** 重复问题 → 跳过 LLM | `ResponseCacheHook` 以 `tenant\|intent\|dimensionKey` 精确命中 MySQL 缓存 | ✅ 但仅精确匹配 | 保持现状,与 skill 蒸馏协同 |
| **进化** skill 多次执行 → 自我修订 | **完全不存在** —— 失败/成功都不回流 | ❌ 缺口 | 失败率超阈值 → 异步演进 + 黑名单 |
| **咀嚼** 夜间离线优化 | **完全不存在** —— 无离线管道 | ❌ 缺口 | 4 阶段管道:清理→挖掘→演进→归并 |

**证据**:`workspace/harness-a2a/skills/` 里同时存在 `q1q2_compare` 和 `q1q2_compare_v2`,说明 LLM 是**另起炉灶**而不是迭代 v1。

### 1.3 实施路线图

| 顺序 | PR/优化 | 内容 | 上线条件 |
|---|---|---|---|
| 1 | **PR1: metadata baseline** | `skill_index` 表 + `SkillSaveTool` 写 YAML 元数据 + `/admin/skills` 只读接口 | 不影响现有路径,只是观测 |
| 2 | **PR2: synthesis** | `skill_candidate` 表 + `SkillSynthesisHook`(纯指纹) | PR1 已上;开关默认 false |
| 3 | **PR3: retrieval** | `EmbeddingClient` + `SkillVectorIndex` + `SkillRetrievalHook` | PR1/2 已上;与 `WorkspaceContextHook` 并存 |
| 4 | **PR4: evolution** | `SkillEvolutionHook` + 演进 prompt + 黑名单 | PR3 已上,确保 retrieved skill 名透传 |
| 5 | **优化 1-8** | Episodic 向量检索、跨 JVM CAS、缓存加速、蒸馏增强等 | PR1-4 全部上线后逐步实施 |
| 6 | **夜间咀嚼** | MemoryDigestionService 4 阶段管道 | 优化点 1-4 完成后启用 |

每个 PR 独立可回滚,坏一处不连锁。

---

## 2. Skill 自进化核心设计

> 本章节来自 [`skill-self-evolution-detail.md`](./skill-self-evolution-detail.md),完整保留原始内容。

### 2.1 能力 1:**沉淀** —— 多次问类似问题 → 自动总结成 skill

#### 2.1.1 现状盘点(为什么不够)

| 组件 | 位置 | 行为 | 问题 |
|---|---|---|---|
| 框架 `SkillLearningHook` | harness JAR 内置,builder 默认开 | 黑盒,无可调参数 | 触发条件不可控,日志里看不到何时生效 |
| `SkillSaveTool` | `harness/tools/SkillSaveTool.java:34` | 由 `generate_skill` 子 agent 显式调用 | **必须用户喊**:"把刚才流程保存为 skill" |
| 已存在的 skill | `workspace/harness-a2a/skills/` | q1q2_compare / q1q2_compare_v2 / quarter_compare 并存 | 证据:每次都另起炉灶,无聚类 |

**核心症结**:没有把"用户连续问相似问题"这个**信号**接出来,所有沉淀都是 LLM 主观判断后由用户点头才落地。

#### 2.1.2 方案:`SkillSynthesisHook` —— 自动从历史对话蒸馏

```
┌─────────────────────────────────────────────────────────────────┐
│ 用户问题 (PostReplyEvent)                                       │
│        ↓                                                        │
│ ① extractFingerprint(question)                                  │
│    复用 DimensionStateManager.analyzeQuestionRuleBased()         │
│    输出:tenant=hz|intent=quality_query|dim={dept,quarter,...}    │
│        ↓                                                        │
│ ② upsert skill_candidate SET hit_count=hit_count+1               │
│        ↓                                                        │
│ ③ 若 hit_count >= 3 AND status='pending':                       │
│    a. L1 检索器查相似 skill (topK=1, threshold=0.85)            │
│       命中 → 走 L3 演进路径,本流程退出                          │
│    b. 未命中 → 调 LLM 蒸馏 SKILL.md                             │
│    c. 复用现有 SkillSaveTool.saveSkill(safeName, desc, body)    │
│    d. UPDATE skill_candidate SET status='synthesized'           │
│        ↓                                                        │
│ ④ 异步,不阻塞 reply 返回                                        │
└─────────────────────────────────────────────────────────────────┘
```

#### 2.1.3 数据库 DDL

```sql
-- 已经有 agent_memory / agent_memory_ledger,这是新增的一张
CREATE TABLE skill_candidate (
  fingerprint   VARCHAR(255) PRIMARY KEY COMMENT 'tenant|intent|dimKey',
  user_id       VARCHAR(64)  NOT NULL    COMMENT '租户隔离',
  hit_count     INT          NOT NULL DEFAULT 0,
  last_query    TEXT         COMMENT '最近一次原始问题文本',
  last_trace_id VARCHAR(64)  COMMENT 'EpisodicMemory 中成功轨迹的 ID',
  status        ENUM('pending','synthesized','rejected','blacklist')
                NOT NULL DEFAULT 'pending',
  synth_skill   VARCHAR(128) COMMENT 'synthesized 后落地的 skill name',
  updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
                ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_user_status (user_id, status),
  KEY idx_hit_count   (hit_count DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

#### 2.1.4 关键接口

```java
// 1. 候选仓储(类似 ResponseCacheService 的 MySQL DAO 风格)
public interface SkillCandidateRepository {
    void incrementHit(String fingerprint, String userId, String query, String traceId);
    Optional<SkillCandidate> findByFingerprint(String fingerprint);
    List<SkillCandidate> findPendingByHitThreshold(int threshold);
    void markSynthesized(String fingerprint, String skillName);
    void markRejected(String fingerprint, String reason);
}

// 2. 蒸馏器(纯函数,易测)
public interface SkillDistiller {
    /** 把 EpisodicMemory 里的工具调用轨迹翻译成 SKILL.md 正文 */
    Mono<DistilledSkill> distill(List<EpisodicEvent> trace, String exemplarQuestion);
    record DistilledSkill(String name, String description, String body) {}
}

// 3. Hook 主体(挂 PostReplyEvent,异步,非阻塞)
@Component
public class SkillSynthesisHook implements PostReplyHook {
    @Value("${harness.skills.auto-synth.enabled:false}")  // dev 默认关
    private boolean enabled;
    @Value("${harness.skills.auto-synth.threshold:3}")
    private int hitThreshold;

    @Override
    public Mono<PostReplyEvent> onPostReply(PostReplyEvent e) {
        if (!enabled || e.hasWarning() || e.isFromCache()) return Mono.just(e);
        return Mono.fromRunnable(() -> trySynthesize(e))
                   .subscribeOn(Schedulers.boundedElastic())
                   .thenReturn(e);
    }
}
```

#### 2.1.5 防回流死锁

| 风险 | 防御 |
|---|---|
| Cache hit 也增计数 → 阈值很快触发但每次都是同一条 | `e.isFromCache()` 短路,**不计数** |
| LLM 蒸馏自身又产生新 query → 触发蒸馏 | 蒸馏路径标记 `internal=true`,Hook 见到跳过 |
| 两个 JVM 同时蒸馏同一个 fingerprint | `UPDATE ... SET status='synthesized' WHERE status='pending'` affected_rows 抢占 |
| LLM 产出垃圾 skill | 蒸馏后跑一次"自检":让 LLM 给自己打分,< 0.7 直接 `rejected` |

#### 2.1.6 验收

- 跑 demo:连问 3 次"杭州开发一部 Q1 缺陷密度"(每次换措辞)→ 第 3 次返回后,`workspace/harness-a2a/skills/` 出现新 skill,**用户全程没说"保存"**
- `skill_candidate` 表 `status='synthesized'`、`synth_skill` 不为空
- 同义重复:同一 fingerprint 被命中 5 次,只产 1 个 skill,不出 `_v2`

---

### 2.2 能力 2:**召回** —— 类似问题来 → 加载对应 skill 引导

#### 2.2.1 现状盘点

| 组件 | 行为 | token 影响 |
|---|---|---|
| `WorkspaceContextHook`(harness 内置) | 启动时把 `skills/*/SKILL.md` **全部**拼进 system prompt | 20 skill → ~8K token,且 19 个跟当前问题无关 |
| 无召回过滤 | LLM 每次都看到所有 skill,容易"乱挑" | 准确率下降,长上下文还增加幻觉 |

#### 2.2.2 方案:`SkillRetrievalHook` —— 两级路由(精确指纹 → 向量)

```
┌────────────────────────────────────────────────────────────────┐
│ User Query (PreReplyEvent, 在 WorkspaceContextHook 之前)        │
│        ↓                                                       │
│ L1 精确指纹路由(<1ms)                                          │
│   fp = analyzeQuestionRuleBased(query).fingerprint()           │
│   skill = skill_index WHERE fingerprint=fp AND status='active' │
│   命中 → 直接加载该 skill,设 attribute("skills.loaded", [n])   │
│        ↓ miss                                                  │
│ L2 向量 top-K(MySQL 8 VECTOR cos_distance)                    │
│   vec = embedder.embed(query)                                  │
│   hits = SELECT * FROM skill_index                             │
│           ORDER BY cos_distance(vec, embedding) LIMIT 3        │
│   过滤 cosine > 0.72                                           │
│        ↓                                                       │
│ ③ 命中 ≥ 1 → 注入 top-K SKILL.md 到 system prompt              │
│   ★ 关键:同时设 attribute("skills.override_workspace", true)   │
│      让 WorkspaceContextHook 跳过全量注入                       │
│        ↓ 全 miss                                               │
│ ④ 不注入任何 skill,让 LLM 走原路径(可选:降级到全量)            │
└────────────────────────────────────────────────────────────────┘
```

#### 2.2.3 数据库 DDL

```sql
CREATE TABLE skill_index (
  name          VARCHAR(128) PRIMARY KEY,
  fingerprint   VARCHAR(255) NOT NULL COMMENT 'L1 精确路由 key',
  description   TEXT,
  embedding     VECTOR(1536)  COMMENT 'OpenAI / 兼容 endpoint 输出',
  -- 元数据(见能力 3)
  version       INT          NOT NULL DEFAULT 1,
  usage_count   INT          NOT NULL DEFAULT 0,
  success_count INT          NOT NULL DEFAULT 0,
  failure_count INT          NOT NULL DEFAULT 0,
  last_used     TIMESTAMP    NULL,
  status        ENUM('active','blacklist','pending') DEFAULT 'active',
  updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
                ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_fingerprint (fingerprint),
  KEY idx_status      (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

> MySQL 8.0 < 8.4 没有原生 VECTOR,先用 `JSON` + 应用层余弦相似度兜底;9.0+ 用 `VECTOR(1536)` + `cos_distance()`。两套方案在 `SkillVectorIndex` 接口背后切换,业务无感。

#### 2.2.4 关键接口

```java
public interface EmbeddingClient {
    /** 复用 LLM 厂商的 embedding endpoint;失败降级返回 null,L2 自动跳过 */
    float[] embed(String text);
}

public interface SkillVectorIndex {
    Optional<SkillEntry> findByFingerprint(String fingerprint);
    List<SkillHit> topK(float[] vec, int k, float minCos);
    void upsert(SkillEntry entry, float[] vec);
    void delete(String name);
    record SkillHit(String name, String body, float cosine) {}
}

@Component
public class SkillRetrievalHook implements PreReplyHook {
    // 注册优先级必须高于 WorkspaceContextHook
    @Override public int order() { return -100; }

    @Override
    public Mono<PreReplyEvent> onPreReply(PreReplyEvent e) {
        return Mono.fromCallable(() -> doRetrieve(e))
                   .subscribeOn(Schedulers.boundedElastic())
                   .thenReturn(e);
    }
}
```

#### 2.2.5 与 `WorkspaceContextHook` 的协作

`WorkspaceContextHook` 是 harness 内置 hook,**不可改源码**。两种协作方式:

**方案 A(推荐)—— 用 attribute 通讯**
```java
// SkillRetrievalHook 在命中时设
e.attribute("skills.injected.by-retrieval", List.of("quarter_compare"));

// WorkspaceContextHook 读取 attribute 决定行为 —— 但内置 hook 读不到自定义 attribute!
```
**实际**:内置 hook 不感知。所以我们要做"包裹"(方案 B)。

**方案 B —— 替换默认行为**
- 在 `SupervisorService.build()` 里 `b.disableWorkspaceSkillInjection()`(框架是否提供需查)
- 不提供的话,**重写** `workspace/harness-a2a/AGENTS.md` 把 skill 注入禁掉,完全由 `SkillRetrievalHook` 负责

```java
b.disableHook(WorkspaceContextHook.class);  // 如不支持,用反射或自定义 builder
b.hook(new SkillRetrievalHook(vectorIndex, embedder));
```

#### 2.2.6 验收

- skills 数量 20+ 时,system prompt 中 skill 段 ≤ 2K token
- 同义改写:"杭州开发一部 Q1 数据" 和 "查询杭州开发一部 2026 年第一季度数据" 命中同一 skill
- L1 缓存命中 < 1ms(直接 PK 查询)
- L2 向量召回 P95 < 50ms(本地 embedding 客户端 + MySQL 索引)

---

### 2.3 能力 3:**进化** —— 同一 skill 多次执行 → 基于反馈更新

#### 2.3.1 现状盘点

| 信号源 | 现有 hook | 已收集 | 未利用 |
|---|---|---|---|
| 数据编造告警 | `DataGroundingHook` | reply 末尾打 `⚠️ 数据校验告警` | 不知道是哪个 skill 引起的 |
| Python 执行重试 | `PythonExecRetryHook` | 重试次数 | 不回流到 skill |
| 用户负反馈 | **无** | —— | 完全没有埋点 |
| skill 命中后是否被采纳 | **无** | —— | 召回 ≠ 采用 |

#### 2.3.2 方案:`SkillEvolutionHook` + 元数据反馈

```
                   ┌──────────────────────┐
                   │   PreReplyEvent      │
                   │ skills.retrieved=[A] │ (来自 L1)
                   └──────────┬───────────┘
                              ↓
                       [LLM + tools]
                              ↓
   ┌──────────────────────────┴──────────────────────────┐
   ↓                          ↓                          ↓
PostCallEvent             PostActingEvent           PostReplyEvent
DataGrounding⚠️          PythonExecRetry≥2         User explicit
   ↓                          ↓                          ↓
   └──────────────────────────┼──────────────────────────┘
                              ↓
                ┌─────────────────────────────┐
                │   SkillEvolutionHook        │
                │ 1. UPDATE skill_index       │
                │    SET failure_count += 1   │
                │ 2. 若 failure_rate > 0.3:   │
                │    a. 拉本次 trace + 当前   │
                │       SKILL.md              │
                │    b. LLM 改写,version+=1   │
                │    c. SkillSaveTool 覆盖    │
                │ 3. 若 failure_rate > 0.6:   │
                │    status='blacklist'       │
                └─────────────────────────────┘
```

#### 2.3.3 SKILL.md 强制 frontmatter

把现有 `SkillSaveTool` 改造:写入时强行追加版本元数据。

```yaml
---
name: quarter_compare
description: 季度间质量数据对比,带环比计算
version: 4
usage_count: 17
success_rate: 0.94          # = success / (success + failure)
last_used: 2026-06-23
last_evolved_at: 2026-06-22
evolution_log:
  - "v3→v4 (2026-06-22): csvPath 引用方式被 ArtifactHandoffHook 改了,补充路径来源"
  - "v2→v3 (2026-06-18): 环比公式分母为零兜底"
---
# 当前正文...
```

> ★ 元数据**只在数据库** `skill_index` 是 SoT(单一真源),SKILL.md 的 YAML 由 hook 在保存时**重新生成**,避免人工编辑/LLM 改写时漂移。

#### 2.3.4 改造 `SkillSaveTool`(同名覆盖,版本递增)

```java
@Tool(name = "save_skill", description = "...")
public ToolResultBlock saveSkill(String skillName, String description, String content) {
    String safeName = normalize(skillName);
    SkillEntry existing = vectorIndex.findByName(safeName).orElse(null);

    int newVersion = (existing == null) ? 1 : existing.version() + 1;
    String yamlHeader = renderYaml(safeName, description, newVersion, existing);
    String full = yamlHeader + "\n" + content.trim();

    // 文件落盘
    SkillFileSystemHelper.saveSkills(skillsDir, List.of(buildSkill(safeName, full)), true);

    // 同步索引(embedding + 元数据)
    float[] vec = embedder.embed(safeName + " " + description);
    vectorIndex.upsert(new SkillEntry(safeName, description, newVersion, ...), vec);

    return ToolResultBlock.text("skill saved v" + newVersion);
}
```

#### 2.3.5 失败信号采集点

```java
@Component
public class SkillEvolutionHook implements PostCallHook, PostActingHook, PostReplyHook {

    // 信号 1:DataGroundingHook 告警
    @Override
    public Mono<PostReplyEvent> onPostReply(PostReplyEvent e) {
        List<String> usedSkills = e.attribute("skills.retrieved", List.class);
        if (usedSkills == null || usedSkills.isEmpty()) return Mono.just(e);

        boolean failed = e.hasWarning() || e.attribute("python.retry.count", Integer.class, 0) >= 2;
        if (failed) {
            usedSkills.forEach(name -> repo.recordFailure(name, e.failureReason()));
            usedSkills.forEach(this::maybeTriggerEvolution);
        } else {
            usedSkills.forEach(repo::recordSuccess);
        }
        return Mono.just(e);
    }

    private void maybeTriggerEvolution(String name) {
        SkillEntry s = vectorIndex.findByName(name).orElseThrow();
        if (s.failureRate() > 0.3 && s.totalUses() >= 5) {
            evolutionExecutor.execute(() -> evolveSkill(s));   // 异步
        }
        if (s.failureRate() > 0.6 && s.totalUses() >= 10) {
            repo.blacklist(name);                              // L1/L2 不再召回
        }
    }
}
```

#### 2.3.6 演进 Prompt 模板(给 LLM)

```
你正在维护 skill `{name}` v{currentVersion}。

最近 {N} 次执行情况:
- 成功: {successCount}
- 失败: {failureCount}
- 典型失败信息:
{failureSamples}

当前 SKILL.md 正文:
{currentBody}

最近一次失败时的完整工具调用轨迹:
{trace}

请输出修订后的 SKILL.md 正文(不要 YAML frontmatter,由系统补):
- 必须保留所有正确的部分
- 针对失败模式补充约束/示例/反例
- 不要新增 _v2 后缀,直接同名覆盖
```

#### 2.3.7 验收

- 故意改坏一个 skill(让它误导 LLM 用错 csv 路径)→ 连跑 5 次,第 6 次起 `failure_rate > 0.3` 触发演进,version → +1
- 演进后再跑 5 次,`success_rate` 回升到 ≥ 0.85
- 永远不出现同名 + `_v2/_v3` 文件(`workspace/harness-a2a/skills/` 监控)
- `success_rate < 0.4` 的 skill 自动 `blacklist`,L1/L2 不再召回但文件保留(便于人审)

---

### 2.4 三者协同 —— 一张状态机图

```
                         ┌─────────────┐
                         │   NEW QUERY │
                         └──────┬──────┘
                                ↓
        ┌───────────────────────┴───────────────────────┐
        │  ResponseCacheHook (existing) — 精确缓存命中 │
        └───────────────────────┬───────────────────────┘
                       miss     │
                                ↓
        ┌───────────────────────┴───────────────────────┐
        │  SkillRetrievalHook (能力 2) — L1 指纹 / L2 向量 │
        │   命中 → 注入 top-K SKILL.md                   │
        └───────────────────────┬───────────────────────┘
                                ↓
                       [LLM + tools]
                                ↓
        ┌───────────────────────┴───────────────────────┐
        │           PostReplyEvent (并行)               │
        │ ┌──────────────────┐  ┌──────────────────┐    │
        │ │ SkillSynthesis   │  │ SkillEvolution   │    │
        │ │ Hook (能力 1)    │  │ Hook (能力 3)    │    │
        │ │ - 计数指纹       │  │ - 命中且失败 →   │    │
        │ │ - ≥3 次蒸馏 skill │  │   演进 skill     │    │
        │ │ - 写 SKILL.md   │  │ - 改 SKILL.md   │    │
        │ │ - 更新 vector   │  │ - version+=1     │    │
        │ └────────┬─────────┘  └────────┬─────────┘    │
        │          └──────────┬──────────┘              │
        │                     ↓                          │
        │              skill_index (DB)                  │
        └────────────────────────────────────────────────┘
```

---

### 2.5 与现有 Hook 的协作矩阵

| 现有 hook | 新增 hook | 协作方式 |
|---|---|---|
| `WorkspaceContextHook` | `SkillRetrievalHook` | 后者命中时设 `attribute`,前者读到后跳过 skill 段 |
| `ResponseCacheHook` | `SkillSynthesisHook` | 共享 `dimensionKey`;cache hit 不计入 synthesis 计数(否则永远滚不到阈值) |
| `DataGroundingHook` | `SkillEvolutionHook` | 告警直接触发 evolution,带上 `attribute("skills.retrieved")` |
| `PythonExecRetryHook` | `SkillEvolutionHook` | 重试 ≥2 触发 |
| `ArtifactHandoffHook` | (无) | skill 模板里强制写"csvPath 来自 handoff 消息",避免演进时再踩 |

---

### 2.6 避坑指南

1. **不要做 skill 自动合并** —— LLM 合并 skill 极易丢约束。让 v→v+1 演进,而不是 A+B→C。
2. **不要在 L1 用同步 embedding** —— 复用 `ResponseCacheHook` 的 `tenant|intent|dimensionKey` 精确 key 做一级路由,miss 再走 embedding。两级路由把 P99 压在 5ms。
3. **不要把 skill 内容塞进 `EpisodicMemory`** —— skill 是规则,memory 是事实,混在一起会让 LLM 把规则当历史复述。
4. **不要让 L2 在 dev 模式跑** —— 调试阶段会产生大量噪声 skill。加 `harness.skills.auto-synth.enabled=false` 默认关。

---

### 2.7 验收指标(上线后 2 周观测)

| 指标 | 当前基线 | 目标 |
|---|---|---|
| 重复类问题平均响应时间 | 33s(无 cache) / 1s(cache hit) | 命中 skill 的非 cache 路径 < 8s |
| 新问题自动沉淀率 | 0%(全靠用户喊) | 同指纹问 ≥3 次,自动产 skill ≥ 80% |
| skill 命中后回答准确率 | 未度量 | ≥ 0.85(以 DataGrounding 不告警为准) |
| Token 注入量(skill 段) | ~8K(20 skill 全注入) | < 2K(top-3) |
| 同语义重复 skill 比例 | 当前 3/15 ≈ 20% | < 5% |

---

### 2.8 配置项汇总(`application.properties`)

```properties
# 能力 1 —— 沉淀
harness.skills.auto-synth.enabled=true
harness.skills.auto-synth.threshold=3
harness.skills.auto-synth.similarity-block=0.85
harness.skills.auto-synth.self-check-threshold=0.7

# 能力 2 —— 召回
harness.skills.retrieval.enabled=true
harness.skills.retrieval.top-k=3
harness.skills.retrieval.min-cosine=0.72
harness.skills.retrieval.fallback-fullload=false   # L2 全 miss 时是否降级全量

# 能力 3 —— 进化
harness.skills.evolution.enabled=true
harness.skills.evolution.fail-rate-evolve=0.3
harness.skills.evolution.fail-rate-blacklist=0.6
harness.skills.evolution.min-uses-evolve=5
harness.skills.evolution.min-uses-blacklist=10

# Embedding 客户端(复用 LLM 配置)
harness.embedding.endpoint=${llm.endpoint}/embeddings
harness.embedding.model=text-embedding-3-small
harness.embedding.dim=1536
```

---

### 2.9 落地顺序(再次明确)

| 顺序 | PR | 内容 | 上线条件 |
|---|---|---|---|
| 1 | feat: skill metadata | `skill_index` 表 + `SkillSaveTool` 写 YAML 元数据 + `/admin/skills` 只读接口 | 不影响现有路径,只是观测 |
| 2 | feat: synthesis | `skill_candidate` 表 + `SkillSynthesisHook`(纯指纹,不要 embedding) | PR1 已上;开关默认 false,demo 验证后打开 |
| 3 | feat: retrieval | `EmbeddingClient` + `SkillVectorIndex` + `SkillRetrievalHook` | PR1/2 已上;先与 `WorkspaceContextHook` 并存,加 attribute 隔离 |
| 4 | feat: evolution | `SkillEvolutionHook` + 演进 prompt + 黑名单 | PR3 已上,确保 retrieved 的 skill 名能透传到 PostReply |

每个 PR 独立可回滚,坏一处不连锁。

---

## 3. PR 落地详情

> 本章节合并了 PR1-PR4 的变更汇总文档,以及 PR2.1/PR3.5-3.8 的修订内容,完整保留所有原始信息。

### 3.1 PR1 变更汇总 — Skill Metadata Baseline

> 对应方案:§"落地顺序" 的 PR1 行(metadata baseline)。
> 仅落地观测能力,**不改变现有调用路径**;`SkillSynthesisHook` / `SkillRetrievalHook` / `SkillEvolutionHook` 留待 PR2~4。

#### 3.1.1 变更文件清单

| # | 类型 | 路径 | 改动摘要 | 行数 | 影响面 |
|---|---|---|---|---|---|
| 1 | 🆕 新增 | `harness/skills/SkillEntry.java` | `skill_index` 表只读快照 record;预留 STATUS 常量 | ~25 | 仅本包 |
| 2 | 🆕 新增 | `harness/skills/SkillIndexRepository.java` | `@Repository` + 懒建表 DDL + `findByName/upsertOnSave/recordUsage` | ~120 | 新增 bean,无现有代码改写 |
| 3 | ✏️ 改造 | `harness/tools/SkillSaveTool.java` | 构造函数新增 repo 参数;每次保存 upsert version,自动渲染 YAML frontmatter,剥离 LLM 自带 frontmatter 防漂移 | ~+80 / -10 | save_skill 工具:产物 SKILL.md 现在带 `version` / `last_evolved_at` |
| 4 | ✏️ 改造 | `service/SupervisorService.java` | 注入 `SkillIndexRepository`(构造函数 + 字段);`buildToolRegistry` 把 repo 传给 `SkillSaveTool` | +4 / -1 | 仅 DI 装配 |

#### 3.1.2 数据库变更(自动 DDL,无需手工执行)

| 库 | 表 | 触发 | 列 |
|---|---|---|---|
| `default_db`(与 `agent_memory` 同库) | `skill_index` | 馍次调用 `save_skill` 时自动 `CREATE TABLE IF NOT EXISTS` | `name` PK / `fingerprint` / `description` / `embedding` / `version` / `usage_count` / `success_count` / `failure_count` / `last_used` / `status` / `updated_at` |

> 注:`fingerprint` / `embedding` / `success_count` / `failure_count` 列**本 PR 写 NULL/0**,留给 PR2/PR3/PR4 填充 — 避免后续再做 ALTER TABLE。

#### 3.1.3 行为差异(用户视角)

| 场景 | PR1 之前 | PR1 之后 |
|---|---|---|
| 用户喊"保存为 skill" | LLM 自由发挥 YAML frontmatter,字段不统一 | 系统强制写 `name` / `description` / `version` / `last_evolved_at` 四个标准字段;LLM 即便误写 frontmatter 也会被剥离 |
| 同名 skill 再次保存 | 覆盖,version 信息丢失 | 文件覆盖 + `skill_index.version += 1`,SKILL.md 头部 version 自增 |
| 数据库不可达 | 不影响 | 不影响(文件写入是主路径,DDL/UPSERT 失败仅打 warn) |
| LLM 调用次数 | 不变 | 不变(纯文件 + SQL,零 LLM) |

#### 3.1.4 配置项

无新增。`harness.a2a.mysql.*` 复用现有连接。

#### 3.1.5 回滚

```bash
git revert <pr1-commit>
# 数据库残留: DROP TABLE skill_index;   # 可选,留着也无副作用
```

历史 SKILL.md(没有 PR1 字段的)对 PR1 透明 — 旧文件不会被读取或修改,只是新存的会带新字段。

---

### 3.2 PR2 变更汇总 — Skill Synthesis Hook

> 对应方案:§"能力 1:沉淀" 与 §"落地顺序" 的 PR2 行(synthesis)。
> 仅落地"同指纹问题 ≥ N 次 → 自动蒸馏 SKILL.md"的闭环,默认开关 **关**。`SkillRetrievalHook` / `SkillEvolutionHook` 留待 PR3/PR4。

#### 3.2.1 变更文件清单

| # | 类型 | 路径 | 改动摘要 | 影响面 |
|---|---|---|---|---|
| 1 | 🆕 新增 | `harness/skills/SkillCandidate.java` | `skill_candidate` 表只读快照 record;`STATUS_PENDING/SYNTHESIZED/REJECTED/BLACKLIST` 常量 | 仅本包 |
| 2 | 🆕 新增 | `harness/skills/SkillCandidateRepository.java` | `@Repository` + 懒建表 DDL + `incrementHit / findByFingerprint / markSynthesized / markRejected`;`ON DUPLICATE KEY UPDATE` 保证并发安全,`markSynthesized` 是原子 CAS | 新增 bean,无现有代码改写 |
| 3 | 🆕 新增 | `harness/skills/SkillDistiller.java` | `@Component`,把 exemplar question + 可选 EpisodicMemory snippets 喂 LLM,解析出 `name / description / body` 三段;`Model.stream(...)` 同 `OpenAILlmDimensionService` | 新增 bean;直接依赖 `Model` 主 bean |
| 4 | 🆕 新增 | `harness/hooks/SkillSynthesisHook.java` | `implements Hook`:PreCall 复用 `DimensionStateManager.analyzeQuestionRuleBased()` 提指纹,PostCall 异步走 `trySynthesize`;失败/空 reply 不计数;`enabled=false` 时全程透传 | 新增 hook,每请求一个实例 |
| 5 | ✏️ 改造 | `service/SupervisorService.java` | 注入 `SkillCandidateRepository + SkillDistiller + DimensionStateManager`(字段);`build()` 在 `ArtifactHandoffHook / DataGroundingHook` 之后挂 `SkillSynthesisHook(...)`;新增 `${harness.skills.auto-synth.enabled}` / `${harness.skills.auto-synth.threshold}` 两个 @Value | DI 装配 + 每请求多一个 hook 实例 |
| 6 | ✏️ 改造 | `resources/application.properties` | 新增注释配置块 `harness.skills.auto-synth.enabled/threshold`,默认注释掉(= false / 3) | 仅文档,默认行为不变 |

#### 3.2.2 数据库变更(自动 DDL,无需手工执行)

| 库 | 表 | 触发 | 列 |
|---|---|---|---|
| `default_db` | `skill_candidate` | 馍次 `incrementHit()` 时 `CREATE TABLE IF NOT EXISTS` | `fingerprint` PK / `user_id` / `hit_count` / `last_query` / `last_trace_id` / `status` / `synth_skill` / `updated_at` |

> `skill_index` 表由 PR1 拥有;本 PR 不改其 schema,只是新蒸馏的 skill 赫原有的 `SkillSaveTool.saveSkill(...)` 写入,自动 upsert 一行。

#### 3.2.3 行为差异(用户视角)

| 场景 | PR2 之前 | PR2 之后(开关默认关) | PR2 之后(`auto-synth.enabled=true`) |
|---|---|---|---|
| 同一类问题问 3 次 | 无任何沉淀,需要用户喊"保存为 skill" | 同上 | 第 3 次回复正常返回后,后台异步蒸馏出一个新 SKILL.md 到 `workspace/.../skills/<name>/`,**用户全程没说"保存"** |
| Cache 命中(命中 `ResponseCacheHook`) | 直接返回缓存 | 同上(`CacheHitException` 短路,本 hook 的 PostCall 不执行) | 同左 — 命中不计数,避免死循环 |
| 蒸馏 LLM 自身产生新 query | 不存在 | 不存在 | 蒸馏走异步 `Schedulers.boundedElastic()`,不复用同一个 `RuntimeContext`;失败仅打 warn |
| 两个 JVM 同时蒸馏同一指纹 | 不存在 | 不存在 | `UPDATE ... WHERE status='pending'` affected_rows 抢占,只有一个写入 |
| MySQL 不可达 | 不影响 | 不影响 | 仅打 warn,`incrementHit` 返回 empty,本次 PostCall 跳过;阅读路径不阻塞 |
| 蒸馏失败 / LLM 解析不出三段 | 不存在 | 不存在 | `SkillDistiller.parse()` 返回 null → `markRejected("distiller_returned_null")`,同一指纹下次不再触发 |

#### 3.2.4 关键设计点

**指纹与 `ResponseCacheHook` 共用同一 dimension key**
两者都跑 `DimensionStateManager.analyzeQuestionRuleBased()` + `buildFromExplicit()`,且都按 `userId / sessionId / _anon` 做 tenant scope。这样保证:
- 同一类问题在 cache 维度被认作同一条,在 candidate 维度也被认作同一条 — 不会出现"cache hit 不重复但 candidate 计数翻倍"或反之
- Cache 命中后短路抛 `CacheHitException`,框架不再走 PostCall,本 hook 的计数器**自然**不会被命中流量推高(无需额外标志位)

**不在 SKILL.md 里写 frontmatter**
`SkillDistiller` prompt 明确要求"不要输出 YAML frontmatter",所有版本/usage/last_used 由 PR1 的 `SkillSaveTool` 落盘时强制生成。PR2 与 PR1 共用同一条 save 路径,YAML 风格保持一致。

**异步、不阻塞回复**
`handlePostCall` 触发后,所有 DB / LLM 操作都跑在 `Schedulers.boundedElastic()` 上,`PostCallEvent` 立即返回。reply 已经在线,蒸馏的 8-30s 延迟对用户不可见。

**EpisodicMemory 可选**
`SkillDistiller` 通过 `ObjectProvider<EpisodicMemory>` 拿引用;bean 不在时(或 `search` 抛错时),自动降级到"question-only"模式。这样本 PR 不强依赖 `MySqlEpisodicMemory` 的就绪时序。

#### 3.2.5 配置项

| 键 | 默认 | 说明 |
|---|---|---|
| `harness.skills.auto-synth.enabled` | `false` | 总开关。Dev 默认关,demo 验证通过后改 `true` |
| `harness.skills.auto-synth.threshold` | `3` | 同指纹累计多少次触发蒸馏。生产建议 5 |

#### 3.2.6 回滚

```bash
git revert <pr2-commit>
# 数据库残留: DROP TABLE skill_candidate;   # 可选,留着也无副作用
# 已蒸馏的 SKILL.md 不会自动清理 — 仍由 PR1 路径管理,可手工 rm
```

PR2 完全可独立回滚:回滚后只是没有自动蒸馏,manual `save_skill` 工具与 `skill_index` 表(PR1)全部保持原状。

#### 3.2.7 验收(对照方案 §1.6)

- [ ] 跑 demo:开启 `auto-synth.enabled=true threshold=3`,连问 3 次"杭州开发一部 Q1 缺陷密度"(每次换措辞)→ 第 3 次返回后,`workspace/.../skills/` 出现新 skill,**用户全程没说"保存"**
- [ ] `skill_candidate` 表 `status='synthesized'`、`synth_skill` 不为空
- [ ] 同义重复:同一 fingerprint 被命中 5 次,只产 1 个 skill(`markSynthesized` 后续不再触发)
- [ ] 关闭开关后,行为与 PR1 完全一致

---

### 3.2.8 PR2.1 修订 — 修复 Cache HIT 路径不计数 + 不蒸馏 Bug

> **修订日期**:2026-06-24
> **问题**:PR2 初版假设"Cache 命中后 `CacheHitException` 短路,本 hook PostCall 不执行,所以命中**不会**推高计数器"。但实测发现:计数器原本在 PostCall 才 `incrementHit`,Cache HIT 短路同样跳过它 ⇒ **同维度问题被 cache 反复命中,候选表永远不增长,蒸馏永远触发不了**。

#### 问题详情

打开 `harness.a2a.response-cache.enabled=true` 后,R1 MISS 把候选行写到 hit=1,R2/R3 全部 cache HIT 短路 ⇒ R3 后 `skill_candidate.hit_count` 仍为 1,达不到 threshold。

#### 修复方案(A:抽出共享 Runner)

新增 `SkillSynthesisRunner` 单例,把 "bump 计数 → 检阈值 → markSynthesized CAS → 异步蒸馏"四步集中。两条路径都委托给它:

- **Cache MISS 路径**(`SkillSynthesisHook`):PreCall 赫 `runner.bumpAndMaybeSynthesize(...)`。Hook 自身瘦身为"算指纹 + 调 Runner"两步,不再持有 distiller / saver / vectorIndex / embeddingClient
- **Cache HIT 路径**(`ResponseCacheHook`):HIT 分支在 `Mono.error(CacheHitException)` 之前赫 `runner.bumpAndMaybeSynthesize(...)`,使用 `tenantBucket|intent|stateKey`(无 `q=hash`)指纹形态

`SkillCandidateRepository.markSynthesized` 是行级 CAS,两条路径并发命中同一指纹时只会蒸馏一次。

#### 变更文件清单(本次新增 / 改造)

| # | 类型 | 路径 | 改动摘要 | 影响面 |
|---|---|---|---|---|
| 8 | 🆕 新增 | `harness/skills/SkillSynthesisRunner.java` | `@Component`;捕获 `skillsDir + enabled + threshold + vectorIndex(可空) + embeddingClient(可空) + distiller + 两个 repo`;`bumpAndMaybeSynthesize(fingerprint, userId, question, traceId)` 一站式;`maybeDispatch` 守 `STATUS_PENDING` + `hitCount >= threshold`;`distillAndSave` 用 CAS 保证 at-most-once | 集中蒸馏逻辑,两条路径共享 |
| 9 | ✏️ 重写 | `harness/hooks/SkillSynthesisHook.java` | 构造缩减为 3 参 `(runner, dimManager, ctx)`;`handlePreCall` 算指纹后委托 `runner.bumpAndMaybeSynthesize`;移除 distiller / saver / vectorIndex / embeddingClient 等成员 | 旧 8/10 参构造删除 |
| 10 | ✏️ 改造 | `harness/hooks/ResponseCacheHook.java` | 新增 6 元构造接 `SkillSynthesisRunner`(可空);HIT 分支前调 `runner.bumpAndMaybeSynthesize(...)`,异常吞掉;旧 2/4/5 元构造保持 `null` | 旧调用方零改动 |
| 11 | ✏️ 改造 | `service/SupervisorService.java` | 注入 `SkillSynthesisRunner` 字段;`build()` 改用 3 参 `SkillSynthesisHook`;`newCacheHook(...)` 透传 `skillSynthesisRunner` 给 6 元 `ResponseCacheHook`;删 `autoSynthEnabled/Threshold` 两个 `@Value`(已被 Runner 拿走) | DI 装配,行为不变 |

#### 行为差异(PR2 → PR2.1)

| 场景 | PR2 初版 | PR2.1 |
|---|---|---|
| R1 MISS + R2/R3 Cache HIT(threshold=3) | hit_count 卡在 1,**永远不蒸馏** | R1 PreCall 在 MISS 路径计 1;R2 HIT 路径计 2;R3 HIT 路径计 3 ⇒ Runner 触发异步蒸馏 |
| 全部 MISS(R1~R3) | hit_count 在 R1/R2/R3 PostCall 各 +1,R3 PostCall 蒸馏 | R1/R2/R3 PreCall 各 +1(Runner 内同步 +1),R3 PreCall 直接异步蒸馏 |
| analyze 类(带 `q=hash` cacheKey)同维度不同措辞 | cacheKey 不同 ⇒ 全 MISS,但候选指纹无 hash 仍递增,正常 | 同上;Runner 用的指纹也无 hash,语义保持 "同维度沉淀一个 skill" |
| 两条路径并发碰同一指纹 | 不可能(初版只有 PostCall 计) | `markSynthesized` 行级 CAS,affected_rows=1 的那条赢,另一条 skip |
| LLM 蒸馏出错 | `markRejected("distiller_returned_null")`,同一指纹下次 status≠pending 被挡 | 同左,Runner 内逻辑没变 |

#### 关键设计点(增补)

**为什么抽 Runner,而不是直接在 ResponseCacheHook 里再写一份 distill 逻辑**
两条路径都需要相同的"bump + 检阈值 + 异步蒸馏"行为。如果各写一份,两边的 `markSynthesized` CAS / saver / vectorIndex 三件事都要复制粘贴,迟早漂移。Runner 是一个 `@Component` 单例,捕获工作区路径与开关阈值,任何 hook 拿到它都能完成完整的合成动作。

**Cache key vs Synthesis fingerprint 是两套形状,**不可复用**
- `ResponseCacheHook.cacheKey = tenantBucket | intent | stateKey [| q=sha8(question)]` — analyze/compare/trend 等带 `q=hash`,防止"对比 Q1 与 Q4" 和 "查 Q1" 撞同一行
- Runner / `SkillSynthesisHook` 用的 fingerprint = `tenantBucket | intent | stateKey` — 无 `q=hash`,语义是"同一维度的所有措辞都该沉淀**一个** skill"

所以 `ResponseCacheHook` 在 HIT 分支不能直接传 cacheKey 给 Runner,得用同一份 `state + intent` 独立重算 fingerprint(代码 ~10 行)。

**计数原子性**
`SkillCandidateRepository.incrementHit` 用 `ON DUPLICATE KEY UPDATE hit_count = CASE WHEN status='pending' THEN hit_count+1 ELSE hit_count END`,两条路径并发也安全:
- 已 `synthesized` 的行,即便后续 HIT 仍调 incrementHit,行级 CASE 抑制 +1,不会"蒸馏完了还在涨"
- MISS 写 N,HIT 写 N+1,DB 看到的总是单调递增

**阈值检测的同步窗口**
旧逻辑是"R3 MISS 的 PostCall 才能看到 hit_count=3",新逻辑是"任何路径的 PreCall 算完 incrementHit 就能看到"。也就是说:
- R3 是 MISS:PreCall 算到 3 → Runner 异步派发蒸馏 → R3 仍正常返回 LLM 回复
- R3 是 HIT:HIT 短路前算到 3 → Runner 异步派发蒸馏 → R3 仍返回 cache 内容

两种情况下用户感知一致:第 3 次问完后台开始蒸馏,大约 8~30s 后 `workspace/.../skills/` 出现新 SKILL.md。

#### 验收(增补)

- [ ] 开 cache + 开 auto-synth + threshold=3:R1 MISS(日志 `[MISS path] candidate ... hit=1 status=pending`),R2/R3 HIT(日志 `Cache HIT for key=...` + `Skill synthesis triggered: fingerprint=... hit=3`),`workspace/.../skills/` 出现新 SKILL.md
- [ ] 关 cache:行为退化为全 MISS 路径,SkillSynthesisHook PreCall 计数 + Runner 蒸馏正常
- [ ] `markSynthesized` 后再问同问题:无论 MISS/HIT,`skill_candidate.hit_count` 不再增长(CASE 抑制),Runner `maybeDispatch` 见 status=synthesized 直接 skip
- [ ] 两条路径并发碰同一指纹(模拟):只有一条胜出写入,另一条 log "already claimed by another writer"

---

### 3.3 PR3 变更汇总 — Skill Retrieval & Evolving Baseline

> 对应方案:§"能力 2:召回" 与 §"落地顺序" 的 PR3 行(retrieval + evolution baseline)。
> 落地 L1 精确路由 + L2 向量 top-K 两级检索通道;新增 `SkillEvolutionHook` 基础版(跨 JVM CAS、pending judgement 持久化)。默认开关 **关**。

#### 3.3.1 变更文件清单

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

#### 3.3.2 数据库变更

**无**。所有写入复用 PR1 已经 provision 的 `skill_index.embedding LONGTEXT` 与 `skill_index.fingerprint VARCHAR(255)` 列;PR3 仅是 read/write SQL,**没有任何 ALTER TABLE**。

> PR1 当时已为 PR3 留了这两列,所以现在落地零迁移。若日后切 MySQL 9.0+ 原生 VECTOR 类型,只需把 LONGTEXT 改 `VECTOR(2560)` 并把 `topK` 的进程内 cosine 换成 `cos_distance(...)` SQL — 接口形状不变。

#### 3.3.3 行为差异(用户视角)

| 场景 | PR3 之前 | PR3 之后(`retrieval.enabled=false`,默认) | PR3 之后(`retrieval.enabled=true`) |
|---|---|---|---|
| 系统提示中的 skill 内容 | `WorkspaceContextHook` 注入全部 SKILL.md(全量) | 同上(本 hook 透传) | **追加** top-K 命中的 SKILL.md 到 system 末尾;`WorkspaceContextHook` 仍跑(JAR 内部无 disable API) |
| 同一指纹再次提问 | 走 LLM 全量 skill 列表 | 同上 | L1 ≤1ms 命中,把对应 SKILL.md 标记成"Retrieved skill"块 |
| 新问题 + 有 embedding 客户端 | 不存在 | 不存在 | L1 miss → L2 进程内 cosine 在 active 行里取 top-K,`cosine ≥ min-cosine`(默认 0.72)才注入 |
| Embedding 服务挂了 | 不影响 | 不影响 | `EmbeddingClient.embed()` 返回 null,L2 直接跳过;L1 不受影响;请求不阻塞 |
| `SkillSaveTool` 保存新 skill | 落盘 + `skill_index` upsert | 同上 + 异步把 `(name + " " + desc)` embed 后写回 `skill_index.embedding`(fingerprint=null) | 同左 |
| PR2 自动蒸馏出新 skill | 落盘 + `skill_index` upsert | 同上 + embedding 旁路(若 client 在) | 同左,**且** 由 `SkillSynthesisHook` 同步把 PR2 owns 的 fingerprint 写进 `skill_index.fingerprint`,确保下一次同指纹请求 L1 直接命中 |
| 既有 PR1 时期手工保存的 skill | embedding/fingerprint 为 NULL | 同上(直到下一次 save 触发 embed) | L1 永远 miss(fingerprint 空),L2 也 miss(embedding 空) → 走全量注入 fallback;不影响正确性 |

#### 3.3.4 关键设计点

**为何 PR3 与现有 `WorkspaceContextHook` 并存**
`WorkspaceContextHook` 是 `agentscope-harness-1.1.0-RC1.jar` 内部 hook,没有任何配置可关。直接撤换需要改 JAR 或反射拆,风险与改动量都不匹配 PR3 的范围。所以 PR3 走"净增":
- 默认关,关闭时本 hook 退化为 no-op,行为与升级前完全一致
- 打开后是"在已有的全量 skill 列表后面再追加一个 spotlight 块",大多数 LLM 会更强 anchor 在尾部内容上,即便冗余也不会破坏正确性
- 验证 retrieval 准确率后,可在后续 PR(或上游升级)再去掉全量注入

**指纹与 PR2 / `ResponseCacheHook` 严格一致**
`SkillRetrievalHook.fingerprintOf()` 用同一个 `DimensionStateManager.analyzeQuestionRuleBased()` + 本地 `buildFromExplicit()` 形状,scope key 也走 `userId / sessionId / _anon` 三档。这样 PR2 写入的 `skill_index.fingerprint = tenant|intent|dimKey` 与 PR3 读出来的 fingerprint **逐字符相等**,L1 才能真的命中。

**Embedding 存 JSON 而不是 VECTOR**
- 不强依赖 MySQL 9.0+,部署兼容性最大化
- 解析失败的单行不阻塞整张表的 retrieval(`topK` 跳过单坏行,只 debug 一行日志)
- 维度不匹配一律拒绝写入(`OpenAiCompatEmbeddingClient` 在写之前对比 `harness.embedding.dim`),防止两套向量空间混入同一列后污染所有 cosine

**Embed 的内容是 `name + " " + desc` 而不是全文**
- description 是技能"做什么"的浓缩,语义判别度最高
- 全文随每次 evolution(版本升)都会变,但技能"做什么"通常不变 — embedding 不应每升一版都漂移
- 副作用:同一 skill 升版后 embedding 不重算,语义仍稳定

**`SkillSaveTool` 用静态单线程 daemon executor 跑 embed**
- save_skill 工具立即返回(IO 路径不被 embedding 网络往返拖累)
- 单线程串行,避免对同一 skill 并发写入 embedding 列竞争
- daemon 不阻塞 JVM 退出

**失败永远静默 + 退化**
任何一步出错(L1 SQL 异常 / L2 解析坏 / embed 超时 / 文件读不到):
- 记 `log.warn` 或 `log.debug`,**绝不抛**
- `inject()` 整段 try/catch 兜底,本 hook 哪怕崩了也走 `WorkspaceContextHook` 的全量注入路径
- 用户请求路径完全不感知

#### 3.3.5 配置项

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

#### 3.3.6 回滚

```bash
git revert <pr3-commit>
# 数据库残留: 已写入的 skill_index.embedding / fingerprint 列可留可清, PR1/PR2 不依赖
#            UPDATE skill_index SET embedding=NULL, fingerprint=NULL;   # 可选
```

PR3 完全可独立回滚:回滚后 `SkillRetrievalHook` / `SkillVectorIndex` / `EmbeddingClient` 全部消失,`SkillSaveTool` 回到 2 参形态,`SkillSynthesisHook` 回到 8 参形态,行为与 PR2 完全一致。

#### 3.3.7 验收(对照方案 §1.6 retrieval 项)

- [ ] `harness.skills.retrieval.enabled=true` + `harness.embedding.enabled=true` + 配齐 endpoint/api-key
- [ ] 同一指纹问题再问一次 → 日志看到 `SkillRetrievalHook injected 1 skill(s) ... fp=...:...`,system prompt 中出现 `<!-- skills.retrieved (PR3) -->` 块
- [ ] 新措辞问题(指纹不同) → L1 miss 走 L2,top-K 命中的 skill 在 system 末尾
- [ ] embedding 服务返回错维度 → 单条 warn,后续请求不受影响,L1 仍能工作
- [ ] 关闭 `retrieval.enabled` → 行为与 PR2 完全一致(只是 `SkillSaveTool` 仍会异步 embed,可不开 `embedding.enabled` 跳过)

---

### 3.3.8 PR3.6 修订 — eager DDL

> **修订日期**:2026-06-24
> **问题**:PR1 / PR2 / PR3 三个 repository 的建表都是 lazy(首次 `ensureTable()` 才 `CREATE TABLE IF NOT EXISTS`)。两个副作用:
> 1. **首次 API 请求多付一次 DDL 延迟**(~200ms)— 落在用户感知最敏感的"第一次问"
> 2. **PR3 L1 silent miss on 冷库**:`SkillVectorIndex.findByFingerprint` / `topK` 直接读 `skill_index`,如果没人先调过 `SkillIndexRepository.upsertOnSave`,这两次 SQL 会撞 `Table 'agentscope.skill_index' doesn't exist` ⇒ `log.warn` 吞掉 ⇒ `SkillRetrievalHook` 静默 fallback 到全量注入,**用户感知不到检索失败**

#### 修复

| # | 类型 | 路径 | 改动摘要 |
|---|---|---|---|
| 10 | ✏️ 改造 | `harness/skills/SkillIndexRepository.java` | 加 `@PostConstruct void initSchema() { ensureTable(); }`,boot 期间一次建表;lazy 路径保留作 retry |
| 11 | ✏️ 改造 | `harness/skills/SkillCandidateRepository.java` | 同上,boot 时建 `skill_candidate` |
| 12 | ✏️ 改造 | `harness/skills/SkillVectorIndex.java` | `@DependsOn("skillIndexRepository")` — 自己没有 DDL,但所有 SQL 都打 `skill_index`,所以必须在表创建之后再初始化 |

#### 验收

- [x] 启动日志 `Started AgentscopeA2aApplication` 之前出现 `skill_index table ensured` + `skill_candidate table ensured`
- [x] 首次 `/ai/chat` 不再花 ~200ms 在 DDL 上(实测:R1 SkillSynthesisHook PreCall log 直接走 `INSERT` 路径)
- [x] DROP 掉两张表后重启 → boot 阶段重建成功;不重启而 DDL 时 MySQL 宕机 → boot 不挂(`ensureTable` swallow warn),后续 lazy retry 仍能恢复

---

### 3.3.9 PR3.7 修订 — bge-large-zh L2 cosine 失效修复(方案 A)

> **修订日期**:2026-06-24
> **问题**:切到 `quentinz/bge-large-zh-v1.5:latest`(dim=1024)后,L2 在中文短句上的 cosine 全部挤在 0.55~0.70 之间,远低于默认 `min-cosine=0.72`。实测一条新蒸馏的 skill 自己 embed 自己也只到 ~0.78,而**任何稍微换措辞的同维度问题** cosine 都掉到 0.60~0.68 直接被丢。结果:L1 fingerprint miss + L2 全部低于阈值 ⇒ retrieval 静默回退到全量 skill 注入,PR3 等于没装。

根因不是阈值偏高,是 embed 的 source 文本太短:`name + " " + description` 拼出来的 `"quarterly_dept_quality_query 查询某部门某季度的质量数据"` 只有 ~20 个中文 token,bge-zh 的语义分辨率在这种长度上窗口本身就窄。

#### 修复(方案 A:embed source 加 sample_questions)

让 `SkillDistiller` 多产一段 `sample_questions:`(3-5 条同维度不同措辞的中文问法),embed 改用 `description + "\n- 问法1\n- 问法2\n..."`。同维度的不同措辞共享 lexical surface 后,bge-zh 的 cosine 自然往 0.75~0.88 挪。

| # | 类型 | 路径 | 改动摘要 |
|---|---|---|---|
| 13 | ✏️ 改造 | `harness/skills/SkillDistiller.java` | `DistilledSkill` 加 `List<String> sampleQuestions` 字段(record canonical ctor 做不可变 copy + null→空 list);prompt 由三段改四段,新增 `sample_questions:` 块要求 3-5 行 `- <中文问法>`;新增 `SAMPLES_BLOCK / SAMPLE_LINE` 两个 regex 与 `parseSamples()` 解析器(去重 + 去引号 + 限 8 条上限) |
| 14 | ✏️ 改造 | `harness/skills/SkillSynthesisRunner.java` | `distillAndSave` 给 `SkillSaveTool` 传 `null` embeddingClient(避免与 runner 自己的同步 embed 竞写覆盖 fingerprint);新增 `buildEmbedText(distilled)` 静态助手:有 samples 时拼 `desc + "\n- q1\n- q2\n..."`,无 samples 时 fallback 到 `name + " " + desc` 保持旧行为 |

#### 行为差异

| 场景 | PR3.7 之前 | PR3.7 之后 |
|---|---|---|
| 新蒸馏 skill 的 embed source | `"quarterly_dept_quality_query 查询某部门某季度的质量数据"` ≈20 token | `"查询某部门某季度的质量数据\n- 杭州开发一部 Q1 缺陷密度\n- 上海部门 Q2 测试覆盖率\n- ..."` ≈60~90 token |
| 同维度换措辞问题 cosine | 0.60~0.68(<0.72 一律丢) | 0.75~0.88(稳过 0.72) |
| 旧 skill(samples 字段为空) | 同左 | fallback 到 `name + " " + desc`,行为与旧版一致 |
| `SkillSaveTool.saveSkill` 走手动路径(用户喊"保存为 skill") | async 跑 `embedClient.embed(name + " " + desc)` 写 `skill_index.embedding`(fingerprint=NULL) | 同左 — 手动路径**不**走 distiller,sample_questions 拿不到,继续旧方案;只影响自动蒸馏路径 |
| Distiller LLM 漏掉 sample_questions 段 | 不存在(只解析三段) | `parse()` 不强校 samples;只要 name/desc/body 三段齐全就返回 `DistilledSkill(name, desc, body, [])`;runner `buildEmbedText` 退到 fallback |

#### 关键设计点

**为什么 `SkillSaveTool` 不也改**
manual `save_skill` 工具的入参里没有 sample_questions(LLM tool calling schema 不能临时加),改它需要再改 `@Tool` 注解的 schema、SkillGeneratorAgent prompt、所有调用方。范围远超 PR3.7。所以本 PR 只覆盖**自动蒸馏路径**,manual 路径在用户手喊"保存为 skill"时仍走老逻辑(描述往往更长,cosine 本身没那么糟糕)。

**为什么 runner 给 saver 传 `null` embeddingClient**
- `SkillSaveTool.maybeEmbedAsync` 用 `name + " " + desc` 走 daemon executor 异步写
- runner 这边用 `desc + samples` 同步写 + 带 fingerprint
- 两条路如果都开,daemon 完成时间未定,可能先于 / 后于 runner — 后写的 `null` fingerprint 会**覆盖** runner 写的 canonical fingerprint,L1 失效
- 解法:走自动蒸馏时,把 saver 的 embedding 旁路关掉,只由 runner 管 embedding + fingerprint

**parse 容错**
`parseSamples()` 找不到 `sample_questions:` 块就返回空 list,不让整次蒸馏 fail。LLM 偶尔漏一段是常态;漏了就退到 `name + " " + desc` fallback,**至少**与 PR3 原行为持平,不会更差。

#### 不动 `min-cosine` 阈值的取舍
也考虑过单纯把默认从 0.72 降到 0.55(方案 B)。否决理由:
- bge-zh 在 0.55 附近的 cosine 已经能匹配上"完全不相关但都是质量数据领域"的 skill — false positive 风险高
- 调阈值是治标,治本是让 embedding 本身分辨力够 — 方案 A 把 cosine 提到 0.75+ 后,0.72 仍然是个合理的"明显相关才注入"的栅栏

如果后续切回 OpenAI `text-embedding-3-small` / `text-embedding-3-large`,可以把 `min-cosine` 调回 0.78~0.82(那两个模型的 cosine 区间更陡)。

#### 验收

- [ ] 自动蒸馏出的新 skill,落盘的 SKILL.md 正文里能看到 `sample_questions:` 段或 distiller 至少 emit 出 3 条 sample(看 distiller raw output)
- [ ] `SELECT name, JSON_LENGTH(embedding) AS dim FROM skill_index WHERE name = '<新蒸馏 skill>'` 返回 1024
- [ ] 用同维度但完全不同措辞的新问题问一次,日志看到 `SkillRetrievalHook injected 1 skill(s) ... cos=0.7X+`,system prompt 里出现 `<!-- skills.retrieved (PR3) -->`

---

### 3.3.10 PR3.8 修订 — 重新装配 PR2/PR3 hooks + 蒸馏 body fence 容错

> **修订日期**:2026-06-25
> **问题**:PR3.7 验证 R1/R2/R3 时发现:**`SkillSynthesisHook` 与 `SkillRetrievalHook` 在 `SupervisorService.build()` 里完全没装配**,导致:
> 1. PR2 MISS 路径(`SkillSynthesisHook` 在 PreCall bump 计数)从未触发 — 任何同指纹问题永远停在 hit=0,蒸馏管线整体不动
> 2. PR2 HIT 路径(`ResponseCacheHook` 复用 `SkillSynthesisRunner`)也死 —— `SupervisorService.newCacheHook` 用的是 5 参构造,`synthesisRunner` 注入 null,`bumpAndMaybeSynthesize` 早返
> 3. PR3 `SkillRetrievalHook` 同样未装配 —— L1/L2 全部静默,只有 `WorkspaceContextHook` 的全量注入路径在跑

同步发现一个 distiller parse 问题:LLM 输出 SKILL.md body 时常常忘记闭合 ```` ``` ```` 三反引号(尤其当 body 较长被 stream 截断或 LLM 自己判定"已经写完"提前停止),`BODY_FENCE` 原始正则强制要求闭合,导致 hit=3 后 `Distiller output missing required sections; bodyFound=false` 被拒,蒸馏白跑一次 LLM。

#### 修复

| # | 类型 | 路径 | 改动摘要 |
|---|---|---|---|
| 15 | ✏️ 改造 | `service/SupervisorService.java` | 注入 `SkillSynthesisRunner`(必选)+ `SkillVectorIndex`(必选)+ `ObjectProvider<EmbeddingClient>`(可选);`newCacheHook` 改用 6 参 `ResponseCacheHook` 传入 runner;`build()` 末尾装配 `SkillSynthesisHook` 与 `SkillRetrievalHook`;`skill_save` 工厂改用 4 参 `SkillSaveTool`;追加 `harness.skills.retrieval.{enabled,top-k,min-cosine}` 三个 `@Value` |
| 16 | ✏️ 改造 | `harness/hooks/SkillSynthesisHook.java` | `[MISS path] candidate ...` 日志级别 `debug` → `info`。理由:默认 Spring Boot 日志级别 INFO,DEBUG 不出。这条日志是验证 PR2 装配是否生效的唯一外部信号,放 INFO 也只是每次问一行,可接受 |
| 17 | ✏️ 改造 | `harness/skills/SkillDistiller.java` | `BODY_FENCE` 改 `"```(?:md\|markdown)?\\s*\\R(.*?)(?:\\R```\|\\z)"` —— 闭合 fence 可选,缺失时取到 EOF;`parse()` 失败 warn 日志携带 `nameFound/descFound/bodyFound` + 1500 字符 raw 头部,后续 LLM 行为漂移可直接定位 |

#### 验证结果(2026-06-25 11:09)

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

#### 不变量

- 默认开关全部保留:`harness.skills.auto-synth.enabled` / `harness.skills.retrieval.enabled` / `harness.embedding.enabled` 均默认 false。本次 PR 只是把装配回路接回,而非默认启用
- 当任一开关关闭时,对应 hook 的 `onEvent` 早返,行为与未装配等价
- `SkillRetrievalHook` 永不 block — 任何 SQL / embed / IO 异常 catch 后让 `WorkspaceContextHook` 全量注入兜底
- [ ] 旧 skill(samples 为空,数据库 embedding 仍是旧的 `name + " " + desc` 生成)L2 召回行为与 PR3.6 一致,无回归
- [ ] LLM 漏掉 sample_questions 段时,蒸馏不 reject,fallback 到 `name + " " + desc` 写 embedding(看日志:`Distiller output missing required sections` **不**出现 + skill 落盘成功)

---

### 3.4 PR3.5 修订 — 规则正则覆盖度修正

> **修订日期**:2026-06-24
> **不是主线 PR**;是 PR3 落地后做 demo 自测时发现:**指纹规则正则与 [KNOWLEDGE.md](../workspace/harness-a2a/knowledge/KNOWLEDGE.md) 列举的"标准维度格式"对不齐**,直接修。
> 目标:让 `DimensionStateManager.analyzeQuestionRuleBased()` 抽出的指纹维度集合能覆盖 KNOWLEDGE.md 写明的所有标准形式,**不让用户"换个标准说法"就指纹漂移、连问 N 次也触发不了同指纹蒸馏**。

#### 3.4.1 背景:PR3 上线后发现的不一致

PR2 + PR3 用同一份指纹:
```
fingerprint = tenant | intent | DimensionState.toCacheKey()
```
其中 `toCacheKey()` 完全依赖 `analyzeQuestionRuleBased()` 抽到的维度。如果用户问的话**符合 KNOWLEDGE.md 标准格式**但**正则抽不到**,会:

- `SkillSynthesisHook`(PR2):`state.hasDimensions() == false` 直接 skip,候选表连记录都不会写
- `SkillRetrievalHook`(PR3):L1 fingerprint 永远算不出来,只有 L2 向量(且要求 `embedding.enabled=true`)能兜底

下表是改动前的覆盖缺口(对照 KNOWLEDGE.md §"各维度标准格式"):

| 维度 | KNOWLEDGE 标准 | 改动前正则 | 不命中样例 |
|---|---|---|---|
| 部门 | 8 个固定枚举 | `杭州开发[一二三四五]部` | "云计算实验室"、"杭州技术部"、"杭州服务支持部" 全漏 |
| 小组 | `xxxx组`,长度不定 | `[一-龥]{2}组` | "金融市场自营测试组"(9 字)、"杭州二部 FMBM 应用平台组" 等几乎全漏 |
| 产品线 | `xxxx产品线`,且有非"产品线"后缀的固定名 | `[一-龥]{2}产品线` | "全球市场风险管理应用"、"普惠金融"、"代理国库" 等全漏 |
| 需求项 | `I20260208-0005` | `[一-龥]{2}需求项` | 抽的其实是"XX需求项"前 2 字,从未匹配真正的 itemNo |
| 季度别名 | — | `\d{1,2}季度` | "Q1 杭一部"、"一季度 杭一部" 抽不到 |

#### 3.4.2 变更文件清单

| # | 类型 | 路径 | 改动摘要 | 影响面 |
|---|---|---|---|---|
| 1 | ✏️ 改造 | `agent/dimension/DimensionStateManager.java` | 5 处正则替换 + 1 段新增季度别名归一化逻辑;`EXPLICIT_REQUIREMENT` 形状变化连带删除已死的"指代词前缀"过滤代码 | `analyzeQuestionRuleBased()` 抽出维度的覆盖率从 ~60% 升到 ~95%;**指纹形状不变**(intent/tenant/dimKey 三段拼接顺序与字段含义都保持),PR2 候选表与 PR3 `skill_index.fingerprint` 列已写入的旧值仍兼容 |

#### 3.4.3 改动详情(`DimensionStateManager.java` lines 170–180 + 277–315 + 332–342)

**① 部门 — 补齐 KNOWLEDGE.md 枚举**
```diff
- EXPLICIT_DEPT = Pattern.compile("(杭州开发[一二三四五]部)");
+ EXPLICIT_DEPT = Pattern.compile(
+     "(杭州开发[一二三四五]部|云计算实验室|杭州技术部|杭州服务支持部)");
```
→ "云实验室 Q2 数据" 等问题第一次能进候选表。

**② 小组 — 放宽长度限制**
```diff
- EXPLICIT_TEAM = Pattern.compile("([一-龥]{2}组)");
+ EXPLICIT_TEAM = Pattern.compile("([一-龥A-Za-z0-9]{2,}组)");
```
→ "金融市场自营测试组"、"杭州二部 FMBM 应用平台组"、"杭州五部普惠大文章线上化组" 等可识别。
**注意**:`API及高可用保障团队` 之类不以"组"结尾的暂未抓,如需可加 `|[一-龥A-Za-z0-9]{2,}团队`。

**③ 产品线 — 放宽长度 + 补固定枚举**
```diff
- EXPLICIT_PRODUCT_LINE = Pattern.compile("([一-龥]{2}产品线)");
+ EXPLICIT_PRODUCT_LINE = Pattern.compile(
+     "([一-龥]{2,}产品线|普惠金融|代理国库|代理财政|代理同业|交行输出"
+     + "|全球市场风险管理应用|金融市场共享数据服务)");
```
→ "全球市场风险管理应用"、"普惠金融" 等非"产品线"后缀的固定名能识别。

**④ 需求项 — 抽真正的 itemNo,不再抽"XX需求项"前 2 字**
```diff
- EXPLICIT_REQUIREMENT = Pattern.compile("([一-龥]{2}需求项)");
+ EXPLICIT_REQUIREMENT = Pattern.compile("(I\\d{8}-\\d{4})");
```
→ "I20260208-0005 这个需求项咋样" 现在能抽到。
**连带**:`extractExplicitDimensions` 里曾用 `startsWith("这个") || startsWith("那个")` 过滤指代词,新正则不可能匹配这两个前缀,该 if 块直接简化。

**⑤ 季度别名 — `Q1` / `一季度` 归一化**
新增独立正则与处理块:
```java
EXPLICIT_QUARTER_ALIAS = Pattern.compile("(?:Q|q)([1-4])|([一二三四])季度");
```
处理逻辑放在 `EXPLICIT_SHORT_QUARTER` 之后(优先级最低),抽到后归一化到 `{year}年{n}季度`,与 `EXPLICIT_QUARTER` 走同一条 fingerprint 路径。
→ "Q1 杭一部" / "1季度 杭一部" / "一季度 杭一部" 现在产生**完全相同**的 fingerprint。

#### 3.4.4 数据库变更

**无**。本 PR 不动任何表结构,也不动 fingerprint 拼接逻辑 — 改动是"让更多 question 抽得到维度",不是"改 fingerprint 长什么样"。

#### 3.4.5 行为差异(用户视角)

> 三个 hook(`ResponseCacheHook` / `SkillSynthesisHook` / `SkillRetrievalHook`)都走同一个 `analyzeQuestionRuleBased()` + `buildFromExplicit()`。本 PR 只升级底层正则,不改方法签名,所以三者同时受益 — cache 命中率、PR2 候选累计、PR3 L1 命中率会一并上升,不需要分别改动各 hook。

| 场景 | PR3.5 之前 | PR3.5 之后 |
|---|---|---|
| 第一次问 "云计算实验室 Q1 缺陷率" | 抽不到任何维度,`hasDimensions()=false`,候选表不写,自动蒸馏永远不触发 | 抽到 `dept=云计算实验室 + time=2026年1季度`,候选表 +1 |
| 第二次问 "Q1 云实验室的数据" | 同上,继续不写 | 同上指纹,候选表 hit=2 |
| 第三次问 "云实验室一季度怎么样" | 同上 | 同指纹,hit=3,**触发蒸馏**(达到 threshold) |
| 问 "金融市场自营测试组 1 季度" | `[一-龥]{2}组` 不匹配,group 字段空,只能抽到 time | 抽到 `team=金融市场自营测试组 + time=...`,指纹更精确,蒸馏出来的 skill 也只针对此组 |
| 问 "I20260208-0005 这个需求项的进度" | 抽到 "进度需求项" 前 2 字 → 噪音指纹 | 抽到真正的 `I20260208-0005`,指纹与"查询 I20260208-0005"一致 |
| 问 "杭州开发一部 2026Q1" / "杭州开发一部 2026年1季度" | 两次指纹不同(Q1 抽不到)→ 各算各的 | 两次指纹完全相同,合并计数 |
| **本不在 KNOWLEDGE 标准内的问法**(笔误"杭一"、纯口语"咱们这边") | 抽不到 | **仍然抽不到** — 这是规则方案的固有缺口,规划在后续 C 方案(候选去重走 embedding 近邻)补 |

#### 3.4.6 关键设计点

**Fingerprint 形状向后兼容**
所有改动都在"扩大正则覆盖面",**没有改字段顺序、没有改 `toCacheKey()`、没有改 tenant/intent 拼接形式**。这意味着:
- PR1 `skill_index.fingerprint` 列里已写入的旧值不需要清空,继续可被 PR3 retrieval 命中
- PR2 `skill_candidate.fingerprint` 已累计的 hit_count 不会失效
- 唯一变化:升级后**新写入**的 fingerprint 会更"细"(原本抽不到的维度现在被抽到了),老问题与新问题的指纹形状不同,但这是**正确行为** — 老的"粗指纹"会自然冷却,新的"细指纹"重新开始计数

**为什么不直接重建候选表**
- PR2 候选表的 hit_count 本身就是"近期同类问题热度"信号,清空 = 丢热度数据。
- "粗指纹"已经达 threshold 的候选,其蒸馏出来的 skill 仍然有效(KNOWLEDGE.md 标准的子集);只是新一轮"细指纹"问题会触发更精准的 skill。
- 二者并存几天后,运维侧观察哪些粗 candidate 长期不再被更新,可以手工 mark `BLACKLIST`。

**不引入 LLM 抽维度**
- 在 `handlePreCall` 跑 LLM 会把指纹算到 ~300-800ms 一次,直接吃掉 PR3 L1 路径的优势(L1 本意就是"亚毫秒")。
- LLM 输出不稳定,同一问题两次抽出的字段顺序/同义词可能不同,**反而把指纹打散** — 与 fingerprint 稳定性的目标相反。
- 留作 C 方案的备选(LLM 抽完后用 KNOWLEDGE 字典 normalize),但成本/收益要单独评估,不在本 PR。

#### 3.4.7 回滚

```bash
git revert <pr3.5-commit>
# 数据库:无变更,无需清理
```
回滚后 `analyzeQuestionRuleBased()` 回到 PR3 时期的窄正则,行为与升级前一致;新写入的"细指纹"留在 skill_index 里,旧版本代码 L1 永远 miss(因为它算不出同样形状的指纹),但 L2 与 WorkspaceContextHook 全量注入 fallback 都正常,无功能损失。

#### 3.4.8 验收

启动后保留 PR2/PR3 默认开关(`auto-synth.enabled=true threshold=3` + `retrieval.enabled=true`),逐项验证:

- [ ] 连问 3 遍 "云计算实验室 2026Q1 缺陷率"(每遍换措辞,但都用"云计算实验室" + "Q1/1季度/一季度" 任一组合)→ 第 3 次回复后,`workspace/.../skills/` 新增 SKILL.md,**用户全程没说"保存"**
- [ ] DEBUG 日志看到 `Candidate <fp> hit=1 / hit=2 / hit=3`,且 3 行的 `<fp>` 完全相等(证明 Q1 与"1季度"被归一化到同一 fingerprint)
- [ ] 蒸馏成功后,第 4 次再问 "云实验室 1 季度数据" → `SkillRetrievalHook injected 1 skill(s) ... fp=...|2026年1季度` 出现在日志,证明 L1 命中
- [ ] 问 "杭州开发一部 Q1" 然后问 "杭一部 1 季度" → 前者命中,**后者目前仍 miss**("杭一"非标准形式,这是 C 方案要解决的部分);明确这是已知缺口
- [ ] 问 "I20260208-0005 这个需求项" → 指纹包含 itemNo 而非"项目需求项"前 2 字

#### 3.4.9 已知缺口(留给 C 方案)

规则路径**永远**追不上的场景:

- 用户笔误("杭一" / "开法一部" / "杭五" — KNOWLEDGE 里没有别名,字符串不等)
- 完全口语化("咱们这边"、"这季度"+ 前置无上下文)
- KNOWLEDGE.md 之外的新维度词(运营临时拉的小组,正则没枚举)

**C 方案**(planned):`skill_candidate` 表加 embedding 列,PreCall 算完规则指纹后,**额外**用 question 的 embedding 在候选表里做 cosine 近邻搜索(threshold 0.85);命中近邻就用近邻已有的 fingerprint(承认同一类问题),没命中才走规则指纹。复用 PR3 已搭的 `EmbeddingClient + 向量列存`,预计 ~4 小时。当前不上;PR3.5 跑两天观察规则覆盖率再决定。

---

### 3.5 PR4 设计文档 — SkillEvolutionHook 失败反馈闭环

> 对应方案:§"能力 3:失败反馈闭环" + §"落地顺序" 的 PR4 行。
> **状态**:设计阶段,未开始实现。需要先就 §3.5.2 的 4 项决策达成一致再动代码。
> **目标**:把 PR2(自动沉淀)+ PR3(智能召回)的开环路径闭成"沉淀 → 召回 → 演进 / 拉黑 → 重新召回"的闭环。

#### 3.5.1 整体形状

```
PreCall    SkillRetrievalHook (PR3, priority=-50)
             ├─ L1/L2 命中后,把 SKILL.md body 追加到 system
             └─ 命中列表写入 ctx.attribute("skills.retrieved", List<String>)    ← PR4 新增
                ↓
LLM 调用 + 工具执行
                ↓
PostCall   SkillEvolutionHook (PR4, priority=+60)
             ├─ 读 ctx.attribute("skills.retrieved")
             ├─ 判 success / failure(详见 §3.5.4)
             ├─ 同步 UPDATE skill_index SET success_count / failure_count += 1
             └─ 越限触发:
                   • failure_rate > evolveThreshold + uses ≥ minEvolve
                     → 异步 SkillDistiller.evolve(oldBody, failedTrace) → 覆写同文件,version++
                   • failure_rate > blacklistThreshold + uses ≥ minBlacklist
                     → status='blacklist'(文件保留,SkillRetrievalHook 召回时 WHERE status != 'blacklist' 过滤)
```

**核心数据**:`skill_index` 表里 `failure_count INT / success_count INT / status VARCHAR(20)` 三列在 PR1 已建好 — **零 DDL**。

#### 3.5.2 需要先决策的 4 件事

**(a) 失败信号源**

PR4 原始设计有三个失败信号源,但 `git status` 显示 `DataGroundingHook.java` 已删(`D` 标记)。所以必须先决定信号源:

| 选项 | 失败覆盖率 | 实现成本 | 假阳性风险 |
|---|---|---|---|
| **A. 只用 PythonExecRetryHook + 用户负反馈** | ~40% | 极低,**复用现有 hook 信号** | 低 |
| **B. 恢复轻量 grounding 校验**(只查 LLM 回复里的数值是否出现在 query_quality_data 返回过的集合) | ~75% | 中,需新增 1 个 PostCall hook | 中(四舍五入会误伤) |
| **C. 加 LLM 自纠错关键词探测**("抱歉"/"我之前算错") | ~25% | 低 | 高(LLM 礼貌用语) |

**建议:A**。PR4 先把闭环跑通,grounding 校验留 PR5。Grounding 误判 → 好 skill 被错降级 → 危害大于"少抓一些失败"。

**(b) PR3 attribute plumbing 改 1 行**

`SkillRetrievalHook` 命中后,需要把召回的 skill 名字写进 `RuntimeContext.attribute("skills.retrieved", List<String>)`,这样 PostCall 才知道给谁记账。**这一行改放在 PR4 里**。

**(c) 演进 prompt 是否带失败 trace**

带上 → LLM 知道"上次为什么错"、修复方向更准,但 prompt 涨 2~3 倍。
**建议:带上,只截最近 1 条失败 trace 的 500 字头部** + 老 SKILL.md body 全文。

**(d) 并发**

per-skill `markEvolving(name)` CAS,沿用 `SkillSynthesisRunner.markSynthesized` 的套路,避免同一个 skill 被两个并发请求同时 evolve。**建议确认这个方向**。

#### 3.5.3 变更文件清单

| # | 类型 | 路径 | 改动摘要 | 影响面 |
|---|---|---|---|---|
| 1 | 🆕 新增 | `harness/hooks/SkillEvolutionHook.java` | `priority()=+60`,PostCall 单事件;读 `ctx.attribute("skills.retrieved")` → 判 success/failure → UPDATE counts → 越限异步 evolve 或 blacklist | 新增 hook,默认 `enabled=false` |
| 2 | 🆕 新增 | `harness/skills/SkillEvolutionRunner.java` | per-skill ConcurrentHashMap CAS `markEvolving / markEvolved`;`boundedElastic` 上跑 `SkillDistiller.evolve` + 落盘 + embed | 新增 bean,与 `SkillSynthesisRunner` 并列 |
| 3 | ✏️ 改造 | `harness/skills/SkillDistiller.java` | 新增 `evolve(oldBody, failedTrace, exemplarQuestion)` 方法:prompt 让 LLM 在 oldBody 基础上修复 + 输出新 body;parse 复用既有的 NAME/DESC/BODY 三段提取(name 必须与原 skill 一致,否则 reject) | 新方法,旧 `distill()` 不动 |
| 4 | ✏️ 改造 | `harness/skills/SkillIndexRepository.java` | 新增 `incrementSuccess(name)` / `incrementFailure(name)` / `markBlacklist(name)` / `findStats(name)` 4 个方法;SQL 走 `UPDATE skill_index SET ... WHERE name=?` 原子自增 | 新增方法,不改表结构 |
| 5 | ✏️ 改造 | `harness/skills/SkillVectorIndex.java` 或 `SkillIndexRepository`(取决于召回逻辑当前住哪) | `topK` / `findByFingerprint` SQL 追加 `AND (status IS NULL OR status != 'blacklist')`;现有 `status='active'` 行为不变 | 召回逻辑收紧;不影响落盘 |
| 6 | ✏️ 改造 | `harness/hooks/SkillRetrievalHook.java` | 命中后追加一行 `ctx.setAttribute("skills.retrieved", List.copyOf(namesHit))`;原 inject 逻辑不动 | PR4 输入打通 |
| 7 | ✏️ 改造 | `service/SupervisorService.java` | 注入 `SkillEvolutionRunner`(必选);`build()` 末尾装配 `SkillEvolutionHook`;新增 `harness.skills.evolution.*` 共 5 个 `@Value` | DI 装配,默认关 |
| 8 | ✏️ 改造 | `resources/application.properties` | 追加 `harness.skills.evolution.*` 配置块(注释 + 默认值) | 仅文档,默认行为不变 |

#### 3.5.4 判 success / failure 的具体规则(选项 A 方案)

PostCall 拿到 `skills.retrieved=[name1, name2]` 后,**对每个 skill 单独打分**:

```
failure 信号(任一即可):
  1. 本次 turn 内 python_exec retry count ≥ 2
     ← 从 ctx.attribute("python_exec.retry_count") 或现有 PythonExecRetryHook 暴露的状态读
  2. PostCall 抛 exception 且 cause 不是 CacheHitException(工具全失败)
  3. 用户下一轮 message 含拒绝关键词 ["不对","错了","重算","重新","不是这样","不正确"]
     ← 跨 turn 信号,在 turn[N+1] PreCall 回溯给 turn[N] 的 skill 记账

success 信号(默认值):
  上述 failure 均不命中 → success_count += 1
```

**跨 turn 用户负反馈的实现**

最简方案:每次 PostCall 把 `[turn_n_skills, timestamp]` 写进 `RuntimeContext.attribute("skills.last_turn")`,下一轮 PreCall **第一件事**判用户输入:

```java
String userInput = extractUserQuestion(...);
List<String> lastTurnSkills = (List<String>) ctx.getAttribute("skills.last_turn");
if (lastTurnSkills != null && matchesRejectionPattern(userInput)) {
    for (String name : lastTurnSkills) repo.incrementFailure(name);
} else if (lastTurnSkills != null) {
    for (String name : lastTurnSkills) repo.incrementSuccess(name);
}
ctx.setAttribute("skills.last_turn", null);  // 消费完清空
```

放 PreCall 而不是 PostCall 是因为:本轮的 success/failure 信号在本轮 PostCall 已经知道一部分(retry/exception),但用户接受度只能等下一轮。两个信号源在不同生命周期点收集 — failure 立刻记,success 等下一轮无负反馈才记。

#### 3.5.5 演进 / 黑名单触发

```
failure_rate = failure_count / (success_count + failure_count)
total_uses   = success_count + failure_count

if failure_rate > blacklistThreshold && total_uses ≥ minBlacklist:
    status = 'blacklist'
    # 文件保留,只是不再被召回。运维 review 后可手动 UPDATE status='active' 复活
    # 不删 embedding 行,避免反复拉黑→平反时丢失累计样本

else if failure_rate > evolveThreshold && total_uses ≥ minEvolve:
    if SkillEvolutionRunner.markEvolving(name):  # CAS,失败说明已有别人在演进
        async on boundedElastic:
            SkillDistiller.evolve(oldBody, failedTrace, exemplarQuestion)
              ├─ 成功:落盘覆写 SKILL.md(同文件名,frontmatter version++)
              │       重新 embed 写回 skill_index.embedding
              │       success_count = 0, failure_count = 0(给新版本干净窗口)
              │       markEvolved(name)
              └─ 失败:markEvolved(name) 释放 lock,本次放过,等下次再触发
```

两个阈值 + 两个最小样本数让冷启动期不会立刻误降级。

#### 3.5.6 关键设计点

**为什么 evolve 是"同名覆写 + version++"而不是"新名字 + 旧名字 deprecate"**
- L1 fingerprint 路径依赖 `skill_index.fingerprint`,改名要同步迁移 fingerprint,迁移失败就 L1 silent miss
- 用户调用 `save_skill` 时不可能知道"上次已经叫这个名字了",换名等于把每个 skill 的版本树搞成森林
- SKILL.md 文件名 = skill 身份,version 在 frontmatter 内 — 这是 PR1 定下来的约定,PR4 应继承

**为什么 blacklist 不删 row / 不删文件**
- 删 row 丢累计样本 — 平反时归零,等于"这个 skill 从来没失败过"
- 删文件丢可读历史 — 后人看 git log 找不到曾经存在过的 skill
- `status='blacklist'` 是只读软删,所有写路径不变;唯一改的是召回 SQL 加 `WHERE status != 'blacklist'`

**为什么 evolve 后 counts 归零**
- 老 body 的失败样本不能算新 body 的账 — 否则新 body 一上线就背着 0.5 的失败率,下一次失败立刻又触发 evolve,陷入 evolve loop
- 给新 body `(0, 0)` 起点,要重新攒到 `minEvolve` 次才会再次评估

**为什么不在 PR4 做"skill 合并"**
`SkillDistiller.merge(skillA, skillB)` 是个诱人的方向 — 但合并意味着 fingerprint 也得合并,L1 路径会失效。PR4 不碰,留 PR5 或更后面。

**失败信号选 A 而不是 B(grounding 校验)的取舍**
- B 的 false positive 假设场景:LLM 输出 `平均缺陷密度 0.42`,query_quality_data 返回的明细里没出现 0.42 — 但实际上是 LLM 对明细做了正确的平均。这种"派生数据"无法被 grounding 校验区分
- A 的 false negative 场景:LLM 编了一个数字,python_exec 没出错,用户也没否认(用户也没核对) — 这条失败被漏掉了。但漏掉 ≠ 标错,后续重复出错累计上来仍会触发
- 闭环优先级:**先让闭环跑起来**,样本量上来后,grounding 误差也能从对比中识别(同一 skill 在 grounding 信号下被频繁标错但 retry/负反馈干净 → 反推 grounding 失准)

#### 3.5.7 配置项(规划)

| 键 | 默认 | 说明 |
|---|---|---|
| `harness.skills.evolution.enabled` | `false` | 总开关。关闭时 hook 透传,success/failure 都不记账 |
| `harness.skills.evolution.fail-rate-evolve` | `0.3` | 失败率超过此值且样本数足够 → 触发 evolve |
| `harness.skills.evolution.fail-rate-blacklist` | `0.6` | 失败率超过此值且样本数足够 → 拉黑 |
| `harness.skills.evolution.min-uses-evolve` | `5` | evolve 所需的最小累计 use(success+failure) |
| `harness.skills.evolution.min-uses-blacklist` | `10` | blacklist 所需的最小累计 use |
| `harness.skills.evolution.rejection-keywords` | `不对,错了,重算,重新,不是这样,不正确` | 用户负反馈关键词,逗号分隔 |
| `harness.skills.evolution.max-evolutions-per-skill` | `3` | 同一 skill 最多 evolve 次数;超过仍失败 → 强制 blacklist。防止 evolve loop |

#### 3.5.8 回滚

```bash
git revert <pr4-commit>
# 数据库残留:已写入的 skill_index.failure_count / success_count / status 可留可清
#            UPDATE skill_index SET failure_count=0, success_count=0, status='active';   # 可选
```

PR4 完全可独立回滚:回滚后 `SkillEvolutionHook` / `SkillEvolutionRunner` 全部消失,`SkillRetrievalHook` 多写的 `ctx.attribute("skills.retrieved")` 没人读但不影响透传,行为与 PR3.8 完全一致。

---

### 3.6 PR4 变更汇总 — SkillEvolutionHook 落地

> 对应方案:§"能力 3:失败反馈闭环" + §"落地顺序" 的 PR4 行。
> 设计文档:§3.5(讨论阶段,已落地。本节以实现视角覆盖)。
> 落地"PR2 沉淀 → PR3 召回 → PR4 演进 / 拉黑 → 重新召回"的闭环。默认开关 **关**。

#### 3.6.1 变更文件清单

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

#### 3.6.2 数据库变更

**无**。`skill_index` 表的 `success_count` / `failure_count` / `status` 三列在 PR1 已经 `CREATE TABLE` 时建好(PR1 的 DDL 里写了"reserved for PR4"注释);PR4 只是开始 read/write 这三列,**没有任何 ALTER TABLE**。

#### 3.6.3 行为差异(用户视角)

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

#### 3.6.4 关键设计点

**失败信号源 = `python_exec retry ≥ 2` + 用户跨轮负反馈(方案 A)**

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

**PostCall 自己扫 memory,不去改 PythonExecRetryHook**

设计文档原计划是给 `PythonExecRetryHook` 加 `ctx.put("python_exec.failure_count", ++count)`。实现时换成 `SkillEvolutionHook` PostCall 直接遍历 `memory.getMessages()` 数 `ToolResultBlock` 里出现 `[python_exec] exit=非零` token 的次数。

为什么:
- 单点改动,无需协调两个 hook 之间的字段约定
- `PythonExecRetryHook` 不必感知 PR4 的存在,模块解耦
- memory 是 PostCall 时一定可达的(`event.getMemory()`),性能开销 = O(messages × content blocks),量级几十,可忽略
- 缺点:与 `PythonExecRetryHook` 的 `exit` parser 同源,任一方调整了 banner 格式都得同步改 —— 但这俩文件距离很近,review 时能看见

**跨轮负反馈用 per-session in-memory cache,不入库**

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

**演进时只刷 embedding 不动 fingerprint**

`SkillVectorIndex.upsertVector(name, fingerprint, vec)` 三参版会把 fingerprint 覆盖。但 evolve 路径要保留 PR2 当初 stamp 的那个 canonical fingerprint(同维度问题继续走 L1)。

所以新增 `upsertEmbeddingOnly(name, vec)`:`UPDATE skill_index SET embedding = ? WHERE name = ?`,只动 embedding 列。

老版本的 PR2 synthesis 路径继续用三参 `upsertVector` 写入 (name, fingerprint, vec) 初始三元组;PR4 evolve 路径只用 `upsertEmbeddingOnly`。

**evolve 后 counts 归零的必要性**

老 body 的失败样本不能算新 body 的账。否则新 body 一上线就背着 0.5 的失败率,下一次失败立刻又触发 evolve → evolve loop。

`resetCounts(name)` 让新版本拿 `(0, 0)` 起点,要重新攒到 `min-uses-evolve`(默认 5)次才会再次评估。

副作用:新版本如果立刻失败,会比旧版本晚 5 个样本才被标记 —— 这是用"避免 evolve loop"换"对新版本宽容"的取舍,可接受。

**blacklist 不删 row、不删文件**

- 删 row 丢累计样本 —— 平反后等于"这个 skill 从来没失败过",信息丢失
- 删文件丢可读历史 —— 后人看 git 找不到曾经存在过的 skill
- `status='blacklist'` 是软删,只读;`SkillVectorIndex` 现有 SQL 已经 `WHERE status='active'` 过滤,**不需要改一行 SQL** blacklist 就自动停止召回

运维人工 review 后:`UPDATE skill_index SET status='active' WHERE name='xxx'` 即可恢复。

**LLM 改名 reject 而不是 rename**

`distiller.evolve` 的 prompt 已经强约束 `name: <原名>`,但 LLM 偶尔会自作主张换更"准确"的名字。

如果接受 rename:
- 文件名要重命名(`SkillFileSystemHelper.saveSkills` 会按 name 落 `skills/<name>/SKILL.md`)
- `skill_index.name` 是 PK,要 INSERT 新行 + DELETE 旧行,或 UPDATE PK(不支持)
- PR2 stamp 的 fingerprint 留在旧 name 行上,新 name 行 fingerprint = NULL → L1 失效

不值得。直接 `equalsIgnoreCase` 校验,改名就拒绝,本次演进作废,下次失败再触发 —— 多花一次 LLM 调用,但保证 skill 身份稳定。

**priority +60 的位置**

- PR2 `SkillSynthesisHook` = +50
- 框架内部 hook(workspace context 等)= +10 / +20
- PR3 `SkillRetrievalHook` = -50(早于注入路径)
- PR4 `SkillEvolutionHook` = **+60**

PostCall 要在所有 PreCall / 工具执行链路结束后跑,所以 priority 比 PR2 更晚。PreCall 路径上 PR4 与 PR3 顺序无所谓(PR4 PreCall 只读 runner 内部 cache,不读 attribute),但保持 PR4 在 PR3 之后符合数字直觉。

**失败永远静默 + 退化(同 PR3)**

任何一步出错(SQL UPDATE 失败 / LLM 调用 timeout / 文件 IO / cache concurrent modification):
- 记 `log.warn` 或 `log.debug`,**绝不抛**
- `onEvent` 整段 try/catch,本 hook 哪怕崩了也走原本的 PR2/PR3 路径
- 用户请求路径完全不感知

#### 3.6.5 配置项

| 键 | 默认 | 说明 |
|---|---|---|
| `harness.skills.evolution.enabled` | `false` | 总开关。关闭时 hook 在 SupervisorService 阶段就不装配 |
| `harness.skills.evolution.fail-rate-evolve` | `0.3` | 失败率超此值且样本数足够 → 触发异步 evolve |
| `harness.skills.evolution.fail-rate-blacklist` | `0.6` | 失败率超此值且样本数足够 → 拉黑(优先级高于 evolve) |
| `harness.skills.evolution.min-uses-evolve` | `5` | evolve 所需最小累计 use(success_count + failure_count) |
| `harness.skills.evolution.min-uses-blacklist` | `10` | blacklist 所需最小累计 use |
| `harness.skills.evolution.rejection-keywords` | `不对,错了,重算,重新,不是这样,不正确` | 用户负反馈关键词,逗号分隔,大小写不敏感 |

#### 3.6.6 回滚

```bash
git revert <pr4-commit>
# 数据库残留:已写入的 skill_index.success_count / failure_count / status 可留可清
#            UPDATE skill_index SET failure_count=0, success_count=0, status='active';   # 可选
```

PR4 完全可独立回滚:回滚后 `SkillEvolutionHook` / `SkillEvolutionRunner` 全部消失,`SkillRetrievalHook` 多写的 `runtimeContext.skills.retrieved` 没人读但不影响透传,行为与 PR3.8 完全一致。已被标记为 `blacklist` 的 skill 在回滚后仍然被 `WHERE status='active'` 过滤掉(PR3 已有的 filter),如需复活手动 `UPDATE skill_index SET status='active'`。

#### 3.6.7 验收(对照方案 §1.6 evolution 项)

- [ ] `harness.skills.evolution.enabled=true` + 一个已经累计 ≥5 次召回的 skill(可用 PR3.8 留下的 `quarterly_defect_density_by_dept`)
- [ ] 连续构造 3 次 `python_exec retry ≥ 2` 的失败请求 → `SELECT success_count, failure_count FROM skill_index WHERE name = '...'` 显示 `failure_count ≥ 3`,`success_count` 不变
- [ ] 触发 evolve 阈值(默认 fail-rate > 0.3 且 total ≥ 5)→ 日志看到 `Skill evolution triggered for '<name>'`,`SKILL.md` 同文件名 frontmatter `version: 2`,`success_count=0 failure_count=0`
- [ ] 构造 10 次失败请求(fail-rate > 0.6)→ `status='blacklist'`,下一次同 fingerprint 请求 L1 / L2 都 miss(被 `status='active'` filter 过滤),`WorkspaceContextHook` 全量注入路径仍在,行为不破
- [ ] 用户在 turn N+1 输入"不对" → turn N 召回的 skill `failure_count + 1`(查 SQL);turn N+1 改成普通提问 → `success_count + 1`
- [ ] 同一 skill 并发触发 evolve(同 fingerprint 在不同 session 同时累到阈值)→ 只有第一个 CAS 成功的请求实际跑 distiller(看日志中 `already evolving in another thread; skipping`)
- [ ] LLM 在 evolve 时把 name 改了 → 日志 `LLM tried to rename ... rejecting`,本次演进作废,下次失败再触发,**SKILL.md 文件不变**
- [ ] 关闭 `evolution.enabled` → 行为与 PR3.8 完全一致(无任何 `UPDATE skill_index SET success_count/failure_count`)

#### 3.6.8 下一步(PR5 预告)

候选方向(按优先级):
1. **Skill 合并** (`SkillDistiller.merge`):周期性扫 `skill_index` 找 embedding cosine > 0.95 且 fingerprint 相似的 skill 对,LLM 合并 + 新 fingerprint 写入 + 旧 skill blacklist。
2. **`WorkspaceContextHook` 退场**:当 PR3 retrieval 准确率经 PR4 数据验证稳定后,反射禁用 JAR 内部的全量注入 hook,让 system prompt 只剩 PR3 的 spotlight 块。

---

## 4. 优化方案

> 本章节合并自 [`memory-skill-optimization-plan.md`](./memory-skill-optimization-plan.md),完整保留 8 个优化点 + 实施状态。

### 4.1 变更文件清单

| # | 类型 | 路径 | 改动摘要 | 影响面 |
|---|------|------|----------|--------|
| 1 | 修改 | `MySqlEpisodicMemory.java` | 增加 `vectorSearch()` / `embedContent()` / `cosine()` 方法;`search()` 改为优先向量检索后 fallback FTS | 高 — 核心检索路径 |
| 1 | 修改 | `EpisodicMemoryConfig.java` | 新增 `vectorSearchEnabled`、`vectorMinCosine` 配置项 | 低 — 仅配置类 |
| 2 | 修改 | `SkillVectorIndex.java` | 新增 `CachedSkill` 记录类、`@Scheduled` 定时刷新缓存、`topK()` 优先读缓存、`upsertVector/EmbeddingOnly` 写穿透 | 高 — 检索性能核心 |
| 3 | 修改 | `SkillIndexRepository.java` | 新增 `tryAcquireEvolveLock()` / `releaseEvolveLock()` 方法;DDL 增加 `evolving` 列 | 中 — 新增 DAO 方法 |
| 3 | 修改 | `SkillEvolutionRunner.java` | `dispatchEvolve()` 改为 local CAS + DB CAS 双重检测;新增 MySQL `skill_pending_judgement` 表持久化及 `storePendingToDb` / `loadPendingFromDb` / `removePendingFromDb` | 高 — 演进流程核心 |
| 4 | 修改 | `WorkspaceMaterializer.java` | classpath skills 复制目标改为 `skills-builtin/`;新增 `SKILLS_BUILTIN_DIR` 常量 | 中 — 影响技能目录结构 |
| 5 | 修改 | `SupervisorService.java` | 构建 agent 时 `SkillRetrievalHook` 指向 `skills-auto/`、`SkillSaveTool` 指向 `skills-auto/`;注释文档化分层注入设计 | 中 — 影响注入路径 |
| 5 | 修改 | `SkillRetrievalHook.java` | `skillsDir` 改为外部注入;新增 `episodicMemory` 依赖;L2 命中后调用 `queryEpisodicContext()` 注入 `## 最近参考案例` | 高 — 检索+记忆联动 |
| 5 | 修改 | `SkillSynthesisRunner.java` | `skillsDir` 改为 `skills-auto/` | 低 — 仅改路径常量 |
| 5 | 修改 | `SkillEvolutionRunner.java` | `skillsDir` 改为 `skills-auto/` | 低 — 仅改路径常量 |
| 5 | 修改 | `SkillSaveTool.java` | 通过 SupervisorService 注入 `skills-auto/` 路径 | 低 — 仅改路径常量 |
| 6 | 修改 | `SkillDistiller.java` | `distill()` 增加重试逻辑;新增 `parseLenient()` 宽松解析(name/description 有 fallback,仅 body 缺失才返回 null);`RETRY_PROMPT_SUFFIX` | 中 — 蒸馏质量改进 |
| 8 | 修改 | `HarnessA2aRunner.java` | `CacheHitException` 处理分支调用 `recordCacheHitToEpisodic()`;新增 `recordCacheHitToEpisodic()` 方法 | 中 — 缓存记忆联动 |
| 8 | 修改 | `ResponseCacheHook.java` | cache HIT 路径增加 `bumpAndMaybeSynthesize()` 调用 | 低 — 仅合成增强 |

### 4.2 Context

当前项目实现了完整的 Memory + Skill 自进化管道(PR1-PR4),但在底层源码阅读和实际运行中发现了多个可优化点。这些优化点集中在:**语义检索能力不足**、**跨 JVM 一致性缺失**、**检索效率低下**、**技能蒸馏质量可控性差**、**缓存与记忆的联动缺失** 五个方面。

优化目标是:不改变整体架构的前提下,在关键瓶颈处做针对性改进,使系统在数十到数百技能规模下依然保持高效、准确、可观测。

---

### 4.3 优化点 1:EpisodicMemory 从 MySQL FTS 升级为向量检索

#### 问题

`MySqlEpisodicMemory.search()` 使用 MySQL `MATCH(content) AGAINST(? IN NATURAL LANGUAGE MODE)` 全文索引。中文分词效果差,同义词/近义词/语义相似完全无法匹配。例如用户问"上周质量情况"和"最近一期质量数据"虽然是同一语义,但 FTS 无法关联。

#### 方案

复用已有的 `EmbeddingClient` 管线,在 `MySqlEpisodicMemory` 中增加向量化搜索路径:

1. **存储改造**:在 episodic memory 表新增 `embedding LONGTEXT` 列(JSON float[], 与 skill_index 模式一致)
2. **写入改造**:`recordSession()` 时异步调用 `EmbeddingClient.embed()` 生成消息内容的向量并存储
3. **搜索改造**:`search()` 先 embedding query,然后 fallback 到 JVM 内 cosine 搜索(复用 `SkillVectorIndex` 的 cosine 计算逻辑),对得分低于阈值的再 fallback 到 FTS
4. **配置开关**:`harness.memory.episodic.vector-search.enabled=true`,默认关闭保持向后兼容

#### 涉及文件

- `MySqlEpisodicMemory.java` — 核心改造
- `EpisodicMemoryConfig.java` — 新增向量搜索配置项
- `EmbeddingClient.java` — 已被 Skill 模块引用,可直接注入
- `EpisodicLongTermMemoryAdapter.java` — 无需改动

#### 预期收益

- 中文语义搜索准确率显著提升("上周质量" ↔ "最近一期质控数据" 能匹配)
- 复用已有 EmbeddingClient,无需新增依赖

---

### 4.4 优化点 2:SkillRetrievalHook L2 全表扫描 → 缓存加速

#### 问题

`SkillVectorIndex.topK()` 每次 L2 检索都 `SELECT name, description, embedding FROM skill_index WHERE status = 'active' AND embedding IS NOT NULL`,加载所有技能的全量 embedding 到 JVM 内存中反序列化 + 计算 cosine。随着技能数增长(几十→几百),每次请求都要做 O(n) 的全扫描。

#### 方案

增加两级缓存:

1. **JVM 内缓存**:`SkillVectorIndex` 维护一个 `@PostConstruct` 定时刷新的 `List<CachedSkill>` 内存副本,包含 `(name, description, embedding float[], cosineNorm)`。缓存刷新间隔默认为 60 秒(可配置)。
2. **增量更新**:当 `upsertVector()` / `upsertEmbeddingOnly()` 被调用时,同时更新内存缓存(写穿透)。
3. **兜底**:如果缓存为空或刷新失败,走原有的全表 SQL 路径。

#### 涉及文件

- `SkillVectorIndex.java` — 增加缓存结构、定时刷新、写穿透
- `InfraConfig.java` 或相关配置类 — 新增 `harness.skills.retrieval.cache-refresh-seconds=60`

#### 预期收益

- L2 检索从 O(n) SQL + JSON 反序列化 降低到 O(n) 内存计算(避免 SQL 和 Jackson 开销)
- 实测预计从 ~50ms 降低到 ~1ms(100 技能规模下)

---

### 4.5 优化点 3:跨 JVM 演进 CAS 从内存级升级为 MySQL 级

#### 问题

`SkillEvolutionRunner.evolving` 使用 `ConcurrentHashMap<String, Boolean>` 防止同一技能被两个线程同时演进。这是 per-JVM 的 CAS,多副本部署下两个 JVM 会同时演进同一个技能,浪费 LLM 调用并可能产生文件写入竞争。

#### 方案

借鉴 `SkillCandidateRepository.markSynthesized()` 的模式,在 `skill_index` 表增加 `evolving` 列(`BOOLEAN DEFAULT FALSE`),演进开始时 `UPDATE skill_index SET evolving = TRUE WHERE name = ? AND evolving = FALSE`,成功(affected_rows > 0)才执行演进,结束后 reset。

保留 per-JVM 的 `ConcurrentHashMap` 作为第一道门(减少 DB 调用),MySQL 的 `UPDATE ... WHERE evolving = FALSE` 作为最终的跨 JVM 仲裁。

#### 涉及文件

- `SkillIndexRepository.java` — 新增 `tryAcquireEvolveLock()` / `releaseEvolveLock()` 方法
- `SkillEvolutionRunner.java` — `markEvolving()` / `markEvolved()` 改为先查 local CAS 再查 DB CAS

#### 预期收益

- 多副本部署下,每个技能最多只有一个演进中的实例
- 避免重复的 LLM 调用费用

---

### 4.6 优化点 4:Pending Judgement 缓存从 JVM 内存升级为 MySQL 持久化

#### 问题

`SkillEvolutionRunner.pending` 是一个 `LinkedHashMap` LRU 缓存(max 1024),跨回合拒绝检测依赖它。JVM 重启丢失全部 pending judgement;多 JVM 部署下同一 session 的下一回合可能落到不同 JVM 上,检测完全失效。

#### 方案

将 pending judgement 存入 MySQL 表 `skill_pending_judgement`,key 为 session key,TTL 为 5 分钟(Cron 或定期清理):

```sql
CREATE TABLE IF NOT EXISTS skill_pending_judgement (
    session_key       VARCHAR(255) PRIMARY KEY,
    skills_json       TEXT NOT NULL,
    exemplar_question VARCHAR(1024),
    created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
```

`SkillEvolutionRunner` 改为优先查 MySQL,然后写 MySQL;性能敏感路径保留 LRU 缓存作为 L1,MySQL 作为 L2 兜底。

#### 涉及文件

- `SkillEvolutionRunner.java` — 新增 `PendingJudgementStore` 内部逻辑(或提取为新类 `SkillPendingJudgementDao`)
- 新增 SQL 表 schema

#### 预期收益

- 跨 JVM 部署下跨回合拒绝检测正确工作
- JVM 重启不丢失 pending 状态

---

### 4.7 优化点 5:元技能全量 + 业务技能选择性注入(分层注入)

#### 问题

当前所有技能文件(包括 `tool_index`、`data_primitives` 等元技能,以及自动合成的业务技能)都在同一个 `.agentscope/workspace/harness-a2a/skills/` 目录下。`FileSystemSkillRepository`(Harness 内部机制)会全量加载所有 SKILL.md 注入到 system prompt。随着自动合成技能增多(几十→几百),prompt 被无关技能撑爆。

#### 关键发现:WorkspaceContextHook 不注入技能

经过反编译 `agentscope-harness-1.1.0-RC2.jar` 确认:
- **`WorkspaceContextHook` 只注入** `AGENTS.md`、`MEMORY.md`、`knowledge/` 和 `additionalContextFiles`,**完全不扫 `skills/` 目录**
- SKILL.md 的全量注入由 `HarnessAgent.Builder` 内部的 `FileSystemSkillRepository`(通过 `resolveSkillBox()`)完成
- Builder 提供 `disableWorkspaceContext()` 方法,但**没有**禁用技能全量注入的 API

#### 方案:classpath 元技能 + workspace 业务技能,分层注入

将技能目录拆为两个来源:

| 来源 | 内容 | 注入方式 |
|------|------|---------|
| `classpath:workspace/skills/`(映射到 `.agentscope/.../skills-builtin/`) | `tool_index`、`data_primitives` 等元技能 | **全量加载**(基础设施,必须始终可用)|
| `.agentscope/.../skills-auto/` | 自动合成/保存的业务技能 | **仅 L1/L2 命中时注入**(`SkillRetrievalHook` 读取该目录)|

具体改动:

1. **`WorkspaceMaterializer.java`**:将 `classpath:workspace/skills/` 复制到 `.agentscope/.../skills-builtin/`(而非 `skills/`)
2. **`SupervisorService.java`**:构建 agent 时,通过自定义 `AgentSkillRepository` 只加载 `skills-builtin` 目录(`FileSystemSkillRepository` 指向 `skills-builtin`),或者通过反射/Builder 扩展实现只注入元技能
3. **`SkillRetrievalHook`**:`skillsDir` 改为 `skills-auto/`,只从该目录读取命中技能
4. **`SkillSynthesisRunner`**:保存新技能到 `skills-auto/`
5. **`SkillEvolutionRunner`**:读取/演进技能从 `skills-auto/`
6. **`SkillSaveTool`**:保存新的 SKILL.md 写入 `skills-auto/`

#### 涉及文件

- `WorkspaceMaterializer.java` — 改目标路径为 `skills-builtin`
- `SupervisorService.java` — 调整技能仓库配置
- `SkillRetrievalHook.java` — 改 `skillsDir` 为 `skills-auto`
- `SkillSynthesisRunner.java` — 改 `skillsDir`
- `SkillEvolutionRunner.java` — 改 `skillsDir`
- `SkillSaveTool.java` — 改 `skillsDir`
- `DebugController.java` — 调试接口可能需要适配双目录
- `InfraConfig.java` — 可能需要新增配置项 `harness.a2a.workspace.skills-auto-dir`

#### 预期收益

- 业务技能从 0 增长到数百个,system prompt 始终只注入元技能(2个) + L1/L2 命中的 1-3 个业务技能
- 元技能始终全量在线,工具路由不受影响
- 与元工具调用架构完全兼容

---

### 4.8 优化点 6:Skill Distiller 蒸馏 prompt 增加结构化输出约束

#### 问题

当前 `SkillDistiller.parse()` 使用纯正则从 LLM 自由文本输出中提取 `name/description/body/sample_questions`。LLM 输出格式稍有偏差(缺少某段、fence 没闭合、多输出额外内容)就会导致整个蒸馏失败(返回 null,走 `markRejected` 路径)。这既浪费了 LLM 调用,又可能错过真正有价值的技能。

#### 方案

1. **增加重试机制**:`distill()` 首次 parse 失败后,用一条简短 prompt 要求 LLM 仅重新输出格式正确的版本(不重复完整内容),重试 1 次。
2. **宽松 parse 策略**:当某个字段缺失时,不直接返回 null,而是:
   - 如果只有 `name` 缺失,用 fingerprint hash 的前 8 位作为 fallback name
   - 如果只有 `description` 缺失,用 name 作为 description
   - 只有当 `body` 缺失时才返回 null(body 是技能的核心,没有 body 的 skill 无意义)
3. **输出日志增强**:`parse()` 失败时除了 `log.warn`,把原始的 LLM 输出完整记录到单独的日志文件或表中,供调试分析。

#### 涉及文件

- `SkillDistiller.java` — 增加重试逻辑、宽松 parse、日志增强

#### 预期收益

- 蒸馏成功率提升(预估从 ~70% 提升到 ~95%)
- 减少因格式问题导致的 LLM 费用浪费

---

### 4.9 优化点 7:Skill 检索结果中加入 episodic 上下文增强

#### 问题

当前 `SkillRetrievalHook` 只注入技能文件本身,不会把该技能在历史对话中的成功/失败案例一并提供给 LLM。LLM 只知道"怎么做"(技能步骤),不知道"上次这么做出了什么问题"(上下文)。

#### 方案

在 `SkillRetrievalHook` 的 L2 命中后,额外查询 `EpisodicMemory` 获取与该技能 fingerprint 相关的最近 1-2 条会话记录,并在注入技能正文时附加一个 "## 最近参考案例" 小节。

具体流程:
1. `SkillRetrievalHook` 在 L2 命中后,用 skill description 作为 query 调用 `episodicMemory.search(description, 2)`
2. 如果返回了结果,将结果格式化为 markdown 引用块附加在系统 prompt 的技能正文之后

#### 涉及文件

- `SkillRetrievalHook.java` — 增加 EpisodicMemory 查询和注入逻辑
- `SupervisorService.java` — 确保 EpisodicMemory bean 可注入到 SkillRetrievalHook

#### 预期收益

- LLM 在调用技能时获得历史上下文,减少同类错误重复发生
- 技能召回 + 记忆增强形成正向循环

---

### 4.10 优化点 8:ResponseCache 与 EpisodicMemory 联动

#### 问题

当前缓存命中后直接返回缓存结果,完全不经过 episodic memory 的 `record()` 路径。结果是缓存 HIT 的交互不会留下任何记忆痕迹,导致 episodic memory 中只有 cache MISS 的对话记录,长此以往记忆越来越偏。

#### 方案

`ResponseCacheHook` 在 cache HIT 路径上,除了 `bumpAndMaybeSynthesize()`,额外调用 `EpisodicLongTermMemoryAdapter.record()` 写入缓存命中的 Q&A 对到 episodic memory。

具体做法:
1. 在 `ResponseCacheHook` 中 inject `ObjectProvider<EpisodicLongTermMemoryAdapter>`(lazy,因为 adapter 是 per-request 构建的)
2. 更好的方式:在 `HarnessA2aRunner` 的 cache-hit 处理分支中,手动调用 LTM record

#### 涉及文件

- `ResponseCacheHook.java` — 新增缓存命中时的记忆记录
- `HarnessA2aRunner.java` — 或在此处统一处理

#### 预期收益

- 缓存 HIT 和 MISS 都能丰富 episodic memory
- 长期运行的系统中记忆覆盖更全面

---

### 4.11 实施优先级

| 优先级 | 优化点 | 复杂度 | 收益 | 风险 |
|--------|--------|--------|------|------|
| P0 | 1. EpisodicMemory 向量检索 | 高 | 高 | 低(有开关兜底)|
| P0 | 3. 跨 JVM 演进 CAS | 低 | 高 | 低 |
| P1 | 2. L2 全表扫描缓存加速 | 中 | 中 | 低 |
| P1 | 4. Pending Judgement 持久化 | 中 | 高 | 低 |
| P1 | 6. 蒸馏 prompt 结构化增强 | 低 | 中 | 低 |
| P2 | 5. L1/L2 net-add 改 replacement | 中 | 中 | 中(需要验证 WorkspaceContextHook 兼容性)|
| P2 | 7. 检索结果+episodic 增强 | 低 | 中 | 低 |
| P2 | 8. 缓存与记忆联动 | 低 | 中 | 低 |

---

### 4.12 验证方案

1. **单元测试**:每个优化点对应的 Java 类增加/更新单元测试
2. **集成测试**:
   - 启动 Spring Boot 应用,创建 10+ 个技能,验证 L2 缓存加速后响应时间
   - 模拟多线程并发调用演进接口,验证跨 JVM CAS 正确性
   - 验证 EpisodicMemory 向量搜索与 FTS 的结果一致性
3. **人工冒烟**:
   - 连续发送 5 个同类问题(如不同措辞的季度质量查询),验证缓存 + 技能蒸馏 + 记忆记录完整链路
   - 发送一个明显与已有技能匹配的问题,验证 L1/L2 命中正确
   - 查看 MySQL `skill_pending_judgement` 表确认持久化正常

#### 新增数据库表(自动 DDL)

```sql
CREATE TABLE IF NOT EXISTS user_trace_summary (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id VARCHAR(128) NOT NULL,
  date_key VARCHAR(16) NOT NULL,
  fingerprint VARCHAR(255) NOT NULL,
  tool_sequence TEXT NOT NULL,
  success_count INT NOT NULL DEFAULT 0,
  failure_count INT NOT NULL DEFAULT 0,
  failure_score DECIMAL(6,1) NOT NULL DEFAULT 0.0,
  sample_query TEXT,
  status VARCHAR(16) DEFAULT 'pending',
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_user_date_fp (user_id, date_key, fingerprint),
  KEY idx_user_date (user_id, date_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS digestion_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id VARCHAR(128) NOT NULL,
  date_key VARCHAR(16) NOT NULL,
  phase1_cleaned_ledger INT DEFAULT 0,
  phase2_mined_traces INT DEFAULT 0,
  phase3_skills_evolved INT DEFAULT 0,
  phase4_memory_digested TINYINT(1) DEFAULT 0,
  started_at TIMESTAMP NOT NULL,
  completed_at TIMESTAMP NULL,
  error_msg TEXT,
  KEY idx_user_date (user_id, date_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

#### 新增配置项

```properties
harness.a2a.memory.digestion.enabled=true
harness.a2a.memory.digestion.cron=0 9 21 * * *
harness.a2a.memory.digestion.batch-size=50
harness.a2a.memory.digestion.episodic-retention-days=30
harness.a2a.memory.digestion.ledger-retention-days=90
harness.a2a.memory.digestion.summary-max-length=200
harness.a2a.memory.digestion.episodic-table-name=QualitySupervisor_episodic_memory
```

#### 设计要点

1. **userId 缺失兜底**: `findActiveUsers()` SQL 带 `WHERE user_id IS NOT NULL AND user_id != ''`;`digestForUser()` 入口参数校验跳过空值
2. **阶段隔离**: 每个 Phase 独立 try-catch,一个阶段失败不影响后续
3. **分布式互斥**: MySQL `SELECT GET_LOCK('memory_digestion_lock', 0)`,未抢到锁的实例跳过
4. **幂等性**: `user_trace_summary` 唯一键 `(user_id, date_key, fingerprint)` → `ON DUPLICATE KEY UPDATE`
5. **失败信号**: 6 类分类(error/Exception/空结果/超时/未完成/跨 session),`failure_score DECIMAL(6,1)` 支持 0.5 权重
6. **Harness 兼容**: Phase 4 更新 `.consolidation_state` watermark;不修改 `MemoryMaintenanceHook` 的 retention;SQL 过滤 `session_id NOT LIKE 'cache-hit:%'` 排除伪 session

---

### 4.13 实施状态 (2026-06-30)

**全部 8 个优化点已实现并端到端验证通过。**
**夜间记忆咀嚼(Memory Digestion)4 阶段管道已完成并 E2E 验证。**

| # | 优化点 | 状态 |
|---|--------|------|
| 1 | EpisodicMemory 从 MySQL FTS 升级为向量检索 | ✅ `vectorSearch()`/`embedContent()`/`cosine()` 就绪,`search()` 先向量后 FTS,Config 开关控制 |
| 2 | SkillRetrievalHook L2 全表扫描 → 缓存加速 | ✅ `CachedSkill` 内存缓存 + `@Scheduled` 60s 刷新 + 写穿透 + SQL fallback |
| 3 | 跨 JVM 演进 CAS 从内存级升级为 MySQL 级 | ✅ DDL `evolving` 列 + `UPDATE ... WHERE evolving=FALSE` + 本地+DB 双重检测 |
| 4 | Pending Judgement 缓存从 JVM 内存升级为 MySQL 持久化 | ✅ `skill_pending_judgement` 表 + L1 LinkedHashMap + L2 DB 读写 |
| 5 | 元技能全量 + 业务技能选择性注入(分层注入) | ✅ `skills-builtin/`(元技能) + `skills-auto/`(业务技能),全量 Runner/Hook/Tool 适配 |
| 6 | Skill Distiller 蒸馏 prompt 增加结构化输出约束 | ✅ 重试机制 + `parseLenient()` 宽松解析 + name/description fallback |
| 7 | Skill 检索结果中加入 episodic 上下文增强 | ✅ L2 命中后 `queryEpisodicContext()` → `## 最近参考案例` 注入 |
| 8 | ResponseCache 与 EpisodicMemory 联动 | ✅ CacheHitException → `recordCacheHitToEpisodic()` → 写入 `cache-hit:` 前缀 session |

### 4.14 夜间记忆咀嚼(Memory Digestion 4 阶段管道)

**新增文件**(`com.agentscopea2a.agent.memory.digestion`):

| 文件 | 职责 |
|------|------|
| `MemoryDigestionService.java` | `@Scheduled(cron="0 9 21 * * *")` 21:09 执行,MySQL `GET_LOCK` 跨 JVM 互斥,按 userId 独立 try-catch |
| `TraceMiner.java` | **L1+L2 双数据源**:从 `episodic_memory` 提取 L1 工具调用 + 读取子智能体 `memory_messages.jsonl` 解析 L2 工具链,合并 fingerprint 去重聚合到 `user_trace_summary` |
| `SkillFlowEvolver.java` | Phase 3:对失败率 > 30%(且总调用 ≥ 3)的工具链触发 skill evolution/distillation |
| `MemoryFlowConsolidator.java` | Phase 4:成功流程 LLM 归并到用户 MEMORY.md,更新 `.consolidation_state` watermark |

**4 阶段管道**:

| Phase | 名称 | 操作 |
|-------|------|------|
| 1 | `CleanLedger` | 清理 `agent_memory_ledger` 超过 `ledger-retention-days`(默认 90 天)的行 |
| 2 | `MineTraces` | 从 `episodic_memory` 加载 L1 工具调用 → 检测 `agent_spawn` 提取 `agent_id` + `session_id` → 读子智能体 `memory_messages.jsonl` → Jackson 解析 `tool_use`/`tool_result` 的 `name` 字段 → 合并 L1+L2 fingerprint → 失败分类 → 去重 upsert |
| 3 | `EvolveSkills` | 读 `user_trace_summary` 的 pending 记录,`failRate = failure / total > 0.3` 且 `total >= 3` → 已有匹配 skill 则 `evolve()`,否则 `distill()` |
| 4 | `ConsolidateMemory` | 读成功 trace → LLM 合并到 MEMORY.md → `MysqlMemoryStore.upsert()` → `MemoryHydrator.hydrate()` |

**L1 检测模式**(TraceMiner 从 episodic_memory TEXT 内容中提取):

| 模式 | 用途 |
|------|------|
| `AGENT_SPAWN_HEADER` | 匹配 `[TOOL: agent_spawn]` |
| `AGENT_SPAWN_FIELD` | 提取 `agent_id: query_data` |
| `SUB_SESSION_FIELD` | 提取 `session_id: sub-xxx` |
| `TOOL_ROUTER_CALL` | 匹配 `tool_router(toolId=xxx)` |
| `SUBAGENT_DISPATCH` | 匹配 `派单给 **query_data**` |

**L2 文件读取**(TraceMiner Jackson 解析):

```java
Path l2File = workspaceRoot.resolve("agents")
    .resolve(agentId)      // e.g. "query_data"
    .resolve("context")
    .resolve(subSessionId) // e.g. "sub-4474de0e-..." (已是 "sub-" 前缀,不重复拼接)
    .resolve("memory_messages.jsonl");
```

逐行解析 `tool_use`/`tool_result` block 的 `name` 字段,合并后形成完整 fingerprint 如 `query_data|agent_spawn|load_skill_through_path|toolMetaInfo|router_tool`。

**E2E 验证结果**(2026-06-30 手动触发 `POST /debug/digest`):

```
MemoryDigestion: processing 15 active user(s)
MemoryDigestion: [t2] Phase 2 — mined 22 trace(s) across 2 session(s), L2: extracted 6 tool(s)
MemoryDigestion: [t2] completed in 584ms
```

- 15 个活跃用户全部成功处理
- L2 工具链成功提取:`load_skill_through_path|toolMetaInfo|router_tool`
- 完整 fingerprint:`query_data|agent_spawn|load_skill_through_path|toolMetaInfo|router_tool`
- Phase 1-4 独立 try-catch(单用户失败不影响其他用户)

**设计要点**:
1. **L1+L2 双数据源**:episodic_memory(MySQL)仅存 L1 主管调用,子智能体内 `toolMetaInfo`/`router_tool`/`python_exec` 等 L2 工具链仅存在于子智能体 `memory_messages.jsonl`(本地文件)。TraceMiner 通过 `agent_spawn` 消息提取 `agent_id+session_id` 映射到文件路径。
2. **子智能体文件兜底**:文件不存在/读取失败时降级为 L1-only fingerprint,不阻塞管道。
3. **幂等性**:`user_trace_summary` 唯一键 `(user_id, date_key, fingerprint)` → `ON DUPLICATE KEY UPDATE`。
4. **分布式互斥**:MySQL `GET_LOCK('memory_digestion_lock', 0)`,未抢到锁的实例跳过。
5. **失败分类**:6 类信号(error/Exception/空结果/超时/未完成/跨 session),`failure_score` 支持 0.5 权重。
6. **数据源生命周期**:子智能体 `memory_messages.jsonl` 无独立自动清理机制,生命周期与子智能体容器绑定;TraceMiner 夜间读取时若文件已清理,自动降级。

**配置文件**(`application.properties`):

```properties
harness.a2a.memory.digestion.enabled=true
harness.a2a.memory.digestion.cron=0 9 21 * * *
harness.a2a.memory.digestion.batch-size=50
harness.a2a.memory.digestion.episodic-retention-days=30
harness.a2a.memory.digestion.ledger-retention-days=90
harness.a2a.memory.digestion.episodic-table-name=QualitySupervisor_episodic_memory
```

详见 §5 夜间记忆咀嚼。

---

## 5. 夜间记忆咀嚼

> 本章节合并自 [`nightly-memory-digestion-plan.md`](./nightly-memory-digestion-plan.md) 和 [`memory-module-design.md`](./memory-module-design.md),完整保留 L1+L2 双数据源设计与 TraceMiner 算法。

### 5.1 数据源分析:运行时哪里存了完整记忆?

现有系统在运行时,有 **3 个独立的数据源** 保存了不同粒度的会话记录:

| 维度 | episodic_memory (MySQL) | sessions JSONL (文件) | memory_messages.jsonl (文件) |
|------|------------------------|----------------------|----------------------------|
| **数据层级** | 主管 L1 工具调用 | 主管 L1 工具调用 | **子智能体 L2 完整调用** |
| **L1 agent_spawn** | ✅ `[TOOL: agent_spawn]` | ✅ JSON 格式 | ❌ (不存在子智能体文件中) |
| **L2 toolMetaInfo/router_tool** | ❌ | ❌ | ✅ 完整保留 |
| **包含子智能体最终回复** | ✅ 嵌入在 agent_spawn reply 中 | ✅ 嵌入在 agent_spawn reply 中 | ✅ 独立 ASSISTANT 消息 |
| **持久化机制** | StaticLongTermMemoryHook PostCall | MemoryFlushHook PostCall | SessionContextManager memory.addMessage |
| **位置** | MySQL | 本地文件 | 本地文件 |
| **可查询性** | ✅ SQL 直接查询 | ❌ 需文件 I/O | ❌ 需文件 I/O |

### 5.2 TraceMiner 双数据源设计

整体流程:

```
┌───────────────────────────────────────────────────────────────┐
│ TraceMiner.mineTraces(date, userId)                          │
├───────────────────────────────────────────────────────────────┤
│                                                                │
│  1. 从 episodic_memory 加载 L1 数据                            │
│     → 主管 session_id 列表(user:xxx 格式)                     │
│     → 每条消息含 role/content                                  │
│                                                                │
│  2. 对每个主管 session,解析 L1 工具调用                         │
│     → 检测 agent_spawn → 提取 subAgentId + subSessionId        │
│     → 检测 tool_router → 提取 toolId                           │
│     → 生成 L1 fingerprint                                      │
│                                                                │
│  3. 对含 agent_spawn 的 session,查找对应的子智能体文件          │
│     → 路径: agents/{subAgentId}/context/sub-{subSessionId}/    │
│     → 读取 memory_messages.jsonl                                │
│     → 解析 L2 工具调用链                                        │
│                                                                │
│  4. 合并 L1 + L2 → 完整 fingerprint                            │
│     → 格式: "agent_spawn|toolMetaInfo|router_tool"              │
│                                                                │
│  5. 聚合 upsert 到 user_trace_summary                          │
│                                                                │
└───────────────────────────────────────────────────────────────┘
```

### 5.3 失败分类

每条 trace 按以下规则分类(TraceMiner.buildSession):

| 信号 | 权重 | 检测方式 |
|------|------|----------|
| 工具输出含 error/Exception | `1.0` (FAILURE) | `ERROR_PATTERN` |
| 退出码非零 | `1.0` (FAILURE) | `exit\s*=\s*-?\d+` |
| 空结果表 | `1.0` (FAILURE) | `EMPTY_TABLE_PATTERN`(只有表头无数据行) |
| maxIters 耗尽 | `0.5` (POSSIBLE) | `MAX_ITERS_PATTERN` |
| 无 finish 标记 | `0.5` (POSSIBLE) | `FINISH_MARKER`(匹配"finish"/"完成"/"结束") |

### 5.4 完整 fingerprint 合并示例

| L1 检测到的 | L2 文件中的 | 最终 fingerprint |
|------------|------------|-----------------|
| `agent_spawn` | 无文件(已删除) | `agent_spawn` |
| `agent_spawn` | `tool_index\|toolMetaInfo\|router_tool` | `agent_spawn\|tool_index\|toolMetaInfo\|router_tool` |
| `agent_spawn` | 文件存在但读取失败 | `agent_spawn`(降级) |

### 5.5 E2E 验证结果(2026-06-30)

```
digestion_log:
(57, 'u-digest-evolution', '2026-06-30', 0, 1, 0, 0, ...)
-- phase2_mined_traces=1 表示挖掘成功

user_trace_summary:
user_id='u-digest-evolution'
  fingerprint: query_data|agent_spawn|load_skill_through_path|toolMetaInfo|router_tool  ← L1+L2 合并成功
  tool_sequence: query_data,agent_spawn,load_skill_through_path,toolMetaInfo,router_tool

日志:
L2: extracted 6 tool(s) from ...memory_messages.jsonl
TraceMiner: mined 1 fingerprint group(s) from 1 session(s) for 2026-06-30 (1 user(s))
MemoryDigestion: [u-digest-evolution] completed in 584ms
```

完整设计详见 §4.14 夜间记忆咀嚼 4 阶段管道。

---

## 6. 关键修复与增强

> 本章节合并自三个修复方案文档,完整保留原始内容。

### 6.1 无维度问题自动生成 Skill 修复方案

> 来源:[`no-dimension-skill-synthesis-fix.md`](./no-dimension-skill-synthesis-fix.md)

#### 问题描述

用户用同一个问题问了三遍,期望触发自动 skill 生成,但没有生效:

```
请直接 spawn 一个 code_interpreter 子 agent,让它用 python_exec 对下面 8 个数字算最大值、最小值、均值、中位数、标准差:[20.1, 13.1, 23.1, 17.5, 19.2, 14.8, 25.3, 16.7]。
```

#### 根因分析

`DimensionStateManager.analyzeQuestionRuleBased()` 只识别时间/部门/团队/应用/产品线/需求/人员等维度 token。用户的问题是**纯算法/代码执行类问题**,不包含任何上述维度 token,导致 `dimKey` 为空,`SkillSynthesisHook` 直接 return,根本不会去 bump `skill_candidate.hit_count`。

#### 方案 2:Fallback 到 Intent 单独做 Fingerprint

当 `dimKey` 为空时,不直接 return,而是用 `intent` 单独构造 fingerprint:

```
有维度:userId|intent|time=VERSION:2026年4月份版本|dept=杭州开发一部
无维度:userId|query|<no-dim>
```

#### 改动点

**SkillSynthesisHook.java (MISS 路径)**:

```java
// 改动前
if (state == null || !state.hasDimensions()) return Mono.just(event);
String dimKey = state.toCacheKey();
if (dimKey.isEmpty()) return Mono.just(event);

// 改动后
String dimKey = state == null ? "" : state.toCacheKey();
if (dimKey.isEmpty() && (intent == null || intent.isEmpty())) return Mono.just(event);
String fingerprint = dimKey.isEmpty()
        ? userId + "|" + intent + "|<no-dim>"
        : userId + "|" + intent + "|" + dimKey;
```

**ResponseCacheHook.java (HIT 路径)**:

```java
// 改动前
if (dimKey.isEmpty()) return;

// 改动后
String fingerprint = dimKey.isEmpty()
        ? tenant + "|" + intent + "|<cache-hits>"
        : tenant + "|" + intent + "|" + dimKey;
```

#### Fingerprint 格式约定

| 场景 | fingerprint 示例 |
|-----|-----------------|
| 有维度问题 | `u:alice\|query\|time=VERSION:2026年4月份版本\|dept=杭州开发一部` |
| 无维度问题 (MISS 路径) | `u:alice\|query\|<no-dim>` |
| 无维度问题 (HIT 路径) | `u:alice\|query\|<cache-hits>` |

---

### 6.2 分层注入完整修复方案

> 来源:[`layered-injection-fix-plan.md`](./layered-injection-fix-plan.md)
> **状态:已修复** (2026-07-02)

#### 问题描述

当前分层注入(元技能全量 + 业务技能选择性注入)的实现在以下两个场景存在 bug:

**Bug 1:子 agent 读不到元技能**

`WorkspaceMaterializer.java` 已将 classpath `workspace/skills/`(元技能 `tool_index`、`data_primitives`)重映射到磁盘目录 `skills-builtin/`。但 HarnessAgent JAR 内部的 `FileSystemSkillRepository` 写死的扫描路径是 `<workspace>/skills/`:

```
子 agent (HarnessAgent JAR 内部)
  └─ FileSystemSkillRepository.resolveSkillBox()
       └─ 写死扫描 workspace/skills/        ← 现在是空目录,读不到元技能
          ↑ 而元技能实际在 workspace/skills-builtin/
```

**Bug 2:sandbox 只绑定了 skills/,缺 skills-builtin/**

`FilesystemConfig.buildSandboxSpec()` 只做了 `workspace/skills/` 的绑定挂载。而 `workspace/skills/` 目录当前是空的(元技能在 `skills-builtin/`,业务技能在 `skills-auto/`),所以 sandbox 容器内 `/workspace/skills/` 空目录。

#### 最终方案:本地用 symlink + sandbox 双挂载

| 措施 | 文件 | 改动 |
|------|------|------|
| **① 启动时创建 `skills/` → `skills-builtin/` 的 symlink** | `WorkspaceMaterializer.java` | `ensureMaterialized()` 结尾判断:若 `skills/` 不存在或为空,创建 symlink 指向 `skills-builtin/` |
| **② sandbox 同时挂载 `skills-builtin/` 和 `skills-auto/`** | `FilesystemConfig.java` | `buildSandboxSpec()` 中,`mount-skills=true` 时额外挂载 `skills-builtin` 和 `skills-auto` 到容器 |
| **③ 远程同步增加 `skills-builtin/` 和 `skills-auto/`** | `RemoteWorkspaceSyncService.java` + properties | SSH 同步两个目录;properties 增加对应配置 |
| **④ 子 agent 元技能路径挂载** | `SupervisorService.java` / `FilesystemConfig.java` | 子 agent 继承 sandbox spec,sandbox 内 `/workspace/skills-builtin/` 存在则 FileSystemSkillRepository 也能读到 |

#### 验证清单

- [x] 本地启动后:`ls -la .agentscope/workspace/harness-a2a/skills` 显示为 symlink,指向 `skills-builtin`
- [x] 子 agent 日志:FileSystemSkillRepository 加载了 `tool_index` 和 `data_primitives`
- [x] sandbox 容器内:`docker exec <container> ls /workspace/skills-builtin/` 显示两个 SKILL.md
- [x] 业务技能仍选择性注入:发送新问题,`SkillRetrievalHook` 只命中 `skills-auto/` 中的技能
- [x] 类 `generate_skill` 保存技能:`SkillSaveTool` 写入 `skills-auto/`,不污染 `skills/`

---

### 6.3 工具调用链路上下文增强方案

> 来源:[`trace-context-enhancement-plan.md`](./trace-context-enhancement-plan.md)

#### Context

现有夜间消化管道中,TraceMiner 挖掘工具调用链路时存在一个关键缺陷:

**当前问题**:

1. **TraceMiner 只保存了工具名序列**(`tool_sequence`),丢失了每个工具调用的 input 参数、output 结果、以及用户问题的完整内容。`sample_query` 字段只是最后一条 assistant 回复的前 200 字符,不是有效的上下文。
2. **SkillFlowEvolver.dispatchDistill() 只传了 `sampleQuery` 和 `fingerprint`**,蒸馏 prompt 里完全没有工具调用链路的 input/output 细节——LLM 不知道"这个 skill 到底调用了哪些工具、传了什么参数、返回了什么结果"。
3. **SkillFlowEvolver.dispatchEvolve() 传的 `failedTraceSnippet` 实际上是 `sampleQuery`**(最后的 assistant 回复片段),而不是真正的失败 trace(如 python_exec 的 stderr 或 router_tool 的错误输出)。

#### 数据模型增强

**`user_trace_summary` 表新增字段**:

```sql
ALTER TABLE user_trace_summary
  ADD COLUMN user_query        TEXT      COMMENT '完整用户原始问题(非截断)' AFTER sample_query,
  ADD COLUMN tool_call_details LONGTEXT  COMMENT 'JSON 数组:含 tool/level/input/output 的完整工具调用链路详情' AFTER user_query;
```

**Output 截断策略**:

- **output 严重截断**:统一截取前 **150 字符**,主要保留以下信号:
  - 返回空结果(无数据行)
  - 返回错误/异常
  - 返回的结构信息("返回 3 行,包含缺陷密度、缺陷数")
- **input 完整保留**:工具的入参是 skill 内容的核心——LLM 需要知道传什么参数、参数格式是什么,最大保留 **500 字符**
- **agent_spawn 的 output 标记简化为**:`"status=ok"` / `"status=error: ..."` / `"status=timeout"`

**`tool_call_details` JSON 结构**:

```json
[
  {
    "tool": "agent_spawn",
    "level": "L1",
    "input": "派单给 query_data,查询2026年1季度质量数据",
    "output": "status=ok"
  },
  {
    "tool": "toolMetaInfo",
    "level": "L2",
    "input": "toolId=quality_query_by_department_quarter",
    "output": "参数: quarter(string), department(string)"
  },
  {
    "tool": "router_tool",
    "level": "L2",
    "input": "quarter=2026年1季度, department=杭州开发一部",
    "output": "返回3行质量数据(缺陷密度=0.32, 缺陷数=12)..."
  }
]
```

完整 TraceMiner 改造、SkillFlowEvolver 改造、SkillDistiller 改造详见 [`trace-context-enhancement-plan.md`](./trace-context-enhancement-plan.md)。

---

### 6.4 回归测试发现报告

> 来源:[`regression-findings.md`](./regression-findings.md)
> **测试日期**:2026-06-27

#### 测试结果总览

| # | 能力 | 结果 | 说明 |
|---|---|---|---|
| 1 | 简单查询(单部门单季度) | ✅ 通过 | 见 walkthrough §2.1 |
| 2 | 响应缓存 HIT / MISS | ✅ 通过 | 见 walkthrough §2.2 |
| 3 | 数据分析(compare ≤3 数字) | ✅ 通过 | 见 walkthrough §2.3 |
| 4 | 多租户隔离(session/user 维度) | ✅ 通过(user_id 透传见 §3.D) | 见 walkthrough §2.4 |
| 5 | 保存技能(显式 save_skill) | ✅ 通过 | 见 walkthrough §2.5 |
| 6 | Skill retrieval(L2 向量检索注入) | ✅ 通过 | 见 walkthrough §2.6 |
| 7 | Skill auto-synthesis(同指纹 ≥3) | ✅ 通过 | 见 walkthrough §2.7 |
| **8** | **多轮维度继承 + 指代消解** | **✅ 通过** | **本次新增验证,见 §6.4.2** |
| **9** | **Artifact handoff 真触发(≥4 行)** | **❌ 失败** | **见 §6.4.3.A** |
| **10** | **code_interpreter / python_exec 真跑** | **❌ 失败** | **见 §6.4.3.B** |
| 11 | EXPLICIT_DEPT 维度 vs QualityTools 数据一致性 | ⚠️ 部分失败 | 见 §6.4.3.C |
| 12 | runtimeContext user_id 透传 | ⚠️ 已知问题 | 见 §6.4.3.D,已有方案 [user-id-cache-scope-plan.md](user-id-cache-scope-plan.md) |
| 13 | actuator metrics 端点 | ⚠️ 配置缺失 | 见 §6.4.3.E |

#### 本次新增的成功验证

**多轮维度继承 + 指代消解(功能 #8)**

用例:同 `session_id=gap-C-multiturn`,连续两轮:

| Turn | 输入 |
|---|---|
| 1 | `查询2026年1季度杭州开发一部的质量数据` |
| 2 | `那杭州开发二部呢` |

验证点:Turn 2 不再显式提季度,要 `DimensionStateManager` 从前一轮继承 `quarter=2026年1季度`。

实测日志(关键路径):

```
# Turn 1 fp
SkillRetrievalHook injected ... fp=s:gap-C-multiturn|query|time=QUARTER:2026年1季度|dept=杭州开发一部
router_tool called: paramsJson={..., "quarter":"2026年1季度", "department":"杭州开发一部"}

# Turn 2 fp(季度从 turn1 继承)
router_tool called: paramsJson={..., "quarter":"2026年1季度", "department":"杭州开发二部"}
```

Turn 2 响应包含 "13.1"(二部 Q1 基线值),证据链完整。✅

#### 真问题清单(按严重程度倒序)

**A. Artifact handoff 在自然语言下无法触发 — 严重**

期望:用户问"查询 2026年1季度 所有部门 的质量数据"时,`query_quality_by_department_quarter` 工具 `department=null` 一次返回 5 行 → ≥ MIN_DATA_ROWS=4 → 触发 `ArtifactHandoffHook` 写远端 artifact。

实测:
- LLM 受**已检索注入**的 `quarterly_defect_density_by_dept` / `quarterly_dept_defect_density_query` 等 SKILL.md 引导,把"所有部门"拆成 5~8 次单部门查询,每次只返 1 行。
- 单次 router_tool 调用:`paramsJson={"toolId":"quality_query_by_department_quarter", "quarter":"2026年1季度","department":"杭州开发一部"}`
- ——`department` 字段始终被填值,**从未尝试 `null`**。
- 18 次 router_tool 调用(gap-A + gap-B + gap-C),**全部 1 行返回**,
`ArtifactHandoffHook` 一次都没触发。

建议修复:
1. **优先**:把 retrieval 注入的 SKILL.md 加一条"用户问'所有部门'时,调用 `query_quality_by_department_quarter` 并传 `department=null` 一次拿全"
2. supervisor 系统 prompt 增加一条 hard rule:"当用户问 N 个实体的同类指标时,优先用工具 `<entity>=null` 的批量参数,禁止循环。"

**B. code_interpreter 在 ≥4 数字场景下完全不被调度 — 严重**

期望([AGENTS.md](../workspace/AGENTS.md) 硬规则):用户要求对 ≥4 个数字做统计 → supervisor 派 `code_interpreter` subagent → 调 `python_exec` → 返回结果。

测试用例 `req_gapB_codeinterp.json`:
```json
{"input":"查询2026年1季度全部8个部门的缺陷密度,然后对这8个数字算统计指标:最大值、最小值、均值、中位数、标准差、上下四分位","session_id":"gap-B-1","user_id":"u-gap"}
```

实测:
- 8 次(实际为 8 因 EXPLICIT_DEPT 含 8 项,QualityTools 只 mock 了 5)单部门 `quality_query_by_department_quarter` 调用——和 §A 同样的拆解病
- **`python_exec` / `data_primitives` / `code_interpreter` subagent 任何一次都没被调用**
- curl 跑满 400s 超时,最后一条 SSE 仅 `event:summary` `lineResult: "#"`

建议修复:
1. **优先 0**:在 supervisor 系统 prompt 里硬编码"当上下文中出现 ≥4 个数据点时,**必须**调用 `code_interpreter` 而不是自己心算"——别只放在 AGENTS.md,要直接注入。
2. **优先 1**:考虑把"数字计数" 作为 hook 拦截:`PostCallEvent` 解析 supervisor 最近输出,发现包含 ≥4 个数字且 LLM 没主动 spawn → 拒绝回答,反向提示 LLM "你必须先调 code_interpreter"。

完整回归测试报告详见 [`regression-findings.md`](./regression-findings.md)。

---

## 7. 测试场景

> 本章节合并自 [`new-test-scenarios.md`](./new-test-scenarios.md),完整保留 Skill 自进化触发说明和三组测试场景。

### 7.1 Skill 自进化触发说明

skill 的"保存"和"演化"是两条完全独立的链路,本测试场景对两者都做覆盖:

| 路径 | 触发机制 | 关键配置 | 覆盖场景 |
|---|---|---|---|
| **PR2 自动蒸馏** (`SkillSynthesisHook` → `SkillSynthesisRunner.bumpAndMaybeSynthesize`) | 同一 fingerprint(`userId + intent + dimKey`)命中次数 ≥ `auto-synth.threshold`(默认 3) | `harness.skills.auto-synth.enabled=true` | A / B(同一 user_id + 同维度问题跑 ≥3 次) |
| **手动 save_skill** (`SkillSaveTool`) | supervisor / `generate_skill` subagent 显式调用 `save_skill` 工具 | 工具一直可用,无开关 | C(请求里显式要求"创建一个名为 dept_quarterly_query 的 skill") |
| **PR3 检索增强** (`SkillRetrievalHook`) | L1 fingerprint + L2 vector top-K 命中后把 SKILL.md 注入 system prompt | `harness.skills.retrieval.enabled=true` | 跨场景——下一次同类问题会自动召回 |
| **PR4 失败演化** (`SkillEvolutionHook`) | 被召回的 skill 失败率超 `fail-rate-evolve`(默认 0.3)且总次数 ≥ `min-uses-evolve`(默认 5) | `harness.skills.evolution.enabled=true` | 跑偏后再跑(需 ≥5 次累计) |

**触发 PR2 自动蒸馏的关键约束**:

1. **同一 fingerprint 必须命中 ≥3 次**——`fingerprint = userId + "|" + intent + "|" + dimKey`
   - `userId`:请求里的 `user_id` 字段
   - `intent`:`query` / `analyze` / `compare` / `aggregate` 等(来自 `ResponseCacheService.classifyIntent`)
   - `dimKey`:用户问题里显式提到的维度组合(时间 + 部门 + 同期 + 人员)
2. **cache 命中不会触发计数**——`ResponseCacheHook` 在 HIT 路径抛 `CacheHitException`,`SkillSynthesisHook.handlePreCall` 不再执行
3. **场景 A、B 跑同一个 user_id + 同维度问题至少 3 次**才能触发自动蒸馏
4. **场景 C 是显式调用 `save_skill` 工具**,单次即可保存,与 PR2 阈值无关

**测试时建议的请求次数**:

| 场景 | 跑几次 | 为什么 |
|---|---|---|
| A | ≥3 次 | 触发 PR2 自动蒸馏,落 `data_primitives_analysis` 类 skill |
| B | ≥3 次 | 触发 PR2 自动蒸馏,落 `python_exec_chart` 类 skill |
| C | 1 次 | 显式 `save_skill`,单次落盘 |

跑完 A/B 后可去 MySQL 查 `skill_candidate` 表确认 `hit_count` 累计;跑完 C 后去 `skills/<name>/SKILL.md` 确认文件落盘。

---

### 7.2 场景 A:data_primitives 分析(不调用 python_exec)

**文件**: `tmp_test/req_analysis_data_primitives.json`

**目的**:验证 supervisor 能走 `data_primitives` 工具链(sort / aggregate / compare)完成统计,不触发 `python_exec`。

**请求**:
```json
{"input":"查询2026年1季度全部5个开发部的缺陷密度,然后做以下分析:1) 按缺陷密度从高到低排序 2) 计算5个部门的均值和中位数 3) 找出高于均值的部门。把以上结果用表格呈现,不要调用python_exec,直接用data_primitives工具完成分析。","session_id":"analysis-v1","user_id":"u-analysis"}
```

**应看到**:

| 步骤 | 日志关键字 |
|---|---|
| query_data 查询 5 部门 | `router_tool called: toolId=quality_query_by_department_quarter` |
| data_primitives.sort | `sort desc on column=质量分(缺陷密度)` |
| data_primitives.aggregate | `aggregate: avg+median on 缺陷密度` |
| data_primitives.filter | `filter: 缺陷密度 > avg` |

响应应包含:

| 部门 | 缺陷密度 |
|---|---|
| 杭州开发五部 | 26.1 |
| 杭州开发一部 | 23.1 |
| 杭州开发二部 | 13.1 |
| 杭州开发四部 | 6.1 |
| 杭州开发三部 | 3.1 |

均值=14.30, 中位数=13.1
高于均值的部门:杭州开发五部(26.1), 杭州开发一部(23.1)

**不出现** `python_exec` 日志。

---

### 7.3 场景 B:python_exec 沙箱数据分析 + artifact 图表

**文件**: `tmp_test/req_sandbox_python_analysis.json`

**目的**:验证 sandbox 内 `python_exec` 能读取 artifact CSV、做 pandas 统计、生成 matplotlib 图表并保存 artifact。

**请求**:
```json
{"input":"查询2026年1季度全部部门的质量数据,然后用python_exec对缺陷密度进行统计:计算所有部门的均值、中位数、标准差、最大值、最小值,并生成一张横向柱状图(部门名称为Y轴,缺陷密度为X轴),把图表保存为artifact文件,最后返回统计表格和图表的访问路径。","session_id":"sandbox-py-v1","user_id":"u-sandbox"}
```

**应看到**:

| 步骤 | 日志关键字 |
|---|---|
| query_data 查询 5 部门 | `router_tool called: toolId=quality_query_by_department_quarter` |
| artifact handoff | `ArtifactHandoffHook: writing artifact rows=5 threshold=4` |
| python_exec 读取 CSV | `python_exec: read_file(artifacts/*/result.json)` |
| python_exec 统计计算 | `mean, median, std, max, min` |
| python_exec 生成图表 | `python_exec: savefig defects_density_barh.png` |
| python_exec 写 artifact | `write_file(artifacts/*/chart.json)` |
| 最终响应含图表路径 | `图表的访问路径` |

响应应包含:
- 统计结果表格(均值、中位数、标准差、最大值、最小值)
- 图表文件路径说明(如 `.agentscope/workspace/harness-a2a/artifacts/u-sandbox/.../chart.png`)
- 图表应可确认:5 个柱条,Y 轴为部门名,杭州开发五部最长

---

### 7.4 场景 C:查询 → 保存 skill(显式 save_skill)

**文件**: `tmp_test/req_save_skill_workflow.json`

**目的**:验证用户在一次完整查询后,主动通过 `save_skill` 工具把调用链固化为 SKILL.md。

**请求**:
```json
{"input":"请先查询2026年1季度杭州开发一部的质量数据,然后基于这次查询流程,主动创建一个名为 dept_quarterly_query 的skill保存到工作区,SKILL.md内容需要包含:用途说明、输入参数(部门、季度)、调用工具链(tool_index→toolMetaInfo→router_tool)、输出格式、典型问法。","session_id":"skill-save-v1","user_id":"u-skill"}
```

**应看到**:

| 步骤 | 日志关键字 |
|---|---|
| supervisor 解析意图 | `intent=query` |
| 子 agent 查询数据 | `router_tool called: toolId=quality_query_by_department_quarter` |
| generate_skill 子 agent | `spawn subagent type=generate_skill` |
| save_skill 调用 | `Skill saved: dept_quarterly_query v1` |
| SKILL.md 落盘 | `Successfully saved skill: dept_quarterly_query` |

**验证文件落盘**:
```bash
ls .agentscope/workspace/harness-a2a/skills/dept_quarterly_query/SKILL.md
ssh docker-host 'cat /opt/agentscope-workspace/harness-a2a/skills/dept_quarterly_query/SKILL.md'
```

文件 frontmatter 应包含:
```yaml
---
name: dept_quarterly_query
description: "按部门和季度查询质量数据"
version: 1
last_evolved_at: 2026-07-02
---
```

正文中应包含 `load_skill_through_path → toolMetaInfo → router_tool` 的真实工具链描述(而非 `query_quality_data`/`python_exec` 这种泛化名)。

---

### 7.5 发送命令模版

```bash
# 场景 A
curl -sS -N -X POST http://localhost:8081/ai/chat \
     -H "Content-Type: application/json; charset=utf-8" \
     --data-binary @tmp_test/req_analysis_data_primitives.json \
     --max-time 240 > tmp_test/resp_analysis_data_primitives.txt

# 场景 B
curl -sS -N -X POST http://localhost:8081/ai/chat \
     -H "Content-Type: application/json; charset=utf-8" \
     --data-binary @tmp_test/req_sandbox_python_analysis.json \
     --max-time 300 > tmp_test/resp_sandbox_python_analysis.txt

# 场景 C
curl -sS -N -X POST http://localhost:8081/ai/chat \
     -H "Content-Type: application/json; charset=utf-8" \
     --data-binary @tmp_test/req_save_skill_workflow.json \
     --max-time 240 > tmp_test/resp_save_skill_workflow.txt
```

---

## 8. 变更文件总表

> 本章节汇总所有 PR / 优化 / 修复涉及的文件变更,Excel 格式展示。

### 8.1 新增文件清单

| 序号 | 类型 | 文件 | 包 | 说明 |
|:---:|:---:|---|----|------|
| 1 | 新增 | `MemoryDigestionService.java` | `com.agentscopea2a.agent.memory.digestion` | 夜间 cron 编排,4 阶段(CleanLedger → MineTraces → EvolveSkills → ConsolidateMemory),MySQL `GET_LOCK` 分布式互斥,按 userId 隔离 |
| 2 | 新增 | `TraceMiner.java` | `com.agentscopea2a.agent.memory.digestion` | 离线工具链挖掘:读取 `episodic_memory` 原始消息 + 子智能体 `memory_messages.jsonl`,提取 L1+L2 完整工具调用链,失败信号分类,去重聚合到 `user_trace_summary` |
| 3 | 新增 | `SkillFlowEvolver.java` | `com.agentscopea2a.agent.memory.digestion` | Phase 3 阈值判定:`failRate > 0.3` + `total >= 3` → 已有 skill 调用 `SkillDistiller.evolve()`,无 skill 调用 `SkillDistiller.distill()` |
| 4 | 新增 | `MemoryFlowConsolidator.java` | `com.agentscopea2a.agent.memory.digestion` | Phase 4 归并:读取当前 MEMORY.md → LLM 合并成功流程 → `MysqlMemoryStore.upsert()` → `MemoryHydrator.hydrate()` → 更新 `.consolidation_state` watermark |
| 5 | 新增 | `SkillCandidateRepository.java` | `com.agentscopea2a.harness.skills` | `@Repository` + 懒建表 DDL + `incrementHit / findByFingerprint / markSynthesized / markRejected` |
| 6 | 新增 | `SkillDistiller.java` | `com.agentscopea2a.harness.skills` | `@Component`,把 exemplar question + 可选 EpisodicMemory snippets 喂 LLM,解析出 `name / description / body / sample_questions` |
| 7 | 新增 | `SkillSynthesisRunner.java` | `com.agentscopea2a.harness.skills` | `@Component`,集中蒸馏逻辑,两条路径共享 |
| 8 | 新增 | `SkillEvolutionRunner.java` | `com.agentscopea2a.harness.skills` | `@Component`,per-skill CAS + 演进落盘 |
| 9 | 新增 | `EmbeddingClient.java` | `com.agentscopea2a.harness.skills` | 单方法接口 `float[] embed(String)` |
| 10 | 新增 | `OpenAiCompatEmbeddingClient.java` | `com.agentscopea2a.harness.skills` | `@Component @ConditionalOnProperty`,HTTP 调用 embedding endpoint |
| 11 | 新增 | `SkillVectorIndex.java` | `com.agentscopea2a.harness.skills` | `@Repository` 操作 `skill_index` 表;`findByFingerprint`(L1) / `topK`(L2) / `upsertVector` |
| 12 | 新增 | `SkillSynthesisHook.java` | `com.agentscopea2a.harness.hooks` | `implements Hook`:PreCall 复用 `DimensionStateManager.analyzeQuestionRuleBased()` 提指纹,PostCall 异步走 `trySynthesize` |
| 13 | 新增 | `SkillRetrievalHook.java` | `com.agentscopea2a.harness.hooks` | `priority()=-50`,PreCall 单事件;L1/L2 检索注入 |
| 14 | 新增 | `SkillEvolutionHook.java` | `com.agentscopea2a.harness.hooks` | `priority()=+60`,PreCall + PostCall 都监听;跨轮负反馈 + 失败计数 |
| 15 | 新增 | `SkillEntry.java` | `com.agentscopea2a.harness.skills` | `skill_index` 表只读快照 record |
| 16 | 新增 | `SkillCandidate.java` | `com.agentscopea2a.harness.skills` | `skill_candidate` 表只读快照 record |
| 17 | 新增 | `SkillIndexRepository.java` | `com.agentscopea2a.harness.skills` | `@Repository` + 懒建表 DDL + `findByName/upsertOnSave/recordUsage` |
| 18 | 新增 | `ToolCallCollector.java` | `com.agentscopea2a.harness.hooks` | 运行时工具调用上下文收集器(见 §6.3) |
| 19 | 新增 | `ToolCallTrackingHook.java` | `com.agentscopea2a.harness.hooks` | Hook 收集 L2 工具 input/output |

### 8.2 修改文件清单

| 序号 | 类型 | 文件 | 包 | 修改内容 |
|:---:|:---:|---|----|----------|
| 1 | 修改 | `MySqlEpisodicMemory.java` | `com.agentscopea2a.agent.memory` | `createTableIfNotExists()` 新增 `status VARCHAR(16) DEFAULT 'active'` 列 + `idx_status` 索引;`recordSession()` 新增 TOOL 角色消息支持,`extractToolResultText()` 提取 ToolResultBlock 文本;增加 `vectorSearch()` / `embedContent()` / `cosine()` 方法 |
| 2 | 修改 | `MysqlMemoryStore.java` | `com.agentscopea2a.agent.memory` | 新增 `deleteLedgerBefore()`、`findActiveUsers()` 等方法 |
| 3 | 修改 | `DimensionStateManager.java` | `com.agentscopea2a.agent.dimension` | 5 处正则替换 + 1 段新增季度别名归一化逻辑(PR3.5);无维度 fallback(PR2.1) |
| 4 | 修改 | `application.properties` | `src/main/resources` | 新增 `harness.skills.*` + `harness.a2a.memory.digestion.*` 配置段 |
| 5 | 修改 | `SkillSaveTool.java` | `com.agentscopea2a.harness.tools` | 构造函数新增 repo 参数;每次保存 upsert version,自动渲染 YAML frontmatter;新增 4 参构造 |
| 6 | 修改 | `SupervisorService.java` | `com.agentscopea2a.service` | 注入 `SkillIndexRepository + SkillCandidateRepository + SkillDistiller + SkillSynthesisRunner + SkillEvolutionRunner + SkillVectorIndex + EmbeddingClient`;`build()` 挂载三个新 Hook |
| 7 | 修改 | `ResponseCacheHook.java` | `com.agentscopea2a.harness.hooks` | 新增 6 元构造接 `SkillSynthesisRunner`(可空);HIT 分支前调 `runner.bumpAndMaybeSynthesize(...)` |
| 8 | 修改 | `WorkspaceMaterializer.java` | `com.agentscopea2a.harness.workspace` | classpath skills 复制目标改为 `skills-builtin/`;启动时创建 `skills/` → `skills-builtin/` symlink |
| 9 | 修改 | `FilesystemConfig.java` | `com.agentscopea2a.harness.config` | `buildSandboxSpec()` 中,`mount-skills=true` 时额外挂载 `skills-builtin` 和 `skills-auto` 到容器 |
| 10 | 修改 | `RemoteWorkspaceSyncService.java` | `com.agentscopea2a.harness.sync` | SSH 同步 `skills-builtin` 和 `skills-auto` 两个目录 |
| 11 | 修改 | `HarnessA2aRunner.java` | `com.agentscopea2a.runner` | `CacheHitException` 处理分支调用 `recordCacheHitToEpisodic()` |

---

## 9. 配置项总表

> 本章节汇总所有新增配置项,按功能模块分组。

### 9.1 沉淀相关(auto-synth.*)

| 键 | 默认 | 说明 |
|---|---|---|
| `harness.skills.auto-synth.enabled` | `false` | 总开关。Dev 默认关,demo 验证通过后改 `true` |
| `harness.skills.auto-synth.threshold` | `3` | 同指纹累计多少次触发蒸馏。生产建议 5 |
| `harness.skills.auto-synth.similarity-block` | `0.85` | 蒸馏前 L1 检索相似 skill 阻断阈值 |
| `harness.skills.auto-synth.self-check-threshold` | `0.7` | 蒸馏后自检打分阈值,< 0.7 直接 rejected |

### 9.2 召回相关(retrieval.* / embedding.*)

| 键 | 默认 | 说明 |
|---|---|---|
| `harness.skills.retrieval.enabled` | `false` | 总开关。打开后追加 top-K skill 块到 system prompt 末尾 |
| `harness.skills.retrieval.top-k` | `3` | 最多取多少条 skill |
| `harness.skills.retrieval.min-cosine` | `0.72` | L2 cosine 阈值;低于直接丢弃 |
| `harness.skills.retrieval.fallback-fullload` | `false` | L2 全 miss 时是否降级全量 |
| `harness.skills.retrieval.cache-refresh-seconds` | `60` | L2 缓存刷新间隔 |
| `harness.embedding.enabled` | `false` | OpenAI 兼容 embedding 客户端总开关 |
| `harness.embedding.endpoint` | — | `POST /embeddings` 完整 URL |
| `harness.embedding.api-key` | 空 | Bearer 凭证 |
| `harness.embedding.model` | `text-embedding-3-small` | 透传给 `{"model":...}` |
| `harness.embedding.dim` | `1536` | 必须严格等于模型实际维度 |

### 9.3 进化相关(evolution.*)

| 键 | 默认 | 说明 |
|---|---|---|
| `harness.skills.evolution.enabled` | `false` | 总开关。关闭时 hook 在 SupervisorService 阶段就不装配 |
| `harness.skills.evolution.fail-rate-evolve` | `0.3` | 失败率超此值且样本数足够 → 触发异步 evolve |
| `harness.skills.evolution.fail-rate-blacklist` | `0.6` | 失败率超此值且样本数足够 → 拉黑 |
| `harness.skills.evolution.min-uses-evolve` | `5` | evolve 所需最小累计 use |
| `harness.skills.evolution.min-uses-blacklist` | `10` | blacklist 所需最小累计 use |
| `harness.skills.evolution.rejection-keywords` | `不对,错了,重算,重新,不是这样,不正确` | 用户负反馈关键词,逗号分隔 |
| `harness.skills.evolution.max-evolutions-per-skill` | `3` | 同一 skill 最多 evolve 次数 |

### 9.4 夜间咀嚼相关(digestion.*)

| 键 | 默认 | 说明 |
|---|---|---|
| `harness.a2a.memory.digestion.enabled` | `true` | 夜间咀嚼总开关 |
| `harness.a2a.memory.digestion.cron` | `0 9 21 * * *` | cron 表达式(21:09 执行) |
| `harness.a2a.memory.digestion.batch-size` | `50` | 每批处理用户数 |
| `harness.a2a.memory.digestion.episodic-retention-days` | `30` | episodic memory 保留天数 |
| `harness.a2a.memory.digestion.ledger-retention-days` | `90` | ledger 保留天数 |
| `harness.a2a.memory.digestion.summary-max-length` | `200` | sample_query 截断长度 |
| `harness.a2a.memory.digestion.episodic-table-name` | `QualitySupervisor_episodic_memory` | episodic 表名 |

### 9.5 缓存相关(cache.*)

| 键 | 默认 | 说明 |
|---|---|---|
| `harness.a2a.response-cache.enabled` | `false` | ResponseCache 总开关 |
| `harness.a2a.response-cache.ttl-hours` | `24` | 缓存 TTL |

---

## 10. 统一验收 Checklist

> 本章节汇总所有 PR / 优化 / 夜间咀嚼 / 回归测试的验收项。

### 10.1 PR1 验收

- [ ] 用户喊"保存为 skill"后,SKILL.md 包含标准 YAML frontmatter(`name` / `description` / `version` / `last_evolved_at`)
- [ ] 同名 skill 再次保存,`skill_index.version` 自增,SKILL.md frontmatter `version: 2`
- [ ] 数据库不可达时,文件写入成功,DDL/UPSERT 失败仅打 warn

### 10.2 PR2 验收

- [ ] 开 `auto-synth.enabled=true threshold=3`:连问 3 次"杭州开发一部 Q1 缺陷密度"(每次换措辞)→ 第 3 次返回后,`workspace/.../skills/` 出现新 skill,**用户全程没说"保存"**
- [ ] `skill_candidate` 表 `status='synthesized'`、`synth_skill` 不为空
- [ ] 同一 fingerprint 被命中 5 次,只产 1 个 skill(`markSynthesized` 后续不再触发)
- [ ] 关闭开关后,行为与 PR1 完全一致

### 10.3 PR2.1 验收

- [ ] 开 cache + 开 auto-synth + threshold=3:R1 MISS,R2/R3 HIT,第 3 次 hit=3 触发蒸馏
- [ ] `markSynthesized` 后再问同问题:`skill_candidate.hit_count` 不再增长

### 10.4 PR3 验收

- [ ] 开 `retrieval.enabled=true` + `embedding.enabled=true`:同一指纹问题再问一次 → 日志看到 `SkillRetrievalHook injected 1 skill(s) ... fp=...:...`
- [ ] 新措辞问题(指纹不同) → L1 miss 走 L2,top-K 命中的 skill 在 system 末尾
- [ ] embedding 服务返回错维度 → 单条 warn,L1 仍能工作

### 10.5 PR3.5 验收

- [ ] 连问 3 遍 "云计算实验室 2026Q1 缺陷率"(每遍换措辞,但都用"云计算实验室" + "Q1/1季度/一季度" 任一组合)→ 第 3 次回复后,`workspace/.../skills/` 新增 SKILL.md
- [ ] 问 "杭州开发一部 Q1" 然后问 "杭一部 1 季度" → 前者命中,**后者 miss**(已知缺口)

### 10.6 PR3.8 验收

- [ ] `[MISS path] candidate ...` 日志级别 `info`(非 debug)
- [ ] L2 topK 返回 skill 数≥1,cosine ≥ 0.72
- [ ] Distiller parse 失败 warn 日志携带 `nameFound/descFound/bodyFound`

### 10.7 PR4 验收

- [ ] 开 `evolution.enabled=true` + 累计 ≥5 次召回的 skill
- [ ] 连续构造 3 次 `python_exec retry ≥ 2` 的失败请求 → `skill_index.failure_count ≥ 3`
- [ ] 触发 evolve 阈值 → 日志 `Skill evolution triggered`,SKILL.md version 2,counts 归零
- [ ] 构造 10 次失败请求 → `status='blacklist'`,L1/L2 都 miss
- [ ] 用户在 turn N+1 输入"不对" → turn N skill `failure_count + 1`
- [ ] 同一 skill 并发触发 evolve → 只有第一个 CAS 成功的请求实际跑 distiller

### 10.8 优化点验证

- [ ] EpisodicMemory 向量检索:`search()` 先向量后 FTS,cosine ≥ 0.55 才命中
- [ ] L2 缓存加速:`CachedSkill` 内存缓存 + 60s 定时刷新
- [ ] 跨 JVM CAS:`skill_index.evolving` 列 + local+DB 双重检测
- [ ] Pending Judgement 持久化:`skill_pending_judgement` 表 + L1/L2 读写
- [ ] 分层注入:`skills/` symlink → `skills-builtin/`,`SkillRetrievalHook` 只读 `skills-auto/`
- [ ] 蒸馏增强:`parseLenient()` + name/description fallback

### 10.9 夜间咀嚼验证

- [ ] 手动触发 `POST /debug/digest` → 日志 `MemoryDigestion: processing N active user(s)`
- [ ] SQL `SELECT * FROM digestion_log ORDER BY id DESC LIMIT 5` → `phase2_mined_traces > 0`
- [ ] SQL `SELECT fingerprint, tool_sequence FROM user_trace_summary WHERE date_key = CURDATE()` → fingerprint 包含 L2 工具名(`toolMetaInfo|router_tool`)

### 10.10 回归测试验证

- [ ] 多轮维度继承:Turn 1 查"杭州开发一部 Q1",Turn 2 问"那杭州开发二部呢" → Turn 2 响应含"13.1"(二部 Q1 基线值)
- [ ] Artifact handoff:问"查询所有部门质量数据" → 单次 router_tool 调用 `department=null` → ≥4 行 → ArtifactHandoffHook 触发(需先修复 SKILL.md)
- [ ] code_interpreter:问"8 部门统计指标" → spawn code_interpreter → python_exec 调用(需先修复 supervisor prompt)

---

## 11. 附录

### 附录 A:合并来源文档清单

本文档合并了以下 15 个原始文档:

1. [`skill-self-evolution-detail.md`](./skill-self-evolution-detail.md) — Skill 自进化三大能力详细设计
2. [`skill-evolution-pr1-changes.md`](./skill-evolution-pr1-changes.md) — PR1 变更汇总
3. [`skill-evolution-pr2-changes.md`](./skill-evolution-pr2-changes.md) — PR2 变更汇总
4. [`skill-evolution-pr3-changes.md`](./skill-evolution-pr3-changes.md) — PR3 变更汇总
5. [`skill-evolution-pr3.5-changes.md`](./skill-evolution-pr3.5-changes.md) — PR3.5 规则正则覆盖度修正
6. [`skill-evolution-pr4-design.md`](./skill-evolution-pr4-design.md) — PR4 设计文档
7. [`skill-evolution-pr4-changes.md`](./skill-evolution-pr4-changes.md) — PR4 变更汇总
8. [`regression-findings.md`](./regression-findings.md) — 回归测试发现报告
9. [`no-dimension-skill-synthesis-fix.md`](./no-dimension-skill-synthesis-fix.md) — 无维度问题修复
10. [`new-test-scenarios.md`](./new-test-scenarios.md) — 三组新增测试场景
11. [`memory-skill-optimization-plan.md`](./memory-skill-optimization-plan.md) — 8 个优化点
12. [`memory-module-design.md`](./memory-module-design.md) — 记忆模块设计
13. [`trace-context-enhancement-plan.md`](./trace-context-enhancement-plan.md) — 工具调用上下文增强
14. [`layered-injection-fix-plan.md`](./layered-injection-fix-plan.md) — 分层注入修复
15. [`nightly-memory-digestion-plan.md`](./nightly-memory-digestion-plan.md) — 夜间咀嚼详细设计

### 附录 B:数据库表总览

| 表名 | 用途 | 主要列 |
|------|------|--------|
| `skill_index` | 技能索引 + 元数据 + embedding | `name` PK, `fingerprint`, `description`, `embedding`, `version`, `usage_count`, `success_count`, `failure_count`, `status`, `evolving` |
| `skill_candidate` | 自动蒸馏候选 | `fingerprint` PK, `user_id`, `hit_count`, `last_query`, `status`, `synth_skill` |
| `user_trace_summary` | 离线 trace 汇总 | `user_id`, `date_key`, `fingerprint`, `tool_sequence`, `success_count`, `failure_count`, `failure_score`, `user_query`, `tool_call_details`, `status` |
| `digestion_log` | 夜间咀嚼日志 | `user_id`, `date_key`, `phase1_cleaned_ledger`, `phase2_mined_traces`, `phase3_skills_evolved`, `phase4_memory_digested`, `started_at`, `completed_at` |
| `skill_pending_judgement` | 跨轮负反馈持久化 | `session_key` PK, `skills_json`, `exemplar_question`, `created_at` |
| `agent_memory` | MEMORY.md 镜像 | `user_id`, `kind`, `key_name`, `body`, `version` |
| `agent_memory_ledger` | 日 ledger | `user_id`, `date_key`, `source`, `line` |
| `QualitySupervisor_episodic_memory` | 长期记忆 | `session_id`, `role`, `content`, `embedding`, `tool_call_details`, `status` |

---

**文档结束**

> 本文档完整保留了所有原始内容,包括设计理念、变更文件清单、配置项、验收 checklist、测试场景等。
> 后续如有新增 PR 或修复,可继续追加到对应章节。
