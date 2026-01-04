# E2E Test Framework Documentation

## Overview

This document describes the E2E Integration Test framework for SchemaDiff, built with **JUnit 5**, **Testcontainers**, and **AssertJ**.

## Architecture

### Singleton Container Pattern

The framework uses a **Database-per-Test** pattern:
- **ONE MySQL container** is started at the beginning of the test suite
- Each test creates **isolated databases** inside that container
- Container is reused across all tests (100x faster than new containers per test)

```
┌─────────────────────────────────────────────────────────┐
│                 MySQL Test Container                     │
│                    (Singleton)                           │
├─────────────────────────────────────────────────────────┤
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐   │
│  │ ref_test_1   │  │ target_test_1│  │ ref_test_2   │   │
│  │ (6 tables)   │  │ (6 tables)   │  │ (6 tables)   │   │
│  └──────────────┘  └──────────────┘  └──────────────┘   │
│        ...              ...              ...             │
└─────────────────────────────────────────────────────────┘
```

### Directory Structure

```
src/test/
├── java/com/schemadiff/e2e/
│   ├── base/                          # Framework core
│   │   ├── AbstractMySQLTest.java     # Base test class
│   │   ├── DiffResultAssert.java      # Custom AssertJ assertions
│   │   ├── MySQLTestContainer.java    # Singleton container manager
│   │   ├── SchemaDiffRunner.java      # Programmatic test runner
│   │   ├── TestContext.java           # Per-test database context
│   │   └── TestTags.java              # JUnit 5 tags
│   └── mysql/                         # MySQL-specific tests
│       ├── tables/
│       │   └── TableDifferenceTest.java
│       ├── columns/
│       │   └── ColumnDifferenceTest.java
│       ├── constraints/
│       │   └── ConstraintDifferenceTest.java
│       ├── indexes/
│       │   └── IndexDifferenceTest.java
│       ├── CLIExecutionTest.java      # JAR CLI tests
│       ├── ComplexScenarioTest.java   # Kitchen sink tests
│       └── FullSchemaTest.java        # WSO2 APIM schema tests
└── resources/
    ├── junit-platform.properties      # JUnit 5 config
    └── mysql/
        ├── schemas/
        │   └── minimal_reference.sql  # Fast test schema (6 tables)
        └── testcases/
            ├── tables/
            │   ├── missing/delta.sql
            │   └── extra/delta.sql
            ├── columns/
            │   ├── missing/delta.sql
            │   ├── extra/delta.sql
            │   └── modified/delta.sql
            ├── constraints/
            │   ├── missing/delta.sql
            │   └── extra/delta.sql
            ├── indexes/
            │   ├── missing/delta.sql
            │   └── extra/delta.sql
            └── complex/
                └── delta.sql
```

## Test Tags

Tests are categorized using JUnit 5 tags for selective execution:

### By Speed
| Tag | Description | When to Run |
|-----|-------------|-------------|
| `@Fast` | Minimal schema tests | Every commit |
| `@Slow` | Full schema / CLI tests | PR merge only |
| `@FullSchema` | WSO2 APIM schema tests | PR merge only |

### By Database
| Tag | Description |
|-----|-------------|
| `@MySQL` | MySQL-specific tests |
| `@Postgres` | PostgreSQL tests (future) |
| `@Oracle` | Oracle tests (future) |
| `@MSSQL` | SQL Server tests (future) |

### By Object Type
| Tag | Description |
|-----|-------------|
| `@Tables` | Table existence tests |
| `@Columns` | Column difference tests |
| `@Constraints` | Constraint tests (PK, FK, UK, CHECK) |
| `@Indexes` | Index difference tests |

### By Change Type
| Tag | Description |
|-----|-------------|
| `@Missing` | Objects in reference but not target |
| `@Extra` | Objects in target but not reference |
| `@Modified` | Objects with structural changes |

### Special Tags
| Tag | Description |
|-----|-------------|
| `@Smoke` | Critical path validation |
| `@Complex` | Multi-change scenarios |
| `@CLI` | JAR execution tests (ProcessBuilder) |

