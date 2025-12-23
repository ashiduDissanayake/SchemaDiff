// ============================================================================
// DatabaseMetadata.java
// ============================================================================
package com.schemadiff.model;

import java.util.*;

/**
 * Represents complete database schema metadata including tables,
 * columns, constraints, and indexes.
 */
public class DatabaseMetadata {
    private String schemaName;
    private Map<String, TableMetadata> tables = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private long extractionTimestamp;

    public DatabaseMetadata() {
        this.extractionTimestamp = System.currentTimeMillis();
    }

    public void addTable(TableMetadata table) {
        if (table == null || table.getName() == null) {
            throw new IllegalArgumentException("Table and table name cannot be null");
        }
        tables.put(table.getName(), table);
    }

    public TableMetadata getTable(String name) {
        return tables.get(name);
    }

    public Map<String, TableMetadata> getTables() {
        return Collections.unmodifiableMap(tables);
    }

    public Set<String> getTableNames() {
        return tables.keySet();
    }

    public String getSchemaName() {
        return schemaName;
    }

    public void setSchemaName(String schemaName) {
        this.schemaName = schemaName;
    }

    public long getExtractionTimestamp() {
        return extractionTimestamp;
    }

    public void setExtractionTimestamp(long extractionTimestamp) {
        this.extractionTimestamp = extractionTimestamp;
    }

    @Override
    public String toString() {
        return String.format("DatabaseMetadata[schema=%s, tables=%d]",
                schemaName, tables.size());
    }
}