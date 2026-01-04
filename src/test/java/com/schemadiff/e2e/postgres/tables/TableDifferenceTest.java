package com.schemadiff.e2e.postgres.tables;

import com.schemadiff.e2e.base.AbstractPostgresTest;
import com.schemadiff.e2e.base.TestTags.*;
import com.schemadiff.model.DiffResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import static com.schemadiff.e2e.base.DiffResultAssert.assertThat;

/**
 * E2E tests for table-level schema differences on PostgreSQL.
 */
@Fast
@Tables
@DisplayName("PostgreSQL Table Difference Detection")
class TableDifferenceTest extends AbstractPostgresTest {

    @Test
    @Missing
    @DisplayName("Should detect missing tables in target schema")
    void shouldDetectMissingTables(TestInfo testInfo) throws Exception {
        // Arrange
        ctx = createContext(testInfo);
        ctx.setupBothSchemas(MINIMAL_SCHEMA);
        ctx.applyDelta("/postgres/testcases/tables/missing/delta.sql");

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
        ctx.applyDelta("/postgres/testcases/tables/extra/delta.sql");

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

        // Act
        DiffResult result = compareSchemas();

        // Assert
        assertThat(result)
            .hasNoDifferences()
            .hasNoMissingTables()
            .hasNoExtraTables();
    }
}

