# Agent 对话上下文压缩与预算治理实施方案

> 文档目的：为内网业务场景控制一次请求发送给 LLM 的上下文规模，在估算上下文接近有效输入预算前启动压缩，避免一次用户提问因多轮工具调用、Skill、工具定义和系统提示词持续膨胀。
>
> 适用接口：`/ai/chat`、`/v2/ai/chat`。两者都复用 `HarnessA2aRunnerV2` 创建 Agent，因此核心上下文治理应放在 Runner/Agent middleware 层，而不是前端或某一个 Skill 中。

## 1. 目标与边界

### 1.1 目标

1. 在每次模型调用前保守估算实际输入规模，并在达到有效输入预算的 `warn-ratio` 时按优先级压缩，达到 `hard-ratio` 时执行硬保护；预算由模型窗口、输出预留和输入上限共同决定，不硬编码为固定 40K/50K。
2. 大工具结果不进入后续每一轮模型上下文；完整结果保存在 artifact 或工具执行侧，需要时通过引用读取。
3. Skill 只向模型提供索引和摘要，任何情况下都不向模型加载或返回完整 `SKILL.md`。
4. 保留当前 ECharts/HTML 的前端展示行为：脚本结果的可渲染代码块继续通过 `script_output` 事件发送，不能因上下文压缩而丢失前端展示。
5. 提供日志、指标和拒绝/降级信息，使内网部署可以判断究竟是哪一类内容导致超限。

### 1.2 非目标

- 不修改前端协议和页面渲染逻辑。
- 不让 LLM 参与压缩决策；压缩由后端按确定性规则完成。
- 不删除数据库中的会话、Trace 或 artifact 原始数据。
- 不向 system prompt 注入任何历史案例；当前会话的必要消息仍按消息级 compaction 处理。
- 不依赖在线 tokenizer 服务。内网必须离线可运行。

## 2. 当前实现与问题定位

### 2.1 已有能力

`analysis-project/src/main/java/com/agentscopea2a/v2/runner/HarnessA2aRunnerV2.java` 当前配置了 AgentScope 的 `CompactionConfig`：

```java
.compaction(CompactionConfig.builder()
        .triggerMessages(20)
        .keepMessages(8)
        .build())
```

它按消息数量触发，而不是按 token 总量触发。触发后由框架摘要较早消息，只保留最近消息。

项目还注册了：

`analysis-project/src/main/java/com/agentscopea2a/v2/middleware/ToolResultTruncationMiddleware.java`

当前默认压缩 `load_skill_through_path`、`sql_list` 等配置的工具结果。它只压缩已经消费过的结果，最新的 `ToolResultBlock` 会完整保留，避免当前推理拿不到刚执行的数据。

### 2.2 上下文膨胀的主要来源

一次“单轮”用户请求可能内部执行多轮 ReAct，因此上下文包含：

| 来源 | 当前风险 | 是否已有控制 |
|---|---|---|
| 系统提示词、固定规则 | 每次模型调用都占用 | 无总预算控制 |
| 固定工具 schema | 工具数量多时持续占用 | 仅通过禁用部分工具缓解 |
| 30 多个 Skill 描述/正文 | 目录描述多，完整正文会进一步膨胀 | 当前全量暴露目录 |
| `sql_registry_exec`、`script_exec` 等结果 | JSON、表格、日志可能很大 | 不一定在截断工具列表中 |
| 最新工具结果 | 为保证当前推理完整，当前轮不会压缩 | 没有单结果硬上限 |
| 多轮 tool call 历史 | 消息数量和字符数持续增长 | 只有 20 条消息触发的 compaction |

因此当前系统不是“没有压缩”，而是“有局部压缩，没有总 token 预算保护”。尤其当最新一次工具结果本身很大时，即使 Compaction 已触发，本次请求仍可能超过模型上下文窗口。

### 2.3 上线前阻断差异

以下差异已经从当前代码核实，属于实施计划的一部分，不能把已有基础类或已打开配置开关视为完成：

| 级别 | 当前实现 | 上线要求 |
|---|---|---|
| P0 | `ContextSizeEstimator` 与 middleware 局部估算统一使用 `chars / 4` | 按 Unicode code point 区分 ASCII、CJK 和其他 Unicode，并覆盖混合文本测试 |
| P1 | `reserve-output-tokens` 已配置但未参与 middleware 阈值计算 | 所有 warn/hard 判断统一基于 `effectiveInputBudget` |
| P1 | Skill Top-K 已可开启，但 filter 层不直接执行 `SkillUsageResolver` 兜底 | filter 自身求交并在身份或 Resolver 失败时 fail-closed，完成前关闭严格路由 |
| P2 | 最新 artifact-tool 结果较小时会提前返回，更早的大结果可能未处理 | 扫描全部历史 artifact-tool 结果，逐个压缩或 handoff |

## 3. 目标架构：先按可使用范围隔离，再做数据库驱动的 Capability Routing

现有 `SkillCandidateSelector` 是单层候选筛选器。本方案调整为数据库驱动的分层能力路由，能力分类不写入 Java 常量、`application.properties` 或 Skill 正文。

```text
用户问题 + 当前登录用户
  -> Skill Usage Resolver（可使用范围硬门槛）
  -> 当前用户可使用的 Skill 集合
  -> 领域标签硬隔离（一级，不改变现有关键词门控逻辑）
  -> 业务主题筛选（二级）
  -> 指标标签筛选（三级）
  -> Capability Discovery（能力发现，仅生成排序加分信号）
  -> Skill 确定性评分、稳定排序与显式名称优先
  -> 仅暴露当前用户可见 Skill 的短摘要
  -> Agent / Subagent 通用工具集
  -> 按需读取服务端 Skill 规则并执行校验
```

Skill 标签路由采用固定层级 `领域标签 -> 业务主题 -> 指标标签`。领域标签的优先级高于业务主题：配置了领域标签的 Skill，只有用户问题命中对应领域关键词时才可进入后续候选池；业务主题和指标命中均不得绕过这一门控。现有“例会材料”领域的精确关键词包含判断、反向排除、显式 Skill 名称优先及空候选不恢复全量等规则保持不变，本次只在领域门控之后增加业务主题层，不改写领域隔离语义。

### 3.1 可使用范围是候选路由的前置硬门槛

Skill 广场的“可见”与对话中的“可使用”是不同概念。上下文路由必须以现有的“可使用”口径作为硬门槛，不能在 Top-K 得出结果后再过滤。否则当前用户不能使用的 Skill 名称、摘要、关键词、能力标签或候选数量都会进入模型上下文；显式指定时还可能暴露不应参与当前对话的 Skill。

本方案调整为：每次 `/ai/chat` 和 `/v2/ai/chat` 创建 Agent 前，先依据当前 `userId` 计算 `usableSkillNames`。后续所有步骤只能处理该集合：

```text
所有已登记 Skill
  -> 按当前 userId 执行可使用判定
  -> usableSkillNames
  -> 与 active skill_routing_metadata 取交集
  -> 领域硬门控 -> 业务主题筛选 -> 指标筛选
  -> Capability Router 生成可选加分信号
  -> SkillCandidateSelector Top-K
  -> 模型可见的名称 + 短摘要（均来自可使用集合）
```

`usableSkillNames` 为空时，路由层返回空候选，不得回退成“全部 Skill 可用”。低置信度回退到 Top-10 时，也只能从 `usableSkillNames` 中选择，不能扩展到全局 Skill。

### 3.2 当前项目的 Skill 来源与可使用逻辑

| 来源 | 可使用范围 | 现有判定依据 | 路由处理 |
|---|---|---|---|
| 内置 Skill：`src/main/resources/workspace/skills/**/SKILL.md` | 全员 | 文件属于系统内置业务能力 | 对所有已登录用户进入可使用集合；仍受 `active` 路由开关控制 |
| 杭研维度发布的 Skill | 杭研成员 | `skill_publish.status=APPROVED` 且 `target_type=COMPANY`；现有代码将 `COMPANY` 直接命中 | 对所有当前登录用户进入可使用集合 |
| 小组 / 部门维度发布的 Skill | 对应小组 / 部门成员 | `skill_publish.status=APPROVED`，`target_id` 匹配当前最大“版本月份”的 `developer_pl_person_info.统计组/部门` | 命中成员自动可用，无需创建 `skill_reference` |
| 产品线维度发布的 Skill | 目前不自动可用 | `PRODUCT_LINE` 查询分支在现有 Java 与 SQL 中均已注释 | 不进入自动可使用集合；恢复现有产品线分支后再纳入 |
| 私有 Skill | owner 或命中授权者 | `skill_visible_grant` 的 USER / GROUP / DEPARTMENT / VIRTUAL_GROUP 命中 | 授权命中自动可用 |
| 个人 Skill | **仅创建者本人** | owner 命中；个人 Skill 不接受跨用户引用作为可使用来源 | 非 owner 永远不进入可使用集合，不因关键词命中、低置信度回退或 Capability 绑定而参与路由 |

现有机制中，Skill 广场的可见范围、引用记录和运行时加载存在不同口径。本方案不改变广场“可见”语义，而是统一对话路由的“可使用”口径：

1. 现有 `SkillManageService.isVisible()` 与 `SkillMapper.xml` 的 `visibleSkillIds` 将 `PERSONAL` 与 `PUBLIC` 一并视为全员可见。这是 Skill 广场浏览规则，不作为对话路由的候选来源；本任务不以修改该页面语义为前提。
2. 现有 `DatabaseSkillRepository` 的运行时加载已采用 `owner ∪ skill_reference ∪ 已审批维度发布 ∪ 私有授权`。新的路由集合应复用这套“使用”来源，而不是从广场可见列表推导。
3. 用户确认的业务规则要求“人维度仅个人能使用”，因此统一 Resolver 在处理 `PERSONAL` 时优先 owner，排除跨用户 `skill_reference`；这是对当前运行时查询的最小纠偏。
4. 内置目录 `workspace/skills` 由 `BuiltinSkillRegistrar` 注册到 `skill_index`，不是 `skill_manage` 的个人 Skill；它们需要明确标记为系统全员可使用，不能被个人/维度规则误过滤。

### 3.3 统一的 SkillUsageResolver

新增一个请求期只读服务，例如 `SkillUsageResolver`，作为“当前用户是否能将 Skill 用于对话”的唯一解释器。`DatabaseSkillRepository`、`SkillVectorIndexVisibilityFilter`、Capability Router 和路由配置运行时查询都调用它。Skill 广场列表、详情读取继续保留现有可见性逻辑，避免把页面浏览权限和对话使用权限混为一谈。

建议接口：

```java
public interface SkillUsageResolver {
    Set<String> findUsableRetrievalNames(String userId);
    boolean canUseManagedSkill(Long skillId, String userId);
    boolean canManageRoutingConfig(String retrievalName, String userId);
}
```

