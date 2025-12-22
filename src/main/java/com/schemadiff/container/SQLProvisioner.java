package com.schemadiff.container;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.Statement;

public class SQLProvisioner {
    private final Connection connection;

    public SQLProvisioner(Connection connection) {
        this.connection = connection;
    }

    public void execute(File sqlFile) throws Exception {
        String sql = Files.readString(sqlFile.toPath());
        // Simple splitting by semicolon, ignoring potential semicolons in quotes.
        // This is a basic implementation. For production, consider using a proper SQL parser or Testcontainers ScriptUtils.
        String[] statements = sql.split(";");

        try (Statement stmt = connection.createStatement()) {
            for (String statement : statements) {
                String trimmed = statement.trim();

                // Handle comments that might be attached to the statement due to splitting
                String[] lines = trimmed.split("\\r?\\n");
                StringBuilder cleanSql = new StringBuilder();
                for (String line : lines) {
                    String lineTrimmed = line.trim();
                    if (!lineTrimmed.startsWith("--") && !lineTrimmed.startsWith("/*") && !lineTrimmed.isEmpty()) {
                        cleanSql.append(line).append(" ");
                    }
                }

                String finalSql = cleanSql.toString().trim();
                if (!finalSql.isEmpty()) {
                    stmt.execute(finalSql);
                }
            }
        }
    }
}