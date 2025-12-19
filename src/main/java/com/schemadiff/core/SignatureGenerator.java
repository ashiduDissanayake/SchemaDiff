package com.schemadiff.core;

import com.schemadiff.model.ConstraintMetadata;

import java.util.stream.Collectors;

public class SignatureGenerator {

    public static String generate(ConstraintMetadata constraint) {
        String columns = constraint.getColumns().stream()
            .map(String::toUpperCase)
            .sorted()
            .collect(Collectors.joining(","));

        String signature = constraint.getType() + ":" + columns;

        if (constraint.getReferencedTable() != null) {
            signature += "→" + constraint.getReferencedTable().toUpperCase();
        }

        return signature;
    }
}