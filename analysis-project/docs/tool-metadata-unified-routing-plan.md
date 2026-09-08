# SQL、API 与 Python 脚本统一工具路由设计实施方案

> **当前实现决策（2026-09）：本节优先于本文早期设计。** 系统不需要工具隔离，因此不再使用“调用范围”或 Skill-Tool 绑定进行授权。`tool_route_metadata.enabled` 仅控制工具是否进入 `tool_index` 目录；`toolMetaInfo`、`router_tool`、`sql_registry_exec`、`script_exec` 以及 Skill 中指定的固定 `toolId` 不得因该字段或 Skill 绑定而拒绝调用。执行时只校验真实 API/SQL/SCRIPT 对象存在、启用且参数合法。`access_scope`、`tool_route_skill_binding` 及 `ToolAccessPolicy` 仅保留历史兼容数据/代码，不属于新路由有效逻辑。

> 文档目的：针对内网环境中数百个预注册 SQL、数百个 API 数据查询工具和数百个 Python 脚本，建立一套由 LLM 自主探索、服务端确定性过滤的统一工具目录，避免将全量工具详情注入模型上下文。
>
> 设计基线：延续 `docs/context-compaction-governance-plan.md` 的上下文治理原则。Skill 路由负责选择多步业务工作流，Tool 路由负责工作流内或无 Skill 命中时的原子能力选择。初始 system prompt 只提供共享规范词典中的指标标签目录；LLM 根据指标分阶段查询候选与可用维度，再查询具体工具的参数元信息，最后调用现有执行入口。工具标签只负责路由召回，工具是否只能由指定 Skill 使用由独立的调用范围与绑定关系控制。

## 0. 主题优先三级目录修订（当前有效）

本节替代文档中所有“初始提示词注入指标目录”“第一次只传 `metricTags`”及两级“指标 → 维度”描述。统一路由使用三级受控标签：`业务主题（TOPIC） → 指标（METRIC） → 维度（DIMENSION）`。

- `TOPIC` 是指标归属的业务主题，不是可计算指标。例如 `QI卡口` 下可有 `Q1-1`、`Q2-1` 等指标；一个工具可标注多个主题。
- 初始 system prompt 只注入当前可执行工具的去重 `TOPIC` 标签，不注入全量指标。这样数百个细粒度指标不会进入每次会话上下文。
- LLM 必须先调用 `tool_index(topicTags=[...])`。该调用只返回该主题交集中的 `availableMetricTags`，不返回工具候选；主题未知或未传时不回退全量。
- LLM 第二次从 `availableMetricTags` 选择 `metricTags` 并调用 `tool_index(topicTags, metricTags, ...)`。这一步返回工具候选和 `availableDimensionTags`。
- LLM 需要继续收窄时，只能使用上一步返回的 `availableDimensionTags` 追加 `dimensionTags`。主题和指标均为 OR 语义，维度保持 AND 语义；`toolTypes` 仍为 IN 过滤。
- 每个可参与路由的工具必须至少配置一个 `topic_tags` 和一个 `metric_tags`，并且两者均引用已启用的共享词典项。

数据模型增量：`tool_route_metadata` 新增 `topic_tags TEXT NOT NULL DEFAULT '[]'` 和 `access_scope VARCHAR(16) NOT NULL DEFAULT 'GLOBAL'`；新增 `tool_route_skill_binding` 关系表；`tool_route_tag_dictionary.tag_type` 扩展为 `TOPIC | METRIC | DIMENSION`。调用范围和绑定关系迁移文件建议为 `V20260904.2__tool_route_access_scope_and_skill_binding.sql`，它必须与现有 Flyway 基线保持 openGauss 方言一致。

当前 `tool_index` 契约如下：

```java
ToolIndexResponse toolIndex(
        List<String> topicTags,
        List<String> metricTags,
        List<String> dimensionTags,
        List<String> toolTypes,
        Integer limit)
```

响应新增 `filters.topicTags`、`unknownTopicTags` 与 `availableMetricTags`。`toolMetaInfo(toolId)` 同时返回 `topicTags`，便于审计所选能力所属的业务主题。管理页提供主题词典、工具主题多选和主题列；全局 feature flag 与单条 `enabled` 的控制语义不变。

## 1. 背景与当前问题

当前存在三套相互独立的工具发现方式：

| 现有入口 | 管理对象 | 当前发现方式 | 主要问题 |
|---|---|---|---|
| `sql_list` | 预注册复杂 SQL | 返回全部启用 SQL 及参数 | 数百条记录一次进入上下文，LLM 需要在长列表中选择 |
| `toolMetaInfo` | `ToolRoutersIndex` 中的 API 工具 | 已知 `toolId` 后查询反射参数 | 只能查 API，不能发现工具；LLM 需要先从 Skill 或提示词猜中 ID |
| `script_list` | 预注册 Python 脚本 | 返回全部启用脚本及参数 | 数百条记录一次进入上下文，与 SQL/API 无法统一比较 |

工具数量继续增长后，主要风险包括：

1. 全量列表消耗大量输入 token，并在多轮 ReAct 中重复保留。
2. SQL、API、Python 脚本使用不同的发现协议，LLM 需要先判断去哪套索引查找。
3. 名称相似或能力重叠时，LLM 缺少统一的指标、维度和优先级依据。
4. 当前 `toolMetaInfo` 只支持 API，三类工具的查参行为不一致。
5. 路由信息散落在注册表名称、描述、Skill 正文和 Java 注解中，难以治理和评测。

## 2. 目标与非目标

### 2.1 目标

1. 所有可被 LLM 自主发现的数据查询/计算 SQL、API 和 Python 脚本都在 `tool_route_metadata` 中登记。
2. 初始 system prompt 只注入所有可执行工具的 `metric_tags` 去重集合，不注入工具详情。
3. LLM 自主从用户问题中识别指标和维度，再通过 `tool_index` 分阶段探索候选。
4. 服务端只对 LLM 传入的规范标签做确定性过滤，不接收用户原始问题，不替 LLM 做语义路由。
5. 将 `toolMetaInfo` 扩展为三类工具统一的查参门面。
6. 保留 `sql_registry_exec`、`script_exec`、`router_tool` 三个现有执行入口及其安全校验。
7. 通过有限结果集、缓存、一致性校验、指标和离线评测保证可运营性。
8. 旧 Skill 和旧调用链分阶段迁移，并按切换前、切换后定义不同回滚路径。
9. Skill 路由与 Tool 路由共享同一套规范指标标签，避免同一指标在两套元数据中漂移。
10. 支持 SQL、Python 脚本及其他原子工具配置为全局可用或仅允许指定 Skill 使用，并在发现、查参、执行三个阶段执行一致的服务端校验。

### 2.2 非目标

- 第一阶段不引入向量检索、Embedding、全文搜索或二次 LLM 分类器。
- 不在 `tool_route_metadata` 中增加别名、关键词、数据源、适用场景等未确认字段。
- 不把 SQL 模板、Python 源码或完整参数 schema 放进初始提示词或 `tool_index` 结果。
- 不将三种执行器合并为一个执行工具；统一的是发现和查参协议，不是执行实现。
- 不根据 `tool_type` 硬编码 `SCRIPT > SQL > API`。业务优先顺序由 `priority` 表达。
- 第一阶段不删除 `sql_list` 和 `script_list`，只从新版推荐路径中移除。
- 不将 Skill 作为第四种 `tool_type`。Skill 有独立的可使用范围、加载规则和多步工作流语义，不是原子执行器。
- 不使用领域、主题、指标或维度标签表达工具权限；这些标签只用于召回和候选收窄。
- `tool_index`、`toolMetaInfo`、`router_tool`、`sql_registry_exec`、`script_exec` 等元工具/执行入口，以及下载、文件、会话等基础设施工具，不作为待发现业务能力登记，不能为了通过审计而伪造指标标签。

## 3. 总体架构

