# Agent 对话上下文压缩与预算治理实施方案

> 文档目的：为内网业务场景控制一次请求发送给 LLM 的上下文规模，在估算上下文达到 50K 前启动压缩，避免一次用户提问因多轮工具调用、Skill、工具定义和系统提示词持续膨胀。
>
> 适用接口：`/ai/chat`、`/v2/ai/chat`。两者都复用 `HarnessA2aRunnerV2` 创建 Agent，因此核心上下文治理应放在 Runner/Agent middleware 层，而不是前端或某一个 Skill 中。

## 1. 目标与边界

### 1.1 目标

1. 在每次模型调用前估算实际输入规模，并在达到约 40K 时按优先级压缩，50K 时执行硬保护。
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

## 3. 目标架构：先按可使用范围隔离，再做数据库驱动的 Capability Routing

现有 `SkillCandidateSelector` 是单层候选筛选器。本方案调整为数据库驱动的分层能力路由，能力分类不写入 Java 常量、`application.properties` 或 Skill 正文。

```text
用户问题 + 当前登录用户
  -> Skill Usage Resolver（可使用范围硬门槛）
  -> 当前用户可使用的 Skill 集合
  -> Capability Discovery（能力发现）
  -> 仅在可使用集合内做 Capability 候选召回
  -> Skill 重排与显式名称优先
  -> 仅暴露当前用户可见 Skill 的短摘要
  -> Agent / Subagent 通用工具集
  -> 按需读取服务端 Skill 规则并执行校验
```

### 3.1 可使用范围是候选路由的前置硬门槛

Skill 广场的“可见”与对话中的“可使用”是不同概念。上下文路由必须以现有的“可使用”口径作为硬门槛，不能在 Top-K 得出结果后再过滤。否则当前用户不能使用的 Skill 名称、摘要、别名、关键词、能力标签或候选数量都会进入模型上下文；显式指定时还可能暴露不应参与当前对话的 Skill。

本方案调整为：每次 `/ai/chat` 和 `/v2/ai/chat` 创建 Agent 前，先依据当前 `userId` 计算 `usableSkillNames`。后续所有步骤只能处理该集合：

```text
所有已登记 Skill
  -> 按当前 userId 执行可使用判定
  -> usableSkillNames
  -> 与 skill_routing_metadata / capability binding 取交集
  -> Capability Router
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
| 个人 Skill | **仅创建者本人** | owner 命中；个人 Skill 不接受跨用户引用作为可使用来源 | 非 owner 永远不进入可使用集合，不因别名命中、低置信度回退或 Capability 绑定而参与路由 |

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

显式写出某个 Skill 名称或别名时，先在 `usableSkillNames` 内匹配：

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
    aliases         JSONB NOT NULL DEFAULT '[]'::jsonb,
    keywords        JSONB NOT NULL DEFAULT '[]'::jsonb,
    domain_tags     JSONB NOT NULL DEFAULT '[]'::jsonb,
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
    FOREIGN KEY (skill_name) REFERENCES skill_index(name),
    FOREIGN KEY (capability_name) REFERENCES capability_registry(capability_name)
);
```

`skill_routing_metadata` 继续保存 Skill 级别的别名、指标标签、数据源标签和短摘要；`capability_registry` 只保存业务能力分类；绑定表表达一个 Skill 可属于多个能力。能力和 Skill 均由登记表维护，页面新增、改名、停用时必须同步维护关联记录。工具不是 Skill 的登记项，继续由 Agent/Subagent 的通用工具集和现有权限机制管理。

### 3.2 运行时准确性措施

