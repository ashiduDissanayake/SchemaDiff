# 🎯 SchemaDiff 2.0 - Production Schema Comparison Tool

## 🚀 Overview

**SchemaDiff 2.0** is a production-grade schema comparison tool that detects structural differences between database schemas with **zero Liquibase dependency**.

### ✨ Key Features

- ✅ **Four Operational Modes**:  Script vs Script, Script vs Live, Live vs Script, Live vs Live
- ✅ **Hierarchical Tree Output**: Beautiful ASCII tree visualization
- ✅ **Signature-Based Constraint Matching**: No reliance on volatile constraint names
- ✅ **Level-Based Comparison**: Smart skipping of child objects when parent is missing
- ✅ **Multi-Database Support**: PostgreSQL, MySQL, Oracle, MSSQL, DB2
- ✅ **Docker Integration**: Automatic ephemeral container lifecycle management
- ✅ **Type Normalization**: Cross-database type comparison
- ✅ **Production Ready**: No TODOs, comprehensive error handling

---

## 🏗️ Architecture

```
Reference Source          Target Source
     │                         │
     ├─ JDBC URL              ├─ JDBC URL
     └─ . sql File             └─ .sql File
          │                        │
          ▼                        ▼
    ┌──────────────────────────────────┐
    │   Docker Container Manager       │
    │  (Automatic Lifecycle Control)   │
    └──────────────────────────────────┘
                   │
          ┌────────▼────────┐
          │ Metadata Extract │
          │   (Pure JDBC)    │
          └────────┬─────────┘
                   │
          ┌────────▼─────────────────┐
          │  Hierarchical Comparison │
          │  Level 1:  Tables         │
          │  Level 2: Columns        │
          │  Level 3: Constraints    │
          │  Level 4: Indexes        │
          └────────┬─────────────────┘
                   │
          ┌────────▼────────┐
          │  Tree Report    │
          │  Generator      │
          └─────────────────┘
```

---

## 🛠️ Build

```bash
mvn clean package
```

Creates:  `target/schemadiff2-2.0.0-shaded.jar`

---

## 🎯 Usage Examples

### **Mode 1: Script vs Script**

Compare two SQL schema files:

```bash
java -jar target/schemadiff2-2.0.0-shaded.jar \
  --reference schema_v1.sql \
  --target schema_v2.sql \
  --db-type mysql \
  --image mysql:8
```

### **Mode 2: Script vs Live**

Compare expected schema file against production database:

```bash
java -jar target/schemadiff2-2.0.0-shaded.jar \
  --reference expected_schema.sql \
  --target jdbc:postgresql://prod.example.com:5432/mydb \
  --target-user readonly \
  --target-pass s3cr3t \
  --db-type postgres \
  --image postgres:15
```

### **Mode 3: Live vs Script**

Compare production database against new schema file:

```bash
java -jar target/schemadiff2-2.0.0-shaded.jar \
  --reference jdbc:mysql://prod:3306/mydb \
  --ref-user admin \
  --ref-pass admin123 \
  --target new_feature_schema.sql \
  --db-type mysql \
  --image mysql:8
```

### **Mode 4: Live vs Live**

Compare two live databases (e.g., prod vs staging):

```bash
java -jar target/schemadiff2-2.0.0-shaded.jar \
  --reference jdbc:postgresql://prod:5432/mydb \
  --ref-user admin \
  --ref-pass prod_pass \
  --target jdbc:postgresql://staging:5432/mydb \
  --target-user admin \
  --target-pass staging_pass \
  --db-type postgres
```

---

## 📊 Sample Output

```
═══════════════════════════════════════════════════════════
[-] SCHEMA SUMMARY: 8 Differences Found
═══════════════════════════════════════════════════════════
 |
 ├── [X] MISSING TABLES (1)
 │   └── IDN_OAUTH_CONSUMER_APPS
 |
 ├── [+] EXTRA TABLES (1)
 │   └── TEMP_MIGRATION_LOG
 |
 ├── [M] COLUMN DIFFERENCES
 │   ├── [! ] Table:  USERS
 │   │   ├── [X] Missing Column:  email [varchar(255)]
 │   │   └── [M] Modified Column: password_hash - Type mismatch:  varchar(255) != varchar(128)
 │   └── [! ] Table: ORDERS
 │       └── [+] Extra Column: tracking_code
 |
 └── [C] CONSTRAINT DIFFERENCES
     └── [!] Table: ORDERS
         └── [X] Missing Constraint: FOREIGN_KEY

═══════════════════════════════════════════════════════════
Legend:
  [-] Root summary
  [X] Missing:  Exists in Reference but not in Target
  [+] Extra:  Exists in Target but not in Reference
  [M] Modified: Structural change detected
  [!] Warning: Requires attention
  [C] Constraint mismatch
  [I] Index mismatch
═══════════════════════════════════════════════════════════
```

---

## 🗂️ CLI Options

| Option | Description | Required |
|--------|-------------|----------|
| `--reference` | Reference JDBC URL or . sql file | Yes* |
| `--target` | Target JDBC URL or .sql file | Yes* |
| `--ref-user` | Reference DB username | If JDBC |
| `--ref-pass` | Reference DB password | If JDBC |
| `--target-user` | Target DB username | If JDBC |
| `--target-pass` | Target DB password | If JDBC |
| `--db-type` | Database: postgres, mysql, oracle, mssql, db2 | Yes |
| `--image` | Docker image (e.g., postgres:15, mysql:8) | If script |
