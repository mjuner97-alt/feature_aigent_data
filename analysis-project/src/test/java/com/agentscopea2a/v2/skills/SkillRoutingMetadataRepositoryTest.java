package com.agentscopea2a.v2.skills;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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

    @Test
    void findAllCachesWithinTtlAndFindActiveFiltersInactive() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getString("skill_name")).thenReturn("active_skill", "disabled_skill");
        when(rs.getString("short_summary")).thenReturn("s", "s");
        when(rs.getString("aliases")).thenReturn("[]", "[]");
        when(rs.getString("keywords")).thenReturn("[]", "[]");
        when(rs.getString("metric_tags")).thenReturn("[]", "[]");
        when(rs.getString("domain_tags")).thenReturn("[]", "[]");
        when(rs.getString("data_source_tags")).thenReturn("[]", "[]");
        when(rs.getInt("priority")).thenReturn(0, 0);
        when(rs.getBoolean("active")).thenReturn(true, false);

        SkillRoutingMetadataRepository repository =
                new SkillRoutingMetadataRepository(dataSource, new ObjectMapper(), 60_000L);
        markSchemaReady(repository);

        assertEquals(2, repository.findAll().size());
        assertEquals(List.of("active_skill"), repository.findActive().stream()
                .map(SkillRoutingMetadata::skillName).toList());

        // Second read inside the TTL window must not touch the database again.
        assertEquals(2, repository.findAll().size());
        verify(dataSource, times(1)).getConnection();
    }

    @Test
    void upsertInvalidatesCache() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);
        when(statement.executeUpdate()).thenReturn(1);

        SkillRoutingMetadataRepository repository =
                new SkillRoutingMetadataRepository(dataSource, new ObjectMapper(), 60_000L);
        markSchemaReady(repository);

        assertTrue(repository.findAll().isEmpty());
        repository.upsert(new SkillRoutingMetadata("q2_skill", "summary", List.of(), List.of(),
                List.of(), List.of(), List.of(), 0, true, null));

        // Cache invalidated -> next read goes back to the database
        // (initial findAll + upsert itself + post-upsert findAll).
        assertTrue(repository.findAll().isEmpty());
        verify(dataSource, times(3)).getConnection();
    }

    private static void markSchemaReady(SkillRoutingMetadataRepository repository) throws Exception {
        Field field = SkillRoutingMetadataRepository.class.getDeclaredField("tableEnsured");
        field.setAccessible(true);
        field.setBoolean(repository, true);
    }
}
