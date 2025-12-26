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
public class OracleExtractorTest {

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

    private OracleExtractor extractor;

    @BeforeEach
    public void setUp() throws SQLException {
        extractor = new OracleExtractor("SYSTEM");

        lenient().when(connection.getMetaData()).thenReturn(metaData);
        lenient().when(metaData.getDatabaseMajorVersion()).thenReturn(19);
        lenient().when(metaData.getDatabaseMinorVersion()).thenReturn(0);

        lenient().when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        lenient().when(preparedStatement.executeQuery()).thenReturn(resultSet);

        // OracleExtractor might use createStatement for schema owner if not provided,
        // but we provided "SYSTEM" in constructor.
        // However, let's mock it just in case logic changes.
        lenient().when(connection.createStatement()).thenReturn(statement);
        lenient().when(statement.executeQuery(anyString())).thenReturn(resultSetSchema);
    }

    @Test
    public void testExtract() throws SQLException {
        when(resultSet.next()).thenReturn(false);

        DatabaseMetadata metadata = extractor.extract(connection);
        assertNotNull(metadata);

        verify(connection, atLeast(4)).prepareStatement(anyString());
    }
}