## Running Tests

### Command Line

```bash
# Run all fast tests (every commit)
mvn21 test -Dtest.groups="fast"

# Run MySQL tests only
mvn21 test -Dtest.groups="mysql"

# Run specific test categories
mvn21 test -Dtest.groups="tables"
mvn21 test -Dtest.groups="columns"
mvn21 test -Dtest.groups="constraints"
mvn21 test -Dtest.groups="indexes"

# Run smoke tests
mvn21 test -Dtest.groups="smoke"

# Exclude slow tests
mvn21 test -Dtest.excludedGroups="slow,cli,full-schema"

# Run a specific test class
mvn21 test -Dtest="TableDifferenceTest"

# Run a specific test method
mvn21 test -Dtest="TableDifferenceTest#shouldDetectMissingTables"

# Update golden master snapshots
mvn21 test -DupdateSnapshots=true
```

### CI Pipeline

The GitHub Actions workflow runs tests in stages:

1. **fast-tests** - Every push/PR (fast tests only)
2. **mysql-tests** - After fast tests pass
3. **full-schema-tests** - On main/master push only
4. **cli-tests** - On main/master push only

## Writing New Tests

### 1. Create a Delta SQL File

```sql
-- src/test/resources/mysql/testcases/tables/missing/delta.sql
-- Delta: Remove tables from target
DROP TABLE IF EXISTS MY_TABLE;
```

### 2. Write the Test

```java
@Fast
@Tables
@Missing
@DisplayName("Should detect missing tables")
void shouldDetectMissingTables(TestInfo testInfo) throws Exception {
    // Arrange
    ctx = createContext(testInfo);
    ctx.setupBothSchemas(MINIMAL_SCHEMA);
    ctx.applyDelta("/mysql/testcases/tables/missing/delta.sql");
    
    // Act
    DiffResult result = compareSchemas();
    
    // Assert
    assertThat(result)
        .hasDifferences()
        .hasMissingTable("MY_TABLE");
}
```

### 3. Using Custom Assertions

```java
DiffResultAssert.assertThat(result)
    .hasDifferences()
    .hasMissingTable("USERS")
    .hasExtraTable("DEBUG_LOG")
    .hasMissingTableCount(2)
    .hasNoExtraTables()
    .hasColumnDifference("USERS")
    .hasColumnDifferenceContaining("USERS", "EMAIL")
    .hasConstraintDifference("USER_ROLES")
    .hasIndexDifference("AUDIT_LOG");
```

## Golden Master Testing

For complex output verification:

```java
// First run: generate expected.txt
mvn21 test -DupdateSnapshots=true -Dtest="MyTest"

// Review the generated file
cat src/test/resources/mysql/testcases/my_case/expected.txt

// Subsequent runs: verify against expected
mvn21 test -Dtest="MyTest"
```

## Extending to Other Databases

The framework is designed for extension:

1. Create `PostgresTestContainer.java` (singleton pattern)
2. Create `AbstractPostgresTest.java` extending base patterns
3. Add PostgreSQL test resources under `src/test/resources/postgres/`
4. Tag tests with `@Postgres`

Example structure:
```
src/test/
├── java/com/schemadiff/e2e/
│   ├── base/
│   │   ├── PostgresTestContainer.java
│   │   └── AbstractPostgresTest.java
│   └── postgres/
│       └── tables/
│           └── TableDifferenceTest.java
└── resources/postgres/
    ├── schemas/
    │   └── minimal_reference.sql
    └── testcases/
        └── ...
```

## Troubleshooting

### Container Reuse

Enable container reuse for faster local development:

```properties
# ~/.testcontainers.properties
testcontainers.reuse.enable=true
```

### Debug Logging

Add to test to see comparison report:
```java
SchemaDiffRunner.ComparisonResult result = runComparison();
printReport(result);  // Prints full ASCII tree
```

### Test Isolation Issues

Each test gets unique database names:
- `ref_{testname}_{counter}_{uuid}`
- `target_{testname}_{counter}_{uuid}`

Databases are cleaned up in `@AfterEach`.

