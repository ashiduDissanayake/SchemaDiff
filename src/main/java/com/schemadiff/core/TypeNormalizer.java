package com.schemadiff.core;

public class TypeNormalizer {

    public static boolean typesMatch(String refType, String targetType) {
        String normalized1 = normalize(refType);
        String normalized2 = normalize(targetType);
        return normalized1.equals(normalized2);
    }

    private static String normalize(String type) {
        type = type.toLowerCase().trim();

        // Map Oracle types to standard
        type = type.replace("number", "int");
        type = type.replace("varchar2", "varchar");

        // Map MSSQL types
        type = type.replace("nvarchar", "varchar");

        return type;
    }
}