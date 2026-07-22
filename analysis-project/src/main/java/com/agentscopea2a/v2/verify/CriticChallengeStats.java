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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Critic self-learning (V4.0 §31.2 Critic 自学习挑战模式). Tracks per-challenge-type how often a hole
 * of that type was found and how often it was "confirmed" (the verdict ended up FAIL). High-
 * effectiveness types are surfaced as a focus hint in the critic prompt, so the Critic shifts effort
 * toward challenge patterns that historically catch real problems.
 *
 * <p>"Confirmed" is a heuristic proxy: if the final verdict was FAIL, all holes the Critic raised in
 * that run count as confirmed. This is noisy but trends correctly over enough samples.
 */
@Component
public class CriticChallengeStats {

    private static final Logger log = LoggerFactory.getLogger(CriticChallengeStats.class);
    private static final int MIN_SAMPLE = 3;
    private static final int TOP_N = 3;

    private final DataSource dataSource;

    public CriticChallengeStats(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Record the holes the Critic found in one run, marking them confirmed iff the verdict was FAIL. */
    public void recordFindings(List<CriticResult.Hole> holes, boolean verdictFail) {
        if (holes == null || holes.isEmpty()) {
            return;
        }
        Map<String, Integer> byType = new LinkedHashMap<>();
        for (CriticResult.Hole h : holes) {
            String t = h.type() == null || h.type().isBlank() ? "未知" : h.type().trim();
            byType.merge(t, 1, Integer::sum);
        }
        String sql = "INSERT INTO critic_challenge_stats (challenge_type, found_count, confirmed_count) "
                + "VALUES (?,?,?) ON DUPLICATE KEY UPDATE "
                + "found_count=found_count+VALUES(found_count), "
                + "confirmed_count=confirmed_count+VALUES(confirmed_count)";
        Connection conn = DataSourceUtils.getConnection(dataSource);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Map.Entry<String, Integer> e : byType.entrySet()) {
                ps.setString(1, e.getKey());
                ps.setInt(2, e.getValue());
                ps.setInt(3, verdictFail ? e.getValue() : 0);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (Exception e) {
            log.warn("CriticChallengeStats: recordFindings failed: {}", e.getMessage());
        } finally {
            DataSourceUtils.releaseConnection(conn, dataSource);
        }
    }

    /** Per-type hit rate (confirmed / found). */
    public Map<String, Double> effectiveness() {
        Map<String, Double> out = new LinkedHashMap<>();
        String sql = "SELECT challenge_type, found_count, confirmed_count FROM critic_challenge_stats";
        Connection conn = DataSourceUtils.getConnection(dataSource);
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                int found = rs.getInt("found_count");
                int confirmed = rs.getInt("confirmed_count");
                out.put(rs.getString("challenge_type"), found == 0 ? 0.0 : (double) confirmed / found);
            }
        } catch (Exception e) {
            log.warn("CriticChallengeStats: effectiveness failed: {}", e.getMessage());
        } finally {
            DataSourceUtils.releaseConnection(conn, dataSource);
        }
        return out;
    }

    /**
     * A focus line appended to the critic prompt: the top {@link #TOP_N} challenge types by hit rate
     * (with at least {@link #MIN_SAMPLE} finds). Empty string when there isn't enough data yet.
     */
    public String focusHint() {
        StringBuilder sb = new StringBuilder();
        String sql = "SELECT challenge_type, found_count, confirmed_count FROM critic_challenge_stats "
                + "WHERE found_count >= ? ORDER BY confirmed_count * 1.0 / found_count DESC LIMIT ?";
        Connection conn = DataSourceUtils.getConnection(dataSource);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, MIN_SAMPLE);
            ps.setInt(2, TOP_N);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int found = rs.getInt("found_count");
                    int confirmed = rs.getInt("confirmed_count");
                    double rate = found == 0 ? 0 : 100.0 * confirmed / found;
                    if (sb.length() == 0) {
                        sb.append("【自学习】近期高效挑战类型(请优先覆盖): ");
                    } else {
                        sb.append(", ");
                    }
                    sb.append(rs.getString("challenge_type"))
                            .append(String.format("(命中率%.0f%%)", rate));
                }
            }
        } catch (Exception e) {
            log.warn("CriticChallengeStats: focusHint failed: {}", e.getMessage());
        } finally {
            DataSourceUtils.releaseConnection(conn, dataSource);
        }
        return sb.toString();
    }
}
