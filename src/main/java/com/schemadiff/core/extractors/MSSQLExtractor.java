package com.schemadiff.core.extractors;

import com.schemadiff.core.MetadataExtractor;
import com.schemadiff.core.SignatureGenerator;
import com.schemadiff.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;

/**
 * Microsoft SQL Server-specific metadata extractor with comprehensive support for:
 * - IDENTITY auto-increment columns
 * - CHECK constraints
 * - Foreign key ON DELETE/UPDATE rules
 * - NVARCHAR, VARCHAR(MAX), and other MSSQL types
 * - Computed columns and default constraints
 * - Index types (CLUSTERED, NONCLUSTERED, COLUMNSTORE, etc.)
 */
public class MSSQLExtractor extends MetadataExtractor {

    private static final Logger log = LoggerFactory.getLogger(MSSQLExtractor.class);

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

    public MSSQLExtractor() {
        this("dbo", true, null);
    }

    public MSSQLExtractor(String targetSchema) {
        this(targetSchema, true, null);
    }

    public MSSQLExtractor(String targetSchema, boolean enableRetry, ExtractionProgress progressListener) {
        this.targetSchema = targetSchema != null ? targetSchema : "dbo";
        this.enableRetry = enableRetry;
        this.progressListener = progressListener != null ? progressListener : new NoOpProgress();
    }

    @Override
    public DatabaseMetadata extract(Connection conn) throws SQLException {
        log.info("Starting SQL Server schema extraction");
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

            metadata.setSchemaName(targetSchema);
            log.info("Extracting schema: {}", targetSchema);

            extractTables(conn, metadata);
            extractColumns(conn, metadata);
            extractConstraints(conn, metadata);
            extractIndexes(conn, metadata);

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
        log.info("SQL Server version: {}.{}", majorVersion, minorVersion);

        if (majorVersion < 11) { // SQL Server 2012 = v11
            log.warn("SQL Server version {}.{} may not support all features. Recommended: 2012+",
                    majorVersion, minorVersion);
        }
    }


