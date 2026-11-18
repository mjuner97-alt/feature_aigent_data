# tool_metric_catalog 目录机制解析

> 2026/11/18 整理。对应代码：`v2/middleware/ToolMetricCatalogMiddleware.java`、`v2/toolrouting/ToolRoutingCatalogService.java`、`v2/tools/ToolIndexTool.java`。

## 1. 这是什么

`<tool_metric_catalog>` 是一段**动态渲染进系统提示词**的目录块，内容是当前所有可用工具的「主题 → 指标」完整词表。它是 LLM 做"用户要查的指标是否支持"这一**终局判断**的唯一依据，也是 `tool_index` 三级标签发现协议的准入门。

AGENTS.md 决策树 ②（`AGENTS.md:33-76`）规定：

1. LLM 先对目录块做**纯语义精确匹配**（不调任何工具），判断用户请求的指标是否在目录内；
2. **不在目录内 → 终局结论**，立即回复「当前不支持该指标查询」，禁止再调 `tool_index` / `toolMetaInfo` 下探验证——"目录外的指标在 tool_index 里同样查不到，下探只会浪费多轮工具调用后得到同样的结论"；
3. 在目录内 → 才进入三级下探协议：

```
<tool_metric_catalog> (系统提示词)
   ↓ LLM 选 topicTags
tool_index 第1次调用 → 返回 availableMetricTags
   ↓ LLM 选 metricTags (收窄)
tool_index 第2次调用 → 返回 availableDimensionTags
   ↓ (需收窄时) 选 dimensionTags / toolTypes
toolMetaInfo 拿参数 schema → 按 executeWith 调执行器
   API→router_tool | SQL→sql_registry_exec | SCRIPT→script_exec
```

设计动机（2026/09/10 定稿）：把"目录渲染主题→指标词表"和"判定终局化"绑定。之前目录只有主题列表，LLM 会以"目录里没有就去 tool_index 碰碰运气"的方式空转多轮；目录升格为指标级词表 + 判定不可推翻后，这些轮次被整体砍掉。

## 2. 目录怎么生成

### 2.1 数据源：ToolRoutingCatalogService.snapshot()

`ToolRoutingCatalogService.buildSnapshot()`（`ToolRoutingCatalogService.java`）每次调用实时构建：

1. 从 `ToolRoutingMetadataRepository.findEnabled()` 取启用路由元数据，再经 `ToolRoutingAvailabilityResolver.isAvailable` 过滤（**enabled 只是目录可见性，真实对象可用才算**）；
2. 同 toolId 双启用时按 priority 取高者去重（防御直改 DB 造成的寻址歧义）；
3. `canonicalize`：工具的 topic/metric/dimension 标签做 trim + 小写键去重，**保留元数据原始写法**，不做词袋过滤（2026/11/18 改：此前要过 `tool_route_tag_dictionary` 字典过滤，后端直插元数据的新标签会被静默丢弃，见 §7）；
4. 过滤掉无 topicTags 或无 metricTags 的工具；
5. 汇总出 `visibleTopics / visibleMetrics / visibleDimensions`，连同工具列表组成不可变 `ToolRoutingCatalog`。

> 词袋表 `tool_route_tag_dictionary` 的角色收窄为：前端配置页候选下拉 + 应用内 upsert 保存路径的 typo 校验（`ToolRoutingMetadataRepository.upsert` 的 `validateEnabled`）。词袋 disabled 开关不再能抑制目录标签。

### 2.2 渲染：ToolMetricCatalogMiddleware

`ToolMetricCatalogMiddleware.onSystemPrompt()`（`ToolMetricCatalogMiddleware.java:27-52`）在每次系统提示词组装时，把 `systemPrompt + "\n\n" + renderCatalog()` 追加返回。

`renderCatalog()` 逻辑：

- 遍历 catalog.tools()，按 topic 聚合其 metricTags（`TreeMap<String, TreeSet<String>>`，天然去重排序）；
- 渲染为：
  ```
  <tool_metric_catalog>
  可查询业务主题与指标（完整清单，目录外判断的唯一依据，判断时不需要调用任何工具）：
  - 主题A：指标1、指标2
  - 主题B：指标3
  </tool_metric_catalog>
  ```
- **超长降级**：块超过 `maxChars`（配置 `harness.a2a.tool-routing.metric-catalog-max-chars`，默认 8000）时，退化为 `renderTopicsOnly()` 只渲染主题名列表——保证提示词预算可控，代价是指标级精确判断退化为主题级；
- 任何异常（如 snapshot 抛错）返回兜底文案「工具主题目录暂不可用，请勿猜测工具 ID」，不让 agent 带着空目录跑。

