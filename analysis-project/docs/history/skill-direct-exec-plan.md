# Skill 直接执行工程化（绕过 LLM）实施方案

> 日期：2026-10-13
> 状态：方案已拍板，实施中
> 关联：`docs/history/skill-retrieval-logic.md`（检索链路现状）、`docs/history/dimension-alias-config-plan.md`（维度解析）

## Context

内网 LLM 基本不可用，需要把"执行 skill"从 LLM ReAct 链路改为**确定性工程路径**：
用户问题 → 解析为「维度 + 指标」（维度复用 `DimensionStateManager`，指标 = skill 分级关键词命中）→ 命中 skill 且配置了对应 script_exec 脚本 → **Java 直接调用脚本**并流式返回 stdout → 匹配不到/不可执行时**回退原 LLM 链路**（目录展示 + load_skill + script_exec，行为不变）。

已拍板的策略（用户确认）：
1. **匹配门槛**：沿用现有检索（`SkillCandidateSelector` 分级关键词下钻）能找到 skill 时，**只执行分数最高的 top-1**；top-1 未配置脚本则回退。
2. **参数缺失**：版本/季度缺失用当前在建版本（`<domain_knowledge_current_version>` 同源 SQL）补默认值；其他必填参数（如部门）缺失 → 回退 LLM 链路追问。
3. **脚本执行失败**（非 0 退出/超时/拒绝/脚本不存在）→ 直接把错误报给用户，不回退 LLM。
4. **维度多值**（"比较一部和二部"）→ 直接以 **List 传参**给脚本（维度参数一般就是 list；单值传 String 兼容存量脚本）。

## 现状锚点（探索结论）

| 环节 | 位置 | 复用方式 |
|---|---|---|
| 确定性短路模式 | `V2ChatStreamServiceImpl.stream()` L428-439（歧义反问轮） | 直接照抄：sendText → `emitter.complete()` → `cleanup.run()`（必须显式跑 cleanup，否则 inFlight 永不清理） |
| 维度解析 | `DimensionStateManager.processQuestionInContext(ctx, q)` (L145) | 返回 `ProcessResult(enriched, DimensionState, aliases)`，ctx 级状态、并发安全； DimensionState 含 timeDimension(VERSION/QUARTER)/departments/peerDimension(TEAM/APPLICATION/PRODUCT_LINE/REQUIREMENT)/persons |
| skill 关键词匹配 | `SkillCandidateSelector.select(allSkills, metadata, question)` (L57) | 现成 top-1 排序（explicit > domain 3000 > L3 1500 > L2 1000 > L1 500 > 叶层 500/词 cap1500）；domain 互斥过滤已在内部。⚠️ 2026/10/13 语义定稿（见 `skill-retrieval-logic.md` §7.3）：**层级只加权不过滤**——任一层（L1/L2/L3/叶层）命中即入围，全层未命中才出局；截断两档看榜首命中链完整性。本方案重载共享 select 主体自动继承，门槛维持拍板口径：**候选非空即执行 top-1，不区分精确/宽泛档** |
| 路由元数据 | `SkillRoutingMetadataRepository.findActive()`（TTL 30s 缓存） | 加 `script_id`/`param_mapping` 两列 |
| 脚本执行 | `ScriptExecTool.scriptExec(scriptId, params)` (v2/tools/ScriptExecTool.java:179) | 纯 Java 方法；stdout 下载块已由 `extractDownloads` 原位替换成短链 HTML；`executeForDebug` (L552) 是现成公共入口 |
| stdout 提取 | `ScriptExecOutputExtractor.extractStdout()`（v2/hooks 包） | 剥掉 `[script_exec] .../stderr` 信封，只留 stdout |
| 当前在建版本 | `VersionPlanKnowledgeHook`（v2/hooks，TTL 1800s） | 需暴露 public 查询方法供参数补默认值复用 |
| 前端渲染 | `frontend/src/components/Markdown.vue` | markdown 表格/HTML 围栏/下载链接可直接渲染，done 事件文本喂进去即可 |
| 权限 | `SkillUsageResolver.findUsableRetrievalNames(userId)` + builtin 例外 | 直接执行必须同规则过滤 |