1. 首先沿用现有可使用范围，只允许 `usableSkillNames` 中的 Skill 进入任何路由、回退和模型上下文。
2. 用户明确写出当前可使用的 Skill 名称或别名时，跳过“例会材料”领域门控，直接强制命中并排在首位。
3. 未显式指定 Skill 且用户原始需求包含精确关键词“例会材料”时，只保留 `domain_tags` 中完整标签等于“例会材料”的 Skill，再结合指标标签、关键词、别名、数据源标签和优先级评分。
4. 未显式指定 Skill 且用户原始需求不包含“例会材料”时，排除 `domain_tags` 中完整标签等于“例会材料”的 Skill；其余未标记该领域的 Skill 正常参与评分。不能以“不含例会材料”为理由推断或要求填写“非例会材料”标签。
5. “例会材料”使用普通字符串包含判断，不使用正则、分词或语义猜测；领域标签使用归一化后的完整标签相等判断，避免把“非例会材料”误认为“例会材料”。
6. 领域门控后的候选继续使用现有混合评分：别名/关键词、指标标签、其他领域标签、数据源标签和优先级。
7. 低置信度、分数接近或能力分类为空时，只能在领域门控后的当前用户可使用 Skill 中扩展到 Top-10，禁止回退时重新加入已排除的“例会材料”Skill，也不插入“通用 Skill”。
8. 每次 Tool 调用继续执行现有 Agent/Subagent 工具可用性、数据源和参数校验；路由层不增加按 Skill 配置的工具白名单。
9. 记录是否命中“例会材料”、候选 Skill、评分、显式命中、回退原因和校验结果；不记录完整用户问题或业务结果。
10. 数据库元数据按 `updated_at` 缓存，登记表更新后主动失效缓存；启动时检查孤儿绑定和缺失 active 元数据。

其中第 4 条的候选集合定义为：

```text
非例会请求候选集
  = 当前用户可使用的 Skill
  - domain_tags 中包含完整标签“例会材料”的 Skill
```

因此，领域标签为空、未配置领域标签或领域标签为其他值的 Skill 都会保留；同时包含“例会材料”和其他标签的 Skill 仍应排除。只有执行上述差集后确实没有剩余 Skill，才返回空候选集，绝不能将原来的全部可使用 Skill 恢复回来。

| Skill 的 `domain_tags` | 问题不含“例会材料”时 |
|---|---|
| 空或未配置 | 保留 |
| `["质量管理"]` | 保留 |
| `["非例会材料"]` | 保留，但不要求配置该标签 |
| `["例会材料"]` | 排除 |
| `["例会材料", "质量管理"]` | 排除 |

### 3.3 上下文边界

- 模型只看到能力名称/短摘要和 Skill 名称/短摘要，不看到完整 `SKILL.md`。
- 工具继续按当前 Agent/Subagent 的通用工具集暴露；Capability Router 不新增 Skill 到 Tool 的配置关系。
- 完整 Skill 正文、参考文件和大工具结果保留在服务端或 artifact 中，按需由后端读取和校验。
- 历史案例完全不注入 system prompt；本方案不改变 LLM 最终答案文本。

### 3.5 系统工具白名单与业务工具边界（2026-08-28）

为减少固定工具 schema 占用，v2 主 Agent 的 Harness 系统工具采用白名单，当前只保留：

- `agent_spawn`、`agent_send`：启动和通信子智能体；
- `task_output`、`task_list`：查看异步子任务结果和状态；
- `load_skill_through_path`：按需加载 Skill 资源。

以下系统工具从主 Agent 的模型请求中移除：记忆类 `retrieveFromMemory`、`memory_search`、`memory_get`、`recordToMemory`；会话类 `session_search`、`session_list`、`session_history`、`session_save`；文件与命令类 `read_file`、`write_file`、`edit_file`、`grep_files`、`glob_files`、`list_files`、`execute`；任务/管理类 `task_cancel`、`agent_list`、`skill_manage`、`skill_curator`、`propose_skill`、`save_skill`；计划类 `reset_equipped_tools`、`plan_enter`、`plan_write`、`plan_exit`、`todo_write`。

这只是主 Agent 的系统工具边界，不等同于删除 Java Bean 或后台管理能力。v2 业务工具仍按通用工具集独立管理；`wide_table_query`、`clickhouse_query` 继续隐藏，`sql_list`、`script_list` 恢复为可见，和 `python_exec`、`arith`、`sql_registry_exec`、`script_exec`、`tool_router`、`toolMetaInfo` 一样按现有 Skill/Agent 配置使用。

代码实现位于 `v2/runner/HarnessA2aRunnerV2`（构建后清理非白名单系统工具）和 `v2/config/V2ToolConfig`（主 Agent 业务工具注册）。重启后端后生效；验证时查看 `LLM request tools: count=..., names=[...]` 日志，确认最终发送给模型的清单。子 Agent 可按其独立声明保留所需工具，不能将子 Agent 工具数量与主 Agent 混统计。