```text
tool_route_metadata（统一路由目录）
  -> 按请求权限过滤 GLOBAL / SKILL_BOUND
  -> 去重 topic_tags、metric_tags
  -> ToolMetricCatalogMiddleware
  -> system prompt 仅包含当前调用范围内的“可查询业务主题目录”

用户问题
  -> 现有 Skill 路由筛选当前用户可使用的业务工作流
       -> 命中业务 Skill：加载工作流规则，在工作流内进入 Tool 层
       -> 无业务 Skill 命中：主管直接进入 Tool 层
  -> 服务端将选中的 Skill 写入当前 RuntimeContext（LLM 不可伪造）
  -> LLM 从指标目录识别规范指标标签
  -> 第一次 tool_index(metricTags)：只返回当前 Skill 有权使用的候选集及其可用维度
  -> 可选第二次 tool_index(metricTags, dimensionTags, toolTypes, limit) 收窄
  -> LLM 比较 description / dimensionTags / priority / toolType
  -> toolMetaInfo(toolId)
  -> 根据 executeWith 调用执行入口
       SQL    -> sql_registry_exec(sqlId, params)
       SCRIPT -> script_exec(scriptId, params)
       API    -> router_tool(paramsJson)
  -> 执行入口再次校验 ID、真实执行对象可用性、当前 Skill 调用范围和参数
```

职责边界如下：

| 组件 | 职责 | 不负责 |
|---|---|---|
| `tool_route_metadata` | 保存统一路由摘要、规范标签和调用范围 | 保存执行代码、SQL 模板或参数 schema |
| 指标目录 middleware | 向 system prompt 注入去重指标集合 | 为当前问题选择指标或工具 |
| `tool_index` | 按规范标签、当前 Skill 调用范围确定性过滤、排序和限量 | 理解原始问题、模糊召回或决定最终工具 |
| LLM | 理解用户问题、选择标签、比较候选、决定工具 | 绕过服务端执行校验 |
| `toolMetaInfo` | 按 ID 返回三类工具统一参数定义，并校验当前调用范围 | 执行工具或返回执行源码 |
| 三类执行入口 | 执行并进行最终安全校验，包括 Skill 绑定 | 路由候选发现 |

### 3.1 Skill 路由与 Tool 路由的边界

两套路由不是平行且互相竞争的目录，而是上下两层：

| 层级 | 路由对象 | 典型内容 | 进入下一层的方式 |
|---|---|---|---|
| Skill 路由 | 当前用户可使用的多步业务工作流 | 取数、计算、复核、输出格式、报告生成 | Skill 中使用固定 `toolId`，或按指标调用 `tool_index` 动态选原子工具 |
| Tool 路由 | SQL、API、Python 脚本原子能力 | 单次查询、单次计算脚本、单个数据接口 | `toolMetaInfo -> executeWith` |

主管 Agent 的固定决策顺序：

1. 优先使用现有 Skill 路由给出的、与问题明确匹配的业务工作流。
2. Skill 已给出固定 `toolId` 时，不必再次调用 `tool_index`；直接通过统一 `toolMetaInfo` 校验并执行。
3. Skill 需要在多个底层能力中动态选择时，在 Skill 工作流内部调用 `tool_index`。
4. 没有匹配的业务 Skill 时，主管才直接通过 `tool_index` 探索原子能力。
5. Tool 候选不能反向绕过 Skill 的用户可使用范围，也不能自动加载一个未被 Skill 路由选中的工作流。

现有 `*_tool_index` 一类只用于枚举 API ID 的包装 Skill 不属于业务工作流。统一 Tool 路由上线后，应将其中的工具描述迁入 `tool_route_metadata`，扫描其引用方并分批退役，避免 `<available_skills>` 与 `<tool_metric_catalog>` 同时表达同一批原子工具。真正包含业务规则、计算步骤或输出契约的 Skill 保留。

仓库当前还存在名称为 `tool_index` 的旧包装 Skill（`workspace/skills/tool_index/SKILL.md`）。它与新建的 `@Tool(name="tool_index")` 存在直接命名歧义，不能并行作为两个“工具索引”使用。启用新元工具前必须删除该旧 Skill，并将其中仍有价值的 API 描述迁入 `tool_route_metadata`。

删除范围包括工作区文件、Skill 运行时索引/管理记录、路由元数据及引用关系。删除前必须扫描并更新 `AGENTS.md`、`analyze_data.md`、`generate_skill.md`、其他 Skill 和测试中的引用。删除后，SQL/SCRIPT 可依赖保留的 `sql_list/script_list` 兼容链回滚；旧 API 发现能力只能通过回滚上一版工作区并恢复该 Skill 的数据库记录恢复，详见 §16。

验收必须确认：`<available_skills>` 中不再出现原子工具枚举包装 Skill；模型可见的 `tool_index` 只有新的元工具，不存在 Skill/Tool 同名竞争。

### 3.2 工具调用范围与 Skill 绑定（已废弃）

> 本节为历史方案，仅用于解释数据库迁移中的遗留字段和表；不得作为实现、接口或验收依据。新实现不做 Skill 绑定授权，详见文首“当前实现决策”。

领域、主题、指标和维度标签解决的是“工具是否适合这个问题”，不能解决“当前工作流是否有权调用这个工具”。因此工具权限单独建模，不将权限语义塞入领域标签或其他路由标签。

`tool_route_metadata.access_scope` 有两种值：

| 值 | 语义 |
|---|---|
| `GLOBAL` | 只要工具路由总开关、工具元数据和真实执行对象均可用，主 Agent 或任意可用 Skill 都可以发现和调用 |
| `SKILL_BOUND` | 只能由 `tool_route_skill_binding` 中存在有效绑定的当前 Skill 发现和调用 |

`SKILL_BOUND` 工具必须至少绑定一个有效 Skill，一个工具可以绑定多个 Skill。绑定关系只允许引用真实存在且可使用的 Skill；Skill 停用、删除或当前用户无权使用时，绑定不产生调用权限。

服务端在 Skill 路由确定并加载工作流后，将可信的 `activeSkillName` 写入当前请求的 `RuntimeContext`。这个值来源于服务端选中的 Skill 或受信任的子 Agent 工作流，不接受 `tool_index`、`toolMetaInfo`、`router_tool`、`sql_registry_exec` 或 `script_exec` 的 LLM 入参覆盖。无法取得当前 Skill 身份时，`SKILL_BOUND` 工具必须 fail-closed。

无业务 Skill 命中时，主管仍可直接进入 Tool 路由，但只能看到和调用 `GLOBAL` 工具；需要使用 `SKILL_BOUND` 工具时，必须先进入绑定的业务 Skill 工作流。直接知道 `toolId` 不能绕过调用范围校验。

两层关系如下：

```text
tool_route_metadata
  tool_id        tool_type        access_scope=GLOBAL | SKILL_BOUND

tool_route_skill_binding
  tool_id        skill_name       enabled

GLOBAL      -> 任意有权请求
SKILL_BOUND -> 当前 activeSkillName 命中有效绑定
```

### 3.3 统一校验位置

调用范围必须在三个位置复用同一个 `ToolAccessPolicy`（名称可按代码规范调整），不能只在 `tool_index` 过滤：

1. `tool_index`：候选目录只返回当前 Skill 有权使用的工具。
2. `toolMetaInfo`：即使 LLM 直接传入已知 `toolId`，也要按当前请求上下文重新校验，未授权时统一返回“工具不存在或不可用”。
3. `router_tool`、`sql_registry_exec`、`script_exec`：真正执行前再次校验，防止绕过索引或利用旧列表工具直接执行。校验失败不得触发 SQL 查询、脚本进程或 API 方法调用。

`sql_list`、`script_list` 兼容期也必须只返回当前调用范围允许的记录；不能因为它们是旧入口而暴露 `SKILL_BOUND` 工具。旧入口最终退役后，执行入口的最终校验仍需保留。

### 3.4 共享规范标签词典

Skill 路由的 `skill_routing_metadata.metric_tags` 与 Tool 路由的 `tool_route_metadata.metric_tags` 必须引用同一套规范指标标签。不能分别维护两份自由文本词表。

新增 `tool_route_tag_dictionary` 作为共享词典，供 Skill 和 Tool 管理写入时共同校验：

```sql
CREATE TABLE IF NOT EXISTS tool_route_tag_dictionary (
    tag_type       VARCHAR(16) NOT NULL,
    tag_name       VARCHAR(64) NOT NULL,
    description    VARCHAR(500) NOT NULL DEFAULT '',
    enabled        BOOLEAN NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMP NOT NULL DEFAULT now(),
    updated_at     TIMESTAMP NOT NULL DEFAULT now(),
    PRIMARY KEY (tag_type, tag_name),
    CONSTRAINT ck_tool_route_tag_dictionary_type
        CHECK (tag_type IN ('METRIC', 'DIMENSION'))
);
```

