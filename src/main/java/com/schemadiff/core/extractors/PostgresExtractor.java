package com.schemadiff.core.extractors;

import com.schemadiff.core.MetadataExtractor;
import com. schemadiff.core.SignatureGenerator;
import com.schemadiff.model.*;

import java.sql.*;
import java. util.*;

public class PostgresExtractor extends MetadataExtractor {

    @Override
    public DatabaseMetadata extract(Connection conn) throws Exception {
        DatabaseMetadata metadata = new DatabaseMetadata();

        extractTables(conn, metadata);
        extractColumns(conn, metadata);
        extractConstraints(conn, metadata);
        extractIndexes(conn, metadata);

        return metadata;
    }

    private void extractTables(Connection conn, DatabaseMetadata metadata) throws SQLException {
        String query = """
            SELECT table_name
            FROM information_schema.tables
            WHERE table_schema = 'public'
            AND table_type = 'BASE TABLE'
            ORDER BY table_name
            """;

        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(query)) {
            while (rs.next()) {
                metadata.addTable(new TableMetadata(rs.getString("table_name")));
            }
        }
    }

    private void extractColumns(Connection conn, DatabaseMetadata metadata) throws SQLException {
        String query = """
            SELECT
                table_name,
                column_name,
                data_type,
                character_maximum_length,
                numeric_precision,
                numeric_scale,
                is_nullable,
                column_default
            FROM information_schema.columns
            WHERE table_schema = 'public'
            ORDER BY table_name, ordinal_position
            """;

        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(query)) {
            while (rs.next()) {
                String tableName = rs.getString("table_name");
                TableMetadata table = metadata.getTable(tableName);
                if (table == null) continue;

                ColumnMetadata column = new ColumnMetadata(
                    rs. getString("column_name"),
                    buildDataType(rs),
                    "NO". equals(rs.getString("is_nullable")),
                    rs. getString("column_default")
                );
                table.addColumn(column);
            }
        }
    }

    private String buildDataType(ResultSet rs) throws SQLException {
        String baseType = rs.getString("data_type").toLowerCase();

        // Handle character types
        if (baseType.contains("character") || baseType.equals("varchar")) {
            Integer length = rs.getInt("character_maximum_length");
            if (! rs.wasNull()) {
                return "varchar(" + length + ")";
            }
        }

        // Handle numeric types
        if (baseType.equals("numeric") || baseType.equals("decimal")) {
            Integer precision = rs.getInt("numeric_precision");
            Integer scale = rs.getInt("numeric_scale");
            if (!rs.wasNull() && precision != null) {
                if (scale != null && scale > 0) {
                    return baseType + "(" + precision + "," + scale + ")";
                }
                return baseType + "(" + precision + ")";
            }
        }

        // Normalize common types
        return switch (baseType) {
            case "character varying" -> "varchar";
            case "integer" -> "int";
            case "bigint" -> "bigint";
            case "timestamp without time zone" -> "timestamp";
            case "timestamp with time zone" -> "timestamptz";
            default -> baseType;
        };
    }

    private void extractConstraints(Connection conn, DatabaseMetadata metadata) throws SQLException {
        // Primary Keys
        String pkQuery = """
            SELECT
                tc.table_name,
                kcu.column_name,
                tc.constraint_name
            FROM information_schema.table_constraints tc
            JOIN information_schema.key_column_usage kcu
                ON tc.constraint_name = kcu.constraint_name
            WHERE tc.constraint_type = 'PRIMARY KEY'
            AND tc.table_schema = 'public'
            ORDER BY tc.table_name, kcu.ordinal_position
            """;

        Map<String, ConstraintMetadata> pkMap = new HashMap<>();
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(pkQuery)) {
            while (rs.next()) {
                String table = rs.getString("table_name");
                String column = rs. getString("column_name");

                ConstraintMetadata pk = pkMap.computeIfAbsent(table, k ->
                    new ConstraintMetadata("PRIMARY_KEY", "PRIMARY_KEY", new ArrayList<>(), null)
                );
                pk. getColumns().add(column);
            }
        }

        for (Map.Entry<String, ConstraintMetadata> entry : pkMap. entrySet()) {
            ConstraintMetadata pk = entry. getValue();
            pk.setSignature(SignatureGenerator.generate(pk));
            TableMetadata table = metadata.getTable(entry.getKey());
            if (table != null) table.addConstraint(pk);
        }

        // Foreign Keys
        String fkQuery = """
            SELECT
                tc.table_name,
                kcu. column_name,
                ccu.table_name AS foreign_table_name,
                ccu.column_name AS foreign_column_name,
                tc.constraint_name
            FROM information_schema.table_constraints tc
            JOIN information_schema.key_column_usage kcu
                ON tc.constraint_name = kcu.constraint_name
            JOIN information_schema.constraint_column_usage ccu
                ON ccu.constraint_name = tc.constraint_name
            WHERE tc.constraint_type = 'FOREIGN KEY'
            AND tc.table_schema = 'public'
            ORDER BY tc. table_name, kcu.ordinal_position
            """;

        Map<String, ConstraintMetadata> fkMap = new HashMap<>();
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(fkQuery)) {
            while (rs.next()) {
                String table = rs.getString("table_name");
                String column = rs.getString("column_name");
                String refTable = rs.getString("foreign_table_name");
                String constraintName = rs.getString("constraint_name");

                String key = table + "|" + constraintName;
                ConstraintMetadata fk = fkMap.computeIfAbsent(key, k ->
                    new ConstraintMetadata(constraintName, "FOREIGN_KEY", new ArrayList<>(), refTable)
                );
                fk.getColumns().add(column);
            }
        }

        for (ConstraintMetadata fk : fkMap.values()) {
            fk.setSignature(SignatureGenerator.generate(fk));

            // Find the table this FK belongs to
            for (String tableName : metadata.getTableNames()) {
                TableMetadata table = metadata.getTable(tableName);
                if (table != null && fk.getColumns().stream().anyMatch(col -> table.getColumn(col) != null)) {
                    table.addConstraint(fk);
                    break;
                }
            }
        }

        // Unique Constraints
        String uniqueQuery = """
            SELECT
                tc.table_name,
                kcu.column_name,
                tc.constraint_name
            FROM information_schema.table_constraints tc
            JOIN information_schema.key_column_usage kcu
                ON tc.constraint_name = kcu.constraint_name
            WHERE tc.constraint_type = 'UNIQUE'
            AND tc.table_schema = 'public'
            ORDER BY tc.table_name, kcu.ordinal_position
            """;

        Map<String, ConstraintMetadata> uniqueMap = new HashMap<>();
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(uniqueQuery)) {
            while (rs.next()) {
                String table = rs.getString("table_name");
                String column = rs. getString("column_name");
                String constraintName = rs.getString("constraint_name");

                String key = table + "|" + constraintName;
                ConstraintMetadata unique = uniqueMap.computeIfAbsent(key, k ->
                    new ConstraintMetadata(constraintName, "UNIQUE", new ArrayList<>(), null)
                );
                unique.getColumns().add(column);
            }
        }

        for (ConstraintMetadata unique : uniqueMap.values()) {
            unique. setSignature(SignatureGenerator. generate(unique));
            for (String tableName : metadata.getTableNames()) {
                TableMetadata table = metadata.getTable(tableName);
                if (table != null && unique.getColumns().stream().anyMatch(col -> table.getColumn(col) != null)) {
                    table.addConstraint(unique);
                    break;
                }
            }
        }
    }

    private void extractIndexes(Connection conn, DatabaseMetadata metadata) throws SQLException {
        String query = """
            SELECT
                t.relname AS table_name,
                i.relname AS index_name,
                a.attname AS column_name,
                ix.indisunique AS is_unique,
                array_position(ix.indkey, a.attnum) AS column_position
            FROM pg_class t
            JOIN pg_index ix ON t.oid = ix. indrelid
            JOIN pg_class i ON i.oid = ix.indexrelid
            JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY(ix.indkey)
            WHERE t.relkind = 'r'
            AND t.relnamespace = (SELECT oid FROM pg_namespace WHERE nspname = 'public')
            AND NOT ix.indisprimary
            ORDER BY t. relname, i.relname, column_position
            """;

        Map<String, IndexMetadata> indexMap = new HashMap<>();
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(query)) {
            while (rs.next()) {
                String table = rs.getString("table_name");
                String indexName = rs.getString("index_name");
                String column = rs.getString("column_name");
                boolean unique = rs.getBoolean("is_unique");

                String key = table + "|" + indexName;
                IndexMetadata index = indexMap.computeIfAbsent(key, k ->
                    new IndexMetadata(indexName, new ArrayList<>(), unique)
                );
                index. getColumns().add(column);
            }
        }

        for (Map.Entry<String, IndexMetadata> entry : indexMap.entrySet()) {
            String table = entry.getKey().split("\\|")[0];
            TableMetadata tableMeta = metadata.getTable(table);
            if (tableMeta != null) {
                tableMeta.addIndex(entry. getValue());
            }
        }
    }
}