# -*- coding: utf-8 -*-
"""
stdout 下载块协议助手 (供 script_exec 脚本 import, 与 _gauss_jdbc/_sql_registry 同级).

Java 端 ScriptExecTool 解析下载块: 内容落库生成短链, 块原位替换成一行
📥 <a href="/redirect/download?shortCode=xxx" ...>filename</a> HTML 链接; 块内内容不进 LLM 上下文.
块 print 的位置 = 最终展示里链接行的位置 (原位替换), 需调整图/链接上下顺序时挪 print 顺序即可.

两种内容形态:
- 文本 (CSV/markdown/html): print_download_csv / print_download_block, 原样落库;
- 二进制 (xlsx 等): print_download_xlsx, 内存生成后 base64, 内容以 "base64:" 前缀
  进 DOWNLOAD_CONTENT —— url_shortener.content 是 TEXT 列存不了原始字节, Java
  DownloadContentService/RedirectController 按前缀解码并以原始字节回吐 (表结构零迁移).
"""
import base64
import io
import json

import pandas as pd

XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"


def render_markdown_table(df):
    """df -> markdown 表 (tabulate to_markdown). 管道/换行按 Java MarkdownTableConverter
    规则预处理 (tabulate 不转义管道符, 会撑破表格结构, 导致转换器 split("\\|") 串列)."""
    safe = df.fillna("").astype(str).apply(
        lambda col: col.str.replace("|", "\\|", regex=False).str.replace("\n", " ", regex=False))
    return safe.to_markdown(index=False)


def print_download_csv(content, filename, mime_type=None):
    """输出一个文本下载块. content: DataFrame (自动转 markdown 表并做管道/换行转义,
    Java 侧自动转标准 CSV) 或任意多行字符串; filename: 下载文件名;
    mime_type: 可选, 缺省由 Java 侧按 text/csv 处理 (受 MIME 白名单约束)."""
    if isinstance(content, pd.DataFrame):
        content = render_markdown_table(content)
    meta = {"filename": filename}
    if mime_type:
        meta["mimeType"] = mime_type
    _print_block(meta, content)


def print_download_xlsx(sheets, filename, mime_type=XLSX_MIME):
    """输出一个 xlsx 多 sheet 下载块 (二进制走 base64, 依赖容器内 openpyxl).
    sheets: {sheet 名: DataFrame}, dict 顺序即 sheet 顺序; filename: 下载文件名.
    解码后大小受 DownloadContentService 5MB 上限约束 (base64 膨胀 1.37x,
    即原始 xlsx 需 <= ~3.6MB, 万行级明细无压力)."""
    buf = io.BytesIO()
    with pd.ExcelWriter(buf, engine="openpyxl") as writer:
        for name, df in sheets.items():
            df.to_excel(writer, sheet_name=str(name), index=False)
    b64 = base64.b64encode(buf.getvalue()).decode("ascii")
    _print_block({"filename": filename, "mimeType": mime_type}, "base64:" + b64)


def _print_block(meta, content):
    print(f'<<<DOWNLOAD_META>>> {json.dumps(meta, ensure_ascii=False)}')
    print("<<<DOWNLOAD_CONTENT>>>")
    print(content)
    print("<<<DOWNLOAD_END>>>")
