# -*- coding: utf-8 -*-
"""
q2_1 展示层: df -> 语义 HTML 表体 (配合 _html_table.render_html_custom 使用).

个性化分工 (docs/202611/个性化HTML表格兼容统一样式方案.md §4):
- 本模块只做数据变换 (dim 列转中文/隐藏原子指标列/百分比格式化) + 语义类标注,
  不写外壳内联样式 — 红表头/边框/斑马纹/字体统一由 _html_table.CSS 维护;
- 输出是表体不是成品, 必须再交给 render_html_custom(body_html) 套统一外壳:
      from _html_table import render_html_custom
      from _q2_1_display import format_df_for_html
      print(render_html_custom(format_df_for_html(df)))
"""
from html import escape

import pandas as pd

# db_col -> 中文名 (dim_xxx 去掉 dim_ 前缀后作 key), 按需补充; 未映射的列保留原名
DIMENSION_CN_MAP = {}

# 隐藏的原子指标列 (中间计算列, 展示层只留派生结果列)
HIDDEN_COLS = [
    "q1_bus_count", "q1_tech_count", "q1_bus_done", "q1_bus_ach",
    "q1_tech_done", "q1_tech_ach", "q21_base", "q21_done", "q21_ach",
    "q22_done", "q22_ach", "q3_done", "q3_ach", "q4_done", "q4_ach",
    "q4_case_total", "q4_case_done",
]

_SUMMARY_MARK = "汇总"


def _fmt_pct(x):
    if pd.isna(x):
        return "-"
    try:
        return f"{float(x):.2%}"
    except (ValueError, TypeError):
        return str(x)


def format_df_for_html(df, dim_cn_map=None, hide_cols=None):
    """df -> 语义 HTML 表体 (<table>...</table>), 供 render_html_custom 包装.

    - dim_xxx 列名转中文 (dim_cn_map/DIMENSION_CN_MAP), 隐藏原子指标列;
    - 百分比列 (列名含 率/进度/执行率) 格式化为 xx.xx%, 先于 fillna 保住数值类型;
    - 维度列值 == '汇总' 的行标 <tr class="summary-row"> (统一 CSS 负责加粗灰底);
    - 单元格值一律 html.escape, 脏数据不打穿结构."""
    if df is None or df.empty:
        return "<p>无数据</p>"
    if not isinstance(df, pd.DataFrame):
        raise TypeError(f"df 必须是 pandas DataFrame, 收到: {type(df).__name__}")

    dim_cn_map = dim_cn_map if dim_cn_map is not None else DIMENSION_CN_MAP
    hide = set(HIDDEN_COLS if hide_cols is None else hide_cols)

    rename_map = {
        c: dim_cn_map.get(c[4:], c) for c in df.columns if c.startswith("dim_")
    }
    dim_cols_display = set(rename_map.values())

    df_display = df.rename(columns=rename_map)
    df_display = df_display[[c for c in df_display.columns if c not in hide]].copy()

    pct_cols = [
        c for c in df_display.columns
        if "率" in c or "进度" in c or "执行率" in c
    ]
    for c in pct_cols:
        df_display[c] = df_display[c].map(_fmt_pct)
    df_display = df_display.fillna("-")

    dim_cols = [c for c in df_display.columns if c in dim_cols_display]

    parts = ["<table>", "<thead><tr>"]
    parts.append("".join(f"<th>{escape(str(c))}</th>" for c in df_display.columns))
    parts.append("</tr></thead>")
    parts.append("<tbody>")
    for _, row in df_display.iterrows():
        is_summary = any(str(row[c]) == _SUMMARY_MARK for c in dim_cols)
        tr_cls = ' class="summary-row"' if is_summary else ""
        cells = [
            f'<td class="rowhead">{escape(str(row[c]))}</td>'
            if i == 0
            else f"<td>{escape(str(row[c]))}</td>"
            for i, c in enumerate(df_display.columns)
        ]
        parts.append(f"<tr{tr_cls}>{''.join(cells)}</tr>")
    parts.append("</tbody>")
    parts.append("</table>")
    return "\n".join(parts)
