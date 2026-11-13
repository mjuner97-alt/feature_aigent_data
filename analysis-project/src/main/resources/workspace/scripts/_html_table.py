# -*- coding: utf-8 -*-
"""
df -> 统一样式 HTML 表格 (供 script_exec 脚本 import, 与 _download/_sql_registry 同级).

统一结果输出的表格风格 (表头 rgb(192,0,0) 白字, 表体白底), 避免各脚本各写一套内联样式.
前端 Markdown.vue 两条渲染路径:
- 完整文档 (全文 <!DOCTYPE html> 开头) -> iframe srcdoc 隔离渲染, 须独占整次输出;
- 片段 (fragment=True) -> 放 ```html 围栏, 可与文字/echarts 混排.
"""
import html as _html

import pandas as pd

CSS = """
/* unified-table: 片段 <style> 会被长任务 HtmlReportRenderer 提升到汇总文档 head,
   独有 class 保证只命中自带 wrapper, 不污染宿主 (id="report" 会与其容器撞名,
   见 docs/202611/统一样式片段长任务样式污染修复方案.md) */
.unified-table { box-sizing: border-box; }
.unified-table * { box-sizing: border-box; }
.unified-table {
  font-family: "Microsoft YaHei", "PingFang SC", sans-serif;
  color:#333; /* 背景透明: 前端 iframe 是透明设计, 白底会在表格右侧显出大片空白 */
}
.unified-table .table-wrap { overflow-x:auto; width:100%; }
.unified-table table {
  border-collapse: collapse !important; background:#ffffff !important;
  width: max-content !important; /* 贴内容收缩, 不强行铺满 (对齐 HtmlReportRenderer) */
  min-width: 0 !important; max-width:100% !important; margin:10px 0;
}
.unified-table th {
  background: rgb(192, 0, 0) !important; color:#fff !important; font-weight:600 !important;
  border:1px solid #bfbfbf !important; text-align:center !important;
}
.unified-table td {
  color:#333; /* 不加 important, 保留业务数据标红等个性标记 */
  border:1px solid #bfbfbf !important; text-align:center !important;
  word-break:break-word; overflow-wrap:anywhere;
}
.unified-table tr:nth-child(even) td { background:#fafafa !important; }
.unified-table tr:hover td { background:#fff3e0 !important; }
.unified-table tr.summary-row td { font-weight:600 !important; background:#f0f0f0 !important; }
.unified-table td.rowhead { text-align:left !important; }
.unified-table td.red, .unified-table .red { color:red !important; }
.unified-table .section-title-sm { font-size:1rem; margin-top:16px; margin-bottom:6px; color:#000; }
"""

DOC_TEMPLATE = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<title>{title}</title>
<style>{css}</style>
</head>
<body>
{body}
</body>
</html>"""


def _title_html(title):
    return (
        f'<div class="section-title-sm">{_html.escape(str(title))}</div>'
        if title
        else ""
    )


def _assemble(inner, title, fragment):
    body = (
        '<div class="unified-table">\n'
        f'<div class="table-wrap">\n{_title_html(title)}{inner}\n</div>\n'
        '</div>'
    )
    if fragment:
        return f"<style>{CSS}</style>\n{body}"
    return DOC_TEMPLATE.format(
        title=_html.escape(str(title)) if title else "数据表格", css=CSS, body=body
    )


def render_html_table(df, title=None, fragment=False):
    """df -> 统一样式 HTML 表格.

    fragment=False: 完整 HTML 文档, 脚本把它作为独占输出直接 print (勿与其他文本拼接);
    fragment=True: <style>+<div>+<table> 片段, 供 ```html 围栏混排.
    title: 可选标题 (片段模式下同样渲染)."""
    if not isinstance(df, pd.DataFrame):
        raise TypeError(f"df 必须是 pandas DataFrame, 收到: {type(df).__name__}")

    safe = df.fillna("").astype(str).apply(
        lambda col: col.str.replace("\n", " ", regex=False))
    table_html = safe.to_html(index=False, border=0, na_rep="", escape=True)
    return _assemble(table_html, title, fragment)


def render_html_custom(body_html, title=None, fragment=False):
    """现成 HTML 表体 -> 统一外壳 (统一 CSS + table-wrap + 文档/片段两档).

    个性化表格的兼容出口: body_html 原样嵌入, 外壳样式由本模块统一维护.
    统一样式用 !important 压制个性化内联样式 (对齐 HtmlReportRenderer REPORT_CSS,
    内联红头/灰头都会被强制成统一红头); 个性化只保留在非受管属性 (td color) 和
    语义类: tr.summary-row / td.rowhead / td.red.
    全部规则挂在独有 class unified-table 作用域, 整份 HTML 被误注入其他页面时样式零泄漏.
    body_html 为信任输入不转义 — 单元格值的 html.escape 由调用方负责.
    参考 docs/202611/个性化HTML表格兼容统一样式方案.md."""
    return body_html
