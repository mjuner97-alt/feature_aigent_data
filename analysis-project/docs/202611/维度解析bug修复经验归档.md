# 维度解析 bug 修复经验归档（正则盲区一类）

> 2026-11-04 存档 | 汇总 2026/10-11 多轮同类修复，供后续同类问题快速定位
> 涉及文件：DimensionStateManager.java / QuestionAnalysis.java / DimensionState.java / DirectSkillExecutor.java
> 关联方案：docs/202610/组名连接词列举alias嵌入抑制误杀修复.md、docs/202610/列举量词区间与多token维度解析盲区修复方案.md、docs/202611/混合省略日期区间解析修复方案.md

## 0. 问题类的统一定义

这一类 bug 的共同本质：**`EXPLICIT_*` / `SHARED_*` / `RANGE_*` 正则族对「维度值
前后还能出现什么」的容忍度，跟不上用户口语的灵活性**。规则引擎是「正则提取 →
level 判定 → 状态继承 → 参数装配」的串行管道，任何一环对该问法失配，维度值就丢。

诊断时按 §2 的五环排查表逐环核对，多数 5 分钟内可定位。

## 1. 已修 bug 清单（症状 → 根因环 → 修复点）

| 症状（用户问法） | 失配环 | 根因 | 修复 | 文档 |
| --- | --- | --- | --- | --- |
| 「三部车贷组和房贷组」只识别一个组 | ①提取 | EXPLICIT_TEAM 字符类含「和」，贪婪吞成 `车贷组和房贷组` | enumerationSubSpan 连接词/「组」边界拆分（组数≥2 作边界） | 202610/组名连接词… |
| 「车贷组房贷组」（零连接词连写） | ①提取 | 同上 | enumerationSubSpan §5.1 零连接词拆分 | 同上 |
| 「1、2、3**三个**季度」vestQuarter 全空 | ①提取 | 共享后缀列举正则列举段与「季度」间只允许空白，量词挡中间 | SHARED_SUFFIX_QUARTER/VERSION 插入 `(?:[一二两三四五六七八九十\d]+个)?` | 202610/列举量词区间… §1 |
| 「1-3季度」只收尾季 | ①提取 | 无区间正则，单数正则只吃「3季度」 | 新增 RANGE_QUARTER/RANGE_VERSION 闭区间展开+span 屏蔽 | 同上 §2 |
| 「I编码 F-应用码」itemNo 被挤掉 | ①提取+槽位设计 | PeerDimension 单类型单槽位，APPLICATION 占槽后需求项正则被门禁跳过 | 精确格式编码脱离槽位竞争：专字段 requirementNos/subRequirementNos 无条件提取，DirectSkillExecutor.extract() 合并 | 同上 §3 |
| 「**杭研** 1、2、3三个季度」整轮全空 | ②level 判定+③继承 | 杭研是 orgName 动态键词（刻意不进 deptName 词表）；detectLevel TIME 检查不含量词列举形态 → level=null；inheritDimensions level==null 直接 return previous 丢弃已提取维度 | detectLevel TIME 补齐 SHARED_*/RANGE_*；inheritDimensions level==null 但 hasAnyDimension() 时仍落地显式值 | 同上 §3.5 |
| 「10月26日到期」被吃成 10月版本 | ①提取 | 日期 span 未屏蔽，EXPLICIT_MONTH 从日期里吃值 | dateShieldSpans 先收集屏蔽再跑版本正则 | （既有） |
| 「F-FPA4季度」应用码贪婪吃成 F-FPA4 | ①提取 | 应用码正则吞数字 | 应用码让位规则：数字前截断，让出的「4季度」回流季度维度 | （既有） |

## 2. 五环排查表（诊断顺序）

维度丢失/多值时，从上往下逐环核对。**先本机写 scratch 测试复现**（参考
ReproHangyanTest 模式：`manager.analyzeQuestionRuleBased(q)` 打印 level/
explicitDimensions，再加 `previewState(q, null)` 看落地）——很多 bug 提取层
是好的，丢在后面。

### ① 提取环（extractExplicitDimensions）

- 复现 `analyzeQuestionRuleBased(q).getExplicitDimensions()` 是否含期望值；
- 不含 → 检查对应正则：维度值**之间**（连接词/量词/区间符/空格）和**前后**
  （前缀/后缀 token）各允许什么字符；
- 新增/放宽正则的三条军规：
  - 时间写法放宽后，**span 必须屏蔽**（防单数正则重复收集），加入 sharedTimeSpans；
  - 值域校验兜底（季度 1..4 双端越界整段弃收）；
  - **detectLevel 与提取层正则必须同步**（见 ②，Bug D 教训）。

### ② level 判定环（detectLevel）

- `analyzeQuestionRuleBased(q).getLevel()` 为 null 而提取有值 → level 检查
  漏了该形态；
