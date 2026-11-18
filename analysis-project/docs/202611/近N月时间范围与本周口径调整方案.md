# 近N月时间范围与本周口径调整方案

> 2026-11-17 立项并实施完毕（同日，全量单测过，E2E 待内网）。需求四条：
> ① 本周=周一~今天；② 本月=本月1号~今天；③ 近x个月划归时间范围维度
> （仿近x周追问历史/未来）；④ 近x个版本保持版本维度（在建版本最后版本往前推x个）。

## 0. 需求与现状对照

| # | 需求 | 现状 | 差异结论 |
| --- | --- | --- | --- |
| ① | 本周 = 周一~今天 | `THIS_WEEK` = today-7 ~ today（滚动7天，2026-10-27 拍板②） | **要改**（推翻旧拍板） |
| ② | 本月 = 本月1号~今天 | `THIS_MONTH` = 本月1号 ~ today（TimeRangeResolver.java:99） | **已满足**，仅补测试锁定 |
| ③ | 近x个月 → timeRange 维度 + 方向追问 | `LAST_N_VERSIONS` 第二分支 `(?:近\|最近)(\d{1,3})个月` 吃掉 → 版本键 `LAST:{n}V`（按版本个数推，2026-10-19 拍板③） | **要改**（换轨） |
| ④ | 近x个版本 → 版本维度，在建版本回推 | `LAST:{n}V` 已是终版口径（VersionCalendarRepository，2026/11/11 LAST:nV 退化 bug 修复方案拍板①） | **不动**，仅拆分正则 |

## 1. 拍板点（2026-11-17 已拍板）

**⑴ 本周口径推翻旧拍板**：2026-10-27 拍板②"本周=today-7~today"废弃，改为
自然周至今：`本周一 ~ today`（`today.with(previousOrSame(MONDAY))`）。
- 恒返回 [本周一, today] 两端点时间范围，start/end 都传；周一当天 start=end=周一，
  不设单日特例。
- `LAST_WEEK` 不动（已是日历周 上周一~上周日）；`NEXT:{n}w` 不动（today~today+7n）。

**⑵ 近x个月区间口径（历史/未来两个方向）**：仿周/天的滚动口径——
- 历史：`LAST:{n}M` = today.minusMonths(n) ~ today（含今天，与 LAST:nw 同构）；
- 未来：`NEXT:{n}M` = today ~ today.plusMonths(n)（含今天，与 NEXT:nw 同构）。
- 备选（未采）：自然月口径（n个月前月初~今天 / 本月初~未来第n个月月末）。
  与既有"近N天/近N周=滚动"口径不一致，且"近3个月"业务语义更贴近滚动窗口。
  ✅ 2026-11-17 拍板：滚动窗口。

**⑶ 追问与答复**：完全复用 AMBIGUOUS:WEEKS 的链路与答复词表——
- 反问文案：「您说的「近N个月」是指历史近N个月（往前推），还是未来近N个月？请回复：历史 / 未来」
- 答复映射：含 未来/往后/接下来/今后/以后 → `NEXT:{n}M`；含 历史/往前/之前/过去/前面/先前 → `LAST:{n}M`；答非所问 → null（回退正常链路，同 WEEKS）。

**⑷ "近x个月版本"连写形态归属**：口语值方案 §3.1 的 `(?:月个)?版` 分支
（"近3个月版本"→`LAST:3V`）保持不变——仅裸"近x个月"换轨到 timeRange，
带"版本"后缀的仍归版本键（负向断言让位）。✅ 2026-11-17 拍板确认。

## 2. 改动点

### 2.1 TimeRangeResolver（时间范围锚点解析）

- `resolve`：`THIS_WEEK` → `{today.with(previousOrSame(MONDAY)), today}`；
  javadoc 拍板注记同步改写（§7② 口径变更记录）。
- `CONFIG_ANCHOR_PATTERN`：追加 `LAST:[1-9][0-9]*M|NEXT:[1-9][0-9]*M|AMBIGUOUS:MONTHS:[1-9][0-9]*`。
- `resolveParametric`：尾缀 `m` 分支（大写 M，与 d/w 并列）——
  `LAST:{n}M` = today.minusMonths(n)~today；`NEXT:{n}M` = today~today.plusMonths(n)。
- `isAmbiguousAnchor` / `ambiguityAskPrompt` / `resolveAmbiguousReply`：各加 MONTHS 分支。

### 2.2 TimeRangeExtractor（提取层）

- 新增 `LAST_MONTHS = 近\s*({CN_NUM})\s*个月(?!\s*版)` → `AMBIGUOUS:MONTHS:{n}`
  （中文数字复用 CN_NUM，"近一个月"/"近3个月"都命中；`(?!\s*版)` 让位
  "近x个月版本/近x个月个版本"连写形态归版本键，拍板⑷）。
- 注册进 `collectL1Spans`（与 LAST_WEEKS/LAST_DAYS 并列，span 优先级先于词表 L2）。
- `matchesDriftRegex`（ResponseCache 缓存兜底）：追加 LAST_MONTHS。

### 2.3 DimensionStateManager（版本键拆分 + level 判定）

