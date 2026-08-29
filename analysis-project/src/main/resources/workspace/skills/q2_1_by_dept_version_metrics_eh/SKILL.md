---
name: q2_1_by_dept_version_metrics_eh
description: 通过 script_exec 调用预注册 Python 脚本，一次完成部门版本 Q2-1 取数、计算并在聊天页面内联渲染 Markdown、ECharts 和 HTML 报告。
---

# Q2-1 指标查询与内联报告

业务表：GaussDB `remote_app.dsqa_dwd_req_item_app_portrait_wide_inf`

预注册脚本：`q2_1_metrics_by_dept_version`

脚本参数：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `dept` | string | 是 | 开发部门，例如“杭州开发二部” |
| `version` | string | 是 | 完整版本，例如“2026年7月份版本” |

## 工作流

1. 从用户问题提取 `dept` 和 `version`。缺少任一参数时追问，不得默认查全部。
2. 只调用一次 `script_exec`：

```text
script_exec(
  scriptId="q2_1_metrics_by_dept_version",
  params={"dept":"杭州开发二部","version":"2026年7月份版本"}
)
```

3. 成功后，不输出  `echarts` 和 `html` 部分（已将工具结果在前端展示），仅做根据业务知识做简单总结。

## 输出约束

- Python 脚本是数据计算、ECharts option 和 HTML 表格模板的唯一来源。
- 不重新计算百分比，不根据示例补写数据，不让模型重新生成 ECharts 或 HTML。
- 不输出 `script_exec` 的执行标题、exit、elapsed 和 stdout 分隔线、`echarts` 和 `html`


如果工具执行失败、退出码非 0 或 stdout 为空，只返回明确错误，不得构造虚假图表或表格。
