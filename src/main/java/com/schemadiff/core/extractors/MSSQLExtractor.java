package com.schemadiff.core.extractors;

import com.schemadiff.core.MetadataExtractor;
import com.schemadiff.core.SignatureGenerator;
import com.schemadiff.model.*;

import java.sql.*;
import java.util.*;

public class MSSQLExtractor extends MetadataExtractor {

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
            SELECT name
            FROM sys.tables
            WHERE type = 'U'
            ORDER BY name
            """;

        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(query)) {
            while (rs. next()) {
                metadata.addTable(new TableMetadata(rs.getString("name")));
            }
        }
    }

    private void extractColumns(Connection conn, DatabaseMetadata metadata) throws SQLException {
        String query = """
            SELECT
                t.name AS table_name,
                c.name AS column_name,
                ty.name AS data_type,
                c.max_length,
                c.precision,
                c.scale,
                c.is_nullable,
                dc.definition AS default_value
            FROM sys.tables t
            JOIN sys.columns c ON t.object_id = c.object_id
            JOIN sys.types ty ON c.user_type_id = ty.user_type_id
            LEFT JOIN sys.default_constraints dc ON c.default_object_id = dc.object_id
            WHERE t.type = 'U'
            ORDER BY t.name, c.column_id
            """;

        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt. executeQuery(query)) {
            while (rs.next()) {
                String tableName = rs.getString("table_name");
                TableMetadata table = metadata.getTable(tableName);
                if (table == null) continue;

                ColumnMetadata column = new ColumnMetadata(
                    rs.getString("column_name"),
                    buildDataType(rs),
                    ! rs.getBoolean("is_nullable"),
                    rs.getString("default_value")
                );
                table.addColumn(column);
            }
        }
    }

    private String buildDataType(ResultSet rs) throws SQLException {
        String baseType = rs.getString("data_type").toLowerCase();

        // NVARCHAR/VARCHAR handling
        if (baseType. contains("varchar") || baseType.contains("char")) {
            int maxLength = rs.getInt("max_length");
            if (maxLength == -1) {
                return baseType + "(max)";
            }
            // NVARCHAR stores 2 bytes per character
            if (baseType.startsWith("n")) {
                maxLength = maxLength / 2;
            }
            return "varchar(" + maxLength + ")";
        }

        // DECIMAL/NUMERIC handling
        if (baseType.equals("decimal") || baseType.equals("numeric")) {
            int precision = rs.getInt("precision");
            int scale = rs.getInt("scale");
            if (scale > 0) {
                return baseType + "(" + precision + "," + scale + ")";
            }
            return "int";
        }

        // Normalize types
        return switch (baseType) {
            case "int" -> "int";
            case "bigint" -> "bigint";
            case "smallint" -> "smallint";
            case "tinyint" -> "tinyint";
            case "bit" -> "boolean";
            case "datetime", "datetime2" -> "timestamp";
            case "date" -> "date";
            case "text", "ntext" -> "longtext";
            case "varbinary" -> "blob";
            default -> baseType;
        };
    }

    private void extractConstraints(Connection conn, DatabaseMetadata metadata) throws SQLException {
        // Primary Keys
        String pkQuery = """
            SELECT
                t.name AS table_name,
                c. name AS column_name,
                kc.name AS constraint_name
            FROM sys.tables t
            JOIN sys. indexes i ON t.object_id = i.object_id
            JOIN sys.index_columns ic ON i.object_id = ic.object_id AND i.index_id = ic.index_id
            JOIN sys.columns c ON ic. object_id = c.object_id AND ic.column_id = c.column_id
            JOIN sys.key_constraints kc ON i.object_id = kc.parent_object_id AND i.index_id = kc. unique_index_id
            WHERE kc.type = 'PK'
            ORDER BY t.name, ic.key_ordinal
            """;

        Map<String, ConstraintMetadata> pkMap = new HashMap<>();
        try (Statement stmt = conn. createStatement(); ResultSet rs = stmt.executeQuery(pkQuery)) {
            while (rs.next()) {
                String table = rs.getString("table_name");
                String column = rs. getString("column_name");

                ConstraintMetadata pk = pkMap.computeIfAbsent(table, k ->
                    new ConstraintMetadata("PRIMARY_KEY", "PRIMARY_KEY", new ArrayList<>(), null)
                );
                pk. getColumns().add(column);
            }
        }

        for (Map.Entry<String, ConstraintMetadata> entry : pkMap.entrySet()) {
            ConstraintMetadata pk = entry.getValue();
            pk.setSignature(SignatureGenerator. generate(pk));
            TableMetadata table = metadata.getTable(entry.getKey());
            if (table != null) table.addConstraint(pk);
        }

        // Foreign Keys
        String fkQuery = """
            SELECT
                t.name AS table_name,
                c.name AS column_name,
                rt.name AS ref_table_name,
                fk.name AS constraint_name
            FROM sys.foreign_keys fk
            JOIN sys.tables t ON fk.parent_object_id = t.object_id
            JOIN sys.tables rt ON fk.referenced_object_id = rt.object_id
            JOIN sys.foreign_key_columns fkc ON fk.object_id = fkc.constraint_object_id
            JOIN sys.columns c ON fkc. parent_object_id = c.object_id AND fkc.parent_column_id = c.column_id
            ORDER BY t.name, fkc.constraint_column_id
            """;

        Map<String, ConstraintMetadata> fkMap = new HashMap<>();
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(fkQuery)) {
            while (rs.next()) {
                String table = rs.getString("table_name");
                String column = rs.getString("column_name");
                String refTable = rs. getString("ref_table_name");
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
            for (String tableName : metadata. getTableNames()) {
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
                t.name AS table_name,
                c.name AS column_name,
                kc.name AS constraint_name
            FROM sys.tables t
            JOIN sys.indexes i ON t. object_id = i.object_id
            JOIN sys.index_columns ic ON i.object_id = ic.object_id AND i.index_id = ic.index_id
            JOIN sys.columns c ON ic.object_id = c.object_id AND ic. column_id = c.column_id
            JOIN sys.key_constraints kc ON i.object_id = kc.parent_object_id AND i.index_id = kc.unique_index_id
            WHERE kc.type = 'UQ'
            ORDER BY t.name, ic. key_ordinal
            """;

