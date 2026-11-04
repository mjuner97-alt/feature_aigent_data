# 内置维度键 projectNo（项目编号）方案

> 状态：2026-11-03 设计并实施
> 上游底稿：`docs/202610/dimension-key-dynamic-dimension-plan.md`（§1-§5 现状与检查表，本文不重复推导）
> 建模模板：同底稿 §6.3 `subItemNo`（内置键 8→9 那次）的「专字段 + 槽位照旧」路径，内置键 11→12。

## 1. 需求

维度同义词页原有方案是配置动态键 + 正则捕获词表（底稿 §6.4，`AliasRegexSupport`），但项目编号需要跨轮继承/反问/层级判定等结构化链路能力，拍板**走内置键**，不走页面配置。

编号形态（仅大写）：

```
KJ|CC + 8位数字 + (-字母数字后缀段)*
KJ20260114、CC20250745            （裸码）
KJ99990273-412、KJ20260190-H01    （单段后缀）
CC99990073-41081-2                （多段后缀）
```

正则（单捕获组收整串，`(?:...)` 不占组号）：

```java
EXPLICIT_PROJECT_NO = Pattern.compile("((?:KJ|CC)\\d{8}(?:-[A-Za-z0-9]+)*)")
```

中文字符不在 `[A-Za-z0-9]` 内，匹配自然截断（`KJ20260114和CC...` 不吞「和」）。

## 2. 拍板

| # | 问题 | 结论 |
|---|---|---|
| ① | 问题含项目编号且未显式提时间，是否补默认在建版本？ | **不补**（沿用需求项 2026/10/21 拍板，扩展 `requirementWithoutExplicitTime`）——精确编号查询按全历史口径由脚本自查 |
| ② | 前缀大小写 | **仅大写** KJ/CC，与需求项 I/S 编号同口径；小写交 LLM 兜底 |
| ③ | 建模 | 同 subItemNo：专字段 `projectNos` 无条件收 + `PeerDimensionType.PROJECT` 槽位先到先得（优先级 I > S > 项目编号），alias 命中即跳过正则兜底（`aliasHit` 守卫） |
| ④ | 指代词 | 不做（不加 `ReferenceType.PROJECT`，无「这个项目编号」支持），后续按需升级 |

## 3. 改动清单（实施落点）

### 3.1 状态建模

- `v2/dimension/DimensionState.java`：字段 `projectNos` + getter/setter；`PeerDimensionType` 新增 `PROJECT`。
- `v2/dimension/QuestionAnalysis.java`：`projectNos` 字段 + extra 反序列化兜底（照 `subRequirementNos`）。

### 3.2 DimensionStateManager（提取核心）

- `EXPLICIT_PROJECT_NO` 正则常量。
- **span 屏蔽三处**追加：`bareAppCodes`（防 "KJ" 被裸应用码抢成 F-KJ）、`buildMaskedQuestion`、`maskForPersonScan`。
- 提取块：subItemNo 块后照抄——`!aliasHit(aliasRes)` 守卫内 find 循环收专字段；槽位 `getPeerDimension() == null` 时 set `PROJECT`。
- 层级判定：PEER 分支追加 `EXPLICIT_PROJECT_NO.matcher(q).find()`。
- 中文标签：两处 switch 加 `case PROJECT -> "项目编号"`。

### 3.3 DirectSkillExecutor（键贯通）

- `DIMENSION_KEYS` 加 `projectNo`（subItemNo 后）。
- `DimensionExtraction` record：`subItemNo` 后插 `projectNo` 字段；4 个 legacy 构造器委派链补 `List.of()`；全参调用点（`withTimeRangeAnchors`/`extract` 等）同步。
- `extract()`：switch 加 `case PROJECT -> projectNo = values`；专字段合并块（`state.getProjectNos()` 去重保序）。
- `requirementWithoutExplicitTime`：`hasRequirement` 追加 projectNo 非空（拍板①）。

### 3.4 seed / 标签 / 知识注入

- `DimensionKeyConfigRepository.BUILTIN_KEYS` 加 `projectNo → 项目编号`（内置 11→12；`ensureBuiltinKeyRows` 存在性补种自动处理存量表，无迁移脚本）。
- `DimensionStateMiddleware` 两处标签 switch 加 PROJECT。
- `DimensionPrompts` peer 提取说明 + 枚举示例加项目编号。
- `workspace/knowledge/KNOWLEDGE.md`、`workspace/skills/data_primitives/SKILL.md` 维度举例补项目编号。

### 3.5 前端

- `frontend/src/types/skillRouting.ts` `DIRECT_EXEC_DIMENSION_KEYS` fallback 加 projectNo。
- `frontend/src/pages/DimensionKeyPage.vue` `RULE_HINTS` 加 projectNo 提示。
- build 产物落 `src/main/resources/static/`。

## 4. 边界（不做的）

- 不做指代词（拍板④）；`dimension_alias` 词表不收项目编号（正则兜底已覆盖，alias 压制语义不变）。
- 项目编号不进 `VOCAB_MERGE_KEYS`（无词表口语值需求，词表行无效）。

## 5. 测试

- `DirectSkillExecutorTest` activeKeys 键计数 15→16。
- 新增单测（模板：`DimensionStateManagerAliasIntegrationTest` S 编号用例）：
  1. 裸码/单段后缀/多段后缀提取；
  2. 多编号多值收集 + 中文截断；
  3. 槽位优先级：I 编号同句时 I 占槽、projectNos 专字段仍收；
  4. alias 命中抑制正则兜底；
  5. span 屏蔽：不产出 F-KJ 裸应用码；
  6. 时间抑制：项目编号+无显式时间不补默认在建版本，显式时间正常传。

## 6. 验证

1. `mvn test` 全量（基线 ~800，重点键计数与新用例）。
2. 前端 build 落 static/。
3. E2E（内网）：新 conversationId 提「KJ99990273-412 和 CC20200537-404 的完成率对比」，param_mapping 绑 `projectNo` 的 skill 收到 List 两值；提「KJ20260114的缺陷数」验证不补默认版本。