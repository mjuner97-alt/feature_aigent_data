# 我X按需注入与细粒度作用域抑制方案

> 关联：docs/202610/人员台账维度方案.md §3.3（我X enrich）。2026-10-23 E2E 实证问题，
> 本文先定逻辑再实施。

## 1. 问题定位（trace 复盘）

问句：**"我部4季度需求项的平均生命周期"**（只说了"我部"，未提组/产品线/应用）。

实际传参：

```json
{"product_line":"集成管理", "stat_group":"集成管理组", "quarter":"2026年4季度", "dev_dept":["杭州产品部"]}
```

脚本多级维度参数并存，四个条件 AND 过滤 → 空集。期望只有 `dev_dept + quarter`。

### 根因①（本例主因）：enrichPersonal 全量注入

DirectSkillExecutor.java:880-887，`enrichPersonal` 的实现是：**任何 我X 命中
（personal.anyHit()）就把台账归属行四维度全部 append**——deptName+groupName+appName+
productLine 一次性注入。"我部"命中 → 用户台账行里的 集成管理组/集成管理（用户所属组和
产品线）也跟着进了 params。

`PersonalResolution` 明明已带 per-type 命中标志（myDept/myGroup/myApp/myProductLine/
myself，PersonalDimensionResolver.java:54-61），但 enrich 没有用——这是 10/22 实现时
"双命中无冲突"拍板的过度实现：设计意图是"我的组和我的应用"各填各的，写成了"命中任意
一个、四维度全填"。

### 根因②（叠加）：跨轮继承残留

上一轮显式提及的组/产品线（peerDimension）经 `previewState → inheritDimensions →
extract`（DirectSkillExecutor.java:1041-1073）保留到本轮。本轮"我部"在语义上是
**作用域重锚**（把查询范围重设为部门级），上一轮的细粒度过滤值已不成立，但仍被继承。

## 2. 语义设计

**核心原则：我X 是精确的作用域声明，说哪个维度注哪个；重锚之后，本轮未重新提及的
细粒度过滤值一律失效。**

- **R1 我X按需注入（修根因①）**：enrichPersonal 改为按 per-type 标志分别注入：
  - 我部 → 只注 deptName（台账 depts，兼岗多部门保留多值）
  - 我组 → 注 deptName + groupName（组名在台账内跨部门可能重名，连带注入台账部门）
  - 我的应用 → 只注 appName
  - 我的产品线 → 只注 productLine
  - 我（裸）→ 只注 perNo（=userId）+ perName
  - 命中多个（"我的组和我的应用"）→ 各注各的，互不连带
- **R2 细粒度作用域抑制（修根因②）**：本轮问句触发"重锚"（条件见 §3.2）时，
  对 组/应用/产品线/人 4 个细粒度维度**按维度独立判定**：本轮问句未显式提及该维度
  → extraction 对应字段清空（继承值丢弃）；显式提及 → 保留。显式提及判定含 我X
  per-type 命中（我组即组显式提及，R1 刚注入的台账组名不因抑制被误清），见 §3.2 矩阵。
- **R3 豁免**：
  - 本轮显式提及："我部集成管理组4季度" → 组过滤保留（产品线仍未提 → 抑制）
  - 指代词："那个组呢" → isHasReference 命中时整体不抑制（指代消解依赖上一轮 state）
  - 时间/需求项/指标：查询主体不是作用域，跨轮继承照旧，永不抑制
- **R4 下钻协同**："我部各组/各产品线" → drillDown=分组输出，组/产品线**过滤**值本就
  不该传（§5.1 守卫已拦粒度词垃圾值，正交性拍板不变）；继承的真组名过滤值由 R2 抑制。

## 3. 生效层

### 3.1 L1：enrichPersonal 按 per-type 注入（一处改）

用 `personal.myDept()/myGroup()/myApp()/myProductLine()/myself()` 替换 `anyHit()`
的判断：每标志只 append 对应 ledger 字段；**myGroup 连带 deptName 双注**（台账部门
跟随，兼岗多值保留）。改动集中在 DirectSkillExecutor 单个方法 + 对应单测断言调整。

