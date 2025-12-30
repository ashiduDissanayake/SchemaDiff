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

    public void addMissingConstraintDetailed(String table, ConstraintMetadata constraint) {
        String details = formatConstraintDetails(constraint);
        constraintDiffs.computeIfAbsent(table, k -> new ArrayList<>())
            .add("[X] Missing Constraint: " + constraint.getType() + " " + constraint.getName() + " " + details);
    }

    public void addExtraConstraintDetailed(String table, ConstraintMetadata constraint) {
        String details = formatConstraintDetails(constraint);
        constraintDiffs.computeIfAbsent(table, k -> new ArrayList<>())
            .add("[+] Extra Constraint: " + constraint.getType() + " " + constraint.getName() + " " + details);
    }

    public void addModifiedConstraintDetailed(String table, ConstraintMetadata refConstraint, ConstraintMetadata targetConstraint) {
        String refDetails = formatConstraintDetails(refConstraint);
        String targetDetails = formatConstraintDetails(targetConstraint);
        constraintDiffs.computeIfAbsent(table, k -> new ArrayList<>())
            .add("[M] Modified Constraint: " + refConstraint.getName() +
                 "\n        Expected: " + refDetails +
                 "\n        Found:    " + targetDetails);
    }

    private String formatConstraintDetails(ConstraintMetadata c) {
        StringBuilder sb = new StringBuilder();
        sb.append("ON (").append(String.join(", ", c.getColumns())).append(")");

        if (c.getReferencedTable() != null) {
            sb.append(" REFERENCES ").append(c.getReferencedTable());
            if (c.getReferencedColumns() != null && !c.getReferencedColumns().isEmpty()) {
                sb.append("(").append(String.join(", ", c.getReferencedColumns())).append(")");
            }
            if (c.getDeleteRule() != null) {
                sb.append(" ON DELETE ").append(c.getDeleteRule());
            }
            if (c.getUpdateRule() != null) {
                sb.append(" ON UPDATE ").append(c.getUpdateRule());
            }
        }

        if (c.getCheckClause() != null && !c.getCheckClause().isEmpty()) {
            sb.append(" CHECK ").append(c.getCheckClause());
        }

        return sb.toString();
    }

    public void addMissingIndex(String table, String signature) {
        indexDiffs. computeIfAbsent(table, k -> new ArrayList<>())
            .add("[X] Missing Index on: " + signature);
    }

    public void addMissingIndexDetailed(String table, IndexMetadata index) {
        String details = formatIndexDetails(index);
        indexDiffs.computeIfAbsent(table, k -> new ArrayList<>())
            .add("[X] Missing Index: " + index.getName() + " " + details);
    }

    public void addExtraIndexDetailed(String table, IndexMetadata index) {
        String details = formatIndexDetails(index);
        indexDiffs.computeIfAbsent(table, k -> new ArrayList<>())
            .add("[+] Extra Index: " + index.getName() + " " + details);
    }

    public void addModifiedIndexDetailed(String table, String indexName, IndexMetadata refIndex, IndexMetadata targetIndex) {
        String refDetails = formatIndexDetails(refIndex);
        String targetDetails = formatIndexDetails(targetIndex);
        indexDiffs.computeIfAbsent(table, k -> new ArrayList<>())
            .add("[M] Modified Index: " + indexName +
                 "\n        Expected: " + refDetails +
                 "\n        Found:    " + targetDetails);
    }

    private String formatIndexDetails(IndexMetadata idx) {
        StringBuilder sb = new StringBuilder();
        sb.append("ON (").append(String.join(", ", idx.getColumns())).append(")");
        sb.append(" [");
        sb.append(idx.isUnique() ? "UNIQUE" : "NON-UNIQUE");
        if (idx.getIndexType() != null) {
            sb.append(", ").append(idx.getIndexType());
        }
        sb.append("]");
        return sb.toString();
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