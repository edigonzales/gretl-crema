import java.sql.DriverManager;

/** Independent JDBC oracle; compiled after the native image, never in its classpath. */
public final class DatabaseCheck {
    public static void main(String[] args) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:file:" + args[0], "sa", "");
             var statement = connection.createStatement();
             var rows = statement.executeQuery(args[1])) {
            if (!rows.next() || rows.getLong(1) != Long.parseLong(args[2]) || rows.next()) {
                throw new AssertionError("Database result differs from expectation: " + args[1]);
            }
        }
        System.out.println("DATABASE_CHECK_OK");
    }
}
