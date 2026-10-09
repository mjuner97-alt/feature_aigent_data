# Tool-Tool 重叠检测 + 工具路由启动阻断 方案

日期：2026-10-08
状态：v1 已实施并 E2E 验证（2026-10-08，开关选 B）；v2 四层一致性规则已于 2026-10-09 实施并 E2E 验证（判定规则见 §3.1，实施记录见 §6，生产误判反馈驱动）
关联：`skill-tool-overlap-and-description-similarity-plan.md`（Skill↔Tool 检测，已上线）

## 1. 背景与目标

现状：重叠检测页面（`/script-registry/overlap`，组件 `RoutingOverlapPage.vue`）只检测 **Skill 与 Tool** 之间的能力重复（`SkillToolOverlapService`，信号 S1 topic_tags 交集 / S2 描述 embedding cosine / S3 名称-keywords-toolId 匹配）。

目标：

1. 新增 **Tool↔Tool** 重叠检测，展示在同一页面。
2. 存在 HIGH 级 Tool↔Tool 重叠时，**工具路由启动被阻断**，启动日志明确提示"与工具 xxx 有重复，请处理"。

## 2. 现状盘点（关键代码位置）

| 组件 | 文件 | 说明 |
|---|---|---|
| Skill↔Tool 检测 | `v2/governance/SkillToolOverlapService.java` | S1/S2/S3 三信号 + HIGH/MEDIUM/LOW 分级，进程内 TTL 缓存，`invalidate()` 失效 |
| HTTP 视图 | `v2/governance/RoutingOverlapController.java` | `GET /api/routing-overlap`、`/summary`，只读 |
| 重叠对视图 | `v2/governance/RoutingOverlapView.java` | record，HTTP 合约 |
| 启动审计 | `v2/toolrouting/ToolRoutingStartupAudit.java:43` | ApplicationRunner；`strict-startup=true` 时审计不过抛 `IllegalStateException` 阻断 Spring 启动（现成机制） |
| 审计装配 | `v2/config/V2ToolConfig.java:243` | `strictStartup` 读 `harness.a2a.tool-routing.strict-startup`（`application.properties:281`，当前 false） |
| 工具元数据 | `v2/toolrouting/ToolRoutingMetadata.java` | toolId / toolType / description / topicTags / metricTags / dimensionTags / enabled |
| 元数据仓库 | `v2/toolrouting/ToolRoutingMetadataRepository.java:47` | `findEnabled()` |
| 元数据保存失效 | `v2/toolrouting/ToolRoutingMetadataAdminService.java:81` | 保存后已调 `overlapService.invalidate()` |
| embedding 缓存 | `v2/governance/GovernanceEmbeddingCache.java` | `EntityType.SKILL/TOOL`，`semanticAvailable()`/`warm()` |
| 前端页面 | `frontend/src/pages/RoutingOverlapPage.vue`（107 行） | 级别过滤 + summary 统计 + 表格 |
| 前端 API | `frontend/src/api/routingOverlap.ts`、`types/routingOverlap.ts` | `BASE=/api/routing-overlap`，X-User-Id 头 |
| 工具性相似度 | `v2/governance/TextSimilarityUtil.java` | cosine / normalizeName / levenshteinSimilarity，直接复用 |

## 3. 方案设计

### 3.1 新增 `ToolToolOverlapService`（v2/governance/）

#### 3.1.0 判定哲学：从 Agent 下钻链路定义"重复"

Agent 实际选工具的链路（`ToolRoutersIndex.java:112` router_tool / `:158` toolMetaInfo）：

```
skill (load_skill_through_path)
  → tool_index / 路由元数据候选集 (topic_tags 圈定范围, metadata Top-K)
  → 叶子选型: Agent 对着候选集里的 toolId 描述 (+ toolMetaInfo 查参数签名) 二选一
  → router_tool(toolId=xxx) 执行
```

因此"重复"的判定标准不是表面的名称相似，而是：**下钻到最小粒度（叶子选型）后，两个工具出现在同一候选集里，且 Agent 无法从描述上、功能上（参数签名）对二者做出区分** —— 此时 Agent 必然瞎选，这就是重复。

推论：

