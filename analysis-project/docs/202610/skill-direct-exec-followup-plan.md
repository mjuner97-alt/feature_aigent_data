# Skill 直接执行追问轮方案 v2（走 LLM 链路，Java 只做上下文补给）

> 日期：2026-10-19（v2，推翻同日 v1 的确定性追问执行方案）
> 状态：方案待拍板
> 关联：`docs/202610/skill-direct-exec-plan.md`（直接执行，§9/§10 已实施）、`docs/202610/skill-retrieval-logic.md`（检索两阶段）
> v1 撤销原因（用户拍板）：追问轮确定性执行难度太大、太复杂且不准确（追问语义千变万化：换维度/换对比对象/换指标口径/否定前问……规则覆盖不全），**第二次追问回归 LLM 链路**，由 LLM 凭语境理解。

## 1. 问题与目标

直接执行首轮（「杭州开发二部7月版本 Q2-1 达标率」）秒回后，用户追问「9月的呢」→ 现状 selector 无佐证不出候选 → 目录为空 + 回退 LLM，但 LLM 拿不到任何上一轮执行上下文（技能名、参数），弱模型无从恢复。

**目标（v2 口径）**：追问轮走原 LLM 链路（目录 + load_skill + script_exec），Java 不做任何执行决策，只把「上一轮直接执行了什么、用了什么参数」**补给给 LLM**，让 LLM 自己理解追问并复用参数。

## 2. 设计总览：快照 + 两个补给点，零执行逻辑

```
首轮「杭州开发二部7月版本 Q2-1 达标率」
  → DirectSkillExecutor.plan() 命中 → 直接执行 ✅
  → plan() 内记录 LastDirectExec 快照 (skillName, scriptId, params)     ← 唯一新增状态
  → runDirectExecution 成功后回填 resultDigest（stdout 摘要，截断）      ← 结果补给
第二问「9月的呢」
  → plan(): selector 空 → null → 回退 LLM（现状，不变）
  → 补给点① 目录: SkillVectorIndexVisibilityFilter 把快照 skill 并入候选集
  → 补给点② prompt: LastDirectExecContextHook 注入 <last_direct_exec> 块
  → LLM 理解追问: 复用 deptName、替换 versionPlan → load_skill → script_exec → 回答
```

**为什么必须补结果摘要**：直接执行短路不启 agent——首轮的问题与回答**不在 LLM 消息历史里**（ResponseCache/memory ledger/会话历史全不经过 LLM），第二轮的 LLM 是「失忆」状态。没有摘要：追问回答残缺（说不出「较 7 月的 85%…」）、对比类追问（「9月和7月比呢」）死路。

关键取舍：
- **不判定追问形态**——v1 的追问资格守卫/merge 规则全部废除。「是不是追问、继承哪些维度」交给 LLM 判断；快照存在就注入，正常问题注入也无害（块里写明「新问题则忽略」）。
- **不改 DimensionStateManager**（v1 拍板点 D2/G2 修复降级为可选项，见 §5 D1）。
- **两条 Chat 链路 stream() 零改动**——快照记录在 plan() 内部（4 参签名已具备），注入在 hook/filter 层。

## 3. 改动设计

### 3.1 快照：`DirectSkillExecutor` 内 LastDirectExec（仿 pending 模式）

```java
/** 上次直接执行快照: key=userId:conversationId, TTL 30min(拍板点 D3), 惰性清理。 */
record LastDirectExec(String question, String skillName, String scriptId,
                      Map<String, Object> params, String resultDigest, long createdAt) {}

private final ConcurrentHashMap<String, LastDirectExec> lastExec = new ConcurrentHashMap<>();
```

- **记录时机分两步**：
  1. plan() 返回**可执行**计划（missingAsk 为空）时记 question（本轮问题原文）+ skillName + scriptId + params（正常路径、`consumePending` 补参完成路径两处；反问轮不记）——此刻保证 hint 块里参数始终可用（即使脚本后续失败，用户重试追问仍有上下文）；
  2. `runDirectExecution` 成功后（extractStdout 拿到 stdout）调 `recordResult(userId, conversationId, stdout)` 回填 resultDigest 并**刷新 createdAt**（TTL 从执行时刻起算，比 plan 时刻更准）——两条 Chat 链路各加一行调用，是本方案对 stream() 的唯一改动。
