

// ============================================================================
// ColumnMetadata.java
// ============================================================================
package com.schemadiff.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Represents metadata for a table column including data type, nullability,
 * default value, and MySQL-specific properties.
 */
public class ColumnMetadata {
    private String name;
    private String dataType;
    private boolean notNull;
    private String defaultValue;

    // MySQL-specific properties
    private int ordinalPosition;
    private String columnType;  // Full type like 'int(11) unsigned'
    private boolean autoIncrement;
    private boolean unsigned;
    private String comment;
    private String characterSet;
    private String collation;

    public ColumnMetadata(String name, String dataType, boolean notNull, String defaultValue) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Column name cannot be null or empty");
        }
        if (dataType == null || dataType.trim().isEmpty()) {
            throw new IllegalArgumentException("Data type cannot be null or empty");
        }
        this.name = name;
        this.dataType = dataType;
        this.notNull = notNull;
        this.defaultValue = defaultValue;
    }

    // Primary getters
    public String getName() {
        return name;
    }

    public String getDataType() {
        return dataType;
    }

    public boolean isNotNull() {
        return notNull;
    }

    public String getDefaultValue() {
        return defaultValue;
    }

    // Extended property getters and setters
    public int getOrdinalPosition() {
        return ordinalPosition;
    }

    public void setOrdinalPosition(int ordinalPosition) {
        this.ordinalPosition = ordinalPosition;
    }

    public String getColumnType() {
        return columnType;
    }

    public void setColumnType(String columnType) {
        this.columnType = columnType;
    }

    public boolean isAutoIncrement() {
        return autoIncrement;
    }

    public void setAutoIncrement(boolean autoIncrement) {
        this.autoIncrement = autoIncrement;
    }

    public boolean isUnsigned() {
        return unsigned;
    }

    public void setUnsigned(boolean unsigned) {
        this.unsigned = unsigned;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public String getCharacterSet() {
        return characterSet;
    }

    public void setCharacterSet(String characterSet) {
        this.characterSet = characterSet;
    }

    public String getCollation() {
        return collation;
    }

    public void setCollation(String collation) {
        this.collation = collation;
    }

    @Override
    public String toString() {
        return String.format("ColumnMetadata[name=%s, type=%s, notNull=%s, default=%s]",
                name, dataType, notNull, defaultValue);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ColumnMetadata that = (ColumnMetadata) o;
        return notNull == that.notNull &&
                autoIncrement == that.autoIncrement &&
                unsigned == that.unsigned &&
                name.equalsIgnoreCase(that.name) &&
                dataType.equalsIgnoreCase(that.dataType) &&
                Objects.equals(defaultValue, that.defaultValue) &&
                Objects.equals(columnType, that.columnType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name.toLowerCase(), dataType.toLowerCase(), notNull, defaultValue);
    }
}