- **topic_tags 交集是判定前提，不是佐证**。topic 完全不相交的工具永远不会进入同一候选集，Agent 不存在在二者间选型的场景 → 不构成重复（最多 LOW 巡检）。这与 Skill↔Tool 检测中 S1"只佐证"的定位不同：Skill 有独立的工作流层价值，而工具只在候选集里被比较。
- **名称相似本身不构成功能重复**（`query_data` / `query_data_v2` 同名但描述可区分时 Agent 仍能选对），降级为 LOW 命名卫生信号；"名称相似但描述不可区分"的情形已被 HIGH 覆盖，无需单列。
- **（v2 补充，2026/10/08 生产反馈）四层一致性规则**：`业务主题 topic_tags / 指标标签 metric_tags / 维度标签 dimension_tags` 三层外加 `描述`，**有不同，即判断两个工具不一致**。反过来说，判 HIGH（重复、阻断）要求四层**全部**无差异。背景：内网生产的 `BacklogIssueNumByApp/ByDept/ByGroup/ByProductLine` 是同功能不同维度的变体——metric_tags 全同（遗留问题）、描述全同（cosine 1.00）、dimension_tags 相交但不同（`应用,版本计划` / `部门,版本计划` / `小组,版本计划` / `产品线,版本计划`）——v1 的"维度不相交豁免"对相交集合不生效，metric_tags 又从未参与比较，导致 4 个变体工具两两被判 HIGH。v2 把"差异"从豁免信号升级为判定主轴。

#### 3.1.1 实现

输入只用 `toolRoutingMetadataRepository.findEnabled()`，对工具两两配对（先按 topic 交集剪枝再算 cosine；工具数量几十级，O(n²) 可接受；结果 TTL 缓存 + `invalidate()`，模式与 `SkillToolOverlapService` 相同）。

判定信号：

- **T0 同候选集（前提）**：两侧 `topicTags` 交集 ≥ 1，否则跳过（完全不相交的工具永远不会进入同一候选集，连 LOW 也不出）。
- **T1 描述无差异**：两侧 description 的 embedding cosine ≥ `cosineHighThreshold`（0.88）视为"描述无差异"；cosine 介于 `cosineThreshold`（0.80）~0.88 视为"相近但有差异"（Agent 大概率能从措辞区分，人工确认）。向量经 `GovernanceEmbeddingCache.embeddingFor(EntityType.TOOL, toolId, description)`，已有缓存不重复请求。
- **T2 参数签名**：从 `UnifiedToolMetadataService.find()` 取两侧参数表，比较"必填参数名集合"与"全参数名集合"（全参数名 Jaccard ≥ 0.8 且必填集合相同 → 签名不可区分）。**v2 中 T2 不再参与非 degraded 的分级**（描述无差异即 HIGH，描述有差异即封顶 MEDIUM，签名不影响），只保留两个用途：① degraded 时作为唯一硬证据；② 视图展示信号。
- **T3 名称相似**：`normalizeName(toolIdA)` vs `normalizeName(toolIdB)` levenshtein ≥ `nameSimilarityThreshold`（仅 LOW 命名卫生用）。
- **T4 四层一致性（v2 核心，取代 v1 的"维度不相交豁免"）**：逐层比较，任一层"有不同"即为差异层：

| 层 | 比较对象 | "无差异"判定 |
|---|---|---|
| L1 业务主题 | `topicTags` | 集合相等（前提 T0 已保证交集 ≥ 1；相交但不相等 → 差异） |
| L2 指标标签 | `metricTags` | 集合相等 |
| L3 维度标签 | `dimensionTags` | 集合相等（**相交但不同也算差异**，如 `应用,版本计划` vs `部门,版本计划`） |
| L4 描述 | description | cosine ≥ 0.88（degraded 时 L4 无证据，不单独构成差异，见 §3.3） |

分级（v2）：

| 级别 | 条件 | 含义 |
|---|---|---|
| **HIGH** | T0 且 **L1∧L2∧L3∧L4 全部无差异**（cosine ≥ 0.88）；degraded 下 L4 无证据时：L1∧L2∧L3 相等 且 T2 签名完全一致（硬证据） | 四层全部不可区分，Agent 叶子选型必然瞎选 → **阻断启动** |
| **MEDIUM** | T0 且（T1 cosine ≥ 0.80 或 T2 命中），但存在至少一个差异层 | 同候选集内相近，但 Agent 仍可按差异层（维度/指标/主题/描述措辞）选型，人工确认；**suggestion 点名差异层** |
| **LOW** | 仅 T3 命中（命名卫生），或 T0 交集 ≥ 2 但 T1/T2 均未命中（巡检） | 供巡检参考，永不阻断 |