- system prompt 中的指标目录来自启用的 `METRIC` 词典项与当前可执行工具标签的交集。
- `tool_index` 返回的维度来自启用的 `DIMENSION` 词典项与当前候选工具标签的交集。
- Tool 元数据写入必须引用启用词典项；Skill 路由配置新增/更新时，其 `metric_tags` 也执行相同校验。
- 词典由平台管理员或指定指标 owner 审批；业务工具 owner 只能选择已有标签，新增标签需先进入词典审核。
- 导入和新增词典项时执行标准化后的重复检查，并用编辑距离生成近重复告警，例如“缺陷密度/缺陷率”；告警只提示人工裁定，不自动合并语义不同的标签。

## 4. 数据模型

### 4.1 `tool_route_metadata` 表

建议新增 openGauss 迁移，文件同时创建路由元数据表和 §3.2 的共享标签词典：

`src/main/resources/db/migration/gauss/V20260903.1__tool_route_metadata.sql`

```sql
CREATE TABLE IF NOT EXISTS tool_route_metadata (
    tool_id         VARCHAR(128) PRIMARY KEY,
    tool_type       VARCHAR(16) NOT NULL,
    description     VARCHAR(3000) NOT NULL DEFAULT '',
    access_scope    VARCHAR(16) NOT NULL DEFAULT 'GLOBAL',
    topic_tags      TEXT NOT NULL DEFAULT '[]',
    metric_tags     TEXT NOT NULL DEFAULT '[]',
    dimension_tags  TEXT NOT NULL DEFAULT '[]',
    priority        INT NOT NULL DEFAULT 0,
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT ck_tool_route_metadata_type
        CHECK (tool_type IN ('SQL', 'API', 'SCRIPT')),
    CONSTRAINT ck_tool_route_metadata_access_scope
        CHECK (access_scope IN ('GLOBAL', 'SKILL_BOUND'))
);

CREATE INDEX IF NOT EXISTS idx_tool_route_metadata_active_priority
    ON tool_route_metadata(enabled, priority DESC, tool_id);
```

字段约束：

| 字段 | 规则 |
|---|---|
| `tool_id` | 三类工具之间全局唯一；必须与真实执行 ID 完全一致 |
| `tool_type` | 只能是 `SQL`、`API`、`SCRIPT` |
| `description` | 说明用途、主要输出和关键差异，建议 30 至 200 个中文字符 |
| `access_scope` | `GLOBAL` 或 `SKILL_BOUND`；只表达调用范围，不表达领域或指标匹配 |
| `metric_tags` | JSON 字符串数组；至少一项；只放可查询/可计算的规范指标 |
| `dimension_tags` | JSON 字符串数组；可为空；例如部门、人员、季度、版本 |
| `priority` | 同类候选的人工排序依据；数值越大越靠前 |
| `enabled` | 只控制是否参与路由目录；执行对象自身仍有独立启用状态 |

`metric_tags` 与 `dimension_tags` 在写入时必须完成以下规范化：

- 去除首尾空白；
- 删除空标签；
- 数组内去重；
- 保留规范中文名称，不自动生成同义词；
- 限制单标签长度和单工具标签数量，建议分别不超过 64 字符、30 项；
- 非法 JSON 或非字符串数组拒绝写入，不能静默当成空数组。

### 4.2 `tool_route_skill_binding` 表

`SKILL_BOUND` 工具通过独立关系表绑定允许使用它的 Skill。该表不保存工具源码、参数或标签，只表达调用授权：

```sql
CREATE TABLE IF NOT EXISTS tool_route_skill_binding (
    tool_id       VARCHAR(128) NOT NULL,
    skill_name    VARCHAR(255) NOT NULL,
    enabled       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP NOT NULL DEFAULT now(),
    PRIMARY KEY (tool_id, skill_name)
);

CREATE INDEX IF NOT EXISTS idx_tool_route_skill_binding_skill
    ON tool_route_skill_binding(skill_name, enabled, tool_id);
```

`tool_id` 和 `skill_name` 使用逻辑引用，不建立跨来源物理外键：工具对象可能来自 SQL、SCRIPT 或 Java API 注册表，Skill 也可能来自 Skill 广场或内置 Skill。启动审计和管理保存校验必须检查两端真实存在；孤儿绑定不得产生权限。

字段规则：

| 字段 | 规则 |
|---|---|
| `tool_id` | 必须对应 `tool_route_metadata.tool_id`；工具改名时同事务迁移 |
| `skill_name` | 必须对应真实 Skill 的逻辑名称；Skill 改名时同事务迁移 |
| `enabled` | 只控制绑定是否生效；工具自身 `enabled=false` 或 Skill 不可用时仍不可调用 |

约束规则：

- `access_scope=GLOBAL` 的工具可以没有绑定；存在绑定时不改变其全局可用语义。
- `access_scope=SKILL_BOUND` 的工具至少需要一个 `enabled=true` 的绑定；缺少有效绑定时从目录排除并在审计中报警。
- 删除、停用或无权限使用 Skill 时，绑定记录可以保留，但运行时必须视为无效；恢复 Skill 后可重新生效。
- 管理页面切换 `SKILL_BOUND`、新增绑定和启用工具应采用保存校验，不能先让工具进入目录再补绑定。

### 4.3 ID 与执行对象映射

| `tool_type` | `tool_id` 对应对象 | 元信息来源 | `executeWith` |
|---|---|---|---|
| `SQL` | `sql_registry.sql_id` | `sql_registry.params_schema` | `sql_registry_exec` |
| `SCRIPT` | `script_registry.script_id` | `script_registry.params_schema` | `script_exec` |
| `API` | `ToolRoutersIndex` 中已登记的数据查询/计算 `@Tool` 名称 | Java 反射的 `MethodInfo` | `router_tool` |

`tool_id` 使用单列主键意味着三类工具不能重名。发布迁移前必须执行冲突审计；发现重名时由工具所有者改名或停用，路由层不能根据类型猜测。

### 4.4 路由目录与执行注册表的权威边界

- `tool_route_metadata` 是“是否可被 LLM 发现及如何选择”的唯一权威来源。
- `sql_registry`、`script_registry` 和 `ToolRoutersIndex` 是“对象是否真实存在及如何执行”的权威来源。
- 只有路由元数据启用且真实执行对象存在、可用的交集才可以进入指标目录和候选结果。
- 删除或禁用真实执行对象时，即使路由元数据仍启用，也必须从运行时目录中排除；路由元数据停用只影响目录发现。
- 禁用路由元数据不会删除或禁用真实执行对象，便于灰度和回滚。
- `tool_route_skill_binding` 是 `SKILL_BOUND` 的唯一授权来源；领域标签、主题标签、指标标签和维度标签不得作为授权兜底。
- 访问权限的最终判断必须同时满足：路由元数据启用、真实执行对象可用、当前用户可使用当前 Skill、以及 `access_scope` 对应的范围规则。

## 5. `tool_index` 工具协议

### 5.1 入参

新增 `ToolIndexTool`，对主 Agent 和需要数据查询能力的子 Agent注册：

```java
@Tool(name = "tool_index", description = "按规范指标和维度标签查询可用工具候选")
public ToolIndexResponse toolIndex(
        List<String> metricTags,
        List<String> dimensionTags,
        List<String> toolTypes,
        Integer limit)
```

JSON 调用示例：

```json
{
  "metricTags": ["达标率", "质量分"],
  "dimensionTags": ["部门", "季度"],
  "toolTypes": ["SCRIPT", "SQL", "API"],
  "limit": 10
}
```

参数规则：

1. `metricTags` 必填且不能为空，必须使用 system prompt 指标目录里的规范标签。
2. `dimensionTags` 可选；首次调用应省略，先让服务端返回当前指标候选实际支持的规范维度；LLM 需要收窄时再发起第二次调用。
3. `toolTypes` 可选；不传表示允许三种类型。
4. `limit` 默认 10，最小 1，最大 20。
5. 不提供 `query` 或用户原始问题参数，确保语义理解留在 LLM 侧。
6. 第一阶段不提供无条件全量浏览和分页，防止重新产生数百条上下文。
7. 不提供 `skillName`、`activeSkillName` 或权限范围参数；当前 Skill 身份由服务端从 `RuntimeContext` 获取，不能由 LLM 自行声明。

### 5.2 匹配语义

