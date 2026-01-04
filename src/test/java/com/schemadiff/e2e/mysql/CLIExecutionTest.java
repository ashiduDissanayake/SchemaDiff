package com.schemadiff.e2e.mysql;

import com.schemadiff.e2e.base.MySQLTestContainer;
import com.schemadiff.e2e.base.TestContext;
import com.schemadiff.e2e.base.TestTags.*;
import org.junit.jupiter.api.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CLI E2E test using ProcessBuilder to execute the actual JAR.
 *
 * This test verifies that:
 * 1. The JAR builds correctly
 * 2. CLI argument parsing works
 * 3. Exit codes are correct
 *
 * This is intentionally @Slow as it spawns a new JVM process.
 * Run only on PR merge, not on every commit.
 */
@Slow
@CLI
@MySQL
@DisplayName("MySQL CLI JAR Execution Test")
class CLIExecutionTest {

    private static final String JAR_PATH = "target/schemadiff2-2.0.0.jar";
    private TestContext ctx;

    @BeforeAll
    static void ensureJarExists() {
        File jar = new File(JAR_PATH);
        Assumptions.assumeTrue(jar.exists(),
            "JAR not found at " + JAR_PATH + ". Run 'mvn package' first.");
    }

    @BeforeAll
    static void ensureContainerStarted() {
        MySQLTestContainer.getInstance();
    }

    @AfterEach
    void cleanup() {
        if (ctx != null) {
            ctx.cleanup();
        }
    }

    @Test
    @DisplayName("CLI should return exit code 0 when schemas are identical")
    void shouldReturnExitCode0WhenIdentical() throws Exception {
        // Arrange - create identical schemas
        ctx = TestContext.create("cli_identical");
        Path refSql = createTempSqlFile("ref", """
            CREATE TABLE TEST_TABLE (
                ID INT PRIMARY KEY,
                NAME VARCHAR(100)
            );
        """);
        Path targetSql = createTempSqlFile("target", """
            CREATE TABLE TEST_TABLE (
                ID INT PRIMARY KEY,
                NAME VARCHAR(100)
            );
        """);

        // Act
        ProcessResult result = executeSchemaDiff(
            "--reference", refSql.toString(),
            "--target", targetSql.toString(),
            "--db-type", "mysql",
            "--image", "mysql:8.0"
        );

        // Assert
        assertThat(result.exitCode)
            .as("Exit code should be 0 for identical schemas.\nStdout:\n%s\nStderr:\n%s",
                result.stdout, result.stderr)
            .isEqualTo(0);
        assertThat(result.stdout).contains("0 Differences Found");
    }

    @Test
    @DisplayName("CLI should return exit code 1 when schemas differ")
    void shouldReturnExitCode1WhenDifferent() throws Exception {
        // Arrange - create different schemas
        ctx = TestContext.create("cli_different");
        Path refSql = createTempSqlFile("ref", """
            CREATE TABLE USERS (
                ID INT PRIMARY KEY,
                NAME VARCHAR(100)
            );
            CREATE TABLE ORDERS (
                ID INT PRIMARY KEY
            );
        """);
        Path targetSql = createTempSqlFile("target", """
            CREATE TABLE USERS (
                ID INT PRIMARY KEY,
                NAME VARCHAR(100)
            );
        """);

        // Act
        ProcessResult result = executeSchemaDiff(
            "--reference", refSql.toString(),
            "--target", targetSql.toString(),
            "--db-type", "mysql",
            "--image", "mysql:8.0"
        );

        // Assert
        assertThat(result.exitCode)
            .as("Exit code should be 1 when differences found.\nStdout:\n%s\nStderr:\n%s",
                result.stdout, result.stderr)
            .isEqualTo(1);
        assertThat(result.stdout).contains("MISSING TABLES");
        assertThat(result.stdout).contains("ORDERS");
    }

    @Test
    @DisplayName("CLI should return exit code 2 on invalid arguments")
    void shouldReturnExitCode2OnError() throws Exception {
        // Act - call with missing required arguments
        ProcessResult result = executeSchemaDiff(
            "--reference", "nonexistent.sql",
            "--target", "also-nonexistent.sql",
            "--db-type", "mysql"
            // Missing --image which is required for .sql files
        );

        // Assert
        assertThat(result.exitCode)
            .as("Exit code should be 2 on error.\nStderr:\n%s", result.stderr)
            .isEqualTo(2);
    }

    @Test
    @DisplayName("CLI should show help with --help flag")
    void shouldShowHelp() throws Exception {
        // Act
        ProcessResult result = executeSchemaDiff("--help");

        // Assert
        assertThat(result.exitCode).isEqualTo(0);
        assertThat(result.stdout).contains("--reference");
        assertThat(result.stdout).contains("--target");
        assertThat(result.stdout).contains("--db-type");
    }

    // === Helper Methods ===

    private ProcessResult executeSchemaDiff(String... args) throws Exception {
        String[] command = new String[args.length + 3];
        command[0] = "java";
        command[1] = "-jar";
        command[2] = JAR_PATH;
        System.arraycopy(args, 0, command, 3, args.length);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);

        Process process = pb.start();

        String stdout;
        String stderr;
        try (BufferedReader outReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
             BufferedReader errReader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
            stdout = outReader.lines().collect(Collectors.joining("\n"));
            stderr = errReader.lines().collect(Collectors.joining("\n"));
        }

        boolean finished = process.waitFor(120, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("Process timed out after 120 seconds");
        }

        return new ProcessResult(process.exitValue(), stdout, stderr);
    }

    private Path createTempSqlFile(String prefix, String content) throws Exception {
        Path tempFile = Files.createTempFile(prefix + "_", ".sql");
        Files.writeString(tempFile, content);
        tempFile.toFile().deleteOnExit();
        return tempFile;
    }

    private record ProcessResult(int exitCode, String stdout, String stderr) {}
}

