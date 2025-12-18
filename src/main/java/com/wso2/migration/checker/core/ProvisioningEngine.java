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

        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password);
             Statement stmt = conn.createStatement()) {

            stmt.execute(sql); // let DB parse it
        } catch (Exception e) {
            throw new RuntimeException("Failed to execute SQL file: " + sqlFile.getName(), e);
        }
    }
}