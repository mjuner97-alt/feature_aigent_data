#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""xlsx 多 sheet 下载块 demo: script_exec 一次调用, stdout 出汇总表 + 多 sheet xlsx 短链."""
import json
import sys

import pandas as pd

from _download import print_download_xlsx


def main():
    params = json.loads(sys.stdin.read() or "{}")
    dept = params.get("dept", "演示部门")

    df = pd.DataFrame({
        "项目编号": ["P001", "P002"],
        "项目名称": ["演示项目A", "演示项目B"],
        "开发部门": [dept, dept],
        "数值": [1.5, 2.5],
    })
    summary = pd.DataFrame({"指标": ["总数", "数值合计"], "值": [len(df), df["数值"].sum()]})

    print("| 指标 | 值 |")
    print("|---|---:|")
    for _, row in summary.iterrows():
        print(f"| {row['指标']} | {row['值']} |")
    print()
    print("```echarts")
    print('{"series": [{"type": "bar", "data": [4.0]}]}')
    print("```")
    print_download_xlsx({"明细": df, "汇总": summary}, "xlsx_demo_多sheet.xlsx")


if __name__ == "__main__":
    main()
