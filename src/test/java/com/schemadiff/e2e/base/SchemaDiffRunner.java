package com.schemadiff.e2e.base;

import com.schemadiff.core.ComparisonEngine;
import com.schemadiff.core.MetadataExtractor;
import com.schemadiff.core.extractors.*;
import com.schemadiff.model.DatabaseMetadata;
import com.schemadiff.model.DatabaseType;
import com.schemadiff.model.DiffResult;
import com.schemadiff.report.TreeReportBuilder;

import java.sql.Connection;

/**
 * Programmatic entry point for SchemaDiff comparison in tests.
 *
 * This bypasses the CLI layer for faster test execution (same JVM).
 * For true E2E CLI tests, use SchemaDiffCLIRunner instead.
 */
public class SchemaDiffRunner {

    private final DatabaseType dbType;

    public SchemaDiffRunner(DatabaseType dbType) {
        this.dbType = dbType;
    }

    /**
     * Creates a runner for MySQL comparisons.
     */
    public static SchemaDiffRunner forMySQL() {
        return new SchemaDiffRunner(DatabaseType.MYSQL);
    }

    /**
     * Creates a runner for PostgreSQL comparisons.
     */
    public static SchemaDiffRunner forPostgres() {
        return new SchemaDiffRunner(DatabaseType.POSTGRES);
    }

    /**
     * Creates a runner for the specified database type.
     */
    public static SchemaDiffRunner forType(DatabaseType type) {
        return new SchemaDiffRunner(type);
    }

    /**
     * Compares two databases using JDBC connections.
     * Returns a ComparisonResult containing both the DiffResult and the formatted report.
     */
    public ComparisonResult compare(Connection refConnection, Connection targetConnection) throws Exception {
        MetadataExtractor extractor = createExtractor();

        DatabaseMetadata refMetadata = extractor.extract(refConnection);
        DatabaseMetadata targetMetadata = extractor.extract(targetConnection);

        ComparisonEngine engine = new ComparisonEngine();
        DiffResult diffResult = engine.compare(refMetadata, targetMetadata);

        TreeReportBuilder reporter = new TreeReportBuilder();
        String report = reporter.build(diffResult);

        return new ComparisonResult(diffResult, report, refMetadata, targetMetadata);
    }

    /**
     * Compares using a TestContext (convenience method).
     */
    public ComparisonResult compare(TestContext ctx) throws Exception {
        try (Connection refConn = ctx.getRefConnection();
             Connection targetConn = ctx.getTargetConnection()) {
            return compare(refConn, targetConn);
        }
    }

    private MetadataExtractor createExtractor() {
        return switch (dbType) {
            case MYSQL -> new MySQLExtractor();
            case POSTGRES -> new PostgresExtractor();
            case ORACLE -> new OracleExtractor();
            case MSSQL -> new MSSQLExtractor();
            case DB2 -> new DB2Extractor();
        };
    }

    /**
     * Result of a schema comparison.
     * Contains both structured data (DiffResult) and human-readable report.
     */
    public static class ComparisonResult {
        private final DiffResult diffResult;
        private final String report;
        private final DatabaseMetadata refMetadata;
        private final DatabaseMetadata targetMetadata;

        public ComparisonResult(DiffResult diffResult, String report,
                               DatabaseMetadata refMetadata, DatabaseMetadata targetMetadata) {
            this.diffResult = diffResult;
            this.report = report;
            this.refMetadata = refMetadata;
            this.targetMetadata = targetMetadata;
        }

        public DiffResult getDiffResult() {
            return diffResult;
        }

        public String getReport() {
            return report;
        }

        public DatabaseMetadata getRefMetadata() {
            return refMetadata;
        }

        public DatabaseMetadata getTargetMetadata() {
            return targetMetadata;
        }

        public boolean hasDifferences() {
            return diffResult.hasDifferences();
        }

        public int getTotalDifferences() {
            return diffResult.getTotalDifferences();
        }
    }
}

