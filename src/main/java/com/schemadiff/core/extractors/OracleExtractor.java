package com.schemadiff.core.extractors;

import com.schemadiff.core.MetadataExtractor;
import com.schemadiff.core.SignatureGenerator;
import com.schemadiff.model.*;

import java.sql.*;
import java.util.*;

public class OracleExtractor extends MetadataExtractor {

    @Override
    public DatabaseMetadata extract(Connection conn) throws Exception {
        DatabaseMetadata metadata = new DatabaseMetadata();

        String currentUser = getCurrentUser(conn);
        extractTables(conn, metadata, currentUser);
        extractColumns(conn, metadata, currentUser);
        extractConstraints(conn, metadata, currentUser);
        extractIndexes(conn, metadata, currentUser);

        return metadata;
    }

    private String getCurrentUser(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt. executeQuery("SELECT USER FROM DUAL")) {
            if (rs.next()) {
                return rs.getString(1);
            }
            throw new SQLException("Could not determine current user");
        }
    }

    private void extractTables(Connection conn, DatabaseMetadata metadata, String owner) throws SQLException {
        String query = "SELECT table_name FROM all_tables WHERE owner = ?  ORDER BY table_name";

        try (PreparedStatement pstmt = conn.prepareStatement(query)) {
            pstmt.setString(1, owner. toUpperCase());
            ResultSet rs = pstmt.executeQuery();

            while (rs. next()) {
                metadata.addTable(new TableMetadata(rs.getString("table_name")));
            }
        }
    }

    private void extractColumns(Connection conn, DatabaseMetadata metadata, String owner) throws SQLException {
        String query = """
            SELECT
                table_name,
                column_name,
                data_type,
                data_length,
                data_precision,
                data_scale,
                nullable,
                data_default
            FROM all_tab_columns
            WHERE owner = ?
            ORDER BY table_name, column_id
            """;

        try (PreparedStatement pstmt = conn.prepareStatement(query)) {
            pstmt.setString(1, owner.toUpperCase());
            ResultSet rs = pstmt.executeQuery();

            while (rs.next()) {
                String tableName = rs.getString("table_name");
                TableMetadata table = metadata. getTable(tableName);
                if (table == null) continue;

                ColumnMetadata column = new ColumnMetadata(
                    rs.getString("column_name"),
                    buildDataType(rs),
                    "N".equals(rs.getString("nullable")),
                    rs.getString("data_default")
                );
                table.addColumn(column);
            }
        }
    }

    private String buildDataType(ResultSet rs) throws SQLException {
        String baseType = rs.getString("data_type").toLowerCase();

        // Oracle NUMBER handling
        if (baseType.equals("number")) {
            Integer precision = rs. getInt("data_precision");
            Integer scale = rs.getInt("data_scale");

            if (rs.wasNull() || precision == null) {
                return "int"; // NUMBER without precision = integer
            }

            if (scale != null && scale > 0) {
                return "numeric(" + precision + "," + scale + ")";
            }
            return "int";
        }

        // VARCHAR2 handling
        if (baseType.equals("varchar2")) {
            Integer length = rs.getInt("data_length");
            if (! rs.wasNull()) {
                return "varchar(" + length + ")";
            }
        }

        // CHAR handling
        if (baseType.equals("char")) {
            Integer length = rs. getInt("data_length");
            if (!rs.wasNull()) {
                return "char(" + length + ")";
            }
        }

        // Normalize types
        return switch (baseType) {
            case "varchar2" -> "varchar";
            case "clob" -> "longtext";
            case "blob" -> "blob";
            case "date" -> "timestamp";
            case "timestamp(6)" -> "timestamp";
            default -> baseType;
        };
    }

    private void extractConstraints(Connection conn, DatabaseMetadata metadata, String owner) throws SQLException {
        // Primary Keys
        String pkQuery = """
            SELECT
                c.table_name,
                cc.column_name,
                c.constraint_name
            FROM all_constraints c
            JOIN all_cons_columns cc ON c.constraint_name = cc.constraint_name AND c.owner = cc.owner
            WHERE c.constraint_type = 'P'
            AND c.owner = ?
            ORDER BY c.table_name, cc.position
            """;

        Map<String, ConstraintMetadata> pkMap = new HashMap<>();
        try (PreparedStatement pstmt = conn.prepareStatement(pkQuery)) {
            pstmt.setString(1, owner. toUpperCase());
            ResultSet rs = pstmt.executeQuery();

            while (rs.next()) {
                String table = rs.getString("table_name");
                String column = rs. getString("column_name");

                ConstraintMetadata pk = pkMap.computeIfAbsent(table, k ->
                    new ConstraintMetadata("PRIMARY_KEY", "PRIMARY_KEY", new ArrayList<>(), null)
                );
                pk.getColumns().add(column);
            }
        }

        for (Map.Entry<String, ConstraintMetadata> entry : pkMap. entrySet()) {
            ConstraintMetadata pk = entry. getValue();
            pk.setSignature(SignatureGenerator.generate(pk));
            TableMetadata table = metadata.getTable(entry. getKey());
            if (table != null) table.addConstraint(pk);
        }

        // Foreign Keys
        String fkQuery = """
            SELECT
                c.table_name,
                cc.column_name,
                c.r_constraint_name,
                rc.table_name AS ref_table_name,
                c.constraint_name
            FROM all_constraints c
            JOIN all_cons_columns cc ON c.constraint_name = cc.constraint_name AND c.owner = cc.owner
            JOIN all_constraints rc ON c.r_constraint_name = rc.constraint_name AND c.r_owner = rc.owner
            WHERE c.constraint_type = 'R'
            AND c. owner = ?
            ORDER BY c.table_name, cc.position
            """;

        Map<String, ConstraintMetadata> fkMap = new HashMap<>();
        try (PreparedStatement pstmt = conn.prepareStatement(fkQuery)) {
            pstmt.setString(1, owner.toUpperCase());
            ResultSet rs = pstmt. executeQuery();

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
            for (String tableName : metadata.getTableNames()) {
                TableMetadata table = metadata.getTable(tableName);
                if (table != null && fk.getColumns().stream().anyMatch(col -> table.getColumn(col) != null)) {
                    table. addConstraint(fk);
                    break;
                }
            }
        }

        // Unique Constraints
        String uniqueQuery = """
            SELECT
                c.table_name,
                cc.column_name,
                c.constraint_name
            FROM all_constraints c
            JOIN all_cons_columns cc ON c.constraint_name = cc. constraint_name AND c.owner = cc.owner
            WHERE c. constraint_type = 'U'
            AND c.owner = ?
            ORDER BY c.table_name, cc.position
            """;

        Map<String, ConstraintMetadata> uniqueMap = new HashMap<>();
        try (PreparedStatement pstmt = conn.prepareStatement(uniqueQuery)) {
            pstmt. setString(1, owner.toUpperCase());
            ResultSet rs = pstmt.executeQuery();

            while (rs. next()) {
                String table = rs.getString("table_name");
                String column = rs.getString("column_name");
                String constraintName = rs.getString("constraint_name");

                String key = table + "|" + constraintName;
                ConstraintMetadata unique = uniqueMap. computeIfAbsent(key, k ->
                    new ConstraintMetadata(constraintName, "UNIQUE", new ArrayList<>(), null)
                );
                unique.getColumns().add(column);
            }
        }

        for (ConstraintMetadata unique : uniqueMap. values()) {
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

    private void extractIndexes(Connection conn, DatabaseMetadata metadata, String owner) throws SQLException {
        String query = """
            SELECT
                i.table_name,
                i.index_name,
                ic.column_name,
                i.uniqueness,
                ic.column_position
            FROM all_indexes i
            JOIN all_ind_columns ic ON i.index_name = ic.index_name AND i.table_owner = ic.table_owner
            WHERE i.table_owner = ?
            AND i.index_type = 'NORMAL'
            AND NOT EXISTS (
                SELECT 1 FROM all_constraints c
                WHERE c.constraint_type = 'P'
                AND c.index_name = i.index_name
                AND c.owner = i.table_owner
            )
            ORDER BY i.table_name, i.index_name, ic.column_position
            """;

        Map<String, IndexMetadata> indexMap = new HashMap<>();
        try (PreparedStatement pstmt = conn.prepareStatement(query)) {
            pstmt. setString(1, owner.toUpperCase());
            ResultSet rs = pstmt.executeQuery();

            while (rs. next()) {
                String table = rs.getString("table_name");
                String indexName = rs. getString("index_name");
                String column = rs.getString("column_name");
                boolean unique = "UNIQUE".equals(rs.getString("uniqueness"));

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