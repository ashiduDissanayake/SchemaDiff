package com.schemadiff;

import com.schemadiff.core.extractors.MySQLExtractor;
import com.schemadiff.model.ConstraintMetadata;
import com.schemadiff.model.DatabaseMetadata;
import com.schemadiff.model.TableMetadata;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MySQLExtractorTest {

    @Test
    public void testMultipleForeignKeysSameTablePair() throws Exception {
        Connection mockConn = mock(Connection.class);
        Statement mockStmt = mock(Statement.class);
        ResultSet mockRsTables = mock(ResultSet.class);
        ResultSet mockRsColumns = mock(ResultSet.class);
        ResultSet mockRsPKs = mock(ResultSet.class);
        ResultSet mockRsFKs = mock(ResultSet.class);
        ResultSet mockRsIndexes = mock(ResultSet.class);

        when(mockConn.createStatement()).thenReturn(mockStmt);

        // Mock Tables
        when(mockStmt.executeQuery(anyString()))
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

        // Indexes
        when(mockRsIndexes.next()).thenReturn(false);

        MySQLExtractor extractor = new MySQLExtractor();
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
        Statement mockStmt = mock(Statement.class);
        ResultSet mockRsTables = mock(ResultSet.class);
        ResultSet mockRsColumns = mock(ResultSet.class);
        ResultSet mockRsPKs = mock(ResultSet.class);
        ResultSet mockRsFKs = mock(ResultSet.class);
        ResultSet mockRsIndexes = mock(ResultSet.class);

        when(mockConn.createStatement()).thenReturn(mockStmt);

        // Mock Tables
        when(mockStmt.executeQuery(anyString()))
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

        MySQLExtractor extractor = new MySQLExtractor();
        DatabaseMetadata metadata = extractor.extract(mockConn);

        TableMetadata usersTable = metadata.getTable("users");
        List<com.schemadiff.model.ColumnMetadata> columns = usersTable.getColumns();

        assertEquals(1, columns.size());
        // We expect the type to include "unsigned" if the extractor supports it.
        // Currently, it uses DATA_TYPE ("int") and precision (10), so it produces "int(10)".
        // If it misses "unsigned", this test will fail if we assert "int(10) unsigned".
        // Let's assert what we WANT it to be.
        String expectedType = "int(10) unsigned";
        // Or at least "int unsigned". The precision handling in `buildDataType` appends `(10)`.

        // Note: buildDataType currently appends precision if available.
        // If DATA_TYPE is used, it returns "int". Precision is 10. So "int(10)".
        // It does not check for unsigned.

        assertEquals("int(10) unsigned", columns.get(0).getDataType());
    }
}
