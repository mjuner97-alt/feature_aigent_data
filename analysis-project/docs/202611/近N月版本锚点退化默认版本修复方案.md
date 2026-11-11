# 近N个月/近N个版本锚点退化为默认版本修复方案

> 状态：**已拍板（2026-11-11，§5 两项全拍）+ 已实施**（F1/F2/F3B 代码落地，
> 单测见 §4；F4 内网诊断与 E2E 待内网执行）。
>
> 需求：修复 `LAST:{n}V` 锚点解析失败后静默回退"全量在建版本"的 bug——用户问
> "近4个月/近4个版本"，无论 n 是几，实际传给脚本的 `version_plan` 永远是当前在建
> 版本默认值（本例 10/11/12月版本），n 语义完全丢失。

## 1. 现象（2026-11-11 内网 case）

问题：`杭州三部住房组近4个月代码提交情况`

```
维度解析：versionPlan = ["LAST:4V"]          ← 提取正确
实际传参：version_plan = ["2026年10月份版本", "2026年11月份版本", "2026年12月份版本"]
```

- 期望（拍板①终版口径，在建锚点 12月版往前推 4 个）：`9/10/11/12月版本`；
- 实际 = **全量在建版本默认值**（缺 9月版、含未投产的 12月版恰是默认兜底的形态）；
- 用户反馈：近 3/4/5 个版本结果完全一样——n 被吞。

## 2. 根因链

整条链路（提取 → 解析 → 兜底）三层，其中②是核心：

### ① 提取层：近N个月 → LAST:nV（现状维持，非本 bug 核心）

`DimensionStateManager.java:347` `LAST_N_VERSIONS` 正则把"近N个版本"与"近N个月"
都归 versionPlan 键 → `LAST:{n}V`（2026-10 口语值方案拍板③："近X个月"也归版本键，
周/天后缀归 timeRange）。提取本身正确（本例 LAST:4V 无误），本方案不动该映射
（是否改口径见 §5 拍板①）。

### ② 解析层：LAST:nV 展开依赖"在建版本 ∩ 日历快照"，miss 后 n 静默丢失（核心）

`VersionPlanAnchorResolver.resolveAnchor` LAST: 分支（VersionPlanAnchorResolver.java:260-268）：

```java
paramVersions = calendar.lastVersions(parseLastN(anchor), lastInBuildAnchor());
```

失败路径逐级退化：

1. **`lastInBuildAnchor()`（VersionPlanAnchorResolver.java:301-341）只认
   "在建版本 ∩ 日历快照"**：在建版本逐个去 `rdc_train_calendar` 快照（SNAPSHOT_SQL，
   `WHERE 火车类型 IN ('直接发布火车（四周）', '非直接发布火车（四周）（4+4）'）`) 里查
   投产窗口，全查不到 → anchor = null。
   **嫌疑最大的差异点**：在建版本 SQL（VersionPlanKnowledgeHook.VERSION_PLAN_SQL）
   **不筛火车类型**，快照 SQL **筛**——在建版本行的火车类型若不在那两种之内
   （或版本计划名/投产日为空），快照必 miss；
2. anchor=null → `lastVersions` 退化路径（VersionCalendarRepository.java:175-185）：
   按快照 v_end 排序 `limit(n)`——快照缺旧版本/gauss 不可用时**返回数量 < n 或为空**，
   且静默截断无任何日志；
3. 展开 空 → `resolveAnchor` 返回 null → `expandVersions` 丢弃锚点
   （VersionPlanAnchorResolver.java:274）。

注意：**月序回推路径（VersionCalendarRepository.java:160-172）本身不依赖日历快照、
缺位月照常生成、跨年自动进位，是最稳的路径**——但它要求 anchor 是标准月版名，而
anchor 的产出（lastInBuildAnchor）恰恰依赖快照命中，整条月序路径被②1 一步卡死。

### ③ 兜底层：空值静默落"全量在建版本"默认，n 被吞且无观测

锚点展开空 → versionPlan 无值 → `buildParams` 走
`defaultTimeValues("versionPlan")`（DirectSkillExecutor.java:1437、2541-2554）=
全量在建版本（10/11/12月版本）。兜底本身是 fail-safe 拍板设计，但对"近N个"这种
**带明确语义的口语**，静默吞 n 且无日志、无前端提示，用户无从感知。

## 3. 修复方案

### F1（核心）：月序锚点不再依赖快照命中——LAST:nV 从在建版本名直接构造月序回推

`lastInBuildAnchor()` 快照 miss 时的兜底：从 `currentVersions()` 里挑**标准月版名形态**
（`MONTH_VERSION_ANCHOR` 可匹配，如"2026年12月份版本"）中年月最大者作 anchor，
传给 `lastVersions` 走月序回推。月序路径不查快照，gauss 不可用也能展开，n 永远给 n 个。

- 改动点：`VersionPlanAnchorResolver.lastInBuildAnchor`（或在 resolver 层加一步
  "快照 miss → 在建版本名正则取最大月"），`VersionCalendarRepository` 不动；
- 修复后本例：在建 10/11/12月版本 → anchor=12月版 → 月序回推 4 → 9/10/11/12月版本 ✓。

