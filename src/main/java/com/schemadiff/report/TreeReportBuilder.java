package com.schemadiff.report;

import com.schemadiff.model.DiffResult;

import java.util. List;
import java.util. Map;

public class TreeReportBuilder {

    public String build(DiffResult result) {
        StringBuilder sb = new StringBuilder();

        sb.append("\n");
        sb.append("═══════════════════════════════════════════════════════════\n");
        sb.append("[-] SCHEMA SUMMARY:  ").append(result.getTotalDifferences()).append(" Differences Found\n");
        sb.append("═══════════════════════════════════════════════════════════\n");
        sb.append(" |\n");

        if (result.getTotalDifferences() == 0) {
            sb.append(" └── ✓ No differences detected\n");
            return sb.toString();
        }

        // Missing tables
        if (! result.getMissingTables().isEmpty()) {
            sb.append(" ├── [X] MISSING TABLES (").append(result.getMissingTables().size()).append(")\n");
            for (int i = 0; i < result.getMissingTables().size(); i++) {
                String table = result.getMissingTables().get(i);
                boolean isLast = i == result.getMissingTables().size() - 1;
                sb.append(" │   ").append(isLast ? "└──" : "├──").append(" ").append(table).append("\n");
            }
            sb.append(" |\n");
        }

        // Extra tables
        if (!result.getExtraTables().isEmpty()) {
            sb.append(" ├── [+] EXTRA TABLES (").append(result.getExtraTables().size()).append(")\n");
            for (int i = 0; i < result.getExtraTables().size(); i++) {
                String table = result. getExtraTables().get(i);
                boolean isLast = i == result.getExtraTables().size() - 1;
                sb.append(" │   ").append(isLast ? "└──" : "├──").append(" ").append(table).append("\n");
            }
            sb.append(" |\n");
        }

        // Column differences
        if (!result.getColumnDiffs().isEmpty()) {
            sb.append(" ├── [M] COLUMN DIFFERENCES\n");
            int tableCount = 0;
            for (Map. Entry<String, List<String>> entry : result.getColumnDiffs().entrySet()) {
                tableCount++;
                boolean isLastTable = tableCount == result.getColumnDiffs().size();

                sb.append(" │   ").append(isLastTable ? "└──" : "├──").append(" [! ] Table:  ").append(entry.getKey()).append("\n");

                List<String> diffs = entry.getValue();
                for (int i = 0; i < diffs.size(); i++) {
                    boolean isLastDiff = i == diffs. size() - 1;
                    String prefix = isLastTable ? "     " : " │   ";
                    sb.append(prefix).append("   ").append(isLastDiff ? "└──" : "├──").append(" ").append(diffs.get(i)).append("\n");
                }
            }
            sb. append(" |\n");
        }

        // Constraint differences
        if (!result.getConstraintDiffs().isEmpty()) {
            sb.append(" ├── [C] CONSTRAINT DIFFERENCES\n");
            int tableCount = 0;
            for (Map.Entry<String, List<String>> entry : result.getConstraintDiffs().entrySet()) {
                tableCount++;
                boolean isLastTable = tableCount == result.getConstraintDiffs().size();

                sb.append(" │   ").append(isLastTable ? "└──" : "├──").append(" [!] Table: ").append(entry.getKey()).append("\n");

                List<String> diffs = entry.getValue();
                for (int i = 0; i < diffs.size(); i++) {
                    boolean isLastDiff = i == diffs.size() - 1;
                    String prefix = isLastTable ? "     " : " │   ";
                    sb.append(prefix).append("   ").append(isLastDiff ? "└──" : "├──").append(" ").append(diffs.get(i)).append("\n");
                }
            }
            sb. append(" |\n");
        }

        // Index differences
        if (!result.getIndexDiffs().isEmpty()) {
            sb.append(" └── [I] INDEX DIFFERENCES\n");
            int tableCount = 0;
            for (Map.Entry<String, List<String>> entry : result.getIndexDiffs().entrySet()) {
                tableCount++;
                boolean isLastTable = tableCount == result. getIndexDiffs().size();

                sb.append("     ").append(isLastTable ? "└──" : "├──").append(" [!] Table: ").append(entry.getKey()).append("\n");

                List<String> diffs = entry.getValue();
                for (int i = 0; i < diffs.size(); i++) {
                    boolean isLastDiff = i == diffs.size() - 1;
                    String prefix = isLastTable ? "     " : "     ";
                    sb.append(prefix).append("   ").append(isLastDiff ? "└──" : "├──").append(" ").append(diffs.get(i)).append("\n");
                }
            }
        }

        sb.append("\n");
        sb.append("═══════════════════════════════════════════════════════════\n");
        sb.append("Legend:\n");
        sb.append("  [-] Root summary\n");
        sb.append("  [X] Missing:  Exists in Reference but not in Target\n");
        sb.append("  [+] Extra:  Exists in Target but not in Reference\n");
        sb.append("  [M] Modified: Structural change detected\n");
        sb.append("  [! ] Warning: Requires attention\n");
        sb.append("  [C] Constraint mismatch\n");
        sb.append("  [I] Index mismatch\n");
        sb.append("═══════════════════════════════════════════════════════════\n");

        return sb.toString();
    }
}