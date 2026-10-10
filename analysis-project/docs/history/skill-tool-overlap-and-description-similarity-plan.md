# Skill-Tool 功能重叠检测 与 Skill 描述相似提醒 方案

> 设计基线：延续 `docs/skill-routing-config-page-plan.md`（Skill 路由配置页）与 `docs/tool-metadata-unified-routing-plan.md`（统一工具路由）。本方案只做**治理检测与提醒**，不改变两套路由的运行时行为。

## 0. 结论先行

| 问题 | 决策 |
|---|---|
| Skill-Tool 功能重叠在哪里提醒？ | 不塞进任何一侧的配置表单。新增一个专门的"重叠检测"页面（`ScriptRegistryShell` 菜单第 4 项），**只在重叠页提醒（2026-10-10 定版：Skill 配置页不打标）**；Tool 路由页仅保留指向重叠页的橙色 "Skill重叠" 角标（纯提示，不影响启用开关） |
| 是否新增数据库表存重叠对？ | 不需要。重叠是**派生数据**，由检测服务按需计算 + 内存缓存（短 TTL）。配置元数据变更后主动失效 |
| Skill 描述相似在哪里校验？ | Skill 广场保存链路：前端保存前调独立检查接口弹提醒；后端 `create/update` 复检防绕过。相似列表返回 `ownerUserId`，提示"已存在相近描述，请联系 X" |

## 1.0 两侧检索链路差异（重叠判定的语义边界，2026/10/10 补）

Skill 与 Tool 的 agent 筛选链路完全不同，重叠检测必须按"两条链路各自怎么选中实体"来定义，不能套用 Tool↔Tool 的"同一候选集"前提（那是同一链路内的四层比较）：

| | Skill 检索链路 | Tool 检索链路 |
|---|---|---|
| 圈定 | 领域标签 domainTags 互斥过滤/加成 + keywords 命中 | topic_tags 圈定候选集（metadata Top-K） |
| 下钻 | — | 指标 metric_tags / 维度 dimension_tags |
| 终选 | **描述**（LLM 对着声明文本选） | **描述**（LLM 对着 toolId 描述选型） |

推论：

1. **两侧唯一稳定交汇点是最终"描述层"**——所以 S2（声明文本 vs tool 描述语义相似）是主信号，S3（keywords/名称 vs toolId）是强信号；S1'（keywords 字面命中 tool 侧词面）是两条链路之间的词面交汇佐证。
2. **Tool 侧四层标签不做"层相等"比较，但作为 S1' 佐证匹配面参与**（2026-10-10 修订）：Skill 侧没有对应层，层对层比较不成立且字典不相交；但 topic/metric/dimension 标签是 tool 的路由圈定面，与 skill keywords 一样描述"什么问题会走到我"，故纳入 S1' 的字面匹配面。

## 1. 场景一：Skill-Tool 功能重叠检测

### 1.1 重叠的语义（先定义清楚再检测）

按统一路由文档 §3.1 的两层模型：Skill 是多步业务工作流，Tool 是原子能力。因此**重叠不等于错误**，检测目标是三类治理问题：

| 类别 | 特征 | 治理动作 |
|---|---|---|
| A. 原子工具枚举包装 Skill | Skill 只是枚举/转发若干 tool 的能力，无业务规则 | 退役 Skill，描述迁入 `tool_route_metadata` |
| B. 能力声明越界 | Skill 的 description/summary 声称的能力其实已有 Tool 覆盖，Skill 应上收为"编排"而非"能力" | 修改 Skill 描述/摘要，明确工作流价值 |
| C. 正常引用 | Skill 正文写固定 `toolId`、按指标调 `tool_index` | **不算重叠**，不告警 |

关键边界：**只看能力声明层（description / short_summary / 标签），不看 Skill 正文对工具的引用**。正文引用工具是设计意图，能力声明重叠才是治理对象。

### 1.2 检测信号（三层，加权分级）

输入数据全部来自现有表，无需新增采集：

- Skill 侧：`skill_index`（name, description, owner_user_id, status）+ `skill_routing_metadata`（short_summary, keywords, domain_tags, topic_tags, active）。**注（2026-10-10 修订）**：字段以当前 `SkillRoutingMetadata` record 为准（skillName / shortSummary / keywords / domainTags / topicTags / creator / active），metric_tags、aliases、data_source_tags、priority 已随评分体系简化移除
- Tool 侧：`tool_route_metadata`（tool_id, tool_type, description, topic_tags, metric_tags, dimension_tags, enabled）

