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
 * Test context for a single E2E test case.
 *
 * Provides:
 * - Unique database names (ref_xxx, target_xxx) per test
 * - SQL script execution
 * - Cleanup utilities
 *
 * Usage:
 * <pre>
 * TestContext ctx = TestContext.create("my_test");
 * try {
 *     ctx.setupReferenceSchema("/mysql/schemas/minimal_reference.sql");
 *     ctx.setupTargetSchema("/mysql/schemas/minimal_reference.sql");
 *     ctx.applyDelta("/mysql/testcases/tables/missing/delta.sql");
 *     // Run comparison...
 * } finally {
 *     ctx.cleanup();
 * }
 * </pre>
 */
public class TestContext implements AutoCloseable {

    private static final AtomicInteger COUNTER = new AtomicInteger(0);

    private final String testName;
    private final String refDbName;
    private final String targetDbName;
    private boolean cleaned = false;

    private TestContext(String testName) {
        this.testName = testName;
        // Generate unique names using counter + short UUID to avoid collisions
        String uniqueId = String.valueOf(COUNTER.incrementAndGet()) + "_" +
                          UUID.randomUUID().toString().substring(0, 8);
        this.refDbName = "ref_" + sanitize(testName) + "_" + uniqueId;
        this.targetDbName = "target_" + sanitize(testName) + "_" + uniqueId;
    }

    /**
     * Creates a new test context with unique database names.
     */
    public static TestContext create(String testName) {
        return new TestContext(testName);
    }

    /**
     * Sets up both reference and target databases with the same schema.
     * This is the common case where target starts identical to reference.
     */
    public void setupBothSchemas(String schemaResourcePath) throws SQLException, IOException {
        setupReferenceSchema(schemaResourcePath);
        setupTargetSchema(schemaResourcePath);
    }

    /**
     * Sets up the reference database with the given schema.
     */
    public void setupReferenceSchema(String schemaResourcePath) throws SQLException, IOException {
        MySQLTestContainer.createDatabase(refDbName);
        executeScript(refDbName, schemaResourcePath);
    }

    /**
     * Sets up the target database with the given schema.
     */
    public void setupTargetSchema(String schemaResourcePath) throws SQLException, IOException {
        MySQLTestContainer.createDatabase(targetDbName);
        executeScript(targetDbName, schemaResourcePath);
    }

    /**
     * Applies a delta script to the target database.
     * Delta scripts contain ALTER, DROP, or ADD statements.
     */
    public void applyDelta(String deltaResourcePath) throws SQLException, IOException {
        executeScript(targetDbName, deltaResourcePath);
    }

    /**
     * Applies a delta script to the reference database.
     * Used for testing "extra" scenarios where target has more than reference.
     */
    public void applyDeltaToReference(String deltaResourcePath) throws SQLException, IOException {
        executeScript(refDbName, deltaResourcePath);
    }

    /**
     * Executes raw SQL on the target database.
     */
    public void executeOnTarget(String sql) throws SQLException {
        try (Connection conn = MySQLTestContainer.getConnection(targetDbName);
             Statement stmt = conn.createStatement()) {
            for (String statement : splitStatements(sql)) {
                if (!statement.trim().isEmpty()) {
                    stmt.execute(statement);
                }
            }
        }
    }

    /**
     * Executes raw SQL on the reference database.
     */
    public void executeOnReference(String sql) throws SQLException {
        try (Connection conn = MySQLTestContainer.getConnection(refDbName);
             Statement stmt = conn.createStatement()) {
            for (String statement : splitStatements(sql)) {
                if (!statement.trim().isEmpty()) {
                    stmt.execute(statement);
                }
            }
        }
    }

    /**
     * Gets the reference database name.
     */
    public String getRefDbName() {
        return refDbName;
    }

    /**
     * Gets the target database name.
     */
    public String getTargetDbName() {
        return targetDbName;
    }

    /**
     * Gets JDBC URL for the reference database.
     */
    public String getRefJdbcUrl() {
        return MySQLTestContainer.getJdbcUrl(refDbName);
    }

    /**
     * Gets JDBC URL for the target database.
     */
    public String getTargetJdbcUrl() {
        return MySQLTestContainer.getJdbcUrl(targetDbName);
    }

    /**
     * Gets a connection to the reference database.
     */
    public Connection getRefConnection() throws SQLException {
        return MySQLTestContainer.getConnection(refDbName);
    }

    /**
     * Gets a connection to the target database.
     */
    public Connection getTargetConnection() throws SQLException {
        return MySQLTestContainer.getConnection(targetDbName);
    }

    /**
     * Cleans up databases created by this context.
     */
    public void cleanup() {
        if (cleaned) return;
        cleaned = true;

        try {
            MySQLTestContainer.dropDatabase(refDbName);
        } catch (SQLException e) {
            System.err.println("Warning: Failed to drop " + refDbName + ": " + e.getMessage());
        }

        try {
            MySQLTestContainer.dropDatabase(targetDbName);
        } catch (SQLException e) {
            System.err.println("Warning: Failed to drop " + targetDbName + ": " + e.getMessage());
        }
    }

    @Override
    public void close() {
        cleanup();
    }

    // === Private Helpers ===

    private void executeScript(String database, String resourcePath) throws SQLException, IOException {
        String sql = loadResource(resourcePath);

        try (Connection conn = MySQLTestContainer.getConnection(database);
             Statement stmt = conn.createStatement()) {
            for (String statement : splitStatements(sql)) {
                String trimmed = statement.trim();
                // Skip empty statements and pure comment lines
                if (trimmed.isEmpty()) continue;
                if (trimmed.startsWith("--") && !trimmed.contains("\n")) continue;

                // Remove leading comments from multi-line statements
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
                    // Log but continue - some statements may fail (e.g., DROP IF NOT EXISTS)
                    if (!executable.toUpperCase().contains("INSERT")) {
                        System.err.println("[TestContext] Warning: " + database + ": " +
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
        // Simple statement splitter - handles most cases
        // Does not handle stored procedures with multiple semicolons inside
        return sql.split(";(?=([^']*'[^']*')*[^']*$)");
    }

    private String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9]", "_").toLowerCase();
    }
}

