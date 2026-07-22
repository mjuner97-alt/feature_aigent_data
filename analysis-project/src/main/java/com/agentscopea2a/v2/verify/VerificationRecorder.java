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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;

/**
 * Persists verification results (V3.0 design §21.3 / §23). Writes verdicts to
 * {@code verification_record}, the event stream to {@code verification_event}, and repair dispatches
 * to {@code repair_execution_history} (anti-gaming audit). Records Micrometer counters/timers for
 * the Quality Platform dashboard. Failures are logged and swallowed - persistence never blocks the
 * main chain.
 */
@Component
public class VerificationRecorder {

    private static final Logger log = LoggerFactory.getLogger(VerificationRecorder.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DataSource dataSource;
    private final MeterRegistry meterRegistry;

    public VerificationRecorder(DataSource dataSource, MeterRegistry meterRegistry) {
        this.dataSource = dataSource;
        this.meterRegistry = meterRegistry;
    }

    public void recordVerdict(VerificationContext vctx, VerificationVerdict verdict,
                              String checkpoint, long latencyMs) {
        if (vctx == null || verdict == null) return;
        try {
            String issuesJson = MAPPER.writeValueAsString(verdict);
            String correctionsJson = verdict.getCorrections() == null ? "[]"
                    : MAPPER.writeValueAsString(verdict.getCorrections());
            String repairType = verdict.getRepairType() == null ? null : verdict.getRepairType().name();

            Integer dt = dim(verdict, "tool"), dd = dim(verdict, "data"),
                    ds = dim(verdict, "semantic"), da = dim(verdict, "adversarial"),
                    de = dim(verdict, "evidence"), df = dim(verdict, "freshness");

            String sql = "INSERT INTO verification_record (session_id, user_id, checkpoint, trigger_level, "
                    + "candidate_source, trust_score, verdict, dim_tool, dim_data, dim_semantic, "
                    + "dim_adversarial, dim_evidence, dim_freshness, repair_type, summary, issues_json, "
                    + "corrections_json, loop_index, latency_ms, candidate_conclusion, experiment_id, created_at) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, NOW(3))";
            Connection conn = DataSourceUtils.getConnection(dataSource);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, vctx.getSessionId());
                ps.setString(2, vctx.getUserId());
                ps.setString(3, checkpoint);
                ps.setString(4, vctx.getTriggerLevel());
                ps.setString(5, safe(vctx.getCandidateSource()));
                ps.setObject(6, verdict.getTrustScore(), Types.INTEGER);
                ps.setString(7, safe(verdict.getVerdict()));
                setIntOrNull(ps, 8, dt);
                setIntOrNull(ps, 9, dd);
                setIntOrNull(ps, 10, ds);
                setIntOrNull(ps, 11, da);
                setIntOrNull(ps, 12, de);
                setIntOrNull(ps, 13, df);
                ps.setString(14, repairType);
                ps.setString(15, safe(verdict.getSummary()));
                ps.setString(16, issuesJson);
                ps.setString(17, correctionsJson);
                ps.setInt(18, verdict.getLoopIndex());
                ps.setLong(19, latencyMs);
                ps.setString(20, safe(vctx.getCandidateConclusion()));
                ps.setString(21, vctx.getActiveExperimentId());
                ps.executeUpdate();
            } finally {
                DataSourceUtils.releaseConnection(conn, dataSource);
            }
        } catch (Exception e) {
            log.warn("VerificationRecorder: recordVerdict failed for session={}: {}", vctx.getSessionId(), e.getMessage());
        }

        try {
            meterRegistry.counter("verification.verdict", "verdict", safe(verdict.getVerdict()),
                    "checkpoint", checkpoint).increment();
            Timer.builder("verification.latency").tag("checkpoint", checkpoint)
                    .register(meterRegistry).record(java.time.Duration.ofMillis(latencyMs));
        } catch (Exception ignored) {
        }
    }

    public void recordRepairHistory(VerificationContext vctx, int loopIndex, RepairPlan plan,
                                    boolean gamingSuspect, String outcome) {
        if (vctx == null || plan == null) return;
        try {
            String sql = "INSERT INTO repair_execution_history (session_id, loop_index, error_type, "
                    + "repair_type, directive, forbidden_hit, gaming_suspect, outcome, created_at) "
                    + "VALUES (?,?,?,?,?,?,?,?, NOW(3))";
            Connection conn = DataSourceUtils.getConnection(dataSource);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, vctx.getSessionId());
                ps.setInt(2, loopIndex);
                ps.setString(3, safe(plan.errorType()));
                ps.setString(4, plan.type() == null ? null : plan.type().name());
                ps.setString(5, safe(plan.directive()));
                ps.setInt(6, 0);
                ps.setInt(7, gamingSuspect ? 1 : 0);
                ps.setString(8, safe(outcome));
                ps.executeUpdate();
            } finally {
                DataSourceUtils.releaseConnection(conn, dataSource);
            }
        } catch (Exception e) {
            log.warn("VerificationRecorder: recordRepairHistory failed: {}", e.getMessage());
        }
    }

    /** Batch-persist the event stream. Called once at request cleanup. */
    public void recordEvents(VerificationContext vctx) {
        if (vctx == null || vctx.getEventStream().isEmpty()) return;
        String sql = "INSERT INTO verification_event (event_id, session_id, type, actor, parent_event_id, "
                + "payload_json, created_ts) VALUES (?,?,?,?,?,?,?)";
        Connection conn = null;
        try {
            conn = DataSourceUtils.getConnection(dataSource);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (AgentExecutionEvent e : vctx.getEventStream()) {
                    ps.setString(1, e.eventId());
                    ps.setString(2, safe(e.sessionId()));
                    ps.setString(3, safe(e.type()));
                    ps.setString(4, safe(e.actor()));
                    ps.setString(5, e.parentEventId());
                    ps.setString(6, e.payload() == null ? null : MAPPER.writeValueAsString(e.payload()));
                    ps.setLong(7, e.timestamp());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        } catch (Exception e) {
            log.warn("VerificationRecorder: recordEvents failed for session={}: {}", vctx.getSessionId(), e.getMessage());
        } finally {
            if (conn != null) {
                DataSourceUtils.releaseConnection(conn, dataSource);
            }
        }
    }

    private static Integer dim(VerificationVerdict v, String key) {
        return v.getDimensions() == null ? null : v.getDimensions().get(key);
    }

    private static void setIntOrNull(PreparedStatement ps, int idx, Integer val) throws SQLException {
        if (val == null) ps.setNull(idx, Types.INTEGER);
        else ps.setInt(idx, val);
    }

    private static String safe(String s) {
        return s == null ? null : (s.length() > 65000 ? s.substring(0, 65000) : s);
    }
}
