package com.agentscopea2a.v2.toolrouting;

import com.agentscopea2a.entity.ScriptRegistryEntry;
import com.agentscopea2a.mapper.gauss.ScriptRegistryMapper;
import com.agentscopea2a.mapper.gauss.SqlRegistryMapper;
import com.agentscopea2a.v2.registry.service.ScriptSourceService;

/** Checks that route metadata still points to an enabled, executable real object. */
public class ToolRoutingAvailabilityResolver {

    private final SqlRegistryMapper sqlRegistryMapper;
    private final ScriptRegistryMapper scriptRegistryMapper;
    private final ApiToolMetadataProvider apiToolMetadataProvider;
    private final ScriptSourceService scriptSourceService;

    public ToolRoutingAvailabilityResolver(SqlRegistryMapper sqlRegistryMapper,
                                           ScriptRegistryMapper scriptRegistryMapper,
                                           ApiToolMetadataProvider apiToolMetadataProvider,
                                           ScriptSourceService scriptSourceService) {
        this.sqlRegistryMapper = sqlRegistryMapper;
        this.scriptRegistryMapper = scriptRegistryMapper;
        this.apiToolMetadataProvider = apiToolMetadataProvider;
        this.scriptSourceService = scriptSourceService;
    }

    public boolean isAvailable(ToolRoutingMetadata metadata) {
        if (metadata == null) {
            return false;
        }
        return switch (metadata.toolType()) {
            case SQL -> sqlRegistryMapper.selectBySqlId(metadata.toolId()) != null;
            case API -> apiToolMetadataProvider.isActiveApiTool(metadata.toolId());
            case SCRIPT -> scriptAvailable(metadata.toolId());
        };
    }

    private boolean scriptAvailable(String scriptId) {
        ScriptRegistryEntry entry = scriptRegistryMapper.selectByScriptId(scriptId);
        return entry != null && scriptSourceService.isAvailable(entry);
    }
}
