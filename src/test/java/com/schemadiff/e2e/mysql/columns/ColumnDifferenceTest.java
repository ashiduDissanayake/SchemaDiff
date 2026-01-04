package com.schemadiff.e2e.mysql.columns;

import com.schemadiff.e2e.base.AbstractMySQLTest;
import com.schemadiff.e2e.base.TestTags.*;
import com.schemadiff.model.DiffResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import static com.schemadiff.e2e.base.DiffResultAssert.assertThat;

/**
 * E2E tests for column-level schema differences.
 *
 * Tests:
 * - Missing columns (exist in reference but not in target)
 * - Extra columns (exist in target but not in reference)
 * - Modified columns (type, nullable, default value changes)
 */
@Fast
@Columns
@DisplayName("MySQL Column Difference Detection")
class ColumnDifferenceTest extends AbstractMySQLTest {

    @Test
    @Missing
    @DisplayName("Should detect missing columns in target schema")
    void shouldDetectMissingColumns(TestInfo testInfo) throws Exception {
        // Arrange
        ctx = createContext(testInfo);
        ctx.setupBothSchemas(MINIMAL_SCHEMA);
        ctx.applyDelta("/mysql/testcases/columns/missing/delta.sql");

        // Act
        DiffResult result = compareSchemas();

        // Assert
        assertThat(result)
            .hasDifferences()
            .hasColumnDifference("USERS")
            .hasColumnDifferenceContaining("USERS", "STATUS")
            .hasColumnDifferenceContaining("USERS", "TENANT_ID")
            .hasNoMissingTables()
            .hasNoExtraTables();
    }

    @Test
    @Extra
    @DisplayName("Should detect extra columns in target schema")
    void shouldDetectExtraColumns(TestInfo testInfo) throws Exception {
        // Arrange
        ctx = createContext(testInfo);
        ctx.setupBothSchemas(MINIMAL_SCHEMA);
        ctx.applyDelta("/mysql/testcases/columns/extra/delta.sql");

        // Act
        DiffResult result = compareSchemas();

        // Assert
        assertThat(result)
            .hasDifferences()
            .hasColumnDifference("USERS")
            .hasColumnDifferenceContaining("USERS", "PHONE_NUMBER")
            .hasColumnDifferenceContaining("USERS", "LAST_LOGIN")
            .hasColumnDifferenceContaining("USERS", "IS_ADMIN");
    }

    @Test
    @Modified
    @DisplayName("Should detect modified column types in target schema")
    void shouldDetectModifiedColumnTypes(TestInfo testInfo) throws Exception {
        // Arrange
        ctx = createContext(testInfo);
        ctx.setupBothSchemas(MINIMAL_SCHEMA);
        ctx.applyDelta("/mysql/testcases/columns/modified/delta.sql");

        // Act
        DiffResult result = compareSchemas();

        // Assert
        assertThat(result)
            .hasDifferences()
            .hasColumnDifference("USERS")
            // USERNAME changed from VARCHAR(255) to VARCHAR(100)
            .hasColumnDifferenceContaining("USERS", "USERNAME")
            // STATUS default changed from 'ACTIVE' to 'PENDING'
            .hasColumnDifferenceContaining("USERS", "STATUS");
    }

    @Test
    @DisplayName("Should detect column with different nullable setting")
    void shouldDetectNullableDifference(TestInfo testInfo) throws Exception {
        // Arrange
        ctx = createContext(testInfo);
        ctx.setupBothSchemas(MINIMAL_SCHEMA);

        // Change EMAIL from nullable to NOT NULL
        ctx.executeOnTarget("ALTER TABLE USERS MODIFY COLUMN EMAIL VARCHAR(255) NOT NULL");

        // Act
        DiffResult result = compareSchemas();

        // Assert
        assertThat(result)
            .hasDifferences()
            .hasColumnDifference("USERS")
            .hasColumnDifferenceContaining("USERS", "EMAIL");
    }

    @Test
    @DisplayName("Should detect column with different default value")
    void shouldDetectDefaultValueDifference(TestInfo testInfo) throws Exception {
        // Arrange
        ctx = createContext(testInfo);
        ctx.setupBothSchemas(MINIMAL_SCHEMA);

        // Change STATUS default from 'ACTIVE' to 'INACTIVE'
        ctx.executeOnTarget("ALTER TABLE USERS MODIFY COLUMN STATUS VARCHAR(25) DEFAULT 'INACTIVE'");

        // Act
        DiffResult result = compareSchemas();

        // Assert
        assertThat(result)
            .hasDifferences()
            .hasColumnDifference("USERS")
            .hasColumnDifferenceContaining("USERS", "STATUS");
    }
}

