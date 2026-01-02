package com.schemadiff.core.extractors;

import com.schemadiff.core.MetadataExtractor;
import com.schemadiff.core.SignatureGenerator;
import com.schemadiff.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.*;

/**
 * Oracle Database-specific metadata extractor with comprehensive support for:
 * - SEQUENCE-based auto-increment detection via triggers
 * - CHECK constraints
 * - Foreign key ON DELETE rules (CASCADE, SET NULL, NO ACTION)
 * - VARCHAR2, CLOB, BLOB, and other Oracle types
 * - Table and column comments (USER_TAB_COMMENTS, USER_COL_COMMENTS)
 * - Index types (NORMAL, BITMAP, FUNCTION-BASED)
 */
public class OracleExtractor extends MetadataExtractor {

    private static final Logger log = LoggerFactory.getLogger(OracleExtractor.class);

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

    public OracleExtractor() {
        this(null, true, null);
    }

    public OracleExtractor(String targetSchema) {
        this(targetSchema, true, null);
    }

    public OracleExtractor(String targetSchema, boolean enableRetry, ExtractionProgress progressListener) {
        this.targetSchema = targetSchema;
        this.enableRetry = enableRetry;
        this.progressListener = progressListener != null ? progressListener : new NoOpProgress();
    }

    @Override
    public DatabaseMetadata extract(Connection conn) throws SQLException {
        log.info("Starting Oracle schema extraction");
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

            String owner = getSchemaOwner(conn);
            metadata.setSchemaName(owner);
            log.info("Extracting schema: {}", owner);

            extractTables(conn, metadata, owner);
            extractColumns(conn, metadata, owner);
            extractConstraints(conn, metadata, owner);
            extractIndexes(conn, metadata, owner);

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
        log.info("Oracle version: {}.{}", majorVersion, minorVersion);

        if (majorVersion < 11) {
            log.warn("Oracle version {}.{} may not support all features. Recommended: 11g+",
                    majorVersion, minorVersion);
        }
    }

