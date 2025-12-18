package com.wso2.migration.checker;

import com.wso2.migration.checker.driftmaster.core.*;
import com.wso2.migration.checker.driftmaster.core.containers.*;
import com.wso2.migration.checker.driftmaster.model.*;
import picocli.CommandLine;
import picocli.CommandLine. Command;
import picocli. CommandLine.Option;

import java.io.File;
import java.util.concurrent.Callable;

@Command(name = "driftmaster", mixinStandardHelpOptions = true, version = "1.0",
        description = "Detects schema and logic drift between databases")
public class DriftMaster implements Callable<Integer> {

    @Option(names = {"--reference"}, required = true, description = "Reference DB JDBC URL")
    String referenceJdbc;

    @Option(names = {"--reference-user"}, required = true, description = "Reference DB username")
    String referenceUser;

    @Option(names = {"--reference-pass"}, required = true, description = "Reference DB password")
    String referencePass;

    @Option(names = {"--target-schema"}, description = "Path to . sql file for target schema")
    File targetSchema;

    @Option(names = {"--target-jdbc"}, description = "Target DB JDBC URL (alternative to --target-schema)")
    String targetJdbc;

    @Option(names = {"--target-user"}, description = "Target DB username")
    String targetUser;

    @Option(names = {"--target-pass"}, description = "Target DB password")
    String targetPass;

    @Option(names = {"--db"}, required = true, description = "Database type:  oracle, postgres, mysql, mssql, db2")
    String dbType;

    @Override
    public Integer call() throws Exception {
        ContainerManager containerManager = null;
        DatabaseConfig targetConfig;

        try {
            // Parse and validate database type
            DatabaseType type = DatabaseType.fromString(dbType);

            // Determine target configuration
            if (targetSchema != null) {
                System.out.println("🚀 Starting ephemeral " + type + " container...");
                containerManager = createContainer(type);
                containerManager.start();

                // Provision schema
                System.out.println("📦 Provisioning schema into container...");
                ProvisioningEngine provisioner = new ProvisioningEngine(containerManager. getJdbcUrl(),
                        containerManager.getUsername(), containerManager.getPassword());
                provisioner.execute(targetSchema);

                targetConfig = new DatabaseConfig(containerManager.getJdbcUrl(),
                        containerManager.getUsername(), containerManager.getPassword());
            } else if (targetJdbc != null) {
                targetConfig = new DatabaseConfig(targetJdbc, targetUser, targetPass);
            } else {
                throw new IllegalArgumentException("Must provide either --target-schema or --target-jdbc");
            }

            DatabaseConfig referenceConfig = new DatabaseConfig(referenceJdbc, referenceUser, referencePass);

            // Perform structural diff
            System.out.println("🔍 Analyzing structural drift...");
            StructuralDiffEngine structuralEngine = new StructuralDiffEngine();
            StructuralDrift structuralDrift = structuralEngine.compare(referenceConfig, targetConfig, type);

            // Perform logic diff
            System.out.println("🔍 Analyzing logic drift...");
            LogicDiffEngine logicEngine = new LogicDiffEngine();
            LogicDrift logicDrift = logicEngine.compare(referenceConfig, targetConfig, type);

            // Build unified report
            DriftReportBuilder reportBuilder = new DriftReportBuilder();
            DriftReport report = reportBuilder.build(structuralDrift, logicDrift);

            System.out.println("\n" + report.toJson());

            return report.hasDrift() ? 1 : 0;

        } catch (Exception e) {
            System. err.println("❌ Error: " + e.getMessage());
            e.printStackTrace();
            return 2;
        } finally {
            if (containerManager != null) {
                System.out.println("🧹 Cleaning up containers...");
                containerManager.stop();
            }
        }
    }

    private ContainerManager createContainer(DatabaseType type) {
        return switch (type) {
            case ORACLE -> new OracleContainer();
            case POSTGRES -> new PostgresContainer();
            case MYSQL -> new MySQLContainer();
            case MSSQL -> new MSSQLContainer();
            case DB2 -> new DB2Container();
        };
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new DriftMaster()).execute(args);
        System.exit(exitCode);
    }

    enum DatabaseType {
        ORACLE, POSTGRES, MYSQL, MSSQL, DB2;

        static DatabaseType fromString(String s) {
            return switch (s.toLowerCase()) {
                case "oracle" -> ORACLE;
                case "postgres", "postgresql" -> POSTGRES;
                case "mysql" -> MYSQL;
                case "mssql", "sqlserver" -> MSSQL;
                case "db2" -> DB2;
                default -> throw new IllegalArgumentException("Unknown DB type: " + s);
            };
        }
    }
}