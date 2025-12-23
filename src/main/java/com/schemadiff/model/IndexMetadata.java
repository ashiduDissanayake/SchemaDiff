

// ============================================================================
// IndexMetadata.java
// ============================================================================
package com.schemadiff.model;

import java.util.*;

/**
 * Represents metadata for database indexes including columns, uniqueness,
 * and MySQL-specific index properties.
 */
public class IndexMetadata {
    private String name;
    private List<String> columns;
    private boolean unique;

    // MySQL-specific properties
    private String indexType;  // BTREE, HASH, FULLTEXT, SPATIAL
    private String comment;

    public IndexMetadata(String name, List<String> columns, boolean unique) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Index name cannot be null or empty");
        }
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("Index columns cannot be null or empty");
        }
        this.name = name;
        this.columns = new ArrayList<>(columns);
        this.unique = unique;
    }

    // Primary getters
    public String getName() {
        return name;
    }

    public List<String> getColumns() {
        return Collections.unmodifiableList(columns);
    }

    public boolean isUnique() {
        return unique;
    }

    // Extended getters and setters
    public String getIndexType() {
        return indexType;
    }

    public void setIndexType(String indexType) {
        this.indexType = indexType;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    @Override
    public String toString() {
        return String.format("IndexMetadata[name=%s, columns=%s, unique=%s, type=%s]",
                name, columns, unique, indexType);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        IndexMetadata that = (IndexMetadata) o;
        return unique == that.unique &&
                name.equalsIgnoreCase(that.name) &&
                columns.equals(that.columns) &&
                Objects.equals(indexType, that.indexType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name.toLowerCase(), columns, unique);
    }
}