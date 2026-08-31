import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

/**
 * OpenGauss connectivity test via opengauss-jdbc (fork of postgresql-jdbc).
 *
 * <p>Required jar: org.opengauss:opengauss-jdbc:5.1.0 (already in pom.xml).
 * The 5.1.0 driver class is {@code org.postgresql.Driver} (fork keeps original
 * package) and accepts URL scheme {@code jdbc:postgresql:}.
 *
 * <p>Compile + run from project root:
 * <pre>
 * JAR=~/.m2/repository/org/opengauss/opengauss-jdbc/5.1.0/opengauss-jdbc-5.1.0.jar
 * javac -cp "$JAR" src/main/resources/docs/table-mertics/OpenGaussConnectionTest.java
 * java -cp "$JAR;src/main/resources/docs/table-mertics" OpenGaussConnectionTest
 *
 * # Windows classpath separator is ';'; on Linux/macOS use ':'
 * # Override via env vars: OG_HOST, OG_PORT, OG_DB, OG_USER, OG_PASS, OG_TIMEOUT
 * </pre>
 */
public final class OpenGaussConnectionTest {

    private static String env(String k, String def) {
        String v = System.getenv(k);
        return (v == null || v.isEmpty()) ? def : v;
    }

    public static void main(String[] args) {
        String host = env("OG_HOST", "116.148.121.37");
        String port = env("OG_PORT", "5432");
        String db = env("OG_DB", "postgres");
        String user = env("OG_USER", "remote_app");
        String pass = env("OG_PASS", "MyPass@123");
        int timeout = Integer.parseInt(env("OG_TIMEOUT", "10"));

        String url = String.format(
                "jdbc:postgresql://%s:%s/%s?loginTimeout=%d&connectTimeout=%d",
                host, port, db, timeout, timeout);

        System.out.printf("[connect] %s@%s:%s/%s (timeout=%ds)%n", user, host, port, db, timeout);

        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            System.out.println("[CONFIG FAIL] opengauss-jdbc not on classpath: " + e.getMessage());
            System.exit(3);
            return;
        }

        try (Connection conn = DriverManager.getConnection(url, user, pass)) {
            System.out.println("[CONNECT OK]");
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT version()")) {
                if (rs.next()) {
                    System.out.println("  version: " + rs.getString(1));
                }
            }
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT current_user, current_database(), inet_server_addr()")) {
                if (rs.next()) {
                    System.out.printf("  user=%s db=%s server=%s%n",
                            rs.getString(1), rs.getString(2), rs.getString(3));
                }
            }
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                     "SELECT count(*) FROM information_schema.tables WHERE table_schema NOT IN ('pg_catalog','information_schema')")) {
                if (rs.next()) {
                    System.out.println("  user_tables_count: " + rs.getInt(1));
                }
            }
        } catch (Exception e) {
            Throwable cause = e;
            while (cause.getCause() != null) cause = cause.getCause();
            System.out.println("[CONNECT FAIL] " + e.getClass().getSimpleName() + ": " + e.getMessage());
            if (cause != e) {
                System.out.println("  root: " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
            }
            if (e.getMessage() != null && e.getMessage().contains("Forbid remote connection with initial user")) {
                System.out.println();
                System.out.println("HINT: OpenGauss forbids remote login as the initial user.");
                System.out.println("      Create a non-initial user locally, then retry with OG_USER set.");
            }
            System.exit(1);
        }
    }
}
