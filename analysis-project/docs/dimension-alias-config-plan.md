# 维度别名配置与消解方案（应用 / 产品线 / 小组）

> 状态：设计稿，待评审
> 日期：2026-09-21
> 关联代码：`com.agentscopea2a.v2.dimension.DimensionStateManager`、`v2.middleware.DimensionStateMiddleware`、`v2.service.impl.V2ChatStreamServiceImpl`

## 1. 背景与现状

多轮对话中的业务同级维度（组 / 应用 / 产品线）目前由
`DimensionStateManager.extractExplicitDimensions`（DimensionStateManager.java:308）用三段硬编码正则抽取：

| 维度 | 正则 | 问题 |
|---|---|---|
| 应用 | `(F-[A-Za-z][A-Za-z0-9-]*)` | 只认 F-XXX 标准编码，口语化词（智慧三农、社保、国库）不命中 |
| 小组 | `([一-龥A-Za-z0-9]{2,}组)` | 只认"…组"后缀，GBC、财政、军队、同业 等口语词不命中；且会把"风险组"误判成小组 |
| 产品线 | `([一-龥]{2,产品线\|普惠金融\|代理国库…)` | 固定枚举写死在代码里，扩充要改代码重发版 |

需求：

1. 建一张同义词配置表（含维度字段），维护"口语化词 → 标准化名称"映射；
2. 跨维度同词（如"军队"既是小组"特种业务组"又是产品线"企业资金管理系统"）按**优先级 / 关键词触发**消解（"军队组"→小组，"军队"→产品线）；
3. 同维度一对多（如"风险组"→ 全球市场风险管理应用 / 金融产品定价与估值系统）**反问用户确认**。

## 2. 总体设计

```
用户问题
   │
   ▼
V2ChatStreamServiceImpl 入口
   │  buildRuntimeContext (V2ChatStreamServiceImpl.java:814)
   ▼
AliasResolver.resolve(question)          ★新增：同义词表解析（内存缓存，零 LLM）
   │
   │  ① 最长匹配优先：跨 span 去重（"个贷前端"压过"个贷"、
   │     "GBC场景创新组"压过"GBC"，见 §4.2 步骤 2）
   ▼
   ├─ 命中且唯一 ──────────────► DimensionStateManager 现有流程
   │                              （维度状态填【标准化名称】，继承/指代消解/组装不变）
   ├─ 命中但跨维度冲突 ────────► ② 同 span 定维度（关键词触发 → 单维度 → 无歧义优先 → 兜底序）
   └─ 命中但同维度一对多 ──────► 确定性短路：SSE 直接反问用户，
                                  pendingClarification 存入 DimensionState，
                                  下一轮匹配候选后自动恢复原问题
```

核心原则（沿用 ArithTool 的设计哲学，不依赖内网小参数 LLM 的 prompt 遵从度）：

- 别名解析、维度判定、歧义检测全部**确定性代码**完成；
- LLM 只在反问话术的"用户自由回复理解"上兜底（用户回复"第二个"这类指代时优先用确定性序号匹配，匹配不上才交给 LLM 语境理解）。

## 3. 配置表设计

**单张同义词表** `dimension_alias`（三维度本来同构，用 dimension 字段区分即可，不拆三张表），
建在 **gaussCustomerDataSource（openGauss，与 `skill_index` / `skill_routing_metadata` 同库）**。
建表沿用项目现有 ensureTable / 启动 DDL 模式，不用 flyway（openGauss 报 PG9.2，flyway 已禁）。

### 3.1 DDL

