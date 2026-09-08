package com.agentscopea2a.v2.toolrouting;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Applies only deterministic, structured filtering to an executable catalog snapshot. */
public class ToolIndexService {

    public static final int DEFAULT_LIMIT = 10;
    public static final int MAX_LIMIT = 20;

    private final int defaultLimit;
    private final int maxLimit;
    private final ToolRoutingMetrics metrics;

    public ToolIndexService() {
        this(DEFAULT_LIMIT, MAX_LIMIT, ToolRoutingMetrics.noop());
    }

    public ToolIndexService(int defaultLimit, int maxLimit, ToolRoutingMetrics metrics) {
        this.defaultLimit = Math.max(1, defaultLimit);
        this.maxLimit = Math.max(this.defaultLimit, maxLimit);
        this.metrics = metrics == null ? ToolRoutingMetrics.noop() : metrics;
    }

    public ToolIndexResponse index(ToolRoutingCatalog catalog, ToolIndexRequest request) {
        ToolRoutingCatalog safeCatalog = catalog == null
                ? new ToolRoutingCatalog(List.of(), Set.of(), Set.of(), Set.of()) : catalog;
        ToolIndexRequest safeRequest = request == null
                ? new ToolIndexRequest(List.of(), List.of(), List.of(), List.of(), null) : request;

        Map<String, String> knownTopics = lookup(safeCatalog.topicTags());
        Map<String, String> knownMetrics = lookup(safeCatalog.metricTags());
        Map<String, String> knownDimensions = lookup(safeCatalog.dimensionTags());
        List<String> requestedTopics = normalizeInput(safeRequest.topicTags());
        List<String> requestedMetrics = normalizeInput(safeRequest.metricTags());
        List<String> requestedDimensions = normalizeInput(safeRequest.dimensionTags());
        List<String> requestedTypes = normalizeInput(safeRequest.toolTypes());
        List<String> unknownTopics = unknown(requestedTopics, knownTopics);
        List<String> unknownMetrics = unknown(requestedMetrics, knownMetrics);
        List<String> unknownDimensions = unknown(requestedDimensions, knownDimensions);
        Set<String> topicFilters = resolveKnown(requestedTopics, knownTopics);
        Set<String> metricFilters = resolveKnown(requestedMetrics, knownMetrics);
        Set<String> dimensionFilters = resolveKnown(requestedDimensions, knownDimensions);
        Set<ToolRoutingToolType> typeFilters = parseTypes(requestedTypes);

        List<ToolRoutingMetadata> topicAndTypeMatches = safeCatalog.tools().stream()
                .filter(metadata -> matchesAnyTopic(metadata, topicFilters))
                .filter(metadata -> typeFilters.isEmpty() || typeFilters.contains(metadata.toolType()))
                .sorted(Comparator.comparingInt(ToolRoutingMetadata::priority).reversed()
                        .thenComparing(ToolRoutingMetadata::toolId))
                .toList();

        List<String> availableMetrics = topicAndTypeMatches.stream()
                .flatMap(metadata -> metadata.metricTags().stream())
                .filter(tag -> knownMetrics.containsKey(normalize(tag)))
                .distinct()
                .sorted()
                .toList();

        List<ToolRoutingMetadata> metricAndTypeMatches = topicAndTypeMatches.stream()
                .filter(metadata -> matchesAnyMetric(metadata, metricFilters))
                .toList();

        List<String> availableDimensions = metricAndTypeMatches.stream()
                .flatMap(metadata -> metadata.dimensionTags().stream())
                .filter(tag -> knownDimensions.containsKey(normalize(tag)))
                .distinct()
                .sorted()
                .toList();

        List<ToolRoutingMetadata> matches = metricAndTypeMatches.stream()
                .filter(metadata -> supportsAllDimensions(metadata, dimensionFilters))
                .toList();
        int limit = limit(safeRequest.limit());
        List<ToolIndexCandidate> candidates = matches.stream()
                .limit(limit)
                .map(metadata -> new ToolIndexCandidate(metadata.toolId(), metadata.toolType(), metadata.description(),
                        metadata.metricTags(), metadata.dimensionTags(), metadata.priority(), metadata.toolType().executeWith()))
                .toList();

        recordMetrics(unknownTopics, unknownMetrics, unknownDimensions, matches, candidates);
        return new ToolIndexResponse(
                new ToolIndexResponse.ToolIndexFilters(requestedTopics, requestedMetrics, requestedDimensions, requestedTypes),
                matches.size(), matches.size() > limit, unknownTopics, unknownMetrics, unknownDimensions,
                availableMetrics, availableDimensions, candidates);
    }

