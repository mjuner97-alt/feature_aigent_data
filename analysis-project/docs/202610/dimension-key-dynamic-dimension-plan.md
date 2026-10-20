# 维度键字典与动态维度底稿

> 状态：现状已实施（2026-10-13 落地，随 skill-direct-exec-plan.md §9 交付）；§6.1 timeRange 已实施（2026-10-14/15）
> 用途：**后续新增这一块需求的底稿**——先读 §1-§3 现状，再按 §5 改动清单评估波及面；
> 设计拍板/未做项见 §4，新需求若推翻某条拍板请在 §6 补记。
> 上游方案：`docs/history/skill-direct-exec-plan.md` §9（原始设计，本文不重复其推导过程）

## 1. 定位与双轨模型

维度键分两类，**提取路径完全不同**，新需求先确认改哪条轨：

| | 内置 9 键 | 动态键（用户新增） |
|---|---|---|
| 键名 | versionPlan / vestQuarter / deptName / groupName / appName / productLine / itemNo / subItemNo / perName | 管理页任意新增（camelCase，如 qGate） |
| 提取方式 | `DimensionStateManager` 结构化规则（正则 + AliasResolver 词表消解 + §13.4 两遍分段抽取） | 对**当前问题**做纯 contains 扫描（alias 命中 → 收集标准值） |
| 状态归属 | 进 `DimensionState`，跨轮继承/反问/归一化全链路 | **不进 DimensionState，不跨轮继承**（§9.1 拍板） |
| 词表 | `dimension_alias` 表（独立体系，见 dimension-alias-config-plan.md） | `dimension_key_config.aliases` JSON 列（键自带词表） |
| 值形态 | 单值/多值经状态机 | `Map<key, List<value>>`（`extraDimensions`），多值 = 多 alias 同时命中 |

两轨在 `DirectSkillExecutor.valuesFor()` 汇合：内置键走 switch 命中，动态键走
`default -> extraction.extraDimensions().getOrDefault(key, List.of())`，
timeRange 走裸键锚点透传与 `.start/.end` 后缀特判
（DirectSkillExecutor.java:513 `valuesFor`）。

## 2. 配置链路（管理页 → 表 → 快照）

- **页面**：`frontend/src/pages/DimensionKeyPage.vue`（路由 /script-registry/dimension-key）。
  管理员可新增/编辑/启停/软删；非内置键可编辑词表行（alias → value）；内置键只读。
  写操作后调 `POST /v2/dimension/key/reload` 立即失效快照，否则最长等 TTL 5 分钟。
- **表**：`dimension_key_config`（gauss 库），DDL/seed 见 skill-direct-exec-plan.md §9.2。
  `DimensionKeyConfigRepository.ensureTable()` 首连自动建表 + 灌内置 8 键 seed
  （builtin=true，aliases 空数组）。
- **快照**：Repository 内存 TTL 快照（`harness.a2a.dimension.key-cache-ttl-millis:300000`），
  读失败沿用上次快照——匹配在每轮请求热路径上，不允许阻塞或抛错（同 dimension_alias 模式）。
- **API**：`v2/controller/DimensionKeyController.java`：`GET/POST/PUT/DELETE /v2/dimension/key`
  + `POST .../reload`，写操作仅管理员（AdminRoleService，同 DimensionAliasController 模式）。
- **记录结构**：`DimensionKeyConfig.java`（record）= `{id, key, label, builtin, aliases:[{alias,value}], enabled, remark}`。

## 3. 运行时链路（问题 → 脚本参数）

1. DirectSkillExecutor 命中 skill 直连脚本时（plan()），调
   `extractExtraDimensions(question)`（DirectSkillExecutor.java:457）：
   - `repo.findAllEnabled()` 取启用键快照；
   - 跳过 `builtin` 键（内置键走结构化规则，见 §1）；
   - 例外：`timeRange` 保留键走 `TimeRangeExtractor.extract`（L1 绝对区间正则 +
     L2 词表最长匹配，见 §6.1.3），通用 contains 循环跳过该键；
   - 其余动态键逐键逐 alias `question.contains(alias)` → 命中收集 value（去重保序）；
   - 产出 `extraDimensions` 塞进 `DimensionExtraction`（record 尾部字段）。