```sql
CREATE TABLE IF NOT EXISTS dimension_alias (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  dimension     VARCHAR(16)  NOT NULL COMMENT '维度：TEAM / PRODUCT_LINE / APPLICATION',
  alias         VARCHAR(64)  NOT NULL COMMENT '口语化词，如 军队 / GBC / 车贷组',
  standard_name VARCHAR(128) NOT NULL COMMENT '标准化名称，如 特种业务组 / FS-LFS-FARM',
  trigger_keyword VARCHAR(16) NULL COMMENT '触发关键词：仅当问题中出现该完整词（如 军队组）时本行才参与匹配',
  enabled       TINYINT(1)   NOT NULL DEFAULT 1,
  remark        VARCHAR(255) NULL COMMENT '备注，如 待业务确认默认维度',
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_dim_alias_std (dimension, alias, standard_name),
  KEY idx_alias (alias)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='维度同义词配置：口语化词→标准名';
```

约定：

- **一对多 = 同 (dimension, alias) 多行**（如"风险组"在产品线维度两行）。运行时按维度分组计数 > 1 即触发反问，无需额外标志位；
- **trigger_keyword（触发关键词，完整词）**：带触发词的行是**条件行**——只有当问题里出现该完整关键词（如"军队组"）时才参与匹配；裸"军队"时小组行不参与，产品线行自然唯一命中。触发词命中的行在同词竞争中**优先于**普通行（更具体的说法赢更笼统的）；
- **不带 trigger_keyword 的行无条件参与**。同维度内多候选一律反问；跨维度同词且都无触发词时按 §4.2 规则 c/d 兜底；
- 口语化词和标准名都可以互相冗余登记（如"应用平台组"和"平台组"都指向"杭州二部FMBM应用平台组"），数据量小（~70 行），不做归一化。

### 3.2 种子数据（需求给的清单原样入库）

- 小组 32 条（GBC→杭州服务支持部GBC场景创新组 … 资金交易组→杭州二部FMBM资金）；
- 产品线 24 条（其中"风险组"两条 → 全球市场风险管理应用 / 金融产品定价与估值系统）；
- 应用 11 条（其中"三农政法"两条 → FS-LFS-FARM / FS-GBCP-EPL；"国库"两条 → F-TIPS / F-TIPS-CTBS）。

入库脚本见附录 A 数据 + 第 10 节需业务确认项。

### 3.3 跨维度冲突清单（种子数据推导）

| 口语词 | 小组 | 产品线 | 应用 | 处置 |
|---|---|---|---|---|
| 军队 | 特种业务组 | 企业资金管理系统 | — | 小组行配触发词"军队组"（完整词）：说"军队组"→小组；裸"军队"时小组行为条件行不参与 → 唯一落产品线（需求原文规则） |
| GBC | 杭州服务支持部GBC场景创新组 | 民生政务 | — | 裸"GBC"双维度平，**待业务确认**落谁；输入"GBC场景创新组"时最长匹配自然落小组 |
| 财政 | 杭州服务支持部代理财政业务开发组 | 代理财政 | — | 裸"财政"双维度平，**待业务确认**；"代理财政开发组"已有独立 alias 落小组 |
| 国库 | — | 代理国库 | F-TIPS / F-TIPS-CTBS（一对多） | 应用侧本身要反问；裸"国库"按无歧义优先落产品线（§4.2 规则 c） |
| 个贷前端 | 个贷对客渠道组 | — | F-WAPB-LOAN | 双维度平，**待业务确认** |
| 量化 / 量化组 | 量化组→杭州二部量化交易组 | 量化→量化投资及交易 | — | 不同 alias 串，最长匹配自然分开，无冲突 |
| 个贷 / 个贷XX组 | 个贷消费组…（4 个嵌套 alias） | 个贷→个人信贷产品线 | — | 最长匹配优先，"个贷消费组"整段落小组，裸"个贷"落产品线 |

## 4. AliasResolver：别名解析器（新增类）

位置：`com.agentscopea2a.v2.dimension.AliasResolver`，单例 bean，被 `DimensionStateManager` 持有。

### 4.1 缓存

