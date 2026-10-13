package com.agentscopea2a.v2.toolrouting;

import com.agentscopea2a.entity.ScriptRegistryEntry;
import com.agentscopea2a.entity.SqlRegistryEntry;
import com.agentscopea2a.mapper.gauss.ScriptRegistryMapper;
import com.agentscopea2a.mapper.gauss.SqlRegistryMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
        assertEquals("downloadFilename", response.parameters().get(1).name());
        assertEquals("string", response.parameters().get(1).type());
        assertEquals(false, response.parameters().get(1).required());
        assertEquals("referenceOnly", response.parameters().get(2).name());
        assertEquals("boolean", response.parameters().get(2).type());
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
        assertEquals("downloadFilename", response.parameters().get(0).name());
        assertEquals("referenceOnly", response.parameters().get(1).name());
    }

    @Test
    void fallsBackToApiIndexWhenNoMetadataRow() {
        ToolRoutingMetadataRepository repository = mock(ToolRoutingMetadataRepository.class);
        ToolRoutingAvailabilityResolver availability = mock(ToolRoutingAvailabilityResolver.class);
        SqlRegistryMapper sqlMapper = mock(SqlRegistryMapper.class);
        ScriptRegistryMapper scriptMapper = mock(ScriptRegistryMapper.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ApiToolMetadataProvider> apiProvider = mock(ObjectProvider.class);
        ApiToolMetadataProvider index = mock(ApiToolMetadataProvider.class);
        when(repository.findByToolId("generate_csv_download_url")).thenReturn(Optional.empty());
        when(apiProvider.getIfAvailable()).thenReturn(index);
        when(index.findApiTool("generate_csv_download_url")).thenReturn(Optional.of(new ApiToolMetadata(
                "generate_csv_download_url", "生成 CSV 下载短链",
                List.of(new ToolParameterMetadata("agentPath", "java.lang.String", true, "artifact 路径")))));

        ToolMetadataResponse response = new UnifiedToolMetadataService(repository, availability, sqlMapper,
                scriptMapper, apiProvider, new ObjectMapper()).find("generate_csv_download_url");

        assertEquals(ToolRoutingToolType.API, response.toolType());
        assertEquals("generate_csv_download_url", response.toolId());
        assertEquals("生成 CSV 下载短链", response.description());
        assertEquals(0, response.priority());
        assertTrue(response.topicTags().isEmpty());
        assertEquals("router_tool", response.executeWith());
        assertEquals("toolId", response.invocation().idField());
        assertEquals("paramsJson", response.invocation().paramsField());
        assertEquals("agentPath", response.parameters().get(0).name());
    }

    @Test
    void fallsBackToSqlRegistryWhenNoMetadataRow() {
        ToolRoutingMetadataRepository repository = mock(ToolRoutingMetadataRepository.class);
        ToolRoutingAvailabilityResolver availability = mock(ToolRoutingAvailabilityResolver.class);
        SqlRegistryMapper sqlMapper = mock(SqlRegistryMapper.class);
        ScriptRegistryMapper scriptMapper = mock(ScriptRegistryMapper.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ApiToolMetadataProvider> apiProvider = mock(ObjectProvider.class);
        when(apiProvider.getIfAvailable()).thenReturn(null);
        when(repository.findByToolId("quality_sql")).thenReturn(Optional.empty());
        when(sqlMapper.selectBySqlId("quality_sql")).thenReturn(SqlRegistryEntry.builder()
                .name("质量明细").description("按部门查质量明细")
                .paramsSchema("[{\"name\":\"department\",\"type\":\"string\",\"required\":true,\"description\":\"部门\"}]")
                .sqlTemplate("select secret from t").build());

        ToolMetadataResponse response = new UnifiedToolMetadataService(repository, availability, sqlMapper,
                scriptMapper, apiProvider, new ObjectMapper()).find("quality_sql");

        assertEquals(ToolRoutingToolType.SQL, response.toolType());
        assertEquals("按部门查质量明细", response.description());
        assertTrue(response.topicTags().isEmpty());
        assertEquals("sql_registry_exec", response.executeWith());
        assertEquals("department", response.parameters().get(0).name());
        assertEquals("downloadFilename", response.parameters().get(1).name());
    }

    @Test
    void fallsBackToScriptRegistryWhenSourceAvailable() {
        ToolRoutingMetadataRepository repository = mock(ToolRoutingMetadataRepository.class);
        ToolRoutingAvailabilityResolver availability = mock(ToolRoutingAvailabilityResolver.class);
        SqlRegistryMapper sqlMapper = mock(SqlRegistryMapper.class);
        ScriptRegistryMapper scriptMapper = mock(ScriptRegistryMapper.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ApiToolMetadataProvider> apiProvider = mock(ObjectProvider.class);
        when(apiProvider.getIfAvailable()).thenReturn(null);
        when(repository.findByToolId("batch_job")).thenReturn(Optional.empty());
        when(sqlMapper.selectBySqlId("batch_job")).thenReturn(null);
        when(scriptMapper.selectByScriptId("batch_job")).thenReturn(ScriptRegistryEntry.builder()
                .name("批量任务").description("批量处理脚本")
                .paramsSchema("[{\"name\":\"date\",\"type\":\"string\",\"required\":true,\"description\":\"日期\"}]")
                .build());
        when(availability.isAvailable(any())).thenReturn(true);

        ToolMetadataResponse response = new UnifiedToolMetadataService(repository, availability, sqlMapper,
                scriptMapper, apiProvider, new ObjectMapper()).find("batch_job");

        assertEquals(ToolRoutingToolType.SCRIPT, response.toolType());
        assertEquals("script_exec", response.executeWith());
        assertEquals("scriptId", response.invocation().idField());
        assertEquals("date", response.parameters().get(0).name());
    }

    @Test
    void fallsBackToScriptRegistryRejectsWhenSourceUnavailable() {
        ToolRoutingMetadataRepository repository = mock(ToolRoutingMetadataRepository.class);
        ToolRoutingAvailabilityResolver availability = mock(ToolRoutingAvailabilityResolver.class);
        SqlRegistryMapper sqlMapper = mock(SqlRegistryMapper.class);
        ScriptRegistryMapper scriptMapper = mock(ScriptRegistryMapper.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ApiToolMetadataProvider> apiProvider = mock(ObjectProvider.class);
        when(apiProvider.getIfAvailable()).thenReturn(null);
        when(repository.findByToolId("batch_job")).thenReturn(Optional.empty());
        when(scriptMapper.selectByScriptId("batch_job")).thenReturn(ScriptRegistryEntry.builder()
                .name("批量任务").description("批量处理脚本").build());
        when(availability.isAvailable(any())).thenReturn(false);

        UnifiedToolMetadataService unified = new UnifiedToolMetadataService(repository, availability, sqlMapper,
                scriptMapper, apiProvider, new ObjectMapper());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> unified.find("batch_job"));
        assertEquals("工具不存在或不可用", e.getMessage());
    }

    @Test
    void rejectsUnknownToolWhenNoSourceAtAll() {
        ToolRoutingMetadataRepository repository = mock(ToolRoutingMetadataRepository.class);
        ToolRoutingAvailabilityResolver availability = mock(ToolRoutingAvailabilityResolver.class);
        SqlRegistryMapper sqlMapper = mock(SqlRegistryMapper.class);
        ScriptRegistryMapper scriptMapper = mock(ScriptRegistryMapper.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ApiToolMetadataProvider> apiProvider = mock(ObjectProvider.class);
        when(apiProvider.getIfAvailable()).thenReturn(null);
        when(repository.findByToolId("nope")).thenReturn(Optional.empty());

        UnifiedToolMetadataService unified = new UnifiedToolMetadataService(repository, availability, sqlMapper,
                scriptMapper, apiProvider, new ObjectMapper());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> unified.find("nope"));
        assertEquals("工具不存在或不可用", e.getMessage());
    }
}
