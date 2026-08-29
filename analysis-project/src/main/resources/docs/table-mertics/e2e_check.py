"""E2E 数字校验脚本 - 直接连 GaussDB 跑 SQL, 跟 agent 返回的 4 个指标对齐.

用法:
    # 默认连 116.148.121.37 (dev)
    python e2e_check.py

    # 覆盖连接参数
    OG_HOST=... OG_PORT=5432 OG_DB=postgres OG_USER=... OG_PASS=... python e2e_check.py

    # 覆盖 filter 值 (按维度)
    DEV_DEPT=杭州开发二部 VERSION_PLAN=2026年8月份版本 python e2e_check.py
    PRODUCT_LINE=个人信贷产品线 VERSION_PLAN=2026年8月份版本 python e2e_check.py
    STAT_GROUP=信贷应用开发组 VERSION_PLAN=2026年8月份版本 python e2e_check.py

脚本通过 JPype 调 opengauss-jdbc 5.1.0 连 DB (pg8000/psycopg2 不能协商 OpenGauss 的
SHA256 SASL, 详见 test_opengauss_connection.py). 直接 SQL 算 4 个指标值, 跟 agent
返回的 4 个数字逐一对比, 误差应 <= 0.01 (百分比小数点后 2 位).

注意: 当前 dev DB 数据有 CSV-import 损坏:
  - schema 实际为 remote_app (不是 DDL 里的 remote_app)
  - 列名 _ 被替换成 0 (例: dev_dept -> dev0dept, score_status_2_1 -> score0status0201)
  - 第一列 projectzh0no 还带 UTF-8 BOM 前缀
  - 中文字段值都是 ? (编码丢失, 不可恢复)
所以本脚本默认查 remote_app schema + 实际列名 + 无 filter (拿全表 99 行做总数校验).
DBA 修复数据后, 改 SCHEMA / 列名常量回 DDL 形式即可.
"""
import os
import sys

import jpype
import jpype.imports

DEFAULT_JAR = os.path.expanduser(
    "~/.m2/repository/org/opengauss/opengauss-jdbc/5.1.0/opengauss-jdbc-5.1.0.jar"
)

HOST = os.environ.get("OG_HOST", "116.148.121.37")
PORT = os.environ.get("OG_PORT", "5432")
DB = os.environ.get("OG_DB", "postgres")
USER = os.environ.get("OG_USER", "remote_app")
PASSWORD = os.environ.get("OG_PASS", "MyPass@123")
TIMEOUT = int(os.environ.get("OG_TIMEOUT", "10"))
JAR = os.environ.get("OG_JAR", DEFAULT_JAR)

# 实际 DB schema/列名 (CSV-import 损坏后). DDL 里是 remote_app / dev_dept / ...
SCHEMA = os.environ.get("OG_SCHEMA", "remote_app")
TABLE = os.environ.get("OG_TABLE", "dsqa_dwd_req_item_app_portrait_wide_inf")
COL_DEPT = os.environ.get("OG_COL_DEPT", "dev0dept")
COL_VERSION = os.environ.get("OG_COL_VERSION", "version0plan")
COL_PRODUCT_LINE = os.environ.get("OG_COL_PRODUCT_LINE", "product0line")
COL_STAT_GROUP = os.environ.get("OG_COL_STAT_GROUP", "stat0group")
COL_SCORE_STATUS = os.environ.get("OG_COL_SCORE_STATUS", "score0status0201")
COL_STANDARD_IS = os.environ.get("OG_COL_STANDARD_IS", "standard0is0201")

FILTER_DEV_DEPT = os.environ.get("DEV_DEPT", "")
FILTER_PRODUCT_LINE = os.environ.get("PRODUCT_LINE", "")
FILTER_STAT_GROUP = os.environ.get("STAT_GROUP", "")
FILTER_VERSION_PLAN = os.environ.get("VERSION_PLAN", "")