### 3.2 L2：plan() 重锚抑制（新增一步，buildParams 之前）

```
我X enrich（R1 改造后）→ 人名扫描 → [新增] 重锚抑制 → 下钻展开 → buildParams
```

重锚触发条件（任一）：
a. `personal.anyHit()`（我X 命中，含我部/我组/我的应用/我的产品线/裸我）
b. 本轮问句显式命中部门（EXPLICIT_DEPT / 词表 dept 命中）——非"我部"的显式部门
   问句同一重锚语义（§6-2 拍板：需触发）
c. drillDown extraDimensions 非空（分组输出语义）

抑制判定（按维度独立）：
| 维度 | "本轮显式提及"判定 | 未提及则 |
|------|-------------------|---------|
| 组 | EXPLICIT_TEAM 正则命中且过守卫（复用/暴露 DimensionStateManager.hasTeamOrProductLineHit，含词表反哺 stems）∪ personal.myGroup() | 清 groupName |
| 产品线 | 同上（EXPLICIT_PRODUCT_LINE 分支）∪ personal.myProductLine() | 清 productLine |
| 应用 | EXPLICIT_APP / bareAppCodes 命中 ∪ personal.myApp() | 清 appName |
| 人 | personScan 产物 perName/perNo 非空（唯一命中/重名反问路径）∪ personal.myself() | 清 perName/perNo/persons |

"显式提及"并入 我X per-type 命中，保证 R1 刚注入的台账值不被抑制误清。deptName 不在
抑制矩阵（重锚目标是部门级作用域，注入/继承的部门一律保留）。

指代豁免：`QuestionAnalysis.isHasReference()` 命中 → 跳过整个抑制
（analyzeQuestionRuleBased 已 public，plan 里可直接调）。

### 3.3 L3：§3.5 快照补缺收窄（§6-3 拍板：一律不补）

followup §3.5"同 skill 必填参数快照兜底"目前按 required 全量补（:549-560）。收窄：
mapping value ∈ {groupName, appName, productLine, perName, perNo} 的参数**一律不补缺**，
不分重锚轮与否（细粒度过滤值跨轮补进来几乎总是 AND 过度过滤）；部门/时间补缺保留
（补缺本为修"时间层追问丢部门"）。否则上一轮 params 里的 product_line 会在本轮被
补回来，绕过 L2。

## 4. 例子矩阵

| 问句 | 本轮显式 | 继承/台账残留 | 传参（目标） |
|------|---------|--------------|-------------|
| 我部4季度需求项 | dept(我部) | 台账组/产品线（R1 后不再注入）+ 旧轮残留（R2 抑制） | dev_dept + quarter |
| 我部集成管理组4季度 | dept+group | 旧产品线（R2 抑制） | dev_dept + stat_group + quarter |
| 我部各组4季度 | dept + drillDown | 旧组名（R2 抑制） | dev_dept + drill_down |
| 那个组呢（指代） | —（豁免） | 保留 | 原行为 |
| 我组和我的应用对比 | group+app（我组连带台账部门） | 旧产品线（R2 抑制） | dev_dept + stat_group + app + 时间 |
| 杭州三部4季度需求项 | dept(显式) | 旧组/产品线（R2b 抑制，已拍板） | dev_dept + quarter |

## 5. 兼容性/风险

- **追问场景**："那各应用呢"/"那3月呢"——前者走 drillDown（c 触发，组过滤抑制=正确），
  后者时间继承不受影响；"那个组呢"指代豁免。
- **"我组"连带部门（§6-1 拍板）**：R1 双注入 deptName + groupName，脚本收双参即可；
  不再存在"我组不带部门"路径。
- **LLM 回退轨不受影响**：L2 只在直接执行 plan 内；state 本身不动（保指代能力），
  DimensionStateMiddleware 注入的上下文照旧。
- **重名反问轮**：pending 补参轮走 withResolvedPerson 重建 extraction，L2 需对补参轮
  路径同样生效（或接受补参轮继承 preParams——实现时定）。

## 6. 拍板结论（2026-10-23）

