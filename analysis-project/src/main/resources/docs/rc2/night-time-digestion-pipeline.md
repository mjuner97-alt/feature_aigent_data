# 夜间咀嚼管道（Night-time Digestion Pipeline）技术文档

> **文档日期**: 2026-07-09
> **对应代码**: `agent/memory/digestion/*`、`harness/skills/*`、`harness/hooks/*`

夜间咀嚼管道 — 简述

一句话总结

每天晚上定时扫描用户对话记录，找出失败多的模式→ 蒸馏成新技能或演进旧技能；找出成功多的模式→ 合并进用户记忆。

四个阶段

Phase 1 清理：删除 90 天前的账本记录（腾空间）

Phase 2 挖掘：扫描对话记录
每个会话 → 提取工具调用链 → 判断成功/失败
按 (用户, 指纹) 分组汇总 → 写入 user_trace_summary 表
┌─────────────────────────────────────────────┐
│  fingerprint:     agent_spawn|tool_index|router_tool   │
│  runtime_fingerprint:  _global|query|defect_density    │ ← 关键：指标指纹
│  success_count: 3,  failure_count: 7                   │
│  status: pending                                       │
└─────────────────────────────────────────────┘

Phase 3 技能演进/蒸馏：处理 pending 轨迹
对每条轨迹 → 评估是否"值得处理"
┌─────────────────────────────────────────┐
│ 条件：总使用 ≥5次 且 失败率 >30%        │
└─────────────────────────────────────────┘

    然后 查找已有技能：
    ┌───────────────────────────────────────────────────┐
    │ 先用 runtime_fingerprint 查 (_global|query|defect_density) │  ← 主路径
    │ 没命中 → 再用 tool_sequence_fingerprint 查                 │  ← 回退
    │ 都没命中 → 这是新模式，需要蒸馏                            │
    └───────────────────────────────────────────────────┘

    新模式 → 蒸馏新技能：
      子智能体分析对话 → 生成 SKILL.md → 写磁盘 + 写数据库 + 写向量

    已有技能 → 演进旧技能：
      读旧 SKILL.md + 失败上下文 → LLM 重写 → 覆写 + 计数清零

Phase 4 记忆归并：成功的模式
读取成功轨迹 → LLM 合并进用户的 MEMORY.md

核心效果

┌──────────────────────────────┬──────────────────────────┬──────────────────────────────────────────────┐
│             场景             │       没有夜间咀嚼       │                  有夜间咀嚼                  │
├──────────────────────────────┼──────────────────────────┼──────────────────────────────────────────────┤
│ 用户反复问"缺陷密度"但总失败 │ 每次都走失败路径，无改善 │ 自动蒸馏出 defect_density 技能，下次命中     │
├──────────────────────────────┼──────────────────────────┼──────────────────────────────────────────────┤
│ 已有技能但频繁失败           │ 失败计数持续累积         │ 自动演进 SKILL.md 修复缺陷，计数清零重新评估 │
├──────────────────────────────┼──────────────────────────┼──────────────────────────────────────────────┤
│ 成功经验散落在对话中         │ 不会被总结               │ 合并进 MEMORY.md，后续对话有上下文           │
└──────────────────────────────┴──────────────────────────┴──────────────────────────────────────────────┘

指纹为什么重要

同一个问题 "查缺陷密度"，不同用户、不同时间的工具调用链可能不同：
- 用户A: agent_spawn|tool_index|router_tool
- 用户B: agent_spawn|tool_index|toolMetaInfo

但如果只按工具链指纹分组，这俩是不同的技能，无法共享。

引入 runtime_fingerprint = _global|query|defect_density 后：
- 不管工具链怎么变，只要问的是"缺陷密度"，都归到同一个技能
- L1 查找优先用指标指纹，确保技能能跨用户、跨工具链共享
- 工具链指纹作为回退，保证即使指标分类没命中，也能按工具链模式找到已有技能
- 不管工具链怎么变，只要问的是"缺陷密度"，都归到同一个技能
- L1 查找优先用指标指纹，确保技能能跨用户、跨工具链共享
- 工具链指纹作为回退，保证即使指标分类没命中，也能按工具链模式找到已有技能

一个完整例子

用户连续 7 次问 "杭州开发一部缺陷密度"，5 次失败

Phase 2:
→ runtime_fingerprint = _global|query|defect_density
→ user_trace_summary: success=2, failure=5, status=pending

Phase 3:
用户连续 7 次问 "杭州开发一部缺陷密度"，5 次失败

Phase 2:
→ runtime_fingerprint = _global|query|defect_density
→ user_trace_summary: success=2, failure=5, status=pending

Phase 3:
→ evaluate: 7次 ≥5, 71%失败 >30% ✓
→ findSkillForTrace: 用 _global|query|defect_density 查 skill_index
→ 没命中 → distill 新技能
→ 子智能体蒸馏 → 生成 defect_density_query/SKILL.md