### 3.4 迁移与兼容

1. 先创建 `capability_registry` 和 `skill_capability_binding`，历史 Skill 默认不自动标记为 active 候选，避免错误路由。
2. 由业务管理员为现有 Top-10 Skill 补齐能力、短摘要、别名和关键词，完成离线问题集评测后再开启路由开关。
3. 在路由开关关闭时，保留当前 Skill 可见性行为；开关开启后按新链路筛选，不改变 Skill 文件内容。
4. `skill_routing_metadata` 作为 Skill 级元数据继续兼容，后续逐步将能力字段迁移到新表，避免一次性改动现有页面和脚本。

启动时 `BuiltinSkillRegistrar` 必须扫描所有 `skills/**/SKILL.md` 并自动补齐缺失的 `skill_index` 与 `skill_routing_metadata` 行：短摘要取 frontmatter 的 `description`（最多 500 字符），别名自动生成目录名、下划线转连字符和去下划线变体，关键词从目录名的稳定片段生成。已存在的管理员登记数据绝不覆盖。只有 Skill frontmatter 明确包含 `capability: <name>` 时才自动创建能力和绑定；未声明能力的 Skill 保持 inactive，等待人工归类。

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
2. 不新增在线依赖。第一版使用确定性的离线估算：ASCII 字符按约 4 字符/token，中文及其他非 ASCII 字符按约 1.5 字符/token，代码块按普通文本统计；估算结果只用于预算决策和日志，不当作计费值。
3. 输出 `ContextSizeSnapshot`，至少包括 `estimatedInputTokens`、`messageCount`、`toolResultChars`、`skillChars`、`largestBlockChars`。
4. `ContextBudgetProperties` 使用 `@ConfigurationProperties(prefix = "harness.a2a.context-budget")`，提供：

```properties
harness.a2a.context-budget.enabled=true
harness.a2a.context-budget.max-input-tokens=50000
harness.a2a.context-budget.reserve-output-tokens=8000
harness.a2a.context-budget.warn-ratio=0.80
harness.a2a.context-budget.hard-ratio=1.00
```

5. 首先只记录指标和日志，不改变请求内容。日志必须包含 `conversationId`、接口来源、消息数、估算 token、最大单块字符数和各来源占比；不得记录完整业务数据。

**验收：** 构造含系统文本、Skill、工具结果和普通消息的 `ReasoningInput`，估算结果稳定；相同输入重复执行结果一致；日志不包含工具结果正文。

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
4. 对非最新的大型结构化结果：优先替换为字段摘要、行数、列名和 artifact 引用，不把完整 CSV/大 JSON 重复注入。
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

Skill 路由相关内容必须落在数据库登记表，而不是写在 `application.properties`、`SKILL.md` 正文或 Java 常量中。现有 `skill_index` 是 Skill 名称、描述、版本和使用统计的索引；`skill_manage.category/tags` 主要用于管理页面且不覆盖全部内置 Skill。因此新增一张与 `skill_index.name` 一对一关联的运行时路由表。

**文件：**

- Create: `analysis-project/src/main/resources/db/migration/gauss/V20260827.1__skill_routing_metadata.sql`
- Create: `analysis-project/src/main/java/com/agentscopea2a/v2/skills/SkillRoutingMetadata.java`
- Create: `analysis-project/src/main/java/com/agentscopea2a/v2/skills/SkillRoutingMetadataRepository.java`
- Modify: `analysis-project/src/main/java/com/agentscopea2a/v2/skills/BuiltinSkillRegistrar.java`
- Modify: `analysis-project/src/main/java/com/agentscopea2a/v2/skillManager/service/SkillManageBridge.java`
- Test: `analysis-project/src/test/java/com/agentscopea2a/v2/skills/SkillRoutingMetadataRepositoryTest.java`

**表结构：**

