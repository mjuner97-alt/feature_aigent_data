"""OpenGauss connectivity test via opengauss-jdbc (called from Python through JPype).

Why not pg8000 or psycopg2?
  Both speak the standard PostgreSQL wire protocol and cannot negotiate
  OpenGauss's modified SHA256 SASL mechanism. They fail at the auth
  handshake ("none of the server's SASL authentication mechanisms are
  supported") even when the password is correct.

  py-opengauss gets further (it implements the SHA256 client hash) but on
  OpenGauss 5.0.0 the server rejects its computed hash with SQLSTATE 28P01
  "Invalid username/password, login denied" - same password works via JDBC.

  The reliable path is the official opengauss-jdbc driver (already in
  pom.xml as org.opengauss:opengauss-jdbc:5.1.0). JPype lets us call it
  from Python without writing a separate .java file.

Requirements:
    pip install jpype1
    JDK 8+ on PATH (project uses JDK 21)

Usage:
    python test_opengauss_connection.py
    # override via env vars
    OG_HOST=... OG_PORT=5432 OG_DB=postgres OG_USER=... OG_PASS=... \
        python test_opengauss_connection.py

JAR location is auto-discovered from the local Maven repo; override with
OG_JAR if you keep it elsewhere.
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
        if "Forbid remote connection with initial user" in msg:
            print()
            print("HINT: OpenGauss forbids remote login as the initial user.")
            print("      Create a non-initial user locally, then set OG_USER.")
        elif e.getSQLState() == "28P01":
            print()
            print("HINT: 28P01 = invalid password. Check OG_PASS / OG_USER.")
            print("      Note: pg8000 and psycopg2 also fail at the SASL handshake;")
            print("      this script uses opengauss-jdbc, which works.")
        return 2
    except Exception as e:
        print(f"[CONNECT FAIL] {type(e).__name__}: {e}")
        return 1

    try:
        st = conn.createStatement()
        rs = st.executeQuery("SELECT version();")
        if rs.next():
            print("[CONNECT OK]")
            print(f"  version: {rs.getString(1)}")
        rs = st.executeQuery(
            "SELECT current_user, current_database(), inet_server_addr()"
        )
        if rs.next():
            print(f"  user={rs.getString(1)} db={rs.getString(2)} "
                  f"server={rs.getString(3)}")
        rs = st.executeQuery(
            "SELECT count(*) FROM information_schema.tables "
            "WHERE table_schema NOT IN ('pg_catalog','information_schema')"
        )
        if rs.next():
            print(f"  user_tables_count: {rs.getInt(1)}")
        st.close()
    finally:
        conn.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