    private void recordMetrics(List<String> unknownTopics, List<String> unknownMetrics, List<String> unknownDimensions,
                               List<ToolRoutingMetadata> matches, List<ToolIndexCandidate> candidates) {
        boolean invalid = !unknownTopics.isEmpty() || !unknownMetrics.isEmpty() || !unknownDimensions.isEmpty();
        metrics.indexRequest(invalid ? ToolRoutingMetrics.RESULT_INVALID
                : candidates.isEmpty() ? ToolRoutingMetrics.RESULT_EMPTY : ToolRoutingMetrics.RESULT_HIT);
        metrics.candidatesReturned(candidates.size());
        if (!unknownTopics.isEmpty()) {
            metrics.emptyResult("unknown_topic");
        }
        if (!unknownMetrics.isEmpty()) {
            metrics.emptyResult("unknown_metric");
        }
        if (!unknownDimensions.isEmpty()) {
            metrics.emptyResult("unknown_dimension");
        }
        if (!invalid && matches.isEmpty()) {
            metrics.emptyResult("known_tags_no_match");
        }
    }

    private static boolean matchesAnyTopic(ToolRoutingMetadata metadata, Set<String> topicFilters) {
        if (topicFilters.isEmpty()) {
            return false;
        }
        return metadata.topicTags().stream().map(ToolIndexService::normalize).anyMatch(topicFilters::contains);
    }

    private static boolean matchesAnyMetric(ToolRoutingMetadata metadata, Set<String> metricFilters) {
        if (metricFilters.isEmpty()) {
            return false;
        }
        return metadata.metricTags().stream().map(ToolIndexService::normalize).anyMatch(metricFilters::contains);
    }

    private static boolean supportsAllDimensions(ToolRoutingMetadata metadata, Set<String> dimensions) {
        if (dimensions.isEmpty()) {
            return true;
        }
        Set<String> supported = metadata.dimensionTags().stream()
                .map(ToolIndexService::normalize).collect(Collectors.toSet());
        return supported.containsAll(dimensions);
    }

    private static Map<String, String> lookup(Set<String> tags) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String tag : tags) {
            values.putIfAbsent(normalize(tag), tag);
        }
        return values;
    }

    private static List<String> normalizeInput(List<String> tags) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        for (String tag : tags) {
            if (tag != null && !tag.trim().isEmpty()) {
                values.putIfAbsent(normalize(tag), tag.trim());
            }
        }
        return List.copyOf(values.values());
    }

    private static List<String> unknown(List<String> requested, Map<String, String> known) {
        return requested.stream().filter(tag -> !known.containsKey(normalize(tag))).toList();
    }

    private static Set<String> resolveKnown(List<String> requested, Map<String, String> known) {
        Set<String> values = new LinkedHashSet<>();
        for (String tag : requested) {
            if (known.containsKey(normalize(tag))) {
                values.add(normalize(tag));
            }
        }
        return values;
    }

    private static Set<ToolRoutingToolType> parseTypes(List<String> values) {
        Set<ToolRoutingToolType> types = new LinkedHashSet<>();
        for (String value : values) {
            try {
                types.add(ToolRoutingToolType.valueOf(value.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // An unknown type cannot match any configured type.
            }
        }
        return types;
    }

    private int limit(Integer requested) {
        if (requested == null) {
            return defaultLimit;
        }
        return Math.max(1, Math.min(requested, maxLimit));
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
