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
    void sameToolIdTypesReflectTheirOwnMetadataState() {
        // (tool_id, tool_type) 复合主键: 同 toolId 两行元数据各自有开关,
        // 扫描行必须按 (toolId, toolType) 取自己的状态, 不能 last-write-wins 覆盖
        SqlRegistryMapper sqlMapper = mock(SqlRegistryMapper.class);
        ScriptRegistryMapper scriptMapper = mock(ScriptRegistryMapper.class);
        ToolRoutersIndex apiIndex = mock(ToolRoutersIndex.class);
        ScriptSourceService sourceService = mock(ScriptSourceService.class);
        ToolRoutingMetadataRepository metadataRepository = mock(ToolRoutingMetadataRepository.class);
        SqlRegistryEntry sql = SqlRegistryEntry.builder().sqlId("q2_1_dup").name("SQL侧")
                .description("SQL 查询").enabled(1).build();
        ScriptRegistryEntry script = ScriptRegistryEntry.builder().scriptId("q2_1_dup").name("脚本侧")
                .description("脚本计算").scriptPath("q2_1.py").enabled(1).build();
        when(sqlMapper.listAllEnabled()).thenReturn(List.of(sql));
        when(scriptMapper.listAllEnabled()).thenReturn(List.of(script));
        when(apiIndex.getToolMethodMap()).thenReturn(Map.of());
        when(sourceService.isAvailable(script)).thenReturn(true);
        when(metadataRepository.findAll()).thenReturn(List.of(
                new ToolRoutingMetadata("q2_1_dup", ToolRoutingToolType.SCRIPT, "脚本", List.of("QI卡口"),
                        List.of("质量分"), List.of(), 0, true, null),
                new ToolRoutingMetadata("q2_1_dup", ToolRoutingToolType.SQL, "SQL", List.of("QI卡口"),
                        List.of("质量分"), List.of(), 0, false, null)));

        List<ToolRoutingScanCandidate> candidates = new ToolRoutingScanService(
                sqlMapper, scriptMapper, apiIndex, sourceService, metadataRepository, false).scan();

        assertEquals(2, candidates.size());
        assertTrue(candidates.stream().anyMatch(c -> c.toolType() == ToolRoutingToolType.SCRIPT && c.routeEnabled()));
        assertTrue(candidates.stream().anyMatch(c -> c.toolType() == ToolRoutingToolType.SQL && !c.routeEnabled()));
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
