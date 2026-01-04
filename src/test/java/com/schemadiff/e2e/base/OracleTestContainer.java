package com.schemadiff.e2e.base;

import org.testcontainers.containers.OracleContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;

/**
 * Singleton container manager for Oracle E2E tests.
 *
 * Uses Oracle XE container from gvenzl. Each "database" is actually a schema/user
 * within the PDB (pluggable database).
 *
 * Note: Oracle containers are slow to start (~60-120s) compared to MySQL/PostgreSQL.
 * First-time image pull can take 5-10 minutes.
 */
public final class OracleTestContainer {

    // Use gvenzl/oracle-xe for better compatibility with testcontainers
    // oracle-xe uses service name "xepdb1", oracle-free uses "freepdb1"
    private static final String ORACLE_IMAGE = "gvenzl/oracle-xe:21-slim";
    private static final String SYSTEM_PASSWORD = "test";
    // Oracle startup is slow - allow up to 10 minutes for first-time pull + startup
    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(10);

    private static volatile OracleContainer container;
    private static final Object LOCK = new Object();

    private OracleTestContainer() {} // Utility class

    /**
     * Gets the singleton Oracle container, starting it if necessary.
     */
    public static OracleContainer getInstance() {
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

    private static OracleContainer createContainer() {
        DockerImageName imageName = DockerImageName.parse(ORACLE_IMAGE)
                .asCompatibleSubstituteFor("gvenzl/oracle-xe");
        return new OracleContainer(imageName)
                .withPassword(SYSTEM_PASSWORD)
                .withStartupTimeout(STARTUP_TIMEOUT)
                .withReuse(true);
    }

    /**
     * Gets a JDBC connection as SYSTEM user (can create users/schemas).
     */
    public static Connection getRootConnection() throws SQLException {
        OracleContainer c = getInstance();
        return DriverManager.getConnection(c.getJdbcUrl(), "system", SYSTEM_PASSWORD);
    }

    /**
     * Gets a JDBC connection to a specific schema/user.
     */
    public static Connection getConnection(String schema) throws SQLException {
        OracleContainer c = getInstance();
        return DriverManager.getConnection(c.getJdbcUrl(), schema, "test");
    }

    /**
     * Creates a schema (user) with the given name.
     * In Oracle, schemas = users, so we create a user with full privileges.
     */
    public static void createDatabase(String schemaName) throws SQLException {
        String upperSchema = schemaName.toUpperCase();
        try (Connection conn = getRootConnection();
             Statement stmt = conn.createStatement()) {
            // Drop user if exists
            try {
                stmt.execute("DROP USER " + upperSchema + " CASCADE");
            } catch (SQLException e) {
                // User doesn't exist, ignore
            }
            // Create user with password 'test'
            stmt.execute("CREATE USER " + upperSchema + " IDENTIFIED BY test");
            // Grant privileges
            stmt.execute("GRANT CREATE SESSION, CREATE TABLE, CREATE VIEW, CREATE SEQUENCE, " +
                    "CREATE PROCEDURE, CREATE TRIGGER, UNLIMITED TABLESPACE TO " + upperSchema);
            stmt.execute("ALTER USER " + upperSchema + " QUOTA UNLIMITED ON USERS");
        }
    }

    /**
     * Drops a schema (user) if it exists.
     */
    public static void dropDatabase(String schemaName) throws SQLException {
        String upperSchema = schemaName.toUpperCase();
        try (Connection conn = getRootConnection();
             Statement stmt = conn.createStatement()) {
            try {
                stmt.execute("DROP USER " + upperSchema + " CASCADE");
            } catch (SQLException e) {
                // User doesn't exist, ignore
            }
        }
    }

    /**
     * Gets the JDBC URL for a specific schema.
     * Note: Oracle connection URL doesn't change per schema - schema is in the user.
     */
    public static String getJdbcUrl(String schema) {
        return getInstance().getJdbcUrl();
    }

    public static String getHost() {
        return getInstance().getHost();
    }

    public static int getPort() {
        return getInstance().getOraclePort();
    }

    public static boolean isRunning() {
        return container != null && container.isRunning();
    }
}

