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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Request-scoped verification context (V3.0 design §6.3). Mirrors the {@code ToolCallCollector}
 * pattern: created per request by {@code V2ChatStreamServiceImpl}, placed on the per-request
 * {@link io.agentscope.core.agent.RuntimeContext} under {@link #VERIFY_CTX_KEY}, retrieved by
 * {@code VerificationHook} via {@code HookRuntimeContext.resolve()}. <b>Never</b> ThreadLocal -
 * reactive streams cross thread boundaries (see {@code ToolCallTrackingHook} javadoc).
 *
 * <p>Holds: the full event stream (untruncated, for verification), Decision Trace, candidate
 * conclusion, artifact refs, contract snapshot, and the verdicts from each closed-loop iteration.
 */
public class VerificationContext {

    /** RuntimeContext key under which this context is stored (mirrors COLLECTOR_CTX_KEY). */
    public static final String VERIFY_CTX_KEY = "verificationContext";

    /** RuntimeContext key ArtifactHandoffHook appends produced ArtifactRefs under (additive, decoupled). */
    public static final String ARTIFACT_REFS_KEY = "verificationArtifactRefs";

    /** RuntimeContext key ArithMentalMathDetectorHook writes its detection snippet under (additive). */
    public static final String ARITH_MENTAL_MATH_KEY = "arithMentalMathDetected";

    /** Agent names that must NOT trigger verification (anti-recursion, V3.0 design §3.3). */
    private static final Set<String> VERIFIER_AGENT_NAMES = Set.of("verify", "critic");

    private final String sessionId;
    private final String userId;
    private final String userQuery;

    private volatile String triggerLevel = "MEDIUM";
    private final List<AgentExecutionEvent> eventStream = new CopyOnWriteArrayList<>();
    private final List<DecisionTraceEntry> decisionTrace = new CopyOnWriteArrayList<>();
    private volatile String candidateConclusion;
    private volatile String candidateSource;
    private final List<ArtifactRef> artifactRefs = new CopyOnWriteArrayList<>();
    private final List<VerificationVerdict> verdicts = new CopyOnWriteArrayList<>();
    private final List<RepairPlan> repairHistory = new CopyOnWriteArrayList<>();
    private volatile SemanticContracts.Snapshot contractSnapshot = SemanticContracts.Snapshot.empty();
    private final AtomicInteger verifyCallCount = new AtomicInteger(0);
    private final AtomicInteger eventIdSeq = new AtomicInteger(0);

    /** V3.0 corrective loop: fingerprint of the last verified candidate (no-progress detection). */
    private volatile String lastCandidateFingerprint = "";

    /** V4.0 A/B: the active rule-experiment id for this request (null when not in a bucket). */
    private volatile String activeExperimentId;

    public VerificationContext(String sessionId, String userId, String userQuery) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.userQuery = userQuery != null ? userQuery : "";
    }

    // -------- event stream --------

    public void emit(String type, String actor, String parentEventId, Map<String, Object> payload) {
        eventStream.add(new AgentExecutionEvent(
                "evt_" + String.format("%04d", eventIdSeq.incrementAndGet()),
                type, actor, parentEventId, sessionId,
                System.currentTimeMillis(), payload));
    }

    public void emit(String type, String actor, Map<String, Object> payload) {
        emit(type, actor, null, payload);
    }

    public List<AgentExecutionEvent> getEventStream() {
        return eventStream;
    }

    // -------- decision trace --------

    public void addDecisionTrace(DecisionTraceEntry entry) {
        if (entry != null) {
            decisionTrace.add(entry);
        }
    }

    public List<DecisionTraceEntry> getDecisionTrace() {
        return decisionTrace;
    }

    // -------- candidate conclusion --------

    public void setCandidateConclusion(String conclusion, String source) {
        this.candidateConclusion = conclusion;
        this.candidateSource = source;
    }

    public String getCandidateConclusion() { return candidateConclusion; }
    public String getCandidateSource() { return candidateSource; }

    // -------- artifacts --------

    public void addArtifactRef(ArtifactRef ref) {
        if (ref != null) {
            artifactRefs.add(ref);
        }
    }

    public List<ArtifactRef> getArtifactRefs() {
        return artifactRefs;
    }

    /** Agent paths of all produced artifacts - used by B1 terminal-path consistency check. */
    public List<String> getArtifactAgentPaths() {
        List<String> paths = new ArrayList<>();
        for (ArtifactRef r : artifactRefs) {
            if (r.agentPath() != null && !r.agentPath().isBlank()) {
                paths.add(r.agentPath());
            }
        }
        return paths;
    }

    // -------- verdicts / repair history --------

    public void recordVerdict(VerificationVerdict verdict) {
        if (verdict != null) {
            verdicts.add(verdict);
        }
    }

    public List<VerificationVerdict> getVerdicts() {
        return verdicts;
    }

    public VerificationVerdict lastVerdict() {
        return verdicts.isEmpty() ? null : verdicts.get(verdicts.size() - 1);
    }

    public void recordRepair(RepairPlan plan) {
        if (plan != null) {
            repairHistory.add(plan);
        }
    }

    public List<RepairPlan> getRepairHistory() {
        return repairHistory;
    }

    public int repairLoopCount() {
        return repairHistory.size();
    }

    // -------- misc --------

    public int incrementVerifyCalls() {
        return verifyCallCount.incrementAndGet();
    }

    public int getVerifyCallCount() {
        return verifyCallCount.get();
    }

    public String getLastCandidateFingerprint() { return lastCandidateFingerprint; }
    public void setLastCandidateFingerprint(String fp) { this.lastCandidateFingerprint = fp == null ? "" : fp; }

    public String getActiveExperimentId() { return activeExperimentId; }
    public void setActiveExperimentId(String activeExperimentId) { this.activeExperimentId = activeExperimentId; }

    public String getSessionId() { return sessionId; }
    public String getUserId() { return userId; }
    public String getUserQuery() { return userQuery; }

    public String getTriggerLevel() { return triggerLevel; }
    public void setTriggerLevel(String triggerLevel) {
        if (triggerLevel != null) this.triggerLevel = triggerLevel;
    }

    public SemanticContracts.Snapshot getContractSnapshot() { return contractSnapshot; }
    public void setContractSnapshot(SemanticContracts.Snapshot contractSnapshot) {
        if (contractSnapshot != null) this.contractSnapshot = contractSnapshot;
    }

    /** True if the given agent name is a verifier/critic - skip verification for them (anti-recursion). */
    public static boolean isVerifierAgent(String agentName) {
        return agentName != null && VERIFIER_AGENT_NAMES.contains(agentName);
    }
}
