// ============================================================================
// TriggerMetadata.java
// ============================================================================
package com.schemadiff.model;

/**
 * Represents a PostgreSQL trigger metadata.
 */
public class TriggerMetadata {
    private String name;
    private String tableName;
    private String timing; // BEFORE, AFTER, INSTEAD OF
    private String event; // INSERT, UPDATE, DELETE (can be multiple)
    private String level; // ROW or STATEMENT
    private String functionName; // The trigger function that gets executed
    private String condition; // WHEN condition (if any)
    private Integer executionOrder; // For ordering multiple triggers

    public TriggerMetadata() {
    }

    public TriggerMetadata(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getTiming() {
        return timing;
    }

    public void setTiming(String timing) {
        this.timing = timing;
    }

    public String getEvent() {
        return event;
    }

    public void setEvent(String event) {
        this.event = event;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public String getFunctionName() {
        return functionName;
    }

    public void setFunctionName(String functionName) {
        this.functionName = functionName;
    }

    public String getCondition() {
        return condition;
    }

    public void setCondition(String condition) {
        this.condition = condition;
    }

    public Integer getExecutionOrder() {
        return executionOrder;
    }

    public void setExecutionOrder(Integer executionOrder) {
        this.executionOrder = executionOrder;
    }

    @Override
    public String toString() {
        return String.format("TriggerMetadata[name=%s, table=%s, timing=%s, event=%s, level=%s, function=%s]",
                name, tableName, timing, event, level, functionName);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TriggerMetadata that = (TriggerMetadata) o;
        if (name != null ? !name.equalsIgnoreCase(that.name) : that.name != null) return false;
        return tableName != null ? tableName.equalsIgnoreCase(that.tableName) : that.tableName == null;
    }

    @Override
    public int hashCode() {
        int result = name != null ? name.toLowerCase().hashCode() : 0;
        result = 31 * result + (tableName != null ? tableName.toLowerCase().hashCode() : 0);
        return result;
    }
}

