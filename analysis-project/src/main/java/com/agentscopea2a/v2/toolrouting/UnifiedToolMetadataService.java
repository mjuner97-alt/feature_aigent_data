package com.agentscopea2a.v2.toolrouting;

import com.agentscopea2a.entity.ScriptRegistryEntry;
import com.agentscopea2a.entity.SqlRegistryEntry;
import com.agentscopea2a.mapper.gauss.ScriptRegistryMapper;
import com.agentscopea2a.mapper.gauss.SqlRegistryMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Resolves parameters from the authoritative registry for each routed tool type. */
public class UnifiedToolMetadataService {

    private static final TypeReference<List<Map<String, Object>>> PARAMETER_LIST = new TypeReference<>() { };

    private final ToolRoutingMetadataRepository metadataRepository;
    private final ToolRoutingAvailabilityResolver availabilityResolver;
    private final SqlRegistryMapper sqlRegistryMapper;
    private final ScriptRegistryMapper scriptRegistryMapper;
    private final ObjectProvider<ApiToolMetadataProvider> apiToolMetadataProvider;
    private final ObjectMapper objectMapper;
    private final ToolRoutingMetrics metrics;

    public UnifiedToolMetadataService(ToolRoutingMetadataRepository metadataRepository,
                                      ToolRoutingAvailabilityResolver availabilityResolver,
                                      SqlRegistryMapper sqlRegistryMapper,
                                      ScriptRegistryMapper scriptRegistryMapper,
                                      ObjectProvider<ApiToolMetadataProvider> apiToolMetadataProvider,
                                      ObjectMapper objectMapper,
                                      ToolRoutingMetrics metrics) {
        this.metadataRepository = metadataRepository;
        this.availabilityResolver = availabilityResolver;
        this.sqlRegistryMapper = sqlRegistryMapper;
        this.scriptRegistryMapper = scriptRegistryMapper;
        this.apiToolMetadataProvider = apiToolMetadataProvider;
        this.objectMapper = objectMapper;
        this.metrics = metrics == null ? ToolRoutingMetrics.noop() : metrics;
    }

    public UnifiedToolMetadataService(ToolRoutingMetadataRepository metadataRepository,
                                      ToolRoutingAvailabilityResolver availabilityResolver,
                                      SqlRegistryMapper sqlRegistryMapper,
                                      ScriptRegistryMapper scriptRegistryMapper,
                                      ObjectProvider<ApiToolMetadataProvider> apiToolMetadataProvider,
                                      ObjectMapper objectMapper) {
        this(metadataRepository, availabilityResolver, sqlRegistryMapper, scriptRegistryMapper,
                apiToolMetadataProvider, objectMapper, ToolRoutingMetrics.noop());
    }

    public ToolMetadataResponse find(String toolId) {
        ToolRoutingMetadata metadata = metadataRepository.findByToolId(toolId)
                .filter(availabilityResolver::isAvailable)
                .orElseThrow(() -> {
                    metrics.metaInfoRequest("UNKNOWN", ToolRoutingMetrics.RESULT_MISS);
                    return new IllegalArgumentException("工具不存在或不可用");
                });
        try {
            ToolMetadataResponse response = resolveParameters(metadata);
            metrics.metaInfoRequest(metadata.toolType().name(), ToolRoutingMetrics.RESULT_HIT);
            return response;
        } catch (IllegalArgumentException e) {
            metrics.metaInfoRequest(metadata.toolType().name(), ToolRoutingMetrics.RESULT_INVALID);
            throw e;
        }
    }

    private ToolMetadataResponse resolveParameters(ToolRoutingMetadata metadata) {
        List<ToolParameterMetadata> parameters = switch (metadata.toolType()) {
            case SQL -> parameters(sqlRegistryMapper.selectBySqlId(metadata.toolId()));
            case SCRIPT -> parameters(scriptRegistryMapper.selectByScriptId(metadata.toolId()));
            case API -> apiParameters(metadata.toolId());
        };
        return new ToolMetadataResponse(metadata.toolId(), metadata.toolType(), metadata.description(),
                metadata.topicTags(), metadata.metricTags(), metadata.dimensionTags(), metadata.priority(), metadata.toolType().executeWith(),
                parameters, invocation(metadata.toolType()));
    }

    private List<ToolParameterMetadata> parameters(SqlRegistryEntry entry) {
        if (entry == null) {
            throw new IllegalArgumentException("工具不存在或不可用");
        }
        return parseParameters(entry.getParamsSchema());
    }

    private List<ToolParameterMetadata> parameters(ScriptRegistryEntry entry) {
        if (entry == null) {
            throw new IllegalArgumentException("工具不存在或不可用");
        }
        return parseParameters(entry.getParamsSchema());
    }

    private List<ToolParameterMetadata> apiParameters(String toolId) {
        ApiToolMetadataProvider provider = apiToolMetadataProvider.getIfAvailable();
        if (provider == null) {
            throw new IllegalArgumentException("工具不存在或不可用");
        }
        return provider.findApiTool(toolId).map(ApiToolMetadata::parameters)
                .orElseThrow(() -> new IllegalArgumentException("工具不存在或不可用"));
    }

    private List<ToolParameterMetadata> parseParameters(String schema) {
        if (schema == null || schema.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> values = objectMapper.readValue(schema, PARAMETER_LIST);
            List<ToolParameterMetadata> parameters = new ArrayList<>();
            for (Map<String, Object> value : values) {
                Object name = value.get("name");
                if (!(name instanceof String nameText) || nameText.isBlank()) {
                    throw new IllegalArgumentException("工具参数配置无效");
                }
                parameters.add(new ToolParameterMetadata(nameText, asString(value.get("type"), "string"),
                        Boolean.TRUE.equals(value.get("required")), asString(value.get("description"), "")));
            }
            return List.copyOf(parameters);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("工具参数配置无效", e);
        }
    }

    private static String asString(Object value, String defaultValue) {
        return value == null ? defaultValue : String.valueOf(value);
    }

    private static ToolInvocation invocation(ToolRoutingToolType type) {
        return switch (type) {
            case SQL -> new ToolInvocation("sqlId", "params");
            case SCRIPT -> new ToolInvocation("scriptId", "params");
            case API -> new ToolInvocation("toolId", "paramsJson");
        };
    }
}
