# Skill 直接执行缺参反问方案（补参后继续执行）

> 2026/10/15 新需求。归属 `docs/history/skill-direct-exec-plan.md` 的增量方案；
> 实施后在该文件补实施记录。

## 1. 背景

直接执行（绕过 LLM）现行拍板口径：**必填参数缺失 → 回退 LLM**（`DirectSkillExecutor.plan()`
L221-227：required 参数不在 params 里即 `return null`）。内网 LLM 基本不可用，回退等于不可用。

实际场景：用户问「Q2-1 达标率多少」——命中 skill、脚本 `q2_1_metrics_by_dept_version`
的 `dept` 必填，但问题里没说部门。此时应**反问用户补参**，用户回复后**继续直接执行**，
全程不碰 LLM。

## 2. 现状锚点（全部复用，不重造）

| 环节 | 位置 | 说明 |
|---|---|---|
| 缺参检测点 | `DirectSkillExecutor.plan()` 必填循环 | 现在直接 `return null`，改为收集缺失清单 |
| 必填名单 | `parseNames(paramsSchema, requiredOnly=true)` | 来自 script_registry.params_schema |
| 二次确认先例 1 | `DimensionStateManager.consumeClarification / startClarificationIfAmbiguous` | 维度歧义反问，两链路 stream() 开头消费 |
| 二次确认先例 2 | `SkillFlowChatRouter.pending`（Map + TTL 5min） | 长任务确认，key=`userId:conversationId` |
| 回答文本解析 | `DimensionStateManager.previewState(q, prev)` | 只读预览，别名/两遍分段抽取都走这层 |
| 维度键中文标签 | `DimensionKeyConfigRepository.findAllEnabled()` 的 `label` | 内置 8 键有种子 label；字典键用户填的 |
| 短路发送模式 | 两链路 stream() 的 clarifyPrompt 块 | sendText → complete → 显式 cleanup.run() |
| 维度键→提取体取值 | `DirectSkillExecutor.valuesFor()` | 含 extraDimensions 动态键分支 |

## 3. 拍板口径

| # | 决策 | 说明 |
|---|---|---|
| 1 | 只对**在 param_mapping 内**的缺失必填参数反问 | 有维度语义、能出中文标签、回答可解析；不在 mapping 内的必填参数（语义未知）维持回退 LLM |
| 2 | version/vestQuarter 缺失仍用当前在建版本补默认 | 不反问（现行口径不变） |
| 3 | **一次只问一个参数** | 多参数同时问，用户一句回答无法可靠拆分归属 |
| 4 | 回答解析不出对应维度值 → 丢弃 pending 走正常链路 | 用户可能问了别的事；宁可回退不可误执行 |
| 5 | pending TTL 5 分钟，key=`userId:conversationId` | 与 SkillFlowChatRouter 同款 |
| 6 | 反问轮和执行轮都正常写维度状态 | dept 等跨轮继承不受影响；与执行短路轮同语义 |
| 7 | 反问轮零 token、不启 agent | 与歧义反问轮同款短路 |

## 4. 改动设计

### 4.1 `DirectSkillExecutor`（核心）

**计划记录扩展**（尾部追加，旧构造器重载保留）：

```java
public record DirectExecutionPlan(
        DimensionExtraction extraction,
        String skillName,
        String scriptId,
        Map<String, Object> params,
        List<String> missingAsk) {          // 新增: 待反问的维度键 label 列表(≤1 个)
    /** Legacy 4-arg shape (no missingAsk) for existing call sites/tests. */
    public DirectExecutionPlan(DimensionExtraction extraction, String skillName,
                               String scriptId, Map<String, Object> params) {
        this(extraction, skillName, scriptId, params, List.of());
    }
    public boolean needsAsk() { return !missingAsk.isEmpty(); }
}
```

**plan() 签名加会话键**（pending 需要定位会话）：

```java
public DirectExecutionPlan plan(String question, String userId, DimensionState previousState) {
    return plan(question, userId, previousState, null);   // 3参重载保留: 无会话键 = 不启用 pending
}
public DirectExecutionPlan plan(String question, String userId,
                                DimensionState previousState, String conversationId) { ... }
```

**判定链变化**（其余不变）：

