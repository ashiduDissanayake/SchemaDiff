# MySQL Testing Workflow Documentation

## Overview
This document explains the logic and validity of the MySQL testing workflow implemented in SchemaDiff 2.0. The system uses a sophisticated "Database-per-Test" strategy with Docker containers to ensure fast, reliable, and isolated integration tests.

## 1. The Workflow Components

The testing workflow consists of three main layers:

### A. CI Pipeline (`.github/workflows/e2e-tests.yml`)
The GitHub Actions workflow segregates tests into stages to optimize feedback time:
1.  **Fast Tests**: Runs unit tests and minimal checks.
2.  **MySQL Tests**: Runs specifically tagged MySQL integration tests (`mvn test -Dtest.groups="mysql"`).
3.  **Full Schema Tests**: Runs comprehensive scenarios only on main branch pushes.

### B. Test Categorization (`TestTags.java`)
Tests are categorized using JUnit 5 Tags, allowing precise control over execution:
- `@MySQL` (mapped to `@Tag("mysql")`): Identifies all MySQL-related tests.
- `@Slow`: Marks tests that take longer (like `CLIExecutionTest` which spawns a JVM) to exclude them from the fast feedback loop.

### C. Maven Configuration (`pom.xml`)
The `maven-surefire-plugin` is configured to respect these groups via system properties (`test.groups`, `test.excludedGroups`), enabling the CI logic to dynamically select which tests to run.

---

## 2. Infrastructure Logic

The core innovation lies in how the testing infrastructure handles the database lifecycle.

### A. The "Singleton Container" Pattern (`MySQLTestContainer.java`)
Instead of starting a new Docker container for every test method (which takes 5-10 seconds each), the system uses a **Singleton** pattern:
- **One Container Rule**: A single MySQL 8.0 container is started once for the entire test suite execution.
- **Lazy Initialization**: The container starts only when the first test requests it.
- **Reuse**: The container remains alive across all test classes until the JVM shuts down.

### B. The "Database-per-Test" Pattern (`TestContext.java`)
To ensure isolation without the overhead of restarting containers:
- **Unique Databases**: Each test method creates two fresh databases with unique names (e.g., `ref_testMissingColumn_1234`, `target_testMissingColumn_1234`).
- **Isolation**: Changes in one test cannot affect others because they operate in completely separate databases.
- **Cleanup**: Databases are dropped after the test completes (in `cleanup()`).

### C. Performance Optimizations
The container is configured for maximum throughput:
- `fsync` disabled (`innodb-flush-log-at-trx-commit=0`).
- Binary logs disabled.
- Shared memory settings optimized for transient data.

---

## 3. Validity of the Testing Approach

### Why is this "Good"?

1.  **Real Integration vs. Mocking**:
    - The tests run against a **real MySQL instance**. This validates the actual JDBC driver behavior, SQL syntax compatibility, and database metadata return values (e.g., `INFORMATION_SCHEMA`). Mocks often fail to catch subtle database-specific behaviors.

2.  **High Fidelity**:
    - By using the official `mysql:8.0` image, the environment mirrors production usage closely.

3.  **Speed & Reliability**:
    - The "Database-per-Test" approach offers the speed of in-memory databases (creation takes milliseconds) with the fidelity of a real database.
    - Parallel execution is possible (enabled in `pom.xml`) because each test uses unique database names.

4.  **Full E2E Verification**:
    - `CLIExecutionTest.java` verifies the final build artifact (`java -jar ...`), ensuring that the packaged application works correctly from the user's perspective (argument parsing, exit codes, output formatting).

### Validation Checklist
The workflow validates:
- [x] **Schema Extraction**: Can we correctly read tables, columns, and constraints?
- [x] **Drift Detection**: Does the logic correctly identify added, removed, and modified elements?
- [x] **Application Entry**: Does the CLI accept arguments and report errors correctly?
- [x] **Database Connectivity**: Are connection pools and JDBC settings correct?

## 4. How to Run

### Local Execution
To run only MySQL tests locally:
```bash
mvn test -Dtest.groups="mysql"
```

To run a specific test class:
```bash
mvn test -Dtest=TableDifferenceTest
```

### Debugging
The `AbstractMySQLTest` provides helper methods like `printReport()` to inspect the diff output during development.
