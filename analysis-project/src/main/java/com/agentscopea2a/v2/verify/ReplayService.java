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
import io.agentscope.core.agent.RuntimeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Replay Engine (V3.0 design §15/§16, P1). Reconstructs a request's verification context from the
 * persisted {@code verification_event} stream + {@code verification_record} candidate conclusion,
 * and re-runs the engine to answer "why did it answer this yesterday?" or to regression-test a new
 * engine version against historical sessions (without live traffic).
 *
 * <ul>
 *   <li><b>TRACE</b> - return the original recorded verdict + reconstructed event timeline (instant).</li>
 *   <li><b>VERSION</b> - re-run the current Rule Engine + verify on the stored candidate; compare.</li>
 *   <li><b>MODEL</b> - same as VERSION but pin a specific verify-model instance key.</li>
 *   <li><b>CONTRACT</b> - same as VERSION (re-runs against the currently loaded Semantic Contracts).</li>
 * </ul>
 */
@Component
public class ReplayService {

    private static final Logger log = LoggerFactory.getLogger(ReplayService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration VERIFY_TIMEOUT = Duration.ofSeconds(120);

    public enum ReplayMode { TRACE, VERSION, MODEL, CONTRACT }

    private final DataSource dataSource;
    private final DeterministicChecker deterministicChecker;
    private final VerifyAgentInvoker verifyInvoker;
    private final SemanticContractRegistry contractRegistry;

    public ReplayService(DataSource dataSource, DeterministicChecker deterministicChecker,
                         VerifyAgentInvoker verifyInvoker, SemanticContractRegistry contractRegistry) {
        this.dataSource = dataSource;
        this.deterministicChecker = deterministicChecker;
        this.verifyInvoker = verifyInvoker;
        this.contractRegistry = contractRegistry;
    }

    public ReplayResult replay(String sessionId, String mode, String modelKey) {
        List<AgentExecutionEvent> events = loadEvents(sessionId);
        OriginalVerdict original = loadOriginalVerdict(sessionId);
        Integer origTrust = original == null ? null : original.trustScore;
        String origVerdict = original == null ? null : original.verdict;
        String candidate = original == null || original.candidateConclusion == null ? "" : original.candidateConclusion;

        ReplayMode m = parseMode(mode);
        if (m == ReplayMode.TRACE || events.isEmpty() || original == null) {
            String note = events.isEmpty() ? "no events for session"
                    : (original == null ? "no verdict record for session" : "trace replay (original verdict)");
            return new ReplayResult(sessionId, m == null ? mode : m.name(), origTrust, origVerdict,
                    null, null, null, events.size(), truncate(candidate, 500), note);
        }

        // VERSION / MODEL / CONTRACT: reconstruct context and re-run.
        VerificationContext vctx = new VerificationContext(sessionId, original.userId, "");
        for (AgentExecutionEvent e : events) {
            vctx.getEventStream().add(e);
        }
        vctx.setCandidateConclusion(candidate, "replay");
        vctx.setContractSnapshot(contractRegistry.snapshotAll());
        RuntimeContext ctx = RuntimeContext.builder().sessionId(sessionId).userId(original.userId).build();

        try {
            DeterministicChecker.Result precheck = deterministicChecker.check(vctx);
            String effectiveKey = m == ReplayMode.MODEL ? modelKey : null;
            VerificationVerdict replayed = verifyInvoker.verify(vctx, precheck, ctx, effectiveKey)
                    .block(VERIFY_TIMEOUT);
            String note = switch (m) {
                case MODEL -> "model replay (verify-model=" + (modelKey == null ? "default" : modelKey) + ")";
                case CONTRACT -> "contract replay (current Semantic Contracts)";
                default -> "version replay (current engine)";
            };
            return new ReplayResult(sessionId, m.name(), origTrust, origVerdict,
                    replayed == null ? null : replayed.getTrustScore(),
                    replayed == null ? null : replayed.getVerdict(),
                    replayed == null ? null : replayed.getSummary(),
                    events.size(), truncate(candidate, 500), note);
        } catch (Exception e) {
            log.warn("Replay {} failed: {}", sessionId, e.getMessage());
            return new ReplayResult(sessionId, m.name(), origTrust, origVerdict,
                    null, null, null, events.size(), truncate(candidate, 500),
                    "replay failed: " + e.getMessage());
        }
    }

    private static ReplayMode parseMode(String mode) {
        if (mode == null) return ReplayMode.TRACE;
        try {
            return ReplayMode.valueOf(mode.toUpperCase());
        } catch (Exception e) {
            return ReplayMode.TRACE;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // ===== loading =====

    @SuppressWarnings("unchecked")
    private List<AgentExecutionEvent> loadEvents(String sessionId) {
        List<AgentExecutionEvent> out = new ArrayList<>();
        String sql = "SELECT event_id, type, actor, parent_event_id, session_id, created_ts, payload_json "
                + "FROM verification_event WHERE session_id=? ORDER BY created_ts, id";
        Connection conn = DataSourceUtils.getConnection(dataSource);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> payload = null;
                    String json = rs.getString("payload_json");
                    if (json != null && !json.isBlank()) {
                        try {
                            payload = MAPPER.readValue(json, Map.class);
                        } catch (Exception ignored) {
                            payload = Map.of();
                        }
                    }
                    out.add(new AgentExecutionEvent(
                            rs.getString("event_id"), rs.getString("type"), rs.getString("actor"),
                            rs.getString("parent_event_id"), rs.getString("session_id"),
                            rs.getLong("created_ts"), payload));
                }
            }
        } catch (Exception e) {
            log.warn("Replay loadEvents failed: {}", e.getMessage());
        } finally {
            DataSourceUtils.releaseConnection(conn, dataSource);
        }
        return out;
    }

    private OriginalVerdict loadOriginalVerdict(String sessionId) {
        String sql = "SELECT trust_score, verdict, candidate_conclusion, user_id FROM verification_record "
                + "WHERE session_id=? ORDER BY created_at DESC LIMIT 1";
        Connection conn = DataSourceUtils.getConnection(dataSource);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new OriginalVerdict(
                            (Integer) rs.getObject("trust_score"),
                            rs.getString("verdict"),
                            rs.getString("candidate_conclusion"),
                            rs.getString("user_id"));
                }
            }
        } catch (Exception e) {
            log.warn("Replay loadOriginalVerdict failed: {}", e.getMessage());
        } finally {
            DataSourceUtils.releaseConnection(conn, dataSource);
        }
        return null;
    }

    private record OriginalVerdict(Integer trustScore, String verdict, String candidateConclusion, String userId) {
    }
}