- 启动时 + 每 5 分钟 TTL 异步刷新同义词表到内存：`Map<PeerDimensionType, Map<String alias, List<Entry>>>`；
- 管理 API 写操作后主动失效（见 §7）；
- 表读失败时沿用上次快照并 warn 日志（匹配链路在每轮请求热路径上，绝不能阻塞或抛错）。

### 4.2 匹配算法

```text
resolve(question):
  1. 收集候选：对同义词表所有 enabled alias（跨全部维度）做 question.contains(alias) 扫描，
     记录 (start, end, dimension, alias, candidates[])，同 alias 多处命中各记一条
     —— alias 总量 ~70，每轮全量 contains 扫描开销可忽略（微秒级）
  2. 跨 span 去重：按 (end-start) 长度降序贪心accept，与已 accept span 重叠的丢弃
     —— "个贷前端"压过"个贷"，"GBC场景创新组"压过"GBC"，"快捷支付产品线"压过"快捷"
  3. 同 span 定维度（按顺序）：
     a. 关键词触发：带 trigger_keyword 的候选行，仅当问题包含该完整关键词时才在步骤 1 参与收集；
        触发词已命中的行 ⇒ 该行维度直接胜（"军队组"：小组行触发词命中 ⇒ TEAM，
        压过同样命中"军队"的产品线行——更具体的说法赢更笼统的）
     b. 只有一个维度有该 alias（其余维度是条件行未触发）⇒ 该维度
        （裸"军队"：小组行因触发词"军队组"未出现而不参与 ⇒ 唯一落产品线）
     c. 多维度同词且都无触发 ⇒ 无歧义优先：唯一候选的维度胜
        （"国库"：产品线单候选 vs 应用双候选 ⇒ 产品线；应用侧需要时用户会说 F-TIPS）
     d. 仍平 ⇒ 固定兜底序 PRODUCT_LINE > TEAM > APPLICATION（业务 2026/09/21 拍板，写死在 AliasResolver.FALLBACK_ORDER）
  4. 维度内候选数 > 1 ⇒ 该 span 标记 ambiguous，进入反问流程
  5. 每 span 至多产出一条 ResolvedAlias{dimension, alias, standardName?} 或
      AmbiguousAlias{dimension, alias, candidates[]}
```

### 4.3 结果语义

- `ResolvedAlias` 的 standardName **必须是标准名**（"军队"进状态时就是"企业资金管理系统"），保证：
  - `DimensionState.toCacheKey()` / `FingerprintCalculator` 指纹稳定——用户说"风险组"和"全球市场风险管理应用"落到同一候选，auto-synth 计数能累积（与部门 `normalizeDepartment` 同一先例，DimensionStateManager.java:223）；
  - 下游 `query_data` / `wide_table_query` 收到的维度值可直接当过滤条件，不再需要 LLM 翻译。
- `AmbiguousAlias` 不写维度状态，走 §6 反问。

## 5. DimensionStateManager 集成改造

改动集中在 `extractExplicitDimensions`（DimensionStateManager.java:308）及其调用方：

1. **别名解析先行**：入口先调 `AliasResolver.resolve(q)`；
2. **peer 维度抽取顺序调整为**：alias 命中（已含标准名）→ `EXPLICIT_APP`（F-xxx 正则保留，兜底新应用编码）→ `EXPLICIT_REQUIREMENT`（Ixxx 格式）→ 其他正则兜底；
   - 现有 `EXPLICIT_TEAM` / `EXPLICIT_PRODUCT_LINE` 正则**降级为兜底**：仅当无 alias 命中时才跑，且产出值标记为"非标准形态"（LLM `updateFromAnswer` 提取后同样过一遍 alias 归一）；
   - 这消除了现状 bug："风险组"被小组正则抢注为 TEAM——alias 表优先后，"风险组"正确落产品线反问；
