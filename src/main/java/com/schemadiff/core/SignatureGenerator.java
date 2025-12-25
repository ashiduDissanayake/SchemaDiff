package com.schemadiff.core;

import com.schemadiff.model.ConstraintMetadata;

import java.util.stream.Collectors;
import java.util.Collections;

public class SignatureGenerator {

    public static String generate(ConstraintMetadata constraint) {
        String columns = constraint.getColumns().stream()
            .map(String::toUpperCase)
            .sorted()
            .collect(Collectors.joining(","));

        String signature = constraint.getType() + ":" + columns;

        if (constraint.getReferencedTable() != null) {
            signature += "→" + constraint.getReferencedTable().toUpperCase();
            if (constraint.getReferencedColumns() != null && !constraint.getReferencedColumns().isEmpty()) {
                String refCols = constraint.getReferencedColumns().stream()
                    .map(String::toUpperCase)
                    .collect(Collectors.joining(","));
                signature += "(" + refCols + ")";
            }

            // Append rules if present to detect changes in FK behavior
            if (constraint.getDeleteRule() != null) {
                signature += " ON DELETE " + constraint.getDeleteRule();
            }
            if (constraint.getUpdateRule() != null) {
                signature += " ON UPDATE " + constraint.getUpdateRule();
            }
        }

        return signature;
    }
}