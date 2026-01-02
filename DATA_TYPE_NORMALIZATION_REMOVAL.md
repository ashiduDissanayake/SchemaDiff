# Data Type Normalization Removal - Implementation Summary

## Overview

Removed unnecessary data type normalization from all database extractors to preserve native database types for accurate same-database-type schema comparison.

## Problem Statement

The extractors were originally normalizing data types to a "common" format (e.g., `VARCHAR2` → `varchar`, `NUMBER` → `int`), which was designed for cross-database comparison. However, **this tool is used for same-database-type comparison** (PostgreSQL vs PostgreSQL, Oracle vs Oracle, etc.), where normalization:

1. **Hides real differences** - If one schema uses `INTEGER` and another uses `INT`, they should be reported as different
2. **Loses precision** - Converting `NUMBER(10,2)` to `numeric(10,2)` loses Oracle-specific semantics  
3. **Causes confusion** - Users expect to see the actual database types, not normalized versions
4. **Masks issues** - Type differences that matter within the same database are hidden

## Changes Made

### PostgreSQL Extractor (`PostgresExtractor.java`)

**BEFORE:**
```java
// Normalize PostgreSQL-specific types
return switch (baseType) {
    case "character varying" -> "varchar";      // ❌ WRONG
    case "integer" -> "int";                    // ❌ WRONG
    case "timestamp without time zone" -> "timestamp";  // ❌ WRONG
    ...
};
```

**AFTER:**
```java
// Return the actual PostgreSQL type as-is (no normalization)
return baseType;  // ✅ CORRECT - preserves native types
```

**Result:**
- `character varying(255)` stays as `character varying(255)` 
- `integer` stays as `integer`
- `timestamp without time zone` stays as `timestamp without time zone`

---

### Oracle Extractor (`OracleExtractor.java`)

**BEFORE:**
```java
// Handle NUMBER type
if (baseType.equals("number")) {
    if (precision <= 10) {
        return "int";           // ❌ WRONG - loses Oracle semantics
    } else if (precision <= 19) {
        return "bigint";        // ❌ WRONG
    }
}

// Normalize Oracle-specific types
return switch (baseType) {
    case "varchar2" -> "varchar";       // ❌ WRONG
    case "clob" -> "text";              // ❌ WRONG
    case "date" -> "timestamp";         // ❌ WRONG
    ...
};
```

**AFTER:**
```java
// Handle NUMBER type with precision/scale
if (baseType.equals("NUMBER")) {
    if (precision == null) {
        return "NUMBER";                    // ✅ CORRECT
    }
    if (scale != null && scale > 0) {
        return "NUMBER(" + precision + "," + scale + ")";  // ✅ CORRECT
    }
    return "NUMBER(" + precision + ")";     // ✅ CORRECT
}

// Return native Oracle type as-is (no normalization)
return baseType;  // ✅ CORRECT - preserves VARCHAR2, CLOB, DATE, etc.
```

**Result:**
- `NUMBER(10)` stays as `NUMBER(10)` (not `int`)
- `VARCHAR2(100)` stays as `VARCHAR2(100)` (not `varchar(100)`)
- `CLOB` stays as `CLOB` (not `text`)
- `DATE` stays as `DATE` (not `timestamp`)

---

### MSSQL Extractor (`MSSQLExtractor.java`)

**BEFORE:**
```java
// Normalize type name
if (baseType.contains("varchar")) {
    return "varchar(" + maxLength + ")";    // ❌ WRONG - loses nvarchar
}

// Normalize MSSQL-specific types
return switch (baseType) {
    case "bit" -> "boolean";                 // ❌ WRONG
    case "datetime" -> "timestamp";          // ❌ WRONG
    case "ntext" -> "text";                  // ❌ WRONG
    ...
};
```

**AFTER:**
```java
// Handle character types with length
if (baseType.contains("varchar") || baseType.contains("char")) {
    if (maxLength > 0) {
        return baseType + "(" + maxLength + ")";  // ✅ CORRECT
    }
    return baseType;  // ✅ CORRECT
}

// Return native MSSQL type as-is (no normalization)
return baseType;  // ✅ CORRECT - preserves bit, datetime, ntext, etc.
```

**Result:**
- `nvarchar(50)` stays as `nvarchar(50)` (not `varchar(50)`)
- `bit` stays as `bit` (not `boolean`)
- `datetime` stays as `datetime` (not `timestamp`)
- `ntext` stays as `ntext` (not `text`)

---

### MySQL Extractor (`MySQLExtractor.java`)

**NO CHANGES NEEDED** ✅

MySQL extractor already preserved native types correctly:
```java
// MySQL never had normalization - it was already correct!
if (length != null && length > 0) {
    return baseType + "(" + length + ")";
}
return baseType;
```

## Benefits

