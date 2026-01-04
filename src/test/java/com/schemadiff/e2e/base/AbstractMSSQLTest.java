package com.schemadiff.e2e.base;

import com.schemadiff.e2e.base.TestTags.MSSQL;
import com.schemadiff.model.DatabaseType;
import com.schemadiff.model.DiffResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Abstract base class for MSSQL E2E tests.
 *
 * Note: MSSQL tests require the MSSQL container to be available.
 * They are skipped if container startup fails.
 */
@MSSQL
public abstract class AbstractMSSQLTest {

    protected static final String MINIMAL_SCHEMA = "/mssql/schemas/minimal_reference.sql";
    protected static final SchemaDiffRunner runner = SchemaDiffRunner.forType(DatabaseType.MSSQL);

    protected static final boolean UPDATE_SNAPSHOTS =
            Boolean.getBoolean("updateSnapshots") ||
            "true".equalsIgnoreCase(System.getProperty("updateSnapshots"));

    protected MSSQLTestContext ctx;

    private static boolean containerInitialized = false;
    private static boolean containerAvailable = false;
    private static String skipReason = null;

    /**
     * Lazy initialization of MSSQL container.
     * Only starts when a test actually runs (not filtered out by tags).
     */
    @BeforeEach
    void ensureContainerStarted() {
        if (!containerInitialized) {
            containerInitialized = true;
            try {
                MSSQLTestContainer.getInstance();
                containerAvailable = true;
            } catch (Exception e) {
                skipReason = "MSSQL container not available: " + e.getMessage();
                containerAvailable = false;
            }
        }
        // Skip test if container is not available
        Assumptions.assumeTrue(containerAvailable, skipReason);
    }

    @AfterEach
    void cleanupContext() {
        if (ctx != null) {
            ctx.cleanup();
            ctx = null;
        }
    }

    protected MSSQLTestContext createContext(TestInfo testInfo) {
        String name = testInfo.getTestMethod()
                .map(m -> m.getName())
                .orElse("unknown");
        ctx = MSSQLTestContext.create(name);
        return ctx;
    }

    protected MSSQLTestContext createContext(String name) {
        ctx = MSSQLTestContext.create(name);
        return ctx;
    }

    protected SchemaDiffRunner.ComparisonResult runComparison() throws Exception {
        return runner.compare(ctx);
    }

    protected DiffResult compareSchemas() throws Exception {
        return runComparison().getDiffResult();
    }

    protected DiffResultAssert assertDiff(DiffResult result) {
        return DiffResultAssert.assertThat(result);
    }

    protected void verifyAgainstExpected(SchemaDiffRunner.ComparisonResult result, String expectedResourcePath)
            throws IOException {
        String actualReport = result.getReport();

        if (UPDATE_SNAPSHOTS) {
            updateSnapshot(expectedResourcePath, actualReport);
            System.out.println("[SNAPSHOT UPDATED] " + expectedResourcePath);
            return;
        }

        String expected = loadExpected(expectedResourcePath);
        assertThat(normalizeWhitespace(actualReport))
                .as("Schema diff report should match expected output.\nActual:\n%s", actualReport)
                .isEqualTo(normalizeWhitespace(expected));
    }

    protected String loadExpected(String resourcePath) throws IOException {
        var is = getClass().getResourceAsStream(resourcePath);
        if (is == null) {
            throw new IOException("Expected file not found: " + resourcePath +
                    "\nRun with -DupdateSnapshots=true to create it.");
        }
        return new String(is.readAllBytes());
    }

    protected void updateSnapshot(String resourcePath, String content) throws IOException {
        Path targetPath = Paths.get("src/test/resources" + resourcePath);
        Files.createDirectories(targetPath.getParent());
        Files.writeString(targetPath, content);
    }

    protected String normalizeWhitespace(String text) {
        return text.replaceAll("\\r\\n", "\n")
                   .replaceAll("[ \\t]+\\n", "\n")
                   .trim();
    }

    protected void printReport(SchemaDiffRunner.ComparisonResult result) {
        System.out.println("=== Schema Comparison Report ===");
        System.out.println(result.getReport());
        System.out.println("================================");
    }
}

