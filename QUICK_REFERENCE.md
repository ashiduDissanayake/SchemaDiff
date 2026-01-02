# 🚀 SchemaDiff 2.0 - Quick Reference Card

## Build & Run

```bash
# Build
mvn21 clean package -DskipTests

# Run (basic syntax)
java21 -jar target/schemadiff2-2.0.0.jar \
  --db-type <mysql|postgres|mssql|oracle|db2> \
  --reference <jdbc-url-or-file.sql> \
  --target <jdbc-url-or-file.sql> \
  [--image <docker-image>]
```

## Common Commands

### MySQL: Script vs Script
```bash
java21 -jar target/schemadiff2-2.0.0.jar \
  --db-type mysql \
  --reference apimgt/mysql.sql \
  --target apimgt/mysql_v2.sql \
  --image mysql:8.0
```

### PostgreSQL: Script vs Live
```bash
java21 -jar target/schemadiff2-2.0.0.jar \
  --db-type postgres \
  --reference apimgt/postgresql.sql \
  --target jdbc:postgresql://localhost:5432/mydb \
  --target-user postgres \
  --target-pass postgres \
  --image postgres:17.0
```

### MSSQL: Live vs Live
```bash
java21 -jar target/schemadiff2-2.0.0.jar \
  --db-type mssql \
  --reference jdbc:sqlserver://prod:1433;databaseName=db1;encrypt=false \
  --ref-user sa \
  --ref-pass YourStrong@Pass1 \
  --target jdbc:sqlserver://staging:1433;databaseName=db2;encrypt=false \
  --target-user sa \
  --target-pass YourStrong@Pass2
```

### Oracle: Script vs Script (requires ojdbc driver)
```bash
java21 -cp "ojdbc8.jar:target/schemadiff2-2.0.0.jar" com.schemadiff.SchemaDiffCLI \
  --db-type oracle \
  --reference apimgt/oracle.sql \
  --target apimgt/oracle_v2.sql \
  --image gvenzl/oracle-free:23-slim
```

## Supported Features by Database

| Feature | MySQL | PostgreSQL | MSSQL | Oracle |
|---------|-------|------------|-------|--------|
| Tables, Columns, PKs, FKs, Uniques, Checks, Indexes | ✅ | ✅ | ✅ | ✅ |
| Auto-increment | ✅ | ✅ | ✅ | ✅ |
| Sequences | N/A | ✅ | ❌ | ❌ |
| Functions | ❌ | ✅ | ❌ | ❌ |
| Triggers | ❌ | ✅ | ✅ | ⚠️* |

*Oracle: Only auto-increment triggers

## Tested Docker Images

- **MySQL:** `mysql:8.0`, `mysql:8.4`
- **PostgreSQL:** `postgres:17.0`
- **MSSQL:** `mcr.microsoft.com/mssql/server:2022-latest`
- **Oracle:** `gvenzl/oracle-free:23-slim`
- **DB2:** `ibmcom/db2:11.5.0.0a`

## Exit Codes

- **0** = Success (no differences or comparison completed)
- **1** = Differences found
- **2** = Error (connection, provisioning, extraction, or docker)

## Recent Fixes ✅

✅ Oracle image compatibility (`gvenzl/oracle-free` now supported)  
✅ MSSQL prelogin warnings suppressed  
✅ MSSQL trigger extraction confirmed working  
✅ Oracle JDBC verbose logging suppressed  
✅ Clean production-ready output  

## Troubleshooting

**Oracle driver missing?**
```bash
# Add ojdbc8.jar to classpath
java21 -cp "ojdbc8.jar:target/schemadiff2-2.0.0.jar" com.schemadiff.SchemaDiffCLI [options]
```

**MSSQL password error?**
Use strong password: Uppercase + Lowercase + Digits + Special chars

**PostgreSQL schema not found?**
Use `public` schema or specify in JDBC URL

## Documentation

- **README.md** - How to use the tool
- **ARCHITECTURE.md** - Technical deep dive
- **COMPLETION_REPORT.md** - What was fixed
- **test-all.sh** - Automated test suite

---

**Need help?** Check the full README.md or ARCHITECTURE.md

