package com.agentscopea2a.v2.skillManager.service;

import java.util.List;

/** Single semantic resolution rule for dependency overrides and defaults. */
public final class MetricDependencyResolver {
    private MetricDependencyResolver() {}

    public enum Source { DEFAULT, OVERRIDE, NONE }
    public record Resolution(List<Long> metricIds, Source source) {}

    public static Resolution resolve(boolean overrideConfigured, List<Long> overrideIds, List<Long> defaultIds) {
        List<Long> override = overrideIds == null ? List.of() : MetricDependencyIds.parse(MetricDependencyIds.encode(overrideIds));
        if (overrideConfigured) return override.isEmpty() ? new Resolution(List.of(), Source.OVERRIDE) : new Resolution(override, Source.OVERRIDE);
        List<Long> defaults = defaultIds == null ? List.of() : MetricDependencyIds.parse(MetricDependencyIds.encode(defaultIds));
        return defaults.isEmpty() ? new Resolution(List.of(), Source.NONE) : new Resolution(defaults, Source.DEFAULT);
    }
}
