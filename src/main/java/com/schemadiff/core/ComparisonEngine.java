package com.schemadiff.core;

import com.schemadiff. model.*;

import java.util.*;

public class ComparisonEngine {

    public DiffResult compare(DatabaseMetadata reference, DatabaseMetadata target) {
        DiffResult result = new DiffResult();

        // Level 1: Table existence
        compareTableExistence(reference, target, result);

        // Level 2: Column definitions (only for common tables)
        compareColumns(reference, target, result);

        // Level 3: Constraints
        compareConstraints(reference, target, result);

        // Level 4: Indexes
        compareIndexes(reference, target, result);

        return result;
    }

    private void compareTableExistence(DatabaseMetadata ref, DatabaseMetadata target, DiffResult result) {
        Set<String> refTables = ref.getTableNames();
        Set<String> targetTables = target.getTableNames();

        for (String table : refTables) {
            if (! targetTables.contains(table. toUpperCase())) {
                result.addMissingTable(table);
            }
        }

        for (String table : targetTables) {
            if (!refTables.contains(table.toUpperCase())) {
                result.addExtraTable(table);
            }
        }
    }

    private void compareColumns(DatabaseMetadata ref, DatabaseMetadata target, DiffResult result) {
        for (String tableName : ref.getTableNames()) {
            TableMetadata refTable = ref.getTable(tableName);
            TableMetadata targetTable = target.getTable(tableName);

            if (targetTable == null) continue; // Skip missing tables

            for (ColumnMetadata refCol : refTable.getColumns()) {
                ColumnMetadata targetCol = targetTable.getColumn(refCol.getName());

                if (targetCol == null) {
                    result.addMissingColumn(tableName, refCol.getName(), refCol.getDataType());
                } else {
                    List<String> diffs = new ArrayList<>();

                    if (!refCol.getDataType().equalsIgnoreCase(targetCol.getDataType())) {
                        diffs.add("Type mismatch: " + refCol.getDataType() + " != " + targetCol.getDataType());
                    }

                    if (refCol.isNotNull() != targetCol.isNotNull()) {
                        diffs.add("Nullable mismatch: " + !refCol.isNotNull() + " != " + !targetCol.isNotNull());
                    }

                    if (refCol.isAutoIncrement() != targetCol.isAutoIncrement()) {
                        diffs.add("AutoIncrement mismatch: " + refCol.isAutoIncrement() + " != " + targetCol.isAutoIncrement());
                    }

                    if (refCol.isUnsigned() != targetCol.isUnsigned()) {
                        diffs.add("Unsigned mismatch: " + refCol.isUnsigned() + " != " + targetCol.isUnsigned());
                    }

                    // Simple default value comparison
                    String def1 = refCol.getDefaultValue();
                    String def2 = targetCol.getDefaultValue();
                    if (!Objects.equals(def1, def2)) {
                        // Handle potential null vs "NULL" string discrepancies if necessary, but strict for now
                        diffs.add("Default value mismatch: " + def1 + " != " + def2);
                    }

                    if (!diffs.isEmpty()) {
                        result.addModifiedColumn(tableName, refCol.getName(), String.join(", ", diffs));
                    }
                }
            }

            for (ColumnMetadata targetCol : targetTable.getColumns()) {
                if (refTable.getColumn(targetCol.getName()) == null) {
                    result.addExtraColumn(tableName, targetCol.getName());
                }
            }
        }
    }

