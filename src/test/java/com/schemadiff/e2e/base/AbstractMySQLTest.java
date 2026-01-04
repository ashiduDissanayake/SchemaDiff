package com.schemadiff.e2e.base;

import com.schemadiff.e2e.base.TestTags.MySQL;
import com.schemadiff.model.DiffResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Abstract base class for MySQL E2E tests.
 *
 * Provides:
 * - Container initialization (singleton, shared across all tests)
 * - Test context management (unique databases per test)
 * - Common assertion utilities
 * - Golden master update mechanism (-DupdateSnapshots=true)
 *
 * Subclasses should:
 * 1. Create their own TestContext in each test method
 * 2. Use the provided assertion helpers
 * 3. Call ctx.cleanup() in @AfterEach or use try-with-resources
 */
@MySQL
public abstract class AbstractMySQLTest {

    protected static final String MINIMAL_SCHEMA = "/mysql/schemas/minimal_reference.sql";
    protected static final SchemaDiffRunner runner = SchemaDiffRunner.forMySQL();

    // For golden master update mode
    protected static final boolean UPDATE_SNAPSHOTS =
            Boolean.getBoolean("updateSnapshots") ||
            "true".equalsIgnoreCase(System.getProperty("updateSnapshots"));

    protected TestContext ctx;

    /**
     * Ensures the MySQL container is started before any tests run.
     * This is called once per test class.
     */
    @BeforeAll
    static void ensureContainerStarted() {
        MySQLTestContainer.getInstance(); // Triggers lazy start
    }

    /**
     * Cleanup hook - should be called by subclasses or use TestContext with try-with-resources.
     */
    @AfterEach
    void cleanupContext() {
        if (ctx != null) {
            ctx.cleanup();
            ctx = null;
        }
    }

    /**
     * Creates a test context with a name derived from the test method.
     */
    protected TestContext createContext(TestInfo testInfo) {
        String name = testInfo.getTestMethod()
                .map(m -> m.getName())
                .orElse("unknown");
        ctx = TestContext.create(name);
        return ctx;
    }

    /**
     * Creates a test context with a custom name.
     */
    protected TestContext createContext(String name) {
        ctx = TestContext.create(name);
        return ctx;
    }

    /**
     * Runs the schema comparison using the current test context.
     */
    protected SchemaDiffRunner.ComparisonResult runComparison() throws Exception {
        return runner.compare(ctx);
    }

    /**
     * Convenience method for comparing and returning the DiffResult.
     */
    protected DiffResult compareSchemas() throws Exception {
        return runComparison().getDiffResult();
    }

    /**
     * Gets a fluent assertion for the comparison result.
     */
    protected DiffResultAssert assertDiff(DiffResult result) {
        return DiffResultAssert.assertThat(result);
    }

    /**
     * Verifies the result matches an expected output file (golden master pattern).
     *
     * If -DupdateSnapshots=true is set, creates/updates the expected file instead.
     *
     * @param result The comparison result
     * @param expectedResourcePath Path to expected.txt file (e.g., "/mysql/testcases/tables/missing/expected.txt")
     */
    protected void verifyAgainstExpected(SchemaDiffRunner.ComparisonResult result, String expectedResourcePath)
            throws IOException {
        String actualReport = result.getReport();

        if (UPDATE_SNAPSHOTS) {
            // Update mode: write actual output to file
            updateSnapshot(expectedResourcePath, actualReport);
            System.out.println("[SNAPSHOT UPDATED] " + expectedResourcePath);
            return;
        }

        // Normal mode: compare against expected
        String expected = loadExpected(expectedResourcePath);
        assertThat(normalizeWhitespace(actualReport))
                .as("Schema diff report should match expected output.\nActual:\n%s", actualReport)
                .isEqualTo(normalizeWhitespace(expected));
    }

    /**
     * Loads expected content from a resource file.
     */
    protected String loadExpected(String resourcePath) throws IOException {
        var is = getClass().getResourceAsStream(resourcePath);
        if (is == null) {
            throw new IOException("Expected file not found: " + resourcePath +
                    "\nRun with -DupdateSnapshots=true to create it.");
        }
        return new String(is.readAllBytes());
    }

    /**
     * Updates or creates a snapshot file.
     */
    protected void updateSnapshot(String resourcePath, String content) throws IOException {
        // Determine the actual file path in src/test/resources
        Path targetPath = Paths.get("src/test/resources" + resourcePath);
        Files.createDirectories(targetPath.getParent());
        Files.writeString(targetPath, content);
    }

    /**
     * Normalizes whitespace for comparison (handles line ending differences, trailing spaces).
     */
    protected String normalizeWhitespace(String text) {
        return text.replaceAll("\\r\\n", "\n")
                   .replaceAll("[ \\t]+\\n", "\n")
                   .trim();
    }

    /**
     * Prints the comparison report to stdout (useful for debugging tests).
     */
    protected void printReport(SchemaDiffRunner.ComparisonResult result) {
        System.out.println("=== Schema Comparison Report ===");
        System.out.println(result.getReport());
        System.out.println("================================");
    }
}