信号定义（2026-10-10 修订版）：

| 信号 | 计算 | 定位 |
|---|---|---|
| S2 描述语义相似 | EmbeddingClient cosine(skill 声明文本, tool description)，阈值可配（默认 0.80） | **主信号**。声明文本实际取 `short_summary` 优先，兜底 `skill_manage.description`、`skill_index.description`（见 1.5 修订注） |
| S3 名称/别名/字面引用 | ① skill 路由 `keywords` + skillName + 两侧行名称归一化（全半角/大小写/`_`/`-`）后与 `tool_id` 精确或近匹配（归一化编辑距离 ≥ 0.85）；② skill 声明文本中出现 `tool_id` 字面量 | 强信号。②是包装 Skill 的典型特征（描述里直接枚举工具 ID） |
| S1' 关键词字面佐证 | ① skill 路由 `keywords` 字面命中 tool 侧词面（`topicTags ∪ metricTags ∪ dimensionTags ∪ description` 任一包含）；② skill `domainTags` **定向配对** tool `dimensionTags`（字面包含，不参与对 description/topic/metric 的通用匹配）。①②任一命中即 S1' 命中 | **仅佐证，不单独定级**。语义：keywords 与四层标签/描述都是两侧的"路由面"——命中即同一问题词面会同时把请求导向 skill 与 tool；domain↔dimension 是同类粗粒度轴（域过滤 ↔ 数据切片范围）的定向对齐，两者值空间现状基本不相交（词典血缘是 DOMAIN→DOMAIN 而非 DOMAIN→DIMENSION），对齐需词典侧配合，空转期不添噪音。tool 标签不参与"层相等"比较（skill 无对应层），但作为佐证匹配面参与 |

> 设计修订说明（2026-10-10）：原 S1"指标标签/主题标签交集"作废——`SkillCandidateSelector` 的路由匹配只用 **显式名称/keywords 命中 + domainTags 互斥过滤与加成 + 描述**，`topicTags` 仅内部管理归类不参与匹配，metric_tags 已移除；且 skill 侧 topicTags 走 DOMAIN 之外的自有字典、与 tool 侧 TOPIC 字典不相交，交集恒空无佐证价值。佐证改用 keywords 字面命中（keywords 是路由实际生效字段）。原修正说明"编排 Skill 必然与工具共享指标标签"的结论仍成立，只是承载字段换成了 keywords。

分级规则：

```text
HIGH   = S3 命中（名称匹配或描述含 toolId 字面量）
        或 S2 ≥ 高阈值(0.88)
MEDIUM = S2 达阈值(0.80) 且 S1' 命中（语义相似 + 关键词字面佐证）

（2026-10-10 定版：S2 达阈值但无 S1' 佐证的对不出记录——纯措辞相似缺独立证据，
 展示只添噪音；skill-tool 视图取消 LOW 级，级别只剩 HIGH/MEDIUM）
```

参与检测的范围：Tool 侧 `enabled=true`；Skill 侧路由 `active=true` **且** `skill_index.status='active'`（软删的页面 Skill 经 `markBlacklist` 后行仍在表里，必须排除，否则已删 Skill 继续出现在重叠对中）。停用侧的历史重叠不产生噪音。

### 1.3 降级路径

`EmbeddingClient` 实现可能返回 null（无 provider 或调用失败，见接口契约"must not throw"）。降级规则：

- 单条 embed 失败：该实体只参与 S1/S3 文本信号，不参与 S2；
- 整体无 embedding provider：结果响应带 `degraded: true`，只输出 S1/S3 命中的对；
- 检测接口本身不因 embedding 异常失败，与 episodic 检索的容错口径一致。

### 1.4 数据与缓存（不新增持久化重叠表）

- **不建"重叠对"数据库表**：重叠是元数据的派生视图，落表会引入同步负担（两侧任何一次保存都要重算），且治理页天然是低频访问。
- 新增内存级 `SkillToolOverlapService`（建议放 `v2/toolrouting` 或新 `v2/governance` 包）：
  - 检测结果缓存于 JVM 内存，TTL 默认 300s；
  - `SkillRoutingMetadataRepository` 保存、`ToolRoutingMetadataAdminService` 保存成功后主动失效（与 tool routing 目录快照的失效模式一致）；
  - 多 JVM 最多延迟一个 TTL 看到新结果，治理场景可接受。

