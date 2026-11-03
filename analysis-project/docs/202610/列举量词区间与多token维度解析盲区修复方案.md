# 列举/量词/区间/多 token 维度解析盲区修复方案

> 2026-11-02 | 内网 E2E 实证 | **已实施（2026-11-02 当日落地，dimension 包 285 单测 + DirectSkillExecutorTest 128 单测全绿）**
> 同类背景：组名连接词列举 alias 嵌入抑制误杀修复.md（已修复）

## 0. 问题归类

三个新 bug 与已修复的组名连接词列举同属一类：**`EXPLICIT_*` 正则族对「两个维度值
之间还能塞什么」的容忍度过窄**——顿号/和已覆盖，但量词（三个）、区间符（1-3）、
多 token 共存（需求项编号+应用码）仍失配。用户口语里这些都是合法问法。

| # | 问法 | 现象 | 期望 |
| --- | --- | --- | --- |
| A | 杭州开发五部 1、2、3**三个**季度的研发周期 | vestQuarter 全空 | 1/2/3 三季 |
| B | 杭州开发五部 1**-**3季度的研发周期 | 只剩 2026年3季度 | 1/2/3 三季 |
| C | I20260922-0366　F-PFXP 详细设计质检 11月版 | itemNo 空，只有 app | 双值共存 |

## 1. Bug A：「1、2、3三个季度」整组失配

### 根因

`SHARED_SUFFIX_QUARTER`（DimensionStateManager.java:314）：

```
(?:(\d{4})年)?(\d{1,2})((?:[、，,和与]\s*\d{1,2})+)\s*季度
```

列举段后**只允许空白**再接「季度」。「1、2、3三个季度」中「三个」量词挡在中间
⇒ 共享正则不匹配；随后单数正则 `EXPLICIT_QUARTER` 要求数字紧贴「季度」，
「3三个季度」同样失配；`EXPLICIT_QUARTER_ALIAS`（[一二三四]季度）吃不到
「三个季度」⇒ 全空。（对照：「1、2、3季度」走共享正则三值全收——10:02:38 trace 实证。）

### 修复

列举段与「季度」之间允许「N个」量词（复用数量粒度短语字符集）：

```java
private static final Pattern SHARED_SUFFIX_QUARTER = Pattern.compile(
        "(?:(\\d{4})年)?(\\d{1,2})((?:[、，,和与]\\s*\\d{1,2})+)\\s*(?:[一二两三四五六七八九十\\d]+个)?\\s*季度");
```

量词内数字不参与列举分段（group(3) 仍只含连接词分隔的裸数字）。「1、2、3三个季度」
→ 1/2/3 三值。**同构放宽 `SHARED_SUFFIX_VERSION`**（「10、11、12三个月份版本」
同病同修），量词置于 `(?!延后)` 守卫之前不影响既有防误吃语义。

### 不做

- 裸「三个季度」（无列举前缀）：属「近 N 季度」锚点语义（未建），另行立项；
- 中文数字列举（「一、二季度」）：现网无实证，不动。

## 2. Bug B：「1-3季度」区间只收尾季

### 根因

无区间正则。「1-3季度」中「3季度」被 `EXPLICIT_QUARTER` 单数收走，「1」悬空。

### 修复

新增 `RANGE_QUARTER`，在共享列举正则之后、单数 `EXPLICIT_QUARTER` 之前跑，
命中即按闭区间展开并屏蔽 span（防单数正则重复收尾季）：

```java
// 季度区间 ("1-3季度"/"1~3季度"/"1到3季度" → 逐季展开)；两端 1..4 越界整段弃收
private static final Pattern RANGE_QUARTER = Pattern.compile(
        "(?:(\\d{4})年)?(\\d{1,2})\\s*[-~～—至到]\\s*(\\d{1,2})\\s*(?:[一二两三四五六七八九十\\d]+个)?季度");
```

展开规则：start≤end 且两端均 ∈[1,4] 才展开，否则整段弃收（防「1-9季度」垃圾）；
年份口径与既有写法一致（有前缀用前缀年，无前缀补当前年）。

**月份区间同构实施**：`RANGE_VERSION`（「10-12月份版本」）同一套展开逻辑，
复用 BARE_DATE_SPAN 既有屏蔽（「10月26日」不被误吃；区间符两侧是纯数字，
不与「10月26日」的 `月D日` 形态冲突）。风险点：无年份裸月份区间「10-12月」
不带「版本」后缀时是否收——**不收**，保持与 EXPLICIT_MONTH 同口径（须月份?
版本? 后缀），避免与日期区间歧义。

