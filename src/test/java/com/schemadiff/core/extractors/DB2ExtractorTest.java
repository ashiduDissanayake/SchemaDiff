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
public class DB2ExtractorTest {

    @Mock
    private Connection connection;
    @Mock
    private DatabaseMetaData metaData;
    @Mock
    private Statement statement;
    @Mock
    private PreparedStatement preparedStatement;
    @Mock
    private ResultSet resultSet;
    @Mock
    private ResultSet resultSetSchema;

    private DB2Extractor extractor;

    @BeforeEach
    public void setUp() throws SQLException {
        extractor = new DB2Extractor("DB2INST1");

        lenient().when(connection.getMetaData()).thenReturn(metaData);
        lenient().when(metaData.getDatabaseMajorVersion()).thenReturn(11); // DB2 11.5
        lenient().when(metaData.getDatabaseMinorVersion()).thenReturn(5);

        lenient().when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        lenient().when(preparedStatement.executeQuery()).thenReturn(resultSet);

        // For getSchemaName
        lenient().when(connection.createStatement()).thenReturn(statement);
        lenient().when(statement.executeQuery(anyString())).thenReturn(resultSetSchema);
    }

    @Test
    public void testExtract() throws SQLException {
        when(resultSet.next()).thenReturn(false);

        DatabaseMetadata metadata = extractor.extract(connection);
        assertNotNull(metadata);

        // Expect calls for Tables, Columns, PKs, FKs, Checks, Unique, Indexes
        verify(connection, atLeast(4)).prepareStatement(anyString());
    }
}
