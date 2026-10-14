# 维度键字典与动态维度底稿

> 状态：现状已实施（2026-10-13 落地，随 skill-direct-exec-plan.md §9 交付）
> 用途：**后续新增这一块需求的底稿**——先读 §1-§3 现状，再按 §5 改动清单评估波及面；
> 设计拍板/未做项见 §4，新需求若推翻某条拍板请在 §6 补记。
> 上游方案：`docs/history/skill-direct-exec-plan.md` §9（原始设计，本文不重复其推导过程）

## 1. 定位与双轨模型

维度键分两类，**提取路径完全不同**，新需求先确认改哪条轨：

| | 内置 8 键 | 动态键（用户新增） |
|---|---|---|
| 键名 | versionPlan / vestQuarter / deptName / groupName / appName / productLine / itemNo / perName | 管理页任意新增（camelCase，如 qGate） |
| 提取方式 | `DimensionStateManager` 结构化规则（正则 + AliasResolver 词表消解 + §13.4 两遍分段抽取） | 对**当前问题**做纯 contains 扫描（alias 命中 → 收集标准值） |
| 状态归属 | 进 `DimensionState`，跨轮继承/反问/归一化全链路 | **不进 DimensionState，不跨轮继承**（§9.1 拍板） |
| 词表 | `dimension_alias` 表（独立体系，见 dimension-alias-config-plan.md） | `dimension_key_config.aliases` JSON 列（键自带词表） |
| 值形态 | 单值/多值经状态机 | `Map<key, List<value>>`（`extraDimensions`），多值 = 多 alias 同时命中 |

两轨在 `DirectSkillExecutor.valuesFor()` 汇合：内置键走 switch 命中，动态键走
`default -> extraction.extraDimensions().getOrDefault(key, List.of())`
（DirectSkillExecutor.java:313-327）。

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
   `extractExtraDimensions(question)`（DirectSkillExecutor.java:267）：
   - `repo.findAllEnabled()` 取启用键快照；
   - 跳过 `builtin` 键（内置键走结构化规则，见 §1）；
   - 逐键逐 alias `question.contains(alias)` → 命中收集 value（去重保序）；
   - 产出 `extraDimensions` 塞进 `DimensionExtraction`（record 尾部字段）。
2. `valuesFor()` 按 param_mapping 的键取值：动态键 → `extraDimensions`；
   单值 String / 多值 List 由脚本参数 schema 决定。
3. `activeKeys()`（DirectSkillExecutor.java:299）= 内置 8 键 ∪ 字典启用键，
   是 param_mapping 下拉与保存期校验的唯一值域来源（`SkillRoutingMetadataAdminService.java:122` 同源读取；
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

### 6.1 时间范围维度 `timeRange`（2026-10-14 设计，未实施）

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
  ensureTable 补"存在性补 seed"——行被删后重启不复活需明确：**不补**，删=关功能）；
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
- `activeKeys()` 透出 `timeRange.start` / `timeRange.end` 后缀形态，供下拉与保存校验。

#### 6.1.7 改动清单

- 新增 `TimeRangeExtractor`（L1 正则 + L2 键内最长匹配）+ `TimeRangeResolver` + 单测；
- `DirectSkillExecutor`：`extractExtraDimensions` 挂 timeRange 特殊分支（通用 contains
  循环跳过该键）、`valuesFor` 加 `.start/.end` 后缀特判、`activeKeys` 加后缀形态；
- `DimensionKeyConfigRepository`：seed 补 timeRange 行 + 保留键清单；
- `DimensionKeyController`/`SkillRoutingMetadataAdminService`：保存期锚点白名单校验；
- ResponseCache：timeRange 命中绕过或指纹强化（拍板点②）；
- 前端零改动（非 builtin，下拉/编辑复用现状）；`DIRECT_EXEC_DIMENSION_KEYS` fallback
  枚举不含 timeRange 可接受（fallback 仅字典库挂掉时兜底）。

#### 6.1.8 测试要点

1. L1 正则：`10月1日到10月15日`/跨年缺省/`2026-10-01~10-15`/非法日期（13月）不命中；
2. L2：本周/前一周/近一周 各命中；「前一周」不被更短 alias 抢；多锚点去重保序；
3. Resolver：周一当天问 THIS_WEEK（单日区间）、LAST_WEEK 跨月（如 9/29~10/5）、
   时区固定不受服务器 locale 影响；
4. 绑定：单值 String、mapping 未配 timeRange 时锚点提取无副作用；
5. 保存校验：非法锚点拒绝、LAST:3V 暂缓期拒绝；
6. 存量：393 单测基线不回归（extraDimensions 缺省行为不变）。

#### 6.1.9 拍板记录（2026-10-14，均取建议默认）

| # | 问题 | 结论 |
|---|---|---|
| ① | 「本周」区间 | 周一 ~ 今天 |
| ② | ResponseCache 处理 | timeRange 命中即绕过（不读不写） |
| ③ | 「近一周」区间 | today-6 ~ today（含今天） |
