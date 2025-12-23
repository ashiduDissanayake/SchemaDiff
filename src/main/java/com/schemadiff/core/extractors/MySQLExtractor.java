package com.schemadiff.core.extractors;

import com.schemadiff.core.MetadataExtractor;
import com.schemadiff.core.SignatureGenerator;
import com.schemadiff.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class MySQLExtractor extends MetadataExtractor {

    private static final Logger log = LoggerFactory.getLogger(MySQLExtractor.class);

    // Constants
    private static final String CONSTRAINT_TYPE_PK = "PRIMARY_KEY";
    private static final String CONSTRAINT_TYPE_FK = "FOREIGN_KEY";
    private static final String CONSTRAINT_TYPE_CHECK = "CHECK";
    private static final String CONSTRAINT_TYPE_UNIQUE = "UNIQUE";
    private static final String PRIMARY_KEY_NAME = "PRIMARY";
    private static final int MAX_TYPE_LENGTH = 999999;
    private static final int QUERY_TIMEOUT_SECONDS = 300; // 5 minutes
    private static final int MAX_RETRIES = 3;
    private static final String NULLABLE_NO = "NO";
    private static final String NULLABLE_YES = "YES";

    // Configuration
    private final String targetSchema;
    private final boolean enableRetry;
    private final ExtractionProgress progressListener;

    /**
     * Progress callback interface for tracking extraction phases
     */
    public interface ExtractionProgress {
        void onPhaseStart(String phase);
        void onPhaseComplete(String phase, int itemsProcessed, long durationMs);
        void onWarning(String message);
    }

    public MySQLExtractor() {
        this(null, true, null);
    }

    public MySQLExtractor(String targetSchema) {
        this(targetSchema, true, null);
    }

    public MySQLExtractor(String targetSchema, boolean enableRetry, ExtractionProgress progressListener) {
        this.targetSchema = targetSchema;
        this.enableRetry = enableRetry;
        this.progressListener = progressListener != null ? progressListener : new NoOpProgress();
    }

    @Override
    public DatabaseMetadata extract(Connection conn) throws SQLException {
        log.info("Starting MySQL schema extraction");
        long startTime = System.currentTimeMillis();

        validateConnection(conn);
        DatabaseMetadata metadata = null;

        // Save original connection state
        boolean originalAutoCommit = conn.getAutoCommit();
        boolean originalReadOnly = conn.isReadOnly();
        Integer originalTransactionIsolation = null;

        try {
            // Set up consistent snapshot for extraction
            originalTransactionIsolation = conn.getTransactionIsolation();
            conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            conn.setAutoCommit(false);
            conn.setReadOnly(true);

            // Start transaction with consistent snapshot (InnoDB)
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY");
            }

            metadata = new DatabaseMetadata();
            String schemaName = getSchemaName(conn);
            metadata.setSchemaName(schemaName);
            log.info("Extracting schema: {}", schemaName);

            // Extract in dependency order
            extractTables(conn, metadata, schemaName);
            extractColumns(conn, metadata, schemaName);
            extractConstraints(conn, metadata, schemaName);
            extractIndexes(conn, metadata, schemaName);

            // Validate extracted metadata
            validateMetadata(metadata);

            conn.commit();

            long duration = System.currentTimeMillis() - startTime;
            log.info("Schema extraction completed successfully in {}ms - Tables: {}, Indexes: {}, Constraints: {}",
                    duration,
                    metadata.getTables().size(),
                    countIndexes(metadata),
                    countConstraints(metadata));

            return metadata;

        } catch (SQLException e) {
            log.error("Schema extraction failed", e);
            try {
                conn.rollback();
            } catch (SQLException rollbackEx) {
                log.error("Failed to rollback transaction", rollbackEx);
            }
            // Clear partial state
            metadata = null;
            throw new SQLException("Failed to extract schema metadata: " + e.getMessage(), e);

        } finally {
            // Always restore original connection state
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

        // Verify MySQL version and set UTF-8
        DatabaseMetaData dbMeta = conn.getMetaData();
        int majorVersion = dbMeta.getDatabaseMajorVersion();
        int minorVersion = dbMeta.getDatabaseMinorVersion();
        log.info("MySQL version: {}.{}", majorVersion, minorVersion);

        if (majorVersion < 5 || (majorVersion == 5 && minorVersion < 7)) {
            log.warn("MySQL version {}.{} may not support all features. Recommended: 5.7+",
                    majorVersion, minorVersion);
        }

        // Ensure UTF-8 encoding for international characters
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("SET NAMES utf8mb4");
        } catch (SQLException e) {
            log.warn("Failed to set UTF-8 encoding: {}", e.getMessage());
        }
    }

    private String getSchemaName(Connection conn) throws SQLException {
        if (targetSchema != null && !targetSchema.trim().isEmpty()) {
            return targetSchema.trim();
        }

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT DATABASE()")) {
            if (rs.next()) {
                String schema = rs.getString(1);
                if (schema == null || schema.trim().isEmpty()) {
                    throw new SQLException("No database selected. Use 'USE database_name' or specify targetSchema");
                }
                return schema.trim();
            }
            throw new SQLException("Failed to determine current database");
        }
    }

    private void extractTables(Connection conn, DatabaseMetadata metadata, String schemaName)
            throws SQLException {
        String phase = "Tables";
        progressListener.onPhaseStart(phase);
        long startTime = System.currentTimeMillis();

        log.debug("Extracting tables");

        String query = """
            SELECT 
                TABLE_NAME,
                ENGINE,
                TABLE_COLLATION,
                TABLE_COMMENT,
                CREATE_TIME,
                UPDATE_TIME,
                TABLE_ROWS,
                AVG_ROW_LENGTH,
                DATA_LENGTH
            FROM INFORMATION_SCHEMA.TABLES 
            WHERE TABLE_SCHEMA = ? 
              AND TABLE_TYPE = 'BASE TABLE'
            ORDER BY TABLE_NAME
            """;

        int count = executeWithRetry(() -> {
            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                stmt.setString(1, schemaName);

                int tableCount = 0;
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String tableName = rs.getString("TABLE_NAME");

                        try {
                            TableMetadata table = new TableMetadata(tableName);

                            // Core properties
                            table.setEngine(rs.getString("ENGINE"));
                            table.setCollation(rs.getString("TABLE_COLLATION"));

                            // Handle NULL values properly
                            String comment = rs.getString("TABLE_COMMENT");
                            table.setComment(comment != null ? comment : "");

                            table.setCreateTime(rs.getTimestamp("CREATE_TIME"));
                            table.setUpdateTime(rs.getTimestamp("UPDATE_TIME"));

                            // Statistics (optional, can be null)
                            Long tableRows = getNullableLong(rs, "TABLE_ROWS");
                            if (tableRows != null) {
                                table.setTableRows(tableRows);
                            }

                            metadata.addTable(table);
                            tableCount++;

                            if (tableCount % 100 == 0) {
                                log.debug("Processed {} tables...", tableCount);
                            }

                        } catch (Exception e) {
                            String warning = String.format("Failed to extract table: %s - %s",
                                    tableName, e.getMessage());
                            log.error(warning, e);
                            progressListener.onWarning(warning);
                            throw new SQLException("Error processing table: " + tableName, e);
                        }
                    }
                }
                return tableCount;
            }
        });

        long duration = System.currentTimeMillis() - startTime;
        log.debug("Extracted {} tables in {}ms", count, duration);
        progressListener.onPhaseComplete(phase, count, duration);
    }

    private void extractColumns(Connection conn, DatabaseMetadata metadata, String schemaName)
            throws SQLException {
        String phase = "Columns";
        progressListener.onPhaseStart(phase);
        long startTime = System.currentTimeMillis();

        log.debug("Extracting columns");

        String query = """
            SELECT 
                TABLE_NAME, 
                COLUMN_NAME, 
                ORDINAL_POSITION,
                DATA_TYPE, 
                CHARACTER_MAXIMUM_LENGTH,
                NUMERIC_PRECISION, 
                NUMERIC_SCALE, 
                IS_NULLABLE, 
                COLUMN_DEFAULT,
                COLUMN_TYPE,
                COLUMN_KEY,
                EXTRA,
                COLUMN_COMMENT,
                CHARACTER_SET_NAME,
                COLLATION_NAME
            FROM INFORMATION_SCHEMA.COLUMNS
            WHERE TABLE_SCHEMA = ?
            ORDER BY TABLE_NAME, ORDINAL_POSITION
            """;

        int count = executeWithRetry(() -> {
            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                stmt.setString(1, schemaName);

                int columnCount = 0;
                String currentTable = null;

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String tableName = rs.getString("TABLE_NAME");
                        String columnName = rs.getString("COLUMN_NAME");

                        try {
                            TableMetadata table = metadata.getTable(tableName);
                            if (table == null) {
                                String warning = String.format(
                                        "Column %s.%s found for non-existent table",
                                        tableName, columnName);
                                log.warn(warning);
                                progressListener.onWarning(warning);
                                continue;
                            }

                            if (!tableName.equals(currentTable)) {
                                currentTable = tableName;
                                log.trace("Processing columns for table: {}", tableName);
                            }

                            // Build column metadata
                            String dataType = buildDataType(rs);
                            boolean notNull = NULLABLE_NO.equals(rs.getString("IS_NULLABLE"));
                            String defaultValue = rs.getString("COLUMN_DEFAULT");

                            ColumnMetadata column = new ColumnMetadata(
                                    columnName, dataType, notNull, defaultValue
                            );

                            // Extended metadata
                            column.setOrdinalPosition(rs.getInt("ORDINAL_POSITION"));
                            column.setColumnType(rs.getString("COLUMN_TYPE"));

                            String extra = rs.getString("EXTRA");
                            column.setAutoIncrement(extra != null && extra.toLowerCase().contains("auto_increment"));

                            String comment = rs.getString("COLUMN_COMMENT");
                            column.setComment(comment != null ? comment : "");

                            column.setCharacterSet(rs.getString("CHARACTER_SET_NAME"));
                            column.setCollation(rs.getString("COLLATION_NAME"));

                            // Parse UNSIGNED from COLUMN_TYPE
                            String columnType = rs.getString("COLUMN_TYPE");
                            if (columnType != null && columnType.toLowerCase().contains("unsigned")) {
                                column.setUnsigned(true);
                            }

                            table.addColumn(column);
                            columnCount++;

                            if (columnCount % 1000 == 0) {
                                log.debug("Processed {} columns...", columnCount);
                            }

                        } catch (Exception e) {
                            String warning = String.format("Failed to extract column: %s.%s - %s",
                                    tableName, columnName, e.getMessage());
                            log.error(warning, e);
                            progressListener.onWarning(warning);
                            throw new SQLException("Error processing column in table: " + tableName, e);
                        }
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
        String baseType = rs.getString("DATA_TYPE");
        if (baseType == null || baseType.trim().isEmpty()) {
            return "unknown";
        }
        baseType = baseType.toLowerCase().trim();

        // Use safe nullable getters
        Long length = getNullableLong(rs, "CHARACTER_MAXIMUM_LENGTH");
        Long precision = getNullableLong(rs, "NUMERIC_PRECISION");
        Long scale = getNullableLong(rs, "NUMERIC_SCALE");

        // Build type string with sensible limits
        if (length != null && length > 0) {
            long cappedLength = Math.min(length, MAX_TYPE_LENGTH);
            return baseType + "(" + cappedLength + ")";
        }

        if (precision != null && precision > 0) {
            if (scale != null && scale > 0) {
                return baseType + "(" + precision + "," + scale + ")";
            }
            return baseType + "(" + precision + ")";
        }

        return baseType;
    }

    private Long getNullableLong(ResultSet rs, String columnName) {
        try {
            long value = rs.getLong(columnName);
            return rs.wasNull() ? null : value;
        } catch (SQLException e) {
            log.trace("Column {} not available or not numeric: {}", columnName, e.getMessage());
            return null;
        }
    }

    private void extractConstraints(Connection conn, DatabaseMetadata metadata, String schemaName)
            throws SQLException {
        String phase = "Constraints";
        progressListener.onPhaseStart(phase);
        long startTime = System.currentTimeMillis();

        log.debug("Extracting constraints");

        int pkCount = extractPrimaryKeys(conn, metadata, schemaName);
        int fkCount = extractForeignKeys(conn, metadata, schemaName);
        int checkCount = extractCheckConstraints(conn, metadata, schemaName);
        int uniqueCount = extractUniqueConstraints(conn, metadata, schemaName);

        int total = pkCount + fkCount + checkCount + uniqueCount;
        long duration = System.currentTimeMillis() - startTime;

        log.debug("Extracted {} constraints (PK: {}, FK: {}, Check: {}, Unique: {}) in {}ms",
                total, pkCount, fkCount, checkCount, uniqueCount, duration);
        progressListener.onPhaseComplete(phase, total, duration);
    }

    private int extractPrimaryKeys(Connection conn, DatabaseMetadata metadata, String schemaName)
            throws SQLException {
        String query = """
            SELECT 
                TABLE_NAME, 
                COLUMN_NAME,
                ORDINAL_POSITION
            FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE
            WHERE CONSTRAINT_SCHEMA = ? 
              AND CONSTRAINT_NAME = ?
            ORDER BY TABLE_NAME, ORDINAL_POSITION
            """;

        return executeWithRetry(() -> {
            Map<String, List<String>> pkMap = new LinkedHashMap<>();

            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                stmt.setString(1, schemaName);
                stmt.setString(2, PRIMARY_KEY_NAME);

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String table = rs.getString("TABLE_NAME");
                        String column = rs.getString("COLUMN_NAME");
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
                            PRIMARY_KEY_NAME,
                            entry.getValue(),
                            null
                    );
                    pk.setSignature(SignatureGenerator.generate(pk));
                    table.addConstraint(pk);

                    log.trace("Added PK to {}: {}", tableName, entry.getValue());
                } else {
                    progressListener.onWarning("PK found for non-existent table: " + tableName);
                }
            }

            return pkMap.size();
        });
    }

    private int extractForeignKeys(Connection conn, DatabaseMetadata metadata, String schemaName)
            throws SQLException {
        String query = """
            SELECT 
                kcu.TABLE_NAME,
                kcu.CONSTRAINT_NAME,
                kcu.COLUMN_NAME,
                kcu.ORDINAL_POSITION,
                kcu.REFERENCED_TABLE_NAME,
                kcu.REFERENCED_COLUMN_NAME,
                rc.UPDATE_RULE,
                rc.DELETE_RULE
            FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE kcu
            JOIN INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS rc
              ON kcu.CONSTRAINT_SCHEMA = rc.CONSTRAINT_SCHEMA
             AND kcu.CONSTRAINT_NAME = rc.CONSTRAINT_NAME
            WHERE kcu.CONSTRAINT_SCHEMA = ?
              AND kcu.REFERENCED_TABLE_NAME IS NOT NULL
            ORDER BY kcu.TABLE_NAME, kcu.CONSTRAINT_NAME, kcu.ORDINAL_POSITION
            """;

        return executeWithRetry(() -> {
            Map<ForeignKeyIdentifier, ForeignKeyBuilder> fkMap = new LinkedHashMap<>();

            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                stmt.setString(1, schemaName);

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String tableName = rs.getString("TABLE_NAME");
                        String constraintName = rs.getString("CONSTRAINT_NAME");
                        String columnName = rs.getString("COLUMN_NAME");
                        String refTableName = rs.getString("REFERENCED_TABLE_NAME");
                        String refColumnName = rs.getString("REFERENCED_COLUMN_NAME");
                        String updateRule = rs.getString("UPDATE_RULE");
                        String deleteRule = rs.getString("DELETE_RULE");

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
                    // Validate referenced table exists
                    if (metadata.getTable(builder.refTableName) == null) {
                        String warning = String.format("FK %s.%s references non-existent table: %s",
                                builder.tableName, builder.constraintName, builder.refTableName);
                        log.warn(warning);
                        progressListener.onWarning(warning);
                    }

                    table.addConstraint(fk);
                    log.trace("Added FK to {}: {} -> {}",
                            builder.tableName, builder.constraintName, builder.refTableName);
                } else {
                    progressListener.onWarning("FK found for non-existent table: " + builder.tableName);
                }
            }

            return fkMap.size();
        });
    }

    private int extractCheckConstraints(Connection conn, DatabaseMetadata metadata, String schemaName)
            throws SQLException {
        // Check constraints only available in MySQL 8.0.16+
        // Need to join with TABLE_CONSTRAINTS to get the TABLE_NAME as CHECK_CONSTRAINTS doesn't have it in MySQL 8.0
        String query = """
            SELECT 
                tc.TABLE_NAME,
                cc.CONSTRAINT_NAME,
                cc.CHECK_CLAUSE
            FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS cc
            JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc
              ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA
             AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
            WHERE cc.CONSTRAINT_SCHEMA = ?
            ORDER BY tc.TABLE_NAME, cc.CONSTRAINT_NAME
            """;

        try {
            return executeWithRetry(() -> {
                try (PreparedStatement stmt = conn.prepareStatement(query)) {
                    stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                    stmt.setString(1, schemaName);

                    int count = 0;
                    try (ResultSet rs = stmt.executeQuery()) {
                        while (rs.next()) {
                            String tableName = rs.getString("TABLE_NAME");
                            String constraintName = rs.getString("CONSTRAINT_NAME");
                            String checkClause = rs.getString("CHECK_CLAUSE");

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
        } catch (SQLException e) {
            // Check constraints not supported in older MySQL versions
            if (e.getErrorCode() == 1146) { // Table doesn't exist
                log.debug("Check constraints not supported (MySQL < 8.0.16)");
                return 0;
            }
            throw e;
        }
    }

    private int extractUniqueConstraints(Connection conn, DatabaseMetadata metadata, String schemaName)
            throws SQLException {
        String query = """
            SELECT 
                tc.TABLE_NAME,
                tc.CONSTRAINT_NAME,
                kcu.COLUMN_NAME,
                kcu.ORDINAL_POSITION
            FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc
            JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE kcu
              ON tc.CONSTRAINT_SCHEMA = kcu.CONSTRAINT_SCHEMA
             AND tc.CONSTRAINT_NAME = kcu.CONSTRAINT_NAME
             AND tc.TABLE_NAME = kcu.TABLE_NAME
            WHERE tc.CONSTRAINT_SCHEMA = ?
              AND tc.CONSTRAINT_TYPE = 'UNIQUE'
            ORDER BY tc.TABLE_NAME, tc.CONSTRAINT_NAME, kcu.ORDINAL_POSITION
            """;

        return executeWithRetry(() -> {
            Map<ConstraintIdentifier, List<String>> uniqueMap = new LinkedHashMap<>();

            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                stmt.setString(1, schemaName);

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String tableName = rs.getString("TABLE_NAME");
                        String constraintName = rs.getString("CONSTRAINT_NAME");
                        String columnName = rs.getString("COLUMN_NAME");

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

    private void extractIndexes(Connection conn, DatabaseMetadata metadata, String schemaName)
            throws SQLException {
        String phase = "Indexes";
        progressListener.onPhaseStart(phase);
        long startTime = System.currentTimeMillis();

        log.debug("Extracting indexes");

        String query = """
            SELECT 
                TABLE_NAME, 
                INDEX_NAME, 
                COLUMN_NAME, 
                SEQ_IN_INDEX,
                NON_UNIQUE,
                INDEX_TYPE,
                INDEX_COMMENT
            FROM INFORMATION_SCHEMA.STATISTICS
            WHERE TABLE_SCHEMA = ? 
              AND INDEX_NAME != ?
            ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX
            """;

        int count = executeWithRetry(() -> {
            Map<IndexIdentifier, IndexBuilder> indexMap = new LinkedHashMap<>();

            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                stmt.setString(1, schemaName);
                stmt.setString(2, PRIMARY_KEY_NAME);

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String tableName = rs.getString("TABLE_NAME");
                        String indexName = rs.getString("INDEX_NAME");
                        String columnName = rs.getString("COLUMN_NAME");
                        boolean unique = rs.getInt("NON_UNIQUE") == 0;
                        String indexType = rs.getString("INDEX_TYPE");
                        String comment = rs.getString("INDEX_COMMENT");

                        IndexIdentifier id = new IndexIdentifier(tableName, indexName);
                        IndexBuilder builder = indexMap.computeIfAbsent(id, k ->
                                new IndexBuilder(tableName, indexName, unique, indexType, comment)
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
                    log.trace("Added index to {}: {} ({})",
                            builder.tableName, builder.indexName, index.getIndexType());
                } else {
                    progressListener.onWarning("Index found for non-existent table: " + builder.tableName);
                }
            }

            return indexMap.size();
        });

        long duration = System.currentTimeMillis() - startTime;
        log.debug("Extracted {} indexes in {}ms", count, duration);
        progressListener.onPhaseComplete(phase, count, duration);
    }

    /**
     * Validates extracted metadata for consistency
     */
    private void validateMetadata(DatabaseMetadata metadata) {
        log.debug("Validating extracted metadata");

        int issues = 0;

        // Check for tables without columns
        for (TableMetadata table : metadata.getTables().values()) {
            if (table.getColumns().isEmpty()) {
                log.warn("Table {} has no columns", table.getName());
                issues++;
            }

            // Check for foreign keys referencing non-existent tables
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

    /**
     * Execute a database operation with retry logic for transient failures
     */
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

                long backoffMs = 1000L * attempt; // Linear backoff: 1s, 2s, 3s
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
    }    private boolean isRetryable(SQLException e) {
        // Common transient errors: deadlock, lock wait timeout, connection issues
        int errorCode = e.getErrorCode();
        String sqlState = e.getSQLState();

        if (sqlState != null && sqlState.startsWith("40")) { // Transaction rollback
            return true;
        }

        // MySQL specific error codes
        return errorCode == 1213 || // Deadlock found when trying to get lock
               errorCode == 1205 || // Lock wait timeout exceeded
               errorCode == 2006 || // MySQL server has gone away
               errorCode == 2013;   // Lost connection to MySQL server during query
    }

    private int countIndexes(DatabaseMetadata metadata) {
        int count = 0;
        for (TableMetadata table : metadata.getTables().values()) {
            count += table.getIndexes().size();
        }
        return count;
    }

    private int countConstraints(DatabaseMetadata metadata) {
        int count = 0;
        for (TableMetadata table : metadata.getTables().values()) {
            count += table.getConstraints().size();
        }
        return count;
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
            // Map rules roughly - normally would use enums
            return fk;
        }
    }

    private record IndexIdentifier(String tableName, String indexName) {}

    private static class IndexBuilder {
        final String tableName;
        final String indexName;
        final boolean unique;
        final String indexType;
        final String comment;
        final List<String> columns = new ArrayList<>();

        IndexBuilder(String tableName, String indexName, boolean unique, String indexType, String comment) {
            this.tableName = tableName;
            this.indexName = indexName;
            this.unique = unique;
            this.indexType = indexType;
            this.comment = comment;
        }

        void addColumn(String column) {
            columns.add(column);
        }

        IndexMetadata build() {
            IndexMetadata index = new IndexMetadata(indexName, columns, unique);
            index.setIndexType(indexType);
            index.setComment(comment != null ? comment : "");
            return index;
        }
    }

    private record ConstraintIdentifier(String tableName, String constraintName) {}
}
