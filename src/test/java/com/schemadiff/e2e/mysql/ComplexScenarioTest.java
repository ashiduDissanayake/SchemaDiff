package com.schemadiff.e2e.mysql;

import com.schemadiff.e2e.base.AbstractMySQLTest;
import com.schemadiff.e2e.base.SchemaDiffRunner;
import com.schemadiff.e2e.base.TestTags.*;
import com.schemadiff.model.DiffResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import static com.schemadiff.e2e.base.DiffResultAssert.assertThat;

/**
 * Complex integration tests that combine multiple types of schema changes.
 *
 * These tests validate that SchemaDiff correctly handles real-world scenarios
 * where multiple changes occur simultaneously.
 */
@Fast
@Complex
@DisplayName("MySQL Complex Scenario Tests")
class ComplexScenarioTest extends AbstractMySQLTest {

    @Test
    @DisplayName("Should detect multiple types of differences in kitchen sink scenario")
    void shouldDetectMultipleDifferenceTypes(TestInfo testInfo) throws Exception {
        // Arrange
        ctx = createContext(testInfo);
        ctx.setupBothSchemas(MINIMAL_SCHEMA);
        ctx.applyDelta("/mysql/testcases/complex/delta.sql");

        // Act
        SchemaDiffRunner.ComparisonResult compResult = runComparison();
        DiffResult result = compResult.getDiffResult();

        // Debug output
        printReport(compResult);

        // Assert - verify multiple categories have differences
        assertThat(result).hasDifferences();

        // Missing table: TOKENS
        assertThat(result).hasMissingTable("TOKENS");

        // Extra table: CUSTOM_CONFIG
        assertThat(result).hasExtraTable("CUSTOM_CONFIG");

        // Column changes in USERS
        assertThat(result).hasColumnDifference("USERS");

        // Total should be > 5 (multiple changes)
        assertThat(result).hasTotalDifferences(8); // Expected: 1 missing table + 1 extra table + 3 column changes + 1 constraint + 2 indexes
    }

    @Test
    @DisplayName("Should handle cascading effects of dropped tables")
    void shouldHandleCascadingDrops(TestInfo testInfo) throws Exception {
        // Arrange
        ctx = createContext(testInfo);
        ctx.setupBothSchemas(MINIMAL_SCHEMA);

        // Drop parent table - this cascades to child tables
        ctx.executeOnTarget("""
            SET FOREIGN_KEY_CHECKS = 0;
            DROP TABLE IF EXISTS USER_ATTRIBUTES;
            DROP TABLE IF EXISTS USER_ROLES;
            DROP TABLE IF EXISTS TOKENS;
            DROP TABLE IF EXISTS USERS;
            SET FOREIGN_KEY_CHECKS = 1;
        """);

        // Act
        DiffResult result = compareSchemas();

        // Assert - all 4 tables should be missing
        assertThat(result)
            .hasDifferences()
            .hasMissingTable("USERS")
            .hasMissingTable("USER_ROLES")
            .hasMissingTable("USER_ATTRIBUTES")
            .hasMissingTable("TOKENS")
            .hasMissingTableCount(4);
    }

    @Test
    @DisplayName("Should handle schema with only structural changes no data")
    void shouldCompareEmptySchemas(TestInfo testInfo) throws Exception {
        // Arrange
        ctx = createContext(testInfo);
        ctx.setupBothSchemas(MINIMAL_SCHEMA);

        // Schemas are identical, both empty of data

        // Act
        DiffResult result = compareSchemas();

        // Assert
        assertThat(result).hasNoDifferences();
    }

    @Test
    @DisplayName("Should detect table rename as missing + extra")
    void shouldDetectTableRenameAsMissingPlusExtra(TestInfo testInfo) throws Exception {
        // Arrange
        ctx = createContext(testInfo);
        ctx.setupBothSchemas(MINIMAL_SCHEMA);

        // Rename AUDIT_LOG to SYSTEM_LOG
        ctx.executeOnTarget("RENAME TABLE AUDIT_LOG TO SYSTEM_LOG");

        // Act
        DiffResult result = compareSchemas();

        // Assert - tool sees this as missing + extra (cannot detect rename)
        assertThat(result)
            .hasDifferences()
            .hasMissingTable("AUDIT_LOG")
            .hasExtraTable("SYSTEM_LOG");
    }

    @Test
    @Smoke
    @DisplayName("Should produce consistent results on repeated comparisons")
    void shouldBeIdempotent(TestInfo testInfo) throws Exception {
        // Arrange
        ctx = createContext(testInfo);
        ctx.setupBothSchemas(MINIMAL_SCHEMA);
        ctx.executeOnTarget("DROP TABLE IF EXISTS TOKENS");

        // Act - run comparison twice
        DiffResult result1 = compareSchemas();
        DiffResult result2 = compareSchemas();

        // Assert - results should be identical
        assertThat(result1)
            .hasDifferences()
            .hasMissingTable("TOKENS");
        assertThat(result2)
            .hasDifferences()
            .hasMissingTable("TOKENS");

        // Verify counts match
        org.assertj.core.api.Assertions.assertThat(result1.getTotalDifferences())
            .isEqualTo(result2.getTotalDifferences());
    }
}

