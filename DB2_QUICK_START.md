# DB2 Quick Start

This guide explains how to use SchemaDiff with IBM DB2.

## Prerequisites
*   Java 21+
*   SchemaDiff 2.0.0 JAR
*   Access to an IBM DB2 database instance

## Running a Comparison

To compare two DB2 schemas (Live vs Live), use the following command:

```bash
java -jar target/schemadiff2-2.0.0.jar \
  --db-type db2 \
  --reference "jdbc:db2://host1:50000/db1" --ref-user user1 --ref-pass pass1 \
  --target "jdbc:db2://host2:50000/db2" --target-user user2 --target-pass pass2
```

## Running with SQL Scripts

To compare a local SQL script against a live DB2 database:

```bash
java -jar target/schemadiff2-2.0.0.jar \
  --db-type db2 \
  --reference "path/to/schema.sql" \
  --image "ibmcom/db2" \
  --target "jdbc:db2://host:50000/db" --target-user user --target-pass pass
```

**Note:** When using SQL scripts, a Docker image (e.g., `ibmcom/db2`) is required to spin up a temporary container for parsing the script.

## Supported Features
*   Tables and Columns
*   Primary Keys
*   Foreign Keys (Cascading rules)
*   Check Constraints
*   Unique Constraints
*   Indexes (Unique/Non-Unique)