3. **`QuestionAnalysis` 增字段** `ambiguousAliases`，随 `ProcessResult` 透出，供短路层判断；
4. `DimensionState` 增字段 `pendingClarification`（§6.3）。⚠️ 实施修正：框架 `RuntimeContext` 属性**不持久化**（`AgentState.context` 只存会话消息，plan_mode_context 是专用字段），反问状态机落在单例 `DimensionStateManager` 的 `currentState` 上（与维度继承同机制），JVM 重启丢 pending（与既有维度状态持久化缺口一致，用户重新提问即可）；
5. ResponseCache / SkillSynthesis 等下游全部经由 `DimensionState` 取值，无需改动，自动拿到标准名。

## 6. 一对多歧义反问流程

### 6.1 拦截点选择（已比选）

| 方案 | 说明 | 结论 |
|---|---|---|
| A. prompt 注入 | DimensionStateMiddleware 往 system prompt 加"请先反问用户" | 否决：依赖小参数 LLM 遵从度，可能边反问边把分析跑了，违背项目"用确定性机制替代 prompt engineering"原则 |
| **B. 确定性短路（推荐）** | V2ChatStreamServiceImpl 在 buildRuntimeContext 之后、agent run 之前调 AliasResolver；有 AmbiguousAlias 时不启 agent，直接走 SSE 文本事件反问并 complete | 采纳：零 token、确定性强、前端零改动（反问就是普通文本消息） |

### 6.2 反问交互

```
用户：风险组 7月份版本有多少需求项
系统（SSE 文本，无 agent 运行）：
  『风险组』在产品线维度有多个匹配，请确认具体指哪一个：
  1. 全球市场风险管理应用
  2. 金融产品定价与估值系统
  （回复序号或名称即可）

用户：1
系统：→ 解析为 全球市场风险管理应用，拼回原问题，正常跑 agent
```

### 6.3 状态机（DimensionState 新字段）

```java
public static class PendingClarification implements State {
    private PeerDimensionType dimension;
    private String alias;              // 风险组
    private List<String> candidates;   // [全球市场风险管理应用, 金融产品定价与估值系统]
    private String originalQuestion;   // 短路前的完整原问题
}
```

下一轮入口（短路判断之前）：

1. `pendingClarification != null` 时，先拿用户回复做**确定性匹配**：
   - 序号："1 / 2 / 第一个 / 第二个" → 按下标取候选；
   - 文本：回复 contains 某候选（或候选的 alias 变体）→ 取该候选；
2. 命中 → `peerDimension = 该维度[选定标准名]`，清除 pending，把**原问题**（alias 位置替换为标准名）作为本轮问题正常跑 agent；
3. 未命中 →
   - 回复中出现了新的、可无歧义解析的维度词 → 视为新问题，丢弃 pending，正常走流程；
   - 否则（闲聊 / 无关内容）→ 丢弃 pending，正常走流程（宁可少问一次也不要死循环反问）；
4. pending 存在单例 DimensionStateManager 的 currentState 上（内存，JVM 重启丢失）；同一会话新问题覆盖旧 pending。

### 6.4 与缓存的关系

短路轮**不写 ResponseCache**（没有 agent 运行、无 answer）；确认后的那轮问题已含标准名，指纹干净。

## 7. 管理接口（Phase 1 只做后端）

