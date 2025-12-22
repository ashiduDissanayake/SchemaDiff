package com.schemadiff.container;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class SQLProvisioner {
    private final Connection connection;
    private boolean continueOnError = true; // Allow continuing on non-critical errors

    public SQLProvisioner(Connection connection) {
        this.connection = connection;
    }

    public void setContinueOnError(boolean continueOnError) {
        this.continueOnError = continueOnError;
    }

    public void execute(File sqlFile) throws Exception {
        String sql = Files.readString(sqlFile.toPath());

        // Preprocess SQL for MySQL compatibility: add ROW_FORMAT=DYNAMIC to tables
        sql = preprocessMySQLSchema(sql);

        // Split by semicolon and process each statement
        String[] statements = sql.split(";");

        List<String> errors = new ArrayList<>();
        try (Statement stmt = connection.createStatement()) {
            // Try to set session-level MySQL settings (no SUPER privilege required)
            try {
                stmt.execute("SET SESSION innodb_strict_mode = OFF");
                System.out.println("✓ MySQL session configured for schema compatibility");
            } catch (Exception e) {
                // Ignore if not supported
            }

            int statementCount = 0;
            int successCount = 0;
            int errorCount = 0;

            for (String statement : statements) {
                // Remove comments first
                String cleaned = removeSingleLineComments(statement);
                String trimmed = cleaned.trim();

                // Skip if empty after comment removal
                if (trimmed.isEmpty()) {
                    continue;
                }

                // Skip block comments
                if (trimmed.startsWith("/*")) {
                    continue;
                }

                try {
                    statementCount++;
                    stmt.execute(trimmed);
                    successCount++;
                } catch (Exception e) {
                    errorCount++;
                    String preview = trimmed.substring(0, Math.min(100, trimmed.length()));
                    String errorMsg = "Statement #" + statementCount + ": " + e.getMessage() +
                                    "\n  SQL: " + preview + (trimmed.length() > 100 ? "..." : "");
                    errors.add(errorMsg);

                    if (!continueOnError) {
                        System.err.println("Failed to execute statement #" + statementCount + ": " + preview + "...");
                        throw e;
                    } else {
                        System.err.println("⚠️  Warning: Skipping failed statement #" + statementCount +
                                         ": " + e.getMessage().split("\n")[0]);
                    }
                }
            }

            System.out.println("✓ Executed " + successCount + " SQL statements successfully" +
                             (errorCount > 0 ? " (" + errorCount + " errors skipped)" : ""));

            if (!errors.isEmpty() && continueOnError) {
                System.out.println("\n⚠️  Note: " + errors.size() + " statement(s) failed but were skipped:");
                for (int i = 0; i < Math.min(3, errors.size()); i++) {
                    System.out.println("  - " + errors.get(i).split("\n")[0]);
                }
                if (errors.size() > 3) {
                    System.out.println("  ... and " + (errors.size() - 3) + " more errors");
                }
            }
        }
    }

    private String preprocessMySQLSchema(String sql) {
        // Add ROW_FORMAT=DYNAMIC to CREATE TABLE statements that don't have it
        // This allows larger index keys in MySQL 8.0
        return sql.replaceAll(
            "(?i)(CREATE\\s+TABLE[^;]+)ENGINE\\s*=?\\s*INNODB",
            "$1 ROW_FORMAT=DYNAMIC ENGINE=INNODB"
        );
    }

    private String removeSingleLineComments(String sql) {
        StringBuilder result = new StringBuilder();
        String[] lines = sql.split("\n");
        for (String line : lines) {
            int commentIndex = line.indexOf("--");
            if (commentIndex >= 0) {
                // Keep the part before the comment
                String beforeComment = line.substring(0, commentIndex).trim();
                if (!beforeComment.isEmpty()) {
                    result.append(beforeComment).append("\n");
                }
            } else {
                result.append(line).append("\n");
            }
        }
        return result.toString();
    }
}