### 1.5 共用基础设施：描述 Embedding 缓存

S2 和场景二都需要"实体描述 -> 向量"。新增一个进程内缓存 `GovernanceEmbeddingCache`：

- 键：`(entityType, entityId)`，entityType ∈ `SKILL | TOOL`；SKILL 的 entityId 用 `retrievalName`（与 `skill_index.name` 一致，由 `SkillManageBridge` 保证同步）；
- 值：`float[]`，来源 `EmbeddingClient.embed()`；
- **描述来源（2026-10-10 修订）**：TOOL 取 `tool_route_metadata.description`；SKILL 声明文本**实际实现**为三级兜底——`skill_routing_metadata.short_summary` 优先，空则取 `skill_manage.description`（status != DELETED），再兜底 `skill_index.description`（见 `SkillToolOverlapService.compute()`）。方案原定"skill_manage.description 单一权威源、short_summary 不参与"未落地，以实现为准；若后续 short_summary 与广场描述分化明显再评估是否切回单一权威源；
- 加载策略：**启动后台异步预热**（不阻塞启动）+ 保存写穿。不预热则场景二首次保存需对全库 Skill 逐条 embed（数百次调用），延迟不可接受；预热未完成期间检测自动降级为 L1/S3 文本信号，响应带 `degraded: true`；
- 保存写穿：Skill 广场保存（`SkillManageBridge.syncToRetrievalIndex` 成功后）、Skill 路由元数据保存、Tool 路由元数据保存时更新对应条目；软删/删除时移除；
- 规模评估：数百 Skill + 数百 Tool，每向量约 4KB，全量 < 5MB，内存可承载；
- 不落库。注意：`skill_index` 的 embedding 列此前是有意删除的（避免写入空转），本缓存沿用"不写 DB"的决策，重启后惰性重建；
- 无 provider 时缓存返回空，走 1.3 降级。

### 1.6 后端接口

新增 `RoutingOverlapController`，路径 `/api/routing-overlap`：

```text
GET /api/routing-overlap
     查询重叠对列表，支持 level(HIGH/MEDIUM/LOW)、skillName、toolId、degraded 过滤与分页

GET /api/routing-overlap/summary
     各级别计数 + 按 skillName/toolId 的命中计数（供两个配置页行内徽标）
```

返回对象（只读 DTO，不复用内部 record）：

```json
{
  "skillName": "query_defect_density",
  "skillOwnerUserId": "u_1024",
  "skillSummary": "查询缺陷密度并生成报告",
  "toolId": "defect_density_query",
  "toolType": "SQL",
  "level": "HIGH",
  "signals": {
    "metricTagOverlap": ["缺陷密度"],
    "cosine": 0.87,
    "aliasHit": false,
    "toolIdLiteralInDescription": true
  },
  "suggestion": "该 Skill 与工具在指标[缺陷密度]上能力重叠且描述高度相似；若 Skill 仅为工具枚举/转发，建议退役并将描述迁入工具路由元数据"
}
```

`suggestion` 按 §1.1 的类别 A/B 生成模板文案，不做自动处置。

### 1.7 前端

新增：

```text
frontend/src/pages/RoutingOverlapPage.vue     # 路由 /script-registry/overlap
frontend/src/api/routingOverlap.ts
frontend/src/types/routingOverlap.ts
```

修改：

```text
frontend/src/components/ScriptRegistryShell.vue  # 菜单第 4 项"重叠检测"
frontend/src/pages/SkillRoutingConfigPage.vue    # **移除**已有 HIGH 徽标接线（2026-10-10 定版：Skill 配置页不打标）
frontend/src/pages/ToolRoutingPage.vue           # 保留 "Skill重叠" 橙色角标（纯提示跳重叠页，不影响启用开关）
```

页面交互：

