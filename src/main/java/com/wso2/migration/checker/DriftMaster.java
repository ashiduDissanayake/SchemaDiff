package com.wso2.migration.checker;

import com.wso2.migration.checker.core.*;
import com.wso2.migration.checker.core.containers.*;
import com.wso2.migration.checker.model.*;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.util.concurrent.Callable;

@Command(name = "driftmaster", mixinStandardHelpOptions = true, version = "1.0",
        description = "Detects schema and logic drift between databases")
public class DriftMaster implements Callable<Integer> {

    @Option(names = {"--reference"}, description = "Reference DB JDBC URL")
    String referenceJdbc;

    @Option(names = {"--reference-user"}, description = "Reference DB username")
    String referenceUser;

    @Option(names = {"--reference-pass"}, description = "Reference DB password")
    String referencePass;

    @Option(names = {"--reference-schema"}, description = "Path to . sql file for reference schema")
    File referenceSchema;

    @Option(names = {"--target"}, description = "Target DB JDBC URL")
    String targetJdbc;

    @Option(names = {"--target-user"}, description = "Target DB username")
    String targetUser;

    @Option(names = {"--target-pass"}, description = "Target DB password")
    String targetPass;

    @Option(names = {"--target-schema"}, description = "Path to .sql file for target schema")
    File targetSchema;

    @Option(names = {"--db"}, required = true, description = "Database type:  oracle, postgres, mysql, mssql, db2")
    String dbType;

    @Override
    public Integer call() throws Exception {
        ContainerManager referenceContainer = null;
        ContainerManager targetContainer = null;
        DatabaseConfig referenceConfig;
        DatabaseConfig targetConfig;

        try {
            // Parse and validate database type
            DatabaseType type = DatabaseType.fromString(dbType);

            // ========== REFERENCE CONFIGURATION ==========
            if (referenceSchema != null) {
                // Reference is a SQL file -> Use ephemeral container
                System.out.println("🚀 Starting ephemeral " + type + " container for reference schema.. .");
                referenceContainer = createContainer(type);
                referenceContainer.start();

                System.out.println("📦 Provisioning reference schema into container...");
                ProvisioningEngine provisioner = new ProvisioningEngine(
                        referenceContainer.getJdbcUrl(),
                        referenceContainer.getUsername(),
                        referenceContainer.getPassword()
                );
                provisioner. execute(referenceSchema);

                referenceConfig = new DatabaseConfig(
                        referenceContainer.getJdbcUrl(),
                        referenceContainer.getUsername(),
                        referenceContainer.getPassword()
                );
            } else if (referenceJdbc != null) {
                // Reference is a live database
                referenceConfig = new DatabaseConfig(referenceJdbc, referenceUser, referencePass);
            } else {
                throw new IllegalArgumentException("Must provide either --reference or --reference-schema");
            }

            // ========== TARGET CONFIGURATION ==========
            if (targetSchema != null) {
                // Target is a SQL file -> Use ephemeral container
                System.out.println("🚀 Starting ephemeral " + type + " container for target schema...");
                targetContainer = createContainer(type);
                targetContainer.start();

                System.out.println("📦 Provisioning target schema into container...");
                ProvisioningEngine provisioner = new ProvisioningEngine(
                        targetContainer.getJdbcUrl(),
                        targetContainer.getUsername(),
                        targetContainer.getPassword()
                );
                provisioner.execute(targetSchema);

                targetConfig = new DatabaseConfig(
                        targetContainer.getJdbcUrl(),
                        targetContainer.getUsername(),
                        targetContainer.getPassword()
                );
            } else if (targetJdbc != null) {
                // Target is a live database
                targetConfig = new DatabaseConfig(targetJdbc, targetUser, targetPass);
            } else {
                throw new IllegalArgumentException("Must provide either --target or --target-schema");
            }

            // ========== PERFORM COMPARISON ==========
            System. out.println("🔍 Analyzing structural drift...");
            StructuralDiffEngine structuralEngine = new StructuralDiffEngine();
            StructuralDrift structuralDrift = structuralEngine.compare(referenceConfig, targetConfig, type);

            System.out. println("🔍 Analyzing logic drift...");
            LogicDiffEngine logicEngine = new LogicDiffEngine();
            LogicDrift logicDrift = logicEngine.compare(referenceConfig, targetConfig, type);

            // Build unified report
            DriftReportBuilder reportBuilder = new DriftReportBuilder();
            DriftReport report = reportBuilder.build(structuralDrift, logicDrift);

            System.out.println("\n" + report.toJson());

            return report.hasDrift() ? 1 : 0;

        } catch (Exception e) {
            System.err.println("❌ Error:  " + e.getMessage());
            e.printStackTrace();
            return 2;
        } finally {
            // Cleanup containers
            if (referenceContainer != null) {
                System.out.println("🧹 Cleaning up reference container...");
                referenceContainer.stop();
            }
            if (targetContainer != null) {
                System.out.println("🧹 Cleaning up target container...");
                targetContainer. stop();
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

    public enum DatabaseType {
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