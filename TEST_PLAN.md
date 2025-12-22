# MySQL Support Testing Plan

This document outlines the testing strategy for verifying the `MySQLExtractor` and general MySQL support in SchemaDiff.

## 1. Unit Testing (Mock-based)

Since the extraction logic relies on JDBC `ResultSet` calls, we can use Mockito to simulate database responses. This allows us to verify the extractor's logic without needing a running database instance.

### Test Coverage Checklist

#### Column Definitions
- [x] **Integer Types**: Verify `INT`, `BIGINT`, `SMALLINT`, `TINYINT`.
- [x] **Unsigned Attribute**: Verify `UNSIGNED` flag is correctly appended (Fix verified).
- [x] **String Types**: Verify `VARCHAR`, `CHAR`, `TEXT`. Check length handling.
- [ ] **Decimal/Numeric**: Verify `DECIMAL(p,s)`.
- [ ] **Time Types**: Verify `TIMESTAMP`, `DATETIME`, `DATE`.
- [ ] **Nullability**: Verify `NOT NULL` vs `NULL`.
- [ ] **Default Values**: Verify default value extraction.

#### Constraints
- [x] **Primary Keys**: Verify PK extraction and column mapping.
- [x] **Foreign Keys**:
    - Verify basic FK extraction.
    - Verify multiple FKs between the same two tables (Fix verified).
    - Verify composite FKs (multiple columns).

#### Indexes
- [ ] **Non-Unique Indexes**: Verify basic index extraction.
- [ ] **Unique Indexes**: Verify `UNIQUE` constraint/index extraction.
- [ ] **Composite Indexes**: Verify indexes spanning multiple columns.

## 2. Integration Testing (Docker/Testcontainers)

This layer verifies the interaction with a real MySQL database. It requires a Docker environment.

### Scenarios
1.  **Script vs Script**:
    -   Ref: `ref.sql` (Baseline schema).
    -   Target: `target.sql` (Modified schema).
    -   Expected: Tool detects differences matching the modifications.

2.  **Live vs Live**:
    -   Spin up two MySQL containers.
    -   Provision them with different schemas.
    -   Run SchemaDiff connecting to both via JDBC.
    -   Expected: Accurate diff report.

### Key MySQL-Specific Checks
-   Case sensitivity handling (Linux vs Windows MySQL defaults).
-   `AUTO_INCREMENT` ignoring (usually we don't diff auto-increment current values, but schema definition yes).
-   Character Set / Collation differences (if supported).

## 3. Manual / CLI Testing

Steps to manually verify using the built JAR.

1.  **Build the project**: `mvn clean package`.
2.  **Prepare Database**: Ensure a local MySQL is running.
3.  **Run Command**:
    ```bash
    java -jar target/schemadiff2-2.0.0.jar \
      --reference "jdbc:mysql://localhost:3306/db1" --ref-user root --ref-pass password \
      --target "jdbc:mysql://localhost:3306/db2" --target-user root --target-pass password \
      --db-type mysql
    ```
