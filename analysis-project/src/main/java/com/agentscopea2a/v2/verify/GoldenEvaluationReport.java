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

/**
 * Aggregated Golden evaluation report (V3.0 design §13.5/§25, P0). Computed from the
 * {@code golden_evaluation_result} rows of one eval id. {@code gatePassed} is the regression gate:
 * false when core accuracy dropped beyond the configured threshold vs the previous stable version.
 */
public record GoldenEvaluationReport(
        String evalId,
        String versionLabel,
        int total,
        int accuracyCount,
        double accuracyRate,
        double hallucinationRate,
        double avgTrustScore,
        double repairRate,
        boolean gatePassed,
        String gateReason,
        List<EvaluationResult> results) {
}