## 改动设计

### 1. 存储层：`skill_routing_metadata` 加 2 列（ensureColumn 幂等，flyway 不可用）

- `script_id VARCHAR(128) NULL` — 该 skill 对应的 script_registry 脚本 ID；**NULL = 该 skill 不参与直接执行**（天然开关）
- `param_mapping TEXT NULL` — JSON 对象：`{"dept":"deptName","version":"versionPlan"}`，key=脚本参数名，value=维度键

维度键枚举（= §2 `DimensionExtraction` 固定 schema，全键必出现、空数组=未提及；值来源 `DimensionState`）：
`versionPlan`→timeDimension(VERSION) / `vestQuarter`→timeDimension(QUARTER) / `deptName`→departments / `groupName`→peerDimension(TEAM) / `appName`→peerDimension(APPLICATION) / `productLine`→peerDimension(PRODUCT_LINE) / `itemNo`→peerDimension(REQUIREMENT) / `perName`→persons

改动文件：
- `v2/skills/SkillRoutingMetadata.java` — record 尾部追加 `scriptId`、`paramMapping` 字段（canonical 构造器收尾，旧构造器重载补 `null, null`）
- `v2/skills/SkillRoutingMetadataInput.java` / `SkillRoutingMetadataView.java` — 同步追加
- `v2/skills/SkillRoutingMetadataRepository.java` — `ensureColumn` 两条 + SELECT 列表/map() 对齐 + upsert/rename 透传（⚠️ 已知坑：SELECT 与 map() 必须对齐，历史出过 map() 死列 bug）
- `v2/skills/SkillRoutingMetadataAdminService.java` — update/创建透传新字段
- `v2/skills/SkillRoutingMetadataController.java` — PUT body 增加 `scriptId`/`paramMapping` camelCase 字段
- ⚠️ `SkillRoutingStartupAudit` 自动停用重建行时透传新字段（同 2026/10/13 层级关键词的坑）

### 2. 新组件 `v2/skills/DirectSkillExecutor.java`

单例 bean（`V2SkillConfig` 注册），构造注入：
`SkillRoutingMetadataRepository`、`SkillCandidateSelector`（与 visibility filter 同参新实例）、`SkillUsageResolver`、`ObjectProvider<VersionPlanKnowledgeHook>`、`ScriptRegistryMapper`、`SkillIndexRepository`（builtin source 判定）。

```java
/** 纯判定，无副作用；null = 不短路，走 LLM */
public DirectExecutionPlan plan(String question, String userId);

/**
 * 标准化「维度+指标」提取体：固定 schema 全键输出（问题未提及的维度 = 空数组）。
 * 别名/简称/版本缺省补全全部在本层消化（analyzeQuestionRuleBased + getCurrentState 只读预览 + currentVersions），
 * 下游 param_mapping 只面对这 8+1 个固定键，不接触任何半结构化文本。
 */
public record DimensionExtraction(
        List<String> versionPlan, List<String> vestQuarter,
        List<String> deptName, List<String> groupName,
        List<String> appName, List<String> productLine,
        List<String> itemNo, List<String> perName,
        List<String> metrics,            // 指标佐证 = top-1 的 L1/L2/L3/叶层真命中词
        String matchedSkill) {}

public record DirectExecutionPlan(
        DimensionExtraction extraction,  // 标准中间体：日志/审计/后续扩展的唯一事实源
        String skillName, String scriptId,
        Map<String, Object> params) {}   // 由 extraction × param_mapping 派生，非独立提取
```

**参数组装两层结构（2026/10/13 定稿）**：

```
Layer 1  问题 → DimensionExtraction（固定 schema 归一化出口，全键输出、空数组=未提及）
Layer 2  param_mapping: {"dept":"deptName","version":"versionPlan"}  （脚本参数 → Layer 1 键）
填参规则（唯一一条）: params[param] = extraction[key]；
  空数组 + versionPlan/vestQuarter → 补当前在建版本（第一个值），仍空 → 必填缺失 → null 回退 LLM
  单值 → String，多值 → List<String>；多余参数一律不传（params_schema 白名单）
```

