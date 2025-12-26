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
public class PostgresExtractorTest {

    @Mock
    private Connection connection;
    @Mock
    private DatabaseMetaData metaData;
    @Mock
    private PreparedStatement preparedStatement;
    @Mock
    private ResultSet resultSet;

    private PostgresExtractor extractor;

    @BeforeEach
    public void setUp() throws SQLException {
        extractor = new PostgresExtractor("public");

        lenient().when(connection.getMetaData()).thenReturn(metaData);
        lenient().when(metaData.getDatabaseMajorVersion()).thenReturn(13);
        lenient().when(metaData.getDatabaseMinorVersion()).thenReturn(0);

        lenient().when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        lenient().when(preparedStatement.executeQuery()).thenReturn(resultSet);
    }

    @Test
    public void testExtract() throws SQLException {
        // Mock result sets to return empty first (or basic data) to avoid infinite loops if not handled
        when(resultSet.next()).thenReturn(false);

        DatabaseMetadata metadata = extractor.extract(connection);
        assertNotNull(metadata);

        // Verify that expected queries were executed (roughly)
        // Tables, Columns, Constraints (PK, FK, Check, Unique), Indexes
        verify(connection, atLeast(4)).prepareStatement(anyString());
    }
}
