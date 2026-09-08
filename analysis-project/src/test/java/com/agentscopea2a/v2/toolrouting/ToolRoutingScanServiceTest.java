package com.agentscopea2a.v2.toolrouting;

import com.agentscopea2a.entity.ScriptRegistryEntry;
import com.agentscopea2a.entity.SqlRegistryEntry;
import com.agentscopea2a.mapper.gauss.ScriptRegistryMapper;
import com.agentscopea2a.mapper.gauss.SqlRegistryMapper;
import com.agentscopea2a.v2.registry.service.ScriptSourceService;
import com.agentscopea2a.v2.tools.ToolRoutersIndex;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolRoutingScanServiceTest {

    @Test
    void scansAllSourcesAndFlagsDuplicateIdsAndMissingScriptFiles() {
        SqlRegistryMapper sqlMapper = mock(SqlRegistryMapper.class);
        ScriptRegistryMapper scriptMapper = mock(ScriptRegistryMapper.class);
        ToolRoutersIndex apiIndex = mock(ToolRoutersIndex.class);
        ScriptSourceService sourceService = mock(ScriptSourceService.class);
        ToolRoutingMetadataRepository metadataRepository = mock(ToolRoutingMetadataRepository.class);
        SqlRegistryEntry sql = SqlRegistryEntry.builder().sqlId("quality_metrics").name("质量指标")
                .description("查询质量分").enabled(1).build();
        ScriptRegistryEntry script = ScriptRegistryEntry.builder().scriptId("quality_metrics")
                .name("质量脚本").description("计算质量分").scriptPath("quality.py").enabled(1).build();
        when(sqlMapper.listAllEnabled()).thenReturn(List.of(sql));
        when(scriptMapper.listAllEnabled()).thenReturn(List.of(script));
        when(sourceService.isAvailable(script)).thenReturn(false);
        when(apiIndex.getToolMethodMap()).thenReturn(Map.of());
        when(metadataRepository.findAll()).thenReturn(List.of());

        List<ToolRoutingScanCandidate> candidates = new ToolRoutingScanService(
                sqlMapper, scriptMapper, apiIndex, sourceService, metadataRepository, false).scan();

        assertEquals(2, candidates.size());
        assertTrue(candidates.stream().allMatch(candidate -> candidate.issueCodes().contains("DUPLICATE_TOOL_ID")));
        assertTrue(candidates.stream().anyMatch(candidate -> candidate.toolType() == ToolRoutingToolType.SCRIPT
                && candidate.issueCodes().contains("SCRIPT_SOURCE_UNAVAILABLE")));
    }

    @Test
    void excludesInfrastructureApiToolsButKeepsBusinessApiTools() {
        SqlRegistryMapper sqlMapper = mock(SqlRegistryMapper.class);
        ScriptRegistryMapper scriptMapper = mock(ScriptRegistryMapper.class);
        ToolRoutersIndex apiIndex = mock(ToolRoutersIndex.class);
        ScriptSourceService sourceService = mock(ScriptSourceService.class);
        ToolRoutingMetadataRepository metadataRepository = mock(ToolRoutingMetadataRepository.class);
        when(sqlMapper.listAllEnabled()).thenReturn(List.of());
        when(scriptMapper.listAllEnabled()).thenReturn(List.of());
        when(apiIndex.getToolMethodMap()).thenReturn(Map.of(
                "sql_list", mock(ToolRoutersIndex.MethodInfo.class),
                "data_aggregate", mock(ToolRoutersIndex.MethodInfo.class),
                "quality_query_by_version_department", mock(ToolRoutersIndex.MethodInfo.class)));
        when(apiIndex.findApiTool("quality_query_by_version_department")).thenReturn(
                java.util.Optional.of(new ApiToolMetadata("quality_query_by_version_department", "查询质量分", List.of())));
        when(metadataRepository.findAll()).thenReturn(List.of());

        List<ToolRoutingScanCandidate> candidates = new ToolRoutingScanService(
                sqlMapper, scriptMapper, apiIndex, sourceService, metadataRepository, false).scan();

        assertEquals(List.of("quality_query_by_version_department"),
                candidates.stream().map(ToolRoutingScanCandidate::toolId).toList());
    }
}
