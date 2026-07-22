/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.agentscopea2a.v2.verify;

import com.agentscopea2a.v2.config.HarnessRunnerProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Semantic Contract Registry (V3.0 design §11, P0). Loads active metric / dimension / business-rule
 * contracts from the {@code semantic_*} tables into an in-memory cache with a TTL, and serves
 * snapshots to the Rule Engine (B4 deterministic checks) and the verify.md prompt.
 *
 * <p>Business semantics live here - in a queryable registry - instead of being baked into prompts,
 * so the verifier "understands the business" rather than letting the LLM guess metric direction or
 * aggregation rules.
 */
@Component
public class SemanticContractRegistry {

    private static final Logger log = LoggerFactory.getLogger(SemanticContractRegistry.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DataSource dataSource;
    private final HarnessRunnerProperties.Verification config;

    private volatile List<SemanticContracts.MetricContract> metricsCache = new CopyOnWriteArrayList<>();
    private volatile List<SemanticContracts.DimensionContract> dimensionsCache = new CopyOnWriteArrayList<>();
    private volatile List<SemanticContracts.BusinessRuleContract> rulesCache = new CopyOnWriteArrayList<>();
    private volatile long loadedAt = 0L;

    public SemanticContractRegistry(DataSource dataSource, HarnessRunnerProperties properties) {
        this.dataSource = dataSource;
        this.config = properties.getVerification();
    }

    public boolean isEnabled() {
        return config.getContract().isEnabled();
    }

    /** Ensure the cache is fresh; reloads when TTL expires. */
    private void ensureLoaded() {
        if (!isEnabled()) {
            return;
        }
        long ttlMs = config.getContract().getCacheTtlSeconds() * 1000L;
        long now = System.currentTimeMillis();
        if (loadedAt != 0L && (now - loadedAt) < ttlMs) {
            return;
        }
        synchronized (this) {
            if (loadedAt != 0L && (System.currentTimeMillis() - loadedAt) < ttlMs) {
                return;
            }
            try {
                metricsCache = new CopyOnWriteArrayList<>(loadMetrics());
                dimensionsCache = new CopyOnWriteArrayList<>(loadDimensions());
                rulesCache = new CopyOnWriteArrayList<>(loadRules());
                loadedAt = System.currentTimeMillis();
                log.info("SemanticContractRegistry: loaded {} metrics, {} dimensions, {} rules",
                        metricsCache.size(), dimensionsCache.size(), rulesCache.size());
            } catch (Exception e) {
                log.warn("SemanticContractRegistry: load failed (using {} cached): {}",
                        metricsCache.size(), e.getMessage());
                loadedAt = System.currentTimeMillis(); // avoid retry storm
            }
        }
    }

    public SemanticContracts.MetricContract lookupMetric(String metricId) {
        if (metricId == null) return null;
        ensureLoaded();
        for (SemanticContracts.MetricContract m : metricsCache) {
            if (metricId.equalsIgnoreCase(m.metricId()) || metricId.equalsIgnoreCase(m.metricName())) {
                return m;
            }
        }
        return null;
    }

    public SemanticContracts.DimensionContract lookupDimension(String dimension) {
        if (dimension == null) return null;
        ensureLoaded();
        for (SemanticContracts.DimensionContract d : dimensionsCache) {
            if (dimension.equalsIgnoreCase(d.dimension())) {
                return d;
            }
        }
        return null;
    }

    public List<SemanticContracts.BusinessRuleContract> businessRules() {
        ensureLoaded();
        return rulesCache;
    }

    /** Full snapshot - fed into the verify.md prompt so the LLM judges semantics against contracts. */
    public SemanticContracts.Snapshot snapshotAll() {
        ensureLoaded();
        return new SemanticContracts.Snapshot(
                List.copyOf(metricsCache), List.copyOf(dimensionsCache), List.copyOf(rulesCache));
    }

    // -------- loading --------

    private List<SemanticContracts.MetricContract> loadMetrics() {
        String sql = "SELECT metric_id, metric_name, business_definition, formula, unit, "
                + "direction_higher, aggregation_rule_json, owner, version "
                + "FROM semantic_metric_contract WHERE status='active'";
        List<SemanticContracts.MetricContract> out = new ArrayList<>();
        Connection conn = DataSourceUtils.getConnection(dataSource);
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                List<String> allow = List.of();
                List<String> deny = List.of();
                String json = rs.getString("aggregation_rule_json");
                if (json != null && !json.isBlank()) {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, List<String>> rule = MAPPER.readValue(json, Map.class);
                        allow = rule.getOrDefault("allow", List.of());
                        deny = rule.getOrDefault("deny", List.of());
                    } catch (Exception ignored) {
                        log.debug("parse aggregation_rule_json failed for {}: {}", rs.getString("metric_id"), json);
                    }
                }
                out.add(new SemanticContracts.MetricContract(
                        rs.getString("metric_id"),
                        rs.getString("metric_name"),
                        rs.getString("business_definition"),
                        rs.getString("formula"),
                        rs.getString("unit"),
                        rs.getString("direction_higher"),
                        allow, deny,
                        rs.getString("owner"),
                        rs.getString("version")));
            }
        } catch (Exception e) {
            log.warn("loadMetrics failed: {}", e.getMessage());
        } finally {
            DataSourceUtils.releaseConnection(conn, dataSource);
        }
        return out;
    }

    private List<SemanticContracts.DimensionContract> loadDimensions() {
        String sql = "SELECT dimension, allowed_values_json, version FROM semantic_dimension_contract WHERE status='active'";
        List<SemanticContracts.DimensionContract> out = new ArrayList<>();
        Connection conn = DataSourceUtils.getConnection(dataSource);
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                List<String> values = List.of();
                String json = rs.getString("allowed_values_json");
                if (json != null && !json.isBlank()) {
                    try {
                        @SuppressWarnings("unchecked")
                        List<String> v = MAPPER.readValue(json, List.class);
                        values = v;
                    } catch (Exception ignored) {
                        log.debug("parse allowed_values_json failed for {}", rs.getString("dimension"));
                    }
                }
                out.add(new SemanticContracts.DimensionContract(
                        rs.getString("dimension"), values, rs.getString("version")));
            }
        } catch (Exception e) {
            log.warn("loadDimensions failed: {}", e.getMessage());
        } finally {
            DataSourceUtils.releaseConnection(conn, dataSource);
        }
        return out;
    }

    private List<SemanticContracts.BusinessRuleContract> loadRules() {
        String sql = "SELECT rule_id, `condition`, description, version FROM semantic_business_rule WHERE status='active'";
        List<SemanticContracts.BusinessRuleContract> out = new ArrayList<>();
        Connection conn = DataSourceUtils.getConnection(dataSource);
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new SemanticContracts.BusinessRuleContract(
                        rs.getString("rule_id"),
                        rs.getString("condition"),
                        rs.getString("description"),
                        rs.getString("version")));
            }
        } catch (Exception e) {
            log.warn("loadRules failed: {}", e.getMessage());
        } finally {
            DataSourceUtils.releaseConnection(conn, dataSource);
        }
        return out;
    }
}
