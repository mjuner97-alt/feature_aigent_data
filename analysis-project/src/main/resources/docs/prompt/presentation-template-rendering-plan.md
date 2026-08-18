# 数据展示模板化方案（减少 Skill 与 LLM 输出 Token）

> 创建日期：2026-08-18  
> 状态：已实施（支持注册 SQL 直连、resultRef 和 inline）  
> 适用范围：Skill 的 ECharts 图表、复杂 HTML 表格和定时报表

## 1. 结论

不要让 LLM 在 Skill 中保存、在每次调用中理解、在最终回答中生成完整的 ECharts option 或 HTML/CSS。

将展示代码下沉到服务端的**版本化展示模板**；LLM 只负责：

1. 调取数据；
2. 根据固定数据契约传入数值；
3. 输出 1 至 3 句业务结论；
4. 调用 `presentation_render(templateId, payload)` 生成报告 artifact。

`presentation_render` 返回短报告链接和报告标识，而不返回 HTML 或 option 源码。前端通过报告链接在 iframe 或新标签页中展示受控的完整 HTML。这样模型上下文只包含业务数据，不包含数千字符的样式与 JavaScript。

本方案不使用现有 `chart_generate` 作为固定模板报表的实现路径。它仍可保留给真正的临时、自由形式图表；但该工具需要二次调用 LLM 生成 option，无法保证题述颜色、图例、固定目标线、部门顺序等严格契约。

## 2. 现状与问题

当前项目已有三个相关能力，但它们不能解决固定样式报表的 token 问题：

| 现有能力 | 现状 | 不适合的原因 |
|---|---|---|
| `workspace/skills/*/SKILL.md` | Skill 可给出完整 ECharts / HTML 示例 | 完整模板随 Skill 首次加载进入模型上下文，且模型最终仍会重复生成大量代码 |
| `EChartTool.chart_generate` | 将数据交给另一轮 LLM，生成柱状/折线 option | 额外模型调用、通用模板无法保证业务图的精确样式和字段语义 |
| `HtmlReportRenderer` | Skill Job 可把 Markdown 与 `echarts` 围栏转为自包含 HTML | 仍要求上游生成完整 option；仅适用于 Job 报告，不是聊天的通用展示协议 |
| Vue / React 聊天 Markdown | Vue 可透传 HTML；React 默认转义 HTML | 直接让 LLM 返回 HTML 的前端行为不一致，且存在 XSS 与页面性能风险 |

以题述的组合图和 14 列跨列表格为例，静态结构、CSS、option 字段和示例数据远大于实际业务数据。它们不应成为 LLM 的输入或输出。

## 3. 目标与边界

### 3.1 目标

- 固定业务样式的 Skill 首次加载从数千至上万字符降低到约 0.8 至 1.5K 字符。
- 最终 LLM 输出只包含结论和短报告引用，不包含 HTML、CSS、JavaScript 或 ECharts option。
- 图表和表格的部门顺序、标题、颜色、阈值、字段名由代码保证，而非由模型“尽量遵循”。
- Vue、React 和 Skill Job 使用同一份受控 HTML 报告，避免各端重复实现。
- 数据和展示代码严格分离，拒绝任意 HTML/JavaScript 注入。

### 3.2 非目标

- 不替换所有自由探索型图表；临时问题仍可使用 `chart_generate` 或 CSV 下载。
- 不让 LLM 传递 `formatter`、`tooltip`、`style`、`script`、任意 SQL 或任意 HTML。
- 不在本阶段改造已有 `HtmlReportRenderer` 的 Markdown 通用能力；新模板渲染器与其并存。

## 4. 方案总览

```text
用户问题
  -> load_skill_through_path（短 Skill：查询脚本 + 模板 ID + payload 字段）
  -> presentation_render（只传 templateId + 注册 SQL 参数）
  -> PresentationRenderTool（按 ID 查询模板和绑定的 sql_registry 配置）
  -> SqlRegistryExecTool.executeStructured（结构化结果不经过 LLM）
  -> json-envelope-v1（通用解析 variables_json + summary_json，不含业务字段）
  -> 通用变量绑定器（ECharts JSON + HTML 模板渲染）
  -> DownloadContentService（写入完整 HTML）
  -> 短工具结果：reportId + 受控访问 URL
  -> 最终回答：摘要 + 报告链接/嵌入卡片
```

关键原则：完整 HTML 只存在于服务端 artifact，**绝不作为 ToolResult 回传给 LLM**。工具返回最多包含 `reportId`、URL、标题和字节大小。

## 5. 新增组件

建议新建 `com.agentscopea2a.v2.presentation` 包。