1. **pending 消费优先**（入口处，`conversationId != null` 时）：有 pending → 本轮 question
   视为补参回答。用 `dimensionStateManager.previewState(question, previousState)` +
   `valuesFor()` 解析**第一个缺失维度键**的值：
   - 非空 → 并入已填 params；还有剩余缺失 → 生成下一个 missingAsk（继续反问）；
     无剩余 → 返回完整执行计划（跳过 skill 匹配，skill/scriptId/mapping 都在 pending 里）
   - 解析不出 → 清除 pending，继续正常判定链（口径 4）
2. **正常链路的缺参分支**：必填循环里收集缺失。循环结束后：
   - 缺失参数中存在**不在 param_mapping 内**的 → 维持 `return null`（口径 1，回退 LLM）
   - 缺失参数全部在 mapping 内 → 取第一个，label 查
     `dimensionKeyRepoProvider` 字典（失败/查不到回退键名本身），存 pending
     （`skill/scriptId/mapping/已填 params/缺失清单`，key=`userId:conversationId`），
     返回 `new DirectExecutionPlan(extraction, topName, top.scriptId(), params, List.of(label))`
   - 注意：version/vestQuarter 缺失已被 `defaultTimeValue` 补值，天然不进缺失清单（口径 2）

**pending 存储**：

```java
private record PendingDirectExec(String skillName, String scriptId,
        Map<String, String> mapping, Map<String, Object> filledParams,
        List<String> missingKeys, long createdAt) {}
private final ConcurrentHashMap<String, PendingDirectExec> pending = new ConcurrentHashMap<>();
private static final long PENDING_TTL_MS = 5 * 60 * 1000L;
```

取值时惰性清理过期条目（对比 createdAt），不引入定时任务。

**pending 消费轮的权限**：首次 plan 时已过 canUse；补参轮不重复校验
（skill/scriptId 均来自 pending 原样透传，攻击者无法注入）。

### 4.2 调用方（两条链路同款改法）

`V2ChatStreamServiceImpl.stream()` 与 `ChatStreamServiceImpl.stream()` 的直接执行短路块：

```java
if (plan != null) {
    dimensionStateManager.processQuestionInContext(ctx, text);   // 反问轮/执行轮都写状态(口径 6)
    if (plan.needsAsk()) {
        // 反问轮: 短路发文本, 同歧义反问轮模式 (sendText → complete → 显式 cleanup)
        strategy.sendText(streamCtx, TextPayload.chunk(askPrompt(plan), true));
        emitter.complete();
        cleanup.run();
        return emitter;
    }
    // 执行轮照旧: Mono.fromRunnable(runDirectExecution...)
    ...
}
```

**反问文案**（两个流服务各一份私有方法，或抽公共 util）：

```
已匹配技能「{skillName}」，还需要补充：{label}。请直接回复，如：{示例}
```

示例值按维度键给固定提示（deptName→`杭州开发一部`；groupName→`xx组`；其余用 label 本身）。
`.needsAsk()` 轮不落问答库之外的东西——answerContent 不追加（走 sendText 一次性文本，
与 clarifyPrompt 短路轮一致，不进 saveAnswerIntoDB）。

> 对照：歧义反问轮也是 sendText 后直接 complete，不落库。补参轮的最终回答（执行成功后）
> 正常落库。

### 4.3 文档

- `docs/history/skill-direct-exec-plan.md` — §「拍板口径」追加：必填缺失回退 LLM
  **改为**「mapping 内缺失 → 反问补参；mapping 外缺失 → 仍回退 LLM」
- 本文件补实施记录引用

## 5. 不做的事（明确排除）

- 不做多参数一次性反问（口径 3；逐个问最坏 N 轮，实际脚本必填参数 ≤2-3 个）
- 不做「回答+新问题混合」的意图识别（解析不出即放弃，口径 4，保持确定性）
- 不做 pending 的持久化（JVM 重启丢失，与维度状态的既有缺口同语义，不新增缺口）
- 不改 `SkillCandidateSelector` / 匹配逻辑（反问轮跳过匹配，直接用 pending 里的 top-1）

## 6. 测试与验证

**单测**（`DirectSkillExecutorTest` 存量 23 个全过，3 参重载行为不变）：

