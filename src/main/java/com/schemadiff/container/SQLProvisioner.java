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
        String[] statements = sql.split(";");

        try (Statement stmt = connection.createStatement()) {
            for (String statement : statements) {
                String trimmed = statement.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("--") && !trimmed.startsWith("/*")) {
                    stmt.execute(trimmed);
                }
            }
        }
    }
}