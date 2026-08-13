#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
q_join_gauss_clickhouse_demo - 跨库同期对账示例: GaussDB 项目画像 + ClickHouse trace_event

演示两种 driver 同进程共存:
    - GaussDB: JPype + opengauss-jdbc (复用 _gauss_jdbc.query_gauss, psycopg2 不支持 SHA256 SASL)
    - ClickHouse: sqlalchemy + clickhouse-sqlalchemy (HTTP, 无 SASL 坑)

JPype 启动 JVM 加载 opengauss-jdbc jar, 与 sqlalchemy 进程内独立, 可共存.

注意: trace_event 表只有 conversation_id/trace_id 维度, 无 dept/project_no 业务外键,
无法与 GaussDB 项目画像表做 row-level join. 这里做"同期对账":
    - GaussDB 按 dept+version 查项目打分情况
    - ClickHouse 按 event_date 时间窗口 (与 version 对应月份对齐) 查 trace 事件统计
    - pandas 把两份汇总指标并排输出, 不是 pd.merge

调用方式 (Java 端 script_exec 工具):
    python3 q_join_gauss_clickhouse_demo.py
    stdin: {"dept":"杭州开发二部","version":"2026年7月份版本",
            "start_date":"2026-07-01","end_date":"2026-07-31"}
    env:   GAUSS_JDBC_URL=jdbc:postgresql://host:port/db
           GAUSS_USER=...  GAUSS_PASS=...  GAUSS_JAR=/root/.m2/.../opengauss-jdbc-5.1.0.jar
           CLICKHOUSE_DB_URL=clickhouse+http://user:pwd@host:8123/db

script_registry 注册:
    script_id   = q_join_gauss_clickhouse_demo
    script_path = 555153205/q_join_gauss_clickhouse_demo.py
    datasources = ["gauss","clickhouse"]   # 关键: 两个数据源都要声明
    params_schema = [
        {"name":"dept","type":"string","required":true},
        {"name":"version","type":"string","required":true},
        {"name":"start_date","type":"string","required":true},
        {"name":"end_date","type":"string","required":true}
    ]

返回字段:
    # GaussDB 项目画像
    total_projects       - 项目总数
    scored_projects      - Q2_1 已打分项目数
    passed_projects      - Q2_1 达标项目数
    scored_pct           - 打分率
    passed_pct           - 达标率
    # ClickHouse trace_event 同期统计
    total_events         - 事件总数
    distinct_conv        - 去重会话数 (下限, sum of per-group)
    avg_duration_ms      - 加权平均耗时
    max_duration_ms      - 最大耗时
