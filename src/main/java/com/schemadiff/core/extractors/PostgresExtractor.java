package com.schemadiff.core.extractors;

import com.schemadiff.core.MetadataExtractor;
import com.schemadiff.core.SignatureGenerator;
import com.schemadiff.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PostgreSQL-specific metadata extractor with comprehensive support for:
 * - SERIAL/BIGSERIAL auto-increment columns
 * - CHECK constraints
 * - Foreign key ON DELETE/UPDATE rules
 * - BYTEA, TEXT, and other PostgreSQL types
 * - Sequences and default value extraction
 * - Index types (BTREE, HASH, GIN, GIST, etc.)
 */
public class PostgresExtractor extends MetadataExtractor {

    private static final Logger log = LoggerFactory.getLogger(PostgresExtractor.class);

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

    public PostgresExtractor() {
        this("public", true, null);
    }

    public PostgresExtractor(String targetSchema) {
        this(targetSchema, true, null);
    }

    public PostgresExtractor(String targetSchema, boolean enableRetry, ExtractionProgress progressListener) {
        this.targetSchema = targetSchema != null ? targetSchema : "public";
        this.enableRetry = enableRetry;
        this.progressListener = progressListener != null ? progressListener : new NoOpProgress();
    }

    @Override
    public DatabaseMetadata extract(Connection conn) throws SQLException {
        log.info("Starting PostgreSQL schema extraction");
        long startTime = System.currentTimeMillis();

        validateConnection(conn);
        DatabaseMetadata metadata = new DatabaseMetadata();

        boolean originalAutoCommit = conn.getAutoCommit();
        boolean originalReadOnly = conn.isReadOnly();
        Integer originalTransactionIsolation = null;

        try {
            originalTransactionIsolation = conn.getTransactionIsolation();
            conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            conn.setAutoCommit(false);
            conn.setReadOnly(true);

            metadata.setSchemaName(targetSchema);
            log.info("Extracting schema: {}", targetSchema);

            extractTables(conn, metadata);
            extractColumns(conn, metadata);
            extractConstraints(conn, metadata);
            extractIndexes(conn, metadata);
            extractSequences(conn, metadata);
            extractFunctions(conn, metadata);
            extractTriggers(conn, metadata);

            validateMetadata(metadata);
            conn.commit();

            long duration = System.currentTimeMillis() - startTime;
            log.info("Schema extraction completed successfully in {}ms - Tables: {}, Indexes: {}, Constraints: {}, Sequences: {}, Functions: {}, Triggers: {}",
                    duration, metadata.getTables().size(), countIndexes(metadata), countConstraints(metadata),
                    metadata.getSequences().size(), metadata.getFunctions().size(), metadata.getTriggers().size());

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
        log.info("PostgreSQL version: {}.{}", majorVersion, minorVersion);

        if (majorVersion < 9 || (majorVersion == 9 && minorVersion < 6)) {
            log.warn("PostgreSQL version {}.{} may not support all features. Recommended: 9.6+",
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
                t.table_name,
                obj_description(pgc.oid) AS table_comment
            FROM information_schema.tables t
            LEFT JOIN pg_class pgc ON pgc.relname = t.table_name
            LEFT JOIN pg_namespace pgn ON pgn.oid = pgc.relnamespace AND pgn.nspname = t.table_schema
            WHERE t.table_schema = ?
            AND t.table_type = 'BASE TABLE'
            ORDER BY t.table_name
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

        // UPDATED QUERY: Added 'c.is_identity' to the SELECT list
        String query = """
        SELECT
            c.table_name,
            c.column_name,
            c.ordinal_position,
            c.data_type,
            c.character_maximum_length,
            c.numeric_precision,
            c.numeric_scale,
            c.is_nullable,
            c.column_default,
            c.is_identity,
            c.udt_name,
            pgd.description AS column_comment
        FROM information_schema.columns c
        LEFT JOIN pg_catalog.pg_statio_all_tables st ON c.table_schema = st.schemaname AND c.table_name = st.relname
        LEFT JOIN pg_catalog.pg_description pgd ON pgd.objoid = st.relid AND pgd.objsubid = c.ordinal_position
        WHERE c.table_schema = ?
        ORDER BY c.table_name, c.ordinal_position
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
                        boolean notNull = "NO".equals(rs.getString("is_nullable"));
                        String defaultValue = normalizeDefault(rs.getString("column_default"));

                        ColumnMetadata column = new ColumnMetadata(columnName, dataType, notNull, defaultValue);
                        column.setOrdinalPosition(rs.getInt("ordinal_position"));

                        String comment = rs.getString("column_comment");
                        column.setComment(comment != null ? comment : "");

                        // UPDATED LOGIC: Detect auto-increment (Identity OR Serial)
                        String colDefault = rs.getString("column_default");
                        String isIdentity = rs.getString("is_identity");

                        boolean isAutoInc = false;

                        // Check 1: Modern Postgres Identity Column (GENERATED AS IDENTITY)
                        if ("YES".equalsIgnoreCase(isIdentity)) {
                            isAutoInc = true;
                        }
                        // Check 2: Legacy Postgres Serial (DEFAULT nextval(...))
                        else if (colDefault != null && colDefault.contains("nextval")) {
                            isAutoInc = true;
                        }

                        column.setAutoIncrement(isAutoInc);

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
        String udtName = rs.getString("udt_name");

        // Handle character types
        if (baseType.equals("character varying") || baseType.equals("varchar")) {
            Integer length = getNullableInt(rs, "character_maximum_length");
            if (length != null && length > 0) {
                return "varchar(" + length + ")";
            }
            return "varchar";
        }

        if (baseType.equals("character")) {
            Integer length = getNullableInt(rs, "character_maximum_length");
            if (length != null && length > 0) {
                return "char(" + length + ")";
            }
            return "char";
        }

        // Handle numeric types
        if (baseType.equals("numeric") || baseType.equals("decimal")) {
            Integer precision = getNullableInt(rs, "numeric_precision");
            Integer scale = getNullableInt(rs, "numeric_scale");
            if (precision != null && precision > 0) {
                if (scale != null && scale > 0) {
                    return baseType + "(" + precision + "," + scale + ")";
                }
                return baseType + "(" + precision + ")";
            }
        }

        // Normalize PostgreSQL-specific types
        return switch (baseType) {
            case "character varying" -> "varchar";
            case "integer" -> "int";
            case "bigint" -> "bigint";
            case "smallint" -> "smallint";
            case "timestamp without time zone" -> "timestamp";
            case "timestamp with time zone" -> "timestamptz";
            case "time without time zone" -> "time";
            case "time with time zone" -> "timetz";
            case "double precision" -> "double precision";
            case "real" -> "real";
            case "boolean" -> "boolean";
            case "bytea" -> "bytea";
            case "text" -> "text";
            case "json" -> "json";
            case "jsonb" -> "jsonb";
            case "uuid" -> "uuid";
            case "date" -> "date";
            case "ARRAY" -> udtName != null ? udtName : "array";
            case "USER-DEFINED" -> udtName != null ? udtName : "user-defined";
            default -> baseType;
        };
    }

    private String normalizeDefault(String defaultValue) {
        if (defaultValue == null || defaultValue.trim().isEmpty()) {
            return null;
        }

        // Remove type casts like ::integer, ::character varying
        defaultValue = defaultValue.replaceAll("::[a-zA-Z_ ]+", "");

        // Handle nextval for SERIAL columns - keep simplified version
        if (defaultValue.contains("nextval")) {
            Pattern pattern = Pattern.compile("nextval\\('([^']+)'");
            Matcher matcher = pattern.matcher(defaultValue);
            if (matcher.find()) {
                return "nextval('" + matcher.group(1) + "')";
            }
        }

        // Trim quotes and whitespace
        defaultValue = defaultValue.trim();
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
                tc.table_name,
                kcu.column_name,
                kcu.ordinal_position
            FROM information_schema.table_constraints tc
            JOIN information_schema.key_column_usage kcu
                ON tc.constraint_name = kcu.constraint_name
                AND tc.table_schema = kcu.table_schema
            WHERE tc.constraint_type = 'PRIMARY KEY'
            AND tc.table_schema = ?
            ORDER BY tc.table_name, kcu.ordinal_position
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
                tc.table_name,
                tc.constraint_name,
                kcu.column_name,
                kcu.ordinal_position,
                kcu_target.table_name AS foreign_table_name,
                kcu_target.column_name AS foreign_column_name,
                rc.update_rule,
                rc.delete_rule
            FROM information_schema.table_constraints tc
                     JOIN information_schema.referential_constraints rc
                          ON tc.constraint_name = rc.constraint_name
                              AND tc.constraint_schema = rc.constraint_schema
                     JOIN information_schema.key_column_usage kcu
                          ON kcu.constraint_name = tc.constraint_name
                              AND kcu.constraint_schema = tc.constraint_schema
                     JOIN information_schema.key_column_usage kcu_target
                          ON kcu_target.constraint_name = rc.unique_constraint_name
                              AND kcu_target.constraint_schema = rc.unique_constraint_schema
                              AND kcu.position_in_unique_constraint = kcu_target.ordinal_position
            WHERE tc.constraint_type = 'FOREIGN KEY'
              AND tc.table_schema = ?
            ORDER BY tc.table_name, tc.constraint_name, kcu.ordinal_position
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
                        String refTableName = rs.getString("foreign_table_name");
                        String refColumnName = rs.getString("foreign_column_name");
                        String updateRule = rs.getString("update_rule");
                        String deleteRule = rs.getString("delete_rule");

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

    private int extractCheckConstraints(Connection conn, DatabaseMetadata metadata) throws SQLException {
        String query = """
            SELECT
                tc.table_name,
                tc.constraint_name,
                cc.check_clause
            FROM information_schema.table_constraints tc
            JOIN information_schema.check_constraints cc
                ON tc.constraint_name = cc.constraint_name
                AND tc.constraint_schema = cc.constraint_schema
            WHERE tc.constraint_type = 'CHECK'
            AND tc.table_schema = ?
            ORDER BY tc.table_name, tc.constraint_name
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
                tc.table_name,
                tc.constraint_name,
                kcu.column_name,
                kcu.ordinal_position
            FROM information_schema.table_constraints tc
            JOIN information_schema.key_column_usage kcu
                ON tc.constraint_name = kcu.constraint_name
                AND tc.table_schema = kcu.table_schema
            WHERE tc.constraint_type = 'UNIQUE'
            AND tc.table_schema = ?
            ORDER BY tc.table_name, tc.constraint_name, kcu.ordinal_position
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
                t.relname AS table_name,
                i.relname AS index_name,
                a.attname AS column_name,
                ix.indisunique AS is_unique,
                am.amname AS index_type,
                array_position(ix.indkey, a.attnum) AS column_position,
                pg_get_indexdef(ix.indexrelid) AS index_def
            FROM pg_class t
            JOIN pg_index ix ON t.oid = ix.indrelid
            JOIN pg_class i ON i.oid = ix.indexrelid
            JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY(ix.indkey)
            JOIN pg_am am ON i.relam = am.oid
            JOIN pg_namespace n ON t.relnamespace = n.oid
            WHERE t.relkind = 'r'
            AND n.nspname = ?
            AND NOT ix.indisprimary
            ORDER BY t.relname, i.relname, column_position
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

    private void extractSequences(Connection conn, DatabaseMetadata metadata) throws SQLException {
        String phase = "Sequences";
        progressListener.onPhaseStart(phase);
        long startTime = System.currentTimeMillis();

        log.debug("Extracting sequences");

        String query = """
            SELECT
                s.sequence_name,
                s.data_type,
                s.start_value,
                s.increment,
                s.minimum_value,
                s.maximum_value,
                s.cycle_option,
                pg_get_serial_sequence(c.table_name, c.column_name) AS owned_by_seq,
                c.table_name || '.' || c.column_name AS owned_by
            FROM information_schema.sequences s
            LEFT JOIN information_schema.columns c
                ON s.sequence_schema = c.table_schema
                AND pg_get_serial_sequence(c.table_name, c.column_name) = s.sequence_schema || '.' || s.sequence_name
            WHERE s.sequence_schema = ?
            ORDER BY s.sequence_name
            """;

        int count = executeWithRetry(() -> {
            int seqCount = 0;

            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                stmt.setString(1, targetSchema);

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        SequenceMetadata sequence = new SequenceMetadata();
                        sequence.setName(rs.getString("sequence_name"));
                        sequence.setDataType(rs.getString("data_type"));
                        sequence.setStartValue(getLong(rs, "start_value"));
                        sequence.setIncrementBy(getLong(rs, "increment"));
                        sequence.setMinValue(getLong(rs, "minimum_value"));
                        sequence.setMaxValue(getLong(rs, "maximum_value"));
                        sequence.setCycle("YES".equalsIgnoreCase(rs.getString("cycle_option")));
                        sequence.setOwnedBy(rs.getString("owned_by"));

                        metadata.addSequence(sequence);
                        seqCount++;

                        log.trace("Extracted sequence: {}", sequence.getName());
                    }
                }
            }

            return seqCount;
        });

        long duration = System.currentTimeMillis() - startTime;
        log.debug("Extracted {} sequences in {}ms", count, duration);
        progressListener.onPhaseComplete(phase, count, duration);
    }

    private void extractFunctions(Connection conn, DatabaseMetadata metadata) throws SQLException {
        String phase = "Functions";
        progressListener.onPhaseStart(phase);
        long startTime = System.currentTimeMillis();

        log.debug("Extracting functions");

        String query = """
            SELECT
                p.proname AS function_name,
                n.nspname AS schema_name,
                pg_get_function_result(p.oid) AS return_type,
                l.lanname AS language,
                pg_get_functiondef(p.oid) AS function_definition,
                pg_get_function_arguments(p.oid) AS arguments,
                CASE p.provolatile
                    WHEN 'i' THEN 'IMMUTABLE'
                    WHEN 's' THEN 'STABLE'
                    WHEN 'v' THEN 'VOLATILE'
                END AS volatility,
                p.proisstrict AS is_strict,
                CASE p.prosecdef
                    WHEN true THEN 'DEFINER'
                    ELSE 'INVOKER'
                END AS security_type
            FROM pg_proc p
            JOIN pg_namespace n ON p.pronamespace = n.oid
            JOIN pg_language l ON p.prolang = l.oid
            WHERE n.nspname = ?
            AND p.prokind IN ('f', 'p')  -- functions and procedures
            ORDER BY p.proname, p.oid
            """;

        int count = executeWithRetry(() -> {
            int funcCount = 0;

            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                stmt.setString(1, targetSchema);

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        FunctionMetadata function = new FunctionMetadata();
                        function.setName(rs.getString("function_name"));
                        function.setSchema(rs.getString("schema_name"));
                        function.setReturnType(rs.getString("return_type"));
                        function.setLanguage(rs.getString("language"));
                        function.setDefinition(rs.getString("function_definition"));
                        function.setArguments(rs.getString("arguments"));
                        function.setVolatility(rs.getString("volatility"));
                        function.setIsStrict(rs.getBoolean("is_strict"));
                        function.setSecurityType(rs.getString("security_type"));

                        metadata.addFunction(function);
                        funcCount++;

                        log.trace("Extracted function: {}", function.getSignature());
                    }
                }
            }

            return funcCount;
        });

        long duration = System.currentTimeMillis() - startTime;
        log.debug("Extracted {} functions in {}ms", count, duration);
        progressListener.onPhaseComplete(phase, count, duration);
    }

    private void extractTriggers(Connection conn, DatabaseMetadata metadata) throws SQLException {
        String phase = "Triggers";
        progressListener.onPhaseStart(phase);
        long startTime = System.currentTimeMillis();

        log.debug("Extracting triggers");

        String query = """
            SELECT
                t.tgname AS trigger_name,
                c.relname AS table_name,
                CASE t.tgtype & 2
                    WHEN 2 THEN 'BEFORE'
                    ELSE 'AFTER'
                END AS timing,
                CASE
                    WHEN t.tgtype & 4 = 4 THEN 'INSERT'
                    WHEN t.tgtype & 8 = 8 THEN 'DELETE'
                    WHEN t.tgtype & 16 = 16 THEN 'UPDATE'
                    ELSE 'UNKNOWN'
                END AS event,
                CASE t.tgtype & 1
                    WHEN 1 THEN 'ROW'
                    ELSE 'STATEMENT'
                END AS level,
                p.proname AS function_name,
                pg_get_triggerdef(t.oid) AS trigger_def
            FROM pg_trigger t
            JOIN pg_class c ON t.tgrelid = c.oid
            JOIN pg_namespace n ON c.relnamespace = n.oid
            JOIN pg_proc p ON t.tgfoid = p.oid
            WHERE n.nspname = ?
            AND NOT t.tgisinternal
            ORDER BY c.relname, t.tgname
            """;

        int count = executeWithRetry(() -> {
            int triggerCount = 0;

            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                stmt.setString(1, targetSchema);

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        TriggerMetadata trigger = new TriggerMetadata();
                        trigger.setName(rs.getString("trigger_name"));
                        trigger.setTableName(rs.getString("table_name"));
                        trigger.setTiming(rs.getString("timing"));
                        trigger.setEvent(rs.getString("event"));
                        trigger.setLevel(rs.getString("level"));
                        trigger.setFunctionName(rs.getString("function_name"));

                        metadata.addTrigger(trigger);
                        triggerCount++;

                        log.trace("Extracted trigger: {} on table {}", trigger.getName(), trigger.getTableName());
                    }
                }
            }

            return triggerCount;
        });

        long duration = System.currentTimeMillis() - startTime;
        log.debug("Extracted {} triggers in {}ms", count, duration);
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
            return true; // Serialization failure
        }

        // PostgreSQL-specific error codes
        String message = e.getMessage();
        return message != null && (
                message.contains("deadlock detected") ||
                message.contains("could not serialize") ||
                message.contains("connection") ||
                message.contains("broken pipe")
        );
    }

    private Integer getNullableInt(ResultSet rs, String columnName) {
        try {
            int value = rs.getInt(columnName);
            return rs.wasNull() ? null : value;
        } catch (SQLException e) {
            return null;
        }
    }

    private Long getLong(ResultSet rs, String columnName) {
        try {
            long value = rs.getLong(columnName);
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
            index.setIndexType(indexType != null ? indexType.toUpperCase() : "BTREE");
            return index;
        }
    }

    private record ConstraintIdentifier(String tableName, String constraintName) {}
}