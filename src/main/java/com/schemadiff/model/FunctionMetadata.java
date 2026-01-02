// ============================================================================
// FunctionMetadata.java
// ============================================================================
package com.schemadiff.model;

/**
 * Represents a PostgreSQL function/procedure metadata.
 */
public class FunctionMetadata {
    private String name;
    private String schema;
    private String returnType;
    private String language;
    private String definition; // Function body/source code
    private String arguments; // Function parameter signature
    private String volatility; // VOLATILE, STABLE, IMMUTABLE
    private Boolean isStrict; // Returns NULL on NULL input
    private String securityType; // DEFINER or INVOKER

    public FunctionMetadata() {
    }

    public FunctionMetadata(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }

    public String getReturnType() {
        return returnType;
    }

    public void setReturnType(String returnType) {
        this.returnType = returnType;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getDefinition() {
        return definition;
    }

    public void setDefinition(String definition) {
        this.definition = definition;
    }

    public String getArguments() {
        return arguments;
    }

    public void setArguments(String arguments) {
        this.arguments = arguments;
    }

    public String getVolatility() {
        return volatility;
    }

    public void setVolatility(String volatility) {
        this.volatility = volatility;
    }

    public Boolean getIsStrict() {
        return isStrict;
    }

    public void setIsStrict(Boolean isStrict) {
        this.isStrict = isStrict;
    }

    public String getSecurityType() {
        return securityType;
    }

    public void setSecurityType(String securityType) {
        this.securityType = securityType;
    }

    /**
     * Returns a unique signature for this function including name and arguments.
     */
    public String getSignature() {
        return name + "(" + (arguments != null ? arguments : "") + ")";
    }

    @Override
    public String toString() {
        return String.format("FunctionMetadata[name=%s, args=%s, returnType=%s, language=%s]",
                name, arguments, returnType, language);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FunctionMetadata that = (FunctionMetadata) o;
        return getSignature().equalsIgnoreCase(that.getSignature());
    }

    @Override
    public int hashCode() {
        return getSignature().toLowerCase().hashCode();
    }
}