实现输入分为两类：

- 系统内置 Skill：从 `skill_index.source`、内置注册来源或显式 `scope=BUILTIN` 判断，直接加入全员可使用集合；
- `skill_manage` Skill：严格按 owner、已审批且命中当前组织的发布记录、私有授权和显式引用计算；个人 Skill 排除跨用户引用。

`PERSONAL` 的判定优先级高于引用记录：只要该 Skill 为个人 Skill，非 owner 即使历史上存在 `skill_reference` 也不能获得可使用资格。这样兼容存量引用数据，同时落实人维度仅本人可用。

维度可使用规则沿用现有代码口径，而不是在路由配置表中重复维护：

```text
COMPANY（杭研）     -> 全员
GROUP（小组）       -> 当前用户“统计组”命中
DEPARTMENT（部门）  -> 当前用户“部门”命中
PRODUCT_LINE        -> 当前项目当前实现中产品线归属分支被注释；恢复后才纳入可使用集合
VIRTUAL_GROUP       -> 仅按现有 skill_visible_grant 私有授权逻辑命中
```

“当前用户组织归属”沿用现有 `SkillMapper.xml` 的查询口径：从 `developer_pl_person_info` 中取当前最大“版本月份”的“统一认证号”记录，并以“统计组”/“部门”匹配发布目标；`MockOrgService.getUserOrgs(userId)` 保持用于页面展示和审批相关逻辑。人员表无匹配记录时，不得假定其属于部门或小组；`COMPANY` 仍按当前代码直接命中，显式 USER 私有授权仍可命中。

### 3.4 路由元数据与可使用范围的关系

`skill_routing_metadata`、`capability_registry`、`skill_capability_binding` 只描述“如何匹配”，不授予任何访问权限。

具体规则：

- 路由元数据表不新增 `visibility`、`owner_user_id`、部门等重复字段；可使用范围的真相仍来自内置来源、`skill_manage`、`skill_reference`、`skill_publish`、`skill_visible_grant` 和组织归属。
- Capability 与 Skill 绑定可以是全局配置，但查询时必须 `binding.skill_name IN usableSkillNames`。
- 个人 Skill 的路由元数据只可由 owner（或未来定义的系统管理员）查看和修改；其他维度 Skill 的管理权限沿用既有 owner/审批权限，不因“可使用”自动获得“可配置”权限。
- “Skill 配置”页面的列表也必须按当前管理用户的可管理范围过滤；普通用户不能借该页面枚举其他人的个人 Skill。
- 内置 Skill 与杭研级 Skill 的路由配置可由既有管理权限主体维护；普通使用者只通过对话使用，不因全员可使用而获得编辑权限。

### 3.5 显式名称、错误语义和审计

显式写出某个 Skill 名称时，先在 `usableSkillNames` 内匹配：

- 命中可使用 Skill：强制置于候选首位；
- 仅在全局元数据命中、但不在可使用集合：按“未找到可用 Skill”处理，不返回“无权限访问某某 Skill”，避免泄露 Skill 存在性；
- 完全未命中：沿用现有低置信度回退，但回退候选仍只来自可使用集合。

审计日志记录 `userId` 的哈希、可使用候选数量、路由前后候选数量、是否出现未授权显式命名以及最终路由结果；不记录未授权 Skill 的名称、正文、摘要或业务数据。

### 3.6 数据库登记模型

新增能力登记表，与现有 `skill_index`、`skill_routing_metadata` 分层关联：

```sql
CREATE TABLE capability_registry (
    capability_name VARCHAR(128) PRIMARY KEY,
    short_summary   VARCHAR(500) NOT NULL,
    keywords        TEXT NOT NULL DEFAULT '[]',
    domain_tags     TEXT NOT NULL DEFAULT '[]',
    priority        INTEGER NOT NULL DEFAULT 0,
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at      TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE skill_capability_binding (
    skill_name      VARCHAR(128) NOT NULL,
    capability_name VARCHAR(128) NOT NULL,
    priority        INTEGER NOT NULL DEFAULT 0,
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (skill_name, capability_name),
    FOREIGN KEY (capability_name) REFERENCES capability_registry(capability_name)
);
```

`skill_routing_metadata` 保存 Skill 级别的领域标签、业务主题标签、指标标签、关键词、短摘要和创建人；不再保存或使用 `aliases`，也不保存数据源标签。`capability_registry` 只保存业务能力分类和关键词；绑定表表达一个 Skill 可属于多个能力。`skill_name` 是逻辑外键，可指向 `skill_manage.retrieval_name` 或系统内置 `skill_index.name`；由于来源是两个表的并集，不建立只指向 `skill_index` 的物理外键，改由注册器、桥接服务和启动审计保证引用完整性。能力和 Skill 均由登记表维护，页面新增、改名、停用时必须同步维护关联记录。工具不是 Skill 的登记项，继续由 Agent/Subagent 的通用工具集和现有权限机制管理。

### 3.7 Skill 路由标签词典

Skill 配置页面的标签字段统一使用数据库标签词典，不允许页面自由录入标签，也不提供别名字段。词典至少包含以下三个命名空间：

| 标签类型 | 用途 | 路由层级 |
|---|---|---|
| `DOMAIN` 领域标签 | 现有领域关键词隔离，例如“例会材料”“质量管理” | 一级硬门控 |
| `TOPIC` 业务主题 | 在领域门控后的候选中继续区分业务主题，例如“QI卡口” | 二级筛选 |
| `METRIC` 指标标签 | 在主题候选中精确定位指标，例如“Q2-1”“通过率” | 三级筛选 |

第一版复用现有 `tool_route_tag_dictionary` 作为统一路由标签词典，每条记录包含 `tag_type`、`tag_name`、`description`、`enabled` 和更新时间。需要通过增量迁移把字典约束及 `ToolRoutingTagType` 从当前的 `TOPIC/METRIC/DIMENSION` 扩展为 `DOMAIN/TOPIC/METRIC/DIMENSION`。`DOMAIN` 只供 Skill 领域隔离使用；工具路由继续使用 `TOPIC/METRIC/DIMENSION`，不得因为共享物理词典而把工具维度或主题解释为 Skill 领域。

Skill 元数据的 `domain_tags`、`topic_tags`、`metric_tags` 只能引用对应类型且已启用的词条；停用词条不得继续作为新配置值。保存时 Repository 必须分别调用字典校验，不能只校验 `METRIC`。领域匹配仍复用现有精确关键词门控，不因词典统一而改为语义匹配或工具路由逻辑。

Skill 的创建人直接对应 Skill 广场的 `skill_manage.owner_user_id`。`workspace/skills` 下的内置 Skill 没有 Skill 广场所有者，统一显示为“通用”；该字段由服务端从 Skill 所有权数据推导，不能由前端伪造。创建人只用于展示和“我的”筛选，不改变 Skill 的可使用范围和权限。历史数据库中的 `maintainer` 列仅作为兼容存储列，逻辑接口统一返回 `creator`。

### 3.8 运行时准确性措施

1. 首先沿用现有可使用范围，只允许 `usableSkillNames` 中的 Skill 进入任何路由、回退和模型上下文。
2. 用户明确写出当前可使用的 Skill 名称时，沿用现有显式名称优先规则，直接强制命中并排在首位；删除 `aliases` 后不再提供别名强制命中。
3. 未显式指定 Skill 时，先执行现有领域标签硬隔离。配置了领域标签的 Skill，用户原始问题必须包含相应领域关键词才允许进入后续候选；业务主题、指标、关键词和优先级均不能绕过领域门控。本次增加业务主题字段时不得修改这一判断的匹配、排除和回退语义。
4. 现有“例会材料”规则保持不变：问题包含精确关键词“例会材料”时，只保留 `domain_tags` 中完整标签等于“例会材料”的 Skill；问题不包含时排除这些 Skill。领域为空的 Skill 可继续参与后续路由；配置了其他领域标签的 Skill 仍必须命中自身领域关键词，不能因为“不属于例会材料”而自动放行。不能以“不含例会材料”为理由推断或要求填写“非例会材料”标签。
5. “例会材料”继续使用普通字符串包含判断，不使用正则、分词或语义猜测；领域标签继续使用归一化后的完整标签相等判断，避免把“非例会材料”误认为“例会材料”。
6. 领域门控后按 `topic_tags` 做业务主题筛选，再按 `metric_tags` 做指标筛选；剩余候选继续结合关键词和优先级评分。固定顺序为 `领域 -> 业务主题 -> 指标`。
7. 低置信度或分数接近时，只能在依次完成可使用范围、领域、业务主题和指标筛选后的候选池中扩展到 Top-10，禁止回退时重新加入任一前置阶段已排除的 Skill，也不插入“通用 Skill”。Capability 是否召回到分类不作为扩容触发条件。
8. 每次 Tool 调用继续执行现有 Agent/Subagent 工具可用性、数据源和参数校验；路由层不增加按 Skill 配置的工具白名单。
9. 记录命中的领域、业务主题和指标标签、候选 Skill、评分、显式命中、回退原因和校验结果；不记录完整用户问题或业务结果。
10. 数据库元数据按 `updated_at` 缓存，登记表更新后主动失效缓存；启动时检查孤儿绑定和缺失 active 元数据。

其中第 3、4 条共同定义候选集合：

```text
非例会请求候选集
  = 当前用户可使用的 Skill
  - domain_tags 中包含完整标签“例会材料”的 Skill
  -> 保留 domain_tags 为空的 Skill
  -> 对其他非空 domain_tags 继续执行现有领域关键词命中判断
```

因此，领域标签为空或未配置领域标签的 Skill 可保留；领域标签为其他值时，只有用户问题包含对应领域关键词才可保留。同时包含“例会材料”和其他标签的 Skill，在问题不含“例会材料”时仍应按原有专项规则排除。只有执行领域门控后确实没有剩余 Skill，才返回空候选集，绝不能将原来的全部可使用 Skill 恢复回来。

| Skill 的 `domain_tags` | 问题不含“例会材料”时 |
|---|---|
| 空或未配置 | 保留 |
| `["质量管理"]` | 仅当问题包含“质量管理”时保留，否则排除 |
| `["非例会材料"]` | 不要求配置该标签；若历史数据存在，仍按其自身关键词门控 |
| `["例会材料"]` | 排除 |
| `["例会材料", "质量管理"]` | 按原有“例会材料”专项规则排除，不因命中“质量管理”而绕过 |

### 3.9 上下文边界

- 模型只看到能力名称/短摘要和 Skill 名称/短摘要，不看到完整 `SKILL.md`。
- 工具继续按当前 Agent/Subagent 的通用工具集暴露；Capability Router 不新增 Skill 到 Tool 的配置关系。
- 完整 Skill 正文、参考文件和大工具结果保留在服务端或 artifact 中，按需由后端读取和校验。
- 历史案例完全不注入 system prompt；本方案不改变 LLM 最终答案文本。