```sql
CREATE TABLE skill_routing_metadata (
    skill_name        VARCHAR(128) PRIMARY KEY,
    short_summary     VARCHAR(3000) NOT NULL,
    aliases           JSONB NOT NULL DEFAULT '[]'::jsonb,
    keywords          JSONB NOT NULL DEFAULT '[]'::jsonb,
    metric_tags       JSONB NOT NULL DEFAULT '[]'::jsonb,
    domain_tags       JSONB NOT NULL DEFAULT '[]'::jsonb,
    data_source_tags  JSONB NOT NULL DEFAULT '[]'::jsonb,
    priority          INTEGER NOT NULL DEFAULT 0,
    active            BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at        TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT fk_skill_routing_metadata_skill
        FOREIGN KEY (skill_name) REFERENCES skill_index(name)
);

CREATE INDEX idx_skill_routing_metadata_active
    ON skill_routing_metadata(active, priority DESC);
```

字段职责：

| 字段 | 用途 |
|---|---|
| `short_summary` | 给候选路由使用的一两句适用场景提示，不承担完整执行逻辑 |
| `aliases` | 显式名称/目录名/业务别名的强制命中，如 `q2_1`、`q2-1`、`Q2-1` |
| `keywords` | 业务关键词，如“达标率”“打分率”“版本” |
| `metric_tags` | 指标匹配，如 `q2_1`、`pass_rate` |
| `domain_tags` | 领域匹配，如 `quality_metrics`、`trace_analysis` |
| `data_source_tags` | 数据源匹配和执行前校验 |
| `priority` | 同分候选的稳定排序；数值越高优先级越高 |
| `active` | 是否参与候选筛选和工具校验 |

不得增加 `generic_fallback` 字段：低置信度时只是将实际业务 Skill 的候选总量扩大到 Top-10，不存在额外通用 Skill。

**实现要求：**

1. `BuiltinSkillRegistrar` 在新内置 Skill 注册到 `skill_index` 时，同步建立路由元数据行；初始 `short_summary` 自动取受限描述，数组字段自动生成可确认的初稿，待管理员仅修正明显错误并补充必要别名、关键词和标签。
2. `SkillManageBridge` 创建、改名、禁用或删除页面 Skill 时，同步创建、重命名、停用或删除对应路由元数据，避免 `skill_index` 与路由表不一致。
3. 运行时只读取 `active=true` 的元数据，并按 `updated_at` 缓存；更新后必须失效缓存，不能等待进程重启。
4. 历史已有 Skill 必须通过迁移初始化对应的路由行。管理员不需要重写复杂 Skill，只需确认自动初稿并补充高价值别名、关键词和标签；未确认的行不得标记为可用于生产候选。
5. `short_summary` 由登记表维护，仅作为候选提示；服务端读取原始 Skill 进行最终校验，摘要缺失、过期或与原文冲突时标记待修订并暂停生产路由，不能把全文回传给模型。

**全局配置边界：**

以下是系统级策略，应继续保留在 `application.properties`，不存入单条 Skill 记录：