1. **"我组"连带部门：需要连**（组名在台账内跨部门可能重名）。R1 中"我组"=
   deptName + groupName 双注入（台账部门跟随，depts 兼岗多值保留），不再存在
   只注组名的路径。
2. **显式部门问句触发重锚：部门名也触发**。"杭州三部4季度"与"我部4季度"语义等价，
   旧细粒度残留同样丢弃（R2b 生效）。
3. **§3.5 细粒度补缺：一律不补**（不分重锚轮）。理由：细粒度过滤值跨轮补进来几乎
   总是错的（AND 过度过滤）；时间/部门补缺场景（追问丢部门）保留。
4. **重名反问补参轮也跑 L2 抑制：补参轮也跑**（补参轮的 extraction 重建后统一走
   抑制再 buildParams，行为一致）。

## 7. 实施拆分（四拍板全定，合并一期）

- **一次性实施**：R1（enrichPersonal per-type，我组双注 deptName+groupName）+
  R2（L2 重锚抑制 + 指代豁免 + 触发含显式部门 b）+ L3（§3.5 补缺收窄）+
  重名反问补参轮同跑抑制。
- 单测（DirectSkillExecutorTest 人员段改造）：我部不注组/产品线、我组=组+部门双注、
  重锚清继承（我部/显式部门）、显式组保留、指代豁免、补参轮抑制。
- 预计触面：DirectSkillExecutor、DimensionStateManager（暴露显式命中判定）、
  两个测试类。
- 文档：人员台账维度方案.md §3.3 补 R1/R2 修订记录。

## 8. 我X 正则动态配置化（2026-10-26 新增需求，随 perRole P1 一期实施）

> 动机（用户拍板）：MY_DEPT/MY_GROUP/MY_APP/MY_PRODUCT_LINE/MYSELF 全部硬编码，
> 业务说法不断出现新变体（"我分管的产品线""我担任…"），怕固定化枚举不全，
> 要求像维度键词表一样**前端可随时添加、TTL 热生效、不用发版**。

### 8.1 承载：正则放它触发的目标维度键行（regex 型 alias 条目）

`AliasMapping` 加可选 `type` 字段：`{"alias":"<正则串>","value":"<标签>","type":"regex"}`；
缺省或其它值 = 同义词条目（**存量行为零变化**）。分键承载：

| 键行 | value 标签 | 承载正则 | 首版 seed |
|---|---|---|---|
| deptName | `myDept` | 我部/我们部门/本部门… | 现 MY_DEPT 硬编码正则 |
| groupName | `myGroup` | 我的组/我们组/我的团队… | 现 MY_GROUP 硬编码正则（仍无裸"我组"） |
| appName | `myApp` | 我的应用/我的系统… | 现 MY_APP 硬编码正则 |
| productLine | `myProductLine` | 我的产品线/我分管的… | 现 MY_PRODUCT_LINE 硬编码正则 |
| perNo | `myself` | 我/我个人/我本人 + 我担任/我现任/我所担任 | 现 MYSELF 核心 + perRole 变体 |
| perRole | `extract` | 角色提取（第一捕获组=角色值） | 见 岗位角色内置维度方案.md §3.1c |

安全约束：**所有既有同义词匹配路径必须过滤 `type=regex` 条目**（AliasMapping
加 `isRegex()`，词表匹配/时间锚点/下钻 contains-scan 等消费方统一走
`isSynonym()` 判定）——否则正则串被当字面 alias 匹配产生垃圾命中。

### 8.2 加载与生效

- PersonalDimensionResolver 注入 DimensionKeyConfigRepository，findAllEnabled
  TTL 快照取 6 键行 → 按 value 标签分组 → `Pattern.compile`（失败跳过 + log）
  → 产出不可变 MyPatterns 快照；
- resolver 五处硬编码正则改用快照；行缺失/aliases 空 → 内置默认兜底（seed 保证
  行存在，此为防御）；
- **R1/R2 逻辑零改动**：per-type 标志（myDept/myGroup/myApp/myProductLine/myself）
  照旧由 resolver 产出，enrichPersonal/applyReanchorSuppression 只认标志不认正则；