### 3.10 系统工具白名单与业务工具边界（2026-08-28）

为减少固定工具 schema 占用，v2 主 Agent 的 Harness 系统工具采用白名单，当前只保留：

- `agent_spawn`、`agent_send`：启动和通信子智能体；
- `task_output`、`task_list`：查看异步子任务结果和状态；
- `load_skill_through_path`：按需加载 Skill 资源。

以下系统工具从主 Agent 的模型请求中移除：记忆类 `retrieveFromMemory`、`memory_search`、`memory_get`、`recordToMemory`；会话类 `session_search`、`session_list`、`session_history`、`session_save`；文件与命令类 `read_file`、`write_file`、`edit_file`、`grep_files`、`glob_files`、`list_files`、`execute`；任务/管理类 `task_cancel`、`agent_list`、`skill_manage`、`skill_curator`、`propose_skill`、`save_skill`；计划类 `reset_equipped_tools`、`plan_enter`、`plan_write`、`plan_exit`、`todo_write`。

这只是主 Agent 的系统工具边界，不等同于删除 Java Bean 或后台管理能力。v2 业务工具仍按通用工具集独立管理；`wide_table_query`、`clickhouse_query` 继续隐藏，`sql_list`、`script_list` 恢复为可见，和 `python_exec`、`arith`、`sql_registry_exec`、`script_exec`、`tool_router`、`toolMetaInfo` 一样按现有 Skill/Agent 配置使用。

代码实现位于 `v2/runner/HarnessA2aRunnerV2`（构建后清理非白名单系统工具）和 `v2/config/V2ToolConfig`（主 Agent 业务工具注册）。重启后端后生效；验证时查看 `LLM request tools: count=..., names=[...]` 日志，确认最终发送给模型的清单。子 Agent 可按其独立声明保留所需工具，不能将子 Agent 工具数量与主 Agent 混统计。

### 3.11 迁移与兼容

1. 先创建 `capability_registry` 和 `skill_capability_binding`，历史 Skill 默认不自动标记为 active 候选，避免错误路由。
2. 由业务管理员为现有 Top-10 Skill 补齐能力、短摘要、领域、业务主题、指标和关键词，创建人自动从 Skill 广场同步，完成离线问题集评测后再开启路由开关。
3. 在路由开关关闭时，保留当前 Skill 可见性行为；开关开启后按新链路筛选，不改变 Skill 文件内容。
4. `skill_routing_metadata` 作为 Skill 级元数据继续兼容，后续逐步将能力字段迁移到新表，避免一次性改动现有页面和脚本。

启动时 `BuiltinSkillRegistrar` 必须扫描所有 `skills/**/SKILL.md` 并自动补齐缺失的 `skill_index` 与 `skill_routing_metadata` 行：短摘要取 frontmatter 的 `description`（最多 500 字符），关键词可从目录名的稳定片段生成；不再自动生成或保存别名。自动补齐行的领域、业务主题和指标初始为空，创建人按 Skill 广场所有者推导（通用 Skill 为“通用”），必须由管理员通过词典选择并确认标签后再进入严格标签路由。已存在的管理员登记数据绝不覆盖。只有 Skill frontmatter 明确包含 `capability: <name>` 时才自动创建能力和绑定；未声明能力的 Skill 保持 inactive，等待人工归类。

每次调用模型的输入按以下顺序治理：

```text
用户请求
  -> 组装 system prompt、工具 schema、会话消息
  -> ContextBudgetMiddleware 估算 token
  -> 未超预算：原样调用模型
  -> 接近预算：移除历史案例 + 裁剪低优先级用户记忆 + 压缩已消费工具结果 + 缩短 Skill 描述
  -> 仍超预算：历史消息 compaction，只保留当前会话必要消息和最近工具链
  -> 单个最新结果仍超预算：落 artifact，替换为引用/摘要
  -> 超过硬上限仍无法安全压缩：返回明确错误，不向模型发送超限请求
```

建议的保留优先级：

1. 当前用户问题、当前 Skill 的硬规则和工具调用参数。
2. 当前工具调用的结构化结果摘要。
3. 最近一轮或两轮的 tool call/tool result 配对。
4. 当前任务所需的 Skill 摘要、固定核心规则和工具调用参数。
5. 已消费的 Skill 摘要、旧工具结果和旧的中间思考文本。

> 约束调整：不向 system prompt 注入任何历史案例；不向模型加载任何完整 `SKILL.md`。预算不足时优先移除历史案例、裁剪低优先级用户记忆、压缩工具结果和 Skill 摘要，固定核心规则与当前用户问题必须保留。

## 4. 分阶段实施计划

### Task 1：建立上下文规模观测

**文件：**

- Create: `analysis-project/src/main/java/com/agentscopea2a/v2/context/ContextSizeEstimator.java`
- Create: `analysis-project/src/main/java/com/agentscopea2a/v2/context/ContextBudgetProperties.java`
- Modify: `analysis-project/src/main/java/com/agentscopea2a/v2/config/V2InfraConfig.java`
- Modify: `analysis-project/src/main/resources/application.properties`
- Test: `analysis-project/src/test/java/com/agentscopea2a/v2/context/ContextSizeEstimatorTest.java`

**实现要求：**

1. `ContextSizeEstimator` 接受最终传给模型的 `ReasoningInput`，分别统计：
   - `messages` 文本字符数；
   - `ToolResultBlock` 字符数；
   - system/tool schema 字符数（如果 AgentScope 暴露该字段）；
   - 估算 token 数。
2. 不新增在线依赖。第一版使用按 Unicode code point 分段的确定性保守估算：ASCII 连续段按 `ceil(length / 4)`；CJK 汉字、日文假名和韩文音节按每个 code point 约 1 token；其他 Unicode 字符按约 1.5 字符/token。禁止对整段文本直接使用 `chars / 4`，Java 实现不得使用 `String.length()` 代替 code point 数。消息、工具调用和 schema 额外增加固定结构开销，估算结果只用于预算决策和日志，不当作计费值。
3. 输出 `ContextSizeSnapshot`，至少包括 `estimatedInputTokens`、`messageCount`、`toolResultChars`、`skillChars`、`largestBlockChars`。
4. `ContextBudgetProperties` 使用 `@ConfigurationProperties(prefix = "harness.a2a.context-budget")`，提供：

```properties
# Task 1/Task 5 的 Unicode 估算和输出预留接入完成前保持关闭
harness.a2a.context-budget.enabled=false
harness.a2a.context-budget.max-input-tokens=50000
# provider 的完整上下文窗口；输入预算不得挤占输出预留
harness.a2a.context-budget.model-context-tokens=64000
harness.a2a.context-budget.reserve-output-tokens=8000
harness.a2a.context-budget.warn-ratio=0.80
harness.a2a.context-budget.hard-ratio=1.00
```

5. 首先只记录指标和日志，不改变请求内容。日志必须包含 `conversationId`、接口来源、消息数、估算 token、最大单块字符数和各来源占比；不得记录完整业务数据。

**验收：** 构造含系统文本、Skill、工具结果和普通消息的 `ReasoningInput`，估算结果稳定；相同输入重复执行结果一致；400 个 ASCII 字符加 100 个中文字符的估算值应明显高于统一 `500 / 4` 的 125 token 结果；日志不包含工具结果正文。测试必须覆盖 surrogate pair、CJK、混合文本、工具 schema 和结构开销。

### Task 2：把现有工具结果压缩改为可配置分层策略

**实施状态（2026-08-29）：基础版本已实现。** 已接入 `compactable-tools`、`artifact-tools` 和 `max-latest-tool-tokens` 配置；历史结果继续结构化压缩，最新超限结果保存为隔离 artifact 并仅向模型返回引用摘要。`script_output` 中的 ECharts/HTML 仍由独立 SSE 事件完整发送。后续仍需补充更完整的 JSON/HTML artifact 专项验收。

**文件：**

- Modify: `analysis-project/src/main/java/com/agentscopea2a/v2/middleware/ToolResultTruncationMiddleware.java`
- Modify: `analysis-project/src/main/java/com/agentscopea2a/v2/config/V2InfraConfig.java`
- Modify: `analysis-project/src/main/resources/application.properties`
- Test: `analysis-project/src/test/java/com/agentscopea2a/v2/middleware/ToolResultTruncationMiddlewareTest.java`

**实现要求：**

1. 保留现在的 Markdown 结构化压缩规则，避免破坏 Skill 中的代码块、表格、标题和列表。
2. 将工具分为三类配置：

```properties
# 已消费后允许结构化压缩的工具
harness.a2a.context-budget.compactable-tools=load_skill_through_path,sql_list
# 可转为摘要/引用的潜在大结果工具
harness.a2a.context-budget.artifact-tools=sql_registry_exec,script_exec,wide_table_query,python_exec
# 单个工具结果进入模型的最大估算 token
harness.a2a.context-budget.max-latest-tool-tokens=8000
```

3. 对已消费结果：继续使用 `compactMarkdown()`，并保留压缩标记。
4. 对所有非最新的大型结构化结果：扫描全部历史消息中的 `artifact-tools` 结果，逐个替换为字段摘要、行数、列名和 artifact 引用，不把完整 CSV/大 JSON 重复注入；不能因为最后一个工具结果很小就提前结束扫描。
5. 对最新结果：先保留完整内容；如果超过 `max-latest-tool-tokens`，交给 Task 4 的 artifact handoff，而不是简单截断。这样不会让模型看到半截 JSON 或损坏的 ECharts/HTML fenced block。
6. `script_exec` 的 `echarts`/`html` 代码块只在 SSE 的 `script_output` 中展示；模型侧可以收到“已生成渲染块，完整内容见 artifact”的短摘要，但不得影响事件内容。

**验收：**

- 已消费的 Skill 结果能缩短，代码块和表格仍存在；
- 大 JSON 不被截成非法 JSON；
- ECharts/HTML 代码块的 `script_output` 仍完整；
- 未配置工具的旧行为保持不变。

### Task 3：只注入 Skill 摘要，禁止完整正文进入模型上下文

**文件：**

- Inspect/Modify: `analysis-project/src/main/java/com/agentscopea2a/v2/skills/SkillVisibilityFilter.java` 及实际加载 Skill 的实现类
- Modify: `analysis-project/src/main/java/com/agentscopea2a/v2/middleware/PerUserMemoryContextMiddleware.java`
- Modify: `analysis-project/src/main/java/com/agentscopea2a/v2/middleware/EpisodicRetrievalMiddleware.java`
- Modify: `analysis-project/src/main/java/com/agentscopea2a/v2/middleware/DimensionStateMiddleware.java`
- Modify: `analysis-project/src/main/java/com/agentscopea2a/v2/config/V2MemoryConfig.java`
- Modify: `analysis-project/src/main/resources/application.properties`
- Modify: `analysis-project/src/main/resources/workspace/AGENTS.md`（仅在确认规则需要时）
- Test: `analysis-project/src/test/java/com/agentscopea2a/v2/skills/SkillVisibilityFilterTest.java`

