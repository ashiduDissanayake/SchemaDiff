# SchemaDiff System - Complete MySQL Flow Explanation

## 📋 Table of Contents
1. [System Overview](#system-overview)
2. [Flow Diagram](#flow-diagram)
3. [Phase 1: Database Connection](#phase-1-database-connection)
4. [Phase 2: Metadata Extraction (DB → Java Objects)](#phase-2-metadata-extraction)
5. [Phase 3: Comparison Logic](#phase-3-comparison-logic)
6. [Phase 4: Report Generation](#phase-4-report-generation)
7. [Data Model Hierarchy](#data-model-hierarchy)
8. [MySQL-Specific Implementation](#mysql-specific-implementation)

---

## System Overview

**SchemaDiff** is a schema comparison tool that:
1. **Extracts** database schema metadata into Java objects
2. **Compares** two schemas (reference vs target)
3. **Reports** differences in a hierarchical format

### Supported Comparison Modes:
- **LIVE_VS_LIVE**: Two running databases
- **SCRIPT_VS_SCRIPT**: Two SQL files (using Docker containers)
- **LIVE_VS_SCRIPT**: Live database vs SQL file
- **SCRIPT_VS_LIVE**: SQL file vs live database

---

## Flow Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                        SchemaDiffCLI                            │
│                     (Entry Point / Main)                        │
└────────────────┬────────────────────────────────────────────────┘
                 │
                 ├─► Detect Mode (Live DB or .sql file)
                 │
        ┌────────┴─────────┐
        │                  │
        ▼                  ▼
  REFERENCE DB         TARGET DB
        │                  │
        │  ┌──────────────►│ (Both go through same process)
        │  │               │
        ▼  │               ▼
┌────────────────────────────────────────┐
│  Connection Establishment              │
│  • JDBC for Live DB                    │
│  • Docker Container for .sql           │
└────────────┬───────────────────────────┘
             │
             ▼
┌────────────────────────────────────────┐
│   MySQLExtractor.extract(connection)   │
│                                        │
│   Queries INFORMATION_SCHEMA tables    │
│   to extract metadata in 4 phases:    │
│                                        │
│   1. Tables    (TABLES)                │
│   2. Columns   (COLUMNS)               │
│   3. Constraints (KEY_COLUMN_USAGE,    │
│                   TABLE_CONSTRAINTS,   │
│                   REFERENTIAL_CNSTRS,  │
│                   CHECK_CONSTRAINTS)   │
│   4. Indexes   (STATISTICS)            │
└────────────┬───────────────────────────┘
             │
             ▼
┌────────────────────────────────────────┐
│     DatabaseMetadata Object            │
│     (In-Memory Java Representation)    │
│                                        │
│  └─► Map<String, TableMetadata>       │
│       └─► List<ColumnMetadata>        │
│       └─► List<ConstraintMetadata>    │
│       └─► List<IndexMetadata>         │
└────────────┬───────────────────────────┘
             │
             │ (Both reference & target extracted)
             ▼
┌────────────────────────────────────────┐
│   ComparisonEngine.compare()           │
│                                        │
│   Hierarchical Comparison:             │
│   Level 1: Table Existence             │
│   Level 2: Column Definitions          │
│   Level 3: Constraints                 │
│   Level 4: Indexes                     │
└────────────┬───────────────────────────┘
             │
             ▼
┌────────────────────────────────────────┐
│        DiffResult Object               │
│                                        │
│  • List<String> missingTables          │
│  • List<String> extraTables            │
│  • Map<Table, List> columnDiffs        │
│  • Map<Table, List> constraintDiffs    │
│  • Map<Table, List> indexDiffs         │
└────────────┬───────────────────────────┘
             │
             ▼
┌────────────────────────────────────────┐
│   TreeReportBuilder.build()            │
│   Generates formatted tree output      │
└────────────┬───────────────────────────┘
             │
             ▼
        Console Output
```

---

## Phase 1: Database Connection

### Entry Point: `SchemaDiffCLI.java`

The CLI parses command-line arguments and determines how to connect:

```java
// Example command:
// java -jar schemadiff.jar --reference jdbc:mysql://localhost/ref_db 
//                          --target jdbc:mysql://localhost/target_db 
//                          --db-type mysql
```

### Connection Logic:

#### For Live Database:
```java
// JDBCHelper.connect() is used
Connection conn = DriverManager.getConnection(
    "jdbc:mysql://localhost:3306/database_name",
    username,
    password
);
```

#### For .sql File:
```java
// 1. Start Docker container with MySQL image
ContainerManager container = new ContainerManager("mysql:8.0", DatabaseType.MYSQL);
container.start(); // Spins up container, waits for ready

// 2. Get connection to container
Connection conn = container.getConnection();

// 3. Execute SQL file to create schema
new SQLProvisioner(conn).execute(new File("schema.sql"));

// 4. Now ready for extraction
```

---

## Phase 2: Metadata Extraction (DB → Java Objects)

### The Heart: `MySQLExtractor.java`

This class transforms database schema into Java objects by querying MySQL's **INFORMATION_SCHEMA**.

### 🔹 Step 1: Extract Tables

**SQL Query:**
```sql
SELECT 
    TABLE_NAME,
    ENGINE,
    TABLE_COLLATION,
    TABLE_COMMENT,
    CREATE_TIME,
    UPDATE_TIME,
    TABLE_ROWS,
    AVG_ROW_LENGTH,
    DATA_LENGTH
FROM INFORMATION_SCHEMA.TABLES 
WHERE TABLE_SCHEMA = 'your_database_name' 
  AND TABLE_TYPE = 'BASE TABLE'
ORDER BY TABLE_NAME
```

**Java Conversion:**
```java
// For each row in ResultSet:
TableMetadata table = new TableMetadata(tableName);
table.setEngine(rs.getString("ENGINE"));          // "InnoDB"
table.setCollation(rs.getString("TABLE_COLLATION")); // "utf8mb4_general_ci"
table.setComment(rs.getString("TABLE_COMMENT"));
table.setCreateTime(rs.getTimestamp("CREATE_TIME"));
table.setTableRows(rs.getLong("TABLE_ROWS"));

metadata.addTable(table); // Stored in TreeMap<String, TableMetadata>
```

### 🔹 Step 2: Extract Columns

**SQL Query:**
```sql
SELECT 
    TABLE_NAME, 
    COLUMN_NAME, 
    ORDINAL_POSITION,
    DATA_TYPE,              -- 'int', 'varchar', 'datetime'
    CHARACTER_MAXIMUM_LENGTH,
    NUMERIC_PRECISION, 
    NUMERIC_SCALE, 
    IS_NULLABLE,            -- 'YES' or 'NO'
    COLUMN_DEFAULT,
    COLUMN_TYPE,            -- 'int(11) unsigned'
    COLUMN_KEY,
    EXTRA,                  -- 'auto_increment'
    COLUMN_COMMENT,
    CHARACTER_SET_NAME,
    COLLATION_NAME
FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = 'your_database_name'
ORDER BY TABLE_NAME, ORDINAL_POSITION
```

**Java Conversion:**
```java
// Build data type with precision/scale
String dataType = buildDataType(rs); // e.g., "varchar(255)", "decimal(10,2)"

// Parse nullable
boolean notNull = "NO".equals(rs.getString("IS_NULLABLE"));

// Create column object
ColumnMetadata column = new ColumnMetadata(
    columnName,       // "user_id"
    dataType,         // "int(11)"
    notNull,          // true
    defaultValue      // null or "0"
);

// Set MySQL-specific properties
column.setOrdinalPosition(rs.getInt("ORDINAL_POSITION"));
column.setColumnType(rs.getString("COLUMN_TYPE")); // "int(11) unsigned"

String extra = rs.getString("EXTRA");
column.setAutoIncrement(extra != null && extra.contains("auto_increment"));

String columnType = rs.getString("COLUMN_TYPE");
column.setUnsigned(columnType != null && columnType.contains("unsigned"));

column.setComment(rs.getString("COLUMN_COMMENT"));
column.setCharacterSet(rs.getString("CHARACTER_SET_NAME"));
column.setCollation(rs.getString("COLLATION_NAME"));

// Add to table
TableMetadata table = metadata.getTable(tableName);
table.addColumn(column);
```

### 🔹 Step 3: Extract Constraints

Constraints are extracted in 4 sub-phases:

#### 3a. Primary Keys

**SQL Query:**
```sql
SELECT 
    TABLE_NAME, 
    COLUMN_NAME,
    ORDINAL_POSITION
FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE
WHERE CONSTRAINT_SCHEMA = 'your_database_name' 
  AND CONSTRAINT_NAME = 'PRIMARY'
ORDER BY TABLE_NAME, ORDINAL_POSITION
```

**Java Conversion:**
```java
// Group by table (may have composite PKs)
Map<String, List<String>> pkMap = new LinkedHashMap<>();

// For each row:
String table = rs.getString("TABLE_NAME");     // "users"
String column = rs.getString("COLUMN_NAME");   // "user_id"
pkMap.computeIfAbsent(table, k -> new ArrayList<>()).add(column);

// Create constraint objects
for (Map.Entry<String, List<String>> entry : pkMap.entrySet()) {
    ConstraintMetadata pk = new ConstraintMetadata(
        "PRIMARY_KEY",           // type
        "PRIMARY",               // name
        entry.getValue(),        // ["user_id"] or ["user_id", "tenant_id"]
        null                     // no referenced table
    );
    
    // Generate signature for comparison
    pk.setSignature(SignatureGenerator.generate(pk));
    // Example: "PRIMARY_KEY:USER_ID"
    
    table.addConstraint(pk);
}
```

#### 3b. Foreign Keys

**SQL Query:**
```sql
SELECT 
    kcu.TABLE_NAME,
    kcu.CONSTRAINT_NAME,
    kcu.COLUMN_NAME,
    kcu.ORDINAL_POSITION,
    kcu.REFERENCED_TABLE_NAME,
    kcu.REFERENCED_COLUMN_NAME,
    rc.UPDATE_RULE,           -- 'CASCADE', 'SET NULL', 'NO ACTION', 'RESTRICT'
    rc.DELETE_RULE
FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE kcu
JOIN INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS rc
  ON kcu.CONSTRAINT_SCHEMA = rc.CONSTRAINT_SCHEMA
 AND kcu.CONSTRAINT_NAME = rc.CONSTRAINT_NAME
WHERE kcu.CONSTRAINT_SCHEMA = 'your_database_name'
  AND kcu.REFERENCED_TABLE_NAME IS NOT NULL
ORDER BY kcu.TABLE_NAME, kcu.CONSTRAINT_NAME, kcu.ORDINAL_POSITION
```

**Java Conversion:**
```java
// Use builder pattern for composite FKs
ForeignKeyBuilder builder = new ForeignKeyBuilder(
    tableName,         // "orders"
    constraintName,    // "fk_orders_users"
    refTableName,      // "users"
    updateRule,        // "CASCADE"
    deleteRule         // "CASCADE"
);

// Add column mappings
builder.addColumn("user_id", "user_id");

// Build constraint
ConstraintMetadata fk = builder.build();
fk.setSignature(SignatureGenerator.generate(fk));
// Example: "FOREIGN_KEY:USER_ID→USERS(USER_ID) ON DELETE CASCADE ON UPDATE CASCADE"

table.addConstraint(fk);
```

#### 3c. Check Constraints (MySQL 8.0.16+)

**SQL Query:**
```sql
SELECT 
    tc.TABLE_NAME,
    cc.CONSTRAINT_NAME,
    cc.CHECK_CLAUSE
FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS cc
JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc
  ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA
 AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
WHERE cc.CONSTRAINT_SCHEMA = 'your_database_name'
ORDER BY tc.TABLE_NAME, cc.CONSTRAINT_NAME
```

**Java Conversion:**
```java
ConstraintMetadata check = new ConstraintMetadata(
    "CHECK",
    constraintName,          // "chk_age_positive"
    new ArrayList<>(),       // columns extracted from clause
    null
);
check.setCheckClause(checkClause);  // "(age >= 0)"
check.setSignature(SignatureGenerator.generate(check));

table.addConstraint(check);
```

#### 3d. Unique Constraints

**SQL Query:**
```sql
SELECT 
    tc.TABLE_NAME,
    tc.CONSTRAINT_NAME,
    kcu.COLUMN_NAME,
    kcu.ORDINAL_POSITION
FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc
JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE kcu
  ON tc.CONSTRAINT_SCHEMA = kcu.CONSTRAINT_SCHEMA
 AND tc.CONSTRAINT_NAME = kcu.CONSTRAINT_NAME
 AND tc.TABLE_NAME = kcu.TABLE_NAME
WHERE tc.CONSTRAINT_SCHEMA = 'your_database_name'
  AND tc.CONSTRAINT_TYPE = 'UNIQUE'
ORDER BY tc.TABLE_NAME, tc.CONSTRAINT_NAME, kcu.ORDINAL_POSITION
```

**Java Conversion:**
```java
// Group columns for composite unique constraints
Map<ConstraintIdentifier, List<String>> uniqueMap = new LinkedHashMap<>();

// Build constraint
ConstraintMetadata unique = new ConstraintMetadata(
    "UNIQUE",
    constraintName,     // "uk_users_email"
    columnsList,        // ["email"]
    null
);
unique.setSignature(SignatureGenerator.generate(unique));
// Example: "UNIQUE:EMAIL"

table.addConstraint(unique);
```

### 🔹 Step 4: Extract Indexes

**SQL Query:**
```sql
SELECT 
    TABLE_NAME, 
    INDEX_NAME, 
    COLUMN_NAME, 
    SEQ_IN_INDEX,
    NON_UNIQUE,          -- 0 = unique, 1 = non-unique
    INDEX_TYPE,          -- 'BTREE', 'HASH', 'FULLTEXT', 'SPATIAL'
    INDEX_COMMENT
FROM INFORMATION_SCHEMA.STATISTICS
WHERE TABLE_SCHEMA = 'your_database_name' 
  AND INDEX_NAME != 'PRIMARY'
ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX
```

**Java Conversion:**
```java
// Use builder for composite indexes
IndexBuilder builder = new IndexBuilder(
    tableName,          // "users"
    indexName,          // "idx_users_lastname_firstname"
    unique,             // false
    indexType,          // "BTREE"
    comment             // ""
);

// Add columns in order
builder.addColumn("last_name");
builder.addColumn("first_name");

// Build index
IndexMetadata index = builder.build();
index.setIndexType(indexType);
index.setComment(comment);

table.addIndex(index);
```

### Result: Complete DatabaseMetadata Object

After extraction, you have a complete in-memory representation:

```
DatabaseMetadata
  schemaName: "my_database"
  tables: TreeMap {
    "users" → TableMetadata {
        name: "users"
        engine: "InnoDB"
        collation: "utf8mb4_general_ci"
        columns: [
          ColumnMetadata {
            name: "user_id"
            dataType: "int(11)"
            notNull: true
            defaultValue: null
            autoIncrement: true
            unsigned: true
          },
          ColumnMetadata {
            name: "username"
            dataType: "varchar(50)"
            notNull: true
            defaultValue: null
          },
          ColumnMetadata {
            name: "email"
            dataType: "varchar(255)"
            notNull: true
            defaultValue: null
          }
        ]
        constraints: [
          ConstraintMetadata {
            type: "PRIMARY_KEY"
            name: "PRIMARY"
            columns: ["user_id"]
            signature: "PRIMARY_KEY:USER_ID"
          },
          ConstraintMetadata {
            type: "UNIQUE"
            name: "uk_users_email"
            columns: ["email"]
            signature: "UNIQUE:EMAIL"
          }
        ]
        indexes: [
          IndexMetadata {
            name: "idx_users_username"
            columns: ["username"]
            unique: false
            indexType: "BTREE"
          }
        ]
      }
    "orders" → TableMetadata { ... }
  }
```

---

## Phase 3: Comparison Logic

### The Brain: `ComparisonEngine.java`

Once both reference and target schemas are extracted into `DatabaseMetadata` objects, the comparison happens in a **hierarchical 4-level approach**.

### 🔹 Level 1: Table Existence

**Logic:**
```java
private void compareTableExistence(DatabaseMetadata ref, DatabaseMetadata target, DiffResult result) {
    Set<String> refTables = ref.getTableNames();      // ["users", "orders", "products"]
    Set<String> targetTables = target.getTableNames(); // ["users", "orders"]

    // Find missing tables (in reference but not in target)
    for (String table : refTables) {
        if (!targetTables.contains(table.toUpperCase())) {
            result.addMissingTable(table);  // "products" is missing
        }
    }

    // Find extra tables (in target but not in reference)
    for (String table : targetTables) {
        if (!refTables.contains(table.toUpperCase())) {
            result.addExtraTable(table);  // None in this example
        }
    }
}
```

**Output:**
```
❌ Missing Tables:
  └─ products
```

### 🔹 Level 2: Column Definitions

**Only compares tables that exist in BOTH schemas.**

**Logic:**
```java
private void compareColumns(DatabaseMetadata ref, DatabaseMetadata target, DiffResult result) {
    for (String tableName : ref.getTableNames()) {
        TableMetadata refTable = ref.getTable(tableName);
        TableMetadata targetTable = target.getTable(tableName);

        if (targetTable == null) continue; // Skip missing tables (already reported)

        // Compare each column in reference
        for (ColumnMetadata refCol : refTable.getColumns()) {
            ColumnMetadata targetCol = targetTable.getColumn(refCol.getName());

            if (targetCol == null) {
                // Column missing in target
                result.addMissingColumn(tableName, refCol.getName(), refCol.getDataType());
            } else {
                // Column exists, check for modifications
                List<String> diffs = new ArrayList<>();

                // 1. Data Type comparison
                if (!TypeNormalizer.typesMatch(refCol.getDataType(), targetCol.getDataType())) {
                    diffs.add("Type mismatch: " + refCol.getDataType() + " != " + targetCol.getDataType());
                }

                // 2. Nullability comparison
                if (refCol.isNotNull() != targetCol.isNotNull()) {
                    diffs.add("Nullable mismatch: " + !refCol.isNotNull() + " != " + !targetCol.isNotNull());
                }

                // 3. Auto-increment comparison
                if (refCol.isAutoIncrement() != targetCol.isAutoIncrement()) {
                    diffs.add("AutoIncrement mismatch: " + refCol.isAutoIncrement() + " != " + targetCol.isAutoIncrement());
                }

                // 4. Unsigned comparison (MySQL-specific)
                if (refCol.isUnsigned() != targetCol.isUnsigned()) {
                    diffs.add("Unsigned mismatch: " + refCol.isUnsigned() + " != " + targetCol.isUnsigned());
                }

                // 5. Default value comparison
                String def1 = refCol.getDefaultValue();
                String def2 = targetCol.getDefaultValue();
                if (!Objects.equals(def1, def2)) {
                    diffs.add("Default value mismatch: " + def1 + " != " + def2);
                }

                if (!diffs.isEmpty()) {
                    result.addModifiedColumn(tableName, refCol.getName(), String.join(", ", diffs));
                }
            }
        }

        // Check for extra columns in target
        for (ColumnMetadata targetCol : targetTable.getColumns()) {
            if (refTable.getColumn(targetCol.getName()) == null) {
                result.addExtraColumn(tableName, targetCol.getName());
            }
        }
    }
}
```

**Example Output:**
```
📋 users
  ├─ [X] Missing Column: phone_number [varchar(20)]
  ├─ [M] Modified Column: email - Type mismatch: varchar(255) != varchar(100)
  └─ [+] Extra Column: middle_name
```

### 🔹 Level 3: Constraints

**Uses signature-based comparison** for intelligent matching.

**Logic:**
```java
private void compareConstraints(DatabaseMetadata ref, DatabaseMetadata target, DiffResult result) {
    for (String tableName : ref.getTableNames()) {
        TableMetadata refTable = ref.getTable(tableName);
        TableMetadata targetTable = target.getTable(tableName);

        if (targetTable == null) continue;

        // Build signature maps
        // Signature examples:
        //   PK:  "PRIMARY_KEY:USER_ID"
        //   FK:  "FOREIGN_KEY:USER_ID→USERS(USER_ID) ON DELETE CASCADE"
        //   UNQ: "UNIQUE:EMAIL"
        Map<String, ConstraintMetadata> refConstraints = buildSignatureMap(refTable.getConstraints());
        Map<String, ConstraintMetadata> targetConstraints = buildSignatureMap(targetTable.getConstraints());

        // Find missing constraints
        for (String signature : refConstraints.keySet()) {
            if (!targetConstraints.containsKey(signature)) {
                result.addMissingConstraint(tableName, refConstraints.get(signature).getType());
            }
        }
    }
}

private Map<String, ConstraintMetadata> buildSignatureMap(List<ConstraintMetadata> constraints) {
    Map<String, ConstraintMetadata> map = new HashMap<>();
    for (ConstraintMetadata c : constraints) {
        map.put(c.getSignature(), c);  // Signature generated during extraction
    }
    return map;
}
```

**Signature Generation (from SignatureGenerator.java):**
```java
public static String generate(ConstraintMetadata constraint) {
    // 1. Sort columns alphabetically for comparison
    String columns = constraint.getColumns().stream()
        .map(String::toUpperCase)
        .sorted()
        .collect(Collectors.joining(","));

    // 2. Start with type and columns
    String signature = constraint.getType() + ":" + columns;

    // 3. Add foreign key details
    if (constraint.getReferencedTable() != null) {
        signature += "→" + constraint.getReferencedTable().toUpperCase();
        
        if (constraint.getReferencedColumns() != null) {
            String refCols = constraint.getReferencedColumns().stream()
                .map(String::toUpperCase)
                .collect(Collectors.joining(","));
            signature += "(" + refCols + ")";
        }

        // Include CASCADE rules (important!)
        if (constraint.getDeleteRule() != null) {
            signature += " ON DELETE " + constraint.getDeleteRule();
        }
        if (constraint.getUpdateRule() != null) {
            signature += " ON UPDATE " + constraint.getUpdateRule();
        }
    }

    return signature;
}
```

**Why Signatures?**
- Handles different constraint names but same definition
- Example: `fk_user_id` vs `orders_user_fkey` → same signature if they reference the same table/column
- Detects subtle changes like CASCADE rule modifications

**Example Output:**
```
📋 orders
  ├─ [X] Missing Constraint: FOREIGN_KEY
  └─ [X] Missing Constraint: UNIQUE
```

### 🔹 Level 4: Indexes

**Simple column-based comparison.**

**Logic:**
```java
private void compareIndexes(DatabaseMetadata ref, DatabaseMetadata target, DiffResult result) {
    for (String tableName : ref.getTableNames()) {
        TableMetadata refTable = ref.getTable(tableName);
        TableMetadata targetTable = target.getTable(tableName);

        if (targetTable == null) continue;

        // Build simple column signature sets
        Set<String> refIndexSigs = buildIndexSignatures(refTable.getIndexes());
        Set<String> targetIndexSigs = buildIndexSignatures(targetTable.getIndexes());

        // Compare
        for (String sig : refIndexSigs) {
            if (!targetIndexSigs.contains(sig)) {
                result.addMissingIndex(tableName, sig);
            }
        }
    }
}

private Set<String> buildIndexSignatures(List<IndexMetadata> indexes) {
    Set<String> signatures = new HashSet<>();
    for (IndexMetadata index : indexes) {
        // Signature is just comma-separated column list
        // "last_name,first_name"
        signatures.add(String.join(",", index.getColumns()));
    }
    return signatures;
}
```

**Example Output:**
```
📋 users
  └─ [X] Missing Index on: username,email
```

---

## Phase 4: Report Generation

### TreeReportBuilder.java

Converts the `DiffResult` into a formatted tree output.

**Structure:**
```
═══════════════════════════════════════════════
Schema Comparison Report
═══════════════════════════════════════════════

❌ Missing Tables (1):
  └─ products

📋 users (3 differences)
  ├─ [X] Missing Column: phone_number [varchar(20)]
  ├─ [M] Modified Column: email - Type mismatch
  └─ [X] Missing Index on: username

📋 orders (2 differences)
  ├─ [X] Missing Constraint: FOREIGN_KEY
  └─ [+] Extra Column: notes

═══════════════════════════════════════════════
Total Differences: 6
Exit Code: 1
```

---

## Data Model Hierarchy

```
DatabaseMetadata (Root container)
  │
  ├─ schemaName: String
  ├─ extractionTimestamp: long
  └─ tables: Map<String, TableMetadata>
       │
       └─ TableMetadata (Per table)
            │
            ├─ name: String
            ├─ engine: String (MySQL: "InnoDB", "MyISAM")
            ├─ collation: String
            ├─ comment: String
            ├─ createTime: Timestamp
            ├─ tableRows: Long
            │
            ├─ columns: List<ColumnMetadata>
            │    │
            │    └─ ColumnMetadata (Per column)
            │         ├─ name: String
            │         ├─ dataType: String ("varchar(255)", "int(11)")
            │         ├─ notNull: boolean
            │         ├─ defaultValue: String
            │         ├─ ordinalPosition: int
            │         ├─ columnType: String ("int(11) unsigned")
            │         ├─ autoIncrement: boolean
            │         ├─ unsigned: boolean
            │         ├─ comment: String
            │         ├─ characterSet: String
            │         └─ collation: String
            │
            ├─ constraints: List<ConstraintMetadata>
            │    │
            │    └─ ConstraintMetadata (Per constraint)
            │         ├─ name: String
            │         ├─ type: String ("PRIMARY_KEY", "FOREIGN_KEY", etc.)
            │         ├─ columns: List<String>
            │         ├─ referencedTable: String (FK only)
            │         ├─ referencedColumns: List<String> (FK only)
            │         ├─ updateRule: String (FK: "CASCADE", "SET NULL")
            │         ├─ deleteRule: String (FK: "CASCADE", "RESTRICT")
            │         ├─ checkClause: String (CHECK only)
            │         └─ signature: String (for comparison)
            │
            └─ indexes: List<IndexMetadata>
                 │
                 └─ IndexMetadata (Per index)
                      ├─ name: String
                      ├─ columns: List<String>
                      ├─ unique: boolean
                      ├─ indexType: String ("BTREE", "HASH")
                      └─ comment: String
```

---

## MySQL-Specific Implementation

### Key MySQL Features Captured:

1. **Storage Engine**
   - InnoDB, MyISAM, etc.
   - Extracted from `INFORMATION_SCHEMA.TABLES.ENGINE`

2. **Unsigned Integers**
   - MySQL-specific modifier
   - Detected from `COLUMN_TYPE` field containing "unsigned"

3. **Auto-Increment**
   - Detected from `EXTRA` column containing "auto_increment"

4. **Collation & Character Set**
   - Table-level and column-level collations
   - Important for string comparisons and sorting

5. **Index Types**
   - BTREE (default)
   - HASH (MEMORY engine)
   - FULLTEXT (text search)
   - SPATIAL (geographic data)

6. **Check Constraints**
   - Only available in MySQL 8.0.16+
   - Gracefully skipped in older versions

7. **Foreign Key Rules**
   - ON DELETE: CASCADE, SET NULL, NO ACTION, RESTRICT
   - ON UPDATE: CASCADE, SET NULL, NO ACTION, RESTRICT
   - Included in constraint signature for detection

### Transaction Safety:

```java
// Set up consistent snapshot
conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
conn.setAutoCommit(false);
conn.setReadOnly(true);

// InnoDB consistent snapshot
stmt.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY");

// Extract metadata (all queries see same consistent view)
extractTables(...);
extractColumns(...);
extractConstraints(...);
extractIndexes(...);

// Commit
conn.commit();
```

This ensures all metadata is extracted from a **consistent point-in-time snapshot**, even if the database is being modified during extraction.

### Retry Logic:

```java
private <T> T executeWithRetry(SQLCallable<T> callable) throws SQLException {
    int attempt = 0;
    while (attempt < MAX_RETRIES) {
        try {
            return callable.call();
        } catch (SQLException e) {
            if (!isRetryable(e) || attempt >= MAX_RETRIES) {
                throw e;
            }
            // Exponential backoff
            Thread.sleep(1000L * attempt);
            attempt++;
        }
    }
}

private boolean isRetryable(SQLException e) {
    int errorCode = e.getErrorCode();
    return errorCode == 1213 || // Deadlock
           errorCode == 1205 || // Lock wait timeout
           errorCode == 2006 || // Server gone away
           errorCode == 2013;   // Lost connection
}
```

Handles transient MySQL errors automatically.

---

## Complete Flow Example

### Input:
```bash
java -jar schemadiff.jar \
  --reference jdbc:mysql://localhost:3306/production_db \
  --target jdbc:mysql://localhost:3306/staging_db \
  --ref-user root \
  --ref-pass secret \
  --target-user root \
  --target-pass secret \
  --db-type mysql
```

### Process:

1. **CLI Parsing** → Detects LIVE_VS_LIVE mode

2. **Reference Extraction**:
   - Connect to `production_db`
   - Query `INFORMATION_SCHEMA.TABLES` → Extract 50 tables
   - Query `INFORMATION_SCHEMA.COLUMNS` → Extract 500 columns
   - Query constraint tables → Extract 80 constraints
   - Query `INFORMATION_SCHEMA.STATISTICS` → Extract 120 indexes
   - Build `DatabaseMetadata` object in memory

3. **Target Extraction**:
   - Connect to `staging_db`
   - Same process as reference
   - Build second `DatabaseMetadata` object

4. **Comparison**:
   - Level 1: Find 2 missing tables
   - Level 2: Find 15 column differences
   - Level 3: Find 5 missing constraints
   - Level 4: Find 8 missing indexes

5. **Report**:
   - Generate tree output
   - Print to console
   - Exit with code 1 (differences found)

---

## Summary

**Database → Java Objects:**
- MySQL stores metadata in `INFORMATION_SCHEMA`
- `MySQLExtractor` queries these system tables
- Each row from `ResultSet` → Java object
- Hierarchical structure: `DatabaseMetadata` → `TableMetadata` → `ColumnMetadata`, etc.

**Comparison Logic:**
- Two `DatabaseMetadata` objects compared hierarchically
- Level-by-level approach: Tables → Columns → Constraints → Indexes
- Signature-based matching for constraints (handles name differences)
- Results accumulated in `DiffResult` object

**Key Design Patterns:**
- **Builder Pattern**: For complex objects (ForeignKey, Index)
- **Strategy Pattern**: Different extractors per database type
- **Template Method**: Abstract `MetadataExtractor` with DB-specific implementations
- **Immutable Collections**: Defensive copies prevent external modification

This architecture makes the system:
- ✅ Extensible (easy to add new DB types)
- ✅ Maintainable (clear separation of concerns)
- ✅ Reliable (transaction safety, retry logic)
- ✅ Accurate (signature-based comparison catches subtle differences)