```properties
harness.a2a.skill-context.max-visible-skills=5
harness.a2a.skill-context.fallback-visible-skills=10
harness.a2a.skill-context.min-confidence=0.65
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
- Modify: `src/main/java/com/agentscopea2a/v2/skills/SkillRoutingMetadataController.java`
- Modify: `src/main/resources/mybatis/mapper/gauss/SkillMapper.xml`
- Test: `src/test/java/com/agentscopea2a/v2/skills/DatabaseSkillUsageResolverTest.java`

**实现要求：**

1. 按本章 3.2 的规则实现单一 `SkillUsageResolver`，返回当前用户可使用的 retrieval name 集合；该集合只服务对话运行时和路由，不替换 Skill 广场的 `isVisible()`。
2. 系统内置 `workspace/skills` 与 `COMPANY:杭研` 已审批发布 Skill 对所有当前登录用户可使用。
3. `PERSONAL` 仅 owner 可使用。Resolver 和运行时 SQL 必须先识别 `PERSONAL` 并排除跨用户 `skill_reference`；`SkillManageService.isVisible()` 与 `visibleSkillIds` 保持既有广场可见性语义，不在本任务中改变。
4. GROUP、DEPARTMENT、PRODUCT_LINE（功能恢复时）和 VIRTUAL_GROUP 必须复用现有组织/授权数据；不得在 `skill_routing_metadata` 新增冗余的可使用范围列。维度发布只有状态为 `APPROVED` 且目标命中当前用户时才能自动可使用。
5. `DatabaseSkillRepository` 的 `getSkill`、`getAllSkillNames` 和 `getAllSkills` 改用 `SkillUsageResolver` 结果，消除 owner/引用/维度 SQL 口径分散造成的运行时权限漂移。
6. `SkillVectorIndexVisibilityFilter` 在获取 `all` Skill 后立即与 `usableSkillNames` 相交；任何空结果均保持空，不得回退至 `all`。
7. `SkillRoutingMetadataController` 的运行时候选查询通过 `SkillUsageResolver` 过滤；配置页面仍按既有 owner/审批管理权限校验，不能将个人 Skill 配置暴露给非 owner。
8. 系统管理员身份的具体判定复用已有审批人/管理员机制；若当前没有统一管理员概念，第一阶段仅允许 owner 编辑 `skill_manage` 对应配置，内置与杭研级配置沿用当前注册表管理入口的既有授权，不能为了页面方便放开全员编辑。

**验收：**

- 用户 A 的 PERSONAL Skill 不进入用户 B 的候选集、运行时仓储和模型上下文；即使 B 持有历史引用记录也不能使用。Skill 广场仍按既有可见性规则展示。
- 杭研发布 Skill 和内置 `workspace/skills` 对 A/B 均可使用；
- 部门/小组 Skill 只对当前最大版本组织数据中命中的成员自动可使用；
- 私有 USER/GROUP/DEPARTMENT/VIRTUAL_GROUP 授权与现有页面逻辑一致；
- 未授权用户显式输入个人 Skill 名称，响应不泄露其存在；
- 一个请求中所有运行时使用判断来自同一 Resolver，不存在仓储/路由/模型上下文三套不一致规则。

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

1. 先应用现有 `usableSkillNames`；用户问题中明确出现其中某个 Skill 名称、目录名或已登记别名时，强制将该 Skill 放入候选集首位，不受例会领域门控和 `max-visible-skills` 限制影响。
2. 未显式指定 Skill 时先执行例会领域门控：需求包含“例会材料”时只保留领域标签完整等于“例会材料”的 Skill；需求不包含“例会材料”时，从当前用户可使用集合中排除这些 Skill，并保留领域为空或其他领域的 Skill。然后才执行 Capability 召回和 Top-K，不单独维护“非例会材料”标签；仅在差集确实为空时返回空候选。
3. 在领域门控后的候选池中使用混合评分，而不是只使用向量相似度：

```text
finalScore = explicitNameMatch
           + keywordAndAliasMatch
           + metricAndOtherDomainTagMatch
           + dataSourceTagMatch
           + optionalSemanticSimilarity
           + historicalSuccessfulRouteBoost