扩展成本：新脚本接入 = 管理页配 script_id + param_mapping，Java 零改动；新维度（如时间范围「今天/近一周/X月X日-X月X日」，DimensionState 现不解析，本期不做）= 将来 Layer 1 schema 加键 + 提取规则，映射/填参逻辑零改动。

**param_mapping 合法性校验（防配错在配置期而非运行期）**：key 必须 ⊆ 该脚本 `params_schema`（否则填了也必被工具白名单拒绝）、value 必须 ∈ 8 维度键枚举。管理页保存时校验拒绝非法配置；`SkillRoutingStartupAudit` 启动时对存量行审计（非法映射 warn + 该行按无 script_id 处理，不参与直接执行，不影响 LLM 链路）。脚本参数名不规则（`devDept`/`planVersion`/中文名等）由映射 key 原样吸收，代码不感知。

**plan() 判定链（全部确定性）**：
1. 开关关 / metadata 空 / question 空 → null
2. `findActive()` + 权限过滤（非 builtin skill 必须在 `findUsableRetrievalNames(userId)` 内；builtin = `skill_index.source` 为 BuiltinSkillRegistrar 注册值，实现时对该常量确认；解析失败 fail-closed → null）
3. `SkillCandidateSelector` 匹配（见 §3 重载）→ 空则 null；取 **top-1**
4. top-1 的 `scriptId` 为空 → null（回退 LLM，目录照常）
5. `ScriptRegistryMapper.selectByScriptId(scriptId)` 查 `params_schema` 必填参数；按上面两层结构填参——先产出 `DimensionExtraction`（固定 schema 全键），再逐条 `param_mapping` 从 extraction 取值：
   - 单值 → String；多值 → List<String>
   - 取到空数组且键为 `versionPlan`/`vestQuarter` → `VersionPlanKnowledgeHook.currentVersions()` 补当前在建版本（第一个值）；仍无 → 缺参
   - 其他必填缺失 → null（回退 LLM）
   - 多余参数一律不传（params_schema 白名单）
6. 返回 plan（extraction + params）

**参数取值来源**：plan() 内不写状态——先 `analyzeQuestionRuleBased(question)` + `getCurrentState()` 做只读预览填参；**只有 V2ChatStreamServiceImpl 决定短路后才调 `processQuestionInContext(ctx, question)` 正式写状态**（避免回退 LLM 时维度状态被解析两次，特别是 clarifiedPeer 一次性回填被吃掉）。

### 3. `SkillCandidateSelector` 增加 metadata-only 重载

现签名要求 `List<AgentSkill>`（只用于 availableNames 过滤）。直接执行路径不加载 AgentSkill，新增：

```java
public SkillCandidateSelection selectFromMetadata(List<SkillRoutingMetadata> activeMetadata, String question)
```

把现 select() 主体抽成共享私有方法（入围/打分/排序逻辑零变化，现有单测不动一行全过——同 2026/10/13 层级关键词的兼容策略；"层级只加权不过滤 + 截断看榜首"定稿语义由共享主体自动继承）。`SkillCandidateSelection` 若不含命中关键词明细，追加 `matchedKeywords` 字段（尾部追加，旧构造器重载保留）。**注意**：matchedKeywords 收集各层真命中的词条（定稿语义下无"保底存活"概念，入围者必为某层真命中），与打分口径一致。

### 4. `VersionPlanKnowledgeHook` 暴露查询

新增 `public List<String> currentVersions()`：复用现有 SQL + TTL 缓存（把 onEvent 里的取值逻辑抽出来）；失败返回空列表。onEvent 行为不变。

### 5. `ScriptExecTool` 结构化执行入口

新增公共方法，复用现有私有链路，不改动执行逻辑：

```java
public record ScriptExecution(int exitCode, String fullText) {}
public ScriptExecution executeStructured(String scriptId, Map<String, Object> params)
```

