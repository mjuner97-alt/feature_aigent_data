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

import java.util.List;
import java.util.Map;

/**
 * Aggregated dashboard snapshot returned by GET /v2/ai/verification/dashboard.
 * Bundles SLO report, calibration state, critic stats, and experiment list
 * into one response so the frontend needs only one call per refresh cycle.
 */
public class DashboardSnapshot {

    private final SloReport slo;
    private final Map<String, Object> calibration;
    private final List<RuleExperiment> experiments;
    private final Map<String, Double> criticStats;

    public DashboardSnapshot(SloReport slo, Map<String, Object> calibration,
                              List<RuleExperiment> experiments,
                              Map<String, Double> criticStats) {
        this.slo = slo;
        this.calibration = calibration;
        this.experiments = experiments;
        this.criticStats = criticStats;
    }

    public SloReport getSlo() { return slo; }
    public Map<String, Object> getCalibration() { return calibration; }
    public List<RuleExperiment> getExperiments() { return experiments; }
    public Map<String, Double> getCriticStats() { return criticStats; }
}