第二天用户再问 "缺陷密度":
→ SkillRetrievalHook 用 _global|query|defect_density 查 L1
→ 命中! → 加载 SKILL.md → 给 LLM 用 → 回答质量提升

引入 runtime_fingerprint = _global|query|defect_density 后：
- 不管工具链怎么变，只要问的是"缺陷密度"，都归到同一个技能
- L1 查找优先用指标指纹，确保技能能跨用户、跨工具链共享
- 工具链指纹作为回退，保证即使指标分类没命中，也能按工具链模式找到已有技能

一个完整例子

用户连续 7 次问 "杭州开发一部缺陷密度"，5 次失败

Phase 2:
→ runtime_fingerprint = _global|query|defect_density
→ user_trace_summary: success=2, failure=5, status=pending

Phase 3:
→ evaluate: 7次 ≥5, 71%失败 >30% ✓
→ findSkillForTrace: 用 _global|query|defect_density 查 skill_index
→ 没命中 → distill 新技能
→ 子智能体蒸馏 → 生成 defect_density_query/SKILL.md

第二天用户再问 "缺陷密度":
→ SkillRetrievalHook 用 _global|query|defect_density 查 L1
→ 命中! → 加载 SKILL.md → 给 LLM 用 → 回答质量提升

✻ Worked for 20s

❯   Phase 3:                                                                                                                                                                        
→ evaluate: 7次 ≥5, 71%失败 >30% ✓                                                                                                                                            
→ findSkillForTrace: 用 _global|query|defect_density 查 skill_index                                                                                                           
→ 没命中 → distill 新技能                                                                                                                                                     
→ 子智能体蒸馏 → 生成 defect_density_query/SKILL.md

这一步如果命中了 是优化defect_density_query/SKILL.md 吗？

● 对，命中了就走**演进（Evolve）**路径，而不是蒸馏新技能。具体流程：

命中已有技能 defect_density_query
↓
dispatchEvolve("defect_density_query", trace)
↓
1. 加锁（本地 CAS + MySQL evolving=TRUE）
   ↓
2. 读旧 SKILL.md 内容
   oldBody = Files.readString("skills-auto/defect_density_query/SKILL.md")
   ↓
3. 构建失败上下文
   failedContext = "用户问: 查缺陷密度 → 工具链: agent_spawn→tool_index→router_tool → 失败原因: ..."
   ↓
4. LLM 重写（SkillDistiller.evolve）
   Prompt 大意: "这个技能在以下场景失败了，请改进它"
   输入: 旧 SKILL.md + 失败上下文 + 用户问题
   输出: 改进后的 SKILL.md（name 不变）
   ↓
5. 覆写磁盘 + 刷新向量
   ↓
6. 清零计数器（关键！）
   resetCounts("defect_density_query")
   → success_count = 0, failure_count = 0
   ↓
7. 释放锁（MySQL evolving=FALSE）

蒸馏 vs 演进对比

┌─────────────────────┬──────────────────────────────────┬────────────────────────────────────┐
│                     │     蒸馏（Distill）— 未命中      │        演进（Evolve）— 命中        │
├─────────────────────┼──────────────────────────────────┼────────────────────────────────────┤
│ 触发条件            │ findSkillForTrace() 返回 null    │ findSkillForTrace() 返回已有技能名 │
├─────────────────────┼──────────────────────────────────┼────────────────────────────────────┤
│ 做什么              │ 从零创建新技能                   │ 改进已有技能                       │
├─────────────────────┼──────────────────────────────────┼────────────────────────────────────┤
│ LLM prompt          │ "请根据对话上下文编写一个新技能" │ "这个技能失败了，请修复它"         │
├─────────────────────┼──────────────────────────────────┼────────────────────────────────────┤
│ skill_index.name    │ 新名字（蒸馏生成）               │ 不变                               │
├─────────────────────┼──────────────────────────────────┼────────────────────────────────────┤
│ skill_index.version │ 初始 1                           │ +1（upsertOnSave 自增）            │
├─────────────────────┼──────────────────────────────────┼────────────────────────────────────┤
│ 计数器              │ N/A                              │ 清零（resetCounts）                │
├─────────────────────┼──────────────────────────────────┼────────────────────────────────────┤
│ 锁                  │ 无                               │ 本地 CAS + MySQL 跨 JVM 锁         │
└─────────────────────┴──────────────────────────────────┴────────────────────────────────────┘

清零计数器为什么重要

不清零的话，旧技能的 7 次失败计数会跟着新版本走，导致：
- 新版本还没被用过就 failure_count=5
- 可能立即再次触发演进 → 死循环

resetCounts() 让新版本从 0/0 开始重新积累评估数据。

---

## 一、概述

夜间咀嚼是一个定时批处理管道，每天 21:09（默认 cron）对活跃用户的会话数据做离线分析，目标有三：

1. **沉淀新技能** — 高失败率的工具调用模式 → 蒸馏为 SKILL.md
2. **演进已有技能** — 已有技能仍频繁失败 → 重写 SKILL.md 修复缺陷
3. **归并成功经验** — 成功的调用模式 → 合并进用户 MEMORY.md

