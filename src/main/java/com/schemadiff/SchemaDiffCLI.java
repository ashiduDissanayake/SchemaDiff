package com.schemadiff;

import com.schemadiff.container.ContainerManager;
import com.schemadiff.container.SQLProvisioner;
import com.schemadiff.core.ComparisonEngine;
import com.schemadiff.core.MetadataExtractor;
import com.schemadiff. core.extractors.*;
import com.schemadiff.model.*;
import com.schemadiff.util.JDBCHelper;
import com.schemadiff.report.TreeReportBuilder;
import picocli.CommandLine;
import picocli.CommandLine. Command;
import picocli. CommandLine.Option;

import java.io.File;
import java.sql.Connection;
import java.util. concurrent.Callable;

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
        ContainerManager refContainer = null;
        ContainerManager targetContainer = null;

        try {
            DatabaseType type = DatabaseType.fromString(dbType);

            // Determine operational mode
            ComparisonMode mode = detectMode(reference, target);
            System.out.println("📊 Mode: " + mode);

            // Extract reference metadata
            DatabaseMetadata refMetadata;
            if (isScript(reference)) {
                validateDockerImage();
                refContainer = new ContainerManager(dockerImage, type);
                refContainer.start();
                new SQLProvisioner(refContainer. getConnection()).execute(new File(reference));
                refMetadata = extractMetadata(refContainer.getConnection(), type);
            } else {
                Connection conn = JDBCHelper.connect(reference, refUser, refPass);
                refMetadata = extractMetadata(conn, type);
                conn.close();
            }

            // Extract target metadata
            DatabaseMetadata targetMetadata;
            if (isScript(target)) {
                validateDockerImage();
                targetContainer = new ContainerManager(dockerImage, type);
                targetContainer.start();
                new SQLProvisioner(targetContainer.getConnection()).execute(new File(target));
                targetMetadata = extractMetadata(targetContainer. getConnection(), type);
            } else {
                Connection conn = JDBCHelper.connect(target, targetUser, targetPass);
                targetMetadata = extractMetadata(conn, type);
                conn. close();
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
            System.err.println("❌ Error: " + e. getMessage());
            e.printStackTrace();
            return 2;
        } finally {
            if (refContainer != null) refContainer.stop();
            if (targetContainer != null) targetContainer.stop();
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
        int exitCode = new CommandLine(new SchemaDiffCLI()).execute(args);
        System.exit(exitCode);
    }

}