要点：

- 差异层的方向是"**豁免 HIGH**"而非"制造记录"：四层全同才 HIGH，任何一层不同就把该对封顶 MEDIUM（前提 T0 与 MEDIUM 信号仍需满足）。
- L1-L3 用**集合相等**而非交集：生产变体工具的 dimension_tags 相交（都含"版本计划"）但不同，v1 的"不相交才豁免"漏判；metric_tags v1 根本没比。
- cosine 0.80~0.88 区间不再与 T2 叠加升 HIGH（v1 行为），该区间本身就是 L4 的"有差异"证据。
- T2（参数签名）不依赖 embedding：degraded（embedding 不可用）时，T0 + L1-L3 相等 + 签名完全一致仍判 HIGH 并阻断；只有依赖 cosine 的判定在 degraded 下降级为不阻断。

视图 record `ToolToolOverlapView`（v2 调整）：

```java
record ToolToolOverlapView(
        String toolIdA, String toolTypeA,
        String toolIdB, String toolTypeB,
        String level, List<String> topicTagOverlap,
        double cosine, boolean signatureSame,
        boolean aliasHit,
        List<String> differingLayers,      // 新增: 差异层名列表, 如 ["维度标签","描述"], HIGH 时为空
        List<String> metricTagsA,          // 新增: 展示 L2 两侧取值
        List<String> metricTagsB,
        List<String> dimensionTagsA,
        List<String> dimensionTagsB,
        String suggestion) {}
```

（v1 的 `dimensionDistinguishable` 字段删除，由 `differingLayers` 取代；前端 `toolSignals()` 改为展示"差异层: 维度标签(A: 应用、版本计划 vs B: 部门、版本计划)"式文案。）

`suggestion` 固定句式含"**与工具 xxx 有重复**"（HIGH）；MEDIUM 点名差异层，如：`工具 BacklogIssueNumByApp 与工具 BacklogIssueNumByDept 描述无法区分（cosine=1.00）但维度标签不同（A: 应用、版本计划；B: 部门、版本计划），Agent 可按维度选型，请人工确认`。

### 3.2 HTTP 端点扩展

`RoutingOverlapController` 新增（与现有端点并列，独立路径更清晰）：

- `GET /api/routing-overlap/tool-tool` — 参数 `level` / `toolId` / `limit` / `offset`，返回 `{degraded, total, items[]}`
- `GET /api/routing-overlap/tool-tool/summary` — counts + highByTool

`ToolRoutingMetadataAdminService.java:81` 保存后的 `invalidate()` 同时调 `ToolToolOverlapService.invalidate()`（两个工具任一侧元数据变更都失效两个缓存）。

### 3.3 启动阻断（核心需求）

挂载点直接复用 `ToolRoutingStartupAudit`（已是 ApplicationRunner + strict 阻断机制，**不需要新写启动流程代码**）：

1. 构造器注入 `ToolToolOverlapService`（`V2ToolConfig.java:243` 装配处加一个参数）。
2. `audit()` 末尾追加：取 tool-tool 报告，HIGH 级且非 degraded 的每一对生成新 issue kind `tool_overlap`：
   ```java
   addIssue(issues, "tool_overlap", pair.toolIdB(),
           "与工具 " + pair.toolIdA() + " 有重复，请处理");
   ```
3. 现有 `run()` 逻辑不变：`strict-startup=true` 时自然被 `IllegalStateException` 阻断，启动日志逐条 `WARN [tool_overlap] 与工具 xxx 有重复，请处理`。

阻断开关（两个选项，**推荐 B**）：

- **选项 A：复用 `strict-startup`**。零新增配置，但现有审计（missing/orphan/invalid_tags/script_unavailable）也会一起阻断，影响面大——任何一条脏元数据都起不来。
- **选项 B（推荐）：新增独立开关 `harness.a2a.tool-routing.block-tool-overlap`（默认 false）**。`run()` 中单独判断：`blockToolOverlap && hasToolOverlapIssue && !degraded` 时才抛异常，异常消息包含全部重复对。其余审计问题仍走原 strict 逻辑。影响面最小、可灰度。

补充规则（两种选项都适用）：