- 多个 `metricTags` 使用 OR：工具命中任意目标指标即可进入初选。
- 多个 `dimensionTags` 使用 AND：工具必须支持所有指定维度。
- `toolTypes` 使用 IN 过滤。
- 标签只做去首尾空白、忽略大小写后的精确匹配。
- 不做分词、包含匹配、拼音、同义词、关键词或向量召回。
- 结果按 `priority DESC, tool_id ASC` 稳定排序。
- 不按工具类型附加隐含分值。
- 在标签过滤前先执行调用范围过滤：`GLOBAL` 工具可进入任意有权请求；`SKILL_BOUND` 要求当前 `activeSkillName` 命中有效绑定。无当前 Skill 时排除 `SKILL_BOUND` 工具。

选择 OR 指标、AND 维度的原因是：用户可能一次查询多个指标，只覆盖其中一个指标的工具仍应被看到；但用户明确要求的部门、季度等维度必须全部得到支持。为避免 LLM 猜测“部门/开发部门”之类的维度名称，第一次索引调用只传指标，第二次调用才使用第一次响应中的规范维度标签。

### 5.3 返回结构

```json
{
  "filters": {
    "metricTags": ["达标率", "质量分"],
    "dimensionTags": ["部门", "季度"],
    "toolTypes": []
  },
  "matchedCount": 3,
  "truncated": false,
  "unknownMetricTags": [],
  "unknownDimensionTags": [],
  "availableDimensionTags": ["部门", "季度", "版本"],
  "candidates": [
    {
      "toolId": "q2_1_metrics_by_dept_quarter",
      "toolType": "SCRIPT",
      "accessScope": "SKILL_BOUND",
      "description": "按部门和季度计算质量分、打分率和达标率",
      "metricTags": ["质量分", "打分率", "达标率"],
      "dimensionTags": ["部门", "季度"],
      "priority": 100,
      "executeWith": "script_exec"
    }
  ]
}
```

返回规则：

- `matchedCount` 表示限量前的可执行候选总数。
- 超过 `limit` 时仅返回前 `limit` 条，并设置 `truncated=true`。
- `unknownMetricTags` 和 `unknownDimensionTags` 返回未出现在当前运行时目录中的输入标签。
- `availableDimensionTags` 从“已通过指标和类型过滤、尚未应用维度 AND 条件”的候选集合中去重产生，并按稳定字典序返回。它只包含共享词典中的启用维度标签。
- 未命中时返回空 `candidates`，不回退全量，不做模糊匹配。
- 候选不含参数 schema、SQL 模板、脚本路径、源码、数据源凭据等执行细节。
- `executeWith` 由服务端按 `toolType` 生成，LLM 不需要猜执行入口。

当 `truncated=true` 时，LLM 应从 `availableDimensionTags` 中选择与用户要求对应的规范维度，再增加维度或类型过滤，不提供翻页浏览。若未来离线评测证明 20 条仍不足，再单独设计游标分页，不能直接取消结果上限。

推荐的两阶段调用示例：

```text
第一次：tool_index(metricTags=["达标率"])
返回：候选 Top-N + availableDimensionTags=["部门", "季度", "版本"]

第二次：tool_index(
  metricTags=["达标率"],
  dimensionTags=["部门", "季度"]
)
返回：同时支持部门和季度的候选 Top-N
```

## 6. 统一 `toolMetaInfo`

### 6.1 目标

保留现有方法名和必填 `toolId` 参数，将当前只支持 API 的实现扩展为三类工具统一查参门面：

```text
toolMetaInfo(toolId)
  -> 查询 tool_route_metadata，确定 toolType
  -> 校验路由元数据可见性和真实执行对象可用性
  -> 从 RuntimeContext 读取 activeSkillName 并校验 accessScope/Skill 绑定
  -> API:    从 ToolRoutersIndex MethodInfo 生成参数信息
  -> SQL:    从 sql_registry.params_schema 生成参数信息
  -> SCRIPT: 从 script_registry.params_schema 生成参数信息
  -> 返回统一结构
```

### 6.2 统一返回结构

```json
{
  "toolId": "q2_1_metrics_by_dept_quarter",
  "toolType": "SCRIPT",
  "accessScope": "SKILL_BOUND",
  "description": "按部门和季度计算质量分、打分率和达标率",
  "metricTags": ["质量分", "打分率", "达标率"],
  "dimensionTags": ["部门", "季度"],
  "priority": 100,
  "executeWith": "script_exec",
  "parameters": [
    {
      "name": "department",
      "type": "string",
      "required": true,
      "description": "部门名称"
    },
    {
      "name": "quarter",
      "type": "string",
      "required": true,
      "description": "季度"
    }
  ],
  "invocation": {
    "idField": "scriptId",
    "paramsField": "params"
  }
}
```

三类 `invocation`：

| 类型 | 返回值 |
|---|---|
| `SQL` | `{"idField":"sqlId","paramsField":"params"}` |
| `SCRIPT` | `{"idField":"scriptId","paramsField":"params"}` |
| `API` | `{"idField":"toolId","paramsField":"paramsJson"}` |

实现建议新增 `UnifiedToolMetadataService`，由 `ToolRoutersIndex.toolMetaInfo` 委托该服务，而不是将 SQL/脚本 Mapper 逻辑继续堆入反射路由类。这样 `ToolRoutersIndex` 仍专注 API 注册与执行，统一服务负责目录类型分派和返回 DTO 组装。

这里必须避免 `ToolRoutersIndex -> UnifiedToolMetadataService -> ToolRoutersIndex` 的构造器循环依赖。具体采用以下边界：

- 新增只读接口 `ApiToolMetadataProvider`，提供 `findApiTool(toolId)` 和 `activeApiToolIds()`；
- `ToolRoutersIndex` 实现该接口，注册时同时保存 `@Tool.description`、参数描述、类型和必填信息；
- `UnifiedToolMetadataService` 通过 `ObjectProvider<ApiToolMetadataProvider>` 延迟获取 API 元数据提供者；
- `ToolRoutersIndex` 通过 `ObjectProvider<UnifiedToolMetadataService>` 延迟委托 `toolMetaInfo`；
- `toolMetaInfo` 调用发生在 Spring Bean 初始化完成之后，不在 `@PostConstruct` 阶段调用统一服务。

现有 `MethodInfo` 只保存 `Method` 和部分参数信息，实施时需要把 `@Tool.description` 与 `@ToolParam.description` 纳入只读 API 元数据 DTO，保证 API、SQL、SCRIPT 的 `parameters.description` 返回口径一致。

### 6.3 安全与错误语义

- 未登记、路由禁用、执行对象不存在或执行对象禁用，统一返回 `工具不存在或不可用`。
- 当前 Skill 无调用权限、`SKILL_BOUND` 工具没有有效绑定或无法取得可信 Skill 上下文时，也统一返回 `工具不存在或不可用`，不泄露绑定关系。
- 不向 LLM 区分“曾存在但禁用”和“从未存在”，避免泄露不可用工具目录。
- SQL 与脚本的 `params_schema` 非法时返回 `工具参数配置无效`，记录带 `toolId/toolType` 的错误日志。
- 不返回 SQL 模板、脚本路径、Python 源码、Bean 类名或 Java 方法名。
- 执行入口必须再次查询真实注册表、调用范围和参数，不能信任之前的 `toolMetaInfo` 结果。

## 7. 指标目录注入

### 7.1 动态 system prompt 块

新增 `ToolMetricCatalogMiddleware`，在 `onSystemPrompt` 中追加：

```text
<tool_metric_catalog>
可查询指标：质量分、打分率、达标率、缺陷密度

工具探索规则：
1. 从上述目录识别用户所需的规范指标标签。
2. 第一次调用 tool_index 时只传指标标签，读取候选及 availableDimensionTags。
3. 需要按维度收窄时，只能从 availableDimensionTags 选择规范维度发起第二次调用。
4. 根据候选的描述、维度、优先级和类型选择工具，不得编造 toolId。
5. 确定工具后调用 toolMetaInfo 获取参数定义。
6. 按 executeWith 调用对应执行工具。
7. 候选为空时检查规范标签或减少维度条件，不得请求全量工具列表。
8. 目录中的工具已经通过服务端调用范围过滤；不要通过传入 skillName、activeSkillName 或 accessScope 参数尝试扩大权限。
</tool_metric_catalog>
```

目录来源必须是“路由元数据启用、真实执行对象可用且当前请求有权调用”的交集，不能只查询 `tool_route_metadata.enabled=true`。无可信 `activeSkillName` 时，目录只包含 `GLOBAL` 工具；进入 Skill 工作流后，Skill 内部的目录查询可增加已绑定的 `SKILL_BOUND` 工具。标签去重后使用稳定的字典序排列，避免相同配置产生不同 prompt，利于 prompt cache。