### F2：退化路径数量保真 + 可观测

快照路径保留（anchor 非标准月版名时仍有意义），但两处加固：

- `lastVersions` 退化路径返回数量 < n 时打 `log.warn`（含 n、快照版本数）；
- F1 生效后该路径只剩"anchor 非月版名"一种入口，量少且均有日志。

### F3（拍板②：选 B）：锚点解析失败不兜底默认，走缺参反问

拍板②已定 **B**（日历一般不会挂，F1 修好后月序路径基本不失败，宁可诚实反问也不
静默答错）：

- **触发条件收窄**：仅当 extraction 里**带锚点但展开为空**（锚点非空、展开结果空）时
  跳过 `defaultTimeValues` 兜底，versionPlan 按"必填缺失"走缺参反问
  （2026/10/15 方案，DirectSkillExecutor mapping 内反问链路）；
- **未提及时间的问法不受影响**：extraction 无任何 versionPlan/vestQuarter 值时，
  `defaultTimeValues` 补全量在建版本的既有拍板**保持不变**（否则"查一下提交情况"
  这类无时间问法会被误伤反问）；
- 实现要点：`valuesFor`/`buildParams` 需区分"提取层无值"与"有锚点但展开空"两种空
  （如在 extraction 侧回传 `anchorExpandFailed` 标记，或 valuesFor 对锚点空返回
  哨兵），不能一刀切关兜底；
- 反问话术携带失败原因："时间口径解析失败（版本日历暂不可用），请指定具体版本，
  如：10月版、11月版"；
- **实施记录（2026-11-11）**：两种空以 `timeAnchorExpandFailed(extraction, key)` 静态判定
  （DirectSkillExecutor）——键为 versionPlan/vestQuarter 且提取层原始值含锚点（vestQuarter
  空时同源看 versionPlan）即视为解析失败，在 buildParams 缺值分支 `continue` 跳过默认；
  同时 `log.warn` 留痕。无锚点时原 `defaultTimeValues` 兜底不变。

### F4：诊断验证（内网执行一次核对根因，2026-11-11 已实施代码、诊断待内网）

内网执行一次并核对日志，确认快照 miss 的具体原因：

1. `VersionPlanKnowledgeHook refreshed versions: [...]`（在建版本名清单）；
2. `VersionCalendarRepository` 快照内容（`snapshotSize()` / 诊断口）里是否含同名版本；
3. 若不在 → 查 `rdc_train_calendar` 在建版本行的 `火车类型` 与 `常规投产日`
   （快照 SQL 的 WHERE 与 GROUP BY 是否把它们滤掉）；
4. 结论决定 F1 之外是否还要放宽 CAL_SNAPSHOT_SQL 的火车类型过滤（如需改 SQL 单独立项）。

## 4. 测试计划

| 层 | 用例 |
|---|---|
| VersionPlanAnchorResolverTest | 快照 miss + 在建版本含月版名 → 月序展开 n 个；LAST:9V 跨年回推（12月版 → 4~12月版）；在建版本全非月版名 → 走快照退化路径 |
| VersionCalendarRepositoryTest | 退化路径快照缺旧版本返回数 < n 时的 warn 与返回值 |
| DirectSkillExecutorTest | 锚点展开空（F3B）→ 跳过 defaultTimeValues，走缺参反问；extraction 无时间值 → 仍补全量在建版本默认（既有拍板回归）；两种空区分正确 |
| 单测存量 | LAST:nV 相关既有用例全绿（879+ 基线） |
| E2E 内网 | "杭州三部住房组近4个月代码提交情况" → version_plan = 9/10/11/12月版本；"近6个版本" → 6 个值；gauss 断连时 LAST:nV 仍按月序展开；日历不可用 + 无在建版本 → 反问轮话术正确（内网前端纯 text 事件格式） |

## 5. 拍板结果（2026-11-11）

- **拍板①："近N个月"是否维持归版本键** → **本期不挪**，维持 2026-10 拍板③口径
  （近N个月 ≈ 近N个版本）。本方案 F1 修好即闭环；改自然月口径另行立方案。
- **拍板②：§3 F3 兜底行为** → **选 B**：锚点展开失败不兜底默认，走缺参反问
  （理由：日历一般不会挂，F1 后月序路径基本不失败，失败时宁可诚实反问）。
  实现细则见 §3 F3——仅"有锚点但展开空"触发反问，"未提及时间"仍走在建版本默认。

## 6. 影响面

- 仅 LAST:{n}V 一个锚点的解析路径；THIS_V / THIS_Q / YEAR_V / HALF_V / DELAYED 不动；
- `containsAnchorPhrase` ResponseCache 绕过逻辑不变（提取层无改动）；
- `defaultTimeValues` 兜底**收窄而非删除**：仅"extraction 无时间值"的问法继续兜底
  在建版本；"有锚点但展开空"改走反问（F3B），需两种空可区分；
- 缺参反问轮为既有链路（2026/10/15 方案）复用，无新增端点；
- 时间窗口 `.start/.end` 后缀键随展开结果自动修正，无单独改动。