整个管道由 `MemoryDigestionService` 编排，按 Phase 1→2→3→4 顺序执行，每个 Phase 独立 try-catch，单个 Phase 失败不阻塞后续。

---

## 二、管道架构

```
                          ┌──────────────────────────────────────────┐
                          │ MemoryDigestionService                    │
                          │ @Scheduled(cron="0 9 21 * * *")          │
                          │ @ConditionalOnProperty(digestion.enabled) │
                          │                                          │
                          │  MySQL GET_LOCK("memory_digestion_lock") │
                          │  → 跨 JVM 互斥，同一时间只有一个实例执行  │
                          │                                          │
                          │  findActiveUsers() → 遍历每个 userId     │
                          │    ↓                                     │
                          │  Phase 1: CleanLedger                   │
                          │  Phase 2: MineTraces                     │
                          │  Phase 3: EvolveSkills                   │
                          │  Phase 4: ConsolidateMemory               │
                          │    ↓                                     │
                          │  recordDigestionLog()                    │
                          └──────────────────────────────────────────┘
```

---

## 三、Phase 1: CleanLedger（清理账本）

### 输入
| 输入 | 来源 | 说明 |
|------|------|------|
| `userId` | `findActiveUsers()` | 当前处理的用户 ID |
| `cutoff` | `now - ledgerRetentionDays`（默认 90 天） | 保留窗口 |

### 处理
```java
store.deleteLedgerBefore(userId, cutoff);
```
删除 `agent_memory_ledger` 表中早于 cutoff 的记录。

### 输出
- 删除 `agent_memory_ledger` 中过期行
- 无返回值（副作用）

---

## 四、Phase 2: MineTraces（挖掘轨迹）

### 输入
| 输入 | 来源 | 说明 |
|------|------|------|
| `date` | `LocalDate.now()` | 当天日期 |
| `userId` | `findActiveUsers()` | 当前处理的用户 ID |

### 处理核心

```
TraceMiner.mineTraces(date, userId)
    ↓
loadSessions(date, userId)
    → JDBC 查询 episodic_memory 表
    → 过滤 cache-hit: 前缀的会话
    → 返回 List<RawSession>
    ↓
对每个 RawSession:
    buildSession(sessionId, messages)
        → 提取 L1 工具链 (tool_router, agent_spawn, python_exec 等)
        → 读取 L2 子智能体文件 (memory_messages.jsonl)
        → 合并 L1 + L2 工具链
        → classifyTrace() → FailureClass(failureCount, failureScore, successful)
        → 提取 userQuery (第一条 USER 消息)
    ↓
按 (userId, fingerprint) 分组:
    → fingerprint = "tool1|tool2|tool3" (工具序列指纹)
    → runtimeFingerprint = fingerprintCalc.computeMetric("query", userQuery)
                               = "_global|query|defect_density" (指标指纹)
    ↓
upsertGroups(groups)
    → INSERT INTO user_trace_summary
        (user_id, date_key, fingerprint, runtime_fingerprint, tool_sequence,
         success_count, failure_count, sample_query, user_query, tool_call_details, status)
      ON DUPLICATE KEY UPDATE
        success_count = VALUES(success_count), failure_count = VALUES(failure_count), ...
```

### L1 / L2 工具链提取

| 层级 | 来源 | 提取方式 |
|------|------|----------|
| L1（主智能体） | `episodic_memory` 表 TOOL/ASSISTANT 消息 | 正则匹配 `tool_router`、`[TOOL: xxx]`、`agent_spawn`、`派单给` 等模式 |
| L2（子智能体） | `workspace/agents/{agentId}/context/{subSessionId}/memory_messages.jsonl` | 解析 JSONL 中 `tool_use`/`tool_result` 块，提取 input/output |

L2 工具 ID 插入到 L1 工具链的最后一个 `agent_spawn` 之后。

### 失败分类

| 模式 | 权重 | 判定条件 |
|------|------|----------|
| `FAILURE` | 1.0 | `python_exec` 非零退出码、tool 输出包含 `error`/`Exception`/`失败`/`timeout` |
| `FAILURE` | 1.0 | `tool_router` 返回空 markdown 表 |
| `POSSIBLE_FAILURE` | 0.5 | 会话无结束标记、assistant 提到 `maxIters`/`最大迭代` |

`successful = (failureCount == 0) && (failureScore < 1.0)`

### 双重指纹策略

| 指纹类型 | 格式 | 示例 | 存储 | 用途 |
|----------|------|------|------|------|
| 工具序列指纹 | `tool1\|tool2\|tool3` | `agent_spawn\|tool_index\|router_tool` | `user_trace_summary.fingerprint` | Phase 3 回退查找 |
| 运行时指标指纹 | `_global\|intent\|metricTag` | `_global\|query\|defect_density` | `user_trace_summary.runtime_fingerprint` | Phase 3 主查找（L1） |