目录渲染服务和 `tool_index` 必须接收同一个服务端 `ToolAccessContext`，至少包含 `userId`、`sessionId` 和可信的 `activeSkillName`；不能各自从 LLM 消息猜测当前 Skill。Skill 选择发生在目录初次渲染之后时，应在进入 Skill 工作流时重建或刷新一次受限目录，而不是把所有 `SKILL_BOUND` 工具提前放入公共 system prompt。请求结束后不得把 Skill 授权状态复用于其他请求。

### 7.2 上下文上限

即使只注入标签，指标数量长期也可能增长，因此增加以下保护：

- 去重标签数量指标与字符数指标必须可观测；
- 配置 `harness.a2a.tool-routing.metric-catalog-max-chars`，建议默认 8000；
- 管理写入/批量导入在提交前计算预计目录字符数；超出上限时拒绝使该标签或元数据进入启用状态，并返回明确校验错误；middleware 本身只读，不承担“阻止发布”职责；
- 若绕过管理服务的直接 SQL 导致运行时目录超限，目录快照构建失败，继续使用最后一份成功快照并报警；首次启动没有成功快照时整块注入“目录暂不可用”，绝不在标签中间截断；
- 数据库暂时不可用时保留基础 system prompt，注入“工具指标目录暂不可用，请勿猜测工具 ID”，不导致整个聊天接口启动失败。

middleware 注册到主 Agent 的有序 middleware 列表。`analyze_data` 等需要自主选工具的子 Agent 也必须注入同一目录，不能只依赖父 Agent 的 system prompt；可在 `SubagentRegistrar` 构建子 Agent 时复用同一个目录渲染服务。维度目录不进入初始 prompt，而由第一次 `tool_index` 的 `availableDimensionTags` 按指标候选动态返回。

指标目录 middleware、新 `tool_index` 注册和下述静态探索规则必须受同一个 `harness.a2a.tool-routing.enabled` 开关控制。开关关闭时，正式会话继续使用旧提示词和旧发现链，不能出现“提示词要求调用新工具，但 toolkit 尚未注册”的半切换状态。

### 7.3 静态提示词调整

修改工作区提示词：

- `workspace/AGENTS.md`：先保留“业务 Skill 优先”，将原子工具发现路径替换为 `tool_index -> toolMetaInfo -> executeWith`。
- `workspace/agent-subagents/analyze_data.md`：使用相同统一流程，并按两阶段方式发现维度。
- `workspace/skills/tool_index/SKILL.md`：在新元工具启用前删除，并清理运行时索引、管理记录、路由元数据和全部引用。
- 其他同类 `*_tool_index`：识别为待退役的原子工具枚举包装 Skill，按引用关系分批迁移。
- 其他旧 Skill 暂不批量强改，先通过兼容工具保证可运行，再按使用频率迁移。

动态指标不能写死在 `AGENTS.md`，否则数据库更新后必须重新部署工作区文件，且容易与运行时目录不一致。

## 8. 一致性校验与缓存

### 8.1 运行时可执行交集

新增 `ToolRoutingAvailabilityResolver`：

```text
SQL 元数据
  -> SqlRegistryMapper.selectBySqlId(toolId) != null
SCRIPT 元数据
  -> ScriptRegistryMapper.selectByScriptId(toolId) != null
  -> scriptPath 按 ScriptExecTool 相同规则解析
  -> workspace/scripts/scriptPath 是 regular file
API 元数据
  -> ToolRoutersIndex.getToolMethodMap().containsKey(toolId)
```

SCRIPT 路径校验必须复用执行器的约束：只允许既有合法路径格式、禁止 `..`、解析后必须位于 workspace `scripts` 目录，且 `Files.isRegularFile` 为真。容器模式下，宿主机绑定目录是当前执行器检查文件存在性的依据；如果部署模型不能保证宿主机与容器目录一致，必须增加容器内探针，否则该脚本不得进入可用目录。

`tool_index`、指标目录和 `toolMetaInfo` 必须复用同一 resolver，避免三处可见性口径不同。文件缺失的 SCRIPT 必须在索引前排除，而不是等 `script_exec` 返回 `SOURCE_NOT_FOUND`。

可用性 resolver 只判断真实执行对象是否存在且可用；`ToolAccessPolicy` 在此基础上按请求上下文过滤调用范围。两者必须保持职责分离：缓存的全局可执行快照不能包含某个用户或某个 Skill 的授权结果，防止把一个请求的 `SKILL_BOUND` 候选泄露给另一个请求。

### 8.2 启动审计

应用完成所有工具注册后执行一次审计，输出：

- 启用 SQL 中缺少路由元数据的 ID；
- 启用脚本中缺少路由元数据的 ID；
- 启用脚本但 `script_path` 非法、文件不存在或不是普通文件的 ID；
- 应被自主发现的数据查询/计算 API 中缺少路由元数据的 ID；元工具、执行入口和基础设施工具不计入覆盖率分母；
- 路由元数据指向不存在或禁用对象的孤儿 ID；
- `tool_type` 与真实对象类型不一致的 ID；
- 非法/空 `metric_tags`、非法 `dimension_tags` 的 ID。
- `access_scope` 非法、`SKILL_BOUND` 缺少有效绑定、绑定指向不存在 Skill/Tool 或绑定已失效的 ID。

配置：

```properties
harness.a2a.tool-routing.enabled=false
harness.a2a.tool-routing.strict-startup=false
harness.a2a.tool-routing.default-limit=10
harness.a2a.tool-routing.max-limit=20
harness.a2a.tool-routing.cache-ttl-ms=30000
harness.a2a.tool-routing.metric-catalog-max-chars=8000
```

- `strict-startup=false`：记录问题，坏记录不进入目录，用于迁移期。
- `strict-startup=true`：存在缺失、孤儿、类型错误或非法标签时阻止启动，用于完成迁移后的生产环境。
- `enabled=false`：保留旧路径，便于先部署表和数据再开启新路由。

### 8.3 缓存策略

缓存一个不可变的运行时目录快照，快照包含：

- 可执行的 `ToolRoutingMetadata` 列表；
- 去重指标标签集合；
- 去重维度标签集合；
- `toolId -> metadata` 映射；
- 快照版本或生成时间。

该快照只缓存“全局可执行能力”，不缓存 `ToolAccessContext` 过滤后的结果。每次 `tool_index`、`toolMetaInfo` 和执行入口调用时，都使用当前请求的 `userId/sessionId/activeSkillName` 重新执行 `ToolAccessPolicy`；如为性能需要，可在请求范围内缓存授权判断，但缓存键必须包含请求身份和 Skill 身份，并在请求结束丢弃。

使用 30 秒 TTL，管理端成功写入路由元数据或 SQL/脚本启用状态后主动失效。刷新失败时继续使用最后一份成功快照并报警；应用首次启动且没有成功快照时返回“目录暂不可用”，不能退回未经校验的全量记录。

主动失效是单 JVM 行为。多 JVM 共用同一个 GaussDB 时，写入实例可以立即清理本地缓存，其他实例最多等待 TTL 后刷新；这是已知的一致性窗口，不应被误判为路由数据损坏。若业务要求跨实例秒级生效，后续再引入 Redis 或数据库版本号通知，本期不增加外部依赖。

API 工具集合在应用生命周期内通常不变；若未来支持动态注册工具，需要在注册变更后触发同一缓存失效接口。

## 9. 管理与发布流程

### 9.1 第一阶段管理方式

第一阶段通过前端管理页面提供配置，同时保留受控批量导入能力，支持：

- 新增/更新路由元数据；
- 启用/停用路由元数据；
- 查询缺失与孤儿记录；
- 校验 JSON 标签和真实工具映射；
- 配置 `accessScope`：`GLOBAL`、`SKILL_BOUND`；
- 对 `SKILL_BOUND` 工具维护多个 Skill 绑定，并显示绑定失效原因；
- 保存前校验工具和 Skill 均真实存在、当前绑定有效；切换为 `SKILL_BOUND` 时没有有效绑定不得启用；
- 写入成功后失效缓存。

不能根据工具名称或 description 自动生成业务标签。数百条存量工具应由业务所有者确认指标和维度，可用批量 CSV/SQL 导入降低录入成本，但发布前必须执行校验。

共享标签词典的治理流程：

