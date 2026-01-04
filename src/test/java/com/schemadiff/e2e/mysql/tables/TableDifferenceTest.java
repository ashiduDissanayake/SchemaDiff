package com.schemadiff.e2e.mysql.tables;

import com.schemadiff.e2e.base.AbstractMySQLTest;
import com.schemadiff.e2e.base.TestTags.*;
import com.schemadiff.model.DiffResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import static com.schemadiff.e2e.base.DiffResultAssert.assertThat;

/**
 * E2E tests for table-level schema differences.
 *
 * Tests:
 * - Missing tables (exist in reference but not in target)
 * - Extra tables (exist in target but not in reference)
 */
@Fast
@Tables
@DisplayName("MySQL Table Difference Detection")
class TableDifferenceTest extends AbstractMySQLTest {

    @Test
    @Missing
    @DisplayName("Should detect missing tables in target schema")
    void shouldDetectMissingTables(TestInfo testInfo) throws Exception {
        // Arrange
        ctx = createContext(testInfo);
        ctx.setupBothSchemas(MINIMAL_SCHEMA);
        ctx.applyDelta("/mysql/testcases/tables/missing/delta.sql");

        // Act
        DiffResult result = compareSchemas();

        // Assert
        assertThat(result)
            .hasDifferences()
            .hasMissingTable("USERS")
            .hasMissingTable("USER_ROLES")
            .hasMissingTable("USER_ATTRIBUTES")
            .hasMissingTable("TOKENS")
            .hasMissingTableCount(4)
            .hasNoExtraTables();
    }

    @Test
    @Extra
    @DisplayName("Should detect extra tables in target schema")
    void shouldDetectExtraTables(TestInfo testInfo) throws Exception {
        // Arrange
        ctx = createContext(testInfo);
        ctx.setupBothSchemas(MINIMAL_SCHEMA);
        ctx.applyDelta("/mysql/testcases/tables/extra/delta.sql");

        // Act
        DiffResult result = compareSchemas();

        // Assert
        assertThat(result)
            .hasDifferences()
            .hasExtraTable("UNAUTHORIZED_TABLE")
            .hasExtraTable("DEBUG_LOG")
            .hasExtraTableCount(2)
            .hasNoMissingTables();
    }

    @Test
    @Smoke
    @DisplayName("Should report no differences when schemas are identical")
    void shouldReportNoDifferencesWhenIdentical(TestInfo testInfo) throws Exception {
        // Arrange
        ctx = createContext(testInfo);
        ctx.setupBothSchemas(MINIMAL_SCHEMA);
        // No delta applied - schemas are identical

        // Act
        DiffResult result = compareSchemas();

        // Assert
        assertThat(result)
            .hasNoDifferences()
            .hasNoMissingTables()
            .hasNoExtraTables()
            .hasNoColumnDifferences()
            .hasNoConstraintDifferences()
            .hasNoIndexDifferences();
    }

    @Test
    @DisplayName("Should detect both missing and extra tables simultaneously")
    void shouldDetectMissingAndExtraTables(TestInfo testInfo) throws Exception {
        // Arrange
        ctx = createContext(testInfo);
        ctx.setupBothSchemas(MINIMAL_SCHEMA);

        // Drop one table and add another
        ctx.executeOnTarget("DROP TABLE IF EXISTS TOKENS");
        ctx.executeOnTarget("CREATE TABLE NEW_TABLE (ID INT PRIMARY KEY) ENGINE=InnoDB");

        // Act
        DiffResult result = compareSchemas();

        // Assert
        assertThat(result)
            .hasDifferences()
            .hasMissingTable("TOKENS")
            .hasExtraTable("NEW_TABLE")
            .hasMissingTableCount(1)
            .hasExtraTableCount(1);
    }
}

