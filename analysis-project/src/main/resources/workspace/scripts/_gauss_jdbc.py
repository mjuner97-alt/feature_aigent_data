#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
_gauss_jdbc - openGauss JDBC helper via JPype

psycopg2 不支持 openGauss 的 SHA256 SASL 认证 (报 "none of the server's SASL
authentication mechanisms are supported"). 本模块用 JPype 调 opengauss-jdbc
(与 Java 端同一个驱动 org.opengauss:opengauss-jdbc:5.1.0), 通过 JDBC 连接 openGauss.

参考: docs/table-mertics/test_opengauss_connection.py

env vars (由 ScriptExecTool 注入):
    GAUSS_JDBC_URL - jdbc:postgresql://host:port/db?params
    GAUSS_USER     - 用户名
    GAUSS_PASS     - 密码
    GAUSS_JAR      - opengauss-jdbc jar 路径

Usage:
    from _gauss_jdbc import query_gauss
    rows = query_gauss(sql, params={"dept": "...", "version": "..."})
    # rows: list of dict, 每个 dict 是一行, key 是 SQL AS 别名
"""
import os
import re
import sys

_jvm_started = False


def _ensure_jvm():
    """启动 JPype JVM (仅一次, 同进程多次调 query_gauss 不会重复启动)."""
    global _jvm_started
    if _jvm_started:
        return
    # GAUSS_JAR 默认值: plan-b 镜像内 opengauss-jdbc-5.1.0.jar 路径 (Dockerfile 预填 m2 缓存).
    # env 优先 (local-python 模式可传宿主机 ~/.m2/... 路径覆盖).
    default_jar = "/root/.m2/repository/org/opengauss/opengauss-jdbc/5.1.0/opengauss-jdbc-5.1.0.jar"
    jar = os.environ.get("GAUSS_JAR", default_jar)
    if not os.path.exists(jar):
        print(f"ERROR opengauss-jdbc jar 不存在: {jar} (env GAUSS_JAR={os.environ.get('GAUSS_JAR')})", file=sys.stderr)
        sys.exit(1)
    try:
        import jpype
        import jpype.imports  # 启用 from java... import ... 语法
        if not jpype.isJVMStarted():
            jpype.startJVM(classpath=[jar])
    except Exception as e:
        print(f"ERROR 启动 JPype JVM 失败: {type(e).__name__}: {e}", file=sys.stderr)
        sys.exit(1)
    _jvm_started = True


def _convert_placeholders(sql, params):
    """把 :param_name 占位符转成 JDBC 的 ?, 返回 (jdbc_sql, param_values_in_order).

    SQLAlchemy 用 :param_name, JDBC PreparedStatement 用 ?. 本函数按出现顺序替换.
    """
    if not params:
        return sql, []
    values = []

    def replace(m):
        name = m.group(1)
        if name in params:
            values.append(params[name])
            return "?"
        return m.group(0)

    converted = re.sub(r":(\w+)", replace, sql)
    return converted, values


def query_gauss(sql, params=None):
    """执行参数化 SQL 查询, 返回 list of dict.

    Args:
        sql: SQL 字符串, 用 :param_name 占位 (如 WHERE dept = :dept)
        params: dict, 如 {"dept": "杭州开发二部", "version": "2026年7月份版本"}

    Returns:
        list of dict, 每个 dict 是一行, key 是 SQL AS 别名 (getColumnLabel).
        空结果返回 [].
    """
    _ensure_jvm()

    import jpype
    from java.sql import DriverManager

    jdbc_url = os.environ.get("GAUSS_JDBC_URL")
    user = os.environ.get("GAUSS_USER")
    password = os.environ.get("GAUSS_PASS")
    if not jdbc_url or not user:
        print("ERROR GAUSS_JDBC_URL/GAUSS_USER 环境变量未设置", file=sys.stderr)
        sys.exit(1)

    jdbc_sql, param_values = _convert_placeholders(sql, params)

    conn = DriverManager.getConnection(jdbc_url, user, password)
    try:
        stmt = conn.prepareStatement(jdbc_sql)
        for i, val in enumerate(param_values, 1):
            stmt.setString(i, str(val) if val is not None else "")

        rs = stmt.executeQuery()
        meta = rs.getMetaData()
        n = meta.getColumnCount()
        labels = [meta.getColumnLabel(i) for i in range(1, n + 1)]

        rows = []
        while rs.next():
            row = {}
            for idx, label in enumerate(labels, 1):
                row[label] = rs.getString(idx)
            rows.append(row)

        return rows
    finally:
        conn.close()
