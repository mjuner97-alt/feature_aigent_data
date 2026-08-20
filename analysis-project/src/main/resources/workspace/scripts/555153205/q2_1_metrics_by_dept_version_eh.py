#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
q2_1_metrics_by_dept_version - Q2-1 打分状态/达标率指标计算

由 script_exec 工具调用, 替代 sql_registry_exec + python_exec 两步走.
脚本内部完成: SQL 取数 (GaussDB via JPype+opengauss-jdbc) + pandas 算 总数/已打分/达标数, 一次返回.

调用方式 (Java 端 script_exec 工具):
    python3 q2_1_metrics_by_dept_version.py
    stdin: {"dept":"杭州开发二部","version":"2026年7月份版本"}
    env:   GAUSS_JDBC_URL=jdbc:postgresql://host:port/db
           GAUSS_USER=...  GAUSS_PASS=...  GAUSS_JAR=/root/.m2/.../opengauss-jdbc-5.1.0.jar

输出约定 (stdout):
    可由聊天前端直接渲染的完整 Markdown，包含摘要、ECharts 和 HTML 明细表。

返回字段:
    total       - 总行数
    scored      - Q2_1打分状态 == "已打分" 的行数
    passed      - Q2_1是否达标 == "达标" 的行数
    scored_pct  - 打分率 (round 2 位小数, 0-100)
    passed_pct  - 达标率 (round 2 位小数, 0-100)
"""
import sys
import json
from html import escape
import pandas as pd
from _gauss_jdbc import query_gauss


DEPARTMENTS = [
    "杭州开发一部",
    "杭州开发二部",
    "杭州开发三部",
    "杭州开发四部",
    "杭州开发五部",
    "杭州服务支持部",
    "杭州技术部",
    "云计算实验室",
    "杭州产品部",
]


def render_report(dept, version, total, scored, passed, scored_pct, passed_pct):
    """Build the complete chat response without asking the LLM to assemble templates."""
    title = f"{dept} {version} Q2-1 指标报告"
    option = {
        "title": {
            "text": title,
            "left": "center",
            "textStyle": {"fontSize": 20},
        },
        "tooltip": {"trigger": "axis"},
        "legend": {
            "data": ["打分率", "达标率"],
            "bottom": 0,
            "textStyle": {"fontSize": 14, "color": "#000000"},
        },
        "grid": {
            "left": 56,
            "right": 24,
            "top": 64,
            "bottom": 64,
            "containLabel": True,
        },
        "xAxis": {
            "type": "category",
            "name": "版本",
            "data": [version],
            "axisLine": {"lineStyle": {"color": "#000000"}},
            "axisLabel": {"fontSize": 14, "color": "#000000"},
            "nameTextStyle": {"fontSize": 14, "color": "#000000"},
        },
        "yAxis": {
            "type": "value",
            "name": "比例",
            "min": 0,
            "max": 100,
            "axisLine": {"lineStyle": {"color": "#000000"}},
            "axisLabel": {
                "fontSize": 14,
                "color": "#000000",
                "formatter": "{value}%",
            },
            "nameTextStyle": {"fontSize": 14, "color": "#000000"},
            "splitLine": {"lineStyle": {"color": "#E0E0E0"}},
        },
        "series": [
            {
                "name": "打分率",
                "type": "line",
                "color": "#2563EB",
                "symbol": "circle",
                "symbolSize": 10,
                "lineStyle": {"color": "#2563EB", "width": 3},
                "itemStyle": {
                    "color": "#2563EB",
                    "borderColor": "#FFFFFF",
                    "borderWidth": 2,
                },
                "label": {
                    "show": True,
                    "formatter": "{c}%",
                    "color": "#2563EB",
                    "fontWeight": "bold",
                },
                "data": [scored_pct],
            },
            {
                "name": "达标率",
                "type": "line",
                "color": "#16803A",
                "symbol": "circle",
                "symbolSize": 10,
                "lineStyle": {"color": "#16803A", "width": 3, "type": "dashed"},
                "itemStyle": {
                    "color": "#16803A",
                    "borderColor": "#FFFFFF",
                    "borderWidth": 2,
                },
                "label": {
                    "show": True,
                    "formatter": "{c}%",
                    "color": "#16803A",
                    "fontWeight": "bold",
                },
                "data": [passed_pct],
            },
        ],
    }

    rows = []
    for department in DEPARTMENTS:
        if department == dept:
            cells = [
                department,
                version,
                str(total),
                str(scored),
                str(passed),
                f"{scored_pct:.2f}%",
                f"{passed_pct:.2f}%",
            ]
        else:
            cells = [department, "-", "-", "-", "-", "-", "-"]
        rows.append(
            "<tr>"
            f'<td style="padding:8px;text-align:left;">{escape(cells[0])}</td>'
            + "".join(f"<td>{escape(value)}</td>" for value in cells[1:])
            + "</tr>"
        )

    total_cells = [
        "杭研合计",
        version,
        str(total),
        str(scored),
        str(passed),
        f"{scored_pct:.2f}%",
        f"{passed_pct:.2f}%",
    ]
    rows.append(
        '<tr style="font-weight:bold;background-color:#FAFAFA;">'
        f'<td style="padding:8px;text-align:left;">{total_cells[0]}</td>'
        + "".join(f"<td>{escape(value)}</td>" for value in total_cells[1:])
        + "</tr>"
    )

    html_table = """<div style="overflow-x:auto;width:100%;">
