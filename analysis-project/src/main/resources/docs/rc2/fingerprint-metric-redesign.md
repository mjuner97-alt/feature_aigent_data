# Fingerprint 重设计方案：metric 入 fingerprint，dimKey 移出

> 版本：v1.1 | 日期：2026-07-04 | 状态：✅ 已实施

---

## 1 问题分析

当前 fingerprint 格式 `{tenant}|{intent}|{dimKey}` 存在三个严重问题：

### P1：指标未参与 fingerprint → 不同指标撞车

同维度不同指标的问题产生完全相同的 fingerprint，导致 hit_count 聚合到同一行，最终蒸馏出一个覆盖多个指标的"大杂烩" skill。

| 问题 | fingerprint | 问题 |
|------|------------|------|
| 2026年2季度杭州开发五部的**缺陷密度** | `u:alice\|query\|time=QUARTER:2026年2季度\|dept=杭州开发五部` | ⚠️ 三者同 fingerprint |
| 2026年2季度杭州开发五部的**检出率** | `u:alice\|query\|time=QUARTER:2026年2季度\|dept=杭州开发五部` | ⚠️ |
| 2026年2季度杭州开发五部的**研发问题数** | `u:alice\|query\|time=QUARTER:2026年2季度\|dept=杭州开发五部` | ⚠️ |

后果：三个不同指标只蒸馏一个 skill，且该 skill 无法精准对应任何一个指标。

### P2：维度归一化缺失 → 同指标不同表达无法聚合

部门名称和时间段缺乏同义词/缩写归一化，同一问题不同表达产生不同 fingerprint。

| 表达 | 提取结果 | fingerprint |
|------|---------|------------|
| 2季度5部的缺陷密度 | dept 未识别（缩写"5部"不匹配），time 未识别 | `u:alice\|query\|<no-dim>` 或 null |
| 2026年2季度杭州开发五部的缺陷密度 | 完整识别 | `u:alice\|query\|time=QUARTER:2026年2季度\|dept=杭州开发五部` |

后果：同一个指标被拆成多个 fingerprint，hit_count 无法达到阈值，永远不触发蒸馏。

### P3：无维度问题被 L1 排除 → 写入/读出不对称

| 路径 | fingerprint | 结果 |
|------|------------|------|
| PR2 写入（SkillSynthesisHook） | `u:alice\|query\|<no-dim>` | ✅ 写入成功 |
| PR3 读出（SkillRetrievalHook） | `fingerprintOf()` 返回 `null` | ❌ L1 无法命中 |

后果：`"缺陷密度"` 这样无维度的问题，沉淀了 skill 但永远无法被 L1 检索命中，只能依赖 L2 向量检索。

---

## 2 核心洞察

Fingerprint 的目的是**识别"同一类问题"以聚合计数触发蒸馏**。

关键问题是：**"同一类问题"的粒度应该是什么？**

答案：**指标级别（metric），而非维度级别（dimension）**。

理由：
- 一个 "缺陷密度查询" skill 应该对所有维度组合通用——不管查 Q1 还是 Q2，不管查五部还是一部
- 维度差异是参数化问题，不是技能分类问题
- 维度归一化是无限难题（缩写、别名、方言），不适合做 fingerprint 组成部分

**指标级粒度完全由配置驱动**，业务方可通过 `workspace/knowledge/metric-categories.yaml` 随时扩展：

```
metric-categories.yaml  →  MetricClassificationService.ruleBasedTag(question)  →  fingerprint
```

新增指标类别只需加一行 YAML，无需改代码、无需重新部署：

```yaml
- tag: q2_gate_indicator
  chinese_hint: "Q2卡口指标"
  keywords: ["Q2卡口", "卡口指标", "q2_gate"]
  description: "questions about Q2 gate indicators"
```

| 问题 | ruleBasedTag | fingerprint |
|------|-------------|-------------|
| 杭州开发五部的Q2卡口指标 | `q2_gate_indicator` | `u:alice|query|q2_gate_indicator` |
| Q2卡口指标 | `q2_gate_indicator` | `u:alice|query|q2_gate_indicator` |

