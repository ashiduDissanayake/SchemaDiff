# PostgreSQL Extractor - Sequences, Functions, and Triggers Support

## Overview

The PostgreSQL extractor now fully supports extraction of **Sequences**, **Functions**, and **Triggers** in addition to the existing features (tables, columns, indexes, and constraints). This brings feature parity with the complete PostgreSQL schema and enables comprehensive schema analysis.

## New Features

### 1. Sequences
PostgreSQL sequences are used for auto-incrementing columns and generating unique numbers.

**Extracted Metadata:**
- Sequence name
- Data type (bigint, integer, etc.)
- Start value
- Increment by
- Minimum value
- Maximum value
- Cache size
- Cycle option (whether sequence wraps around)
- Owned by (which table.column owns this sequence)

**Example:**
```sql
CREATE SEQUENCE user_id_seq
    START WITH 1
    INCREMENT BY 1
    MINVALUE 1
    MAXVALUE 999999999
    CACHE 1;
```

### 2. Functions
PostgreSQL functions (stored procedures) are extracted with their complete definitions.

**Extracted Metadata:**
- Function name
- Schema name
- Return type
- Programming language (plpgsql, sql, etc.)
- Function definition (complete source code)
- Arguments (parameter signature)
- Volatility (VOLATILE, STABLE, IMMUTABLE)
- Strictness (returns NULL on NULL input)
- Security type (DEFINER or INVOKER)

**Example:**
```sql
CREATE OR REPLACE FUNCTION update_modified_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.TIME_STAMP = now();
    RETURN NEW;
END;
$$ LANGUAGE 'plpgsql';
```

### 3. Triggers
PostgreSQL triggers are extracted with their event details and associated functions.

**Extracted Metadata:**
- Trigger name
- Table name
- Timing (BEFORE, AFTER, INSTEAD OF)
- Event (INSERT, UPDATE, DELETE)
- Level (ROW or STATEMENT)
- Function name (the trigger function to execute)
- Condition (WHEN clause if present)

**Example:**
```sql
CREATE TRIGGER TIME_STAMP 
AFTER UPDATE ON AM_GW_API_ARTIFACTS 
FOR EACH ROW 
EXECUTE PROCEDURE update_modified_column();
```

## Usage

### Basic Usage

```java
PostgresExtractor extractor = new PostgresExtractor("public");
DatabaseMetadata metadata = extractor.extract(connection);

// Access sequences
Map<String, SequenceMetadata> sequences = metadata.getSequences();
for (SequenceMetadata seq : sequences.values()) {
    System.out.println("Sequence: " + seq.getName());
    System.out.println("  Start: " + seq.getStartValue());
    System.out.println("  Increment: " + seq.getIncrementBy());
    System.out.println("  Owned by: " + seq.getOwnedBy());
}

// Access functions
Map<String, FunctionMetadata> functions = metadata.getFunctions();
for (FunctionMetadata func : functions.values()) {
    System.out.println("Function: " + func.getSignature());
    System.out.println("  Language: " + func.getLanguage());
    System.out.println("  Return type: " + func.getReturnType());
}

// Access triggers
Map<String, TriggerMetadata> triggers = metadata.getTriggers();
for (TriggerMetadata trigger : triggers.values()) {
    System.out.println("Trigger: " + trigger.getName());
    System.out.println("  Table: " + trigger.getTableName());
    System.out.println("  Timing: " + trigger.getTiming());
    System.out.println("  Event: " + trigger.getEvent());
    System.out.println("  Function: " + trigger.getFunctionName());
}
```

### Command Line Usage

```bash
java -jar schemadiff2-2.0.0.jar \
    --db-type postgres \
    --jdbc-url jdbc:postgresql://localhost:5432/mydb \
    --username myuser \
    --password mypass \
    --schema public
```

## Model Classes

### SequenceMetadata
```java
public class SequenceMetadata {
    private String name;
    private String dataType;
    private Long startValue;
    private Long incrementBy;
    private Long minValue;
    private Long maxValue;
    private Long cacheSize;
    private Boolean cycle;
    private String ownedBy;
    // ... getters and setters
}
```

