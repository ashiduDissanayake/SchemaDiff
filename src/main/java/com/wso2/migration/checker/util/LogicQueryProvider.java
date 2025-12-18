package com.wso2.migration.checker.util;

import static com.wso2.migration.checker.DriftMaster.DatabaseType;

public class LogicQueryProvider {

    public static String getTriggerQuery(DatabaseType dbType) {
        return switch (dbType) {
            case ORACLE -> """
                SELECT trigger_name, trigger_body 
                FROM user_triggers 
                WHERE trigger_name NOT LIKE 'BIN$%'
                """;
            case POSTGRES -> """
                SELECT t.tgname, pg_get_triggerdef(t.oid)
                FROM pg_trigger t
                JOIN pg_class c ON t.tgrelid = c.oid
                WHERE NOT t.tgisinternal
                """;
            case MYSQL -> """
                SELECT trigger_name, action_statement
                FROM information_schema.triggers
                WHERE trigger_schema = DATABASE()
                """;
            case MSSQL -> """
                SELECT name, OBJECT_DEFINITION(object_id)
                FROM sys.triggers
                WHERE parent_class = 1
                """;
            case DB2 -> """
                SELECT trigname, text
                FROM syscat.triggers
                WHERE trigschema = CURRENT SCHEMA
                """;
        };
    }

    public static String getProcedureQuery(DatabaseType dbType) {
        return switch (dbType) {
            case ORACLE -> """
                SELECT name, text
                FROM user_source
                WHERE type = 'PROCEDURE'
                ORDER BY name, line
                """;
            case POSTGRES -> """
                SELECT proname, prosrc
                FROM pg_proc
                WHERE prokind = 'p'
                AND pronamespace = (SELECT oid FROM pg_namespace WHERE nspname = 'public')
                """;
            case MYSQL -> """
                SELECT routine_name, routine_definition
                FROM information_schema.routines
                WHERE routine_type = 'PROCEDURE'
                AND routine_schema = DATABASE()
                """;
            case MSSQL -> """
                SELECT name, OBJECT_DEFINITION(object_id)
                FROM sys.procedures
                WHERE type = 'P'
                """;
            case DB2 -> """
                SELECT routinename, text
                FROM syscat.routines
                WHERE routinetype = 'P'
                AND routineschema = CURRENT SCHEMA
                """;
        };
    }

    public static String getFunctionQuery(DatabaseType dbType) {
        return switch (dbType) {
            case ORACLE -> """
                SELECT name, text
                FROM user_source
                WHERE type = 'FUNCTION'
                ORDER BY name, line
                """;
            case POSTGRES -> """
                SELECT proname, prosrc
                FROM pg_proc
                WHERE prokind = 'f'
                AND pronamespace = (SELECT oid FROM pg_namespace WHERE nspname = 'public')
                """;
            case MYSQL -> """
                SELECT routine_name, routine_definition
                FROM information_schema.routines
                WHERE routine_type = 'FUNCTION'
                AND routine_schema = DATABASE()
                """;
            case MSSQL -> """
                SELECT name, OBJECT_DEFINITION(object_id)
                FROM sys.objects
                WHERE type IN ('FN', 'IF', 'TF')
                """;
            case DB2 -> """
                SELECT routinename, text
                FROM syscat.routines
                WHERE routinetype = 'F'
                AND routineschema = CURRENT SCHEMA
                """;
        };
    }
}