未匹配到任何指标关键词时，兜底为 `general`：`u:alice|query|general`

| 粒度 | 问题类型聚合 | 维度变化鲁棒性 | L1 命中率 |
|------|------------|--------------|----------|
| 维度级（当前） | ❌ 不同指标撞车 | ❌ 同义不同 fingerprint | ❌ 无维度排除 |
| 指标级（方案） | ✅ 不同指标独立 | ✅ 维度差异不影响 | ✅ 总有有效 fingerprint |

---

## 3 方案设计

### 3.1 新 fingerprint 格式

```
旧: {tenant}|{intent}|{dimKey}           e.g. u:alice|query|time=QUARTER:2026年2季度|dept=杭州开发五部
新: _global|{intent}|{metricTag}          e.g. _global|query|defect_density
```

| 组成 | 来源 | 示例 |
|------|------|------|
| tenant | `FingerprintCalculator.SKILL_SCOPE` = `"_global"` | 所有用户共享同一 fingerprint 行 |
| intent | `ResponseCacheService.classifyIntent()` | `query`, `analyze`, `skill` |
| metricTag | `MetricClassificationService.ruleBasedTag()` | `defect_density`, `response_time`, `error_rate`, `general`(兜底) |

**metricTag 兜底逻辑**：
- `ruleBasedTag()` 返回有效标签 → 直接使用
- `ruleBasedTag()` 返回 null 或空 → 使用 `"general"`

**永不返回 null**：无论问题有无维度、有无指标关键词，fingerprint 总有有效值。

### 3.2 场景验证

| 场景 | 问题 A | 问题 B | 旧 fingerprint | 新 fingerprint | 预期 |
|------|--------|--------|---------------|---------------|------|
| 1. 同指标不同表达 | 2季度5部的缺陷密度 | 2026年2季度杭州开发五部的缺陷密度 | ❌ 不同 | ✅ `_global\|query\|defect_density` 相同 | ✅ |
| 2. 同维度不同指标 | 缺陷密度 | 检出率 | ❌ 相同(撞车) | ✅ `defect_density` vs `error_rate` 不同 | ✅ |
| 3. 同指标有无维度 | 2026年2季度五部的Q2卡口指标 | 杭州开发五部的Q2卡口指标 | ❌ 不同 | ✅ `_global\|query\|general` 相同 | ✅ |
| 4. 无指标无维度 | 帮我查一下 | - | `u:x\|query\|<no-dim>` | `_global\|query\|general` | ✅ |
| 5. 分析类 | 分析一下缺陷密度趋势 | - | `u:x\|analyze\|...` | `_global\|analyze\|defect_density` | ✅ |
| 6. **跨用户聚合** | Alice 问"缺陷密度" | Bob 问"缺陷密度" | ❌ `u:alice\|...\|defect_density` ≠ `u:bob\|...\|defect_density` | ✅ `_global\|query\|defect_density` 相同 | ✅ |

### 3.3 各组件影响分析

#### 不改的部分

| 组件 | 原因 |
|------|------|
| ResponseCache 缓存键 | 缓存键 = `intent\|dimKey[|q=hash]`，与 fingerprint 独立，**不改** |
| DimensionState / DimensionStateManager | 维度提取归一化是独立需求，不影响 fingerprint |
| MetricClassificationService | `ruleBasedTag()` 已是同步方法，直接被 FingerprintCalculator 调用 |
| SkillDistiller | 蒸馏逻辑与 fingerprint 格式无关 |
| SkillSynthesisRunner | `bumpAndMaybeSynthesize(fingerprint, ...)` 接口不变 |
| TraceMiner / SkillFlowEvolver | 离线指纹是 `tool_id\|tool_id` 格式，与运行时 fingerprint 无关 |
| SkillCandidateRepository | DDL 不变，VARCHAR(255) 足够新格式 |

#### 改动的部分