2. `valuesFor()`（DirectSkillExecutor.java:513）按 param_mapping 的键取值：动态键 → `extraDimensions`；
   单值 String / 多值 List 由脚本参数 schema 决定；`timeRange.start/.end` 后缀键经
   `TimeRangeResolver` 解析第一个锚点。
3. `activeKeys()`（DirectSkillExecutor.java:497，经 `TimeRangeResolver.expandKeys` 追加
   `.start/.end` 后缀形态）= 内置 8 键 ∪ 字典启用键，
   是 param_mapping 下拉与保存期校验的唯一值域来源（`SkillRoutingMetadataAdminService.serializeParamMapping`
   :117-130 同源读取；
   前端 Skill 配置页下拉动态加载，失败回退静态枚举 `DIRECT_EXEC_DIMENSION_KEYS`）。
4. 动态键必填缺失 → 不传参数 → **回退 LLM**（无缺省来源，内置 version/quarter 的
   "当前在建版本"缺省不适用于动态键，§9.1 拍板）。

## 4. 设计拍板与已知边界（新需求默认沿用，推翻需明确记录）

| # | 拍板/边界 | 内容 |
|---|---|---|
| 1 | 动态键不跨轮继承 | 只在当前问题提取；第二轮未提及 → 必填缺失回退 LLM（§9.1） |
| 2 | 触发词条件行未做 | 词表是裸 contains，无 trigger_keyword 机制（dimension_alias 有，此表没有）——靠配置纪律规避：不配裸短词（`Q1卡口→Q1` 而非裸 `Q1`）。页面 hint 已提示"触发词条件行为后续版本规划" |
| 3 | alias 匹配语义 | 纯 `contains`，无最长匹配、无跨 span 去重、无嵌入裁决——"Q1卡口"和裸"Q1"若同时配置，两行各自独立命中（长词不压短词） |
| 4 | 无歧义反问 | 同 key 多 value 是合法多值语义（List 传参），不触发反问（与 dimension_alias 的一对多反问不同） |
| 5 | 无归一化/无正则 | alias 是字面量，value 是字面量；不接入 normalizeDepartment 等归一化 |
| 6 | 与 DimensionState 零交互 | 动态键不影响指纹/cacheKey/ResponseCache/维度上下文注入 |
| 7 | 容错契约 | 字典库读失败 → 动态键整体跳过（warn 日志），不阻塞主链路；ObjectProvider 缺失 → 退化为纯 8 键 |

## 5. 改动清单（新增需求时按此评估波及面）

**后端核心三件**（几乎任何需求都会碰）：
- `v2/dimension/DimensionKeyConfig.java` + `DimensionKeyConfigRepository.java`（表结构/快照/CRUD）
- `v2/skills/DirectSkillExecutor.java`：`extractExtraDimensions`（提取语义）、
  `valuesFor`（取值）、`activeKeys`（值域）、`DIMENSION_KEYS`（内置键静态集合）
- `v2/controller/DimensionKeyController.java`（API 面）

**波及面检查表**：
- [ ] 改表结构 → Repository ensureTable + seed + record 字段 + TTL 快照列
- [ ] 改提取语义（如加正则/触发词/最长匹配）→ 只动 `extractExtraDimensions`；若要复用
      dimension_alias 的裁决逻辑，注意那是 `DimensionStateManager.suppressEmbeddedAliasHits`
      的 Manager 领域知识，抽公共工具时两处行为必须对齐
- [ ] 改值域 → `activeKeys()` 单一来源；检查 `SkillRoutingMetadataAdminService`
      保存期校验（曾是 static 方法，勿回退）与前端 `DIRECT_EXEC_DIMENSION_KEYS` fallback
