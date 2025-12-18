package com.wso2.migration.checker.core;

import com.example.driftmaster.model.DatabaseConfig;
import com.example. driftmaster.model.StructuralDrift;
import liquibase. Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.diff.DiffResult;
import liquibase.diff.compare.CompareControl;
import liquibase.diff.output.DiffOutputControl;
import liquibase. diff.output.changelog.DiffToChangeLog;
import liquibase.serializer.core.string.StringChangeLogSerializer;
import liquibase.structure.DatabaseObject;
import liquibase.structure.core.*;

import java. sql.Connection;
import java.sql. DriverManager;
import java.util.*;

import static com.example.driftmaster.DriftMaster.DatabaseType;

public class StructuralDiffEngine {

    public StructuralDrift compare(DatabaseConfig reference, DatabaseConfig target, DatabaseType dbType) throws Exception {
        Database referenceDb = null;
        Database targetDb = null;
        Connection refConn = null;
        Connection targetConn = null;

        try {
            // Load JDBC drivers
            loadDriver(dbType);

            // Create connections
            refConn = DriverManager.getConnection(reference.getJdbcUrl(), 
                reference.getUsername(), reference.getPassword());
            targetConn = DriverManager.getConnection(target.getJdbcUrl(), 
                target.getUsername(), target.getPassword());

            // Create Liquibase Database instances
            referenceDb = DatabaseFactory.getInstance()
                . findCorrectDatabaseImplementation(new JdbcConnection(refConn));
            targetDb = DatabaseFactory.getInstance()
                .findCorrectDatabaseImplementation(new JdbcConnection(targetConn));

            // Perform diff
            CompareControl compareControl = new CompareControl(
                new HashSet<>(Arrays.asList(
                    Table.class,
                    Column.class,
                    Index. class,
                    PrimaryKey.class,
                    ForeignKey.class,
                    UniqueConstraint.class
                ))
            );

            DiffResult diffResult = liquibase.diff.DiffGeneratorFactory.getInstance()
                .compare(referenceDb, targetDb, compareControl);

            // Parse diff result
            return parseDiffResult(diffResult);

        } finally {
            if (referenceDb != null) referenceDb.close();
            if (targetDb != null) targetDb.close();
            if (refConn != null) refConn.close();
            if (targetConn != null) targetConn.close();
        }
    }

    private void loadDriver(DatabaseType dbType) throws Exception {
        switch (dbType) {
            case ORACLE -> Class.forName("oracle.jdbc. OracleDriver");
            case POSTGRES -> Class.forName("org.postgresql.Driver");
            case MYSQL -> Class.forName("com.mysql.cj.jdbc.Driver");
            case MSSQL -> Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
            case DB2 -> Class.forName("com.ibm.db2.jcc.DB2Driver");
        }
    }

    private StructuralDrift parseDiffResult(DiffResult diffResult) {
        StructuralDrift drift = new StructuralDrift();

        // Missing objects (in reference, not in target)
        drift.getMissingTables().addAll(getObjectNames(diffResult.getMissingObjects(Table.class)));
        drift.getMissingColumns().addAll(getObjectNames(diffResult.getMissingObjects(Column.class)));
        drift.getMissingIndexes().addAll(getObjectNames(diffResult.getMissingObjects(Index.class)));
        drift.getMissingPrimaryKeys().addAll(getObjectNames(diffResult.getMissingObjects(PrimaryKey.class)));
        drift.getMissingForeignKeys().addAll(getObjectNames(diffResult.getMissingObjects(ForeignKey.class)));
        drift.getMissingConstraints().addAll(getObjectNames(diffResult.getMissingObjects(UniqueConstraint.class)));

        // Unexpected objects (in target, not in reference)
        drift.getUnexpectedTables().addAll(getObjectNames(diffResult.getUnexpectedObjects(Table.class)));
        drift.getUnexpectedColumns().addAll(getObjectNames(diffResult.getUnexpectedObjects(Column.class)));
        drift.getUnexpectedIndexes().addAll(getObjectNames(diffResult.getUnexpectedObjects(Index.class)));

        // Changed objects
        drift.getChangedTables().addAll(getObjectNames(diffResult.getChangedObjects(Table.class)));
        drift.getChangedColumns().addAll(getObjectNames(diffResult.getChangedObjects(Column.class)));

        return drift;
    }

    private List<String> getObjectNames(Set<?  extends DatabaseObject> objects) {
        List<String> names = new ArrayList<>();
        for (DatabaseObject obj :  objects) {
            names.add(obj.getName());
        }
        return names;
    }
}