- **degraded（embedding 不可用）时的阻断口径（v2）**：L4 描述无证据，依赖 cosine 的判定降级不阻断，只 WARN；但 **T0（同候选集）+ L1-L3（topic/metric/dimension 三层标签集合相等）+ T2（参数签名完全不可区分）不依赖 embedding，属硬证据，degraded 下仍判 HIGH 并阻断**。签名取不到（SCRIPT/API 解析失败）或签名可区分时，degraded 下不出 HIGH。
- LOW/MEDIUM 永不阻断，只进页面巡检。
- issue detail 文案统一为：`与工具 <对方toolId> 有重复，请处理`（3.3 第 2 步已含）。

### 3.4 前端（RoutingOverlapPage.vue）

最小改动方案——页面顶部加视图切换（`el-radio-group`）：

- `Skill↔Tool`（现有表格与逻辑不动）
- `Tool↔Tool`（新）：列 = 级别 / 工具A / 类型A / 工具B / 类型B / 主题交集 / cosine / 参数签名 / 信号 / 建议

`api/routingOverlap.ts` 加 `listToolToolOverlap()` / `toolToolOverlapSummary()`；`types/routingOverlap.ts` 加 `ToolToolOverlapItem`。summary 统计条复用（HIGH 计数取当前视图）。

### 3.5 测试

- `ToolToolOverlapServiceTest`（仿 `SkillToolOverlapServiceTest`，v2 用例按四层规则重写）：
  - T0 未命中（topic 完全不相交）→ 即使描述完全相同也不出记录（前提校验）；
  - 四层全同（topic/metric/dimension 集合相等 + cosine 0.93）→ HIGH，`differingLayers` 为空；
  - **生产复现场景**：topic/metric 相等 + 描述全同（cosine 1.00）+ dimension 相交但不同（`应用,版本计划` vs `部门,版本计划`）→ **MEDIUM**，`differingLayers=["维度标签"]`，suggestion 含两侧维度取值（v1 误判 HIGH 的回归用例）；
  - metric_tags 不等 → MEDIUM，`differingLayers=["指标标签"]`；
  - topic 相交但不相等 → MEDIUM，`differingLayers=["业务主题"]`；
  - cosine 0.85（0.80~0.88 区间）+ 四层标签全同 → MEDIUM（描述"相近但有差异"，不再与签名叠加升 HIGH）；
  - 仅 toolId 名称相似（topic 不相交）→ LOW；
  - degraded：L1-L3 相等 + 签名完全一致 → 仍 HIGH；L1-L3 相等 + 签名可区分 → 无 HIGH；L3 不同 + 签名一致 → MEDIUM（差异层豁免在 degraded 同样生效）。
- `ToolRoutingStartupAuditTest` 补充：注入 mock `ToolToolOverlapService` 返回一对 HIGH → `block-tool-overlap=true` 时抛异常且消息含"与工具 xxx 有重复"；false 时仅 WARN；degraded 且仅 cosine 证据时仅 WARN（既有用例保留，视图字段变化同步更新构造参数）。
- `ToolRoutingStartupAuditTest` 补充：注入 mock `ToolToolOverlapService` 返回一对 HIGH → `block-tool-overlap=true` 时抛异常且消息含"与工具 xxx 有重复"；false 时仅 WARN；degraded 且仅 cosine 证据时仅 WARN。

### 3.6 E2E 验收

1. 管理页把某工具描述改成与另一工具同义（topic 有交集）→ 页面 Tool↔Tool 视图出现 HIGH。
   **v2 追加（生产回归）**：插入两条 topic/metric 相等、描述全同、dimension_tags 相交但不同（`应用,版本计划` / `部门,版本计划`）的元数据 → 必须判 **MEDIUM**（差异层=维度标签），不阻断；再把其中一条 dimension_tags 改成与前条完全相等 → 变 HIGH 且阻断。
2. `block-tool-overlap=true` 重启 → 启动失败，日志含 `[tool_overlap] 与工具 xxx 有重复，请处理`。
3. 处理重复（改描述/改 topic 拆开候选集/停用其一）→ 重启成功，页面该对消失。
4. 停掉 embedding 服务重启 → 若存在参数签名完全一致的对则仍阻断；否则不阻断，页面显示 degraded 标记。

## 4. 实施顺序

1. `ToolToolOverlapService` + 单测（纯新增）
2. Controller 端点 + AdminService invalidate 联动
3. 前端视图切换 + API/类型
4. `ToolRoutingStartupAudit` 接入 + `block-tool-overlap` 开关（选项 B）+ 单测
5. E2E 四步验收