<table border="1" style="border-collapse:collapse;text-align:center;width:100%;min-width:760px;font-family:sans-serif;color:#111827;">
  <thead style="background-color:#C00000;color:#FFFFFF;font-weight:bold;">
    <tr><th rowspan="2" style="padding:10px;">开发部门</th><th rowspan="2" style="padding:10px;">版本</th><th colspan="3" style="padding:10px;">Q2-1 统计</th><th colspan="2" style="padding:10px;">比例</th></tr>
    <tr><th style="padding:8px;">总数</th><th style="padding:8px;">已打分</th><th style="padding:8px;">达标数</th><th style="padding:8px;">打分率</th><th style="padding:8px;">达标率</th></tr>
  </thead>
  <tbody>
    {{REPORT_ROWS}}
  </tbody>
</table>
</div>""".replace("{{REPORT_ROWS}}", "\n    ".join(rows))

    parts = [
        f"{dept} {version} Q2-1 达标率为 **{passed_pct:.2f}%**。",
        "",
        "| 项目总数 | 已打分 | 达标数 | 打分率 | 达标率 |",
        "|---:|---:|---:|---:|---:|",
        f"| {total} | {scored} | {passed} | {scored_pct:.2f}% | {passed_pct:.2f}% |",
        "",
        "```echarts",
        json.dumps(option, ensure_ascii=False, indent=2),
        "```",
        "",
        "```html",
        html_table,
        "```",
        "",
        "数据来源：GaussDB remote_app.dsqa_dwd_req_item_app_portrait_wide_inf。",
    ]
    if scored < passed:
        parts.extend([
            "",
            "说明：当前结果中“已打分”少于“达标数”，以上数字按脚本原始统计口径展示。",
        ])
    return "\n".join(parts)


def main():
    # 1. 从 stdin 读参数
    try:
        params = json.loads(sys.stdin.read() or "{}")
    except Exception as e:
        print(f"ERROR 解析 stdin JSON 失败: {e}", file=sys.stderr)
        sys.exit(1)

    dept = params.get("dept")
    version = params.get("version")
    if not dept or not version:
        print(f"ERROR 缺少必填参数 dept/version, 收到: {params}", file=sys.stderr)
        sys.exit(1)

    # 2. 执行 SQL (JPype + opengauss-jdbc, psycopg2 不支持 openGauss SHA256 SASL 认证)
    sql = """
        SELECT
          projectzh_no AS "项目编号",
          projectzh_name AS "项目名称",
          dev_dept AS "开发部门",
          version_plan AS "版本计划",
          app AS "涉及应用",
          product_line AS "产品线",
          stat_group AS "统计组",
          score_status_2_1 AS "Q2_1打分状态",
          standard_is_2_1 AS "Q2_1是否达标"
        FROM dsqa_dwd_req_item_app_portrait_wide_inf
        WHERE dev_dept = :dept
          AND version_plan = :version
          AND in_date = (
            SELECT MAX(in_date) FROM dsqa_dwd_req_item_app_portrait_wide_inf
          )
    """

    try:
        rows = query_gauss(sql, params={"dept": dept, "version": version})
        df = pd.DataFrame(rows)
    except Exception as e:
        print(f"ERROR 查询 GaussDB 失败: {type(e).__name__}: {e}", file=sys.stderr)
        sys.exit(2)

    # 3. 算指标 (空结果 total=0 时, scored/passed/pct 都为 0)
    total = len(df)
    if total == 0:
        scored = 0
        passed = 0
        scored_pct = 0.0
        passed_pct = 0.0
    else:
        scored = int((df["Q2_1打分状态"] == "已打分").sum())
        passed = int((df["Q2_1是否达标"] == "达标").sum())
        scored_pct = round(scored / total * 100, 2)
        passed_pct = round(passed / total * 100, 2)

    # 4. 输出前端可直接渲染的完整 Markdown 报告
    if total == 0:
        print(f"{dept} {version} 无数据。")
        return
    print(render_report(dept, version, total, scored, passed, scored_pct, passed_pct))


if __name__ == "__main__":
    main()
