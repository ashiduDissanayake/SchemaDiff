package com.schemadiff.e2e.base;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test context for MSSQL E2E tests.
 */
public class MSSQLTestContext implements GenericTestContext {

    private static final AtomicInteger COUNTER = new AtomicInteger(0);

    private final String testName;
    private final String refDbName;
    private final String targetDbName;
    private boolean cleaned = false;

    private MSSQLTestContext(String testName) {
        this.testName = testName;
        String uniqueId = String.valueOf(COUNTER.incrementAndGet()) + "_" +
                          UUID.randomUUID().toString().substring(0, 8);
        this.refDbName = "ref_" + sanitize(testName) + "_" + uniqueId;
        this.targetDbName = "target_" + sanitize(testName) + "_" + uniqueId;
    }

    public static MSSQLTestContext create(String testName) {
        return new MSSQLTestContext(testName);
    }

    @Override
    public void setupBothSchemas(String schemaResourcePath) throws SQLException, IOException {
        setupReferenceSchema(schemaResourcePath);
        setupTargetSchema(schemaResourcePath);
    }

    @Override
    public void setupReferenceSchema(String schemaResourcePath) throws SQLException, IOException {
        MSSQLTestContainer.createDatabase(refDbName);
        executeScript(refDbName, schemaResourcePath);
    }

    @Override
    public void setupTargetSchema(String schemaResourcePath) throws SQLException, IOException {
        MSSQLTestContainer.createDatabase(targetDbName);
        executeScript(targetDbName, schemaResourcePath);
    }

    @Override
    public void applyDelta(String deltaResourcePath) throws SQLException, IOException {
        executeScript(targetDbName, deltaResourcePath);
    }

    @Override
    public void applyDeltaToReference(String deltaResourcePath) throws SQLException, IOException {
        executeScript(refDbName, deltaResourcePath);
    }

    @Override
    public void executeOnTarget(String sql) throws SQLException {
        try (Connection conn = MSSQLTestContainer.getConnection(targetDbName);
             Statement stmt = conn.createStatement()) {
            for (String statement : splitStatements(sql)) {
                if (!statement.trim().isEmpty()) {
                    stmt.execute(statement);
                }
            }
        }
    }

    @Override
    public void executeOnReference(String sql) throws SQLException {
        try (Connection conn = MSSQLTestContainer.getConnection(refDbName);
             Statement stmt = conn.createStatement()) {
            for (String statement : splitStatements(sql)) {
                if (!statement.trim().isEmpty()) {
                    stmt.execute(statement);
                }
            }
        }
    }

    @Override
    public String getRefDbName() {
        return refDbName;
    }

    @Override
    public String getTargetDbName() {
        return targetDbName;
    }

    public String getRefJdbcUrl() {
        return MSSQLTestContainer.getJdbcUrl(refDbName);
    }

    public String getTargetJdbcUrl() {
        return MSSQLTestContainer.getJdbcUrl(targetDbName);
    }

    @Override
    public Connection getRefConnection() throws SQLException {
        return MSSQLTestContainer.getConnection(refDbName);
    }

    @Override
    public Connection getTargetConnection() throws SQLException {
        return MSSQLTestContainer.getConnection(targetDbName);
    }

    @Override
    public void cleanup() {
        if (cleaned) return;
        cleaned = true;

        try {
            MSSQLTestContainer.dropDatabase(refDbName);
        } catch (SQLException e) {
            System.err.println("Warning: Failed to drop " + refDbName + ": " + e.getMessage());
        }

        try {
            MSSQLTestContainer.dropDatabase(targetDbName);
        } catch (SQLException e) {
            System.err.println("Warning: Failed to drop " + targetDbName + ": " + e.getMessage());
        }
    }

    private void executeScript(String database, String resourcePath) throws SQLException, IOException {
        String sql = loadResource(resourcePath);

        try (Connection conn = MSSQLTestContainer.getConnection(database);
             Statement stmt = conn.createStatement()) {
            // MSSQL uses GO as batch separator, split by both ; and GO
            for (String statement : splitStatements(sql)) {
                String trimmed = statement.trim();
                if (trimmed.isEmpty()) continue;
                if (trimmed.equalsIgnoreCase("GO")) continue;
                if (trimmed.startsWith("--") && !trimmed.contains("\n")) continue;

                String executable = trimmed;
                while (executable.startsWith("--")) {
                    int newlineIdx = executable.indexOf('\n');
                    if (newlineIdx > 0) {
                        executable = executable.substring(newlineIdx + 1).trim();
                    } else {
                        executable = "";
                        break;
                    }
                }
                if (executable.isEmpty()) continue;

                try {
                    stmt.execute(executable);
                } catch (SQLException e) {
                    if (!executable.toUpperCase().contains("INSERT")) {
                        System.err.println("[MSSQLTestContext] Warning: " + database + ": " +
                                executable.substring(0, Math.min(60, executable.length())) +
                                "... - " + e.getMessage());
                    }
                }
            }
        }
    }

    private String loadResource(String path) throws IOException {
        try (InputStream is = getClass().getResourceAsStream(path)) {
            if (is == null) {
                throw new IOException("Resource not found: " + path);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String[] splitStatements(String sql) {
        // Split by semicolon or GO (MSSQL batch separator)
        return sql.split(";(?=([^']*'[^']*')*[^']*$)|\\bGO\\b");
    }

    private String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9]", "_").toLowerCase();
    }
}