    private String getSchemaOwner(Connection conn) throws SQLException {
        if (targetSchema != null && !targetSchema.trim().isEmpty()) {
            return targetSchema.trim().toUpperCase();
        }

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT USER FROM DUAL")) {
            if (rs.next()) {
                String owner = rs.getString(1);
                if (owner == null || owner.trim().isEmpty()) {
                    throw new SQLException("Could not determine current schema owner");
                }
                return owner.trim().toUpperCase();
            }
            throw new SQLException("Failed to determine current schema owner");
        }
    }

    private void extractTables(Connection conn, DatabaseMetadata metadata, String owner) throws SQLException {
        String phase = "Tables";
        progressListener.onPhaseStart(phase);
        long startTime = System.currentTimeMillis();

        log.debug("Extracting tables");

        String query = """
            SELECT t.table_name, c.comments AS table_comment
            FROM all_tables t
            LEFT JOIN all_tab_comments c ON t.owner = c.owner AND t.table_name = c.table_name
            WHERE t.owner = ? AND t.nested = 'NO'
            ORDER BY t.table_name
            """;

        int count = executeWithRetry(() -> {
            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                stmt.setString(1, owner);

                int tableCount = 0;
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String tableName = rs.getString("table_name");
                        TableMetadata table = new TableMetadata(tableName);

                        String comment = rs.getString("table_comment");
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

    private void extractColumns(Connection conn, DatabaseMetadata metadata, String owner) throws SQLException {
        String phase = "Columns";
        progressListener.onPhaseStart(phase);
        long startTime = System.currentTimeMillis();

        log.debug("Extracting columns");

        String query = """
            SELECT c.table_name, c.column_name, c.column_id AS ordinal_position,
                   c.data_type, c.data_length, c.data_precision, c.data_scale,
                   c.nullable, c.data_default, c.char_length,
                   cm.comments AS column_comment
            FROM all_tab_columns c
            LEFT JOIN all_col_comments cm ON c.owner = cm.owner AND c.table_name = cm.table_name AND c.column_name = cm.column_name
            WHERE c.owner = ?
            ORDER BY c.table_name, c.column_id
            """;

        int count = executeWithRetry(() -> {
            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                stmt.setString(1, owner);

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
                        boolean notNull = "N".equals(rs.getString("nullable"));
                        String defaultValue = normalizeDefault(rs.getString("data_default"));

                        ColumnMetadata column = new ColumnMetadata(columnName, dataType, notNull, defaultValue);
                        column.setOrdinalPosition(rs.getInt("ordinal_position"));

                        String comment = rs.getString("column_comment");
                        column.setComment(comment != null ? comment : "");

                        table.addColumn(column);
                        columnCount++;
                    }
                }
                return columnCount;
            }
        });

        // Detect auto-increment via sequences and triggers
        detectAutoIncrementColumns(conn, metadata, owner);

        long duration = System.currentTimeMillis() - startTime;
        log.debug("Extracted {} columns in {}ms", count, duration);
        progressListener.onPhaseComplete(phase, count, duration);
    }

    private String buildDataType(ResultSet rs) throws SQLException {
        String baseType = rs.getString("data_type");
        if (baseType == null) return "unknown";

        baseType = baseType.toUpperCase().trim(); // Oracle types are typically uppercase

        // Handle NUMBER type with precision/scale
        if (baseType.equals("NUMBER")) {
            Integer precision = getNullableInt(rs, "data_precision");
            Integer scale = getNullableInt(rs, "data_scale");

            // If no precision specified, it's NUMBER (no params)
            if (precision == null) {
                return "NUMBER";
            }

            // If scale is specified
            if (scale != null && scale > 0) {
                return "NUMBER(" + precision + "," + scale + ")";
            }

            // If only precision is specified
            return "NUMBER(" + precision + ")";
        }

        // Handle VARCHAR2 and CHAR types with length
        if (baseType.equals("VARCHAR2") || baseType.equals("NVARCHAR2")) {
            Integer length = getNullableInt(rs, "char_length");
            if (length == null) {
                length = getNullableInt(rs, "data_length");
            }
            if (length != null && length > 0) {
                return baseType + "(" + length + ")";
            }
            return baseType;
        }

        if (baseType.equals("CHAR") || baseType.equals("NCHAR")) {
            Integer length = getNullableInt(rs, "char_length");
            if (length == null) {
                length = getNullableInt(rs, "data_length");
            }
            if (length != null && length > 0) {
                return baseType + "(" + length + ")";
            }
            return baseType;
        }

        // Return native Oracle type as-is (no normalization)
        return baseType;
    }

    private String normalizeDefault(String defaultValue) {
        if (defaultValue == null || defaultValue.trim().isEmpty()) {
            return null;
        }

        defaultValue = defaultValue.trim();

        if (defaultValue.startsWith("'") && defaultValue.endsWith("'") && defaultValue.length() > 1) {
            defaultValue = defaultValue.substring(1, defaultValue.length() - 1);
        }

        if (defaultValue.equalsIgnoreCase("SYSDATE")) {
            return "SYSDATE";
        }
        if (defaultValue.toUpperCase().startsWith("SYS_GUID()")) {
            return "SYS_GUID()";
        }

        return defaultValue;
    }

    private void detectAutoIncrementColumns(Connection conn, DatabaseMetadata metadata, String owner) {
        // 1. SELECT without filtering the LONG column in the WHERE clause
        String query = """
        SELECT t.table_name, t.trigger_body
        FROM all_triggers t
        WHERE t.owner = ?
        AND t.trigger_type = 'BEFORE EACH ROW'
        AND t.triggering_event LIKE '%INSERT%'
        """;

        // 2. Compile Regex to find ":NEW.COLUMN_NAME" assignment
        // Looks for: INTO :NEW.MY_COL_NAME
        // Case insensitive, handles whitespace
        Pattern columnPattern = Pattern.compile("INTO\\s+:NEW\\.([A-Za-z0-9_]+)", Pattern.CASE_INSENSITIVE);

        try (PreparedStatement stmt = conn.prepareStatement(query)) {
            stmt.setString(1, owner);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String tableName = rs.getString("table_name");

                    // 3. Fetch the LONG column into Java memory
                    String triggerBody = rs.getString("trigger_body");

                    if (triggerBody == null) continue;

                    // 4. Clean and Standardize
                    String upperBody = triggerBody.toUpperCase();

                    // 5. Validation: Must contain 'NEXTVAL' to be a sequence trigger
                    if (!upperBody.contains("NEXTVAL")) {
                        continue;
                    }

                    // 6. Extraction: Use Regex to find exactly WHICH column is getting the ID
                    Matcher matcher = columnPattern.matcher(upperBody);
                    if (matcher.find()) {
                        String targetColumnName = matcher.group(1); // The captured column name

                        // 7. Update Metadata
                        TableMetadata table = metadata.getTable(tableName);
                        if (table != null) {
                            ColumnMetadata column = table.getColumn(targetColumnName);
                            if (column != null) {
                                column.setAutoIncrement(true);
                                log.debug("Detected Oracle Auto-Increment: {}.{}", tableName, targetColumnName);
                            }
                        }
                    }
                }
            }
        } catch (SQLException e) {
            log.warn("Failed to detect auto-increment columns: {}", e.getMessage());
        }
    }

    private void extractConstraints(Connection conn, DatabaseMetadata metadata, String owner) throws SQLException {
        String phase = "Constraints";
        progressListener.onPhaseStart(phase);
        long startTime = System.currentTimeMillis();

        log.debug("Extracting constraints");

        int pkCount = extractPrimaryKeys(conn, metadata, owner);
        int fkCount = extractForeignKeys(conn, metadata, owner);
        int checkCount = extractCheckConstraints(conn, metadata, owner);
        int uniqueCount = extractUniqueConstraints(conn, metadata, owner);

        int total = pkCount + fkCount + checkCount + uniqueCount;
        long duration = System.currentTimeMillis() - startTime;

        log.debug("Extracted {} constraints (PK: {}, FK: {}, Check: {}, Unique: {}) in {}ms",
                total, pkCount, fkCount, checkCount, uniqueCount, duration);
        progressListener.onPhaseComplete(phase, total, duration);
    }

    private int extractPrimaryKeys(Connection conn, DatabaseMetadata metadata, String owner) throws SQLException {
        String query = """
            SELECT c.table_name, cc.column_name, cc.position
            FROM all_constraints c
            JOIN all_cons_columns cc ON c.constraint_name = cc.constraint_name AND c.owner = cc.owner
            WHERE c.constraint_type = 'P' AND c.owner = ?
            ORDER BY c.table_name, cc.position
            """;

        return executeWithRetry(() -> {
            Map<String, List<String>> pkMap = new LinkedHashMap<>();

            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                stmt.setString(1, owner);

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

    private int extractForeignKeys(Connection conn, DatabaseMetadata metadata, String owner) throws SQLException {
        String query = """
            SELECT c.table_name, cc.column_name, cc.position,
                   rc.table_name AS ref_table_name, rcc.column_name AS ref_column_name,
                   c.constraint_name, c.delete_rule
            FROM all_constraints c
            JOIN all_cons_columns cc ON c.constraint_name = cc.constraint_name AND c.owner = cc.owner
            JOIN all_constraints rc ON c.r_constraint_name = rc.constraint_name AND c.r_owner = rc.owner
            JOIN all_cons_columns rcc ON rc.constraint_name = rcc.constraint_name AND rc.owner = rcc.owner AND cc.position = rcc.position
            WHERE c.constraint_type = 'R' AND c.owner = ?
            ORDER BY c.table_name, c.constraint_name, cc.position
            """;

        return executeWithRetry(() -> {
            Map<ForeignKeyIdentifier, ForeignKeyBuilder> fkMap = new LinkedHashMap<>();

            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                stmt.setString(1, owner);

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String tableName = rs.getString("table_name");
                        String constraintName = rs.getString("constraint_name");
                        String columnName = rs.getString("column_name");
                        String refTableName = rs.getString("ref_table_name");
                        String refColumnName = rs.getString("ref_column_name");
                        String deleteRule = normalizeRule(rs.getString("delete_rule"));

                        ForeignKeyIdentifier id = new ForeignKeyIdentifier(tableName, constraintName);
                        ForeignKeyBuilder builder = fkMap.computeIfAbsent(id, k -> new ForeignKeyBuilder(
                                tableName, constraintName, refTableName, "NO ACTION", deleteRule
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
            case "CASCADE" -> "CASCADE";
            case "SET NULL" -> "SET NULL";
            case "NO ACTION" -> "NO ACTION";
            default -> rule;
        };
    }

    private int extractCheckConstraints(Connection conn, DatabaseMetadata metadata, String owner) throws SQLException {
        // 1. Remove the 'NOT LIKE' clause from SQL. It causes ORA-00932 on LONG columns.
        String query = """
        SELECT c.table_name, c.constraint_name, c.search_condition
        FROM all_constraints c
        WHERE c.constraint_type = 'C'
        AND c.owner = ?
        ORDER BY c.table_name, c.constraint_name
        """;

        return executeWithRetry(() -> {
            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                stmt.setString(1, owner);

                int count = 0;
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String tableName = rs.getString("table_name");
                        String constraintName = rs.getString("constraint_name");

                        // 2. Fetch the LONG column into Java memory
                        String searchCondition = rs.getString("search_condition");

                        // 3. Perform the filtering in Java instead of SQL
                        if (searchCondition == null || searchCondition.trim().isEmpty()) {
                            continue;
                        }

                        // Oracle stores "NOT NULL" constraints as CHECK constraints.
                        // We typically want to ignore these as they are handled by column nullability.
                        String upperCondition = searchCondition.trim().toUpperCase();
                        if (upperCondition.endsWith("IS NOT NULL")) {
                            continue;
                        }

                        TableMetadata table = metadata.getTable(tableName);
                        if (table != null) {
                            ConstraintMetadata check = new ConstraintMetadata(
                                    CONSTRAINT_TYPE_CHECK,
                                    constraintName,
                                    new ArrayList<>(),
                                    null
                            );
                            check.setCheckClause(searchCondition);
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

    private int extractUniqueConstraints(Connection conn, DatabaseMetadata metadata, String owner) throws SQLException {
        String query = """
            SELECT c.table_name, cc.column_name, c.constraint_name, cc.position
            FROM all_constraints c
            JOIN all_cons_columns cc ON c.constraint_name = cc.constraint_name AND c.owner = cc.owner
            WHERE c.constraint_type = 'U' AND c.owner = ?
            ORDER BY c.table_name, c.constraint_name, cc.position
            """;

        return executeWithRetry(() -> {
            Map<ConstraintIdentifier, List<String>> uniqueMap = new LinkedHashMap<>();

            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                stmt.setString(1, owner);

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

    private void extractIndexes(Connection conn, DatabaseMetadata metadata, String owner) throws SQLException {
        String phase = "Indexes";
        progressListener.onPhaseStart(phase);
        long startTime = System.currentTimeMillis();

        log.debug("Extracting indexes");

        String query = """
            SELECT i.table_name, i.index_name, ic.column_name,
                   i.uniqueness, i.index_type, ic.column_position
            FROM all_indexes i
            JOIN all_ind_columns ic ON i.index_name = ic.index_name AND i.table_owner = ic.table_owner
            WHERE i.table_owner = ?
            AND NOT EXISTS (
                SELECT 1 FROM all_constraints c
                WHERE c.constraint_type = 'P'
                AND c.index_name = i.index_name
                AND c.owner = i.table_owner
            )
            ORDER BY i.table_name, i.index_name, ic.column_position
            """;

        int count = executeWithRetry(() -> {
            Map<IndexIdentifier, IndexBuilder> indexMap = new LinkedHashMap<>();

            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                stmt.setString(1, owner);

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String tableName = rs.getString("table_name");
                        String indexName = rs.getString("index_name");
                        String columnName = rs.getString("column_name");
                        boolean unique = "UNIQUE".equals(rs.getString("uniqueness"));
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
        int errorCode = e.getErrorCode();

        // Oracle-specific error codes
        return errorCode == 60 ||     // Deadlock detected
               errorCode == 8177 ||   // Can't serialize access
               errorCode == 1013 ||   // User requested cancel
               errorCode == 1089;     // Immediate shutdown in progress
    }

    private Integer getNullableInt(ResultSet rs, String columnName) {
        try {
            int value = rs.getInt(columnName);
            return rs.wasNull() ? null : value;
        } catch (SQLException e) {
            return null;
        }
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
            String normalizedType = indexType != null ? indexType : "NORMAL";
            index.setIndexType(normalizedType);
            return index;
        }
    }

    private record ConstraintIdentifier(String tableName, String constraintName) {}
}

