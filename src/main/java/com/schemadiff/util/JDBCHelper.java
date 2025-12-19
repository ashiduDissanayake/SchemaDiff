package com.schemadiff.util;

import java.sql. Connection;
import java.sql. DriverManager;
import java. sql.SQLException;

public class JDBCHelper {

    public static Connection connect(String jdbcUrl, String username, String password) throws SQLException {
        try {
            // Load appropriate driver
            if (jdbcUrl.contains("postgresql")) {
                Class. forName("org.postgresql.Driver");
            } else if (jdbcUrl.contains("mysql")) {
                Class.forName("com.mysql.cj. jdbc.Driver");
            } else if (jdbcUrl.contains("oracle")) {
                Class.forName("oracle.jdbc.OracleDriver");
            } else if (jdbcUrl.contains("sqlserver")) {
                Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
            } else if (jdbcUrl.contains("db2")) {
                Class.forName("com.ibm.db2.jcc.DB2Driver");
            }

            return DriverManager.getConnection(jdbcUrl, username, password);
        } catch (ClassNotFoundException e) {
            throw new SQLException("JDBC Driver not found", e);
        }
    }
}