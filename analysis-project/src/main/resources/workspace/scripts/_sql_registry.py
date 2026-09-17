#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
_sql_registry - sql_registry 注册 SQL 调配助手 (script_exec 脚本共享模块)

与 _gauss_jdbc.py 同级 (PYTHONPATH=<workspace>/scripts 注入, 子目录脚本可直接 import).
脚本按 sqlId (原来 sql_registry_exec 用的那个 ID) 取出 sql_registry 里注册好的
SQL 并执行, 不再在脚本里各自维护一份 SQL.

Usage:
    from _sql_registry import run_registered_sql

    rows = run_registered_sql(
        "q2_1_metrics_by_dept_version",
        {"dept": ["杭州开发二部"], "version": ["2026年7月份版本"]},
    )
    # rows: list of dict, 每个 dict 是一行, key 是 SQL AS 别名

内部流程:
    1. SELECT sql_template, params_schema, datasource FROM sql_registry WHERE sql_id = :id
       (走 _gauss_jdbc.query_gauss, env 已由 ScriptExecTool 注入)
    2. 按 params_schema 做与 Java 端 (SqlRegistryExecTool) 同规则的校验:
       多余参数拒绝、必填缺失报错 (安全闸门双份, 宁重复勿缺失)
    3. 按 datasource 分派:
       - gauss:      query_gauss (JPype + opengauss-jdbc, 主路径)
       - mysql:      MYSQL_DB_URL env + sqlalchemy
       - clickhouse: CLICKHOUSE_DB_URL env + sqlalchemy
       (env 未注入 = script_registry.datasources 未声明该库, 最小权限模型直接报错)
    4. 未注册的 sqlId -> ERROR ... 不在 sql_registry 中, exit 非 0

sqlId 固定写在脚本内 (子模式 a), LLM 无法更换 —— 幻觉防护由 SkillFixedToolGuardHook
的 scriptId 分支覆盖.
"""
import json
import os
import re
import sys

ROW_LIMIT = 10000
HAS_LIMIT_PATTERN = re.compile(r"\bLIMIT\s+(\d+|:\w+)", re.IGNORECASE)


def _fail(msg):
    print(f"ERROR {msg}", file=sys.stderr)
    sys.exit(1)


def _validate_params(sql_id, params_schema_json, params):
    """与 Java SqlRegistryExecTool 同规则: 多余参数拒绝 + 必填缺失报错."""
    declared = set()
    required = []
    if params_schema_json and str(params_schema_json).strip():
        try:
            raw = json.loads(params_schema_json)
        except Exception as e:
            _fail(f"sql_id='{sql_id}' 的 params_schema 解析失败: {e}")
        if isinstance(raw, list):
            for item in raw:
                if isinstance(item, dict) and item.get("name"):
                    name = str(item["name"])
                    declared.add(name)
                    if item.get("required") is True:
                        required.append(name)

    param_map = params or {}
    for key in param_map:
        if key not in declared:
            _fail(f"参数 '{key}' 不在 sql_id={sql_id} 的 params_schema 内. "
                  f"已声明参数: {sorted(declared)} (多余参数一律拒执行, 防注入)")
    missing = [n for n in required if n not in param_map]
    if missing:
        _fail(f"缺少必填参数: {missing} (sqlId={sql_id})")


def _ensure_limit(sql):
    """与 Java ensureLimit 对齐: 模板没显式 LIMIT 自动追加 LIMIT 10000."""
    if HAS_LIMIT_PATTERN.search(sql):
        return sql
    stripped = sql.rstrip().rstrip(";").rstrip()
    return f"{stripped}\nLIMIT {ROW_LIMIT}"


def _query_sqlalchemy(url, sql, params):
    from sqlalchemy import bindparam, create_engine, text

    stmt = text(sql)
    for key, value in (params or {}).items():
        if isinstance(value, list):
            stmt = stmt.bindparams(bindparam(key, expanding=True))
    engine = create_engine(url)
    try:
        with engine.connect() as conn:
            result = conn.execute(stmt, params or {})
            cols = list(result.keys())
            return [dict(zip(cols, row)) for row in result.fetchall()]
    finally:
        engine.dispose()


def run_registered_sql(sql_id, params=None):
    """按 sqlId 执行 sql_registry 里注册的 SQL, 返回 list of dict."""
    from _gauss_jdbc import query_gauss

    if not sql_id or not str(sql_id).strip():
        _fail("sqlId 不能为空")

    try:
        rows = query_gauss(
            "SELECT sql_template, params_schema, datasource FROM sql_registry WHERE sql_id = :sql_id",
            params={"sql_id": str(sql_id).strip()},
        )
    except SystemExit:
        raise
    except Exception as e:
        _fail(f"查询 sql_registry 失败: {type(e).__name__}: {e}")

    if not rows:
        _fail(f"sqlId '{sql_id}' 不在 sql_registry 中 (核对注册 SQL 的 sql_id 拼写)")
    entry = rows[0]

    template = (entry.get("sql_template") or "").strip()
    if not template:
        _fail(f"sql_id='{sql_id}' 的 sql_template 为空 (DBA 录入失误?)")

    _validate_params(str(sql_id), entry.get("params_schema"), params)

    datasource = (entry.get("datasource") or "").strip().lower()
    sql = _ensure_limit(template)

    if datasource == "gauss":
        return query_gauss(sql, params=params)

    if datasource in ("mysql", "clickhouse"):
        env_key = f"{datasource.upper()}_DB_URL"
        url = os.environ.get(env_key)
        if not url:
            _fail(f"env {env_key} 未注入 (script_registry.datasources 未声明 {datasource}? "
                  f"最小权限模型: 未声明的库不注入)")
        try:
            return _query_sqlalchemy(url, sql, params)
        except SystemExit:
            raise
        except Exception as e:
            _fail(f"sqlId '{sql_id}' 查询 {datasource} 失败: {type(e).__name__}: {e}")

    _fail(f"sql_id='{sql_id}' 的 datasource='{datasource}' 不在支持列表 (gauss/mysql/clickhouse)")
