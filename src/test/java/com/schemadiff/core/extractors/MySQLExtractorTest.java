package com.schemadiff.core.extractors;

import com.schemadiff.model.DatabaseMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.*;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class MySQLExtractorTest {

    @Mock
    private Connection connection;
    @Mock
    private DatabaseMetaData metaData;
    @Mock
    private Statement statement;
    @Mock
    private PreparedStatement preparedStatement;
    @Mock
    private ResultSet resultSetSchema;
    @Mock
    private ResultSet resultSetTables;

    private MySQLExtractor extractor;

    @BeforeEach
    public void setUp() throws SQLException {
        extractor = new MySQLExtractor(null);

        lenient().when(connection.getMetaData()).thenReturn(metaData);
        lenient().when(metaData.getDatabaseMajorVersion()).thenReturn(8);
        lenient().when(metaData.getDatabaseMinorVersion()).thenReturn(0);

        // Mock statement for getSchemaName
        lenient().when(connection.createStatement()).thenReturn(statement);
        lenient().when(statement.executeQuery(anyString())).thenReturn(resultSetSchema);

        // Mock prepared statement for other queries
        lenient().when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        lenient().when(preparedStatement.executeQuery()).thenReturn(resultSetTables);

        // Mock getSchemaName result
        lenient().when(resultSetSchema.next()).thenReturn(true);
        lenient().when(resultSetSchema.getString(1)).thenReturn("test_schema");

        // Mock extractTables result (empty)
        lenient().when(resultSetTables.next()).thenReturn(false);
    }

    @Test
    public void testExtract() throws SQLException {
        DatabaseMetadata metadata = extractor.extract(connection);
        assertNotNull(metadata);

        verify(resultSetSchema, atLeastOnce()).getString(1);
    }
}