```

`explicitNameMatch` 为确定性最高优先级。`keywordAndAliasMatch` 覆盖指标简称、部门别名、版本简称、数据源和业务词，例如 `Q2-1`、`达标率`、`7月版`。向量相似度只能作为补充项，不能覆盖明确名称或业务标签匹配。

4. 配置 Skill 的核心业务类别，例如“质量指标查询”“数据查询”“报表渲染”“追踪分析”。在正常的 Top-K 候选外，每个命中的核心类别至少保留一个最高分 Skill，防止同类别候选被其他领域 Skill 挤掉。
5. 当最高候选分数低于置信度阈值，或候选之间分数过于接近时，触发保守回退：只在领域门控后的候选池中从 `5` 扩大到最多 `10`。不得因候选为空恢复全部 Skill，也不得重新加入已被排除的例会材料 Skill。
6. `short_summary` 不准确时不得直接放行错误执行。模型准备发起工具调用时，服务端根据原始 Skill、工具参数、目标数据源和用户权限执行二次校验；校验不通过时禁止执行，并返回简短纠正信息。连续校验失败的 Skill 自动标记待修订。
7. 记录结构化审计信息：是否命中“例会材料”、候选 Skill、每项评分、最终选择、是否命中强制规则、是否触发回退、摘要校验和二次校验结果。日志和指标不得保存完整用户问题或业务结果正文。
8. 建立离线评测集，格式为“问题 -> 预期 Skill 集合”。上线门槛为：正确 Skill 的 `Recall@5 >= 99%`；低置信度样本必须扩展到领域门控后的 Top-10，不允许静默漏掉正确 Skill。

**配置：**

```properties
harness.a2a.skill-context.max-visible-skills=5
# 低置信度时的候选总数上限，包含全部候选，不额外追加通用 Skill
harness.a2a.skill-context.fallback-visible-skills=10
harness.a2a.skill-context.min-confidence=0.65
harness.a2a.skill-context.min-score-gap=0.10
harness.a2a.skill-context.enforce-explicit-name=true
harness.a2a.skill-context.enforce-domain-coverage=true
harness.a2a.skill-context.route-validation.enabled=true
```

**验收：**

- 显式写出 `q2_1_by_dept_version_metrics` 时，它总在候选集内且排在首位；
- 包含“例会材料”的问题只出现领域标签为“例会材料”的 Skill；不包含该词的问题不出现此领域 Skill；
- 显式指定当前可使用的例会 Skill 时，即使问题不含“例会材料”，该 Skill 仍排在首位；
- `Q2-1`、`达标率`、`7月版` 等别名命中时，对应质量指标 Skill 出现在 Top-K；
- 低置信度问题会扩容到总数 10 个实际业务 Skill，不包含额外通用路由 Skill；
- 离线样本集的 `Recall@5` 达到 99% 以上；
- 模型调用不匹配工具时，服务端拒绝执行并给出正确的短摘要；
- 所有候选条目仍只包含名称、短描述和结构化摘要，绝不包含完整 `SKILL.md`。

### Task 3.3：Capability Router（替代单层全量 Skill 筛选）

**当前状态：基础链路已实现，默认关闭。** 已新增数据库迁移、`CapabilityRepository`、`CapabilityRouter`，并接入 `SkillVectorIndexVisibilityFilter`。能力登记数据未补齐前保持 `harness.a2a.capability-routing.enabled=false`；启用前必须先完成 Task 3.1 的可见性统一、再完成 Top-10 Skill 登记和离线评测。

本任务将 Task 3.1 的 `SkillCandidateSelector` 定位为“Skill 重排器”，不再让它直接对所有 Skill 承担完整路由职责。

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
  -> 明确 Skill 名称/别名（仅在可使用范围匹配）
  -> 强制命中 Skill
否则
  -> capability_registry 召回 Top-3 能力
  -> skill_capability_binding 展开可使用的实际业务 Skill（目标约 20）
  -> SkillCandidateSelector 混合重排
  -> 正常 Top-5；低置信度回退到可使用实际业务 Skill 的 Top-10
  -> 模型看到 Skill 摘要；工具继续使用当前 Agent/Subagent 通用工具集
```

**验收：**

- 1000 个 Skill 时，正常请求不需要全量 Skill 参与重排；
- 显式 Skill 名称总能绕过能力分类并命中；
- 显式名称仅在当前用户可使用范围内绕过能力分类；
- 一个 Skill 可绑定多个能力；
- 能力表或绑定更新后，无需发布 Java 代码即可生效；
- 低置信度回退仍只包含实际 active Skill，总数不超过 10；
- Capability Router 不要求配置或筛选每个 Skill 的工具白名单；工具注册仍由现有 Agent/Subagent 配置决定。

### Task 4：大工具结果统一 artifact handoff

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

**验收：** 10 万字符工具结果进入模型时只出现短 handoff；artifact 可按会话读取；ECharts/HTML 前端事件不受影响。

### Task 5：增加模型调用前总预算 middleware

**当前状态：已实现基础版本。** `ContextBudgetMiddleware` 已接入 AgentScope middleware 列表：估算输入达到 `warn-ratio`（默认约 40K）时先压缩已消费工具结果；仍达到硬预算（默认 50K）时仅在本次模型调用副本中保留首条 system 消息、压缩标记和最近 6 条消息，仍超限则返回 `CONTEXT_BUDGET_EXCEEDED`。Memory 中的原始消息不修改，LLM 最终答案不处理。AgentScope 自带 compaction 的 `triggerMessages`/`keepMessages` 已改为配置项。

当前版本尚未接入 provider-specific tokenizer，也未对 system prompt/schema 做自动重写；这两类内容若单独超过预算会明确失败，不会静默截断。

**文件：**

