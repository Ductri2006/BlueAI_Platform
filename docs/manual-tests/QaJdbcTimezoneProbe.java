import java.sql.DriverManager;

/** Read-only JDBC probe, restricted to a disposable fixture database. */
class QaJdbcTimezoneProbe {
    public static void main(String[] args) throws Exception {
        String base = "jdbc:mysql://fixture-db:3306/blueai_timezone_probe?useSSL=false&allowPublicKeyRetrieval=true&";
        for (String configuration : new String[]{"serverTimezone=UTC",
                "connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"}) {
            try (var connection = DriverManager.getConnection(base + configuration, "root", "qa-disposable-timezone-root");
                 var statement = connection.createStatement();
                 var row = statement.executeQuery("SELECT @@session.time_zone, @@global.time_zone")) {
                if (!row.next()) throw new IllegalStateException("Missing timezone probe row");
                System.out.println(configuration + " | session=" + row.getString(1) + " | global=" + row.getString(2));
            }
        }
    }
}