| 组件 | 职责 |
|---|---|
| `presentation_template_registry` | GaussDB 模板注册表；保存模板 ID、ECharts JSON、HTML、变量 schema 和启用状态 |
| `PresentationTemplateMapper` | 按 `templateId` 查询启用模板，并提供管理 CRUD |
| `RegisteredPresentationTemplateRenderer` | 校验变量、保持 JSON 类型绑定、渲染 HTML 循环并执行安全检查 |
| `PresentationRenderService` | 查询注册表、调用通用渲染器、持久化报告并生成受控 URL |
| `PresentationRenderTool` | 向 Agent 暴露 `presentation_render` 工具 |
| `PresentationReportController` | `GET /api/presentation/reports/{reportId}`，鉴权后返回 `text/html` |
| `PresentationTemplateController` | `/api/presentation-template-registry`，供用户维护模板 |

模板代码不写入 Skill 或 Java 专属类，而由用户维护在 GaussDB。ECharts 模板必须是合法 JSON；HTML 支持 `{{name}}` 变量、`{{#records}}...{{/records}}` 循环和保留占位符 `{{@echarts}}`。

## 6. 工具契约

### 6.1 工具定义

这里的“通用”指**工具协议和渲染生命周期通用**，不是所有 Skill 共用一套图表或表格样式。每个 Skill 可以注册一个或多个自己的模板；模板 ID 是样式和数据契约的唯一入口。

例如：

| Skill | templateId | 样式归属 |
|---|---|---|
| 版本变更代码行指标 | `delivery-production-change-trend-v1` | 固定组合折线图 + 部门多级表格 |
| 缺陷密度分析 | `defect-density-department-v1` | 横向柱状图 + 统计摘要表 |
| 质量趋势分析 | `quality-quarter-trend-v2` | 多指标趋势图 + 目标区间 |
| 自定义经营报表 | `business-report-custom-v1` | 专属 HTML 页面和图表布局 |

因此调用方只依赖统一接口，具体的 ECharts option、HTML 结构、CSS、字段顺序和校验规则全部封装在对应模板中。新增一个 Skill 的专属样式，只需新增模板注册项，不应把条件分支堆进一个“万能模板”。

```text
presentation_render(
  templateId: string,
  params?: JSON object,
  resultRef?: string,
  variables?: JSON string
) -> {
  "reportId": "pr_xxx",
  "title": "非直接发布交付周至投产周变更代码行占比趋势（杭研）",
  "url": "/api/presentation/reports/pr_xxx",
  "expiresAt": "2026-08-25T10:00:00+08:00"
}
```

Tool description 必须简短并列出模板 ID 与字段链接，不嵌入完整 HTML/option：

```text
执行 GaussDB 中已注册的展示模板。variables 仅可传 variable_schema 规定的数据；
不得传 HTML、CSS、JavaScript、ECharts option 或 URL。返回报告 URL；最终回答用 Markdown 链接引用 URL。
```

### 6.2 通用校验

- `templateId` 必须命中 `presentation_template_registry` 中启用的精确 ID。
- 固定报表优先配置 `data_provider_type=sql`、`data_provider_id=<sql_id>`，调用方只传 `params`。
- 注册 SQL 必须返回恰好一行，列名为 `variables_json` 和 `summary_json`；两列内容均为 JSON 对象。业务计算、排序、补位和格式化由 SQL 完成，Java 不新增 Skill 专用 Adapter。
- 已调用 `sql_registry_exec` 时使用 `referenceOnly=true`，再把短期 `resultRef` 交给渲染工具。
- `variables` 仅保留给小型临时数据，最大 2MB；单份 ECharts/HTML 模板最大 1MB。
- 拒绝 variables 中出现未声明字段，避免模型把代码塞入数据。
- 所有字符串在 HTML 输出前转义；数值以 Jackson 的数值节点读取，拒绝字符串拼接的 JavaScript。
- 报告归属 `userId` 和 `conversationId`，报告读取接口必须校验所属用户；不可把本地 artifact 绝对路径暴露给客户端。
- 模板自己构造 ECharts option，以 JSON 序列化注入 `<script type="application/json">`，不要拼接可执行 JavaScript 字符串。

## 7. 题述模板设计

### 7.1 模板 ID 与数据契约

模板 ID：`delivery-production-change-trend-v1`

LLM 仅传递以下 payload；不传标题样式、颜色、图例、表头 HTML 或 ECharts 配置：

