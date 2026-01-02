# 🎯 SchemaDiff 2.0 - Multi-Database Schema Comparison Tool

## Overview

**SchemaDiff 2.0** is a production-grade database schema comparison tool that detects structural differences between database schemas. It supports **same-database-type comparisons** across multiple database platforms with zero Liquibase dependency.

> 📘 **For technical details, architecture, and database-specific logic**, see [ARCHITECTURE.md](ARCHITECTURE.md)

### Key Capabilities

- ✅ **Four Operational Modes**: Script vs Script, Script vs Live, Live vs Script, Live vs Live
- ✅ **Multi-Database Support**: MySQL, PostgreSQL, Oracle, MSSQL, DB2
- ✅ **Extended PostgreSQL Support**: Sequences, Functions, Triggers
- ✅ **MSSQL Trigger Support**: Full extraction of AFTER and INSTEAD OF triggers
- ✅ **Oracle Trigger Detection**: Auto-increment via SEQUENCE + TRIGGER pattern
- ✅ **Hierarchical Tree Output**: Beautiful ASCII tree visualization
- ✅ **Signature-Based Constraint Matching**: No reliance on volatile constraint names
- ✅ **Docker Integration**: Automatic ephemeral container lifecycle management
- ✅ **Production Ready**: Comprehensive error handling and validation

---

## 🚀 Quick Start

### Prerequisites

- **Java 21** or higher
- **Maven 3.x** (for building)
- **Docker** (for Script vs Script mode)

### Build

```bash
# Using Maven 3.x with Java 21
mvn21 clean package
```

This creates: `target/schemadiff2-2.0.0.jar`

---

## 📋 Supported Databases

| Database   | Version Tested           | Docker Image                          | Port | Notes                                    |
|------------|--------------------------|---------------------------------------|------|------------------------------------------|
| MySQL      | 8.0, 8.4                 | `mysql:8.0`, `mysql:8.4`              | 3306 | Standard support                         |
| PostgreSQL | 17.0                     | `postgres:17.0`                       | 5432 | **Extended**: Sequences, Functions, Triggers |
| MSSQL      | 2022                     | `mcr.microsoft.com/mssql/server:2022-latest` | 1433 | Requires strong password & encryption handling |
| Oracle     | 23c Free                 | `gvenzl/oracle-free:23-slim`          | 1521 | **Trigger-based auto-increment detection**, Requires ojdbc driver |
| DB2        | 11.5                     | `ibmcom/db2:11.5.0.0a`                | 50000| Basic support                            |

### Database-Specific Features

| Feature                | MySQL | PostgreSQL | MSSQL | Oracle | DB2 |
|------------------------|-------|------------|-------|--------|-----|
| Tables                 | ✅    | ✅         | ✅    | ✅     | ✅  |
| Columns                | ✅    | ✅         | ✅    | ✅     | ✅  |
| Primary Keys           | ✅    | ✅         | ✅    | ✅     | ✅  |
| Foreign Keys           | ✅    | ✅         | ✅    | ✅     | ✅  |
| Unique Constraints     | ✅    | ✅         | ✅    | ✅     | ✅  |
| Check Constraints      | ✅    | ✅         | ✅    | ✅     | ✅  |
| Indexes                | ✅    | ✅         | ✅    | ✅     | ✅  |
| Auto-increment         | ✅    | ✅         | ✅    | ✅     | ✅  |
| **Sequences**          | N/A   | ✅         | ❌    | ❌     | ❌  |
| **Functions**          | ❌    | ✅         | ❌    | ❌     | ❌  |
| **Triggers**           | ❌    | ✅         | ✅    | ⚠️ *   | ❌  |

**Notes:**
- MySQL uses `AUTO_INCREMENT` instead of sequences
- Oracle ⚠️: Only extracts triggers used for auto-increment logic (BEFORE INSERT with NEXTVAL pattern)
- PostgreSQL has the most comprehensive feature coverage
- MSSQL: Full trigger extraction support (AFTER, INSTEAD OF triggers)

---

## 🎮 The 4 Modes of Operation

### Mode 1: Script vs Script
**Use Case**: Compare two SQL schema files (e.g., versions 1.0 vs 2.0)

```bash
java21 -jar target/schemadiff2-2.0.0.jar \
  --reference apimgt/mysql.sql \
  --target apimgt/mysql_v2.sql \
  --db-type mysql \
  --image mysql:8.0
```

**How it works**: 
1. Spins up a Docker container with the specified image
2. Provisions both SQL scripts into separate schemas
3. Extracts metadata from both schemas
4. Compares and reports differences
5. Automatically cleans up the container