运行时指标指纹由 `FingerprintCalculator.computeMetric("query", userQuery)` 计算，其中：
- `metricTag` 来自 `MetricClassificationService.ruleBasedTag(question)` — 基于关键词的快速分类
- 9 个内置指标类别：`defect_density`、`response_time`、`error_rate`、`throughput`、`availability`、`code_quality`、`test_coverage`、`range_analysis`、`stat_summary`
- 关键词不匹配时回退到 `general`

### 输出
| 输出 | 目标 | 说明 |
|------|------|------|
| `user_trace_summary` 行 | MySQL 表 | 聚合后的轨迹摘要，`status='pending'` |
| 返回值 | `MemoryDigestionService` | upserted group 数量 |

### 数据库表: `user_trace_summary`

```sql
CREATE TABLE IF NOT EXISTS user_trace_summary (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         VARCHAR(128) NOT NULL,
    date_key        DATE NOT NULL,
    fingerprint     VARCHAR(255) NOT NULL COMMENT 'tool-sequence fingerprint',
    runtime_fingerprint VARCHAR(255) DEFAULT NULL COMMENT 'metric fingerprint for L1 lookup',
    tool_sequence   TEXT,
    success_count   INT NOT NULL DEFAULT 0,
    failure_count   INT NOT NULL DEFAULT 0,
    sample_query    TEXT COMMENT 'last assistant snippet from failed session',
    user_query      TEXT COMMENT 'first user question from session',
    tool_call_details TEXT COMMENT 'JSON-serialized ToolCallDetail list',
    status          VARCHAR(16) NOT NULL DEFAULT 'pending',
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_date_fp (user_id, date_key, fingerprint)
)
```

---

## 五、Phase 3: EvolveSkills（技能演进与蒸馏）

### 输入
| 输入 | 来源 | 说明 |
|------|------|------|
| `pendingTraces` | `loadPendingTraces(userId, date)` | `status='pending'` 的 `user_trace_summary` 行 |
| 每行 | `TraceSummary` record | 包含 fingerprint, runtimeFingerprint, toolSequence, successCount, failureCount, sampleQuery, userQuery, toolCallDetails |

### 处理核心

```
SkillFlowEvolver.evolve(traces)
    ↓
对每个 TraceSummary t:
    ↓
1. evaluate(t)
    → total = successCount + failureCount
    → failRate = failureCount / total
    → 可操作条件: total >= minTraces(默认5) && failRate > 0.3
    → 不满足 → skip
    ↓
2. findSkillForTrace(t)
    → 优先: runtimeFingerprint → skill_index.fingerprint (L1 主键)
    → 回退: fingerprint → skill_index.tool_sequence_fingerprint
    → 双重查找都不命中 → 新技能蒸馏路径
    → 任一命中 → 已有技能演进路径
    ↓
3a. dispatchDistill(t)  [新技能]
    → metricTag = metricClassifier.ruleBasedTag(userQuery)
    → runtimeFp = fingerprintCalc.computeMetric("query", userQuery)
    → toolCallContext = SkillSynthesisRunner.buildEnrichedContext(userQuery, t.toolCallDetails)
    →
    ├── viaSubagent=true (默认):
    │   → SkillSynthesisRunner.distillForDigestion(toolSeqFp, runtimeFp, userQuery, toolCallContext, metricTag)
    │       → 构建 HarnessAgent 子智能体 (skill_distiller agent)
    │       → CaptureSkillSaveTool 捕获 save_skill 调用
    │       → 子智能体 LLM 分析对话 + 工具链路
    │       → 捕获 DistilledSkill 结果
    │       → 去重检查 (findNameByFingerprint + findByName)
    │       → metricTag 注入 (withMetricTag)
    │       → SkillSaveTool.saveSkill() 写磁盘
    │       → 嵌入同步刷新 (buildEmbedText → embeddingClient.embed → vectorIndex.upsert)
    │       → upsertToolSequenceFingerprint() 写独立列
    │
    └── viaSubagent=false (回退):
        → SkillDistiller.distillWithContext(userQuery, fingerprint, toolCallContext, metricTag)
        → saveDistilled() → 同上但不走子智能体
    ↓
3b. dispatchEvolve(skillName, t)  [已有技能]
    → markEvolving(skillName)  // 本地 CAS: ConcurrentHashMap.putIfAbsent
    → indexRepo.tryAcquireEvolveLock(skillName)  // MySQL CAS: UPDATE ... SET evolving=TRUE WHERE evolving=FALSE
    → 加锁失败 → skip
    → 加锁成功:
        → oldBody = Files.readString(skillFile)
        → failedContext = buildFailedContext(t)  // 优先用 toolCallDetails, 回退用 sampleQuery
        → userQuery = t.userQuery != null ? t.userQuery : t.sampleQuery
        → SkillDistiller.evolve(skillName, oldBody, userQuery, failedContext)
        → evolved != null → saveEvolved(name, evolved)
            → SkillSaveTool.saveSkill(name, description, body)  // 写磁盘
            → vectorIndex.upsertEmbeddingOnly(name, vec)  // 刷新嵌入
            → indexRepo.resetCounts(name)  // 清零 success/failure 计数器
        → finally: indexRepo.releaseEvolveLock(name) + markEvolved(name)
```

