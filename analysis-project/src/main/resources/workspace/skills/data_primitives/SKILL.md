---
name: data_primitives
description: 数据计算原语索引 — 5 个 pandas 套路工具,绕过 code_interpreter,通过 tool_router 路由调用
---

# 数据计算原语 (data_primitives)

`analyze_data` 等子 agent **不直接拥有** `data_aggregate`、`data_pivot` 这类工具,而是和 `tool_index`
完全一致的两步式路由:**先用本技能确定 toolId,再 `toolMetaInfo` → `router_tool`**。

代码由 Java 端按模板拼接,LLM 只传结构化参数 —— 完全消除「LLM 写 Python 错」这条故障路径。

## 固定调用流程

1. 在本技能中根据计算需求选一个 `toolId`(`data_aggregate` / `data_top_n` / `data_compare_ratio` / `data_pivot` / `data_distribution`)。
2. 调用 `toolMetaInfo(toolId="...")` 获取参数清单和 `routerExample`。
3. 拼 JSON 字符串调用 `router_tool(paramsJson="...")`。
4. 直接使用 `router_tool` 返回的 markdown 表回答,不得编造数据。

> ⚠️ **csvPath 必须来自工具调用结果中「📦 完整数据已保存为 CSV artifact」段落给的那条路径**。
> 不要手工编造或改写路径(里面带 `<userId>/<taskId>` 前缀,改写会导致权限拦截)。

## 维度说明 — 无任何硬限制

所有以「列名」为参数的字段(`groupByColumns` / `sortByColumn` / `joinKeyColumn` /
`valueColumn` / `indexColumn` / `columnsColumn`)**接受任意 CSV 列名**,
只要 `query_data` 取数时把该维度查出来落到 CSV 里就行。

实际可用维度举例:**部门、应用 (F-CMS/F-Loan/...)、组 (个贷组/...)、产品线 (信贷产品线/...)、人员、版本、季度、需求项**
等,**单维或任意多维组合**均可。

## 可用工具索引

### data_aggregate

- 场景:CSV 数据按一列或多列分组,对一个数值列做聚合 (mean/sum/std/median/count/min/max)。
- 适合问题:
  - `按部门、季度做缺陷密度均值`
  - `各应用 × 各组的缺陷密度标准差`
  - `按人员看缺陷密度中位数`
- 关键参数:
  - `csvPath` — 必填,从 handoff 消息复制完整路径。
  - `groupByColumns` — 必填,JSON 数组。例: `["部门"]` / `["应用","季度"]` / `["产品线","组"]`。
  - `valueColumn` — 必填,要聚合的数值列。
  - `aggFn` — 可选,默认 `mean`。可选: `mean` / `sum` / `std` / `median` / `count` / `min` / `max`。

### data_top_n

- 场景:按某数值列排序,取前 N 行。
- 适合问题:
  - `缺陷密度最高的 5 个部门`
  - `质量分最差的 Top-3 组`
- 关键参数:
  - `csvPath` — 必填。
  - `sortByColumn` — 必填,排序依据数值列。
  - `n` — 可选,默认 5。
  - `ascending` — 可选,默认 false(降序,即数值大的排前面)。

### data_compare_ratio

- 场景:两张 CSV 按 join 键合并,计算同期/同维度的「变化量 + 变化率(%)」。
- 适合问题:
  - `2026Q1 vs 2026Q2 各部门缺陷密度变化率`
  - `4月份 vs 5月份各应用质量分环比`
- 关键参数:
  - `csvPathA` — 必填,基准期 CSV。
  - `csvPathB` — 必填,对比期 CSV。
  - `joinKeyColumn` — 必填,用于 join 的键列。**任意维度都行**:部门、应用、组、产品线、人员…
  - `valueColumn` — 必填,要对比的数值列。
  - `labelA` / `labelB` — 可选,列名标签,如 "2026Q1" / "2026Q2"。

### data_pivot

- 场景:行 × 列 × 值 的二维透视表。
- 适合问题:
  - `部门(行) × 季度(列) 的缺陷密度均值`
  - `应用(行) × 人员(列) 的缺陷数计数`
- 关键参数:
  - `csvPath` — 必填。
  - `indexColumn` — 必填,行索引列。
  - `columnsColumn` — 必填,列名展开来源列。
  - `valueColumn` — 必填,值列。
  - `aggFn` — 可选,默认 `mean`。

### data_distribution

- 场景:单列数值的分布描述统计(count/mean/std/min/p25/p50/p75/max)。
- 适合问题:
  - `缺陷密度的整体分布情况`
  - `质量分的 p50 / p75 分位`
- 关键参数:
  - `csvPath` — 必填。
  - `valueColumn` — 必填,要统计的数值列。

## 选择规则

- 简单 GROUP BY 聚合 → `data_aggregate`。
- 「最高 / 最差 / Top-N」字眼 → `data_top_n`。
- 「同比 / 环比 / 对比 / 变化率」字眼 → `data_compare_ratio`(注意需要两份 CSV)。
- 「行 × 列 二维」、「按 X 和 Y 双维度」字眼 → `data_pivot`。
- 「整体分布 / 分位 / p50 / 标准差描述」字眼 → `data_distribution`。
- 复杂多步骤 / 回归 / 相关系数 / 时序拟合 → **不要用本技能**,派 `code_interpreter` 子 agent。

## 调用示例(router_tool)

```
router_tool(paramsJson="{\"toolId\":\"data_aggregate\",\"csvPath\":\"/workspace/artifacts/alice/task_3f1a/qdq-abc.csv\",\"groupByColumns\":[\"部门\",\"季度\"],\"valueColumn\":\"缺陷密度\",\"aggFn\":\"mean\"}")
```

```
router_tool(paramsJson="{\"toolId\":\"data_compare_ratio\",\"csvPathA\":\"/workspace/artifacts/alice/q1.csv\",\"csvPathB\":\"/workspace/artifacts/alice/q2.csv\",\"joinKeyColumn\":\"应用\",\"valueColumn\":\"缺陷密度\",\"labelA\":\"2026Q1\",\"labelB\":\"2026Q2\"}")
```

## 注意事项

- `toolId` 必须完全照抄本技能中的英文字符串,不要自行改名。
- 列名要和 CSV 表头精确匹配(包括括号全/半角、空格)。
- 一份 CSV 拿不到的维度组合,需要先用 `query_data` 多取几份 CSV,再用 `data_compare_ratio` / `data_pivot` 合并。