        Map<String, ConstraintMetadata> uniqueMap = new HashMap<>();
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(uniqueQuery)) {
            while (rs.next()) {
                String table = rs.getString("table_name");
                String column = rs.getString("column_name");
                String constraintName = rs.getString("constraint_name");

                String key = table + "|" + constraintName;
                ConstraintMetadata unique = uniqueMap.computeIfAbsent(key, k ->
                    new ConstraintMetadata(constraintName, "UNIQUE", new ArrayList<>(), null)
                );
                unique.getColumns().add(column);
            }
        }

        for (ConstraintMetadata unique : uniqueMap.values()) {
            unique.setSignature(SignatureGenerator.generate(unique));
            for (String tableName : metadata. getTableNames()) {
                TableMetadata table = metadata.getTable(tableName);
                if (table != null && unique.getColumns().stream().anyMatch(col -> table.getColumn(col) != null)) {
                    table. addConstraint(unique);
                    break;
                }
            }
        }
    }

    private void extractIndexes(Connection conn, DatabaseMetadata metadata) throws SQLException {
        String query = """
            SELECT
                t.name AS table_name,
                i.name AS index_name,
                c.name AS column_name,
                i.is_unique,
                ic.key_ordinal
            FROM sys.tables t
            JOIN sys.indexes i ON t. object_id = i.object_id
            JOIN sys.index_columns ic ON i.object_id = ic.object_id AND i.index_id = ic.index_id
            JOIN sys.columns c ON ic.object_id = c.object_id AND ic.column_id = c. column_id
            WHERE i.is_primary_key = 0
            AND i.is_unique_constraint = 0
            AND i.type_desc = 'NONCLUSTERED'
            ORDER BY t. name, i.name, ic.key_ordinal
            """;

        Map<String, IndexMetadata> indexMap = new HashMap<>();
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(query)) {
            while (rs.next()) {
                String table = rs.getString("table_name");
                String indexName = rs.getString("index_name");
                String column = rs. getString("column_name");
                boolean unique = rs.getBoolean("is_unique");

                String key = table + "|" + indexName;
                IndexMetadata index = indexMap.computeIfAbsent(key, k ->
                    new IndexMetadata(indexName, new ArrayList<>(), unique)
                );
                index.getColumns().add(column);
            }
        }

        for (Map.Entry<String, IndexMetadata> entry : indexMap.entrySet()) {
            String table = entry. getKey().split("\\|")[0];
            TableMetadata tableMeta = metadata.getTable(table);
            if (tableMeta != null) {
                tableMeta.addIndex(entry. getValue());
            }
        }
    }
}