### 评估阈值

| 参数 | 默认值 | 配置项 | 说明 |
|------|--------|--------|------|
| `minTraces` | 5 | `harness.a2a.memory.digestion.min-traces` | 总使用次数阈值 |
| `failRate` | 0.3 | 硬编码 | 失败率阈值 |

条件：`total >= minTraces && failRate > 0.3`

### 双重指纹查找优先级

```
findSkillForTrace(TraceSummary t):
    1. runtimeFingerprint ≠ null 且非空
       → indexRepo.findNameByFingerprint(runtimeFingerprint)  // 查 skill_index.fingerprint
       → 命中 → return skillName  (L1 主键查找)
    2. fingerprint ≠ null 且非空
       → indexRepo.findNameByToolSequenceFingerprint(fingerprint)  // 查 skill_index.tool_sequence_fingerprint
       → 命中 → return skillName  (回退查找)
    3. 双重都不命中 → return null  (走新技能蒸馏路径)
```

### 蒸馏路径选择

| 路径 | 配置 | 方法 | 特点 |
|------|------|------|------|
| 子智能体蒸馏 | `via-subagent=true`（默认） | `SkillSynthesisRunner.distillForDigestion()` | 完整对话上下文，无 thinking 标签污染，结构化输出捕获 |
| 直调 LLM | `via-subagent=false` | `SkillDistiller.distillWithContext()` | 回退路径，LLM 直接蒸馏，可能有 thinking 标签污染 |

### 锁机制

| 锁类型 | 范围 | 实现 | 用途 |
|--------|------|------|------|
| 本地 CAS | 单 JVM | `ConcurrentHashMap<String, Boolean> evolving.putIfAbsent` | 防止同 JVM 内并发演进 |
| MySQL CAS | 跨 JVM | `UPDATE skill_index SET evolving=TRUE WHERE name=? AND evolving=FALSE` | 防止多 JVM 并发演进同一技能 |

### 输出
| 输出 | 目标 | 说明 |
|------|------|------|
| 新 SKILL.md 文件 | `{workspace}/skills-auto/{name}/SKILL.md` | 蒸馏或演进后的技能文件 |
| `skill_index` 行 | MySQL | upsert（版本+1）、fingerprint 写入、tool_sequence_fingerprint 写入 |
| `skill_index.evolving` | MySQL | 演进锁（完成后释放） |
| `skill_index.success_count/failure_count` | MySQL | 演进后清零（resetCounts） |
| 返回值 | `MemoryDigestionService` | 演进/蒸馏的技能数量 |

---

## 六、Phase 4: ConsolidateMemory（归并记忆）

### 输入
| 输入 | 来源 | 说明 |
|------|------|------|
| `userId` | `findActiveUsers()` | 当前处理的用户 |
| `successTraces` | `loadSuccessTraces(userId, date)` | `success_count > 0` 的 `user_trace_summary` 行 |

### 处理核心

```
MemoryFlowConsolidator.consolidate(userId, successTraces)
    ↓
1. 过滤: 只保留 successCount > 0 的轨迹
    ↓
2. 读取当前 MEMORY.md
    → MysqlMemoryStore.read(userId, KIND_MEMORY_MD, "MEMORY.md")
    ↓
3. 构建 LLM prompt
    → buildConsolidationPrompt(currentMd, traces)
    → 列出每个成功轨迹:
        - 模式: runtimeFingerprint ?? fingerprint  (优先用指标指纹)
        - 工具链: toolSequence
        - 成功次数
        - 用户问题
        - 工具调用详情（渲染为编号步骤列表）
    → 中文 prompt 要求 LLM 合并到 MEMORY.md 的对应章节
    ↓
4. 调用 LLM
    → model.stream(prompt, options)
    → 收集流式输出
    ↓
5. 写回 MEMORY.md
    → MysqlMemoryStore.upsert(userId, KIND_MEMORY_MD, "MEMORY.md", consolidatedContent)
    → MemoryHydrator.hydrate(userId)  // 同步到文件系统
    ↓
6. 更新水位线
    → 写入 {workspaceMemoryRoot}/{userId}/.consolidation_state
    → 内容: Instant.now().toString()
```

### 输出
| 输出 | 目标 | 说明 |
|------|------|------|
| 更新的 MEMORY.md | MySQL + 文件系统 | 合并了成功经验的记忆文件 |
| `.consolidation_state` | 文件系统 | 水位线，防止内置 MemoryConsolidator 重复处理 |
| 返回值 | `MemoryDigestionService` | `true` 表示更新了 MEMORY.md |

---

## 七、数据库表总览

