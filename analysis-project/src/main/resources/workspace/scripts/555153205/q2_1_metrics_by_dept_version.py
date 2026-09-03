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
    前 N 行 markdown 表 (LLM 直读)
    末行 json: {...} (程序解析用)

返回字段:
    total       - 总行数
    scored      - Q2_1打分状态 == "已打分" 的行数
    passed      - Q2_1是否达标 == "达标" 的行数
    scored_pct  - 打分率 (round 2 位小数, 0-100)
    passed_pct  - 达标率 (round 2 位小数, 0-100)
"""
import sys
import json
import os
import pandas as pd
from _gauss_jdbc import query_gauss


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
        WHERE dev_dept IN (:dept)
          AND version_plan IN (:version)
          AND in_date = (
            SELECT MAX(in_date) FROM dsqa_dwd_req_item_app_portrait_wide_inf
          )
    """

    try:
        rows = query_gauss(sql, params={"dept": depts, "version": versions})
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

    # 4. 输出 (markdown 表 + JSON 行)
    # 4.1 markdown 表
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


if __name__ == "__main__":
    main()
