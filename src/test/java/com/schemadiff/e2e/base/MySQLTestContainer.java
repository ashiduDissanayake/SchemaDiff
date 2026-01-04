package com.schemadiff.e2e.base;

import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Singleton container manager for MySQL E2E tests.
 *
 * Implements the "Database-per-Test" pattern:
 * - ONE container is started at the beginning of the test suite
 * - Each test creates isolated databases inside that container
 * - Container is reused across all tests (100x faster than new containers per test)
 *
 * Thread Safety: Container startup is synchronized, but database operations
 * should use isolated database names per test to allow parallel execution.
 */
public final class MySQLTestContainer {

    private static final String MYSQL_IMAGE = "mysql:8.0";
    private static final String ROOT_USER = "root";
    private static final String ROOT_PASSWORD = "test";

    private static volatile MySQLContainer<?> container;
    private static final Object LOCK = new Object();

    private MySQLTestContainer() {} // Utility class

    /**
     * Gets the singleton MySQL container, starting it if necessary.
     * Thread-safe lazy initialization.
     */
    public static MySQLContainer<?> getInstance() {
        if (container == null) {
            synchronized (LOCK) {
                if (container == null) {
                    container = createContainer();
                    container.start();

                    // Register shutdown hook for cleanup
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

    /**
     * Creates a new MySQL container configured for testing.
     */
    private static MySQLContainer<?> createContainer() {
        return new MySQLContainer<>(DockerImageName.parse(MYSQL_IMAGE))
                .withDatabaseName("schemadiff_test")
                .withUsername(ROOT_USER)
                .withPassword(ROOT_PASSWORD)
                // Allow creating multiple databases
                .withEnv("MYSQL_ROOT_PASSWORD", ROOT_PASSWORD)
                // Performance optimizations for testing
                .withCommand(
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_unicode_ci",
                    "--skip-log-bin",  // Faster writes
                    "--innodb-flush-log-at-trx-commit=0",  // Less durable, faster
                    "--innodb-doublewrite=0"
                )
                .withReuse(true);  // Enable container reuse for faster CI
    }

    /**
     * Gets a JDBC connection to the container as root user.
     * Can create databases and execute admin commands.
     */
    public static Connection getRootConnection() throws SQLException {
        MySQLContainer<?> c = getInstance();
        String url = String.format("jdbc:mysql://%s:%d/?allowMultiQueries=true&useSSL=false&allowPublicKeyRetrieval=true",
                c.getHost(), c.getMappedPort(3306));
        return DriverManager.getConnection(url, ROOT_USER, ROOT_PASSWORD);
    }

    /**
     * Gets a JDBC connection to a specific database.
     */
    public static Connection getConnection(String database) throws SQLException {
        MySQLContainer<?> c = getInstance();
        String url = String.format("jdbc:mysql://%s:%d/%s?allowMultiQueries=true&useSSL=false&allowPublicKeyRetrieval=true",
                c.getHost(), c.getMappedPort(3306), database);
        return DriverManager.getConnection(url, ROOT_USER, ROOT_PASSWORD);
    }

    /**
     * Creates a fresh database with the given name.
     * Drops the database first if it exists.
     */
    public static void createDatabase(String dbName) throws SQLException {
        try (Connection conn = getRootConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP DATABASE IF EXISTS " + dbName);
            stmt.execute("CREATE DATABASE " + dbName + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
    }

    /**
     * Drops a database if it exists.
     */
    public static void dropDatabase(String dbName) throws SQLException {
        try (Connection conn = getRootConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP DATABASE IF EXISTS " + dbName);
        }
    }

    /**
     * Gets the JDBC URL for a specific database.
     * Useful for SchemaDiff Live mode testing.
     */
    public static String getJdbcUrl(String database) {
        MySQLContainer<?> c = getInstance();
        return String.format("jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true",
                c.getHost(), c.getMappedPort(3306), database);
    }

    /**
     * Gets the container host.
     */
    public static String getHost() {
        return getInstance().getHost();
    }

    /**
     * Gets the mapped MySQL port.
     */
    public static int getPort() {
        return getInstance().getMappedPort(3306);
    }

    /**
     * Checks if the container is running.
     */
    public static boolean isRunning() {
        return container != null && container.isRunning();
    }
}