| 表名 | Phase | 读/写 | 说明 |
|------|-------|-------|------|
| `agent_memory_ledger` | 1 | 读 + 删 | 清理过期账本记录 |
| `{episodicTableName}` | 2 | 读 | 加载会话消息 |
| `user_trace_summary` | 2（写）、3/4（读） | 读写 | 轨迹聚合摘要 |
| `skill_index` | 3 | 读写 | 技能元数据（版本、计数、锁、指纹） |
| `skill_candidate` | 在线路径 | 读写 | 仅在线路径使用，夜间路径不涉及 |
| `digestion_log` | 末尾 | 写 | 执行日志（每 Phase 统计） |
| MEMORY.md (via MysqlMemoryStore) | 4 | 读写 | 用户记忆归并 |

### `skill_index` 表结构

```sql
CREATE TABLE IF NOT EXISTS skill_index (
    name                        VARCHAR(128) PRIMARY KEY,
    fingerprint                 VARCHAR(255) NULL COMMENT 'L1 lookup key: _global|intent|metricTag',
    description                 TEXT,
    embedding                   LONGTEXT NULL COMMENT 'PR3 reserved; JSON float[]',
    version                     INT NOT NULL DEFAULT 1,
    usage_count                 INT NOT NULL DEFAULT 0,
    success_count               INT NOT NULL DEFAULT 0,
    failure_count               INT NOT NULL DEFAULT 0,
    last_used                   TIMESTAMP NULL,
    evolving                    BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'PR4 cross-JVM evolve lock',
    status                      VARCHAR(16) NOT NULL DEFAULT 'active',
    tool_sequence_fingerprint   VARCHAR(255) DEFAULT NULL COMMENT 'Phase 3 offline lookup key',
    updated_at                  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_status (status),
    KEY idx_tool_seq_fp (tool_sequence_fingerprint)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

**关键字段说明**：
- `fingerprint`: 运行时指标指纹（如 `_global|query|defect_density`），L1 主键查找用
- `tool_sequence_fingerprint`: 工具序列指纹（如 `agent_spawn|tool_index|router_tool`），回退查找用
- `evolving`: 跨 JVM 演进锁，`tryAcquireEvolveLock()` 设置为 TRUE，`releaseEvolveLock()` 释放
- `success_count` / `failure_count`: 演进后通过 `resetCounts()` 清零

---

## 八、指纹体系

### 双重指纹设计

| 指纹类型 | 格式 | 示例 | 计算方式 | 存储位置 | 查找优先级 |
|----------|------|------|----------|----------|-----------|
| 运行时指标指纹 | `_global\|{intent}\|{metricTag}` | `_global\|query\|defect_density` | `FingerprintCalculator.computeMetric()` | `skill_index.fingerprint` | **优先**（L1 主键） |
| 工具序列指纹 | `{tool1}\|{tool2}\|{tool3}` | `agent_spawn\|tool_index\|router_tool` | `TraceMiner.fingerprint()` | `skill_index.tool_sequence_fingerprint` | 回退 |

### metricTag 分类

由 `MetricClassificationService.ruleBasedTag()` 基于关键词匹配确定：

| 类别 | 关键词示例 | Chinese Hint |
|------|-----------|--------------|
| `defect_density` | 缺陷密度, bug密度, 缺陷率, defect_density | 缺陷密度/Bug密度 |
| `response_time` | \brt\b, 响应时间, 耗时, 延迟, latency | 响应时间/延迟/RT |
| `error_rate` | 错误率, 失败率, 异常率, error_rate | 错误率/失败率/异常率 |
| `throughput` | 吞吐量, tps, qps, 并发 | 吞吐量/TPS/QPS/并发 |
| `availability` | 可用性, sla, 稳定性 | 可用性/SLA/稳定性 |
| `code_quality` | 代码质量, 圈复杂度, 重复率 | 代码质量/圈复杂度/重复率 |
| `test_coverage` | 测试覆盖率, 覆盖率, case覆盖率 | 测试覆盖率/覆盖率 |
| `range_analysis` | 最大值, 最小值, 极差, 波动 | 极值范围分析 |
| `stat_summary` | 均值, 平均值, 求和, 统计 | 通用统计汇总 |
| `general`（回退） | 不匹配任何关键词时 | — |

**匹配规则**：按配置顺序优先匹配；关键词以 `\` 开头视为正则表达式；其余用 `contains()` 匹配；`ruleBasedTag()` 返回 `null` 时回退到 `general`。

### 指纹计算流程

```
用户问题: "对比2026年1季度和2026年2季度杭州开发一部的缺陷密度"
    ↓
FingerprintCalculator.computeMetric("query", question)
    ↓
MetricClassificationService.ruleBasedTag(question)
    → question.toLowerCase().contains("缺陷密度") → true
    → return "defect_density"
    ↓
computeMetric("_global", "query", "defect_density")
    → "_global|query|defect_density"