`scriptExec()` 返回的 ToolResultBlock 文本信封已含 `exit=`/stdout/stderr；executeStructured 捕获 exitCode（runProcess 返回路径带出，或解析信封首行 `exit=(-?\d+)`）。失败判定：`exitCode != 0` 或文本含 `script_exec 拒绝执行` / `❌`。

### 6. 短路点：`V2ChatStreamServiceImpl.stream()`

位置：clarifyPrompt 短路块（L428-439）**之后**、`Mono.fromRunnable` 之前：

```java
if (directExecEnabled && clarifyPrompt == null) {
    DirectExecutionPlan plan = directSkillExecutor.plan(text, userId);   // 只读判定
    if (plan != null) {
        // 正式写维度状态（继承/别名/clarifiedPeer 回填），与 LLM 路径共用
        RuntimeContext ctx = buildRuntimeContext(conversationId, userId, text);
        dimensionStateManager.processQuestionInContext(ctx, text);
        // boundedElastic 上异步执行（脚本上限 300s，不能占 servlet 线程）
        Mono.fromRunnable(() -> runDirectExecution(streamCtx-ish, plan, strategy)).subscribeOn(boundedElastic).subscribe();
        return emitter;
    }
}
```

`runDirectExecution` 步骤（复用 StreamContext/strategy/cleanup 全套）：
1. `strategy.sendThink(progress("已匹配技能「X」，直接执行脚本 Y…"))` + `hasSentExecuting` 置位
2. 发 ActivityFeed 事件（可选但推荐）：`tool_call_start`（toolCallId=UUID, name=script_exec, input=params）→ 保持与 LLM 路径 UX 一致
3. `scriptExecTool.executeStructured(scriptId, params)`
4. 成功：`ScriptExecOutputExtractor.extractStdout(fullText)` → 发 `tool_result_end` 事件 → `strategy.sendText(TextPayload.chunk(stdout, true))`（"done" 事件，Markdown.vue 渲染表格/HTML/下载链接）→ `traceCtx.markSuccess()` → `emitter.complete()` → `cleanup.run()`（显式，同歧义短路模式）
5. 失败：`strategy.sendError(ctx, new IllegalStateException(摘要))`（code 500 done 事件，用户已拍板直接报错）→ `traceCtx.markError()` → complete + cleanup
6. 给 `collector` 加一条 `ToolCallDetail`（script_exec + 入参/出参），使 cleanup 的 episodic 持久化与 trace 有记录

注意：短路轮不启 agent → ResponseCache/memory ledger/SkillSynthesis 等下游自然不跑（与歧义反问轮同语义）。

### 7. 配置开关

```properties
# application.properties（默认关）
harness.a2a.skill-direct-exec.enabled=false
```

内网/dev 环境在 application-dev.properties 或环境变量开 true。与 `routing.enabled` 互相独立（direct-exec 只依赖 metadata 表有 script_id 的行）。

### 8. 管理页（Skill 配置页）

- `frontend/src/types/skillRouting.ts` — 接口加 `scriptId`、`paramMapping`
- `frontend/src/pages/SkillRoutingConfigPage.vue` — table 加「脚本 ID」「参数映射」两列（空 = 不参与直接执行）；编辑弹窗加脚本 ID 输入框 + **参数映射两列小表格（可增删行：左列脚本参数名自由输入，右列维度键下拉，选项 = 8 固定维度键）**——下拉交互天然实现「value ∈ 维度键枚举」校验（2026/10/13 用户确认），key ⊆ params_schema 校验由保存时后端校验兜底
- 后端 PUT API 字段透传（§1）

### 9. 维度键字典动态化（方案 A，2026/10/13 拍板，已实施）

> 实施记录（2026/10/13）：`v2/dimension/DimensionKeyConfig(+Repository)` + `V2DimensionConfig`
> bean + `DimensionKeyController`（/v2/dimension/key CRUD+reload）+ `DirectSkillExecutor`
> 动态键提取（`extraDimensions`/`activeKeys()`/词表 contains 扫描）+ AdminService 校验动态化
> + 前端 `DimensionKeyPage.vue`（/script-registry/dimension-key）+ Skill 配置页下拉动态加载
> （失败回退静态枚举）。393 单测全过。

