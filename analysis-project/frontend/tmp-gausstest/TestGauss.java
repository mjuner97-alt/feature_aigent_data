import java.sql.*;

/** 手动验证 Gauss 连接: java -cp opengauss-jdbc-5.1.0.jar;. TestGauss <jdbc-url> */
public class TestGauss {
    public static void main(String[] a) throws Exception {
        String url = a[0];
        try (Connection c = DriverManager.getConnection(url, "readwriter", "readwriter123")) {
            System.out.println("CONNECTED: " + c.getMetaData().getDatabaseProductVersion());
        } catch (SQLException e) {
            System.out.println("FAILED: " + e.getMessage());
            Throwable r = e.getCause();
            while (r != null) { System.out.println("  caused by: " + r); r = r.getCause(); }
        }
    }
}
