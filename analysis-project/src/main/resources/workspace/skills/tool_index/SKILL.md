---
name: tool_index
description: 工具索引 - 根据用户意图选择质量查询工具 ID
---

# 工具索引

用于根据用户意图选择正确的 `quality_query_by_*` 工具。工具直接调用，无需路由。

## 固定调用流程

1. 先根据用户意图在本技能中选择一个工具。
2. 直接调用该工具，传入参数。
3. 只使用工具返回的真实结果回答用户，不得编造工具输出。

## 可用工具索引

### quality_query_by_version_department

- 场景:按版本计划/月度版本查询部门质量分,或比较某个版本下各部门质量好坏。
- 适合问题:
  - `2026年4月份版本各部门质量分是多少?`
  - `2026年4月份版本哪个部门质量最差?`
- 关键参数:
  - `version_plan`: 必填,格式如 `2026年4月份版本`。
  - `department`: 可选,如 `杭州开发一部`;不传表示所有部门。

### quality_query_by_department_quarter

- 场景:按季度查询部门质量分,或比较某季度各部门质量好坏。
- 适合问题:
  - `2026年1季度各部门质量分是多少?`
  - `2026年2季度杭州开发五部质量分是多少?`
- 关键参数:
  - `quarter`: 必填,格式如 `2026年1季度`。
  - `department`: 可选,如 `杭州开发五部`;不传表示所有部门。

### quality_query_by_version_person

- 场景:在某个月度版本下,按部门继续下钻到应用、组、产品线或人员。
- 适合问题:
  - `2026年4月份版本杭州开发五部各应用质量分是多少?`
  - `2026年4月份版本杭州开发五部F-CMS应用下各人员质量分是多少?`
- 关键参数:
  - `version_plan`: 必填,格式如 `2026年4月份版本`。
  - `department`: 必填,如 `杭州开发五部`。
  - `peer_type`: 可选,`APPLICATION` / `TEAM` / `PRODUCT_LINE`。
  - `peer_name`: 可选,如 `F-CMS`、`个贷组`、`信贷产品线`。
  - `person`: 可选,人员姓名。

### quality_query_by_quarter_person

- 场景:在某个季度下,按部门继续下钻到应用、组、产品线或人员。
- 适合问题:
  - `2026年1季度杭州开发五部各应用质量分是多少?`
  - `2026年1季度杭州开发五部F-CMS应用下张三质量分是多少?`
- 关键参数:
  - `quarter`: 必填,格式如 `2026年1季度`。
  - `department`: 必填,如 `杭州开发五部`。
  - `peer_type`: 可选,`APPLICATION` / `TEAM` / `PRODUCT_LINE`。
  - `peer_name`: 可选,如 `F-CMS`、`个贷组`、`信贷产品线`。
  - `person`: 可选,人员姓名。

### wide_table_query

- 场景:通用 GaussDB 宽表查询,SELECT 指定字段 + WHERE 等值筛选 -> markdown 表 (>=4 行时自动落 CSV artifact + 预览)。不在 Java 里写业务指标公式,聚合/计数/比率都走 `python_exec` + pandas。
- 适合问题: 基于宽表加工的指标类问题 (完成率/达标率/打分状态/合格率等), 配合 `wide_table_*_metrics` 系列业务 skill 使用。
- 关键参数:
  - `table`: 必填,宽表名 (不含 schema, schema 固定为 remote_app),如 `dsqa_dwd_req_item_app_portrait_wide_inf`。
  - `fields`: 必填,SELECT 字段列表,如 `["projectzh_no","dev_dept","score_status_2_1"]`。
  - `filters`: 可选,WHERE 等值条件 JSON 对象,如 `{"dev_dept":"杭州开发二部","version_plan":"2026年8月份版本"}`。
- 调用方式: **直接调用** `wide_table_query(table=..., fields=..., filters={...})`, 已注册在 analyze_data 子 agent 的 Toolkit 上, 不走 `router_tool`。schema 和 LIMIT 10000 由工具内部硬编码, 不在参数中暴露给 LLM。
- **table/fields/filters 不要自己编**, 从 SkillRetrievalHook 检索到的 `wide_table_*_metrics` skill 里复制。每个 skill 对应一张宽表 + 一组指标, 包含字段映射和 python_exec 模板。

## 选择规则

- 用户说版本计划、月份版本、`x月份版本`:优先选择 `quality_query_by_version_department` 或 `quality_query_by_version_person`。
- 用户说季度、`x季度`:优先选择 `quality_query_by_department_quarter` 或 `quality_query_by_quarter_person`。
- 只到部门粒度:选择 department 类工具。
- 出现应用、组、产品线、人员:选择 person 类下钻工具。
- 用户问 **基于宽表的指标加工** (完成率/达标率/打分状态/合格率/Q2-1/Q2-2/Q3 等):选择 `wide_table_query` (**直接调用**),配合 SkillRetrievalHook 自动检索到的 `wide_table_*_metrics` skill 执行。
- 工具 ID 必须完全照抄本技能中的英文字符串,不要自行改名。