> 实施记录（2026/10/14）：直接执行短路覆盖第二条 Chat 链路 `/ai/chat`（内网反馈原方案只作用于
> `/v2/ai/chat` 前端）。`ChatStreamServiceImpl.stream()` 在歧义反问短路块之后插入同款 plan() 判定 +
> `runDirectExecution`；与 v2 的差异：① /ai/chat 协议无 ActivityFeed 事件，进度走 think 状态行
> （sendVisibleThinkStatus）；② 无 ToolCallCollector，脚本失败进 /ai/chat 失败监控
> （recordMonitorFailure）；③ 成功文本仍 append 进 answerContent 走 cleanup 的 saveAnswerIntoDB 落库。
> SkillFlowChatRouter 五处 `normalChat.stream()` 出口全部覆盖，插入点唯一。

§2/§8 的 8 个维度键目前硬编码（`DirectSkillExecutor.DIMENSION_KEYS` + 前端
`DIRECT_EXEC_DIMENSION_KEYS` + 保存期校验），新增第 9 个维度要改多处代码。方案 A
把「键的配置」动态化：**内置 8 键保留结构化规则提取不变，新增键走词表命中提取**，
管理员纯配置即可扩展 `param_mapping` 可用维度键。

#### 9.1 范围拍板

| 决策点 | 结论 |
|---|---|
| 内置 8 键 | 提取逻辑不动（`DimensionStateManager` 结构化规则 + AliasResolver），字典表只存展示信息（key/label，内置行只读） |
| 新增键提取 | **词表命中**：问题中出现 alias（原样/正则，同 dimension_alias 语义）→ 填入对应标准值；多值 = 多个不同 alias 同时命中 |
| 动态键跨轮继承 | **不做**（后续再说）——动态键只在当前问题提取，不进 `DimensionState`、不跨轮继承；第二轮未提及 → 必填缺失回退 LLM |
| 触发词条件行 | **不做**（后续再说）——Phase 1 词表直接命中；管理员配置时避免裸短词（如卡口维度配 `Q1卡口→Q1` 而非裸 `Q1`），防短词误命中 |
| 动态键缺省来源 | 无缺省（内置 version/quarter 的"当前在建版本"缺省不适用于动态键）；脚本配必填且问题未提 → 回退 LLM |

#### 9.2 存储：新表 `dimension_key_config`（gauss 库，照搬 DimensionAliasRepository 模式）

```
id BIGSERIAL PK
key        VARCHAR(64)  UNIQUE NOT NULL   -- param_mapping value / param_mapping 下拉取值（camelCase）
label      VARCHAR(128) NOT NULL           -- 展示名，如 卡口
builtin    BOOLEAN DEFAULT FALSE           -- 内置 8 键 seed 行，管理页只读（不可删/不可改 key）
aliases    TEXT                            -- JSON 数组 [{"alias":"Q1卡口","value":"Q1"}, ...]
enabled    BOOLEAN DEFAULT TRUE
remark     VARCHAR(255)
created_at / updated_at
```

- `ensureTable` 幂等建表 + 首建 seed 内置 8 键（aliases 为空数组，提取不走词表）
- TTL 快照缓存 + `invalidateCache()`，读失败沿用上次快照（同 `DimensionAliasRepository`）

#### 9.3 后端改动

- 新增 `v2/dimension/DimensionKeyConfig.java`（record）+ `DimensionKeyConfigRepository.java`
  + `V2DimensionConfig` 注册 bean（同 alias 三件套）
- 新增 `v2/controller/DimensionKeyController.java`：`GET/POST/PUT/DELETE /v2/dimension/key`
  + `POST /v2/dimension/key/reload`，写操作仅管理员（照抄 `DimensionAliasController` 的
  `AdminRoleService` 校验模式）
