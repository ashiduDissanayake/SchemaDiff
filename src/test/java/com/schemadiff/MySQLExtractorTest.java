package com.schemadiff;

import com.schemadiff.core.extractors.MySQLExtractor;
import com.schemadiff.model.ConstraintMetadata;
import com.schemadiff.model.DatabaseMetadata;
import com.schemadiff.model.TableMetadata;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MySQLExtractorTest {

    private void setupMockDatabaseMetaData(Connection mockConn) throws Exception {
        java.sql.DatabaseMetaData mockMetaData = mock(java.sql.DatabaseMetaData.class);
        when(mockConn.getMetaData()).thenReturn(mockMetaData);
        when(mockMetaData.getDatabaseMajorVersion()).thenReturn(8);
        when(mockMetaData.getDatabaseMinorVersion()).thenReturn(0);
        when(mockMetaData.getDatabaseProductName()).thenReturn("MySQL");
        when(mockConn.getCatalog()).thenReturn("test_db");
    }

    @Test
    public void testMultipleForeignKeysSameTablePair() throws Exception {
        Connection mockConn = mock(Connection.class);
        setupMockDatabaseMetaData(mockConn);

        Statement mockStmt = mock(Statement.class);
        PreparedStatement mockPrepStmt = mock(PreparedStatement.class);
        ResultSet mockRsTables = mock(ResultSet.class);
        ResultSet mockRsColumns = mock(ResultSet.class);
        ResultSet mockRsPKs = mock(ResultSet.class);
        ResultSet mockRsFKs = mock(ResultSet.class);
        ResultSet mockRsIndexes = mock(ResultSet.class);

        when(mockConn.createStatement()).thenReturn(mockStmt);
        when(mockConn.prepareStatement(anyString())).thenReturn(mockPrepStmt);

        // Mock Tables
        when(mockPrepStmt.executeQuery())
                .thenReturn(mockRsTables)
                .thenReturn(mockRsColumns)
                .thenReturn(mockRsPKs)
                .thenReturn(mockRsFKs)
                .thenReturn(mockRsIndexes);

        // Table: orders
        when(mockRsTables.next()).thenReturn(true).thenReturn(false);
        when(mockRsTables.getString("TABLE_NAME")).thenReturn("orders");

        // Columns (simplified, just needed to avoid NPE if any)
        when(mockRsColumns.next()).thenReturn(false);

        // PKs
        when(mockRsPKs.next()).thenReturn(false);

        // FKs
        // We simulate two FKs: fk_user_id (orders.user_id -> users.id) and fk_creator_id (orders.creator_id -> users.id)
        // Note: The query uses ORDER BY TABLE_NAME, ORDINAL_POSITION.
        // We'll simulate 2 rows.
        when(mockRsFKs.next()).thenReturn(true, true, false);

        // Row 1: fk_user_id
        // Row 2: fk_creator_id

        // Order of calls inside loop:
        // constraintName = rs.getString("CONSTRAINT_NAME");
        // table = rs.getString("TABLE_NAME");
        // column = rs.getString("COLUMN_NAME");
        // refTable = rs.getString("REFERENCED_TABLE_NAME");

        when(mockRsFKs.getString("CONSTRAINT_NAME")).thenReturn("fk_user_id", "fk_creator_id");
        when(mockRsFKs.getString("TABLE_NAME")).thenReturn("orders", "orders");
        when(mockRsFKs.getString("COLUMN_NAME")).thenReturn("user_id", "creator_id");
        when(mockRsFKs.getString("REFERENCED_TABLE_NAME")).thenReturn("users", "users");
        when(mockRsFKs.getString("REFERENCED_COLUMN_NAME")).thenReturn("id", "id");
        when(mockRsFKs.getString("UPDATE_RULE")).thenReturn("RESTRICT", "RESTRICT");
        when(mockRsFKs.getString("DELETE_RULE")).thenReturn("CASCADE", "CASCADE");

        // Indexes
        when(mockRsIndexes.next()).thenReturn(false);

        MySQLExtractor extractor = new MySQLExtractor("test_db");
        DatabaseMetadata metadata = extractor.extract(mockConn);

        TableMetadata ordersTable = metadata.getTable("orders");
        List<ConstraintMetadata> constraints = ordersTable.getConstraints();

        // We expect 2 foreign keys.
        // If the bug exists, we might see only 1, because the key was "orders|users".
        long fkCount = constraints.stream()
                .filter(c -> "FOREIGN_KEY".equals(c.getType()))
                .count();

        assertEquals(2, fkCount, "Should extract both foreign keys referencing the same table");
    }

    @Test
    public void testUnsignedColumn() throws Exception {
        Connection mockConn = mock(Connection.class);
        setupMockDatabaseMetaData(mockConn);

        Statement mockStmt = mock(Statement.class);
        PreparedStatement mockPrepStmt = mock(PreparedStatement.class);
        ResultSet mockRsTables = mock(ResultSet.class);
        ResultSet mockRsColumns = mock(ResultSet.class);
        ResultSet mockRsPKs = mock(ResultSet.class);
        ResultSet mockRsFKs = mock(ResultSet.class);
        ResultSet mockRsIndexes = mock(ResultSet.class);

        when(mockConn.createStatement()).thenReturn(mockStmt);
        when(mockConn.prepareStatement(anyString())).thenReturn(mockPrepStmt);

        // Mock Tables
        when(mockPrepStmt.executeQuery())
                .thenReturn(mockRsTables)
                .thenReturn(mockRsColumns)
                .thenReturn(mockRsPKs)
                .thenReturn(mockRsFKs)
                .thenReturn(mockRsIndexes);

        // Table: users
        when(mockRsTables.next()).thenReturn(true).thenReturn(false);
        when(mockRsTables.getString("TABLE_NAME")).thenReturn("users");

        // Columns
        // Simulate: id INT UNSIGNED
        when(mockRsColumns.next()).thenReturn(true).thenReturn(false);
        when(mockRsColumns.getString("TABLE_NAME")).thenReturn("users");
        when(mockRsColumns.getString("COLUMN_NAME")).thenReturn("id");
        when(mockRsColumns.getString("DATA_TYPE")).thenReturn("int");
        when(mockRsColumns.getString("COLUMN_TYPE")).thenReturn("int(10) unsigned"); // This is what MySQL returns in COLUMN_TYPE

        // Mocking behavior for buildDataType
        // Note: each call to wasNull() corresponds to the preceding getLong call.
        // MySQLExtractor calls getLong("CHARACTER_MAXIMUM_LENGTH"), then wasNull().
        // Then getLong("NUMERIC_PRECISION"), then wasNull().
        // Then getLong("NUMERIC_SCALE"), then wasNull().

        // Mockito requires careful ordering.
        // We will assume the order of calls in the code is preserved.

        when(mockRsColumns.getLong("CHARACTER_MAXIMUM_LENGTH")).thenReturn(0L);
        when(mockRsColumns.getLong("NUMERIC_PRECISION")).thenReturn(10L);
        when(mockRsColumns.getLong("NUMERIC_SCALE")).thenReturn(0L);

        // wasNull() is called 3 times.
        // 1st time (after CHAR_MAX): true (so it's null)
        // 2nd time (after PRECISION): false (so it's 10)
        // 3rd time (after SCALE): false (so it's 0)
        when(mockRsColumns.wasNull()).thenReturn(true, false, false);

        when(mockRsColumns.getString("IS_NULLABLE")).thenReturn("NO");
        when(mockRsColumns.getString("COLUMN_DEFAULT")).thenReturn(null);

        // Others
        when(mockRsPKs.next()).thenReturn(false);
        when(mockRsFKs.next()).thenReturn(false);
        when(mockRsIndexes.next()).thenReturn(false);

        MySQLExtractor extractor = new MySQLExtractor("test_db");
        DatabaseMetadata metadata = extractor.extract(mockConn);

        TableMetadata usersTable = metadata.getTable("users");
        List<com.schemadiff.model.ColumnMetadata> columns = usersTable.getColumns();

        assertEquals(1, columns.size());

        com.schemadiff.model.ColumnMetadata col = columns.get(0);
        assertEquals("int(10)", col.getDataType());
        assertEquals(true, col.isUnsigned());
    }

    @Test
    public void testCommonDataTypes() throws Exception {
        // Setup mocks
        Connection mockConn = mock(Connection.class);
        setupMockDatabaseMetaData(mockConn);

        Statement mockStmt = mock(Statement.class);
        PreparedStatement mockPrepStmt = mock(PreparedStatement.class);
        ResultSet mockRsTables = mock(ResultSet.class);
        ResultSet mockRsColumns = mock(ResultSet.class);

        when(mockConn.createStatement()).thenReturn(mockStmt);
        when(mockConn.prepareStatement(anyString())).thenReturn(mockPrepStmt);

        when(mockPrepStmt.executeQuery())
                .thenReturn(mockRsTables)
                .thenReturn(mockRsColumns)
                .thenReturn(mock(ResultSet.class)) // PKs
                .thenReturn(mock(ResultSet.class)) // FKs
                .thenReturn(mock(ResultSet.class)) // CheckConstraints
                .thenReturn(mock(ResultSet.class)) // UniqueConstraints
                .thenReturn(mock(ResultSet.class)); // Indexes

        // Table
        when(mockRsTables.next()).thenReturn(true).thenReturn(false);
        when(mockRsTables.getString("TABLE_NAME")).thenReturn("types_table");

        // Columns: varchar(100), decimal(10,2), timestamp
        when(mockRsColumns.next()).thenReturn(true, true, true, false);
        when(mockRsColumns.getString("TABLE_NAME")).thenReturn("types_table");

        // 1. VARCHAR(100)
        when(mockRsColumns.getString("COLUMN_NAME")).thenReturn("col_varchar", "col_decimal", "col_timestamp");
        when(mockRsColumns.getString("DATA_TYPE")).thenReturn("varchar", "decimal", "timestamp");
        when(mockRsColumns.getString("COLUMN_TYPE")).thenReturn("varchar(100)", "decimal(10,2)", "timestamp");

        // wasNull calls logic:
        // 1. col_varchar: MAX_LEN=100 (not null), PREC=null (wasNull=true), SCALE=null (wasNull=true)
        // 2. col_decimal: MAX_LEN=null (wasNull=true), PREC=10 (false), SCALE=2 (false)
        // 3. col_timestamp: MAX_LEN=null (true), PREC=null (true), SCALE=null (true)

        // Setup return values for getLong and wasNull
        // Note: Mockito requires sequential setup for consecutive calls

        // CHAR_MAX_LENGTH calls:
        // 1 (varchar): 100
        // 2 (decimal): 0 (will be followed by wasNull=true)
        // 3 (timestamp): 0 (will be followed by wasNull=true)
        when(mockRsColumns.getLong("CHARACTER_MAXIMUM_LENGTH")).thenReturn(100L, 0L, 0L);

        // NUMERIC_PREC calls:
        // 1 (varchar): 0 (wasNull=true)
        // 2 (decimal): 10
        // 3 (timestamp): 0 (wasNull=true)
        when(mockRsColumns.getLong("NUMERIC_PRECISION")).thenReturn(0L, 10L, 0L);

        // NUMERIC_SCALE calls:
        // 1 (varchar): 0 (wasNull=true)
        // 2 (decimal): 2
        // 3 (timestamp): 0 (wasNull=true)
        when(mockRsColumns.getLong("NUMERIC_SCALE")).thenReturn(0L, 2L, 0L);

        // wasNull sequence (3 calls per column * 3 columns = 9 calls)
        // Col 1: false (len), true (prec), true (scale)
        // Col 2: true (len), false (prec), false (scale)
        // Col 3: true (len), true (prec), true (scale)
        when(mockRsColumns.wasNull()).thenReturn(
            false, true, true,  // Varchar
            true, false, false, // Decimal
            true, true, true    // Timestamp
        );

        when(mockRsColumns.getString("IS_NULLABLE")).thenReturn("YES");
        when(mockRsColumns.getString("COLUMN_DEFAULT")).thenReturn(null);

        MySQLExtractor extractor = new MySQLExtractor("test_db");
        DatabaseMetadata metadata = extractor.extract(mockConn);
        TableMetadata table = metadata.getTable("types_table");

        List<com.schemadiff.model.ColumnMetadata> cols = table.getColumns();
        assertEquals(3, cols.size());

        assertEquals("varchar(100)", cols.get(0).getDataType());
        assertEquals(false, cols.get(0).isNotNull());

        assertEquals("decimal(10,2)", cols.get(1).getDataType());
        assertEquals("timestamp", cols.get(2).getDataType());
    }

    @Test
    public void testPrimaryKeys() throws Exception {
        Connection mockConn = mock(Connection.class);
        setupMockDatabaseMetaData(mockConn);

        Statement mockStmt = mock(Statement.class);
        PreparedStatement mockPrepStmt = mock(PreparedStatement.class);
        ResultSet mockRsTables = mock(ResultSet.class);
        ResultSet mockRsPKs = mock(ResultSet.class);

        when(mockConn.createStatement()).thenReturn(mockStmt);
        when(mockConn.prepareStatement(anyString())).thenReturn(mockPrepStmt);

        when(mockPrepStmt.executeQuery())
                .thenReturn(mockRsTables)
                .thenReturn(mock(ResultSet.class)) // Columns
                .thenReturn(mockRsPKs)
                .thenReturn(mock(ResultSet.class)) // FKs
                .thenReturn(mock(ResultSet.class)) // CheckConstraints
                .thenReturn(mock(ResultSet.class)) // UniqueConstraints
                .thenReturn(mock(ResultSet.class)); // Indexes

        when(mockRsTables.next()).thenReturn(true).thenReturn(false);
        when(mockRsTables.getString("TABLE_NAME")).thenReturn("users");

        // PK Result Set
        // Composite PK: id, org_id
        when(mockRsPKs.next()).thenReturn(true, true, false);
        when(mockRsPKs.getString("TABLE_NAME")).thenReturn("users");
        when(mockRsPKs.getString("COLUMN_NAME")).thenReturn("id", "org_id");

        MySQLExtractor extractor = new MySQLExtractor("test_db");
        DatabaseMetadata metadata = extractor.extract(mockConn);
        TableMetadata table = metadata.getTable("users");

        List<ConstraintMetadata> constraints = table.getConstraints();
        // Constraint type for PK is "PRIMARY_KEY" in MySQLExtractor
        long pkCount = constraints.stream().filter(c -> "PRIMARY_KEY".equals(c.getType())).count();
        assertEquals(1, pkCount);

        ConstraintMetadata pk = constraints.stream().filter(c -> "PRIMARY_KEY".equals(c.getType())).findFirst().get();
        assertEquals(List.of("id", "org_id"), pk.getColumns());
    }

    @Test
    public void testIndexes() throws Exception {
        Connection mockConn = mock(Connection.class);
        setupMockDatabaseMetaData(mockConn);

        Statement mockStmt = mock(Statement.class);
        PreparedStatement mockPrepStmt = mock(PreparedStatement.class);
        ResultSet mockRsTables = mock(ResultSet.class);
        ResultSet mockRsIndexes = mock(ResultSet.class);

        when(mockConn.createStatement()).thenReturn(mockStmt);
        when(mockConn.prepareStatement(anyString())).thenReturn(mockPrepStmt);

        when(mockPrepStmt.executeQuery())
                .thenReturn(mockRsTables)
                .thenReturn(mock(ResultSet.class)) // Columns
                .thenReturn(mock(ResultSet.class)) // PKs
                .thenReturn(mock(ResultSet.class)) // FKs
                .thenReturn(mock(ResultSet.class)) // CheckConstraints
                .thenReturn(mock(ResultSet.class)) // UniqueConstraints
                .thenReturn(mockRsIndexes);

        when(mockRsTables.next()).thenReturn(true).thenReturn(false);
        when(mockRsTables.getString("TABLE_NAME")).thenReturn("users");

        // Indexes
        // 1. idx_email (unique)
        // 2. idx_name_city (composite, non-unique) - 2 rows
        when(mockRsIndexes.next()).thenReturn(true, true, true, false);

        when(mockRsIndexes.getString("TABLE_NAME")).thenReturn("users");
        when(mockRsIndexes.getString("INDEX_NAME")).thenReturn("idx_email", "idx_name_city", "idx_name_city");
        when(mockRsIndexes.getString("COLUMN_NAME")).thenReturn("email", "name", "city");

        // NON_UNIQUE: 0 = Unique, 1 = Non-Unique
        when(mockRsIndexes.getInt("NON_UNIQUE")).thenReturn(0, 1, 1);

        MySQLExtractor extractor = new MySQLExtractor("test_db");
        DatabaseMetadata metadata = extractor.extract(mockConn);
        TableMetadata table = metadata.getTable("users");

        List<com.schemadiff.model.IndexMetadata> indexes = table.getIndexes();
        assertEquals(2, indexes.size());

        // Check unique index
        var idxEmail = indexes.stream().filter(i -> "idx_email".equals(i.getName())).findFirst().orElseThrow();
        assertEquals(true, idxEmail.isUnique());
        assertEquals(List.of("email"), idxEmail.getColumns());

        // Check composite index
        var idxComposite = indexes.stream().filter(i -> "idx_name_city".equals(i.getName())).findFirst().orElseThrow();
        assertEquals(false, idxComposite.isUnique());
        assertEquals(List.of("name", "city"), idxComposite.getColumns());
    }
}
