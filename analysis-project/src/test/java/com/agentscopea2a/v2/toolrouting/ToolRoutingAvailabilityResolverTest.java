package com.agentscopea2a.v2.toolrouting;

import com.agentscopea2a.entity.ScriptRegistryEntry;
import com.agentscopea2a.entity.SqlRegistryEntry;
import com.agentscopea2a.mapper.gauss.ScriptRegistryMapper;
import com.agentscopea2a.mapper.gauss.SqlRegistryMapper;
import com.agentscopea2a.v2.registry.service.ScriptSourceService;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolRoutingAvailabilityResolverTest {

    @Test
    void excludesRegisteredScriptWhenItsSourceFileIsMissing() throws Exception {
        SqlRegistryMapper sqlMapper = mock(SqlRegistryMapper.class);
        ScriptRegistryMapper scriptMapper = mock(ScriptRegistryMapper.class);
        ApiToolMetadataProvider apiProvider = mock(ApiToolMetadataProvider.class);
        Path workspace = Files.createTempDirectory("tool-routing-workspace");
        ScriptSourceService sourceService = new ScriptSourceService(workspace.toString());
        ScriptRegistryEntry entry = ScriptRegistryEntry.builder()
                .scriptId("quality_script").scriptPath("owner/quality_script.py").enabled(1).build();
        when(scriptMapper.selectByScriptId("quality_script")).thenReturn(entry);

        ToolRoutingAvailabilityResolver resolver = new ToolRoutingAvailabilityResolver(
                sqlMapper, scriptMapper, apiProvider, sourceService);

        assertFalse(resolver.isAvailable(metadata("quality_script", ToolRoutingToolType.SCRIPT)));
        Files.createDirectories(workspace.resolve("scripts/owner"));
        Files.writeString(workspace.resolve("scripts/owner/quality_script.py"), "print('ok')");
        assertTrue(resolver.isAvailable(metadata("quality_script", ToolRoutingToolType.SCRIPT)));
    }

    @Test
    void usesEachTypeSpecificAuthoritativeRegistry() {
        SqlRegistryMapper sqlMapper = mock(SqlRegistryMapper.class);
        ScriptRegistryMapper scriptMapper = mock(ScriptRegistryMapper.class);
        ApiToolMetadataProvider apiProvider = mock(ApiToolMetadataProvider.class);
        ScriptSourceService sourceService = mock(ScriptSourceService.class);
        when(sqlMapper.selectBySqlId("quality_sql")).thenReturn(SqlRegistryEntry.builder().sqlId("quality_sql").enabled(1).build());
        when(apiProvider.isActiveApiTool("quality_api")).thenReturn(true);

        ToolRoutingAvailabilityResolver resolver = new ToolRoutingAvailabilityResolver(
                sqlMapper, scriptMapper, apiProvider, sourceService);

        assertTrue(resolver.isAvailable(metadata("quality_sql", ToolRoutingToolType.SQL)));
        assertTrue(resolver.isAvailable(metadata("quality_api", ToolRoutingToolType.API)));
    }

    private static ToolRoutingMetadata metadata(String id, ToolRoutingToolType type) {
        return new ToolRoutingMetadata(id, type, "description", List.of("QI卡口"), List.of("质量分"), List.of(), 0, true, null);
    }
}