- [ ] 改缺省逻辑 → `defaultTimeValue`/`currentVersionsSafe` 只服务内置 version/quarter；
      动态键加缺省要新增来源，且注意"必填缺失回退 LLM"的兜底链不受影响
- [ ] 改继承/状态 → 动态键进 DimensionState 是**架构级变更**（影响指纹/cacheKey/
      ResponseCache/维度注入），必须单独评审，见 §6
- [ ] 前端 → `DimensionKeyPage.vue`（字典页）+ `SkillRoutingConfigPage.vue`（下拉）+
      `frontend/src/api/dimensionKey.ts` / `types/dimensionKey.ts`
- [ ] 单测 → `DimensionKeyConfigRepositoryTest` + `DirectSkillExecutorTest`
      （extraDimensions 缺省空 map 行为不能变）

## 6. 需求补记

> 新需求在此追加：日期 / 需求描述 / 是否推翻 §4 拍板 / 落地记录。

### 6.1 时间范围维度 `timeRange`（2026-10-14 设计，**已实施 2026-10-14/15**）

> 实施落点：`v2/dimension/TimeRangeExtractor` + `TimeRangeResolver`（含单测）、
> `DirectSkillExecutor` 提取/取值/值域三处接入、`DimensionKeyConfigRepository.ensureReservedRow`、
> `DimensionKeyController` 锚点白名单校验、`ResponseCacheMiddleware` 绕过（拍板②）、
> 前端 `SkillRoutingConfigPage.vue` 下拉透出 `.start/.end`（onMounted 动态加载）。
> 与设计的差异仅一处：保留行补种语义，见 §6.1.4。

**范围拍板**：本期只做**日历/日期类**范围——本周、前一周、近一周、`X月X日到X月X日` 一类；
**近 N 个版本 / 近 N 个季度暂缓**（版本/季度→日期区间的取数口径未定，锚点 DSL 预留
`LAST:{n}V/{n}Q` 但本期不实现、保存期直接拒绝）。

#### 6.1.1 与现有动态键的本质差异

§3 的动态键是「alias→字面量值」；timeRange 的值是**语义锚点**，必须在执行期算出
start/end（"本周"昨天问和今天问结果不同）。因此提取层产出锚点、新增独立解析层，
**不能照搬纯 contains 直接出参**。

#### 6.1.2 锚点 DSL（value 白名单，本阶段）

| 锚点 | 语义 | 解析产出（含端点，ISO yyyy-MM-dd） |
|---|---|---|
| `THIS_WEEK` | 本周 | 本周一 ~ 今天（拍板点①已定，2026-10-14） |
| `LAST_WEEK` | 上周/前一周 | 上周一 ~ 上周日（完整周） |
| `LAST:{n}d`（n≥1） | 近 n 天 | today-(n-1) ~ today（含今天） |
| `DATE:a~b` | 绝对闭区间 | 原样（仅 L1 正则产出，禁止人工配置） |
| `THIS_MONTH` / `THIS_YEAR` | 本月/今年 | DSL 顺带支持（java.time 零成本），是否灌 seed 词表行由配置决定（"今年"在原始需求清单里） |

暂缓（保存期校验直接拒绝）：`LAST:{n}V`、`LAST:{n}Q`。

#### 6.1.3 提取层（两层，均确定性）

```
question
  ├─ L1 绝对区间正则（代码内置 TimeRangeExtractor）
  │    「10月1日到10月15日」→ DATE:2026-10-01~2026-10-15（缺年补当前年）
  │    「2026-10-01~10-15」「10.1-10.15」「X月X日至X月X日」（分隔符 到/至/~/—/-）
  │    命中 span 屏蔽后供 L2 及其他扫描复用
  └─ L2 词表 contains（timeRange 行的 aliases，配置可扩）
       本周→THIS_WEEK  上周/前一周→LAST_WEEK  近一周/最近一周/近7天→LAST:7d
       键内按 alias 长度降序 accept、重叠丢弃（「前一周」压「周」）——
       ⚠️ 对 §4#3 的收窄：仅 timeRange 键做最长匹配，其他动态键维持裸 contains
→ extraDimensions: timeRange = [锚点, ...]（去重保序）
```

