package com.schemadiff.model;

public class ColumnMetadata {
    private String name;
    private String dataType;
    private boolean notNull;
    private String defaultValue;

    public ColumnMetadata(String name, String dataType, boolean notNull, String defaultValue) {
        this.name = name;
        this.dataType = dataType;
        this.notNull = notNull;
        this.defaultValue = defaultValue;
    }

    public String getName() { return name; }
    public String getDataType() { return dataType; }
    public boolean isNotNull() { return notNull; }
    public String getDefaultValue() { return defaultValue; }
}