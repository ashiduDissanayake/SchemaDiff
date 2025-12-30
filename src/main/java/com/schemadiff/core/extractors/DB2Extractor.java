package com.schemadiff.core.extractors;

import com.schemadiff.core.MetadataExtractor;
import com.schemadiff.core.SignatureGenerator;
import com.schemadiff.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;

/**
 * IBM DB2-specific metadata extractor with support for:
 * - IDENTITY auto-increment columns
 * - CHECK constraints
 * - Foreign key ON DELETE rules
 * - DB2-specific types (VARCHAR, CLOB, BLOB, TIMESTAMP, etc.)
 * - Table and column comments (SYSCAT.TABLES, SYSCAT.COLUMNS)
 * - Index types (SYSCAT.INDEXES)
 */
public class DB2Extractor extends MetadataExtractor {

    private static final Logger log = LoggerFactory.getLogger(DB2Extractor.class);

    private static final String CONSTRAINT_TYPE_PK = "PRIMARY_KEY";
    private static final String CONSTRAINT_TYPE_FK = "FOREIGN_KEY";
    private static final String CONSTRAINT_TYPE_CHECK = "CHECK";
    private static final String CONSTRAINT_TYPE_UNIQUE = "UNIQUE";
    private static final int QUERY_TIMEOUT_SECONDS = 300;
    private static final int MAX_RETRIES = 3;

    private final String targetSchema;
    private final boolean enableRetry;
    private final ExtractionProgress progressListener;

    public interface ExtractionProgress {
        void onPhaseStart(String phase);
        void onPhaseComplete(String phase, int itemsProcessed, long durationMs);
        void onWarning(String message);
    }

    public DB2Extractor() {
        this(null, true, null);
    }

    public DB2Extractor(String targetSchema) {
        this(targetSchema, true, null);
    }

    public DB2Extractor(String targetSchema, boolean enableRetry, ExtractionProgress progressListener) {
        this.targetSchema = targetSchema;
        this.enableRetry = enableRetry;
        this.progressListener = progressListener != null ? progressListener : new NoOpProgress();
    }

    @Override
    public DatabaseMetadata extract(Connection conn) throws SQLException {
        log.info("Starting DB2 schema extraction");
        long startTime = System.currentTimeMillis();

        validateConnection(conn);
        DatabaseMetadata metadata = new DatabaseMetadata();

        boolean originalAutoCommit = conn.getAutoCommit();
        boolean originalReadOnly = conn.isReadOnly();
        Integer originalTransactionIsolation = null;

        try {
            originalTransactionIsolation = conn.getTransactionIsolation();
            conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            conn.setAutoCommit(false);
            conn.setReadOnly(true);

            String schema = getSchemaName(conn);
            metadata.setSchemaName(schema);
            log.info("Extracting schema: {}", schema);

            extractTables(conn, metadata, schema);
            extractColumns(conn, metadata, schema);
            extractConstraints(conn, metadata, schema);
            extractIndexes(conn, metadata, schema);

            validateMetadata(metadata);
            conn.commit();

            long duration = System.currentTimeMillis() - startTime;
            log.info("Schema extraction completed successfully in {}ms - Tables: {}, Indexes: {}, Constraints: {}",
                    duration, metadata.getTables().size(), countIndexes(metadata), countConstraints(metadata));

            return metadata;

        } catch (SQLException e) {
            log.error("Schema extraction failed", e);
            try {
                conn.rollback();
            } catch (SQLException rollbackEx) {
                log.error("Failed to rollback transaction", rollbackEx);
            }
            throw new SQLException("Failed to extract schema metadata: " + e.getMessage(), e);

        } finally {
            try {
                if (originalTransactionIsolation != null) {
                    conn.setTransactionIsolation(originalTransactionIsolation);
                }
                conn.setAutoCommit(originalAutoCommit);
                conn.setReadOnly(originalReadOnly);
            } catch (SQLException e) {
                log.error("Failed to restore connection state", e);
            }
        }
    }

    private void validateConnection(Connection conn) throws SQLException {
        if (conn == null || conn.isClosed()) {
            throw new SQLException("Invalid or closed database connection");
        }

        DatabaseMetaData dbMeta = conn.getMetaData();
        int majorVersion = dbMeta.getDatabaseMajorVersion();
        int minorVersion = dbMeta.getDatabaseMinorVersion();
        log.info("DB2 version: {}.{}", majorVersion, minorVersion);
    }