```json
{
  "scope": "杭研",
  "targetPercent": 15.0,
  "trend": [
    {
      "version": "2026年7月份版本",
      "deliveryToProductionPercent": 14.7,
      "deliveryWeekPercent": 7.3,
      "postDeliveryPercent": 7.5
    }
  ],
  "departments": [
    {
      "department": "杭州开发一部",
      "totalLines": 1000,
      "deliveryToProductionChangedLines": 120,
      "deliveryToProductionChangedPercent": 12.0,
      "deliveryToProductionFirstCommitLines": 80,
      "deliveryToProductionFirstCommitPercent": 8.0,
      "deliveryWeekLines": 70,
      "deliveryWeekPercent": 7.0,
      "deliveryWeekFirstCommitLines": 40,
      "deliveryWeekFirstCommitPercent": 4.0,
      "postDeliveryLines": 50,
      "postDeliveryPercent": 5.0,
      "postDeliveryFirstCommitLines": 30,
      "postDeliveryFirstCommitPercent": 3.0
    }
  ]
}
```

数据来源应尽量让预注册 `script_exec` 直接按此 schema 返回 JSON。若现有脚本无法一次得到该结构，新增专用脚本或服务端 assembler；不要把原始大表交给 LLM 再让它计算、排序、改字段名。

### 7.2 模板内固定规则

下列规则写在 `presentation_template_registry` 对应记录中，不出现在 Skill 的大段示例中：

| 规则 | 服务端行为 |
|---|---|
| 图表标题 | 固定为 `非直接发布交付周至投产周变更代码行占比趋势（{scope}）` |
| 折线 | 蓝色实线“交付周至投产周”；橙色虚线“交付周”；绿色虚线“交付后” |
| 目标 | 红色实线 `markLine`；值取 `targetPercent`，默认 15 |
| 字体 | 标题 20px；图例及坐标轴 14px |
| 坐标 | 黑色坐标轴；`2026年7月份版本` 在后端格式化成 `26年7月版` |
| 表格 | 红色表头、多级表头、固定列顺序、数值右对齐、百分比统一两位小数加 `%` |
| 部门顺序 | 严格按 `杭州开发一部、杭州开发二部、杭州开发三部、杭州开发四部、杭州开发五部、杭州服务支持部、杭州技术部、云计算实验室、杭州产品部` 输出；缺失部门补 `-` |
| 合计行 | 服务端从部门明细重新汇总，禁止接受 LLM 传入的“杭研合计”行 |

题述中“杭州服务支持杭州技术部”与示例中的“杭州服务支持部、杭州技术部”存在歧义。实现前需由业务方确认这是一条合并部门名称还是两个部门；建议先采用示例的两个独立部门，并将排序表做成可配置常量。

### 7.3 服务端渲染产物

产物为独立 HTML：内联项目已使用的 ECharts 资源或受控本地静态资源，包含单个 `<div>` 图表和标准 `<table>`。其渲染方式与 `HtmlReportRenderer` 的自包含 Skill Job 报告一致，但数据/option 来自严格模板，而非 LLM 文本。

聊天最终答案只需类似：

```markdown
整体情况：7 月版本杭研交付周至投产周变更代码行占比为 14.7%，较上版本下降 1.9 个百分点，已达到 15% 目标；杭州开发二部为 16.2%，未达标。

[查看趋势图和部门明细报告](/api/presentation/reports/pr_01J...)
```

## 8. Skill 改造示例

现有 `q2_1_by_dept_version_metrics` 与本需求的业务指标不相同，应新增对应 Skill，或在相关指标 Skill 中增加一个简短“展示”章节。不要把题述的大 JSON 和 HTML 样例写回 `SKILL.md`。

建议内容如下（约数百字符）：

```markdown
## 展示输出

当用户要求“非直接发布交付周至投产周变更代码行占比”的趋势图或部门明细时：

1. 调用预注册脚本取得 `delivery-production-change-trend-v1` 所需 JSON；不得自行补算或重排部门。
2. 调用 `presentation_render(templateId="delivery-production-change-trend-v1", variables=<脚本 JSON>)`。
3. 最终回答仅写：整体占比、相对上一版本趋势、是否达到 15% 目标、未达标部门，以及工具返回的报告链接。
4. 不输出 ECharts option、HTML、CSS、JavaScript 或 Markdown 表格全文。
```

可在 `SKILL.md` 增加一个指向开发文档的引用，例如 `展示字段详见 presentation template: delivery-production-change-trend-v1`；不要为方便人读而把完整 schema 复制进每个 Skill。模型需要的 schema 由 `presentation_render` 的工具参数说明提供，或由查询脚本的结构化返回直接保证。

## 9. 前端接入

### 9.1 第一阶段：链接即可用

最终消息中的报告 URL 先以普通 Markdown 链接交付。现有 Vue 与 React 都能安全支持链接，交付风险最低。

### 9.2 第二阶段：报告卡片与内嵌

定义无歧义的短标记，例如：

```text
[[presentation-report:pr_01J...]]
```

