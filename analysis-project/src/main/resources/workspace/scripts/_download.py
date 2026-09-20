# -*- coding: utf-8 -*-
"""
stdout 下载块协议助手 (供 script_exec 脚本 import, 与 _gauss_jdbc/_sql_registry 同级).

Java 端 ScriptExecTool 解析下载块: 内容落库生成短链, 块原位替换成一行
📥 [filename](/redirect/download?shortCode=xxx) 链接; 块内内容不进 LLM 上下文.
块 print 的位置 = 最终展示里链接行的位置 (原位替换), 需调整图/链接上下顺序时挪 print 顺序即可.
"""
import json

import pandas as pd


def render_markdown_table(df):
    """df -> markdown 表 (tabulate to_markdown). 管道/换行按 Java MarkdownTableConverter
    规则预处理 (tabulate 不转义管道符, 会撑破表格结构, 导致转换器 split("\\|") 串列)."""
    safe = df.fillna("").astype(str).apply(
        lambda col: col.str.replace("|", "\\|", regex=False).str.replace("\n", " ", regex=False))
    return safe.to_markdown(index=False)


def print_download_block(content, filename, mime_type=None):
    """输出一个下载块. content: DataFrame (自动转 markdown 表并做管道/换行转义) 或任意多行字符串;
    filename: 下载文件名; mime_type: 可选, 缺省由 Java 侧按 text/csv 处理
    (受 DownloadContentService MIME 白名单约束)."""
    if isinstance(content, pd.DataFrame):
        content = render_markdown_table(content)
    meta = {"filename": filename}
    if mime_type:
        meta["mimeType"] = mime_type
    print(f'<<<DOWNLOAD_META>>> {json.dumps(meta, ensure_ascii=False)}')
    print("<<<DOWNLOAD_CONTENT>>>")
    print(content)
    print("<<<DOWNLOAD_END>>>")
