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

/**
 * A/B experiment measurement (V4.0 §31.2). Compares the experiment bucket's verdict distribution
 * against the baseline (non-experiment) bucket since the experiment started. {@code recommendation}
 * is promote / rollback / inconclusive based on whether the candidate rule caught more real issues
 * (higher fail-rate) with enough samples.
 */
public record ExperimentMetrics(
        String experimentId,
        int sampleSize,
        double failRate,
        double baselineFailRate,
        double delta,
        double avgTrust,
        String recommendation) {
}