- **resultDigest 截断规则**（确定性，无 LLM）：`ScriptExecOutputExtractor.extractStdout` 后取前 2000 字符（拍板点 D2b），超出追加 `…(已截断)`；markdown 表格前缀截断保住表头+前几行，正是追问轮最需要的部分。
- **只存 question + skillName + scriptId + 已填 params + 结果摘要**——不存 extraction/mapping（v2 不重算参数，把上轮 params 原样给 LLM 参考最直观）。question 原文是 hint 块里唯一带**指标语义**的字段（「Q2-1 达标率」还是「缺陷密度」全靠它），params 只有维度值。
- **公开查询口**：`public LastDirectExec lastExec(String userId, String conversationId)`（含 TTL 过期判断 + 惰性清理），供 filter/hook 消费；conversationId 为 null 不记不查（与 pending 同规则）。
- 纯内存，JVM 重启丢失——与维度状态同语义，不新增缺口。
- 已知限制：追问轮由 LLM 执行 script_exec 时**不刷新快照**（直接执行路径才知道），链式第三问「10月的呢」时快照仍是首轮的。弱模型场景下 LLM 回答里通常带出参数，问题不大；若实测不够再补「script_exec 调用观测 → 刷快照」（需 hook 介入，本期不做，见 §5 D5）。

### 3.2 补给点①：目录并入快照 skill（`SkillVectorIndexVisibilityFilter.filter()`）

selector 结果为空（或快照 skill 不在结果中）时，把快照 skill 对应的 AgentSkill **追加进候选集**：

- filter 末尾（`return result` 前）加：

```java
LastDirectExec last = lastExecStore.lookup(ctx);   // userId+conversationId 从 ctx 取
if (last != null) {
    AgentSkill skill = byName.get(last.skillName());
    if (skill != null && result.stream().noneMatch(s -> s.getName().equals(last.skillName()))) {
        result.add(skill);
    }
}
```

- 追问轮目录非空 → LLM 有 `load_skill_through_path` 可走（原链路恢复），而不是空目录直接裸奔工具层。
- **不改旁路语义**：bypass 命中仍 `return List.of()` 在先（§8 拍板①，分析类问题不走 skill 目录）；但 hint 块（3.3）仍注入，LLM 在工具层仍可自行调 script_exec——旁路拦目录不拦上下文，边界与现状一致。
- 注入方式：filter 构造器尾部追加 `LastDirectExecStore` 参数（ObjectProvider 可选，null 时行为不变，存量构造器重载保留）。
- ⚠️ 前置确认点：ctx 中是否已有 conversationId 可取（`lastQuestion` 同源，`buildRuntimeContext` 处 put）；没有则在 `V2ChatStreamServiceImpl`/`ChatStreamServiceImpl` 的 buildRuntimeContext 补一行 `ctx.put("conversationId", ...)`。

### 3.3 补给点②：prompt 注入 `<last_direct_exec>` 块（新 hook，仿 VersionPlanKnowledgeHook）

新增 `v2/hooks/LastDirectExecContextHook implements Hook`：

- `onEvent` 处理 `PreCallEvent`，`event.appendSystemContent(block)`（与 VersionPlanKnowledgeHook.java:147-161 同机制），priority -45 附近；注入失败 catch 住不阻塞请求。
- 注入内容（把结构化事实给足，指令一句话）：

```
<last_direct_exec>
上一轮用户问题及直接执行情况（用户追问时复用）：
question: 杭州开发二部7月版本 Q2-1 达标率多少
skill: q2_1_by_dept_version_metrics
script_id: q2_1_metrics_by_dept_version
params: {"deptName":"杭州开发二部","versionPlan":"2026年7月份版本"}
result: | 部门 | 版本 | 达标率 |\n|---|---|---|\n| 杭州开发二部 | 2026年7月份版本 | 85% |…
若用户本轮是追问（只变更部分维度，如时间、部门），复用未变更参数、仅替换本轮提及的维度，
调用 script_exec(script_id=上面值, params=变更后参数) 执行并汇报结果；
对比类追问可直接引用 result 中的上一轮数据；
若用户问了新问题，忽略本块。
</last_direct_exec>
```

- resultDigest 为空（脚本还没跑完/失败）→ 不输出 result 行，其余照常；快照不存在 → 不注入（零成本）。
- 开关：`harness.a2a.skill-direct-exec.followup-context.enabled=true`（默认跟 direct-exec 开关联动或独立，拍板点 D4）。
- 注册：`V2SkillConfig`/`HarnessA2aRunnerV2` 挂 hook（同 VersionPlanKnowledgeHook 挂法），依赖 DirectSkillExecutor 查询口。
- 消费方是 LLM：params 用首轮**已填好的实参**（含当前在建版本缺省补全后的值），LLM 照抄改一个键即可，不需要它重算任何缺省——弱模型友好。

### 3.5 快照兜底补缺（正常路径窄修，修 G2 在交替场景的暴露）

**问题**：G2（`inheritDimensions` TIME 层丢部门）在「直接执行 → 关键词命中直接执行」的交替场景高频暴露——T3「10月达标率呢」走正常直接执行路径，extraction.deptName=[] → 必填缺失 → 反问「部门」，体验断裂。