```

---

## 九、关键组件依赖关系

```
                          ┌─────────────────────────┐
                          │ MemoryDigestionService   │
                          │ (编排器)                 │
                          └─────┬──────┬──────┬──────┘
                                │      │      │
                    ┌───────────┘      │      └───────────┐
                    ↓                  ↓                   ↓
            ┌──────────────┐  ┌───────────────┐  ┌──────────────────┐
            │ TraceMiner    │  │ SkillFlowEvolver│  │ MemoryFlowConsolidator│
            │ (Phase 2)    │  │ (Phase 3)     │  │ (Phase 4)        │
            └──────┬───────┘  └──┬───┬───┬───┘  └────────┬─────────┘
                   │             │   │   │                │
          ┌────────┘      ┌────┘   │   └─────┐          │
          ↓                ↓        ↓          ↓          ↓
  FingerprintCalculator  SkillSynthesis  SkillDistiller  MysqlMemoryStore
  MetricClassificationService  Runner        (evolve)     MemoryHydrator
                             │    │
                    ┌────────┘    └──────────┐
                    ↓                        ↓
            CaptureSkillSaveTool        SkillSaveTool
            (子智能体捕获)              (磁盘写入)
                    │                        │
                    └────────┬───────────────┘
                             ↓
                    SkillIndexRepository  ←  skill_index 表
                    SkillVectorIndex      ←  向量索引
                    EmbeddingClient       ←  嵌入服务
```

---

## 十、配置项

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `harness.a2a.memory.digestion.enabled` | `false` | 总开关 |
| `harness.a2a.memory.digestion.cron` | `0 9 21 * * *` | 定时任务 cron |
| `harness.a2a.memory.digestion.batch-size` | `50` | TraceMiner 批量大小 |
| `harness.a2a.memory.digestion.episodic-retention-days` | `30` | TraceMiner 数据保留天数 |
| `harness.a2a.memory.digestion.ledger-retention-days` | `90` | Phase 1 清理窗口 |
| `harness.a2a.memory.digestion.min-traces` | `5` | Phase 3 最低轨迹数阈值 |
| `harness.a2a.memory.digestion.via-subagent` | `true` | Phase 3 是否走子智能体蒸馏 |
| `harness.a2a.memory.digestion.summary-max-length` | `200` | Phase 4 摘要最大长度 |
| `harness.a2a.memory.digestion.episodic-table-name` | `QualitySupervisor_episodic_memory` | Phase 2 查询的表名 |
| `harness.a2a.workspace.path` | `.agentscope/workspace/harness-a2a` | 工作空间根路径 |
| `harness.skills.auto-synth.threshold` | `3` | 在线路径蒸馏阈值 |
| `harness.skills.auto-synth.via-subagent` | `true` | 在线路径是否走子智能体 |
| `harness.skills.metric-classification.enabled` | `true` | 指标分类开关 |

---

## 十一、手动触发

```bash
# 触发夜间消化（无需等待 cron）
curl -sS -X POST http://localhost:8081/api/digestion/run \
  -H "Content-Type: application/json" \
  -d '{"user_id":"u-test-001"}' --max-time 300

# 响应示例
{"status":"ok","elapsedMs":70110,"timestamp":"2026-07-09T15:37:28.469"}
```

`DigestionController` 仅在 `harness.a2a.memory.digestion.enabled=true` 时激活。`user_id` 参数可选，不提供时对所有活跃用户执行。

---

## 十二、故障排查

### 常见问题

| 问题 | 日志特征 | 根因 | 修复 |
|------|----------|------|------|
| fingerprint 生成 `general` | `[FINGERPRINT] ruleBasedTag returned null/blank` | HTTP 请求编码损坏（中文→`???????`）或 `ruleBasedTag()` 无匹配 | 确保客户端发送 UTF-8 编码 JSON；检查 `metric-categories.yaml` 加载 |
| 子智能体蒸馏返回 null | `Subagent distillation returned null for toolSeqFp=...` | LLM 未调用 `save_skill`，可能需要调整 prompt 或增加 maxIters | 已知遗留项，暂不影响管道 |
| 演进锁残留 | `SELECT evolving FROM skill_index WHERE evolving=TRUE` 返回行 | 进程在 `tryAcquireEvolveLock` 后崩溃，未执行 `releaseEvolveLock` | 手动 `UPDATE skill_index SET evolving=FALSE WHERE evolving=TRUE` |
| `compiledKeywords` 为空 | `[METRIC_CLASSIFY] compiledKeywords is EMPTY` | YAML 配置加载失败或关键词字段为空 | 自动回退到内置默认值；检查 `workspace/knowledge/metric-categories.yaml` |

### 关键日志模式

```
# Phase 2: TraceMiner 产出
INFO  TraceMiner: mined N fingerprint group(s) from M session(s) for DATE (USER users)

# Phase 3: 指标分类
INFO  MetricClassificationService: [METRIC_CLASSIFY] Initialized with 53 keywords across 9 categories
INFO  MetricClassificationService: [METRIC_CLASSIFY] keyword match: keyword='缺陷密度' tag='defect_density'