**实现要求：**

1. Agent 只向模型暴露 Skill 名称、描述和受限路由摘要，不在 system prompt 或工具结果中返回完整 `SKILL.md`。
2. `short_summary` 只用于候选路由提示，不承担完整执行逻辑；工具顺序、参数要求、成功/失败条件和业务口径仍以服务端保存的原始 Skill 为准。
3. `load_skill_through_path` 后续改为服务端解析 Skill：模型只收到完成当前步骤所需的最小规则摘要；完整正文留在服务端用于校验/执行，不进入模型上下文。
4. 固定核心规则永久保留，不参与动态裁剪；不相关工具通过工具组隐藏，减少工具 schema。
5. 用户记忆只保留最近或高优先级条目，并设置最大字符预算。
6. 关闭 `EpisodicRetrievalMiddleware` 的历史案例注入；历史案例可以继续存储，但不进入聊天模型上下文。
7. 维度状态设置最大字符数，超限时按字段优先级裁剪，不截断 JSON 或半个字段。
8. 保留数据库和文件系统中的 Skill 原文，摘要只作为上下文材料，不覆盖原文。
9. 增加开关和上限，便于内网回滚或逐步放量：

```properties
harness.a2a.skill-context.index-only=true
harness.a2a.skill-context.max-visible-skills=5
harness.a2a.skill-context.max-description-chars=500
harness.a2a.skill-context.max-summary-chars=3000
harness.a2a.memory.max-system-prompt-chars=3000
harness.a2a.dimension.max-chars=2000
harness.episodic.retrieval.enabled=false
```

**注意：** 该任务不能改变 Skill 的业务语义，也不能把用户在 Skill 中声明的输出规则删除。Skill 原文仍可供服务端读取，但完整正文不得通过 system prompt、`load_skill_through_path` 或其他工具结果发送给模型。

### Task 3.0：建立 Skill 路由元数据登记表

Skill 路由相关内容必须落在数据库登记表，而不是写在 `application.properties`、`SKILL.md` 正文或 Java 常量中。现有 `skill_index` 是系统内置 Skill 的名称、描述、版本和使用统计索引；`skill_manage` 是页面 Skill 的管理主数据。因此路由表以规范 `skill_name` 作为逻辑外键，关联 `skill_manage.retrieval_name ∪ skill_index.name`，不再声明只与 `skill_index.name` 一对一。

**文件：**

- Existing baseline: `analysis-project/src/main/resources/db/migration/gauss/V20260827.1__skill_routing_metadata.sql`
- Existing FK adjustment: `analysis-project/src/main/resources/db/migration/gauss/V20260831.1__skill_routing_metadata_manage_source.sql`
- Create: `analysis-project/src/main/resources/db/migration/gauss/V20260903.3__skill_routing_metadata_hierarchy.sql`
- Modify: `analysis-project/src/main/java/com/agentscopea2a/v2/skills/SkillRoutingMetadata.java`
- Modify: `analysis-project/src/main/java/com/agentscopea2a/v2/skills/SkillRoutingMetadataInput.java`
- Modify: `analysis-project/src/main/java/com/agentscopea2a/v2/skills/SkillRoutingMetadataView.java`
- Modify: `analysis-project/src/main/java/com/agentscopea2a/v2/skills/SkillRoutingMetadataRepository.java`
- Modify: `analysis-project/src/main/java/com/agentscopea2a/v2/skills/SkillRoutingMetadataAdminService.java`
- Modify: `analysis-project/src/main/java/com/agentscopea2a/v2/toolrouting/ToolRoutingTagType.java`
- Modify: `analysis-project/src/main/java/com/agentscopea2a/v2/toolrouting/ToolRoutingTagDictionary.java`
- Modify: `analysis-project/src/main/java/com/agentscopea2a/v2/skills/BuiltinSkillRegistrar.java`
- Modify: `analysis-project/src/main/java/com/agentscopea2a/v2/skillManager/service/SkillManageBridge.java`
- Modify: `analysis-project/frontend/src/types/skillRouting.ts`
- Modify: `analysis-project/frontend/src/pages/SkillRoutingConfigPage.vue`
- Test: `analysis-project/src/test/java/com/agentscopea2a/v2/skills/SkillRoutingMetadataRepositoryTest.java`

**目标逻辑表结构：**

```sql
CREATE TABLE skill_routing_metadata (
    skill_name        VARCHAR(128) PRIMARY KEY,
    short_summary     VARCHAR(3000) NOT NULL,
    keywords          TEXT NOT NULL DEFAULT '[]',
    domain_tags       TEXT NOT NULL DEFAULT '[]',
    topic_tags        TEXT NOT NULL DEFAULT '[]',
    metric_tags       TEXT NOT NULL DEFAULT '[]',
    maintainer        VARCHAR(128) NOT NULL DEFAULT '', -- 兼容物理列；逻辑字段为 creator
    priority          INTEGER NOT NULL DEFAULT 0,
    active            BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at        TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_skill_routing_metadata_active
    ON skill_routing_metadata(active, priority DESC);
```

该 DDL 表示完成 Task 3.0 后运行时使用的目标字段，不代表当前数据库物理结构。当前 `V20260827.1` 仍包含 `aliases`、`data_source_tags`，且没有 `topic_tags`、历史创建人兼容列；旧列为数据库回滚兼容暂时保留，但服务端、接口和页面不以旧字段名对外暴露。不得修改已经执行过的 `V20260827.1`，必须通过新的 `V20260903.3` 增量迁移到目标态。

`V20260903.3__skill_routing_metadata_hierarchy.sql` 至少包含以下动作：

```sql
ALTER TABLE skill_routing_metadata
  ADD COLUMN IF NOT EXISTS topic_tags TEXT NOT NULL DEFAULT '[]';

ALTER TABLE skill_routing_metadata
  ADD COLUMN IF NOT EXISTS maintainer VARCHAR(128) NOT NULL DEFAULT ''; -- 兼容物理列，逻辑字段为 creator

ALTER TABLE skill_capability_binding
  DROP CONSTRAINT IF EXISTS fk_skill_capability_skill;

ALTER TABLE tool_route_tag_dictionary
  DROP CONSTRAINT IF EXISTS ck_tool_route_tag_dictionary_type;

ALTER TABLE tool_route_tag_dictionary
  ADD CONSTRAINT ck_tool_route_tag_dictionary_type
  CHECK (tag_type IN ('DOMAIN', 'TOPIC', 'METRIC', 'DIMENSION'));
```

Capability 绑定的 `skill_name` 与路由元数据使用相同的逻辑外键规则。`V20260831.1` 只删除了 `skill_routing_metadata` 的旧外键，并未删除 `skill_capability_binding` 中的 `fk_skill_capability_skill`，因此后续迁移必须显式处理，不能把两者混为同一个约束。

数组字段统一存储为 JSON 数组文本 `TEXT`，由 Repository 使用 `ObjectMapper` 解析和校验。不要在设计、Flyway 和运行时自建表之间混用 `JSONB` 与 `TEXT`。正式环境以 `db/migration/gauss` 下的 Flyway 迁移为唯一 schema 基线；Repository 的 `CREATE TABLE IF NOT EXISTS` 仅作为本地/历史 MySQL 兼容启动路径，必须生成与 Flyway 等价的列定义，不能承担后续增量迁移。upsert SQL 需要由数据源方言适配层选择 openGauss 的 `ON CONFLICT` 或 MySQL 的 `ON DUPLICATE KEY UPDATE`，禁止在标注为 openGauss 的 Repository 中直接硬编码 MySQL 方言。

`skill_routing_metadata.skill_name` 不建立指向 `skill_index` 的物理外键。配置页面以 `skill_manage.retrieval_name` 为主数据，系统内置 Skill 以 `skill_index.name` 为主数据，单一外键无法表达这个并集；现有 `V20260831.1__skill_routing_metadata_manage_source.sql` 也已删除旧外键。引用完整性由 `BuiltinSkillRegistrar`、`SkillManageBridge` 和启动/发布审计共同保证，审计发现孤儿记录时禁止将其置为 `active=true`。

字段职责：

| 字段 | 用途 |
|---|---|
| `short_summary` | 给候选路由使用的一两句适用场景提示，不承担完整执行逻辑 |
| `keywords` | 业务关键词，如“达标率”“打分率”“版本” |
| `domain_tags` | 一级领域硬隔离；有领域标签时问题必须包含对应领域关键词，保持现有检索隔离逻辑 |
| `topic_tags` | 领域门控后的二级业务主题筛选，如“QI卡口” |
| `metric_tags` | 主题筛选后的三级指标匹配，如 `Q2-1`、`pass_rate` |
| `creator` | Skill 广场创建人；通用 Skill 固定为“通用”，不参与权限判定或路由评分 |
| `priority` | 同分候选的稳定排序；数值越高优先级越高 |
| `active` | 是否参与候选筛选和工具校验 |

不得增加 `generic_fallback` 字段：低置信度时只是将实际业务 Skill 的候选总量扩大到 Top-10，不存在额外通用 Skill。

**实现要求：**

1. `BuiltinSkillRegistrar` 在新内置 Skill 注册到 `skill_index` 时，同步建立路由元数据行；初始 `short_summary` 自动取受限描述，关键词可生成待确认初稿，领域、业务主题和指标由管理员从词典选择。不再生成或维护别名。
2. `SkillManageBridge` 创建、改名、禁用或删除页面 Skill 时，同步创建、重命名、停用或删除对应路由元数据；与 `BuiltinSkillRegistrar`、启动审计共同维护 `skill_manage.retrieval_name`、`skill_index.name` 和路由表之间的逻辑引用完整性。
3. 运行时只读取 `active=true` 的元数据，并按 `updated_at` 缓存；更新后必须失效缓存，不能等待进程重启。
4. 历史已有 Skill 必须通过迁移初始化对应的路由行。旧 `aliases` 列先保留用于数据库回滚兼容，但服务端、接口和页面停止读写，运行时不再用其匹配；确认无回滚需求后再单独删除。管理员不需要重写复杂 Skill，只需确认关键词并从词典补充领域、业务主题和指标；未确认的行不得标记为可用于生产候选。
5. Skill 配置保存时，服务端从 Skill 广场所有权读取创建人（通用 Skill 写入“通用”），并校验 `domain_tags`、`topic_tags`、`metric_tags` 均来自对应的已启用标签词典。前端不能自行提交创建人。`ToolRoutingTagType` 和数据库 CHECK 约束必须先支持 `DOMAIN`；Repository 必须分别校验三个字段，不能沿用当前只校验 `METRIC` 的实现。
6. `short_summary` 由登记表维护，仅作为候选提示；服务端读取原始 Skill 进行最终校验，摘要缺失、过期或与原文冲突时标记待修订并暂停生产路由，不能把全文回传给模型。

