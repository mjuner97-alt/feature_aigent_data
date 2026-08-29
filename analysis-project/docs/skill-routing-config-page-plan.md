# Skill 路由配置页面实施方案

## 1. 目标

在现有 `SCRIPT 注册表` 管理区域增加一个“Skill 配置”页面，用于维护 Skill 路由元数据，服务于 Capability Router 和 Top-K Skill 候选筛选。

页面只维护配置，不维护 Skill 具体内容：

- 不显示、不编辑 `SKILL.md` 正文；
- 不复制 Skill 广场中的内容编辑表单；
- Skill 名称、默认摘要和是否存在由已有 Skill 注册表自动提供；
- 用户只补充路由所需的别名、关键词、指标标签、领域标签、优先级和启用状态。

## 2. 当前基础

项目已有以下运行时能力：

- `skill_index`：Skill 基础索引；
- `skill_routing_metadata`：Skill 路由元数据表；
- `SkillRoutingMetadataRepository`：GaussDB 读写仓储，并支持启动时建表；
- `SkillCandidateSelector`：按名称、别名、关键词和优先级筛选候选；
- `SkillVectorIndexVisibilityFilter`：将候选结果注入运行时 Skill 可见性。

当前缺口是：`skill_routing_metadata` 没有管理 API，前端也没有配置入口。

## 3. 页面位置与路由

沿用现有 `ScriptRegistryShell` 左侧菜单，增加第三项：

```text
SCRIPT 注册表
├── SQL 注册
├── python 脚本注册
└── Skill 配置
```

新增前端路由：

```text
/script-registry/skills
```

页面继续复用现有登录守卫、`X-User-Id` 请求头、Element Plus 表格和分页样式。

## 4. 配置字段

### 4.1 列表展示字段

列表只展示配置状态和摘要信息，不展示 Skill 正文：

| 字段 | 来源 | 编辑方式 |
|---|---|---|
| Skill 名称 | `skill_index.name` | 只读 |
| Skill 描述 | `skill_index.description` | 只读，超长省略 |
| 路由摘要 | `skill_routing_metadata.short_summary` | 配置弹窗编辑 |
| 别名 | `aliases` | 配置弹窗编辑 |
| 关键词 | `keywords` | 配置弹窗编辑 |
| 指标标签 | `metric_tags` | 配置弹窗编辑 |
| 领域标签 | `domain_tags` | 配置弹窗编辑 |
| 数据源标签 | `data_source_tags` | 配置弹窗编辑 |
| 优先级 | `priority` | 配置弹窗编辑 |
| 启用 | `active` | 列表开关 |
| 更新时间 | `updated_at` | 只读 |
| 操作 | - | 配置/停用 |

### 4.2 降低用户配置量

用户不需要填写 Skill 名称、完整描述或正文：

1. 后端以 `skill_index` 为主表查询全部 Skill；
2. 没有路由记录的 Skill 由查询接口返回一条默认空配置；
3. 保存时只写入 `skill_routing_metadata`，不存在则 upsert；
4. `short_summary` 初始值优先取 `skill_index.description`，用户只在需要时修改；
5. 数组字段使用逗号分隔的标签输入，前端转换为 JSON 数组，避免用户手写 JSON；
6. `active` 默认开启，优先级默认 `0`；
7. 页面不提供“新建 Skill”操作，Skill 的创建和正文配置仍在 Skill 广场完成。

建议默认值：

```text
short_summary    = skill_index.description 或空字符串
aliases          = []
keywords         = []
metric_tags      = []
domain_tags      = []
data_source_tags = []
priority         = 0
active           = true
```

## 5. 后端接口设计

新增控制器，例如 `SkillRoutingMetadataController`，路径统一为 `/api/skill-routing`：

```text
GET  /api/skill-routing
     查询全部 Skill 及其路由配置，支持 keyword、active、page、size

GET  /api/skill-routing/{skillName}
     查询单个 Skill 的配置；无配置时返回由 skill_index 补齐的默认值

PUT  /api/skill-routing/{skillName}
     保存配置，执行 upsert；skillName 必须存在于 skill_index

PATCH /api/skill-routing/{skillName}/active
      仅切换 active，不修改其他字段
```

### 5.1 返回对象

```json
{
  "skillName": "q2_1_by_dept_version_metrics",
  "description": "部门和版本 Q2-1 指标查询",
  "shortSummary": "按部门和版本查询 Q2-1 指标",
  "aliases": ["q2_1", "Q2-1"],
  "keywords": ["达标率", "打分率"],
  "metricTags": ["quality"],
  "domainTags": ["quality_metrics"],
  "dataSourceTags": ["gauss"],
  "priority": 10,
  "active": true,
  "updatedAt": "2026-08-27T16:00:00"
}
```

### 5.2 校验规则

- `skillName` 必须存在于 `skill_index`，禁止保存孤儿配置；
- `shortSummary` 最大 3000 字符；
- 每个标签数组去空、去重，单项最大 128 字符；
- `priority` 为整数，建议范围 `-1000..1000`；
- 未知字段拒绝保存；
- 停用只影响路由候选，不删除 Skill 内容和历史数据；
- 更新名称时由既有 Skill 管理服务同步元数据，页面不允许直接修改主键名称。

