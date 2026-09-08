package com.agentscopea2a.v2.toolrouting;

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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolRoutingMetadataRepositoryTest {

    @Test
    void ensuresTopicColumnWithOpenGaussCompatibleAlterSyntax() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        new ToolRoutingMetadataRepository(dataSource, new ObjectMapper(), mock(ToolRoutingTagDictionary.class), 0L)
                .findEnabled();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection, org.mockito.Mockito.atLeastOnce()).prepareStatement(sql.capture());
        assertTrue(sql.getAllValues().stream().anyMatch(value ->
                value.startsWith("ALTER TABLE tool_route_metadata ADD COLUMN topic_tags")));
        assertTrue(sql.getAllValues().stream().noneMatch(value ->
                value.contains("ADD COLUMN IF NOT EXISTS")));
    }

    @Test
    void upsertValidatesTagsUsesCompatibleSyntaxAndInvalidatesCache() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        ToolRoutingTagDictionary dictionary = mock(ToolRoutingTagDictionary.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);
        when(statement.executeUpdate()).thenReturn(1);

        ToolRoutingMetadataRepository repository = new ToolRoutingMetadataRepository(
                dataSource, new ObjectMapper(), dictionary, 60_000L);
        markSchemaReady(repository);
        repository.findEnabled();

        ToolRoutingMetadata metadata = new ToolRoutingMetadata("quality_script", ToolRoutingToolType.SCRIPT,
                "quality", List.of("QI卡口"), List.of("质量分"), List.of("部门"), 10, true, null);
        assertTrue(repository.upsert(metadata));
        repository.findEnabled();

        verify(dictionary).validateEnabled(ToolRoutingTagType.TOPIC, List.of("QI卡口"));
        verify(dictionary).validateEnabled(ToolRoutingTagType.METRIC, List.of("质量分"));
        verify(dictionary).validateEnabled(ToolRoutingTagType.DIMENSION, List.of("部门"));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection, times(3)).prepareStatement(sql.capture());
        assertTrue(sql.getAllValues().stream().anyMatch(value -> value.contains("ON DUPLICATE KEY UPDATE")));
        assertEquals(3, org.mockito.Mockito.mockingDetails(connection).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("prepareStatement")).count());
    }

    private static void markSchemaReady(ToolRoutingMetadataRepository repository) throws Exception {
        Field field = ToolRoutingMetadataRepository.class.getDeclaredField("tableEnsured");
        field.setAccessible(true);
        field.setBoolean(repository, true);
    }
}