    private void compareConstraints(DatabaseMetadata ref, DatabaseMetadata target, DiffResult result) {
        for (String tableName : ref.getTableNames()) {
            TableMetadata refTable = ref.getTable(tableName);
            TableMetadata targetTable = target.getTable(tableName);

            if (targetTable == null) continue;

            // Build maps by signature for missing/extra detection
            Map<String, ConstraintMetadata> refConstraints = buildSignatureMap(refTable.getConstraints());
            Map<String, ConstraintMetadata> targetConstraints = buildSignatureMap(targetTable.getConstraints());

            // Find missing constraints (by signature)
            for (String signature : refConstraints.keySet()) {
                if (!targetConstraints.containsKey(signature)) {
                    ConstraintMetadata refConstraint = refConstraints.get(signature);
                    result.addMissingConstraintDetailed(tableName, refConstraint);
                }
            }

            // Find extra constraints (by signature)
            for (String signature : targetConstraints.keySet()) {
                if (!refConstraints.containsKey(signature)) {
                    ConstraintMetadata targetConstraint = targetConstraints.get(signature);
                    result.addExtraConstraintDetailed(tableName, targetConstraint);
                }
            }

            // Check for modified constraints (same name but different signature)
            for (ConstraintMetadata refConstraint : refTable.getConstraints()) {
                ConstraintMetadata targetConstraint = targetTable.getConstraint(refConstraint.getName());

                if (targetConstraint != null) {
                    // Both exist with same name - check if signature differs
                    if (!refConstraint.getSignature().equals(targetConstraint.getSignature())) {
                        result.addModifiedConstraintDetailed(tableName, refConstraint, targetConstraint);
                    }
                }
            }
        }
    }

    private void compareIndexes(DatabaseMetadata ref, DatabaseMetadata target, DiffResult result) {
        for (String tableName : ref.getTableNames()) {
            TableMetadata refTable = ref.getTable(tableName);
            TableMetadata targetTable = target.getTable(tableName);

            if (targetTable == null) continue;

            // Build maps by name for proper comparison
            Map<String, IndexMetadata> refIndexes = buildIndexMapByName(refTable.getIndexes());
            Map<String, IndexMetadata> targetIndexes = buildIndexMapByName(targetTable.getIndexes());

            // Find missing indexes
            for (String indexName : refIndexes.keySet()) {
                IndexMetadata refIndex = refIndexes.get(indexName);
                IndexMetadata targetIndex = targetIndexes.get(indexName);

                if (targetIndex == null) {
                    // Index completely missing
                    result.addMissingIndexDetailed(tableName, refIndex);
                } else {
                    // Index exists - compare properties
                    List<String> diffs = new ArrayList<>();

                    // Compare columns
                    if (!refIndex.getColumns().equals(targetIndex.getColumns())) {
                        diffs.add("Columns differ");
                    }

                    // Compare uniqueness (CRITICAL!)
                    if (refIndex.isUnique() != targetIndex.isUnique()) {
                        diffs.add("Uniqueness: " + refIndex.isUnique() + " != " + targetIndex.isUnique());
                    }

                    // Compare index type
                    if (!Objects.equals(refIndex.getIndexType(), targetIndex.getIndexType())) {
                        diffs.add("Type: " + refIndex.getIndexType() + " != " + targetIndex.getIndexType());
                    }

                    if (!diffs.isEmpty()) {
                        result.addModifiedIndexDetailed(tableName, indexName, refIndex, targetIndex);
                    }
                }
            }

            // Find extra indexes
            for (String indexName : targetIndexes.keySet()) {
                if (!refIndexes.containsKey(indexName)) {
                    IndexMetadata targetIndex = targetIndexes.get(indexName);
                    result.addExtraIndexDetailed(tableName, targetIndex);
                }
            }
        }
    }

    private Map<String, IndexMetadata> buildIndexMapByName(List<IndexMetadata> indexes) {
        Map<String, IndexMetadata> map = new HashMap<>();
        for (IndexMetadata index : indexes) {
            map.put(index.getName().toUpperCase(), index);
        }
        return map;
    }

    private Map<String, ConstraintMetadata> buildSignatureMap(List<ConstraintMetadata> constraints) {
        Map<String, ConstraintMetadata> map = new HashMap<>();
        for (ConstraintMetadata c : constraints) {
            map.put(c.getSignature(), c);
        }
        return map;
    }
}