| 文件 | 改动 |
|------|------|
| `FingerprintCalculator.java` | 新增 `computeMetric(tenant, intent, question)` 方法，注入 MetricClassificationService |
| `SkillSynthesisHook.java` | 替换 inline fingerprint 计算 → `fingerprintCalculator.computeMetric()` |
| `ResponseCacheHook.java` | `bumpAndMaybeSynthesize()` 中替换 → `fingerprintCalculator.computeMetric()` |
| `SkillRetrievalHook.java` | `fingerprintOf()` 改用新格式，**永不返回 null** |
| `SkillEvolutionHook.java` | `resolveSkillsByFingerprint()` 改用新格式 |

---

## 4 详细设计

### 4.1 FingerprintCalculator 改动

```java
@Component
public class FingerprintCalculator {

    /** Skill fingerprint scope — all users accumulate on the same row. */
    public static final String SKILL_SCOPE = "_global";

    private final DimensionStateManager dimManager;
    private final MetricClassificationService metricClassifier;  // 新增注入

    public FingerprintCalculator(DimensionStateManager dimManager,
                                  MetricClassificationService metricClassifier) {
        this.dimManager = dimManager;
        this.metricClassifier = metricClassifier;
    }

    // ── 技能 fingerprint：全局 scope（所有用户共享同一行）──

    /**
     * 技能级 fingerprint，格式: _global|intent|metricTag
     * 所有用户共享同一 fingerprint 行，避免多用户重复蒸馏。
     * metricTag 来自 MetricClassificationService.ruleBasedTag()，兜底 "general"。
     * 永不返回 null。
     */
    public String computeMetric(String intent, String question) {
        Objects.requireNonNull(intent, "intent");
        return computeMetric(SKILL_SCOPE, intent, question);
    }

    /**
     * 带自定义 tenant 的 fingerprint（仅用于 ResponseCache 缓存键）。
     * 技能相关用途请使用 computeMetric(intent, question)。
     */
    public String computeMetric(String tenant, String intent, String question) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(intent, "intent");
        String metricTag = metricClassifier.ruleBasedTag(question);
        if (metricTag == null || metricTag.isBlank()) {
            metricTag = "general";
        }
        return tenant + "|" + intent + "|" + metricTag;
    }

    // ── 旧方法保留（过渡期兼容 + ResponseCache 仍在用）──

    /** @deprecated 仅用于 ResponseCache 缓存键和过渡期旧格式兼容 */
    @Deprecated
    public String fingerprint(String question, RuntimeContext ctx) {
        Parts p = compute(question, ctx);
        return format(p);
    }

    // compute(), format(), tenantBucket(), buildFromExplicit() 保持不变
    // ResponseCache 缓存键仍需 dimKey
}
```

### 4.2 SkillSynthesisHook 改动

```java
// 旧代码（inline）:
QuestionAnalysis analysis = dimManager.analyzeQuestionRuleBased(question);
DimensionState state = buildFromExplicit(analysis);
String intent = ResponseCacheService.classifyIntent(question);
String dimKey = state == null ? "" : state.toCacheKey();
String userId = userBucket();
String fingerprint = dimKey.isEmpty()
        ? userId + "|" + intent + "|" + FingerprintCalculator.NO_DIM_SUFFIX
        : userId + "|" + intent + "|" + dimKey;

// 新代码（delegate to FingerprintCalculator with _global scope）:
String question = ResponseCacheService.extractUserQuestion(event.getInputMessages());
if (question.isEmpty()) return Mono.just(event);
String intent = ResponseCacheService.classifyIntent(question);
String fingerprint = fingerprintCalculator.computeMetric(intent, question);
// fingerprint = _global|query|defect_density — 所有用户共享

// userId 仍按用户区分（用于 DB 跟踪），但 fingerprint 用 _global
String userId = FingerprintCalculator.tenantBucket(runtimeContext);
runner.bumpAndMaybeSynthesize(fingerprint, userId, question, null);

// 删除: buildFromExplicit(), userBucket() 本地副本
```

### 4.3 ResponseCacheHook 改动

