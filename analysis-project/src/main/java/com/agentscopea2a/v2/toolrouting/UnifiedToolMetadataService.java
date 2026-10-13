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
import java.util.Optional;

/** Resolves parameters from the authoritative registry for each routed tool type. */
public class UnifiedToolMetadataService {

    private static final TypeReference<List<Map<String, Object>>> PARAMETER_LIST = new TypeReference<>() { };
    private static final List<ToolParameterMetadata> SQL_EXECUTOR_PARAMETERS = List.of(
            new ToolParameterMetadata("downloadFilename", "string", false, "可选；导出 CSV 时指定文件名"),
            new ToolParameterMetadata("referenceOnly", "boolean", false, "可选；true 时仅返回结果引用"));

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
        Optional<ToolRoutingMetadata> configured = metadataRepository.findByToolId(toolId)
                .filter(availabilityResolver::isAvailable);
        if (configured.isPresent()) {
            try {
                ToolMetadataResponse response = resolveParameters(configured.get());
                metrics.metaInfoRequest(configured.get().toolType().name(), ToolRoutingMetrics.RESULT_HIT);
                return response;
            } catch (IllegalArgumentException e) {
                metrics.metaInfoRequest(configured.get().toolType().name(), ToolRoutingMetrics.RESULT_INVALID);
                throw e;
            }
        }
        // 路由决策 (docs/history/tool-metadata-unified-routing-plan.md 当前实现决策):
        // tool_route_metadata 只控制 tool_index 目录可见性; toolMetaInfo 是按 toolId
        // 寻址的查询通道, 元数据缺行 (如 generate_csv_download_url 被扫描页作为基础
        // 设施工具排除) 不得拒绝调用, 回退到权威注册源按真实对象存在性校验。
        ToolMetadataResponse fallback = findFromAuthoritativeSource(toolId);
        if (fallback != null) {
            metrics.metaInfoRequest(fallback.toolType().name(), ToolRoutingMetrics.RESULT_HIT);
            return fallback;
        }
        metrics.metaInfoRequest("UNKNOWN", ToolRoutingMetrics.RESULT_MISS);
        throw new IllegalArgumentException("工具不存在或不可用");
    }

    /** Resolves parameters from the real registration when no routing metadata row exists. */
    private ToolMetadataResponse findFromAuthoritativeSource(String toolId) {
        ApiToolMetadataProvider provider = apiToolMetadataProvider.getIfAvailable();
        if (provider != null) {
            Optional<ApiToolMetadata> api = provider.findApiTool(toolId);
            if (api.isPresent()) {
                ApiToolMetadata meta = api.get();
                return new ToolMetadataResponse(toolId, ToolRoutingToolType.API, meta.description(),
                        List.of(), List.of(), List.of(), 0, ToolRoutingToolType.API.executeWith(),
                        meta.parameters(), invocation(ToolRoutingToolType.API));
            }
        }
        SqlRegistryEntry sqlEntry = sqlRegistryMapper.selectBySqlId(toolId);
        if (sqlEntry != null) {
            return new ToolMetadataResponse(toolId, ToolRoutingToolType.SQL, sqlEntry.getDescription(),
                    List.of(), List.of(), List.of(), 0, ToolRoutingToolType.SQL.executeWith(),
                    sqlParameters(sqlEntry), invocation(ToolRoutingToolType.SQL));
        }
        ScriptRegistryEntry scriptEntry = scriptRegistryMapper.selectByScriptId(toolId);
        if (scriptEntry != null) {
            // 脚本源可用性 (容器 cat 探测) 封装在 resolver 里, 借合成元数据复用其判定
            ToolRoutingMetadata synthetic = new ToolRoutingMetadata(toolId, ToolRoutingToolType.SCRIPT,
                    "", List.of(), List.of(), List.of(), 0, true, null);
            if (availabilityResolver.isAvailable(synthetic)) {
                return new ToolMetadataResponse(toolId, ToolRoutingToolType.SCRIPT, scriptEntry.getDescription(),
                        List.of(), List.of(), List.of(), 0, ToolRoutingToolType.SCRIPT.executeWith(),
                        parameters(scriptEntry), invocation(ToolRoutingToolType.SCRIPT));
            }
        }
        return null;
    }

    private ToolMetadataResponse resolveParameters(ToolRoutingMetadata metadata) {
        List<ToolParameterMetadata> parameters = switch (metadata.toolType()) {
            case SQL -> sqlParameters(sqlRegistryMapper.selectBySqlId(metadata.toolId()));
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

    /** SQL registry metadata contains business parameters; expose executor options too. */
    private List<ToolParameterMetadata> sqlParameters(SqlRegistryEntry entry) {
        List<ToolParameterMetadata> parameters = new ArrayList<>(parameters(entry));
        for (ToolParameterMetadata executorParameter : SQL_EXECUTOR_PARAMETERS) {
            if (parameters.stream().noneMatch(p -> p.name().equals(executorParameter.name()))) {
                parameters.add(executorParameter);
            }
        }
        return List.copyOf(parameters);
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