"""
import os
import sys
import json
import pandas as pd
from sqlalchemy import create_engine, text
from _gauss_jdbc import query_gauss


def main():
    # 1. 从 stdin 读参数
    try:
        params = json.loads(sys.stdin.read() or "{}")
    except Exception as e:
        print(f"ERROR 解析 stdin JSON 失败: {e}", file=sys.stderr)
        sys.exit(1)

    dept = params.get("dept")
    version = params.get("version")
    start_date = params.get("start_date")
    end_date = params.get("end_date")
    if not (dept and version and start_date and end_date):
        print(f"ERROR 缺少必填参数 dept/version/start_date/end_date, 收到: {params}", file=sys.stderr)
        sys.exit(1)

    # ---------- 2. GaussDB 查项目画像 (JPype 路径) ----------
    # SQL 占位符 :name 由 _gauss_jdbc._convert_placeholders 翻译成 JDBC 的 ?
    gauss_sql = """
        SELECT
          projectzh_no AS "project_no",
          dev_dept AS "dept",
          version_plan AS "version",
          score_status_2_1 AS "score_status",
          standard_is_2_1 AS "is_passed"
        FROM dsqa_dwd_req_item_app_portrait_wide_inf
        WHERE dev_dept = :dept
          AND version_plan = :version
          AND in_date = (
            SELECT MAX(in_date) FROM dsqa_dwd_req_item_app_portrait_wide_inf
          )
    """
    try:
        rows = query_gauss(gauss_sql, params={"dept": dept, "version": version})
        df_gauss = pd.DataFrame(rows)
    except Exception as e:
        print(f"ERROR 查询 GaussDB 失败: {type(e).__name__}: {e}", file=sys.stderr)
        sys.exit(2)

    # 算 GaussDB 侧指标 (空结果填 0)
    total_projects = len(df_gauss)
    if total_projects == 0:
        scored_projects = 0
        passed_projects = 0
        scored_pct = 0.0
        passed_pct = 0.0
    else:
        # GaussDB 经 JDBC rs.getString 返回的全是 string, 直接和字符串字面量比
        scored_projects = int((df_gauss["score_status"] == "已打分").sum())
        passed_projects = int((df_gauss["is_passed"] == "达标").sum())
        scored_pct = round(scored_projects / total_projects * 100, 2)
        passed_pct = round(passed_projects / total_projects * 100, 2)

    # ---------- 3. ClickHouse 查同期 trace_event 统计 (sqlalchemy 路径) ----------
    ck_url = os.environ.get("CLICKHOUSE_DB_URL")
    if not ck_url:
        print("ERROR CLICKHOUSE_DB_URL 环境变量未设置. "
              "排查: script_registry.datasources 是否含 'clickhouse'?",
              file=sys.stderr)
        sys.exit(1)

    # 按 event_type 分组, 拿到 per-group 指标后再 pandas 聚合
    # event_date 是 Date 分区字段, 走分区裁剪比 timestamp 范围扫描快
    ck_sql = """
        SELECT
          event_type AS "event_type",
          count() AS "event_count",
          uniqExact(conversation_id) AS "distinct_conv",
          round(avg(duration_ms), 2) AS "avg_duration_ms",
          max(duration_ms) AS "max_duration_ms"
        FROM trace_event
        WHERE event_date BETWEEN toDate(:start) AND toDate(:end)
        GROUP BY event_type
    """
    try:
        engine = create_engine(ck_url)
        df_ck = pd.read_sql(text(ck_sql), engine, params={
            "start": start_date,
            "end": end_date,
        })
        engine.dispose()
    except Exception as e:
        print(f"ERROR 查询 ClickHouse 失败: {type(e).__name__}: {e}", file=sys.stderr)
        sys.exit(2)

    # 算 ClickHouse 侧全局指标
    if df_ck.empty:
        total_events = 0
        distinct_conv = 0
        avg_duration_ms = 0.0
        max_duration_ms = 0
    else:
        total_events = int(df_ck["event_count"].sum())
        distinct_conv = int(df_ck["distinct_conv"].sum())  # 下限近似
        total_duration = (df_ck["event_count"] * df_ck["avg_duration_ms"]).sum()
        avg_duration_ms = round(total_duration / total_events, 2) if total_events else 0.0
        max_duration_ms = int(df_ck["max_duration_ms"].max())

    # ---------- 4. 输出 (同期对账, 并排展示, 不做 row-level merge) ----------
    print(f"## 同期对账: {dept} / {version} (event_date in [{start_date}, {end_date}])")
    print()
    print("### GaussDB 项目画像")
    print(f"| 项目总数 | 已打分 | 达标数 | 打分率 | 达标率 |")
    print(f"|---:|---:|---:|---:|---:|")
    print(f"| {total_projects} | {scored_projects} | {passed_projects} | {scored_pct}% | {passed_pct}% |")
    print()

    print("### ClickHouse trace_event 同期统计")
    print(f"| 事件总数 | 去重会话数(下限) | 平均耗时ms | 最大耗时ms |")
    print(f"|---:|---:|---:|---:|")
    print(f"| {total_events} | {distinct_conv} | {avg_duration_ms} | {max_duration_ms} |")
    print()

    # trace_event 按 event_type 明细前 5
    if not df_ck.empty:
        print("trace_event 按 event_type 明细 (前 5, 按 event_count desc):")
        for _, row in df_ck.sort_values("event_count", ascending=False).head(5).iterrows():
            print(f"  - {row['event_type']}: 事件数={row['event_count']}, "
                  f"去重会话={row['distinct_conv']}, "
                  f"平均={row['avg_duration_ms']}ms, 最大={row['max_duration_ms']}ms")
    print()

    print(f'json: {{"total_projects":{total_projects},"scored_projects":{scored_projects},'
          f'"passed_projects":{passed_projects},"scored_pct":{scored_pct},"passed_pct":{passed_pct},'
          f'"total_events":{total_events},"distinct_conv_lower_bound":{distinct_conv},'
          f'"avg_duration_ms":{avg_duration_ms},"max_duration_ms":{max_duration_ms}}}')


if __name__ == "__main__":
    main()