    private void extractTables(Connection conn, DatabaseMetadata metadata) throws SQLException {
        String phase = "Tables";
        progressListener.onPhaseStart(phase);
        long startTime = System.currentTimeMillis();

        log.debug("Extracting tables");

        String query = """
            SELECT 
                t.name AS table_name,
                ep.value AS table_comment,
                t.create_date,
                t.modify_date
            FROM sys.tables t
            LEFT JOIN sys.extended_properties ep 
                ON t.object_id = ep.major_id 
                AND ep.minor_id = 0 
                AND ep.class = 1
                AND ep.name = 'MS_Description'
            WHERE t.type = 'U'
            AND SCHEMA_NAME(t.schema_id) = ?
            ORDER BY t.name
            """;

        int count = executeWithRetry(() -> {
            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                stmt.setString(1, targetSchema);

                int tableCount = 0;
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String tableName = rs.getString("table_name");
                        TableMetadata table = new TableMetadata(tableName);

                        String comment = rs.getString("table_comment");
                        table.setComment(comment != null ? comment : "");

                        table.setCreateTime(rs.getTimestamp("create_date"));
                        table.setUpdateTime(rs.getTimestamp("modify_date"));

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


    private void extractColumns(Connection conn, DatabaseMetadata metadata) throws SQLException {
        String phase = "Columns";
        progressListener.onPhaseStart(phase);
        long startTime = System.currentTimeMillis();

        log.debug("Extracting columns");

        String query = """
            SELECT
                t.name AS table_name,
                c.name AS column_name,
                c.column_id AS ordinal_position,
                ty.name AS data_type,
                c.max_length,
                c.precision,
                c.scale,
                c.is_nullable,
                c.is_identity,
                c.is_computed,
                dc.definition AS default_value,
                cc.definition AS computed_definition,
                ep.value AS column_comment
            FROM sys.tables t
            JOIN sys.columns c ON t.object_id = c.object_id
            JOIN sys.types ty ON c.user_type_id = ty.user_type_id
            LEFT JOIN sys.default_constraints dc ON c.default_object_id = dc.object_id
            LEFT JOIN sys.computed_columns cc ON t.object_id = cc.object_id AND c.column_id = cc.column_id
            LEFT JOIN sys.extended_properties ep 
                ON t.object_id = ep.major_id 
                AND c.column_id = ep.minor_id 
                AND ep.class = 1
                AND ep.name = 'MS_Description'
            WHERE t.type = 'U'
            AND SCHEMA_NAME(t.schema_id) = ?
            ORDER BY t.name, c.column_id
            """;

        int count = executeWithRetry(() -> {
            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                stmt.setString(1, targetSchema);

                int columnCount = 0;
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String tableName = rs.getString("table_name");
                        String columnName = rs.getString("column_name");

                        TableMetadata table = metadata.getTable(tableName);
                        if (table == null) {
                            log.warn("Column {}.{} found for non-existent table", tableName, columnName);
                            continue;
                        }

                        String dataType = buildDataType(rs);
                        boolean notNull = !rs.getBoolean("is_nullable");
                        String defaultValue = normalizeDefault(rs.getString("default_value"));

                        ColumnMetadata column = new ColumnMetadata(columnName, dataType, notNull, defaultValue);
                        column.setOrdinalPosition(rs.getInt("ordinal_position"));

                        String comment = rs.getString("column_comment");
                        column.setComment(comment != null ? comment : "");

                        // Detect IDENTITY (auto-increment)
                        if (rs.getBoolean("is_identity")) {
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
        String baseType = rs.getString("data_type");
        if (baseType == null) return "unknown";

        baseType = baseType.toLowerCase().trim();

        // Handle character types
        if (baseType.contains("varchar") || baseType.contains("char")) {
            int maxLength = rs.getInt("max_length");
            if (maxLength == -1) {
                return baseType + "(max)";
            }
            // NVARCHAR/NCHAR stores 2 bytes per character
            if (baseType.startsWith("n")) {
                maxLength = maxLength / 2;
            }
            if (maxLength > 0) {
                // Normalize type name
                if (baseType.contains("varchar")) {
                    return "varchar(" + maxLength + ")";
                } else {
                    return "char(" + maxLength + ")";
                }
            }
            return baseType.contains("varchar") ? "varchar" : "char";
        }

        // Handle numeric types
        if (baseType.equals("decimal") || baseType.equals("numeric")) {
            int precision = rs.getInt("precision");
            int scale = rs.getInt("scale");
            if (precision > 0) {
                if (scale > 0) {
                    return baseType + "(" + precision + "," + scale + ")";
                }
                return baseType + "(" + precision + ")";
            }
        }

        // Normalize MSSQL-specific types
        return switch (baseType) {
            case "int" -> "int";
            case "bigint" -> "bigint";
            case "smallint" -> "smallint";
            case "tinyint" -> "tinyint";
            case "bit" -> "boolean";
            case "datetime", "datetime2", "smalldatetime" -> "timestamp";
            case "date" -> "date";
            case "time" -> "time";
            case "text", "ntext" -> "text";
            case "varbinary", "binary" -> "bytea";
            case "image" -> "bytea";
            case "uniqueidentifier" -> "uuid";
            case "xml" -> "xml";
            case "money", "smallmoney" -> "decimal";
            case "real" -> "real";
            case "float" -> "double precision";
            default -> baseType;
        };
    }

    private String normalizeDefault(String defaultValue) {
        if (defaultValue == null || defaultValue.trim().isEmpty()) {
            return null;
        }

        // Remove parentheses wrapping (SQL Server wraps defaults in parens)
        defaultValue = defaultValue.trim();
        while (defaultValue.startsWith("(") && defaultValue.endsWith(")")) {
            defaultValue = defaultValue.substring(1, defaultValue.length() - 1).trim();
        }

        // Remove single quotes for string literals
        if (defaultValue.startsWith("'") && defaultValue.endsWith("'") && defaultValue.length() > 1) {
            defaultValue = defaultValue.substring(1, defaultValue.length() - 1);
        }

        return defaultValue;
    }


    private void extractConstraints(Connection conn, DatabaseMetadata metadata) throws SQLException {
        String phase = "Constraints";
        progressListener.onPhaseStart(phase);
        long startTime = System.currentTimeMillis();

        log.debug("Extracting constraints");

        int pkCount = extractPrimaryKeys(conn, metadata);
        int fkCount = extractForeignKeys(conn, metadata);
        int checkCount = extractCheckConstraints(conn, metadata);
        int uniqueCount = extractUniqueConstraints(conn, metadata);

        int total = pkCount + fkCount + checkCount + uniqueCount;
        long duration = System.currentTimeMillis() - startTime;

        log.debug("Extracted {} constraints (PK: {}, FK: {}, Check: {}, Unique: {}) in {}ms",
                total, pkCount, fkCount, checkCount, uniqueCount, duration);
        progressListener.onPhaseComplete(phase, total, duration);
    }

    private int extractPrimaryKeys(Connection conn, DatabaseMetadata metadata) throws SQLException {
        String query = """
            SELECT
                t.name AS table_name,
                c.name AS column_name,
                ic.key_ordinal,
                kc.name AS constraint_name
            FROM sys.tables t
            JOIN sys.indexes i ON t.object_id = i.object_id
            JOIN sys.index_columns ic ON i.object_id = ic.object_id AND i.index_id = ic.index_id
            JOIN sys.columns c ON ic.object_id = c.object_id AND ic.column_id = c.column_id
            JOIN sys.key_constraints kc ON i.object_id = kc.parent_object_id AND i.index_id = kc.unique_index_id
            WHERE kc.type = 'PK'
            AND SCHEMA_NAME(t.schema_id) = ?
            ORDER BY t.name, ic.key_ordinal
            """;

        return executeWithRetry(() -> {
            Map<String, List<String>> pkMap = new LinkedHashMap<>();

            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                stmt.setString(1, targetSchema);

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String table = rs.getString("table_name");
                        String column = rs.getString("column_name");
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

    private int extractForeignKeys(Connection conn, DatabaseMetadata metadata) throws SQLException {
        String query = """
            SELECT
                t.name AS table_name,
                c.name AS column_name,
                rt.name AS ref_table_name,
                rc.name AS ref_column_name,
                fk.name AS constraint_name,
                fk.delete_referential_action_desc AS delete_rule,
                fk.update_referential_action_desc AS update_rule,
                fkc.constraint_column_id
            FROM sys.foreign_keys fk
            JOIN sys.tables t ON fk.parent_object_id = t.object_id
            JOIN sys.tables rt ON fk.referenced_object_id = rt.object_id
            JOIN sys.foreign_key_columns fkc ON fk.object_id = fkc.constraint_object_id
            JOIN sys.columns c ON fkc.parent_object_id = c.object_id AND fkc.parent_column_id = c.column_id
            JOIN sys.columns rc ON fkc.referenced_object_id = rc.object_id AND fkc.referenced_column_id = rc.column_id
            WHERE SCHEMA_NAME(t.schema_id) = ?
            ORDER BY t.name, fk.name, fkc.constraint_column_id
            """;

        return executeWithRetry(() -> {
            Map<ForeignKeyIdentifier, ForeignKeyBuilder> fkMap = new LinkedHashMap<>();

            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                stmt.setString(1, targetSchema);

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String tableName = rs.getString("table_name");
                        String constraintName = rs.getString("constraint_name");
                        String columnName = rs.getString("column_name");
                        String refTableName = rs.getString("ref_table_name");
                        String refColumnName = rs.getString("ref_column_name");
                        String updateRule = normalizeRule(rs.getString("update_rule"));
                        String deleteRule = normalizeRule(rs.getString("delete_rule"));

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
        if (rule == null) return "NO_ACTION";
        return switch (rule.toUpperCase()) {
            case "NO_ACTION" -> "NO ACTION";
            case "CASCADE" -> "CASCADE";
            case "SET_NULL" -> "SET NULL";
            case "SET_DEFAULT" -> "SET DEFAULT";
            default -> rule;
        };
    }

    private int extractCheckConstraints(Connection conn, DatabaseMetadata metadata) throws SQLException {
        String query = """
            SELECT
                t.name AS table_name,
                cc.name AS constraint_name,
                cc.definition AS check_clause
            FROM sys.check_constraints cc
            JOIN sys.tables t ON cc.parent_object_id = t.object_id
            WHERE SCHEMA_NAME(t.schema_id) = ?
            ORDER BY t.name, cc.name
            """;

        return executeWithRetry(() -> {
            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                stmt.setString(1, targetSchema);

                int count = 0;
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String tableName = rs.getString("table_name");
                        String constraintName = rs.getString("constraint_name");
                        String checkClause = rs.getString("check_clause");

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

    private int extractUniqueConstraints(Connection conn, DatabaseMetadata metadata) throws SQLException {
        String query = """
            SELECT
                t.name AS table_name,
                c.name AS column_name,
                kc.name AS constraint_name,
                ic.key_ordinal
            FROM sys.tables t
            JOIN sys.indexes i ON t.object_id = i.object_id
            JOIN sys.index_columns ic ON i.object_id = ic.object_id AND i.index_id = ic.index_id
            JOIN sys.columns c ON ic.object_id = c.object_id AND ic.column_id = c.column_id
            JOIN sys.key_constraints kc ON i.object_id = kc.parent_object_id AND i.index_id = kc.unique_index_id
            WHERE kc.type = 'UQ'
            AND SCHEMA_NAME(t.schema_id) = ?
            ORDER BY t.name, kc.name, ic.key_ordinal
            """;

        return executeWithRetry(() -> {
            Map<ConstraintIdentifier, List<String>> uniqueMap = new LinkedHashMap<>();

            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                stmt.setString(1, targetSchema);

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String tableName = rs.getString("table_name");
                        String constraintName = rs.getString("constraint_name");
                        String columnName = rs.getString("column_name");

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


    private void extractIndexes(Connection conn, DatabaseMetadata metadata) throws SQLException {
        String phase = "Indexes";
        progressListener.onPhaseStart(phase);
        long startTime = System.currentTimeMillis();

        log.debug("Extracting indexes");

        String query = """
            SELECT
                t.name AS table_name,
                i.name AS index_name,
                c.name AS column_name,
                i.is_unique,
                i.type_desc AS index_type,
                ic.key_ordinal
            FROM sys.tables t
            JOIN sys.indexes i ON t.object_id = i.object_id
            JOIN sys.index_columns ic ON i.object_id = ic.object_id AND i.index_id = ic.index_id
            JOIN sys.columns c ON ic.object_id = c.object_id AND ic.column_id = c.column_id
            WHERE i.is_primary_key = 0
            AND i.is_unique_constraint = 0
            AND SCHEMA_NAME(t.schema_id) = ?
            ORDER BY t.name, i.name, ic.key_ordinal
            """;

        int count = executeWithRetry(() -> {
            Map<IndexIdentifier, IndexBuilder> indexMap = new LinkedHashMap<>();

            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                stmt.setString(1, targetSchema);

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String tableName = rs.getString("table_name");
                        String indexName = rs.getString("index_name");
                        String columnName = rs.getString("column_name");
                        boolean unique = rs.getBoolean("is_unique");
                        String indexType = rs.getString("index_type");

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

        int issues = 0;

        for (TableMetadata table : metadata.getTables().values()) {
            if (table.getColumns().isEmpty()) {
                log.warn("Table {} has no columns", table.getName());
                issues++;
            }

            for (ConstraintMetadata constraint : table.getConstraints()) {
                if (CONSTRAINT_TYPE_FK.equals(constraint.getType())) {
                    String refTable = constraint.getReferencedTable();
                    if (refTable != null && metadata.getTable(refTable) == null) {
                        log.warn("FK {} in table {} references non-existent table: {}",
                                constraint.getName(), table.getName(), refTable);
                        issues++;
                    }
                }
            }
        }

        if (issues > 0) {
            log.warn("Metadata validation found {} potential issues", issues);
        } else {
            log.debug("Metadata validation passed");
        }
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
        String sqlState = e.getSQLState();
        if (sqlState != null && sqlState.startsWith("40")) {
            return true; // Transaction/deadlock errors
        }

        // MSSQL-specific error codes
        int errorCode = e.getErrorCode();
        return errorCode == 1205 || // Deadlock
               errorCode == 1204 || // Lock issue
               errorCode == -2;      // Timeout
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
            // Normalize MSSQL index types
            String normalizedType = indexType != null ? indexType.replace("_", " ") : "NONCLUSTERED";
            index.setIndexType(normalizedType);
            return index;
        }
    }

    private record ConstraintIdentifier(String tableName, String constraintName) {}
}