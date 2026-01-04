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
 * Test context for Oracle E2E tests.
 *
 * Note: Oracle uses schemas (users) instead of databases.
 * Each "database" in this context is actually a user/schema.
 */
public class OracleTestContext implements GenericTestContext {

    private static final AtomicInteger COUNTER = new AtomicInteger(0);

    private final String testName;
    private final String refDbName;
    private final String targetDbName;
    private boolean cleaned = false;

    private OracleTestContext(String testName) {
        this.testName = testName;
        // Oracle identifiers limited to 30 chars (128 in 12.2+), keep it short
        String uniqueId = String.valueOf(COUNTER.incrementAndGet());
        this.refDbName = "REF" + sanitize(testName).substring(0, Math.min(10, sanitize(testName).length())) + uniqueId;
        this.targetDbName = "TGT" + sanitize(testName).substring(0, Math.min(10, sanitize(testName).length())) + uniqueId;
    }

    public static OracleTestContext create(String testName) {
        return new OracleTestContext(testName);
    }

    @Override
    public void setupBothSchemas(String schemaResourcePath) throws SQLException, IOException {
        setupReferenceSchema(schemaResourcePath);
        setupTargetSchema(schemaResourcePath);
    }

    @Override
    public void setupReferenceSchema(String schemaResourcePath) throws SQLException, IOException {
        OracleTestContainer.createDatabase(refDbName);
        executeScript(refDbName, schemaResourcePath);
    }

    @Override
    public void setupTargetSchema(String schemaResourcePath) throws SQLException, IOException {
        OracleTestContainer.createDatabase(targetDbName);
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
        try (Connection conn = OracleTestContainer.getConnection(targetDbName);
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
        try (Connection conn = OracleTestContainer.getConnection(refDbName);
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
        return OracleTestContainer.getJdbcUrl(refDbName);
    }

    public String getTargetJdbcUrl() {
        return OracleTestContainer.getJdbcUrl(targetDbName);
    }

    @Override
    public Connection getRefConnection() throws SQLException {
        return OracleTestContainer.getConnection(refDbName);
    }

    @Override
    public Connection getTargetConnection() throws SQLException {
        return OracleTestContainer.getConnection(targetDbName);
    }

    @Override
    public void cleanup() {
        if (cleaned) return;
        cleaned = true;

        try {
            OracleTestContainer.dropDatabase(refDbName);
        } catch (SQLException e) {
            System.err.println("Warning: Failed to drop " + refDbName + ": " + e.getMessage());
        }

        try {
            OracleTestContainer.dropDatabase(targetDbName);
        } catch (SQLException e) {
            System.err.println("Warning: Failed to drop " + targetDbName + ": " + e.getMessage());
        }
    }

    private void executeScript(String schema, String resourcePath) throws SQLException, IOException {
        String sql = loadResource(resourcePath);

        try (Connection conn = OracleTestContainer.getConnection(schema);
             Statement stmt = conn.createStatement()) {
            for (String statement : splitStatements(sql)) {
                String trimmed = statement.trim();
                if (trimmed.isEmpty()) continue;
                if (trimmed.startsWith("--") && !trimmed.contains("\n")) continue;
                // Skip Oracle-specific terminator
                if (trimmed.equals("/")) continue;

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
                // Remove trailing slash if present
                if (executable.endsWith("/")) {
                    executable = executable.substring(0, executable.length() - 1).trim();
                }

                try {
                    stmt.execute(executable);
                } catch (SQLException e) {
                    if (!executable.toUpperCase().contains("INSERT")) {
                        System.err.println("[OracleTestContext] Warning: " + schema + ": " +
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
        // Oracle uses ; or / as statement terminators
        return sql.split(";(?=([^']*'[^']*')*[^']*$)|/\\s*\\n");
    }

    private String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();
    }
}

