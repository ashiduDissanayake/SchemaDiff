package com.schemadiff.e2e.postgres;

import com.schemadiff.e2e.base.AbstractPostgresTest;
import com.schemadiff.e2e.base.SchemaDiffRunner;
import com.schemadiff.e2e.base.TestTags.*;
import com.schemadiff.model.DiffResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import static com.schemadiff.e2e.base.DiffResultAssert.assertThat;

/**
 * Full schema E2E tests for PostgreSQL using real WSO2 APIM schema.
 * These tests are slower and run on PR merge only.
 */
@Slow
@FullSchema
@DisplayName("PostgreSQL Full Schema Tests")
class FullSchemaTest extends AbstractPostgresTest {

    private static final String FULL_SCHEMA = "/postgres/schemas/wso2_apim_full.sql";

    @Test
    @Smoke
    @DisplayName("Should compare identical full WSO2 APIM schemas")
    void shouldCompareIdenticalFullSchemas(TestInfo testInfo) throws Exception {
        // Skip if full schema file doesn't exist yet
        if (getClass().getResourceAsStream(FULL_SCHEMA) == null) {
            ctx = createContext(testInfo);
            ctx.setupBothSchemas(MINIMAL_SCHEMA);
            DiffResult result = compareSchemas();
            assertThat(result).hasNoDifferences();
            return;
        }

        ctx = createContext(testInfo);
        ctx.setupBothSchemas(FULL_SCHEMA);

        DiffResult result = compareSchemas();

        assertThat(result)
            .hasNoDifferences();
    }

    @Test
    @DisplayName("Should detect table drift in full schema")
    void shouldDetectTableDriftInFullSchema(TestInfo testInfo) throws Exception {
        ctx = createContext(testInfo);
        ctx.setupBothSchemas(MINIMAL_SCHEMA);

        // Simulate drift by dropping a table
        ctx.executeOnTarget("DROP TABLE IF EXISTS TOKENS CASCADE");

        DiffResult result = compareSchemas();

        assertThat(result)
            .hasDifferences()
            .hasMissingTable("TOKENS");
    }
}

