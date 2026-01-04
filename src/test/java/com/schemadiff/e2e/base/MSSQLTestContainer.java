package com.schemadiff.e2e.base;

import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Singleton container manager for MSSQL E2E tests.
 *
 * Implements the "Database-per-Test" pattern:
 * - ONE container is started at the beginning of the test suite
 * - Each test creates isolated databases inside that container
 * - Container is reused across all tests
 */
public final class MSSQLTestContainer {

    private static final String MSSQL_IMAGE = "mcr.microsoft.com/mssql/server:2022-latest";
    private static final String SA_PASSWORD = "Strong!Password123";

    private static volatile MSSQLServerContainer<?> container;
    private static final Object LOCK = new Object();

    private MSSQLTestContainer() {} // Utility class

    /**
     * Gets the singleton MSSQL container, starting it if necessary.
     */
    public static MSSQLServerContainer<?> getInstance() {
        if (container == null) {
            synchronized (LOCK) {
                if (container == null) {
                    container = createContainer();
                    container.start();

                    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                        if (container != null && container.isRunning()) {
                            container.stop();
                        }
                    }));
                }
            }
        }
        return container;
    }

    private static MSSQLServerContainer<?> createContainer() {
        return new MSSQLServerContainer<>(DockerImageName.parse(MSSQL_IMAGE))
                .acceptLicense()
                .withPassword(SA_PASSWORD)
                .withReuse(true);
    }

    /**
     * Gets a JDBC connection as SA user (can create databases).
     */
    public static Connection getRootConnection() throws SQLException {
        MSSQLServerContainer<?> c = getInstance();
        String url = String.format("jdbc:sqlserver://%s:%d;encrypt=false;trustServerCertificate=true",
                c.getHost(), c.getMappedPort(1433));
        return DriverManager.getConnection(url, "sa", SA_PASSWORD);
    }

    /**
     * Gets a JDBC connection to a specific database.
     */
    public static Connection getConnection(String database) throws SQLException {
        MSSQLServerContainer<?> c = getInstance();
        String url = String.format("jdbc:sqlserver://%s:%d;databaseName=%s;encrypt=false;trustServerCertificate=true",
                c.getHost(), c.getMappedPort(1433), database);
        return DriverManager.getConnection(url, "sa", SA_PASSWORD);
    }

    /**
     * Creates a fresh database with the given name.
     */
    public static void createDatabase(String dbName) throws SQLException {
        try (Connection conn = getRootConnection();
             Statement stmt = conn.createStatement()) {
            // Force close any existing connections
            stmt.execute("IF EXISTS (SELECT * FROM sys.databases WHERE name = '" + dbName + "') " +
                    "ALTER DATABASE [" + dbName + "] SET SINGLE_USER WITH ROLLBACK IMMEDIATE");
            stmt.execute("DROP DATABASE IF EXISTS [" + dbName + "]");
            stmt.execute("CREATE DATABASE [" + dbName + "]");
        }
    }

    /**
     * Drops a database if it exists.
     */
    public static void dropDatabase(String dbName) throws SQLException {
        try (Connection conn = getRootConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("IF EXISTS (SELECT * FROM sys.databases WHERE name = '" + dbName + "') " +
                    "ALTER DATABASE [" + dbName + "] SET SINGLE_USER WITH ROLLBACK IMMEDIATE");
            stmt.execute("DROP DATABASE IF EXISTS [" + dbName + "]");
        }
    }

    /**
     * Gets the JDBC URL for a specific database.
     */
    public static String getJdbcUrl(String database) {
        MSSQLServerContainer<?> c = getInstance();
        return String.format("jdbc:sqlserver://%s:%d;databaseName=%s;encrypt=false;trustServerCertificate=true",
                c.getHost(), c.getMappedPort(1433), database);
    }

    public static String getHost() {
        return getInstance().getHost();
    }

    public static int getPort() {
        return getInstance().getMappedPort(1433);
    }

    public static boolean isRunning() {
        return container != null && container.isRunning();
    }
}