### FunctionMetadata
```java
public class FunctionMetadata {
    private String name;
    private String schema;
    private String returnType;
    private String language;
    private String definition;
    private String arguments;
    private String volatility;
    private Boolean isStrict;
    private String securityType;
    // ... getters and setters
}
```

### TriggerMetadata
```java
public class TriggerMetadata {
    private String name;
    private String tableName;
    private String timing;
    private String event;
    private String level;
    private String functionName;
    private String condition;
    // ... getters and setters
}
```

## DatabaseMetadata Updates

The `DatabaseMetadata` class now includes:

```java
public class DatabaseMetadata {
    private Map<String, SequenceMetadata> sequences;
    private Map<String, FunctionMetadata> functions;
    private Map<String, TriggerMetadata> triggers;
    
    public void addSequence(SequenceMetadata sequence) { }
    public void addFunction(FunctionMetadata function) { }
    public void addTrigger(TriggerMetadata trigger) { }
    
    public Map<String, SequenceMetadata> getSequences() { }
    public Map<String, FunctionMetadata> getFunctions() { }
    public Map<String, TriggerMetadata> getTriggers() { }
}
```

## Extraction Process

The PostgreSQL extractor now performs the following steps:

1. **Extract Tables** - Basic table metadata
2. **Extract Columns** - Column definitions with data types
3. **Extract Constraints** - Primary keys, foreign keys, check, unique
4. **Extract Indexes** - All indexes with types (BTREE, HASH, GIN, GIST, etc.)
5. **Extract Sequences** ⭐ NEW - All sequences with ownership info
6. **Extract Functions** ⭐ NEW - All functions and procedures
7. **Extract Triggers** ⭐ NEW - All triggers with event details

## Performance

The extraction process uses:
- Prepared statements with query timeout (300 seconds)
- Transaction isolation (REPEATABLE READ)
- Read-only transactions
- Automatic retry on transient failures
- Progress tracking callbacks

## Logging

The extractor provides detailed logging:

```
INFO  PostgreSQL schema extraction completed in 1234ms
      - Tables: 50
      - Indexes: 120
      - Constraints: 85
      - Sequences: 15      ⭐ NEW
      - Functions: 8       ⭐ NEW
      - Triggers: 12       ⭐ NEW
```

## Testing

Run the test suite:

```bash
mvn21 test -Dtest=PostgresExtractorTest
```

Test with a real database:

```bash
./test_postgres_features.sh
```

## PostgreSQL Version Support

- **Minimum:** PostgreSQL 9.6+
- **Recommended:** PostgreSQL 12+
- **Tested:** PostgreSQL 13, 14, 15, 16

## Comparison with MySQL

| Feature          | MySQL Extractor | PostgreSQL Extractor |
|------------------|-----------------|---------------------|
| Tables           | ✅              | ✅                  |
| Columns          | ✅              | ✅                  |
| Indexes          | ✅              | ✅                  |
| Constraints      | ✅              | ✅                  |
| Auto-increment   | ✅              | ✅ (via sequences)  |
| Sequences        | ❌              | ✅ ⭐ NEW          |
| Functions        | ❌              | ✅ ⭐ NEW          |
| Triggers         | ❌              | ✅ ⭐ NEW          |

## Examples from WSO2 AM Database

The WSO2 API Manager PostgreSQL schema (`apimgt/postgresql.sql`) contains:

- **20+ sequences** (e.g., `IDN_OAUTH_CONSUMER_APPS_PK_SEQ`)
- **1+ function** (`update_modified_column()`)
- **1+ trigger** (`TIME_STAMP` trigger on `AM_GW_API_ARTIFACTS`)

All of these are now fully extracted and available for schema comparison and analysis.

## Migration Notes

If you're upgrading from a previous version:

1. The `DatabaseMetadata` class now has three additional collections
2. The extraction process includes three additional phases
3. Logging output includes counts for sequences, functions, and triggers
4. No breaking changes to existing APIs

## Future Enhancements

Potential future additions:
- Views extraction
- Materialized views
- User-defined types
- Partitions
- Policies
- Extensions

## See Also

- [PostgreSQL Quick Start Guide](POSTGRES_QUICK_START.md)
- [PostgreSQL Implementation Summary](POSTGRESQL_IMPLEMENTATION_COMPLETE.md)
- [README Extractors](README_EXTRACTORS.md)