预计改动：新增 3 个 Java 文件（Service/View/单测）+ 修改 4 个（Controller/AdminService/Audit/V2ToolConfig）+ 前端 3 个文件，无表结构变更（重叠仍是派生数据不落库）。

## 5. 实施记录（2026-10-08）

- 新增：`ToolToolOverlapService` / `ToolToolOverlapView` / `ToolToolOverlapServiceTest`；修改：`RoutingOverlapController`（/tool-tool、/tool-tool/summary）、`ToolRoutingMetadataAdminService`（@Autowired 6 参构造 + 双 invalidate）、`ToolRoutingStartupAudit`（tool_overlap issue + block 抛异常 + ensureToolVectorsWarmed）、`V2ToolConfig`（toolToolOverlapService bean + block-tool-overlap 装配）、前端三件套。
- 单测 18 个新增用例全过，全量 262 个测试 BUILD SUCCESS；前端 vite build 通过。
- 实施期发现并修正的设计点：**启动审计（ApplicationRunner）先于后台 embedding 预热完成**（预热 35 skills 约 100s），原 `degraded = !semanticAvailable || !warm` 会让 cosine 证据在启动时永远判不出。修正：① `ToolToolOverlapService.report()` 的 degraded 只看 `semanticAvailable()`，向量缺失逐对降级（cosineKnown）；② block 开启时审计先调 `ensureToolVectorsWarmed()` 同步预热工具向量（工具数少，~20s 可接受）。
- E2E 三步：① 插入 `t_e2e_dup`（复制 q2_1_metrics_by_dept_version 元数据）→ `/api/routing-overlap/tool-tool` 返回 HIGH（cosine=1.0）+ 2 个真实 MEDIUM 对；② `block-tool-overlap=true` 启动 → ApplicationRunner 阶段抛 `IllegalStateException: 工具路由启动检测到 2 组工具功能重复…与工具 t_e2e_dup 有重复，请处理`，进程退出码 1；③ 删除该行后同开关重启 → 正常启动，HIGH 消失。
- 注意：阻断发生在 ApplicationRunner 阶段（web 端口已短暂监听后进程退出），运维侧探活需以进程退出码/日志为准，不能只看端口。
- openGauss 往 DB 写元数据时 `signatureSame` 判定依赖 `UnifiedToolMetadataService.find()`，SCRIPT/API 类型参数解析失败的工具自动跳过 T2，不影响 T1/T3 判定。

## 6. v2 修订记录（2026-10-08，四层一致性规则，**待实施**）

**生产反馈**：内网生产环境 `BacklogIssueNumByApp/ByDept/ByGroup/ByProductLine` 四个变体工具两两被判 HIGH（共 6 对）——实际它们是同功能不同维度的变体，不是重复。生产元数据：metric_tags 全同（遗留问题）、描述全同（cosine 1.00）、dimension_tags **相交但不同**（`应用,版本计划` / `部门,版本计划` / `小组,版本计划` / `产品线,版本计划`）。

**v1 为什么漏**：v1 的 T4 豁免条件是"双方 dimension_tags 均非空且**不相交**"，生产的维度标签都含"版本计划"，交集非空 → 豁免不触发；且 v1 从未比较 metric_tags。

**用户定版的判定规则（原话）**："业务主题 / 指标标签 / 维度标签 这三层外加描述，有不同，即判断两个工具不一致。"

**v2 变更摘要**（详细规则已并入 §3.1，此处只列差异）：

1. 判定主轴从"HIGH 证据 + 豁免信号"改为"**四层一致性**"：L1 topic_tags / L2 metric_tags / L3 dimension_tags 集合相等 + L4 描述 cosine ≥ 0.88，四层全部无差异才 HIGH；任一差异层封顶 MEDIUM 并在 suggestion 点名。
2. cosine 0.80~0.88 不再与 T2 叠加升 HIGH（该区间即 L4 的"有差异"）。
3. T2 参数签名降级为：degraded 硬证据 + 展示信号，不参与非 degraded 分级。
4. `ToolToolOverlapView` 删 `dimensionDistinguishable`，增 `differingLayers` / `metricTagsA` / `metricTagsB`；前端 `toolSignals()` 与 suggestion 文案同步。
5. 测试重写（§3.5 v2 用例），E2E 增加生产复现场景（§3.6 第 1 步 v2 追加）。

