# DB2 Extractor Summary

The DB2 Extractor is a new addition to SchemaDiff 2.0, providing support for IBM DB2 databases. It allows for the extraction of metadata such as tables, columns, constraints (primary keys, foreign keys, check, unique), and indexes.

## Key Features

*   **Tables & Comments:** Extracts table definitions and associated comments from `SYSCAT.TABLES`.
*   **Columns & Types:** Retrieves column details including data types, nullability, default values, and identity (auto-increment) status from `SYSCAT.COLUMNS`.
*   **Data Type Normalization:** Maps DB2 types to a standardized set of types (e.g., `VARCHAR`, `DECIMAL`, `INTEGER`, `DATE`, `TIMESTAMP`).
*   **Primary Keys:** Identifies primary keys using `SYSCAT.TABCONST` and `SYSCAT.KEYCOLUSE`.
*   **Foreign Keys:** Extracts foreign key relationships, including update and delete rules, using `SYSCAT.REFERENCES`.
*   **Check Constraints:** Captures check constraints from `SYSCAT.CHECKS`.
*   **Unique Constraints:** Identifies unique constraints.
*   **Indexes:** Extracts index definitions from `SYSCAT.INDEXES`, excluding implicit primary key indexes.

## Implementation Details

The extractor is implemented in `com.schemadiff.core.extractors.DB2Extractor`. It extends the `MetadataExtractor` abstract class and implements the `extract` method.

### Dependencies
*   Requires a valid JDBC connection to a DB2 database.
*   Access to system catalog views: `SYSCAT.TABLES`, `SYSCAT.COLUMNS`, `SYSCAT.TABCONST`, `SYSCAT.REFERENCES`, `SYSCAT.CHECKS`, `SYSCAT.INDEXES`, `SYSCAT.KEYCOLUSE`, `SYSCAT.INDEXCOLUSE`.

### Usage
The extractor is automatically selected when using the `--db-type db2` flag in the SchemaDiff CLI.