**窄修**（区别于 v1 撤销的全量 merge）：plan() 正常路径填参后、必填缺失分支**之前**插一步：

```java
// 快照兜底: 只补缺、不覆盖, 且仅限同一个 skill (param 名才对得上)
LastDirectExec last = lastExec(userId, conversationId);
if (last != null && last.skillName().equals(topName)) {
    for (String req : required) {
        if (!params.containsKey(req) && last.params().containsKey(req)) {
            params.put(req, last.params().get(req));
        }
    }
}
```

- **只补 required 且缺失的参数**——本轮提及的维度（已有值）绝不被快照覆盖；与 v1 merge 的「非空覆盖/空继承」相比，这是纯补缺，不改变任何已提取值。
- **仅同 skill**——不同 skill 的 param 名空间不同，跨 skill 翻译需要映射推导，超出窄修范围（那种场景维持现状：反问/回退）。
- 反问文案场景（§10 pending 反问后用户补参）不受影响——兜底发生在反问判定之前，能兜住就不反问了。
- 存量行为变化面：仅「上一轮同 skill 直接执行过 + 本轮关键词命中 + 本轮必填维度未提及」这一种组合，从「反问」变「继承上轮值直接执行」——正是期望行为。
- 与 DimensionStateManager 的 ctx 状态无关（G2 本体不动，拍板点 D1 维持不修）。

### 3.6 明确不做（v2 撤销项）

| v1 组件 | 处置 |
|---|---|
| `planFollowUp()` 追问路径 | 删——执行决策回归 LLM |
| `mergeExtraction()` 提取体 merge | 删 |
| 追问资格守卫四条（维度键/长度/排除词/mapping 值域） | 删——LLM 理解语境，无误劫持问题 |
| 正常路径提取源切换 | 删——plan() 提取逻辑零改动 |
| 动态键 extraDimensions merge | 删——params 原样给 LLM，天然含动态键 |

## 4. 场景推演

首轮执行成功，快照 = {q2_1 skill, script, params={dept:二部, version:7月版本}}。

| 追问 | 链路行为 |
|---|---|
| 9月的呢 | 目录含快照 skill + hint 块 → LLM 复用 dept、换 version → script_exec → 回答 |
| 那缺陷密度呢 | selector 显式/关键词命中其他 skill → 正常路由（hint 块也在，LLM 自行取舍） |
| 9月趋势分析呢 | 旁路 → 目录空，hint 仍在 → LLM 工具层决策（与现状旁路语义一致） |
| 帮我写个周报 | selector 空 → 目录仅多一个快照 skill + hint；LLM 按块内指令忽略 → 正常回答 |
| 随便聊（无 record） | 零注入零并入，与现状逐字节一致 |

误劫持风险消失：最坏情况是目录多一项 + prompt 多 6 行，去留由 LLM 判断。

### 4.1 多轮交替场景（直接执行 ↔ LLM 逐轮交替）

| 轮次 | 问法 | 链路 | 快照 | 说明 |
|---|---|---|---|---|
| T1 | 二部7月达标率 | 直接执行 ✅ | 记 T1 | 秒回 |
| T2 | 9月的呢 | selector 空 → LLM（hint=T1） | 不刷新（D5） | LLM 改 version 执行 ✅ |
| T3 | 10月达标率呢 | **关键词命中 → 直接执行** | 执行后刷成 T3 | ⚠️ G2 复现：TIME 层丢部门 → 反问部门——**快照兜底（§3.5）修复** |
| T4 | 10月和9月比呢 | selector 空 → LLM | — | hint=T3 快照 + 对话历史含 T2 问答（LLM 轮启了 agent，问答进历史），两套上下文互补 ✅ |

两条互补规律：
- **快照** = 最近一次**直接执行**轮的 question/params/result（直接执行轮刷新，LLM 轮不刷）；
- **对话历史** = LLM 轮的问答（agent 启动即持久化），直接执行轮的问答不在其中（这正是快照存在的原因）。

裸追问（「10月的呢」）在任何轮次都走 LLM（v2 口径，拍板已定）；「交替中不走 LLM」仅当该轮关键词命中 selector 时成立。

## 5. 拍板点清单