## 6. 数据访问实现

扩展现有 `SkillRoutingMetadataRepository`，增加：

```java
List<SkillRoutingMetadataView> findAllWithSkillIndex(String keyword, Boolean active, int limit, int offset);
Optional<SkillRoutingMetadataView> findOneWithSkillIndex(String skillName);
boolean upsert(String skillName, SkillRoutingMetadataInput input);
boolean setActive(String skillName, boolean active);
```

查询采用 `skill_index LEFT JOIN skill_routing_metadata`，因此未配置 Skill 也会出现在页面；不要把 `skill_index` 中不存在的路由记录返回给前端。

建议新增只读 DTO，避免把仓储内部 `record SkillRoutingMetadata` 直接暴露为 HTTP 合约。

## 7. 前端实现

新增：

```text
frontend/src/api/skillRouting.ts
frontend/src/types/skillRouting.ts
frontend/src/pages/SkillRoutingConfigPage.vue
```

修改：

```text
frontend/src/components/ScriptRegistryShell.vue
frontend/src/main.ts
```

页面交互：

1. 页面进入时加载配置列表；
2. 支持按 Skill 名称、描述搜索，按启用状态筛选；
3. 点击“配置”打开窄表单弹窗；
4. 标签字段用文本输入，按逗号拆分并展示为可删除 Tag；
5. 摘要、标签、优先级保存后刷新当前行；
6. 列表开关只调用 active 接口；
7. 保存失败显示后端校验消息；
8. 不提供查看/编辑正文按钮，避免用户误以为此页管理 Skill 内容。

## 8. 与运行时的关系

运行时仍按以下链路工作：

```text
skill_index + skill_routing_metadata
    -> Capability Router / SkillCandidateSelector
    -> Top-K Skill 摘要
    -> Agent 通用工具集
```

本页面更新后无需重新发布 Java 代码；运行时仓储下一次查询即可读取新配置。缓存若已存在，应采用短 TTL 或保存后清理缓存，确保管理页面修改在下一个请求生效。

页面配置不改变：

- Skill 正文加载和存储方式；
- `script_exec`、`sql_registry_exec` 等通用工具注册；
- `/ai/chat`、`/v2/ai/chat` 的接口协议；
- ECharts/HTML `script_output` 事件；
- Skill 广场已有的创建、编辑、审批和发布流程。

## 9. 权限与安全

- 查询接口沿用当前登录校验；
- 修改接口至少要求已登录用户，建议后续增加管理员/Skill 所有者权限；
- 不允许通过此页面修改其他用户的 Skill 正文或可见性授权；
- 后端再次校验 `skillName`、标签长度和 priority，不能只依赖前端校验；
- 日志只记录 Skill 名称、操作类型和结果，不记录业务问题或工具结果正文。

## 10. 数据一致性与启动扫描

已有 `BuiltinSkillRegistrar` 会在启动时将内置 Skill 注册到 `skill_index`，并为缺失路由记录写入默认元数据。页面查询采用左连接作为兜底。

建议在后续实施中增加一个只读一致性检查接口或启动日志：

- `skill_index` 中有、路由表中没有：显示“待配置”，不阻塞运行；
- 路由表中有、`skill_index` 中没有：记录告警，不展示，不自动删除；
- Skill 停用时将路由 `active=false`，保留历史配置。

## 11. 测试与验收

### 后端

- 列表能返回已有 Skill 和未配置 Skill；
- 无配置 Skill 的默认摘要来自 `skill_index.description`；
- upsert 后再次查询能读回全部字段；
- 非法 Skill 名称、超长标签、非法 priority 被拒绝；
- 停用后不再进入 `findActive()`；
- 孤儿路由记录不会出现在列表；
- 数据库异常返回明确的 5xx 错误，不泄漏连接信息。

### 前端

- `/script-registry/skills` 路由可访问并受登录保护；
- 菜单高亮和现有 SQL/python 页面一致；
- 标签输入不要求手写 JSON；
- 保存、启用/停用、搜索状态正确更新；
- 页面不出现 `SKILL.md` 正文编辑控件；
- `npm run build` 成功。

### 运行时回归

- 更新别名后，用户使用该别名可以命中对应 Skill；
- `active=false` 后，Skill 不进入候选集；
- 显式 Skill 名称仍然优先命中；
- Skill 正文、通用工具和两个聊天接口行为不变。

## 12. 实施顺序

1. 新增后端 DTO、查询/保存接口和仓储测试；
2. 新增前端类型、API 和配置页面；
3. 把页面接入 `ScriptRegistryShell` 与路由；
4. 执行后端单元测试和前端构建；
5. 使用一个已有 Skill 验证“默认待配置 -> 保存别名/关键词 -> 运行时命中”；
6. 再批量补齐 Top-10 Skill 的能力、摘要、别名和关键词。

整个页面只增加路由配置入口，不重复维护 Skill 内容，用户配置字段控制在必要范围内。
