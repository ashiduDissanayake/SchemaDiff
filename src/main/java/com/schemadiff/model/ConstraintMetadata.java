package com.schemadiff. model;

import java.util. List;

public class ConstraintMetadata {
    private String name;
    private String type;
    private List<String> columns;
    private String referencedTable;
    private String signature;

    public ConstraintMetadata(String name, String type, List<String> columns, String referencedTable) {
        this.name = name;
        this.type = type;
        this.columns = columns;
        this.referencedTable = referencedTable;
    }

    public String getType() { return type; }
    public List<String> getColumns() { return columns; }
    public String getReferencedTable() { return referencedTable; }
    public String getSignature() { return signature; }
    public void setSignature(String signature) { this.signature = signature; }
}