前端只识别该标记，并请求元数据接口后渲染“查看报告”卡片。点击后使用受限 iframe 打开报告 URL；不要解析或执行模型返回的任意 HTML。Vue 的 `Markdown.vue` 和 React 的 `Markdown.tsx` 应共用这个协议，消除当前 HTML 透传行为差异。

报告 iframe 推荐：

```html
<iframe sandbox="allow-scripts" referrerpolicy="no-referrer"></iframe>
```

报告页 CSP 应至少限制 `default-src 'self'`、禁止远程脚本与网络访问。ECharts 使用本地打包资源，不走 CDN。

## 10. 实施步骤

### P0：建立闭环（3 至 5 个开发日）

1. 定义 `PresentationTemplate`、registry、payload 校验和 artifact metadata。
2. 实现 `delivery-production-change-trend-v1`，用单元测试锁定标题、部门排序、缺省填充、百分比格式、目标线与合计计算。
3. 新增 `presentation_render` 工具和受控报告读取接口。
4. 为业务查询新增或调整预注册脚本，使其直接返回模板 payload。
5. 新建/修改对应 Skill，删去完整 option、HTML 示例，仅保留工作流。
6. 先以 Markdown 链接在两个聊天端验证。

### P1：体验与治理（2 至 3 个开发日）

1. Vue、React 均实现报告卡片和受限 iframe。
2. 加 MySQL 元数据、TTL 清理、按用户/会话鉴权、审计日志与访问指标。
3. 为每个模板记录 `templateId`、版本、渲染耗时、payload 大小、artifact 大小和失败原因。
4. 增加第二个表格/图表模板，验证 registry 不需要修改核心服务。

### P2：逐步迁移（持续）

1. 盘点所有 Skill 中超过 1K 字符的展示代码。
2. 优先迁移高频、强样式约束、固定列/固定图表的场景。
3. 只有用户明确要求自定义视觉规则时，才保留自由图表路径；将结果同样产出为 artifact，不把完整代码回注入对话。

## 11. 验收标准

| 项目 | 验收条件 |
|---|---|
| Token | 迁移后对应 Skill 不含完整 HTML/ECharts 示例；首次加载字节数降低至少 70% |
| 正确性 | 部门排序、缺失补位、合计、百分比、版本简称由单元测试覆盖 |
| 样式 | 图例、配色、虚线、目标线、字体和多级表头与业务确认稿一致 |
| 安全 | payload 不能注入 HTML/JS；跨用户/过期报告访问返回 403/404 |
| 上下文 | `presentation_render` 的 ToolResult 不含 HTML、ECharts option、原始大表或 artifact 本地路径 |
| 兼容性 | Vue、React、Skill Job 都能打开同一报告 URL；无报告时聊天文本仍正常展示 |
| 回归 | 原有 `chart_generate` 与 `HtmlReportRenderer` 继续可用 |

## 12. 风险与决策

| 风险 | 控制方式 |
|---|---|
| 模板数量膨胀 | 仅为固定、重复、高频业务样式建模板；模板 ID 带版本，废弃模板保留兼容期 |
| LLM 组错 payload | 让 `script_exec` 直接返回 payload；工具 schema 做严格校验并给出字段级错误 |
| 报告 URL 泄露 | 使用服务端鉴权、归属校验和过期时间；不要使用可猜测文件路径 |
| ECharts 资源体积 | 静态资源本地复用，HTML 中只引用受控资源；或复用已有 `HtmlReportRenderer` 的内联资源策略 |
| 业务规则变更 | 修改模板版本和脚本，不修改历史报告；由模板测试确保新旧版本可追溯 |

## 13. 与现有代码的衔接点

- `EChartTool`：保留给自由图表；固定报表不调用它，避免二次 LLM 生成 option。
- `HtmlReportRenderer`：可抽取本地 ECharts 资源加载和 HTML 安全骨架；不复用“解析模型输出 echarts 围栏”的入口。
- `ArtifactStore` / `ArtifactIo`：用于隔离存储报告；artifact 路径只在服务端元数据中保存。
- `RedirectController` / 下载服务：可复用其受控下载思路，但报告应以 `text/html` inline 打开而不是附件下载。
- `ToolResultTruncationMiddleware`：不应承担报告代码截断职责；正确做法是工具从源头不返回报告代码。

## 14. 待业务确认

1. “杭州服务支持杭州技术部”是一个名称，还是“杭州服务支持部、杭州技术部”两个部门。
2. “杭研合计”是所有列求和后再计算比例，还是直接使用数据源给出的聚合记录；本方案默认前者。
3. 报告保留时长、是否允许下载 HTML、是否需要导出 Excel/PDF。
4. `targetPercent` 是否始终为 15，或应由版本/指标配置表读取。
