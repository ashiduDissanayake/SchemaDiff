package com.schemadiff.model;

import java.util.*;

public class TableMetadata {
    private String name;
    private List<ColumnMetadata> columns = new ArrayList<>();
    private List<ConstraintMetadata> constraints = new ArrayList<>();
    private List<IndexMetadata> indexes = new ArrayList<>();

    public TableMetadata(String name) {
        this.name = name;
    }

    public void addColumn(ColumnMetadata column) {
        columns.add(column);
    }

    public void addConstraint(ConstraintMetadata constraint) {
        constraints. add(constraint);
    }

    public void addIndex(IndexMetadata index) {
        indexes.add(index);
    }

    public ColumnMetadata getColumn(String name) {
        return columns.stream()
            .filter(c -> c.getName().equalsIgnoreCase(name))
            .findFirst()
            .orElse(null);
    }

    // Getters
    public String getName() { return name; }
    public List<ColumnMetadata> getColumns() { return columns; }
    public List<ConstraintMetadata> getConstraints() { return constraints; }
    public List<IndexMetadata> getIndexes() { return indexes; }
}