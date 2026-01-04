# E2E Testing Framework

This document describes the end-to-end testing framework for SchemaDiff.

## Overview

The E2E test framework validates SchemaDiff's schema comparison capabilities across multiple databases using Testcontainers for isolated, reproducible testing.

## Architecture

```
src/test/
├── java/com/schemadiff/e2e/
│   ├── base/                    # Shared test infrastructure
│   │   ├── AbstractMySQLTest.java
│   │   ├── AbstractPostgresTest.java
│   │   ├── AbstractMSSQLTest.java
│   │   ├── AbstractOracleTest.java
│   │   ├── MySQLTestContainer.java
│   │   ├── PostgresTestContainer.java
│   │   ├── MSSQLTestContainer.java
│   │   ├── OracleTestContainer.java
│   │   ├── TestContext.java
│   │   ├── SchemaDiffRunner.java
│   │   ├── DiffResultAssert.java
│   │   └── TestTags.java
│   ├── mysql/                   # MySQL-specific tests
│   │   ├── tables/
│   │   ├── columns/
│   │   ├── constraints/
│   │   └── indexes/
│   ├── postgres/                # PostgreSQL-specific tests
│   ├── mssql/                   # MSSQL-specific tests
│   └── oracle/                  # Oracle-specific tests
└── resources/
    ├── junit-platform.properties
    ├── mysql/
    │   ├── schemas/
    │   │   └── minimal_reference.sql
    │   └── testcases/
    │       ├── tables/
    │       │   ├── missing/delta.sql
    │       │   └── extra/delta.sql
    │       ├── columns/
    │       ├── constraints/
    │       └── indexes/
    ├── postgres/
    ├── mssql/
    └── oracle/
```

## Key Design Patterns

### 1. Singleton Container Pattern

Instead of spinning up new containers for every test:
- **ONE container per database type** is started at suite level
- Tests create **isolated databases within that container**
- Result: ~100x faster execution, reduced CI resource usage

```java
// Container is shared across all MySQL tests
MySQLTestContainer.getInstance(); // Lazy singleton

// Each test gets isolated databases
TestContext ctx = TestContext.create("my_test");
ctx.setupBothSchemas(MINIMAL_SCHEMA);
// Creates: ref_my_test_xxx, target_my_test_xxx databases
```

### 2. Database-per-Test Pattern

```java
// Each test creates unique database names
String uniqueId = counter + "_" + UUID.randomUUID().substring(0, 8);
refDbName = "ref_" + testName + "_" + uniqueId;
targetDbName = "target_" + testName + "_" + uniqueId;

// Cleanup after test
ctx.cleanup(); // Drops both databases
```

### 3. Delta Script Pattern

Tests apply "delta scripts" to create differences:

```sql
-- delta.sql: What changes to apply to target
DROP TABLE IF EXISTS USERS;
ALTER TABLE TOKENS DROP COLUMN EMAIL;
```

Test flow:
1. Load reference schema to both ref and target databases
2. Apply delta script to target only
3. Run comparison
4. Assert differences match expected

### 4. Golden Master Pattern

For complex output verification:

```java
// Update mode: creates expected files
// mvn test -DupdateSnapshots=true

// Normal mode: compares against expected
verifyAgainstExpected(result, "/mysql/testcases/tables/missing/expected.txt");
```

## Test Tags

Tests are tagged for selective execution:

### Speed Tags
- `@Fast` - Minimal schema tests (~100ms each)
- `@Slow` - Full schema tests (~seconds each)
- `@FullSchema` - Production schema tests

### Database Tags
- `@MySQL`, `@Postgres`, `@MSSQL`, `@Oracle`, `@DB2`

### Object Type Tags
- `@Tables`, `@Columns`, `@Constraints`, `@Indexes`

### Change Type Tags
- `@Missing`, `@Extra`, `@Modified`

### Special Tags
- `@Smoke` - Critical path validation
- `@Complex` - Multi-change scenarios
- `@CLI` - JAR execution tests