提取只认时间 span；**不进 DimensionState、不跨轮继承**（沿用 §4#1 拍板，追问丢范围
是已知边界，之后按需升级）。

#### 6.1.4 配置落点

- `dimension_key_config` seed 新增一行 `key=timeRange, label=时间范围, builtin=FALSE`
  （**不做 builtin**：repository `update` 带 `WHERE builtin=FALSE`、管理页内置行全只读，
  而本键词表必须可由管理员扩充「大上周」等；非 builtin 即零页面/仓储改动。
  **实施修订**：`ensureReservedRow()` 做存在性补种——建表与存量表都探测，**仅当表中完全没有
  timeRange 行时**才插入（enabled=TRUE）；软删行（enabled=false）视为存在、不复活，
  「删/停用=关功能」拍板保持成立，补种只兜底"表被手工清空/seed 失败"场景）；
- `key=timeRange` 加入保留键清单（其他行不得占用，UNIQUE 约束兜底）；
- 保存期校验（DimensionKeyController/AdminService）：`key=timeRange` 的 aliases.value
  必须匹配锚点白名单正则（`THIS_WEEK|LAST_WEEK|LAST:\d+d|THIS_MONTH|THIS_YEAR`），
  违规保存报错；其他键不受影响；
- 该行 enabled=false 或被删 → 两层提取整体关闭（功能开关语义）。

#### 6.1.5 解析层 TimeRangeResolver（执行期）

- 新组件 `v2/dimension/TimeRangeResolver`（或 skills 包），输入锚点 List，输出
  `LinkedHashMap<锚点, [start, end]>`；显式 `ZoneId.of("Asia/Shanghai")`；
- **解析结果绝不进指纹/缓存**（同"当前在建版本"缺省在执行期注入的先例）；
- 拍板点②已定（2026-10-14）：直接执行命中 timeRange 时**绕过 ResponseCache**
  （实现时确认直接执行路径与 ResponseCache 的交点，timeRange 命中即跳过缓存读写）。

#### 6.1.6 参数绑定

- param_mapping 用后缀键：`{"startDate": "timeRange.start", "endDate": "timeRange.end"}`；
- `valuesFor()` 特判前缀 `timeRange.`：解析锚点 → 取**第一个**锚点的 start/end
  （多范围对比不支持，单值 String 传参；多锚点即多值 List 的场景记为边界，先不支持）；
- `activeKeys()` 透出 `timeRange.start` / `timeRange.end` 后缀形态，供下拉与保存校验；
- **裸键 `timeRange`**：传锚点原词（不解析日期）。单锚点 → String，多锚点 → List。
  适用：脚本自己换算日期（业务口径的"周"）、为 `LAST:{n}V/Q` 预留（锚点透传脚本，
  口径确定后 param_mapping 不改）、多范围对比场景。两类键可同时配在同一个 skill。

**绑定示例**（param_mapping `{"range":"timeRange"}` vs `{"startDate":"timeRange.start","endDate":"timeRange.end"}`）：

| 问题 | 裸键 `range` 收到 | 后缀键收到 |
|---|---|---|
| 本周的缺陷数 | `range="THIS_WEEK"` | `startDate=本周一, endDate=今天` |
| 10月1日到10月15日的缺陷数 | `range="DATE:2026-10-01~2026-10-15"` | `startDate=2026-10-01, endDate=2026-10-15` |
| 本周和前一周的对比 | `range=["THIS_WEEK","LAST_WEEK"]` | 第一个范围：`startDate=本周一, endDate=今天` |

选型：脚本参数是日期字段 → 后缀键（Java 算好，推荐默认）；脚本参数是一个时间范围
字段且愿意自己解析锚点 → 裸键。

#### 6.1.7 改动清单（已全部落地 2026-10-14/15）