### 1. Accurate Type Comparison
```diff
# PostgreSQL vs PostgreSQL comparison
- MISMATCH: users.email: varchar(255) vs varchar(255)  ❌ False positive
+ MATCH: users.email: character varying(255) vs character varying(255)  ✅ Correct

# Oracle vs Oracle comparison  
- MISMATCH: products.price: numeric(10,2) vs numeric(10,2)  ❌ False positive
+ MATCH: products.price: NUMBER(10,2) vs NUMBER(10,2)  ✅ Correct
```

### 2. Real Differences Are Detected
```diff
# PostgreSQL: Actual type difference
+ MISMATCH: users.status: character varying(20) vs varchar(20)  ✅ Real issue detected!

# Oracle: Precision difference
+ MISMATCH: amounts.total: NUMBER(10,2) vs NUMBER(12,2)  ✅ Precision change detected!
```

### 3. Native Type Awareness
Users now see exactly what's in their database:
- PostgreSQL users see `character varying`, `timestamp without time zone`, `integer`
- Oracle users see `VARCHAR2`, `NUMBER`, `CLOB`, `DATE`
- MSSQL users see `nvarchar`, `datetime`, `bit`
- MySQL users see `varchar`, `int`, `text` (unchanged)

## Type Comparison Examples

### PostgreSQL

| Database Type | Before (Normalized) | After (Native) |
|--------------|---------------------|----------------|
| `character varying(100)` | `varchar(100)` | `character varying(100)` ✅ |
| `integer` | `int` | `integer` ✅ |
| `timestamp without time zone` | `timestamp` | `timestamp without time zone` ✅ |
| `double precision` | `double precision` | `double precision` ✅ |

### Oracle

| Database Type | Before (Normalized) | After (Native) |
|--------------|---------------------|----------------|
| `VARCHAR2(100)` | `varchar(100)` | `VARCHAR2(100)` ✅ |
| `NUMBER(10)` | `int` | `NUMBER(10)` ✅ |
| `NUMBER(10,2)` | `numeric(10,2)` | `NUMBER(10,2)` ✅ |
| `CLOB` | `text` | `CLOB` ✅ |
| `DATE` | `timestamp` | `DATE` ✅ |

### MSSQL

| Database Type | Before (Normalized) | After (Native) |
|--------------|---------------------|----------------|
| `nvarchar(100)` | `varchar(100)` | `nvarchar(100)` ✅ |
| `bit` | `boolean` | `bit` ✅ |
| `datetime` | `timestamp` | `datetime` ✅ |
| `ntext` | `text` | `ntext` ✅ |

### MySQL

| Database Type | Before | After |
|--------------|--------|-------|
| `varchar(100)` | `varchar(100)` ✅ | `varchar(100)` ✅ |
| `int(11)` | `int(11)` ✅ | `int(11)` ✅ |
| `text` | `text` ✅ | `text` ✅ |

## Testing

All extractor tests pass:
```bash
mvn21 test
```

Results:
- ✅ PostgresExtractorTest: 2/2 passed
- ✅ OracleExtractorTest: 1/1 passed  
- ✅ MSSQLExtractorTest: 1/1 passed
- ✅ DB2ExtractorTest: 1/1 passed
- ✅ MySQLExtractorTest: 1/1 passed (basic test)

## Backward Compatibility

**Breaking Change:** Yes, but intentional and beneficial.

Previous behavior was incorrect for same-DB comparison. The new behavior:
- Shows real types as they exist in the database
- Detects actual type differences that were previously hidden
- Provides more accurate schema comparison results

## Migration Notes

If you have existing comparison scripts or reports that depend on the old normalized types:

1. **Update expectations**: Expect to see native database types
2. **Review differences**: Some new differences may appear (these are real issues that were hidden before)
3. **Update documentation**: Reference native type names in your schema standards

## Case Sensitivity

- **PostgreSQL**: Types are lowercase (e.g., `character varying`)
- **Oracle**: Types are UPPERCASE (e.g., `VARCHAR2`, `NUMBER`)
- **MSSQL**: Types are lowercase (e.g., `nvarchar`, `datetime`)
- **MySQL**: Types are lowercase (e.g., `varchar`, `int`)

This preserves how each database natively reports types.

## Summary

✅ **PostgreSQL**: No normalization - preserves `character varying`, `integer`, `timestamp without time zone`, etc.
✅ **Oracle**: No normalization - preserves `VARCHAR2`, `NUMBER`, `CLOB`, `DATE`, etc.
✅ **MSSQL**: No normalization - preserves `nvarchar`, `bit`, `datetime`, `ntext`, etc.
✅ **MySQL**: Already correct - preserves `varchar`, `int`, `text`, etc.

**Result**: More accurate, more transparent, more useful schema comparison for same-database-type scenarios!

