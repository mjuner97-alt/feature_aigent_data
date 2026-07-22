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

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Admin endpoints for the V3.0 Quality Platform offline modules (Phase 2): Golden evaluation,
 * Replay, auto-optimization, and the Version Registry. These are operational / governance tools -
 * not on the request hot path.
 *
 * <ul>
 *   <li>{@code POST /v2/ai/verification/golden-eval} - start a Golden evaluation (async), returns evalId.</li>
 *   <li>{@code GET  /v2/ai/verification/golden-eval/{evalId}} - aggregated report + per-case results.</li>
 *   <li>{@code GET  /v2/ai/verification/replay?sessionId=&mode=&modelKey=} - replay a session
 *       (TRACE / VERSION / MODEL / CONTRACT).</li>
 *   <li>{@code POST /v2/ai/verification/optimize} - run the auto-optimization loop (proposals only).</li>
 *   <li>{@code GET  /v2/ai/verification/versions?component=&limit=} - list registered versions.</li>
 * </ul>
 */
@RestController
@RequestMapping("/v2/ai/verification")
public class VerificationAdminController {

    private final GoldenEvaluationRunner goldenRunner;
    private final ReplayService replayService;
    private final QualityOptimizationLoop optimizationLoop;
    private final VersionRegistry versionRegistry;
    private final TrustCalibrationService trustCalibration;
    private final SloMonitor sloMonitor;
    private final CalibrationState calibrationState;
    private final RuleExperimentService ruleExperimentService;
    private final CriticChallengeStats criticChallengeStats;

    public VerificationAdminController(GoldenEvaluationRunner goldenRunner,
                                       ReplayService replayService,
                                       QualityOptimizationLoop optimizationLoop,
                                       VersionRegistry versionRegistry,
                                       TrustCalibrationService trustCalibration,
                                       SloMonitor sloMonitor,
                                       CalibrationState calibrationState,
                                       RuleExperimentService ruleExperimentService,
                                       CriticChallengeStats criticChallengeStats) {
        this.goldenRunner = goldenRunner;
        this.replayService = replayService;
        this.optimizationLoop = optimizationLoop;
        this.versionRegistry = versionRegistry;
        this.trustCalibration = trustCalibration;
        this.sloMonitor = sloMonitor;
        this.calibrationState = calibrationState;
        this.ruleExperimentService = ruleExperimentService;
        this.criticChallengeStats = criticChallengeStats;
    }

    @PostMapping("/golden-eval")
    public Map<String, String> startGoldenEval(
            @RequestParam(required = false) String versionLabel,
            @RequestParam(required = false) String agentVersion,
            @RequestParam(required = false) String promptVersion,
            @RequestParam(required = false) String skillVersion,
            @RequestParam(required = false) String semanticVersion,
            @RequestParam(required = false) String releasedBy) {
        String evalId = goldenRunner.startEvaluation(
                versionLabel, agentVersion, promptVersion, skillVersion, semanticVersion, releasedBy);
        return Map.of("evalId", evalId, "status", "started");
    }

    @GetMapping("/golden-eval/{evalId}")
    public GoldenEvaluationReport getGoldenReport(@PathVariable String evalId) {
        return goldenRunner.getReport(evalId);
    }

    @GetMapping("/replay")
    public ReplayResult replay(@RequestParam String sessionId,
                               @RequestParam(defaultValue = "TRACE") String mode,
                               @RequestParam(required = false) String modelKey) {
        return replayService.replay(sessionId, mode, modelKey);
    }

    @PostMapping("/optimize")
    public List<OptimizationProposal> optimize() {
        return optimizationLoop.runOptimization();
    }

    @GetMapping("/versions")
    public List<VersionRecord> versions(@RequestParam String component,
                                        @RequestParam(defaultValue = "20") int limit) {
        return versionRegistry.listByComponent(component, limit);
    }

    // ===== V4.0: online calibration + SLO + auto-apply/rollback =====

    @PostMapping("/feedback")
    public Map<String, String> feedback(@RequestParam String sessionId,
                                        @RequestParam String verdict,
                                        @RequestParam String humanLabel,
                                        @RequestParam(required = false) String note,
                                        @RequestParam(required = false) String createdBy) {
        trustCalibration.recordFeedback(
                new VerificationFeedback(sessionId, verdict, humanLabel, note, createdBy));
        return Map.of("status", "recorded");
    }

    @PostMapping("/calibrate")
    public CalibrationReport calibrate() {
        return trustCalibration.calibrate();
    }