1. 顶部统计卡片：HIGH / MEDIUM / LOW 计数 + `degraded` 状态提示（"语义相似信号不可用，仅展示标签与名称命中"）；
2. 重叠对表格：Skill 名称、路由摘要、工具 ID/类型、信号明细（命中关键词、相似度）、级别、建议；
3. 点击 Skill 名称跳 Skill 配置页该行，点击工具 ID 跳 Tool 路由页该行；
4. **Skill 配置页不打标（2026-10-10 定版）**：Skill 与 Tool 分属两条不同检索链路，重叠治理动作是退役/改写 Skill，属低频治理事项，统一收敛到重叠页查看——重叠页是唯一提醒入口（Tool 路由页 "Skill重叠" 角标保留，因整改主体是 Skill 而非工具开关，仅作提示）；
5. 第一期**纯展示**，不提供"忽略"持久化——若上线后噪音明显，二期再加 `overlap_ignore` 表（这也是本方案唯一预留的将来落库点）。

### 1.8 权限

- 重叠检测是管理员治理视图：查询接口沿用登录校验，建议仅管理员角色可见（与 Skill 路由配置页的权限升级同步做）；
- 结果中不包含 Skill 正文、SQL 模板、脚本路径，与统一路由文档"候选不含执行细节"口径一致。

## 2. 场景二：Skill 广场创建/保存时描述相似提醒

### 2.1 总体链路

```text
SkillFormPage 编辑
  -> 保存前调用 POST /api/skills/similarity-check （新增）
  -> 命中高相似：弹确认框列出相似 Skill（含 ownerUserId）
       "已存在相近描述的 Skill：xxx，请联系 {ownerUserId} 确认是否重复"
  -> 用户选择"仍要保存"：请求带 acknowledgeSimilar=true 再提交
  -> 后端 create/update 复检（防绕过前端）：
       命中高相似且未带 acknowledgeSimilar -> 409 + 相似列表
       带标记 -> 放行并记 WARN 日志（审计）
```

选择"强提醒 + 二次确认"而非纯提示的原因：Skill 数量会随广场和 agent 自进化持续增长，名称判重（现有 `existsByName`）只挡完全同名，描述相似不设卡会让重复能力野蛮生长，之后只能靠场景一的重叠页事后清理。若业务方坚持纯提示，只需把 409 改为响应体 `similarityWarnings` 字段，链路不变。

### 2.2 检测范围与算法

比较对象：**全部未删除的 Skill**（含他人 PRIVATE），不过滤可见性。去重治理的目标就是全库唯一，只与可见范围比较会漏掉跨用户的重复能力。

两级算法，任一命中即进入相似列表：

| 级别 | 计算 | 阈值（可配） |
|---|---|---|
| L1 文本 | ① 名称归一化（全半角/大小写/`_`/`-` 统一）后与已有 name、retrieval_name、skill_routing_metadata.aliases 比对；② description 分词 Jaccard；③ 归一化 Levenshtein 相似度 | 名称近匹配 ≥ 0.85；Jaccard ≥ 0.6 |
| L2 语义 | `GovernanceEmbeddingCache` cosine(新 description, 已有 description) | ≥ 0.85 |

- L2 命中但 L1 未命中是典型场景（换个说法写同一件事），必须有；
- 无 embedding provider 或 `GovernanceEmbeddingCache` 预热未完成时只跑 L1，响应带 `degraded: true`，前端提示"语义级检测不可用"；
- 自身更新（PUT）时 `excludeSkillId = 自身 id`，不和自己比。

### 2.3 后端改动

`SkillManageController` 新增：

```text
POST /api/skills/similarity-check
     body: { name, description, excludeSkillId? }
     返回: { degraded, matches: [ { skillId, name, description, ownerUserId,
                                    visibility, similarity, evidence } ] }
```

`SkillManageService`：

- `create(skill, ownerUserId)`：插入前复用同一检测；命中高相似（同 2.2 阈值）且请求未携带 `acknowledgeSimilar=true` 时抛业务异常（HTTP 409 + matches 列表），前端转确认框；
- `update(id, patch, userId)`：description 或 name 变更时同样复检，`excludeSkillId=id`；
- `createForAgent(...)`（agent 经 `SkillSaveTool` 创建）：同样复检但**只记 WARN 日志不阻塞**——agent 无法响应 HITL 确认框，阻塞会打断 ReAct 流程；日志供事后在重叠页人工跟进；
- 提醒语模板："该 Skill 已有相近描述（{name}，联系人：{ownerUserId}），请确认是否重复创建"。

### 2.4 比较范围与信息暴露（有意取舍）

