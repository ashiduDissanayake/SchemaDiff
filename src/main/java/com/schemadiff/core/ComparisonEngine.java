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

                    if (!TypeNormalizer.typesMatch(refCol.getDataType(), targetCol.getDataType())) {
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

            Map<String, ConstraintMetadata> refConstraints = buildSignatureMap(refTable. getConstraints());
            Map<String, ConstraintMetadata> targetConstraints = buildSignatureMap(targetTable.getConstraints());

            for (String signature : refConstraints.keySet()) {
                if (!targetConstraints.containsKey(signature)) {
                    result.addMissingConstraint(tableName, refConstraints.get(signature).getType());
                }
            }
        }
    }

    private void compareIndexes(DatabaseMetadata ref, DatabaseMetadata target, DiffResult result) {
        for (String tableName : ref.getTableNames()) {
            TableMetadata refTable = ref.getTable(tableName);
            TableMetadata targetTable = target.getTable(tableName);

            if (targetTable == null) continue;

            Set<String> refIndexSigs = buildIndexSignatures(refTable.getIndexes());
            Set<String> targetIndexSigs = buildIndexSignatures(targetTable.getIndexes());

            for (String sig : refIndexSigs) {
                if (!targetIndexSigs. contains(sig)) {
                    result.addMissingIndex(tableName, sig);
                }
            }
        }
    }

    private Map<String, ConstraintMetadata> buildSignatureMap(List<ConstraintMetadata> constraints) {
        Map<String, ConstraintMetadata> map = new HashMap<>();
        for (ConstraintMetadata c : constraints) {
            map.put(c.getSignature(), c);
        }
        return map;
    }

    private Set<String> buildIndexSignatures(List<IndexMetadata> indexes) {
        Set<String> signatures = new HashSet<>();
        for (IndexMetadata index : indexes) {
            signatures. add(String.join(",", index.getColumns()));
        }
        return signatures;
    }
}