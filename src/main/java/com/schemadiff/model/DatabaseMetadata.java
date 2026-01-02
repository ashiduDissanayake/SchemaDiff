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
    private Map<String, SequenceMetadata> sequences = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private Map<String, FunctionMetadata> functions = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private Map<String, TriggerMetadata> triggers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
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

    public void addSequence(SequenceMetadata sequence) {
        if (sequence == null || sequence.getName() == null) {
            throw new IllegalArgumentException("Sequence and sequence name cannot be null");
        }
        sequences.put(sequence.getName(), sequence);
    }

    public SequenceMetadata getSequence(String name) {
        return sequences.get(name);
    }

    public Map<String, SequenceMetadata> getSequences() {
        return Collections.unmodifiableMap(sequences);
    }

    public void addFunction(FunctionMetadata function) {
        if (function == null || function.getName() == null) {
            throw new IllegalArgumentException("Function and function name cannot be null");
        }
        functions.put(function.getSignature(), function);
    }

    public FunctionMetadata getFunction(String signature) {
        return functions.get(signature);
    }

    public Map<String, FunctionMetadata> getFunctions() {
        return Collections.unmodifiableMap(functions);
    }

    public void addTrigger(TriggerMetadata trigger) {
        if (trigger == null || trigger.getName() == null) {
            throw new IllegalArgumentException("Trigger and trigger name cannot be null");
        }
        triggers.put(trigger.getName(), trigger);
    }

    public TriggerMetadata getTrigger(String name) {
        return triggers.get(name);
    }

    public Map<String, TriggerMetadata> getTriggers() {
        return Collections.unmodifiableMap(triggers);
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
        return String.format("DatabaseMetadata[schema=%s, tables=%d, sequences=%d, functions=%d, triggers=%d]",
                schemaName, tables.size(), sequences.size(), functions.size(), triggers.size());
    }
}