比较范围是全库 Skill，因此相似列表可能包含当前用户不可见的 PRIVATE Skill，即"通过相似提醒可以感知到私有 Skill 的存在"。这是**有意接受的取舍**：内部平台、去重治理优先于该级别的存在性隐私；且提醒语本身就引导用户去联系 `ownerUserId`，隐藏目标反而让"联系谁"无从谈起。为把暴露面压到最小，相似列表对 PRIVATE Skill 只返回 `{name, description, ownerUserId, similarity, evidence}`，不返回其授权关系、正文和统计。

## 3. 代码改动清单

| 类型 | 路径 | 责任 |
|---|---|---|
| 新增 | `.../v2/governance/GovernanceEmbeddingCache.java` | 描述向量进程内缓存，写穿失效，无 provider 降级 |
| 新增 | `.../v2/governance/SkillToolOverlapService.java` | 场景一检测：三信号加权分级 + TTL 缓存 |
| 新增 | `.../v2/governance/RoutingOverlapController.java` | `/api/routing-overlap` 列表与 summary |
| 新增 | `.../v2/governance/SkillDescriptionSimilarityService.java` | 场景二检测：L1 文本 + L2 语义，可见性过滤 |
| 修改 | `.../v2/skillManager/controller/SkillManageController.java` | 新增 similarity-check 接口；create/update 透传 acknowledgeSimilar |
| 修改 | `.../v2/skillManager/service/SkillManageService.java` | create/update 复检 + 409；createForAgent 记日志 |
| 修改 | `.../v2/skills/SkillRoutingMetadataRepository.java`（或其保存入口） | 保存成功后失效 overlap 缓存与 embedding 缓存条目 |
| 修改 | `.../v2/toolrouting/ToolRoutingMetadataAdminService.java` | 保存成功后同上失效 |
| 新增 | `frontend/src/pages/RoutingOverlapPage.vue` + api/types | 重叠检测页 |
| 修改 | `frontend/src/components/ScriptRegistryShell.vue` | 菜单第 4 项 |
| 修改 | `frontend/src/pages/SkillRoutingConfigPage.vue` | 移除 HIGH 徽标接线（routingOverlapSummary 调用 + el-tag + 跳转方法） |
| 修改 | `frontend/src/pages/ToolRoutingPage.vue` | 保留 "Skill重叠" 角标（不再改动） |
| 修改 | `frontend/src/pages/skill/SkillFormPage.vue` | 保存前 check + 确认框 |
| 修改 | `src/main/resources/application.properties` | `harness.a2a.governance.overlap.*`、`similarity.*` 阈值与开关 |

配置项：

```properties
harness.a2a.governance.overlap.enabled=true
harness.a2a.governance.overlap.cosine-threshold=0.80
harness.a2a.governance.overlap.metric-overlap-high=2
harness.a2a.governance.overlap.cache-ttl-ms=300000
harness.a2a.governance.similarity.cosine-threshold=0.85
harness.a2a.governance.similarity.name-levenshtein-threshold=0.85
harness.a2a.governance.similarity.jaccard-threshold=0.60
```

## 4. 测试与验收

### 后端

- S1' 不单独定级：仅 keywords 与 tool description 字面相关的合法工作流 Skill 不高于 LOW（S2 未达阈值时不出记录）；S3 名称命中或描述含 toolId 字面量判 HIGH；S2 分级阈值生效；
- 软删 Skill 不参与：`skill_index.status='blacklist'` 或路由 `active=false` 的行不出现在重叠对；
- embedding 返回 null：单条降级不报错；无 provider 或预热未完成：整体 `degraded=true` 且文本信号正常；
- SKILL 声明文本三级兜底（short_summary → skill_manage.description → skill_index.description）逐级生效，声明为空的对跳过；
- overlap 缓存 TTL 生效，两侧元数据保存后主动失效；
- similarity-check 与全库 Skill 比较：他人 PRIVATE Skill 同样进入相似列表，但只含 name/description/ownerUserId/similarity/evidence，无授权关系与正文；DELETED 状态不参与；
- create 命中高相似且无 acknowledge：409 + matches；带 acknowledge：放行且日志含 skillName 与相似目标；
- update 排除自身；name/description 未变更的 update 不触发复检；
- createForAgent 命中只记日志，不抛异常。

### 前端