- 常见漏判源：orgName 动态键词（杭研/研发部/基地等）不在 EXPLICIT_DEPT 也
  不在 vocab.deptValues（**刻意设计**，deptName 正则只认杭州开发X部）；
- 新增时间正则必须同时加进 detectLevel 的 TIME 分支。

### ③ 继承环（inheritDimensions）

- level==null 短路：现为「无显式维度才 return previous」，hasAnyDimension()
  为 true 时 deepCopy+applyExplicitDimensions 兜底（别再回退这个）；
- level 对应 switch 分支决定从 previous 继承哪些槽位，TIME 槽在 DEPARTMENT/
  PEER 等层级都继承，只有 level 正确才不会被下级覆盖；
- applyExplicitDimensions 语义：**显式时间 → 重置时间以下全部**（dept/peer/
  persons/requirementNos/subRequirementNos）；显式部门 → 重置部门以下；
  null 字段不清空继承值。

### ④ 直接执行链环（DirectSkillExecutor.plan()）

previewState 之后的独立处理，维度到这一步还可能被砍：

- `applyLedgerTruthScan` B③ 真值校验丢值（WARN "Ledger truth-check dropped"）
  与 3 键互斥裁决（WARN "真值域互斥裁决"）；
- `applyReanchorSuppression` 重锚抑制（我X/显式部门/drillDown 触发时压组/应用提及）；
- 精确编码专字段合并：extract() 中槽位 REQUIREMENT 值 ∪ state.getRequirementNos()；
- 缺参反问/回退：orgName 命中时缺 deptName 不反问（上级组织口径 :634-641）。

**诊断手段：grep 服务端日志** `Ledger truth-check dropped` / `真值域互斥裁决` /
`重锚抑制`，对照 trace 时间戳。

### ⑤ 参数装配环（buildParams / DirectSkillExecutor.extract）

- 槽位类型 → 参数名映射（REQUIREMENT→itemNo、APPLICATION→appName 等）；
- 专字段落 DimensionState 才能进参数（previewStateCarriesRequirementNosForParamAssembly
  用例保护）；
- 数值参数转换（整数→Long 小数→Double 失败保底）、array 恒传 List。

## 3. 修复套路模板（新 bug 对号入座）

**A. 维度值之间的分隔符不被容忍** → 放宽对应正则的中间段，同步考虑：
span 屏蔽、值域校验、detectLevel 同步。

**B. 高精度编码与口语值争槽位** → PeerDimension 单槽位是刻意设计（跨轮继承/
指代消解依赖它），**不要改成多槽**；定长精确编码走专字段（requirementNos/
subRequirementNos/projectNos 模式）无条件提取 + extract() 合并。

**C. 口语词/动态键词把整轮维度搞丢** → 先确认提取层（大概率是好的），再查
detectLevel 漏判 → inheritDimensions 短路。兜底层（hasAnyDimension 落地）
已加，新形态优先补 detectLevel。

**D. alias 词表命中与正则兜底打架** → aliasHit 门禁口径：任一 alias 命中
即关闭组/产品线/需求项正则兜底；alias 嵌入组 token 用 embeddedInTeamSpan
互斥判定（后缀>1 字符且互不包含才抑制）。改动前先看
DimensionStateManagerEmbeddedAliasTest 的既有边界。

## 4. 回归测试资产

| 测试类 | 覆盖 |
| --- | --- |
| DimensionStateManagerAliasIntegrationTest（77 用例） | alias 解析优先/正则兜底/列举量词区间/专字段共存/杭研 level 兜底/§13.4 分段抽取 |
| DimensionStateManagerEmbeddedAliasTest（19 用例） | alias 嵌入抑制边界、连接词/连写拆分 |
| DimensionStateManagerRuleTest（44 用例） | 纯正则老行为回归 |
| DimensionStateManagerClarificationTest（18 用例） | 歧义反问短路 |
| DirectSkillExecutorTest（130 用例） | 直接执行链/真值校验/重锚抑制/参数装配 |

新 bug 修复时：先加复现用例（红）→ 修 → 全绿 → 在本文档 §1 表格追加一行。

## 5. 已知边界（未修，勿当回归误报）

- 裸「三个季度」（无列举前缀）的「近 N 季度」锚点语义未建，另行立项；
- 中文数字列举（「一、二季度」）现网无实证未动；
- teamSpans 生成级拆分（组 span 喂屏蔽/层级判定多条路径，牵连大，另行立项）；
- 跨轮指代「它的缺陷密度」对 requirementNos 专字段不生效（走槽位路径）；
- F-PFXP 丢应用（2026-11-04 16:07 trace）疑似 ④ applyLedgerTruthScan B③，
  待服务端日志确认，暂未修。
