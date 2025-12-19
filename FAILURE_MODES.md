# Database Failure Modes and Risk Analysis

This document outlines potential failure points within the `SchemaDiff` (DriftMaster) system, identified through code analysis of the container managers and provisioning engine.

## Systemic Risks

### 1. Naive Schema Provisioning
The `ProvisioningEngine.java` reads the entire SQL schema file into a single string and executes it via `Statement.execute(sql)`.

```java
String sql = Files.readString(sqlFile.toPath());
// ...
stmt.execute(sql);
```

**Why this fails:**
*   **Memory Usage:** Large schema files are loaded entirely into memory, posing an OOM risk.
*   **Batch Separators:** Standard JDBC `execute()` does not natively handle script delimiters used in complex schemas:
    *   **Oracle:** Uses `/` to terminate PL/SQL blocks. Passing a script with `/` to `execute()` often causes syntax errors.
    *   **MSSQL:** Uses `GO` as a batch separator (a client-side command, not T-SQL). JDBC does not recognize `GO` and will throw syntax errors.
    *   **DB2/Postgres:** Multiple statements in a single string may fail if the driver is not explicitly configured to allow them (though some drivers are more lenient).

## Database-Specific Risks

### MySQL
*   **Key Length Limit:** MySQL 8.0 defaults to `utf8mb4` (4 bytes/char). WSO2 schemas often define `VARCHAR(1024)` indexes, which exceed the 3072-byte InnoDB limit (`1024 * 4 > 3072`).
    *   *Mitigation:* The `MySQLContainer` code now enforces `latin1` charset, but this must be maintained.
*   **Multi-Query Support:** The provisioning engine relies on the JDBC URL parameter `allowMultiQueries=true` to execute the schema script. If this is removed or overridden, provisioning will fail.

### Oracle
*   **Container Version:** Uses `gvenzl/oracle-xe:latest`. The `latest` tag is unstable and may introduce breaking changes or incompatibilities with the JDBC driver (`ojdbc11`).
*   **PL/SQL Delimiters:** The `apimgt/oracle.sql` file heavily uses `/` to separate triggers and procedures. The current `ProvisioningEngine` will likely fail to execute this file correctly.
*   **XE Limitations:** The Express Edition (XE) has resource limits (RAM/CPU) and feature limitations compared to Enterprise Edition, which might cause failures for complex production schemas.

### Microsoft SQL Server (MSSQL)
*   **Batch Separators (`GO`):** The `apimgt/mssql.sql` file uses `GO` extensively. The JDBC driver will fail to execute these scripts via a single `stmt.execute()` call.
*   **Container Version:** Uses `mcr.microsoft.com/mssql/server:2022-latest`. `latest` tag implies potential instability.
*   **EULA:** The container accepts the EULA automatically, but changes to licensing terms in the image could theoretically break the boot process.

### PostgreSQL
*   **Version Pinning:** Uses `postgres:15-alpine`. If the target schema uses features from newer versions (16+) or relies on behavior deprecated in 15, it will fail.
*   **Alpine Linux:** Alpine images use `musl` libc instead of `glibc`. While usually fine for the database process, it can sometimes cause subtle compatibility issues with certain extensions or locales.

### DB2
*   **Container Version:** Uses `icr.io/db2_community/db2:latest`. `latest` tag instability.
*   **Multi-Statement Execution:** Similar to other DBs, executing a large script with mixed DDL/DML in a single call is prone to failure without a proper script parser/splitter.
