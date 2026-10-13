#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
q_clickhouse_demo_trace_events - ClickHouse platform_orders 明细下载示例 (一步完成)

⚠️ 历史说明: 本脚本原查 default.trace_event 事件流, 但该表在 ClickHouse 服务器上
不存在 (2026/10/09 确认, 服务器只有 default.platform_orders), 已改指 platform_orders;
script_id 沿用旧名未改. 与 q2_1_metrics_by_dept_version 同范式: 汇总指标 + echarts
+ 明细 CSV/xlsx 下载块一步完成.

调用方式 (Java 端 script_exec 工具):
    python3 q_clickhouse_demo_trace_events.py
    stdin: {"start_date":"2026-01-01","end_date":"2026-12-31","user_id":"1001"}
    env:   CLICKHOUSE_DB_URL=clickhouse+http://user:pwd@host:8123/db
           (Gauss env 由 ScriptExecTool defaultEnv 默认注入, 本脚本不走 Gauss)

script_registry 注册:
    script_id   = q_clickhouse_demo_trace_events
    script_path = 555153205/q_clickhouse_demo_trace_events.py
    datasources = ["clickhouse"]   # 让 ScriptExecTool 注入 CLICKHOUSE_DB_URL
    params_schema = [
        {"name":"start_date","type":"string","required":true},
        {"name":"end_date","type":"string","required":true},
        {"name":"user_id","type":"string","required":false}
    ]

输出约定 (stdout):
    汇总 markdown 表 + json: {...} 行 + echarts 可渲染块
    + <<<DOWNLOAD_META>>>/<<<DOWNLOAD_CONTENT>>>/<<<DOWNLOAD_END>>> 下载块
    (CSV 走 print_download_csv, xlsx 多 sheet 走 print_download_xlsx;
     下载块由 Java ScriptExecTool 剥离落库生成短链, 原位替换成下载链接;
     明细行只进下载块, 不进 LLM 上下文)

返回字段:
    total_orders   - 订单总数
    total_amount   - 消费总额 (round 2)
    avg_amount     - 平均客单价 (round 2)
    distinct_users - 覆盖用户数
"""
import os
import sys
import json
import pandas as pd
from sqlalchemy import create_engine, text
from _download import print_download_csv, print_download_xlsx

DOWNLOAD_CSV_FILENAME = "platform_orders_明细.csv"
DOWNLOAD_XLSX_FILENAME = "platform_orders_明细.xlsx"

COLS = {"order_id": "订单ID", "user_id": "用户ID", "product": "商品",
        "amount": "金额", "order_date": "订单日期"}


def main():
    # 1. 从 stdin 读参数
    try:
        params = json.loads(sys.stdin.read() or "{}")
    except Exception as e:
        print(f"ERROR 解析 stdin JSON 失败: {e}", file=sys.stderr)
        sys.exit(1)

    start_date = params.get("start_date")
    end_date = params.get("end_date")
    user_id = params.get("user_id")  # 可选, 过滤用户
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

    # 3. SQL - ClickHouse 方言, 取明细行 (下载块用), 聚合在 pandas 侧算
    #    - 占位符用 SQLAlchemy text() 的 :name 风格
    #    - order_date 是 Date 分区字段, 走分区裁剪
    #    - user_id 可选过滤: 传了才加 WHERE
    user_clause = "AND user_id = :user_id" if user_id else ""
    sql = f"""
        SELECT
          order_id,
          user_id,
          product,
          amount,
          order_date
        FROM default.platform_orders
        WHERE order_date BETWEEN toDate(:start) AND toDate(:end)
        {user_clause}
        ORDER BY order_date, order_id
    """
    bind_params = {"start": start_date, "end": end_date}
    if user_id:
        bind_params["user_id"] = user_id

    # 4. 查询 + pandas 算指标
    try:
        engine = create_engine(ck_url)
        df = pd.read_sql(text(sql), engine, params=bind_params)
        engine.dispose()
    except Exception as e:
        print(f"ERROR 查询 ClickHouse 失败: {type(e).__name__}: {e}", file=sys.stderr)
        sys.exit(2)

    df = df.rename(columns=COLS)
    total_orders = len(df)
    if total_orders == 0:
        total_amount = 0.0
        avg_amount = 0.0
        distinct_users = 0
    else:
        total_amount = round(float(df["金额"].sum()), 2)
        avg_amount = round(float(df["金额"].mean()), 2)
        distinct_users = int(df["用户ID"].nunique())

    # 5. 输出 (markdown 汇总 + JSON 行 + echarts 块 + 下载块)
    # 5.1 汇总表
    print(f"| 订单数 | 消费总额 | 平均客单价 | 覆盖用户数 |")
    print(f"|---:|---:|---:|---:|")
    print(f"| {total_orders} | {total_amount} | {avg_amount} | {distinct_users} |")
    print()

    # 5.2 空数据提示
    if total_orders == 0:
        u_hint = f", user_id={user_id}" if user_id else ""
        print(f"无数据 (order_date in [{start_date}, {end_date}]{u_hint})")
    print()

    # 5.3 JSON 行 (程序解析用, LLM 直接读无需 arith 复算)
    print(f'json: {{"total_orders":{total_orders},"total_amount":{total_amount},'
          f'"avg_amount":{avg_amount},"distinct_users":{distinct_users}}}')

    # 5.4 echarts 可渲染块 (script_exec 约定: 输出必带 html/echarts,
    #     /ai/chat 的 ChatScriptExecResultHook 据此接管 stdout、末尾追加展示)
    if total_orders:
        daily = df.groupby("订单日期").agg(
            订单数=("订单ID", "count"), 消费额=("金额", "sum")).reset_index()
        daily = daily.sort_values("订单日期")
        option = {
            "title": {"text": "每日订单数与消费额", "left": "center"},
            "tooltip": {"trigger": "axis"},
            "xAxis": {"type": "category", "data": [str(d) for d in daily["订单日期"]]},
            "yAxis": [{"type": "value", "name": "订单数"},
                      {"type": "value", "name": "消费额"}],
            "series": [
                {"name": "订单数", "type": "bar",
                 "data": [int(v) for v in daily["订单数"]]},
                {"name": "消费额", "type": "line", "yAxisIndex": 1,
                 "data": [float(v) for v in daily["消费额"]]},
            ],
        }
        print("```echarts")
        print(json.dumps(option, ensure_ascii=False))
        print("```")
    else:
        # script_exec 约定输出必带可渲染块 (接管逻辑依赖), 空数据用占位块
        print("```html\n<!-- 无数据, 无图表 -->\n```")

    # 5.5 明细进下载块: N 行只落库生成短链, 不占 LLM 上下文;
    #     块 print 在 echarts 块之后 -> 最终展示"图在上、下载链接在下";
    #     两个块 (CSV + xlsx 多sheet) 依次生成两条链接, 顺序 = print 顺序
    if total_orders:
        summary_df = pd.DataFrame({
            "指标": ["订单数", "消费总额", "平均客单价", "覆盖用户数"],
            "值": [total_orders, total_amount, avg_amount, distinct_users],
        })
        print_download_csv(df, DOWNLOAD_CSV_FILENAME)
        print_download_xlsx({"明细": df, "汇总": summary_df}, DOWNLOAD_XLSX_FILENAME)


if __name__ == "__main__":
    main()
