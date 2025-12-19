package com.schemadiff.model;

import java.util.*;

public class DatabaseMetadata {
    private Map<String, TableMetadata> tables = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    public void addTable(TableMetadata table) {
        tables.put(table.getName(), table);
    }

    public TableMetadata getTable(String name) {
        return tables.get(name);
    }

    public Set<String> getTableNames() {
        return tables.keySet();
    }
}