    @GetMapping("/calibration")
    public Map<String, Object> calibration() {
        return Map.of(
                "passThreshold", calibrationState.getPassThreshold(),
                "warnThreshold", calibrationState.getWarnThreshold(),
                "directThreshold", calibrationState.getDirectThreshold(),
                "hintThreshold", calibrationState.getHintThreshold(),
                "wData", calibrationState.getWData(),
                "wTool", calibrationState.getWTool(),
                "wSemantic", calibrationState.getWSemantic(),
                "wAdversarial", calibrationState.getWAdversarial());
    }

    /**
     * Aggregated dashboard snapshot: bundles SLO report, calibration state, critic
     * stats, and active experiments into one response so the frontend needs only
     * one call per 15s refresh cycle.
     */
    @GetMapping("/dashboard")
    public DashboardSnapshot dashboard(@RequestParam(defaultValue = "24") int windowHours) {
        SloReport slo = sloMonitor.report(windowHours);
        Map<String, Object> calibration = Map.of(
                "passThreshold", calibrationState.getPassThreshold(),
                "warnThreshold", calibrationState.getWarnThreshold(),
                "directThreshold", calibrationState.getDirectThreshold(),
                "hintThreshold", calibrationState.getHintThreshold(),
                "wData", calibrationState.getWData(),
                "wTool", calibrationState.getWTool(),
                "wSemantic", calibrationState.getWSemantic(),
                "wAdversarial", calibrationState.getWAdversarial());
        List<RuleExperiment> runningExperiments = ruleExperimentService.listByStatus("running");
        Map<String, Double> criticEffectiveness = criticChallengeStats.effectiveness();
        return new DashboardSnapshot(slo, calibration, runningExperiments, criticEffectiveness);
    }

    /**
     * Hourly time-series buckets for the quality trends chart.
     */
    @GetMapping("/trends")
    public List<HourlyBucket> trends(@RequestParam(defaultValue = "24") int windowHours) {
        return sloMonitor.hourlyBuckets(windowHours);
    }

    @GetMapping("/slo")
    public SloReport slo(@RequestParam(defaultValue = "24") int windowHours) {
        return sloMonitor.report(windowHours);
    }

    @PostMapping("/optimize/apply")
    public OptimizationApplyResult applyTweaks(@RequestParam(required = false) String releasedBy) {
        return optimizationLoop.applyThresholdTweaks(releasedBy);
    }

    @PostMapping("/optimize/rollback")
    public Map<String, Object> rollback(@RequestParam(required = false) String evalId) {
        if (evalId != null && !evalId.isBlank()) {
            boolean rolledBack = optimizationLoop.checkAutoRollback(evalId);
            return Map.of("rolledBack", String.valueOf(rolledBack), "evalId", evalId);
        }
        optimizationLoop.rollbackToBaseline();
        return Map.of("rolledBack", "true", "mode", "baseline");
    }

    // ===== V4.0: A/B rule experiments + Critic self-learning stats =====

    @PostMapping("/experiment/start")
    public Map<String, String> startExperiment(@RequestParam String name,
                                                @RequestParam String metricId,
                                                @RequestParam String direction,
                                                @RequestParam(required = false) String denyAggregation,
                                                @RequestParam(defaultValue = "10") int trafficPct) {
        String id = ruleExperimentService.start(name, metricId, direction, denyAggregation, trafficPct);
        return Map.of("experimentId", id, "status", "running");
    }

    @GetMapping("/experiment/{id}/measure")
    public ExperimentMetrics measureExperiment(@PathVariable String id) {
        return ruleExperimentService.measure(id);
    }

    @PostMapping("/experiment/{id}/promote")
    public Map<String, String> promoteExperiment(@PathVariable String id) {
        ruleExperimentService.promote(id);
        return Map.of("experimentId", id, "status", "promoted");
    }

    @PostMapping("/experiment/{id}/rollback")
    public Map<String, String> rollbackExperiment(@PathVariable String id) {
        ruleExperimentService.rollback(id);
        return Map.of("experimentId", id, "status", "rolled_back");
    }

    @GetMapping("/experiment")
    public List<RuleExperiment> listExperiments(@RequestParam(defaultValue = "running") String status) {
        return ruleExperimentService.listByStatus(status);
    }

    @GetMapping("/critic-stats")
    public Map<String, Double> criticStats() {
        return criticChallengeStats.effectiveness();
    }
}
