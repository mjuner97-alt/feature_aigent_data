---
name: tool_index
description: 工具索引 — 根据用户意图选择 AgentTools 子工具 ID,再配合 toolMetaInfo/router_tool 调用
---

# 工具索引

用于在子 agent 只拥有 `toolMetaInfo` 和 `router_tool` 两个元工具时,先确定业务子工具 `toolId`。

## 固定调用流程

1. 先根据用户意图在本技能中选择一个 `toolId`。
2. 调用 `toolMetaInfo(toolId="...")` 获取该工具的描述、入参、参数类型、必填项和 `routerExample`。
3. 按元信息组装 JSON 字符串,调用 `router_tool(paramsJson="...")`。
4. 只使用 `router_tool` 的真实返回结果回答用户,不得编造工具输出。

## 可用工具索引

### agent_tools_ping

- 场景:链路自检、健康检查、ping、打招呼回显。
- 必填意图:无。
- 常用参数:
  - `echo`: 可选,需要回显给工具的文本。

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
