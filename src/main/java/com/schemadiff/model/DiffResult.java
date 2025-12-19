package com.schemadiff.model;

import java.util.*;

public class DiffResult {
    private List<String> missingTables = new ArrayList<>();
    private List<String> extraTables = new ArrayList<>();
    private Map<String, List<String>> columnDiffs = new LinkedHashMap<>();
    private Map<String, List<String>> constraintDiffs = new LinkedHashMap<>();
    private Map<String, List<String>> indexDiffs = new LinkedHashMap<>();

    public void addMissingTable(String table) {
        missingTables.add(table);
    }

    public void addExtraTable(String table) {
        extraTables.add(table);
    }

    public void addMissingColumn(String table, String column, String type) {
        columnDiffs.computeIfAbsent(table, k -> new ArrayList<>())
            .add("[X] Missing Column: " + column + " [" + type + "]");
    }

    public void addModifiedColumn(String table, String column, String reason) {
        columnDiffs. computeIfAbsent(table, k -> new ArrayList<>())
            .add("[M] Modified Column: " + column + " - " + reason);
    }

    public void addExtraColumn(String table, String column) {
        columnDiffs.computeIfAbsent(table, k -> new ArrayList<>())
            .add("[+] Extra Column: " + column);
    }

    public void addMissingConstraint(String table, String type) {
        constraintDiffs. computeIfAbsent(table, k -> new ArrayList<>())
            .add("[X] Missing Constraint: " + type);
    }

    public void addMissingIndex(String table, String signature) {
        indexDiffs. computeIfAbsent(table, k -> new ArrayList<>())
            .add("[X] Missing Index on: " + signature);
    }

    public boolean hasDifferences() {
        return !missingTables.isEmpty() || !extraTables.isEmpty() ||
               !columnDiffs.isEmpty() || !constraintDiffs.isEmpty() || ! indexDiffs.isEmpty();
    }

    public int getTotalDifferences() {
        return missingTables.size() + extraTables.size() +
               columnDiffs.values().stream().mapToInt(List::size).sum() +
               constraintDiffs.values().stream().mapToInt(List::size).sum() +
               indexDiffs.values().stream().mapToInt(List::size).sum();
    }

    // Getters
    public List<String> getMissingTables() { return missingTables; }
    public List<String> getExtraTables() { return extraTables; }
    public Map<String, List<String>> getColumnDiffs() { return columnDiffs; }
    public Map<String, List<String>> getConstraintDiffs() { return constraintDiffs; }
    public Map<String, List<String>> getIndexDiffs() { return indexDiffs; }
}