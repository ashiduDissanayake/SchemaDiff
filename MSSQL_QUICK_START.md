# MSSQL Schema Comparison - Quick Start Guide

## ✅ Installation Complete

The MSSQL extractor has been successfully implemented and integrated into SchemaDiff.

---

## 🚀 Quick Usage

### 1. Compare Two MSSQL SQL Scripts

```bash
java21 -jar target/schemadiff2-2.0.0.jar \
    --db-type mssql \
    --reference apimgt/mssql.sql \
    --target apimgt/mssql_modified.sql \
    --image mcr.microsoft.com/mssql/server:2022-latest
```

### 2. Compare Live MSSQL Databases

```bash
java21 -jar target/schemadiff2-2.0.0.jar \
    --db-type mssql \
    --reference "jdbc:sqlserver://localhost:1433;databaseName=ref_db;encrypt=false" \
    --ref-user sa \
    --ref-pass YourPassword123 \
    --target "jdbc:sqlserver://localhost:1433;databaseName=target_db;encrypt=false" \
    --target-user sa \
    --target-pass YourPassword123
```

### 3. Compare Script vs Live Database

```bash
java21 -jar target/schemadiff2-2.0.0.jar \
    --db-type mssql \
    --reference apimgt/mssql.sql \
    --image mcr.microsoft.com/mssql/server:2022-latest \
    --target "jdbc:sqlserver://prod.server.com:1433;databaseName=prod_db;encrypt=false" \
    --target-user readonly \
    --target-pass secret
```

---

## 🎯 What Gets Detected

### ✅ Tables
- Missing tables
- Extra tables
- Table comments (from extended properties)
- Create/modify timestamps

### ✅ Columns
- Missing/extra columns
- Data type changes (VARCHAR, NVARCHAR, INT, BIGINT, UNIQUEIDENTIFIER, XML, etc.)
- NOT NULL changes
- **IDENTITY (auto-increment) changes**
- **Default value changes**
- **Computed columns**
- Column comments (from extended properties)
- NVARCHAR length (automatically handles 2-byte encoding)

### ✅ Constraints
- Primary keys (single and multi-column)
- Foreign keys with:
  - **ON DELETE rules** (CASCADE, NO_ACTION, SET_NULL, SET_DEFAULT)
  - **ON UPDATE rules** (CASCADE, NO_ACTION, SET_NULL, SET_DEFAULT)
  - Referenced table and column changes
- **CHECK constraints** with expressions
- UNIQUE constraints (multi-column support)

### ✅ Indexes
- Missing/extra indexes
- **Index type changes** (CLUSTERED, NONCLUSTERED, COLUMNSTORE, HEAP)
- **Uniqueness changes**
- Column composition changes
- Multi-column index ordering

---

## 🔍 MSSQL-Specific Features

### 1. IDENTITY Detection
```sql
-- Both patterns are detected as auto-increment:
CREATE TABLE users (
    id INT IDENTITY(1,1) PRIMARY KEY,     -- Detected ✅
    bigid BIGINT IDENTITY(100,5)          -- Detected ✅
);
```

### 2. Foreign Key Cascade Rules
```sql
-- All rules are extracted and compared:
FOREIGN KEY (user_id) 
    REFERENCES users(id) 
    ON DELETE CASCADE           -- Detected ✅
    ON UPDATE NO_ACTION         -- Detected ✅
```

**Difference Detection:**
```
❌ Modified Constraint: fk_user_orders
   Reference: ON DELETE CASCADE
   Target:    ON DELETE NO_ACTION
```

### 3. CHECK Constraints
```sql
-- Full expression extraction:
CREATE TABLE products (
    price DECIMAL(10,2) CHECK (price > 0),                           -- Detected ✅
    status VARCHAR(20) CHECK (status IN ('active', 'inactive'))      -- Detected ✅
);
```

### 4. MSSQL Types
```sql
CREATE TABLE data (
    id UNIQUEIDENTIFIER DEFAULT NEWID(),  -- Detected as "uuid" ✅
    content VARBINARY(MAX),                -- Detected as "bytea" ✅
    metadata XML,                          -- Detected as "xml" ✅
    description NVARCHAR(MAX),             -- Detected as "varchar(max)" ✅
    price MONEY,                           -- Detected as "decimal" ✅
    created DATETIME2                      -- Detected as "timestamp" ✅
);
```

### 5. NVARCHAR Length Handling
```sql
-- MSSQL stores NVARCHAR as 2 bytes per character
CREATE TABLE test (
    name NVARCHAR(100)  -- max_length = 200, but detected as varchar(100) ✅
);
```

The extractor automatically divides `max_length` by 2 for NVARCHAR/NCHAR types.

### 6. Default Value Normalization
```sql
-- Input (MSSQL wraps in parentheses):
column_default = '((0))'
column_default = '(''active'')'
column_default = '(getdate())'

-- Normalized to:
'0'
'active'
'getdate()'
```

### 7. Advanced Index Types
```sql
-- Clustered index (table organization)
CREATE CLUSTERED INDEX idx_pk ON users(id);

-- Nonclustered index (standard)
CREATE NONCLUSTERED INDEX idx_email ON users(email);

-- Columnstore index (analytics)
CREATE COLUMNSTORE INDEX idx_analytics ON sales_data;
```

All index types are extracted and compared! ✅