def main() -> int:
    if not os.path.exists(JAR):
        print(f"[CONFIG FAIL] opengauss-jdbc jar not found at: {JAR}")
        print("  set OG_JAR env var to its actual path")
        return 3

    print(f"[connect] {USER}@{HOST}:{PORT}/{DB} (timeout={TIMEOUT}s)")
    print(f"[jar] {JAR}")

    try:
        if not jpype.isJVMStarted():
            jpype.startJVM(classpath=[JAR])
    except Exception as e:
        print(f"[JVM FAIL] {type(e).__name__}: {e}")
        return 3

    from java.sql import DriverManager, SQLException

    url = (
        f"jdbc:postgresql://{HOST}:{PORT}/{DB}"
        f"?loginTimeout={TIMEOUT}&connectTimeout={TIMEOUT}"
    )

    try:
        conn = DriverManager.getConnection(url, USER, PASSWORD)
    except SQLException as e:
        msg = str(e.getMessage()) if e.getMessage() else str(e)
        print(f"[REJECTED] SQLSTATE={e.getSQLState()} code={e.getErrorCode()}")
        print(f"  message: {msg}")
        return 2

    try:
        # 拼 WHERE (等值 AND)
        where_parts = []
        params = []
        if FILTER_DEV_DEPT:
            where_parts.append(f'"{COL_DEPT}" = ?')
            params.append(FILTER_DEV_DEPT)
        if FILTER_PRODUCT_LINE:
            where_parts.append(f'"{COL_PRODUCT_LINE}" = ?')
            params.append(FILTER_PRODUCT_LINE)
        if FILTER_STAT_GROUP:
            where_parts.append(f'"{COL_STAT_GROUP}" = ?')
            params.append(FILTER_STAT_GROUP)
        if FILTER_VERSION_PLAN:
            where_parts.append(f'"{COL_VERSION}" = ?')
            params.append(FILTER_VERSION_PLAN)

        where_clause = (" WHERE " + " AND ".join(where_parts)) if where_parts else ""

        full_table = f'"{SCHEMA}"."{TABLE}"'

        # 4 个指标 - 直接 SQL 算
        # 1. 全量
        sql_total = f"SELECT count(*) FROM {full_table}{where_clause}"
        # 2. 完成数 (score_status_2_1 = '已完成')
        sql_completion = (
            f"SELECT count(*) FROM {full_table}{where_clause}"
            f"{' AND ' if where_clause else ' WHERE '}\"{COL_SCORE_STATUS}\" = ?"
        )
        # 3. 达标数 (standard_is_2_1 = '已达标')
        sql_standard = (
            f"SELECT count(*) FROM {full_table}{where_clause}"
            f"{' AND ' if where_clause else ' WHERE '}\"{COL_STANDARD_IS}\" = ?"
        )

        print(f"\n[SQL] total: {sql_total}")
        print(f"[SQL] completion: {sql_completion}")
        print(f"[SQL] standard: {sql_standard}")
        print(f"[params] filter values: {params}")

        total = exec_count(conn, sql_total, params)
        completion_count = exec_count(conn, sql_completion, params + ['已完成'])
        standard_count = exec_count(conn, sql_standard, params + ['已达标'])

        print()
        print("=" * 50)
        print(f"  全量 (total)        = {total}")
        print(f"  Q2-1 完成数          = {completion_count}")
        print(f"  Q2-1 达标数          = {standard_count}")
        if total > 0:
            completion_rate = (completion_count / total) * 100
            print(f"  完成率              = {completion_rate:.2f}%  (= {completion_count} / {total})")
        else:
            print("  完成率              = N/A (total=0)")
        if completion_count > 0:
            standard_rate = (standard_count / completion_count) * 100
            print(f"  达标率              = {standard_rate:.2f}%  (= {standard_count} / {completion_count})")
        else:
            print("  达标率              = N/A (completion_count=0)")
        print("=" * 50)
        print()
        print("对比 agent 回复:")
        print("  agent 应返回与上面一致的 4 个数字 (误差 <= 0.01).")
        print("  若 agent 回复与上面不符, 检查:")
        print("    1. agent 是否误把'达标率'分母用了全量 (应除以完成数)")
        print("    2. agent 是否心算百分比 (应走 arith)")
        print("    3. agent 是否过滤条件写错 (version_plan 漏掉等)")

    finally:
        conn.close()
    return 0


def exec_count(conn, sql, params):
    from java.sql import Types
    ps = conn.prepareStatement(sql)
    try:
        for i, p in enumerate(params, start=1):
            ps.setString(i, p)
        rs = ps.executeQuery()
        if rs.next():
            return rs.getInt(1)
        return 0
    finally:
        ps.close()


if __name__ == "__main__":
    sys.exit(main())
