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
            SELECT TABLE_NAME, COLUMN_NAME, DATA_TYPE, COLUMN_TYPE, CHARACTER_MAXIMUM_LENGTH,
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
        String columnType = rs.getString("COLUMN_TYPE").toLowerCase();

        // Handle lengths - use getLong for safety with UNSIGNED values
        Long lengthLong = null;
        try {
            lengthLong = rs.getLong("CHARACTER_MAXIMUM_LENGTH");
            if (rs.wasNull()) lengthLong = null;
        } catch (SQLException e) {
            // Ignore if column doesn't support this
        }

        Long precisionLong = null;
        try {
            precisionLong = rs.getLong("NUMERIC_PRECISION");
            if (rs.wasNull()) precisionLong = null;
        } catch (SQLException e) {
            // Ignore if column doesn't support this
        }

        Long scaleLong = null;
        try {
            scaleLong = rs.getLong("NUMERIC_SCALE");
            if (rs.wasNull()) scaleLong = null;
        } catch (SQLException e) {
            // Ignore if column doesn't support this
        }

        String typeDefinition = baseType;

        // Build type string - cap extremely large values for readability
        if (lengthLong != null) {
            long length = Math.min(lengthLong, 999999);
            typeDefinition = baseType + "(" + length + ")";
        } else if (precisionLong != null && scaleLong != null) {
            if (scaleLong == 0) {
                typeDefinition = baseType + "(" + precisionLong + ")";
            } else {
                typeDefinition = baseType + "(" + precisionLong + "," + scaleLong + ")";
            }
        } else if (precisionLong != null) {
            typeDefinition = baseType + "(" + precisionLong + ")";
        }

        if (columnType.contains("unsigned")) {
            typeDefinition += " unsigned";
        }

        return typeDefinition;
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
            SELECT CONSTRAINT_NAME, TABLE_NAME, COLUMN_NAME, REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME
            FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE
            WHERE CONSTRAINT_SCHEMA = DATABASE() AND REFERENCED_TABLE_NAME IS NOT NULL
            ORDER BY TABLE_NAME, CONSTRAINT_NAME, ORDINAL_POSITION
            """;

        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(fkQuery)) {
            Map<String, ConstraintMetadata> fkMap = new HashMap<>();
            Map<String, String> fkTableMap = new HashMap<>(); // Track which table each FK belongs to

            while (rs.next()) {
                String constraintName = rs.getString("CONSTRAINT_NAME");
                String table = rs.getString("TABLE_NAME");
                String column = rs.getString("COLUMN_NAME");
                String refTable = rs.getString("REFERENCED_TABLE_NAME");

                String key = table + "|" + constraintName;
                ConstraintMetadata fk = fkMap.computeIfAbsent(key, k ->
                    new ConstraintMetadata(constraintName, "FOREIGN_KEY", new ArrayList<>(), refTable)
                );
                fk.getColumns().add(column);
                fkTableMap.put(key, table); // Store the table name for this FK
            }

            for (Map.Entry<String, ConstraintMetadata> entry : fkMap.entrySet()) {
                ConstraintMetadata fk = entry.getValue();
                fk.setSignature(SignatureGenerator.generate(fk));

                String tableName = fkTableMap.get(entry.getKey());
                TableMetadata table = metadata.getTable(tableName);
                if (table != null) {
                    table.addConstraint(fk);
                }
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