- `LAST_N_VERSIONS` 删第二分支 `(?:近|最近)(\d{1,3})个月`（不再把"近x个月"
  产出为版本 `LAST:{n}V`）；第一分支 `(?:近|最近)(\d{1,3})个(?:月个)?版本` 保留。
- 新增 `LAST_N_MONTHS` 常量（与 2.2 同形态，本类只用于 detectLevel 判定）。
- `detectLevel` TIME 判定链追加 LAST_MONTHS 命中（**防套路C**：timeRange 提取轨
  命中但 level 漏判 → inheritDimensions 短路整轮维度丢失）。
- `extractExplicitDimensions` 不动：timeRange 走 TimeRangeExtractor 独立轨。

### 2.4 DirectSkillExecutor（反问链路）

- 三处短路点（:721/:945/:1218 的 `isAmbiguousAnchor` 过滤）与答复处理
  （:1881/:1970）均已按锚点前缀泛化，TimeRangeResolver 加完 MONTHS 分支后
  **自动生效，无需改动**；实施时核对这 5 处即可。

### 2.5 配置与数据

- 无 DB 迁移、无词表配置变更：AMBIGUOUS:MONTHS 是正则产物不进词表；
  `isValidConfigAnchor` 只作用于保存期校验，存量词表不含新形态无风险。
- 内置词表 近7天/近30天（LAST:7d/LAST:30d）不变。

## 3. 测试计划

| 测试类 | 新增用例 |
| --- | --- |
| TimeRangeResolverTest | ① 周三问"本周"→周一~周三；周一当天→单日；② LAST:2M/NEXT:2M 区间断言；③ MONTHS 反问文案 + 答复映射（历史→LAST:nM / 未来→NEXT:nM / 答非所问→null） |
| TimeRangeExtractorTest | 近3个月→AMBIGUOUS:MONTHS:3；近一个月→AMBIGUOUS:MONTHS:1；"近3个月版本"不产 MONTHS（让位版本键）；matchesDriftRegex 命中 |
| DimensionStateManagerRuleTest | "近3个月"不产版本 LAST:nV；"近3个月"+部门时 detectLevel=TIME（不整轮丢维度）；"近3个版本"仍产 LAST:3V；"近3个月版本"仍产 LAST:3V |
| DirectSkillExecutorTest | MONTHS 反问短路下发；答复"历史"→ LAST:nM 落地 start/end 参数 |

## 4. E2E 验证清单（内网）

1. "杭州一部本周的缺陷数" → start=本周一 end=今天；
2. "本月积压" → start=本月1号 end=今天（回归确认）；
3. "近3个月的缺陷趋势" → 反问 → 答"历史" → 脚本收到 start=today-3月 end=today；
4. "近3个月" 答"未来" → NEXT:3M；
5. "近3个版本的缺陷对比" → 不反问，版本列表=在建版本回推3个；
6. 反问轮继承：MONTHS 反问→答复后追问其他维度，不重复反问。

## 5. 风险

- **语义变更**：此前"近x个月"按版本个数推（2026-10-19 拍板③），换轨后老用户
  问法语义变化——属需求方主动调整，非回归。
- **THIS_WEEK 口径变更**：锚点值不进指纹、执行期才解析日期，无缓存/指纹污染；
  存量脚本只收 start/end 字符串，不感知口径。
- **"近x个月版本"双轨竞争**：正则负向断言 `(?!\s*个?版)` 保证 timeRange 让位，
  版本键独占（E2E 用例 5 覆盖）。

## 6. 实施记录（2026-11-17）

- TimeRangeResolver：THIS_WEEK=本周一~today（previousOrSame MONDAY）；LAST/NEXT:{n}M
  滚动口径；MONTHS 反问/答复三分支；javadoc 拍板注记改写。
- TimeRangeExtractor：LAST_MONTHS=`近\s*(CN_NUM)\s*个月(?!\s*个?版)`（实施中发现
  `(?!\s*版)` 挡不住"近3个月**个**版本"连写，收紧为 `个?版`）→ AMBIGUOUS:MONTHS:{n}；
  matchesDriftRegex 追加。
- DimensionStateManager：LAST_N_VERSIONS 拆成单分支 `(?:近|最近)(\d{1,3})个(?:月个?|个)?版本`
  ——实施中发现旧"近3个月版本"（月版本中缀）原由被删的"个月"分支兜底，中缀组
  `月个?|个` 补齐三种连写；新增 LAST_N_MONTHS 供 detectLevel TIME 判定（防套路C）。
- DirectSkillExecutor：5 处泛化点核对零改动（MONTHS 分支加在 TimeRangeResolver 后自动生效）。
- VersionPlanAnchorResolver.ANCHOR_PHRASE_REGEX 的 `(?:版本|月)` 保留：ResponseCache
  绕过判定对反问链路同样正确，与 matchesDriftRegex 双保险。
- 测试：全量过（新增 10 用例：Resolver 6 / Extractor 3 / RuleTest 改 1 加 1 /
  DirectSkillExecutor 3）。E2E 按 §4 清单待内网。