新增 `DimensionAliasController`，路径 `/v2/dimension/alias`：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/{dimension}` | 列表（dimension ∈ team / product-line / application） |
| POST | `/{dimension}` | 新增（校验 alias/standardName 非空、去重） |
| PUT | `/{dimension}/{id}` | 修改（改 trigger_keyword / enabled / alias / standard_name） |
| DELETE | `/{dimension}/{id}` | 删除（软删：enabled=0，默认不物理删） |
| POST | `/reload` | 手动刷新内存缓存 |

鉴权与异常处理沿用现有 Controller 约定；`GlobalExceptionHandler` 里新异常 handler 注意放行顺序问题（Exception 兜底会抢优先级，见既有踩坑记录）。
/pm 子应用的可视化维护页**不在本期**，Phase 3 再评估（复用 skill-routing-config-page 的模式）。

## 8. 测试计划

单元测试（重点，全确定性逻辑）：

- 匹配：
  - `军队的需求项` → 产品线/企业资金管理系统（小组行触发词未出现，不参与）；`军队组的需求项` → 小组/特种业务组（触发词"军队组"命中，压过产品线"军队"）；
  - `风险组` → AMBIGUOUS(产品线, 2 候选)；`三农政法`、`国库` → AMBIGUOUS(应用)；
  - `GBC场景创新组` → 小组（最长匹配压过 GBC）；裸 `GBC` → 双维度平，按 §4.2 规则 c/d 兜底（归属待业务确认）；
  - `个贷消费组` → 小组；`个贷` → 产品线；`快捷支付产品线` → 快捷支付系统；
  - `银企团队` → 产品线（后缀触发只认"组"，不误伤）；
  - 用户输入已是标准名（`杭州二部量化交易组`）→ 幂等，返回原值。
- 状态机：反问 → "1" / "第二个" / 直接答标准名 / 无关回复 四条路径；pending 跨轮持久化。
- 兜底链：无 alias 命中时 F-xxx / Ixxx 正则仍工作；`EXPLICIT_TEAM` 不再抢注"风险组"。
- 缓存：`风险组(确认后)` 与 `全球市场风险管理应用` 生成相同 toCacheKey。

E2E（沿用现网验证模式）：真实 SSE 会话跑通 反问→确认→分析 全链路 + JVM 重启后 pending 恢复。

## 9. 实施阶段

| 阶段 | 内容 | 交付 |
|---|---|---|
| P1 | 单表 DDL + 种子数据 + AliasResolver + 单测 | 纯新增，零行为变化 |
| P2 | DimensionStateManager 集成（alias 优先、正则降兜底、standardName 进状态） | 口语词识别生效，含"风险组"误判修复 |
| P3 | 歧义短路 + pendingClarification 状态机 + 管理接口 | 反问闭环 |

P1/P2 无破坏性，可先合入验证；P3 触碰 V2ChatStreamServiceImpl 主链路，单独评审。

## 10. 开放问题（业务拍板记录 2026/09/21）

兜底序已拍板：规则 d 定为 **PRODUCT_LINE > TEAM > APPLICATION**（裸词双维度平时产品线优先）。

1. **GBC / 财政 / 个贷前端**：按新兜底序暂落产品线（民生政务 / 代理财政）或小组（个贷对客渠道组）；业务确认裸词仍指向小组时会自行加带 trigger_keyword 的条件行覆盖（改表即生效，TTL 5 分钟）；
2. **国库**：按无歧义优先落产品线（代理国库），落应用场景用户会说 F-TIPS / F-TIPS-CTBS；如需反问请业务反馈；
3. **资金交易组 → 杭州二部FMBM资金**：业务确认是完整专名，非截断，保留原样；
4. 应用 **三农政法** 一对多按需求走反问；若业务本意是"两个都查"，改为一次查询双值而非反问；
5. 应用维度表原文"银银 → F-BBC" 等行首空格已按 trim 入库，请复核有无遗漏行。

---

## 11. 已知缺口：口语词在 LLM 侧未替换，参数映射会走错维度（2026/09/21 E2E）

**现象**：问「军队7月份版本有多少需求项」，维度上下文已正确注入「产品线：企业资金管理系统」
（且 `<dimension_context>` 已带"必须直接采用、禁止追问"指令），但 LLM 命中 skill
`context_demo_q2_1_dept_version_user_generated`（固定流程优先）后，因该 skill 参数 schema
只有 `deptName` + `versionPlan`，仍按字面填了 `deptName=军队`——口语词「军队」从未离开
LLM 视野，参数名带 dept 就往里塞，指令拦不住。

**另两个已排除/设计内行为**：
- 「需求项」不在 dev 库 `<tool_metric_catalog>`（仅 QI卡口/test 两主题）→ 目录外终局
  「不支持该指标」是决策树设计内行为；业务要支持需把宽表主题/指标注册进 tool routing
  metadata（数据配置，非代码）。
- `quality_query_by_version_department` 等质量库工具按 deptName 取数，产品线
  「企业资金管理系统」在质量库无数据，返回空属数据层事实。

**候选修法（未实施，待内网验证后拍板）——确定性改写优先**：
在 `V2ChatStreamServiceImpl.stream()` 已有的反问拦截点旁，利用 `AliasResolver.resolve`
已返回的命中 span（start/end/standardName），**把问题文本中的口语词原位替换为标准名**
（从右往左替换保偏移），再传给 agent：
「军队7月份版本有多少需求项」→「企业资金管理系统7月份版本有多少需求项」。
与反问流程 `req.setQuestion(替换后问题)` 完全同机制；LLM 只见标准名，"企业资金管理系统"
显然不是部门，自然不会塞进 deptName。前端展示的原始用户输入不受影响（改的是发给
agent 的 question）。配套在 `<dimension_context>` 指令中加一句兜底：
「任何工具参数禁止使用用户原词口语词」。多命中场景会整句替换（如「比较军队和量化组」→
「比较企业资金管理系统和杭州二部量化交易组」），对 LLM 是纯收益。

---

### 附录 A：种子数据 SQL 骨架

> ⚠️ 本附录**只是给人对照的参考**，不会被执行：gauss 库 flyway 已禁用（openGauss 报 PG9.2），
> 真实种子在 `DimensionAliasSeed.builtin()`，由 `DimensionAliasRepository.ensureTable()`
> 首连自动建表 + 灌入；改配置走管理接口 `/v2/dimension/alias`。同步修订以 Java 侧为准。

```sql
-- 小组（32，原需求清单）
INSERT INTO dimension_alias (dimension, alias, standard_name) VALUES
 ('TEAM','GBC','杭州服务支持部GBC场景创新组'),
 ('TEAM','GBC场景创新组','杭州服务支持部GBC场景创新组'),
 ('TEAM','GPC组','杭州二部GPC应用开发组'),
 ('TEAM','财政','杭州服务支持部代理财政业务开发组'),
 ('TEAM','军队','特种业务组'),       -- 触发词"军队组"，见下方 UPDATE
 ('TEAM','车贷组','个贷汽车场景建设组'),
 ('TEAM','创新消费组','个贷消费场景组'),
 ('TEAM','代理财政开发组','杭州服务支持部代理财政业务开发组'),
 ('TEAM','对公组','杭州二部金融市场对公组'),
 ('TEAM','分行','杭州服务支持部分行平台服务创新组'),
 ('TEAM','分行平台服务创新组','杭州服务支持部分行平台服务创新组'),
 ('TEAM','个贷前端','个贷对客渠道组'),
 ('TEAM','个贷渠道组','个贷对客渠道组'),
 ('TEAM','个贷消费组','个贷消费场景组'),
 ('TEAM','个贷住房组','个贷住房场景建设组'),
 ('TEAM','个人组','杭州二部金融市场个人组'),
 ('TEAM','交易报价组','杭州二部金融市场交易报价组'),
 ('TEAM','境外对客组','杭州二部金融市场境外对客组'),
 ('TEAM','境外组','杭州二部金融市场境外对客组'),
 ('TEAM','量化交易组','杭州二部量化交易组'),
 ('TEAM','量化组','杭州二部量化交易组'),
 ('TEAM','平台组','杭州二部FMBM应用平台组'),
 ('TEAM','汽车贷','个贷汽车场景建设组'),
 ('TEAM','渠道组','个贷对客渠道组'),
 ('TEAM','生态触客','杭州服务支持部生态触客创新组'),
 ('TEAM','生态触客创新组','杭州服务支持部生态触客创新组'),
 ('TEAM','同业','同业客户组'),
 ('TEAM','投融资组','杭州二部FMBM投融资组'),
 ('TEAM','线上化组','杭州五部普惠大文章线上化组'),
 ('TEAM','应用平台组','杭州二部FMBM应用平台组'),
 ('TEAM','中台组','杭州二部金融市场中台组'),
 ('TEAM','资金交易组','杭州二部FMBM资金');       -- 业务确认：完整专名，非截断（2026/09/21）

