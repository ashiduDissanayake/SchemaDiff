package com.wso2.migration.checker.model;

import java.util.ArrayList;
import java.util.List;

public class StructuralDrift {
    private List<String> missingTables = new ArrayList<>();
    private List<String> missingColumns = new ArrayList<>();
    private List<String> missingIndexes = new ArrayList<>();
    private List<String> missingPrimaryKeys = new ArrayList<>();
    private List<String> missingForeignKeys = new ArrayList<>();
    private List<String> missingConstraints = new ArrayList<>();
    
    private List<String> unexpectedTables = new ArrayList<>();
    private List<String> unexpectedColumns = new ArrayList<>();
    private List<String> unexpectedIndexes = new ArrayList<>();
    
    private List<String> changedTables = new ArrayList<>();
    private List<String> changedColumns = new ArrayList<>();

    public boolean hasDrift() {
        return !missingTables.isEmpty() || !missingColumns.isEmpty() || 
               !missingIndexes.isEmpty() || !unexpectedTables.isEmpty() ||
               !unexpectedColumns.isEmpty() || !changedTables.isEmpty() ||
               !changedColumns.isEmpty() || !missingPrimaryKeys.isEmpty() ||
               !missingForeignKeys.isEmpty() || !missingConstraints.isEmpty();
    }

    // Getters
    public List<String> getMissingTables() { return missingTables; }
    public List<String> getMissingColumns() { return missingColumns; }
    public List<String> getMissingIndexes() { return missingIndexes; }
    public List<String> getMissingPrimaryKeys() { return missingPrimaryKeys; }
    public List<String> getMissingForeignKeys() { return missingForeignKeys; }
    public List<String> getMissingConstraints() { return missingConstraints; }
    public List<String> getUnexpectedTables() { return unexpectedTables; }
    public List<String> getUnexpectedColumns() { return unexpectedColumns; }
    public List<String> getUnexpectedIndexes() { return unexpectedIndexes; }
    public List<String> getChangedTables() { return changedTables; }
    public List<String> getChangedColumns() { return changedColumns; }
}