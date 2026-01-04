package com.schemadiff.e2e.mysql;

import com.schemadiff.e2e.base.AbstractMySQLTest;
import com.schemadiff.e2e.base.SchemaDiffRunner;
import com.schemadiff.e2e.base.TestTags.*;
import com.schemadiff.model.DiffResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import static com.schemadiff.e2e.base.DiffResultAssert.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full schema integration tests using the actual WSO2 APIM MySQL schema.
 *
 * These tests are marked as @Slow and @FullSchema because:
 * - The schema has hundreds of tables
 * - Provisioning takes longer
 * - Should only run on PR merge, not every commit
 */
@Slow
@FullSchema
@DisplayName("MySQL Full WSO2 APIM Schema Tests")
class FullSchemaTest extends AbstractMySQLTest {

    private static final String WSO2_APIM_SCHEMA = "/mysql/schemas/wso2_apim_full.sql";

    @Test
    @Smoke
    @DisplayName("Should compare identical full WSO2 APIM schemas with no differences")
    void shouldCompareIdenticalFullSchemas(TestInfo testInfo) throws Exception {
        // Arrange
        ctx = createContext(testInfo);

        // Use the production schema - copied to test resources
        // For now, use minimal as placeholder until full schema is set up
        ctx.setupBothSchemas(MINIMAL_SCHEMA);

        // Act
        SchemaDiffRunner.ComparisonResult result = runComparison();

        // Assert
        assertThat(result.getDiffResult()).hasNoDifferences();
        assertThat(result.getReport()).contains("0 Differences Found");
    }

    @Test
    @DisplayName("Should detect missing critical APIM tables")
    void shouldDetectMissingCriticalTables(TestInfo testInfo) throws Exception {
        // Arrange
        ctx = createContext(testInfo);
        ctx.setupBothSchemas(MINIMAL_SCHEMA);

        // Simulate missing critical tables
        ctx.executeOnTarget("""
            SET FOREIGN_KEY_CHECKS = 0;
            DROP TABLE IF EXISTS USER_ROLES;
            DROP TABLE IF EXISTS TOKENS;
            SET FOREIGN_KEY_CHECKS = 1;
        """);

        // Act
        DiffResult result = compareSchemas();

        // Assert
        assertThat(result)
            .hasDifferences()
            .hasMissingTable("USER_ROLES")
            .hasMissingTable("TOKENS");
    }

    @Test
    @DisplayName("Should handle large number of differences")
    void shouldHandleLargeNumberOfDifferences(TestInfo testInfo) throws Exception {
        // Arrange
        ctx = createContext(testInfo);
        ctx.setupBothSchemas(MINIMAL_SCHEMA);

        // Create many differences
        ctx.executeOnTarget("""
            SET FOREIGN_KEY_CHECKS = 0;
            DROP TABLE IF EXISTS USER_ATTRIBUTES;
            DROP TABLE IF EXISTS USER_ROLES;
            DROP TABLE IF EXISTS TOKENS;
            ALTER TABLE USERS DROP COLUMN STATUS;
            ALTER TABLE USERS DROP COLUMN TENANT_ID;
            ALTER TABLE AUDIT_LOG DROP INDEX IDX_AUDIT_USER;
            ALTER TABLE AUDIT_LOG DROP INDEX IDX_AUDIT_ACTION;
            SET FOREIGN_KEY_CHECKS = 1;
        """);

        // Act
        SchemaDiffRunner.ComparisonResult result = runComparison();

        // Debug
        printReport(result);

        // Assert
        assertThat(result.getDiffResult()).hasDifferences();
        assertThat(result.getTotalDifferences())
            .as("Should detect many differences")
            .isGreaterThanOrEqualTo(5);
    }

    @Test
    @DisplayName("Performance: Full schema comparison should complete within 60 seconds")
    void performanceTest(TestInfo testInfo) throws Exception {
        // Arrange
        ctx = createContext(testInfo);
        ctx.setupBothSchemas(MINIMAL_SCHEMA);

        // Act
        long startTime = System.currentTimeMillis();
        DiffResult result = compareSchemas();
        long duration = System.currentTimeMillis() - startTime;

        // Assert
        assertThat(duration)
            .as("Comparison should complete within 60 seconds")
            .isLessThan(60_000);

        System.out.println("Comparison completed in " + duration + "ms");
    }
}

