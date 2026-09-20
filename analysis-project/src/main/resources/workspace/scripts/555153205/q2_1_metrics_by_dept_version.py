#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
q2_1_metrics_by_dept_version - Q2-1 打分状态/达标率指标计算 + 明细下载 (一步完成)

由 script_exec 工具调用. 脚本内部完成: 注册 SQL 调配 (sql_registry via _sql_registry,
与 sql_registry_exec 用同一个 sqlId) + pandas 算 总数/已打分/达标数 + 明细 CSV 下载块.

调用方式 (Java 端 script_exec 工具):
    python3 q2_1_metrics_by_dept_version.py
    stdin: {"dept":"杭州开发二部","version":"2026年7月份版本"}
    env:   GAUSS_JDBC_URL=jdbc:postgresql://host:port/db
           GAUSS_USER=...  GAUSS_PASS=...  GAUSS_JAR=/root/.m2/.../opengauss-jdbc-5.1.0.jar

输出约定 (stdout):
    汇总 markdown 表 + json: {...} 行 + echarts 可渲染块
    + <<<DOWNLOAD_META>>>/<<<DOWNLOAD_CONTENT>>>/<<<DOWNLOAD_END>>> 下载块
    (下载块由 Java ScriptExecTool 剥离落库生成短链, 原位替换成下载链接;
     明细行只进下载块, 不进 LLM 上下文)

返回字段:
    total       - 总行数
    scored      - Q2_1打分状态 == "已打分" 的行数
    passed      - Q2_1是否达标 == "达标" 的行数
    scored_pct  - 打分率 (round 2 位小数, 0-100)
    passed_pct  - 达标率 (round 2 位小数, 0-100)
"""
import sys
import json
import pandas as pd
from _gauss_jdbc import query_gauss          # 保留 (_sql_registry 内部依赖)
from _sql_registry import run_registered_sql
from _download import print_download_block

SQL_ID = "q2_1_metrics_by_dept_version"      # 与 sql_registry 注册的 sqlId 一致
DOWNLOAD_FILENAME = "q2_1_明细.csv"           # downloadFilename 固化在脚本里, LLM 不传


def main():
    # 1. 从 stdin 读参数
    try:
        params = json.loads(sys.stdin.read() or "{}")
    except Exception as e:
        print(f"ERROR 解析 stdin JSON 失败: {e}", file=sys.stderr)
        sys.exit(1)

    depts = params.get("dept")
    versions = params.get("version")
    if isinstance(depts, str): depts = [depts]
    if isinstance(versions, str): versions = [versions]
    if not isinstance(depts, list) or not isinstance(versions, list):
        print(f"ERROR 参数 dept/version 必须是字符串或字符串数组, 收到: {params}", file=sys.stderr)
        sys.exit(1)
    depts = [str(v).strip() for v in depts if v is not None and str(v).strip()]
    versions = [str(v).strip() for v in versions if v is not None and str(v).strip()]
    if not depts or not versions:
        print(f"ERROR 缺少必填参数 dept/version, 收到: {params}", file=sys.stderr)
        sys.exit(1)

    # 2. 调配注册 SQL (脚本内不再硬编码 SQL, 与 sql_registry_exec 用同一份)
    try:
        rows = run_registered_sql(SQL_ID, {"dept": depts, "version": versions})
        df = pd.DataFrame(rows)
    except SystemExit:
        raise
    except Exception as e:
        print(f"ERROR 调配注册 SQL 失败: {type(e).__name__}: {e}", file=sys.stderr)
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

    # 4. 输出 (markdown 表 + JSON 行 + echarts 块 + 下载块)
    # 4.1 汇总表
    print(f"| 总数 | 已打分 | 达标数 | 打分率 | 达标率 |")
    print(f"|---:|---:|---:|---:|---:|")
    print(f"| {total} | {scored} | {passed} | {scored_pct}% | {passed_pct}% |")
    print()

    # 4.2 业务解读提示
    if total == 0:
        print("无数据 (dev_dept + version_plan 无匹配行, 或最新 in_date 当天无数据)")
    print()

    # 4.3 JSON 行 (程序解析用, 含百分比, LLM 直接读无需 arith 复算)
    print(f'json: {{"total":{total},"scored":{scored},"passed":{passed},"scored_pct":{scored_pct},"passed_pct":{passed_pct}}}')

    # 4.4 echarts 可渲染块 (script_exec 约定: 输出必带 html/echarts,
    #     /ai/chat 的 ChatScriptExecResultHook 据此接管 stdout、末尾追加展示)
    option = {
        "title": {"text": "Q2-1 打分率/达标率", "left": "center"},
        "tooltip": {"trigger": "axis"},
        "xAxis": {"type": "category", "data": ["打分率", "达标率"]},
        "yAxis": {"type": "value", "max": 100, "axisLabel": {"formatter": "{value}%"}},
        "series": [{
            "type": "bar",
            "data": [
                {"value": scored_pct, "itemStyle": {"color": "#2563EB"}},
                {"value": passed_pct, "itemStyle": {"color": "#16803A"}},
            ],
            "label": {"show": True, "position": "top", "formatter": "{c}%"},
        }],
    }
    print("```echarts")
    print(json.dumps(option, ensure_ascii=False))
    print("```")

    # 4.5 明细进下载块: N 行只落库生成短链, 不占 LLM 上下文;
    #     块 print 在 echarts 块之后 -> 最终展示"图在上、下载链接在下"
    if total:
        print_download_block(df, DOWNLOAD_FILENAME)


if __name__ == "__main__":
    main()
