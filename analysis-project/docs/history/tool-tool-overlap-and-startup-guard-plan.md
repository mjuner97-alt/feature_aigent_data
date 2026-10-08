# Tool-Tool 重叠检测 + 工具路由启动阻断 方案

日期：2026-10-08
状态：已实施并 E2E 验证（2026-10-08，开关选 B；详见文末 §5 实施记录）
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

#### 3.1.1 实现

输入只用 `toolRoutingMetadataRepository.findEnabled()`，对工具两两配对（先按 topic 交集剪枝再算 cosine；工具数量几十级，O(n²) 可接受；结果 TTL 缓存 + `invalidate()`，模式与 `SkillToolOverlapService` 相同）。

判定信号：

- **T0 同候选集（前提）**：两侧 `topicTags` 交集 ≥ 1，否则跳过。
- **T1 描述不可区分**：两侧 description 的 embedding cosine ≥ `cosineThreshold`（0.80）。向量经 `GovernanceEmbeddingCache.embeddingFor(EntityType.TOOL, toolId, description)`，已有缓存不重复请求。
- **T2 功能不可区分**：参数签名相似 —— 从 `UnifiedToolMetadataService`（或 `ApiToolMetadataProvider.findApiTool`）取两侧参数表，比较"必填参数名集合"与"全参数名集合"：全参数名 Jaccard ≥ 0.8 且必填集合相同 → 功能签名不可区分。
- **T3 名称相似**：`normalizeName(toolIdA)` vs `normalizeName(toolIdB)` levenshtein ≥ `nameSimilarityThreshold`（仅 LOW 命名卫生用）。

分级：

| 级别 | 条件 | 含义 |
|---|---|---|
| **HIGH** | T0 且（T1 ≥ `cosineHighThreshold` 0.88，或 T1 ≥ 0.80 且 T2 命中） | 叶子选型时描述不可区分；或描述+功能双重不可区分 → **阻断启动** |
| **MEDIUM** | T0 且 T1 ≥ 0.80 | 描述相近但功能签名可区分，人工确认 |
| **LOW** | 仅 T3 命中（命名卫生），或 T0 交集 ≥ 2 但 T1/T2 均未命中（巡检） | 供巡检参考，永不阻断 |

T2（参数签名）不依赖 embedding，是硬证据：**degraded（embedding 不可用）时，T0 + T2 完全一致仍可判 HIGH 并阻断**；只有依赖 cosine 的判定在 degraded 下降级为不阻断。

视图 record `ToolToolOverlapView`：

```java
record ToolToolOverlapView(
        String toolIdA, String toolTypeA,
        String toolIdB, String toolTypeB,
        String level, List<String> topicTagOverlap,
        double cosine, boolean signatureSame,
        boolean aliasHit,
        String suggestion) {}
```

`suggestion` 固定句式含"**与工具 xxx 有重复**"，如：`工具 query_data 与工具 wide_table_query 在同一候选集内描述无法区分（cosine=0.93），Agent 叶子选型必然瞎选，请处理后重启`。

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

- **degraded（embedding 不可用）时的阻断口径**：依赖 cosine 的判定降级不阻断，只 WARN；但 **T0（同候选集）+ T2（参数签名完全不可区分）不依赖 embedding，属硬证据，degraded 下仍判 HIGH 并阻断**。
- LOW/MEDIUM 永不阻断，只进页面巡检。
- issue detail 文案统一为：`与工具 <对方toolId> 有重复，请处理`（3.3 第 2 步已含）。

### 3.4 前端（RoutingOverlapPage.vue）

最小改动方案——页面顶部加视图切换（`el-radio-group`）：

- `Skill↔Tool`（现有表格与逻辑不动）
- `Tool↔Tool`（新）：列 = 级别 / 工具A / 类型A / 工具B / 类型B / 主题交集 / cosine / 参数签名 / 信号 / 建议

`api/routingOverlap.ts` 加 `listToolToolOverlap()` / `toolToolOverlapSummary()`；`types/routingOverlap.ts` 加 `ToolToolOverlapItem`。summary 统计条复用（HIGH 计数取当前视图）。

### 3.5 测试

- `ToolToolOverlapServiceTest`（仿 `SkillToolOverlapServiceTest`）：
  - T0 未命中（topic 不相交）→ 即使描述完全相同也不出记录（前提校验）；
  - T0 命中 + cosine 0.93 → HIGH；
  - T0 命中 + cosine 0.82 + 参数签名不可区分 → HIGH；
  - T0 命中 + cosine 0.82 + 参数签名可区分 → MEDIUM；
  - 仅 toolId 名称相似（topic 不相交）→ LOW；
  - degraded：cosine 不可算时 T0+T2 完全一致 → 仍 HIGH；仅 T0 → 无 HIGH。
- `ToolRoutingStartupAuditTest` 补充：注入 mock `ToolToolOverlapService` 返回一对 HIGH → `block-tool-overlap=true` 时抛异常且消息含"与工具 xxx 有重复"；false 时仅 WARN；degraded 且仅 cosine 证据时仅 WARN。

### 3.6 E2E 验收

1. 管理页把某工具描述改成与另一工具同义（topic 有交集）→ 页面 Tool↔Tool 视图出现 HIGH。
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
