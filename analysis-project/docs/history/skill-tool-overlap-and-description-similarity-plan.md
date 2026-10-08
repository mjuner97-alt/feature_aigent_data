# Skill-Tool 功能重叠检测 与 Skill 描述相似提醒 方案

> 设计基线：延续 `docs/skill-routing-config-page-plan.md`（Skill 路由配置页）与 `docs/tool-metadata-unified-routing-plan.md`（统一工具路由）。本方案只做**治理检测与提醒**，不改变两套路由的运行时行为。

## 0. 结论先行

| 问题 | 决策 |
|---|---|
| Skill-Tool 功能重叠在哪里提醒？ | 不塞进任何一侧的配置表单。新增一个专门的"重叠检测"页面（`ScriptRegistryShell` 菜单第 4 项），Skill 配置页与 Tool 路由页只在行内放告警徽标，点击跳转到重叠页并过滤该实体 |
| 是否新增数据库表存重叠对？ | 不需要。重叠是**派生数据**，由检测服务按需计算 + 内存缓存（短 TTL）。配置元数据变更后主动失效 |
| Skill 描述相似在哪里校验？ | Skill 广场保存链路：前端保存前调独立检查接口弹提醒；后端 `create/update` 复检防绕过。相似列表返回 `ownerUserId`，提示"已存在相近描述，请联系 X" |

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

- Skill 侧：`skill_index`（name, description, owner_user_id）+ `skill_routing_metadata`（short_summary, aliases, keywords, metric_tags, domain_tags, data_source_tags, active）
- Tool 侧：`tool_route_metadata`（tool_id, tool_type, description, topic_tags, metric_tags, dimension_tags, enabled）

信号定义：

| 信号 | 计算 | 定位 |
|---|---|---|
| S2 描述语义相似 | EmbeddingClient cosine(skill 声明文本, tool description)，阈值可配（默认 0.80） | **主信号**。skill 声明文本 = `skill_manage.description`（单一权威源，见 1.5） |
| S3 名称/别名/字面引用 | ① skill `aliases`/name 归一化（全半角/大小写/`_`/`-`）后与 `tool_id` 精确或近匹配（归一化编辑距离 ≥ 0.85）；② skill 声明文本中出现 `tool_id` 字面量 | 强信号。②是包装 Skill 的典型特征（描述里直接枚举工具 ID） |
| S1 指标标签重叠 | `skill.metric_tags ∩ tool.metric_tags`，取交集数量 | **仅佐证，不单独定级**。两层架构下工作流 Skill 与其编排的工具共享 metric_tags 是正常现象（类别 C），单独出现不构成重叠证据 |

> 设计修正说明：S1 不能作为独立重叠信号——一个编排了缺陷密度工具的业务 Skill 必然与该工具共享"缺陷密度"标签。定级以 S2/S3 为锚，S1 只在 S2 已达阈值时作为佐证提升置信度。

分级规则：

```text
HIGH   = S3 命中（名称匹配或描述含 toolId 字面量）
        或 S2 ≥ 高阈值(0.88)
MEDIUM = S2 达阈值(0.80) 且 S1 ≥ 1（语义相似 + 指标相关，佐证）
LOW    = S2 单独达阈值，或 S1 ≥ 2（仅指标相关巡检提示）
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
- **描述单一权威源**：SKILL 取 `skill_manage.description`（status != DELETED），TOOL 取 `tool_route_metadata.description`。`skill_index.description`、`skill_routing_metadata.short_summary` 都可能与之漂移，不作为 embedding 来源；场景一接受用广场描述比对工具描述（short_summary 通常派生自它），若后续路由摘要分化明显再增加独立缓存键；
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
frontend/src/pages/SkillRoutingConfigPage.vue    # 行内告警徽标（调 summary 接口）
frontend/src/pages/ToolRoutingPage.vue           # 行内告警徽标（同上）
```

页面交互：

1. 顶部统计卡片：HIGH / MEDIUM / LOW 计数 + `degraded` 状态提示（"语义相似信号不可用，仅展示标签与名称命中"）；
2. 重叠对表格：Skill 名称、路由摘要、工具 ID/类型、信号明细（重叠标签、相似度）、级别、建议；
3. 点击 Skill 名称跳 Skill 配置页该行，点击工具 ID 跳 Tool 路由页该行；
4. 两个配置页的行内徽标：HIGH 计数 > 0 显示红色标记，点击跳重叠页并带该实体过滤参数；
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
| 修改 | `frontend/src/pages/SkillRoutingConfigPage.vue`、`ToolRoutingPage.vue` | 行内告警徽标 |
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

- S1 不单独定级：仅指标标签重叠（S1 ≥ 2）的合法工作流 Skill 不高于 LOW；S3 名称命中或描述含 toolId 字面量判 HIGH；S2 分级阈值生效；
- 软删 Skill 不参与：`skill_index.status='blacklist'` 或路由 `active=false` 的行不出现在重叠对；
- embedding 返回 null：单条降级不报错；无 provider 或预热未完成：整体 `degraded=true` 且文本信号正常；
- SKILL embedding 来源是 `skill_manage.description` 而非 short_summary / skill_index.description；
- overlap 缓存 TTL 生效，两侧元数据保存后主动失效；
- similarity-check 与全库 Skill 比较：他人 PRIVATE Skill 同样进入相似列表，但只含 name/description/ownerUserId/similarity/evidence，无授权关系与正文；DELETED 状态不参与；
- create 命中高相似且无 acknowledge：409 + matches；带 acknowledge：放行且日志含 skillName 与相似目标；
- update 排除自身；name/description 未变更的 update 不触发复检；
- createForAgent 命中只记日志，不抛异常。

### 前端

- `/script-registry/overlap` 可访问、受登录保护、菜单高亮一致；
- 两个配置页徽标计数与重叠页过滤联动；
- SkillFormPage：命中相似弹确认框展示 ownerUserId，确认后二次提交成功，取消不落库；
- `npm run build` 成功。

### 治理验收（人工）

- 用一组真实数据跑一遍：能识别出至少 1 个 HIGH 案例（描述语义高度相似或含 toolId 字面量），且编排工具的正常工作流 Skill 不被误判为 HIGH（S1-only 只落 LOW）；
- 故意创建一个描述与现有 Skill 高度相似的新 Skill，走完提醒 -> 确认 -> 创建全链路。

## 5. 实施顺序

1. `GovernanceEmbeddingCache` + 单测（含降级）；
2. `SkillToolOverlapService` + `/api/routing-overlap` + 单测；
3. `RoutingOverlapPage` + 两个配置页徽标；
4. `SkillDescriptionSimilarityService` + similarity-check 接口 + create/update/createForAgent 接入；
5. `SkillFormPage` 确认框；
6. 治理验收 + 配置调阈值。

一期明确不做：重叠对落库、忽略名单、LLM 自动判定重叠语义、agent 创建链路的阻塞式确认。
