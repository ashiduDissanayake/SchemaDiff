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
                // Basic comment filtering
                if (!trimmed.isEmpty() && !trimmed.startsWith("--") && !trimmed.startsWith("/*")) {
                    stmt.execute(trimmed);
                }
            }
        }
    }
}