### 8. Extended Properties (Comments)
```sql
-- Add table comment
EXEC sp_addextendedproperty 
    @name = N'MS_Description', 
    @value = N'User account information', 
    @level0type = N'SCHEMA', @level0name = N'dbo',
    @level1type = N'TABLE', @level1name = N'users';

-- Add column comment
EXEC sp_addextendedproperty 
    @name = N'MS_Description', 
    @value = N'User email address', 
    @level0type = N'SCHEMA', @level0name = N'dbo',
    @level1type = N'TABLE', @level1name = N'users',
    @level2type = N'COLUMN', @level2name = N'email';
```

Comments are extracted and can be compared! ✅

---

## 📊 Sample Output

```
═══════════════════════════════════════════════════════════
[-] SCHEMA SUMMARY: 12 Differences Found
═══════════════════════════════════════════════════════════
 |
 ├── [TABLES] 2 differences
 │   ├── ❌ Missing Table: new_feature_table
 │   └── ➕ Extra Table: deprecated_table
 │
 ├── [COLUMNS] 5 differences
 │   ├── ❌ Missing Column: users.email_verified (boolean)
 │   ├── ➕ Extra Column: users.legacy_id
 │   ├── ⚠ Modified Column: products.price
 │   │   └── Type mismatch: decimal(10,2) != decimal(12,2)
 │   └── ⚠ Modified Column: users.id
 │       └── AutoIncrement mismatch: true != false
 │
 ├── [CONSTRAINTS] 3 differences
 │   ├── ❌ Missing FK: fk_orders_user
 │   │   └── orders(user_id) -> users(id) ON DELETE CASCADE
 │   └── ⚠ Modified FK: fk_items_order
 │       └── DELETE rule changed: CASCADE -> NO_ACTION
 │
 └── [INDEXES] 2 differences
     ├── ❌ Missing Index: idx_users_email (NONCLUSTERED, UNIQUE)
     └── ⚠ Modified Index: idx_products_search
         └── Type changed: NONCLUSTERED -> CLUSTERED
```

---

## 🛠️ Build Commands

### Compile
```bash
mvn21 clean compile
```

### Package
```bash
mvn21 package -DskipTests
```

### Run Tests
```bash
mvn21 test
```

---

## 🐛 Troubleshooting

### Issue: "Docker not found"
**Solution:** When comparing SQL scripts, Docker is required to spin up temporary databases.
```bash
# Check Docker is running
docker ps

# If not installed, install Docker or use JDBC URLs for live databases
```

### Issue: "Connection refused"
**Solution:** Ensure SQL Server is running and accessible.
```bash
# Test connection
sqlcmd -S localhost -U sa -P YourPassword123
```

### Issue: "Login failed for user"
**Solution:** Check SQL Server authentication settings and password.
```bash
# Ensure SQL Server accepts SQL authentication (not just Windows auth)
# Password must meet complexity requirements
```

### Issue: "Unknown database type"
**Solution:** Use `--db-type mssql` (not `sqlserver`)
```bash
--db-type mssql  ✅
--db-type sqlserver ❌
```

---

## 📖 Advanced Examples

### Compare Across SQL Server Versions
```bash
# Compare SQL Server 2019 schema vs SQL Server 2022 schema
java21 -jar target/schemadiff2-2.0.0.jar \
    --db-type mssql \
    --reference schema_2019.sql \
    --image mcr.microsoft.com/mssql/server:2019-latest \
    --target schema_2022.sql \
    --image mcr.microsoft.com/mssql/server:2022-latest
```

### Check Migration Script
```bash
# Verify migration script transforms old to new schema
java21 -jar target/schemadiff2-2.0.0.jar \
    --db-type mssql \
    --reference production_schema.sql \
    --target production_schema.sql,migration_001.sql \
    --image mcr.microsoft.com/mssql/server:2022-latest
```

### Diff Specific Schema
```bash
# Compare only 'dbo' schema (default)
# For custom schema, modify MSSQLExtractor instantiation in code
```

---

## 📚 Documentation Files

- **MSSQL_EXTRACTOR_SUMMARY.md** - Technical implementation details
- **MSSQL_IMPLEMENTATION_COMPLETE.md** - Feature checklist
- **This file** - Quick start guide

---

## ✅ Verified Features

The MSSQL extractor has been implemented to handle:

- [x] 100+ tables from `apimgt/mssql.sql`
- [x] IDENTITY columns (auto-increment)
- [x] ON DELETE CASCADE relationships
- [x] ON UPDATE NO_ACTION relationships
- [x] CHECK constraints
- [x] Multi-column primary keys
- [x] Multi-column unique constraints
- [x] VARBINARY and IMAGE columns
- [x] XML columns
- [x] UNIQUEIDENTIFIER columns
- [x] NVARCHAR with proper length calculation
- [x] VARCHAR(MAX) and NVARCHAR(MAX)
- [x] MONEY and SMALLMONEY types
- [x] DATETIME, DATETIME2, SMALLDATETIME
- [x] All index types (CLUSTERED, NONCLUSTERED, COLUMNSTORE)
- [x] Unique vs non-unique indexes
- [x] Extended properties (comments)

---

## 🎉 You're Ready!

The MSSQL extractor is fully operational and ready to compare your SQL Server schemas with the same level of detail and accuracy as the MySQL and PostgreSQL extractors.

**Start comparing now:**
```bash
java21 -jar target/schemadiff2-2.0.0.jar \
    --db-type mssql \
    --reference apimgt/mssql.sql \
    --target your_modified_schema.sql \
    --image mcr.microsoft.com/mssql/server:2022-latest
```

Happy schema diffing! 🚀

