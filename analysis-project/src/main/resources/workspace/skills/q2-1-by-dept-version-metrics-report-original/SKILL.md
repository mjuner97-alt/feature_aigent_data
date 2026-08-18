---
name: q2-1-by-dept-version-metrics-report-original
description: 原始版 Q2-1 部门版本指标报告 Skill，直接执行注册 SQL 并在回答中内联 ECharts 配置和 HTML 明细表。用于与 q2_1_by_dept_version_metrics_report 的模板化、服务端渲染方案进行对比。
---

# Q2-1 原始报告示例

## 适用范围

用于对比未采用通用 `presentation_render` 信封的原始实现。该版本把查询结果、图表 option 和 HTML 表格都交给模型处理，回答中直接输出 `echarts` 和 `html` 代码块。

数据源为 GaussDB `remote_app.dsqa_dwd_req_item_app_portrait_wide_inf`。用户必须提供完整部门和版本，例如“杭州开发二部 2026年7月份版本 Q2-1 达标率多少”。参数缺失时先追问，不得默认。

## 原始调用流程

1. 使用 `sql_registry_exec` 执行注册 SQL `q2_1_metrics_by_dept_version`。
2. 将本次查询返回的数据整理成项目总数、已打分、达标数、打分率和达标率。
3. 在最终回答中直接生成 Markdown、`echarts` 代码块和 `html` 代码块。
4. 不调用 `presentation_render`，不使用 `variables_json`、`summary_json`、`resultRef` 或模板变量 Schema。

示例调用：

```text
sql_registry_exec(
  sqlId="q2_1_metrics_by_dept_version",
  params={"department":"杭州开发二部","version":"2026年7月份版本"}
)
```

所有数字必须来自本次 SQL 结果。百分比保留两位小数；缺失数据显示 `-`；不要使用示例数字替代实际结果。

## 最终回答结构

按以下顺序输出：一句结论、一行 Markdown 数据表、ECharts 趋势图代码块、HTML 部门明细表代码块、数据来源和口径说明。

不得输出注册 SQL 原文、Python 代码或数据库密码。

## ECharts 输出样例

将样例中的版本和指标替换为本次查询结果，不得照抄示例数字：

```echarts
{
  "title": { "text": "Q2-1 打分率与达标率趋势", "left": "center", "textStyle": { "fontSize": 20 } },
  "tooltip": { "trigger": "axis", "valueFormatter": "(value) => value + '%'" },
  "legend": { "data": ["打分率", "达标率"], "bottom": 0, "textStyle": { "fontSize": 14, "color": "#000" } },
  "grid": { "left": 56, "right": 24, "top": 64, "bottom": 64, "containLabel": true },
  "dataset": {
    "source": [
      { "版本": "26年5月版", "打分率": 93.33, "达标率": 84.44 },
      { "版本": "26年6月版", "打分率": 95.00, "达标率": 87.50 },
      { "版本": "26年7月版", "打分率": 96.00, "达标率": 90.00 }
    ]
  },
  "xAxis": { "type": "category", "name": "版本", "axisLine": { "lineStyle": { "color": "#000" } }, "axisLabel": { "fontSize": 14, "color": "#000" }, "nameTextStyle": { "fontSize": 14, "color": "#000" } },
  "yAxis": { "type": "value", "name": "比例", "min": 0, "max": 100, "axisLine": { "lineStyle": { "color": "#000" } }, "axisLabel": { "fontSize": 14, "color": "#000", "formatter": "{value}%" }, "nameTextStyle": { "fontSize": 14, "color": "#000" }, "splitLine": { "lineStyle": { "color": "#E0E0E0" } } },
  "series": [
    { "name": "打分率", "type": "line", "color": "#0000FF", "encode": { "x": "版本", "y": "打分率" }, "label": { "show": true, "formatter": "{c}%" } },
    { "name": "达标率", "type": "line", "color": "#008000", "lineStyle": { "type": "dashed" }, "encode": { "x": "版本", "y": "达标率" }, "label": { "show": true, "formatter": "{c}%" } }
  ]
}
```

## HTML 输出样例

表头使用红底白字；第一列左对齐；百分比保留两位；部门必须按固定顺序完整输出，缺失项显示 `-`。`总计` 必须根据本次实际记录计算。

固定部门顺序：杭州开发一部、杭州开发二部、杭州开发三部、杭州开发四部、杭州开发五部、杭州服务支持部、杭州技术部、云计算实验室、杭州产品部。

```html
<table border="1" style="border-collapse:collapse;text-align:center;width:100%;font-family:sans-serif;">
  <thead style="background-color:#C00000;color:white;font-weight:bold;">
    <tr><th rowspan="2" style="padding:10px;">开发部门</th><th rowspan="2" style="padding:10px;">版本</th><th colspan="3" style="padding:10px;">Q2-1 统计</th><th colspan="2" style="padding:10px;">比例</th></tr>
    <tr><th style="padding:8px;">总数</th><th style="padding:8px;">已打分</th><th style="padding:8px;">达标数</th><th style="padding:8px;">打分率</th><th style="padding:8px;">达标率</th></tr>
  </thead>
  <tbody>
    <tr><td style="padding:8px;text-align:left;">杭州开发二部</td><td>2026年7月份版本</td><td>80</td><td>0</td><td>80</td><td>0.00%</td><td>100.00%</td></tr>
    <tr style="font-weight:bold;background-color:#FAFAFA;"><td style="padding:8px;text-align:left;">杭研合计</td><td>2026年7月份版本</td><td>80</td><td>0</td><td>80</td><td>0.00%</td><td>100.00%</td></tr>
  </tbody>
</table>
```

## 口径说明

如果“已打分”和“达标数”的统计状态存在表面不一致，必须按 SQL 原始结果展示，并在回答中说明，不得自行修正或推测业务含义。
