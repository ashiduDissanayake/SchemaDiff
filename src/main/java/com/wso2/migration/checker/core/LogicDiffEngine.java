package com.wso2.migration.checker.core;

import com.wso2.migration.checker.model.DatabaseConfig;
import com.wso2.migration.checker.model.LogicDrift;
import com.wso2.migration.checker.util.HashUtil;
import com.wso2.migration.checker.util.LogicQueryProvider;
import com.wso2.migration.checker.DriftMaster.DatabaseType;

import java.sql.*;
import java.util.*;

public class LogicDiffEngine {

    public LogicDrift compare(DatabaseConfig reference, DatabaseConfig target, DatabaseType dbType) throws Exception {
        Map<String, String> referenceLogic = extractLogic(reference, dbType);
        Map<String, String> targetLogic = extractLogic(target, dbType);

        LogicDrift drift = new LogicDrift();

        // Find missing logic (in reference but not in target)
        for (String name : referenceLogic.keySet()) {
            if (!targetLogic.containsKey(name)) {
                drift.getMissingLogic().add(name);
            }
        }

        // Find unexpected logic (in target but not in reference)
        for (String name : targetLogic.keySet()) {
            if (!referenceLogic.containsKey(name)) {
                drift.getUnexpectedLogic().add(name);
            }
        }

        // Find changed logic (different hash)
        for (String name : referenceLogic.keySet()) {
            if (targetLogic.containsKey(name)) {
                String refHash = referenceLogic.get(name);
                String targetHash = targetLogic.get(name);
                if (!refHash.equals(targetHash)) {
                    drift.getChangedLogic().add(name);
                }
            }
        }

        return drift;
    }

    private Map<String, String> extractLogic(DatabaseConfig config, DatabaseType dbType) throws Exception {
        Map<String, String> logic = new HashMap<>();

        try (Connection conn = DriverManager. getConnection(
                config.getJdbcUrl(), config.getUsername(), config.getPassword())) {

            // Extract triggers
            String triggerQuery = LogicQueryProvider.getTriggerQuery(dbType);
            if (triggerQuery != null) {
                logic.putAll(executeLogicQuery(conn, triggerQuery, "TRIGGER"));
            }

            // Extract stored procedures
            String procQuery = LogicQueryProvider.getProcedureQuery(dbType);
            if (procQuery != null) {
                logic.putAll(executeLogicQuery(conn, procQuery, "PROCEDURE"));
            }

            // Extract functions
            String funcQuery = LogicQueryProvider.getFunctionQuery(dbType);
            if (funcQuery != null) {
                logic.putAll(executeLogicQuery(conn, funcQuery, "FUNCTION"));
            }
        }

        return logic;
    }

    private Map<String, String> executeLogicQuery(Connection conn, String query, String type) throws SQLException {
        Map<String, String> results = new HashMap<>();

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {

            while (rs.next()) {
                String name = rs.getString(1);
                String source = rs.getString(2);
                
                // Normalize and hash
                String normalized = normalizeSource(source);
                String hash = HashUtil.sha256(normalized);
                
                results.put(type + ":" + name, hash);
            }
        } catch (SQLException e) {
            // Some databases might not have certain object types - that's OK
            System.err.println("Warning: Could not query " + type + ": " + e.getMessage());
        }

        return results;
    }

    private String normalizeSource(String source) {
        if (source == null) return "";
        
        return source
            .replaceAll("--.*", "")           // Remove single-line comments
            .replaceAll("/\\*.*? \\*/", "")    // Remove multi-line comments
            .replaceAll("\\s+", " ")          // Normalize whitespace
            .toLowerCase()                     // Lowercase
            .trim();
    }
}