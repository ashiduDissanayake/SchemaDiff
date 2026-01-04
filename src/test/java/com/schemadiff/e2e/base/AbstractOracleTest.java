package com.schemadiff.e2e.base;

import com.schemadiff.e2e.base.TestTags.Oracle;
import com.schemadiff.model.DatabaseType;
import com.schemadiff.model.DiffResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Abstract base class for Oracle E2E tests.
 *
 * Note: Oracle tests are @Slow by default due to container startup time (~60-120s).
 * First-time image pull can take 5-10 minutes.
 * They are skipped if Oracle container is not available.
 */
@Oracle
public abstract class AbstractOracleTest {

    protected static final String MINIMAL_SCHEMA = "/oracle/schemas/minimal_reference.sql";
    protected static final SchemaDiffRunner runner = SchemaDiffRunner.forType(DatabaseType.ORACLE);

    protected static final boolean UPDATE_SNAPSHOTS =
            Boolean.getBoolean("updateSnapshots") ||
            "true".equalsIgnoreCase(System.getProperty("updateSnapshots"));

    protected OracleTestContext ctx;

    private static boolean containerAvailable = false;
    private static String skipReason = null;

    /**
     * Initialize Oracle container once per test class.
     * Uses BeforeAll to avoid timeout issues with @BeforeEach.
     * Oracle container startup is slow, especially on first pull.
     */
    @BeforeAll
    static void initOracleContainer() {
        try {
            System.out.println("[Oracle] Starting Oracle container (this may take several minutes on first run)...");
            OracleTestContainer.getInstance();
            containerAvailable = true;
            System.out.println("[Oracle] Container started successfully.");
        } catch (Exception e) {
            skipReason = "Oracle container not available: " + e.getMessage();
            containerAvailable = false;
            System.err.println("[Oracle] " + skipReason);
        }
    }

    @BeforeEach
    void checkContainerAvailable() {
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

    protected OracleTestContext createContext(TestInfo testInfo) {
        String name = testInfo.getTestMethod()
                .map(m -> m.getName())
                .orElse("unknown");
        ctx = OracleTestContext.create(name);
        return ctx;
    }

    protected OracleTestContext createContext(String name) {
        ctx = OracleTestContext.create(name);
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

