package com.schemadiff.container;

import com.schemadiff.model.DatabaseType;
import org.testcontainers.containers.*;

import java.sql.Connection;
import java.sql.DriverManager;

public class ContainerManager {
    private GenericContainer<?> container;
    private String jdbcUrl;
    private String username;
    private String password;

    public ContainerManager(String image, DatabaseType type) {
        this.container = createContainer(image, type);
    }

    private GenericContainer<? > createContainer(String image, DatabaseType type) {
        return switch (type) {
            case POSTGRES -> new PostgreSQLContainer<>(image);
            case MYSQL -> new MySQLContainer<>(image)
                    .withCommand("--character-set-server=latin1", "--collation-server=latin1_swedish_ci")
                    .withUrlParam("allowMultiQueries", "true");
            case ORACLE -> new OracleContainer(image).withReuse(false);
            case MSSQL -> new MSSQLServerContainer<>(image).acceptLicense();
            case DB2 -> new Db2Container(image).acceptLicense();
        };
    }

    public void start() {
        container.start();

        if (container instanceof JdbcDatabaseContainer) {
            JdbcDatabaseContainer<?> jdbc = (JdbcDatabaseContainer<?>) container;
            this.jdbcUrl = jdbc.getJdbcUrl();
            this.username = jdbc.getUsername();
            this.password = jdbc. getPassword();
        }
    }

    public void stop() {
        if (container != null) container.stop();
    }

    public Connection getConnection() throws Exception {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }
}