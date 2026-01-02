# 🏗️ SchemaDiff 2.0 - Architecture & Technical Deep Dive

## Purpose of This Document

This document explains **how SchemaDiff works internally** and the **database-specific logic** that makes cross-platform schema comparison possible. If you're trying to understand why certain design decisions were made, or if you want to extend the tool, start here.

For **operational instructions** (how to run the tool), see [README.md](README.md).

---

## Table of Contents

1. [High-Level Architecture](#high-level-architecture)
2. [Extraction Capabilities Matrix](#extraction-capabilities-matrix)
3. [Database-Specific Logic (The "Why")](#database-specific-logic-the-why)
4. [Signature-Based Comparison](#signature-based-comparison)
5. [Docker Integration](#docker-integration)
6. [Extending the Tool](#extending-the-tool)

---

## High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                         SchemaDiff 2.0                          │
│                      Command-Line Interface                     │
└────────────────────────┬────────────────────────────────────────┘
                         │
         ┌───────────────┴───────────────┐
         │                               │
    ┌────▼─────┐                   ┌────▼─────┐
    │ Reference │                   │  Target  │
    │  Source   │                   │  Source  │
    └────┬─────┘                   └────┬─────┘
         │                               │
         │ (JDBC or .sql file)          │ (JDBC or .sql file)
         │                               │
    ┌────▼─────────────────┐    ┌────▼─────────────────┐
    │  Docker Container    │    │  Docker Container    │
    │  (if .sql file)      │    │  (if .sql file)      │
    │  or Live DB          │    │  or Live DB          │
    └────┬─────────────────┘    └────┬─────────────────┘
         │                               │
         │ SQL Provisioner              │ SQL Provisioner
         │ (schema creation)            │ (schema creation)
         │                               │
    ┌────▼─────────────────┐    ┌────▼─────────────────┐
    │  Database-Specific   │    │  Database-Specific   │
    │  Metadata Extractor  │    │  Metadata Extractor  │
    │  (MySQL, Postgres,   │    │  (MySQL, Postgres,   │
    │   Oracle, MSSQL)     │    │   Oracle, MSSQL)     │
    └────┬─────────────────┘    └────┬─────────────────┘
         │                               │
         │ DatabaseMetadata             │ DatabaseMetadata
         │ (Java Object Model)          │ (Java Object Model)
         │                               │
         └───────────────┬───────────────┘
                         │
                    ┌────▼─────┐
                    │  Schema  │
                    │ Comparator│
                    │ (Signature│
                    │  Matching)│
                    └────┬─────┘
                         │
                    ┌────▼─────┐
                    │  Report  │
                    │ Generator │
                    │ (Tree View)│
                    └──────────┘
```

### Key Components

1. **Main Entry Point** (`Main.java`)
   - Parses command-line arguments
   - Determines mode (Script-Script, Script-Live, Live-Script, Live-Live)
   - Orchestrates workflow

2. **Container Manager** (`ContainerManager.java`)
   - Manages Docker container lifecycle
   - Spins up ephemeral databases for script provisioning
   - Handles cleanup (automatic shutdown on JVM exit)

3. **SQL Provisioner** (`SQLProvisioner.java`)
   - Parses .sql files (handles multi-statement scripts)
   - Executes DDL statements (CREATE TABLE, ALTER TABLE, etc.)
   - Skips failed statements with warnings (resilient parsing)

4. **Database-Specific Extractors**
   - `MySQLExtractor.java`: MySQL 8.0+
   - `PostgresExtractor.java`: PostgreSQL 9.6+ (Extended features)
   - `OracleExtractor.java`: Oracle 11g+ (Trigger-based auto-increment)
   - `MSSQLExtractor.java`: SQL Server 2019+
   - `DB2Extractor.java`: IBM DB2 11.5+

5. **Schema Comparator** (`SchemaComparator.java`)
   - Signature-based constraint matching (no reliance on constraint names)
   - Hierarchical diff tree generation
   - Exit code determination (0 = no diff, 1 = diff found, 2+ = error)

6. **Report Generator** (`ReportGenerator.java`)
   - Beautiful ASCII tree output
   - Categorized differences (missing, extra, modified)
   - Human-readable constraint/index definitions

---

## Extraction Capabilities Matrix

This table shows **what each extractor can detect**. All extractors support the "Standard" features. Extended features are database-specific.

| Feature                  | MySQL  | PostgreSQL | MSSQL  | Oracle | DB2    | Notes |
|--------------------------|--------|------------|--------|--------|--------|-------|
| **Tables**               | ✅ Std | ✅ Std     | ✅ Std | ✅ Std | ✅ Std | Table names, comments |
| **Columns**              | ✅ Std | ✅ Std     | ✅ Std | ✅ Std | ✅ Std | Name, type, nullable, default |
| **Primary Keys**         | ✅ Std | ✅ Std     | ✅ Std | ✅ Std | ✅ Std | Composite PKs supported |
| **Foreign Keys**         | ✅ Std | ✅ Std     | ⚠️ Ltd | ⚠️ Ltd | ✅ Std | See FK behavior notes below |
| **Unique Constraints**   | ✅ Std | ✅ Std     | ✅ Std | ✅ Std | ✅ Std | Multi-column uniques supported |
| **Check Constraints**    | ✅ Std | ✅ Std     | ✅ Std | ✅ Std | ✅ Std | Complex expressions supported |
| **Indexes**              | ✅ Std | ✅ Std     | ✅ Std | ⚠️ Ext | ✅ Std | See index notes below |
| **Auto-Increment**       | ✅ Native | ✅ Seq | ✅ Identity | ⚠️ Trigger | ✅ Identity | See auto-increment notes below |
| **Sequences**            | ❌ N/A | ✅ Ext     | ❌ No  | ❌ No  | ❌ No  | PostgreSQL only |
| **Functions**            | ❌ No  | ✅ Ext     | ❌ No  | ❌ No  | ❌ No  | PostgreSQL only |
| **Triggers**             | ❌ No  | ✅ Ext     | ✅ Ext | ⚠️ AI Only | ❌ No  | PostgreSQL & MSSQL: Full extraction, Oracle: Auto-increment only |

**Legend:**
- ✅ **Std**: Standard support (fully implemented)
- ✅ **Ext**: Extended support (additional features beyond standard)
- ⚠️ **Ltd**: Limited support (platform restrictions apply)
- ⚠️ **AI Only**: Only extracts triggers used for auto-increment logic
- ❌ **No**: Not supported
- ❌ **N/A**: Not applicable (platform doesn't have this feature)

---

## Database-Specific Logic (The "Why")

This section documents **intentional design decisions** that account for database platform differences. These are NOT bugs—they're carefully crafted workarounds for real platform limitations.

### 1. The "Auto-Increment" Abstraction

Each database handles auto-incrementing primary keys differently. The tool normalizes these into a single `autoIncrement` flag on columns.

#### MySQL
- **Native Feature**: `AUTO_INCREMENT` keyword
- **Detection**: Direct column attribute query via `EXTRA` column in `information_schema.COLUMNS`
- **Example**: 
  ```sql
  CREATE TABLE users (
    id INT AUTO_INCREMENT PRIMARY KEY
  );
  ```

#### PostgreSQL
- **Native Feature**: `SERIAL`, `BIGSERIAL`, `IDENTITY` columns
- **Detection**: Queries `pg_class` and `pg_depend` to find sequence ownership
- **Extraction Logic**: 
  1. Identifies sequences owned by columns
  2. Checks for `nextval()` in column defaults
  3. Marks column as auto-increment
- **Example**:
  ```sql
  CREATE TABLE users (
    id SERIAL PRIMARY KEY  -- Creates sequence users_id_seq
  );
  ```

#### MSSQL (SQL Server)
- **Native Feature**: `IDENTITY` property
- **Detection**: Uses `COLUMNPROPERTY(object_id, column_name, 'IsIdentity')`
- **Example**:
  ```sql
  CREATE TABLE users (
    id INT IDENTITY(1,1) PRIMARY KEY
  );
  ```

#### Oracle
- **Native Feature** (11g+): Identity columns OR sequence + trigger pattern
- **Detection**: **Special Logic** (Critical!)
  1. Scans `ALL_TRIGGERS` for `BEFORE INSERT` triggers
  2. Reads `TRIGGER_BODY` (LONG datatype) into memory
  3. Searches for pattern: `INTO :NEW.column_name` + `NEXTVAL`
  4. Marks the target column as auto-increment
- **Why This Way?**: Oracle's `TRIGGER_BODY` is a `LONG` type, which cannot be filtered in SQL `WHERE` clauses (ORA-00932 error). We must fetch ALL triggers into Java, then filter in-memory.
- **Example**:
  ```sql
  CREATE SEQUENCE users_seq START WITH 1;
  CREATE TRIGGER users_trg
  BEFORE INSERT ON users
  FOR EACH ROW
  BEGIN
    SELECT users_seq.NEXTVAL INTO :NEW.id FROM DUAL;
  END;
  ```

---

### 2. The "Foreign Key Behavior" Problem

Foreign keys support `ON DELETE` and `ON UPDATE` actions (CASCADE, SET NULL, NO ACTION, RESTRICT). However, not all databases support all actions.

#### MySQL
- **Support**: Full support for all actions
- **Extraction**: Direct from `information_schema.REFERENTIAL_CONSTRAINTS`

#### PostgreSQL
- **Support**: Full support for all actions
- **Extraction**: Direct from `pg_constraint` system catalog

#### MSSQL (SQL Server)
- **Limitation**: 
  - `ON DELETE CASCADE` causes errors in cyclic relationships or multiple cascade paths
  - Workaround: Many MSSQL schemas use `NO ACTION` instead
- **Extraction**: Detects and reports the actual behavior
- **Tool Behavior**: Reports the difference but doesn't flag it as critical

#### Oracle
- **Limitation**: **NO SUPPORT for `ON UPDATE CASCADE`**
  - Oracle's FK architecture doesn't allow update cascading
  - Only `ON DELETE` actions are supported
- **Extraction**: Only reports `ON DELETE` behavior
- **Tool Behavior**: Ignores `ON UPDATE` differences when comparing to/from Oracle

---

### 3. The "Index Explosion" Problem

Some databases automatically create indexes for foreign keys; others don't.

#### MySQL
- **Behavior**: **Automatically creates indexes for ALL foreign key columns**
- **Consequence**: If you create 50 FKs, MySQL creates 50 indexes automatically
- **Extractor Logic**: Reports these auto-created indexes
- **Comparison Impact**: When comparing MySQL to PostgreSQL/MSSQL, you'll see "extra indexes" in MySQL

#### PostgreSQL
- **Behavior**: **No automatic FK indexing**
- **Best Practice**: Manually create indexes on FK columns for performance
- **Extractor Logic**: Only reports explicitly created indexes

#### MSSQL
- **Behavior**: **No automatic FK indexing**
- **Performance Impact**: Large tables with unindexed FKs can cause severe slowdowns
- **Extractor Logic**: Only reports explicitly created indexes

#### Oracle
- **Behavior**: **No automatic FK indexing**
- **Special Feature**: Supports **function-based indexes** (e.g., `UPPER(column_name)`)
- **Extractor Logic**: Reports all index types, including function-based

---

### 4. The "LONG Data Type" Handler (Oracle)

Oracle has a legacy `LONG` datatype (used for `TRIGGER_BODY`, `SEARCH_CONDITION` in system views) that has severe restrictions:

- **Cannot be used in `WHERE` clauses** (ORA-00932 error)
- **Cannot be used in `ORDER BY` or `GROUP BY`**
- **Must be fetched into memory first, then filtered in Java**

#### Impact on Trigger Detection

The tool **cannot** do this:
```sql
-- THIS FAILS WITH ORA-00932
SELECT table_name 
FROM all_triggers 
WHERE trigger_body LIKE '%NEXTVAL%';  -- ❌ trigger_body is LONG
```

Instead, the tool does this:
```java
// 1. Fetch ALL triggers (no filtering)
ResultSet rs = stmt.executeQuery("SELECT table_name, trigger_body FROM all_triggers");

// 2. Filter in Java
while (rs.next()) {
    String triggerBody = rs.getString("trigger_body");  // Fetch LONG into memory
    if (triggerBody.toUpperCase().contains("NEXTVAL")) {  // ✅ Filter in Java
        // ... mark column as auto-increment
    }
}
```

This is intentional and unavoidable due to Oracle's architecture.

---

### 5. The "Check Constraint" Complexity

Check constraints can be simple (`age > 18`) or complex (`status IN ('ACTIVE', 'PENDING') AND created_date < SYSDATE`).

#### String Normalization

Different databases format check expressions differently:

**MySQL**:
```sql
CHECK (status IN ('ACTIVE','PENDING'))  -- No spaces
```

**PostgreSQL**:
```sql
CHECK ((status IN ('ACTIVE', 'PENDING')))  -- Extra parentheses, spaces
```

**Oracle**:
```sql
CHECK (STATUS IN ('ACTIVE', 'PENDING'))  -- Uppercase column names
```

#### Extractor Logic

The tool performs **light normalization**:
1. Remove extra whitespace
2. Remove redundant parentheses
3. Lowercase function names (but NOT values)

However, it does **NOT** attempt semantic equivalence checking. If PostgreSQL has `((age > 18))` and MySQL has `age > 18`, the tool considers them equivalent.

---

### 6. Trigger Extraction (PostgreSQL and MSSQL)

Both PostgreSQL and MSSQL support full trigger extraction, but with different architectures.

#### PostgreSQL Triggers
- **Architecture**: Triggers call separate **trigger functions** (written in PL/pgSQL or other procedural languages)
- **Extraction**: Queries `pg_trigger` system catalog
- **Details Captured**:
  - Trigger name, table name
  - Timing: `BEFORE`, `AFTER`, `INSTEAD OF`
  - Event: `INSERT`, `UPDATE`, `DELETE`
  - Level: `ROW` or `STATEMENT`
  - Function name (the actual logic is in a separate function object)
- **Example**:
  ```sql
  CREATE FUNCTION update_timestamp()
  RETURNS TRIGGER AS $$
  BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
  END;
  $$ LANGUAGE plpgsql;

  CREATE TRIGGER trg_update_timestamp
  BEFORE UPDATE ON users
  FOR EACH ROW
  EXECUTE FUNCTION update_timestamp();
  ```

#### MSSQL Triggers
- **Architecture**: Triggers contain **inline T-SQL code** (no separate function object)
- **Extraction**: Queries `sys.triggers` and `OBJECT_DEFINITION()` for trigger body
- **Details Captured**:
  - Trigger name, table name
  - Timing: `AFTER`, `INSTEAD OF`
  - Event: `INSERT`, `UPDATE`, `DELETE`
  - Full trigger definition (T-SQL code)
- **Example**:
  ```sql
  CREATE TRIGGER trg_update_timestamp
  ON users
  AFTER UPDATE
  AS
  BEGIN
    UPDATE users
    SET updated_at = GETDATE()
    WHERE id IN (SELECT DISTINCT id FROM inserted);
  END;
  ```

#### Comparison Behavior

The tool compares triggers by:
1. **Name**: Case-insensitive matching
2. **Table**: Which table the trigger is attached to
3. **Timing**: BEFORE/AFTER/INSTEAD OF
4. **Event**: INSERT/UPDATE/DELETE
5. **Level**: ROW or STATEMENT (MSSQL is always ROW-level for DML triggers)

**Note**: The tool does NOT perform deep semantic analysis of trigger logic. It only checks if a trigger with the same name and basic properties exists.

---

### 7. Data Type Handling (Native vs Normalized)

**Important Design Decision**: This tool does **NOT normalize data types** across databases.

#### Why?

The tool is designed for **same-database-type comparisons** (MySQL ↔ MySQL, PostgreSQL ↔ PostgreSQL), NOT cross-database migrations (MySQL ↔ Oracle).

#### Implication

When comparing:
- **MySQL**: Reports `INT`, `VARCHAR(255)`, `TEXT`
- **PostgreSQL**: Reports `INTEGER`, `CHARACTER VARYING(255)`, `TEXT`
- **Oracle**: Reports `NUMBER(10)`, `VARCHAR2(255)`, `CLOB`
- **MSSQL**: Reports `INT`, `NVARCHAR(255)`, `NVARCHAR(MAX)`

If you try to compare MySQL vs PostgreSQL, you'll get false positives (`INT` ≠ `INTEGER`). This is expected.

#### PostgreSQL Extended Types

PostgreSQL extractors preserve native types:
- `BYTEA` (binary data)
- `UUID` (universally unique identifier)
- `JSON`, `JSONB` (JSON document types)
- `ARRAY` types (e.g., `INTEGER[]`)
- `HSTORE` (key-value pairs)

These are NOT converted to generic equivalents.

---

## Signature-Based Comparison

Traditional schema comparison tools match constraints by **name** (e.g., `FK_USERS_ORDERS`). This fails when:
- Constraint names are auto-generated (e.g., `SYS_C0012345` in Oracle)
- Naming conventions differ between environments

SchemaDiff 2.0 uses **signature matching** instead.

### Constraint Signatures

#### Primary Key Signature
```
PK:{column1,column2,...}
```
**Example**: `PK:{id}` or `PK:{user_id,role_id}` (composite)

#### Foreign Key Signature
```
FK:{columns}->{ref_table}({ref_columns})[on_delete][on_update]
```
**Example**: 
- `FK:{user_id}->{users}({id})[CASCADE][]`
- `FK:{order_id,line_num}->{orders}({id,line_num})[NO_ACTION][NO_ACTION]`

#### Unique Constraint Signature
```
UQ:{column1,column2,...}
```
**Example**: `UQ:{email}` or `UQ:{tenant_id,username}`

#### Check Constraint Signature
```
CHECK:<normalized_condition>
```
**Example**: `CHECK:status in ('active','pending')`

### Index Signatures

```
INDEX:{column1,column2,...}[type][unique]
```
**Example**:
- `INDEX:{created_date}[BTREE][non-unique]`
- `INDEX:{email}[HASH][unique]`

---

## Docker Integration

The tool uses **Testcontainers** (a Java library) to manage ephemeral Docker containers.

### Container Lifecycle

1. **Startup**: When a `.sql` file is provided as source
   - Pulls the specified Docker image (or uses default)
   - Starts container with appropriate environment variables
   - Waits for database to be ready (health check)
   - Creates a working schema

2. **Provisioning**: SQL file execution
   - Parses multi-statement SQL files
   - Executes DDL statements sequentially
   - Skips invalid statements (with warnings)

3. **Extraction**: Metadata collection
   - Connects via JDBC
   - Runs database-specific queries
   - Builds in-memory `DatabaseMetadata` object

4. **Cleanup**: Automatic shutdown
   - Uses Ryuk (sidecar container) to monitor JVM
   - When Java process exits, all containers are stopped
   - No manual cleanup needed

### Why Docker?

- **Isolation**: Each test runs in a clean environment
- **Reproducibility**: Same schema script = same database state
- **No side effects**: No pollution of local databases
- **Multi-version testing**: Easy to test against MySQL 8.0 vs 8.4

---

## Extending the Tool

### Adding a New Database Platform

To add support for a new database (e.g., MariaDB, Snowflake):

1. **Create Extractor Class**
   ```java
   public class MariaDBExtractor extends MetadataExtractor {
       @Override
       public DatabaseMetadata extract(Connection conn) throws SQLException {
           // Implement extraction logic
       }
   }
   ```

2. **Implement Extraction Methods**
   - `extractTables()`: Query system catalog for table names/comments
   - `extractColumns()`: Get column definitions (name, type, nullable, default)
   - `extractConstraints()`: Parse PKs, FKs, UNIQUEs, CHECKs
   - `extractIndexes()`: Get index definitions

3. **Handle Database-Specific Features**
   - Auto-increment detection
   - Foreign key behavior
   - Data type mapping

4. **Register in Factory**
   ```java
   // In DatabaseExtractorFactory.java
   public static MetadataExtractor createExtractor(String dbType) {
       return switch (dbType.toLowerCase()) {
           case "mariadb" -> new MariaDBExtractor();
           // ... other cases
       };
   }
   ```

5. **Add Docker Support**
   ```java
   // In ContainerManager.java
   private static final Map<String, String> DEFAULT_IMAGES = Map.of(
       "mariadb", "mariadb:10.11",
       // ... other databases
   );
   ```

6. **Test Thoroughly**
   - Create test SQL scripts
   - Run all 4 modes (Script-Script, Script-Live, Live-Script, Live-Live)
   - Validate constraint signature matching

---

## Error Handling Philosophy

The tool follows a **resilient extraction** approach:

- **SQL Provisioning**: Skips failed statements (with warnings) instead of aborting
- **Metadata Extraction**: Logs warnings for missing data instead of crashing
- **Comparison**: Reports differences even if extraction was partial

### Exit Codes

| Code | Meaning | Use Case |
|------|---------|----------|
| 0 | No differences found | Schemas are identical |
| 1 | Differences detected | Drift found (expected in many scenarios) |
| 2 | Database connection failed | Invalid JDBC URL or credentials |
| 3 | SQL provisioning failed | .sql file is completely invalid |
| 4 | Schema extraction failed | Database queries failed |
| 5 | Docker operation failed | Container startup/cleanup issue |

---

## Performance Considerations

### Query Timeout

All metadata queries have a 300-second timeout to prevent hanging on slow databases.

### Retry Logic

Extractors support automatic retry (default: 3 attempts) for transient connection errors.

### Transaction Isolation

All extraction queries use `TRANSACTION_READ_COMMITTED` and `read-only` mode to minimize locking.

### Batch Processing

Extractors use prepared statements and result set streaming to handle large schemas (1000+ tables).

---

## Known Limitations

1. **Cross-Database Comparison**: Not designed for MySQL ↔ Oracle comparisons (data type differences will cause false positives)
2. **Stored Procedures**: Only PostgreSQL functions are extracted; MySQL/Oracle stored procedures are not supported
3. **Partitions**: Table partitioning is not compared
4. **Views**: Database views are not extracted or compared
5. **Permissions**: User grants and roles are not analyzed
6. **Tablespaces**: Physical storage layout is ignored

---

## Future Enhancements (Roadmap)

- [ ] **View Support**: Extract and compare database views
- [ ] **Stored Procedure Comparison**: Full support for MySQL/Oracle procedures
- [ ] **JSON Output**: Machine-readable diff format for CI/CD pipelines
- [ ] **Partial Schema Comparison**: Compare subset of tables (e.g., only tables starting with `APIM_`)
- [ ] **Performance Metrics**: Track drift over time (historical reporting)
- [ ] **Cloud Database Support**: Native connectors for AWS RDS, Azure SQL, Google Cloud SQL

---

## Summary: Feature Comparison Table

| Feature | MySQL | PostgreSQL | MSSQL | Oracle | DB2 |
|---------|-------|------------|-------|--------|-----|
| **FK Indexing** | Automatic (Enforced by Engine) | Manual (Selected Keys only) | Minimal (Mostly omitted for performance) | Manual (Selected Keys only) | Manual |
| **Special Indexes** | N/A | Standard B-Tree | N/A | Function-Based (e.g. UPPER(col)) | N/A |
| **Auto-Increment** | AUTO_INCREMENT | SEQUENCE objects | IDENTITY property | SEQUENCE + TRIGGER combo | IDENTITY |
| **Trigger Extraction** | ❌ Not implemented | ✅ Full extraction | ✅ Full extraction | ⚠️ Auto-increment only | ❌ Not implemented |
| **Sequence Extraction** | ❌ N/A (uses AUTO_INCREMENT) | ✅ Full extraction | ❌ Not implemented | ❌ Not implemented | ❌ Not implemented |
| **Function Extraction** | ❌ Not implemented | ✅ Full extraction | ❌ Not implemented | ❌ Not implemented | ❌ Not implemented |
| **Data Type Philosophy** | Native (INT, VARCHAR, TEXT) | Native (INTEGER, VARCHAR, TEXT) | Native (INT, NVARCHAR) | Native (NUMBER, VARCHAR2, CLOB) | Native (INTEGER, VARCHAR) |

---

**End of Architecture Document**

For operational usage, see [README.md](README.md).