**实施范围**：`ToolToolOverlapService.evaluatePair()` 分级逻辑 + javadoc、`ToolToolOverlapView`、`ToolToolOverlapServiceTest`（12 个用例中 4 个维度相关用例重写）、`RoutingOverlapPage.vue` `toolSignals()`、`routingOverlap.ts` 类型；`ToolRoutingStartupAudit` 无需改（消费的还是 level=HIGH 的对）。**尚未动任何代码，等用户确认方案后实施。**

### 6.1 v2 实施记录（2026-10-09，已实施并 E2E 验证）

- 用户确认方案后实施：`ToolToolOverlapService`（differingLayers 计算 + HIGH/MEDIUM 分级 + layerDiffText 差异层明细文案）、`ToolToolOverlapView`（删 `dimensionDistinguishable`，增 `differingLayers`/`metricTagsA`/`metricTagsB`，15 字段）、`ToolToolOverlapServiceTest`（14 用例，新增 metricTags 差异 / topic 相交不等 / cosine 0.85 不升 HIGH / 生产复现相交维度 4 个用例）、`ToolRoutingStartupAuditTest`（View 构造参数同步）、前端 `routingOverlap.ts` + `RoutingOverlapPage.vue`（信号列改"差异层: …"展示）。
- 测试：目标两类 23 用例全过，全量 **270 用例 BUILD SUCCESS**；前端 vite build 通过。
- E2E（dev gauss 库插入 `t_e2e_dim_a`/`t_e2e_dim_b`）：
  1. **生产复现场景**：topic/metric 全同（遗留问题）、描述全同（cosine=1.00）、dimension_tags 相交但不同（`应用,版本计划` vs `部门,版本计划`）→ 判 **MEDIUM**，`differingLayers=["维度标签"]`，suggestion= "…描述无法区分（cosine=1.00）但维度标签不同（A: 应用、版本计划；B: 部门、版本计划），Agent 仍可按差异选型，请人工确认是否重复"。v1 会误判 HIGH 的对，v2 正确豁免。
  2. 把 `t_e2e_dim_b` 的 dimension_tags 改成与 A 完全相等 → 四层全同 → HIGH；`block-tool-overlap=true` 重启 → ApplicationRunner 阶段抛 `IllegalStateException: 工具路由启动检测到 2 组工具功能重复…与工具 t_e2e_dim_b 有重复，请处理`，mvn 退出码 1。
  3. 删除测试行（DELETE 2）→ 默认开关正常启动，HIGH 消失，仅剩真实 MEDIUM 对（q2_1_metrics_by_dept_version vs quality_query_by_department_quarter，差异层=维度标签+描述）。
- degraded 场景（L1-L3 相等 + 签名一致仍 HIGH / 维度差异豁免在 degraded 同样生效）由单测覆盖（degradedStillBlocksOnIdenticalSignature、dimensionEscapeAppliesToSignatureEvidenceInDegraded），未重复整机 E2E。
- 顺带确认：生产 4 个 `BacklogIssueNumBy*` 变体在 v2 下均为 MEDIUM（差异层=维度标签），不再阻断；若仍要彻底消除页面噪音，可把描述措辞改得可区分或将 dimension_tags 补全为可区分集合。

### 6.2 同 toolId 跨类型重复注册纳入检测（2026-10-09，已实施）

**生产反馈**：`q2_1_metrics_by_dept_version` 以 SCRIPT 与 SQL 两种类型重复注册（扫描问题 DUPLICATE_TOOL_ID），但 tool-tool 重叠检测没有任何输出——`scanScope()` 原来按 toolId 去重，两条记录塌缩成一个实体，双重循环里永远配不成对。

**变更**：
1. `ToolToolOverlapService.scanScope()` 去重键改为 `toolId + "|" + toolType`，同 toolId 跨类型记录全部保留参与配对；`compute()` 的向量缓存键同步改 `scanKey`（同 toolId 两条记录描述不同，按 toolId 键会互相覆盖向错向量）。
2. `evaluatePair()` 开头（双方未启用排除之后）新增同 toolId 分支：**不经四层证据直接判 HIGH**——Agent 调用层按 toolId 寻址，本身就无法区分，这与"描述不可区分"是更强的重复信号；suggestion 提示重命名独立 toolId 或退役其中一个。
3. 下游消费不变：启动审计/启用拦截取双方已启用的对（同 toolId 对语义正确：一边启用时另一边被拦）；重叠页/工具路由页角标按 toolId 汇总，同 toolId 对两侧都会亮 HIGH 角标。
4. 测试：`ToolToolOverlapServiceTest` 16 用例（新增 sameToolIdCrossTypeDuplicateIsHighWithoutLayerEvidence、sameToolIdCrossTypeBothDisabledIsExcluded），47 用例全过。

