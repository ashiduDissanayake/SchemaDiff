# PostgreSQL Schema Comparison - Quick Start Guide

## ✅ Installation Complete

The PostgreSQL extractor has been successfully built and integrated into SchemaDiff.

---

## 🚀 Quick Usage

### 1. Compare Two PostgreSQL SQL Scripts

```bash
java21 -jar target/schemadiff2-2.0.0.jar \
    --db-type postgres \
    --reference apimgt/postgresql.sql \
    --target apimgt/postgresql_modified.sql \
    --image postgres:15
```

### 2. Compare Live PostgreSQL Databases

```bash
java21 -jar target/schemadiff2-2.0.0.jar \
    --db-type postgres \
    --reference "jdbc:postgresql://localhost:5432/ref_db" \
    --ref-user myuser \
    --ref-pass mypass \
    --target "jdbc:postgresql://localhost:5432/target_db" \
    --target-user myuser \
    --target-pass mypass
```

### 3. Compare Script vs Live Database

```bash
java21 -jar target/schemadiff2-2.0.0.jar \
    --db-type postgres \
    --reference apimgt/postgresql.sql \
    --image postgres:15 \
    --target "jdbc:postgresql://prod.server.com:5432/prod_db" \
    --target-user readonly \
    --target-pass secret
```

---

## 🎯 What Gets Detected

### ✅ Tables
- Missing tables
- Extra tables
- Table comments

### ✅ Columns
- Missing/extra columns
- Data type changes (VARCHAR, INT, BIGINT, BYTEA, JSONB, UUID, etc.)
- NOT NULL changes
- **Auto-increment changes (SERIAL/BIGSERIAL)**
- **Default value changes**
- Column comments

### ✅ Constraints
- Primary keys (single and multi-column)
- Foreign keys with:
  - **ON DELETE rules** (CASCADE, SET NULL, NO ACTION, RESTRICT)
  - **ON UPDATE rules** (CASCADE, SET NULL, NO ACTION, RESTRICT)
  - Referenced table and column changes
- **CHECK constraints** with expressions
- UNIQUE constraints (multi-column support)

### ✅ Indexes
- Missing/extra indexes
- **Index type changes** (BTREE, HASH, GIN, GIST, BRIN, SPGIST)
- **Uniqueness changes**
- Column composition changes
- Multi-column index ordering

---

## 🔍 PostgreSQL-Specific Features

### 1. SERIAL Detection
```sql
-- Both patterns are detected as auto-increment:
CREATE TABLE users (
    id SERIAL PRIMARY KEY,           -- Detected ✅
    bigid BIGSERIAL                   -- Detected ✅
);

-- Equivalent to:
CREATE TABLE users (
    id INTEGER DEFAULT NEXTVAL('users_id_seq') PRIMARY KEY,
    bigid BIGINT DEFAULT NEXTVAL('users_bigid_seq')
);
```

### 2. Foreign Key Cascade Rules
```sql
-- All rules are extracted and compared:
FOREIGN KEY (user_id) 
    REFERENCES users(id) 
    ON DELETE CASCADE           -- Detected ✅
    ON UPDATE RESTRICT          -- Detected ✅
```

**Difference Detection:**
```
❌ Modified Constraint: fk_user_orders
   Reference: ON DELETE CASCADE
   Target:    ON DELETE SET NULL
```

### 3. CHECK Constraints
```sql
-- Full expression extraction:
CREATE TABLE products (
    price DECIMAL(10,2) CHECK (price > 0),           -- Detected ✅
    status VARCHAR(20) CHECK (status IN ('active', 'inactive'))  -- Detected ✅
);
```

### 4. PostgreSQL Types
```sql
CREATE TABLE data (
    id UUID PRIMARY KEY,              -- Detected as "uuid" ✅
    content BYTEA,                    -- Detected as "bytea" ✅
    metadata JSONB,                   -- Detected as "jsonb" ✅
    description TEXT,                 -- Detected as "text" ✅
    created TIMESTAMP WITH TIME ZONE  -- Detected as "timestamptz" ✅
);
```

### 5. Advanced Index Types
```sql
-- GIN for full-text search
CREATE INDEX idx_search USING GIN (to_tsvector('english', content));

-- GIST for geometric data
CREATE INDEX idx_location USING GIST (location);

-- Hash for equality searches
CREATE INDEX idx_hash USING HASH (user_id);
```

All index types are extracted and compared! ✅

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
 │   └── ⚠ Modified Column: products.price
 │       └── Type mismatch: numeric(10,2) != numeric(12,2)
 │
 ├── [CONSTRAINTS] 3 differences
 │   ├── ❌ Missing FK: fk_orders_user
 │   │   └── orders(user_id) -> users(id) ON DELETE CASCADE
 │   └── ⚠ Modified FK: fk_items_order
 │       └── DELETE rule changed: CASCADE -> SET NULL
 │
 └── [INDEXES] 2 differences
     ├── ❌ Missing Index: idx_users_email (BTREE, UNIQUE)
     └── ⚠ Modified Index: idx_products_search
         └── Type changed: BTREE -> GIN
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
**Solution:** Ensure PostgreSQL is running and accessible.
```bash
# Test connection
psql -h localhost -p 5432 -U myuser -d mydb
```

### Issue: "Unknown database type"
**Solution:** Use `--db-type postgres` (not `postgresql`)
```bash
--db-type postgres  ✅
--db-type postgresql ❌
```

---

## 📖 Advanced Examples

### Compare Across PostgreSQL Versions
```bash
# Compare PostgreSQL 12 schema vs PostgreSQL 15 schema
java21 -jar target/schemadiff2-2.0.0.jar \
    --db-type postgres \
    --reference schema_pg12.sql \
    --image postgres:12 \
    --target schema_pg15.sql \
    --image postgres:15
```

### Check Migration Script
```bash
# Verify migration script transforms old to new schema
java21 -jar target/schemadiff2-2.0.0.jar \
    --db-type postgres \
    --reference production_schema.sql \
    --target production_schema.sql,migration_001.sql \
    --image postgres:15
```

### Diff Specific Schema
```bash
# Compare only 'public' schema (default)
# For custom schema, modify PostgresExtractor instantiation in code
```

---

## 📚 Documentation Files

- **POSTGRESQL_EXTRACTOR_SUMMARY.md** - Technical implementation details
- **POSTGRESQL_IMPLEMENTATION_COMPLETE.md** - Feature checklist
- **This file** - Quick start guide

---

## ✅ Verified Features

The PostgreSQL extractor has been tested and verified to handle:

- [x] 100+ tables from `apimgt/postgresql.sql`
- [x] SERIAL and BIGSERIAL columns
- [x] ON DELETE CASCADE relationships
- [x] ON UPDATE CASCADE relationships
- [x] CHECK constraints
- [x] Multi-column primary keys
- [x] Multi-column unique constraints
- [x] BYTEA (binary) columns
- [x] JSONB columns
- [x] UUID columns
- [x] TEXT columns
- [x] TIMESTAMP WITH/WITHOUT TIME ZONE
- [x] All standard index types (BTREE, HASH, GIN, GIST)
- [x] Unique vs non-unique indexes

---

## 🎉 You're Ready!

The PostgreSQL extractor is fully operational and ready to compare your PostgreSQL schemas with the same level of detail and accuracy as the MySQL extractor.

**Start comparing now:**
```bash
java21 -jar target/schemadiff2-2.0.0.jar \
    --db-type postgres \
    --reference apimgt/postgresql.sql \
    --target your_modified_schema.sql \
    --image postgres:15
```

Happy schema diffing! 🚀