## 3. 装配位置

| Agent | 装配方式 | 条件 |
|---|---|---|
| 主 agent | Spring 收集所有 `MiddlewareBase` bean 自动注入中间件列表（`HarnessAgentPartsConfig.java:59-65`） | `harness.a2a.tool-routing.enabled=true` |
| 子 agent | `SubagentRegistrar` 仅对 Toolkit 上有 `tool_index` 的子 agent 追加（`SubagentRegistrar.java:419-421`），旁边一起挂 `DiscoveryStreakResetMiddleware` | 同上开关 |

即：**目录块只发给拥有 tool_index 的 agent**——analyze_data 这类走三级协议的子 agent 有，纯固定流程的子 agent 没有。

## 4. 运行时强制（fail-closed 四层）

提示词约束之外，代码侧有多道兜底，目录外标签在 `ToolIndexTool` 处被硬拒（`ToolIndexTool.java:69-120`）：

1. **目录准入**：`tool_index` 的 @Tool description 明确"topicTags/metricTags 必须来自系统提示词 `<tool_metric_catalog>` 中的准确名称；目录外标签会被直接拒绝并要求终止查询"；
2. **unknown 标签拒绝**：请求标签不在 catalog 已知集合时，返回 ⛔ 指令，明确"目录外指标判定为终局……禁止再调用 tool_index、改换其他标签重试、或调用 toolMetaInfo 及任何执行工具"，并给出现成回复话术；
3. **ID 误用引导**：把 toolId 当标签传、或查 Skill 固定隐藏 ID 时，返回定向指引（直接调执行器 / 用 toolMetaInfo），把 LLM 拉回正确路径而非死循环；
4. **发现连击熔断**：连续 `MAX_DISCOVERY_STREAK` 次调 tool_index 而未调执行工具时（`DISCOVERY_STREAK_KEY` 计数，由 `DiscoveryStreakResetMiddleware` 在真正执行工具后清零），返回熔断指令要求立即选定 toolId 执行或终止。

`ToolIndexService` 侧同步打点：unknown topic/metric/dimension 分别记为 `unknown_topic` / `unknown_metric` / `unknown_dimension` 的 emptyResult 指标（`ToolIndexService.java:95-108`），无效调用可观测。

## 5. 同一契约在各提示词中的分布

目录判定规则在三个提示词文件中重复声明，措辞一致（"判定即终局"）：

- `workspace/AGENTS.md`（主管）：决策树 ② + 调用纪律第 1 条；
- `workspace/agent-subagents/analyze_data.md`：统一发现协议 + 发现纪律第 1 条；
- `ToolIndexTool` 的工具 description + 拒绝响应文案（运行时兜底）。

## 6. 关键设计点小结

| 点 | 说明 |
|---|---|
| 唯一真源 | 目录由 `ToolRoutingCatalogService.snapshot()` 单点构建，提示词渲染与 `tool_index` 校验共用同一 catalog 快照，保证"提示词里写的"与"代码校验的"永远一致 |
| 元数据为准 | 标签真源是 `tool_route_metadata`，后端直插的新标签无需登记词袋即可进目录；词袋只服务前端配置页候选下拉与保存路径 typo 校验 |
| 可用性过滤 | enabled + isAvailable 双条件，目录只列真实可查的工具 |
| 预算护栏 | maxChars 8000，超限退化为主题列表；异常退化为"目录不可用"提示 |
| 判定终局化 | 提示词三处声明 + ToolIndexTool unknown 拒绝 + 发现连击熔断，目录外查询不可能再消耗工具轮次 |
| 判定要求"精确匹配" | 词表是"可查询清单"不是"相近清单"，宁可判不支持也不模糊放行（AGENTS.md:39 判断原则 4） |

## 7. 修改记录

- **2026/11/18 目录标签改为元数据为准**：背景是工具标签常由后端直插 `tool_route_metadata`，而词袋表 `tool_route_tag_dictionary` 未必同步，原 `canonicalTags` 的字典过滤会把词袋缺失的标签静默吞掉（目录不渲染 + tool_index unknown 拒绝，标签彻底不可达）。改动：
  - `ToolRoutingCatalogService` 删除对 `ToolRoutingTagDictionary` 的依赖，`canonicalTags` 只做 trim + 小写键去重（保留元数据原始写法）；
  - `V2ToolConfig.toolRoutingCatalogService` 装配去掉字典参数；
  - 词袋角色收窄为前端配置页候选下拉 + 应用内 upsert 的 `validateEnabled` typo 校验；词袋 disabled 不再抑制目录标签；
  - 新增回归测试 `publishesTagsMissingFromTagDictionary`，全量 936 单测过，内网 E2E 待验。
