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
body  { font-family: "Microsoft YaHei", "PingFang SC", sans-serif; background:#fff; margin:16px; color:#333; }
.table-wrap { overflow-x:auto; width:100%; }
table { border-collapse: collapse; background:#ffffff; width:100%; }
th    { background: rgb(192, 0, 0); color:#fff; font-weight:600; padding:8px 12px; border:1px solid #c00000; text-align:center; }
td    { background:#ffffff; color:#333; padding:8px 12px; border:1px solid #d9d9d9; max-width:480px; word-break:break-all; }
tr:nth-child(even) td { background:#fafafa; }
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

    title_html = f"<h3>{_html.escape(str(title))}</h3>" if title else ""
    body = (
        f'<div class="table-wrap">\n{title_html}{table_html}\n</div>'
        if title_html
        else f'<div class="table-wrap">\n{table_html}\n</div>'
    )
    if fragment:
        return f"<style>{CSS}</style>\n{body}"
    return DOC_TEMPLATE.format(
        title=_html.escape(str(title)) if title else "数据表格", css=CSS, body=body
    )
