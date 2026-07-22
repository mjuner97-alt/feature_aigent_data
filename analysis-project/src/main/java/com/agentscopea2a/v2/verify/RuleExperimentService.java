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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A/B rule experiment service (V4.0 §31.2 校验规则/契约自进化 - A/B 灰度). A candidate Semantic-Contract
 * rule is applied to a traffic bucket of requests (by session-id hash); the verdict distribution of
 * the experiment bucket is compared against the baseline bucket to decide promote / rollback - so a
 * distilled rule is validated against real traffic before it's promoted into the contract registry.
 */
@Component
public class RuleExperimentService {

    private static final Logger log = LoggerFactory.getLogger(RuleExperimentService.class);
    private static final int MIN_SAMPLE = 20;
    private static final double PROMOTE_DELTA = 0.02;

    private final DataSource dataSource;

    public RuleExperimentService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public String start(String name, String metricId, String direction, String denyAggregation, int trafficPct) {
        String experimentId = "rexp-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String sql = "INSERT INTO rule_experiment (experiment_id, name, candidate_metric_id, candidate_direction, "
                + "candidate_deny_aggregation, traffic_percent, status) VALUES (?,?,?,?,?,?, 'running')";
        Connection conn = DataSourceUtils.getConnection(dataSource);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, experimentId);
            ps.setString(2, name);
            ps.setString(3, metricId);
            ps.setString(4, direction);
            ps.setString(5, denyAggregation);
            ps.setInt(6, Math.max(0, Math.min(100, trafficPct)));
            ps.executeUpdate();
            log.info("RuleExperiment {} started: metric={} direction={} deny={} traffic={}%",
                    experimentId, metricId, direction, denyAggregation, trafficPct);
        } catch (Exception e) {
            log.warn("RuleExperiment start failed: {}", e.getMessage());
        } finally {
            DataSourceUtils.releaseConnection(conn, dataSource);
        }
        return experimentId;
    }

    /** The running experiment whose traffic bucket the session falls into, if any. */
    public Optional<RuleExperiment> activeExperiment(String sessionId) {
        if (sessionId == null) return Optional.empty();
        List<RuleExperiment> running = listByStatus("running");
        if (running.isEmpty()) return Optional.empty();
        int bucket = Math.floorMod(sessionId.hashCode(), 100);
        for (RuleExperiment re : running) {
            if (bucket < re.trafficPercent()) {
                return Optional.of(re);
            }
        }
        return Optional.empty();
    }

    /** Build the candidate MetricContract to inject into a request's contract snapshot. */
    public static SemanticContracts.MetricContract candidateContract(RuleExperiment re) {
        List<String> deny = (re.candidateDenyAggregation() == null || re.candidateDenyAggregation().isBlank())
                ? List.of() : List.of(re.candidateDenyAggregation());
        return new SemanticContracts.MetricContract(
                re.candidateMetricId(), re.candidateMetricId(),
                "A/B experiment candidate", null, null, re.candidateDirection(),
                List.of(), deny, "experiment", "exp");
    }

    public ExperimentMetrics measure(String experimentId) {
        Timestamp startedAt = startedAt(experimentId);
        int[] expCounts = verdictCounts("SELECT verdict, COUNT(*) FROM verification_record WHERE experiment_id=? GROUP BY verdict", experimentId, null);
        int expTotal = expCounts[0] + expCounts[1] + expCounts[2];
        int[] baseCounts = startedAt == null ? new int[]{0, 0, 0}
                : verdictCounts("SELECT verdict, COUNT(*) FROM verification_record WHERE experiment_id IS NULL AND created_at >= ? GROUP BY verdict", null, startedAt);
        int baseTotal = baseCounts[0] + baseCounts[1] + baseCounts[2];
        double failRate = expTotal == 0 ? 0 : (double) expCounts[2] / expTotal;
        double baselineFailRate = baseTotal == 0 ? 0 : (double) baseCounts[2] / baseTotal;
        double avgTrust = avgTrust(experimentId);
        double delta = failRate - baselineFailRate;
        String recommendation;
        if (expTotal < MIN_SAMPLE) {
            recommendation = "inconclusive (sample " + expTotal + " < " + MIN_SAMPLE + ")";
        } else if (delta >= PROMOTE_DELTA) {
            recommendation = "promote";
        } else if (delta <= 0) {
            recommendation = "rollback (no lift)";
        } else {
            recommendation = "inconclusive (marginal lift)";
        }
        return new ExperimentMetrics(experimentId, expTotal, failRate, baselineFailRate, delta, avgTrust, recommendation);
    }

    public void promote(String experimentId) {
        setStatus(experimentId, "promoted");
        log.info("RuleExperiment {} promoted", experimentId);
    }

    public void rollback(String experimentId) {
        setStatus(experimentId, "rolled_back");
        log.info("RuleExperiment {} rolled back", experimentId);
    }

    public List<RuleExperiment> listByStatus(String status) {
        List<RuleExperiment> out = new ArrayList<>();
        String sql = "SELECT experiment_id, name, candidate_metric_id, candidate_direction, "
                + "candidate_deny_aggregation, traffic_percent, status, started_at, ended_at "
                + "FROM rule_experiment WHERE status=? ORDER BY started_at DESC";
        Connection conn = DataSourceUtils.getConnection(dataSource);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(mapRow(rs));
            }
        } catch (Exception e) {
            log.warn("RuleExperiment listByStatus failed: {}", e.getMessage());
        } finally {
            DataSourceUtils.releaseConnection(conn, dataSource);
        }
        return out;
    }

    // ===== helpers =====

    private void setStatus(String experimentId, String status) {
        String sql = "UPDATE rule_experiment SET status=?, ended_at=NOW(3) WHERE experiment_id=?";
        Connection conn = DataSourceUtils.getConnection(dataSource);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setString(2, experimentId);
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("RuleExperiment setStatus failed: {}", e.getMessage());
        } finally {
            DataSourceUtils.releaseConnection(conn, dataSource);
        }
    }

    private Timestamp startedAt(String experimentId) {
        String sql = "SELECT started_at FROM rule_experiment WHERE experiment_id=?";
        Connection conn = DataSourceUtils.getConnection(dataSource);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, experimentId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getTimestamp("started_at");
            }
        } catch (Exception e) {
            log.warn("RuleExperiment startedAt failed: {}", e.getMessage());
        } finally {
            DataSourceUtils.releaseConnection(conn, dataSource);
        }
        return null;
    }

    /** returns [pass, warn, fail] counts. Either experimentId or cutoff is bound (the other null). */
    private int[] verdictCounts(String sql, String experimentId, Timestamp cutoff) {
        int[] counts = new int[]{0, 0, 0};
        Connection conn = DataSourceUtils.getConnection(dataSource);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (experimentId != null) {
                ps.setString(1, experimentId);
            } else {
                ps.setTimestamp(1, cutoff);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String v = rs.getString(verdictColumn());
                    int c = rs.getInt(2);
                    if ("pass".equalsIgnoreCase(v)) counts[0] = c;
                    else if ("warn".equalsIgnoreCase(v)) counts[1] = c;
                    else if ("fail".equalsIgnoreCase(v)) counts[2] = c;
                }
            }
        } catch (Exception e) {
            log.warn("RuleExperiment verdictCounts failed: {}", e.getMessage());
        } finally {
            DataSourceUtils.releaseConnection(conn, dataSource);
        }
        return counts;
    }

    private static String verdictColumn() {
        return "verdict";
    }

    private double avgTrust(String experimentId) {
        String sql = "SELECT AVG(trust_score) FROM verification_record WHERE experiment_id=?";
        Connection conn = DataSourceUtils.getConnection(dataSource);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, experimentId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getDouble(1);
            }
        } catch (Exception e) {
            log.warn("RuleExperiment avgTrust failed: {}", e.getMessage());
        } finally {
            DataSourceUtils.releaseConnection(conn, dataSource);
        }
        return 0.0;
    }

    private static RuleExperiment mapRow(ResultSet rs) throws Exception {
        Timestamp start = rs.getTimestamp("started_at");
        Timestamp end = rs.getTimestamp("ended_at");
        return new RuleExperiment(
                rs.getString("experiment_id"), rs.getString("name"),
                rs.getString("candidate_metric_id"), rs.getString("candidate_direction"),
                rs.getString("candidate_deny_aggregation"), rs.getInt("traffic_percent"),
                rs.getString("status"),
                start == null ? null : start.toInstant(),
                end == null ? null : end.toInstant());
    }
}
