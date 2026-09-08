package com.agentscopea2a.v2.toolrouting;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Builds the shared executable snapshot used by prompt rendering and tool discovery. */
public class ToolRoutingCatalogService {

    private final ToolRoutingMetadataRepository metadataRepository;
    private final ToolRoutingTagDictionary tagDictionary;
    private final ToolRoutingAvailabilityResolver availabilityResolver;
    private final ToolRoutingMetrics metrics;

    public ToolRoutingCatalogService(ToolRoutingMetadataRepository metadataRepository,
                                     ToolRoutingTagDictionary tagDictionary,
                                     ToolRoutingAvailabilityResolver availabilityResolver,
                                     ToolRoutingMetrics metrics) {
        this.metadataRepository = metadataRepository;
        this.tagDictionary = tagDictionary;
        this.availabilityResolver = availabilityResolver;
        this.metrics = metrics == null ? ToolRoutingMetrics.noop() : metrics;
    }

    public ToolRoutingCatalogService(ToolRoutingMetadataRepository metadataRepository,
                                     ToolRoutingTagDictionary tagDictionary,
                                     ToolRoutingAvailabilityResolver availabilityResolver) {
        this(metadataRepository, tagDictionary, availabilityResolver, ToolRoutingMetrics.noop());
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
        Map<String, String> topics = enabledTagLookup(ToolRoutingTagType.TOPIC);
        Map<String, String> metrics = enabledTagLookup(ToolRoutingTagType.METRIC);
        Map<String, String> dimensions = enabledTagLookup(ToolRoutingTagType.DIMENSION);
        List<ToolRoutingMetadata> tools = metadataRepository.findEnabled().stream()
                .filter(availabilityResolver::isAvailable)
                .map(metadata -> canonicalize(metadata, topics, metrics, dimensions))
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

    private Map<String, String> enabledTagLookup(ToolRoutingTagType type) {
        Map<String, String> values = new LinkedHashMap<>();
        for (ToolRoutingTag tag : tagDictionary.findEnabled(type)) {
            values.putIfAbsent(normalize(tag.tagName()), tag.tagName());
        }
        return values;
    }

    private static ToolRoutingMetadata canonicalize(ToolRoutingMetadata metadata,
                                                    Map<String, String> topics,
                                                    Map<String, String> metrics,
                                                    Map<String, String> dimensions) {
        return new ToolRoutingMetadata(metadata.toolId(), metadata.toolType(), metadata.description(),
                canonicalTags(metadata.topicTags(), topics), canonicalTags(metadata.metricTags(), metrics),
                canonicalTags(metadata.dimensionTags(), dimensions),
                metadata.priority(), metadata.enabled(), metadata.updatedAt());
    }

    private static List<String> canonicalTags(List<String> tags, Map<String, String> dictionary) {
        return tags.stream().map(ToolRoutingCatalogService::normalize).distinct()
                .filter(dictionary::containsKey).map(dictionary::get).toList();
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