- `/script-registry/overlap` 可访问、受登录保护、菜单高亮一致；
- Skill 配置页无重叠徽标；Tool 路由页 "Skill重叠" 角标与重叠页过滤联动；
- SkillFormPage：命中相似弹确认框展示 ownerUserId，确认后二次提交成功，取消不落库；
- `npm run build` 成功。

### 治理验收（人工）

- 用一组真实数据跑一遍：能识别出至少 1 个 HIGH 案例（描述语义高度相似或含 toolId 字面量），且编排工具的正常工作流 Skill 不被误判为 HIGH（仅 S1' 关键词字面相关、S2 未达阈值时不出记录）；
- 故意创建一个描述与现有 Skill 高度相似的新 Skill，走完提醒 -> 确认 -> 创建全链路。

## 5. 实施顺序

1. `GovernanceEmbeddingCache` + 单测（含降级）；
2. `SkillToolOverlapService` + `/api/routing-overlap` + 单测；
3. `RoutingOverlapPage` + 两个配置页徽标；
4. `SkillDescriptionSimilarityService` + similarity-check 接口 + create/update/createForAgent 接入；
5. `SkillFormPage` 确认框；
6. 治理验收 + 配置调阈值。

一期明确不做：重叠对落库、忽略名单、LLM 自动判定重叠语义、agent 创建链路的阻塞式确认。

## 6. 修订记录

### 6.1 S1 佐证信号换轴 + 与实现对齐（2026-10-10，**代码待实施**）

**触发**：Skill 路由评分体系简化后，`SkillCandidateSelector` 的匹配只用 **显式名称/keywords 命中 + domainTags 互斥过滤与加成 + 描述**；`topicTags` 仅内部管理归类不参与匹配，metric_tags/aliases/data_source_tags/priority 字段已移除。原方案 S1（metric_tags 交集，后实现漂移为 topicTags 交集）的佐证来源已不参与 Skill 路由，失去佐证语义。

**变更**（§1.2 已按此重写，代码 `SkillToolOverlapService.evaluatePair()` 尚未改）：

1. S1 换轴：`skill.keywords` 字面命中 `tool.description` 作为唯一佐证信号（S1'）。keywords 是路由实际生效字段，语义 = 同一问题词面同时导向 skill（keywords 路由）与 tool（描述选型）。
2. 不采用 domainTags ∩ tool topicTags 的层对层比较：skill 侧与 tool 侧分属不同标签字典（DOMAIN vs TOPIC），交集恒空；tool 四层标签改以"佐证匹配面"身份参与 S1'（keywords 字面命中 topic/metric/dimension 标签或 description 任一），2026-10-10 讨论定版。
3. 分级（2026-10-10 讨论定版）：HIGH = S3 或 S2 ≥ 0.88（不变）；MEDIUM = S2 ∈ [0.80, 0.88) 且 S1' 命中；**S2 达阈值但无 S1' 佐证的对不出记录**（原 LOW 巡检级取消，skill-tool 视图只剩 HIGH/MEDIUM 两级）。
4. 同步修正与实现的另外两处漂移：① 声明文本实际是 short_summary → skill_manage.description → skill_index.description 三级兜底，非原方案的"skill_manage.description 单一权威源"（§1.5）；② S3 别名比对集合实际含路由 keywords + skillName + 两侧行名称（§1.2）。

**实施范围（后续动代码时）**：`SkillToolOverlapService.evaluatePair()` S1 计算与分级（view 的 `topicTagOverlap` 字段语义改为命中的关键词列表，或更名 `keywordEvidence`）、`RoutingOverlapView` 与前端 `RoutingOverlapPage.vue` 信号列文案、`SkillToolOverlapServiceTest` 对应用例重写；`invalidate()`/缓存/接口/前端结构不变。

### 6.2 检索链路差异定界 + Skill 配置页取消打标（2026-10-10，**代码待实施**）

用户定版两点：

