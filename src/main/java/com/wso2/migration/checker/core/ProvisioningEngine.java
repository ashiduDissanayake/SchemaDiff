package com.wso2.migration.checker.core;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

public class ProvisioningEngine {
    private final String jdbcUrl;
    private final String username;
    private final String password;

    public ProvisioningEngine(String jdbcUrl, String username, String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
    }

    public void execute(File sqlFile) throws Exception {
        String sql = Files.readString(sqlFile.toPath());
        
        // Remove Byte Order Mark (BOM) if present
        if (sql.startsWith("\uFEFF")) {
            sql = sql.substring(1);
        }

        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password);
             Statement stmt = conn.createStatement()) {
            
            // If allowMultiQueries is enabled, execute the whole script at once
            if (jdbcUrl.contains("allowMultiQueries=true")) {
                try {
                    stmt.execute(sql);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to execute SQL script", e);
                }
            } else {
                // Split SQL by statement delimiter and execute each
                String[] statements = sql.split(";");
                for (String statement : statements) {
                    String trimmed = statement.trim();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("--")) {
                        try {
                            stmt.execute(trimmed);
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to execute SQL: " + trimmed, e);
                        }
                    }
                }
            }
        }
    }
}