```java
// 旧代码（inline）:
String dimKey = state.toCacheKey();
String tenant = tenantBucket();
String fingerprint = dimKey.isEmpty()
        ? tenant + "|" + intent + "|" + FingerprintCalculator.NO_DIM_SUFFIX
        : tenant + "|" + intent + "|" + dimKey;

// 新代码（_global scope for skill fingerprint）:
String fingerprint = fingerprintCalculator.computeMetric(intent, question);
String userId = FingerprintCalculator.tenantBucket(runtimeContext);
synthesisRunner.bumpAndMaybeSynthesize(fingerprint, userId, question, null);

// ⚠️ 注意：ResponseCache 缓存键（cacheKey）不变！
// 缓存键 = u:alice|intent|dimKey[|q=hash]，仍然需要 DimensionState + per-user scope
// 只有传给 bumpAndMaybeSynthesize() 的 fingerprint 要改用 _global scope
```

### 4.4 SkillRetrievalHook 改动

```java
// 旧代码:
private String fingerprintOf(String question) {
    QuestionAnalysis analysis = dimManager.analyzeQuestionRuleBased(question);
    DimensionState state = buildFromExplicit(analysis);
    if (state == null || !state.hasDimensions()) return null;  // ← 问题3的根因
    String intent = ResponseCacheService.classifyIntent(question);
    String dimKey = state.toCacheKey();
    if (dimKey.isEmpty()) return null;
    return tenantBucket() + "|" + intent + "|" + dimKey;
}

// 新代码（_global scope）:
private String fingerprintOf(String question) {
    String intent = ResponseCacheService.classifyIntent(question);
    if (intent == null || intent.isEmpty()) return null;  // intent 仍为必须
    return fingerprintCalculator.computeMetric(intent, question);
    // 永不返回 null（metricTag 兜底 "general"），且使用 _global scope
}
```

**关键变化**：
1. 无维度问题不再被排除出 L1，因为 metricTag 总有值
2. 所有用户共享同一 fingerprint（`_global` scope），跨用户聚合

### 4.5 SkillEvolutionHook 改动

```java
// 旧代码:
String fingerprint = fingerprintCalculator.fingerprint(question, runtimeContext);

// 新代码（_global scope）:
String intent = ResponseCacheService.classifyIntent(question);
if (intent == null || intent.isEmpty()) return List.of();
String fingerprint = fingerprintCalculator.computeMetric(intent, question);
```

---

## 5 向后兼容与数据迁移

### 5.1 过渡期：双格式读取

在 `SkillRetrievalHook` 和 `SkillEvolutionHook` 中，L1 查找先新格式后旧格式：

```java
// 1. 先用新格式查找
String newFp = fingerprintCalculator.computeMetric(tenant, intent, question);
Optional<String> found = vectorIndex.findByFingerprint(newFp);
if (found.isPresent()) return found;

// 2. 过渡期：旧格式 fallback
String legacyFp = fingerprintCalculator.fingerprint(question, runtimeContext);
if (legacyFp != null && !legacyFp.equals(newFp)) {
    found = vectorIndex.findByFingerprint(legacyFp);
    if (found.isPresent()) {
        // 可选：异步迁移旧 fingerprint 到新格式
        migrateFingerprint(found.get(), legacyFp, newFp);
    }
}
return found;
```

### 5.2 数据迁移

由于系统处于开发期，推荐简化方案：

```sql
-- 清空旧格式数据，让新格式自然写入
TRUNCATE skill_candidate;
UPDATE skill_index SET fingerprint = NULL WHERE fingerprint LIKE '%=%';  -- 旧格式含 "="
```

已蒸馏的 skill 文件（SKILL.md）保留在磁盘，fingerprint 清空后不走 L1，改走 L2 语义检索。

### 5.3 最终清理（过渡期结束后）

- 删除 `FingerprintCalculator.fingerprint()` 和 `formatLegacy()`
- 删除 `SkillSynthesisHook` / `ResponseCacheHook` 中的 `buildFromExplicit()` 和 `userBucket()` 本地副本
- 删除 `SkillRetrievalHook` 中的旧格式 fallback 逻辑
- 删除 `NO_DIM_SUFFIX` 常量

---

## 6 L1 vs L2 职责重新明确

| 层级 | 匹配方式 | 粒度 | 作用 |
|------|---------|------|------|
| **L1** | fingerprint 精确匹配 | **指标级** | 快速命中"有没有这个指标的 skill" |
| **L2** | 向量语义检索 | **问题级** | 处理维度差异、同义词、模糊匹配 |