## 3. Bug C：需求项编号与应用码共存时 itemNo 被挤掉

### 根因

PeerDimension 是**单类型单槽位**。应用码 `F-PFXP` 先把 APPLICATION 槽占住
（DimensionStateManager.java:1117-1122），需求项正则被
`explicit.getPeerDimension() == null` 门禁（:1163）整个跳过；DirectSkillExecutor
按槽位类型映射（:1560-1566，`REQUIREMENT -> itemNo`），槽位被占 ⇒ itemNo=[]。

需求项编号 `I\d{8}-\d{4}` / 需求子条目 `S\d{8}-\d{6}` 是**定长高精度编码**，
与组名/产品线这类歧义口语值不同，不该和应用码争槽位——「I20260922-0366 F-PFXP
详细设计质检」里两个都是用户真实意图（需求项属于该应用）。

### 修复（最小侵入：正则命中结果脱离槽位竞争）

1. `QuestionAnalysis.ExplicitDimensions` 新增专字段 `requirementNos` /
   `subRequirementNos`（仅收**精确格式**编码，与 peer 槽位无关，始终提取）；
2. DimensionStateManager 提取处改为：精确编码**无条件**收进专字段（aliasHit
   门禁保留不动——歧义 alias 让位的既有口径不变）；peer 槽位逻辑照旧
   （无 app 时 REQUIREMENT 仍进槽位，跨轮继承/指代消解/组装 prefix 全不动）；
3. DirectSkillExecutor.extract()（:1558-1567）合并：`itemNo = 槽位REQUIREMENT值
   ∪ requirementNos`，subItemNo 同理；
4. 实施补充：专字段同时落到 `DimensionState.requirementNos` / `subRequirementNos`
   （生命周期与 peerDimension 对齐——时间/部门显式指定即重置，peer 槽位显式设置时以
   本轮提取为准防跨轮串值）⇒ app+item 共存时 itemNo **也能跨轮继承**（优于原设计
   的「不继承」局限）；跨轮指代（「它的缺陷密度」）仍走槽位，为已知边界。

### 变体核对（均由同一根因解释）

| 问法 | 失败环节 |
| --- | --- |
| 杭州开发二部　I20260922-0366　F-PFXP 详细设计质检　11月版 | 同上（dept 不占 peer 槽，无关） |
| 需求项I20260922-0366 F-PFXP … / F-PFXP应用I20260922-0366 … | 同上（「需求项」前缀不参与正则） |

## 4. 改动点清单

| 文件 | 改动 |
| --- | --- |
| DimensionStateManager.java | SHARED_SUFFIX_QUARTER / SHARED_SUFFIX_VERSION 量词容忍；新增 RANGE_QUARTER / RANGE_VERSION 展开与屏蔽；需求项/子条目精确编码无条件提取进专字段；applyExplicitDimensions 专字段生命周期对齐 peer 槽位 |
| QuestionAnalysis.java（ExplicitDimensions）/ DimensionState.java | 新增 requirementNos / subRequirementNos（含 build() 扁平 JSON 回填） |
| DirectSkillExecutor.java | extract() 合并专字段到 itemNo / subItemNo（去重保序） |
| 测试 | DimensionStateManagerAliasIntegrationTest 补 10 用例（A/B/C + previewState 落状态） |

## 5. 测试计划（E2E 前置单测）

1. 「杭州开发五部 1、2、3三个季度的研发周期」→ 2026年1/2/3季度；
2. 「杭州开发五部 1-3季度的研发周期」→ 2026年1/2/3季度（含 1~3 / 1到3 变体）；
3. 「10、11、12三个月份版本」→ 三个月版本值；
4. 「10-12月份版本」→ 2026年10/11/12月份版本；
5. 「1-9季度」→ 空（越界弃收）；「1-3季度和5季度」→ 1/2/3/5（区间+单数并存）；
6. 「I20260922-0366 F-PFXP 详细设计质检 11月版」→ app=[F-PFXP] 且 itemNo=[I20260922-0366]；
7. 回归：「I20260208-0005 的缺陷密度」（无 app）itemNo 走槽位路径不变；
   「杭州开发三部10月版各组」等既有 275 用例全绿。

## 6. 风险

- 量词/区间正则放宽可能误吃（「1-3季度」在别处语义）：季度值域 1..4 双重校验兜底；
- 需求项专字段绕过 aliasHit：仅精确格式编码，歧义风险与既有裸码让位逻辑无关；
- PeerDimension 槽位设计不动，跨轮继承局限见 §3 已知边界。