**边界**：双方均未启用的同 toolId 对仍是纯噪音不报（与既有规则一致）；数据整改路径不变——重命名或退役其一，DUPLICATE_TOOL_ID 扫描问题消失后该对随之消失。

**6.2 补遗（同 toolId 检测仍不出现的真因，2026-10-09）**：`tool_route_metadata` 主键是 `tool_id`，同 toolId 的另一类型注册在元数据表里物理上不存在（后配置的类型直接覆盖 tool_type），所以仅改 scanScope 去重键检不出。追加：`ToolToolOverlapService` 注入 `ToolRoutingScanService`，把扫描候选中带 `DUPLICATE_TOOL_ID` 标记、且元数据表无同 (toolId, tool_type) 行的记录合成进扫描范围（无标签、按已启用处理，仅同 toolId 分支生效；只补冲突 id，不引入全量注册工具）。构造函数 7→8 参，`V2ToolConfig` 装配同步；17 用例全过，全量 280 用例 BUILD SUCCESS。

**6.3 同 toolId HIGH 对不阻断启动/启用（2026-10-09，已实施）**：6.2 上线后同 toolId 对（双方"已启用"）触发 block-tool-overlap 拦死启动。定版策略：同 toolId 跨类型注册冲突只能改注册表（重命名/退役）整改，页面开关处置不了，拦启动/拦启用不成比例且会造成死锁。变更：`ToolRoutingStartupAudit` HIGH 对循环里 `toolIdA==toolIdB` 的对只 log.warn（"请重命名独立 toolId 或退役其一 (不阻断启动)"），不产生 tool_overlap issue（strict-startup 也不会因它拦）；`ToolRoutingMetadataAdminService.assertNotHighOverlap` 同样跳过。页面 HIGH 报告与角标照常可见。282 测试全过。

**6.4 同 toolId 冲突允许启用其中一个（2026-10-09，已实施）**：元数据表一行对应一个 toolId，"启用其中一个" = 把该行指向选定类型，路由目录仍只有一条 q2_1 条目，Agent 选型无风险——此前开关置灰纯属前端把同 toolId 对也算进 highByTool。变更：`OverlapSummary` 拆出 `duplicateByTool`（同 toolId 对不再进 `highByTool`）；工具路由页开关置灰只看 `highByTool`，同 toolId 冲突改亮橙色 "ID重复" 角标（点击跳重叠页），红色 "重叠 N" 角标仍留给四层 HIGH 对。283 测试全过，前端 vite build 通过。

### 6.5 全量"仅可启用其中一个"策略（2026-10-09，已实施）

用户定版：不只同 toolId 冲突，所有 HIGH 重叠对（四层一致）统一执行**同一候选集内至多启用一个**。页面侧本就如此（对侧未启用放行、只拦第二个，assertNotHighOverlap 提示语同步改为"请先停用对方，或在重叠检测页区分描述或标签后再启用"）；本次改的是启动期对"双方已启用"存量数据的处置——**由阻断启动改为自愈**：

- `ToolRoutingStartupAudit`（block-tool-overlap=true 且重复工具数 ≤ block-tool-overlap-max-tools）：每对自动停用低优先级一侧（优先级相同保留 toolId 较小者，确定性规则重启幂等），upsert enabled=false + WARN 日志（含保留侧与换启方法），失效 overlap 报告缓存；upsert 失败的对仍记 tool_overlap 走阻断路径兜底。自愈处置过的对不再产生阻断 issue → **程序不挂**。
- 超过 max-tools 阈值的批量数据问题：不自动变更元数据，沿用告警降级（静默改几百条太危险）。
- 同 toolId 冲突对：不参与自愈（元数据只有一行，无"双方"可停），维持只告警。
- 换保留侧流程：页面停用当前启用侧 → 启用另一侧（assertNotHighOverlap 只拦"第二个"）。
- 测试：284 全过（新增 bothEnabledHighPairAutoDisablesLoserInsteadOfBlockingStartup），前端提示文案同步，vite build 通过。

