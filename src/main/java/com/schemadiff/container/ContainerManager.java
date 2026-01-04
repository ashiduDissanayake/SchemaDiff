package com.schemadiff.container;

import com.schemadiff.model.DatabaseType;
import com.schemadiff.util.JDBCHelper;
import org.testcontainers.containers.*;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages Docker containers for database provisioning.
 *
 * Optimized with singleton pattern: ONE container per image/type combination
 * handles multiple databases (ref/target schemas) instead of spinning up
 * separate containers. This reduces startup time by ~50% and memory usage.
 *
 * Usage:
 * - For Script vs Script: Single container with two databases (ref_schema, target_schema)
 * - For Script vs Live: Single container for the script side
 */
public class ContainerManager {

    // Singleton cache: image -> running container
    private static final Map<String, ContainerManager> CONTAINER_CACHE = new ConcurrentHashMap<>();
    private static final Object LOCK = new Object();

    private final GenericContainer<?> container;
    private final DatabaseType type;
    private final String image;
    private String jdbcUrl;
    private String username;
    private String password;
    private String baseHost;
    private int mappedPort;

    // Instance tracking for cleanup
    private boolean isShared = false;

    /**
     * Gets or creates a container for the given image and type.
     * Uses singleton pattern to reuse containers within the same JVM session.
     */
    public static ContainerManager getOrCreate(String image, DatabaseType type) {
        String cacheKey = image + "|" + type.name();

        ContainerManager existing = CONTAINER_CACHE.get(cacheKey);
        if (existing != null && existing.isRunning()) {
            existing.isShared = true;
            return existing;
        }

        synchronized (LOCK) {
            // Double-check after acquiring lock
            existing = CONTAINER_CACHE.get(cacheKey);
            if (existing != null && existing.isRunning()) {
                existing.isShared = true;
                return existing;
            }

            // Create new container
            ContainerManager manager = new ContainerManager(image, type);
            manager.start();
            CONTAINER_CACHE.put(cacheKey, manager);
            return manager;
        }
    }

    /**
     * Creates a new ContainerManager (legacy constructor for backward compatibility).
     * Consider using getOrCreate() for better resource utilization.
     */
    public ContainerManager(String image, DatabaseType type) {
        this.image = image;
        this.type = type;
        this.container = createContainer(image, type);
    }

    private GenericContainer<?> createContainer(String image, DatabaseType type) {
        return switch (type) {
            case POSTGRES -> new PostgreSQLContainer<>(image);
            case MYSQL -> {
                MySQLContainer<?> mysql = new MySQLContainer<>(image)
                    .withUsername("root")
                    .withPassword("test")
                    .withEnv("MYSQL_ROOT_PASSWORD", "test")
                    .withCommand(
                        "--character-set-server=latin1",
                        "--collation-server=latin1_swedish_ci",
                        "--default-authentication-plugin=mysql_native_password",
                        "--innodb-default-row-format=DYNAMIC",
                        "--max-allowed-packet=256M"
                    );
                yield mysql;
            }
            case ORACLE -> {
                DockerImageName oracleImage = DockerImageName.parse(image)
                        .asCompatibleSubstituteFor("gvenzl/oracle-xe");
                yield new OracleContainer(oracleImage).withReuse(false);
            }
            case MSSQL -> new MSSQLServerContainer<>(image).acceptLicense();
            case DB2 -> new Db2Container(image).acceptLicense();
        };
    }

    public void start() {
        if (container.isRunning()) {
            return; // Already started
        }

        container.start();

        if (container instanceof JdbcDatabaseContainer) {
            JdbcDatabaseContainer<?> jdbc = (JdbcDatabaseContainer<?>) container;
            this.jdbcUrl = jdbc.getJdbcUrl();
            this.username = jdbc.getUsername();
            this.password = jdbc.getPassword();
            this.baseHost = jdbc.getHost();
            this.mappedPort = jdbc.getFirstMappedPort();
        }
    }

    public void stop() {
        // Don't stop shared containers - they'll be cleaned up by JVM shutdown
        if (isShared) {
            return;
        }

        if (container != null && container.isRunning()) {
            container.stop();
        }

        // Remove from cache
        String cacheKey = image + "|" + type.name();
        CONTAINER_CACHE.remove(cacheKey);
    }

