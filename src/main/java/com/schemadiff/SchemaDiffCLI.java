package com.schemadiff;

import com.schemadiff.container.ContainerManager;
import com.schemadiff.container.SQLProvisioner;
import com.schemadiff.core.ComparisonEngine;
import com.schemadiff.core.MetadataExtractor;
import com.schemadiff.core.extractors.*;
import com.schemadiff.model.*;
import com.schemadiff.util.JDBCHelper;
import com.schemadiff.report.TreeReportBuilder;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.sql.Connection;
import java.util.concurrent.Callable;

@Command(name = "schemadiff", mixinStandardHelpOptions = true, version = "2.0.0",
        description = "Custom schema drift detection with hierarchical tree output")
public class SchemaDiffCLI implements Callable<Integer> {

    @Option(names = {"--reference"}, description = "Reference:  JDBC URL or . sql file path")
    String reference;

    @Option(names = {"--target"}, description = "Target: JDBC URL or . sql file path")
    String target;

    @Option(names = {"--ref-user"}, description = "Reference DB username")
    String refUser;

    @Option(names = {"--ref-pass"}, description = "Reference DB password")
    String refPass;

    @Option(names = {"--target-user"}, description = "Target DB username")
    String targetUser;

    @Option(names = {"--target-pass"}, description = "Target DB password")
    String targetPass;

    @Option(names = {"--db-type"}, required = true, description = "Database:  postgres, mysql, oracle, mssql, db2")
    String dbType;

    @Option(names = {"--image"}, description = "Docker image (required for . sql inputs, e.g., postgres:15)")
    String dockerImage;

    @Override
    public Integer call() throws Exception {
        ContainerManager sharedContainer = null;

        try {
            DatabaseType type = DatabaseType.fromString(dbType);

            // Determine operational mode
            ComparisonMode mode = detectMode(reference, target);
            System.out.println("Mode: " + mode);

            DatabaseMetadata refMetadata;
            DatabaseMetadata targetMetadata;

            // Optimized: Use single container for Script vs Script mode
            if (mode == ComparisonMode.SCRIPT_VS_SCRIPT) {
                validateDockerImage();

                // Single container, two databases
                sharedContainer = ContainerManager.getOrCreate(dockerImage, type);

                // Create reference database and provision schema
                System.out.println("Provisioning reference schema...");
                try (Connection refConn = sharedContainer.createDatabaseAndConnect("schemadiff_ref")) {
                    new SQLProvisioner(refConn).execute(new File(reference));
                    refMetadata = extractMetadata(refConn, type);
                }

                // Create target database and provision schema
                System.out.println("Provisioning target schema...");
                try (Connection targetConn = sharedContainer.createDatabaseAndConnect("schemadiff_target")) {
                    new SQLProvisioner(targetConn).execute(new File(target));
                    targetMetadata = extractMetadata(targetConn, type);
                }

            } else {
                // Other modes: Script vs Live, Live vs Script, Live vs Live

                // Extract reference metadata
                if (isScript(reference)) {
                    validateDockerImage();
                    sharedContainer = ContainerManager.getOrCreate(dockerImage, type);
                    try (Connection conn = sharedContainer.createDatabaseAndConnect("schemadiff_ref")) {
                        new SQLProvisioner(conn).execute(new File(reference));
                        refMetadata = extractMetadata(conn, type);
                    }
                } else {
                    try (Connection conn = JDBCHelper.connect(reference, refUser, refPass)) {
                        refMetadata = extractMetadata(conn, type);
                    }
                }

                // Extract target metadata
                if (isScript(target)) {
                    validateDockerImage();
                    if (sharedContainer == null) {
                        sharedContainer = ContainerManager.getOrCreate(dockerImage, type);
                    }
                    try (Connection conn = sharedContainer.createDatabaseAndConnect("schemadiff_target")) {
                        new SQLProvisioner(conn).execute(new File(target));
                        targetMetadata = extractMetadata(conn, type);
                    }
                } else {
                    try (Connection conn = JDBCHelper.connect(target, targetUser, targetPass)) {
                        targetMetadata = extractMetadata(conn, type);
                    }
                }
            }

            // Perform hierarchical comparison
            ComparisonEngine engine = new ComparisonEngine();
            DiffResult result = engine.compare(refMetadata, targetMetadata);

            // Generate tree report
            TreeReportBuilder reporter = new TreeReportBuilder();
            String report = reporter.build(result);
            System.out.println(report);

            return result.hasDifferences() ? 1 : 0;

        } catch (Exception e) {
            System.err.println("❌ Error: " + e.getMessage());
            e.printStackTrace();
            return 2;
        } finally {
            // Cleanup: drop the temporary databases
            if (sharedContainer != null) {
                sharedContainer.dropDatabase("schemadiff_ref");
                sharedContainer.dropDatabase("schemadiff_target");
                // Note: Container itself is cached and will be reused or cleaned up at JVM shutdown
                // For explicit cleanup, call ContainerManager.stopAll()
            }
        }
    }