- TTL 内热生效，新说法加条目即生效。

### 8.3 白名单与页面

- `VOCAB_EDITABLE_BUILTIN_KEYS` 3 → **8**：deptName（既有）+ versionPlan/vestQuarter
  （既有，口径值方案已扩）+ groupName /
  appName / productLine / perNo / perRole（否则内置键 aliases 页面锁死）；
- 副作用（可接受）：这五个键的同义词词表同步解锁——groupName/appName 等顺带获得
  口语别名能力，业务想用就配，不用不配；
- 维度键页 aliases 编辑器加 type 列（同义词/正则 切换），regex 条目 value 栏填标签。

### 8.4 边界（本期不配置化）

- DimensionStateManager 的 EXPLICIT_TEAM / EXPLICIT_PRODUCT_LINE（R2 触发 b 的
  显式部门/产品线判定）：值域正则稳定，deptName 词表 alias 已覆盖大部分口语 → 不动；
- 裸"我组"仍不 seed（避"我组装"误伤）；业务确需时自行加条目、误伤自负；
- myself：regex 条目与核心硬编码 MYSELF 是**叠加判定**（任一命中即 myself=true），
  核心负向断言 `(?![一-龥组们的])` 不影响配置变体，反之亦然。

### 8.5 单测

- 配置加载：6 键 seed 等价编译、非法正则跳过、行缺失兜底；
- resolver：自定义配置条目（如 "我分管的" → myProductLine）命中 per-type 标志；
- 同义词路径过滤：deptName 词表匹配不把 regex 条目当字面 alias；
- 既有我X全量测试保持绿（seed = 现硬编码正则，行为等价）。

## 9. 垃圾代词值 block 屏蔽（2026-10-26 E2E 新增，配置化）

### 9.1 背景（E2E 实证）

userId=880000035 在维度键字典已配 regex 条目（groupName 行 `我的组|我们组|...` ⇒ myGroup、
productLine 行 `我的(产品线|条线|业务线)` ⇒ myProductLine）的前提下：

| 问句 | extraction 产物 | 问题 |
|---|---|---|
| 我的组10月的数据 | groupName = ["我的组", "移动展业组"] | "我的组" 被 EXPLICIT_TEAM `([一-龥A-Za-z0-9]{2,}组)` 抢成组名垃圾值，与台账注入真值 双值传参 |
| 我的小组10月的数据 | groupName = ["我的小组", "移动展业组"] | 同上 |
| 我们组10月的数据 | groupName = ["我们组", "移动展业组"] | 同上 |
| 我的产品线10月的数据 | productLine = ["我的产品线", "数据产品线"] | EXPLICIT_PRODUCT_LINE `[一-龥]{2,}产品线` 同样抢词 |

语义上："我的组/我的产品线" 是 **口语代词短语**，归属 PersonalDimensionResolver 的
myGroup/myProductLine 路径（注入台账真值），结构化规则不应再把它当真值收。
粒度词守卫五层（§5.1，各/每/按 首字 + 尾缀 + 词表反哺）拦不住——前导字是 "我"。

### 9.2 方案：目标键行 block 型 regex 条目（第六层守卫，配置驱动）

不走硬编码（用户拍板：垃圾词过滤也要前端可配，类似 §5.1 第五层 drillDown 词表反哺的思路）。
复用 §8 的 regex 型条目机制，**新增用途标签 `block`**：

- **承载**：正则放在它要屏蔽的目标键行（同 §8.1 "正则放目标键" 原则）：
  groupName 行配 `我` ⇒ block、productLine 行配 `我` ⇒ block、appName 行配 `我` ⇒ block；
- **语义**：`type=regex` 且 `value=block` 的条目，alias 编译为该键候选 token 的
  **拒收正则**——结构化规则（EXPLICIT_TEAM/EXPLICIT_PRODUCT_LINE 等）产出的候选 token
  在该键的 block 正则上 `find()` 命中 → 拒收该候选（同粒度词候选跳过语义，跳过继续找下一个）；