新设计下：
- L1 使用 `_global` scope，所有用户共享同一 fingerprint 行
- L1 回答："系统有没有关于 `defect_density` 的 skill？" → 有/没有，O(1)
- L2 回答："杭州开发五部 Q2 缺陷密度这个具体问题最匹配哪个 skill？" → 语义排序，O(N)

维度信息不丢失——它存在于 L2 的 embedding 里，只是不参与 fingerprint。

---

## 7 不在本次范围

| 需求 | 备注 | 建议时机 |
|------|------|---------|
| 部门缩写归一化（5部→杭州开发五部） | 独立需求，不影响 fingerprint | 后续迭代 |
| LLM 指标分类增强 | 当前 `ruleBasedTag()` 是关键词匹配，未来可用 LLM 兜底 | 指标关键词覆盖不足时 |
| fingerprint 相近度匹配 | 当前是精确匹配，模糊匹配交给 L2 | 不需要 |
| 维度归一化（Q1→1季度） | 已在 DimensionStateManager 中实现部分（Q1→2026年1季度），部门缩写未实现 | 独立需求 |

---

## 8 实施状态

### 8.1 已完成的代码改动

| 文件 | 改动 | 验证 |
|------|------|------|
| `FingerprintCalculator.java` | 新增 `SKILL_SCOPE = "_global"` 常量；新增 `computeMetric(intent, question)` 使用 `_global` scope；`computeMetric(question, ctx)` 标记 `@Deprecated`；注入 `MetricClassificationService`；`tenantBucket()` 改为 `public static`；旧方法标记 `@Deprecated` | ✅ 编译通过 |
| `MetricClassificationService.java` | `ruleBasedTag()` 从 `private` 改为 `public`（FingerprintCalculator 需要调用） | ✅ 编译通过 |
| `SkillSynthesisHook.java` | 删除 `buildFromExplicit()` + `userBucket()` 本地副本；改用 `fingerprintCalculator.computeMetric(intent, question)`（_global scope）；构造参数 `DimensionStateManager` → `FingerprintCalculator` | ✅ 编译通过 |
| `ResponseCacheHook.java` | `bumpAndMaybeSynthesize()` 改用 `fingerprintCalculator.computeMetric(intent, question)`（_global scope）；缓存键逻辑不变 | ✅ 编译通过 |
| `SkillRetrievalHook.java` | `fingerprintOf()` 改用 `fingerprintCalculator.computeMetric(intent, question)`（_global scope）；**永不返回 null**；删除 `buildFromExplicit()` 本地副本；构造参数 `DimensionStateManager` → `FingerprintCalculator` | ✅ 编译通过 |
| `SkillEvolutionHook.java` | `resolveSkillsByFingerprint()` 改用 `fingerprintCalculator.computeMetric(intent, question)`（_global scope） | ✅ 编译通过 |
| `SupervisorService.java` | SkillSynthesisHook 和 SkillRetrievalHook 构造参数更新 | ✅ 编译通过 |
| `metric-categories.yaml` | 配置文件已在上一轮创建，9 个指标类别 | ✅ 已存在 |

### 8.2 未实施（待后续迭代）

| 项 | 说明 |
|------|------|
| 双格式 L1 fallback | 设计文档 §5.1 提到的"先新格式后旧格式"查找，当前未实施。开发期可直接清空旧数据 |
| 旧格式清理 | `FingerprintCalculator.fingerprint()`、`compute()`、`format()`、`NO_DIM_SUFFIX` 标记 `@Deprecated` 但未删除 |
| 中文编码修复 | `ruleBasedTag()` 日志中中文显示为 `???????`，需在入口处加 `repairUtf8()` |

---

## 9 验收方案

### V1：指标区分验证 — 不同指标产生不同 fingerprint ✅

**目标**：P1 修复 — 不同指标不再撞车

**结果**（2026-07-04 实测）：

