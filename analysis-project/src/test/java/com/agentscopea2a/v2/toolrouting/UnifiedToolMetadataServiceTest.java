package com.agentscopea2a.v2.toolrouting;

import com.agentscopea2a.entity.SqlRegistryEntry;
import com.agentscopea2a.mapper.gauss.ScriptRegistryMapper;
import com.agentscopea2a.mapper.gauss.SqlRegistryMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UnifiedToolMetadataServiceTest {

    @Test
    void returnsSqlParametersAndInvocationWithoutExposingTemplate() {
        ToolRoutingMetadataRepository repository = mock(ToolRoutingMetadataRepository.class);
        ToolRoutingAvailabilityResolver availability = mock(ToolRoutingAvailabilityResolver.class);
        SqlRegistryMapper sqlMapper = mock(SqlRegistryMapper.class);
        ScriptRegistryMapper scriptMapper = mock(ScriptRegistryMapper.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ApiToolMetadataProvider> apiProvider = mock(ObjectProvider.class);
        ToolRoutingMetadata metadata = new ToolRoutingMetadata("quality_sql", ToolRoutingToolType.SQL,
                "quality summary", List.of("QI卡口"), List.of("质量分"), List.of("部门"), 5, true, null);
        when(repository.findByToolId("quality_sql")).thenReturn(Optional.of(metadata));
        when(availability.isAvailable(metadata)).thenReturn(true);
        when(sqlMapper.selectBySqlId("quality_sql")).thenReturn(SqlRegistryEntry.builder()
                .paramsSchema("[{\"name\":\"department\",\"type\":\"string\",\"required\":true,\"description\":\"部门\"}]")
                .sqlTemplate("select secret from t").build());

        ToolMetadataResponse response = new UnifiedToolMetadataService(repository, availability, sqlMapper,
                scriptMapper, apiProvider, new ObjectMapper()).find("quality_sql");

        assertEquals(ToolRoutingToolType.SQL, response.toolType());
        assertEquals(List.of("QI卡口"), response.topicTags());
        assertEquals("sql_registry_exec", response.executeWith());
        assertEquals("sqlId", response.invocation().idField());
        assertEquals("department", response.parameters().get(0).name());
        assertEquals("部门", response.parameters().get(0).description());
    }

    @Test
    void returnsMetadataForExplicitToolEvenWhenRouteIsDisabled() {
        ToolRoutingMetadataRepository repository = mock(ToolRoutingMetadataRepository.class);
        ToolRoutingAvailabilityResolver availability = mock(ToolRoutingAvailabilityResolver.class);
        SqlRegistryMapper sqlMapper = mock(SqlRegistryMapper.class);
        ScriptRegistryMapper scriptMapper = mock(ScriptRegistryMapper.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ApiToolMetadataProvider> apiProvider = mock(ObjectProvider.class);
        ToolRoutingMetadata metadata = new ToolRoutingMetadata("quality_sql", ToolRoutingToolType.SQL,
                "quality summary", List.of(), List.of(), List.of(), 5, false, null);
        when(repository.findByToolId("quality_sql")).thenReturn(Optional.of(metadata));
        when(availability.isAvailable(metadata)).thenReturn(true);
        when(sqlMapper.selectBySqlId("quality_sql")).thenReturn(SqlRegistryEntry.builder()
                .paramsSchema("[]")
                .sqlTemplate("select 1").build());

        ToolMetadataResponse response = new UnifiedToolMetadataService(repository, availability, sqlMapper,
                scriptMapper, apiProvider, new ObjectMapper()).find("quality_sql");

        assertEquals("quality_sql", response.toolId());
    }
}