1. 平台管理员维护 `METRIC` 和 `DIMENSION` 规范标签。
2. Skill 与 Tool 管理界面或导入模板只能选择启用的规范标签，不能直接写入任意自由文本。
3. 新标签提交时先执行大小写、空白归一化后的精确重复检查。
4. 再执行近重复提示，例如归一化 Levenshtein 相似度达到配置阈值时列出已有标签；只告警，不自动合并。
5. 指标 owner 判断“同义词应合并”还是“业务含义不同应并存”，审批后才能启用。
6. 标签改名必须提供迁移事务，同时更新 `skill_routing_metadata` 与 `tool_route_metadata`，禁止只改一侧。

### 9.2 新工具发布流程

```text
1. 注册真实执行对象
   SQL -> sql_registry
   SCRIPT -> script_registry
   API -> @Tool + ToolRoutersIndex 注册
2. 写入 tool_route_metadata，初始 enabled=false
3. 运行单工具参数 schema 与映射校验
4. 加入离线路由样例并验证
5. 设置 tool_route_metadata.enabled=true
6. 失效缓存并观察路由指标
```

SQL 和脚本管理服务后续应将路由字段作为同一管理事务的一部分：执行对象创建成功但路由元数据未完成时保持路由禁用。API 工具通常随版本发布，其路由元数据使用数据库迁移脚本或管理接口预置。

### 9.3 存量数据迁移

1. 导出现有启用 SQL、脚本和 API ID。
2. 运行全局 ID 冲突检测。
3. 生成待填写模板：`tool_id/tool_type/description/access_scope/topic_tags/metric_tags/dimension_tags/priority/enabled`；`SKILL_BOUND` 另生成 `tool_id/skill_name/enabled` 绑定模板。
4. 业务所有者补齐并复核调用范围、绑定 Skill、主题、指标、维度和优先级。
5. 首次批量导入时全部设为 `enabled=false`。
6. 运行完整性、一致性和离线路由评测。
7. 按业务域或工具类型分批启用，不能一次性无验证开启数百条。
8. 覆盖率达到 100% 后开启 `strict-startup=true`。

冲突检测示例：

```sql
SELECT tool_id, COUNT(*)
FROM (
    SELECT sql_id AS tool_id FROM sql_registry WHERE enabled = 1
    UNION ALL
    SELECT script_id AS tool_id FROM script_registry WHERE enabled = 1
) t
GROUP BY tool_id
HAVING COUNT(*) > 1;
```

API ID 由启动审计与上述数据库结果合并检测，因为 API 注册集合来自 Java 反射而非数据库表。

批量导入顺序必须是：先导入工具元数据和 Skill 绑定（均禁用），再执行孤儿绑定、范围和可用性校验，最后按批次启用工具。`SKILL_BOUND` 工具没有有效绑定时禁止启用；导入程序不得把空绑定自动解释为全局可用。

## 10. 旧工具兼容与退役

### 10.1 兼容期

- 保留 `sql_list()`、`script_list()` 的方法和无参行为，避免旧 Skill 立即失效。
- 新版 `AGENTS.md` 和 `analyze_data.md` 不再推荐使用它们。
- 新元工具启用前删除现有 `workspace/skills/tool_index/SKILL.md` 及其数据库记录和引用；旧包装 Skill 不能与新 `tool_index` 同名并存。
- 新路由开启后，从主 Agent 和新版子 Agent 的 toolkit 移除两个列表工具，旧专用 Agent 如确有依赖可暂时保留。
- `toolMetaInfo(toolId)` 方法名与参数签名保持不变，API 老调用可继续工作。
- 新流程固定使用 `tool_index -> toolMetaInfo -> executeWith`。

### 10.2 退役条件

满足以下条件后才删除 `sql_list/script_list`：

1. 路由元数据覆盖率达到 100%。
2. 所有启用 Skill 的正文不再引用旧列表工具。
3. Trace 中连续一个发布周期无旧列表工具调用。
4. 新路由离线评测和线上成功率达到验收门槛。
5. 已保留可执行的数据库与配置回滚方案。

退役时同步清理所有配置引用，包括：

- `harness.a2a.tool-truncation.tools` 中的 `sql_list`；
- `harness.a2a.context-budget.compactable-tools` 中的 `sql_list`；
- 主 Agent、子 Agent 的工具声明和提示词；
- 旧 Skill 文档、示例、Golden Dataset 与 Trace 断言中的 `sql_list/script_list`；
- 旧包装 `tool_index` Skill 及其数据库路由元数据。

兼容期内上述截断配置继续保留，因为旧 Skill 仍可能调用 `sql_list`；只有从 toolkit 移除并确认无调用后才清理。

## 11. 错误处理与降级

| 场景 | 行为 |
|---|---|
| LLM 传入未知指标标签 | 返回空候选和 `unknownMetricTags`，不模糊匹配 |
| LLM 传入未知维度标签 | 返回空候选和 `unknownDimensionTags`；要求重新读取上一次 `availableDimensionTags` |
| 维度条件过严 | 返回空候选；提示 LLM减少维度条件后重试 |
| 候选超过上限 | 返回 Top-N 与 `truncated=true`，提示增加过滤条件 |
| 路由元数据 JSON 非法 | 该记录不进入快照；记录错误；严格模式阻止启动 |
| 真实执行对象已禁用/删除 | 从目录和查参结果排除；执行器仍做最终拒绝 |
| `toolMetaInfo` 查不到 ID | 统一返回工具不存在或不可用 |
| 数据库刷新暂时失败 | 使用最后成功快照并报警 |
| 首次启动无可用快照 | 指标目录标记暂不可用，禁止猜测 ID，不回退全量 |
| 新路由整体异常 | 关闭 feature flag，恢复旧列表工具注册与旧提示词 |

迁移期对“空候选 + unknownTags”与“空候选 + 已知标签”分别统计和人工抽样；不自动把空候选转换为全量列表。现有 Skill 路由实际采用的是低置信度扩容到受门控候选集，而非无条件恢复全部 Skill，因此 Tool 路由保持“限量、可观测、不全量回退”并不与已验证策略冲突。

## 12. 可观测性

建议增加以下日志字段：

- `metricTags`、`dimensionTags`、`toolTypes`；
- `matchedCount`、`returnedCount`、`truncated`；
- 最终选中的 `toolId/toolType/executeWith`；
- `toolMetaInfo` 命中或失败原因分类；
- 目录快照版本、记录数、指标标签数、刷新耗时；
- 缺失、孤儿、类型错误和非法标签计数。
- 空候选次数，按 `unknownMetricTags`、`unknownDimensionTags`、`knownTagsNoMatch` 分类；
- SCRIPT 文件缺失或路径非法计数；
- 多 JVM 快照年龄与刷新实例标识。

Micrometer 指标建议：

```text
tool_routing_index_requests_total{result=hit|empty|invalid}
tool_routing_index_candidates_returned
tool_routing_meta_info_requests_total{type=SQL|API|SCRIPT,result=hit|miss|invalid}
tool_routing_catalog_entries
tool_routing_catalog_metric_tags
tool_routing_catalog_refresh_total{result=success|failure}
tool_routing_catalog_refresh_duration
tool_routing_consistency_errors{kind=missing|orphan|type_mismatch|invalid_tags}
tool_routing_empty_results_total{reason=unknown_metric|unknown_dimension|known_tags_no_match}
tool_routing_script_unavailable_total{reason=missing_file|invalid_path}
```

Trace 中应能串联：用户问题、`tool_index` 入参/候选、`toolMetaInfo`、实际执行工具和最终结果，但不得把 SQL 模板或脚本源码写入普通业务日志。

## 13. 测试与验收

### 13.1 单元测试

`ToolRoutingMetadataRepositoryTest`：

- 正确解析 JSON 标签；
- 非法 JSON 不被静默接受；
- 缓存 TTL 与主动失效生效；
- 只加载启用记录并保持稳定排序。

`ToolAccessPolicyTest`：

- `GLOBAL` 在无当前 Skill 时可用；
- `SKILL_BOUND` 仅在当前 Skill 存在有效绑定时可用；
- 多个绑定采用 OR；禁用绑定、孤儿绑定和其他 Skill 均拒绝；
- LLM 传入的 `skillName/activeSkillName` 不能覆盖服务端上下文；
- 权限拒绝发生在 SQL、脚本进程或 API 方法执行之前。

`ToolRoutingTagDictionaryTest`：

- Skill 与 Tool 写入只能引用启用的规范标签；
- 指标和维度标签类型不能混用；
- 精确重复被拒绝，近重复只产生审核告警；
- 标签改名同时更新 Skill 与 Tool 元数据；
- system prompt 指标目录和 `availableDimensionTags` 只包含词典与可执行工具的交集。

