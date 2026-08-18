---
name:  
description: 通过绑定预注册 SQL 的 presentation_render 查询部门和版本 Q2-1 指标，并输出 Markdown 摘要及 ECharts/HTML 报告
---

# Q2-1 部门版本指标报告

## 数据契约

- 数据源：GaussDB `remote_app.dsqa_dwd_req_item_app_portrait_wide_inf`。
- 展示模板：`q2_1_by_dept_version_metrics/report-v1`。
- 模板绑定 SQL：`q2_1_report_by_dept_version`。
- 必填参数只有 `dept` 和 `version`，都必须使用完整名称。
- 任一参数缺失时追问，不得默认部门或版本。

预注册 SQL 直接返回通用展示信封：`variables_json` 包含模板变量，`summary_json` 包含聊天摘要。Q2-1 的百分比计算、趋势数组和固定部门补位都由该 SQL 配置负责；`presentation_render` 不认识任何 Q2-1 专用字段，只负责解析信封、按 `variable_schema` 校验并渲染 ECharts/HTML。不得自行传递明细数组、ECharts option、HTML、CSS 或 JavaScript。

## 调用

单部门单版本只调用一次：

```text
presentation_render(
  templateId="q2_1_by_dept_version_metrics/report-v1",
  params={"dept":"杭州开发二部","version":"2026年7月份版本"}
)
```

工具返回：

```json
{
  "reportId":"pr_xxx",
  "title":"杭州开发二部 2026年7月份版本 Q2-1 指标报告",
  "url":"/api/presentation/reports/pr_xxx",
  "markdownLink":"[杭州开发二部 2026年7月份版本 Q2-1 指标报告](/api/presentation/reports/pr_xxx)",
  "expiresAt":"<过期时间>",
  "summary":{
    "department":"杭州开发二部",
    "version":"2026年7月份版本",
    "total":80,
    "scored":0,
    "passed":80,
    "scoredPctText":"0.00%",
    "passedPctText":"100.00%"
  }
}
```

- 所有数字必须读取本次工具返回的 `summary`，不得使用示例数字，也不需要调用 `arith` 或 `python_exec`。
- ⚠️ **报告链接必须使用工具返回的 `markdownLink` 字段原样输出**，格式为 `[文本](完整url)`；禁止只输出 `url`、裸 URL 或自行改写链接。

## 最终回复

聊天主页面必须保留可直接阅读的数据，依次输出：一句结论、一行 Markdown 数据表、报告链接和数据来源。
报告链接必须原样使用本次 `presentation_render` 返回的 `markdownLink`，不得自行截取 host、改写端口、拼接相对路径或更换链接文本。

```markdown
杭州开发二部 2026年7月份版本 Q2-1 达标率为 **100.00%**。

| 开发部门 | 版本 | 项目总数 | 已打分 | 达标数 | 打分率 | 达标率 |
|---|---|---:|---:|---:|---:|---:|
| 杭州开发二部 | 2026年7月份版本 | 80 | 0 | 80 | 0.00% | 100.00% |

[杭州开发二部 2026年7月份版本 Q2-1 指标报告](http://localhost:5174/api/presentation/reports/pr_xxx)

数据来源：GaussDB `remote_app.dsqa_dwd_req_item_app_portrait_wide_inf`。
```

不得在聊天回答中输出 ECharts/HTML 源码，也不得复制报告页中用于结构补位的空部门行。每次请求只能成功生成一次报告。

如果调用方需要先执行 SQL，必须使用 `sql_registry_exec(sqlId="q2_1_report_by_dept_version", params=..., referenceOnly=true)`，再把其返回的 `resultRef` 传给 `presentation_render`。这样不会把 SQL 明细送入模型，也不会重复查询；不要把明细重新复制进 `variables`。
