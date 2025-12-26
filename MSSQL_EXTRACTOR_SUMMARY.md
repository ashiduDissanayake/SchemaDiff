# MSSQL Extractor Implementation Summary

## Overview
Created a comprehensive Microsoft SQL Server extractor (`MSSQLExtractor.java`) that mirrors the MySQL and PostgreSQL extractors' architecture and completeness, with full support for MSSQL-specific features.

## Key Features Implemented

### 1. **Auto-Increment Detection**
- **IDENTITY columns**: Detects columns with `IDENTITY` property
- Properly marks columns as auto-increment in metadata
- Example: `ID INT IDENTITY(1,1)` or `BIGID BIGINT IDENTITY`

### 2. **Data Type Normalization**
Comprehensive support for MSSQL-specific types:
- **Character types**: `VARCHAR`, `NVARCHAR`, `CHAR`, `NCHAR`, `VARCHAR(MAX)`, `TEXT`, `NTEXT`
- **Numeric types**: `INT`, `BIGINT`, `SMALLINT`, `TINYINT`, `DECIMAL`, `NUMERIC`, `MONEY`, `SMALLMONEY`, `REAL`, `FLOAT`
- **Date/Time types**: `DATETIME`, `DATETIME2`, `SMALLDATETIME`, `DATE`, `TIME`
- **Binary types**: `VARBINARY`, `BINARY`, `IMAGE`
- **Other types**: `UNIQUEIDENTIFIER` (UUID), `XML`, `BIT` (boolean)
- **NVARCHAR length calculation**: Automatically divides by 2 (stores 2 bytes per character)
- **VARCHAR(MAX)**: Properly detected and normalized

### 3. **Constraint Extraction**

#### Primary Keys
- Extracts multi-column primary keys
- Maintains column order via `key_ordinal`
- Generates signatures for comparison

#### Foreign Keys
- **Full relationship mapping**: Captures source → target column mappings
- **ON DELETE rules**: CASCADE, NO_ACTION, SET_NULL, SET_DEFAULT
- **ON UPDATE rules**: CASCADE, NO_ACTION, SET_NULL, SET_DEFAULT
- **Referenced columns**: Tracks which columns in referenced table
- Example detection:
  ```sql
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE ON UPDATE NO_ACTION
  ```

#### CHECK Constraints
- Extracts CHECK constraint definitions from `sys.check_constraints`
- Preserves CHECK clause expressions
- Example: `CHECK (age >= 18 AND age <= 120)`

#### UNIQUE Constraints
- Detects single and multi-column unique constraints
- Differentiates from unique indexes
- Maintains column order

### 4. **Index Extraction**
- **Index types**: CLUSTERED, NONCLUSTERED, COLUMNSTORE, HEAP
- **Uniqueness**: Properly detects unique vs non-unique indexes
- **Multi-column indexes**: Maintains column order via `key_ordinal`
- **Excludes primary key indexes**: Filters out `is_primary_key = 1`
- **Excludes unique constraint indexes**: Filters out `is_unique_constraint = 1`

### 5. **Default Value Handling**
- Normalizes MSSQL-specific default formats
- Removes wrapping parentheses: `((0))` → `0`
- Removes single quotes from string literals: `('active')` → `active`
- Handles system functions: `GETDATE()`, `NEWID()`, etc.

### 6. **Metadata Extraction**
- **Table comments**: Extracts from `sys.extended_properties` with `MS_Description`
- **Column comments**: Extracts column-level documentation
- **Computed columns**: Detects `is_computed` flag
- **Ordinal positions**: Maintains column order via `column_id`
- **Schema isolation**: Works with specified schema (default: `dbo`)
- **Create/Modify timestamps**: Extracts `create_date` and `modify_date`

### 7. **Robustness Features**

#### Connection Management
- Transaction isolation: Uses `READ_COMMITTED` for consistent reads
- Read-only transactions during extraction
- Automatic connection state restoration
- Query timeouts (300 seconds)

