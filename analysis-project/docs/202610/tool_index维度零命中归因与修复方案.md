# tool_index 维度零命中归因与修复方案

- 日期: 2026-10-20
- 状态: 设计中
- 关联: `ToolIndexService` / `ToolIndexTool` / `workspace/AGENTS.md` 三级协议

## 1. 问题现象（内网 trace 2026-10-20 16:43）

用户请求：查询杭州开发二部 2026年10月份版本的未闭环问题明细并导出。

| # | 调用 | 入参要点 | 结果 |
|---|------|---------|------|
| 1 | tool_index | topic=[未闭环问题], metric=[未闭环问题], dim=[部门,版本计划], types=[SQL,API,SCRIPT] | matchedCount=0 |
| 2 | tool_index | topic=[数据明细导出工具], metric=[未闭环问题], dim=[部门,版本计划,明细下载] | matchedCount=0 |
| 3 | toolMetaInfo | toolId="dataExportToolIndex"（幻觉 ID） | 工具不存在或不可用 |
| 4 | tool_index | topic=[数据明细导出工具], metric=[未闭环问题], dim=[明细下载] | matchedCount=0 |
| 5 | tool_index | topic=[数据明细导出工具], 空 metric/dim | 熔断：连续第 4 次发现调用 |

第 5 次被 discovery-streak 熔断拦截后，模型只能按文案回复"不支持该指标查询"——但该指标实际在目录内，属于**误终局**。

## 2. 根因分析

### R1 零命中死在维度交集，而非标签错误（核心）

第 1 次调用 `availableMetricTags` 非空且含"未闭环问题"，说明 topic+metric 两级已匹配到工具。零命中发生在 `ToolIndexService.java:78-80` 的 `supportsAllDimensions`：**没有任何单一工具同时声明 `部门`+`版本计划`**。两个维度分别挂在不同工具上，第 1 次响应的 `availableDimensionTags`（跨工具聚合，`ToolIndexService.java:71-76`）同时列出两者，暗示"可以组合传"，与 supportsAll 的单工具全包含语义矛盾。

### R2 维度语义混淆：查询条件 vs 能力声明

模型把"明细下载"（能力/输出维度）和"部门/版本计划"（业务过滤维度）混在同一 `dimensionTags` 里。具体过滤值（杭州开发二部、2026年10月）应该是执行器 params，不该出现在 tool_index 入参；AGENTS.md 三级协议只写了正向收窄路径，未澄清 dimensionTags 语义，也未写零命中回退路径。

### R3 零命中响应无诊断信息

`matchedCount=0` 时响应只回一张新的聚合词表，不说明"死在哪一级"。模型只能盲猜：换 topic（错）、幻觉 toolId（错）、减维度（还选错了主题）。第 4 次其实方向对了（减到 [明细下载]），但 topic 换成了"数据明细导出工具"——该主题下工具维度是 [人维度,版本计划]，明细下载能力挂在 topic=未闭环问题 那组工具下（第 1 次 availableDimensions 可证），再次零命中。

### R4 熔断文案缺第三分支

`ToolIndexTool.java:116-119` 熔断指令只覆盖两种情形：有候选→去执行；目录外→终局拒绝。漏了本 case：**指标在目录内、但维度组合无单工具交集**。模型被迫套用"目录外"文案，产出错误答复。

### R5 幻觉 toolId 无引导

`toolMetaInfo("dataExportToolIndex")` 返回"工具不存在或不可用"（toolmetainfo_no_row_fallback 兜底已生效），但文案未提示"仅 tool_index 候选或 Skill 正文中的 ID 可查"，模型继续盲试。

## 3. 修复设计

### S1 零命中维度诊断（service 层，核心）

`ToolIndexResponse` 增加诊断字段 `dimensionDiagnosis`，**仅当** topic+metric 匹配非空、dimension 收窄后为空时填充：

