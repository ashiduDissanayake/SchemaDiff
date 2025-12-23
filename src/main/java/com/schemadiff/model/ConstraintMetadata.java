

// ============================================================================
// ConstraintMetadata.java
// ============================================================================
package com.schemadiff.model;

import java.util.*;

/**
 * Represents metadata for database constraints including primary keys,
 * foreign keys, unique constraints, and check constraints.
 */
public class ConstraintMetadata {
    private String name;
    private String type;  // PRIMARY_KEY, FOREIGN_KEY, UNIQUE, CHECK
    private List<String> columns;
    private String referencedTable;
    private List<String> referencedColumns;
    private String signature;

    // Foreign key specific properties
    private String updateRule;  // CASCADE, SET NULL, NO ACTION, RESTRICT
    private String deleteRule;

    // Check constraint specific
    private String checkClause;

    public ConstraintMetadata(String type, String name, List<String> columns, String referencedTable) {
        if (type == null || type.trim().isEmpty()) {
            throw new IllegalArgumentException("Constraint type cannot be null or empty");
        }
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Constraint name cannot be null or empty");
        }
        if (columns == null) {
            throw new IllegalArgumentException("Columns list cannot be null");
        }
        this.type = type;
        this.name = name;
        this.columns = new ArrayList<>(columns);
        this.referencedTable = referencedTable;
    }

    // Primary getters
    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    public List<String> getColumns() {
        return Collections.unmodifiableList(columns);
    }

    public String getReferencedTable() {
        return referencedTable;
    }

    public String getSignature() {
        return signature;
    }

    public void setSignature(String signature) {
        this.signature = signature;
    }

    // Extended getters and setters
    public List<String> getReferencedColumns() {
        return referencedColumns != null ?
                Collections.unmodifiableList(referencedColumns) : null;
    }

    public void setReferencedColumns(List<String> referencedColumns) {
        this.referencedColumns = referencedColumns != null ?
                new ArrayList<>(referencedColumns) : null;
    }

    public String getUpdateRule() {
        return updateRule;
    }

    public void setUpdateRule(String updateRule) {
        this.updateRule = updateRule;
    }

    public String getDeleteRule() {
        return deleteRule;
    }

    public void setDeleteRule(String deleteRule) {
        this.deleteRule = deleteRule;
    }

    public String getCheckClause() {
        return checkClause;
    }

    public void setCheckClause(String checkClause) {
        this.checkClause = checkClause;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("ConstraintMetadata[name=%s, type=%s, columns=%s",
                name, type, columns));
        if (referencedTable != null) {
            sb.append(", refTable=").append(referencedTable);
            if (referencedColumns != null) {
                sb.append(", refColumns=").append(referencedColumns);
            }
        }
        if (checkClause != null) {
            sb.append(", check=").append(checkClause);
        }
        sb.append("]");
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ConstraintMetadata that = (ConstraintMetadata) o;
        return type.equalsIgnoreCase(that.type) &&
                name.equalsIgnoreCase(that.name) &&
                columns.equals(that.columns) &&
                Objects.equals(referencedTable, that.referencedTable) &&
                Objects.equals(referencedColumns, that.referencedColumns);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type.toLowerCase(), name.toLowerCase(), columns);
    }
}