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
package com.agentscopea2a.v2.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Replaces the 11 {@code @Value} injections in {@code HarnessA2aRunnerV2} constructor
 * with a single typed bean.
 *
 * <p>Bind target:
 * <ul>
 *   <li>{@code harness.a2a.workspace.path}</li>
 *   <li>{@code harness.a2a.model.instances.{glm-main,light-classifier,fallback,verify,critic}.{api-key,base-url,name}}</li>
 *   <li>{@code harness.a2a.verification.*} (V3.0 Verification Agent)</li>
 * </ul>
 *
 * <p>Spring's relaxed binding maps kebab-case keys to camelCase fields, so
 * {@code glm-main} -> {@code glmMain}, {@code api-key} -> {@code apiKey}, etc.
 */
@ConfigurationProperties(prefix = "harness.a2a")
public class HarnessRunnerProperties {

    @NestedConfigurationProperty
    private Workspace workspace = new Workspace();

    @NestedConfigurationProperty
    private Model model = new Model();

    @NestedConfigurationProperty
    private Verification verification = new Verification();

    public Workspace getWorkspace() {
        return workspace;
    }

    public void setWorkspace(Workspace workspace) {
        this.workspace = workspace;
    }

    public Model getModel() {
        return model;
    }

    public void setModel(Model model) {
        this.model = model;
    }

    public Verification getVerification() {
        return verification;
    }

    public void setVerification(Verification verification) {
        this.verification = verification;
    }

