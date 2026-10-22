#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
q2_1_metrics_dual_time - 双时间维度 demo 脚本 (版本计划 / 季度 同时支持)

用途: 端到端验证「版本/季度互不换算」规则 (2026/10/21 方案A)。
Java 端 DirectSkillExecutor 对双时间键 skill 只会传一个时间家族的入参:
    - 问题显式提及版本计划 (或默认在建版本) → 只传 version
    - 问题显式提及季度                      → 只传 quarter (可附 qStart/qEnd)

stdout 首行「时间入参回显」即 E2E 观察点: 断言脚本实际收到的时间参数集合。

调用方式 (Java 端 script_exec 工具):
    python3 q2_1_metrics_dual_time.py
    stdin: {"dept":["杭州开发二部"],"version":["2026年12月份版本"]}
        或 {"dept":["杭州开发二部"],"quarter":["2026年4季度"]}
    env:   GAUSS_JDBC_URL / GAUSS_USER / GAUSS_PASS / GAUSS_JAR

参数:
    dept    必填, str 或 [str]
    version 可选, 版本计划 (str 或 [str]) — 与 quarter 互斥使用
    quarter 可选, 季度 (str 或 [str]), 形如 "2026年4季度"
    qStart  可选, 季度窗口起 (yyyy-MM-dd), 仅作回显+区间兜底
    qEnd    可选, 季度窗口止 (yyyy-MM-dd), 仅作回显+区间兜底

