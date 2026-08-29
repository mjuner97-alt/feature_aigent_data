package com.agentscopea2a.v2.skills;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillRoutingMetadataRepositoryTest {

    @Test
    void upsertUsesOpenGaussCompatibleDuplicateKeySyntax() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(1);

        SkillRoutingMetadataRepository repository = new SkillRoutingMetadataRepository(dataSource, new ObjectMapper());
        markSchemaReady(repository);

        repository.upsert(new SkillRoutingMetadata("q2_skill", "summary", List.of("q2"), List.of(),
                List.of(), List.of(), List.of(), 0, true, null));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertTrue(sql.getValue().contains("ON DUPLICATE KEY UPDATE"));
        assertTrue(sql.getValue().contains("VALUES(short_summary)"));
        assertFalse(sql.getValue().contains("ON CONFLICT"));
        assertFalse(sql.getValue().contains("EXCLUDED."));
    }

    private static void markSchemaReady(SkillRoutingMetadataRepository repository) throws Exception {
        Field field = SkillRoutingMetadataRepository.class.getDeclaredField("tableEnsured");
        field.setAccessible(true);
        field.setBoolean(repository, true);
    }
}