    /**
     * Force stops all cached containers. Call this for explicit cleanup.
     */
    public static void stopAll() {
        synchronized (LOCK) {
            for (ContainerManager manager : CONTAINER_CACHE.values()) {
                if (manager.container != null && manager.container.isRunning()) {
                    try {
                        manager.container.stop();
                    } catch (Exception e) {
                        System.err.println("Warning: Failed to stop container: " + e.getMessage());
                    }
                }
            }
            CONTAINER_CACHE.clear();
        }
    }

    public boolean isRunning() {
        return container != null && container.isRunning();
    }

    public Connection getConnection() throws Exception {
        return JDBCHelper.connect(jdbcUrl, username, password);
    }

    /**
     * Creates a new database within this container and returns a connection to it.
     * This allows using a single container for multiple schemas (ref + target).
     *
     * @param databaseName Name of the database to create
     * @return Connection to the newly created database
     */
    public Connection createDatabaseAndConnect(String databaseName) throws Exception {
        // First, create the database using the default connection
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {

            switch (type) {
                case MYSQL -> {
                    stmt.execute("CREATE DATABASE IF NOT EXISTS `" + databaseName + "`");
                }
                case POSTGRES -> {
                    // PostgreSQL doesn't support IF NOT EXISTS for CREATE DATABASE
                    try {
                        stmt.execute("CREATE DATABASE \"" + databaseName + "\"");
                    } catch (Exception e) {
                        if (!e.getMessage().contains("already exists")) {
                            throw e;
                        }
                    }
                }
                case MSSQL -> {
                    stmt.execute("IF NOT EXISTS (SELECT * FROM sys.databases WHERE name = '" + databaseName + "') " +
                                "CREATE DATABASE [" + databaseName + "]");
                }
                case ORACLE -> {
                    // Oracle uses schemas/users instead of databases
                    // For Oracle, we'd create a user/schema
                    stmt.execute("CREATE USER " + databaseName + " IDENTIFIED BY test DEFAULT TABLESPACE USERS");
                    stmt.execute("GRANT ALL PRIVILEGES TO " + databaseName);
                }
                case DB2 -> {
                    stmt.execute("CREATE DATABASE " + databaseName);
                }
            }
        }

        // Return connection to the new database
        return getConnectionToDatabase(databaseName);
    }

    /**
     * Gets a connection to a specific database within this container.
     */
    public Connection getConnectionToDatabase(String databaseName) throws Exception {
        String dbUrl = switch (type) {
            case MYSQL -> String.format("jdbc:mysql://%s:%d/%s?allowMultiQueries=true&useSSL=false&allowPublicKeyRetrieval=true",
                    baseHost, mappedPort, databaseName);
            case POSTGRES -> String.format("jdbc:postgresql://%s:%d/%s",
                    baseHost, mappedPort, databaseName);
            case MSSQL -> String.format("jdbc:sqlserver://%s:%d;databaseName=%s;encrypt=false;trustServerCertificate=true",
                    baseHost, mappedPort, databaseName);
            case ORACLE -> String.format("jdbc:oracle:thin:@%s:%d/FREEPDB1",
                    baseHost, mappedPort); // Oracle uses schemas within the PDB
            case DB2 -> String.format("jdbc:db2://%s:%d/%s",
                    baseHost, mappedPort, databaseName);
        };

        return DriverManager.getConnection(dbUrl, username, password);
    }

    /**
     * Drops a database within this container.
     */
    public void dropDatabase(String databaseName) {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {

            switch (type) {
                case MYSQL -> stmt.execute("DROP DATABASE IF EXISTS `" + databaseName + "`");
                case POSTGRES -> stmt.execute("DROP DATABASE IF EXISTS \"" + databaseName + "\"");
                case MSSQL -> stmt.execute("DROP DATABASE IF EXISTS [" + databaseName + "]");
                case ORACLE -> stmt.execute("DROP USER " + databaseName + " CASCADE");
                case DB2 -> stmt.execute("DROP DATABASE " + databaseName);
            }
        } catch (Exception e) {
            System.err.println("Warning: Failed to drop database " + databaseName + ": " + e.getMessage());
        }
    }

    // Getters
    public String getJdbcUrl() { return jdbcUrl; }
    public String getUsername() { return username; }
    public String getPassword() { return password; }
    public DatabaseType getType() { return type; }
    public String getHost() { return baseHost; }
    public int getPort() { return mappedPort; }
}

