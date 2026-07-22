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

import com.agentscopea2a.v2.artifact.ArtifactRef;
import com.agentscopea2a.v2.config.HarnessRunnerProperties;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Verification closed-loop orchestrator (V3.0 design §13/§16). Coordinates the Rule Engine, Semantic
 * Engine, Critic, Trust Score, Repair Policy, and Recorder at each checkpoint.
 *
 * <p><b>Phase 1 execution model</b> (advisory-default):
 * <ul>
 *   <li><b>supervisor-exit</b> (PostCall): full chain - Rule Engine precheck, then (MEDIUM/HIGH) the
 *       verify sub-agent, then (HIGH) the critic sub-agent, score, record, and annotate the final
 *       answer. On FAIL the Repair Policy decides a typed repair; Phase 1 annotates the directive
 *       onto the answer (true gotoReasoning re-reasoning is the next phase).</li>
 *   <li><b>subagent-exit</b> / <b>per-critical-tool</b>: deterministic Rule Engine only (cheap, no
 *       LLM) + record. Catches B1/B2/B4 on intermediate results without the LLM cost.</li>
 * </ul>
 */
@Component
public class VerifyLoopOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(VerifyLoopOrchestrator.class);

    private final DeterministicChecker deterministicChecker;
    private final SemanticContractRegistry contractRegistry;
    private final VerifyAgentInvoker verifyInvoker;
    private final CriticAgentInvoker criticInvoker;
    private final TrustScoreCalculator trustScoreCalculator;
    private final TriggerLevelResolver triggerLevelResolver;
    private final ResponsePolicyResolver responsePolicyResolver;
    private final RepairPolicyEngine repairPolicyEngine;
    private final VerificationRecorder recorder;
    private final CriticChallengeStats criticStats;
    private final RuleExperimentService ruleExpService;
    private final HarnessRunnerProperties.Verification config;

    public VerifyLoopOrchestrator(DeterministicChecker deterministicChecker,
                                  SemanticContractRegistry contractRegistry,
                                  VerifyAgentInvoker verifyInvoker,
                                  CriticAgentInvoker criticInvoker,
                                  TrustScoreCalculator trustScoreCalculator,
                                  TriggerLevelResolver triggerLevelResolver,
                                  ResponsePolicyResolver responsePolicyResolver,
                                  RepairPolicyEngine repairPolicyEngine,
                                  VerificationRecorder recorder,
                                  CriticChallengeStats criticStats,
                                  RuleExperimentService ruleExpService,
                                  HarnessRunnerProperties properties) {
        this.deterministicChecker = deterministicChecker;
        this.contractRegistry = contractRegistry;
        this.verifyInvoker = verifyInvoker;
        this.criticInvoker = criticInvoker;
        this.trustScoreCalculator = trustScoreCalculator;
        this.triggerLevelResolver = triggerLevelResolver;
        this.responsePolicyResolver = responsePolicyResolver;
        this.repairPolicyEngine = repairPolicyEngine;
        this.recorder = recorder;
        this.criticStats = criticStats;
        this.ruleExpService = ruleExpService;
        this.config = properties.getVerification();
    }

    // ==================== supervisor-exit (full chain) ====================

    public Mono<Void> onSupervisorExit(VerificationContext vctx, PostCallEvent event, RuntimeContext ctx) {
        loadCtxIntoVctx(vctx, ctx);
        // corrective mode: the verify+repair loop already ran at PostReasoning (gotoReasoning).
        // Just annotate the final answer with the last verdict - no re-verify, no duplicate record.
        if ("corrective".equalsIgnoreCase(config.getMode())) {
            VerificationVerdict last = vctx.lastVerdict();
            if (last != null) {
                annotate(event, last, null);
            }
            return Mono.empty();
        }
        DeterministicChecker.Result precheck = deterministicChecker.check(vctx);

        // Class B hard fail -> short-circuit the LLM.
        if (precheck.isClassBFatal()) {
            VerificationVerdict verdict = buildPrecheckVerdict(precheck, vctx, "supervisor-exit");
            vctx.recordVerdict(verdict);
            recorder.recordVerdict(vctx, verdict, "supervisor-exit", 0L);
            RepairPlan plan = repairPolicyEngine.plan(verdict, precheck, vctx);
            vctx.recordRepair(plan);
            recorder.recordRepairHistory(vctx, vctx.repairLoopCount(), plan, false, "classB-fail");
            annotate(event, verdict, plan);
            log.info("VerifyLoop: supervisor-exit ClassB fail [{}] session={}", precheck.getFailCode(), vctx.getSessionId());
            return Mono.empty();
        }

        // deterministic-only mode or LOW level -> no LLM.
        if ("deterministic-only".equalsIgnoreCase(config.getMode())
                || !triggerLevelResolver.llmVerifyEnabled(vctx.getTriggerLevel())) {
            VerificationVerdict verdict = buildPrecheckVerdict(precheck, vctx, "supervisor-exit");
            vctx.recordVerdict(verdict);
            recorder.recordVerdict(vctx, verdict, "supervisor-exit", 0L);
            annotate(event, verdict, null);
            return Mono.empty();
        }

        long start = System.currentTimeMillis();
        return verifyInvoker.verify(vctx, precheck, ctx)
                .flatMap(verdict -> {
                    verdict.setCheckpoint("supervisor-exit");
                    verdict.setCandidateSource(vctx.getCandidateSource());
                    verdict.setLoopIndex(vctx.repairLoopCount());
                    Mono<CriticResult> criticMono = triggerLevelResolver.criticEnabled(vctx.getTriggerLevel())
                            ? criticInvoker.critic(vctx, ctx)
                            : Mono.just(CriticResult.unverified("level not HIGH"));
                    return criticMono.flatMap(critic -> {
                        trustScoreCalculator.apply(verdict, critic);
                        criticStats.recordFindings(critic.holes(), verdict.isFail());
                        vctx.recordVerdict(verdict);
                        long latency = System.currentTimeMillis() - start;
                        recorder.recordVerdict(vctx, verdict, "supervisor-exit", latency);

                        RepairPlan plan = RepairPlan.none();
                        if (verdict.isFail() && !verdict.isUnverified()) {
                            plan = repairPolicyEngine.plan(verdict, precheck, vctx);
                            vctx.recordRepair(plan);
                            recorder.recordRepairHistory(vctx, vctx.repairLoopCount(), plan, false, verdict.getVerdict());
                        }
                        annotate(event, verdict, plan.needsAction() ? plan : null);
                        log.info("VerifyLoop: supervisor-exit verdict={} score={} session={}",
                                verdict.getVerdict(), verdict.getTrustScore(), vctx.getSessionId());
                        return Mono.<Void>empty();
                    });
                });
    }

    // ==================== supervisor-reasoning (corrective gotoReasoning loop) ====================

    /**
     * Corrective closed loop (V3.0 design §11.3/§16). Triggered on a PostReasoningEvent whose
     * reasoning carries no tool_use (i.e. the supervisor is about to emit its final answer). Runs
     * verify (+critic), and on FAIL injects the typed repair directive via
     * {@code appendSystemContent} + {@code gotoReasoning()} so the agent re-reasons and revises -
     * never silently rephrases (MODIFY_RESULT is forbidden by the Repair Policy). Loops until
     * PASS/WARN, max-verify-loops, or no-progress (same candidate fingerprint).
     */
    public Mono<Void> onSupervisorReasoning(VerificationContext vctx, PostReasoningEvent event, RuntimeContext ctx) {
        loadCtxIntoVctx(vctx, ctx);

        // Loop guard: max repairs already applied -> let this answer finalize (PostCall annotates).
        if (vctx.repairLoopCount() >= config.getMaxVerifyLoops()) {
            return Mono.empty();
        }
        // No-progress guard: same candidate as last verified -> stop looping, let finalize.
        String fp = fingerprint(vctx.getCandidateConclusion());
        if (fp.equals(vctx.getLastCandidateFingerprint())) {
            return Mono.empty();
        }
        vctx.setLastCandidateFingerprint(fp);

        DeterministicChecker.Result precheck = deterministicChecker.check(vctx);

        // Class B hard fail -> typed repair + re-reason. REFUSE/CLARIFY also go via gotoReasoning so
        // the agent emits the refusal/clarification as its final answer rather than a silent stop.
        if (precheck.isClassBFatal()) {
            VerificationVerdict verdict = buildPrecheckVerdict(precheck, vctx, "supervisor-reasoning");
            vctx.recordVerdict(verdict);
            recorder.recordVerdict(vctx, verdict, "supervisor-reasoning", 0L);
            RepairPlan plan = repairPolicyEngine.plan(verdict, precheck, vctx);
            vctx.recordRepair(plan);
            recorder.recordRepairHistory(vctx, vctx.repairLoopCount(), plan, false, "classB-fail");
            applyGotoReasoning(event, plan);
            log.info("VerifyLoop: corrective classB [{}] -> gotoReasoning action={} loop={}",
                    precheck.getFailCode(), plan.type(), vctx.repairLoopCount());
            return Mono.empty();
        }

        // LOW level: deterministic only, no LLM verify -> no corrective loop.
        if (!triggerLevelResolver.llmVerifyEnabled(vctx.getTriggerLevel())) {
            return Mono.empty();
        }

        long start = System.currentTimeMillis();
        return verifyInvoker.verify(vctx, precheck, ctx)
                .flatMap(verdict -> {
                    verdict.setCheckpoint("supervisor-reasoning");
                    verdict.setCandidateSource(vctx.getCandidateSource());
                    verdict.setLoopIndex(vctx.repairLoopCount());
                    Mono<CriticResult> criticMono = triggerLevelResolver.criticEnabled(vctx.getTriggerLevel())
                            ? criticInvoker.critic(vctx, ctx)
                            : Mono.just(CriticResult.unverified("level not HIGH"));
                    return criticMono.flatMap(critic -> {
                        trustScoreCalculator.apply(verdict, critic);
                        criticStats.recordFindings(critic.holes(), verdict.isFail());
                        vctx.recordVerdict(verdict);
                        recorder.recordVerdict(vctx, verdict, "supervisor-reasoning",
                                System.currentTimeMillis() - start);
                        if (!verdict.isFail() || verdict.isUnverified()) {
                            return Mono.<Void>empty(); // PASS/WARN/unverified -> let finalize
                        }
                        RepairPlan plan = repairPolicyEngine.plan(verdict, precheck, vctx);
                        vctx.recordRepair(plan);
                        recorder.recordRepairHistory(vctx, vctx.repairLoopCount(), plan, false, verdict.getVerdict());
                        applyGotoReasoning(event, plan);
                        log.info("VerifyLoop: corrective FAIL score={} -> gotoReasoning action={} loop={}",
                                verdict.getTrustScore(), plan.type(), vctx.repairLoopCount());
                        return Mono.<Void>empty();
                    });
                });
    }

    /**
     * Inject the typed repair directive and re-enter reasoning. The directive is appended to the
     * system message (persistent across the re-reasoned step) and {@code gotoReasoning()} re-enters
     * the reasoning loop so the agent revises its answer based on the directive.
     */
    private void applyGotoReasoning(PostReasoningEvent event, RepairPlan plan) {
        if (plan == null || !plan.needsAction()) {
            return;
        }
        try {
            event.appendSystemContent("【校验修复指令】" + plan.directive());
            event.gotoReasoning();
        } catch (Exception e) {
            log.warn("VerifyLoop: gotoReasoning injection failed (degrading to finalize): {}", e.getMessage());
        }
    }

    private static String fingerprint(String candidate) {
        if (candidate == null) return "";
        String trimmed = candidate.trim();
        return trimmed.length() > 200 ? trimmed.substring(0, 200) : trimmed;
    }

    // ==================== subagent-exit / per-critical-tool (deterministic only) ====================

    public Mono<Void> onSubagentExit(VerificationContext vctx, PostActingEvent event, RuntimeContext ctx) {
        return runDeterministicOnly(vctx, ctx, "subagent-exit");
    }

    public Mono<Void> onPerCriticalTool(VerificationContext vctx, PostActingEvent event, RuntimeContext ctx) {
        return runDeterministicOnly(vctx, ctx, "per-critical-tool");
    }

    private Mono<Void> runDeterministicOnly(VerificationContext vctx, RuntimeContext ctx, String checkpoint) {
        return Mono.fromRunnable(() -> {
            try {
                loadCtxIntoVctx(vctx, ctx);
                DeterministicChecker.Result precheck = deterministicChecker.check(vctx);
                VerificationVerdict verdict = buildPrecheckVerdict(precheck, vctx, checkpoint);
                vctx.recordVerdict(verdict);
                recorder.recordVerdict(vctx, verdict, checkpoint, 0L);
            } catch (Exception e) {
                log.warn("VerifyLoop: {} deterministic check failed: {}", checkpoint, e.getMessage());
            }
        });
    }

    // ==================== helpers ====================

    private void loadCtxIntoVctx(VerificationContext vctx, RuntimeContext ctx) {
        if (ctx == null) return;
        // artifact refs published by ArtifactHandoffHook (additive).
        Object refs = ctx.get(VerificationContext.ARTIFACT_REFS_KEY);
        if (refs instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof ArtifactRef r) {
                    vctx.addArtifactRef(r);
                }
            }
        }
        if (contractRegistry.isEnabled()) {
            SemanticContracts.Snapshot snap = contractRegistry.snapshotAll();
            // V4.0 A/B: inject the candidate rule for experiment-bucketed requests.
            Optional<RuleExperiment> exp = ruleExpService.activeExperiment(vctx.getSessionId());
            if (exp.isPresent()) {
                List<SemanticContracts.MetricContract> metrics = new ArrayList<>(snap.metrics());
                metrics.add(RuleExperimentService.candidateContract(exp.get()));
                snap = new SemanticContracts.Snapshot(metrics, snap.dimensions(), snap.rules());
                vctx.setActiveExperimentId(exp.get().experimentId());
            }
            vctx.setContractSnapshot(snap);
        }
    }

    private VerificationVerdict buildPrecheckVerdict(DeterministicChecker.Result precheck,
                                                     VerificationContext vctx, String checkpoint) {
        VerificationVerdict v = new VerificationVerdict();
        v.setCheckpoint(checkpoint);
        v.setCandidateSource(vctx.getCandidateSource());
        v.setLoopIndex(vctx.repairLoopCount());
        if (precheck.isClassBFatal()) {
            v.setClassBFatal(true);
            v.setVerdict(VerificationVerdict.FAIL);
            v.setTrustScore(0);
            v.setSummary(precheck.getFailReason());
            v.setRepairHint(classBRepairHint(precheck.getFailCode()));
        } else {
            v.setVerdict(VerificationVerdict.PASS);
            v.setTrustScore(90);
            v.setSummary(precheck.toReport());
            v.setRepairHint("NONE");
        }
        return v;
    }

    private String classBRepairHint(String failCode) {
        if ("B2".equals(failCode)) return "REFUSE";
        if ("B4".equals(failCode)) return "SEMANTIC_FIX";
        if ("B1".equals(failCode)) return "DATA_REQUERY";
        return "REFUSE";
    }

    private void annotate(PostCallEvent event, VerificationVerdict verdict, RepairPlan plan) {
        if (!config.isAnnotateFinalAnswer() || event == null) return;
        try {
            Msg original = event.getFinalMessage();
            if (original == null) return;
            String notice = buildNotice(verdict, plan);
            if (notice == null || notice.isBlank()) return;
            String text = original.getTextContent() == null ? "" : original.getTextContent();
            Msg annotated = Msg.builder()
                    .role(original.getRole() == null ? MsgRole.ASSISTANT : original.getRole())
                    .content(TextBlock.builder().text(text + "\n\n" + notice).build())
                    .build();
            event.setFinalMessage(annotated);
        } catch (Exception e) {
            log.debug("VerifyLoop: annotate failed: {}", e.getMessage());
        }
    }

    private String buildNotice(VerificationVerdict verdict, RepairPlan plan) {
        int score = verdict.getTrustScore();
        String mode = responsePolicyResolver.resolveResponseMode(score);
        if (verdict.isUnverified()) {
            return ""; // silent on unavailable verification
        }
        StringBuilder sb = new StringBuilder("\n---\n🔍 验证提示\n");
        if (VerificationVerdict.PASS.equalsIgnoreCase(verdict.getVerdict())) {
            sb.append("信任评分: ").append(score).append("/100 (PASS)");
        } else if (VerificationVerdict.WARN.equalsIgnoreCase(verdict.getVerdict())) {
            sb.append("> ").append(safeSummary(verdict)).append("\n");
            sb.append("信任评分: ").append(score).append("/100 (WARN)");
        } else {
            sb.append("> 验证未通过: ").append(safeSummary(verdict)).append("\n");
            if (plan != null && plan.needsAction()) {
                sb.append("> 修复建议(").append(plan.type()).append("): ").append(plan.directive()).append("\n");
            }
            sb.append("信任评分: ").append(score).append("/100 (FAIL)");
        }
        sb.append("\n---");
        return sb.toString();
    }

    private static String safeSummary(VerificationVerdict v) {
        return v.getSummary() == null ? "" : (v.getSummary().length() > 200 ? v.getSummary().substring(0, 200) + "..." : v.getSummary());
    }
}