**全局配置边界：**

以下是系统级策略，应继续保留在 `application.properties`，不存入单条 Skill 记录：

```properties
harness.a2a.skill-context.max-visible-skills=5
harness.a2a.skill-context.fallback-visible-skills=10
harness.a2a.skill-context.min-confidence=0.40
harness.a2a.skill-context.min-score-gap=0.10
harness.a2a.skill-context.enforce-explicit-name=true
harness.a2a.skill-context.enforce-domain-coverage=true
harness.a2a.skill-context.route-validation.enabled=true
harness.a2a.capability-routing.enabled=false
harness.a2a.capability-routing.max-capabilities=3
harness.a2a.capability-routing.max-recalled-skills=20
```

这些值决定所有请求统一使用的候选数量、置信度阈值和功能开关；放到每个 Skill 中会产生互相冲突的路由规则，且不利于统一运维调参。

**验收：**

- 每个 `active` Skill 都恰好有一条路由元数据；
- Skill 改名/停用后，`skill_index`、`skill_manage` 和路由元数据没有孤儿记录；
- 模型可见内容只来自 `skill_name + short_summary`，不会读取 `content` 或完整 `SKILL.md`；
- Top-10 中每一个候选均来自 `active=true` 的实际业务 Skill。

### Task 3.1：先实现统一可使用范围解析，并保留 Skill 广场现有可见性

**前置条件：本任务必须先于 Capability Router、Top-K 候选筛选和 Skill 配置页面权限控制完成。**

**文件：**

- Create: `src/main/java/com/agentscopea2a/v2/skills/SkillUsageResolver.java`
- Create: `src/main/java/com/agentscopea2a/v2/skills/DatabaseSkillUsageResolver.java`
- Modify: `src/main/java/com/agentscopea2a/v2/skills/DatabaseSkillRepository.java`
- Modify: `src/main/java/com/agentscopea2a/v2/skills/SkillVectorIndexVisibilityFilter.java`
- Modify: `src/main/java/com/agentscopea2a/v2/config/V2SkillConfig.java`
- Modify: `src/main/java/com/agentscopea2a/v2/skills/SkillRoutingMetadataController.java`
- Modify: `src/main/resources/mybatis/mapper/gauss/SkillMapper.xml`
- Test: `src/test/java/com/agentscopea2a/v2/skills/DatabaseSkillUsageResolverTest.java`

**实现要求：**

1. 按本章 3.8 的规则实现单一 `SkillUsageResolver`，返回当前用户可使用的 retrieval name 集合；该集合只服务对话运行时和路由，不替换 Skill 广场的 `isVisible()`。
2. 系统内置 `workspace/skills` 与 `COMPANY:杭研` 已审批发布 Skill 对所有当前登录用户可使用。
3. `PERSONAL` 仅 owner 可使用。Resolver 和运行时 SQL 必须先识别 `PERSONAL` 并排除跨用户 `skill_reference`；`SkillManageService.isVisible()` 与 `visibleSkillIds` 保持既有广场可见性语义，不在本任务中改变。
4. GROUP、DEPARTMENT、PRODUCT_LINE（功能恢复时）和 VIRTUAL_GROUP 必须复用现有组织/授权数据；不得在 `skill_routing_metadata` 新增冗余的可使用范围列。维度发布只有状态为 `APPROVED` 且目标命中当前用户时才能自动可使用。
5. `DatabaseSkillRepository` 的 `getSkill`、`getAllSkillNames` 和 `getAllSkills` 改用 `SkillUsageResolver` 结果，消除 owner/引用/维度 SQL 口径分散造成的运行时权限漂移。
6. `SkillVectorIndexVisibilityFilter` 必须直接注入 `SkillUsageResolver`，在获取 `all` Skill 后立即与当前用户的 `usableSkillNames` 相交；不能假设上游已经完成过滤。任何空结果均保持空，不得回退至 `all`。Resolver 查询失败或无法确认用户身份时，严格路由模式必须 fail-closed 并返回空候选；兼容模式只能在 feature flag 关闭时使用旧链路。
7. `SkillRoutingMetadataController` 的运行时候选查询通过 `SkillUsageResolver` 过滤；配置页面仍按既有 owner/审批管理权限校验，不能将个人 Skill 配置暴露给非 owner。
8. 系统管理员身份的具体判定复用已有审批人/管理员机制；若当前没有统一管理员概念，第一阶段仅允许 owner 编辑 `skill_manage` 对应配置，内置与杭研级配置沿用当前注册表管理入口的既有授权，不能为了页面方便放开全员编辑。

**验收：**

- 用户 A 的 PERSONAL Skill 不进入用户 B 的候选集、运行时仓储和模型上下文；即使 B 持有历史引用记录也不能使用。Skill 广场仍按既有可见性规则展示。
- 杭研发布 Skill 和内置 `workspace/skills` 对 A/B 均可使用；
- 部门/小组 Skill 只对当前最大版本组织数据中命中的成员自动可使用；
- 私有 USER/GROUP/DEPARTMENT/VIRTUAL_GROUP 授权与现有页面逻辑一致；
- 未授权用户显式输入个人 Skill 名称，响应不泄露其存在；
- 一个请求中所有运行时使用判断来自同一 Resolver，不存在仓储/路由/模型上下文三套不一致规则。

**上线闸门：** 在 Task 3.1 完成 Resolver 注入、filter 层兜底和对应测试前，`harness.a2a.skill-context.routing.enabled` 必须为 `false`。不能因为上游当前“看起来已经过滤”就提前开启；上游过滤是性能优化，filter 层 Resolver 交集是安全兜底。

### Task 3.2：Top-K Skill 候选准确性保障

Top-K 只用于缩小模型可见的 Skill 目录，不能作为唯一的业务路由决策。筛选失败会让模型根本看不到正确 Skill，因此必须按以下顺序建立确定性优先级和回退机制。

**文件：**

- Create: `analysis-project/src/main/java/com/agentscopea2a/v2/skills/SkillCandidateSelector.java`
- Create: `analysis-project/src/main/java/com/agentscopea2a/v2/skills/SkillCandidateSelection.java`
- Modify: `analysis-project/src/main/java/com/agentscopea2a/v2/skills/SkillRoutingMetadataRepository.java`
- Modify: `analysis-project/src/main/java/com/agentscopea2a/v2/skills/SkillVectorIndexVisibilityFilter.java`
- Modify: `analysis-project/src/main/resources/application.properties`
- Test: `analysis-project/src/test/java/com/agentscopea2a/v2/skills/SkillCandidateSelectorTest.java`
- Test: `analysis-project/src/test/java/com/agentscopea2a/v2/skills/SkillCandidateSelectorEvaluationTest.java`

**实现要求：**

#### Top-K 算法规范

`SkillCandidateSelector` 的输入和输出必须固定，不能把“筛选”“打分”和“回退”混成一个模糊的检索操作。

**输入：**

```text
question                 用户原始问题
usableSkillNames         当前用户可使用的 Skill 名称集合
allSkills                当前已加载的 Skill 实例
routingMetadata          Skill 路由元数据快照
enabledTagDictionary     已启用的 DOMAIN/TOPIC/METRIC 词典
recalledSkillNames       Capability Router 可选的召回结果
K                        正常候选数，默认 5
fallbackK                低置信度候选数，默认 10
```

**输出：** `SkillCandidateSelection` 至少包含有序 `skillNames`、每个候选的分项得分、`matchedDomainTags`、`matchedTopicTags`、`matchedMetricTags`、`explicitNameMatched`、`confident`、`fallbackExpanded` 和空结果原因。模型只接收有序 Skill 名称与短摘要，分项得分和匹配原因只进入 Trace/评测，不进入普通模型上下文。

**统一预处理：** 对问题、Skill 名称和标签执行 Unicode 字符串的大小写归一化、首尾空白清理和连续空白折叠；保留 `Q2-1`、下划线和中文标点的语义，不把它们转换成别的写法。标签匹配使用“规范标签完整字符串是否出现在问题中”，不做分词、向量相似或自动同义词推断。同一类型标签发生包含重叠时保留最长命中，例如问题中的 `Q2-1` 命中后不再同时把 `Q2` 计为独立指标。Skill 显式名称只匹配规范 Skill 名称/目录名，不再匹配别名。

**算法顺序：**

```text
1. usableSkillNames + active + allSkills 取交集
2. 检测规范 Skill 名称/目录名显式命中
   -> 显式命中的 Skill 自身强制置首并可绕过后续门控
3. 对其余 Skill 执行现有 DOMAIN 领域硬门控
4. 在领域候选池内识别并筛选 TOPIC 业务主题
5. 在主题候选池内识别并筛选 METRIC 指标
6. 可选读取 Capability Router 结果作为加分信号，不做硬过滤
7. 对最终候选计算分数并稳定排序
8. 显式 Skill 置首并受 fallbackK 总上限保护；其余候选正常取前 K，低置信度或分数接近时取前 fallbackK
```

**第 2 步：显式 Skill：** 只有当前用户可使用、active 且实际存在的 Skill 才能显式命中。ASCII Skill 名称/目录名按标识符边界匹配，避免短名称命中另一个更长名称的一部分；中文名称按规范名称完整字符串匹配。多个显式命中按问题中的出现位置、名称长度降序、`priority` 降序、名称升序排序。显式命中不受领域门控和正常 K 限制，但总数仍受 `fallbackK` 保护；超过时只保留排序后的前 `fallbackK` 并记录 `explicit_truncated=true`。显式命中不能使不可使用或 `active=false` 的 Skill 进入候选。它是唯一允许绕过领域门控的路径，而且只对被明确点名的 Skill 生效；其他补充候选仍执行完整领域、主题和指标流程。

**第 3 步：领域硬门控：**

- `domain_tags` 为空的 Skill 保留；
- `domain_tags` 非空时，至少一个规范领域标签必须出现在问题中，否则排除；多个领域标签采用 OR 语义；
- 问题包含精确关键词“例会材料”时，只保留领域标签完整等于“例会材料”的 Skill；问题不包含时，带有该领域标签的 Skill 按现有反向排除规则排除；
- 领域门控结果为空时直接返回空候选，并记录 `domain_no_match`，不得恢复全量，也不得让主题/指标命中绕过领域门控。

**第 4 步：主题筛选：** 从已启用 `TOPIC` 词典中找出出现在问题中的规范主题标签 `matchedTopicTags`。如果集合为空，表示问题没有明确主题信号，跳过主题收窄，保留领域候选池；如果集合非空，只保留 `topic_tags` 与 `matchedTopicTags` 有交集的 Skill。主题信号已识别但没有 Skill 候选时返回空，并记录 `topic_no_match`，不能退回未按主题筛选的领域候选池。

