package com.schemadiff.e2e.base;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Singleton container manager for PostgreSQL E2E tests.
 *
 * Implements the "Database-per-Test" pattern:
 * - ONE container is started at the beginning of the test suite
 * - Each test creates isolated databases inside that container
 * - Container is reused across all tests (100x faster than new containers per test)
 */
public final class PostgresTestContainer {

    private static final String POSTGRES_IMAGE = "postgres:15";
    private static final String ROOT_USER = "postgres";
    private static final String ROOT_PASSWORD = "test";

    private static volatile PostgreSQLContainer<?> container;
    private static final Object LOCK = new Object();

    private PostgresTestContainer() {} // Utility class

    /**
     * Gets the singleton PostgreSQL container, starting it if necessary.
     */
    public static PostgreSQLContainer<?> getInstance() {
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

    private static PostgreSQLContainer<?> createContainer() {
        return new PostgreSQLContainer<>(DockerImageName.parse(POSTGRES_IMAGE))
                .withDatabaseName("schemadiff_test")
                .withUsername(ROOT_USER)
                .withPassword(ROOT_PASSWORD)
                .withReuse(true);
    }

    /**
     * Gets a JDBC connection as root user (can create databases).
     */
    public static Connection getRootConnection() throws SQLException {
        PostgreSQLContainer<?> c = getInstance();
        String url = String.format("jdbc:postgresql://%s:%d/postgres",
                c.getHost(), c.getMappedPort(5432));
        return DriverManager.getConnection(url, ROOT_USER, ROOT_PASSWORD);
    }

    /**
     * Gets a JDBC connection to a specific database.
     */
    public static Connection getConnection(String database) throws SQLException {
        PostgreSQLContainer<?> c = getInstance();
        String url = String.format("jdbc:postgresql://%s:%d/%s",
                c.getHost(), c.getMappedPort(5432), database);
        return DriverManager.getConnection(url, ROOT_USER, ROOT_PASSWORD);
    }

    /**
     * Creates a fresh database with the given name.
     */
    public static void createDatabase(String dbName) throws SQLException {
        try (Connection conn = getRootConnection();
             Statement stmt = conn.createStatement()) {
            // Terminate existing connections
            stmt.execute("SELECT pg_terminate_backend(pg_stat_activity.pid) " +
                    "FROM pg_stat_activity WHERE pg_stat_activity.datname = '" + dbName + "' " +
                    "AND pid <> pg_backend_pid()");
            stmt.execute("DROP DATABASE IF EXISTS \"" + dbName + "\"");
            stmt.execute("CREATE DATABASE \"" + dbName + "\"");
        }
    }

    /**
     * Drops a database if it exists.
     */
    public static void dropDatabase(String dbName) throws SQLException {
        try (Connection conn = getRootConnection();
             Statement stmt = conn.createStatement()) {
            // Terminate existing connections first
            stmt.execute("SELECT pg_terminate_backend(pg_stat_activity.pid) " +
                    "FROM pg_stat_activity WHERE pg_stat_activity.datname = '" + dbName + "' " +
                    "AND pid <> pg_backend_pid()");
            stmt.execute("DROP DATABASE IF EXISTS \"" + dbName + "\"");
        }
    }

    /**
     * Gets the JDBC URL for a specific database.
     */
    public static String getJdbcUrl(String database) {
        PostgreSQLContainer<?> c = getInstance();
        return String.format("jdbc:postgresql://%s:%d/%s",
                c.getHost(), c.getMappedPort(5432), database);
    }

    public static String getHost() {
        return getInstance().getHost();
    }

    public static int getPort() {
        return getInstance().getMappedPort(5432);
    }

    public static boolean isRunning() {
        return container != null && container.isRunning();
    }
}

