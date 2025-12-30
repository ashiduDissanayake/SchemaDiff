

// ============================================================================
// TableMetadata.java
// ============================================================================
package com.schemadiff.model;

import java.sql.Timestamp;
import java.util.*;

/**
 * Represents metadata for a database table including columns, constraints,
 * indexes, and table properties.
 */
public class TableMetadata {
    private String name;
    private List<ColumnMetadata> columns = new ArrayList<>();
    private List<ConstraintMetadata> constraints = new ArrayList<>();
    private List<IndexMetadata> indexes = new ArrayList<>();

    // MySQL-specific properties
    private String engine;
    private String collation;
    private String comment;
    private Timestamp createTime;
    private Timestamp updateTime;
    private Long tableRows;
    private Long avgRowLength;
    private Long dataLength;

    public TableMetadata(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Table name cannot be null or empty");
        }
        this.name = name;
    }

    public void addColumn(ColumnMetadata column) {
        if (column == null) {
            throw new IllegalArgumentException("Column cannot be null");
        }
        columns.add(column);
    }

    public void addConstraint(ConstraintMetadata constraint) {
        if (constraint == null) {
            throw new IllegalArgumentException("Constraint cannot be null");
        }
        constraints.add(constraint);
    }

    public void addIndex(IndexMetadata index) {
        if (index == null) {
            throw new IllegalArgumentException("Index cannot be null");
        }
        indexes.add(index);
    }

    public ColumnMetadata getColumn(String name) {
        if (name == null) {
            return null;
        }
        return columns.stream()
                .filter(c -> c.getName().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    }

    public ConstraintMetadata getConstraint(String name) {
        if (name == null) {
            return null;
        }
        return constraints.stream()
                .filter(c -> c.getName().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    }

    public IndexMetadata getIndex(String name) {
        if (name == null) {
            return null;
        }
        return indexes.stream()
                .filter(i -> i.getName().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    }

    // Primary getters
    public String getName() {
        return name;
    }

    public List<ColumnMetadata> getColumns() {
        return Collections.unmodifiableList(columns);
    }

    public List<ConstraintMetadata> getConstraints() {
        return Collections.unmodifiableList(constraints);
    }

    public List<IndexMetadata> getIndexes() {
        return Collections.unmodifiableList(indexes);
    }

    // Extended property getters and setters
    public String getEngine() {
        return engine;
    }

    public void setEngine(String engine) {
        this.engine = engine;
    }

    public String getCollation() {
        return collation;
    }

    public void setCollation(String collation) {
        this.collation = collation;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public Timestamp getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Timestamp createTime) {
        this.createTime = createTime;
    }

    public Timestamp getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Timestamp updateTime) {
        this.updateTime = updateTime;
    }

    public Long getTableRows() {
        return tableRows;
    }

    public void setTableRows(Long tableRows) {
        this.tableRows = tableRows;
    }

    public Long getAvgRowLength() {
        return avgRowLength;
    }

    public void setAvgRowLength(Long avgRowLength) {
        this.avgRowLength = avgRowLength;
    }

    public Long getDataLength() {
        return dataLength;
    }

    public void setDataLength(Long dataLength) {
        this.dataLength = dataLength;
    }

    @Override
    public String toString() {
        return String.format("TableMetadata[name=%s, columns=%d, constraints=%d, indexes=%d, engine=%s]",
                name, columns.size(), constraints.size(), indexes.size(), engine);
    }
}