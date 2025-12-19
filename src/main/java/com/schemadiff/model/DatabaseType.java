package com.schemadiff.model;

public enum DatabaseType {
    POSTGRES, MYSQL, ORACLE, MSSQL, DB2;

    public static DatabaseType fromString(String s) {
        return switch (s.toLowerCase()) {
            case "postgres", "postgresql" -> POSTGRES;
            case "mysql" -> MYSQL;
            case "oracle" -> ORACLE;
            case "mssql", "sqlserver" -> MSSQL;
            case "db2" -> DB2;
            default -> throw new IllegalArgumentException("Unknown DB: " + s);
        };
    }
}
