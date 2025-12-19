package com.schemadiff.core.extractors;

import com.schemadiff.core.MetadataExtractor;
import com.schemadiff.core.SignatureGenerator;
import com.schemadiff.model.*;

import java.sql.*;
import java.util.*;

public class MySQLExtractor extends MetadataExtractor {

    @Override
    public DatabaseMetadata extract(Connection conn) throws Exception {
        DatabaseMetadata metadata = new DatabaseMetadata();

        // Extract tables
        extractTables(conn, metadata);

        // Extract columns
        extractColumns(conn, metadata);

        // Extract constraints
        extractConstraints(conn, metadata);

        // Extract indexes
        extractIndexes(conn, metadata);

        return metadata;
    }

    private void extractTables(Connection conn, DatabaseMetadata metadata) throws SQLException {
        String query = "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'";
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(query)) {
            while (rs.next()) {
                String tableName = rs.getString("TABLE_NAME");
                metadata.addTable(new TableMetadata(tableName));
            }
        }
    }

    private void extractColumns(Connection conn, DatabaseMetadata metadata) throws SQLException {
        String query = """
            SELECT TABLE_NAME, COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH,
                   NUMERIC_PRECISION, NUMERIC_SCALE, IS_NULLABLE, COLUMN_DEFAULT
            FROM INFORMATION_SCHEMA. COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
            ORDER BY TABLE_NAME, ORDINAL_POSITION
            """;

        try (Statement stmt = conn. createStatement(); ResultSet rs = stmt.executeQuery(query)) {
            while (rs.next()) {
                String tableName = rs. getString("TABLE_NAME");
                TableMetadata table = metadata.getTable(tableName);
                if (table == null) continue;

                ColumnMetadata column = new ColumnMetadata(
                    rs. getString("COLUMN_NAME"),
                    buildDataType(rs),
                    "NO". equals(rs.getString("IS_NULLABLE")),
                    rs.getString("COLUMN_DEFAULT")
                );
                table.addColumn(column);
            }
        }
    }

    private String buildDataType(ResultSet rs) throws SQLException {
        String baseType = rs.getString("DATA_TYPE").toLowerCase();
        Integer length = rs.getInt("CHARACTER_MAXIMUM_LENGTH");
        if (rs.wasNull()) length = null;

        Integer precision = rs.getInt("NUMERIC_PRECISION");
        if (rs.wasNull()) precision = null;

        Integer scale = rs.getInt("NUMERIC_SCALE");
        if (rs.wasNull()) scale = null;

        if (length != null) return baseType + "(" + length + ")";
        if (precision != null && scale != null) return baseType + "(" + precision + "," + scale + ")";
        if (precision != null) return baseType + "(" + precision + ")";
        return baseType;
    }

    private void extractConstraints(Connection conn, DatabaseMetadata metadata) throws SQLException {
        // Primary Keys
        String pkQuery = """
            SELECT TABLE_NAME, COLUMN_NAME
            FROM INFORMATION_SCHEMA. KEY_COLUMN_USAGE
            WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'PRIMARY'
            ORDER BY TABLE_NAME, ORDINAL_POSITION
            """;

        Map<String, List<String>> pkMap = new HashMap<>();
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(pkQuery)) {
            while (rs.next()) {
                String table = rs.getString("TABLE_NAME");
                String column = rs. getString("COLUMN_NAME");
                pkMap.computeIfAbsent(table, k -> new ArrayList<>()).add(column);
            }
        }

        for (Map.Entry<String, List<String>> entry : pkMap. entrySet()) {
            TableMetadata table = metadata.getTable(entry.getKey());
            if (table != null) {
                ConstraintMetadata pk = new ConstraintMetadata("PRIMARY_KEY", "PRIMARY", entry.getValue(), null);
                pk.setSignature(SignatureGenerator.generate(pk));
                table.addConstraint(pk);
            }
        }

        // Foreign Keys
        String fkQuery = """
            SELECT TABLE_NAME, COLUMN_NAME, REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME
            FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE
            WHERE CONSTRAINT_SCHEMA = DATABASE() AND REFERENCED_TABLE_NAME IS NOT NULL
            ORDER BY TABLE_NAME, ORDINAL_POSITION
            """;

        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(fkQuery)) {
            Map<String, ConstraintMetadata> fkMap = new HashMap<>();
            while (rs.next()) {
                String table = rs.getString("TABLE_NAME");
                String column = rs.getString("COLUMN_NAME");
                String refTable = rs. getString("REFERENCED_TABLE_NAME");

                String key = table + "|" + refTable;
                ConstraintMetadata fk = fkMap.computeIfAbsent(key, k ->
                    new ConstraintMetadata(null, "FOREIGN_KEY", new ArrayList<>(), refTable)
                );
                fk.getColumns().add(column);
            }

            for (ConstraintMetadata fk : fkMap.values()) {
                fk.setSignature(SignatureGenerator.generate(fk));
                TableMetadata table = metadata.getTable(fk.getColumns().get(0)); // Simplified
                if (table != null) table.addConstraint(fk);
            }
        }
    }

    private void extractIndexes(Connection conn, DatabaseMetadata metadata) throws SQLException {
        String query = """
            SELECT TABLE_NAME, INDEX_NAME, COLUMN_NAME, NON_UNIQUE
            FROM INFORMATION_SCHEMA. STATISTICS
            WHERE TABLE_SCHEMA = DATABASE() AND INDEX_NAME != 'PRIMARY'
            ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX
            """;

        Map<String, IndexMetadata> indexMap = new HashMap<>();
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(query)) {
            while (rs.next()) {
                String table = rs.getString("TABLE_NAME");
                String indexName = rs.getString("INDEX_NAME");
                String column = rs.getString("COLUMN_NAME");
                boolean unique = rs.getInt("NON_UNIQUE") == 0;

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
            if (tableMeta != null) tableMeta.addIndex(entry. getValue());
        }
    }
}