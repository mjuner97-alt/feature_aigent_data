#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
q_clickhouse_demo_trace_events - ClickHouse 单库查询示例: trace_event 事件流分析

ScriptExecTool 注入 CLICKHOUSE_DB_URL=clickhouse+http://user:pwd@host:8123/db,
本脚本用 sqlalchemy + pandas.read_sql 直接查. ClickHouse 走 HTTP 协议 (8123 端口),
无 openGauss 那种 SASL 认证坑, 不需要 JPype. 镜像 (Dockerfile:54) 已预装
clickhouse-sqlalchemy, 底层用 requests 走 HTTP.

trace_event 表结构 (业务侧已建):
    event_id          String                  - 事件 ID
    conversation_id   String                  - 会话 ID
    trace_id          String                  - trace ID
    event_type        LowCardinality(String)  - 事件类型
    event_name        String                  - 事件名
    source            String DEFAULT ''       - 来源
    timestamp         DateTime64(3)           - 时间戳 (毫秒精度)
    duration_ms       UInt32 DEFAULT 0         - 耗时 (毫秒)
    event_json        String                  - 事件原始 JSON
    event_date        Date DEFAULT toDate(timestamp)  - 日期分区

调用方式 (Java 端 script_exec 工具):
    python3 q_clickhouse_demo_trace_events.py
    stdin: {"start_date":"2026-07-01","end_date":"2026-07-31"}
    env:   CLICKHOUSE_DB_URL=clickhouse+http://user:pwd@host:8123/db

script_registry 注册:
    script_id   = q_clickhouse_demo_trace_events
    script_path = 555153205/q_clickhouse_demo_trace_events.py
    datasources = ["clickhouse"]   # 关键: 让 ScriptExecTool 注入 CLICKHOUSE_DB_URL
    params_schema = [
        {"name":"start_date","type":"string","required":true},
        {"name":"end_date","type":"string","required":true},
        {"name":"source","type":"string","required":false}
    ]

输出约定 (stdout):
    前 N 行 markdown 汇总表 (LLM 直读)
    末行 json: {...} (程序解析用)

返回字段:
    total_events       - 事件总数
    distinct_conv      - 去重会话数 (uniqExact(conversation_id))
    distinct_traces    - 去重 trace 数
    avg_duration_ms    - 平均耗时 (round 2)
    max_duration_ms    - 最大耗时
"""
import os
import sys
import json
import pandas as pd
from sqlalchemy import create_engine, text


def main():
    # 1. 从 stdin 读参数
    try:
        params = json.loads(sys.stdin.read() or "{}")
    except Exception as e:
        print(f"ERROR 解析 stdin JSON 失败: {e}", file=sys.stderr)
        sys.exit(1)

    start_date = params.get("start_date")
    end_date = params.get("end_date")
    source = params.get("source")  # 可选, 过滤 source 字段
    if not (start_date and end_date):
        print(f"ERROR 缺少必填参数 start_date/end_date, 收到: {params}", file=sys.stderr)
        sys.exit(1)

    # 2. 检查 env
    ck_url = os.environ.get("CLICKHOUSE_DB_URL")
    if not ck_url:
        print("ERROR CLICKHOUSE_DB_URL 环境变量未设置. "
              "排查: script_registry.datasources 是否含 'clickhouse'? "
              "ScriptExecTool.toSqlalchemyUrl 是否把 jdbc:clickhouse:// 转成了 clickhouse+http://?",
              file=sys.stderr)
        sys.exit(1)

    # 3. SQL - ClickHouse 方言, 按 event_type 分组
    #    - 占位符用 SQLAlchemy text() 的 :name 风格 (clickhouse-sqlalchemy 内部翻译成 native binding)
    #    - count() / uniqExact() / avg() / max() / toDate() 是 ClickHouse 内置函数
    #    - event_date 是 Date 分区字段, 走分区裁剪比 timestamp 范围扫描快
    #    - source 可选过滤: 传了才加 WHERE source = :source
    source_clause = "AND source = :source" if source else ""
    sql = f"""
        SELECT
          event_type AS "事件类型",
          count() AS "事件数",
          uniqExact(conversation_id) AS "去重会话数",
          uniqExact(trace_id) AS "去重trace数",
          round(avg(duration_ms), 2) AS "平均耗时ms",
          max(duration_ms) AS "最大耗时ms"
        FROM trace_event
        WHERE event_date BETWEEN toDate(:start) AND toDate(:end)
        {source_clause}
        GROUP BY event_type
        ORDER BY "事件数" DESC
    """

    bind_params = {"start": start_date, "end": end_date}
    if source:
        bind_params["source"] = source

    try:
        engine = create_engine(ck_url)
        df = pd.read_sql(text(sql), engine, params=bind_params)
        engine.dispose()
    except Exception as e:
        print(f"ERROR 查询 ClickHouse 失败: {type(e).__name__}: {e}", file=sys.stderr)
        sys.exit(2)

    # 4. 算全局指标 (空结果填 0)
    if df.empty:
        total_events = 0
        distinct_conv = 0
        distinct_traces = 0
        avg_duration_ms = 0.0
        max_duration_ms = 0
    else:
        total_events = int(df["事件数"].sum())
        # distinct_conv 不能 sum per-group (同一 conversation 跨 event_type 会重复算);
        # 精确全局值需单独查. 这里取 sum 作为下限近似, 字段名标 lower_bound 提醒 LLM.
        distinct_conv = int(df["去重会话数"].sum())
        distinct_traces = int(df["去重trace数"].sum())
        # 加权平均: sum(事件数 * 平均耗时) / sum(事件数)
        total_duration = (df["事件数"] * df["平均耗时ms"]).sum()
        avg_duration_ms = round(total_duration / total_events, 2) if total_events else 0.0
        max_duration_ms = int(df["最大耗时ms"].max())

    # 5. 输出 (markdown 汇总 + 明细前 10 + JSON)
    print(f"| 事件总数 | 去重会话数(下限) | 去重trace数(下限) | 平均耗时ms | 最大耗时ms |")
    print(f"|---:|---:|---:|---:|---:|")
    print(f"| {total_events} | {distinct_conv} | {distinct_traces} | {avg_duration_ms} | {max_duration_ms} |")
    print()

    if df.empty:
        src_hint = f", source={source}" if source else ""
        print(f"无数据 (event_date in [{start_date}, {end_date}]{src_hint})")
    else:
        print("明细 (按事件类型, 前 10 行):")
        for _, row in df.head(10).iterrows():
            print(f"  - {row['事件类型']}: 事件数={row['事件数']}, "
                  f"去重会话={row['去重会话数']}, 去重trace={row['去重trace数']}, "
                  f"平均耗时={row['平均耗时ms']}ms, 最大耗时={row['最大耗时ms']}ms")
    print()

    print(f'json: {{"total_events":{total_events},"distinct_conv_lower_bound":{distinct_conv},'
          f'"distinct_traces_lower_bound":{distinct_traces},'
          f'"avg_duration_ms":{avg_duration_ms},"max_duration_ms":{max_duration_ms}}}')


if __name__ == "__main__":
    main()