-- 跨维度同词的触发词配置（军队：说"军队组"→小组；裸"军队"时小组行不参与，落产品线）
UPDATE dimension_alias SET trigger_keyword='军队组'
 WHERE dimension='TEAM' AND alias='军队';
-- GBC / 财政 / 国库 / 个贷前端 无触发词：按 §4.2 规则 c/d 兜底，
-- d 序为 PRODUCT_LINE > TEAM > APPLICATION（2026/09/21 拍板）；裸词需指小组时加带触发词的行覆盖

-- 产品线（24；风险组两行 = 一对多）
INSERT INTO dimension_alias (dimension, alias, standard_name) VALUES
 ('PRODUCT_LINE','GBC','民生政务'),
 ('PRODUCT_LINE','GMO','金融市场后台运营项目'),
 ('PRODUCT_LINE','财政','代理财政'),
 ('PRODUCT_LINE','对客','金融市场对客交易'),
 ('PRODUCT_LINE','法贷','法人信贷产品线'),
 ('PRODUCT_LINE','风险组','全球市场风险管理应用'),
 ('PRODUCT_LINE','风险组','金融产品定价与估值系统'),
 ('PRODUCT_LINE','个贷','个人信贷产品线'),
 ('PRODUCT_LINE','国库','代理国库'),
 ('PRODUCT_LINE','交易管理','金融市场交易管理'),
 ('PRODUCT_LINE','交易下单','金融市场交易下单'),
 ('PRODUCT_LINE','缴费','缴费产品线'),
 ('PRODUCT_LINE','军队','企业资金管理系统'),
 ('PRODUCT_LINE','开放银行','生态金融管理'),
 ('PRODUCT_LINE','快捷','快捷支付系统'),
 ('PRODUCT_LINE','快捷产品线','快捷支付系统'),
 ('PRODUCT_LINE','快捷支付产品线','快捷支付系统'),
 ('PRODUCT_LINE','量化','量化投资及交易'),
 ('PRODUCT_LINE','票据','票据产品线'),
 ('PRODUCT_LINE','普惠','普惠金融'),
 ('PRODUCT_LINE','投研','金融产品投资研究'),
 ('PRODUCT_LINE','询价','金融市场内部询价及交易'),
 ('PRODUCT_LINE','养老金','养老保险全国统筹基金管理系统'),
 ('PRODUCT_LINE','银企','银企产品线'),
 ('PRODUCT_LINE','银企团队','银企产品线');

-- 应用（11；三农政法、国库各两行 = 一对多）
INSERT INTO dimension_alias (dimension, alias, standard_name) VALUES
 ('APPLICATION','智慧三农','FS-LFS-FARM'),
 ('APPLICATION','智慧政法','FS-GBCP-EPL'),
 ('APPLICATION','三农政法','FS-LFS-FARM'),
 ('APPLICATION','三农政法','FS-GBCP-EPL'),
 ('APPLICATION','社保','F-ASSP'),
 ('APPLICATION','国库','F-TIPS'),
 ('APPLICATION','国库','F-TIPS-CTBS'),
 ('APPLICATION','银证','F-CBST'),
 ('APPLICATION','银商','F-CBMT'),
 ('APPLICATION','银期','F-CBFT'),
 ('APPLICATION','个贷前端','F-WAPB-LOAN'),
 ('APPLICATION','银银','F-BBC');
```
