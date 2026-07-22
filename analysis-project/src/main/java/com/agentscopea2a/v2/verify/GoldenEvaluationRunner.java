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

import com.agentscopea2a.v2.runner.HarnessA2aRunnerV2;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Golden Evaluation Pipeline (V3.0 design §13/§25, P0). Runs the Data Agent over the golden
 * question set after an Agent / Prompt / Skill / Semantic-Contract upgrade, scores each answer
 * (expected-answer accuracy + the verification verdict recorded during the run), and gates the
 * release on accuracy regression vs the previous evaluation.
 *
 * <p>Runs <b>asynchronously</b> ({@link #startEvaluation} returns the eval id immediately; cases are
 * processed on boundedElastic, each result persisted as it completes). The controller / cron triggers
 * it; results are read back via {@link #getReport}. Each case reuses the live verification pipeline
 * (the {@link VerificationHook} fires during the agent run and records a verdict under the case's
 * golden session id), so the benchmark measures the system as actually deployed.
 */
@Component
public class GoldenEvaluationRunner {

    private static final Logger log = LoggerFactory.getLogger(GoldenEvaluationRunner.class);
    private static final double GATE_ACCURACY_DROP = 0.02;
    private static final Duration CASE_TIMEOUT = Duration.ofMinutes(5);

    private final HarnessA2aRunnerV2 runner;
    private final DataSource dataSource;
    private final VersionRegistry versionRegistry;

    public GoldenEvaluationRunner(HarnessA2aRunnerV2 runner, DataSource dataSource, VersionRegistry versionRegistry) {
        this.runner = runner;
        this.dataSource = dataSource;
        this.versionRegistry = versionRegistry;
    }

    /** Kick off an evaluation asynchronously; returns the eval id immediately. */
    public String startEvaluation(String versionLabel, String agentVersion, String promptVersion,
                                  String skillVersion, String semanticVersion, String releasedBy) {
        String evalId = "geval-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String versionId = versionRegistry.register("AGENT", "QualitySupervisorV2",
                agentVersion == null ? "v1" : agentVersion, versionLabel, releasedBy);
        versionRegistry.linkGoldenEval(versionId, evalId);

        Mono.fromRunnable(() -> runCases(evalId, versionLabel, agentVersion, promptVersion, skillVersion, semanticVersion))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        v -> {},
                        e -> log.error("Golden evaluation {} failed: {}", evalId, e.getMessage(), e),
                        () -> log.info("Golden evaluation {} completed", evalId));
        log.info("Golden evaluation {} started (versionLabel={}, agentVersion={})", evalId, versionLabel, agentVersion);
        return evalId;
    }

    private void runCases(String evalId, String versionLabel, String agentVersion, String promptVersion,
                          String skillVersion, String semanticVersion) {
        List<GoldenDatasetCase> cases = loadCases();
        for (GoldenDatasetCase c : cases) {
            try {
                String actual = runAgentForCase(c, evalId);
                RecordedVerdict recorded = queryLatestVerdict("golden-" + evalId + "-" + c.caseId());
                EvaluationResult result = evaluate(evalId, c, actual, recorded, agentVersion, promptVersion,
                        skillVersion, semanticVersion);
                persistResult(result);
                log.info("Golden {} case {} accuracy={} verdict={} score={}",
                        evalId, c.caseId(), result.accuracyPass(), result.verdict(), result.trustScore());
            } catch (Exception e) {
                log.warn("Golden {} case {} errored: {}", evalId, c.caseId(), e.getMessage());
                persistResult(new EvaluationResult(evalId, c.caseId(), agentVersion, promptVersion, skillVersion,
                        semanticVersion, "[ERROR] " + e.getMessage(), null, "unverified",
                        false, false, true, null));
            }
        }
    }

    private String runAgentForCase(GoldenDatasetCase c, String evalId) {
        String sessionId = "golden-" + evalId + "-" + c.caseId();
        RuntimeContext ctx = RuntimeContext.builder().sessionId(sessionId).userId("golden").build();
        VerificationContext vctx = new VerificationContext(sessionId, "golden", c.question());
        vctx.setTriggerLevel(TriggerLevelResolver.HIGH); // thorough: verify + critic
        ctx.put(VerificationContext.VERIFY_CTX_KEY, vctx);

        Msg userMsg = Msg.builder().role(MsgRole.USER)
                .content(TextBlock.builder().text(c.question()).build())
                .build();
        try {
            List<AgentEvent> events = runner.streamEvents(List.of(userMsg), ctx)
                    .collectList().block(CASE_TIMEOUT);
            return extractFinalAnswer(events);
        } catch (Exception e) {
            log.warn("Golden case {} agent run failed: {}", c.caseId(), e.getMessage());
            return "[ERROR] " + e.getMessage();
        }
    }

    private EvaluationResult evaluate(String evalId, GoldenDatasetCase c, String actual, RecordedVerdict rec,
                                      String agentVersion, String promptVersion, String skillVersion, String semanticVersion) {
        boolean accuracyPass = matchExpected(actual, c.expectedAnswer());
        Integer trustScore = rec == null ? null : rec.trustScore;
        String verdict = rec == null ? "unverified" : rec.verdict;
        boolean semanticPass = rec != null
                && ((rec.dimSemantic != null && rec.dimSemantic >= 70)
                || "pass".equalsIgnoreCase(verdict) || "warn".equalsIgnoreCase(verdict));
        boolean hallucination = rec != null
                && ("REFUSE".equalsIgnoreCase(rec.repairType)
                || ("fail".equalsIgnoreCase(verdict) && rec.dimData != null && rec.dimData < 50));
        String repairUsed = rec == null ? null : rec.repairType;
        return new EvaluationResult(evalId, c.caseId(), agentVersion, promptVersion, skillVersion,
                semanticVersion, truncate(actual, 1000), trustScore, verdict, accuracyPass, semanticPass,
                hallucination, repairUsed);
    }

    /** Normalize both sides (strip the verification annotation + whitespace) and check containment. */
    static boolean matchExpected(String actual, String expected) {
        if (expected == null || expected.isBlank()) return true;
        if (actual == null) return false;
        String a = normalize(stripAnnotation(actual));
        String e = normalize(expected);
        if (e.isEmpty()) return true;
        return a.contains(e) || e.contains(a);
    }

    private static String stripAnnotation(String s) {
        int idx = s.indexOf("\n---\n🔍");
        if (idx < 0) idx = s.indexOf("\n🔍");
        return idx >= 0 ? s.substring(0, idx) : s;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.strip().replaceAll("\\s+", "").toLowerCase();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private String extractFinalAnswer(List<AgentEvent> events) {
        if (events == null) return "";
        for (AgentEvent e : events) {
            if (e instanceof AgentResultEvent re && re.getResult() != null) {
                String t = re.getResult().getTextContent();
                if (t != null && !t.isBlank()) return t;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (AgentEvent e : events) {
            if (e instanceof TextBlockDeltaEvent d && d.getDelta() != null) sb.append(d.getDelta());
        }
        return sb.toString();
    }

    // ===== persistence / queries =====

    public GoldenEvaluationReport getReport(String evalId) {
        List<EvaluationResult> results = new ArrayList<>();
        String sql = "SELECT eval_id, case_id, agent_version, prompt_version, skill_version, semantic_version, "
                + "actual_answer, trust_score, verdict, accuracy_pass, semantic_pass, hallucination_flag, repair_used "
                + "FROM golden_evaluation_result WHERE eval_id=? ORDER BY case_id";
        Connection conn = DataSourceUtils.getConnection(dataSource);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, evalId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new EvaluationResult(
                            rs.getString("eval_id"), rs.getString("case_id"),
                            rs.getString("agent_version"), rs.getString("prompt_version"),
                            rs.getString("skill_version"), rs.getString("semantic_version"),
                            rs.getString("actual_answer"),
                            (Integer) rs.getObject("trust_score"),
                            rs.getString("verdict"),
                            rs.getBoolean("accuracy_pass"),
                            rs.getBoolean("semantic_pass"),
                            rs.getBoolean("hallucination_flag"),
                            rs.getString("repair_used")));
                }
            }
        } catch (Exception e) {
            log.warn("Golden getReport failed: {}", e.getMessage());
        } finally {
            DataSourceUtils.releaseConnection(conn, dataSource);
        }

        int total = results.size();
        int acc = (int) results.stream().filter(r -> r.accuracyPass()).count();
        int halluc = (int) results.stream().filter(r -> r.hallucinationFlag()).count();
        int repaired = (int) results.stream().filter(r -> r.repairUsed() != null && !"NONE".equalsIgnoreCase(r.repairUsed())).count();
        double avgTrust = results.stream().filter(r -> r.trustScore() != null)
                .mapToInt(EvaluationResult::trustScore).average().orElse(0.0);
        double accuracyRate = total == 0 ? 0 : (double) acc / total;

        double prevAccuracy = previousEvalAccuracy(evalId);
        boolean gatePassed = prevAccuracy < 0 || (accuracyRate >= prevAccuracy - GATE_ACCURACY_DROP);
        String gateReason = prevAccuracy < 0 ? "no baseline"
                : (gatePassed ? "ok (prev=" + String.format("%.2f", prevAccuracy) + ")"
                : "accuracy dropped from " + String.format("%.2f", prevAccuracy) + " to " + String.format("%.2f", accuracyRate));

        return new GoldenEvaluationReport(evalId, null, total, acc, accuracyRate,
                total == 0 ? 0 : (double) halluc / total, avgTrust,
                total == 0 ? 0 : (double) repaired / total, gatePassed, gateReason, results);
    }

    private double previousEvalAccuracy(String currentEvalId) {
        String sql = "SELECT eval_id, AVG(accuracy_pass) AS acc FROM golden_evaluation_result "
                + "GROUP BY eval_id ORDER BY MAX(created_at) DESC";
        Connection conn = DataSourceUtils.getConnection(dataSource);
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            boolean skippedCurrent = false;
            while (rs.next()) {
                String eid = rs.getString("eval_id");
                if (eid.equals(currentEvalId)) {
                    skippedCurrent = true;
                    continue;
                }
                if (skippedCurrent) {
                    return rs.getDouble("acc");
                }
            }
        } catch (Exception e) {
            log.warn("previousEvalAccuracy failed: {}", e.getMessage());
        } finally {
            DataSourceUtils.releaseConnection(conn, dataSource);
        }
        return -1;
    }

    private void persistResult(EvaluationResult r) {
        String sql = "INSERT INTO golden_evaluation_result (eval_id, case_id, agent_version, prompt_version, "
                + "skill_version, semantic_version, actual_answer, trust_score, verdict, accuracy_pass, "
                + "semantic_pass, hallucination_flag, repair_used) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)";
        Connection conn = DataSourceUtils.getConnection(dataSource);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, r.evalId());
            ps.setString(2, r.caseId());
            ps.setString(3, r.agentVersion());
            ps.setString(4, r.promptVersion());
            ps.setString(5, r.skillVersion());
            ps.setString(6, r.semanticVersion());
            ps.setString(7, r.actualAnswer());
            if (r.trustScore() == null) ps.setNull(8, java.sql.Types.INTEGER);
            else ps.setInt(8, r.trustScore());
            ps.setString(9, r.verdict());
            ps.setInt(10, r.accuracyPass() ? 1 : 0);
            ps.setInt(11, r.semanticPass() ? 1 : 0);
            ps.setInt(12, r.hallucinationFlag() ? 1 : 0);
            ps.setString(13, r.repairUsed());
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("persistResult failed: {}", e.getMessage());
        } finally {
            DataSourceUtils.releaseConnection(conn, dataSource);
        }
    }

    private List<GoldenDatasetCase> loadCases() {
        List<GoldenDatasetCase> out = new ArrayList<>();
        String sql = "SELECT case_id, question, category, expected_sql, expected_answer, expected_metric, "
                + "difficulty, tags, version FROM golden_dataset_case ORDER BY case_id";
        Connection conn = DataSourceUtils.getConnection(dataSource);
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new GoldenDatasetCase(
                        rs.getString("case_id"), rs.getString("question"), rs.getString("category"),
                        rs.getString("expected_sql"), rs.getString("expected_answer"),
                        rs.getString("expected_metric"), rs.getString("difficulty"),
                        rs.getString("tags"), rs.getString("version")));
            }
        } catch (Exception e) {
            log.warn("loadCases failed: {}", e.getMessage());
        } finally {
            DataSourceUtils.releaseConnection(conn, dataSource);
        }
        return out;
    }

    private RecordedVerdict queryLatestVerdict(String sessionId) {
        String sql = "SELECT trust_score, verdict, dim_semantic, dim_data, repair_type FROM verification_record "
                + "WHERE session_id=? ORDER BY created_at DESC LIMIT 1";
        Connection conn = DataSourceUtils.getConnection(dataSource);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new RecordedVerdict(
                            (Integer) rs.getObject("trust_score"),
                            rs.getString("verdict"),
                            (Integer) rs.getObject("dim_semantic"),
                            (Integer) rs.getObject("dim_data"),
                            rs.getString("repair_type"));
                }
            }
        } catch (Exception e) {
            log.warn("queryLatestVerdict failed: {}", e.getMessage());
        } finally {
            DataSourceUtils.releaseConnection(conn, dataSource);
        }
        return null;
    }

    private record RecordedVerdict(Integer trustScore, String verdict, Integer dimSemantic, Integer dimData, String repairType) {
    }
}
