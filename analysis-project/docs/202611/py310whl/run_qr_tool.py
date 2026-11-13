#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
run_qr_tool - qr_tool 的 PyCharm 直跑入口 (无需命令行窗口)

用法: 改下面配置区 -> PyCharm 里右键 Run 'run_qr_tool'
输出: 生成/解码结果直接显示在 PyCharm Run 控制台
"""
import os
import sys

# ==================== 配置区 (只改这里) ====================
MODE = "gen"        # "gen"=文本生成二维码 / "decode"=二维码还原文本

# --- MODE = "gen" 时生效 ---
TEXT = "hello 麒麟内网"      # 要编码的内容, 或 "@文件.txt" 从文件读取
OUT = "qr.png"              # 输出 PNG 路径 (超长自动分片加 _1ofN 后缀)
SCALE = 8                   # 每模块像素数, 越大图越大越好扫
CHUNK = 1000                # 分片每片最大字节数 (默认 1000, 越小越好扫)

# --- MODE = "decode" 时生效 ---
IMAGES = ["qr_1of4.png", "qr_2of4.png"]  # 图片路径列表, 支持 "qr_*of4.png" 通配符
DECODE_OUT = None           # None=打印到控制台; "out.txt"=写入文件

# =========================================================


def run():
    for s in (sys.stdout, sys.stderr):
        if hasattr(s, "reconfigure"):
            s.reconfigure(encoding="utf-8", errors="replace")

    # 让 import qr_tool 找到同目录脚本
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import qr_tool

    mode = MODE.strip().lower()
    if mode == "gen":
        sys.argv = ["qr_tool.py", "gen", TEXT, "-o", OUT,
                    "-s", str(SCALE), "-c", str(CHUNK)]
    elif mode == "decode":
        argv = ["qr_tool.py", "decode"] + list(IMAGES)
        if DECODE_OUT:
            argv += ["-o", DECODE_OUT]
        sys.argv = argv
    else:
        print(f"MODE 只能是 gen / decode, 当前: {MODE!r}")
        sys.exit(1)

    qr_tool.main()


if __name__ == "__main__":
    run()