- `DirectSkillExecutor`：
  - 构造注入 `DimensionKeyConfigRepository`（`ObjectProvider` 可选依赖，缺失时退化为纯 8 键）
  - `DIMENSION_KEYS` 静态 Set 保留（内置键的合法值域），新增 `activeKeys()` =
    内置 8 键 ∪ 字典启用键（TTL 内缓存）
  - `DimensionExtraction` 尾部追加 `Map<String, List<String>> extraDimensions`
    （record 尾部追加 + 旧构造器重载，同 scriptId/paramMapping 兼容策略）
  - `plan()` 提取动态键：对 question 逐个扫描启用键的 aliases（contains 命中 → 收集
    value，去重保序）填入 `extraDimensions`
  - `valuesFor()`：switch 命中内置键走原路；否则查 `extraDimensions`（空 → 参数不传）
- `SkillRoutingMetadataAdminService.serializeParamMapping`：校验值域从静态
  `DIMENSION_KEYS` 改为注入的动态键集（`activeKeys()`）；**注意该方法是 static，需改
  实例方法或传入校验器**
- `SkillRoutingMetadataController` 如有维度键提示接口可透出 `activeKeys()`

#### 9.4 前端改动

- 新页面 `DimensionKeyPage.vue`（或同义词页加 tab）：键列表（key/label/builtin 标识/
  启用开关）+ 编辑弹窗维护 aliases 词表行（alias → value 两列，可增删）；路由/菜单入口
  与维度同义词页并列
- `SkillRoutingConfigPage.vue` 参数映射下拉：选项从 `DIRECT_EXEC_DIMENSION_KEYS`
  静态枚举改为 `GET /v2/dimension/key` 动态加载（加载失败回退静态枚举兜底）；
  `skillRouting.ts` 的静态枚举保留作 fallback

#### 9.5 示例（卡口维度）

配置：键 `qGate`（卡口），aliases `[{"alias":"Q1卡口","value":"Q1"}, ..., {"alias":"二季度卡口","value":"Q2"}]`；
skill 的 `param_mapping` 配 `{"gate":"qGate"}`。问「Q2卡口的达标率」→ `qGate=[Q2]`
→ 脚本收到 `gate=Q2`（单值 String）；问「比较Q1卡口和Q2卡口」→ `gate=["Q1","Q2"]`（List）。

#### 9.6 测试要点

1. Repository：ensureTable 幂等 + seed、TTL 快照、findAllForAdmin、insert/update/setEnabled
2. `DirectSkillExecutor`：动态键命中提取（单值/多值/去重）、aliases 不命中 → 参数不传、
   字典库挂 → 快照兜底、`ObjectProvider` 缺失 → 纯 8 键不炸
3. 保存期校验：合法动态键通过、非法键报错文案含可选键列表
4. 存量：`DirectSkillExecutorTest` 全过（extraDimensions 缺省空 map 行为不变）

## 关键文件清单

**新增**：
- `src/main/java/com/agentscopea2a/v2/skills/DirectSkillExecutor.java`
- `src/test/java/com/agentscopea2a/v2/skills/DirectSkillExecutorTest.java`
- `src/test/java/com/agentscopea2a/v2/skills/SkillRoutingMetadataDirectExecColumnTest.java`（新列 round-trip，仿现有 repository 测试）

**修改**：
- `v2/skills/SkillRoutingMetadata.java`、`SkillRoutingMetadataInput.java`、`SkillRoutingMetadataView.java`、`SkillRoutingMetadataRepository.java`、`SkillRoutingMetadataAdminService.java`、`SkillRoutingMetadataController.java`、`SkillRoutingStartupAudit.java`
- `v2/skills/SkillCandidateSelector.java`（metadata-only 重载）
- `v2/skills/SkillCandidateSelection.java`（matchedKeywords，尾部追加）
- `v2/hooks/VersionPlanKnowledgeHook.java`（currentVersions()）
- `v2/tools/ScriptExecTool.java`（executeStructured）
- `v2/service/impl/V2ChatStreamServiceImpl.java`（短路点）
- `v2/config/V2SkillConfig.java`（DirectSkillExecutor bean + 开关）
- `frontend/src/types/skillRouting.ts`、`frontend/src/pages/SkillRoutingConfigPage.vue`
- `src/main/resources/application.properties`
- `docs/history/skill-retrieval-logic.md`（§8 直接执行链路补记）

