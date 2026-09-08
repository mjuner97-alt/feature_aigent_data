package com.agentscopea2a.v2.toolrouting;

import com.agentscopea2a.entity.ScriptRegistryEntry;
import com.agentscopea2a.entity.SqlRegistryEntry;
import com.agentscopea2a.mapper.gauss.ScriptRegistryMapper;
import com.agentscopea2a.mapper.gauss.SqlRegistryMapper;
import com.agentscopea2a.v2.registry.service.ScriptSourceService;
import com.agentscopea2a.v2.tools.ToolRoutersIndex;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Set;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Reads the three authoritative registrations without invoking any registered business tool. */
@Service
public class ToolRoutingScanService {

    private static final Set<String> INFRASTRUCTURE_API_TOOL_IDS = Set.of(
            "sql_list", "sql_registry_exec", "generate_csv_download_url");

    private final SqlRegistryMapper sqlRegistryMapper;
    private final ScriptRegistryMapper scriptRegistryMapper;
    private final ToolRoutersIndex apiIndex;
    private final ScriptSourceService scriptSourceService;
    private final ToolRoutingMetadataRepository metadataRepository;
    private final boolean globallyEnabled;

    public ToolRoutingScanService(SqlRegistryMapper sqlRegistryMapper,
                                  ScriptRegistryMapper scriptRegistryMapper,
                                  ToolRoutersIndex apiIndex,
                                  ScriptSourceService scriptSourceService,
                                  ToolRoutingMetadataRepository metadataRepository,
                                  @Value("${harness.a2a.tool-routing.enabled:false}") boolean globallyEnabled) {
        this.sqlRegistryMapper = sqlRegistryMapper;
        this.scriptRegistryMapper = scriptRegistryMapper;
        this.apiIndex = apiIndex;
        this.scriptSourceService = scriptSourceService;
        this.metadataRepository = metadataRepository;
        this.globallyEnabled = globallyEnabled;
    }

    public List<ToolRoutingScanCandidate> scan() {
        Map<String, ToolRoutingMetadata> configured = new HashMap<>();
        for (ToolRoutingMetadata metadata : metadataRepository.findAll()) {
            configured.put(metadata.toolId(), metadata);
        }
        List<RawCandidate> raw = new ArrayList<>();
        for (SqlRegistryEntry entry : sqlRegistryMapper.listAllEnabled()) {
            raw.add(new RawCandidate(entry.getSqlId(), ToolRoutingToolType.SQL, entry.getName(), entry.getDescription(), creator(entry.getCreatedByName(), entry.getCreatedBy()), true));
        }
        for (ScriptRegistryEntry entry : scriptRegistryMapper.listAllEnabled()) {
            raw.add(new RawCandidate(entry.getScriptId(), ToolRoutingToolType.SCRIPT, entry.getName(), entry.getDescription(),
                    creator(entry.getCreatedByName(), entry.getCreatedBy()),
                    scriptSourceService.isAvailable(entry)));
        }
        for (String toolId : apiIndex.getToolMethodMap().keySet()) {
            if (!isBusinessApiTool(toolId)) {
                continue;
            }
            ApiToolMetadata api = apiIndex.findApiTool(toolId).orElse(null);
            raw.add(new RawCandidate(toolId, ToolRoutingToolType.API, toolId,
                    api == null ? "" : api.description(), "通用", api != null));
        }
        Map<String, Long> idCounts = raw.stream().collect(java.util.stream.Collectors.groupingBy(
                RawCandidate::toolId, java.util.stream.Collectors.counting()));
        return raw.stream().map(candidate -> toResult(candidate, configured.get(candidate.toolId()), idCounts.get(candidate.toolId())))
                .sorted(Comparator.comparing(ToolRoutingScanCandidate::toolId)
                        .thenComparing(candidate -> candidate.toolType().name()))
                .toList();
    }

    public ToolRoutingStatusResponse status() {
        List<ToolRoutingMetadata> all = metadataRepository.findAll();
        return new ToolRoutingStatusResponse(globallyEnabled, all.size(),
                (int) all.stream().filter(ToolRoutingMetadata::enabled).count());
    }

    private static ToolRoutingScanCandidate toResult(RawCandidate candidate, ToolRoutingMetadata metadata, long idCount) {
        List<String> issues = new ArrayList<>();
        if (idCount > 1) {
            issues.add("DUPLICATE_TOOL_ID");
        }
        if (candidate.toolType() == ToolRoutingToolType.SCRIPT && !candidate.sourceAvailable()) {
            issues.add("SCRIPT_SOURCE_UNAVAILABLE");
        }
        if (candidate.description() == null || candidate.description().isBlank()) {
            issues.add("MISSING_DESCRIPTION");
        }
        return new ToolRoutingScanCandidate(candidate.toolId(), candidate.toolType(), candidate.name(),
                candidate.description() == null ? "" : candidate.description(), candidate.creator(), candidate.sourceAvailable(),
                metadata != null, metadata != null && metadata.enabled(), List.copyOf(issues));
    }

    private static String creator(String displayName, String userId) {
        return displayName != null && !displayName.isBlank() ? displayName
                : (userId == null ? "" : userId);
    }

    private static boolean isBusinessApiTool(String toolId) {
        return !INFRASTRUCTURE_API_TOOL_IDS.contains(toolId) && !toolId.startsWith("data_");
    }

    private record RawCandidate(String toolId, ToolRoutingToolType toolType, String name, String description,
                                String creator,
                                boolean sourceAvailable) {
    }
}
