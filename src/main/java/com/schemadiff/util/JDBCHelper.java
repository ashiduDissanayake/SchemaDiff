package com.schemadiff.util;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class JDBCHelper {

    public static Connection connect(String jdbcUrl, String username, String password) throws SQLException {
        // Since JDBC 4.0, drivers auto-register via ServiceLoader
        // No need for explicit Class.forName() in most cases
        // But we'll keep it as fallback for compatibility

        try {
            // Try to load driver explicitly for compatibility
            if (jdbcUrl.contains("postgresql")) {
                tryLoadDriver("org.postgresql.Driver");
            } else if (jdbcUrl.contains("mysql")) {
                tryLoadDriver("com.mysql.cj.jdbc.Driver");
            } else if (jdbcUrl.contains("oracle")) {
                tryLoadDriver("oracle.jdbc.OracleDriver");
            } else if (jdbcUrl.contains("sqlserver")) {
                tryLoadDriver("com.microsoft.sqlserver.jdbc.SQLServerDriver");
            } else if (jdbcUrl.contains("db2")) {
                tryLoadDriver("com.ibm.db2.jcc.DB2Driver");
            }
        } catch (ClassNotFoundException e) {
            // Ignore - DriverManager will try to auto-load
            System.err.println("⚠️  Warning: Could not explicitly load JDBC driver, attempting auto-load: " + e.getMessage());
        }

        // Let DriverManager try to connect (it will auto-load drivers)
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    private static void tryLoadDriver(String className) throws ClassNotFoundException {
        Class.forName(className);
    }
}

