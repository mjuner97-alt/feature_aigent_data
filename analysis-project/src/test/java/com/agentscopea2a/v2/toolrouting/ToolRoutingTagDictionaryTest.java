package com.agentscopea2a.v2.toolrouting;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;

class ToolRoutingTagDictionaryTest {

    @Test
    void repairsLegacyTagConstraintWhenEnsuringSchema() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement createTable = mock(PreparedStatement.class);
        PreparedStatement alterConstraint = mock(PreparedStatement.class);
        PreparedStatement select = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            if (sql.startsWith("CREATE TABLE")) return createTable;
            if (sql.startsWith("ALTER TABLE")) return alterConstraint;
            return select;
        });
        when(select.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        new ToolRoutingTagDictionary(dataSource).findEnabled(ToolRoutingTagType.TOPIC);

        verify(alterConstraint, atLeastOnce()).execute();
        String constraintSql = org.mockito.Mockito.mockingDetails(connection).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("prepareStatement"))
                .map(invocation -> invocation.getArgument(0, String.class))
                .filter(sql -> sql.contains("ck_tool_route_tag_dictionary_type"))
                .reduce("", (all, sql) -> all + "\n" + sql);
        assertTrue(constraintSql.contains("TOPIC"));
        assertTrue(constraintSql.contains("DIMENSION"));
    }

    @Test
    void bindsEmptyDescriptionWhenTagDescriptionIsNull() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            if (sql.startsWith("CREATE TABLE")) return mock(PreparedStatement.class);
            if (sql.startsWith("ALTER TABLE")) return mock(PreparedStatement.class);
            assertTrue(sql.contains("COALESCE(?, ' ')"));
            return statement;
        });
        when(statement.executeUpdate()).thenReturn(1);

        boolean saved = new ToolRoutingTagDictionary(dataSource).upsert(
                new ToolRoutingTag(ToolRoutingTagType.TOPIC, "test", null, true));

        assertTrue(saved);
        verify(statement).setString(eq(3), eq(""));
    }
}