**第 5 步：指标筛选：** 在主题候选池内，从已启用 `METRIC` 词典中找出出现在问题中的规范指标标签 `matchedMetricTags`。如果集合为空，跳过指标收窄；如果集合非空，只保留 `metric_tags` 与 `matchedMetricTags` 有交集的 Skill。指标信号已识别但没有候选时返回空，并记录 `metric_no_match`，不能恢复主题筛选前的候选。

主题和指标的“跳过”仅表示问题中没有识别到该层标签，不表示该层可以绕过前一层；固定顺序始终是 `领域 -> 业务主题 -> 指标`。例如问题只出现 `Q2-1` 而没有出现任何主题标签时，主题层跳过，指标层仍可按 `Q2-1` 收窄；问题出现 `QI卡口` 和 `Q2-1` 时，必须先取 `QI卡口` 主题交集，再取 `Q2-1` 指标交集。

**第 6 步：Capability Router：** 开启能力路由时，`recalledSkillNames` 只作为 `capabilityScore` 加分信号。它不能从指标候选池删除 Skill，也不能重新加入领域、主题或指标阶段已经排除的 Skill。这样 Capability 粗召回错误只影响同一候选池内的排序，不会造成正确 Skill 完全不可见。只有在独立离线评测证明硬预筛选满足召回门槛后，才能另设 feature flag 启用硬预筛选，v1 不启用。

**第 7 步：评分和稳定排序：** 领域不参与加分，它是硬门控。对最终候选使用以下 v1 固定公式，缺失项按 0 计算：

```text
metricScore     = min(1500, 500 * matchedMetricTagCount)
topicScore      = min(600,  300 * matchedTopicTagCount)
keywordScore    = min(300,   50 * matchedKeywordCount)
capabilityScore = recalledSkillNames 包含当前 Skill ? 100 : 0
priorityScore   = clamp(priority, -100, 100)

finalScore = metricScore
           + topicScore
           + keywordScore
           + capabilityScore
           + priorityScore
```

`matched*Count` 只统计当前 Skill 配置中与问题实际命中集合的交集，不统计 Skill 配置了但问题没有出现的标签。指标权重最高、主题次之，Capability 和人工优先级只能影响同类候选排序，不能压过一个额外的指标命中。v1 不启用语义相似度，也不使用数据源信息参与路由；未来增加语义项时必须低于规范主题/指标证据，不能覆盖硬门控或明确标签命中。排序键依次为：`finalScore` 降序、`metricScore` 降序、`topicScore` 降序、`keywordScore` 降序、`capabilityScore` 降序、`priority` 降序、`skillName` 升序。这样相同输入和相同快照始终产生相同 Top-K，不依赖数据库返回顺序。

**第 8 步：K、置信度和回退：** 显式命中数小于 K 时，先放置显式 Skill，再用按正常规则排序的非显式候选补足到 K；显式命中数大于等于 K 时只返回显式 Skill，最多返回 `fallbackK` 个。包含显式命中的请求视为高置信度，不因其余候选分数接近而扩容。没有显式命中的请求按以下规则决定数量：

```text
top1Evidence =
    0.60 * (第一名 matchedMetricTags 非空 ? 1 : 0)
  + 0.30 * (第一名 matchedTopicTags 非空 ? 1 : 0)
  + 0.10 * (第一名 matchedKeywords 非空 ? 1 : 0)

lowConfidence = top1Evidence < minConfidence
closeScores = 候选数 >= 2 且 (第一名分数 - 第二名分数) / max(1, abs(第一名分数)) < minScoreGap
```

证据只按排序第一名 Skill 与问题的实际交集计算，不能用候选池中其他 Skill 的命中抬高第一名置信度。初始 `minConfidence=0.40`：命中指标时使用 Top-5；命中“主题+关键词”时也使用 Top-5；仅主题、仅关键词或没有结构化证据时扩展到 Top-10。该值必须通过离线评测校准，并分别统计 Top-5 与 Top-10 请求占比、Recall 和上下文字符数；若多数请求长期落入 Top-10，应优先补齐标签/关键词或调整证据模型，不能只提高候选上限。数据源不参与 Skill 路由证据、分数或置信度；工具执行前的数据源合法性校验仍按既有执行器规则执行。

`lowConfidence` 或 `closeScores` 任一成立时取前 `fallbackK`；否则取前 `K`。候选池为空时始终返回空，不触发回退。回退只在已经通过可使用范围、领域、主题和指标筛选的最终候选池中扩容，不能重新加入任一前置阶段排除的 Skill。

**伪代码：**

```java
eligible = activeMetadata
    .filter(m -> usableSkillNames.contains(m.skillName()))
    .filter(m -> allSkillNames.contains(m.skillName()));

explicit = findExplicitCanonicalNames(question, eligible);
remaining = eligible - explicit;

domainPool = applyExistingDomainGate(question, remaining);
if (domainPool.isEmpty() && explicit.isEmpty()) return empty("domain_no_match");

topicPool = narrowByMatchedDictionaryTags(domainPool, question, TOPIC);
if (topicPool.isEmpty() && explicit.isEmpty()) return empty("topic_no_match");

metricPool = narrowByMatchedDictionaryTags(topicPool, question, METRIC);
if (metricPool.isEmpty() && explicit.isEmpty()) return empty("metric_no_match");

ranked = stableSort(metricPool, scoreIncludingCapabilityBoost);
if (!explicit.isEmpty()) {
    explicit = explicit.take(fallbackK);
    return explicit.size() >= K
        ? result(explicit, explicitTruncated)
        : result(concat(explicit, ranked.take(K - explicit.size())), explicitTruncated);
}

limit = shouldExpand(ranked, top1Evidence) ? fallbackK : K;
return result(ranked.take(limit));
```

`narrowByMatchedDictionaryTags` 在该层没有问题命中标签时返回原池；只有“命中了标签但交集为空”时才返回空池。`applyExistingDomainGate` 必须复用当前领域隔离实现，不能被这个通用辅助方法改写。没有路由元数据、`active=false` 或尚未完成词典标签确认的 Skill 不进入严格路由候选；feature flag 关闭时仍沿用旧链路，二者不能混用。

**当前实现差异（2026-09-03）：** 以下是实施本算法前必须消除的差异，不能将当前代码行为视为上述目标算法已经生效。

| 当前实现 | 目标算法 |
|---|---|
| `SkillRoutingMetadata` 和 Repository 仍读写 `aliases`，显式匹配仍使用别名 | 服务端、接口和运行时停止读写/匹配别名，只匹配规范 Skill 名称/目录名 |
| 尚无 `topic_tags` 字段参与 Skill 候选选择 | 在领域门控之后增加 TOPIC 筛选，再执行 METRIC 筛选 |
| 当前得分为 `priority + 每个命中项 25 分`，置信度为 `第一名分数 / 100` | 使用本节固定分项权重、证据置信度和稳定同分排序 |
| Capability 召回非空时直接把候选硬过滤为召回 Skill | Capability 只提供 `capabilityScore`，不得删除标签候选 |
| 无路由元数据的 Skill 会被追加到模型可见集合，空选择会返回整个领域门控集合 | 严格路由开启后仅允许已确认且 `active=true` 的元数据进入候选，空结果保持为空 |
| 当前运行配置为正常 `50`、回退 `100` | 完成元数据覆盖和离线评测后切换为正常 `5`、回退 `10` |
| `SkillCandidateSelection` 只返回名称和三个布尔值 | 增加分项得分、命中标签及空结果原因，供 Trace 和评测使用 |

**附加实现约束：**

1. `short_summary` 不准确时不得直接放行错误执行。模型准备发起工具调用时，服务端根据原始 Skill、工具参数、目标数据源和用户权限执行二次校验；校验不通过时禁止执行，并返回简短纠正信息。连续校验失败的 Skill 自动标记待修订。
2. 记录结构化审计信息：命中的领域、业务主题和指标标签，候选 Skill、每项评分、最终选择、是否命中强制规则、是否触发回退、空结果原因、摘要校验和二次校验结果。日志和指标不得保存完整用户问题或业务结果正文。
3. 建立离线评测集，格式为“问题 -> 预期 Skill 集合”。影子阶段准入要求 `Recall@5 >= 95%` 且所有错误样本可追溯；小流量灰度要求 `Recall@5 >= 97%` 且无 usage/领域越权；全量生产门槛为 `Recall@5 >= 99%`。低置信度样本必须扩展到领域门控后的 Top-10，不允许静默漏掉正确 Skill。

**配置：**

```properties
harness.a2a.skill-context.max-visible-skills=5
# 低置信度时的候选总数上限，包含全部候选，不额外追加通用 Skill
harness.a2a.skill-context.fallback-visible-skills=10
harness.a2a.skill-context.min-confidence=0.40
harness.a2a.skill-context.min-score-gap=0.10
harness.a2a.skill-context.enforce-explicit-name=true
harness.a2a.skill-context.enforce-domain-coverage=true
harness.a2a.skill-context.route-validation.enabled=true
```

**验收：**

- 显式写出 `q2_1_by_dept_version_metrics` 时，它总在候选集内且排在首位；
- 包含“例会材料”的问题只出现领域标签为“例会材料”的 Skill；不包含该词的问题不出现此领域 Skill；
- 配置了其他领域标签的 Skill 也只有在问题包含对应领域关键词时才进入候选，业务主题和指标均不能绕过领域门控；
- 显式指定当前可使用的例会 Skill 时，即使问题不含“例会材料”，该 Skill 仍排在首位；
- 问题包含 `Q2-1`、`达标率`、`7月版` 等已登记关键词或指标标签时，对应质量指标 Skill 出现在 Top-K；
- 低置信度问题会扩容到总数 10 个实际业务 Skill，不包含额外通用路由 Skill；
- 影子评测 `Recall@5 >= 95%`、小流量灰度 `Recall@5 >= 97%`，全量生产前达到 `Recall@5 >= 99%`；
- 模型调用不匹配工具时，服务端拒绝执行并给出正确的短摘要；
- 所有候选条目仍只包含名称、短描述和结构化摘要，绝不包含完整 `SKILL.md`。

### Task 3.3：Capability Router（替代单层全量 Skill 筛选）

**当前状态：基础链路已实现，默认关闭，但尚未符合本节目标算法。** 当前代码仍把 Capability 召回结果作为硬过滤，并且 `SkillCandidateSelector` 尚未支持 `topic_tags`、新评分公式和无别名匹配。能力登记数据未补齐且这些差异未完成前，必须保持 `harness.a2a.capability-routing.enabled=false`；启用前先完成 Task 3.1 的可使用范围统一，再完成 Top-10 Skill 登记和离线评测。

