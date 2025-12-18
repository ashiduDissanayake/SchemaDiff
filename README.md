# 🚀 DriftMaster - Production Schema Drift Detector

## 📋 Overview

**DriftMaster** is a production-ready CLI tool that detects **structural** and **logic drift** between database schemas using: 

- **Liquibase Free API** (structural:  tables, columns, indexes, constraints, keys)
- **Custom Sidecar Logic Comparator** (triggers, stored procedures, functions)
- **Ephemeral Testcontainers** (automatic cleanup)

Supports:  **Oracle XE, PostgreSQL, MySQL, MSSQL, DB2**

---

## 🏗️ Architecture

```
┌─────────────────┐
│   Reference DB  │ (Live JDBC)
└────────┬────────┘
         │
         ▼
    ┌────────────────────┐
    │  DriftMaster CLI   │
    └────────┬───────────┘
             │
         ┌───▼────────────────┐
         │   Target Schema    │
         ├────────────────────┤
         │ Option A: . sql     │ ──► Ephemeral Container
         │ Option B: Live DB  │
         └────────────────────┘
                  │
         ┌────────▼────────┐
         │  Structural     │ (Liquibase)
         │  Diff Engine    │
         └─────────────────┘
                  │
         ┌────────▼────────┐
         │  Logic Diff     │ (Custom SQL)
         │  Engine         │
         └─────────────────┘
                  │
         ┌────────▼────────┐
         │  Unified JSON   │
         │  Report         │
         └─────────────────┘
```

---

## 🛠️ Build

```bash
mvn clean package
```

This creates:  `target/schema-drift-detector-1.0-SNAPSHOT.jar`

---

## 🎯 Usage

### **Option 1: Compare Live DB vs . sql File**

```bash
java -jar target/schema-drift-detector-1.0-SNAPSHOT.jar \
  --reference jdbc:postgresql://localhost:5432/prod_db \
  --reference-user admin \
  --reference-pass secret \
  --target-schema ./schema. sql \
  --db postgres
```

### **Option 2: Compare Two Live Databases**

```bash
java -jar target/schema-drift-detector-1.0-SNAPSHOT.jar \
  --reference jdbc:mysql://prod-host:3306/mydb \
  --reference-user root \
  --reference-pass rootpass \
  --target-jdbc jdbc:mysql://dev-host:3306/mydb \
  --target-user root \
  --target-pass rootpass \
  --db mysql
```

### **Oracle Example**

```bash
java -jar target/schema-drift-detector-1.0-SNAPSHOT.jar \
  --reference jdbc:oracle: thin:@localhost:1521:xe \
  --reference-user system \
  --reference-pass oracle \
  --target-schema ./oracle_schema.sql \
  --db oracle
```

---

## 📊 Output Format

```json
{
  "structuralDrift": {
    "missingTables": ["users", "orders"],
    "missingColumns": ["users.email", "orders.status"],
    "missingIndexes": ["idx_user_email"],
    "missingPrimaryKeys": ["pk_orders"],
    "missingForeignKeys": ["fk_order_user"],
    "unexpectedTables": ["temp_table"],
    "changedColumns": ["users.age"]
  },
  "logicDrift": {
    "missingLogic": ["TRIGGER: audit_trigger"],
    "changedLogic": ["PROCEDURE:calculate_total"],
    "unexpectedLogic":  []
  },
  "summary":  {
    "missingTables": 2,
    "missingColumns": 2,
    "changedTables": 0,
    "missingLogic": 1,
    "changedLogic": 1
  }
}
```

---

## 🔍 Exit Codes

- `0` - No drift detected
- `1` - Drift detected
- `2` - Error during execution

---

## 🧪 Testing with Provided SQL

Use the included `mysql. sql` file:

```bash
# 1. Create a reference MySQL instance
docker run -d --name mysql-ref \
  -e MYSQL_ROOT_PASSWORD=root \
  -e MYSQL_DATABASE=testdb \
  -p 3306:3306 mysql:8

# 2. Load the schema
mysql -h 127.0.0.1 -u root -proot testdb < mysql.sql

# 3. Compare with modified version
# (Edit mysql.sql to add/remove a table)
java -jar target/schema-drift-detector-1.0-SNAPSHOT.jar \
  --reference jdbc:mysql://localhost:3306/testdb \
  --reference-user root \
  --reference-pass root \
  --target-schema ./mysql_modified.sql \
  --db mysql
```

---

## 🛡️ Features

✅ **Structural Drift Detection**
- Tables, columns, indexes
- Primary keys, foreign keys, unique constraints
- Uses Liquibase Free API (no Pro/CLI)

✅ **Logic Drift Detection**
- Triggers
- Stored procedures
- Functions
- Uses SHA-256 hashing after normalization

✅ **Ephemeral Containers**
- Testcontainers for . sql provisioning
- Auto-cleanup on success/failure/JVM shutdown

✅ **Multi-Database Support**
- Oracle XE (`gvenzl/oracle-xe`)
- PostgreSQL (`postgres: 15-alpine`)
- MySQL (`mysql:8`)
- MSSQL (`mcr.microsoft.com/mssql/server:2022-latest`)
- DB2 (`icr.io/db2_community/db2:latest`)

✅ **Production-Ready**
- No TODOs or stubs
- Proper error handling
- Structured JSON output
- Clean shutdown hooks

---

## 🚨 Troubleshooting

### Docker Not Running
```
Error: Could not find a valid Docker environment
Solution: Start Docker Desktop or Docker daemon
```

### JDBC Driver Not Found
```
Error: No suitable driver found for jdbc:oracle... 
Solution:  Ensure ojdbc11 is in dependencies (already included)
```

### Permission Denied on System Catalogs
```
Error: SELECT permission denied on sys.triggers
Solution: Grant read permissions to system catalogs
```

---

## 📝 Design Decisions

1. **Why Liquibase Free?**
   - Community edition supports structural comparison
   - No licensing costs
   - Mature and stable

2. **Why Custom Logic Comparator?**
   - Liquibase Free doesn't detect triggers/procedures/functions
   - Hash-based comparison avoids SQL parsing complexity

3. **Why Testcontainers?**
   - Truly ephemeral (no leftover containers)
   - Automatic resource cleanup
   - Consistent across environments

4. **Why SHA-256 Hashing?**
   - Fast and deterministic
   - Avoids false positives from whitespace/comments
   - Industry-standard collision resistance

---

## 🎓 Architecture Notes

### Structural Diff Flow
```
Reference DB ──► Liquibase Database Object
                          │
Target DB ────► Liquibase Database Object
                          │
                 ┌────────▼────────┐
                 │ DiffGenerator   │
                 │ (Liquibase API) │
                 └────────┬────────┘
                          │
                 ┌────────▼────────┐
                 │   DiffResult    │
                 └─────────────────┘
```

### Logic Diff Flow
```
Reference DB ──► System Catalog Query ──► Raw Source
                                              │
                                      Normalize (remove comments,
                                      whitespace, lowercase)
                                              │
                                          SHA-256
                                              │
Target DB ────► Same Process ────────────► Compare Hashes
```

---

## 🔧 Extending

### Add New Database Type

1. Create `containers/NewDBContainer.java`
2. Add JDBC driver to `pom.xml`
3. Add queries to `LogicQueryProvider.java`
4. Update `DatabaseType` enum in `DriftMaster.java`

### Custom Output Format

Modify `DriftReport.toJson()` to support CSV/XML/HTML. 

---

## 📜 License

MIT License - Free for commercial use

---

## 🙏 Credits

Built with: 
- Liquibase (Apache 2.0)
- Testcontainers (MIT)
- Picocli (Apache 2.0)
- Jackson (Apache 2.0)

---

**Questions?  Open an issue or PR!  🚀**