## 复用清单（不重造）

- `DimensionStateManager.processQuestionInContext` / `analyzeQuestionRuleBased`（维度解析全复用，含 §13.4 两遍分段抽取）
- `SkillCandidateSelector` 打分/收窄/排序（新重载共享主体）
- `SkillCandidateSelector` 的正则缓存与归一化（与 SkillSkillOverlapService 一致性不受影响——不改归一化逻辑）
- `ScriptExecTool` 执行/白名单/下载块协议（只加结构化出口）
- `ScriptExecOutputExtractor.extractStdout`
- `VersionPlanKnowledgeHook` SQL + TTL 缓存
- SSE 短路模式、StreamContext、cleanup 幂等机制

## 测试与验证

**单测**（全确定性逻辑）：
1. `SkillCandidateSelectorTest` 存量全过（重载不改行为）
2. `DirectSkillExecutorTest`：
   - top-1 选择（显式点名 > domain > 层级分排序正确性）
   - 候选空 / top-1 无 scriptId → null
   - 参数填充：单值 String、多值 List、version 缺失补当前在建版本、dept 缺失 → null
   - param_mapping 缺失维度 → 该参数不传；必填缺失 → null
   - 权限：非 builtin 不在 usable 集 → null；解析异常 fail-closed
   - metadata 空一行 / 全 inactive → null
3. Repository 新列：upsert→findActive round-trip、ensureColumn 幂等（老库无列启动不炸）
4. `VersionPlanKnowledgeHook.currentVersions()`：TTL 内不重复查库、异常返空
5. `ScriptExecTool.executeStructured`：成功/拒绝/超时 exitCode 正确

**E2E（内网）**：
1. 给 `q2_1_by_dept_version_metrics` 配 `script_id=q2_1_metrics_by_dept_version` + `param_mapping={"dept":"deptName","version":"versionPlan"}`
2. 问「杭州开发二部 7月版 Q2-1 达标率多少」→ 秒回 markdown 表（SSE done，无 LLM 调用，日志无 ModelCall）；验证下载短链可点
3. 问「比较杭州开发一部和二部…」→ List 传参脚本（若脚本不支持 IN 会报错 → 直接报错路径验证）
4. 问未配置 skill 覆盖的问题（如「帮我写个周报」）→ 走原 LLM 目录链路不变
5. 不说部门 → 回退 LLM 追问（LLM 可用时）/ 报模型不可用（符合预期，回退路径本来就是原链路）
6. 脚本 ID 配错 → 直接报错给用户

## 风险与已知取舍

| # | 风险 | 处置 |
|---|---|---|
| 1 | top-1 误路由（宽松门槛按用户拍板：候选非空即执行 top-1，不区分精确/宽泛档；2026/10/13 下钻算法改"上层保底"后维持此拍板） | script_id 配置即 opt-in，管理员逐个 skill 控制；出问题删 script_id 即回退。若后续误路由频发，可启用"榜首命中链完整才执行"附加门槛（检索两档判定现成信号，见 `skill-retrieval-logic.md` §7.3） |
| 2 | 短路后回退 LLM 的窄边缘（ambiguous 确认轮 + 命中 + 缺参 → clarifiedPeer 一次性回填被消费） | 概率极低；记录在案，出问题再处理 |
| 3 | 脚本不支持 List 参数 | 多值 List 传参是脚本侧职责（用户拍板维度参数一般是 list）；存量单值脚本遇多值问题会由 DB 返回错或脚本报错 → 直接报错路径 |
| 4 | 维度状态 JVM 重启丢失（既有缺口） | 与 LLM 路径同语义，不新增缺口 |
| 5 | `SELECT`/`map()` 列对齐坑、`StartupAudit` 透传坑 | §1 已显式列入改动点 |
| 6 | 工作区有未提交改动（层级关键词功能） | 本方案在其上叠加，不回滚不覆盖；提交策略按用户 `git_push_workflow` 固定流程另行处理 |