- Create: `analysis-project/src/main/java/com/agentscopea2a/v2/middleware/ContextBudgetMiddleware.java`
- Modify: `analysis-project/src/main/java/com/agentscopea2a/v2/config/V2InfraConfig.java`
- Modify: `analysis-project/src/main/java/com/agentscopea2a/v2/runner/HarnessA2aRunnerV2.java`
- Modify: `analysis-project/src/main/resources/application.properties`
- Test: `analysis-project/src/test/java/com/agentscopea2a/v2/middleware/ContextBudgetMiddlewareTest.java`

**实现顺序：**

1. 在 `onReasoning` 入口用 `ContextSizeEstimator` 计算快照。
2. 小于 `warn-ratio`：直接调用 `next.apply(input)`。
3. 达到 `warn-ratio`：先对已消费工具结果执行 Task 2 的结构化压缩，再重新估算。
4. 仍超过预算：调用 AgentScope 已有 Compaction；同时将 `triggerMessages`、`keepMessages` 从 Java 常量改为配置：

```properties
harness.a2a.compaction.trigger-messages=16
harness.a2a.compaction.keep-messages=6
```

5. 达到 `hard-ratio`：只允许携带用户目标、当前 Skill 硬规则摘要、最近工具调用和最近结果摘要；删除旧的思考文本和已消费工具正文。
6. 仍然超限：
   - 若超限来源是单个工具结果，执行 Task 4 handoff 后重算；
   - 若超限来源是系统提示词或工具 schema，记录告警并返回 `CONTEXT_BUDGET_EXCEEDED`，不向模型发送不完整请求。
7. middleware 必须返回新的 `ReasoningInput`，不得修改 Memory 中保存的原始消息；压缩只作用于当前模型调用的副本。
8. 不处理 LLM 最终答案文本，不去重、不删除模型输出。该方案只治理“发送给模型之前的输入上下文”。

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
2. 路由元数据：`active` Skill 的能力、短摘要、别名、关键词和标签均从路由登记表读取；改名/停用后不存在孤儿行。
3. 多 Skill 请求：加载 30 个 Skill 描述时只保留 Top-K 名称和短描述；任何 Skill 都不向模型返回完整正文。
4. 显式 Skill 名称：用户明确写出 Skill 名称时，该 Skill 强制进入候选集首位。
5. 业务别名：`Q2-1`、`达标率`、`7月版` 等指标/领域别名命中正确 Skill；离线评测 `Recall@5 >= 99%`。
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

**建议指标：**

- `agent.context.estimated_input_tokens`
- `agent.context.compaction.count`
- `agent.context.tool_result_compacted.count`
- `agent.context.artifact_handoff.count`
- `agent.context.budget_exceeded.count`
- `agent.context.largest_block_chars`

指标标签只使用接口来源、工具名和结果类型，不使用用户问题、业务数据或完整会话 ID，避免高基数和敏感信息泄漏。

## 5. 配置建议

第一阶段只启用观测和已验证的工具结果压缩：

```properties
harness.a2a.context-budget.enabled=true
harness.a2a.context-budget.max-input-tokens=50000
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
harness.a2a.skill-context.min-confidence=0.65
harness.a2a.skill-context.min-score-gap=0.10
harness.a2a.skill-context.enforce-explicit-name=true
harness.a2a.skill-context.enforce-domain-coverage=true
harness.a2a.skill-context.route-validation.enabled=true
harness.a2a.memory.max-system-prompt-chars=3000
harness.a2a.dimension.max-chars=2000
harness.episodic.retrieval.enabled=false
```

本方案将 `max-input-tokens=50000` 定义为硬预算，`warn-ratio=0.80` 表示估算达到约 40K 时就开始主动压缩，达到 50K 时执行硬保护。模型实际上下文窗口应至少能容纳 50K 输入加 8K 输出；如果模型窗口小于 64K，应按实际窗口同比下调这两个值。不要直接把配置填到模型宣传的最大窗口，内网模型还可能因为网关、模板或隐藏 system prompt 额外占用空间。

## 6. 上线顺序与回滚

### 6.1 上线顺序