1. 必填缺失且在 mapping 内 → 返回 needsAsk 计划，missingAsk=[label]，pending 已存
2. 必填缺失但不在 mapping 内 → 返回 null（回退 LLM，口径 1）
3. version 缺失 → 补当前在建版本，不反问（口径 2）
4. 补参轮：pending 存在 + 回答解析出 dept → 返回完整执行计划，params 合并正确
5. 补参轮：回答解析不出 → pending 清除，走正常判定链
6. 补参轮还有第二个缺失 → 返回第二个 needsAsk（逐个问，口径 3）
7. pending TTL 过期 → 惰性清除，走正常判定链
8. 3 参重载（无 conversationId）→ 缺参行为与旧版一致（null）
9. 反问轮的 label：字典键用 label；字典读失败回退键名

**E2E（内网，两链路各验一遍）**：

1. 问「Q2-1 达标率多少」（缺部门）→ 秒回反问文本，无 LLM 调用
2. 回复「杭州开发二部」→ 直接执行脚本返回 markdown 表；`/v2/ai/chat` 与 `/ai/chat` 都验
3. 反问后回复「帮我写个周报」→ 丢弃 pending，走正常目录链路
4. 反问后 5 分钟不回复再问 → pending 过期，正常判定
5. 脚本有两个非时间必填参数 → 两轮反问依次补齐后执行

## 7. 风险与取舍

| # | 风险 | 处置 |
|---|---|---|
| 1 | 用户回答被误解析为维度值（如反问部门后回答「北京的项目总数」→ dept=北京） | 口径 4 只挡"完全解析不出"；误解析属于接收了合理维度的值，脚本侧兜底（空结果/报错直接透出），概率与 LLM 路径追问等价 |
| 2 | pending 与维度歧义反问（consumeClarification）叠加 | consumeClarification 在 stream() 更早处消费且两者 key 独立；反问文案不同不会混淆；同轮双 pending 出现概率极低，记录在案 |
| 3 | 补参轮 answer 与首轮问题拼成的语义漂移 | params 合并只按维度键，不做语义拼接；首轮已填参数（如 version）原样保留 |
| 4 | 多用户并发同会话 pending 覆盖 | putIfAbsent/直接覆盖均可（同会话并发本身被 inFlight 拒绝），不新增处理 |

## 8. 实施记录（2026/10/15）

- `DirectSkillExecutor`：
  - `DirectExecutionPlan` 尾部追加 `missingAsk`（≤1 个维度键 label）+ `needsAsk()`，
    旧 4 参构造器重载保留（`List.of()`）
  - `plan()` 加 4 参重载（`question, userId, previousState, conversationId`）；
    3 参旧签名委托 conversationId=null（缺参维持回退 LLM，存量行为不变）
  - 必填循环收集 `missingKeys`（dedup 后的 mapping 维度键）；非空 → 存 pending 并返回
    反问计划；`dimKey == null || conversationId == null` → 维持 `return null`（口径 1）
  - `consumePending()`：TTL 惰性清理 → previewState + 词表解析第一个缺失键 →
    解析不出丢弃 pending 返回 null（口径 4）；剩余缺失继续反问（口径 3）；
    补齐返回完整执行计划（skill/scriptId 均来自 pending 原样透传，补参轮不重跑匹配与权限）
  - `labelFor()`：字典 label（timeRange 后缀键剥后缀查），失败回退键名；
  - 静态 `askPrompt()` + `ASK_EXAMPLES` 固定示例（部门→杭州开发一部等）
- 两条链路（`V2ChatStreamServiceImpl` + `ChatStreamServiceImpl`）：短路块内
  `processQuestionInContext` 之后加 needsAsk 分支（sendText → complete → 显式
  cleanup.run()，零 token；v2 用 log 前缀区分链路）
- 单测：`DirectSkillExecutorTest` 35 个全过（新增 7 个覆盖 §6 清单 1/2/3/4/5/6/7/9）
- E2E（内网两链路）待验：反问秒回无 LLM → 补参后执行出表；答非所问丢弃 pending；
  5 分钟过期；两参数两轮反问
- 2026/10/16 补充：version/vestQuarter 缺省从**只取第一个在建版本**改为**全量在建版本列表**
  （`defaultTimeValue` → `defaultTimeValues`）。在建版本有多个时只传第一个会漏查其他版本的行
  → 无数据；脚本侧 version 本就支持字符串数组（SQL IN 聚合），与 LLM 路径注入全部在建版本
  同语义。单测 `missingVersionFilledWithCurrentInBuildVersion` 同步改为断言全量列表。