1. **两侧检索链路差异写入判定语义**（新增 §1.0）：Skill 链路 = 领域标签/keywords/描述供 agent 筛选；Tool 链路 = 四层（主题/指标/维度/描述）供 agent 筛选。重叠检测的主轴收敛到两侧唯一稳定交汇点"描述层"（S2/S3），S1' keywords 字面佐证是两条链路间唯一词面交汇；Tool 侧四层标签不参与与 Skill 的比较（Skill 侧无对应层且字典不相交）。
2. **有重叠仅提醒，Skill 配置页不打标**：重叠的唯一提醒入口是重叠检测页。`SkillRoutingConfigPage.vue` 现有的红色 HIGH 徽标接线（routingOverlapSummary + highBySkill + el-tag 跳转，:83-84/:148）**待移除**；Tool 路由页的橙色 "Skill重叠" 角标保留（整改主体是 Skill，不影响工具启用开关）。

**实施范围（后续动代码时）**：仅前端——`SkillRoutingConfigPage.vue` 删 routingOverlapSummary 导入与调用、highOverlap 状态、el-tag 徽标及跳转方法；§0/§1.0/§1.7/§3/§4 文档已同步。

### 6.3 重构定版（2026-10-10 讨论确认，**已实施并全量验证**）

用户对三个开放决策点定版：

1. **Tool 路由页橙色 "Skill重叠" 角标保留**（整改主体是 Skill，不影响工具启用开关，仅提示跳重叠页）；
2. **S1' keywords 字面佐证保留，且匹配面扩到 tool 四层标签 + domain↔dimension 定向配对**（讨论中用户指出 tool 标签是重要评判依据，并确认领域标签概念上近似 tool 的维度轴）：S1' = ① skill keywords 字面命中 `topicTags ∪ metricTags ∪ dimensionTags ∪ description` 任一；② skill domainTags 定向配对 tool dimensionTags（字面包含，不参与通用匹配避免粗粒度擦边）；①②任一命中即佐证成立；
3. **无佐证不展示**：S2 ∈ [0.80, 0.88) 但无 S1' 佐证的对不出记录，**skill-tool 视图取消 LOW 级**，只剩 HIGH/MEDIUM 两级（前端统计卡与级别过滤的 LOW 项同步移除）。

合并 §6.1 + §6.2 后的完整实施范围：

| 层 | 文件 | 改动 |
|---|---|---|
| 后端 | `SkillToolOverlapService.evaluatePair()` | S1' 计算（keywords 字面命中 tool 四层标签∪description）+ 分级（HIGH=S3或≥0.88 / MEDIUM=0.80~0.88 且 S1' / 无佐证不出）+ suggestion 文案；javadoc 同步 §1.0 链路差异 |
| 后端 | `RoutingOverlapView` | `topicTagOverlap` 更名 `keywordEvidence`（HTTP 合约变更，前端同步） |
| 测试 | `SkillToolOverlapServiceTest` | topicTag 用例改 keywords 佐证语义；新增"无佐证不出记录"用例；存量 8 用例中受影响的重写 |
| 前端 | `types/routingOverlap.ts` | `RoutingOverlapItem.topicTagOverlap` → `keywordEvidence`（ToolToolOverlapItem 不动） |
| 前端 | `RoutingOverlapPage.vue` | 信号列"业务主题重叠"改"关键词命中"；degraded 提示"仅展示名称与业务主题命中"改"仅展示名称/关键词命中"；skill-tool 统计卡删 LOW 计数 |
| 前端 | `SkillRoutingConfigPage.vue` | 删 routingOverlapSummary 导入/调用、highOverlap 状态、el-tag 徽标、跳转方法、徽标样式 |

不变项：TTL 缓存与 invalidate()、Controller 端点与过滤参数、联合存活判定、degraded 降级口径（S3 仍可判 HIGH）、Tool 路由页角标、启动审计（skill-tool 重叠不参与阻断）。

**实施记录（2026-10-10）**：上述 6 文件全部落地。`evaluatePair()` 的 S1' 词面包含采用双向 contains（`tag.contains(kw) || kw.contains(tag)`，兼容中文构词）；`SkillToolOverlapServiceTest` 重写后 11 用例（新增 topicTagOverlapNoLongerProducesRecords / keywordEvidenceAloneDoesNotProduceRecord / mediumRequiresKeywordCorroboration / domainDimensionDirectedPairingCountsAsEvidence，medium 用例用 "报告"→{1,1} vs {1,4} 的 embedding stub 构造 cosine≈0.857）；全量 **322 用例 BUILD SUCCESS**，前端 vite build 通过。注：RoutingOverlapPage.vue 当时已并行叠加了 Skill↔Skill 第三视图，本次仅改 skill-tool 相关部分。
