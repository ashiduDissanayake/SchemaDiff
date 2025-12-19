package com.schemadiff. model;

import java.util.List;

public class IndexMetadata {
    private String name;
    private List<String> columns;
    private boolean unique;

    public IndexMetadata(String name, List<String> columns, boolean unique) {
        this.name = name;
        this.columns = columns;
        this.unique = unique;
    }

    public String getName() { return name; }
    public List<String> getColumns() { return columns; }
    public boolean isUnique() { return unique; }
}