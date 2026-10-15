import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * One-off JDBC runner for the .sql files in this directory (no Chinese literals in this
 * source to stay encoding-safe; all SQL/data is read from UTF-8 files).
 *
 * Usage: java -cp <postgresql-driver.jar> RdcTrainCalendarSeed.java <url> <user> <password> <file.sql>
 */
public class RdcTrainCalendarSeed {

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            System.err.println("Usage: <url> <user> <password> <file.sql>");
            System.exit(2);
        }
        String url = args[0];
        String user = args[1];
        String password = args[2];
        String sql = Files.readString(Path.of(args[3]), StandardCharsets.UTF_8);

        StringBuilder stripped = new StringBuilder();
        for (String line : sql.split("\n", -1)) {
            if (line.trim().startsWith("--")) continue;
            stripped.append(line).append('\n');
        }

        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            for (String part : stripped.toString().split(";")) {
                String stmt = part.trim();
                if (stmt.isEmpty()) continue;
                try (Statement st = conn.createStatement()) {
                    boolean hasRs = st.execute(stmt);
                    System.out.println("[OK] " + firstLine(stmt));
                    if (hasRs) {
                        try (ResultSet rs = st.getResultSet()) {
                            int n = rs.getMetaData().getColumnCount();
                            while (rs.next()) {
                                StringBuilder sb = new StringBuilder("  row:");
                                for (int i = 1; i <= n; i++) {
                                    sb.append(' ').append(rs.getString(i));
                                }
                                System.out.println(sb);
                            }
                        }
                    }
                }
            }
        }
        System.out.println("[DONE] " + args[3]);
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        String head = (i < 0 ? s : s.substring(0, i)).trim();
        return head.length() > 80 ? head.substring(0, 80) + "..." : head;
    }
}