---

### Mode 2: Script vs Live
**Use Case**: Compare expected schema (from file) against a production database

```bash
java21 -jar target/schemadiff2-2.0.0.jar \
  --reference apimgt/postgresql.sql \
  --target jdbc:postgresql://prod-server:5432/wso2am_db \
  --target-user readonly \
  --target-pass s3cr3t \
  --db-type postgres \
  --image postgres:17.0
```

**How it works**:
1. Spins up Docker container for the reference script
2. Connects to the live target database via JDBC
3. Extracts metadata from both
4. Compares and reports differences

---

### Mode 3: Live vs Script
**Use Case**: Compare current production against a new proposed schema

```bash
java21 -jar target/schemadiff2-2.0.0.jar \
  --reference jdbc:mysql://prod-server:3306/wso2am_db \
  --ref-user admin \
  --ref-pass admin123 \
  --target apimgt/mysql_new_feature.sql \
  --db-type mysql \
  --image mysql:8.4
```

**How it works**:
1. Connects to the live reference database
2. Spins up Docker container for the target script
3. Extracts metadata from both
4. Compares and reports differences

---

### Mode 4: Live vs Live
**Use Case**: Compare two live databases (e.g., prod vs staging)

```bash
java21 -jar target/schemadiff2-2.0.0.jar \
  --reference jdbc:postgresql://prod:5432/wso2am_db \
  --ref-user admin \
  --ref-pass prod_pass \
  --target jdbc:postgresql://staging:5432/wso2am_db \
  --target-user admin \
  --target-pass staging_pass \
  --db-type postgres
```

**How it works**:
1. Connects directly to both live databases via JDBC
2. Extracts metadata from both
3. Compares and reports differences
4. No Docker containers needed

---

## 📊 Sample Output

```
═══════════════════════════════════════════════════════════
[-] SCHEMA SUMMARY: 12 Differences Found
═══════════════════════════════════════════════════════════
 |
 ├── [X] MISSING TABLES (1)
 │   └── IDN_OAUTH_CONSUMER_APPS
 |
 ├── [+] EXTRA TABLES (1)
 │   └── TEMP_MIGRATION_LOG
 |
 ├── [M] COLUMN DIFFERENCES
 │   ├── [!] Table: USERS
 │   │   ├── [X] Missing Column: email [varchar(255)]
 │   │   └── [M] Modified Column: password_hash
 │   │       └── Type mismatch: varchar(255) != varchar(128)
 │   └── [!] Table: ORDERS
 │       └── [+] Extra Column: tracking_code
 |
 ├── [C] CONSTRAINT DIFFERENCES
 │   └── [!] Table: ORDERS
 │       ├── [X] Missing Constraint: FK_ORDERS_USERS
 │       │   └── FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
 │       └── [M] Modified Constraint: CHK_ORDER_STATUS
 │           └── CHECK condition differs
 |
 └── [I] INDEX DIFFERENCES
     └── [!] Table: ORDERS
         ├── [X] Missing Index: idx_order_date
         │   └── Columns: order_date
         └── [+] Extra Index: idx_created_at
             └── Columns: created_at

═══════════════════════════════════════════════════════════
Legend:
  [-] Root summary
  [X] Missing: Exists in Reference but not in Target
  [+] Extra: Exists in Target but not in Reference
  [M] Modified: Exists in both but with differences
  [!] Parent marker
═══════════════════════════════════════════════════════════
```

---

## 🔧 Command-Line Options

### Required Parameters

| Parameter     | Description                                      | Example                              |
|---------------|--------------------------------------------------|--------------------------------------|
| `--reference` | Reference source (JDBC URL or .sql file path)    | `jdbc:mysql://localhost:3306/db1` or `schema.sql` |
| `--target`    | Target source (JDBC URL or .sql file path)       | `jdbc:mysql://localhost:3306/db2` or `schema2.sql` |
| `--db-type`   | Database type                                    | `mysql`, `postgres`, `oracle`, `mssql`, `db2` |

### Optional Parameters (for JDBC connections)

| Parameter        | Description                          | Default  |
|------------------|--------------------------------------|----------|
| `--ref-user`     | Username for reference database      | `root` or `postgres` |
| `--ref-pass`     | Password for reference database      | (empty)  |
| `--target-user`  | Username for target database         | `root` or `postgres` |
| `--target-pass`  | Password for target database         | (empty)  |

### Optional Parameters (for Docker)

