package com.schemadiff.e2e.base;

import com.schemadiff.model.DiffResult;
import org.assertj.core.api.AbstractAssert;
import org.assertj.core.api.Assertions;

import java.util.List;
import java.util.Map;

/**
 * Custom AssertJ assertions for DiffResult.
 *
 * Provides fluent, readable assertions for schema comparison results.
 *
 * Usage:
 * <pre>
 * DiffResultAssert.assertThat(result)
 *     .hasDifferences()
 *     .hasMissingTable("USERS")
 *     .hasNoExtraTables()
 *     .hasTotalDifferences(3);
 * </pre>
 */
public class DiffResultAssert extends AbstractAssert<DiffResultAssert, DiffResult> {

    protected DiffResultAssert(DiffResult actual) {
        super(actual, DiffResultAssert.class);
    }

    /**
     * Entry point for DiffResult assertions.
     */
    public static DiffResultAssert assertThat(DiffResult actual) {
        return new DiffResultAssert(actual);
    }

    // === Difference Existence ===

    public DiffResultAssert hasDifferences() {
        isNotNull();
        if (!actual.hasDifferences()) {
            failWithMessage("Expected differences but found none.\nReport:\n%s", formatReport());
        }
        return this;
    }

    public DiffResultAssert hasNoDifferences() {
        isNotNull();
        if (actual.hasDifferences()) {
            failWithMessage("Expected no differences but found %d:\n%s",
                    actual.getTotalDifferences(), formatReport());
        }
        return this;
    }

    public DiffResultAssert hasTotalDifferences(int expected) {
        isNotNull();
        int actualCount = actual.getTotalDifferences();
        if (actualCount != expected) {
            failWithMessage("Expected %d total differences but found %d:\n%s",
                    expected, actualCount, formatReport());
        }
        return this;
    }

    // === Missing Tables ===

    public DiffResultAssert hasMissingTable(String tableName) {
        isNotNull();
        List<String> missing = actual.getMissingTables();
        if (!containsIgnoreCase(missing, tableName)) {
            failWithMessage("Expected missing table '%s' but was not found.\nMissing tables: %s",
                    tableName, missing);
        }
        return this;
    }

    public DiffResultAssert hasMissingTables(String... tableNames) {
        for (String table : tableNames) {
            hasMissingTable(table);
        }
        return this;
    }

    public DiffResultAssert hasNoMissingTables() {
        isNotNull();
        List<String> missing = actual.getMissingTables();
        if (!missing.isEmpty()) {
            failWithMessage("Expected no missing tables but found: %s", missing);
        }
        return this;
    }

    public DiffResultAssert hasMissingTableCount(int expected) {
        isNotNull();
        int actualCount = actual.getMissingTables().size();
        if (actualCount != expected) {
            failWithMessage("Expected %d missing tables but found %d: %s",
                    expected, actualCount, actual.getMissingTables());
        }
        return this;
    }

    // === Extra Tables ===

    public DiffResultAssert hasExtraTable(String tableName) {
        isNotNull();
        List<String> extra = actual.getExtraTables();
        if (!containsIgnoreCase(extra, tableName)) {
            failWithMessage("Expected extra table '%s' but was not found.\nExtra tables: %s",
                    tableName, extra);
        }
        return this;
    }

    public DiffResultAssert hasExtraTables(String... tableNames) {
        for (String table : tableNames) {
            hasExtraTable(table);
        }
        return this;
    }

    public DiffResultAssert hasNoExtraTables() {
        isNotNull();
        List<String> extra = actual.getExtraTables();
        if (!extra.isEmpty()) {
            failWithMessage("Expected no extra tables but found: %s", extra);
        }
        return this;
    }

    public DiffResultAssert hasExtraTableCount(int expected) {
        isNotNull();
        int actualCount = actual.getExtraTables().size();
        if (actualCount != expected) {
            failWithMessage("Expected %d extra tables but found %d: %s",
                    expected, actualCount, actual.getExtraTables());
        }
        return this;
    }

    // === Column Differences ===

    public DiffResultAssert hasColumnDifference(String tableName) {
        isNotNull();
        Map<String, List<String>> diffs = actual.getColumnDiffs();
        if (!containsKeyIgnoreCase(diffs, tableName)) {
            failWithMessage("Expected column differences in table '%s' but found none.\nColumn diffs: %s",
                    tableName, diffs.keySet());
        }
        return this;
    }