1. 先合入 Task 1，仅记录输入规模，观察 1 个工作日。
2. 合入 Task 2，先只配置 `load_skill_through_path,sql_list`，确认 Skill 规则没有丢失。
3. 合入 Task 4，验证大结果 artifact 隔离和下载/读取权限。
4. **先实施 Task 3.1：统一运行时可使用范围解析，并落实 PERSONAL 仅 owner 可使用。** 未完成前不得开启 Top-K 或 Capability Router；Skill 广场保留既有可见性逻辑。
5. 合入 Task 3.2，关闭历史案例注入，将 Skill 切换为短摘要，禁止完整正文进入模型上下文；每次筛选都先应用 usableSkillNames。
6. 合入 Task 3.3：先补齐 Top-10 Skill 的能力登记与绑定，离线评测通过后开启 `capability-routing.enabled`；再逐步覆盖其余 Skill。
7. 最后开启 Task 5 的硬预算和超限保护。
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

1. 正常请求发送给模型的估算输入不超过配置预算。
2. 每一次路由、Capability 召回、Top-K、低置信度回退和模型上下文注入，都只包含当前用户可使用的 Skill；个人 Skill 仅 owner 可使用。
3. 达到约 40K 时开始压缩，达到 50K 时不会继续把大工具结果原样发送给模型。
4. 所有参与路由的 Capability、Skill 和绑定关系均有数据库登记；能力、别名、摘要和标签不散落在代码或 Skill 正文。
5. Skill 先经可使用范围和例会领域门控，再经 Capability 召回、重排；模型只接收 Top-K Skill 的名称、描述和短路由提示，完整执行逻辑仍由服务端按原始 Skill 校验，且不会接收完整 `SKILL.md`。
6. 显式指定的 Skill 必定在当前用户可使用时进入候选集；不可使用的显式名称不泄露存在性；离线评测 `Recall@5 >= 99%`，低置信度问题自动扩容到总数 Top-10，且全部候选都是实际业务 Skill。
7. 工具继续使用既有 Agent/Subagent 通用工具集和权限控制，不新增按 Skill 维护的工具配置。
8. 聊天 system prompt 不包含历史案例。
9. ECharts/HTML 的 `script_output` 展示行为与当前一致。
10. LLM 最终回答不被后端做去重、过滤或重写。
11. `/ai/chat` 与 `/v2/ai/chat` 均有独立回归测试。
12. 超预算时系统有可读错误、日志和指标，而不是静默截断或把非法 JSON 发送给模型。

## 8. 风险与处理

| 风险 | 处理方式 |
|---|---|
| 估算 token 与模型真实 tokenizer 有偏差 | 预留 15% 至 30% 安全空间，并记录网关实际报错；后续可替换为离线 tokenizer |
| 压缩丢掉 Skill 硬规则 | 规则必须用列表/代码块表达；压缩测试逐条断言关键规则存在 |
| Top-K 漏掉正确 Skill | 显式名称强制命中；先执行例会领域门控，再用关键词/别名/指标标签重排；低置信度仅在门控后扩容 |
| 候选路由包含个人或其他维度不可使用 Skill | 先由 `SkillUsageResolver` 计算可使用集合，再执行 Capability/Top-K；PERSONAL 仅 owner 可使用；空集合不得回退为全量 |
| Skill 广场与对话路由口径不同 | 明确区分“广场可见”和“对话可使用”；前者保留既有实现，后者由 Resolver 统一，避免把广场浏览结果直接注入模型上下文 |
| 例会领域在空候选时被重新放回 | 空候选返回空集合，不得恢复全量；显式 Skill 名称是唯一可绕过该领域门控的路径 |
| 路由摘要不准确 | 摘要只参与候选排序；工具执行前按原始 Skill、参数、数据源和权限二次校验；不匹配时拒绝执行，连续失败自动暂停该 Skill |
| 路由元数据与 Skill 主数据不同步 | 创建、改名、停用、删除均由注册器/桥接服务同步；启动和发布前检查孤儿记录与缺失记录 |
| 最新工具结果太大 | 优先 artifact handoff，不做字符串硬截断 |
| AgentScope middleware 顺序变化 | 增加“模型调用前已压缩”的集成测试和启动日志 |
| artifact 跨租户读取 | 复用现有 `ArtifactContext`，artifact 路径必须包含用户和会话隔离信息 |
| `/ai/chat` 与 `/v2/ai/chat` 行为不一致 | 两个接口各跑 SSE 回归；共享预算 middleware，接口差异只保留各自事件策略 |
| 内网更新 JAR 后配置未生效 | 将配置写入内网实际使用的 `application-dev.properties`/环境变量，并在启动日志打印最终预算参数 |