## Running Tests

### Using Maven

```bash
# Run all fast MySQL tests
mvn test -Dtest.groups="mysql & fast"

# Run all PostgreSQL tests
mvn test -Dtest.groups="postgres"

# Run table-related tests for all databases
mvn test -Dtest.groups="tables"

# Exclude slow tests
mvn test -Dtest.excludedGroups="slow"

# Update snapshots
mvn test -DupdateSnapshots=true
```

### Using Test Runner Script

```bash
# Fast MySQL tests
./run-e2e-tests.sh --mysql --fast

# Full PostgreSQL tests
./run-e2e-tests.sh --postgres --full

# All tests
./run-e2e-tests.sh --all

# Update snapshots
./run-e2e-tests.sh --update --mysql
```

## Writing New Tests

### 1. Create Delta Script

```sql
-- src/test/resources/mysql/testcases/tables/missing/delta.sql
DROP TABLE IF EXISTS USERS;
DROP TABLE IF EXISTS USER_ROLES;
```

### 2. Write Test Class

```java
@Fast
@Tables
@MySQL
class TableDifferenceTest extends AbstractMySQLTest {

    @Test
    @Missing
    void shouldDetectMissingTables(TestInfo testInfo) throws Exception {
        ctx = createContext(testInfo);
        ctx.setupBothSchemas(MINIMAL_SCHEMA);
        ctx.applyDelta("/mysql/testcases/tables/missing/delta.sql");

        DiffResult result = compareSchemas();

        assertThat(result)
            .hasDifferences()
            .hasMissingTable("USERS")
            .hasMissingTableCount(2);
    }
}
```

### 3. Custom Assertions

```java
DiffResultAssert.assertThat(result)
    .hasDifferences()
    .hasMissingTable("USERS")
    .hasExtraTable("DEBUG_LOG")
    .hasMissingConstraint("USERS", "FK_USER_ROLE")
    .hasModifiedColumn("USERS", "EMAIL")
    .hasTotalDifferences(5);
```

## CI Integration

### GitHub Actions Workflow

```yaml
# Fast tests on every push
fast-tests:
  runs-on: ubuntu-latest
  strategy:
    matrix:
      database: [mysql, postgres]
  steps:
    - run: mvn test -Dtest.groups="fast & ${{ matrix.database }}"

# Full tests on PR merge
full-tests:
  if: github.event_name == 'push'
  runs-on: ubuntu-latest
  steps:
    - run: mvn test -Dtest.groups="full-schema"
```

## Resource Limits

| Environment | Max Containers | Parallelism |
|-------------|---------------|-------------|
| Local Dev   | Unlimited     | Sequential* |
| GitHub Actions | 2-3        | Sequential* |
| CI with Docker | 4-6       | Sequential* |

*Parallel execution is disabled by default because tests share singleton containers.
Each database type (MySQL, PostgreSQL, etc.) uses a single container for all tests.

## Troubleshooting

### Container Startup Failures

```bash
# Check Docker daemon
docker info

# Check available resources
docker system df

# Enable Testcontainers reuse (faster local dev)
echo "testcontainers.reuse.enable=true" >> ~/.testcontainers.properties
```

### Test Isolation Issues

Each test should:
1. Create unique database names
2. Clean up after itself
3. Not depend on other tests' state

```java
@AfterEach
void cleanup() {
    if (ctx != null) {
        ctx.cleanup();
    }
}
```

### Slow Tests

- Use `@Fast` tag with minimal schemas for quick feedback
- Run `@Slow` tests only on CI merge
- Enable container reuse for local development

## Extending for New Databases

1. Create `XxxTestContainer.java` (singleton pattern)
2. Create `XxxTestContext.java` (implements GenericTestContext)
3. Create `AbstractXxxTest.java` (extends pattern)
4. Add minimal reference schema
5. Create delta scripts for test cases
6. Write test classes

See existing implementations for templates.

