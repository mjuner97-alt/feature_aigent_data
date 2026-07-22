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
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.PostReasoningEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Validates the corrective gotoReasoning closed-loop decision (V3.0 design §11.3/§16): on a FAIL
 * verdict the orchestrator must call {@code appendSystemContent} + {@code gotoReasoning()} on the
 * PostReasoningEvent (re-enter reasoning with the typed directive); on PASS it must not. Uses mocked
 * deps so the loop logic is exercised without a live LLM / framework runtime.
 */
class VerifyLoopOrchestratorCorrectiveTest {

    private DeterministicChecker deterministicChecker;
    private SemanticContractRegistry contractRegistry;
    private VerifyAgentInvoker verifyInvoker;
    private CriticAgentInvoker criticInvoker;
    private TrustScoreCalculator trustScoreCalculator;
    private TriggerLevelResolver triggerLevelResolver;
    private ResponsePolicyResolver responsePolicyResolver;
    private RepairPolicyEngine repairPolicyEngine;
    private VerificationRecorder recorder;
    private CriticChallengeStats criticStats;
    private RuleExperimentService ruleExpService;
    private VerifyLoopOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        deterministicChecker = mock(DeterministicChecker.class);
        contractRegistry = mock(SemanticContractRegistry.class);
        when(contractRegistry.isEnabled()).thenReturn(false);
        verifyInvoker = mock(VerifyAgentInvoker.class);
        criticInvoker = mock(CriticAgentInvoker.class);
        trustScoreCalculator = mock(TrustScoreCalculator.class);
        triggerLevelResolver = mock(TriggerLevelResolver.class);
        when(triggerLevelResolver.llmVerifyEnabled(any())).thenReturn(true);
        when(triggerLevelResolver.criticEnabled(any())).thenReturn(false);
        responsePolicyResolver = mock(ResponsePolicyResolver.class);
        repairPolicyEngine = mock(RepairPolicyEngine.class);
        when(repairPolicyEngine.plan(any(), any(), any())).thenReturn(
                new RepairPlan(RepairType.SEMANTIC_FIX, "fix the direction, do not rephrase", 1, "SEMANTIC_MISMATCH", "HIGH"));
        recorder = mock(VerificationRecorder.class);
        criticStats = mock(CriticChallengeStats.class);
        ruleExpService = mock(RuleExperimentService.class);
        when(ruleExpService.activeExperiment(any())).thenReturn(java.util.Optional.empty());

        HarnessRunnerProperties props = new HarnessRunnerProperties();
        props.getVerification().setMode("corrective");
        props.getVerification().setMaxVerifyLoops(2);

        orchestrator = new VerifyLoopOrchestrator(deterministicChecker, contractRegistry, verifyInvoker,
                criticInvoker, trustScoreCalculator, triggerLevelResolver, responsePolicyResolver,
                repairPolicyEngine, recorder, criticStats, ruleExpService, props);

        when(deterministicChecker.check(any())).thenReturn(new DeterministicChecker.Result());
    }

    private VerificationContext freshVctx(String candidate) {
        VerificationContext vctx = new VerificationContext("sess-1", "u1", "哪个部门质量最好");
        vctx.setTriggerLevel("MEDIUM");
        vctx.setCandidateConclusion(candidate, "supervisor-reasoning");
        return vctx;
    }

    private RuntimeContext mockCtx() {
        RuntimeContext ctx = mock(RuntimeContext.class);
        when(ctx.get(any(String.class))).thenReturn(null);
        return ctx;
    }

    @Test
    void failVerdict_triggersGotoReasoningWithDirective() {
        VerificationContext vctx = freshVctx("杭州开发一部质量最好，质量分最高");
        PostReasoningEvent event = mock(PostReasoningEvent.class);

        VerificationVerdict fail = new VerificationVerdict();
        fail.setVerdict("fail");
        fail.setTrustScore(45);
        when(verifyInvoker.verify(any(), any(), any())).thenReturn(Mono.just(fail));

        orchestrator.onSupervisorReasoning(vctx, event, mockCtx()).block();

        verify(event).gotoReasoning();
        verify(event).appendSystemContent(any(String.class));
        verify(repairPolicyEngine).plan(any(), any(), any());
    }

    @Test
    void passVerdict_doesNotReReason() {
        VerificationContext vctx = freshVctx("杭州开发三部质量最好，缺陷密度最低");
        PostReasoningEvent event = mock(PostReasoningEvent.class);

        VerificationVerdict pass = new VerificationVerdict();
        pass.setVerdict("pass");
        pass.setTrustScore(95);
        when(verifyInvoker.verify(any(), any(), any())).thenReturn(Mono.just(pass));

        orchestrator.onSupervisorReasoning(vctx, event, mockCtx()).block();

        verify(event, never()).gotoReasoning();
        verify(repairPolicyEngine, never()).plan(any(), any(), any());
    }

    @Test
    void maxLoopsReached_doesNotReReasonEvenOnFail() {
        // Simulate that 2 repairs already happened -> loop guard short-circuits before verify.
        VerificationContext vctx = freshVctx("candidate after 2 repairs");
        PostReasoningEvent event = mock(PostReasoningEvent.class);
        vctx.recordRepair(new RepairPlan(RepairType.SEMANTIC_FIX, "1st", 1, "SEMANTIC_MISMATCH", "HIGH"));
        vctx.recordRepair(new RepairPlan(RepairType.SEMANTIC_FIX, "2nd", 1, "SEMANTIC_MISMATCH", "HIGH"));

        orchestrator.onSupervisorReasoning(vctx, event, mockCtx()).block();

        verify(verifyInvoker, never()).verify(any(), any(), any());
        verify(event, never()).gotoReasoning();
    }
}