    public static class Workspace {
        private String path = ".agentscope/workspace/harness-a2a";

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }
    }

    public static class Model {
        @NestedConfigurationProperty
        private Instances instances = new Instances();

        public Instances getInstances() {
            return instances;
        }

        public void setInstances(Instances instances) {
            this.instances = instances;
        }
    }

    public static class Instances {
        @NestedConfigurationProperty
        private ModelInstance glmMain = new ModelInstance();

        @NestedConfigurationProperty
        private ModelInstance lightClassifier = new ModelInstance();

        @NestedConfigurationProperty
        private ModelInstance fallback = new ModelInstance();

        /** V3.0: independent model for the verify sub-agent (isolation from business-model downgrade). */
        @NestedConfigurationProperty
        private ModelInstance verify = new ModelInstance();

        /** V3.0: independent model for the critic sub-agent. */
        @NestedConfigurationProperty
        private ModelInstance critic = new ModelInstance();

        public ModelInstance getGlmMain() {
            return glmMain;
        }

        public void setGlmMain(ModelInstance glmMain) {
            this.glmMain = glmMain;
        }

        public ModelInstance getLightClassifier() {
            return lightClassifier;
        }

        public void setLightClassifier(ModelInstance lightClassifier) {
            this.lightClassifier = lightClassifier;
        }

        public ModelInstance getFallback() {
            return fallback;
        }

        public void setFallback(ModelInstance fallback) {
            this.fallback = fallback;
        }

        public ModelInstance getVerify() {
            return verify;
        }

        public void setVerify(ModelInstance verify) {
            this.verify = verify;
        }

        public ModelInstance getCritic() {
            return critic;
        }

        public void setCritic(ModelInstance critic) {
            this.critic = critic;
        }
    }

    public static class ModelInstance {
        private String apiKey = "";
        private String baseUrl = "";
        private String name = "";

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        /** True when an api-key is configured (i.e. this instance is actually wired). */
        public boolean isConfigured() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    // ===== Verification Agent configuration (V3.0) =====
    // Bind target: harness.a2a.verification.*

    public static class Verification {
        private boolean enabled = true;
        /** advisory | corrective | deterministic-only. V3.0 ships advisory-default for safety. */
        private String mode = "advisory";
        private String checkpoints = "subagent-exit,supervisor-exit,per-critical-tool";
        private String subagentExitScope = "analyze_data,query_data";
        private String criticalTools = "data_query,data_aggregate,router_tool";
        private boolean criticalToolsEnabled = true;
        private int maxVerifyLoops = 2;
        private int maxVerifyCallsPerRequest = 6;
        /** Instance key under harness.a2a.model.instances.* for verify/critic (empty -> default model). */
        private String verifyModelKey = "";
        private String criticModelKey = "";
        private long verifyTimeoutSeconds = 180;
        private int circuitBreakerFailures = 3;
        /** Append a one-line trust-score notice to the supervisor's final answer. */
        private boolean annotateFinalAnswer = true;
        /** V4.0: @Scheduled rollback-watcher poll interval (ms). */
        private long rollbackWatchIntervalMs = 120000L;
        /** V4.0: max ms to wait for a Golden eval before force-rolling back an auto-apply. */
        private long rollbackWatchTimeoutMs = 1800000L;

        @NestedConfigurationProperty
        private Trust trust = new Trust();
        @NestedConfigurationProperty
        private Critic critic = new Critic();
        @NestedConfigurationProperty
        private Repair repair = new Repair();
        @NestedConfigurationProperty
        private Contract contract = new Contract();
        @NestedConfigurationProperty
        private Firewall firewall = new Firewall();
        @NestedConfigurationProperty
        private Deterministic deterministic = new Deterministic();
        @NestedConfigurationProperty
        private Trigger trigger = new Trigger();
        @NestedConfigurationProperty
        private Slo slo = new Slo();
        @NestedConfigurationProperty
        private Golden golden = new Golden();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
        public String getCheckpoints() { return checkpoints; }
        public void setCheckpoints(String checkpoints) { this.checkpoints = checkpoints; }
        public String getSubagentExitScope() { return subagentExitScope; }
        public void setSubagentExitScope(String subagentExitScope) { this.subagentExitScope = subagentExitScope; }
        public String getCriticalTools() { return criticalTools; }
        public void setCriticalTools(String criticalTools) { this.criticalTools = criticalTools; }
        public boolean isCriticalToolsEnabled() { return criticalToolsEnabled; }
        public void setCriticalToolsEnabled(boolean criticalToolsEnabled) { this.criticalToolsEnabled = criticalToolsEnabled; }
        public int getMaxVerifyLoops() { return maxVerifyLoops; }
        public void setMaxVerifyLoops(int maxVerifyLoops) { this.maxVerifyLoops = maxVerifyLoops; }
        public int getMaxVerifyCallsPerRequest() { return maxVerifyCallsPerRequest; }
        public void setMaxVerifyCallsPerRequest(int maxVerifyCallsPerRequest) { this.maxVerifyCallsPerRequest = maxVerifyCallsPerRequest; }
        public String getVerifyModelKey() { return verifyModelKey; }
        public void setVerifyModelKey(String verifyModelKey) { this.verifyModelKey = verifyModelKey; }
        public String getCriticModelKey() { return criticModelKey; }
        public void setCriticModelKey(String criticModelKey) { this.criticModelKey = criticModelKey; }
        public long getVerifyTimeoutSeconds() { return verifyTimeoutSeconds; }
        public void setVerifyTimeoutSeconds(long verifyTimeoutSeconds) { this.verifyTimeoutSeconds = verifyTimeoutSeconds; }
        public int getCircuitBreakerFailures() { return circuitBreakerFailures; }
        public void setCircuitBreakerFailures(int circuitBreakerFailures) { this.circuitBreakerFailures = circuitBreakerFailures; }
        public boolean isAnnotateFinalAnswer() { return annotateFinalAnswer; }
        public void setAnnotateFinalAnswer(boolean annotateFinalAnswer) { this.annotateFinalAnswer = annotateFinalAnswer; }
        public long getRollbackWatchIntervalMs() { return rollbackWatchIntervalMs; }
        public void setRollbackWatchIntervalMs(long v) { this.rollbackWatchIntervalMs = v; }
        public long getRollbackWatchTimeoutMs() { return rollbackWatchTimeoutMs; }
        public void setRollbackWatchTimeoutMs(long v) { this.rollbackWatchTimeoutMs = v; }
        public Trust getTrust() { return trust; }
        public void setTrust(Trust trust) { this.trust = trust; }
        public Critic getCritic() { return critic; }
        public void setCritic(Critic critic) { this.critic = critic; }
        public Repair getRepair() { return repair; }
        public void setRepair(Repair repair) { this.repair = repair; }
        public Contract getContract() { return contract; }
        public void setContract(Contract contract) { this.contract = contract; }
        public Firewall getFirewall() { return firewall; }
        public void setFirewall(Firewall firewall) { this.firewall = firewall; }
        public Deterministic getDeterministic() { return deterministic; }
        public void setDeterministic(Deterministic deterministic) { this.deterministic = deterministic; }
        public Trigger getTrigger() { return trigger; }
        public void setTrigger(Trigger trigger) { this.trigger = trigger; }
        public Slo getSlo() { return slo; }
        public void setSlo(Slo slo) { this.slo = slo; }
        public Golden getGolden() { return golden; }
        public void setGolden(Golden golden) { this.golden = golden; }

        public boolean checkpointEnabled(String name) {
            return checkpoints != null && checkpoints.contains(name);
        }

        public static class Trust {
            private int passThreshold = 85;
            private int warnThreshold = 60;
            private int directThreshold = 90;
            private int hintThreshold = 70;
            private double wData = 0.30;
            private double wTool = 0.20;
            private double wSemantic = 0.30;
            private double wAdversarial = 0.20;
            private double wEvidence = 0.0;
            private double wFreshness = 0.0;
            public int getPassThreshold() { return passThreshold; }
            public void setPassThreshold(int v) { this.passThreshold = v; }
            public int getWarnThreshold() { return warnThreshold; }
            public void setWarnThreshold(int v) { this.warnThreshold = v; }
            public int getDirectThreshold() { return directThreshold; }
            public void setDirectThreshold(int v) { this.directThreshold = v; }
            public int getHintThreshold() { return hintThreshold; }
            public void setHintThreshold(int v) { this.hintThreshold = v; }
            public double getWData() { return wData; }
            public void setWData(double v) { this.wData = v; }
            public double getWTool() { return wTool; }
            public void setWTool(double v) { this.wTool = v; }
            public double getWSemantic() { return wSemantic; }
            public void setWSemantic(double v) { this.wSemantic = v; }
            public double getWAdversarial() { return wAdversarial; }
            public void setWAdversarial(double v) { this.wAdversarial = v; }
            public double getWEvidence() { return wEvidence; }
            public void setWEvidence(double v) { this.wEvidence = v; }
            public double getWFreshness() { return wFreshness; }
            public void setWFreshness(double v) { this.wFreshness = v; }
        }

        public static class Critic {
            private boolean enabled = true;
            private String checkpoints = "supervisor-exit";
            private boolean counterfactual = true;
            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean v) { this.enabled = v; }
            public String getCheckpoints() { return checkpoints; }
            public void setCheckpoints(String v) { this.checkpoints = v; }
            public boolean isCounterfactual() { return counterfactual; }
            public void setCounterfactual(boolean v) { this.counterfactual = v; }
        }

        public static class Repair {
            private String policyPath = "workspace/policies/repair_policy.yaml";
            private boolean allowLlmFallback = true;
            private boolean forbidModifyResult = true;
            public String getPolicyPath() { return policyPath; }
            public void setPolicyPath(String v) { this.policyPath = v; }
            public boolean isAllowLlmFallback() { return allowLlmFallback; }
            public void setAllowLlmFallback(boolean v) { this.allowLlmFallback = v; }
            public boolean isForbidModifyResult() { return forbidModifyResult; }
            public void setForbidModifyResult(boolean v) { this.forbidModifyResult = v; }
        }

        public static class Contract {
            private boolean enabled = true;
            private long cacheTtlSeconds = 300;
            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean v) { this.enabled = v; }
            public long getCacheTtlSeconds() { return cacheTtlSeconds; }
            public void setCacheTtlSeconds(long v) { this.cacheTtlSeconds = v; }
        }

        public static class Firewall {
            private boolean enabled = true;
            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean v) { this.enabled = v; }
        }

        public static class Deterministic {
            private boolean artifactPath = true;
            private boolean fabricationFromNothing = true;
            private boolean contractViolation = true;
            private boolean toolsExistence = true;
            private boolean paramSchema = true;
            private boolean emptyResult = true;
            private boolean entityName = true;
            private boolean timeFormat = true;
            private boolean arithMentalMath = true;
            public boolean isArtifactPath() { return artifactPath; }
            public void setArtifactPath(boolean v) { this.artifactPath = v; }
            public boolean isFabricationFromNothing() { return fabricationFromNothing; }
            public void setFabricationFromNothing(boolean v) { this.fabricationFromNothing = v; }
            public boolean isContractViolation() { return contractViolation; }
            public void setContractViolation(boolean v) { this.contractViolation = v; }
            public boolean isToolsExistence() { return toolsExistence; }
            public void setToolsExistence(boolean v) { this.toolsExistence = v; }
            public boolean isParamSchema() { return paramSchema; }
            public void setParamSchema(boolean v) { this.paramSchema = v; }
            public boolean isEmptyResult() { return emptyResult; }
            public void setEmptyResult(boolean v) { this.emptyResult = v; }
            public boolean isEntityName() { return entityName; }
            public void setEntityName(boolean v) { this.entityName = v; }
            public boolean isTimeFormat() { return timeFormat; }
            public void setTimeFormat(boolean v) { this.timeFormat = v; }
            public boolean isArithMentalMath() { return arithMentalMath; }
            public void setArithMentalMath(boolean v) { this.arithMentalMath = v; }
        }

        public static class Trigger {
            private String highKeywords = "决策,经营,对比,趋势,预测,投资,风险";
            private int multiTableThreshold = 2;
            public String getHighKeywords() { return highKeywords; }
            public void setHighKeywords(String v) { this.highKeywords = v; }
            public int getMultiTableThreshold() { return multiTableThreshold; }
            public void setMultiTableThreshold(int v) { this.multiTableThreshold = v; }
        }

        /** V4.0 SLO targets for cross-request quality regression monitoring. */
        public static class Slo {
            private double passRateTarget = 0.90;
            private double fabricationRateTarget = 0.05;
            private long p95LatencyMsTarget = 30000L;
            public double getPassRateTarget() { return passRateTarget; }
            public void setPassRateTarget(double v) { this.passRateTarget = v; }
            public double getFabricationRateTarget() { return fabricationRateTarget; }
            public void setFabricationRateTarget(double v) { this.fabricationRateTarget = v; }
            public long getP95LatencyMsTarget() { return p95LatencyMsTarget; }
            public void setP95LatencyMsTarget(long v) { this.p95LatencyMsTarget = v; }
        }

        /** V3.0/V4.0 Golden evaluation schedule (daily regression). Disabled by default. */
        public static class Golden {
            private boolean enabled = false;
            private String cron = "0 0 7 * * *";
            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean v) { this.enabled = v; }
            public String getCron() { return cron; }
            public void setCron(String v) { this.cron = v; }
        }
    }
}
