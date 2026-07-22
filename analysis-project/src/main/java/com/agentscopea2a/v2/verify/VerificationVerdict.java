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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * Verification verdict produced by the Semantic Engine (verify.md) and aggregated with the Critic
 * score by {@link TrustScoreCalculator}. Jackson deserializes the strict JSON emitted by verify.md
 * (V3.0 design §8.3 / appendix C) directly into this POJO.
 *
 * <p>Field names match the JSON keys: {@code trustScore, verdict, dimensions, metricsUsed,
 * toolCalls, data, conclusion, summary, corrections, repairHint}. Orchestrator-only metadata
 * (checkpoint / candidateSource / loopIndex) is set after parsing and is not part of the LLM JSON.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class VerificationVerdict {

    public static final String PASS = "pass";
    public static final String WARN = "warn";
    public static final String FAIL = "fail";
    public static final String UNVERIFIED = "unverified";

    private int trustScore;
    private String verdict;
    private Map<String, Integer> dimensions;
    private List<MetricUsage> metricsUsed;
    private DimensionResult toolCalls;
    private DimensionResult data;
    private DimensionResult conclusion;
    private int adversarialScore = 100;
    private String summary;
    private List<String> corrections;
    private String repairHint;

    // ---- orchestrator-only metadata (not in LLM JSON) ----
    private String checkpoint;
    private String candidateSource;
    private int loopIndex;
    private boolean classBFatal;
    private boolean unverified;

    public int getTrustScore() { return trustScore; }
    public void setTrustScore(int trustScore) { this.trustScore = trustScore; }

    public String getVerdict() { return verdict; }
    public void setVerdict(String verdict) { this.verdict = verdict; }

    public Map<String, Integer> getDimensions() { return dimensions; }
    public void setDimensions(Map<String, Integer> dimensions) { this.dimensions = dimensions; }

    public List<MetricUsage> getMetricsUsed() { return metricsUsed; }
    public void setMetricsUsed(List<MetricUsage> metricsUsed) { this.metricsUsed = metricsUsed; }

    public DimensionResult getToolCalls() { return toolCalls; }
    public void setToolCalls(DimensionResult toolCalls) { this.toolCalls = toolCalls; }

    public DimensionResult getData() { return data; }
    public void setData(DimensionResult data) { this.data = data; }

    public DimensionResult getConclusion() { return conclusion; }
    public void setConclusion(DimensionResult conclusion) { this.conclusion = conclusion; }

    public int getAdversarialScore() { return adversarialScore; }
    public void setAdversarialScore(int adversarialScore) { this.adversarialScore = adversarialScore; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public List<String> getCorrections() { return corrections; }
    public void setCorrections(List<String> corrections) { this.corrections = corrections; }

    public String getRepairHint() { return repairHint; }
    public void setRepairHint(String repairHint) { this.repairHint = repairHint; }

    public String getCheckpoint() { return checkpoint; }
    public void setCheckpoint(String checkpoint) { this.checkpoint = checkpoint; }

    public String getCandidateSource() { return candidateSource; }
    public void setCandidateSource(String candidateSource) { this.candidateSource = candidateSource; }

    public int getLoopIndex() { return loopIndex; }
    public void setLoopIndex(int loopIndex) { this.loopIndex = loopIndex; }

    public boolean isClassBFatal() { return classBFatal; }
    public void setClassBFatal(boolean classBFatal) { this.classBFatal = classBFatal; }

    public boolean isUnverified() { return unverified; }
    public void setUnverified(boolean unverified) { this.unverified = unverified; }

    // ---- convenience ----

    public RepairType getRepairType() {
        return RepairType.fromHint(repairHint);
    }

    public boolean isPass() { return PASS.equalsIgnoreCase(verdict); }
    public boolean isWarn() { return WARN.equalsIgnoreCase(verdict); }
    public boolean isFail() { return FAIL.equalsIgnoreCase(verdict); }

    public Integer dimension(String key) {
        return dimensions != null && dimensions.containsKey(key) ? dimensions.get(key) : null;
    }

    /**
     * Build an UNVERIFIED verdict (verify/critic timed out, circuit-breaker tripped, or
     * exception). Never blocks the main chain - the orchestrator degrades to advisory.
     */
    public static VerificationVerdict unverified(String reason, String checkpoint, String source) {
        VerificationVerdict v = new VerificationVerdict();
        v.setTrustScore(0);
        v.setVerdict(UNVERIFIED);
        v.setSummary("verification unavailable: " + reason);
        v.setCheckpoint(checkpoint);
        v.setCandidateSource(source);
        v.setUnverified(true);
        return v;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DimensionResult {
        private String status;
        private int score;
        private List<Issue> issues;

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public int getScore() { return score; }
        public void setScore(int score) { this.score = score; }
        public List<Issue> getIssues() { return issues; }
        public void setIssues(List<Issue> issues) { this.issues = issues; }

        public boolean isFail() { return FAIL.equalsIgnoreCase(status); }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Issue {
        private String severity;
        private String tool;
        private String description;
        private String evidence;

        public String getSeverity() { return severity; }
        public void setSeverity(String severity) { this.severity = severity; }
        public String getTool() { return tool; }
        public void setTool(String tool) { this.tool = tool; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getEvidence() { return evidence; }
        public void setEvidence(String evidence) { this.evidence = evidence; }

        public boolean isFail() { return FAIL.equalsIgnoreCase(severity); }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MetricUsage {
        private String metric;
        private boolean directionConsistent;

        public String getMetric() { return metric; }
        public void setMetric(String metric) { this.metric = metric; }
        public boolean isDirectionConsistent() { return directionConsistent; }
        public void setDirectionConsistent(boolean directionConsistent) { this.directionConsistent = directionConsistent; }
    }
}