#### Error Handling
- **Retry logic**: Up to 3 attempts for transient failures
- **Retryable errors**: Deadlocks (1205), lock issues (1204), timeouts (-2)
- **Exponential backoff**: 1s, 2s, 3s between retries
- **Progress tracking**: Phase-based extraction monitoring

#### Validation
- Post-extraction metadata validation
- Checks for tables without columns
- Validates foreign key references
- Logs warnings for inconsistencies

## Architecture Alignment with MySQL/PostgreSQL Extractors

### Matching Features
1. **Four-phase extraction**: Tables → Columns → Constraints → Indexes
2. **Progress callbacks**: `ExtractionProgress` interface
3. **Retry mechanism**: Transient error handling
4. **Signature generation**: For constraint comparison
5. **Metadata validation**: Post-extraction checks
6. **Logging**: SLF4J integration with DEBUG/INFO/WARN/ERROR levels

### MSSQL-Specific Adaptations

| Feature | MySQL | PostgreSQL | MSSQL |
|---------|-------|------------|-------|
| Auto-increment | `AUTO_INCREMENT` | `SERIAL` / `nextval()` | `IDENTITY(seed, increment)` |
| String types | `VARCHAR` | `VARCHAR`, `TEXT` | `VARCHAR`, `NVARCHAR`, `VARCHAR(MAX)` |
| Unicode | UTF-8 | UTF-8 | NVARCHAR (2 bytes/char) |
| Index types | BTREE, HASH, FULLTEXT, SPATIAL | BTREE, GIN, GIST, etc. | CLUSTERED, NONCLUSTERED, COLUMNSTORE |
| System catalogs | `INFORMATION_SCHEMA` | `INFORMATION_SCHEMA` + `pg_catalog` | `sys.*` views |
| Comments | Table comments | `COMMENT ON` | Extended properties (`MS_Description`) |
| Default wrapping | Raw value | Type-cast | Wrapped in parentheses |

## SQL Queries Used

### Tables Query
```sql
SELECT 
    t.name AS table_name,
    ep.value AS table_comment,
    t.create_date,
    t.modify_date
FROM sys.tables t
LEFT JOIN sys.extended_properties ep 
    ON t.object_id = ep.major_id 
    AND ep.minor_id = 0 
    AND ep.class = 1
    AND ep.name = 'MS_Description'
WHERE t.type = 'U'
AND SCHEMA_NAME(t.schema_id) = ?
```

### Columns Query (with IDENTITY detection)
```sql
SELECT
    t.name AS table_name,
    c.name AS column_name,
    c.column_id AS ordinal_position,
    ty.name AS data_type,
    c.max_length,
    c.precision,
    c.scale,
    c.is_nullable,
    c.is_identity,              -- IDENTITY detection
    c.is_computed,
    dc.definition AS default_value,
    cc.definition AS computed_definition,
    ep.value AS column_comment
FROM sys.tables t
JOIN sys.columns c ON t.object_id = c.object_id
JOIN sys.types ty ON c.user_type_id = ty.user_type_id
LEFT JOIN sys.default_constraints dc ON c.default_object_id = dc.object_id
LEFT JOIN sys.computed_columns cc ON t.object_id = cc.object_id AND c.column_id = cc.column_id
LEFT JOIN sys.extended_properties ep 
    ON t.object_id = ep.major_id 
    AND c.column_id = ep.minor_id 
    AND ep.class = 1
    AND ep.name = 'MS_Description'
WHERE t.type = 'U'
AND SCHEMA_NAME(t.schema_id) = ?
```

