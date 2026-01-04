package com.schemadiff.e2e.base;

import com.schemadiff.e2e.base.TestTags.Postgres;
import com.schemadiff.model.DatabaseType;
import com.schemadiff.model.DiffResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Abstract base class for PostgreSQL E2E tests.
 *
 * Provides:
 * - Container initialization (singleton, shared across all tests)
 * - Test context management (unique databases per test)
 * - Common assertion utilities
 * - Golden master update mechanism (-DupdateSnapshots=true)
 */
@Postgres
public abstract class AbstractPostgresTest {

    protected static final String MINIMAL_SCHEMA = "/postgres/schemas/minimal_reference.sql";
    protected static final SchemaDiffRunner runner = SchemaDiffRunner.forPostgres();

    protected static final boolean UPDATE_SNAPSHOTS =
            Boolean.getBoolean("updateSnapshots") ||
            "true".equalsIgnoreCase(System.getProperty("updateSnapshots"));

    protected PostgresTestContext ctx;

    @BeforeAll
    static void ensureContainerStarted() {
        PostgresTestContainer.getInstance();
    }

    @AfterEach
    void cleanupContext() {
        if (ctx != null) {
            ctx.cleanup();
            ctx = null;
        }
    }

    protected PostgresTestContext createContext(TestInfo testInfo) {
        String name = testInfo.getTestMethod()
                .map(m -> m.getName())
                .orElse("unknown");
        ctx = PostgresTestContext.create(name);
        return ctx;
    }

    protected PostgresTestContext createContext(String name) {
        ctx = PostgresTestContext.create(name);
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