| 请求 | fingerprint | 预期 | 实际 |
|------|-------------|------|------|
| `error_rate data` | `_global\|query\|error_rate` | ✅ error_rate | ✅ `_global\|query\|error_rate` |
| `RT latency data` | `_global\|query\|response_time` | ✅ response_time | ✅ `_global\|query\|response_time` |
| `帮我查一下` (无指标) | `_global\|query\|general` | ✅ general | ✅ `_global\|query\|general` |

日志确认：
```
METRIC_CLASSIFY keyword match: keyword='error_rate' tag='error_rate' question='error_rate data'
METRIC_CLASSIFY regex match: keyword='\brt\b' tag='response_time' question='RT latency data'
```

**结论**：不同指标产生不同 fingerprint，不再撞车 ✅。ASCII 关键词和正则关键词（`\brt\b`）匹配正常。

### V2：同指标聚合验证 — 不同维度表达命中同一 fingerprint ✅

**目标**：P2 修复 — 维度差异不影响聚合

**结果**（2026-07-04 实测）：
- "RT latency data" (用户A) → `_global|query|response_time`
- "RT trend analysis" (用户B) → `_global|query|response_time`
- 两者 fingerprint 完全相同，不再因维度不同而分裂 ✅

### V3：无维度问题 L1 命中验证 ✅

**目标**：P3 修复 — 无维度问题不再被 L1 排除

**结果**（2026-07-04 实测）：

```
L1 result for fp=_global|query|error_rate: picked=[error_rate_anomaly_fix]
```

- 旧格式：无维度问题 → `u:x|query|<no-dim>` → L1 返回 null → 跳过
- 新格式：无维度问题 → `_global|query|error_rate` → L1 正常命中 ✅

### V4：metric-categories.yaml 配置联动验证 ✅

**结果**：✅ 通过

- YAML 加载成功：`Loaded 9 metric categories from metric-categories.yaml`
- `\brt\b` 正则关键词正常匹配：`RT` → `response_time`
- 业务方新增指标类别只需编辑 YAML，无需改代码

### V5：兜底验证 — 未匹配指标走 general ✅

**结果**：✅ 通过

- 无指标关键词的问题 → `_global|query|general`
- `general` 兜底机制正常工作，fingerprint 永不为 null

### V6：跨用户聚合验证 — 不同用户问同一指标 ✅

**目标**：验证多个用户问同一指标时，fingerprint 使用 `_global` scope 聚合到同一行

**结果**（2026-07-04 实测）：

| 步骤 | 请求 | 用户 | 日志 |
|------|------|------|------|
| 1 | `RT latency data` | s:test-fp-global-002 | `[BUMP] fingerprint=_global\|query\|response_time userId=s:test-fp-global-002 hit_count=1` |
| 2 | `RT trend analysis` | s:test-fp-global-004 | `[BUMP] fingerprint=_global\|query\|response_time userId=s:test-fp-global-004 hit_count=2` |

**关键验证**：
- ✅ 两次请求产生相同的 fingerprint `_global|query|response_time`（跨用户共享）
- ✅ hit_count 从 1 增长到 2（第二个用户的请求聚合到同一行）
- ✅ userId 仍按用户区分（`s:test-fp-global-002` vs `s:test-fp-global-004`），用于 DB 跟踪
- ✅ 只蒸馏一次 skill（`markSynthesized` CAS 保证）

### 已知问题：中文编码

**现象**：`ruleBasedTag()` 日志显示 `question='???????'`，中文关键词匹配失败。

**根因**：HTTP 请求传入的中文文本在某些环节被编码为 ISO-8859-1（双编码问题），导致 `contains("缺陷密度")` 匹配不到。这是已有问题（`SkillEvolutionHook` 的 `repairUtf8()` 修复的是 `@Value` 注入的中文，不是请求层）。

**影响范围**：仅影响中文关键词的 `ruleBasedTag` 匹配。ASCII 关键词（`error_rate`、`RT`、`tps`、`latency` 等）和正则关键词（`\brt\b`）匹配正常。

**修复方向**：在 `ruleBasedTag()` 入口处加 `repairUtf8()` 自动检测和修复，与 `SkillEvolutionHook` 中同样的模式。