**6.4 修正（同 toolId 角标与状态显示，2026-10-09）**：用户反馈两处不对——①同 toolId 对在检测页就是 HIGH，工具路由页却只剩橙色角标丢了红色"重叠"标 → 红色角标恢复（highOverlap 与 duplicateByTool 任一命中都亮红色"重叠 N"，count 取先命中的；橙色"ID重复"保留在其后作注册冲突细别）；②"两条都能启用"是显示误导——tool_route_metadata 主键是 tool_id，两行共享同一条元数据记录，开关是同一个 → 状态列对冲突行追加显示启用记录指向的类型（如"已启用·SQL"），悬停提示说明两侧开关操作同一条元数据。仅前端变更，vite build 通过。

### 6.6 元数据主键改 (tool_id, tool_type)——同 toolId 各类型独立开关（2026-10-09，已实施）

用户定版：同 toolId 的 SCRIPT/SQL 要"一个开一个关"，各自独立配置与启用。数据模型变更：

- **DDL/迁移**：`tool_route_metadata` 主键 `tool_id` → `(tool_id, tool_type)`。新建表直接复合主键；存量表启动时 `migratePrimaryKeyIfNeeded()`——information_schema 检查 PK 是否已含 tool_type，未含则依次尝试 `DROP CONSTRAINT tool_route_metadata_pkey` / `DROP PRIMARY KEY` / `ADD PRIMARY KEY (tool_id, tool_type)`（逐条容错，失败下次启动重试；老 PK 下 (id,type) 天然唯一，无脏数据风险）。
- **启用互斥**：`AdminService.save` 在 metadata.enabled=true 时调 `repository.disableOtherTypes(toolId, keepType)` 自动停用同 toolId 其他类型（路由目录单条，Agent 按 toolId 寻址不能有歧义）——"启用即换指"，页面开关各自独立但同一时刻只有一个亮着。
- **防御**：`findByToolId` 加 `ORDER BY enabled DESC, priority DESC, tool_type`（路由寻址偏向启用行）；`ToolRoutingCatalogService.buildSnapshot` 按 toolId 去重取优先级高者（兜直改 DB 的双启用）。
- **前端**：configurations 映射键改 `toolId|toolType`，openConfig/saveConfig/toggleRoute/查看弹窗/三列标签展示全部按行类型定位；状态列恢复纯"已启用/已停用"（行级独立开关，不再有共享记录误导）；悬停提示"启用本类型会自动停用其他类型"。
- 不变项：scanScope 复合键/同 toolId HIGH 分支/duplicateByTool 角标/启动自愈均按 (id,type) 语义天然兼容；API 层 PUT body 本就带 toolType 无需改。
- 测试：286 全过（新增 enablingOneTypeDisablesOtherTypesOfSameToolId、disablingDoesNotTouchOtherTypes），前端 vite build 通过。**重启后端触发 PK 迁移 + 强刷前端**。

**6.6 修复（2026-10-09 内网实测）**：复合主键后 openGauss B-mode 报 "INSERT ON DUPLICATE KEY UPDATE don't allow update on primary key or unique key"（ErrorCode 9504）——upsert 的 UPDATE 子句含 `tool_type=VALUES(tool_type)`，tool_type 已是主键列被禁止更新。去掉该列（冲突行即同 tool_type，本就无需更新），仅前端保存开关报 ToolRoutingMetadataSaveFailed，其余路径不受影响。286 测试过。

**6.6 补遗（2026-10-09 复合主键遗留两处显示/状态错位）**：用户反馈"两个重复 id 的工具还是能同时启用"。后端互斥链（save→disableOtherTypes）本身是对的，错在状态传播：① `ToolRoutingScanService.scan()` 的 configured 映射仍按 toolId 单键——复合主键下同 toolId 两行元数据互相覆盖（last-write-wins），两行扫描行显示同一个启用状态；改键 `toolId|toolType`、按 (toolId, toolType) 取行。② 前端 `toggleRoute` 只更新被切换的行，被 disableOtherTypes 连带停用的另一行要到下次刷新才翻绿→红；启用成功后本地同步同 toolId 其他类型行 routeEnabled=false。新增回归测试 sameToolIdTypesReflectTheirOwnMetadataState（SCRIPT 启用/SQL 停用各自如实显示）。
