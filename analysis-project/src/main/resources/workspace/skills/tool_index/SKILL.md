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

## 选择规则

- 用户说版本计划、月份版本、`x月份版本`:优先选择 `quality_query_by_version_department` 或 `quality_query_by_version_person`。
- 用户说季度、`x季度`:优先选择 `quality_query_by_department_quarter` 或 `quality_query_by_quarter_person`。
- 只到部门粒度:选择 department 类工具。
- 出现应用、组、产品线、人员:选择 person 类下钻工具。
- 工具 ID 必须完全照抄本技能中的英文字符串,不要自行改名。
