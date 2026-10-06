import java.sql.DriverManager;

class QaJdbcContractProbe {
    public static void main(String[] args) throws Exception {
        String url = args[0];
        if (!url.startsWith("jdbc:mysql://fixture-db:3306/blueai_timezone_probe?"))
            throw new IllegalArgumentException("Only the disposable database is allowed");
        try (var connection = DriverManager.getConnection(url, "root", "qa-disposable-timezone-root");
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT @@session.time_zone, @@global.time_zone")) {
            if (!rows.next() || !"+00:00".equals(rows.getString(1)) || !"+07:00".equals(rows.getString(2)))
                throw new AssertionError("Deployment URL failed to force a UTC session on a non-UTC MySQL server");
            System.out.println("PASS deployment JDBC: session=+00:00, global=+07:00, driver=" + connection.getMetaData().getDriverVersion());
        }
    }
}