季度→月份版本换算 (脚本内, 不依赖 Java): "2026年4季度" → 2026年10/11/12月份版本。
若只收到 qStart/qEnd 而无 quarter, 按日期区间覆盖的月份展开。
复用注册 SQL: q2_1_metrics_by_dept_version (dept + version 两参)。
"""
import sys
import json
import re
import datetime
import pandas as pd
from _gauss_jdbc import query_gauss          # 保留 (_sql_registry 内部依赖)
from _sql_registry import run_registered_sql
from _download import print_download_csv, print_download_xlsx

SQL_ID = "q2_1_metrics_by_dept_version"
DOWNLOAD_FILENAME = "q2_1_双时间demo明细.csv"


def _as_str_list(v):
    if v is None:
        return []
    if isinstance(v, str):
        v = [v]
    if not isinstance(v, list):
        return None
    return [str(x).strip() for x in v if x is not None and str(x).strip()]


def quarter_to_month_versions(quarters):
    """'2026年4季度' → ['2026年10月份版本','2026年11月份版本','2026年12月份版本']"""
    months = []
    for q in quarters:
        m = re.match(r"^(\d{4})年([1-4])季度$", q)
        if not m:
            print(f"ERROR 无法识别季度值: {q} (期望 'yyyy年N季度')", file=sys.stderr)
            sys.exit(1)
        year, n = int(m.group(1)), int(m.group(2))
        for mm in (3 * n - 2, 3 * n - 1, 3 * n):
            months.append(f"{year}年{mm}月份版本")
    return months


def date_range_to_month_versions(q_start, q_end):
    """'2026-09-20'/'2026-12-13' → 区间覆盖的月份版本列表 (闭区间, 按自然月)"""
    start = datetime.date.fromisoformat(q_start)
    end = datetime.date.fromisoformat(q_end)
    months = []
    y, m = start.year, start.month
    while (y, m) <= (end.year, end.month):
        months.append(f"{y}年{m}月份版本")
        m += 1
        if m > 12:
            y, m = y + 1, 1
    return months


def main():
    try:
        params = json.loads(sys.stdin.read() or "{}")
    except Exception as e:
        print(f"ERROR 解析 stdin JSON 失败: {e}", file=sys.stderr)
        sys.exit(1)

    depts = _as_str_list(params.get("dept"))
    versions = _as_str_list(params.get("version"))
    quarters = _as_str_list(params.get("quarter"))
    q_start = params.get("qStart")
    q_end = params.get("qEnd")
    if depts is None or versions is None or quarters is None:
        print(f"ERROR 参数 dept/version/quarter 必须是字符串或字符串数组, 收到: {params}", file=sys.stderr)
        sys.exit(1)
    if not depts:
        print(f"ERROR 缺少必填参数 dept, 收到: {params}", file=sys.stderr)
        sys.exit(1)

    # 1. E2E 观察点: 回显实际收到的时间入参 + 脚本采用的时间家族
    if versions:
        time_source = "versionPlan"
    elif quarters:
        time_source = "vestQuarter"
    elif q_start and q_end:
        time_source = "vestQuarter.window"
    else:
        time_source = "none"
    print(f"时间入参回显: version={versions} quarter={quarters} "
          f"qStart={q_start or ''} qEnd={q_end or ''} timeSource={time_source}")

    # 2. 时间二选一: 版本优先; 季度→月份版本 (脚本内换算, 只为复用既有 SQL);
    #    都未收到 → 时间维度为空 (需求项/需求子条目场景拍板: 不补默认在建版本),
    #    脚本侧按空时间口径输出 (注册 SQL 要求 version 入参, 无法全量查询, 报告 0 行)
    if versions:
        time_source = "versionPlan"
        query_versions = versions
    elif quarters:
        time_source = "vestQuarter"
        query_versions = quarter_to_month_versions(quarters)
    elif q_start and q_end:
        time_source = "vestQuarter.window"
        try:
            query_versions = date_range_to_month_versions(str(q_start), str(q_end))
        except ValueError:
            print(f"ERROR qStart/qEnd 期望 yyyy-MM-dd, 收到: {q_start} / {q_end}", file=sys.stderr)
            sys.exit(1)
    else:
        time_source = "none"
        query_versions = []

    # 3. 调配注册 SQL (复用 q2_1 既有 SQL: dept + version); 空时间口径不发 SQL
    if query_versions:
        try:
            rows = run_registered_sql(SQL_ID, {"dept": depts, "version": query_versions})
            df = pd.DataFrame(rows)
        except SystemExit:
            raise
        except Exception as e:
            print(f"ERROR 调配注册 SQL 失败: {type(e).__name__}: {e}", file=sys.stderr)
            sys.exit(2)
    else:
        df = pd.DataFrame()

    # 4. 算指标
    total = len(df)
    if total == 0:
        scored = passed = 0
        scored_pct = passed_pct = 0.0
    else:
        scored = int((df["Q2_1打分状态"] == "已打分").sum())
        passed = int((df["Q2_1是否达标"] == "达标").sum())
        scored_pct = round(scored / total * 100, 2)
        passed_pct = round(passed / total * 100, 2)

    # 5. 输出: 汇总表 + 查询口径行 + json 行 + echarts 块 + 下载块
    scope = "、".join(query_versions) if query_versions else "(空·需求项全历史口径)"
    print(f"查询口径: 部门 {('、'.join(depts))} | 时间来源 {time_source} | 版本 {scope}")
    print()
    print(f"| 总数 | 已打分 | 达标数 | 打分率 | 达标率 |")
    print(f"|---:|---:|---:|---:|---:|")
    print(f"| {total} | {scored} | {passed} | {scored_pct}% | {passed_pct}% |")
    print()
    if total == 0:
        print("无数据 (dept + 展开版本无匹配行, 或空时间口径未发 SQL)")
    print()
    print(f'json: {{"total":{total},"scored":{scored},"passed":{passed},'
          f'"scored_pct":{scored_pct},"passed_pct":{passed_pct},'
          f'"time_source":"{time_source}","versions":{json.dumps(query_versions, ensure_ascii=False)}}}')

    option = {
        "title": {"text": "Q2-1 打分率/达标率 (双时间demo)", "left": "center"},
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

    if total:
        summary_df = pd.DataFrame({
            "指标": ["总数", "已打分", "达标数", "打分率%", "达标率%"],
            "值": [total, scored, passed, scored_pct, passed_pct],
        })
        print_download_csv(df, DOWNLOAD_FILENAME)
        print_download_xlsx({"明细": df, "汇总": summary_df}, "q2_1_双时间demo明细.xlsx")


if __name__ == "__main__":
    main()