| Parameter | Description                   | Default                                  |
|-----------|-------------------------------|------------------------------------------|
| `--image` | Docker image to use           | Auto-detected based on `--db-type`       |

---

## 🛠️ Driver Requirements

### MySQL
- **Driver**: Included in the shaded JAR
- **JDBC URL Format**: `jdbc:mysql://host:port/database`

### PostgreSQL
- **Driver**: Included in the shaded JAR
- **JDBC URL Format**: `jdbc:postgresql://host:port/database`
- **Schema**: Uses `public` schema by default

### MSSQL
- **Driver**: Included in the shaded JAR
- **JDBC URL Format**: `jdbc:sqlserver://host:port;databaseName=dbname;encrypt=false;trustServerCertificate=true`
- **Note**: Requires strong password (uppercase, lowercase, digits, special chars) and encryption handling

### Oracle
- **Driver**: **NOT included** - You must provide `ojdbc8.jar` or `ojdbc11.jar`
- **JDBC URL Format**: `jdbc:oracle:thin:@host:port:SID` or `jdbc:oracle:thin:@host:port/SERVICE`
- **How to add driver**: 
  ```bash
  # Option 1: Add to classpath
  java21 -cp "ojdbc8.jar:target/schemadiff2-2.0.0.jar" com.schemadiff.Main [options]
  
  # Option 2: Install to Maven local repository
  mvn21 install:install-file -Dfile=ojdbc8.jar -DgroupId=com.oracle.database.jdbc \
    -DartifactId=ojdbc8 -Dversion=21.1.0.0 -Dpackaging=jar
  ```

### DB2
- **Driver**: Included in the shaded JAR
- **JDBC URL Format**: `jdbc:db2://host:port/database`

---

## 🧪 Testing Examples

### Test MySQL Schema Comparison
```bash
# Script vs Script
java21 -jar target/schemadiff2-2.0.0.jar \
  --reference apimgt/mysql.sql \
  --target test-data/mysql_target.sql \
  --db-type mysql \
  --image mysql:8.0
```

### Test PostgreSQL with Extended Features
```bash
# This will extract sequences, functions, and triggers
java21 -jar target/schemadiff2-2.0.0.jar \
  --reference apimgt/postgresql.sql \
  --target jdbc:postgresql://localhost:5432/test_db \
  --target-user postgres \
  --target-pass postgres \
  --db-type postgres \
  --image postgres:17.0
```

### Test MSSQL
```bash
java21 -jar target/schemadiff2-2.0.0.jar \
  --reference apimgt/mssql.sql \
  --target jdbc:sqlserver://localhost:1433;databaseName=testdb;encrypt=false;trustServerCertificate=true \
  --target-user sa \
  --target-pass YourStrong@Password123 \
  --db-type mssql \
  --image mcr.microsoft.com/mssql/server:2022-latest
```

### Test Oracle (requires ojdbc driver)
```bash
java21 -cp "ojdbc8.jar:target/schemadiff2-2.0.0.jar" com.schemadiff.Main \
  --reference apimgt/oracle.sql \
  --target jdbc:oracle:thin:@localhost:1521:FREE \
  --target-user system \
  --target-pass oracle \
  --db-type oracle \
  --image gvenzl/oracle-free:23-slim
```

---

## 🐛 Troubleshooting

### Docker Container Issues
- **Problem**: "Cannot connect to Docker daemon"
- **Solution**: Ensure Docker is running: `docker ps`

### Oracle Driver Not Found
- **Problem**: `ClassNotFoundException: oracle.jdbc.OracleDriver`
- **Solution**: Add `ojdbc8.jar` to classpath as shown in Driver Requirements section

### MSSQL Password Error
- **Problem**: "Password does not meet SQL Server password policy"
- **Solution**: Use a strong password with uppercase, lowercase, digits, and special characters

### PostgreSQL Schema Not Found
- **Problem**: "Schema 'WSO2AM_DB' does not exist"
- **Solution**: PostgreSQL schema names are case-sensitive. The tool uses the `public` schema. If your script creates a different schema, adjust the connection accordingly.

---

## 📝 Exit Codes

| Code | Meaning                                    |
|------|--------------------------------------------|
| 0    | Success - Comparison completed             |
| 1    | Error - Invalid arguments or configuration |
| 2    | Error - Database connection failed         |
| 3    | Error - SQL provisioning failed            |
| 4    | Error - Schema extraction failed           |
| 5    | Error - Docker operation failed            |

---

## 📄 License

This project is proprietary software. All rights reserved.

---

## 🤝 Support

For issues, questions, or feature requests, please contact the development team.