# Phase 3: 指纹计算
DEBUG FingerprintCalculator: [FINGERPRINT] computeMetric: question='...' → metricTag='defect_density' → fingerprint='_global|query|defect_density'

# Phase 3: 蒸馏/演进
INFO  SkillSynthesisRunner: Starting digestion distill subagent for toolSeqFp=...
INFO  SkillFlowEvolver: Evolving skill 'xxx' to next version

# Phase 4: 记忆归并
INFO  MemoryFlowConsolidator: Consolidated MEMORY.md for user=xxx (NNN bytes)
```

---

## 十三、数据流图

```
                    ┌──────────────────────────────────────────────────┐
                    │              episodic_memory 表                  │
                    │   (会话消息: USER/ASSISTANT/TOOL/SYSTEM)          │
                    └──────────────────┬───────────────────────────────┘
                                       │ Phase 2: loadSessions()
                                       ↓
                    ┌──────────────────────────────────────────────────┐
                    │              TraceMiner                          │
                    │  • buildSession(): 提取 L1 + L2 工具链            │
                    │  • classifyTrace(): 失败分类                       │
                    │  • fingerprint(): 工具序列指纹                      │
                    │  • FingerprintCalculator.computeMetric(): 指标指纹│
                    └──────────────────┬───────────────────────────────┘
                                       │ upsertGroups()
                                       ↓
            ┌──────────────────────────────────────────────────────────┐
            │           user_trace_summary 表                          │
            │  fingerprint | runtime_fingerprint | tool_sequence       │
            │  success_count | failure_count | sample_query             │
            │  user_query | tool_call_details | status='pending'        │
            └─────────────┬──────────────────────────────┬─────────────┘
                          │ Phase 3: loadPendingTraces()  │ Phase 4: loadSuccessTraces()
                          ↓                              ↓
            ┌──────────────────────┐      ┌──────────────────────────┐
            │  SkillFlowEvolver    │      │  MemoryFlowConsolidator   │
            │                      │      │                          │
            │ evaluate():          │      │  filter successCount > 0│
            │  total≥5 && fail>30%│      │  读 MEMORY.md            │
            │                      │      │  LLM 合并                │
            │ findSkillForTrace(): │      │  写回 MEMORY.md          │
            │  1. runtimeFp → L1  │      └──────────────────────────┘
            │  2. toolSeqFp → 回退 │
            │                      │
            │ 命中 → evolve        │
            │ 未命中 → distill     │
            └──────┬──────┬───────┘
                   │      │
        ┌──────────┘      └──────────────┐
        ↓ evolve                          ↓ distill
  ┌──────────────┐              ┌────────────────────┐
  │ SkillDistiller│              │ SkillSynthesisRunner│
  │ .evolve()    │              │ .distillForDigestion│
  │ (直调 LLM)   │              │                      │
  │              │              │ via-subagent=true:   │
  │ 重写 SKILL.md│              │  HarnessAgent 子智能体│
  │ 保持 name    │              │  CaptureSkillSaveTool│
  └──────┬───────┘              │                      │
         │                      │ via-subagent=false:  │
         │ resetCounts()        │  SkillDistiller      │
         │ releaseEvolveLock() │  .distillWithContext()│
         ↓                      └──────────┬───────────┘
  ┌──────────────────────────────────────────────────┐
  │                skill_index 表                      │
  │  name | fingerprint | tool_sequence_fingerprint   │
  │  version | success_count | failure_count | evolving│
  └──────────────────────────────────────────────────┘
         │
         ↓
  ┌──────────────────────────────────────────────────┐
  │           {workspace}/skills-auto/{name}/SKILL.md │
  └──────────────────────────────────────────────────┘
```

---

## 十四、与在线路径的关系

| 特性 | 在线路径（Path A） | 夜间路径（Phase 3） |
|------|-------------------|-------------------|
| 触发方式 | 用户请求 → SkillSynthesisHook | 定时任务 → MemoryDigestionService |
| 蒸馏入口 | `SkillSynthesisRunner.bumpAndMaybeSynthesize()` | `SkillFlowEvolver.dispatchDistill()` |
| 蒸馏实现 | `distillViaSubagent()` 或 `distillViaDirectLlm()` | `distillForDigestion()` 或 `SkillDistiller.distillWithContext()` |
| CAS 去重 | `skill_candidate.markSynthesized()` | `skill_index.findByName()` + `findNameByFingerprint()` |
| 指纹来源 | `FingerprintCalculator.computeMetric()` (在线) | `FingerprintCalculator.computeMetric()` (Phase 2 预计算) |
| 演进 | `SkillEvolutionHook` (PR4) | `SkillFlowEvolver.dispatchEvolve()` |
| 锁机制 | 无 | 本地 CAS + MySQL `tryAcquireEvolveLock()` |
| metricTag 来源 | `MetricClassificationService.classifyAndUpdateAsync()` | `MetricClassificationService.ruleBasedTag()` |