- **基线 seed**：`我` 一个字（find 语义 = token 含 "我" 即垃圾，一次拦掉
  我的组/我们组/我的小组/我的统计组/我的产品线/我的条线/我的业务线/我的应用 全部变体，
  等价于此前讨论的 containsPronounWo 硬编码，但由配置承载）。真实组名/产品线/应用
  不含 "我"（台账枚举佐证），无误伤面。

### 9.3 承载与 seed

- `DimensionKeyConfigRepository.REGEX_SEEDS` 扩展（同 §8.1 append-if-missing-by-tag 语义，
  删除后重启补回——block 词是行为基线，与 §8 regex 基线同拍板）：

| 目标键行 | 新增条目 | 说明 |
|---|---|---|
| groupName | `{"alias":"我","value":"block","type":"regex"}` | 拦 我的组/我们组/我的小组/我的统计组/我的团队 等 |
| appName | `{"alias":"我","value":"block","type":"regex"}` | 拦 我的应用/我的应用群组 等 |
| productLine | `{"alias":"我","value":"block","type":"regex"}` | 拦 我的产品线/我的条线/我的业务线 等 |

- deptName/itemNo/subItemNo/perName 不加：值域固定（部门枚举/编号格式/台账字典匹配），
  结构化正则本身抢不到代词短语。

### 9.4 运行期加载与守卫插入点

- `DimensionStateManager` 新增 `blockPatterns(String key)`：findAllEnabled TTL 快照上再包
  60s 短缓存（复用 granularityStems 的加载模式），取该键行 isRegex 且 value=block 的
  alias 编译列表；配置缺失/读失败 → 空列表（该键无 block 屏蔽，fail-open）；
- 插入点 = 既有守卫链追加一项（与 isGranularityToken/hasGranularityTail/containsAnyStem
  并列，五处判定点全部覆盖）：
  1. `findTeamToken` 屏蔽文本循环 + 原文回退循环（跳过继续找，全部被拒时同粒度词语义
     不回退原文）；
  2. `findProductLineToken` 两处循环同上；
  3. `hasNonGranularityMatch`（hasExplicitTeamHit / hasExplicitProductLineHit /
     detectLevel 共用）——**关键**：R2 "本轮显式提及" 判定同步生效，"我的组" 不再被当成
     显式组提及保护垃圾值，也不误触发组维度重锚抑制。

### 9.5 边界

- **只拦结构化正则候选**，不拦词表同义词命中（alias contains 是管理员显式配置，优先级高）；
- **不拦台账注入值**（PersonalDimensionResolver 产物是真值，本方案只管 DimensionStateManager
  的 token 提取路径）；
- block 条目 value=block 标签不参与键分型 value 校验（controller 已有 isRegex 豁免，§8 同款）；
- 前端 REGEX_TAG_HINTS 增加 `block: '候选维度值含此正则即拒收（垃圾代词词屏蔽）'` 提示；
- 管理员可按业务补变体（如 "本人所在组"），补条目即生效（TTL 内热生效，同 §8.2）。

### 9.6 单测

- block seed 灌入：groupName/appName/productLine 三行各含 `我`⇒block 条目（append-if-missing）；
- token 拒收："我的组/我们组/我的小组10月的数据" → peerDimension=null，真组名共现不受影响
  （"我的组、数据智能组" → 只收 数据智能组）；
- R2 联动：hasExplicitTeamHit("我的组达标率") = false；
- 误伤回归：真实组名/产品线（数据智能组/个人住房组/质量产品线）照常提取；
- 词表同义词不受 block 影响：alias 显式配置命中照常收值。

### 9.7 拍板结论（2026-10-26 全定，已实施）

1. **删除保护**：基线 block 条目（seed 的 `我`⇒block）管理员不可删——update() 提交的
   aliases 缺失基线 block 条目时静默补回（比 §8 regex 条目的"重启补回"更强）；用户新增的
   其他 block 条目可自由删改；
2. **seed 用裸 `我` 单字**（find 语义，变体全覆盖，零误伤面）；
3. **范围**：deptName/groupName/appName/productLine **四键一起做**（拍板"一起做"）。