| # | 决策点 | 建议 | 备选 |
|---|---|---|---|
| D1 | G2 修复（`inheritDimensions` TIME 层继承下级维度，enriched question 自带部门） | **不修**（弱模型靠 hint 块即可；修核心继承语义回归面大）。若实测追问仍丢部门再立项 | 修（DimensionStateManager case TIME 补继承，LLM 路径同步受益，需回归单测） |
| D2 | 快照记录时机 | plan() 返回可执行计划即记（脚本失败也留快照，重试友好）；resultDigest 由 runDirectExecution 成功后回填 | 执行成功后一次性记录（hint 块在脚本执行期间不完整，不值） |
| D2b | resultDigest 截断长度 | 2000 字符（前缀截断，保住 markdown 表头+前几行） | 1000 / 4000 |
| D3 | 快照 TTL | 30min | 10min / 跟 pending 5min 对齐 |
| D4 | hint 开关 | 独立 `followup-context.enabled`，默认 true（direct-exec 开启时才有快照，天然收敛） | 无开关 |
| D5 | LLM 轮执行 script_exec 是否刷快照 | 不刷（已知限制，§3.1；LLM 轮问答进对话历史互补，§4.1） | ScriptExecTool 加观测回调（复杂化，不做） |
| D6 | ctx 补 conversationId | 确认后按需补一行 | — |
| D9 | 快照兜底补缺（§3.5，同 skill 必填缺失时继承上轮值） | 开（修 G2 在交替场景的暴露，变化面只有一种组合） | 关（维持反问部门） |

## 6. 改动清单

- `v2/skills/DirectSkillExecutor.java`：LastDirectExec record + map + `recordLastExec()`（两处 return 前，含 question 原文）+ `recordResult()`（digest 回填 + TTL 刷新）+ `lastExec()` 查询口 + 快照兜底补缺（§3.5，正常路径必填缺失分支前插一步）；**plan() 判定链除兜底一步外零改动**
- `v2/hooks/LastDirectExecContextHook.java`（新增，仿 VersionPlanKnowledgeHook）
- `v2/skills/SkillVectorIndexVisibilityFilter.java`：构造器尾部追加可选快照源 + filter 末尾并入逻辑
- `v2/config/V2SkillConfig.java`：hook/filter 装配 + 开关
- `v2/service/impl/V2ChatStreamServiceImpl.java` / `ChatStreamServiceImpl.java`：`runDirectExecution` 成功路径各加**一行** `directSkillExecutor.recordResult(...)`（本方案对两条链路的全部改动）
- `application.properties`：`harness.a2a.skill-direct-exec.followup-context.enabled=true`
- 测试：`DirectSkillExecutorTest` 补快照记录/回填/TTL/3 参签名不记 用例；filter 并入用例（快照在/不在/已在候选/旁路置空）；hook 注入格式与开关关闭不注入、digest 空缺 result 行
- **DimensionStateManager、SkillCandidateSelector：零改动**；两条 Chat 链路除 recordResult 一行外零改动

## 7. 测试要点

1. 快照：正常路径执行后可查；补参完成轮记录；反问轮不记录；recordResult 回填 digest 并刷新 TTL；digest 截断（2000 字符 + 省略标记）；TTL 过期返回 null；conversationId null 不记不查；3 参旧签名行为不变（存量 35+ 用例全过是硬门槛）。
2. 目录补给：selector 空 + 快照存在 → 候选含快照 skill；selector 已命中同 skill 不重复；旁路命中仍空目录；无快照行为与现状一致。
2b. 快照兜底（§3.5）：同 skill + 必填缺失 → 补上轮值执行；同 skill + 本轮已提及 → 不覆盖；异 skill 缺失 → 维持反问/回退；快照过期 → 维持反问/回退。
3. hint 注入：快照存在 → 块内容含 question/skill/script_id/params/result 五行；digest 缺失 → 无 result 行；无快照不注入；开关关不注入；注入异常不阻塞请求。
4. E2E（内网，LLM 可用前提下）：首轮直接执行 → 「9月的呢」→ LLM 走 load_skill + script_exec 复用部门换版本 → 结果正确且带「较 7 月…」对比；「9月和7月比呢」直接引用 result 回答；「帮我写个周报」不受 hint 影响。

## 8. 风险与已知取舍

| # | 风险 | 处置 |
|---|---|---|
| 1 | 追问轮依赖 LLM 可用性（内网 LLM 不可用时追问轮失败） | 用户已拍板接受：追问理解本就该 LLM 做，规则化不准确；首轮直接执行不受影响 |
| 2 | 弱模型忽略 hint/复用错参数 | params 给的是实参 JSON + 明确指令；失败即原链路报错，不会错账 |
| 3 | 链式追问快照不刷新（D5） | 记录在案；实测不够再上观测回调 |
| 4 | hint 块占用 prompt 长度 | 参数行固定 + result 摘要截断 2000 字符封顶，总量可控（拍板点 D2b） |
| 5 | 旁路词命中时 hint 仍注入 | 旁路语义是「不走 skill 目录」，不承诺「看不见上下文」；LLM 在工具层自决 |