    private String getSchemaName(Connection conn) throws SQLException {
        if (targetSchema != null && !targetSchema.trim().isEmpty()) {
            return targetSchema.trim().toUpperCase();
        }

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT CURRENT SCHEMA FROM SYSIBM.SYSDUMMY1")) {
            if (rs.next()) {
                String schema = rs.getString(1);
                if (schema == null || schema.trim().isEmpty()) {
                    throw new SQLException("Could not determine current schema");
                }
                return schema.trim().toUpperCase();
            }
            throw new SQLException("Failed to determine current schema");
        }
    }

    private void extractTables(Connection conn, DatabaseMetadata metadata, String schema) throws SQLException {
        String phase = "Tables";
        progressListener.onPhaseStart(phase);
        long startTime = System.currentTimeMillis();

        log.debug("Extracting tables");

        // DB2 System Catalog: SYSCAT.TABLES
        String query = """
            SELECT TABNAME, REMARKS
            FROM SYSCAT.TABLES
            WHERE TABSCHEMA = ? AND TYPE = 'T'
            ORDER BY TABNAME
            """;

        int count = executeWithRetry(() -> {
            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                stmt.setString(1, schema);

                int tableCount = 0;
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String tableName = rs.getString("TABNAME");
                        TableMetadata table = new TableMetadata(tableName);

                        String comment = rs.getString("REMARKS");
                        table.setComment(comment != null ? comment : "");

                        metadata.addTable(table);
                        tableCount++;
                    }
                }
                return tableCount;
            }
        });

        long duration = System.currentTimeMillis() - startTime;
        log.debug("Extracted {} tables in {}ms", count, duration);
        progressListener.onPhaseComplete(phase, count, duration);
    }

    private void extractColumns(Connection conn, DatabaseMetadata metadata, String schema) throws SQLException {
        String phase = "Columns";
        progressListener.onPhaseStart(phase);
        long startTime = System.currentTimeMillis();

        log.debug("Extracting columns");

        // DB2 System Catalog: SYSCAT.COLUMNS
        String query = """
            SELECT TABNAME, COLNAME, COLNO, TYPENAME, LENGTH, SCALE, NULLS, DEFAULT, REMARKS, IDENTITY
            FROM SYSCAT.COLUMNS
            WHERE TABSCHEMA = ?
            ORDER BY TABNAME, COLNO
            """;

        int count = executeWithRetry(() -> {
            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                stmt.setString(1, schema);

                int columnCount = 0;
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String tableName = rs.getString("TABNAME");
                        String columnName = rs.getString("COLNAME");

                        TableMetadata table = metadata.getTable(tableName);
                        if (table == null) {
                            // Can happen if we filtered tables but not columns, or view columns
                            continue;
                        }

                        String dataType = buildDataType(rs);
                        boolean notNull = "N".equals(rs.getString("NULLS"));
                        String defaultValue = normalizeDefault(rs.getString("DEFAULT"));

                        ColumnMetadata column = new ColumnMetadata(columnName, dataType, notNull, defaultValue);
                        column.setOrdinalPosition(rs.getInt("COLNO"));

                        String comment = rs.getString("REMARKS");
                        column.setComment(comment != null ? comment : "");

                        if ("Y".equals(rs.getString("IDENTITY"))) {
                            column.setAutoIncrement(true);
                        }

                        table.addColumn(column);
                        columnCount++;
                    }
                }
                return columnCount;
            }
        });

        long duration = System.currentTimeMillis() - startTime;
        log.debug("Extracted {} columns in {}ms", count, duration);
        progressListener.onPhaseComplete(phase, count, duration);
    }

    private String buildDataType(ResultSet rs) throws SQLException {
        String baseType = rs.getString("TYPENAME");
        if (baseType == null) return "unknown";

        baseType = baseType.toLowerCase().trim();

        if (baseType.equals("varchar") || baseType.equals("char")) {
            int length = rs.getInt("LENGTH");
            if (length > 0) {
                return baseType + "(" + length + ")";
            }
            return baseType;
        }

        if (baseType.equals("decimal")) {
            int precision = rs.getInt("LENGTH"); // In DB2 SYSCAT.COLUMNS, LENGTH is precision for DECIMAL
            int scale = rs.getInt("SCALE");
            if (precision > 0) {
                 return "decimal(" + precision + "," + scale + ")";
            }
            return "decimal";
        }

        return switch (baseType) {
            case "smallint" -> "smallint";
            case "integer" -> "int";
            case "bigint" -> "bigint";
            case "date" -> "date";
            case "time" -> "time";
            case "timestamp" -> "timestamp";
            case "clob" -> "text";
            case "blob" -> "bytea";
            case "double" -> "double precision";
            default -> baseType;
        };
    }

    private String normalizeDefault(String defaultValue) {
        if (defaultValue == null || defaultValue.trim().isEmpty()) {
            return null;
        }

        defaultValue = defaultValue.trim();

        if (defaultValue.startsWith("'") && defaultValue.endsWith("'") && defaultValue.length() > 1) {
            defaultValue = defaultValue.substring(1, defaultValue.length() - 1);
        }

        return defaultValue;
    }

    private void extractConstraints(Connection conn, DatabaseMetadata metadata, String schema) throws SQLException {
        String phase = "Constraints";
        progressListener.onPhaseStart(phase);
        long startTime = System.currentTimeMillis();

        log.debug("Extracting constraints");

        int pkCount = extractPrimaryKeys(conn, metadata, schema);
        int fkCount = extractForeignKeys(conn, metadata, schema);
        int checkCount = extractCheckConstraints(conn, metadata, schema);
        int uniqueCount = extractUniqueConstraints(conn, metadata, schema);

        int total = pkCount + fkCount + checkCount + uniqueCount;
        long duration = System.currentTimeMillis() - startTime;

        log.debug("Extracted {} constraints (PK: {}, FK: {}, Check: {}, Unique: {}) in {}ms",
                total, pkCount, fkCount, checkCount, uniqueCount, duration);
        progressListener.onPhaseComplete(phase, total, duration);
    }

    private int extractPrimaryKeys(Connection conn, DatabaseMetadata metadata, String schema) throws SQLException {
        // SYSCAT.TABCONST (type='P') joined with SYSCAT.KEYCOLUSE
        String query = """
            SELECT tc.TABNAME, kcu.COLNAME, kcu.COLSEQ
            FROM SYSCAT.TABCONST tc
            JOIN SYSCAT.KEYCOLUSE kcu ON tc.CONSTNAME = kcu.CONSTNAME AND tc.TABSCHEMA = kcu.TABSCHEMA
            WHERE tc.TYPE = 'P' AND tc.TABSCHEMA = ?
            ORDER BY tc.TABNAME, kcu.COLSEQ
            """;

        return executeWithRetry(() -> {
            Map<String, List<String>> pkMap = new LinkedHashMap<>();

            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                stmt.setString(1, schema);

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String table = rs.getString("TABNAME");
                        String column = rs.getString("COLNAME");
                        pkMap.computeIfAbsent(table, k -> new ArrayList<>()).add(column);
                    }
                }
            }

            for (Map.Entry<String, List<String>> entry : pkMap.entrySet()) {
                String tableName = entry.getKey();
                TableMetadata table = metadata.getTable(tableName);

                if (table != null) {
                    ConstraintMetadata pk = new ConstraintMetadata(
                            CONSTRAINT_TYPE_PK,
                            CONSTRAINT_TYPE_PK,
                            entry.getValue(),
                            null
                    );
                    pk.setSignature(SignatureGenerator.generate(pk));
                    table.addConstraint(pk);
                }
            }

            return pkMap.size();
        });
    }

    private int extractForeignKeys(Connection conn, DatabaseMetadata metadata, String schema) throws SQLException {
        // SYSCAT.REFERENCES joined with SYSCAT.KEYCOLUSE
        String query = """
            SELECT r.TABNAME, r.CONSTNAME, kcu.COLNAME,
                   r.REFTABNAME, r.DELETERULE, r.UPDATERULE,
                   kcu.COLSEQ
            FROM SYSCAT.REFERENCES r
            JOIN SYSCAT.KEYCOLUSE kcu ON r.CONSTNAME = kcu.CONSTNAME AND r.TABSCHEMA = kcu.TABSCHEMA
            WHERE r.TABSCHEMA = ?
            ORDER BY r.TABNAME, r.CONSTNAME, kcu.COLSEQ
            """;

        // Note: DB2 SYSCAT.REFERENCES gives REFTABNAME but not REFCOLNAME directly in same structure easily.
        // Usually FK columns match PK columns of ref table in order.
        // For simplicity, we might need a more complex query or assume order.
        // Actually SYSCAT.KEYCOLUSE has entries for the FK columns.
        // To get referenced columns, we need to look up the PK/Unique constraint of REFTABNAME that this FK points to (REFKEYNAME).

        String complexQuery = """
            SELECT r.TABNAME, r.CONSTNAME, kcu.COLNAME,
                   r.REFTABNAME,
                   rkcu.COLNAME AS REFCOLNAME,
                   r.DELETERULE, r.UPDATERULE
            FROM SYSCAT.REFERENCES r
            JOIN SYSCAT.KEYCOLUSE kcu ON r.CONSTNAME = kcu.CONSTNAME AND r.TABSCHEMA = kcu.TABSCHEMA
            JOIN SYSCAT.KEYCOLUSE rkcu ON r.REFKEYNAME = rkcu.CONSTNAME AND r.REFTABSCHEMA = rkcu.TABSCHEMA AND kcu.COLSEQ = rkcu.COLSEQ
            WHERE r.TABSCHEMA = ?
            ORDER BY r.TABNAME, r.CONSTNAME, kcu.COLSEQ
            """;

        return executeWithRetry(() -> {
            Map<ForeignKeyIdentifier, ForeignKeyBuilder> fkMap = new LinkedHashMap<>();

            try (PreparedStatement stmt = conn.prepareStatement(complexQuery)) {
                stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                stmt.setString(1, schema);

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String tableName = rs.getString("TABNAME");
                        String constraintName = rs.getString("CONSTNAME");
                        String columnName = rs.getString("COLNAME");
                        String refTableName = rs.getString("REFTABNAME");
                        String refColumnName = rs.getString("REFCOLNAME");
                        String deleteRule = normalizeRule(rs.getString("DELETERULE"));
                        String updateRule = normalizeRule(rs.getString("UPDATERULE"));

                        ForeignKeyIdentifier id = new ForeignKeyIdentifier(tableName, constraintName);
                        ForeignKeyBuilder builder = fkMap.computeIfAbsent(id, k -> new ForeignKeyBuilder(
                                tableName, constraintName, refTableName, updateRule, deleteRule
                        ));

                        builder.addColumn(columnName, refColumnName);
                    }
                }
            }

            for (ForeignKeyBuilder builder : fkMap.values()) {
                ConstraintMetadata fk = builder.build();
                fk.setSignature(SignatureGenerator.generate(fk));

                TableMetadata table = metadata.getTable(builder.tableName);
                if (table != null) {
                    table.addConstraint(fk);
                }
            }

            return fkMap.size();
        });
    }

    private String normalizeRule(String rule) {
        if (rule == null) return "NO ACTION";
        return switch (rule.trim().toUpperCase()) {
            case "C" -> "CASCADE";
            case "N" -> "SET NULL";
            case "R" -> "RESTRICT"; // DB2 RESTRICT is roughly NO ACTION in standard terms but strictly prevents delete
            case "A" -> "NO ACTION";
            default -> "NO ACTION";
        };
    }

    private int extractCheckConstraints(Connection conn, DatabaseMetadata metadata, String schema) throws SQLException {
        // SYSCAT.CHECKS
        String query = """
            SELECT TABNAME, CONSTNAME, TEXT
            FROM SYSCAT.CHECKS
            WHERE TABSCHEMA = ?
            ORDER BY TABNAME, CONSTNAME
            """;

        return executeWithRetry(() -> {
            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                stmt.setString(1, schema);

                int count = 0;
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String tableName = rs.getString("TABNAME");
                        String constraintName = rs.getString("CONSTNAME");
                        String checkClause = rs.getString("TEXT");

                        TableMetadata table = metadata.getTable(tableName);
                        if (table != null) {
                            ConstraintMetadata check = new ConstraintMetadata(
                                    CONSTRAINT_TYPE_CHECK,
                                    constraintName,
                                    new ArrayList<>(),
                                    null
                            );
                            check.setCheckClause(checkClause != null ? checkClause : "");
                            check.setSignature(SignatureGenerator.generate(check));
                            table.addConstraint(check);
                            count++;
                        }
                    }
                }
                return count;
            }
        });
    }

    private int extractUniqueConstraints(Connection conn, DatabaseMetadata metadata, String schema) throws SQLException {
        // SYSCAT.TABCONST (type='U') joined with SYSCAT.KEYCOLUSE
        String query = """
            SELECT tc.TABNAME, kcu.COLNAME, tc.CONSTNAME, kcu.COLSEQ
            FROM SYSCAT.TABCONST tc
            JOIN SYSCAT.KEYCOLUSE kcu ON tc.CONSTNAME = kcu.CONSTNAME AND tc.TABSCHEMA = kcu.TABSCHEMA
            WHERE tc.TYPE = 'U' AND tc.TABSCHEMA = ?
            ORDER BY tc.TABNAME, tc.CONSTNAME, kcu.COLSEQ
            """;

        return executeWithRetry(() -> {
            Map<ConstraintIdentifier, List<String>> uniqueMap = new LinkedHashMap<>();

            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                stmt.setString(1, schema);

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String tableName = rs.getString("TABNAME");
                        String constraintName = rs.getString("CONSTNAME");
                        String columnName = rs.getString("COLNAME");

                        ConstraintIdentifier id = new ConstraintIdentifier(tableName, constraintName);
                        uniqueMap.computeIfAbsent(id, k -> new ArrayList<>()).add(columnName);
                    }
                }
            }

            for (Map.Entry<ConstraintIdentifier, List<String>> entry : uniqueMap.entrySet()) {
                ConstraintIdentifier id = entry.getKey();
                TableMetadata table = metadata.getTable(id.tableName);

                if (table != null) {
                    ConstraintMetadata unique = new ConstraintMetadata(
                            CONSTRAINT_TYPE_UNIQUE,
                            id.constraintName,
                            entry.getValue(),
                            null
                    );
                    unique.setSignature(SignatureGenerator.generate(unique));
                    table.addConstraint(unique);
                }
            }

            return uniqueMap.size();
        });
    }

    private void extractIndexes(Connection conn, DatabaseMetadata metadata, String schema) throws SQLException {
        String phase = "Indexes";
        progressListener.onPhaseStart(phase);
        long startTime = System.currentTimeMillis();

        log.debug("Extracting indexes");

        // SYSCAT.INDEXES joined with SYSCAT.INDEXCOLUSE
        // Filter out Primary Key indexes usually (UNIQUERULE='P')
        String query = """
            SELECT i.TABNAME, i.INDNAME, ic.COLNAME, i.UNIQUERULE, i.INDEXTYPE, ic.COLSEQ
            FROM SYSCAT.INDEXES i
            JOIN SYSCAT.INDEXCOLUSE ic ON i.INDNAME = ic.INDNAME AND i.INDSCHEMA = ic.INDSCHEMA
            WHERE i.TABSCHEMA = ?
            AND i.UNIQUERULE <> 'P'
            ORDER BY i.TABNAME, i.INDNAME, ic.COLSEQ
            """;

        int count = executeWithRetry(() -> {
            Map<IndexIdentifier, IndexBuilder> indexMap = new LinkedHashMap<>();

            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                stmt.setString(1, schema);

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String tableName = rs.getString("TABNAME");
                        String indexName = rs.getString("INDNAME");
                        String columnName = rs.getString("COLNAME");
                        String uniqueRule = rs.getString("UNIQUERULE");
                        boolean unique = "U".equals(uniqueRule);
                        String indexType = rs.getString("INDEXTYPE");

                        IndexIdentifier id = new IndexIdentifier(tableName, indexName);
                        IndexBuilder builder = indexMap.computeIfAbsent(id, k ->
                                new IndexBuilder(tableName, indexName, unique, indexType)
                        );

                        builder.addColumn(columnName);
                    }
                }
            }

            for (IndexBuilder builder : indexMap.values()) {
                IndexMetadata index = builder.build();
                TableMetadata table = metadata.getTable(builder.tableName);

                if (table != null) {
                    table.addIndex(index);
                }
            }

            return indexMap.size();
        });

        long duration = System.currentTimeMillis() - startTime;
        log.debug("Extracted {} indexes in {}ms", count, duration);
        progressListener.onPhaseComplete(phase, count, duration);
    }

    private void validateMetadata(DatabaseMetadata metadata) {
        log.debug("Validating extracted metadata");
        // Reuse validation logic if needed
    }

    private <T> T executeWithRetry(SQLCallable<T> callable) throws SQLException {
        if (!enableRetry) {
            return callable.call();
        }

        int attempt = 0;
        SQLException lastException = null;

        while (attempt < MAX_RETRIES) {
            try {
                return callable.call();
            } catch (SQLException e) {
                lastException = e;
                attempt++;

                if (!isRetryable(e) || attempt >= MAX_RETRIES) {
                    throw e;
                }

                long backoffMs = 1000L * attempt;
                log.warn("Retryable error on attempt {}/{}: {} - Retrying in {}ms",
                        attempt, MAX_RETRIES, e.getMessage(), backoffMs);

                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new SQLException("Interrupted during retry backoff", ie);
                }
            }
        }

        throw lastException;
    }

    private boolean isRetryable(SQLException e) {
        int errorCode = e.getErrorCode();
        // DB2 retryable codes (deadlock, timeout)
        return errorCode == -911 || errorCode == -913;
    }

    private int countIndexes(DatabaseMetadata metadata) {
        return metadata.getTables().values().stream()
                .mapToInt(t -> t.getIndexes().size())
                .sum();
    }

    private int countConstraints(DatabaseMetadata metadata) {
        return metadata.getTables().values().stream()
                .mapToInt(t -> t.getConstraints().size())
                .sum();
    }

    @FunctionalInterface
    private interface SQLCallable<V> {
        V call() throws SQLException;
    }

    private static class NoOpProgress implements ExtractionProgress {
        @Override public void onPhaseStart(String phase) {}
        @Override public void onPhaseComplete(String phase, int itemsProcessed, long durationMs) {}
        @Override public void onWarning(String message) {}
    }

    private record ForeignKeyIdentifier(String tableName, String constraintName) {}

    private static class ForeignKeyBuilder {
        final String tableName;
        final String constraintName;
        final String refTableName;
        final String updateRule;
        final String deleteRule;
        final List<String> columns = new ArrayList<>();
        final List<String> refColumns = new ArrayList<>();

        ForeignKeyBuilder(String tableName, String constraintName, String refTableName,
                          String updateRule, String deleteRule) {
            this.tableName = tableName;
            this.constraintName = constraintName;
            this.refTableName = refTableName;
            this.updateRule = updateRule;
            this.deleteRule = deleteRule;
        }

        void addColumn(String column, String refColumn) {
            columns.add(column);
            refColumns.add(refColumn);
        }

        ConstraintMetadata build() {
            ConstraintMetadata fk = new ConstraintMetadata(
                    CONSTRAINT_TYPE_FK,
                    constraintName,
                    columns,
                    null
            );
            fk.setReferencedTable(refTableName);
            fk.setReferencedColumns(refColumns);
            fk.setUpdateRule(updateRule);
            fk.setDeleteRule(deleteRule);
            return fk;
        }
    }

    private record IndexIdentifier(String tableName, String indexName) {}

    private static class IndexBuilder {
        final String tableName;
        final String indexName;
        final boolean unique;
        final String indexType;
        final List<String> columns = new ArrayList<>();

        IndexBuilder(String tableName, String indexName, boolean unique, String indexType) {
            this.tableName = tableName;
            this.indexName = indexName;
            this.unique = unique;
            this.indexType = indexType;
        }

        void addColumn(String column) {
            columns.add(column);
        }

        IndexMetadata build() {
            IndexMetadata index = new IndexMetadata(indexName, columns, unique);
            index.setIndexType(indexType != null ? indexType : "REGULAR");
            return index;
        }
    }

    private record ConstraintIdentifier(String tableName, String constraintName) {}
}