本任务将 Task 3.2 的 `SkillCandidateSelector` 定位为确定性筛选与重排器；Capability Router 只向它提供可选排序信号，不单独决定最终候选集合。

**文件：**

- Create: `src/main/resources/db/migration/gauss/V20260827.2__capability_routing.sql`
- Create: `src/main/java/com/agentscopea2a/v2/capability/CapabilityMetadata.java`
- Create: `src/main/java/com/agentscopea2a/v2/capability/CapabilityRepository.java`
- Create: `src/main/java/com/agentscopea2a/v2/capability/CapabilityRouter.java`
- Modify: `src/main/java/com/agentscopea2a/v2/skills/SkillVectorIndexVisibilityFilter.java`
- Modify: `src/main/java/com/agentscopea2a/v2/skills/SkillCandidateSelector.java`
- Modify: `src/main/java/com/agentscopea2a/v2/skillManager/service/SkillManageBridge.java`
- Test: `src/test/java/com/agentscopea2a/v2/capability/CapabilityRouterTest.java`

**执行流程：**

```text
usableSkillNames
  -> 明确 Skill 名称（仅在可使用范围匹配）
  -> 强制命中 Skill
否则
  -> 现有领域标签硬门控
  -> SkillCandidateSelector 按业务主题、指标筛选
  -> capability_registry 召回 Top-3 能力并展开绑定 Skill（只生成 capabilityScore）
  -> SkillCandidateSelector 对标签候选池混合重排
  -> 正常 Top-5；低置信度回退到可使用实际业务 Skill 的 Top-10
  -> 模型看到 Skill 摘要；工具继续使用当前 Agent/Subagent 通用工具集
```

**验收：**

- 1000 个 Skill 时只对通过可使用范围和标签门控的元数据做轻量字符串评分，不把全量 Skill 摘要发送给模型；
- 显式 Skill 名称总能绕过能力分类并命中；
- 显式名称仅在当前用户可使用范围内绕过能力分类；
- 一个 Skill 可绑定多个能力；
- 能力表或绑定更新后，无需发布 Java 代码即可生效；
- Capability 召回错误不会删除已通过领域、主题和指标筛选的候选，只影响 `capabilityScore`；
- 低置信度回退仍只包含实际 active Skill，总数不超过 10；
- Capability Router 不要求配置或筛选每个 Skill 的工具白名单；工具注册仍由现有 Agent/Subagent 配置决定。

### Task 4：大工具结果统一 artifact handoff

**当前状态：仅有基础能力，尚未达到本任务完成标准。** 当前 `compactLatestToolResult` 能把一个超限结果替换为 artifact 路径，但返回内容尚未包含本节要求的 `artifactId/contentType/rows/columns/preview`，并且从尾部扫描时遇到第一个未超限的 artifact-tool 结果会提前返回，无法保证更早的大结果被处理。完成 Task 4 必须补齐结构化 handoff 契约和全部历史 artifact-tool 结果扫描测试。

**文件：**

- Modify: `analysis-project/src/main/java/com/agentscopea2a/v2/hooks/ArtifactHandoffHook.java`
- Modify: `analysis-project/src/main/java/com/agentscopea2a/v2/artifact/ArtifactStore.java`（仅补充已有接口能力时）
- Modify: `analysis-project/src/main/java/com/agentscopea2a/v2/hooks/ToolCallTrackingHook.java`
- Test: `analysis-project/src/test/java/com/agentscopea2a/v2/hooks/ArtifactHandoffHookTest.java`

**实现要求：**

1. 对 `sql_registry_exec`、`wide_table_query`、`python_exec` 等大结果，先保存完整 stdout/结构化数据到当前用户和会话隔离的 artifact。
2. 返回给模型的内容统一为：

```text
[大结果已保存]
artifactId=<id>
contentType=<json|csv|text>
rows=<数量或未知>
columns=<列名列表或未知>
preview=<受限预览>
读取方式：使用已有 artifact 工具按需读取，不要把完整结果复制回上下文。
```

3. 预览有硬上限，例如 100 行且不超过 4,000 字符；在行边界截断。
4. 对 `script_exec`，artifact 保存完整脚本输出，但 `script_output` 事件仍只抽取 `echarts`、`echart`、`html`、`htm` fenced block。
5. 失败时返回可重试的短错误，不保存密码、API key 等敏感信息；日志只记录 artifactId 和大小。

**验收：** 10 万字符工具结果进入模型时只出现短 handoff；同一请求中“最后一个结果很小、倒数第二个结果很大”时，倒数第二个结果也被替换；artifact 可按会话读取；ECharts/HTML 前端事件不受影响。

### Task 5：增加模型调用前总预算 middleware

**当前状态：已实现基础版本，但存在上线前阻断差异。** `ContextBudgetMiddleware` 已接入 AgentScope middleware 列表：估算输入达到 `warn-ratio`（默认约 40K）时先压缩已消费工具结果；仍达到硬预算（默认 50K）时仅在本次模型调用副本中保留首条 system 消息、压缩标记和最近 6 条消息，仍超限则返回 `CONTEXT_BUDGET_EXCEEDED`。当前 `ContextSizeEstimator` 和 middleware 内部的最新工具结果估算仍使用统一 `chars / 4`，必须先改为 Task 1 的 Unicode 分段估算；当前 `reserve-output-tokens` 也尚未参与有效预算计算。Memory 中的原始消息不修改，LLM 最终答案不处理。AgentScope 自带 compaction 的 `triggerMessages`/`keepMessages` 已改为配置项。

当前版本尚未接入 provider-specific tokenizer，也未对 system prompt/schema 做自动重写；这两类内容若单独超过预算会明确失败，不会静默截断。provider-specific tokenizer 未接入前必须采用保守估算，不能以当前估算值直接证明网关一定可接受。

**文件：**

- Create: `analysis-project/src/main/java/com/agentscopea2a/v2/middleware/ContextBudgetMiddleware.java`
- Modify: `analysis-project/src/main/java/com/agentscopea2a/v2/config/V2InfraConfig.java`
- Modify: `analysis-project/src/main/java/com/agentscopea2a/v2/runner/HarnessA2aRunnerV2.java`
- Modify: `analysis-project/src/main/resources/application.properties`
- Test: `analysis-project/src/test/java/com/agentscopea2a/v2/middleware/ContextBudgetMiddlewareTest.java`

**实现顺序：**

1. 在 `onReasoning` 入口用 `ContextSizeEstimator` 计算快照。
2. 计算有效输入预算：`effectiveInputBudget = min(maxInputTokens, modelContextTokens - reserveOutputTokens)`；`modelContextTokens` 必须大于 `reserveOutputTokens`。后续 warn/hard 比例全部相对于 `effectiveInputBudget`，不能只看 `maxInputTokens`。
3. 小于 `warn-ratio`：直接调用 `next.apply(input)`。
4. 达到 `warn-ratio`：先对全部历史工具结果执行 Task 2 的结构化压缩，再重新估算。
5. 仍超过预算：调用 AgentScope 已有 Compaction；同时将 `triggerMessages`、`keepMessages` 从 Java 常量改为配置：

```properties
harness.a2a.compaction.trigger-messages=16
harness.a2a.compaction.keep-messages=6
```

6. 达到 `hard-ratio`：只允许携带用户目标、当前 Skill 硬规则摘要、最近工具调用和最近结果摘要；删除旧的思考文本和已消费工具正文。
7. 仍然超限：
   - 若超限来源是单个工具结果，执行 Task 4 handoff 后重算；
   - 若超限来源是系统提示词或工具 schema，记录告警并返回 `CONTEXT_BUDGET_EXCEEDED`，不向模型发送不完整请求。
8. middleware 必须返回新的 `ReasoningInput`，不得修改 Memory 中保存的原始消息；压缩只作用于当前模型调用的副本。
9. 不处理 LLM 最终答案文本，不去重、不删除模型输出。该方案只治理“发送给模型之前的输入上下文”。

建议 middleware 顺序：

```text
ContextBudgetMiddleware
  -> ToolResultTruncationMiddleware
  -> AgentScope CompactionMiddleware
  -> reasoning/model call
```

如果 AgentScope 的实际 middleware 顺序不同，应通过单元测试确认“预算检查发生在模型调用前”，不能只依赖 Bean 声明顺序。

### Task 6：增加端到端验证与监控

**文件：**

- Create: `analysis-project/src/test/java/com/agentscopea2a/v2/context/ContextBudgetIntegrationTest.java`
- Modify: `analysis-project/src/main/java/com/agentscopea2a/v2/trace/` 下现有 trace 指标写入点
- Modify: `analysis-project/src/main/resources/application.properties`

**测试场景：**

1. 小请求：system + 1 条 user 消息，输入不被改写。
2. 路由元数据：`active` Skill 的能力、短摘要、关键词、领域标签、业务主题和指标标签均从路由登记表读取；改名/停用后不存在孤儿行。
3. 多 Skill 请求：加载 30 个 Skill 描述时只保留 Top-K 名称和短描述；任何 Skill 都不向模型返回完整正文。
4. 显式 Skill 名称：用户明确写出 Skill 名称时，该 Skill 强制进入候选集首位。
5. 标签与关键词：`Q2-1`、`达标率`、`7月版` 等规范指标标签或关键词命中正确 Skill；业务主题只在领域门控后参与筛选，评测按影子 `95%`、灰度 `97%`、全量生产 `99%` 的 `Recall@5` 门槛分阶段推进。
6. 例会领域：含“例会材料”时只保留该领域 Skill，不含时排除该领域 Skill；显式 Skill 名称优先。
7. 低置信度：候选不足或分数接近时只在领域门控后的候选池扩容到最多 10 个实际业务 Skill，不额外追加通用路由 Skill。
8. 多轮工具请求：连续 25 个 tool call，旧结果被压缩，最近 6 条保留。
9. 大 CSV/JSON：完整内容进入 artifact，模型只看到 handoff。
10. 最新 ECharts/HTML：模型上下文收到摘要，SSE `script_output` 收到完整两个 fenced block。
11. 无 ECharts/HTML：不产生 `script_output`，不影响 LLM 原始最终文本。
12. 历史案例检索：聊天 system prompt 中不出现历史案例；数据库原始记忆不被删除。
13. 用户记忆和维度状态：只保留最近/高优先级内容，分别不超过配置字符预算。
14. 超大 system/tool schema：优先移除历史案例、压缩 Skill 摘要、旧工具结果和旧过程文本，仍超预算时返回明确错误并记录指标。
15. `/ai/chat` 和 `/v2/ai/chat`：分别验证流式事件、最终文本和上下文预算日志。
16. 输出预留：配置 `model-context-tokens=32000`、`reserve-output-tokens=8000`、`max-input-tokens=50000` 时，有效输入预算必须为 24K；达到其 warn/hard 阈值时触发压缩/保护，不能继续按 50K 判断。
17. usage 兜底：上游传入含不可使用 Skill 的 `allSkills` 时，filter 仍会与 `usableSkillNames` 求交；用户身份缺失、Resolver 异常或返回空集合时，严格路由返回空候选且不泄露 Skill 名称。
18. 多个 artifact 结果：最后一个 artifact-tool 结果较小、倒数第二个结果超限时，倒数第二个仍被替换为结构化 handoff；一次请求中所有超限历史结果均被扫描。