`ToolIndexServiceTest`：

- 多指标按 OR 匹配；
- 多维度按 AND 匹配；
- 类型过滤正确；
- 优先级降序、ID 升序；
- 默认/最大 limit 生效；
- 未知标签返回空候选，不回退全量；
- 不可执行和禁用对象被排除。
- 首次仅按指标查询时返回规范 `availableDimensionTags`；
- 第二次维度 AND 过滤只接受共享词典中的规范维度标签。
- 按 `access_scope` 和当前 `ToolAccessContext` 过滤；无 Skill 上下文时只返回 `GLOBAL`。

`UnifiedToolMetadataServiceTest`：

- API 参数来自反射信息；
- SQL 参数来自 `sql_registry.params_schema`；
- SCRIPT 参数来自 `script_registry.params_schema`；
- 三类返回结构一致且 `invocation` 正确；
- 不存在、禁用、类型不符和 schema 非法均被拒绝；
- 返回值不包含 SQL 模板、脚本路径或源码。

`ToolMetricCatalogMiddlewareTest`：

- 只注入去重指标标签，不注入工具描述与参数；
- 输出顺序稳定；
- 超字符上限不生成残缺目录；
- 仓库异常时返回可理解的降级提示。

`ToolRoutingAvailabilityResolverTest`：

- SCRIPT 数据库记录存在但文件缺失时不可见；
- 非法路径和目录逃逸不可见；
- 普通文件存在时可见；
- SQL/API 的存在性和启用状态按各自真实注册源判断。

### 13.2 集成测试

1. 迁移脚本可在空库创建表、约束和索引。
2. 准备 SQL/API/SCRIPT 各一个同指标候选，`tool_index` 能统一返回并正确标注执行入口。
3. `toolMetaInfo` 对三类工具均返回可直接构造调用的参数信息。
4. 路由元数据存在但执行对象不存在时，指标目录、索引和查参都不可见。
5. SQL/脚本管理操作后缓存主动失效。
6. `GLOBAL`、`SKILL_BOUND` 两种调用范围在 `tool_index`、`toolMetaInfo` 和真实执行入口保持一致。
7. `SKILL_BOUND` 工具只能在绑定 Skill 工作流中执行，直接已知 `toolId` 和旧列表工具均不能绕过。
8. 主 Agent 和 `analyze_data` 子 Agent 都拥有 `tool_index` 与统一 `toolMetaInfo`。
9. `/ai/chat` 与 `/v2/ai/chat` 各验证一条完整调用链。
10. 原有 `sql_registry_exec`、`script_exec`、`router_tool` 安全和结果展示回归测试全部通过。
11. 有匹配业务 Skill 时先进入 Skill 工作流，再在需要时进入 Tool 路由；无匹配 Skill 时主管可直接进入 Tool 路由。
12. 旧 `tool_index` 包装 Skill 删除后，模型目录中不存在 Skill/Tool 同名索引。
13. 两个 JVM 共库时，写入 JVM 立即刷新，另一 JVM 在 TTL 内保持旧快照并在到期后刷新。

### 13.3 离线路由评测

建立包含真实业务问法的评测集，每条至少包含：

```text
question
expected_metric_tags
expected_dimension_tags
acceptable_tool_ids
preferred_tool_id（可选）
```

LLM 负责从问题产生标签和选择候选，因此评测拆为两层：

1. 标签识别：规范指标与维度是否选对。
2. 工具选择：期望工具是否进入 Top-K，LLM 是否最终选中可接受工具。

建议上线门槛：

- 指标标签 Exact/Acceptable Match >= 95%；
- 期望工具 `Recall@10 >= 99%`；
- 最终工具选择准确率 >= 95%；
- 非法/编造 `toolId` 比例 < 0.5%；
- 全流程平均工具发现结果字符数相比当前全量列表下降至少 80%；
- SQL、API、SCRIPT 三类分别统计，不能只看总体平均值。

### 13.4 上线验收

1. 所有应被自主发现的数据查询/计算工具均有合法路由元数据，覆盖率 100%；元工具、执行入口和基础设施工具不计入分母。
2. 初始 system prompt 只出现去重指标目录，不出现数百条工具描述或参数 schema。
3. `tool_index` 单次最多返回 20 条且结果稳定。
4. `toolMetaInfo` 能统一查询三类工具，不泄露执行源码。
5. 真实执行入口仍执行原有白名单、真实执行对象启用状态和参数校验；路由元数据 `enabled` 不作为执行拦截条件。
6. 删除旧 `tool_index` Skill 之前，关闭 feature flag 可恢复旧路由；删除之后按 §16 的版本级回滚恢复旧 API 发现能力。
7. ECharts/HTML、artifact handoff 和 `script_output` SSE 行为不受影响。
8. Skill 和 Tool 元数据引用同一规范指标词典，原子工具包装 Skill 不再出现在 Skill 候选中。
9. SCRIPT 文件缺失时不会出现在指标目录、`tool_index` 或 `toolMetaInfo` 中。
10. 空候选按原因有独立指标和 Trace 字段，迁移期可人工抽样。

## 14. 分阶段实施计划

### 阶段 0：盘点与基线

1. 导出三类启用工具 ID、描述和参数定义。
2. 检测全局 ID 冲突、重复能力和失效对象。
3. 盘点真正的业务工作流 Skill 与仅枚举原子工具的 `*_tool_index` 包装 Skill。
4. 建立并审批第一版共享指标/维度规范词典，映射现有 Skill 与 Tool 标签。
5. 记录当前全量 `sql_list/script_list` 的字符数、token 估算和选择成功率。
6. 建立首批离线路由评测集。

交付物：存量工具清单、冲突报告、路由基线报告。

### 阶段 1：数据库与仓库层

1. 新增 `tool_route_metadata` 与共享标签词典 Flyway 迁移。
2. 新增 `access_scope` 字段和 `tool_route_skill_binding` Flyway 迁移，默认存量工具为 `GLOBAL`。
3. 新增实体、Repository、词典引用校验、Skill 绑定校验和不可变缓存快照。
4. 新增 `ToolAccessPolicy`，统一判断 GLOBAL/绑定 Skill 的调用范围。
5. 新增运行时可用性 resolver，其中 SCRIPT 必须检查真实文件。
6. 新增一致性审计器和标签近重复审核提示。
7. 编写 SQL/API/SCRIPT 各类测试夹具和单元测试。

交付物：关闭 feature flag 时不影响现网的目录基础设施。

### 阶段 2：统一发现与查参

1. 新增 `ToolIndexTool` 与结构化 DTO。
2. 新增 `UnifiedToolMetadataService`。
3. 将 `ToolRoutersIndex.toolMetaInfo` 委托给统一服务，保持原签名，并接入 `ToolAccessPolicy`。
4. 在 `V2ToolConfig` 和 `SubagentRegistrar` 注册 `tool_index`。
5. 在 `router_tool`、`sql_registry_exec`、`script_exec` 及兼容期 `sql_list/script_list` 接入最终调用范围校验。
6. 完成三类端到端查参与执行回归，验证未授权调用不会触发真实执行。

交付物：可独立调用的新协议，旧提示词仍可运行。

### 阶段 3：指标目录、提示词与影子验证

1. 新增 `ToolMetricCatalogMiddleware` 和目录渲染服务，指标来自共享词典交集。
2. 向主 Agent 和数据分析子 Agent 注入同一目录。
3. 将可信 `activeSkillName` 写入请求级 `RuntimeContext`，由目录、查参和执行层共同读取；不得由 LLM 入参提供。
4. 准备 `AGENTS.md`、`analyze_data.md` 的 Skill 优先、Tool 兜底及两阶段维度探索规则，并确保正式会话仅在 feature flag 开启时采用这些新规则。
5. 保持 `harness.a2a.tool-routing.enabled=false`，通过管理接口或测试入口验证目录渲染，不向正式会话暴露新 `tool_index`。

交付物：新目录和提示词具备切换条件，但正式会话仍走旧链路。

### 阶段 4：存量元数据迁移与切换

