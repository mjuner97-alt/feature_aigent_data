# Skill 自进化 —— 三大能力详细展开

汇总改动了哪些文档excel格式展示：


> 目标:把当前"手动喊保存 skill"的半自动沉淀路径,演进为**自动沉淀 + 智能召回 + 失败回流**的闭环,让同一类问题问得越多,系统响应越快、答案越稳。

## 现状一句话总结

| 能力 | 现状 | 评级 |
|---|---|---|
| **沉淀** 类似问题 → 凝结 skill | `generate_skill` 子智能体 + `save_skill` 工具,**需用户显式发话** | ⚠️ 半自动 |
| **召回** 新问题 → 加载对应 skill | `WorkspaceContextHook` 启动时**全量注入** `skills/*/SKILL.md` 到 system prompt | ✅ 但不可扩展 |
| **短路** 重复问题 → 跳过 LLM | `ResponseCacheHook` 以 `tenant\|intent\|dimensionKey` 精确命中 MySQL 缓存 | ✅ 但仅精确匹配 |
| **进化** skill 多次执行 → 自我修订 | **完全不存在** —— 失败/成功都不回流 | ❌ 缺口 |

证据:`workspace/harness-a2a/skills/` 里同时存在 `q1q2_compare` 和 `q1q2_compare_v2`,说明 LLM 是**另起炉灶**而不是迭代 v1。

---

## 能力 1:**沉淀** —— 多次问类似问题 → 自动总结成 skill

### 1.1 现状盘点(为什么不够)

| 组件 | 位置 | 行为 | 问题 |
|---|---|---|---|
| 框架 `SkillLearningHook` | harness JAR 内置,builder 默认开 | 黑盒,无可调参数 | 触发条件不可控,日志里看不到何时生效 |
| `SkillSaveTool` | `harness/tools/SkillSaveTool.java:34` | 由 `generate_skill` 子 agent 显式调用 | **必须用户喊**:"把刚才流程保存为 skill" |
| 已存在的 skill | `workspace/harness-a2a/skills/` | q1q2_compare / q1q2_compare_v2 / quarter_compare 并存 | 证据:每次都另起炉灶,无聚类 |

**核心症结**:没有把"用户连续问相似问题"这个**信号**接出来,所有沉淀都是 LLM 主观判断后由用户点头才落地。

### 1.2 方案:`SkillSynthesisHook` —— 自动从历史对话蒸馏

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

### 1.3 数据库 DDL

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

### 1.4 关键接口

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

### 1.5 防回流死锁

| 风险 | 防御 |
|---|---|
| Cache hit 也增计数 → 阈值很快触发但每次都是同一条 | `e.isFromCache()` 短路,**不计数** |
| LLM 蒸馏自身又产生新 query → 触发蒸馏 | 蒸馏路径标记 `internal=true`,Hook 见到跳过 |
| 两个 JVM 同时蒸馏同一个 fingerprint | `UPDATE ... SET status='synthesized' WHERE status='pending'` affected_rows 抢占 |
| LLM 产出垃圾 skill | 蒸馏后跑一次"自检":让 LLM 给自己打分,< 0.7 直接 `rejected` |

### 1.6 验收
- 跑 demo:连问 3 次"杭州开发一部 Q1 缺陷密度"(每次换措辞)→ 第 3 次返回后,`workspace/harness-a2a/skills/` 出现新 skill,**用户全程没说"保存"**
- `skill_candidate` 表 `status='synthesized'`、`synth_skill` 不为空
- 同义重复:同一 fingerprint 被命中 5 次,只产 1 个 skill,不出 `_v2`

---

## 能力 2:**召回** —— 类似问题来 → 加载对应 skill 引导

### 2.1 现状盘点

| 组件 | 行为 | token 影响 |
|---|---|---|
| `WorkspaceContextHook`(harness 内置) | 启动时把 `skills/*/SKILL.md` **全部**拼进 system prompt | 20 skill → ~8K token,且 19 个跟当前问题无关 |
| 无召回过滤 | LLM 每次都看到所有 skill,容易"乱挑" | 准确率下降,长上下文还增加幻觉 |

### 2.2 方案:`SkillRetrievalHook` —— 两级路由(精确指纹 → 向量)

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

### 2.3 数据库 DDL

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

### 2.4 关键接口

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

### 2.5 与 `WorkspaceContextHook` 的协作

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

### 2.6 验收
- skills 数量 20+ 时,system prompt 中 skill 段 ≤ 2K token
- 同义改写:"杭州开发一部 Q1 数据" 和 "查询杭州开发一部 2026 年第一季度数据" 命中同一 skill
- L1 缓存命中 < 1ms(直接 PK 查询)
- L2 向量召回 P95 < 50ms(本地 embedding 客户端 + MySQL 索引)

---

