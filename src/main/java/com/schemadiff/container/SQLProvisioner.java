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

        // Parse SQL into statements (PostgreSQL-aware)
        List<String> statements = parseStatements(sql);

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
                String trimmed = statement.trim();

                // Skip if empty
                if (trimmed.isEmpty()) {
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

    /**
     * Parse SQL into individual statements, handling:
     * - Dollar quotes ($$...$$) in PostgreSQL functions
     * - Single quotes ('...')
     * - Double quotes ("...")
     * - Single-line comments (--)
     * - Multi-line comments (/*...*\/)
     */
    private List<String> parseStatements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        int i = 0;
        int length = sql.length();

        while (i < length) {
            char c = sql.charAt(i);

            // Handle single-line comments
            if (c == '-' && i + 1 < length && sql.charAt(i + 1) == '-') {
                // Skip until end of line
                while (i < length && sql.charAt(i) != '\n') {
                    i++;
                }
                i++;
                continue;
            }

            // Handle multi-line comments
            if (c == '/' && i + 1 < length && sql.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < length) {
                    if (sql.charAt(i) == '*' && sql.charAt(i + 1) == '/') {
                        i += 2;
                        break;
                    }
                    i++;
                }
                continue;
            }

            // Handle dollar quotes (PostgreSQL)
            if (c == '$') {
                int endTag = i + 1;
                while (endTag < length && sql.charAt(endTag) != '$') {
                    endTag++;
                }
                if (endTag < length) {
                    String tag = sql.substring(i, endTag + 1); // e.g., $$
                    current.append(tag);
                    i = endTag + 1;

                    // Find closing tag
                    while (i < length) {
                        if (sql.startsWith(tag, i)) {
                            current.append(tag);
                            i += tag.length();
                            break;
                        }
                        current.append(sql.charAt(i));
                        i++;
                    }
                    continue;
                }
            }

            // Handle single-quoted strings
            if (c == '\'') {
                current.append(c);
                i++;
                while (i < length) {
                    char ch = sql.charAt(i);
                    current.append(ch);
                    if (ch == '\'') {
                        // Check for escaped quote ''
                        if (i + 1 < length && sql.charAt(i + 1) == '\'') {
                            current.append('\'');
                            i += 2;
                        } else {
                            i++;
                            break;
                        }
                    } else {
                        i++;
                    }
                }
                continue;
            }

            // Handle double-quoted identifiers
            if (c == '"') {
                current.append(c);
                i++;
                while (i < length) {
                    char ch = sql.charAt(i);
                    current.append(ch);
                    if (ch == '"') {
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }

            // Handle statement separator
            if (c == ';') {
                String stmt = current.toString().trim();
                if (!stmt.isEmpty()) {
                    statements.add(stmt);
                }
                current.setLength(0);
                i++;
                continue;
            }

            // Regular character
            current.append(c);
            i++;
        }

        // Add remaining statement
        String stmt = current.toString().trim();
        if (!stmt.isEmpty()) {
            statements.add(stmt);
        }

        return statements;
    }

    private String preprocessMySQLSchema(String sql) {
        // Add ROW_FORMAT=DYNAMIC to CREATE TABLE statements that don't have it
        // This allows larger index keys in MySQL 8.0
        return sql.replaceAll(
            "(?i)(CREATE\\s+TABLE[^;]+)ENGINE\\s*=?\\s*INNODB",
            "$1 ROW_FORMAT=DYNAMIC ENGINE=INNODB"
        );
    }
}