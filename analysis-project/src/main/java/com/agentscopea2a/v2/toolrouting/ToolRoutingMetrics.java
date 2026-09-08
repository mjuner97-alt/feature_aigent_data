package com.agentscopea2a.v2.toolrouting;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Centralized Micrometer instrumentation for unified tool routing (plan §12).
 * All methods are no-ops when constructed without a registry so plain unit tests
 * keep working without a meter backend.
 */
public class ToolRoutingMetrics {

    private static final Logger log = LoggerFactory.getLogger(ToolRoutingMetrics.class);

    public static final String RESULT_HIT = "hit";
    public static final String RESULT_EMPTY = "empty";
    public static final String RESULT_INVALID = "invalid";
    public static final String RESULT_MISS = "miss";
    public static final String RESULT_SUCCESS = "success";
    public static final String RESULT_FAILURE = "failure";

    private final MeterRegistry registry;
    private final AtomicLong catalogEntries = new AtomicLong();
    private final AtomicLong catalogMetricTags = new AtomicLong();

    public ToolRoutingMetrics(MeterRegistry registry) {
        this.registry = registry;
        if (registry != null) {
            registry.gauge("tool_routing_catalog_entries", catalogEntries);
            registry.gauge("tool_routing_catalog_metric_tags", catalogMetricTags);
        }
    }

    public static ToolRoutingMetrics noop() {
        return new ToolRoutingMetrics(null);
    }

    public void indexRequest(String result) {
        increment("tool_routing_index_requests_total", "result", result);
    }

    public void candidatesReturned(int count) {
        if (registry != null && count > 0) {
            registry.counter("tool_routing_index_candidates_returned").increment(count);
        }
    }

    public void emptyResult(String reason) {
        increment("tool_routing_empty_results_total", "reason", reason);
    }

    public void metaInfoRequest(String type, String result) {
        if (registry != null) {
            registry.counter("tool_routing_meta_info_requests_total",
                    "type", type, "result", result).increment();
        }
    }

    public void catalogRefresh(String result, long durationNanos) {
        increment("tool_routing_catalog_refresh_total", "result", result);
        if (registry != null) {
            registry.timer("tool_routing_catalog_refresh_duration").record(durationNanos, TimeUnit.NANOSECONDS);
        }
    }

    public void catalogSize(int entries, int metricTags) {
        catalogEntries.set(entries);
        catalogMetricTags.set(metricTags);
    }

    public void consistencyError(String kind) {
        increment("tool_routing_consistency_errors", "kind", kind);
    }

    public void scriptUnavailable(String reason) {
        increment("tool_routing_script_unavailable_total", "reason", reason);
    }

    private void increment(String name, String key, String value) {
        if (registry != null) {
            try {
                registry.counter(name, key, value).increment();
            } catch (Exception e) {
                log.debug("metric {} failed: {}", name, e.getMessage());
            }
        }
    }
}
