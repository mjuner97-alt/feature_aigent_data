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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * SLO monitor (V4.0 §31.2 跨请求质量回归与 SLO). Aggregates {@code verification_record} over a time
 * window and compares against configured SLO targets (pass-rate, fabrication-rate, p95 verify
 * latency). Surfaces breaches for ops alerting; {@code healthy} is true iff no target is violated.
 */
@Component
public class SloMonitor {

    private static final Logger log = LoggerFactory.getLogger(SloMonitor.class);

    private final DataSource dataSource;
    private final HarnessRunnerProperties.Verification.Slo slo;

    public SloMonitor(DataSource dataSource, HarnessRunnerProperties properties) {
        this.dataSource = dataSource;
        this.slo = properties.getVerification().getSlo();
    }

    public SloReport report(int windowHours) {
        Timestamp cutoff = Timestamp.from(Instant.now().minus(Duration.ofHours(Math.max(1, windowHours))));

        int pass = 0, warn = 0, fail = 0;
        int fabrication = 0;
        double avgTrust = 0.0;
        List<Long> latencies = new ArrayList<>();

        Connection conn = DataSourceUtils.getConnection(dataSource);
        try {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT verdict, COUNT(*) AS c FROM verification_record WHERE created_at > ? GROUP BY verdict")) {
                ps.setTimestamp(1, cutoff);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String v = rs.getString("verdict");
                        int c = rs.getInt("c");
                        if ("pass".equalsIgnoreCase(v)) pass = c;
                        else if ("warn".equalsIgnoreCase(v)) warn = c;
                        else if ("fail".equalsIgnoreCase(v)) fail = c;
                    }
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM verification_record WHERE created_at > ? AND verdict='fail' AND repair_type='REFUSE'")) {
                ps.setTimestamp(1, cutoff);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) fabrication = rs.getInt(1);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT AVG(trust_score) FROM verification_record WHERE created_at > ?")) {
                ps.setTimestamp(1, cutoff);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) avgTrust = rs.getDouble(1);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT latency_ms FROM verification_record WHERE created_at > ? AND latency_ms IS NOT NULL")) {
                ps.setTimestamp(1, cutoff);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) latencies.add(rs.getLong(1));
                }
            }
        } catch (Exception e) {
            log.warn("SloMonitor report failed: {}", e.getMessage());
        } finally {
            DataSourceUtils.releaseConnection(conn, dataSource);
        }

        int total = pass + warn + fail;
        double passRate = total == 0 ? 0 : (double) pass / total;
        double warnRate = total == 0 ? 0 : (double) warn / total;
        double failRate = total == 0 ? 0 : (double) fail / total;
        double fabricationRate = total == 0 ? 0 : (double) fabrication / total;
        long p95 = percentile(latencies, 0.95);

        List<String> breaches = new ArrayList<>();
        if (total > 0 && passRate < slo.getPassRateTarget()) {
            breaches.add(String.format("pass_rate %.2f < %.2f", passRate, slo.getPassRateTarget()));
        }
        if (total > 0 && fabricationRate > slo.getFabricationRateTarget()) {
            breaches.add(String.format("fabrication_rate %.3f > %.3f", fabricationRate, slo.getFabricationRateTarget()));
        }
        if (!latencies.isEmpty() && p95 > slo.getP95LatencyMsTarget()) {
            breaches.add("p95_latency " + p95 + "ms > " + slo.getP95LatencyMsTarget() + "ms");
        }
        return new SloReport(windowHours, total, passRate, warnRate, failRate, fabricationRate,
                avgTrust, p95, breaches, breaches.isEmpty());
    }

    private static long percentile(List<Long> values, double p) {
        if (values.isEmpty()) return 0L;
        Collections.sort(values);
        int idx = (int) Math.ceil(p * values.size()) - 1;
        if (idx < 0) idx = 0;
        if (idx >= values.size()) idx = values.size() - 1;
        return values.get(idx);
    }

    /**
     * Returns hourly buckets for the quality trends time-series chart, aggregating
     * {@code verification_record} over a sliding window. Each bucket contains
     * total / pass / warn / fail counts for one hour.
     */
    public List<HourlyBucket> hourlyBuckets(int windowHours) {
        Timestamp cutoff = Timestamp.from(Instant.now().minus(Duration.ofHours(Math.max(1, windowHours))));
        List<HourlyBucket> buckets = new ArrayList<>();
        Connection conn = DataSourceUtils.getConnection(dataSource);
        try {
            String sql = "SELECT DATE_FORMAT(created_at, '%Y-%m-%d %H:00') AS hour, "
                    + "COUNT(*) AS total, "
                    + "SUM(CASE WHEN verdict='pass' THEN 1 ELSE 0 END) AS pass, "
                    + "SUM(CASE WHEN verdict='warn' THEN 1 ELSE 0 END) AS warn, "
                    + "SUM(CASE WHEN verdict='fail' THEN 1 ELSE 0 END) AS fail "
                    + "FROM verification_record WHERE created_at > ? "
                    + "GROUP BY hour ORDER BY hour";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setTimestamp(1, cutoff);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        buckets.add(new HourlyBucket(
                                rs.getString("hour"),
                                rs.getInt("total"),
                                rs.getInt("pass"),
                                rs.getInt("warn"),
                                rs.getInt("fail")
                        ));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("SloMonitor hourlyBuckets failed: {}", e.getMessage());
        } finally {
            DataSourceUtils.releaseConnection(conn, dataSource);
        }
        return buckets;
    }
}