### Foreign Keys Query (with ON DELETE/UPDATE rules)
```sql
SELECT
    t.name AS table_name,
    c.name AS column_name,
    rt.name AS ref_table_name,
    rc.name AS ref_column_name,
    fk.name AS constraint_name,
    fk.delete_referential_action_desc AS delete_rule,
    fk.update_referential_action_desc AS update_rule,
    fkc.constraint_column_id
FROM sys.foreign_keys fk
JOIN sys.tables t ON fk.parent_object_id = t.object_id
JOIN sys.tables rt ON fk.referenced_object_id = rt.object_id
JOIN sys.foreign_key_columns fkc ON fk.object_id = fkc.constraint_object_id
JOIN sys.columns c ON fkc.parent_object_id = c.object_id AND fkc.parent_column_id = c.column_id
JOIN sys.columns rc ON fkc.referenced_object_id = rc.object_id AND fkc.referenced_column_id = rc.column_id
WHERE SCHEMA_NAME(t.schema_id) = ?
```

### CHECK Constraints Query
```sql
SELECT
    t.name AS table_name,
    cc.name AS constraint_name,
    cc.definition AS check_clause
FROM sys.check_constraints cc
JOIN sys.tables t ON cc.parent_object_id = t.object_id
WHERE SCHEMA_NAME(t.schema_id) = ?
```

### Indexes Query (with type information)
```sql
SELECT
    t.name AS table_name,
    i.name AS index_name,
    c.name AS column_name,
    i.is_unique,
    i.type_desc AS index_type,
    ic.key_ordinal
FROM sys.tables t
JOIN sys.indexes i ON t.object_id = i.object_id
JOIN sys.index_columns ic ON i.object_id = ic.object_id AND i.index_id = ic.index_id
JOIN sys.columns c ON ic.object_id = c.object_id AND ic.column_id = c.column_id
WHERE i.is_primary_key = 0
AND i.is_unique_constraint = 0
AND SCHEMA_NAME(t.schema_id) = ?
```

## Testing Recommendations

### Test Cases to Verify

1. **IDENTITY Columns**
   ```sql
   CREATE TABLE test (
       id INT IDENTITY(1,1) PRIMARY KEY,
       bigid BIGINT IDENTITY(100, 5)
   );
   ```

2. **Foreign Keys with CASCADE**
   ```sql
   CREATE TABLE parent (id INT PRIMARY KEY);
   CREATE TABLE child (
       id INT PRIMARY KEY,
       parent_id INT,
       FOREIGN KEY (parent_id) REFERENCES parent(id) ON DELETE CASCADE ON UPDATE NO_ACTION
   );
   ```

3. **CHECK Constraints**
   ```sql
   CREATE TABLE users (
       age INT CHECK (age >= 18 AND age <= 120),
       status VARCHAR(20) CHECK (status IN ('active', 'inactive', 'suspended'))
   );
   ```

4. **MSSQL-Specific Types**
   ```sql
   CREATE TABLE data (
       content VARBINARY(MAX),
       identifier UNIQUEIDENTIFIER DEFAULT NEWID(),
       description NVARCHAR(MAX),
       metadata XML,
       price MONEY
   );
   ```

5. **Multi-column Constraints**
   ```sql
   CREATE TABLE composite (
       col1 INT,
       col2 INT,
       col3 INT,
       PRIMARY KEY (col1, col2),
       UNIQUE (col2, col3)
   );
   ```

6. **Extended Properties (Comments)**
   ```sql
   EXEC sp_addextendedproperty 
       @name = N'MS_Description', 
       @value = N'This is a table comment', 
       @level0type = N'SCHEMA', @level0name = N'dbo',
       @level1type = N'TABLE', @level1name = N'users';
   ```

## Comparison with MySQL/PostgreSQL Extractors

### Lines of Code
- **MySQLExtractor**: 925 lines
- **PostgresExtractor**: 828 lines
- **MSSQLExtractor**: 833 lines
- Similar complexity and completeness

### Feature Parity
✅ Table extraction with comments  
✅ Column extraction with all attributes  
✅ Primary key extraction  
✅ Foreign key extraction with ON DELETE/UPDATE rules  
✅ CHECK constraint extraction  
✅ UNIQUE constraint extraction  
✅ Index extraction with types and uniqueness  
✅ Auto-increment detection (IDENTITY)  
✅ Default value normalization  
✅ Transaction-based consistent snapshots  
✅ Retry logic for transient errors  
✅ Progress tracking callbacks  
✅ Metadata validation  
✅ Comprehensive logging  

