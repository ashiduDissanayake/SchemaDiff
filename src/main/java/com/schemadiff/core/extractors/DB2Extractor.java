package com.schemadiff.core.extractors;

import com.schemadiff.core.MetadataExtractor;
import com.schemadiff.core.SignatureGenerator;
import com.schemadiff.model.*;

import java.sql.*;
import java.util.*;

public class DB2Extractor extends MetadataExtractor {

    @Override
    public DatabaseMetadata extract(Connection conn) throws Exception {
        DatabaseMetadata metadata = new DatabaseMetadata();

        String currentSchema = getCurrentSchema(conn);
        extractTables(conn, metadata, currentSchema);
        extractColumns(conn, metadata, currentSchema);
        extractConstraints(conn, metadata, currentSchema);
        extractIndexes(conn, metadata, currentSchema);

        return metadata;
    }

    private String getCurrentSchema(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt. executeQuery("SELECT CURRENT SCHEMA FROM SYSIBM. SYSDUMMY1")) {
            if (rs.next()) {
                return rs.getString(1);
            }
            throw new SQLException("Could not determine current schema");
        }
    }

    private void extractTables(Connection conn, DatabaseMetadata metadata, String schema) throws SQLException {
        String query = """
            SELECT tabname
            FROM syscat.tables
            WHERE tabschema = ?
            AND type = 'T'
            ORDER BY tabname
            """;

        try (PreparedStatement pstmt = conn.prepareStatement(query)) {
            pstmt.setString(1, schema. toUpperCase());
            ResultSet rs = pstmt.executeQuery();

            while (rs.next()) {
                metadata. addTable(new TableMetadata(rs.getString("tabname")));
            }
        }
    }

    private void extractColumns(Connection conn, DatabaseMetadata metadata, String schema) throws SQLException {
        String query = """
            SELECT
                tabname,
                colname,
                typename,
                length,
                scale,
                nulls,
                default
            FROM syscat.columns
            WHERE tabschema = ?
            ORDER BY tabname, colno
            """;

        try (PreparedStatement pstmt = conn.prepareStatement(query)) {
            pstmt.setString(1, schema.toUpperCase());
            ResultSet rs = pstmt.executeQuery();

            while (rs.next()) {
                String tableName = rs.getString("tabname");
                TableMetadata table = metadata.getTable(tableName);
                if (table == null) continue;

                ColumnMetadata column = new ColumnMetadata(
                    rs.getString("colname"),
                    buildDataType(rs),
                    "N". equals(rs.getString("nulls")),
                    rs. getString("default")
                );
                table. addColumn(column);
            }
        }
    }

    private String buildDataType(ResultSet rs) throws SQLException {
        String baseType = rs.getString("typename").toLowerCase();
        int length = rs.getInt("length");
        int scale = rs. getInt("scale");

        // VARCHAR handling
        if (baseType.equals("varchar")) {
            return "varchar(" + length + ")";
        }

        // CHAR handling
        if (baseType. equals("character")) {
            return "char(" + length + ")";
        }

        // DECIMAL handling
        if (baseType.equals("decimal") || baseType.equals("numeric")) {
            if (scale > 0) {
                return "numeric(" + length + "," + scale + ")";
            }
            return "int";
        }

        // Normalize types
        return switch (baseType) {
            case "integer" -> "int";
            case "bigint" -> "bigint";
            case "smallint" -> "smallint";
            case "timestamp" -> "timestamp";
            case "date" -> "date";
            case "clob" -> "longtext";
            case "blob" -> "blob";
            default -> baseType;
        };
    }

    private void extractConstraints(Connection conn, DatabaseMetadata metadata, String schema) throws SQLException {
        // Primary Keys
        String pkQuery = """
            SELECT
                tc.tabname,
                kc.colname,
                tc.constname
            FROM syscat.tabconst tc
            JOIN syscat.keycoluse kc ON tc.constname = kc.constname AND tc.tabschema = kc.tabschema
            WHERE tc.type = 'P'
            AND tc.tabschema = ?
            ORDER BY tc.tabname, kc.colseq
            """;

        Map<String, ConstraintMetadata> pkMap = new HashMap<>();
        try (PreparedStatement pstmt = conn.prepareStatement(pkQuery)) {
            pstmt.setString(1, schema.toUpperCase());
            ResultSet rs = pstmt.executeQuery();

            while (rs.next()) {
                String table = rs.getString("tabname");
                String column = rs.getString("colname");

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
                r.tabname,
                kc.colname,
                r.reftabname,
                r.constname
            FROM syscat.references r
            JOIN syscat.keycoluse kc ON r.constname = kc.constname AND r.tabschema = kc.tabschema
            WHERE r.tabschema = ?
            ORDER BY r.tabname, kc.colseq
            """;

        Map<String, ConstraintMetadata> fkMap = new HashMap<>();
        try (PreparedStatement pstmt = conn. prepareStatement(fkQuery)) {
            pstmt.setString(1, schema.toUpperCase());
            ResultSet rs = pstmt.executeQuery();

            while (rs.next()) {
                String table = rs.getString("tabname");
                String column = rs.getString("colname");
                String refTable = rs.getString("reftabname");
                String constraintName = rs.getString("constname");

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
                tc.tabname,
                kc.colname,
                tc.constname
            FROM syscat.tabconst tc
            JOIN syscat.keycoluse kc ON tc.constname = kc.constname AND tc.tabschema = kc.tabschema
            WHERE tc.type = 'U'
            AND tc.tabschema = ?
            ORDER BY tc.tabname, kc.colseq
            """;

        Map<String, ConstraintMetadata> uniqueMap = new HashMap<>();
        try (PreparedStatement pstmt = conn.prepareStatement(uniqueQuery)) {
            pstmt.setString(1, schema. toUpperCase());
            ResultSet rs = pstmt.executeQuery();

            while (rs.next()) {
                String table = rs.getString("tabname");
                String column = rs.getString("colname");
                String constraintName = rs.getString("constname");

                String key = table + "|" + constraintName;
                ConstraintMetadata unique = uniqueMap.computeIfAbsent(key, k ->
                    new ConstraintMetadata(constraintName, "UNIQUE", new ArrayList<>(), null)
                );
                unique.getColumns().add(column);
            }
        }

        for (ConstraintMetadata unique : uniqueMap.values()) {
            unique.setSignature(SignatureGenerator.generate(unique));
            for (String tableName : metadata.getTableNames()) {
                TableMetadata table = metadata.getTable(tableName);
                if (table != null && unique.getColumns().stream().anyMatch(col -> table.getColumn(col) != null)) {
                    table.addConstraint(unique);
                    break;
                }
            }
        }
    }

    private void extractIndexes(Connection conn, DatabaseMetadata metadata, String schema) throws SQLException {
        String query = """
            SELECT
                i.tabname,
                i. indname,
                ic.colname,
                i.uniquerule,
                ic.colseq
            FROM syscat. indexes i
            JOIN syscat. indexcoluse ic ON i.indname = ic.indname AND i. indschema = ic.indschema
            WHERE i.tabschema = ?
            AND i.uniquerule != 'P'
            ORDER BY i.tabname, i.indname, ic.colseq
            """;

        Map<String, IndexMetadata> indexMap = new HashMap<>();
        try (PreparedStatement pstmt = conn.prepareStatement(query)) {
            pstmt.setString(1, schema.toUpperCase());
            ResultSet rs = pstmt.executeQuery();

            while (rs.next()) {
                String table = rs.getString("tabname");
                String indexName = rs.getString("indname");
                String column = rs.getString("colname");
                boolean unique = "U".equals(rs.getString("uniquerule"));

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