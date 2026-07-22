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
import com.agentscopea2a.v2.tools.ToolRoutersIndex;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.PostCallEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end integration test against an in-memory H2 (MySQL-compatibility mode), loaded with the
 * V3.0/V4.0 verification schema + seeds (classpath:verify_schema.sql). Verifies the full stack
 * without an external DB or LLM:
 *
 * <ol>
 *   <li>schema + seeds load (tables exist, seeds present);</li>
 *   <li>JDBC components round-trip (SemanticContractRegistry / VerificationRecorder / VersionRegistry /
 *       RuleExperimentService / TrustCalibrationService / SloMonitor);</li>
 *   <li>{@link VerifyLoopOrchestrator} end-to-end B4 path: contract-aggregation violation flows
 *       precheck -> verdict -> repair policy -> recorder -> DB rows;</li>
 *   <li>{@link ReplayService} TRACE replay reconstructs the recorded verdict + event timeline.</li>
 * </ol>
 *
 * <p>A real-MySQL equivalent (Testcontainers) is configured in the pom; it is not used here because
 * the local Docker Desktop engine is incompatible with the Testcontainers docker-java client
 * (returns HTTP 400 to /info). In a CI environment with a standard Docker daemon, the same test
 * runs unchanged against MySQL via Flyway.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VerifyEndToEndIntegrationTest {

    private static final String H2_URL =
            "jdbc:h2:mem:verify_test;MODE=MySQL;DB_CLOSE_DELAY=-1";

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private HarnessRunnerProperties props;
    private SemanticContractRegistry contractRegistry;
    private VerificationRecorder recorder;
    private VerifyLoopOrchestrator orchestrator;
    private ReplayService replayService;

    @BeforeAll
    void setUp() {
        dataSource = new DriverManagerDataSource(H2_URL, "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        // load schema + seeds exactly once (NOT via URL INIT - H2 re-runs INIT per connection,
        // which would duplicate the seed inserts).
        jdbc.execute("RUNSCRIPT FROM 'classpath:verify_schema.sql'");

        props = new HarnessRunnerProperties();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        CalibrationState calibrationState = new CalibrationState(dataSource, props);
        contractRegistry = new SemanticContractRegistry(dataSource, props);
        recorder = new VerificationRecorder(dataSource, meterRegistry);
        DeterministicChecker deterministicChecker = new DeterministicChecker(
                new ContractComplianceChecker(), mock(ToolRoutersIndex.class), props);
        TrustScoreCalculator trustScore = new TrustScoreCalculator(props, calibrationState);
        TriggerLevelResolver triggerLevel = new TriggerLevelResolver(props);
        ResponsePolicyResolver responsePolicy = new ResponsePolicyResolver(calibrationState);
        RepairPolicyEngine repairPolicy = new RepairPolicyEngine(dataSource, props);
        CriticChallengeStats criticStats = new CriticChallengeStats(dataSource);
        RuleExperimentService ruleExp = new RuleExperimentService(dataSource);
        VerifyAgentInvoker verifyInvoker = mock(VerifyAgentInvoker.class);
        CriticAgentInvoker criticInvoker = mock(CriticAgentInvoker.class);
        orchestrator = new VerifyLoopOrchestrator(deterministicChecker, contractRegistry, verifyInvoker,
                criticInvoker, trustScore, triggerLevel, responsePolicy, repairPolicy, recorder,
                criticStats, ruleExp, props);
        replayService = new ReplayService(dataSource, deterministicChecker, verifyInvoker, contractRegistry);
    }

    @Test
    void schemaAndSeedsLoad() {
        // tables exist (other tests in this class share the DB and may have inserted rows,
        // so we assert existence via a successful COUNT, not emptiness)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM verification_record", Integer.class)).isGreaterThanOrEqualTo(0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM verification_event", Integer.class)).isGreaterThanOrEqualTo(0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rule_experiment", Integer.class)).isGreaterThanOrEqualTo(0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM critic_challenge_stats", Integer.class)).isGreaterThanOrEqualTo(0);

        assertThat(jdbc.queryForObject(
                "SELECT direction_higher FROM semantic_metric_contract WHERE metric_id='quality_score'", String.class))
                .isEqualTo("worse");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM repair_policy_rule", Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM golden_dataset_case", Integer.class)).isEqualTo(4);
    }

    @Test
    void jdbcComponentsRoundTrip() {
        SemanticContracts.MetricContract m = contractRegistry.lookupMetric("quality_score");
        assertThat(m).isNotNull();
        assertThat(m.directionHigher()).isEqualTo("worse");
        assertThat(m.aggregationDeny()).contains("sum");

        VersionRegistry versionRegistry = new VersionRegistry(dataSource);
        String versionId = versionRegistry.register("AGENT", "QualitySupervisorV2", "it-v1", null, "it");
        assertThat(versionRegistry.get(versionId)).isNotNull();

        RuleExperimentService ruleExp = new RuleExperimentService(dataSource);
        String expId = ruleExp.start("it-exp", "quality_score", "worse", "sum", 10);
        assertThat(ruleExp.listByStatus("running").stream().anyMatch(e -> e.experimentId().equals(expId))).isTrue();

        TrustCalibrationService calibration = new TrustCalibrationService(dataSource, new CalibrationState(dataSource, props));
        calibration.recordFeedback(new VerificationFeedback("it-sess-fb", "pass", "correct", null, "it"));
        CalibrationReport report = calibration.calibrate();
        assertThat(report.applied()).isFalse(); // < MIN_SAMPLE (10)

        SloReport slo = new SloMonitor(dataSource, props).report(24);
        assertThat(slo.healthy()).isTrue();
    }

    @Test
    void orchestratorB4EndToEnd() {
        String sessionId = "it-sess-b4";
        VerificationContext vctx = new VerificationContext(sessionId, "it-user", "对质量评分求和");
        vctx.setTriggerLevel("MEDIUM");
        vctx.setCandidateConclusion("对质量评分求和得到 100 分", "supervisor");

        PostCallEvent event = mock(PostCallEvent.class);
        when(event.getFinalMessage()).thenReturn(null);
        RuntimeContext ctx = mock(RuntimeContext.class);
        when(ctx.get(any(String.class))).thenReturn(null);

        orchestrator.onSupervisorExit(vctx, event, ctx).block();

        String verdict = jdbc.queryForObject(
                "SELECT verdict FROM verification_record WHERE session_id=? ORDER BY created_at DESC LIMIT 1",
                String.class, sessionId);
        assertThat(verdict).isEqualTo("fail");
        String repairType = jdbc.queryForObject(
                "SELECT repair_type FROM verification_record WHERE session_id=? ORDER BY created_at DESC LIMIT 1",
                String.class, sessionId);
        assertThat(repairType).isEqualTo("SEMANTIC_FIX");
        Integer repairHistory = jdbc.queryForObject(
                "SELECT COUNT(*) FROM repair_execution_history WHERE session_id=?", Integer.class, sessionId);
        assertThat(repairHistory).isGreaterThanOrEqualTo(1);
    }

    @Test
    void replayTraceReconstructsOriginal() {
        String sessionId = "it-sess-replay";
        VerificationContext vctx = new VerificationContext(sessionId, "it-user", "replay query");
        vctx.setTriggerLevel("MEDIUM");
        vctx.setCandidateConclusion("结论：华东销售最高", "supervisor");
        vctx.emit(AgentExecutionEvent.TOOL_CALL_STARTED, "supervisor", Map.of("tool", "router_tool"));
        vctx.emit(AgentExecutionEvent.TOOL_CALL_COMPLETED, "supervisor",
                Map.of("tool", "router_tool", "output", "rows=5", "isError", false, "isEmpty", false));

        recorder.recordEvents(vctx);
        VerificationVerdict verdict = new VerificationVerdict();
        verdict.setVerdict("warn");
        verdict.setTrustScore(78);
        verdict.setCandidateSource("supervisor");
        recorder.recordVerdict(vctx, verdict, "supervisor-exit", 150L);

        ReplayResult result = replayService.replay(sessionId, "TRACE", null);

        assertThat(result.originalVerdict()).isEqualTo("warn");
        assertThat(result.originalTrustScore()).isEqualTo(78);
        assertThat(result.eventCount()).isGreaterThanOrEqualTo(2);
    }
}