    public DiffResultAssert hasColumnDifferenceContaining(String tableName, String substring) {
        isNotNull();
        Map<String, List<String>> diffs = actual.getColumnDiffs();
        List<String> tableDiffs = getIgnoreCase(diffs, tableName);
        if (tableDiffs == null || tableDiffs.stream().noneMatch(d -> d.contains(substring))) {
            failWithMessage("Expected column difference in '%s' containing '%s'.\nFound: %s",
                    tableName, substring, tableDiffs);
        }
        return this;
    }

    public DiffResultAssert hasNoColumnDifferences() {
        isNotNull();
        Map<String, List<String>> diffs = actual.getColumnDiffs();
        if (!diffs.isEmpty()) {
            failWithMessage("Expected no column differences but found: %s", diffs);
        }
        return this;
    }

    // === Constraint Differences ===

    public DiffResultAssert hasConstraintDifference(String tableName) {
        isNotNull();
        Map<String, List<String>> diffs = actual.getConstraintDiffs();
        if (!containsKeyIgnoreCase(diffs, tableName)) {
            failWithMessage("Expected constraint differences in table '%s' but found none.\nConstraint diffs: %s",
                    tableName, diffs.keySet());
        }
        return this;
    }

    public DiffResultAssert hasConstraintDifferenceContaining(String tableName, String substring) {
        isNotNull();
        Map<String, List<String>> diffs = actual.getConstraintDiffs();
        List<String> tableDiffs = getIgnoreCase(diffs, tableName);
        if (tableDiffs == null || tableDiffs.stream().noneMatch(d -> d.contains(substring))) {
            failWithMessage("Expected constraint difference in '%s' containing '%s'.\nFound: %s",
                    tableName, substring, tableDiffs);
        }
        return this;
    }

    public DiffResultAssert hasNoConstraintDifferences() {
        isNotNull();
        Map<String, List<String>> diffs = actual.getConstraintDiffs();
        if (!diffs.isEmpty()) {
            failWithMessage("Expected no constraint differences but found: %s", diffs);
        }
        return this;
    }

    // === Index Differences ===

    public DiffResultAssert hasIndexDifference(String tableName) {
        isNotNull();
        Map<String, List<String>> diffs = actual.getIndexDiffs();
        if (!containsKeyIgnoreCase(diffs, tableName)) {
            failWithMessage("Expected index differences in table '%s' but found none.\nIndex diffs: %s",
                    tableName, diffs.keySet());
        }
        return this;
    }

    public DiffResultAssert hasIndexDifferenceContaining(String tableName, String substring) {
        isNotNull();
        Map<String, List<String>> diffs = actual.getIndexDiffs();
        List<String> tableDiffs = getIgnoreCase(diffs, tableName);
        if (tableDiffs == null || tableDiffs.stream().noneMatch(d -> d.contains(substring))) {
            failWithMessage("Expected index difference in '%s' containing '%s'.\nFound: %s",
                    tableName, substring, tableDiffs);
        }
        return this;
    }

    public DiffResultAssert hasNoIndexDifferences() {
        isNotNull();
        Map<String, List<String>> diffs = actual.getIndexDiffs();
        if (!diffs.isEmpty()) {
            failWithMessage("Expected no index differences but found: %s", diffs);
        }
        return this;
    }

    // === Helper Methods ===

    private String formatReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("Missing Tables: ").append(actual.getMissingTables()).append("\n");
        sb.append("Extra Tables: ").append(actual.getExtraTables()).append("\n");
        sb.append("Column Diffs: ").append(actual.getColumnDiffs()).append("\n");
        sb.append("Constraint Diffs: ").append(actual.getConstraintDiffs()).append("\n");
        sb.append("Index Diffs: ").append(actual.getIndexDiffs());
        return sb.toString();
    }

    private boolean containsIgnoreCase(List<String> list, String value) {
        return list.stream().anyMatch(s -> s.equalsIgnoreCase(value));
    }

    private boolean containsKeyIgnoreCase(Map<String, ?> map, String key) {
        return map.keySet().stream().anyMatch(k -> k.equalsIgnoreCase(key));
    }

    private <V> V getIgnoreCase(Map<String, V> map, String key) {
        return map.entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(key))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }
}