    private ComparisonMode detectMode(String ref, String target) {
        boolean refIsScript = isScript(ref);
        boolean targetIsScript = isScript(target);

        if (refIsScript && targetIsScript) return ComparisonMode.SCRIPT_VS_SCRIPT;
        if (refIsScript) return ComparisonMode.SCRIPT_VS_LIVE;
        if (targetIsScript) return ComparisonMode.LIVE_VS_SCRIPT;
        return ComparisonMode.LIVE_VS_LIVE;
    }

    private boolean isScript(String input) {
        return input != null && input.toLowerCase().endsWith(".sql");
    }

    private void validateDockerImage() {
        if (dockerImage == null || dockerImage.isBlank()) {
            throw new IllegalArgumentException("--image required when using .sql scripts");
        }
    }

    private DatabaseMetadata extractMetadata(Connection conn, DatabaseType type) throws Exception {
        MetadataExtractor extractor = switch (type) {
            case POSTGRES -> new PostgresExtractor();
            case MYSQL -> new MySQLExtractor();
            case ORACLE -> new OracleExtractor();
            case MSSQL -> new MSSQLExtractor();
            case DB2 -> new DB2Extractor();
        };
        return extractor.extract(conn);
    }

    public static void main(String[] args) {
        // Suppress JDBC driver warnings (MSSQL prelogin, etc.)
        suppressJDBCWarnings();

        int exitCode = new CommandLine(new SchemaDiffCLI()).execute(args);
        System.exit(exitCode);
    }

    /**
     * Suppress verbose JDBC driver warnings for production use.
     * Specifically suppresses MSSQL prelogin warnings and other connection-related noise.
     */
    private static void suppressJDBCWarnings() {
        try {
            // Try to load logging configuration from properties file
            try (var is = SchemaDiffCLI.class.getClassLoader().getResourceAsStream("logging.properties")) {
                if (is != null) {
                    java.util.logging.LogManager.getLogManager().readConfiguration(is);
                }
            } catch (Exception e) {
                // Fallback to programmatic configuration
            }

            // Programmatically suppress MSSQL JDBC driver warnings (prelogin errors during container startup)
            java.util.logging.Logger.getLogger("com.microsoft.sqlserver.jdbc").setLevel(java.util.logging.Level.OFF);
            java.util.logging.Logger.getLogger("com.microsoft.sqlserver.jdbc.SQLServerConnection").setLevel(java.util.logging.Level.OFF);
            java.util.logging.Logger.getLogger("com.microsoft.sqlserver.jdbc.internals").setLevel(java.util.logging.Level.OFF);

            // Suppress Oracle JDBC driver verbose logging
            java.util.logging.Logger.getLogger("oracle.jdbc").setLevel(java.util.logging.Level.SEVERE);
            java.util.logging.Logger.getLogger("oracle.jdbc.driver").setLevel(java.util.logging.Level.SEVERE);
            java.util.logging.Logger.getLogger("oracle.net").setLevel(java.util.logging.Level.SEVERE);
            java.util.logging.Logger.getLogger("oracle.net.ns").setLevel(java.util.logging.Level.SEVERE);

            // Suppress Testcontainers verbose logging
            java.util.logging.Logger.getLogger("org.testcontainers").setLevel(java.util.logging.Level.WARNING);
        } catch (Exception e) {
            // Silently ignore if logging configuration fails
        }
    }

}