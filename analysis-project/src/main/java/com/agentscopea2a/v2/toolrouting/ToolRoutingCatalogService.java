package com.agentscopea2a.v2.toolrouting;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Builds the shared executable catalog used by prompt rendering and tool discovery. */
public class ToolRoutingCatalogService {

    private final ToolRoutingMetadataRepository metadataRepository;
    private final ToolRoutingAvailabilityResolver availabilityResolver;
    private final ToolRoutingMetrics metrics;

    public ToolRoutingCatalogService(ToolRoutingMetadataRepository metadataRepository,
                                     ToolRoutingAvailabilityResolver availabilityResolver) {
        this(metadataRepository, availabilityResolver, ToolRoutingMetrics.noop());
    }

    public ToolRoutingCatalogService(ToolRoutingMetadataRepository metadataRepository,
                                     ToolRoutingAvailabilityResolver availabilityResolver,
                                     ToolRoutingMetrics metrics) {
        this.metadataRepository = metadataRepository;
        this.availabilityResolver = availabilityResolver;
        this.metrics = metrics == null ? ToolRoutingMetrics.noop() : metrics;
    }

    public ToolRoutingCatalog snapshot() {
        long startedAt = System.nanoTime();
        try {
        ToolRoutingCatalog catalog = buildSnapshot();
            metrics.catalogRefresh(ToolRoutingMetrics.RESULT_SUCCESS, System.nanoTime() - startedAt);
            metrics.catalogSize(catalog.tools().size(), catalog.metricTags().size());
            return catalog;
        } catch (RuntimeException e) {
            metrics.catalogRefresh(ToolRoutingMetrics.RESULT_FAILURE, System.nanoTime() - startedAt);
            throw e;
        }
    }

    private ToolRoutingCatalog buildSnapshot() {
        List<ToolRoutingMetadata> tools = metadataRepository.findEnabled().stream()
                .filter(availabilityResolver::isAvailable)
                // 防御: 同 toolId 只应有一个启用类型 (启用互斥由保存路径保证), 直改 DB 双启用时
                // 目录按 toolId 去重取优先级高者, 避免 Agent 寻址歧义
                .collect(java.util.stream.Collectors.toMap(ToolRoutingMetadata::toolId,
                        metadata -> metadata, (a, b) -> a.priority() >= b.priority() ? a : b,
                        java.util.LinkedHashMap::new))
                .values().stream()
                .map(ToolRoutingCatalogService::canonicalize)
                .filter(metadata -> !metadata.topicTags().isEmpty() && !metadata.metricTags().isEmpty())
                .toList();
        Set<String> visibleTopics = tools.stream().flatMap(metadata -> metadata.topicTags().stream())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<String> visibleMetrics = tools.stream().flatMap(metadata -> metadata.metricTags().stream())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<String> visibleDimensions = tools.stream().flatMap(metadata -> metadata.dimensionTags().stream())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new ToolRoutingCatalog(tools, visibleTopics, visibleMetrics, visibleDimensions);
    }

    private static ToolRoutingMetadata canonicalize(ToolRoutingMetadata metadata) {
        return new ToolRoutingMetadata(metadata.toolId(), metadata.toolType(), metadata.description(),
                canonicalTags(metadata.topicTags()), canonicalTags(metadata.metricTags()),
                canonicalTags(metadata.dimensionTags()),
                metadata.priority(), metadata.enabled(), metadata.updatedAt());
    }

    // 标签真源是 tool_route_metadata (后端导入可绕过词袋), 这里只做去重归一, 不做词袋过滤
    private static List<String> canonicalTags(List<String> tags) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String tag : tags) {
            if (tag == null) {
                continue;
            }
            String trimmed = tag.trim();
            if (!trimmed.isEmpty()) {
                values.putIfAbsent(normalize(trimmed), trimmed);
            }
        }
        return List.copyOf(values.values());
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