1. 批量导入经业务确认的元数据和 `SKILL_BOUND` 绑定，初始全部路由禁用；未明确限制的存量工具统一设为 `GLOBAL`。
2. 先在测试环境验证绑定 Skill 的目录、查参和执行闭环，确认无当前 Skill 或其他 Skill 均不可见、不可执行。
3. 按业务域分批启用并执行离线评测。
4. 比较新旧路由成功率、延迟、上下文字符数和重试次数，并完成 API 描述从旧包装 Skill 到元数据表的迁移验收。
5. 覆盖率达到 100% 且达到 §13.3 门槛后，在同一发布制品和维护窗口中删除 `workspace/skills/tool_index/SKILL.md` 及其数据库记录、更新 `generate_skill.md` 等全部引用，并开启 `harness.a2a.tool-routing.enabled=true`，使新提示词、新 middleware 和新元工具一起生效；禁止新旧同名索引同时对模型可见。
6. 开启严格启动检查，并先在测试环境、再在小范围生产流量验证完整调用链。

交付物：新会话默认使用 `tool_index -> toolMetaInfo -> executeWith`，并形成灰度报告。

### 阶段 5：旧路径退役

1. 扫描并迁移仍引用 `sql_list/script_list` 的 Skill。
2. 从主 Agent 和子 Agent toolkit 中移除旧列表工具。
3. 同步清理 tool truncation、context budget、提示词、测试和文档中的旧工具引用。
4. 观察一个发布周期。
5. 满足退役条件后删除旧实现。

交付物：只保留统一发现、统一查参和三类执行器的稳定架构。

## 15. 预计代码改动位置

| 类型 | 路径 | 责任 |
|---|---|---|
| 新增 | `src/main/resources/db/migration/gauss/V20260903.1__tool_route_metadata.sql` | 路由元数据表、共享标签词典、约束与索引 |
| 新增 | `src/main/resources/db/migration/gauss/V20260904.2__tool_route_access_scope_and_skill_binding.sql` | `access_scope` 字段、调用范围约束和 Skill-Tool 绑定表 |
| 新增 | `src/main/java/com/agentscopea2a/v2/toolrouting/ToolRoutingMetadata.java` | 路由元数据模型，与现有 `SkillRoutingMetadata` 命名一致 |
| 新增 | `.../toolrouting/ToolRoutingMetadataRepository.java` | 数据访问、校验和缓存 |
| 新增 | `.../toolrouting/ToolRoutingCatalog.java` | 不可变运行时目录快照 |
| 新增 | `.../toolrouting/ToolRoutingTagDictionary.java` | Skill/Tool 共享规范标签词典 |
| 新增 | `.../toolrouting/ToolRoutingAvailabilityResolver.java` | 三类真实对象可用性校验，包含 SCRIPT 文件检查 |
| 新增 | `.../toolrouting/ToolAccessPolicy.java` | 基于请求级可信 Skill 上下文判断 GLOBAL/SKILL_BOUND 权限 |
| 新增 | `.../toolrouting/ToolRouteSkillBindingRepository.java` | 查询和维护 Skill-Tool 绑定关系 |
| 新增 | `.../toolrouting/ToolIndexService.java` | 确定性标签过滤与排序 |
| 新增 | `.../toolrouting/UnifiedToolMetadataService.java` | 三类统一查参门面 |
| 新增 | `.../toolrouting/ApiToolMetadataProvider.java` | 解耦统一查参与 API 反射索引 |
| 新增 | `.../tools/ToolIndexTool.java` | LLM 可调用的索引工具 |
| 新增 | `.../middleware/ToolMetricCatalogMiddleware.java` | system prompt 指标目录 |
| 修改 | `src/main/java/com/agentscopea2a/v2/tools/ToolRoutersIndex.java` | `toolMetaInfo` 委托统一服务 |
| 修改 | `src/main/java/com/agentscopea2a/v2/config/V2ToolConfig.java` | 注册 Repository、服务和工具 |
| 修改 | `src/main/java/com/agentscopea2a/v2/config/HarnessAgentPartsConfig.java` | 注册指标目录 middleware |
| 修改 | `src/main/java/com/agentscopea2a/v2/runner/SubagentRegistrar.java` | 子 Agent 注册索引工具与指标目录 |
| 修改 | `src/main/java/com/agentscopea2a/v2/tools/SqlRegistryExecTool.java` | SQL 执行前调用范围最终校验 |
| 修改 | `src/main/java/com/agentscopea2a/v2/tools/ScriptExecTool.java` | SCRIPT 执行前调用范围最终校验 |
| 修改 | `src/main/resources/workspace/AGENTS.md` | 主 Agent 统一探索规则 |
| 修改 | `src/main/resources/workspace/agent-subagents/analyze_data.md` | 子 Agent 统一探索规则 |
| 修改 | `src/main/resources/workspace/agent-subagents/generate_skill.md` | 移除旧包装 Skill 引用，改用新元工具协议 |
| 删除 | `src/main/resources/workspace/skills/tool_index/SKILL.md` | 删除与新 `tool_index` 元工具同名的旧 API 枚举包装 Skill |
| 修改 | `src/main/resources/application.properties` | feature flag、limit、TTL、严格模式 |
| 新增 | `src/test/java/com/agentscopea2a/v2/toolrouting/**` | 路由、查参、缓存和 middleware 测试 |
| 修改 | `frontend/src/pages/ToolRoutingPage.vue` | 配置调用范围和多个允许使用的 Skill |

具体实现必须按测试驱动顺序进行：先为每项行为编写失败测试并确认失败原因，再实现最小代码使其通过，最后运行相关模块和全量回归。

## 16. 发布与回滚

发布顺序必须是：

```text
先部署表和代码（routing.enabled=false）
  -> 导入并校验元数据
  -> 测试环境启用
  -> 小范围生产灰度
  -> 全量启用
  -> 最后迁移和退役旧列表工具
```

回滚按发布阶段分三级：

1. 切换前/兼容期路由回滚：设置 `harness.a2a.tool-routing.enabled=false`，恢复旧提示词以及 `sql_list/script_list` 注册，不删除元数据；此时旧 `tool_index` Skill 尚未删除才可完整恢复旧 API 发现链。
2. 删除旧 `tool_index` Skill 后的版本级回滚：回滚到上一版应用和工作区资源，并恢复该 Skill 对应的数据库运行时索引/管理记录。只关闭 feature flag 不能恢复已经删除的 API 索引内容。
3. 单工具回滚：设置对应 `tool_route_metadata.enabled=false`，该工具在各 JVM 缓存刷新后从目录移除，但真实执行对象和 Skill 中的固定 `toolId` 调用保留。

数据库迁移只新增路由字段、绑定表和约束，不修改 `sql_registry`、`script_registry` 的执行字段，因此关闭新功能后 SQL/SCRIPT 原执行链可继续工作。关闭新路由不应自动把 `SKILL_BOUND` 工具变成全局工具；若需要恢复旧链路，必须明确恢复旧工具暴露范围，并继续在执行入口保留绑定校验。API 的旧发现链依赖将被删除的包装 Skill，必须通过版本级回滚恢复。不要在统一路由稳定前删除 `sql_list/script_list` 或执行注册数据。

## 17. 最终决策摘要

1. 使用一个 `tool_route_metadata` 统一登记 SQL、API、Python 脚本的路由信息。
2. Skill 路由选择当前用户可使用的多步业务工作流；Tool 路由只选择 SQL、API、SCRIPT 原子能力。无匹配业务 Skill 时，主管才直接进入 Tool 路由。
3. Skill 与 Tool 路由共享规范指标词典；初始 system prompt 只注入可执行工具指标标签的去重集合。
4. `tool_index` 第一次按指标返回候选和 `availableDimensionTags`，第二次可用规范维度做 AND 收窄；单次限制 Top-10/Top-20。
5. `toolMetaInfo` 统一查询三类工具参数，但不把 SQL 和脚本塞入 API 反射注册表。
6. 三种执行入口继续独立，并保留最终白名单、状态和参数校验；SCRIPT 在候选阶段额外校验真实文件存在。
7. 现有 `workspace/skills/tool_index/SKILL.md` 在新元工具启用前删除，其他原子工具包装 Skill 分批退役。
8. `sql_list/script_list` 仅用于兼容，完成迁移和观察后再退役，并同步清理上下文压缩配置。
9. 空候选不回退全量，但按未知指标、未知维度、已知标签无匹配分别观测和抽样。
10. 通过 feature flag、缓存快照、一致性审计、离线评测和分批启用控制上线风险；多 JVM 接受最长一个 TTL 的一致性窗口。
11. 工具标签只负责召回；`GLOBAL/SKILL_BOUND` 调用范围和 `tool_route_skill_binding` 独立表达权限，并在 `tool_index`、`toolMetaInfo`、`router_tool`、`sql_registry_exec`、`script_exec` 及兼容列表入口统一校验。
