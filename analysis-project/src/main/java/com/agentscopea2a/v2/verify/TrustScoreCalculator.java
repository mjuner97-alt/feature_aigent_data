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
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Aggregates the verify + critic scores into the final Trust Score (V3.0 design §5.2). Weighted sum
 * over the core dimensions (data / tool / semantic / adversarial) plus the advisory dimensions
 * (evidence / freshness, default weight 0). Verdict is derived from thresholds, with any dimension
 * hard-fail or Class B fatal forcing FAIL.
 */
@Component
public class TrustScoreCalculator {

    private final CalibrationState calibrationState;
    private final double wEvidence;
    private final double wFreshness;

    public TrustScoreCalculator(HarnessRunnerProperties properties, CalibrationState calibrationState) {
        this.calibrationState = calibrationState;
        this.wEvidence = properties.getVerification().getTrust().getWEvidence();
        this.wFreshness = properties.getVerification().getTrust().getWFreshness();
    }

    /** Merge the critic score and compute the final trustScore + verdict in-place on {@code v}. */
    public void apply(VerificationVerdict v, CriticResult critic) {
        Map<String, Integer> dims = v.getDimensions() == null
                ? new HashMap<>() : new HashMap<>(v.getDimensions());
        if (critic != null) {
            v.setAdversarialScore(critic.adversarialScore());
            dims.put("adversarial", critic.adversarialScore());
        }
        if (!dims.containsKey("adversarial")) {
            dims.put("adversarial", v.getAdversarialScore());
        }
        v.setDimensions(dims);

        int data = dim(dims, "data", 100);
        int tool = dim(dims, "tool", 100);
        int semantic = dim(dims, "semantic", 100);
        int adversarial = dim(dims, "adversarial", 100);
        int evidence = dim(dims, "evidence", 100);
        int freshness = dim(dims, "freshness", 100);

        // Core weights/thresholds come from the live CalibrationState (V4.0 online calibration);
        // evidence/freshness are advisory (config-only, default 0 weight) and not calibrated.
        double raw = calibrationState.getWData() * data
                + calibrationState.getWTool() * tool
                + calibrationState.getWSemantic() * semantic
                + calibrationState.getWAdversarial() * adversarial
                + wEvidence * evidence
                + wFreshness * freshness;
        int trustScore = Math.max(0, Math.min(100, (int) Math.round(raw)));
        v.setTrustScore(trustScore);

        String verdict;
        if (v.isClassBFatal() || anyDimensionFail(v)
                || (critic != null && critic.adversarialScore() < calibrationState.getWarnThreshold())) {
            verdict = VerificationVerdict.FAIL;
        } else if (trustScore >= calibrationState.getPassThreshold()) {
            verdict = VerificationVerdict.PASS;
        } else if (trustScore >= calibrationState.getWarnThreshold()) {
            verdict = VerificationVerdict.WARN;
        } else {
            verdict = VerificationVerdict.FAIL;
        }
        v.setVerdict(verdict);
    }

    private static int dim(Map<String, Integer> dims, String key, int def) {
        Integer val = dims == null ? null : dims.get(key);
        return val == null ? def : val;
    }

    private static boolean anyDimensionFail(VerificationVerdict v) {
        return isFail(v.getToolCalls()) || isFail(v.getData()) || isFail(v.getConclusion());
    }

    private static boolean isFail(VerificationVerdict.DimensionResult d) {
        return d != null && d.isFail();
    }
}
