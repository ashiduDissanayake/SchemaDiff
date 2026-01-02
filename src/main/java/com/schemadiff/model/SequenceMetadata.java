// ============================================================================
// SequenceMetadata.java
// ============================================================================
package com.schemadiff.model;

/**
 * Represents a PostgreSQL sequence metadata.
 */
public class SequenceMetadata {
    private String name;
    private String dataType;
    private Long startValue;
    private Long incrementBy;
    private Long minValue;
    private Long maxValue;
    private Long cacheSize;
    private Boolean cycle;
    private String ownedBy; // The column that owns this sequence (e.g., "table_name.column_name")

    public SequenceMetadata() {
    }

    public SequenceMetadata(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDataType() {
        return dataType;
    }

    public void setDataType(String dataType) {
        this.dataType = dataType;
    }

    public Long getStartValue() {
        return startValue;
    }

    public void setStartValue(Long startValue) {
        this.startValue = startValue;
    }

    public Long getIncrementBy() {
        return incrementBy;
    }

    public void setIncrementBy(Long incrementBy) {
        this.incrementBy = incrementBy;
    }

    public Long getMinValue() {
        return minValue;
    }

    public void setMinValue(Long minValue) {
        this.minValue = minValue;
    }

    public Long getMaxValue() {
        return maxValue;
    }

    public void setMaxValue(Long maxValue) {
        this.maxValue = maxValue;
    }

    public Long getCacheSize() {
        return cacheSize;
    }

    public void setCacheSize(Long cacheSize) {
        this.cacheSize = cacheSize;
    }

    public Boolean getCycle() {
        return cycle;
    }

    public void setCycle(Boolean cycle) {
        this.cycle = cycle;
    }

    public String getOwnedBy() {
        return ownedBy;
    }

    public void setOwnedBy(String ownedBy) {
        this.ownedBy = ownedBy;
    }

    @Override
    public String toString() {
        return String.format("SequenceMetadata[name=%s, dataType=%s, start=%d, increment=%d, ownedBy=%s]",
                name, dataType, startValue, incrementBy, ownedBy);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SequenceMetadata that = (SequenceMetadata) o;
        return name != null ? name.equalsIgnoreCase(that.name) : that.name == null;
    }

    @Override
    public int hashCode() {
        return name != null ? name.toLowerCase().hashCode() : 0;
    }
}