```
dimensionDiagnosis: {
  requestedDimensionTags: ["部门","版本计划"],
  candidateDimensionSets: [          // topic+metric 候选各自的维度集合（每工具一行）
    ["明细下载","人维度"],            // 示意：导出类工具
    ["部门"]                          // 示意：投产后未闭环工具
  ],
  hint: "请求的维度组合在单一工具上无交集。建议仅保留任务必需维度（如 明细下载）重试一次；业务过滤值（部门名/版本号）应作为执行器 params 传入，不进 tool_index。"
}
```

计算位置：`ToolIndexService.index()` 中 `matches` 与 `metricAndTypeMatches` 分叉处（`ToolIndexService.java:78-80`），候选维度集合直接取自 metricAndTypeMatches 的 `dimensionTags()`，已现成。

### S2 AGENTS.md 三级协议补丁（prompt 层）

在"在目录内 -> 按三级协议"段落后追加：

1. **dimensionTags 语义**：只传维度标签名；具体过滤值（部门名/版本号/月份）不进 tool_index，是执行器 params。
2. **导出/下载类任务**：dimensionTags 只传 `["明细下载"]`，不与业务过滤维度混传。
3. **零命中回退协议**：`matchedCount=0` 且 unknown 标签为空时，先看 `dimensionDiagnosis.candidateDimensionSets`，减掉非必需维度**重试一次**（最多一次）；仍为 0 则停止发现，回复用户"当前维度组合暂不支持该查询"。

### S3 熔断文案补第三分支

`ToolIndexTool.java:115-120` streak 指令增加：

> 若此前每次调用 matchedCount=0 且无 unknown 标签：指标在目录内但维度组合无交集。从最后一次响应的 candidateDimensionSets 中选一个工具，只保留它支持的维度重试一次；仍为 0 则直接回复用户「当前维度组合暂不支持该查询」，禁止继续调用 tool_index。

熔断阈值维持 3 不变（三级协议 + 一次维度回退 = 4 次以内，第 4 次触发熔断时带着回退指引，模型有明确出路）。

### S4 toolMetaInfo 幻觉 ID 文案微调

`ToolMetaInfoTool` 的"工具不存在或不可用"错误后追加一行引导："仅可查询 tool_index 候选或 Skill 正文中指定的 toolId/sqlId/scriptId，不要猜测 ID。"

## 4. 否决的替代方案

- **supportsAll 改 anyMatch**：会把不支持版本计划过滤的工具也返回，用户"2026年10月份版本"条件被静默丢弃，产出错误数据。保留全包含语义，用诊断+回退解决误导问题。
- **service 自动降维重试**：让确定性过滤层猜"哪个维度不重要"越权，维度取舍是业务判断，交给 LLM 带着诊断信息决策。
- **availableDimensionTags 改为分工具展示**：响应膨胀；S1 的 candidateDimensionSets 已按工具给出集合，且只在零命中时出现，零成本。

## 5. 改动清单

| 文件 | 改动 |
|------|------|
| `v2/toolrouting/ToolIndexResponse.java` | 新增 `dimensionDiagnosis` record 字段（requested/candidateDimensionSets/hint） |
| `v2/toolrouting/ToolIndexService.java` | `index()` 中维度收窄分叉处计算诊断，仅在"上一级非空且本级为空"时填充 |
| `v2/tools/ToolIndexTool.java` | streak 熔断文案补第三分支（:115-120） |
| `ToolMetaInfoTool`（幻觉兜底分支） | 错误文案追加引导句 |
| `src/main/resources/workspace/AGENTS.md` | 三级协议追加维度语义澄清 + 零命中回退协议 |
| 测试 | `ToolIndexServiceTest`（诊断字段触发/不触发两分支）、`ToolIndexToolTest`（熔断文案三分支） |

## 6. 验证计划

1. 单测：构造两工具维度不相交的 catalog，断言 matchedCount=0 时 dimensionDiagnosis 非空且集合正确；维度全支持/标签未知时不出现诊断字段。
2. 内网 E2E 重放本 case：期望轨迹为第 1 次零命中（带诊断）→ 第 2 次只传 [明细下载] 命中 → toolMetaInfo 拿参数 → 执行器导出，全程 ≤3 次发现调用，不触发熔断。
3. 回归：目录外指标终局拒绝、Skill 固定 ID 隐藏语义不受影响（现有 580 单测）。
