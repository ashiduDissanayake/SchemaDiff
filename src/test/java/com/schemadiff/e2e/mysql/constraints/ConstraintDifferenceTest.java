package com.schemadiff.e2e.mysql.constraints;

import com.schemadiff.e2e.base.AbstractMySQLTest;
import com.schemadiff.e2e.base.TestTags.*;
import com.schemadiff.model.DiffResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import static com.schemadiff.e2e.base.DiffResultAssert.assertThat;

/**
 * E2E tests for constraint-level schema differences.
 *
 * Tests:
 * - Missing constraints (PRIMARY KEY, FOREIGN KEY, UNIQUE, CHECK)
 * - Extra constraints
 * - Modified constraints (definition changes)
 */
@Fast
@Constraints
@DisplayName("MySQL Constraint Difference Detection")
class ConstraintDifferenceTest extends AbstractMySQLTest {

    @Test
    @Missing
    @DisplayName("Should detect missing unique constraint")
    void shouldDetectMissingUniqueConstraint(TestInfo testInfo) throws Exception {
        // Arrange
        ctx = createContext(testInfo);
        ctx.setupBothSchemas(MINIMAL_SCHEMA);

        // Drop unique constraint on EMAIL
        ctx.executeOnTarget("ALTER TABLE USERS DROP INDEX UK_USERS_EMAIL");

        // Act
        DiffResult result = compareSchemas();

        // Assert
        assertThat(result)
            .hasDifferences()
            .hasConstraintDifference("USERS")
            .hasConstraintDifferenceContaining("USERS", "Missing");
    }

    @Test
    @Missing
    @DisplayName("Should detect missing foreign key constraint")
    void shouldDetectMissingForeignKey(TestInfo testInfo) throws Exception {
        // Arrange
        ctx = createContext(testInfo);
        ctx.setupBothSchemas(MINIMAL_SCHEMA);

        // Drop foreign key
        ctx.executeOnTarget("ALTER TABLE USER_ROLES DROP FOREIGN KEY FK_USER_ROLES_USER");

        // Act
        DiffResult result = compareSchemas();

        // Assert
        assertThat(result)
            .hasDifferences()
            .hasConstraintDifference("USER_ROLES")
            .hasConstraintDifferenceContaining("USER_ROLES", "FOREIGN_KEY");
    }

    @Test
    @Extra
    @DisplayName("Should detect extra unique constraint")
    void shouldDetectExtraUniqueConstraint(TestInfo testInfo) throws Exception {
        // Arrange
        ctx = createContext(testInfo);
        ctx.setupBothSchemas(MINIMAL_SCHEMA);

        // Add an extra unique constraint
        ctx.executeOnTarget("ALTER TABLE USERS ADD CONSTRAINT UK_USERS_TENANT UNIQUE (TENANT_ID)");

        // Act
        DiffResult result = compareSchemas();

        // Assert
        assertThat(result)
            .hasDifferences()
            .hasConstraintDifference("USERS")
            .hasConstraintDifferenceContaining("USERS", "Extra");
    }

    @Test
    @DisplayName("Should detect missing check constraint")
    void shouldDetectMissingCheckConstraint(TestInfo testInfo) throws Exception {
        // Arrange
        ctx = createContext(testInfo);
        ctx.setupBothSchemas(MINIMAL_SCHEMA);

        // Drop check constraint (MySQL 8.0.16+)
        ctx.executeOnTarget("ALTER TABLE TOKENS DROP CHECK CHK_TOKEN_STATE");

        // Act
        DiffResult result = compareSchemas();

        // Assert
        assertThat(result)
            .hasDifferences()
            .hasConstraintDifference("TOKENS");
    }

    @Test
    @DisplayName("Should detect foreign key with different cascade rule")
    void shouldDetectForeignKeyCascadeRuleDifference(TestInfo testInfo) throws Exception {
        // Arrange
        ctx = createContext(testInfo);
        ctx.setupBothSchemas(MINIMAL_SCHEMA);

        // Drop and recreate FK with different cascade rule (RESTRICT instead of CASCADE)
        ctx.executeOnTarget("""
            ALTER TABLE USER_ROLES DROP FOREIGN KEY FK_USER_ROLES_USER;
            ALTER TABLE USER_ROLES ADD CONSTRAINT FK_USER_ROLES_USER 
                FOREIGN KEY (USER_ID) REFERENCES USERS(ID) ON DELETE RESTRICT;
        """);

        // Act
        DiffResult result = compareSchemas();

        // Assert
        assertThat(result)
            .hasDifferences()
            .hasConstraintDifference("USER_ROLES");
    }
}

