package com.schemadiff.e2e.base;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Generic test context interface that works across all database types.
 *
 * Implementations handle database-specific connection management and SQL execution.
 */
public interface GenericTestContext extends AutoCloseable {

    /**
     * Gets the reference database name.
     */
    String getRefDbName();

    /**
     * Gets the target database name.
     */
    String getTargetDbName();

    /**
     * Sets up both reference and target databases with the same schema.
     */
    void setupBothSchemas(String schemaResourcePath) throws SQLException, Exception;

    /**
     * Sets up the reference database with the given schema.
     */
    void setupReferenceSchema(String schemaResourcePath) throws SQLException, Exception;

    /**
     * Sets up the target database with the given schema.
     */
    void setupTargetSchema(String schemaResourcePath) throws SQLException, Exception;

    /**
     * Applies a delta script to the target database.
     */
    void applyDelta(String deltaResourcePath) throws SQLException, Exception;

    /**
     * Applies a delta script to the reference database.
     */
    void applyDeltaToReference(String deltaResourcePath) throws SQLException, Exception;

    /**
     * Executes raw SQL on the target database.
     */
    void executeOnTarget(String sql) throws SQLException;

    /**
     * Executes raw SQL on the reference database.
     */
    void executeOnReference(String sql) throws SQLException;

    /**
     * Gets a connection to the reference database.
     */
    Connection getRefConnection() throws SQLException;

    /**
     * Gets a connection to the target database.
     */
    Connection getTargetConnection() throws SQLException;

    /**
     * Cleans up databases created by this context.
     */
    void cleanup();

    /**
     * AutoCloseable implementation.
     */
    @Override
    default void close() {
        cleanup();
    }
}