- ✅ 新增 `v2/dimension/TimeRangeExtractor`（L1 中文/数字区间正则 + L2 键内最长匹配）
  + `v2/dimension/TimeRangeResolver`（锚点解析/白名单/expandKeys）+ 单测
  （`TimeRangeExtractorTest` / `TimeRangeResolverTest`）；
- ✅ `DirectSkillExecutor`：`extractExtraDimensions` 挂 timeRange 特殊分支（通用 contains
  循环跳过该键）、`valuesFor` 加 `.start/.end` 后缀特判（`timeRangeEdge` 取第一个锚点）、
  `activeKeys` 经 `expandKeys` 加后缀形态；
- ✅ `DimensionKeyConfigRepository`：seed 补 timeRange 行（`TIME_RANGE_ALIASES_SEED`）
  + `ensureReservedRow` 存在性补种（语义见 §6.1.4 实施修订）；
- ✅ 保存期锚点白名单校验：`DimensionKeyController`（timeRange 行 aliases.value 必须
  过 `TimeRangeResolver.isValidConfigAnchor`，非法报错）；
  `SkillRoutingMetadataAdminService.serializeParamMapping` 经 `expandKeys` 校验
  param_mapping 值域（含 `.start/.end`）；
- ✅ ResponseCache：timeRange 命中绕过（拍板②，`ResponseCacheMiddleware`
  加 `DimensionKeyConfigRepository` 可选依赖，`containsTimeRangePhrase` 命中即不读不写，
  onAgent 入口先于指纹计算判断）；
- ✅ 前端：`SkillRoutingConfigPage.vue` 下拉动态加载后，timeRange 行启用时追加
  `timeRange.start/.end` 两个选项；字典接口挂掉时的
  `DIRECT_EXEC_DIMENSION_KEYS` fallback 枚举不含 timeRange（可接受）。

#### 6.1.8 测试要点

1. L1 正则：`10月1日到10月15日`/跨年缺省/`2026-10-01~10-15`/非法日期（13月）不命中；
2. L2：本周/前一周/近一周 各命中；「前一周」不被更短 alias 抢；多锚点去重保序；
3. Resolver：周一当天问 THIS_WEEK（单日区间）、LAST_WEEK 跨月（如 9/29~10/5）、
   时区固定不受服务器 locale 影响；
4. 绑定：单值 String、mapping 未配 timeRange 时锚点提取无副作用；
5. 保存校验：非法锚点拒绝、LAST:3V 暂缓期拒绝；
6. 存量：438 单测基线不回归（extraDimensions 缺省行为不变；2026-10-15 全量绿）。

#### 6.1.9 拍板记录（2026-10-14，均取建议默认）

| # | 问题 | 结论 |
|---|---|---|
| ① | 「本周」区间 | 周一 ~ 今天 |
| ② | ResponseCache 处理 | timeRange 命中即绕过（不读不写） |
| ③ | 「近一周」区间 | today-6 ~ today（含今天） |

### 6.2 时间维度口语值补齐（2026-10-19 设计并实施，E2E 待内网）

> 详见 `docs/202610/time-dimension-plan.md`。不改 §4 任何拍板，全部在 timeRange
> 保留键既有骨架内扩展：L1 正则新增（截至X日/同月省略区间/T-X/R-X/未来X周/近X周）、
> 锚点 DSL 追加（TODAY/UNTIL/NEXT/LAST:{n}w + 里程碑透传型：交付周至投产周/投产周/T-{n}/R-{n}，
> 裸键原样透传不解析日期）、歧义反问（近X周方向、投产前二选一，复用缺参反问 pending 基建）。
> 6 项拍板已定（2026-10-19，该文 §7）：**本周/上周改滚动口径**（本周=today-6~today、
> 上周=today-13~today-7，推翻本底稿 §6.1.9 拍板①）、下周=未来1周（NEXT:1w，无独立锚点）、
> 未来X周含今天、截至X日必须带月、歧义真反问、交付后归并交付周至投产周。