## 能力 3:**进化** —— 同一 skill 多次执行 → 基于反馈更新

### 3.1 现状盘点

| 信号源 | 现有 hook | 已收集 | 未利用 |
|---|---|---|---|
| 数据编造告警 | `DataGroundingHook` | reply 末尾打 `⚠️ 数据校验告警` | 不知道是哪个 skill 引起的 |
| Python 执行重试 | `PythonExecRetryHook` | 重试次数 | 不回流到 skill |
| 用户负反馈 | **无** | —— | 完全没有埋点 |
| skill 命中后是否被采纳 | **无** | —— | 召回 ≠ 采用 |

### 3.2 方案:`SkillEvolutionHook` + 元数据反馈

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

### 3.3 SKILL.md 强制 frontmatter

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

### 3.4 改造 `SkillSaveTool`(同名覆盖,版本递增)

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

### 3.5 失败信号采集点

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

### 3.6 演进 Prompt 模板(给 LLM)

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

### 3.7 验收
- 故意改坏一个 skill(让它误导 LLM 用错 csv 路径)→ 连跑 5 次,第 6 次起 `failure_rate > 0.3` 触发演进,version → +1
- 演进后再跑 5 次,`success_rate` 回升到 ≥ 0.85
- 永远不出现同名 + `_v2/_v3` 文件(`workspace/harness-a2a/skills/` 监控)
- `success_rate < 0.4` 的 skill 自动 `blacklist`,L1/L2 不再召回但文件保留(便于人审)

---

## 三者协同 —— 一张状态机图

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

## 与现有 Hook 的协作矩阵

| 现有 hook | 新增 hook | 协作方式 |
|---|---|---|
| `WorkspaceContextHook` | `SkillRetrievalHook` | 后者命中时设 `attribute`,前者读到后跳过 skill 段 |
| `ResponseCacheHook` | `SkillSynthesisHook` | 共享 `dimensionKey`;cache hit 不计入 synthesis 计数(否则永远滚不到阈值) |
| `DataGroundingHook` | `SkillEvolutionHook` | 告警直接触发 evolution,带上 `attribute("skills.retrieved")` |
| `PythonExecRetryHook` | `SkillEvolutionHook` | 重试 ≥2 触发 |
| `ArtifactHandoffHook` | (无) | skill 模板里强制写"csvPath 来自 handoff 消息",避免演进时再踩 |

---

## 避坑指南

1. **不要做 skill 自动合并** —— LLM 合并 skill 极易丢约束。让 v→v+1 演进,而不是 A+B→C。
2. **不要在 L1 用同步 embedding** —— 复用 `ResponseCacheHook` 的 `tenant|intent|dimensionKey` 精确 key 做一级路由,miss 再走 embedding。两级路由把 P99 压在 5ms。
3. **不要把 skill 内容塞进 `EpisodicMemory`** —— skill 是规则,memory 是事实,混在一起会让 LLM 把规则当历史复述。
4. **不要让 L2 在 dev 模式跑** —— 调试阶段会产生大量噪声 skill。加 `harness.skills.auto-synth.enabled=false` 默认关。

---

## 验收指标(上线后 2 周观测)

| 指标 | 当前基线 | 目标 |
|---|---|---|
| 重复类问题平均响应时间 | 33s(无 cache) / 1s(cache hit) | 命中 skill 的非 cache 路径 < 8s |
| 新问题自动沉淀率 | 0%(全靠用户喊) | 同指纹问 ≥3 次,自动产 skill ≥ 80% |
| skill 命中后回答准确率 | 未度量 | ≥ 0.85(以 DataGrounding 不告警为准) |
| Token 注入量(skill 段) | ~8K(20 skill 全注入) | < 2K(top-3) |
| 同语义重复 skill 比例 | 当前 3/15 ≈ 20% | < 5% |

---

## 配置项汇总(`application.properties`)

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

## 落地顺序(再次明确)

| 顺序 | PR | 内容 | 上线条件 |
|---|---|---|---|
| 1 | feat: skill metadata | `skill_index` 表 + `SkillSaveTool` 写 YAML 元数据 + `/admin/skills` 只读接口 | 不影响现有路径,只是观测 |
| 2 | feat: synthesis | `skill_candidate` 表 + `SkillSynthesisHook`(纯指纹,不要 embedding) | PR1 已上;开关默认 false,demo 验证后打开 |
| 3 | feat: retrieval | `EmbeddingClient` + `SkillVectorIndex` + `SkillRetrievalHook` | PR1/2 已上;先与 `WorkspaceContextHook` 并存,加 attribute 隔离 |
| 4 | feat: evolution | `SkillEvolutionHook` + 演进 prompt + 黑名单 | PR3 已上,确保 retrieved 的 skill 名能透传到 PostReply |

每个 PR 独立可回滚,坏一处不连锁。