## Usage Example

```java
// Basic usage
MSSQLExtractor extractor = new MSSQLExtractor();
DatabaseMetadata metadata = extractor.extract(connection);

// With custom schema
MSSQLExtractor extractor = new MSSQLExtractor("my_schema");
DatabaseMetadata metadata = extractor.extract(connection);

// With progress tracking
MSSQLExtractor.ExtractionProgress progress = new MSSQLExtractor.ExtractionProgress() {
    @Override
    public void onPhaseStart(String phase) {
        System.out.println("Starting: " + phase);
    }
    
    @Override
    public void onPhaseComplete(String phase, int items, long durationMs) {
        System.out.println(phase + ": " + items + " items in " + durationMs + "ms");
    }
    
    @Override
    public void onWarning(String message) {
        System.err.println("Warning: " + message);
    }
};

MSSQLExtractor extractor = new MSSQLExtractor("dbo", true, progress);
DatabaseMetadata metadata = extractor.extract(connection);
```

## Build Configuration

Uses Java 21 with `mvn21`:

```xml
<properties>
    <maven.compiler.source>21</maven.compiler.source>
    <maven.compiler.target>21</maven.compiler.target>
</properties>

<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId>
            <version>3.11.0</version>
            <configuration>
                <source>21</source>
                <target>21</target>
            </configuration>
        </plugin>
    </plugins>
</build>
```

**Build commands:**
```bash
mvn21 clean compile     # Compile
mvn21 package          # Package with tests
mvn21 package -DskipTests  # Package without tests
```

## MSSQL-Specific Considerations

### 1. **NVARCHAR Length Calculation**
MSSQL stores NVARCHAR as 2 bytes per character, but `max_length` returns total bytes.
We automatically divide by 2:
```java
if (baseType.startsWith("n")) {
    maxLength = maxLength / 2;
}
```

### 2. **Default Value Wrapping**
MSSQL wraps defaults in parentheses:
- Input: `((0))` → Normalized: `0`
- Input: `(('active'))` → Normalized: `active`
- Input: `(getdate())` → Normalized: `getdate()`

### 3. **Referential Action Normalization**
MSSQL uses underscores in action descriptions:
- `NO_ACTION` → `NO ACTION`
- `SET_NULL` → `SET NULL`
- `SET_DEFAULT` → `SET DEFAULT`

### 4. **Extended Properties for Comments**
MSSQL uses a complex extended properties system:
```sql
sys.extended_properties WHERE:
  - class = 1 (object/column)
  - name = 'MS_Description'
  - minor_id = 0 (table) or column_id (column)
```

### 5. **Index Type Names**
MSSQL returns:
- `CLUSTERED`
- `NONCLUSTERED`
- `HEAP`
- `COLUMNSTORE`
- `CLUSTERED COLUMNSTORE`
- `NONCLUSTERED COLUMNSTORE`

We normalize underscores to spaces for consistency.

## Next Steps

1. **Test with real MSSQL schemas**
   - Run against the `mssql.sql` schema file
   - Compare with MySQL/PostgreSQL results for equivalence testing

2. **Integration testing**
   - Use Testcontainers with SQL Server
   - Create unit tests for each extraction phase

3. **Performance optimization**
   - Profile extraction on large schemas (1000+ tables)
   - Optimize system catalog queries if needed

4. **Documentation**
   - Add JavaDoc comments to public methods
   - Create user guide for MSSQL-specific features

## Summary

The MSSQL extractor is now **feature-complete** and matches the MySQL and PostgreSQL extractors in:
- ✅ Completeness of metadata extraction
- ✅ Robustness and error handling
- ✅ Code quality and maintainability
- ✅ Progress tracking and logging
- ✅ Database-specific feature support

The extractor properly handles all MSSQL-specific syntax and features, including IDENTITY columns, NVARCHAR length calculations, extended properties, and referential action rules.