### 6.3 需求子条目维度 `subItemNo`（2026-10-20 设计并实施，E2E 待内网）

> 不推翻 §4 拍板，内置键 8→9。需求项编号定为 `I+8位日期数字-4位数字`（如 I20260827-0288，
> 既有正则未变）；需求子条目编号为 `S+8位日期数字-6位数字`（如 S20250926-000741），
> **独立建模**不复用 itemNo：
>
> - `DimensionState.PeerDimensionType` 新增 `SUB_REQUIREMENT`；`QuestionAnalysis.ReferenceType`
>   新增 `SUB_REQUIREMENT`（指代词「这个/那个需求子条目」同步支持），level 映射仍归 PEER。
> - 提取：`DimensionStateManager` 新增 `EXPLICIT_SUB_REQUIREMENT = S\d{8}-\d{6}`，
>   与 I 编号同优先级链（REQUIREMENT 先命中）；§13.4 span 屏蔽同步覆盖。
> - 键贯通：`DirectSkillExecutor.DIMENSION_KEYS`/`DimensionExtraction.subItemNo`/
>   `valuesFor` case、`DimensionKeyConfigRepository` 内置键 seed、
>   `DimensionStateMiddleware`/`assembleQuestion`/`dimensionLabel` label 三处。
> - 知识注入：`DimensionPrompts` 五选一、KNOWLEDGE.md 需求子条目标准格式、
>   data_primitives SKILL.md 维度举例。
> - 已知限制：PeerDimension 单槽位，I/S 编号同句时 I 优先（先命中先占槽）；
>   S 编号入 `dimension_alias` 词表不在本期范围（alias 命中仍会压制编号正则兜底，语义不变）。
> - 测试：`DimensionStateManagerAliasIntegrationTest` +3（S 编号抽取/I 优先/指代词），
>   `DirectSkillExecutorTest` 键计数 12→13/13→14/15→16 两处；117 相关单测全绿（2026-10-20）。

> **2026-10-20 补充（页面/参数映射可见性修复）**：存量 `dimension_key_config` 表因 seed
> 只在建表时执行，新增内置键 `subItemNo` 不会出现在维度键字典页与 Skill 参数映射下拉
> （两页均 API 驱动）。修复：`DimensionKeyConfigRepository.ensureTable` 对存量表新增
> `ensureBuiltinKeyRows` 存在性补种（无行才插，软删不复活，同 timeRange 拍板语义），
> 重启后自动补出行；前端 `skillRouting.ts` 静态 fallback 清单同步加 subItemNo
> （Vue 已重新 build 落 static/）。

### 6.4 动态键正则捕获词表（2026-10-20 设计并实施）

> 场景：数值型口语条件「跨部门流转次数>3」要取**数字本身**作脚本参数——contains 词表
> 只能映射固定标准值，捕获不了变化量。拍板：**value 含 `$1`~`$9` 即正则捕获模板**，
> alias 按正则匹配、命中后捕获组替换 `$n` 产出维度值；不含 `$n` 的行维持 contains
> 语义，存量配置零影响。
>
> - 提取：`DirectSkillExecutor.extractExtraDimensions` → `AliasRegexSupport.extract`
>   （find 循环多命中去重保序、零宽命中终止、编译失败/组缺失跳过不抛）。
> - 校验：`DimensionKeyController.validateAnchorsIfNeeded` 前置通用段——正则行必须
>   可编译且引用组号 ≤ groupCount，否则 400。
> - 配置示例（transferTimes 键）：alias `流转次数\s*>\s*(\d+(?:\.\d+)?)`，value `$1`，
>   param_mapping `threshold → transferTimes`；「流转次数>3」→ params.threshold="3"。
> - 测试：`AliasRegexSupportTest` 9 例（模板识别/替换/去重/可选组 null 跳过/零宽终止/
>   校验）+ DirectSkillExecutor 2 例端到端（命中填参 / 未命中回退 LLM）。
> - 前端：词表编辑器加正则捕获提示（DimensionKeyPage.vue），已 build 落 static/。