**建议指标：**

- `agent.context.estimated_input_tokens`
- `agent.context.compaction.count`
- `agent.context.tool_result_compacted.count`
- `agent.context.artifact_handoff.count`
- `agent.context.budget_exceeded.count`
- `agent.context.largest_block_chars`

指标标签只使用接口来源、工具名和结果类型，不使用用户问题、业务数据或完整会话 ID，避免高基数和敏感信息泄漏。

## 5. 配置建议

当前阻断阶段先使用以下开关，避免尚未按本方案实现的代码进入生产路径：

```properties
harness.a2a.context-budget.enabled=false
harness.a2a.skill-context.routing.enabled=false
harness.a2a.capability-routing.enabled=false
```

完成 Task 1/5 的 Unicode 估算与输出预留、Task 3.0 的字段/字典迁移、Task 3.1 的 filter 层 usage 兜底，并通过对应测试后，再使用以下目标配置启用预算保护和 Skill 严格路由；Capability Router 仍单独保持关闭，直到 Task 3.3 评测通过。`min-confidence=0.40` 只能与新的 `top1Evidence` 算法同批启用；`model-context-tokens` 只有在 `ContextBudgetProperties` 和 middleware 已实现 `effectiveInputBudget` 后才会生效，不能单独修改配置。

```properties
harness.a2a.context-budget.enabled=true
harness.a2a.context-budget.max-input-tokens=50000
harness.a2a.context-budget.model-context-tokens=64000
harness.a2a.context-budget.reserve-output-tokens=8000
harness.a2a.context-budget.warn-ratio=0.80
harness.a2a.context-budget.hard-ratio=1.00
harness.a2a.context-budget.max-latest-tool-tokens=8000
harness.a2a.compaction.trigger-messages=16
harness.a2a.compaction.keep-messages=6
harness.a2a.skill-context.index-only=true
harness.a2a.skill-context.max-visible-skills=5
# 低置信度时的候选总数上限，包含全部候选，不额外追加通用 Skill
harness.a2a.skill-context.fallback-visible-skills=10
harness.a2a.skill-context.max-description-chars=500
harness.a2a.skill-context.max-summary-chars=3000
harness.a2a.skill-context.min-confidence=0.40
harness.a2a.skill-context.min-score-gap=0.10
harness.a2a.skill-context.enforce-explicit-name=true
harness.a2a.skill-context.enforce-domain-coverage=true
harness.a2a.skill-context.route-validation.enabled=true
harness.a2a.skill-context.routing.enabled=true
harness.a2a.capability-routing.enabled=false
harness.a2a.memory.max-system-prompt-chars=3000
harness.a2a.dimension.max-chars=2000
harness.episodic.retrieval.enabled=false
```

本方案将有效输入硬预算定义为 `min(max-input-tokens, model-context-tokens - reserve-output-tokens)`。示例配置得到 `min(50K, 64K - 8K)=50K`，`warn-ratio=0.80` 表示保守估算达到约 40K 时主动压缩，达到有效输入预算时执行硬保护。模型窗口小于 64K 时必须设置真实 `model-context-tokens`，由系统自动压低有效输入预算；不能只调整文档或依赖人工心算。内网模型还可能因网关、模板或隐藏 system prompt 占用额外空间，应在 `max-input-tokens` 中继续保留实测安全余量。

## 6. 上线顺序与回滚

### 6.1 上线顺序

0. **立即关闭未满足上线闸门的功能。** 在 Unicode token 估算和输出预留生效前设置 `harness.a2a.context-budget.enabled=false`；在 filter 层 `SkillUsageResolver` 兜底完成前设置 `harness.a2a.skill-context.routing.enabled=false`。当前配置若为 `true` 视为已知风险，不作为“基础能力已完成”的证据。
1. 先完成 Task 1 的 Unicode 分段估算及混合文本测试，再接入 Task 5 的 `effectiveInputBudget`，只记录输入规模观察 1 个工作日。
2. 合入 Task 2，先只配置 `load_skill_through_path,sql_list`，并补充“最新结果小、倒数第二个 artifact-tool 结果巨大”的回归测试。
3. 合入 Task 4，补齐结构化 handoff 契约，验证大结果 artifact 隔离、下载/读取权限和多结果全量扫描。
4. **完成 Task 3.1：统一运行时可使用范围解析，并落实 PERSONAL 仅 owner 可使用。** filter 层 Resolver 兜底完成前不得开启 Top-K 或 Capability Router；Skill 广场保留既有可见性逻辑。
5. 合入 Task 3.2，关闭历史案例注入，将 Skill 切换为短摘要，禁止完整正文进入模型上下文；每次筛选都先应用 `usableSkillNames`。先以 `Recall@5 >= 95%` 进入影子评测，再按 `97%/99%` 门槛灰度放量。
6. 合入 Task 3.3：先补齐 Top-10 Skill 的能力登记与绑定，离线评测通过后开启 `capability-routing.enabled`；再逐步覆盖其余 Skill。
7. Task 1/2/4/5 的阻断项和回归测试通过后，再开启上下文硬预算和超限保护。
8. 分别在 `/ai/chat`、`/v2/ai/chat` 进行真实内网回归，不修改前端和 Skill 输出协议。

### 6.2 回滚方式

- `harness.a2a.context-budget.enabled=false`：关闭总预算 middleware，保留原有 AgentScope compaction。
- `harness.a2a.skill-context.index-only=false`：恢复 Skill 目录原有文本；完整 `SKILL.md` 仍不得发送给模型，除非明确回滚本方案。
- `harness.a2a.capability-routing.enabled=false`：关闭能力粗路由，恢复现有 Skill 运行时可使用策略；不删除能力、Skill 或绑定登记数据。
- `harness.episodic.retrieval.enabled=true`：恢复历史案例检索注入（仅在确有业务需要时启用）。
- 将 `harness.a2a.compaction.trigger-messages`、`keep-messages` 恢复到当前 Java 配置的 `20/8`。
- 暂时移除 `sql_registry_exec`、`script_exec` 等 artifact-tools 配置，不删除已经生成的 artifact。

回滚只改变当前模型调用的上下文组装，不删除会话历史、Skill 原文或 artifact 数据。

## 7. 完成标准

方案实施后，以下条件必须同时满足：

1. 正常请求发送给模型的保守估算输入不超过 `effectiveInputBudget`，且 `reserve-output-tokens` 已实际参与预算计算。
2. 每一次路由、Capability 召回、Top-K、低置信度回退和模型上下文注入，都只包含当前用户可使用的 Skill；个人 Skill 仅 owner 可使用。
3. 达到有效输入预算的 `warn-ratio` 时开始压缩，达到 `hard-ratio` 时不会继续把大工具结果原样发送给模型；混合中文输入不再按统一 `chars / 4` 低估。
4. 所有参与路由的 Capability、Skill 和绑定关系均有数据库登记；能力、摘要、关键词、领域、业务主题和指标标签不散落在代码或 Skill 正文。
5. Skill 先经可使用范围和现有领域门控，再依次按业务主题、指标执行筛选和重排；模型只接收 Top-K Skill 的名称、描述和短路由提示，完整执行逻辑仍由服务端按原始 Skill 校验，且不会接收完整 `SKILL.md`。
6. 显式指定的 Skill 必定在当前用户可使用时进入候选集；不可使用的显式名称不泄露存在性；影子/灰度/全量生产分别达到 `Recall@5 >= 95%/97%/99%`，低置信度问题自动扩容到总数 Top-10，且全部候选都是实际业务 Skill。
7. 工具继续使用既有 Agent/Subagent 通用工具集和权限控制，不新增按 Skill 维护的工具配置。
8. 聊天 system prompt 不包含历史案例。
9. ECharts/HTML 的 `script_output` 展示行为与当前一致。
10. LLM 最终回答不被后端做去重、过滤或重写。
11. `/ai/chat` 与 `/v2/ai/chat` 均有独立回归测试。
12. 超预算时系统有可读错误、日志和指标，而不是静默截断或把非法 JSON 发送给模型。

## 8. 风险与处理

| 风险 | 处理方式 |
|---|---|
| 估算 token 与模型真实 tokenizer 有偏差 | CJK 按约 1 token/code point、ASCII 按约 4 字符/token 保守估算，输出预留从模型窗口中扣除；继续预留 15% 至 30% 实测安全空间并记录网关报错，后续可替换为 provider tokenizer |
| 压缩丢掉 Skill 硬规则 | 规则必须用列表/代码块表达；压缩测试逐条断言关键规则存在 |
| Top-K 漏掉正确 Skill | 显式名称强制命中；先执行现有领域门控，再按业务主题、指标标签和关键词重排；低置信度仅在门控后扩容 |
| 候选路由包含个人或其他维度不可使用 Skill | `SkillVectorIndexVisibilityFilter` 自身再次调用 `SkillUsageResolver` 求交，而非只依赖上游；PERSONAL 仅 owner 可使用；空集合不得回退为全量 |
| Skill 广场与对话路由口径不同 | 明确区分“广场可见”和“对话可使用”；前者保留既有实现，后者由 Resolver 统一，避免把广场浏览结果直接注入模型上下文 |
| 例会领域在空候选时被重新放回 | 空候选返回空集合，不得恢复全量；显式 Skill 名称是唯一可绕过该领域门控的路径 |
| 路由摘要不准确 | 摘要只参与候选排序；工具执行前按原始 Skill、参数、数据源和权限二次校验；不匹配时拒绝执行，连续失败自动暂停该 Skill |
| 路由元数据与 Skill 主数据不同步 | 创建、改名、停用、删除均由注册器/桥接服务同步；启动和发布前检查孤儿记录与缺失记录 |
| 历史或最新工具结果太大 | 扫描全部 `artifact-tools` 结果并逐个 handoff，不因最新结果较小而提前返回；不做字符串硬截断 |
| AgentScope middleware 顺序变化 | 增加“模型调用前已压缩”的集成测试和启动日志 |
| artifact 跨租户读取 | 复用现有 `ArtifactContext`，artifact 路径必须包含用户和会话隔离信息 |
| `/ai/chat` 与 `/v2/ai/chat` 行为不一致 | 两个接口各跑 SSE 回归；共享预算 middleware，接口差异只保留各自事件策略 |
| 内网更新 JAR 后配置未生效 | 将配置写入内网实际使用的 `application-dev.properties`/环境变量，并在启动日志打印最终预算参数 |
